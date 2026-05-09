// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

use olive_engine::{CameraConfig, CameraTrait, Image};
use log::{error, info, warn};
use ndk_sys::*;
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

const INITIAL_EXPOSURE_US: i32 = 200_000;
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

    fn wait_for_next_capture(&self, last_timestamp: Option<SystemTime>) -> Arc<Image> {
        let mut lock = self.store.image.lock().unwrap();
        loop {
            if let Some(img) = &*lock {
                if last_timestamp.is_none() || Some(img.timestamp) != last_timestamp {
                    return img.clone();
                }
            }
            let (new_lock, result) = self.store.cvar.wait_timeout(lock, Duration::from_millis(250)).unwrap();
            lock = new_lock;
            if result.timed_out() {
                if let Some(img) = &*lock {
                    return img.clone();
                } else {
                    // Fallback if empty
                    return Arc::new(Image {
                        data: vec![],
                        width: 1,
                        height: 1,
                        timestamp: SystemTime::now(),
                        exposure_time_us: 0,
                        post_process_time: Duration::default(),
                    });
                }
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
        info!("Scanning Android cameras for best FOV fit...");

        let manager = ACameraManager_create();
        let mut id_list: *mut ACameraIdList = ptr::null_mut();
        ACameraManager_getCameraIdList(manager, &mut id_list);

        let mut best_id = String::new();
        let mut min_fov = f32::MAX;
        let mut best_crop = [0, 0, 0, 0];
        let mut max_analog = 0;
        let mut best_dim = (0, 0);

        for i in 0..(*id_list).numCameras {
            let cam_id_ptr = *(*id_list).cameraIds.offset(i as isize);
            let cam_id = CStr::from_ptr(cam_id_ptr).to_string_lossy().into_owned();

            let mut metadata: *mut ACameraMetadata = ptr::null_mut();
            if ACameraManager_getCameraCharacteristics(manager, cam_id_ptr, &mut metadata)
                != AMEDIA_OK
            {
                continue;
            }

            // 1. Ensure Back-Facing
            let mut entry = ACameraMetadata_const_entry::default();
            ACameraMetadata_getConstEntry(
                metadata,
                acamera_metadata_tag_ACAMERA_LENS_FACING as u32,
                &mut entry,
            );
            if entry.count == 0
                || *entry.data.u8_
                    != acamera_metadata_enum_acamera_lens_facing_ACAMERA_LENS_FACING_BACK as u8
            {
                ACameraMetadata_free(metadata);
                continue;
            }

            // 2. Physical Size & Focal Length
            ACameraMetadata_getConstEntry(
                metadata,
                acamera_metadata_tag_ACAMERA_SENSOR_INFO_PHYSICAL_SIZE as u32,
                &mut entry,
            );
            if entry.count < 2 {
                ACameraMetadata_free(metadata);
                continue;
            }
            let phys_width_mm = *entry.data.f.offset(0);

            ACameraMetadata_getConstEntry(
                metadata,
                acamera_metadata_tag_ACAMERA_LENS_INFO_AVAILABLE_FOCAL_LENGTHS as u32,
                &mut entry,
            );
            if entry.count == 0 {
                ACameraMetadata_free(metadata);
                continue;
            }
            let focal_length_mm = *entry.data.f.offset(0);

            // 3. Active Array
            ACameraMetadata_getConstEntry(
                metadata,
                acamera_metadata_tag_ACAMERA_SENSOR_INFO_ACTIVE_ARRAY_SIZE as u32,
                &mut entry,
            );
            if entry.count < 4 {
                ACameraMetadata_free(metadata);
                continue;
            }
            let active_array = [
                *entry.data.i32.offset(0),
                *entry.data.i32.offset(1),
                *entry.data.i32.offset(2),
                *entry.data.i32.offset(3),
            ];

            // 4. Max Analog Gain
            ACameraMetadata_getConstEntry(
                metadata,
                acamera_metadata_tag_ACAMERA_SENSOR_INFO_SENSITIVITY_RANGE as u32,
                &mut entry,
            );
            let sensor_max_analog = if entry.count >= 2 {
                *entry.data.i32.offset(1)
            } else {
                800
            };

            // Calculate Horizontal FOV
            let fov_rad = 2.0 * (phys_width_mm / (2.0 * focal_length_mm)).atan();
            let fov_deg = fov_rad * (180.0 / std::f32::consts::PI);

            // Target smallest FOV > 10.0 degrees
            if fov_deg >= 10.0 && fov_deg < min_fov {
                min_fov = fov_deg;
                best_id = cam_id.clone();
                max_analog = sensor_max_analog;

                if fov_deg > 25.0 {
                    let target_fov_rad = 25.0_f32 * (std::f32::consts::PI / 180.0);
                    let target_width_mm = 2.0 * focal_length_mm * (target_fov_rad / 2.0).tan();

                    let crop_ratio = target_width_mm / phys_width_mm;

                    let crop_w = (active_array[2] as f32 * crop_ratio) as i32;
                    let crop_h = (active_array[3] as f32 * crop_ratio) as i32;

                    let crop_x = active_array[0] + (active_array[2] - crop_w) / 2;
                    let crop_y = active_array[1] + (active_array[3] - crop_h) / 2;

                    best_crop = [crop_x, crop_y, crop_w, crop_h];
                    best_dim = (crop_w as u32, crop_h as u32);
                    info!(
                        "Camera {} selected. Native FOV: {:.1}°. Cropping to {:.1}° ({}x{})",
                        best_id, fov_deg, 25.0, crop_w, crop_h
                    );
                } else {
                    best_crop = active_array;
                    best_dim = (active_array[2] as u32, active_array[3] as u32);
                    info!(
                        "Camera {} selected. Native FOV: {:.1}°. Using full array ({}x{})",
                        best_id, fov_deg, best_dim.0, best_dim.1
                    );
                }
            }

            ACameraMetadata_free(metadata);
        }

        ACameraManager_deleteCameraIdList(id_list);
        ACameraManager_delete(manager);

        AndroidCameraConfiguration {
            id: best_id,
            width: best_dim.0,
            height: best_dim.1,
            crop_region: best_crop,
            max_analog_gain: max_analog,
            fov: min_fov,
        }
    }

    /// The AImageReader callback, invoked by the NDK Looper when a RAW16 frame is ready.
    extern "C" fn on_image_available(context: *mut c_void, reader: *mut AImageReader) {
        unsafe {
            let ctx = &*(context as *const ImageReaderContext);
            let mut image: *mut AImage = ptr::null_mut();

            if AImageReader_acquireLatestImage(reader, &mut image) == AMEDIA_OK && !image.is_null()
            {
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

    unsafe fn capture_loop(
        rx: mpsc::Receiver<CaptureCommand>,
        store: Arc<CaptureStore>,
        config_store: Arc<Mutex<Option<AndroidCameraConfiguration>>>,
        exposure_ref: Arc<AtomicI32>,
    ) {
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
            AIMAGE_FORMAT_RAW16 as i32,
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
        let cam_id_c = std::ffi::CString::new(config.id.clone()).unwrap();

        let mut device_callbacks = ACameraDevice_stateCallbacks {
            context: ptr::null_mut(),
            onDisconnected: None,
            onError: None,
        };
        ACameraManager_openCamera(
            manager,
            cam_id_c.as_ptr(),
            &mut device_callbacks,
            &mut device,
        );

        // 3. Create Capture Session
        let mut window: *mut ANativeWindow = ptr::null_mut();
        AImageReader_getWindow(image_reader, &mut window);

        let mut output_container: *mut ACaptureSessionOutputContainer = ptr::null_mut();
        ACaptureSessionOutputContainer_create(&mut output_container);

        let mut output: *mut ACaptureSessionOutput = ptr::null_mut();
        ACaptureSessionOutput_create(window, &mut output);
        ACaptureSessionOutputContainer_add(output_container, output);

        let mut session: *mut ACameraCaptureSession = ptr::null_mut();
        let mut session_callbacks = ACameraCaptureSession_stateCallbacks {
            context: ptr::null_mut(),
            onClosed: None,
            onReady: None,
            onActive: None,
        };
        ACameraDevice_createCaptureSession(
            device,
            output_container,
            &mut session_callbacks,
            &mut session,
        );

        // 4. Create Capture Request (Manual Mode)
        let mut request: *mut ACaptureRequest = ptr::null_mut();
        ACameraDevice_createCaptureRequest(device, ACameraDevice_request_template_TEMPLATE_MANUAL as u32, &mut request);
        ACaptureRequest_addTarget(request, output);

        // Disable 3A routines
        let mode_off = acamera_metadata_enum_acamera_control_mode_ACAMERA_CONTROL_MODE_OFF as u8;
        ACaptureRequest_setEntry_u8(
            request,
            acamera_metadata_tag_ACAMERA_CONTROL_MODE as u32,
            1,
            &mode_off,
        );

        let nr_off =
            acamera_metadata_enum_acamera_noise_reduction_mode_ACAMERA_NOISE_REDUCTION_MODE_OFF
                as u8;
        ACaptureRequest_setEntry_u8(
            request,
            acamera_metadata_tag_ACAMERA_NOISE_REDUCTION_MODE as u32,
            1,
            &nr_off,
        );

        // Hard-lock Max Analog Gain
        ACaptureRequest_setEntry_i32(
            request,
            acamera_metadata_tag_ACAMERA_SENSOR_SENSITIVITY as u32,
            1,
            &config.max_analog_gain,
        );

        // Apply 25-degree Crop
        ACaptureRequest_setEntry_i32(
            request,
            acamera_metadata_tag_ACAMERA_SCALER_CROP_REGION as u32,
            4,
            config.crop_region.as_ptr(),
        );

        let mut target_exposure = INITIAL_EXPOSURE_US;
        let mut exp_ns = (target_exposure as i64) * 1000;
        ACaptureRequest_setEntry_i64(
            request,
            acamera_metadata_tag_ACAMERA_SENSOR_EXPOSURE_TIME as u32,
            1,
            &exp_ns,
        );

        ACameraCaptureSession_setRepeatingRequest(
            session,
            ptr::null_mut(),
            1,
            &mut request,
            ptr::null_mut(),
        );

        'capture: loop {
            // Poll Looper to drive the AImageReader callbacks
            let looper = ALooper_forThread();
            ALooper_pollAll(50, ptr::null_mut(), ptr::null_mut(), ptr::null_mut());

            while let Ok(cmd) = rx.try_recv() {
                match cmd {
                    CaptureCommand::StopCaptureThread => break 'capture,
                    CaptureCommand::SetExposure(exp) => {
                        target_exposure = exp;
                        exp_ns = (target_exposure as i64) * 1000;
                        ACaptureRequest_setEntry_i64(
                            request,
                            acamera_metadata_tag_ACAMERA_SENSOR_EXPOSURE_TIME as u32,
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

impl Drop for AndroidCamera {
    fn drop(&mut self) {
        self.send_command(CaptureCommand::StopCaptureThread);
        if let Some(handle) = self.capture_thread.take() {
            let _ = handle.join();
        }
    }
}
