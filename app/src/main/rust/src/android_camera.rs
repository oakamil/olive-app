// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

use log::{info, warn};
use ndk_sys::*;
use olive_engine::{CameraConfig, CameraTrait, Image};
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

const INITIAL_EXPOSURE_US: i32 = 500_000;
const MIN_EXPOSURE_US: i32 = 16_667;
const MAX_EXPOSURE_US: i32 = 5_000_000;

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
    StopCaptureThread,
}

#[derive(Clone)]
pub struct AndroidCameraConfiguration {
    pub id: String,
    pub logical_id: String,
    pub width: u32,
    pub height: u32,
    pub target_crop: Option<(u32, u32)>,
    pub max_analog_gain: i32,
    pub fov: f32,
    pub white_level: i32,
    pub format: i32,
}

/// Context passed to the AImageReader C callback
struct ImageReaderContext {
    store: Arc<CaptureStore>,
    width: u32,
    height: u32,
    exposure_time_us: Arc<AtomicI32>,
    white_level: f32,
    format: i32,
    frame_exposures: Arc<Mutex<std::collections::VecDeque<(i64, i32)>>>,
}

pub struct AndroidCamera {
    command_tx: Option<mpsc::Sender<CaptureCommand>>,
    store: Arc<CaptureStore>,
    capture_thread: Option<JoinHandle<()>>,

    current_exposure: Arc<AtomicI32>,
    saved_exposure: Arc<AtomicI32>,
    current_gain: Arc<AtomicI32>,
    current_focus: Arc<AtomicU32>, // Store f32 as u32 bits
    current_nr: Arc<AtomicU32>,    // Store u8 as u32

    config: Arc<Mutex<Option<AndroidCameraConfiguration>>>,
}

impl CameraTrait for AndroidCamera {
    fn get_config(&self) -> Option<CameraConfig> {
        self.config.lock().unwrap().as_ref().map(|c| CameraConfig {
            model: format!("Android_Cam_{}", c.id),
            width: c.width,
            height: c.height,
            fps: 0,
            crop: c.target_crop,
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

    fn get_gain_range(&self) -> (f32, f32) {
        let max = self
            .config
            .lock()
            .unwrap()
            .as_ref()
            .map(|c| c.max_analog_gain as f32)
            .unwrap_or(3200.0);
        (100.0, max)
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

    fn enable_hcg(&self) {
        // warn!("HCG explicitly managed by Android ISP; ignoring manual enable.");
    }

    fn disable_hcg(&self) {
        warn!("HCG explicitly managed by Android ISP; ignoring manual disable.");
    }

    fn set_focus(&self, distance: f32) {
        self.current_focus
            .store(distance.to_bits(), Ordering::Relaxed);
        self.send_command(CaptureCommand::SetFocus(distance));
    }

    fn set_noise_reduction(&self, mode: u8) {
        self.current_nr.store(mode as u32, Ordering::Relaxed);
        self.send_command(CaptureCommand::SetNoiseReduction(mode));
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

                cameras.push(CameraInfo {
                    id: cam_id,
                    logical_id,
                    fov: fov_deg,
                });

                ACameraMetadata_free(metadata);
            }

            ACameraManager_delete(manager);
            ACameraManager_deleteCameraIdList(id_list);

            serde_json::to_string(&cameras).unwrap_or_else(|_| "[]".to_string())
        }
    }

    pub fn new(requested_id: Option<String>) -> Self {
        let store = Arc::new(CaptureStore {
            image: Mutex::new(None),
            cvar: Condvar::new(),
        });

        let current_exposure = Arc::new(AtomicI32::new(INITIAL_EXPOSURE_US));
        let saved_exposure = Arc::new(AtomicI32::new(INITIAL_EXPOSURE_US));
        let config = Arc::new(Mutex::new(None));

        let selected_config = unsafe { Self::discover_and_configure_camera(requested_id) };
        let fixed_gain = selected_config.max_analog_gain;

        *config.lock().unwrap() = Some(selected_config);

        let current_gain = Arc::new(AtomicI32::new(fixed_gain));
        let current_focus = Arc::new(AtomicU32::new(0.0f32.to_bits()));
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
            let mut max_analog = 0;
            let mut best_dim = (0, 0);
            let mut best_white_level = 1023;
            let mut best_format = AIMAGE_FORMATS::AIMAGE_FORMAT_RAW16.0 as i32;

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

                // 3. Max Analog Gain
                ACameraMetadata_getConstEntry(
                    metadata,
                    acamera_metadata_tag::ACAMERA_SENSOR_MAX_ANALOG_SENSITIVITY.0 as u32,
                    &mut entry,
                );
                let sensor_max_analog = if entry.count >= 1 {
                    *entry.data.i32_.offset(0)
                } else {
                    800
                };

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
                let mut valid_yuv_sizes = Vec::new();
                let num_configs = stream_configs_entry.count / 4;
                for j in 0..num_configs {
                    let offset = (j * 4) as isize;
                    let format = *stream_configs_entry.data.i32_.offset(offset);
                    let width = *stream_configs_entry.data.i32_.offset(offset + 1);
                    let height = *stream_configs_entry.data.i32_.offset(offset + 2);
                    let is_input = *stream_configs_entry.data.i32_.offset(offset + 3);

                    if is_input == 0 {
                        if format == AIMAGE_FORMATS::AIMAGE_FORMAT_RAW16.0 as i32 {
                            valid_raw_sizes.push((width as u32, height as u32));
                        } else if format == AIMAGE_FORMATS::AIMAGE_FORMAT_YUV_420_888.0 as i32 {
                            valid_yuv_sizes.push((width as u32, height as u32));
                        }
                    }
                }

                let mut valid_sizes = valid_raw_sizes;
                let mut selected_format = AIMAGE_FORMATS::AIMAGE_FORMAT_RAW16.0 as i32;

                if valid_sizes.is_empty() {
                    valid_sizes = valid_yuv_sizes;
                    selected_format = AIMAGE_FORMATS::AIMAGE_FORMAT_YUV_420_888.0 as i32;
                }

                if valid_sizes.is_empty() {
                    info!("Camera {}: Skipped (No RAW16 or YUV support)", cam_id);
                    ACameraMetadata_free(metadata);
                    continue;
                }

                // Always pick the largest available size for the selected format
                valid_sizes.sort_by(|a, b| (b.0 * b.1).cmp(&(a.0 * a.1)));
                let best_valid_size = valid_sizes[0];

                let format_str = if selected_format == AIMAGE_FORMATS::AIMAGE_FORMAT_RAW16.0 as i32
                {
                    "RAW16"
                } else {
                    "YUV_420_888"
                };
                info!(
                    "Camera {}: Back-facing, FOV {:.1}°, supports {} at {}x{}. Considering...",
                    cam_id, fov_deg, format_str, best_valid_size.0, best_valid_size.1
                );

                // Robust selection logic
                let is_requested = requested_id.as_deref() == Some(&cam_id)
                        || requested_id.as_deref() == Some(&logical_id);

                let is_first = best_id.is_empty();

                let mut should_pick = false;
                if is_requested {
                    should_pick = true;
                } else if is_first {
                    // Fallback if the requested camera is not found
                    should_pick = true;
                }

                if should_pick {
                    min_fov = fov_deg;
                    best_id = cam_id.clone();
                    best_logical_id = logical_id.clone();
                    max_analog = sensor_max_analog;
                    best_white_level = sensor_white_level;
                    best_dim = best_valid_size;
                    best_format = selected_format;
                }

                ACameraMetadata_free(metadata);
            }

            ACameraManager_delete(manager);
            ACameraManager_deleteCameraIdList(id_list);

            if best_id.is_empty() {
                panic!("No suitable back-facing camera found!");
            }

            // No software cropping applied
            let target_crop = None;
            info!(
                "Camera {} selected. Native FOV: {:.1}°. Streaming at {}x{}. No software cropping applied.",
                best_id, min_fov, best_dim.0, best_dim.1
            );

            AndroidCameraConfiguration {
                id: best_id,
                logical_id: best_logical_id,
                width: best_dim.0,
                height: best_dim.1,
                target_crop,
                max_analog_gain: max_analog,
                fov: min_fov,
                white_level: best_white_level,
                format: best_format,
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

                if ctx.format == AIMAGE_FORMATS::AIMAGE_FORMAT_RAW16.0 as i32 {
                    let scale = 255.0 / ctx.white_level;

                    for y in 0..height {
                        let src_row_start = y * (row_stride as usize);
                        let dst_row_start = y * width;
                        for x in 0..width {
                            let byte_idx = src_row_start + (x * 2);
                            let val = u16::from_le_bytes([
                                data_slice[byte_idx],
                                data_slice[byte_idx + 1],
                            ]);

                            let scaled = (val as f32 * scale) as i32;
                            image_data[dst_row_start + x] = scaled.clamp(0, 255) as u8;
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

                {
                    let mut queue = ctx.frame_exposures.lock().unwrap();
                    if let Some(pos) = queue.iter().position(|&(ts, _)| ts == timestamp) {
                        actual_exp_us = queue[pos].1;
                        queue.drain(0..=pos);
                    }
                }

                let mut lock = ctx.store.image.lock().unwrap();
                *lock = Some(Arc::new(Image {
                    data: image_data,
                    width: ctx.width,
                    height: ctx.height,
                    timestamp: SystemTime::now(),
                    exposure_time_us: actual_exp_us,
                    post_process_time: receive_time.elapsed(),
                }));
                ctx.store.cvar.notify_all();

                AImage_delete(image);
            }
        }
    }

    extern "C" fn on_session_ready(_context: *mut c_void, _session: *mut ACameraCaptureSession) {
        log::info!("Camera capture session READY");
    }

    extern "C" fn on_session_active(_context: *mut c_void, _session: *mut ACameraCaptureSession) {
        log::info!("Camera capture session ACTIVE");
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

            if timestamp > 0 && exposure_ns > 0 {
                let exposure_us = (exposure_ns / 1000) as i32;
                let mut queue = ctx.frame_exposures.lock().unwrap();
                queue.push_back((timestamp, exposure_us));
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
            log::error!("Camera capture FAILED: reason {}", reason);
        }
    }

    extern "C" fn on_capture_buffer_lost(
        _context: *mut c_void,
        _session: *mut ACameraCaptureSession,
        _request: *mut ACaptureRequest,
        _window: *mut ANativeWindow,
        frame_number: i64,
    ) {
        log::warn!("Camera capture BUFFER LOST for frame {}", frame_number);
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

            let frame_exposures = Arc::new(Mutex::new(
                std::collections::VecDeque::<(i64, i32)>::with_capacity(30),
            ));

            let mut reader_context = Box::new(ImageReaderContext {
                store: Arc::clone(&store),
                width: config.width,
                height: config.height,
                exposure_time_us: exposure_ref,
                white_level: config.white_level as f32,
                format: config.format,
                frame_exposures: Arc::clone(&frame_exposures),
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

            let tonemap_mode_fast =
                acamera_metadata_enum_acamera_tonemap_mode::ACAMERA_TONEMAP_MODE_FAST.0 as u8;
            ACaptureRequest_setEntry_u8(
                request,
                acamera_metadata_tag::ACAMERA_TONEMAP_MODE.0 as u32,
                1,
                &tonemap_mode_fast,
            );

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

            let hot_pixel_off =
                acamera_metadata_enum_acamera_hot_pixel_mode::ACAMERA_HOT_PIXEL_MODE_OFF.0 as u8;
            ACaptureRequest_setEntry_u8(
                request,
                acamera_metadata_tag::ACAMERA_HOT_PIXEL_MODE.0 as u32,
                1,
                &hot_pixel_off,
            );

            let shading_off =
                acamera_metadata_enum_acamera_shading_mode::ACAMERA_SHADING_MODE_OFF.0 as u8;
            ACaptureRequest_setEntry_u8(
                request,
                acamera_metadata_tag::ACAMERA_SHADING_MODE.0 as u32,
                1,
                &shading_off,
            );

            let distortion_off = acamera_metadata_enum_acamera_distortion_correction_mode::ACAMERA_DISTORTION_CORRECTION_MODE_OFF.0 as u8;
            ACaptureRequest_setEntry_u8(
                request,
                acamera_metadata_tag::ACAMERA_DISTORTION_CORRECTION_MODE.0 as u32,
                1,
                &distortion_off,
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

            let mut target_exposure = INITIAL_EXPOSURE_US;
            let mut exp_ns = (target_exposure as i64) * 1000;
            ACaptureRequest_setEntry_i64(
                request,
                acamera_metadata_tag::ACAMERA_SENSOR_EXPOSURE_TIME.0 as u32,
                1,
                &exp_ns,
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
                    }
                }
            }

            info!("Android NDK capture thread stopping. Cleaning up...");
            ACameraCaptureSession_stopRepeating(session);
            ACameraCaptureSession_close(session);
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
