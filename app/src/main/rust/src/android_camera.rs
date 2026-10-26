// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

use log::{error, info, warn};
use ndk_sys::*;
use olive_engine::{CameraConfig, CameraTrait, CameraType, Image};
use std::ffi::CStr;
use std::os::raw::c_void;
use std::ptr;
use std::sync::{
    Arc, Condvar, Mutex,
    atomic::{AtomicI32, AtomicU32, Ordering},
    mpsc,
};
use std::thread::{self, JoinHandle};
use std::time::{Duration, SystemTime};

const INITIAL_EXPOSURE_US: i32 = 250_000;
const MIN_EXPOSURE_US: i32 = 16_667;
const MAX_EXPOSURE_US: i32 = 5_000_000;

pub static CURRENT_HOT_PIXEL_MODE: std::sync::atomic::AtomicU32 =
    std::sync::atomic::AtomicU32::new(0);
pub static SUPPORTED_HOT_PIXEL_MODES: Mutex<Vec<u8>> = Mutex::new(Vec::new());

// --- Data Structures ---

struct CaptureStore {
    image: Mutex<Option<Arc<Image>>>,
    cvar: Condvar,
}

enum CaptureCommand {
    SetExposure(i32),
    SetGain(i32),
    SetFocus(f32),
    SetNoiseReduction(u8),
    SetZoom(f32),
    StopCaptureThread,
}

#[derive(Clone)]
pub struct AndroidCameraConfiguration {
    pub id: String,
    pub logical_id: String,
    pub width: u32,
    pub height: u32,
    pub active_array_size: [i32; 4],
    pub supports_zoom_ratio: bool,
    pub max_analog_gain: i32,
    pub true_max_analog_gain: i32,
    pub hyperfocal_distance: f32,
    pub fov: f32,
    pub white_level: i32,
    pub black_level: f32,
    pub format: i32,
    pub supports_contrast_curve: bool,
    pub zoom_ratio: Option<f32>,
}

/// Context passed to the AImageReader C callback
struct ImageReaderContext {
    store: Arc<CaptureStore>,
    width: u32,
    height: u32,
    exposure_time_us: Arc<AtomicI32>,
    white_level: f32,
    black_level: f32,
    format: i32,
    frame_exposures: Arc<Mutex<std::collections::VecDeque<(i64, i32, f32, i64)>>>,
    prev_sensor_ts: Arc<std::sync::atomic::AtomicI64>,
}

pub struct AndroidCamera {
    command_tx: Option<mpsc::Sender<CaptureCommand>>,
    store: Arc<CaptureStore>,
    capture_thread: Option<JoinHandle<()>>,

    current_exposure: Arc<AtomicI32>,
    saved_exposure: Arc<AtomicI32>,
    current_gain: Arc<AtomicI32>,
    current_focus: Arc<AtomicU32>, // Store f32 as u32 bits
    current_zoom: Arc<AtomicU32>,  // Store f32 as u32 bits
    current_nr: Arc<AtomicU32>,    // Store u8 as u32

    config: Arc<Mutex<Option<AndroidCameraConfiguration>>>,
}

impl CameraTrait for AndroidCamera {
    fn get_exposure_range(&self) -> Option<(i32, i32)> {
        Some((16_667, 2_000_000))
    }

    fn get_config(&self) -> Option<CameraConfig> {
        self.config.lock().unwrap().as_ref().map(|c| CameraConfig {
            camera_type: CameraType::Internal { id: c.id.clone() },
            model: format!("Android_Cam_{}", c.id),
            width: c.width,
            height: c.height,
            fps: 0,
            crop: None,
            initial_fov: Some(c.fov),
        })
    }

    fn start_capture(&mut self) -> Result<(), String> {
        if self.capture_thread.is_some() {
            return Ok(());
        }

        let (tx, rx) = mpsc::channel();
        self.command_tx = Some(tx);

        let store_clone = Arc::clone(&self.store);
        let config_clone = Arc::clone(&self.config);
        let exposure_clone = Arc::clone(&self.current_exposure);
        let gain_clone = Arc::clone(&self.current_gain);
        let focus_clone = Arc::clone(&self.current_focus);
        let nr_clone = Arc::clone(&self.current_nr);

        self.capture_thread = Some(thread::spawn(move || unsafe {
            Self::capture_loop(
                rx,
                store_clone,
                config_clone,
                exposure_clone,
                gain_clone,
                focus_clone,
                nr_clone,
            );
        }));

        Ok(())
    }

    fn stop_capture(&self) {
        self.send_command(CaptureCommand::StopCaptureThread);
        self.store.cvar.notify_all();
    }

    fn get_gain(&self) -> f32 {
        self.current_gain.load(Ordering::Relaxed) as f32
    }

    fn get_gain_range(&self) -> (f32, f32, f32) {
        if let Some(config) = self.config.lock().unwrap().as_ref() {
            let default_gain =
                if config.format == ndk_sys::AIMAGE_FORMATS::AIMAGE_FORMAT_YUV_420_888.0 as i32 {
                    config.true_max_analog_gain as f32
                } else {
                    std::cmp::min(config.max_analog_gain, 3200) as f32
                };
            (100.0, config.max_analog_gain as f32, default_gain)
        } else {
            (100.0, 3200.0, 3200.0)
        }
    }

    fn set_gain(&self, gain: f32) {
        let clamped = gain as i32;
        self.current_gain.store(clamped, Ordering::Relaxed);
        self.send_command(CaptureCommand::SetGain(clamped));
    }

    fn get_exposure(&self) -> i32 {
        self.current_exposure.load(Ordering::Relaxed)
    }

    fn set_exposure(&self, exposure_us: i32) {
        let clamped = exposure_us.clamp(MIN_EXPOSURE_US, MAX_EXPOSURE_US);
        self.current_exposure.store(clamped, Ordering::Relaxed);
        self.send_command(CaptureCommand::SetExposure(clamped));
    }

    fn set_focus(&self, distance: f32) {
        self.current_focus
            .store(distance.to_bits(), Ordering::Relaxed);
        self.send_command(CaptureCommand::SetFocus(distance));
    }

    fn get_focus(&self) -> Option<f32> {
        Some(f32::from_bits(self.current_focus.load(Ordering::Relaxed)))
    }

    fn set_noise_reduction(&self, mode: u8) {
        self.current_nr.store(mode as u32, Ordering::Relaxed);
        self.send_command(CaptureCommand::SetNoiseReduction(mode));
    }

    fn get_noise_reduction(&self) -> u8 {
        self.current_nr.load(Ordering::Relaxed) as u8
    }

    fn set_hot_pixel_mode(&self, mode: u8) {
        CURRENT_HOT_PIXEL_MODE.store(mode as u32, Ordering::Relaxed);
    }

    fn get_hot_pixel_mode(&self) -> u8 {
        CURRENT_HOT_PIXEL_MODE.load(Ordering::Relaxed) as u8
    }

    fn supports_hot_pixel_mode(&self) -> bool {
        let modes = SUPPORTED_HOT_PIXEL_MODES.lock().unwrap();
        modes.iter().any(|&m| m > 0)
    }

    fn supports_noise_reduction(&self) -> bool {
        true
    }

    fn set_zoom(&self, zoom: f32) {
        self.current_zoom.store(zoom.to_bits(), Ordering::Relaxed);
        self.send_command(CaptureCommand::SetZoom(zoom));
    }

    fn get_zoom(&self) -> Option<f32> {
        Some(f32::from_bits(self.current_zoom.load(Ordering::Relaxed)))
    }

    fn get_last_capture(&self) -> Option<Arc<Image>> {
        let lock = self.store.image.lock().unwrap();
        lock.clone()
    }

    fn wait_for_next_capture(
        &self,
        last_timestamp: Option<SystemTime>,
        timeout: Option<Duration>,
    ) -> Option<Arc<Image>> {
        let mut lock = self.store.image.lock().unwrap();
        loop {
            if let Some(img) = &*lock {
                if last_timestamp.is_none() || Some(img.timestamp) != last_timestamp {
                    return Some(img.clone());
                }
            }
            if let Some(duration) = timeout {
                let (new_lock, result) = self.store.cvar.wait_timeout(lock, duration).unwrap();
                lock = new_lock;
                if result.timed_out() {
                    // Check one last time before returning None
                    if let Some(img) = &*lock {
                        if last_timestamp.is_none() || Some(img.timestamp) != last_timestamp {
                            return Some(img.clone());
                        }
                    }
                    return None;
                }
            } else {
                lock = self.store.cvar.wait(lock).unwrap();
            }
        }
    }

    fn adjust_sensitivity(&self, factor: f64) -> bool {
        let current = self.get_exposure();
        let next = ((current as f64) * factor) as i32;
        let clamped = next.clamp(MIN_EXPOSURE_US, MAX_EXPOSURE_US);

        if clamped != current {
            self.set_exposure(clamped);
            true
        } else {
            false
        }
    }

    fn save_state(&self) {
        self.saved_exposure.store(
            self.current_exposure.load(Ordering::Relaxed),
            Ordering::Relaxed,
        );
    }

    fn restore_state(&self) {
        let exp = self.saved_exposure.load(Ordering::Relaxed);
        self.set_exposure(exp);
    }
}

use serde::Serialize;

#[derive(Serialize)]
pub struct CameraInfo {
    pub id: String,
    pub logical_id: String,
    pub fov: f32,
    pub max_zoom: f32,
    pub supports_raw: bool,
}

impl AndroidCamera {
    pub fn get_available_cameras() -> String {
        unsafe {
            let manager = ACameraManager_create();
            let mut id_list: *mut ACameraIdList = ptr::null_mut();
            ACameraManager_getCameraIdList(manager, &mut id_list);

            let mut all_cam_ids: Vec<(String, String)> = Vec::new();
            let num_cameras = (*id_list).numCameras;

            for i in 0..num_cameras {
                let cam_id_ptr = *(*id_list).cameraIds.offset(i as isize);
                let cam_id = CStr::from_ptr(cam_id_ptr).to_string_lossy().into_owned();
                all_cam_ids.push((cam_id.clone(), cam_id.clone()));

                let mut metadata: *mut ACameraMetadata = ptr::null_mut();
                if ACameraManager_getCameraCharacteristics(manager, cam_id_ptr, &mut metadata)
                    == camera_status_t::ACAMERA_OK
                {
                    let mut entry: ACameraMetadata_const_entry = std::mem::zeroed();
                    if ACameraMetadata_getConstEntry(
                        metadata,
                        acamera_metadata_tag::ACAMERA_LOGICAL_MULTI_CAMERA_PHYSICAL_IDS.0 as u32,
                        &mut entry,
                    ) == camera_status_t::ACAMERA_OK
                        && entry.count > 0
                    {
                        let mut start = 0;
                        let data_slice =
                            std::slice::from_raw_parts(entry.data.u8_, entry.count as usize);

                        for k in 0..entry.count as usize {
                            if data_slice[k] == 0 {
                                if let Ok(s) = std::str::from_utf8(&data_slice[start..k]) {
                                    let phys_id = s.to_string();
                                    if !all_cam_ids.iter().any(|(p, _)| *p == phys_id) {
                                        all_cam_ids.push((phys_id, cam_id.clone()));
                                    }
                                }
                                start = k + 1;
                            }
                        }
                    }
                    ACameraMetadata_free(metadata);
                }
            }

            let mut cameras = Vec::new();

            let mut c_strings = Vec::new();
            for (phys_id, logical_id) in all_cam_ids {
                c_strings.push((
                    std::ffi::CString::new(phys_id).unwrap(),
                    std::ffi::CString::new(logical_id).unwrap(),
                ));
            }

            for (cam_id_c, logical_id_c) in &c_strings {
                let cam_id_ptr = cam_id_c.as_ptr();
                let cam_id = cam_id_c.to_string_lossy().into_owned();
                let logical_id = logical_id_c.to_string_lossy().into_owned();

                let mut metadata: *mut ACameraMetadata = ptr::null_mut();
                if ACameraManager_getCameraCharacteristics(manager, cam_id_ptr, &mut metadata)
                    != camera_status_t::ACAMERA_OK
                {
                    continue;
                }

                let mut entry: ACameraMetadata_const_entry = std::mem::zeroed();
                ACameraMetadata_getConstEntry(
                    metadata,
                    acamera_metadata_tag::ACAMERA_LENS_FACING.0 as u32,
                    &mut entry,
                );
                if entry.count == 0
                    || *entry.data.u8_
                        != acamera_metadata_enum_acamera_lens_facing::ACAMERA_LENS_FACING_BACK.0
                            as u8
                {
                    ACameraMetadata_free(metadata);
                    continue;
                }

                ACameraMetadata_getConstEntry(
                    metadata,
                    acamera_metadata_tag::ACAMERA_SENSOR_INFO_PHYSICAL_SIZE.0 as u32,
                    &mut entry,
                );
                if entry.count < 2 {
                    ACameraMetadata_free(metadata);
                    continue;
                }
                let phys_width_mm = *entry.data.f.offset(0);

                ACameraMetadata_getConstEntry(
                    metadata,
                    acamera_metadata_tag::ACAMERA_LENS_INFO_AVAILABLE_FOCAL_LENGTHS.0 as u32,
                    &mut entry,
                );
                if entry.count == 0 {
                    ACameraMetadata_free(metadata);
                    continue;
                }
                let focal_length_mm = *entry.data.f.offset(0);

                let fov_rad = 2.0 * (phys_width_mm / (2.0 * focal_length_mm)).atan();
                let fov_deg = fov_rad * (180.0 / std::f32::consts::PI);

                let mut stream_configs_entry: ACameraMetadata_const_entry = std::mem::zeroed();
                ACameraMetadata_getConstEntry(
                    metadata,
                    acamera_metadata_tag::ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS.0 as u32,
                    &mut stream_configs_entry,
                );

                let mut supports_raw = false;

                // Method 1: Check stream configs for RAW formats
                let num_configs = stream_configs_entry.count / 4;
                for j in 0..num_configs {
                    let offset = (j * 4) as isize;
                    let format = *stream_configs_entry.data.i32_.offset(offset);
                    let is_input = *stream_configs_entry.data.i32_.offset(offset + 3);

                    let is_raw_format = format == AIMAGE_FORMATS::AIMAGE_FORMAT_RAW16.0 as i32
                        || format == AIMAGE_FORMATS::AIMAGE_FORMAT_RAW10.0 as i32
                        || format == AIMAGE_FORMATS::AIMAGE_FORMAT_RAW12.0 as i32;

                    if is_input == 0 && is_raw_format {
                        supports_raw = true;
                        break;
                    }
                }

                // Method 2: Check capabilities
                if !supports_raw {
                    let mut caps_entry: ACameraMetadata_const_entry = std::mem::zeroed();
                    let has_caps = ACameraMetadata_getConstEntry(
                        metadata,
                        acamera_metadata_tag::ACAMERA_REQUEST_AVAILABLE_CAPABILITIES.0 as u32,
                        &mut caps_entry,
                    ) == camera_status_t::ACAMERA_OK;

                    if has_caps {
                        for j in 0..caps_entry.count {
                            let cap = *caps_entry.data.u8_.offset(j as isize);
                            if cap == acamera_metadata_enum_android_request_available_capabilities_t::ACAMERA_REQUEST_AVAILABLE_CAPABILITIES_RAW.0 as u8 {
                                supports_raw = true;
                                break;
                            }
                        }
                    }
                }

                // Query Max Zoom
                let mut max_zoom = 1.0f32;
                let mut zoom_entry: ACameraMetadata_const_entry = std::mem::zeroed();
                if ACameraMetadata_getConstEntry(
                    metadata,
                    acamera_metadata_tag::ACAMERA_CONTROL_ZOOM_RATIO_RANGE.0 as u32,
                    &mut zoom_entry,
                ) == camera_status_t::ACAMERA_OK
                    && zoom_entry.count >= 2
                {
                    max_zoom = *zoom_entry.data.f.offset(1);
                } else if ACameraMetadata_getConstEntry(
                    metadata,
                    acamera_metadata_tag::ACAMERA_SCALER_AVAILABLE_MAX_DIGITAL_ZOOM.0 as u32,
                    &mut zoom_entry,
                ) == camera_status_t::ACAMERA_OK
                    && zoom_entry.count >= 1
                {
                    max_zoom = *zoom_entry.data.f.offset(0);
                }

                cameras.push(CameraInfo {
                    id: cam_id,
                    logical_id,
                    fov: fov_deg,
                    max_zoom,
                    supports_raw,
                });

                ACameraMetadata_free(metadata);
            }

            ACameraManager_delete(manager);
            ACameraManager_deleteCameraIdList(id_list);

            serde_json::to_string(&cameras).unwrap_or_else(|_| "[]".to_string())
        }
    }

    pub fn new(
        requested_id: Option<String>,
        sensor_mode: Option<String>,
        zoom_ratio: Option<f32>,
    ) -> Self {
        let store = Arc::new(CaptureStore {
            image: Mutex::new(None),
            cvar: Condvar::new(),
        });

        let current_exposure = Arc::new(AtomicI32::new(INITIAL_EXPOSURE_US));
        let saved_exposure = Arc::new(AtomicI32::new(INITIAL_EXPOSURE_US));
        let config = Arc::new(Mutex::new(None));

        let selected_config = unsafe {
            Self::discover_and_configure_camera(requested_id, sensor_mode.clone(), zoom_ratio)
        };
        let fixed_gain = if let Some(mode) = &sensor_mode {
            if mode == "Processed" {
                selected_config.true_max_analog_gain
            } else {
                std::cmp::min(selected_config.max_analog_gain, 3200)
            }
        } else {
            std::cmp::min(selected_config.max_analog_gain, 3200)
        };

        *config.lock().unwrap() = Some(selected_config);

        let current_gain = Arc::new(AtomicI32::new(fixed_gain));
        let current_focus = Arc::new(AtomicU32::new(0.0f32.to_bits()));
        let current_zoom = Arc::new(AtomicU32::new(zoom_ratio.unwrap_or(1.0).to_bits()));
        let current_nr = Arc::new(AtomicU32::new(
            acamera_metadata_enum_acamera_noise_reduction_mode::ACAMERA_NOISE_REDUCTION_MODE_OFF.0
                as u32,
        ));

        Self {
            command_tx: None,
            store,
            capture_thread: None,
            current_gain,
            current_exposure,
            saved_exposure,
            current_focus,
            current_zoom,
            current_nr,
            config,
        }
    }

    fn send_command(&self, cmd: CaptureCommand) {
        if let Some(tx) = &self.command_tx {
            let _ = tx.send(cmd);
        }
    }

    unsafe fn discover_and_configure_camera(
        requested_id: Option<String>,
        sensor_mode: Option<String>,
        zoom_ratio: Option<f32>,
    ) -> AndroidCameraConfiguration {
        unsafe {
            let manager = ACameraManager_create();
            let mut id_list: *mut ACameraIdList = ptr::null_mut();
            ACameraManager_getCameraIdList(manager, &mut id_list);

            let mut all_cam_ids: Vec<(String, String)> = Vec::new();

            let num_cameras = (*id_list).numCameras;
            info!("Scanning Android cameras ...");

            for i in 0..num_cameras {
                let cam_id_ptr = *(*id_list).cameraIds.offset(i as isize);
                let cam_id = CStr::from_ptr(cam_id_ptr).to_string_lossy().into_owned();
                all_cam_ids.push((cam_id.clone(), cam_id.clone()));

                let mut metadata: *mut ACameraMetadata = ptr::null_mut();
                if ACameraManager_getCameraCharacteristics(manager, cam_id_ptr, &mut metadata)
                    == camera_status_t::ACAMERA_OK
                {
                    // Extract physical IDs if this is a logical multi-camera
                    let mut entry: ACameraMetadata_const_entry = std::mem::zeroed();
                    if ACameraMetadata_getConstEntry(
                        metadata,
                        acamera_metadata_tag::ACAMERA_LOGICAL_MULTI_CAMERA_PHYSICAL_IDS.0 as u32,
                        &mut entry,
                    ) == camera_status_t::ACAMERA_OK
                        && entry.count > 0
                    {
                        let mut start = 0;
                        let data_slice =
                            std::slice::from_raw_parts(entry.data.u8_, entry.count as usize);

                        for k in 0..entry.count as usize {
                            if data_slice[k] == 0 {
                                // null-terminated string
                                if let Ok(s) = std::str::from_utf8(&data_slice[start..k]) {
                                    let phys_id = s.to_string();
                                    info!(
                                        "Found physical camera {} hidden in logical camera {}",
                                        phys_id, cam_id
                                    );
                                    if !all_cam_ids.iter().any(|(p, _)| *p == phys_id) {
                                        all_cam_ids.push((phys_id, cam_id.clone()));
                                    }
                                }
                                start = k + 1;
                            }
                        }
                    }
                    ACameraMetadata_free(metadata);
                }
            }

            let mut best_id = String::new();
            let mut best_logical_id = String::new();
            let mut min_fov = f32::MAX;
            let mut max_analog = 800;
            let mut true_max_analog = 800;
            let mut hyperfocal = 0.0;
            let mut best_dim = (0, 0);
            let mut best_format = AIMAGE_FORMATS::AIMAGE_FORMAT_RAW16.0 as i32;
            let mut best_white_level = 1023;
            let mut best_black_level = 0.0;
            let mut best_supports_contrast_curve = false;
            let mut best_active_array = [0; 4];
            let mut best_supports_zoom = false;
            let mut exact_match_found = false;

            info!("Total unique cameras to evaluate: {}", all_cam_ids.len());

            let mut c_strings = Vec::new();
            for (phys_id, logical_id) in all_cam_ids {
                c_strings.push((
                    std::ffi::CString::new(phys_id).unwrap(),
                    std::ffi::CString::new(logical_id).unwrap(),
                ));
            }

            for (cam_id_c, logical_id_c) in &c_strings {
                let cam_id_ptr = cam_id_c.as_ptr();
                let cam_id = cam_id_c.to_string_lossy().into_owned();
                let logical_id = logical_id_c.to_string_lossy().into_owned();

                let mut metadata: *mut ACameraMetadata = ptr::null_mut();
                if ACameraManager_getCameraCharacteristics(manager, cam_id_ptr, &mut metadata)
                    != camera_status_t::ACAMERA_OK
                {
                    info!("Camera {}: Skipped (Failed to get characteristics)", cam_id);
                    continue;
                }

                // 1. Ensure Back-Facing
                let mut entry: ACameraMetadata_const_entry = std::mem::zeroed();
                ACameraMetadata_getConstEntry(
                    metadata,
                    acamera_metadata_tag::ACAMERA_LENS_FACING.0 as u32,
                    &mut entry,
                );
                if entry.count == 0
                    || *entry.data.u8_
                        != acamera_metadata_enum_acamera_lens_facing::ACAMERA_LENS_FACING_BACK.0
                            as u8
                {
                    info!("Camera {}: Skipped (Not back-facing)", cam_id);
                    ACameraMetadata_free(metadata);
                    continue;
                }

                // 2. Physical Size & Focal Length
                ACameraMetadata_getConstEntry(
                    metadata,
                    acamera_metadata_tag::ACAMERA_SENSOR_INFO_PHYSICAL_SIZE.0 as u32,
                    &mut entry,
                );
                if entry.count < 2 {
                    info!(
                        "Camera {}: Skipped (Missing physical size metadata)",
                        cam_id
                    );
                    ACameraMetadata_free(metadata);
                    continue;
                }
                let phys_width_mm = *entry.data.f.offset(0);

                ACameraMetadata_getConstEntry(
                    metadata,
                    acamera_metadata_tag::ACAMERA_LENS_INFO_AVAILABLE_FOCAL_LENGTHS.0 as u32,
                    &mut entry,
                );
                if entry.count == 0 {
                    info!("Camera {}: Skipped (Missing focal length metadata)", cam_id);
                    ACameraMetadata_free(metadata);
                    continue;
                }
                let focal_length_mm = *entry.data.f.offset(0);

                // 3. Max Analog and Digital Gain
                ACameraMetadata_getConstEntry(
                    metadata,
                    acamera_metadata_tag::ACAMERA_SENSOR_INFO_SENSITIVITY_RANGE.0 as u32,
                    &mut entry,
                );
                let sensor_max_total_gain = if entry.count >= 2 {
                    *entry.data.i32_.offset(1)
                } else {
                    800
                };

                ACameraMetadata_getConstEntry(
                    metadata,
                    acamera_metadata_tag::ACAMERA_SENSOR_MAX_ANALOG_SENSITIVITY.0 as u32,
                    &mut entry,
                );
                let sensor_max_analog_gain = if entry.count >= 1 {
                    *entry.data.i32_.offset(0)
                } else {
                    800
                };

                // Query Hyperfocal Distance
                ACameraMetadata_getConstEntry(
                    metadata,
                    acamera_metadata_tag::ACAMERA_LENS_INFO_HYPERFOCAL_DISTANCE.0 as u32,
                    &mut entry,
                );
                let sensor_hyperfocal = if entry.count >= 1 {
                    *entry.data.f.offset(0)
                } else {
                    0.0
                };

                ACameraMetadata_getConstEntry(
                    metadata,
                    acamera_metadata_tag::ACAMERA_HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES.0 as u32,
                    &mut entry,
                );
                if entry.count > 0 {
                    let modes = std::slice::from_raw_parts(entry.data.u8_, entry.count as usize);
                    let mut global_modes = SUPPORTED_HOT_PIXEL_MODES.lock().unwrap();
                    global_modes.clear();
                    global_modes.extend_from_slice(modes);
                }

                // Query White Level
                ACameraMetadata_getConstEntry(
                    metadata,
                    acamera_metadata_tag::ACAMERA_SENSOR_INFO_WHITE_LEVEL.0 as u32,
                    &mut entry,
                );
                let sensor_white_level = if entry.count >= 1 {
                    *entry.data.i32_.offset(0)
                } else {
                    1023 // Default to 10-bit max if unknown
                };

                // Query Black Level Pattern
                ACameraMetadata_getConstEntry(
                    metadata,
                    acamera_metadata_tag::ACAMERA_SENSOR_BLACK_LEVEL_PATTERN.0 as u32,
                    &mut entry,
                );
                let sensor_black_level = if entry.count >= 4 {
                    let b1 = *entry.data.i32_.offset(0) as f32;
                    let b2 = *entry.data.i32_.offset(1) as f32;
                    let b3 = *entry.data.i32_.offset(2) as f32;
                    let b4 = *entry.data.i32_.offset(3) as f32;
                    (b1 + b2 + b3 + b4) / 4.0
                } else {
                    0.0
                };

                // Calculate Horizontal FOV
                let fov_rad = 2.0 * (phys_width_mm / (2.0 * focal_length_mm)).atan();
                let fov_deg = fov_rad * (180.0 / std::f32::consts::PI);

                // 5. Query Available Stream Configurations for RAW16 or YUV_420_888
                let mut stream_configs_entry: ACameraMetadata_const_entry = std::mem::zeroed();
                ACameraMetadata_getConstEntry(
                    metadata,
                    acamera_metadata_tag::ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS.0 as u32,
                    &mut stream_configs_entry,
                );

                let mut valid_raw_sizes = Vec::new();
                let mut raw_format_selected = AIMAGE_FORMATS::AIMAGE_FORMAT_RAW16.0 as i32;
                let mut valid_yuv_sizes = Vec::new();
                let num_configs = stream_configs_entry.count / 4;
                for j in 0..num_configs {
                    let offset = (j * 4) as isize;
                    let format = *stream_configs_entry.data.i32_.offset(offset);
                    let width = *stream_configs_entry.data.i32_.offset(offset + 1);
                    let height = *stream_configs_entry.data.i32_.offset(offset + 2);
                    let is_input = *stream_configs_entry.data.i32_.offset(offset + 3);

                    if is_input == 0 {
                        if format == AIMAGE_FORMATS::AIMAGE_FORMAT_RAW16.0 as i32
                            || format == AIMAGE_FORMATS::AIMAGE_FORMAT_RAW10.0 as i32
                            || format == AIMAGE_FORMATS::AIMAGE_FORMAT_RAW12.0 as i32
                        {
                            valid_raw_sizes.push((width as u32, height as u32));
                            raw_format_selected = format;
                        } else if format == AIMAGE_FORMATS::AIMAGE_FORMAT_YUV_420_888.0 as i32 {
                            valid_yuv_sizes.push((width as u32, height as u32));
                        }
                    }
                }

                let mut valid_sizes = valid_raw_sizes.clone();
                let mut selected_format = raw_format_selected;

                if let Some(ref mode) = sensor_mode {
                    if mode == "Processed" {
                        valid_sizes = valid_yuv_sizes.clone();
                        selected_format = AIMAGE_FORMATS::AIMAGE_FORMAT_YUV_420_888.0 as i32;
                    }
                }

                if valid_sizes.is_empty() {
                    if selected_format == raw_format_selected {
                        valid_sizes = valid_yuv_sizes.clone();
                        selected_format = AIMAGE_FORMATS::AIMAGE_FORMAT_YUV_420_888.0 as i32;
                    } else {
                        valid_sizes = valid_raw_sizes.clone();
                        selected_format = raw_format_selected;
                    }
                }

                if valid_sizes.is_empty() {
                    info!("Camera {}: Skipped (No RAW16 or YUV support)", cam_id);
                    ACameraMetadata_free(metadata);
                    continue;
                }

                let mut supports_contrast_curve = false;
                let mut tonemap_modes_entry: ACameraMetadata_const_entry = std::mem::zeroed();
                if ACameraMetadata_getConstEntry(
                    metadata,
                    acamera_metadata_tag::ACAMERA_TONEMAP_AVAILABLE_TONE_MAP_MODES.0 as u32,
                    &mut tonemap_modes_entry,
                ) == camera_status_t::ACAMERA_OK
                {
                    for j in 0..tonemap_modes_entry.count {
                        let mode = *tonemap_modes_entry.data.u8_.offset(j as isize);
                        if mode == acamera_metadata_enum_acamera_tonemap_mode::ACAMERA_TONEMAP_MODE_CONTRAST_CURVE.0 as u8 {
                            supports_contrast_curve = true;
                            break;
                        }
                    }
                }

                let max_binned_pixels: u32 = 13_000_000;

                // Filter out unbinned maximum resolutions (e.g., 50MP, 108MP)
                valid_sizes.retain(|&(w, h)| (w * h) <= max_binned_pixels);

                if valid_sizes.is_empty() {
                    // Safety fallback if no sizes fit (extremely unlikely)
                    warn!("No resolutions found under 13MP, falling back to 1920x1080");
                    valid_sizes.push((1920, 1080));
                }

                // Sort the remaining sizes to grab the largest hardware-binned output (typically 12.5MP)
                valid_sizes.sort_by(|a, b| (b.0 * b.1).cmp(&(a.0 * a.1)));
                let best_valid_size = valid_sizes[0];

                let format_str = if selected_format == AIMAGE_FORMATS::AIMAGE_FORMAT_RAW16.0 as i32
                {
                    "RAW16"
                } else if selected_format == AIMAGE_FORMATS::AIMAGE_FORMAT_RAW10.0 as i32 {
                    "RAW10"
                } else if selected_format == AIMAGE_FORMATS::AIMAGE_FORMAT_RAW12.0 as i32 {
                    "RAW12"
                } else {
                    "YUV_420_888"
                };
                info!(
                    "Camera {}: Back-facing, FOV {:.1}°, supports {} at {}x{}. Considering...",
                    cam_id, fov_deg, format_str, best_valid_size.0, best_valid_size.1
                );

                // Query Exposure Range
                let mut max_exposure_ns = 0i64;
                ACameraMetadata_getConstEntry(
                    metadata,
                    acamera_metadata_tag::ACAMERA_SENSOR_INFO_EXPOSURE_TIME_RANGE.0 as u32,
                    &mut entry,
                );
                if entry.count >= 2 {
                    max_exposure_ns = *entry.data.i64_.offset(1);
                }

                // Robust selection logic
                let is_exact_match = requested_id.as_deref() == Some(&cam_id);
                let is_logical_match = requested_id.as_deref() == Some(&logical_id);

                let is_first = best_id.is_empty();

                let mut should_pick = false;
                if is_exact_match {
                    should_pick = true;
                    exact_match_found = true;
                } else if is_logical_match && !exact_match_found {
                    should_pick = true;
                } else if is_first && !exact_match_found {
                    // Fallback if the requested camera is not found
                    should_pick = true;
                }

                if should_pick {
                    info!(
                        "Camera {} selected. Max Exposure Time: {} ms",
                        cam_id,
                        max_exposure_ns / 1_000_000
                    );
                    min_fov = fov_deg;
                    best_id = cam_id.clone();
                    best_logical_id = logical_id.clone();

                    let mut active_array_size = [0; 4];
                    if ACameraMetadata_getConstEntry(
                        metadata,
                        acamera_metadata_tag::ACAMERA_SENSOR_INFO_ACTIVE_ARRAY_SIZE.0 as u32,
                        &mut entry,
                    ) == camera_status_t::ACAMERA_OK
                        && entry.count >= 4
                    {
                        active_array_size = [
                            *entry.data.i32_.offset(0),
                            *entry.data.i32_.offset(1),
                            *entry.data.i32_.offset(2),
                            *entry.data.i32_.offset(3),
                        ];
                    }

                    let mut supports_zoom_ratio = false;
                    if ACameraMetadata_getConstEntry(
                        metadata,
                        acamera_metadata_tag::ACAMERA_CONTROL_ZOOM_RATIO_RANGE.0 as u32,
                        &mut entry,
                    ) == camera_status_t::ACAMERA_OK
                    {
                        supports_zoom_ratio = true;
                    }

                    if selected_format == AIMAGE_FORMATS::AIMAGE_FORMAT_RAW16.0 as i32
                        || selected_format == AIMAGE_FORMATS::AIMAGE_FORMAT_RAW10.0 as i32
                        || selected_format == AIMAGE_FORMATS::AIMAGE_FORMAT_RAW12.0 as i32
                    {
                        max_analog = sensor_max_analog_gain;
                    } else {
                        max_analog = sensor_max_total_gain;
                    }
                    true_max_analog = sensor_max_analog_gain;

                    hyperfocal = sensor_hyperfocal;
                    best_white_level = sensor_white_level;
                    best_black_level = sensor_black_level;
                    best_dim = best_valid_size;
                    best_format = selected_format;
                    best_supports_contrast_curve = supports_contrast_curve;
                    best_active_array = active_array_size;
                    best_supports_zoom = supports_zoom_ratio;
                }

                ACameraMetadata_free(metadata);
            }

            ACameraManager_delete(manager);
            ACameraManager_deleteCameraIdList(id_list);

            if best_id.is_empty() {
                panic!("No suitable back-facing camera found!");
            }

            // No software cropping applied
            info!(
                "Camera {} selected. Native FOV: {:.1}°. Streaming at {}x{}. No software cropping applied.",
                best_id, min_fov, best_dim.0, best_dim.1
            );

            AndroidCameraConfiguration {
                id: best_id,
                logical_id: best_logical_id,
                width: best_dim.0,
                height: best_dim.1,
                active_array_size: best_active_array,
                supports_zoom_ratio: best_supports_zoom,
                max_analog_gain: max_analog,
                true_max_analog_gain: true_max_analog,
                hyperfocal_distance: hyperfocal,
                fov: min_fov,
                white_level: best_white_level,
                black_level: best_black_level,
                format: best_format,
                supports_contrast_curve: best_supports_contrast_curve,
                zoom_ratio,
            }
        }
    }

    /// The AImageReader callback, invoked by the NDK Looper when a RAW16 frame is ready.
    extern "C" fn on_image_available(context: *mut c_void, reader: *mut AImageReader) {
        unsafe {
            let ctx = &*(context as *const ImageReaderContext);
            let mut image: *mut AImage = ptr::null_mut();
            if AImageReader_acquireLatestImage(reader, &mut image) == media_status_t::AMEDIA_OK
                && !image.is_null()
            {
                let receive_time = std::time::Instant::now();

                let mut data: *mut u8 = ptr::null_mut();
                let mut len: i32 = 0;
                AImage_getPlaneData(image, 0, &mut data, &mut len);

                let mut row_stride: i32 = 0;
                AImage_getPlaneRowStride(image, 0, &mut row_stride);

                // RAW16 is 16-bit. Downshift to 8-bit to fit `Image` struct
                // YUV_420_888 is 8-bit. Plane 0 is the Y-plane (grayscale).
                let width = ctx.width as usize;
                let height = ctx.height as usize;
                let mut image_data = Vec::with_capacity(width * height);
                image_data.set_len(width * height);

                let data_slice = std::slice::from_raw_parts(data, len as usize);

                if ctx.format == AIMAGE_FORMATS::AIMAGE_FORMAT_RAW16.0 as i32
                    || ctx.format == AIMAGE_FORMATS::AIMAGE_FORMAT_RAW10.0 as i32
                    || ctx.format == AIMAGE_FORMATS::AIMAGE_FORMAT_RAW12.0 as i32
                {
                    let black_floor = ctx.black_level;
                    let white_ceiling = ctx.white_level;
                    let range = white_ceiling - black_floor;
                    let scale = if range > 0.0 { 255.0 / range } else { 0.0 };

                    // Build LUT for 16-bit to 8-bit dynamic scaling
                    let mut lut = vec![0u8; 65536];
                    for (i, val) in lut.iter_mut().enumerate() {
                        let f_val = i as f32;
                        let adjusted = f_val - black_floor;
                        *val = (adjusted * scale).clamp(0.0, 255.0) as u8;
                    }

                    if ctx.format == AIMAGE_FORMATS::AIMAGE_FORMAT_RAW16.0 as i32 {
                        for y in 0..height {
                            let src_row_start = y * (row_stride as usize);
                            let dst_row_start = y * width;
                            for x in 0..width {
                                let byte_idx = src_row_start + (x * 2);
                                let val = u16::from_le_bytes([
                                    data_slice[byte_idx],
                                    data_slice[byte_idx + 1],
                                ]);
                                image_data[dst_row_start + x] = lut[val as usize];
                            }
                        }
                    } else if ctx.format == AIMAGE_FORMATS::AIMAGE_FORMAT_RAW10.0 as i32 {
                        for y in 0..height {
                            let src_row_start = y * (row_stride as usize);
                            let dst_row_start = y * width;
                            for (x, pix_chunk) in image_data[dst_row_start..dst_row_start + width]
                                .chunks_exact_mut(4)
                                .enumerate()
                            {
                                let byte_idx = src_row_start + x * 5;
                                let b = &data_slice[byte_idx..byte_idx + 5];
                                let lsb = b[4] as u16;

                                let val0 = ((b[0] as u16) << 2) | (lsb & 0x3);
                                let val1 = ((b[1] as u16) << 2) | ((lsb >> 2) & 0x3);
                                let val2 = ((b[2] as u16) << 2) | ((lsb >> 4) & 0x3);
                                let val3 = ((b[3] as u16) << 2) | ((lsb >> 6) & 0x3);

                                pix_chunk[0] = lut[val0 as usize];
                                pix_chunk[1] = lut[val1 as usize];
                                pix_chunk[2] = lut[val2 as usize];
                                pix_chunk[3] = lut[val3 as usize];
                            }
                        }
                    } else if ctx.format == AIMAGE_FORMATS::AIMAGE_FORMAT_RAW12.0 as i32 {
                        for y in 0..height {
                            let src_row_start = y * (row_stride as usize);
                            let dst_row_start = y * width;
                            for (x, pix_chunk) in image_data[dst_row_start..dst_row_start + width]
                                .chunks_exact_mut(2)
                                .enumerate()
                            {
                                let byte_idx = src_row_start + x * 3;
                                let b = &data_slice[byte_idx..byte_idx + 3];
                                let lsb = b[2] as u16;

                                let val0 = ((b[0] as u16) << 4) | (lsb & 0xF);
                                let val1 = ((b[1] as u16) << 4) | ((lsb >> 4) & 0xF);

                                pix_chunk[0] = lut[val0 as usize];
                                pix_chunk[1] = lut[val1 as usize];
                            }
                        }
                    }
                } else {
                    // YUV_420_888: Copy Y-plane (grayscale) directly
                    for y in 0..height {
                        let src_row_start = y * (row_stride as usize);
                        let dst_row_start = y * width;
                        for x in 0..width {
                            image_data[dst_row_start + x] = data_slice[src_row_start + x];
                        }
                    }
                }

                let target_exp_us = ctx.exposure_time_us.load(Ordering::Relaxed);

                let mut timestamp: i64 = 0;
                AImage_getTimestamp(image, &mut timestamp);

                let mut actual_exp_us = target_exp_us;
                let mut actual_gain = 100.0; // fallback
                let mut actual_interval_us = 0;
                let mut metadata_valid = false;

                {
                    let mut queue = ctx.frame_exposures.lock().unwrap();
                    if let Some(pos) = queue.iter().position(|&(ts, _, _, _)| ts == timestamp) {
                        actual_exp_us = queue[pos].1;
                        actual_gain = queue[pos].2;
                        actual_interval_us = queue[pos].3;
                        metadata_valid = true;
                        queue.drain(0..=pos);
                    }
                }

                if !metadata_valid {
                    // Only warn occasionally to avoid log spam if it completely breaks
                    if timestamp % 10 == 0 {
                        log::warn!(
                            "Frame timestamp {} missing metadata - dropping telemetry",
                            timestamp
                        );
                    }
                }

                let mut lock = ctx.store.image.lock().unwrap();
                *lock = Some(Arc::new(Image {
                    data: image_data,
                    width: ctx.width,
                    height: ctx.height,
                    timestamp: SystemTime::now(),
                    exposure_time_us: actual_exp_us,
                    gain: actual_gain,
                    post_process_time: receive_time.elapsed(),
                    frame_interval_us: actual_interval_us,
                    metadata_valid,
                }));
                ctx.store.cvar.notify_all();

                AImage_delete(image);
            }
        }
    }

    extern "C" fn on_session_ready(_context: *mut c_void, _session: *mut ACameraCaptureSession) {
        info!("Camera capture session READY");
    }

    extern "C" fn on_session_active(_context: *mut c_void, _session: *mut ACameraCaptureSession) {
        info!("Camera capture session ACTIVE");
    }

    extern "C" fn on_capture_completed(
        context: *mut c_void,
        _session: *mut ACameraCaptureSession,
        _request: *mut ACaptureRequest,
        result: *const ACameraMetadata,
    ) {
        unsafe {
            if context.is_null() {
                return;
            }
            let ctx = &*(context as *const ImageReaderContext);

            let mut entry: ACameraMetadata_const_entry = std::mem::zeroed();
            let mut timestamp: i64 = 0;
            if ACameraMetadata_getConstEntry(
                result,
                acamera_metadata_tag::ACAMERA_SENSOR_TIMESTAMP.0 as u32,
                &mut entry,
            ) == camera_status_t::ACAMERA_OK
                && entry.count > 0
            {
                timestamp = *entry.data.i64_.offset(0);
            }

            let mut exposure_ns: i64 = 0;
            if ACameraMetadata_getConstEntry(
                result,
                acamera_metadata_tag::ACAMERA_SENSOR_EXPOSURE_TIME.0 as u32,
                &mut entry,
            ) == camera_status_t::ACAMERA_OK
                && entry.count > 0
            {
                exposure_ns = *entry.data.i64_.offset(0);
            }

            let mut actual_gain: f32 = 100.0;
            if ACameraMetadata_getConstEntry(
                result,
                acamera_metadata_tag::ACAMERA_SENSOR_SENSITIVITY.0 as u32,
                &mut entry,
            ) == camera_status_t::ACAMERA_OK
                && entry.count > 0
            {
                actual_gain = *entry.data.i32_.offset(0) as f32;
            }

            if timestamp > 0 && exposure_ns > 0 {
                let prev = ctx
                    .prev_sensor_ts
                    .swap(timestamp, std::sync::atomic::Ordering::Relaxed);
                let interval_us = if prev > 0 {
                    (timestamp - prev) / 1000
                } else {
                    0
                };
                let exposure_us = (exposure_ns / 1000) as i32;
                let mut queue = ctx.frame_exposures.lock().unwrap();
                queue.push_back((timestamp, exposure_us, actual_gain, interval_us));
                if queue.len() > 30 {
                    queue.pop_front();
                }
            }
        }
    }

    extern "C" fn on_capture_failed(
        _context: *mut c_void,
        _session: *mut ACameraCaptureSession,
        _request: *mut ACaptureRequest,
        failure: *mut ACameraCaptureFailure,
    ) {
        unsafe {
            let reason = (*failure).reason;
            error!("Camera capture FAILED: reason {}", reason);
        }
    }

    extern "C" fn on_capture_buffer_lost(
        _context: *mut c_void,
        _session: *mut ACameraCaptureSession,
        _request: *mut ACaptureRequest,
        _window: *mut ANativeWindow,
        frame_number: i64,
    ) {
        warn!("Camera capture BUFFER LOST for frame {}", frame_number);
    }

    unsafe fn capture_loop(
        rx: mpsc::Receiver<CaptureCommand>,
        store: Arc<CaptureStore>,
        config_store: Arc<Mutex<Option<AndroidCameraConfiguration>>>,
        exposure_ref: Arc<AtomicI32>,
        gain_ref: Arc<AtomicI32>,
        focus_ref: Arc<AtomicU32>,
        nr_ref: Arc<AtomicU32>,
    ) {
        unsafe {
            let config = config_store
                .lock()
                .unwrap()
                .clone()
                .expect("Camera config missing");
            info!(
                "Starting Android NDK capture thread for camera ID: {}",
                config.id
            );

            // A Looper is required for AImageReader callbacks
            ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS as i32);

            // 1. Initialize AImageReader targeting selected format
            let mut image_reader: *mut AImageReader = ptr::null_mut();
            AImageReader_new(
                config.width as i32,
                config.height as i32,
                config.format,
                4,
                &mut image_reader,
            );

            let frame_exposures =
                Arc::new(Mutex::new(
                    std::collections::VecDeque::<(i64, i32, f32, i64)>::with_capacity(30),
                ));

            let mut reader_context = Box::new(ImageReaderContext {
                store: Arc::clone(&store),
                width: config.width,
                height: config.height,
                exposure_time_us: exposure_ref.clone(),
                white_level: config.white_level as f32,
                black_level: config.black_level,
                format: config.format,
                frame_exposures: Arc::clone(&frame_exposures),
                prev_sensor_ts: Arc::new(std::sync::atomic::AtomicI64::new(0)),
            });

            let mut listener = AImageReader_ImageListener {
                context: &mut *reader_context as *mut _ as *mut c_void,
                onImageAvailable: Some(Self::on_image_available),
            };
            AImageReader_setImageListener(image_reader, &mut listener);

            // 2. Open Camera
            let manager = ACameraManager_create();
            let mut device: *mut ACameraDevice = ptr::null_mut();
            let logical_id_c = std::ffi::CString::new(config.logical_id.clone()).unwrap();

            let mut device_callbacks = ACameraDevice_stateCallbacks {
                context: ptr::null_mut(),
                onDisconnected: None,
                onError: None,
            };
            ACameraManager_openCamera(
                manager,
                logical_id_c.as_ptr(),
                &mut device_callbacks,
                &mut device,
            );

            // 3. Create Capture Session
            let mut window: *mut ANativeWindow = ptr::null_mut();
            AImageReader_getWindow(image_reader, &mut window);

            let mut output_container: *mut ACaptureSessionOutputContainer = ptr::null_mut();
            ACaptureSessionOutputContainer_create(&mut output_container);

            let mut output: *mut ACaptureSessionOutput = ptr::null_mut();
            if config.id != config.logical_id {
                let phys_id_c = std::ffi::CString::new(config.id.clone()).unwrap();
                ACaptureSessionPhysicalOutput_create(window, phys_id_c.as_ptr(), &mut output);
            } else {
                ACaptureSessionOutput_create(window, &mut output);
            }
            ACaptureSessionOutputContainer_add(output_container, output);

            let mut session: *mut ACameraCaptureSession = ptr::null_mut();
            let mut session_callbacks = ACameraCaptureSession_stateCallbacks {
                context: ptr::null_mut(),
                onClosed: None,
                onReady: Some(Self::on_session_ready),
                onActive: Some(Self::on_session_active),
            };
            ACameraDevice_createCaptureSession(
                device,
                output_container,
                &mut session_callbacks,
                &mut session,
            );

            // 4. Create Capture Request (Manual Mode)
            let mut request: *mut ACaptureRequest = ptr::null_mut();
            ACameraDevice_createCaptureRequest(
                device,
                ACameraDevice_request_template::TEMPLATE_MANUAL,
                &mut request,
            );
            let mut target: *mut ACameraOutputTarget = ptr::null_mut();
            ACameraOutputTarget_create(window, &mut target);
            ACaptureRequest_addTarget(request, target);

            // Disable 3A routines
            let control_mode_off =
                acamera_metadata_enum_acamera_control_mode::ACAMERA_CONTROL_MODE_OFF.0 as u8;
            ACaptureRequest_setEntry_u8(
                request,
                acamera_metadata_tag::ACAMERA_CONTROL_MODE.0 as u32,
                1,
                &control_mode_off,
            );

            let ae_mode_off =
                acamera_metadata_enum_acamera_control_ae_mode::ACAMERA_CONTROL_AE_MODE_OFF.0 as u8;
            ACaptureRequest_setEntry_u8(
                request,
                acamera_metadata_tag::ACAMERA_CONTROL_AE_MODE.0 as u32,
                1,
                &ae_mode_off,
            );

            let af_mode_off =
                acamera_metadata_enum_acamera_control_af_mode::ACAMERA_CONTROL_AF_MODE_OFF.0 as u8;
            ACaptureRequest_setEntry_u8(
                request,
                acamera_metadata_tag::ACAMERA_CONTROL_AF_MODE.0 as u32,
                1,
                &af_mode_off,
            );

            let ois_mode_off =
                acamera_metadata_enum_acamera_lens_optical_stabilization_mode::ACAMERA_LENS_OPTICAL_STABILIZATION_MODE_OFF.0 as u8;
            ACaptureRequest_setEntry_u8(
                request,
                acamera_metadata_tag::ACAMERA_LENS_OPTICAL_STABILIZATION_MODE.0 as u32,
                1,
                &ois_mode_off,
            );

            let video_stab_off =
                acamera_metadata_enum_acamera_control_video_stabilization_mode::ACAMERA_CONTROL_VIDEO_STABILIZATION_MODE_OFF.0 as u8;
            ACaptureRequest_setEntry_u8(
                request,
                acamera_metadata_tag::ACAMERA_CONTROL_VIDEO_STABILIZATION_MODE.0 as u32,
                1,
                &video_stab_off,
            );

            let mut current_focus = f32::from_bits(focus_ref.load(Ordering::Relaxed));
            ACaptureRequest_setEntry_float(
                request,
                acamera_metadata_tag::ACAMERA_LENS_FOCUS_DISTANCE.0 as u32,
                1,
                &current_focus,
            );

            let mut current_nr = nr_ref.load(Ordering::Relaxed) as u8;
            ACaptureRequest_setEntry_u8(
                request,
                acamera_metadata_tag::ACAMERA_NOISE_REDUCTION_MODE.0 as u32,
                1,
                &current_nr,
            );

            if config.supports_contrast_curve {
                let tonemap_mode_curve =
                    acamera_metadata_enum_acamera_tonemap_mode::ACAMERA_TONEMAP_MODE_CONTRAST_CURVE
                        .0 as u8;
                ACaptureRequest_setEntry_u8(
                    request,
                    acamera_metadata_tag::ACAMERA_TONEMAP_MODE.0 as u32,
                    1,
                    &tonemap_mode_curve,
                );

                let linear_curve: [f32; 4] = [0.0, 0.0, 1.0, 1.0];
                ACaptureRequest_setEntry_float(
                    request,
                    acamera_metadata_tag::ACAMERA_TONEMAP_CURVE_RED.0 as u32,
                    4,
                    linear_curve.as_ptr(),
                );
                ACaptureRequest_setEntry_float(
                    request,
                    acamera_metadata_tag::ACAMERA_TONEMAP_CURVE_GREEN.0 as u32,
                    4,
                    linear_curve.as_ptr(),
                );
                ACaptureRequest_setEntry_float(
                    request,
                    acamera_metadata_tag::ACAMERA_TONEMAP_CURVE_BLUE.0 as u32,
                    4,
                    linear_curve.as_ptr(),
                );
            } else {
                let tonemap_mode_fast =
                    acamera_metadata_enum_acamera_tonemap_mode::ACAMERA_TONEMAP_MODE_FAST.0 as u8;
                ACaptureRequest_setEntry_u8(
                    request,
                    acamera_metadata_tag::ACAMERA_TONEMAP_MODE.0 as u32,
                    1,
                    &tonemap_mode_fast,
                );
            }

            let edge_mode_off =
                acamera_metadata_enum_acamera_edge_mode::ACAMERA_EDGE_MODE_OFF.0 as u8;
            ACaptureRequest_setEntry_u8(
                request,
                acamera_metadata_tag::ACAMERA_EDGE_MODE.0 as u32,
                1,
                &edge_mode_off,
            );

            let awb_mode_off =
                acamera_metadata_enum_acamera_control_awb_mode::ACAMERA_CONTROL_AWB_MODE_OFF.0
                    as u8;
            ACaptureRequest_setEntry_u8(
                request,
                acamera_metadata_tag::ACAMERA_CONTROL_AWB_MODE.0 as u32,
                1,
                &awb_mode_off,
            );

            let mut current_hot_pixel = CURRENT_HOT_PIXEL_MODE.load(Ordering::Relaxed) as u8;
            ACaptureRequest_setEntry_u8(
                request,
                acamera_metadata_tag::ACAMERA_HOT_PIXEL_MODE.0 as u32,
                1,
                &current_hot_pixel,
            );

            let shading_hq =
                acamera_metadata_enum_acamera_shading_mode::ACAMERA_SHADING_MODE_HIGH_QUALITY.0
                    as u8;
            ACaptureRequest_setEntry_u8(
                request,
                acamera_metadata_tag::ACAMERA_SHADING_MODE.0 as u32,
                1,
                &shading_hq,
            );

            if let Some(zoom) = config.zoom_ratio {
                if config.supports_zoom_ratio {
                    ACaptureRequest_setEntry_float(
                        request,
                        acamera_metadata_tag::ACAMERA_CONTROL_ZOOM_RATIO.0 as u32,
                        1,
                        &zoom,
                    );
                } else {
                    let rect = calculate_zoom_rect(&config.active_array_size, zoom);
                    ACaptureRequest_setEntry_i32(
                        request,
                        acamera_metadata_tag::ACAMERA_SCALER_CROP_REGION.0 as u32,
                        4,
                        rect.as_ptr(),
                    );
                }
            }

            let distortion_hq = acamera_metadata_enum_acamera_distortion_correction_mode::ACAMERA_DISTORTION_CORRECTION_MODE_HIGH_QUALITY.0 as u8;
            ACaptureRequest_setEntry_u8(
                request,
                acamera_metadata_tag::ACAMERA_DISTORTION_CORRECTION_MODE.0 as u32,
                1,
                &distortion_hq,
            );

            let aberration_off = acamera_metadata_enum_acamera_color_correction_aberration_mode::ACAMERA_COLOR_CORRECTION_ABERRATION_MODE_OFF.0 as u8;
            ACaptureRequest_setEntry_u8(
                request,
                acamera_metadata_tag::ACAMERA_COLOR_CORRECTION_ABERRATION_MODE.0 as u32,
                1,
                &aberration_off,
            );

            let color_correction_hq = acamera_metadata_enum_acamera_color_correction_mode::ACAMERA_COLOR_CORRECTION_MODE_HIGH_QUALITY.0 as u8;
            ACaptureRequest_setEntry_u8(
                request,
                acamera_metadata_tag::ACAMERA_COLOR_CORRECTION_MODE.0 as u32,
                1,
                &color_correction_hq,
            );

            let black_level_lock = 1u8; // true
            ACaptureRequest_setEntry_u8(
                request,
                acamera_metadata_tag::ACAMERA_BLACK_LEVEL_LOCK.0 as u32,
                1,
                &black_level_lock,
            );

            // Hard-lock Analog Gain
            let mut current_gain = gain_ref.load(Ordering::Relaxed);
            ACaptureRequest_setEntry_i32(
                request,
                acamera_metadata_tag::ACAMERA_SENSOR_SENSITIVITY.0 as u32,
                1,
                &current_gain,
            );

            // Software cropping applied in engine

            let mut target_exposure = exposure_ref.load(Ordering::Relaxed);
            let mut exp_ns = (target_exposure as i64) * 1000;
            ACaptureRequest_setEntry_i64(
                request,
                acamera_metadata_tag::ACAMERA_SENSOR_EXPOSURE_TIME.0 as u32,
                1,
                &exp_ns,
            );

            // Frame duration must be >= exposure time, otherwise exposure is clamped
            let frame_duration_ns = exp_ns + 5_000_000; // Add 5ms margin
            ACaptureRequest_setEntry_i64(
                request,
                acamera_metadata_tag::ACAMERA_SENSOR_FRAME_DURATION.0 as u32,
                1,
                &frame_duration_ns,
            );

            let mut capture_callbacks = ACameraCaptureSession_captureCallbacks {
                context: &mut *reader_context as *mut _ as *mut c_void,
                onCaptureStarted: None,
                onCaptureProgressed: None,
                onCaptureCompleted: Some(Self::on_capture_completed),
                onCaptureFailed: Some(Self::on_capture_failed),
                onCaptureSequenceCompleted: None,
                onCaptureSequenceAborted: None,
                onCaptureBufferLost: Some(Self::on_capture_buffer_lost),
            };

            ACameraCaptureSession_setRepeatingRequest(
                session,
                &mut capture_callbacks,
                1,
                &mut request,
                ptr::null_mut(),
            );

            'capture: loop {
                // Poll Looper to drive the AImageReader callbacks
                let _looper = ALooper_forThread();
                ALooper_pollAll(10, ptr::null_mut(), ptr::null_mut(), ptr::null_mut());

                let mut request_changed = false;

                let new_hot_pixel = CURRENT_HOT_PIXEL_MODE.load(Ordering::Relaxed) as u8;
                if new_hot_pixel != current_hot_pixel {
                    current_hot_pixel = new_hot_pixel;
                    ACaptureRequest_setEntry_u8(
                        request,
                        acamera_metadata_tag::ACAMERA_HOT_PIXEL_MODE.0 as u32,
                        1,
                        &current_hot_pixel,
                    );
                    request_changed = true;
                }

                while let Ok(cmd) = rx.try_recv() {
                    let changed = match cmd {
                        CaptureCommand::StopCaptureThread => break 'capture,
                        CaptureCommand::SetExposure(exp) => {
                            target_exposure = exp;
                            exp_ns = (target_exposure as i64) * 1000;
                            ACaptureRequest_setEntry_i64(
                                request,
                                acamera_metadata_tag::ACAMERA_SENSOR_EXPOSURE_TIME.0 as u32,
                                1,
                                &exp_ns,
                            );

                            let frame_duration_ns = exp_ns + 5_000_000; // Add 5ms margin
                            ACaptureRequest_setEntry_i64(
                                request,
                                acamera_metadata_tag::ACAMERA_SENSOR_FRAME_DURATION.0 as u32,
                                1,
                                &frame_duration_ns,
                            );
                            true
                        }
                        CaptureCommand::SetGain(gain) => {
                            current_gain = gain;
                            ACaptureRequest_setEntry_i32(
                                request,
                                acamera_metadata_tag::ACAMERA_SENSOR_SENSITIVITY.0 as u32,
                                1,
                                &current_gain,
                            );
                            true
                        }
                        CaptureCommand::SetFocus(dist) => {
                            current_focus = dist;
                            ACaptureRequest_setEntry_float(
                                request,
                                acamera_metadata_tag::ACAMERA_LENS_FOCUS_DISTANCE.0 as u32,
                                1,
                                &current_focus,
                            );
                            true
                        }
                        CaptureCommand::SetNoiseReduction(mode) => {
                            current_nr = mode;
                            ACaptureRequest_setEntry_u8(
                                request,
                                acamera_metadata_tag::ACAMERA_NOISE_REDUCTION_MODE.0 as u32,
                                1,
                                &current_nr,
                            );
                            true
                        }
                        CaptureCommand::SetZoom(zoom) => {
                            if config.supports_zoom_ratio {
                                ACaptureRequest_setEntry_float(
                                    request,
                                    acamera_metadata_tag::ACAMERA_CONTROL_ZOOM_RATIO.0 as u32,
                                    1,
                                    &zoom,
                                );
                            } else {
                                let rect = calculate_zoom_rect(&config.active_array_size, zoom);
                                ACaptureRequest_setEntry_i32(
                                    request,
                                    acamera_metadata_tag::ACAMERA_SCALER_CROP_REGION.0 as u32,
                                    4,
                                    rect.as_ptr(),
                                );
                            }
                            true
                        }
                    };

                    if changed {
                        // Update the repeating request
                        ACameraCaptureSession_setRepeatingRequest(
                            session,
                            &mut capture_callbacks,
                            1,
                            &mut request,
                            ptr::null_mut(),
                        );
                        request_changed = false; // Reset to avoid double apply
                    }
                }

                if request_changed {
                    ACameraCaptureSession_setRepeatingRequest(
                        session,
                        &mut capture_callbacks,
                        1,
                        &mut request,
                        ptr::null_mut(),
                    );
                }
            }

            info!("Android NDK capture thread stopping. Cleaning up...");
            ACameraCaptureSession_stopRepeating(session);

            // ACameraCaptureSession_close has a known double-free bug on Android 10 (API 29)
            // when closed just before ACameraDevice_close.
            let api_level = ndk_sys::android_get_device_api_level();
            if api_level == 29 {
                info!("Skipping ACameraCaptureSession_close due to Android 10 NDK bug");
            } else {
                ACameraCaptureSession_close(session);
            }

            ACaptureRequest_free(request);
            ACaptureSessionOutput_free(output);
            ACaptureSessionOutputContainer_free(output_container);
            ACameraDevice_close(device);
            AImageReader_delete(image_reader);
            ACameraManager_delete(manager);
        }
    }
}

impl Drop for AndroidCamera {
    fn drop(&mut self) {
        self.send_command(CaptureCommand::StopCaptureThread);
        if let Some(handle) = self.capture_thread.take() {
            let _ = handle.join();
        }
    }
}

fn calculate_zoom_rect(active_array: &[i32; 4], zoom: f32) -> [i32; 4] {
    let sensor_w = active_array[2] as f32;
    let sensor_h = active_array[3] as f32;

    let crop_w = sensor_w / zoom;
    let crop_h = sensor_h / zoom;

    let center_x = active_array[0] as f32 + sensor_w / 2.0;
    let center_y = active_array[1] as f32 + sensor_h / 2.0;

    let crop_left = center_x - crop_w / 2.0;
    let crop_top = center_y - crop_h / 2.0;

    [
        crop_left as i32,
        crop_top as i32,
        crop_w as i32,
        crop_h as i32,
    ]
}
