use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::jint;
use olive_imu::ImuDevice;
use std::sync::Mutex;
use std::thread;

pub mod android_camera;
pub mod android_imu;
pub mod android_uvc_camera;

static RUNTIME: Mutex<Option<tokio::runtime::Runtime>> = Mutex::new(None);
static TELESCOPE_RUNTIME: Mutex<Option<tokio::runtime::Runtime>> = Mutex::new(None);
static ENGINE_STOP_TX: Mutex<Option<tokio::sync::oneshot::Sender<()>>> = Mutex::new(None);
static ENGINE_STATE: Mutex<
    Option<std::sync::Arc<std::sync::RwLock<olive_engine::engine::EngineStateData>>>,
> = Mutex::new(None);
static CAMERA: Mutex<Option<std::sync::Arc<dyn olive_engine::CameraTrait>>> = Mutex::new(None);
static UVC_CAMERA_RECEIVER: Mutex<Option<std::sync::Arc<android_uvc_camera::AndroidUvcCamera>>> =
    Mutex::new(None);
static MAIN_ENGINE_THREAD: Mutex<Option<std::thread::JoinHandle<()>>> = Mutex::new(None);
static LX200_CONTROLLER: Mutex<
    Option<std::sync::Arc<std::sync::RwLock<olive_engine::telescope_server::Lx200Controller>>>,
> = Mutex::new(None);
static ALPACA_FD_TX: Mutex<Option<tokio::sync::mpsc::Sender<i32>>> = Mutex::new(None);
static ALPACA_V6_FD_TX: Mutex<Option<tokio::sync::mpsc::Sender<i32>>> = Mutex::new(None);

struct JniStorage {
    jvm: jni::JavaVM,
    settings_mgr: jni::objects::GlobalRef,
}

impl olive_imu::PersistentStorage for JniStorage {
    fn get(&self, key: &str) -> Option<String> {
        if let Ok(mut env) = self.jvm.attach_current_thread() {
            if let Ok(jkey) = env.new_string(key) {
                match env.call_method(
                    &self.settings_mgr,
                    "getString",
                    "(Ljava/lang/String;)Ljava/lang/String;",
                    &[(&jkey).into()],
                ) {
                    Ok(result) => {
                        if let Ok(jstr) = result.l() {
                            if !jstr.is_null() {
                                return env
                                    .get_string(&jni::objects::JString::from(jstr))
                                    .ok()
                                    .map(|s| s.into());
                            }
                        }
                    }
                    Err(e) => {
                        log::error!("JniStorage::get call_method error: {:?}", e);
                        if let Ok(true) = env.exception_check() {
                            let _ = env.exception_clear();
                        }
                    }
                }
            }
        } else {
            log::error!("JniStorage::get attach_current_thread failed!");
        }
        None
    }

    fn set(&self, key: &str, value: &str) {
        if let Ok(mut env) = self.jvm.attach_current_thread() {
            if let (Ok(jkey), Ok(jval)) = (env.new_string(key), env.new_string(value)) {
                match env.call_method(
                    &self.settings_mgr,
                    "setString",
                    "(Ljava/lang/String;Ljava/lang/String;)V",
                    &[(&jkey).into(), (&jval).into()],
                ) {
                    Ok(_) => {}
                    Err(e) => {
                        log::error!("JniStorage::set call_method error: {:?}", e);
                        if let Ok(true) = env.exception_check() {
                            let _ = env.exception_clear();
                        }
                    }
                }
            }
        } else {
            log::error!("JniStorage::set attach_current_thread failed!");
        }
    }

    fn remove(&self, key: &str) {
        if let Ok(mut env) = self.jvm.attach_current_thread() {
            if let Ok(jkey) = env.new_string(key) {
                match env.call_method(
                    &self.settings_mgr,
                    "removeString",
                    "(Ljava/lang/String;)V",
                    &[(&jkey).into()],
                ) {
                    Ok(_) => {}
                    Err(e) => {
                        log::error!("JniStorage::remove call_method error: {:?}", e);
                        if let Ok(true) = env.exception_check() {
                            let _ = env.exception_clear();
                        }
                    }
                }
            }
        } else {
            log::error!("JniStorage::remove attach_current_thread failed!");
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_initJni(
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
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_startNativeCameraEngine(
    mut env: JNIEnv,
    _class: JClass,
    motion_sensor_mode: jint,
    extractor: jni::objects::JString,
    sensor_mode: jni::objects::JString,
    internal_camera_id: jni::objects::JString,
    downsample_mode: jni::sys::jint,
    latitude: jni::sys::jdouble,
    longitude: jni::sys::jdouble,
    settings_manager: jni::objects::JObject,
    auto_mode: jni::sys::jboolean,
) {
    let jvm = env.get_java_vm().unwrap();
    let settings_mgr = env.new_global_ref(&settings_manager).unwrap();
    let storage: Option<std::sync::Arc<dyn olive_imu::PersistentStorage>> =
        Some(std::sync::Arc::new(JniStorage { jvm, settings_mgr }));

    let motion_sensor_mode = motion_sensor_mode as i32;
    let internal_camera_id_str: String = env.get_string(&internal_camera_id).unwrap().into();
    let sensor_mode_str: String = env.get_string(&sensor_mode).unwrap().into();
    let extractor_str: String = env.get_string(&extractor).unwrap().into();
    let ext_type = if extractor_str == "Cedar" {
        olive_engine::engine::Extractor::Cedar
    } else {
        olive_engine::engine::Extractor::Approximate
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

    if let Some(old_tx) = ENGINE_STOP_TX.lock().unwrap().replace(tx) {
        let _ = old_tx.send(());
    }

    let rt_handle = rt.handle().clone();

    let old_thread = MAIN_ENGINE_THREAD.lock().unwrap().take();

    if let Some(handle) = old_thread {
        let _ = handle.join();
    }

    let (ready_tx, ready_rx) = std::sync::mpsc::channel();

    let new_thread = thread::spawn(move || {
        rt_handle.block_on(async {
            use crate::android_camera::AndroidCamera;
            use olive_engine::CameraTrait;
            use olive_engine::control_server;
            use olive_engine::engine::{Engine, EngineConfig};
            use std::sync::Arc;

            let camera: std::sync::Arc<dyn CameraTrait> = if internal_camera_id_str == "demo" {
                use olive_engine::dummy_camera::DummyCamera;
                let mut cam = DummyCamera::new();
                if let Err(e) = cam.start_capture() {
                    log::error!("Failed to start demo camera: {}", e);
                    return;
                }
                Arc::new(cam)
            } else {
                let mut cam = AndroidCamera::new(
                    Some(internal_camera_id_str.clone()),
                    Some(sensor_mode_str.clone()),
                    Some(1.0f32),
                );
                if let Err(e) = cam.start_capture() {
                    log::error!("Failed to start Android camera: {}", e);
                    return;
                }
                Arc::new(cam)
            };
            *CAMERA.lock().unwrap() = Some(camera.clone());

            let config = EngineConfig {
                gain: None,
                target_centroids: 20,
                database_path: "default_database.npz".to_string(),
                isp: false,
                bg_mode: olive_engine::engine::BgMode::Block,
                extractor_type: ext_type,
                crop: None,
                downsample: downsample_mode as u8,
                auto_mode: auto_mode != 0 && internal_camera_id_str != "demo",
                is_adaptive_camera: internal_camera_id_str != "demo",
                noise_filter: true,
                initial_fov: camera.get_config().and_then(|c| c.initial_fov),
                use_observer_location: internal_camera_id_str != "demo",
            };
            let client_connected = Arc::new(std::sync::atomic::AtomicBool::new(false));

            let mut initial_state = olive_engine::engine::EngineStateData::default();
            if latitude >= -90.0 && latitude <= 90.0 && longitude >= -180.0 && longitude <= 180.0 {
                initial_state.client_latitude = Some(latitude);
                initial_state.client_longitude = Some(longitude);
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

            let imu_type = if motion_sensor_mode != 0 {
                let mut imu_dev = AndroidImu::new(motion_sensor_mode);
                if imu_dev.init().is_ok() {
                    Some(olive_solve::ImuType::Custom(Box::new(imu_dev)))
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
                        .enable_all()
                        .build()
                        .unwrap(),
                );
            }
            let telescope_rt = telescope_runtime_lock.as_ref().unwrap();

            let (alpaca_fd_tx, alpaca_fd_rx) = tokio::sync::mpsc::channel::<i32>(16);
            *ALPACA_FD_TX.lock().unwrap() = Some(alpaca_fd_tx);

            let (alpaca_v6_fd_tx, alpaca_v6_fd_rx) = tokio::sync::mpsc::channel::<i32>(16);
            *ALPACA_V6_FD_TX.lock().unwrap() = Some(alpaca_v6_fd_tx);

            let mut engine = Engine::new(
                camera,
                config,
                client_connected,
                engine_state,
                imu_type,
                Some(telescope_rt.handle().clone()),
                storage,
                Some(alpaca_fd_rx),
                Some(alpaca_v6_fd_rx),
            )
            .await;

            if latitude >= -90.0 && latitude <= 90.0 && longitude >= -180.0 && longitude <= 180.0 {
                engine
                    .fused_solver
                    .set_observer_location(latitude, longitude);
            }

            let _ = engine.fused_solver.start_imu();
            *LX200_CONTROLLER.lock().unwrap() = Some(engine.lx200_controller.clone());
            let is_running_clone = engine.is_running.clone();
            let telescope_handle = engine.telescope_server_handle.take(); // We now take ownership of this handle

            // Run engine in a separate thread since it might block
            let engine_thread = std::thread::spawn(move || {
                let handles = engine.start();
                let _ = handles.0.join();
                let _ = handles.1.join();
            });

            let _ = ready_tx.send(());

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

    let _ = ready_rx.recv();
    *MAIN_ENGINE_THREAD.lock().unwrap() = Some(new_thread);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_stopEngine(_env: JNIEnv, _class: JClass) {
    if let Some(tx) = ENGINE_STOP_TX.lock().unwrap().take() {
        let _ = tx.send(());
    }

    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        cam.stop_capture();
    }

    // Clear globals immediately so UI doesn't see old data
    *CAMERA.lock().unwrap() = None;
    *UVC_CAMERA_RECEIVER.lock().unwrap() = None;
    *ENGINE_STATE.lock().unwrap() = None;

    // We join here because Kotlin will now call this from a background thread.
    // This ensures hardware is released before the USB connection is closed.
    if let Some(handle) = MAIN_ENGINE_THREAD.lock().unwrap().take() {
        let _ = handle.join();
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_setNativeCameraExposure(
    _env: JNIEnv,
    _class: JClass,
    exposure_us: jint,
) {
    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        cam.set_exposure(exposure_us);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_setNativeCameraStacking(
    _env: JNIEnv,
    _class: JClass,
    stack_count: jint,
) {
    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        cam.set_stack_count(stack_count as usize);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_updateUvcStacking(
    _env: JNIEnv,
    _class: JClass,
    stack_count: jint,
) {
    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        cam.set_stack_count(stack_count as usize);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_setNativeCameraGain(
    _env: JNIEnv,
    _class: JClass,
    gain: jni::sys::jfloat,
) {
    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        cam.set_gain(gain);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_setNativeCameraFocus(
    _env: JNIEnv,
    _class: JClass,
    distance: jni::sys::jfloat,
) {
    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        cam.set_focus(distance);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_setNativeCameraNoiseReduction(
    _env: JNIEnv,
    _class: JClass,
    mode: jint,
) {
    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        cam.set_noise_reduction(mode as u8);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_getNativeCameraBrightnessRange<'local>(
    env: JNIEnv<'local>,
    _class: JClass,
) -> jni::sys::jintArray {
    let result = if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        cam.get_brightness_range()
    } else {
        None
    };

    if let Some((min, max, current)) = result {
        let arr = env.new_int_array(3).unwrap();
        env.set_int_array_region(&arr, 0, &[min, max, current])
            .unwrap();
        arr.into_raw()
    } else {
        std::ptr::null_mut()
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_setNativeCameraBrightness(
    _env: JNIEnv,
    _class: JClass,
    brightness: jint,
) {
    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        cam.set_brightness(brightness);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_setNativeCameraZoomRatio(
    _env: JNIEnv,
    _class: JClass,
    zoom: jni::sys::jfloat,
) {
    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        cam.set_zoom(zoom);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_getNativeCameraGainRange(
    env: JNIEnv,
    _class: JClass,
) -> jni::sys::jfloatArray {
    let (min, max, default_val) = if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        cam.get_gain_range()
    } else {
        (100.0, 3200.0, 3200.0)
    };

    let array = env.new_float_array(3).unwrap();
    env.set_float_array_region(&array, 0, &[min, max, default_val])
        .unwrap();
    array.into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_getNativeCameraGain(
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
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_getNativeCameraExposure(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        cam.get_exposure()
    } else {
        500_000
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_getLatestFrameInto(
    env: JNIEnv,
    _class: JClass,
    buffer: jni::objects::JByteBuffer,
    last_timestamp_ms: jni::sys::jlong,
) -> jni::sys::jlong {
    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        if let Some(img) = cam.get_last_capture() {
            let img_ts_ms = img
                .timestamp
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as i64;

            if img_ts_ms <= last_timestamp_ms {
                return img_ts_ms; // No new frame
            }

            let width = img.width as usize;
            let height = img.height as usize;
            let data = &img.data;

            // ARGB requires 4 bytes per pixel + 8 bytes header
            let req_len = (width * height * 4) + 8;
            if let Ok(capacity) = env.get_direct_buffer_capacity(&buffer) {
                if capacity >= req_len {
                    if let Ok(buf_ptr) = env.get_direct_buffer_address(&buffer) {
                        let slice =
                            unsafe { std::slice::from_raw_parts_mut(buf_ptr as *mut u8, capacity) };
                        slice[0..4].copy_from_slice(&(width as u32).to_le_bytes());
                        slice[4..8].copy_from_slice(&(height as u32).to_le_bytes());

                        let mut dest_idx = 8;
                        for &gray in data {
                            slice[dest_idx] = gray; // R
                            slice[dest_idx + 1] = gray; // G
                            slice[dest_idx + 2] = gray; // B
                            slice[dest_idx + 3] = 255; // A
                            dest_idx += 4;
                        }
                        return img_ts_ms;
                    }
                } else {
                    log::error!(
                        "Provided ByteBuffer is too small. Need {}, got {}",
                        req_len,
                        capacity
                    );
                }
            }
        }
    }
    0
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_setEnginePaused(
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
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_getAvailableInternalCameras(
    env: JNIEnv,
    _class: JClass,
) -> jni::sys::jstring {
    use crate::android_camera::AndroidCamera;
    let json_str = AndroidCamera::get_available_cameras();
    env.new_string(json_str).unwrap().into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_getEngineStats(
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

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_setEngineSigma(
    _env: JNIEnv,
    _class: JClass,
    sigma: jni::sys::jfloat,
) {
    if let Some(engine_state_arc) = ENGINE_STATE.lock().unwrap().as_ref() {
        if let Ok(mut state) = engine_state_arc.write() {
            state.current_sigma = Some(sigma as f32);
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_setEngineStrictMatch(
    _env: JNIEnv,
    _class: JClass,
    strict: jni::sys::jboolean,
) {
    if let Some(engine_state_arc) = ENGINE_STATE.lock().unwrap().as_ref() {
        if let Ok(mut state) = engine_state_arc.write() {
            state.strict_match = strict != 0;
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_handleAlpacaConnection(
    _env: JNIEnv,
    _class: JClass,
    fd: jint,
) {
    if let Some(tx) = ALPACA_FD_TX.lock().unwrap().as_ref() {
        if tx.try_send(fd).is_err() {
            unsafe {
                use std::os::unix::io::FromRawFd;
                drop(std::fs::File::from_raw_fd(fd));
            }
        }
    } else {
        unsafe {
            use std::os::unix::io::FromRawFd;
            drop(std::fs::File::from_raw_fd(fd));
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_handleAlpacaConnectionV6(
    _env: JNIEnv,
    _class: JClass,
    fd: jint,
) {
    if let Some(tx) = ALPACA_V6_FD_TX.lock().unwrap().as_ref() {
        if tx.try_send(fd).is_err() {
            unsafe {
                use std::os::unix::io::FromRawFd;
                drop(std::fs::File::from_raw_fd(fd));
            }
        }
    } else {
        unsafe {
            use std::os::unix::io::FromRawFd;
            drop(std::fs::File::from_raw_fd(fd));
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_getNativeCameraBrightness(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
) -> jni::sys::jint {
    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        cam.get_brightness().unwrap_or(0)
    } else {
        0
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_getNativeCameraZoomRatio(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
) -> jni::sys::jfloat {
    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        cam.get_zoom().unwrap_or(1.0)
    } else {
        1.0
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_getNativeCameraFocus(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
) -> jni::sys::jfloat {
    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        cam.get_focus().unwrap_or(0.0)
    } else {
        0.0
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_getNativeCameraSupportedHotPixelModes<
    'local,
>(
    env: jni::JNIEnv<'local>,
    _class: jni::objects::JClass<'local>,
) -> jni::sys::jintArray {
    let modes = crate::android_camera::SUPPORTED_HOT_PIXEL_MODES
        .lock()
        .unwrap();
    let modes_i32: Vec<i32> = modes.iter().map(|&x| x as i32).collect();
    let array = env.new_int_array(modes_i32.len() as i32).unwrap();
    if !modes_i32.is_empty() {
        env.set_int_array_region(&array, 0, &modes_i32).unwrap();
    }
    array.into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_setNativeCameraHotPixelMode(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
    mode: jni::sys::jint,
) {
    crate::android_camera::CURRENT_HOT_PIXEL_MODE
        .store(mode as u32, std::sync::atomic::Ordering::Relaxed);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_getNativeCameraHotPixelMode(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
) -> jni::sys::jint {
    crate::android_camera::CURRENT_HOT_PIXEL_MODE.load(std::sync::atomic::Ordering::Relaxed)
        as jni::sys::jint
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_processTelescopeData<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    data: jni::objects::JByteArray<'local>,
) -> jni::sys::jbyteArray {
    let controller_opt = LX200_CONTROLLER.lock().unwrap().clone();
    if let Some(controller_lock) = controller_opt {
        if let Ok(data_bytes) = env.convert_byte_array(&data) {
            let mut controller = controller_lock.write().unwrap();
            let response = controller.process_client_data(&data_bytes);
            if !response.is_empty() {
                if let Ok(j_array) = env.byte_array_from_slice(&response) {
                    return j_array.into_raw();
                }
            }
        }
    }
    std::ptr::null_mut()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_resetTelescopeServer<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) {
    if let Some(_engine_state_arc) = ENGINE_STATE.lock().unwrap().clone() {
        // We just reset the buffer and state of the controller, since it's a new connection
        let controller_opt = LX200_CONTROLLER.lock().unwrap().clone();
        if let Some(controller_lock) = controller_opt {
            let mut controller = controller_lock.write().unwrap();
            controller.client_buffer.clear();
            controller.is_stellarium = false;
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_getPlanets(
    mut env: JNIEnv,
    _class: JClass,
    bsp_path: jni::objects::JString,
    time_ms: jni::sys::jlong,
    latitude: jni::sys::jdouble,
    longitude: jni::sys::jdouble,
) -> jni::sys::jstring {
    use chrono::TimeZone;
    use serde::Serialize;
    use starfield::jplephem::kernel::SpiceKernel;
    use starfield::time::Timescale;

    #[derive(Serialize)]
    struct PlanetObj {
        id: i32,
        #[serde(rename = "catalogLabel")]
        catalog_label: String,
        #[serde(rename = "catalogEntry")]
        catalog_entry: String,
        ra: f64,
        dec: f64,
        constellation: Option<String>,
        #[serde(rename = "objectType")]
        object_type: String,
        #[serde(rename = "broadCategory")]
        broad_category: String,
        magnitude: Option<f64>,
        #[serde(rename = "surfaceBrightness")]
        surface_brightness: Option<f64>,
        #[serde(rename = "angularSize")]
        angular_size: Option<String>,
        #[serde(rename = "commonName")]
        common_name: Option<String>,
        description: Option<String>,
        copyright: Option<String>,
    }

    let bsp_path_str: String = env
        .get_string(&bsp_path)
        .map(|s| s.into())
        .unwrap_or_default();

    let result_json = std::panic::catch_unwind(|| {
        let mut kernel = SpiceKernel::open(&bsp_path_str).map_err(|e| e.to_string())?;
        let ts = Timescale::default();

        let dt = chrono::Utc
            .timestamp_millis_opt(time_ms)
            .single()
            .ok_or("Invalid time")?;
        use chrono::{Datelike, Timelike};
        let time = ts.utc((
            dt.year(),
            dt.month(),
            dt.day(),
            dt.hour(),
            dt.minute(),
            dt.second() as f64 + dt.nanosecond() as f64 / 1_000_000_000.0,
        ));
        let observer_loc = starfield::toposlib::WGS84.latlon(latitude, longitude, 0.0);
        let earth = observer_loc
            .at(&time, &mut kernel)
            .map_err(|e| e.to_string())?;

        let mut planets = vec![];
        let targets = [
            ("MERCURY BARYCENTER", "Mercury", 1),
            ("VENUS BARYCENTER", "Venus", 2),
            ("MOON", "Moon", 3),
            ("MARS BARYCENTER", "Mars", 4),
            ("JUPITER BARYCENTER", "Jupiter", 5),
            ("SATURN BARYCENTER", "Saturn", 6),
            ("URANUS BARYCENTER", "Uranus", 7),
            ("NEPTUNE BARYCENTER", "Neptune", 8),
            ("PLUTO BARYCENTER", "Pluto", 9),
        ];

        for (name, common_name, id) in targets {
            if let Ok(astrometric) = earth.observe(name, &mut kernel, &time) {
                let (ra_hours, dec_deg, _dist) = astrometric.radec(None);

                planets.push(PlanetObj {
                    id,
                    catalog_label: "Planet".to_string(),
                    catalog_entry: common_name.to_string(),
                    ra: ra_hours * 15.0, // convert hours to degrees
                    dec: dec_deg,
                    constellation: None,
                    object_type: "Planet".to_string(),
                    broad_category: "planet".to_string(),
                    magnitude: starfield::magnitudelib::planetary_magnitude(&astrometric, &time)
                        .ok(),
                    surface_brightness: None,
                    angular_size: None,
                    common_name: Some(common_name.to_string()),
                    description: Some(format!("The planet {}", common_name)),
                    copyright: None,
                });
            }
        }

        Ok::<Vec<PlanetObj>, String>(planets)
    });

    let json_str = match result_json {
        Ok(Ok(planets)) => serde_json::to_string(&planets).unwrap_or_else(|_| "[]".to_string()),
        Ok(Err(e)) => {
            log::error!("Planet calculation error: {}", e);
            "[]".to_string()
        }
        Err(_) => {
            log::error!("Planet calculation panicked");
            "[]".to_string()
        }
    };

    env.new_string(json_str).unwrap().into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_setGoToTarget(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
    ra: jni::sys::jdouble,
    dec: jni::sys::jdouble,
    epoch: jni::sys::jdouble,
) {
    if let Some(state_arc) = ENGINE_STATE.lock().unwrap().as_ref() {
        if let Ok(mut state) = state_arc.write() {
            state.requested_target_ra = Some(ra as f64);
            state.requested_target_dec = Some(dec as f64);
            state.requested_target_epoch = Some(epoch as f64);
            log::info!(
                "Target slew requested via JNI (EngineState): RA={}, Dec={}, Epoch={}",
                ra,
                dec,
                epoch
            );
        }
    } else {
        log::warn!("setGoToTarget called but ENGINE_STATE is not initialized");
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_clearGoToTarget(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
) {
    if let Some(state_arc) = ENGINE_STATE.lock().unwrap().as_ref() {
        if let Ok(mut state) = state_arc.write() {
            state.requested_target_ra = None;
            state.requested_target_dec = None;
            state.requested_target_epoch = None;
            state.target_ra = None;
            state.target_dec = None;
            state.requested_target_clear = true;
            log::info!("Target slew cleared via JNI");
        }
    } else {
        log::warn!("clearGoToTarget called but ENGINE_STATE is not initialized");
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_setAlignTargetPixel(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
    x: jni::sys::jdouble,
    y: jni::sys::jdouble,
) {
    if let Some(state_arc) = ENGINE_STATE.lock().unwrap().as_ref() {
        if let Ok(mut state) = state_arc.write() {
            state.pending_target_pixel_cmd = Some(olive_engine::engine::TargetPixelCommand::Set(
                x as f64, y as f64,
            ));
            log::info!("Align target pixel requested via JNI: ({}, {})", x, y);
        }
    } else {
        log::warn!("setAlignTargetPixel called but ENGINE_STATE is not initialized");
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_clearAlignTargetPixel(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
) {
    if let Some(state_arc) = ENGINE_STATE.lock().unwrap().as_ref() {
        if let Ok(mut state) = state_arc.write() {
            state.pending_target_pixel_cmd = Some(olive_engine::engine::TargetPixelCommand::Clear);
            log::info!("Clear align target pixel requested via JNI");
        }
    } else {
        log::warn!("clearAlignTargetPixel called but ENGINE_STATE is not initialized");
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_syncTelescope(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
) {
    if let Some(state_arc) = ENGINE_STATE.lock().unwrap().as_ref() {
        if let Ok(mut state) = state_arc.write() {
            state.requested_sync = true;
            log::info!("Sync telescope requested via JNI");
        }
    } else {
        log::warn!("syncTelescope called but ENGINE_STATE is not initialized");
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_syncTelescopeToTarget(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
    ra: jni::sys::jdouble,
    dec: jni::sys::jdouble,
) {
    if let Some(state_arc) = ENGINE_STATE.lock().unwrap().as_ref() {
        if let Ok(mut state) = state_arc.write() {
            state.requested_sync_coords = Some((ra as f64, dec as f64));
            state.requested_sync = true;
            log::info!(
                "Sync telescope to target ({}, {}) requested via JNI",
                ra,
                dec
            );
        }
    } else {
        log::warn!("syncTelescopeToTarget called but ENGINE_STATE is not initialized");
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_getAsteroids(
    mut env: jni::JNIEnv,
    _class: jni::objects::JClass,
    bsp_path: jni::objects::JString,
    time_ms: jni::sys::jlong,
    latitude: jni::sys::jdouble,
    longitude: jni::sys::jdouble,
    asteroids_json: jni::objects::JString,
) -> jni::sys::jstring {
    use chrono::TimeZone;
    use serde::{Deserialize, Serialize};
    use starfield::constants::{C_AUDAY, GM_SUN};
    use starfield::jplephem::kernel::SpiceKernel;
    use starfield::jplephem_ext::SpiceKernelExt;
    use starfield::positions::Position;
    use starfield::time::Timescale;

    #[derive(Deserialize)]
    struct AsteroidRecord {
        id: i32,
        catalog_id: Option<String>,
        common_name: Option<String>,
        h: f64,
        g: f64,
        epoch: f64,
        m: f64,
        peri: f64,
        node: f64,
        incl: f64,
        e: f64,
        a: f64,
    }

    #[derive(Serialize)]
    struct PlanetObj {
        id: i32,
        #[serde(rename = "catalogLabel")]
        catalog_label: String,
        #[serde(rename = "catalogEntry")]
        catalog_entry: String,
        ra: f64,
        dec: f64,
        constellation: Option<String>,
        #[serde(rename = "objectType")]
        object_type: String,
        #[serde(rename = "broadCategory")]
        broad_category: String,
        magnitude: Option<f64>,
        #[serde(rename = "surfaceBrightness")]
        surface_brightness: Option<f64>,
        #[serde(rename = "angularSize")]
        angular_size: Option<String>,
        #[serde(rename = "commonName")]
        common_name: Option<String>,
        description: Option<String>,
        copyright: Option<String>,
    }

    let bsp_path_str: String = env
        .get_string(&bsp_path)
        .map(|s| s.into())
        .unwrap_or_default();

    let json_str: String = env
        .get_string(&asteroids_json)
        .map(|s| s.into())
        .unwrap_or_default();

    let result_json = std::panic::catch_unwind(|| {
        let mut kernel = SpiceKernel::open(&bsp_path_str).map_err(|e| e.to_string())?;
        let ts = Timescale::default();

        let dt = chrono::Utc
            .timestamp_millis_opt(time_ms)
            .single()
            .ok_or("Invalid time")?;
        use chrono::{Datelike, Timelike};
        let time = ts.utc((
            dt.year(),
            dt.month(),
            dt.day(),
            dt.hour(),
            dt.minute(),
            dt.second() as f64 + dt.nanosecond() as f64 / 1_000_000_000.0,
        ));
        let observer_loc = starfield::toposlib::WGS84.latlon(latitude, longitude, 0.0);
        let earth = observer_loc
            .at(&time, &mut kernel)
            .map_err(|e| e.to_string())?;
        let sun = kernel.at("sun", &time).map_err(|e| e.to_string())?;

        let records: Vec<AsteroidRecord> = serde_json::from_str(&json_str).unwrap_or_default();
        let mut results = vec![];

        for rec in records {
            let orbit = starfield::keplerlib::mpcorb_orbit(
                rec.a,
                rec.e,
                rec.incl,
                rec.node,
                rec.peri,
                rec.m,
                &ts.tt_jd(rec.epoch, None),
                GM_SUN,
                rec.common_name.as_deref(),
            );

            let mut target_helio = orbit.at(&time);
            let mut target_bary_pos = target_helio.position + sun.position;
            let mut target_bary_vel = target_helio.velocity + sun.velocity;
            let mut distance_au = (target_bary_pos - earth.position).norm();
            let mut light_time0 = 0.0;

            for _ in 0..5 {
                let light_time = distance_au / C_AUDAY;
                let delta_t = light_time - light_time0;
                if delta_t.abs() < 1e-7 {
                    break;
                }
                let retarded_time = ts.tdb_jd(time.tdb() - light_time);
                target_helio = orbit.at(&retarded_time);
                let sun_ret = kernel
                    .at("sun", &retarded_time)
                    .unwrap_or_else(|_| sun.clone());
                target_bary_pos = target_helio.position + sun_ret.position;
                target_bary_vel = target_helio.velocity + sun_ret.velocity;
                distance_au = (target_bary_pos - earth.position).norm();
                light_time0 = light_time;
            }

            let vector = target_bary_pos - earth.position;
            let velocity = target_bary_vel - earth.velocity;

            let astrometric = Position::astrometric(vector, velocity, &earth, -1, light_time0);

            let (ra_hours, dec_deg, _) = astrometric.radec(None);

            // H-G magnitude
            let r_vec = -target_helio.position;
            let delta_vec = earth.position - target_bary_pos;
            let r = r_vec.norm();
            let delta = delta_vec.norm();
            let ph_ang = r_vec.angle(&delta_vec);
            let ta = (ph_ang / 2.0).tan();
            let phi1 = (-3.33 * ta.powf(0.63)).exp();
            let phi2 = (-1.87 * ta.powf(1.22)).exp();
            let mag = rec.h + 5.0 * (r * delta).log10()
                - 2.5 * ((1.0 - rec.g) * phi1 + rec.g * phi2).log10();

            results.push(PlanetObj {
                id: rec.id,
                catalog_label: "Asteroid".to_string(),
                catalog_entry: rec.catalog_id.unwrap_or_default(),
                ra: ra_hours * 15.0,
                dec: dec_deg,
                constellation: None,
                object_type: "Asteroid".to_string(),
                broad_category: "asteroid".to_string(),
                magnitude: Some(mag),
                surface_brightness: None,
                angular_size: None,
                common_name: rec.common_name.clone(),
                description: rec.common_name.map(|n| format!("The asteroid {}", n)),
                copyright: None,
            });
        }

        Ok::<Vec<PlanetObj>, String>(results)
    });

    let output = match result_json {
        Ok(Ok(planets)) => serde_json::to_string(&planets).unwrap_or_else(|_| "[]".to_string()),
        Ok(Err(e)) => {
            log::error!("Asteroids calculation error: {}", e);
            "[]".to_string()
        }
        Err(_) => {
            log::error!("Asteroids calculation panicked");
            "[]".to_string()
        }
    };

    env.new_string(output)
        .expect("Couldn't create java string!")
        .into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_getComets(
    mut env: jni::JNIEnv,
    _class: jni::objects::JClass,
    bsp_path: jni::objects::JString,
    time_ms: jni::sys::jlong,
    latitude: jni::sys::jdouble,
    longitude: jni::sys::jdouble,
    comets_json: jni::objects::JString,
) -> jni::sys::jstring {
    use chrono::TimeZone;
    use serde::{Deserialize, Serialize};
    use starfield::constants::{C_AUDAY, GM_SUN};
    use starfield::jplephem::kernel::SpiceKernel;
    use starfield::jplephem_ext::SpiceKernelExt;
    use starfield::positions::Position;
    use starfield::time::Timescale;

    #[derive(Deserialize)]
    struct CometRecord {
        id: i32,
        catalog_id: Option<String>,
        common_name: Option<String>,
        h: f64,
        k: f64,
        t_peri: f64,
        q: f64,
        e: f64,
        w: f64,
        node: f64,
        incl: f64,
    }

    #[derive(Serialize)]
    struct PlanetObj {
        id: i32,
        #[serde(rename = "catalogLabel")]
        catalog_label: String,
        #[serde(rename = "catalogEntry")]
        catalog_entry: String,
        ra: f64,
        dec: f64,
        constellation: Option<String>,
        #[serde(rename = "objectType")]
        object_type: String,
        #[serde(rename = "broadCategory")]
        broad_category: String,
        magnitude: Option<f64>,
        #[serde(rename = "surfaceBrightness")]
        surface_brightness: Option<f64>,
        #[serde(rename = "angularSize")]
        angular_size: Option<String>,
        #[serde(rename = "commonName")]
        common_name: Option<String>,
        description: Option<String>,
        copyright: Option<String>,
    }

    let bsp_path_str: String = env
        .get_string(&bsp_path)
        .map(|s| s.into())
        .unwrap_or_default();

    let json_str: String = env
        .get_string(&comets_json)
        .map(|s| s.into())
        .unwrap_or_default();

    let result_json = std::panic::catch_unwind(|| {
        let mut kernel = SpiceKernel::open(&bsp_path_str).map_err(|e| e.to_string())?;
        let ts = Timescale::default();

        let dt = chrono::Utc
            .timestamp_millis_opt(time_ms)
            .single()
            .ok_or("Invalid time")?;
        use chrono::{Datelike, Timelike};
        let time = ts.utc((
            dt.year(),
            dt.month(),
            dt.day(),
            dt.hour(),
            dt.minute(),
            dt.second() as f64 + dt.nanosecond() as f64 / 1_000_000_000.0,
        ));
        let observer_loc = starfield::toposlib::WGS84.latlon(latitude, longitude, 0.0);
        let earth = observer_loc
            .at(&time, &mut kernel)
            .map_err(|e| e.to_string())?;
        let sun = kernel.at("sun", &time).map_err(|e| e.to_string())?;

        let records: Vec<CometRecord> = serde_json::from_str(&json_str).unwrap_or_default();
        let mut results = vec![];

        for rec in records {
            let orbit = starfield::keplerlib::comet_orbit(
                rec.q,
                rec.e,
                rec.incl,
                rec.node,
                rec.w,
                &ts.tt_jd(rec.t_peri, None),
                GM_SUN,
                rec.common_name.as_deref(),
            );

            let mut target_helio = orbit.at(&time);
            let mut target_bary_pos = target_helio.position + sun.position;
            let mut target_bary_vel = target_helio.velocity + sun.velocity;
            let mut distance_au = (target_bary_pos - earth.position).norm();
            let mut light_time0 = 0.0;

            for _ in 0..5 {
                let light_time = distance_au / C_AUDAY;
                let delta_t = light_time - light_time0;
                if delta_t.abs() < 1e-7 {
                    break;
                }
                let retarded_time = ts.tdb_jd(time.tdb() - light_time);
                target_helio = orbit.at(&retarded_time);
                let sun_ret = kernel
                    .at("sun", &retarded_time)
                    .unwrap_or_else(|_| sun.clone());
                target_bary_pos = target_helio.position + sun_ret.position;
                target_bary_vel = target_helio.velocity + sun_ret.velocity;
                distance_au = (target_bary_pos - earth.position).norm();
                light_time0 = light_time;
            }

            let vector = target_bary_pos - earth.position;
            let velocity = target_bary_vel - earth.velocity;

            let astrometric = Position::astrometric(vector, velocity, &earth, -1, light_time0);

            let (ra_hours, dec_deg, _) = astrometric.radec(None);

            // Cometary magnitude
            let r_vec = -target_helio.position;
            let delta_vec = earth.position - target_bary_pos;
            let r = r_vec.norm();
            let delta = delta_vec.norm();
            let mag = rec.h + 5.0 * delta.log10() + 2.5 * rec.k * r.log10();

            results.push(PlanetObj {
                id: rec.id,
                catalog_label: "Comet".to_string(),
                catalog_entry: rec.catalog_id.unwrap_or_default(),
                ra: ra_hours * 15.0,
                dec: dec_deg,
                constellation: None,
                object_type: "Comet".to_string(),
                broad_category: "comet".to_string(),
                magnitude: Some(mag),
                surface_brightness: None,
                angular_size: None,
                common_name: rec.common_name.clone(),
                description: rec.common_name.map(|n| format!("The comet {}", n)),
                copyright: None,
            });
        }

        Ok::<Vec<PlanetObj>, String>(results)
    });

    let output = match result_json {
        Ok(Ok(planets)) => serde_json::to_string(&planets).unwrap_or_else(|_| "[]".to_string()),
        Ok(Err(e)) => {
            log::error!("Comets calculation error: {}", e);
            "[]".to_string()
        }
        Err(_) => {
            log::error!("Comets calculation panicked");
            "[]".to_string()
        }
    };

    env.new_string(output)
        .expect("Couldn't create java string!")
        .into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_startExternalCameraEngine(
    mut env: JNIEnv,
    _class: JClass,
    width: jint,
    height: jint,
    motion_sensor_mode: jint,
    extractor: jni::objects::JString,
    downsample_mode: jni::sys::jint,
    latitude: jni::sys::jdouble,
    longitude: jni::sys::jdouble,
    settings_manager: jni::objects::JObject,
    auto_mode: jni::sys::jboolean,
    fps: jint,
    uvc_wrapper: jni::objects::JObject,
) {
    let jvm = env.get_java_vm().unwrap();
    let jvm_for_uvc = env.get_java_vm().unwrap();
    let wrapper_global = if !uvc_wrapper.is_null() {
        Some(env.new_global_ref(&uvc_wrapper).unwrap())
    } else {
        None
    };

    let settings_mgr = env.new_global_ref(&settings_manager).unwrap();
    let storage: Option<std::sync::Arc<dyn olive_imu::PersistentStorage>> =
        Some(std::sync::Arc::new(JniStorage { jvm, settings_mgr }));

    let motion_sensor_mode = motion_sensor_mode as i32;
    let extractor_str: String = env.get_string(&extractor).unwrap().into();
    let ext_type = if extractor_str == "Cedar" {
        olive_engine::engine::Extractor::Cedar
    } else {
        olive_engine::engine::Extractor::Approximate
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

    if let Some(old_tx) = ENGINE_STOP_TX.lock().unwrap().replace(tx) {
        let _ = old_tx.send(());
    }

    let rt_handle = rt.handle().clone();

    let old_thread = MAIN_ENGINE_THREAD.lock().unwrap().take();

    if let Some(handle) = old_thread {
        let _ = handle.join();
    }

    let (ready_tx, ready_rx) = std::sync::mpsc::channel();

    let new_thread = std::thread::spawn(move || {
        rt_handle.block_on(async {
            use olive_engine::CameraTrait;
            use olive_engine::control_server;
            use olive_engine::engine::{Engine, EngineConfig};
            use std::sync::Arc;

            let uvc_cam = Arc::new(android_uvc_camera::AndroidUvcCamera::new(
                width as u32,
                height as u32,
                fps as u32,
                Some(jvm_for_uvc),
                wrapper_global,
                auto_mode != 0,
            ));
            *UVC_CAMERA_RECEIVER.lock().unwrap() = Some(uvc_cam.clone());
            let camera: std::sync::Arc<dyn CameraTrait> = uvc_cam;
            *CAMERA.lock().unwrap() = Some(camera.clone());

            let config = EngineConfig {
                gain: None,
                target_centroids: 20,
                database_path: "default_database.npz".to_string(),
                isp: false,
                bg_mode: olive_engine::engine::BgMode::Block,
                extractor_type: ext_type,
                crop: None,
                downsample: downsample_mode as u8,
                auto_mode: auto_mode != 0,
                is_adaptive_camera: false,
                noise_filter: false,
                initial_fov: camera.get_config().and_then(|c| c.initial_fov),
                use_observer_location: true,
            };
            let client_connected = Arc::new(std::sync::atomic::AtomicBool::new(false));

            let mut initial_state = olive_engine::engine::EngineStateData::default();
            if latitude >= -90.0 && latitude <= 90.0 && longitude >= -180.0 && longitude <= 180.0 {
                initial_state.client_latitude = Some(latitude);
                initial_state.client_longitude = Some(longitude);
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

            let imu_type = if motion_sensor_mode != 0 {
                let mut imu_dev = AndroidImu::new(motion_sensor_mode);
                if imu_dev.init().is_ok() {
                    Some(olive_solve::ImuType::Custom(Box::new(imu_dev)))
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
                        .enable_all()
                        .build()
                        .unwrap(),
                );
            }
            let telescope_rt = telescope_runtime_lock.as_ref().unwrap();

            let (alpaca_fd_tx, alpaca_fd_rx) = tokio::sync::mpsc::channel::<i32>(16);
            *ALPACA_FD_TX.lock().unwrap() = Some(alpaca_fd_tx);

            let (alpaca_v6_fd_tx, alpaca_v6_fd_rx) = tokio::sync::mpsc::channel::<i32>(16);
            *ALPACA_V6_FD_TX.lock().unwrap() = Some(alpaca_v6_fd_tx);

            let mut engine = Engine::new(
                camera,
                config,
                client_connected,
                engine_state,
                imu_type,
                Some(telescope_rt.handle().clone()),
                storage,
                Some(alpaca_fd_rx),
                Some(alpaca_v6_fd_rx),
            )
            .await;

            if latitude >= -90.0 && latitude <= 90.0 && longitude >= -180.0 && longitude <= 180.0 {
                engine
                    .fused_solver
                    .set_observer_location(latitude, longitude);
            }

            let _ = engine.fused_solver.start_imu();
            *LX200_CONTROLLER.lock().unwrap() = Some(engine.lx200_controller.clone());
            let is_running_clone = engine.is_running.clone();
            let telescope_handle = engine.telescope_server_handle.take();

            let engine_thread = std::thread::spawn(move || {
                let handles = engine.start();
                let _ = handles.0.join();
                let _ = handles.1.join();
            });

            let _ = ready_tx.send(());

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

    let _ = ready_rx.recv();
    *MAIN_ENGINE_THREAD.lock().unwrap() = Some(new_thread);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_pushExternalUvcFrame(
    env: jni::JNIEnv,
    _class: jni::objects::JClass,
    buffer: jni::objects::JByteBuffer,
    width: jni::sys::jint,
    height: jni::sys::jint,
    _timestamp: jni::sys::jlong,
) {
    if let Ok(data_ptr) = env.get_direct_buffer_address(&buffer) {
        if let Ok(data_len) = env.get_direct_buffer_capacity(&buffer) {
            let data_slice = unsafe { std::slice::from_raw_parts(data_ptr as *const u8, data_len) };
            if let Some(cam) = UVC_CAMERA_RECEIVER.lock().unwrap().as_ref() {
                cam.push_external_frame(data_slice, width as u32, height as u32);
            }
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_getNativeCameraNoiseReduction(
    _env: JNIEnv,
    _class: JClass,
) -> jni::sys::jint {
    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        cam.get_noise_reduction() as i32
    } else {
        0
    }
}
