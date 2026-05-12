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

const INITIAL_EXPOSURE_US: i32 = 555550_000;
const MIN_EXPOSURE_US: i32 = 16_667;
const MAX_EXPOSURE_US: i32 = 5_000_000;

// --- Data Structures ---

struct CaptureStore {
    image: Mutex<Option<Arc<Image>>>,
    cvar: Condvar,
}

enum CaptureCommand {
    SetExposure(i32),
    StopCaptureThread,
    #[allow(dead_code)]
    SetGain(f32),
}

#[derive(Clone)]
pub struct AndroidCameraConfiguration {
    pub id: String,
    pub logical_id: String,
    pub width: u32,
    pub height: u32,
    pub crop_region: [i32; 4],
    pub max_analog_gain: i32,
    pub fov: f32,
}

/// Context passed to the AImageReader C callback
struct ImageReaderContext {
    store: Arc<CaptureStore>,
    width: u32,
    height: u32,
    exposure_time_us: Arc<AtomicI32>,
}

pub struct AndroidCamera {
    command_tx: Option<mpsc::Sender<CaptureCommand>>,
    store: Arc<CaptureStore>,
    capture_thread: Option<JoinHandle<()>>,

    current_exposure: Arc<AtomicI32>,
    saved_exposure: Arc<AtomicI32>,
    current_gain: Arc<AtomicU32>, // Holds the fixed max analog gain

    config: Arc<Mutex<Option<AndroidCameraConfiguration>>>,
}

impl CameraTrait for AndroidCamera {
    fn get_config(&self) -> Option<CameraConfig> {
        self.config.lock().unwrap().as_ref().map(|c| CameraConfig {
            model: format!("Android_Cam_{}", c.id),
            width: c.width,
            height: c.height,
            fps: 0,
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

        self.capture_thread = Some(thread::spawn(move || unsafe {
            Self::capture_loop(rx, store_clone, config_clone, exposure_clone);
        }));

        Ok(())
    }

    fn get_gain(&self) -> f32 {
        f32::from_bits(self.current_gain.load(Ordering::Relaxed))
    }

    fn get_min_gain(&self) -> f32 {
        self.get_gain()
    }

    fn get_max_gain(&self) -> f32 {
        self.get_gain()
    }

    fn set_gain(&self, _gain: f32) {
        warn!("set_gain ignored: AndroidCamera is locked to Max Analog Gain.");
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
        warn!("HCG explicitly managed by Android ISP; ignoring manual enable.");
    }

    fn disable_hcg(&self) {
        warn!("HCG explicitly managed by Android ISP; ignoring manual disable.");
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

impl AndroidCamera {
    pub fn new() -> Self {
        let store = Arc::new(CaptureStore {
            image: Mutex::new(None),
            cvar: Condvar::new(),
        });

        let current_exposure = Arc::new(AtomicI32::new(INITIAL_EXPOSURE_US));
        let saved_exposure = Arc::new(AtomicI32::new(INITIAL_EXPOSURE_US));
        let config = Arc::new(Mutex::new(None));

        let selected_config = unsafe { Self::discover_and_configure_camera() };
        let fixed_gain = selected_config.max_analog_gain as f32;

        *config.lock().unwrap() = Some(selected_config);

        let current_gain = Arc::new(AtomicU32::new(fixed_gain.to_bits()));

        Self {
            command_tx: None,
            store,
            capture_thread: None,
            current_gain,
            current_exposure,
            saved_exposure,
            config,
        }
    }

    fn send_command(&self, cmd: CaptureCommand) {
        if let Some(tx) = &self.command_tx {
            let _ = tx.send(cmd);
        }
    }

    unsafe fn discover_and_configure_camera() -> AndroidCameraConfiguration {
        unsafe {
        let manager = ACameraManager_create();
        let mut id_list: *mut ACameraIdList = ptr::null_mut();
        ACameraManager_getCameraIdList(manager, &mut id_list);

        let mut all_cam_ids: Vec<(String, String)> = Vec::new();

        let num_cameras = (*id_list).numCameras;
        info!("Scanning Android cameras for best FOV fit...");
        
        for i in 0..num_cameras {
            let cam_id_ptr = *(*id_list).cameraIds.offset(i as isize);
            let cam_id = CStr::from_ptr(cam_id_ptr).to_string_lossy().into_owned();
            all_cam_ids.push((cam_id.clone(), cam_id.clone()));

            let mut metadata: *mut ACameraMetadata = ptr::null_mut();
            if ACameraManager_getCameraCharacteristics(manager, cam_id_ptr, &mut metadata) == camera_status_t::ACAMERA_OK {
                
                // Extract physical IDs if this is a logical multi-camera
                let mut entry: ACameraMetadata_const_entry = std::mem::zeroed();
                if ACameraMetadata_getConstEntry(
                    metadata,
                    acamera_metadata_tag::ACAMERA_LOGICAL_MULTI_CAMERA_PHYSICAL_IDS.0 as u32,
                    &mut entry,
                ) == camera_status_t::ACAMERA_OK && entry.count > 0 {
                    
                    let mut start = 0;
                    let data_slice = std::slice::from_raw_parts(entry.data.u8_, entry.count as usize);
                    
                    for k in 0..entry.count as usize {
                        if data_slice[k] == 0 { // null-terminated string
                            if let Ok(s) = std::str::from_utf8(&data_slice[start..k]) {
                                let phys_id = s.to_string();
                                info!("Found physical camera {} hidden in logical camera {}", phys_id, cam_id);
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
        let mut best_crop = [0, 0, 0, 0];
        let mut max_analog = 0;
        let mut best_dim = (0, 0);

        info!("Total unique cameras to evaluate: {}", all_cam_ids.len());

        let mut c_strings = Vec::new();
        for (phys_id, logical_id) in all_cam_ids {
            c_strings.push((
                std::ffi::CString::new(phys_id).unwrap(),
                std::ffi::CString::new(logical_id).unwrap()
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
                    != acamera_metadata_enum_acamera_lens_facing::ACAMERA_LENS_FACING_BACK.0 as u8
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
                info!("Camera {}: Skipped (Missing physical size metadata)", cam_id);
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

            // 3. Active Array
            ACameraMetadata_getConstEntry(
                metadata,
                acamera_metadata_tag::ACAMERA_SENSOR_INFO_ACTIVE_ARRAY_SIZE.0 as u32,
                &mut entry,
            );
            if entry.count < 4 {
                info!("Camera {}: Skipped (Missing active array metadata)", cam_id);
                ACameraMetadata_free(metadata);
                continue;
            }
            let active_array = [
                *entry.data.i32_.offset(0),
                *entry.data.i32_.offset(1),
                *entry.data.i32_.offset(2),
                *entry.data.i32_.offset(3),
            ];

            // 4. Max Analog Gain
            ACameraMetadata_getConstEntry(
                metadata,
                acamera_metadata_tag::ACAMERA_SENSOR_INFO_SENSITIVITY_RANGE.0 as u32,
                &mut entry,
            );
            let sensor_max_analog = if entry.count >= 2 {
                *entry.data.i32_.offset(1)
            } else {
                800
            };

            // Calculate Horizontal FOV
            let fov_rad = 2.0 * (phys_width_mm / (2.0 * focal_length_mm)).atan();
            let fov_deg = fov_rad * (180.0 / std::f32::consts::PI);

            // 5. Query Available Stream Configurations for RAW16
            let mut stream_configs_entry: ACameraMetadata_const_entry = std::mem::zeroed();
            ACameraMetadata_getConstEntry(
                metadata,
                acamera_metadata_tag::ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS.0 as u32,
                &mut stream_configs_entry,
            );

            let mut valid_raw_sizes = Vec::new();
            // Format is [format, width, height, input/output]
            let num_configs = stream_configs_entry.count / 4;
            for j in 0..num_configs {
                let offset = (j * 4) as isize;
                let format = *stream_configs_entry.data.i32_.offset(offset);
                let width = *stream_configs_entry.data.i32_.offset(offset + 1);
                let height = *stream_configs_entry.data.i32_.offset(offset + 2);
                let is_input = *stream_configs_entry.data.i32_.offset(offset + 3);

                if format == AIMAGE_FORMATS::AIMAGE_FORMAT_RAW16.0 as i32 && is_input == 0 {
                    valid_raw_sizes.push((width as u32, height as u32));
                }
            }

            if valid_raw_sizes.is_empty() {
                info!("Camera {}: Skipped (Back-facing, FOV {:.1}°, but NO RAW16 stream support)", cam_id, fov_deg);
                ACameraMetadata_free(metadata);
                continue;
            }

            info!("Camera {}: Back-facing, FOV {:.1}°, supports RAW16. Considering...", cam_id, fov_deg);

            // Target smallest FOV > 10.0 degrees
            if fov_deg >= 10.0 && fov_deg < min_fov {
                min_fov = fov_deg;
                best_id = cam_id.clone();
                best_logical_id = logical_id.clone();
                max_analog = sensor_max_analog;

                let target_crop_w;
                let target_crop_h;

                if fov_deg > 25.0 {
                    let target_fov_rad = 25.0_f32 * (std::f32::consts::PI / 180.0);
                    let target_width_mm = 2.0 * focal_length_mm * (target_fov_rad / 2.0).tan();

                    let crop_ratio = target_width_mm / phys_width_mm;

                    let crop_w = (active_array[2] as f32 * crop_ratio) as i32;
                    let crop_h = (active_array[3] as f32 * crop_ratio) as i32;

                    let crop_x = active_array[0] + (active_array[2] - crop_w) / 2;
                    let crop_y = active_array[1] + (active_array[3] - crop_h) / 2;

                    best_crop = [crop_x, crop_y, crop_w, crop_h];
                    target_crop_w = crop_w as u32;
                    target_crop_h = crop_h as u32;
                } else {
                    best_crop = active_array;
                    target_crop_w = active_array[2] as u32;
                    target_crop_h = active_array[3] as u32;
                }

                // Find the smallest valid RAW16 size that is >= our target crop size.
                // If none is larger, just pick the largest available.
                let mut best_valid_size = valid_raw_sizes[0];
                let mut min_diff = u32::MAX;
                
                // Sort to make logic simpler (largest first)
                valid_raw_sizes.sort_by(|a, b| (b.0 * b.1).cmp(&(a.0 * a.1)));
                
                let mut found_larger = false;
                for size in &valid_raw_sizes {
                    if size.0 >= target_crop_w && size.1 >= target_crop_h {
                        found_larger = true;
                        let diff = (size.0 - target_crop_w) + (size.1 - target_crop_h);
                        if diff < min_diff {
                            min_diff = diff;
                            best_valid_size = *size;
                        }
                    }
                }
                if !found_larger {
                    best_valid_size = valid_raw_sizes[0]; // fallback to largest
                }

                best_dim = best_valid_size;

                if fov_deg > 25.0 {
                    info!(
                        "Camera {} selected. Native FOV: {:.1}°. Cropping to {:.1}° (Crop window: {}x{}, Stream output: {}x{})",
                        best_id, fov_deg, 25.0, best_crop[2], best_crop[3], best_dim.0, best_dim.1
                    );
                } else {
                    info!(
                        "Camera {} selected. Native FOV: {:.1}°. Using full array (Crop window: {}x{}, Stream output: {}x{})",
                        best_id, fov_deg, best_crop[2], best_crop[3], best_dim.0, best_dim.1
                    );
                }
            }

            ACameraMetadata_free(metadata);
        }

        ACameraManager_delete(manager);
        ACameraManager_deleteCameraIdList(id_list);

        AndroidCameraConfiguration {
            id: best_id,
            logical_id: best_logical_id,
            width: best_dim.0,
            height: best_dim.1,
            crop_region: best_crop,
            max_analog_gain: max_analog,
            fov: min_fov,
        }
        }
    }

    /// The AImageReader callback, invoked by the NDK Looper when a RAW16 frame is ready.
    extern "C" fn on_image_available(context: *mut c_void, reader: *mut AImageReader) {
        unsafe {
            let ctx = &*(context as *const ImageReaderContext);
            let mut image: *mut AImage = ptr::null_mut();
            if AImageReader_acquireLatestImage(reader, &mut image) == media_status_t::AMEDIA_OK && !image.is_null()
            {
                log::debug!("Received frame from Android camera ({}x{})", ctx.width, ctx.height);
                let receive_time = std::time::Instant::now();

                let mut data: *mut u8 = ptr::null_mut();
                let mut len: i32 = 0;
                AImage_getPlaneData(image, 0, &mut data, &mut len);

                let mut row_stride: i32 = 0;
                AImage_getPlaneRowStride(image, 0, &mut row_stride);

                // RAW16 is 16-bit. Downshift to 8-bit to fit `Image` struct
                let width = ctx.width as usize;
                let height = ctx.height as usize;
                let mut image_data = Vec::with_capacity(width * height);
                image_data.set_len(width * height);

                let data_slice = std::slice::from_raw_parts(data, len as usize);

                for y in 0..height {
                    let src_row_start = y * (row_stride as usize);
                    let dst_row_start = y * width;
                    for x in 0..width {
                        // Extract high byte of the 16-bit word (Little Endian)
                        let byte_idx = src_row_start + (x * 2) + 1;
                        image_data[dst_row_start + x] = data_slice[byte_idx];
                    }
                }

                let current_exp_us = ctx.exposure_time_us.load(Ordering::Relaxed);

                let mut lock = ctx.store.image.lock().unwrap();
                *lock = Some(Arc::new(Image {
                    data: image_data,
                    width: ctx.width,
                    height: ctx.height,
                    timestamp: SystemTime::now(),
                    exposure_time_us: current_exp_us,
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
        _context: *mut c_void,
        _session: *mut ACameraCaptureSession,
        _request: *mut ACaptureRequest,
        _result: *const ACameraMetadata,
    ) {
        // Too spammy for info
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

        // 1. Initialize AImageReader targeting RAW16
        let mut image_reader: *mut AImageReader = ptr::null_mut();
        AImageReader_new(
            config.width as i32,
            config.height as i32,
            AIMAGE_FORMATS::AIMAGE_FORMAT_RAW16.0 as i32,
            4,
            &mut image_reader,
        );

        let mut reader_context = Box::new(ImageReaderContext {
            store: Arc::clone(&store),
            width: config.width,
            height: config.height,
            exposure_time_us: exposure_ref,
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
        let control_mode_off = acamera_metadata_enum_acamera_control_mode::ACAMERA_CONTROL_MODE_OFF.0 as u8;
        ACaptureRequest_setEntry_u8(
            request,
            acamera_metadata_tag::ACAMERA_CONTROL_MODE.0 as u32,
            1,
            &control_mode_off,
        );

        let ae_mode_off = acamera_metadata_enum_acamera_control_ae_mode::ACAMERA_CONTROL_AE_MODE_OFF.0 as u8;
        ACaptureRequest_setEntry_u8(
            request,
            acamera_metadata_tag::ACAMERA_CONTROL_AE_MODE.0 as u32,
            1,
            &ae_mode_off,
        );

        let af_mode_off = acamera_metadata_enum_acamera_control_af_mode::ACAMERA_CONTROL_AF_MODE_OFF.0 as u8;
        ACaptureRequest_setEntry_u8(
            request,
            acamera_metadata_tag::ACAMERA_CONTROL_AF_MODE.0 as u32,
            1,
            &af_mode_off,
        );

        let infinity = 0.0f32;
        ACaptureRequest_setEntry_float(
            request,
            acamera_metadata_tag::ACAMERA_LENS_FOCUS_DISTANCE.0 as u32,
            1,
            &infinity,
        );

        let nr_off =
            acamera_metadata_enum_acamera_noise_reduction_mode::ACAMERA_NOISE_REDUCTION_MODE_OFF.0
                as u8;
        ACaptureRequest_setEntry_u8(
            request,
            acamera_metadata_tag::ACAMERA_NOISE_REDUCTION_MODE.0 as u32,
            1,
            &nr_off,
        );

        // Hard-lock Max Analog Gain
        ACaptureRequest_setEntry_i32(
            request,
            acamera_metadata_tag::ACAMERA_SENSOR_SENSITIVITY.0 as u32,
            1,
            &config.max_analog_gain,
        );

        // Apply 25-degree Crop
        ACaptureRequest_setEntry_i32(
            request,
            acamera_metadata_tag::ACAMERA_SCALER_CROP_REGION.0 as u32,
            4,
            config.crop_region.as_ptr(),
        );

        let mut target_exposure = INITIAL_EXPOSURE_US;
        let mut exp_ns = (target_exposure as i64) * 1000;
        ACaptureRequest_setEntry_i64(
            request,
            acamera_metadata_tag::ACAMERA_SENSOR_EXPOSURE_TIME.0 as u32,
            1,
            &exp_ns,
        );

        let mut capture_callbacks = ACameraCaptureSession_captureCallbacks {
            context: ptr::null_mut(),
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
            ALooper_pollAll(50, ptr::null_mut(), ptr::null_mut(), ptr::null_mut());

            while let Ok(cmd) = rx.try_recv() {
                match cmd {
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

                        // Update the repeating request
                        ACameraCaptureSession_setRepeatingRequest(
                            session,
                            ptr::null_mut(),
                            1,
                            &mut request,
                            ptr::null_mut(),
                        );
                    }
                    CaptureCommand::SetGain(_) => {}
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
