use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::jint;
use std::sync::Mutex;
use std::thread;

pub mod android_camera;

static RUNTIME: Mutex<Option<tokio::runtime::Runtime>> = Mutex::new(None);
static ENGINE_STOP_TX: Mutex<Option<tokio::sync::oneshot::Sender<()>>> = Mutex::new(None);

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_olive_MainActivity_startEngine(
    _env: JNIEnv,
    _class: JClass,
    fd: jint,
) {
    let fd = fd as i32;

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
            use olive_engine::uvc_camera::Camera;
            use olive_engine::CameraTrait;
            use olive_engine::engine::{Engine, EngineConfig};
            use olive_engine::control_server;
            use std::sync::Arc;

            // 30 FPS, MJPEG true
            let mut cam = Camera::with_fd(30, true, Some(fd));
            if let Err(e) = cam.start_capture() {
                log::error!("Failed to start UVC camera: {}", e);
                return;
            }
            let camera: Arc<dyn CameraTrait> = Arc::new(cam);

            let config = EngineConfig {
                gain: None,
                target_centroids: 20,
                database_path: "/sdcard/Documents/default_database.npz".to_string(), // Adjust if needed
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
            
            let server_handle = tokio::spawn(async move {
                control_server::start_server(server_camera, server_client_connected, server_engine_state).await;
            });

            let mut engine = Engine::new(camera, config, client_connected, engine_state, None);
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
pub extern "system" fn Java_com_example_olive_MainActivity_startNativeCameraEngine(
    _env: JNIEnv,
    _class: JClass,
) {
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
            use olive_engine::engine::{Engine, EngineConfig};
            use olive_engine::control_server;
            use std::sync::Arc;

            let mut cam = AndroidCamera::new();
            if let Err(e) = cam.start_capture() {
                log::error!("Failed to start Android camera: {}", e);
                return;
            }
            let camera: Arc<dyn CameraTrait> = Arc::new(cam);

            let config = EngineConfig {
                gain: None,
                target_centroids: 20,
                database_path: "/sdcard/Documents/default_database.npz".to_string(), // Adjust if needed
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
            
            let server_handle = tokio::spawn(async move {
                control_server::start_server(server_camera, server_client_connected, server_engine_state).await;
            });

            let mut engine = Engine::new(camera, config, client_connected, engine_state, None);
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
pub extern "system" fn Java_com_example_olive_MainActivity_stopEngine(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Some(tx) = ENGINE_STOP_TX.lock().unwrap().take() {
        let _ = tx.send(());
    }
}