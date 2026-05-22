use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::jint;
use olive_engine::imu::ImuDevice;
use std::sync::Mutex;
use std::thread;

pub mod android_camera;
pub mod android_imu;

static RUNTIME: Mutex<Option<tokio::runtime::Runtime>> = Mutex::new(None);
static TELESCOPE_RUNTIME: Mutex<Option<tokio::runtime::Runtime>> = Mutex::new(None);
static ENGINE_STOP_TX: Mutex<Option<tokio::sync::oneshot::Sender<()>>> = Mutex::new(None);
static ENGINE_STATE: Mutex<
    Option<std::sync::Arc<std::sync::RwLock<olive_engine::engine::EngineStateData>>>,
> = Mutex::new(None);
static CAMERA: Mutex<Option<std::sync::Arc<dyn olive_engine::CameraTrait>>> = Mutex::new(None);
static MAIN_ENGINE_THREAD: Mutex<Option<std::thread::JoinHandle<()>>> = Mutex::new(None);

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_MainActivity_initJni(
    mut env: JNIEnv,
    _class: JClass,
    files_dir: jni::objects::JString,
) {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("OliveRust"),
    );

    std::panic::set_hook(Box::new(|panic_info| {
        log::error!("RUST PANIC: {}", panic_info);
    }));

    if let Ok(dir) = env.get_string(&files_dir) {
        if let Ok(dir_str) = dir.to_str() {
            if let Err(e) = std::env::set_current_dir(dir_str) {
                log::error!("Failed to set current directory to {}: {}", dir_str, e);
            } else {
                log::info!("Successfully set current directory to {}", dir_str);
            }
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_MainActivity_startEngine(
    mut env: JNIEnv,
    _class: JClass,
    fd: jint,
    imu_enabled: jni::sys::jboolean,
    uvc_fps: jint,
    extractor: jni::objects::JString,
    latitude: jni::sys::jdouble,
    longitude: jni::sys::jdouble,
    time_ms: jni::sys::jlong,
) {
    let fd = fd as i32;
    let use_imu = imu_enabled != 0;
    let target_fps = uvc_fps as u32;
    let extractor_str: String = env.get_string(&extractor).unwrap().into();
    let ext_type = if extractor_str == "Cedar" {
        olive_engine::engine::Extractor::Cedar
    } else {
        olive_engine::engine::Extractor::Sequential
    };

    let mut runtime_lock = RUNTIME.lock().unwrap();
    if runtime_lock.is_none() {
        *runtime_lock = Some(
            tokio::runtime::Builder::new_multi_thread()
                .enable_all()
                .build()
                .unwrap(),
        );
    }
    let rt = runtime_lock.as_ref().unwrap();

    let (tx, rx) = tokio::sync::oneshot::channel::<()>();
    *ENGINE_STOP_TX.lock().unwrap() = Some(tx);

    let rt_handle = rt.handle().clone();

    let old_thread = MAIN_ENGINE_THREAD.lock().unwrap().take();

    let new_thread = thread::spawn(move || {
        if let Some(handle) = old_thread {
            let _ = handle.join();
        }
        rt_handle.block_on(async {
            use olive_engine::CameraTrait;
            use olive_engine::control_server;
            use olive_engine::engine::{Engine, EngineConfig};
            use olive_engine::uvc_camera::Camera;
            use std::sync::Arc;

            let mut cam = Camera::with_fd(target_fps, true, None, Some(fd));
            if let Err(e) = cam.start_capture() {
                log::error!("Failed to start UVC camera: {}", e);
                return;
            }
            let camera: std::sync::Arc<dyn CameraTrait> = Arc::new(cam);
            *CAMERA.lock().unwrap() = Some(camera.clone());

            let config = EngineConfig {
                gain: None,
                target_centroids: 20,
                database_path: "default_database.npz".to_string(),
                isp: false,
                bg_mode: olive_engine::engine::BgMode::Block,
                extractor_type: ext_type,
                crop: None,
                downsample: true,
                bypass_calibration: false,
                noise_filter: false,
                sigma: None,
                initial_fov: None,
            };
            let client_connected = Arc::new(std::sync::atomic::AtomicBool::new(false));

            let mut initial_state = olive_engine::engine::EngineStateData::default();
            if latitude >= -90.0 && latitude <= 90.0 && longitude >= -180.0 && longitude <= 180.0 {
                initial_state.client_latitude = Some(latitude);
                initial_state.client_longitude = Some(longitude);
            }
            if time_ms > 0 {
                use chrono::TimeZone;
                initial_state.client_date = chrono::Utc.timestamp_millis_opt(time_ms).single();
            }

            let engine_state = Arc::new(std::sync::RwLock::new(initial_state));

            let server_camera = camera.clone();
            let server_client_connected = client_connected.clone();
            let server_engine_state = engine_state.clone();

            *crate::ENGINE_STATE.lock().unwrap() = Some(engine_state.clone());

            let server_handle = tokio::spawn(async move {
                control_server::start_server(
                    server_camera,
                    server_client_connected,
                    server_engine_state,
                )
                .await;
            });

            use crate::android_imu::AndroidImu;
            use olive_engine::imu::Imu;

            let imu_arc = if use_imu {
                let mut imu_dev = AndroidImu::new();
                if imu_dev.init().is_ok() {
                    if let Ok(imu) = Imu::start(imu_dev) {
                        Some(Arc::new(imu))
                    } else {
                        None
                    }
                } else {
                    None
                }
            } else {
                None
            };

            let mut telescope_runtime_lock = TELESCOPE_RUNTIME.lock().unwrap();
            if telescope_runtime_lock.is_none() {
                *telescope_runtime_lock = Some(
                    tokio::runtime::Builder::new_multi_thread()
                        .worker_threads(1)
                        .enable_all()
                        .build()
                        .unwrap(),
                );
            }
            let telescope_rt = telescope_runtime_lock.as_ref().unwrap();

            let mut engine = Engine::new(
                camera,
                config,
                client_connected,
                engine_state,
                imu_arc,
                Some(telescope_rt.handle().clone()),
            );
            let is_running_clone = engine.is_running.clone();
            let telescope_handle = engine.telescope_server_handle.take(); // We now take ownership of this handle

            // Run engine in a separate thread since it might block
            let engine_thread = std::thread::spawn(move || {
                let handles = engine.start();
                let _ = handles.0.join();
                let _ = handles.1.join();
            });

            // Wait for stop signal
            let _ = rx.await;
            is_running_clone.store(false, std::sync::atomic::Ordering::Relaxed);
            server_handle.abort();
            let _ = server_handle.await;

            if let Some(handle) = telescope_handle {
                handle.abort();
                let _ = handle.await;
            }

            let _ = engine_thread.join();
        });
    });

    *MAIN_ENGINE_THREAD.lock().unwrap() = Some(new_thread);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_MainActivity_startNativeCameraEngine(
    mut env: JNIEnv,
    _class: JClass,
    imu_enabled: jni::sys::jboolean,
    extractor: jni::objects::JString,
    internal_camera_id: jni::objects::JString,
    latitude: jni::sys::jdouble,
    longitude: jni::sys::jdouble,
    time_ms: jni::sys::jlong,
) {
    let use_imu = imu_enabled != 0;
    let internal_camera_id_str: String = env.get_string(&internal_camera_id).unwrap().into();
    let extractor_str: String = env.get_string(&extractor).unwrap().into();
    let ext_type = if extractor_str == "Cedar" {
        olive_engine::engine::Extractor::Cedar
    } else {
        olive_engine::engine::Extractor::Sequential
    };

    let mut runtime_lock = RUNTIME.lock().unwrap();
    if runtime_lock.is_none() {
        *runtime_lock = Some(
            tokio::runtime::Builder::new_multi_thread()
                .enable_all()
                .build()
                .unwrap(),
        );
    }
    let rt = runtime_lock.as_ref().unwrap();

    let (tx, rx) = tokio::sync::oneshot::channel::<()>();
    *ENGINE_STOP_TX.lock().unwrap() = Some(tx);

    let rt_handle = rt.handle().clone();

    let old_thread = MAIN_ENGINE_THREAD.lock().unwrap().take();

    let new_thread = thread::spawn(move || {
        if let Some(handle) = old_thread {
            let _ = handle.join();
        }
        rt_handle.block_on(async {
            use crate::android_camera::AndroidCamera;
            use olive_engine::CameraTrait;
            use olive_engine::control_server;
            use olive_engine::engine::{Engine, EngineConfig};
            use std::sync::Arc;

            let mut cam = AndroidCamera::new(Some(internal_camera_id_str));
            if let Err(e) = cam.start_capture() {
                log::error!("Failed to start Android camera: {}", e);
                return;
            }
            let camera: std::sync::Arc<dyn CameraTrait> = Arc::new(cam);
            *CAMERA.lock().unwrap() = Some(camera.clone());

            let sigma_val = if ext_type == olive_engine::engine::Extractor::Cedar {
                Some(10.0)
            } else {
                Some(5.0)
            };
            let config = EngineConfig {
                gain: None,
                target_centroids: 20,
                database_path: "default_database.npz".to_string(),
                isp: false,
                bg_mode: olive_engine::engine::BgMode::Line,
                extractor_type: ext_type,
                crop: None,
                downsample: false,
                bypass_calibration: true,
                noise_filter: true,
                sigma: sigma_val,
                initial_fov: camera.get_config().and_then(|c| c.initial_fov),
            };
            let client_connected = Arc::new(std::sync::atomic::AtomicBool::new(false));

            let mut initial_state = olive_engine::engine::EngineStateData::default();
            if latitude >= -90.0 && latitude <= 90.0 && longitude >= -180.0 && longitude <= 180.0 {
                initial_state.client_latitude = Some(latitude);
                initial_state.client_longitude = Some(longitude);
            }
            if time_ms > 0 {
                use chrono::TimeZone;
                initial_state.client_date = chrono::Utc.timestamp_millis_opt(time_ms).single();
            }

            let engine_state = Arc::new(std::sync::RwLock::new(initial_state));

            let server_camera = camera.clone();
            let server_client_connected = client_connected.clone();
            let server_engine_state = engine_state.clone();

            *crate::ENGINE_STATE.lock().unwrap() = Some(engine_state.clone());

            let server_handle = tokio::spawn(async move {
                control_server::start_server(
                    server_camera,
                    server_client_connected,
                    server_engine_state,
                )
                .await;
            });

            use crate::android_imu::AndroidImu;
            use olive_engine::imu::Imu;

            let imu_arc = if use_imu {
                let mut imu_dev = AndroidImu::new();
                if imu_dev.init().is_ok() {
                    if let Ok(imu) = Imu::start(imu_dev) {
                        Some(Arc::new(imu))
                    } else {
                        None
                    }
                } else {
                    None
                }
            } else {
                None
            };

            let mut telescope_runtime_lock = TELESCOPE_RUNTIME.lock().unwrap();
            if telescope_runtime_lock.is_none() {
                *telescope_runtime_lock = Some(
                    tokio::runtime::Builder::new_multi_thread()
                        .worker_threads(1)
                        .enable_all()
                        .build()
                        .unwrap(),
                );
            }
            let telescope_rt = telescope_runtime_lock.as_ref().unwrap();

            let mut engine = Engine::new(
                camera,
                config,
                client_connected,
                engine_state,
                imu_arc,
                Some(telescope_rt.handle().clone()),
            );
            let is_running_clone = engine.is_running.clone();
            let telescope_handle = engine.telescope_server_handle.take(); // We now take ownership of this handle

            // Run engine in a separate thread since it might block
            let engine_thread = std::thread::spawn(move || {
                let handles = engine.start();
                let _ = handles.0.join();
                let _ = handles.1.join();
            });

            // Wait for stop signal
            let _ = rx.await;
            is_running_clone.store(false, std::sync::atomic::Ordering::Relaxed);
            server_handle.abort();
            let _ = server_handle.await;

            if let Some(handle) = telescope_handle {
                handle.abort();
                let _ = handle.await;
            }

            let _ = engine_thread.join();
        });
    });

    *MAIN_ENGINE_THREAD.lock().unwrap() = Some(new_thread);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_MainActivity_stopEngine(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Some(tx) = ENGINE_STOP_TX.lock().unwrap().take() {
        let _ = tx.send(());
    }

    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        cam.stop_capture();
    }

    // Clear globals immediately so UI doesn't see old data
    *CAMERA.lock().unwrap() = None;
    *ENGINE_STATE.lock().unwrap() = None;

    // We join here because Kotlin will now call this from a background thread.
    // This ensures hardware is released before the USB connection is closed.
    if let Some(handle) = MAIN_ENGINE_THREAD.lock().unwrap().take() {
        let _ = handle.join();
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_MainActivity_setNativeCameraExposure(
    _env: JNIEnv,
    _class: JClass,
    exposure_us: jint,
) {
    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        cam.set_exposure(exposure_us);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_MainActivity_setNativeCameraGain(
    _env: JNIEnv,
    _class: JClass,
    gain: jni::sys::jfloat,
) {
    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        cam.set_gain(gain);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_MainActivity_setNativeCameraFocus(
    _env: JNIEnv,
    _class: JClass,
    distance: jni::sys::jfloat,
) {
    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        cam.set_focus(distance);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_MainActivity_setNativeCameraNoiseReduction(
    _env: JNIEnv,
    _class: JClass,
    mode: jint,
) {
    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        cam.set_noise_reduction(mode as u8);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_MainActivity_getMinNativeCameraGain(
    _env: JNIEnv,
    _class: JClass,
) -> jni::sys::jfloat {
    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        cam.get_min_gain()
    } else {
        100.0
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_MainActivity_getNativeCameraGain(
    _env: JNIEnv,
    _class: JClass,
) -> jni::sys::jfloat {
    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        cam.get_gain()
    } else {
        100.0
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_MainActivity_getMaxNativeCameraGain(
    _env: JNIEnv,
    _class: JClass,
) -> jni::sys::jfloat {
    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        cam.get_max_gain()
    } else {
        3200.0
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_MainActivity_getNativeCameraExposure(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        {
            let e = cam.get_exposure();
            log::info!("getNativeCameraExposure returning {}", e);
            e
        }
    } else {
        1_000_000
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_MainActivity_getLatestFrame(
    env: JNIEnv,
    _class: JClass,
) -> jni::sys::jbyteArray {
    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        if let Some(img) = cam.get_last_capture() {
            let width = img.width as usize;
            let height = img.height as usize;
            let data = &img.data;

            // Downsample for preview. Target roughly 640-800 pixels width.
            let step = if width > 1600 {
                4
            } else if width > 800 {
                2
            } else {
                1
            };
            let target_width = width / step;
            let target_height = height / step;
            let mut downsampled = vec![0u8; target_width * target_height];

            for y in 0..target_height {
                let src_y = y * step;
                let dst_row = y * target_width;
                let src_row = src_y * width;
                for x in 0..target_width {
                    let src_x = x * step;
                    let src_idx = src_row + src_x;
                    if src_idx < data.len() {
                        downsampled[dst_row + x] = data[src_idx];
                    }
                }
            }

            let mut result = Vec::with_capacity(downsampled.len() + 8);
            result.extend_from_slice(&(target_width as u32).to_le_bytes());
            result.extend_from_slice(&(target_height as u32).to_le_bytes());
            result.extend_from_slice(&downsampled);

            let array = env.new_byte_array(result.len() as jni::sys::jsize).unwrap();
            unsafe {
                let i8_slice =
                    std::slice::from_raw_parts(result.as_ptr() as *const i8, result.len());
                env.set_byte_array_region(&array, 0, i8_slice).unwrap();
            }
            return array.into_raw();
        }
    }
    std::ptr::null_mut()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_MainActivity_setEnginePaused(
    _env: JNIEnv,
    _class: JClass,
    paused: jni::sys::jboolean,
) {
    if let Some(state_arc) = ENGINE_STATE.lock().unwrap().as_ref() {
        let mut state = state_arc.write().unwrap();
        state.paused = paused != 0;
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_MainActivity_getAvailableInternalCameras(
    env: JNIEnv,
    _class: JClass,
) -> jni::sys::jstring {
    use crate::android_camera::AndroidCamera;
    let json_str = AndroidCamera::get_available_cameras();
    env.new_string(json_str).unwrap().into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_MainActivity_getEngineStats(
    env: JNIEnv,
    _class: JClass,
) -> jni::sys::jstring {
    let state_lock = ENGINE_STATE.lock().unwrap();
    let json_str = if let Some(state) = state_lock.as_ref() {
        if let Ok(guard) = state.read() {
            serde_json::to_string(&*guard).unwrap_or_else(|_| "{}".to_string())
        } else {
            "{}".to_string()
        }
    } else {
        "{}".to_string()
    };

    env.new_string(json_str).unwrap().into_raw()
}
