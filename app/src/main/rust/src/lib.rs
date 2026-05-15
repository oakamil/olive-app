use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::jint;
use std::ptr;
use std::sync::Mutex;
use std::thread;
use olive_engine::imu::ImuDevice;

pub mod android_camera;
pub mod android_imu;

static RUNTIME: Mutex<Option<tokio::runtime::Runtime>> = Mutex::new(None);
static ENGINE_STOP_TX: Mutex<Option<tokio::sync::oneshot::Sender<()>>> = Mutex::new(None);
static ENGINE_STATE: Mutex<Option<std::sync::Arc<std::sync::RwLock<olive_engine::engine::EngineStateData>>>> = Mutex::new(None);
static CAMERA: Mutex<Option<std::sync::Arc<dyn olive_engine::CameraTrait>>> = Mutex::new(None);

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
    _env: JNIEnv,
    _class: JClass,
    fd: jint,
    imu_enabled: jni::sys::jboolean,
) {
    let fd = fd as i32;
    let use_imu = imu_enabled != 0;

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

    thread::spawn(move || {
        rt_handle.block_on(async {
            use olive_engine::CameraTrait;
            use olive_engine::control_server;
            use olive_engine::engine::{Engine, EngineConfig};
            use olive_engine::uvc_camera::Camera;
            use std::sync::Arc;

            // 30 FPS, MJPEG true
            let mut cam = Camera::with_fd(30, true, None, Some(fd));
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
                bg_mode: olive_engine::engine::BgMode::Line,
                extractor_type: olive_engine::engine::Extractor::Sequential,
            };

            let client_connected = Arc::new(std::sync::atomic::AtomicBool::new(false));
            let engine_state = Arc::new(std::sync::RwLock::new(
                olive_engine::engine::EngineStateData::default(),
            ));

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

            let mut engine = Engine::new(camera, config, client_connected, engine_state, imu_arc);
            let is_running_clone = engine.is_running.clone();
            let telescope_handle = engine.telescope_server_handle.take(); // We now take ownership of this handle

            // Run engine in a separate thread since it might block
            std::thread::spawn(move || {
                engine.start();
            });

            // Wait for stop signal
            let _ = rx.await;
            is_running_clone.store(false, std::sync::atomic::Ordering::Relaxed);
            server_handle.abort();
            if let Some(handle) = telescope_handle {
                handle.abort();
            }
        });
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_MainActivity_startNativeCameraEngine(
    _env: JNIEnv,
    _class: JClass,
    imu_enabled: jni::sys::jboolean,
) {
    let use_imu = imu_enabled != 0;

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

    thread::spawn(move || {
        rt_handle.block_on(async {
            use crate::android_camera::AndroidCamera;
            use olive_engine::CameraTrait;
            use olive_engine::control_server;
            use olive_engine::engine::{Engine, EngineConfig};
            use std::sync::Arc;

            let mut cam = AndroidCamera::new();
            if let Err(e) = cam.start_capture() {
                log::error!("Failed to start Android camera: {}", e);
                return;
            }
            let camera: std::sync::Arc<dyn CameraTrait> = Arc::new(cam);
            *CAMERA.lock().unwrap() = Some(camera.clone());

            let config = EngineConfig {
                gain: None,
                target_centroids: 20,
                database_path: "default_database.npz".to_string(),
                isp: false,
                bg_mode: olive_engine::engine::BgMode::Line,
                extractor_type: olive_engine::engine::Extractor::Sequential,
            };

            let client_connected = Arc::new(std::sync::atomic::AtomicBool::new(false));
            let engine_state = Arc::new(std::sync::RwLock::new(
                olive_engine::engine::EngineStateData::default(),
            ));

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

            let mut engine = Engine::new(camera, config, client_connected, engine_state, imu_arc);
            let is_running_clone = engine.is_running.clone();
            let telescope_handle = engine.telescope_server_handle.take(); // We now take ownership of this handle

            // Run engine in a separate thread since it might block
            std::thread::spawn(move || {
                engine.start();
            });

            // Wait for stop signal
            let _ = rx.await;
            is_running_clone.store(false, std::sync::atomic::Ordering::Relaxed);
            server_handle.abort();
            if let Some(handle) = telescope_handle {
                handle.abort();
            }
        });
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_MainActivity_stopEngine(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Some(tx) = ENGINE_STOP_TX.lock().unwrap().take() {
        let _ = tx.send(());
    }
    *CAMERA.lock().unwrap() = None;
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
pub extern "system" fn Java_com_oakamil_olive_MainActivity_getLatestFrame(
    env: JNIEnv,
    _class: JClass,
) -> jni::sys::jbyteArray {
    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        if let Some(img) = cam.get_last_capture() {
            let width = img.width;
            let height = img.height;
            let data = &img.data;
            
            // Downsample by 8x for fast preview
            let step = 8;
            let target_width = width / step;
            let target_height = height / step;
            let mut downsampled = Vec::with_capacity((target_width * target_height) as usize);
            
            for y in 0..target_height {
                for x in 0..target_width {
                    let src_idx = (y * step * width) + (x * step);
                    if (src_idx as usize) < data.len() {
                        downsampled.push(data[src_idx as usize]);
                    } else {
                        downsampled.push(0);
                    }
                }
            }

            let mut result = Vec::with_capacity(downsampled.len() + 8);
            result.extend_from_slice(&target_width.to_le_bytes());
            result.extend_from_slice(&target_height.to_le_bytes());
            result.extend_from_slice(&downsampled);

            let array = env.new_byte_array(result.len() as jni::sys::jsize).unwrap();
            unsafe {
                env.set_byte_array_region(&array, 0, std::mem::transmute(result.as_slice()))
                    .unwrap();
            }
            return array.into_raw();
        }
    }
    ptr::null_mut()
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
