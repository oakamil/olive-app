use jni::objects::{GlobalRef, JByteArray, JByteBuffer, JClass, JObject, JString};
use jni::sys::{
    jboolean, jbyteArray, jdouble, jfloat, jfloatArray, jint, jintArray, jlong, jstring,
};
use jni::{JNIEnv, JavaVM};
use log::{LevelFilter, error, info, warn};
use olive_imu::ImuDevice;
use std::sync::{
    Arc, Mutex, MutexGuard, RwLock,
    atomic::{AtomicBool, Ordering},
    mpsc,
};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

pub mod android_camera;
pub mod android_imu;
pub mod android_uvc_camera;

static LAST_BUFFER_WARN: Mutex<Option<Instant>> = Mutex::new(None);
static RUNTIME: Mutex<Option<tokio::runtime::Runtime>> = Mutex::new(None);
static TELESCOPE_RUNTIME: Mutex<Option<tokio::runtime::Runtime>> = Mutex::new(None);
static ENGINE_STOP_TX: Mutex<Option<tokio::sync::oneshot::Sender<()>>> = Mutex::new(None);
static ENGINE_STATE: Mutex<Option<Arc<RwLock<olive_engine::engine::EngineStateData>>>> =
    Mutex::new(None);
static CAMERA: Mutex<Option<Arc<dyn olive_engine::CameraTrait>>> = Mutex::new(None);
static UVC_CAMERA_RECEIVER: Mutex<Option<Arc<android_uvc_camera::AndroidUvcCamera>>> =
    Mutex::new(None);
static MAIN_ENGINE_THREAD: Mutex<Option<JoinHandle<()>>> = Mutex::new(None);
static LX200_CONTROLLER: Mutex<
    Option<Arc<RwLock<olive_engine::telescope_server::Lx200Controller>>>,
> = Mutex::new(None);
static ALPACA_FD_TX: Mutex<Option<tokio::sync::mpsc::Sender<i32>>> = Mutex::new(None);
static ALPACA_V6_FD_TX: Mutex<Option<tokio::sync::mpsc::Sender<i32>>> = Mutex::new(None);

struct JniStorage {
    jvm: JavaVM,
    settings_mgr: GlobalRef,
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
                                return env.get_string(&JString::from(jstr)).ok().map(|s| s.into());
                            }
                        }
                    }
                    Err(e) => {
                        error!("JniStorage::get call_method error: {:?}", e);
                        if let Ok(true) = env.exception_check() {
                            let _ = env.exception_clear();
                        }
                    }
                }
            }
        } else {
            error!("JniStorage::get attach_current_thread failed!");
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
                        error!("JniStorage::set call_method error: {:?}", e);
                        if let Ok(true) = env.exception_check() {
                            let _ = env.exception_clear();
                        }
                    }
                }
            }
        } else {
            error!("JniStorage::set attach_current_thread failed!");
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
                        error!("JniStorage::remove call_method error: {:?}", e);
                        if let Ok(true) = env.exception_check() {
                            let _ = env.exception_clear();
                        }
                    }
                }
            }
        } else {
            error!("JniStorage::remove attach_current_thread failed!");
        }
    }
}

fn lock_or_recover<'a, T>(mutex: &'a Mutex<T>, name: &str) -> MutexGuard<'a, T> {
    mutex.lock().unwrap_or_else(|e| {
        error!("Mutex {} poisoned; recovering inner state", name);
        e.into_inner()
    })
}

fn throw_runtime_exception(env: &mut JNIEnv, msg: &str) {
    error!("Throwing Java RuntimeException: {}", msg);
    if let Err(e) = env.throw_new("java/lang/RuntimeException", msg) {
        error!("Failed to throw RuntimeException ({}): {:?}", msg, e);
    }
}

fn build_jni_storage(
    env: &JNIEnv,
    settings_manager: &JObject,
) -> Result<Arc<dyn olive_imu::PersistentStorage>, String> {
    let jvm = env
        .get_java_vm()
        .map_err(|e| format!("Failed to acquire JavaVM: {:?}", e))?;
    let settings_mgr = env
        .new_global_ref(settings_manager)
        .map_err(|e| format!("Failed to create global ref for SettingsManager: {:?}", e))?;
    Ok(Arc::new(JniStorage { jvm, settings_mgr }))
}

fn get_or_init_runtime_handle(
    runtime_mutex: &Mutex<Option<tokio::runtime::Runtime>>,
    name: &str,
) -> Result<tokio::runtime::Handle, String> {
    let mut runtime_lock = lock_or_recover(runtime_mutex, name);
    if runtime_lock.is_none() {
        let rt = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .map_err(|e| format!("Failed to build {} Tokio runtime: {}", name, e))?;
        *runtime_lock = Some(rt);
    }
    runtime_lock
        .as_ref()
        .map(|rt| rt.handle().clone())
        .ok_or_else(|| format!("{} Tokio runtime missing after initialization", name))
}

fn prepare_engine_restart() -> tokio::sync::oneshot::Receiver<()> {
    let (tx, rx) = tokio::sync::oneshot::channel::<()>();
    if let Some(old_tx) = lock_or_recover(&ENGINE_STOP_TX, "ENGINE_STOP_TX").replace(tx) {
        let _ = old_tx.send(());
    }
    let old_thread = lock_or_recover(&MAIN_ENGINE_THREAD, "MAIN_ENGINE_THREAD").take();
    if let Some(handle) = old_thread {
        let _ = handle.join();
    }
    rx
}

fn finish_engine_startup(
    env: &mut JNIEnv,
    ready_rx: mpsc::Receiver<Result<(), String>>,
    new_thread: JoinHandle<()>,
) {
    match ready_rx.recv() {
        Ok(Ok(())) => {
            *lock_or_recover(&MAIN_ENGINE_THREAD, "MAIN_ENGINE_THREAD") = Some(new_thread);
        }
        Ok(Err(msg)) => {
            let _ = new_thread.join();
            throw_runtime_exception(env, &msg);
        }
        Err(e) => {
            let _ = new_thread.join();
            throw_runtime_exception(
                env,
                &format!("Engine startup channel closed unexpectedly: {}", e),
            );
        }
    }
}

fn jstring_to_string(env: &mut JNIEnv, value: &JString, name: &str) -> Result<String, String> {
    env.get_string(value)
        .map(|s| s.into())
        .map_err(|e| format!("Failed to read JNI string '{}': {:?}", name, e))
}

fn new_java_string(env: &JNIEnv, value: &str) -> jstring {
    match env.new_string(value) {
        Ok(s) => s.into_raw(),
        Err(e) => {
            error!("Failed to create Java string: {:?}", e);
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_initJni(
    mut env: JNIEnv,
    _class: JClass,
    files_dir: JString,
) {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(LevelFilter::Info)
            .with_tag("OliveRust"),
    );

    std::panic::set_hook(Box::new(|panic_info| {
        error!("RUST PANIC: {}", panic_info);
    }));

    if let Ok(dir) = env.get_string(&files_dir) {
        if let Ok(dir_str) = dir.to_str() {
            if let Err(e) = std::env::set_current_dir(dir_str) {
                error!("Failed to set current directory to {}: {}", dir_str, e);
            } else {
                info!("Successfully set current directory to {}", dir_str);
            }
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_startNativeCameraEngine(
    mut env: JNIEnv,
    _class: JClass,
    motion_sensor_mode: jint,
    sensor_mode: JString,
    internal_camera_id: JString,
    downsample_mode: jint,
    latitude: jdouble,
    longitude: jdouble,
    settings_manager: JObject,
    auto_mode: jboolean,
    initial_zoom: jfloat,
) {
    let storage = match build_jni_storage(&env, &settings_manager) {
        Ok(s) => Some(s),
        Err(e) => {
            throw_runtime_exception(&mut env, &e);
            return;
        }
    };

    let motion_sensor_mode = motion_sensor_mode as i32;
    let internal_camera_id_str =
        match jstring_to_string(&mut env, &internal_camera_id, "internal_camera_id") {
            Ok(s) => s,
            Err(e) => {
                throw_runtime_exception(&mut env, &e);
                return;
            }
        };
    let sensor_mode_str = match jstring_to_string(&mut env, &sensor_mode, "sensor_mode") {
        Ok(s) => s,
        Err(e) => {
            throw_runtime_exception(&mut env, &e);
            return;
        }
    };

    let rt_handle = match get_or_init_runtime_handle(&RUNTIME, "engine") {
        Ok(h) => h,
        Err(e) => {
            throw_runtime_exception(&mut env, &e);
            return;
        }
    };

    let rx = prepare_engine_restart();
    let (ready_tx, ready_rx) = mpsc::channel::<Result<(), String>>();

    let new_thread = thread::spawn(move || {
        rt_handle.block_on(async {
            use crate::android_camera::AndroidCamera;
            use olive_engine::CameraTrait;
            use olive_engine::control_server;
            use olive_engine::engine::{Engine, EngineConfig};

            let camera: Arc<dyn CameraTrait> = if internal_camera_id_str == "demo" {
                use olive_engine::dummy_camera::DummyCamera;
                let mut cam = DummyCamera::new();
                if let Err(e) = cam.start_capture() {
                    let msg = format!("Failed to start demo camera: {}", e);
                    error!("{}", msg);
                    let _ = ready_tx.send(Err(msg));
                    return;
                }
                Arc::new(cam)
            } else {
                let mut cam = match AndroidCamera::new(
                    Some(internal_camera_id_str.clone()),
                    Some(sensor_mode_str.clone()),
                    Some(initial_zoom.max(1.0)),
                ) {
                    Ok(c) => c,
                    Err(e) => {
                        let msg = format!("Failed to initialize Android camera: {}", e);
                        error!("{}", msg);
                        let _ = ready_tx.send(Err(msg));
                        return;
                    }
                };
                if let Err(e) = cam.start_capture() {
                    let msg = format!("Failed to start Android camera: {}", e);
                    error!("{}", msg);
                    let _ = ready_tx.send(Err(msg));
                    return;
                }
                Arc::new(cam)
            };
            *lock_or_recover(&CAMERA, "CAMERA") = Some(camera.clone());

            let config = EngineConfig {
                gain: None,
                target_centroids: 20,
                database_path: "default_database.npz".to_string(),
                isp: false,
                bg_mode: olive_engine::engine::BgMode::Block,
                extractor_type: olive_engine::engine::Extractor::default(),
                crop: None,
                downsample: downsample_mode as u8,
                auto_mode: auto_mode != 0 && internal_camera_id_str != "demo",
                is_adaptive_camera: internal_camera_id_str != "demo",
                noise_filter: true,
                initial_fov: camera.get_config().and_then(|c| c.initial_fov),
                use_observer_location: internal_camera_id_str != "demo",
            };
            let client_connected = Arc::new(AtomicBool::new(false));

            let mut initial_state = olive_engine::engine::EngineStateData::default();
            if latitude >= -90.0 && latitude <= 90.0 && longitude >= -180.0 && longitude <= 180.0 {
                initial_state.client_latitude = Some(latitude);
                initial_state.client_longitude = Some(longitude);
            }

            let engine_state = Arc::new(RwLock::new(initial_state));

            let server_camera = camera.clone();
            let server_client_connected = client_connected.clone();
            let server_engine_state = engine_state.clone();

            *lock_or_recover(&crate::ENGINE_STATE, "ENGINE_STATE") = Some(engine_state.clone());

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

            let telescope_rt_handle =
                match get_or_init_runtime_handle(&TELESCOPE_RUNTIME, "telescope") {
                    Ok(h) => h,
                    Err(e) => {
                        server_handle.abort();
                        let _ = server_handle.await;
                        let _ = ready_tx.send(Err(e));
                        return;
                    }
                };

            let (alpaca_fd_tx, alpaca_fd_rx) = tokio::sync::mpsc::channel::<i32>(16);
            *lock_or_recover(&ALPACA_FD_TX, "ALPACA_FD_TX") = Some(alpaca_fd_tx);

            let (alpaca_v6_fd_tx, alpaca_v6_fd_rx) = tokio::sync::mpsc::channel::<i32>(16);
            *lock_or_recover(&ALPACA_V6_FD_TX, "ALPACA_V6_FD_TX") = Some(alpaca_v6_fd_tx);

            let mut engine = Engine::new(
                camera,
                config,
                client_connected,
                engine_state,
                imu_type,
                Some(telescope_rt_handle),
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
            *lock_or_recover(&LX200_CONTROLLER, "LX200_CONTROLLER") =
                Some(engine.lx200_controller.clone());
            let is_running_clone = engine.is_running.clone();
            // Take ownership of telescope server handle for shutdown
            let telescope_handle = engine.telescope_server_handle.take();

            // Run engine in a separate thread since it might block
            let engine_thread = thread::spawn(move || {
                let handles = engine.start();
                let _ = handles.0.join();
                let _ = handles.1.join();
            });

            let _ = ready_tx.send(Ok(()));

            // Wait for stop signal
            let _ = rx.await;
            is_running_clone.store(false, Ordering::Relaxed);
            server_handle.abort();
            let _ = server_handle.await;

            if let Some(handle) = telescope_handle {
                handle.abort();
                let _ = handle.await;
            }

            let _ = engine_thread.join();
        });
    });

    finish_engine_startup(&mut env, ready_rx, new_thread);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_stopEngine(_env: JNIEnv, _class: JClass) {
    if let Some(tx) = lock_or_recover(&ENGINE_STOP_TX, "ENGINE_STOP_TX").take() {
        let _ = tx.send(());
    }

    if let Some(cam) = lock_or_recover(&CAMERA, "CAMERA").as_ref() {
        cam.stop_capture();
    }

    // Clear globals immediately so UI and external handlers don't see stale state
    *lock_or_recover(&CAMERA, "CAMERA") = None;
    *lock_or_recover(&UVC_CAMERA_RECEIVER, "UVC_CAMERA_RECEIVER") = None;
    *lock_or_recover(&ENGINE_STATE, "ENGINE_STATE") = None;
    *lock_or_recover(&LX200_CONTROLLER, "LX200_CONTROLLER") = None;
    *lock_or_recover(&ALPACA_FD_TX, "ALPACA_FD_TX") = None;
    *lock_or_recover(&ALPACA_V6_FD_TX, "ALPACA_V6_FD_TX") = None;

    // Join engine thread so hardware release completes before USB teardown.
    if let Some(handle) = lock_or_recover(&MAIN_ENGINE_THREAD, "MAIN_ENGINE_THREAD").take() {
        let _ = handle.join();
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_setNativeCameraExposure(
    _env: JNIEnv,
    _class: JClass,
    exposure_us: jint,
) {
    if let Some(cam) = lock_or_recover(&CAMERA, "CAMERA").as_ref() {
        cam.set_exposure(exposure_us);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_updateUvcStacking(
    _env: JNIEnv,
    _class: JClass,
    stack_count: jint,
) {
    if let Some(cam) = lock_or_recover(&CAMERA, "CAMERA").as_ref() {
        cam.set_stack_count(stack_count as usize);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_setNativeCameraGain(
    _env: JNIEnv,
    _class: JClass,
    gain: jfloat,
) {
    if let Some(cam) = lock_or_recover(&CAMERA, "CAMERA").as_ref() {
        cam.set_gain(gain);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_setNativeCameraFocus(
    _env: JNIEnv,
    _class: JClass,
    distance: jfloat,
) {
    if let Some(cam) = lock_or_recover(&CAMERA, "CAMERA").as_ref() {
        cam.set_focus(distance);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_setNativeCameraNoiseReduction(
    _env: JNIEnv,
    _class: JClass,
    mode: jint,
) {
    if let Some(cam) = lock_or_recover(&CAMERA, "CAMERA").as_ref() {
        cam.set_noise_reduction(mode as u8);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_getNativeCameraBrightnessRange<'local>(
    env: JNIEnv<'local>,
    _class: JClass,
) -> jintArray {
    let result = if let Some(cam) = lock_or_recover(&CAMERA, "CAMERA").as_ref() {
        cam.get_brightness_range()
    } else {
        None
    };

    if let Some((min, max, current)) = result {
        match env.new_int_array(3) {
            Ok(arr) => {
                if let Err(e) = env.set_int_array_region(&arr, 0, &[min, max, current]) {
                    error!("Failed to fill brightness range array: {:?}", e);
                    return std::ptr::null_mut();
                }
                arr.into_raw()
            }
            Err(e) => {
                error!("Failed to allocate brightness range array: {:?}", e);
                std::ptr::null_mut()
            }
        }
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
    if let Some(cam) = lock_or_recover(&CAMERA, "CAMERA").as_ref() {
        cam.set_brightness(brightness);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_setNativeCameraZoomRatio(
    _env: JNIEnv,
    _class: JClass,
    zoom: jfloat,
) {
    if let Some(cam) = lock_or_recover(&CAMERA, "CAMERA").as_ref() {
        cam.set_zoom(zoom);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_getNativeCameraGainRange(
    env: JNIEnv,
    _class: JClass,
) -> jfloatArray {
    let (min, max, default_val) = if let Some(cam) = lock_or_recover(&CAMERA, "CAMERA").as_ref() {
        cam.get_gain_range()
    } else {
        (100.0, 3200.0, 3200.0)
    };

    match env.new_float_array(3) {
        Ok(array) => {
            if let Err(e) = env.set_float_array_region(&array, 0, &[min, max, default_val]) {
                error!("Failed to fill gain range array: {:?}", e);
                return std::ptr::null_mut();
            }
            array.into_raw()
        }
        Err(e) => {
            error!("Failed to allocate gain range array: {:?}", e);
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_getNativeCameraGain(
    _env: JNIEnv,
    _class: JClass,
) -> jfloat {
    if let Some(cam) = lock_or_recover(&CAMERA, "CAMERA").as_ref() {
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
    if let Some(cam) = lock_or_recover(&CAMERA, "CAMERA").as_ref() {
        cam.get_exposure()
    } else {
        500_000
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_getLatestFrameInto(
    env: JNIEnv,
    _class: JClass,
    buffer: JByteBuffer,
    last_timestamp_ms: jlong,
) -> jlong {
    if let Some(cam) = lock_or_recover(&CAMERA, "CAMERA").as_ref() {
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
            let Some(pixel_count) = width.checked_mul(height) else {
                return 0;
            };
            let Some(req_len) = pixel_count
                .checked_mul(4)
                .and_then(|bytes| bytes.checked_add(8))
            else {
                return 0;
            };
            let pixels = &data[..pixel_count.min(data.len())];
            if let Ok(capacity) = env.get_direct_buffer_capacity(&buffer) {
                if capacity >= req_len {
                    if let Ok(buf_ptr) = env.get_direct_buffer_address(&buffer) {
                        let slice =
                            unsafe { std::slice::from_raw_parts_mut(buf_ptr as *mut u8, capacity) };
                        slice[0..4].copy_from_slice(&(width as u32).to_le_bytes());
                        slice[4..8].copy_from_slice(&(height as u32).to_le_bytes());

                        let mut dest_idx = 8;
                        for &gray in pixels {
                            slice[dest_idx] = gray; // R
                            slice[dest_idx + 1] = gray; // G
                            slice[dest_idx + 2] = gray; // B
                            slice[dest_idx + 3] = 255; // A
                            dest_idx += 4;
                        }
                        return img_ts_ms;
                    }
                } else {
                    let should_warn = {
                        let mut last = lock_or_recover(&LAST_BUFFER_WARN, "LAST_BUFFER_WARN");
                        let now = Instant::now();
                        let ready = last.map_or(true, |prev| {
                            now.saturating_duration_since(prev) >= Duration::from_secs(5)
                        });
                        if ready {
                            *last = Some(now);
                        }
                        ready
                    };
                    if should_warn {
                        warn!(
                            "Provided ByteBuffer is too small. Need {}, got {}",
                            req_len, capacity
                        );
                    }
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
    paused: jboolean,
) {
    if let Some(state_arc) = lock_or_recover(&ENGINE_STATE, "ENGINE_STATE").as_ref() {
        if let Ok(mut state) = state_arc.write() {
            state.paused = paused != 0;
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_getAvailableInternalCameras(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    use crate::android_camera::AndroidCamera;
    let json_str = AndroidCamera::get_available_cameras();
    new_java_string(&env, &json_str)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_getEngineStats(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let state_lock = lock_or_recover(&ENGINE_STATE, "ENGINE_STATE");
    let json_str = if let Some(state) = state_lock.as_ref() {
        if let Ok(guard) = state.read() {
            serde_json::to_string(&*guard).unwrap_or_else(|_| "{}".to_string())
        } else {
            "{}".to_string()
        }
    } else {
        "{}".to_string()
    };

    new_java_string(&env, &json_str)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_setEngineSigma(
    _env: JNIEnv,
    _class: JClass,
    sigma: jfloat,
) {
    if let Some(engine_state_arc) = lock_or_recover(&ENGINE_STATE, "ENGINE_STATE").as_ref() {
        if let Ok(mut state) = engine_state_arc.write() {
            state.current_sigma = Some(sigma as f32);
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_setEngineStrictMatch(
    _env: JNIEnv,
    _class: JClass,
    strict: jboolean,
) {
    if let Some(engine_state_arc) = lock_or_recover(&ENGINE_STATE, "ENGINE_STATE").as_ref() {
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
    if let Some(tx) = lock_or_recover(&ALPACA_FD_TX, "ALPACA_FD_TX").as_ref() {
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
    if let Some(tx) = lock_or_recover(&ALPACA_V6_FD_TX, "ALPACA_V6_FD_TX").as_ref() {
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
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    if let Some(cam) = lock_or_recover(&CAMERA, "CAMERA").as_ref() {
        cam.get_brightness().unwrap_or(0)
    } else {
        0
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_getNativeCameraZoomRatio(
    _env: JNIEnv,
    _class: JClass,
) -> jfloat {
    if let Some(cam) = lock_or_recover(&CAMERA, "CAMERA").as_ref() {
        cam.get_zoom().unwrap_or(1.0)
    } else {
        1.0
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_getNativeCameraFocus(
    _env: JNIEnv,
    _class: JClass,
) -> jfloat {
    if let Some(cam) = lock_or_recover(&CAMERA, "CAMERA").as_ref() {
        cam.get_focus().unwrap_or(0.0)
    } else {
        0.0
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_getNativeCameraSupportedHotPixelModes<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jintArray {
    let modes = lock_or_recover(
        &crate::android_camera::SUPPORTED_HOT_PIXEL_MODES,
        "SUPPORTED_HOT_PIXEL_MODES",
    );
    let modes_i32: Vec<i32> = modes.iter().map(|&x| x as i32).collect();
    match env.new_int_array(modes_i32.len() as i32) {
        Ok(array) => {
            if !modes_i32.is_empty() {
                if let Err(e) = env.set_int_array_region(&array, 0, &modes_i32) {
                    error!("Failed to fill hot pixel modes array: {:?}", e);
                    return std::ptr::null_mut();
                }
            }
            array.into_raw()
        }
        Err(e) => {
            error!("Failed to allocate hot pixel modes array: {:?}", e);
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_setNativeCameraHotPixelMode(
    _env: JNIEnv,
    _class: JClass,
    mode: jint,
) {
    crate::android_camera::CURRENT_HOT_PIXEL_MODE.store(mode as u32, Ordering::Relaxed);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_getNativeCameraHotPixelMode(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    crate::android_camera::CURRENT_HOT_PIXEL_MODE.load(Ordering::Relaxed) as jint
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_processTelescopeData<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    data: JByteArray<'local>,
) -> jbyteArray {
    let controller_opt = lock_or_recover(&LX200_CONTROLLER, "LX200_CONTROLLER").clone();
    if let Some(controller_lock) = controller_opt {
        if let Ok(data_bytes) = env.convert_byte_array(&data) {
            if let Ok(mut controller) = controller_lock.write() {
                let response = controller.process_client_data(&data_bytes);
                if !response.is_empty() {
                    if let Ok(j_array) = env.byte_array_from_slice(&response) {
                        return j_array.into_raw();
                    }
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
    if let Some(_engine_state_arc) = lock_or_recover(&ENGINE_STATE, "ENGINE_STATE").clone() {
        // We just reset the buffer and state of the controller, since it's a new connection
        let controller_opt = lock_or_recover(&LX200_CONTROLLER, "LX200_CONTROLLER").clone();
        if let Some(controller_lock) = controller_opt {
            if let Ok(mut controller) = controller_lock.write() {
                controller.client_buffer.clear();
                controller.is_stellarium = false;
            }
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_getPlanets(
    mut env: JNIEnv,
    _class: JClass,
    bsp_path: JString,
    time_ms: jlong,
    latitude: jdouble,
    longitude: jdouble,
) -> jstring {
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
            error!("Planet calculation error: {}", e);
            "[]".to_string()
        }
        Err(_) => {
            error!("Planet calculation panicked");
            "[]".to_string()
        }
    };

    new_java_string(&env, &json_str)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_setGoToTarget(
    _env: JNIEnv,
    _class: JClass,
    ra: jdouble,
    dec: jdouble,
    epoch: jdouble,
) {
    if let Some(state_arc) = lock_or_recover(&ENGINE_STATE, "ENGINE_STATE").as_ref() {
        if let Ok(mut state) = state_arc.write() {
            state.requested_target_ra = Some(ra as f64);
            state.requested_target_dec = Some(dec as f64);
            state.requested_target_epoch = Some(epoch as f64);
            info!(
                "Target slew requested via JNI (EngineState): RA={}, Dec={}, Epoch={}",
                ra, dec, epoch
            );
        }
    } else {
        warn!("setGoToTarget called but ENGINE_STATE is not initialized");
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_clearGoToTarget(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Some(state_arc) = lock_or_recover(&ENGINE_STATE, "ENGINE_STATE").as_ref() {
        if let Ok(mut state) = state_arc.write() {
            state.requested_target_ra = None;
            state.requested_target_dec = None;
            state.requested_target_epoch = None;
            state.target_ra = None;
            state.target_dec = None;
            state.requested_target_clear = true;
            info!("Target slew cleared via JNI");
        }
    } else {
        warn!("clearGoToTarget called but ENGINE_STATE is not initialized");
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_setAlignTargetPixel(
    _env: JNIEnv,
    _class: JClass,
    x: jdouble,
    y: jdouble,
) {
    if let Some(state_arc) = lock_or_recover(&ENGINE_STATE, "ENGINE_STATE").as_ref() {
        if let Ok(mut state) = state_arc.write() {
            state.pending_target_pixel_cmd = Some(olive_engine::engine::TargetPixelCommand::Set(
                x as f64, y as f64,
            ));
            info!("Align target pixel requested via JNI: ({}, {})", x, y);
        }
    } else {
        warn!("setAlignTargetPixel called but ENGINE_STATE is not initialized");
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_clearAlignTargetPixel(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Some(state_arc) = lock_or_recover(&ENGINE_STATE, "ENGINE_STATE").as_ref() {
        if let Ok(mut state) = state_arc.write() {
            state.pending_target_pixel_cmd = Some(olive_engine::engine::TargetPixelCommand::Clear);
            info!("Clear align target pixel requested via JNI");
        }
    } else {
        warn!("clearAlignTargetPixel called but ENGINE_STATE is not initialized");
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_syncTelescope(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Some(state_arc) = lock_or_recover(&ENGINE_STATE, "ENGINE_STATE").as_ref() {
        if let Ok(mut state) = state_arc.write() {
            state.requested_sync = true;
            info!("Sync telescope requested via JNI");
        }
    } else {
        warn!("syncTelescope called but ENGINE_STATE is not initialized");
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_syncTelescopeToTarget(
    _env: JNIEnv,
    _class: JClass,
    ra: jdouble,
    dec: jdouble,
) {
    if let Some(state_arc) = lock_or_recover(&ENGINE_STATE, "ENGINE_STATE").as_ref() {
        if let Ok(mut state) = state_arc.write() {
            state.requested_sync_coords = Some((ra as f64, dec as f64));
            state.requested_sync = true;
            info!(
                "Sync telescope to target ({}, {}) requested via JNI",
                ra, dec
            );
        }
    } else {
        warn!("syncTelescopeToTarget called but ENGINE_STATE is not initialized");
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_getAsteroids(
    mut env: JNIEnv,
    _class: JClass,
    bsp_path: JString,
    time_ms: jlong,
    latitude: jdouble,
    longitude: jdouble,
    asteroids_json: JString,
) -> jstring {
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
            error!("Asteroids calculation error: {}", e);
            "[]".to_string()
        }
        Err(_) => {
            error!("Asteroids calculation panicked");
            "[]".to_string()
        }
    };

    new_java_string(&env, &output)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_getComets(
    mut env: JNIEnv,
    _class: JClass,
    bsp_path: JString,
    time_ms: jlong,
    latitude: jdouble,
    longitude: jdouble,
    comets_json: JString,
) -> jstring {
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
            error!("Comets calculation error: {}", e);
            "[]".to_string()
        }
        Err(_) => {
            error!("Comets calculation panicked");
            "[]".to_string()
        }
    };

    new_java_string(&env, &output)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_startExternalCameraEngine(
    mut env: JNIEnv,
    _class: JClass,
    width: jint,
    height: jint,
    motion_sensor_mode: jint,
    downsample_mode: jint,
    latitude: jdouble,
    longitude: jdouble,
    settings_manager: JObject,
    auto_mode: jboolean,
    fps: jint,
    uvc_wrapper: JObject,
) {
    let jvm_for_uvc = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(e) => {
            throw_runtime_exception(&mut env, &format!("Failed to acquire JavaVM: {:?}", e));
            return;
        }
    };
    let wrapper_global = if !uvc_wrapper.is_null() {
        match env.new_global_ref(&uvc_wrapper) {
            Ok(r) => Some(r),
            Err(e) => {
                throw_runtime_exception(
                    &mut env,
                    &format!("Failed to create global ref for UVC wrapper: {:?}", e),
                );
                return;
            }
        }
    } else {
        None
    };

    let storage = match build_jni_storage(&env, &settings_manager) {
        Ok(s) => Some(s),
        Err(e) => {
            throw_runtime_exception(&mut env, &e);
            return;
        }
    };

    let motion_sensor_mode = motion_sensor_mode as i32;

    let rt_handle = match get_or_init_runtime_handle(&RUNTIME, "engine") {
        Ok(h) => h,
        Err(e) => {
            throw_runtime_exception(&mut env, &e);
            return;
        }
    };

    let rx = prepare_engine_restart();
    let (ready_tx, ready_rx) = mpsc::channel::<Result<(), String>>();

    let new_thread = thread::spawn(move || {
        rt_handle.block_on(async {
            use olive_engine::CameraTrait;
            use olive_engine::control_server;
            use olive_engine::engine::{Engine, EngineConfig};

            let uvc_cam = Arc::new(android_uvc_camera::AndroidUvcCamera::new(
                width as u32,
                height as u32,
                fps as u32,
                Some(jvm_for_uvc),
                wrapper_global,
                auto_mode != 0,
            ));
            *lock_or_recover(&UVC_CAMERA_RECEIVER, "UVC_CAMERA_RECEIVER") = Some(uvc_cam.clone());
            let camera: Arc<dyn CameraTrait> = uvc_cam;
            *lock_or_recover(&CAMERA, "CAMERA") = Some(camera.clone());

            let config = EngineConfig {
                gain: None,
                target_centroids: 20,
                database_path: "default_database.npz".to_string(),
                isp: false,
                bg_mode: olive_engine::engine::BgMode::Block,
                extractor_type: olive_engine::engine::Extractor::default(),
                crop: None,
                downsample: downsample_mode as u8,
                auto_mode: auto_mode != 0,
                is_adaptive_camera: false,
                noise_filter: false,
                initial_fov: camera.get_config().and_then(|c| c.initial_fov),
                use_observer_location: true,
            };
            let client_connected = Arc::new(AtomicBool::new(false));

            let mut initial_state = olive_engine::engine::EngineStateData::default();
            if latitude >= -90.0 && latitude <= 90.0 && longitude >= -180.0 && longitude <= 180.0 {
                initial_state.client_latitude = Some(latitude);
                initial_state.client_longitude = Some(longitude);
            }

            let engine_state = Arc::new(RwLock::new(initial_state));

            let server_camera = camera.clone();
            let server_client_connected = client_connected.clone();
            let server_engine_state = engine_state.clone();

            *lock_or_recover(&crate::ENGINE_STATE, "ENGINE_STATE") = Some(engine_state.clone());

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

            let telescope_rt_handle =
                match get_or_init_runtime_handle(&TELESCOPE_RUNTIME, "telescope") {
                    Ok(h) => h,
                    Err(e) => {
                        server_handle.abort();
                        let _ = server_handle.await;
                        let _ = ready_tx.send(Err(e));
                        return;
                    }
                };

            let (alpaca_fd_tx, alpaca_fd_rx) = tokio::sync::mpsc::channel::<i32>(16);
            *lock_or_recover(&ALPACA_FD_TX, "ALPACA_FD_TX") = Some(alpaca_fd_tx);

            let (alpaca_v6_fd_tx, alpaca_v6_fd_rx) = tokio::sync::mpsc::channel::<i32>(16);
            *lock_or_recover(&ALPACA_V6_FD_TX, "ALPACA_V6_FD_TX") = Some(alpaca_v6_fd_tx);

            let mut engine = Engine::new(
                camera,
                config,
                client_connected,
                engine_state,
                imu_type,
                Some(telescope_rt_handle),
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
            *lock_or_recover(&LX200_CONTROLLER, "LX200_CONTROLLER") =
                Some(engine.lx200_controller.clone());
            let is_running_clone = engine.is_running.clone();
            let telescope_handle = engine.telescope_server_handle.take();

            let engine_thread = thread::spawn(move || {
                let handles = engine.start();
                let _ = handles.0.join();
                let _ = handles.1.join();
            });

            let _ = ready_tx.send(Ok(()));

            let _ = rx.await;
            is_running_clone.store(false, Ordering::Relaxed);
            server_handle.abort();
            let _ = server_handle.await;

            if let Some(handle) = telescope_handle {
                handle.abort();
                let _ = handle.await;
            }

            let _ = engine_thread.join();
        });
    });

    finish_engine_startup(&mut env, ready_rx, new_thread);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_pushExternalUvcFrame(
    env: JNIEnv,
    _class: JClass,
    buffer: JByteBuffer,
    width: jint,
    height: jint,
    _timestamp: jlong,
) {
    if let Ok(data_ptr) = env.get_direct_buffer_address(&buffer) {
        if let Ok(data_len) = env.get_direct_buffer_capacity(&buffer) {
            let data_slice = unsafe { std::slice::from_raw_parts(data_ptr as *const u8, data_len) };
            if let Some(cam) = lock_or_recover(&UVC_CAMERA_RECEIVER, "UVC_CAMERA_RECEIVER").as_ref()
            {
                cam.push_external_frame(data_slice, width as u32, height as u32);
            }
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oakamil_olive_OliveEngine_getNativeCameraNoiseReduction(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    if let Some(cam) = lock_or_recover(&CAMERA, "CAMERA").as_ref() {
        cam.get_noise_reduction() as i32
    } else {
        0
    }
}
