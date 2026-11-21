// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

use log::{error, info, warn};
use olive_engine::CameraTrait;
use olive_engine::engine::EngineStateData;
use olive_imu::{ImuDevice, PersistentStorage};
use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::sync::{
    Arc, Mutex, RwLock,
    atomic::{AtomicBool, Ordering},
};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

pub mod apple_camera;
pub mod apple_imu;

pub use apple_camera::{
    AppleCamera, AppleCameraControlCallback, OPTICAL_INFINITY_FOCUS, olive_ios_push_camera_frame,
    olive_ios_register_camera_control_callback, olive_ios_unregister_camera_control_callback,
};
pub use apple_imu::{AppleImu, olive_ios_push_gyro_update};

pub type AppleStorageGetCallback = extern "C" fn(key: *const c_char) -> *mut c_char;
pub type AppleStorageSetCallback = extern "C" fn(key: *const c_char, value: *const c_char);
pub type AppleStorageRemoveCallback = extern "C" fn(key: *const c_char);

static STORAGE_GET_CB: Mutex<Option<AppleStorageGetCallback>> = Mutex::new(None);
static STORAGE_SET_CB: Mutex<Option<AppleStorageSetCallback>> = Mutex::new(None);
static STORAGE_REMOVE_CB: Mutex<Option<AppleStorageRemoveCallback>> = Mutex::new(None);

unsafe extern "C" {
    fn free(ptr: *mut std::ffi::c_void);
}

#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_register_storage_callbacks(
    get_cb: AppleStorageGetCallback,
    set_cb: AppleStorageSetCallback,
    remove_cb: AppleStorageRemoveCallback,
) {
    *STORAGE_GET_CB.lock().unwrap() = Some(get_cb);
    *STORAGE_SET_CB.lock().unwrap() = Some(set_cb);
    *STORAGE_REMOVE_CB.lock().unwrap() = Some(remove_cb);
}

pub struct IosStorage {
    pub is_external: bool,
}

impl olive_imu::PersistentStorage for IosStorage {
    fn get(&self, key: &str) -> Option<String> {
        if key == "imu_calibration" && self.is_external {
            return None;
        }
        let cb = (*STORAGE_GET_CB.lock().unwrap())?;
        let c_key = CString::new(key).ok()?;
        let res_ptr = cb(c_key.as_ptr());
        if res_ptr.is_null() {
            None
        } else {
            let s = unsafe { CStr::from_ptr(res_ptr) }
                .to_str()
                .ok()
                .map(|s| s.to_string());
            unsafe { free(res_ptr as *mut std::ffi::c_void) };
            s
        }
    }

    fn set(&self, key: &str, value: &str) {
        if key == "imu_calibration" && self.is_external {
            return;
        }
        if let Some(cb) = *STORAGE_SET_CB.lock().unwrap() {
            if let (Ok(c_key), Ok(c_val)) = (CString::new(key), CString::new(value)) {
                cb(c_key.as_ptr(), c_val.as_ptr());
            }
        }
    }

    fn remove(&self, key: &str) {
        if key == "imu_calibration" && self.is_external {
            return;
        }
        if let Some(cb) = *STORAGE_REMOVE_CB.lock().unwrap() {
            if let Ok(c_key) = CString::new(key) {
                cb(c_key.as_ptr());
            }
        }
    }
}

static RUNTIME: Mutex<Option<tokio::runtime::Runtime>> = Mutex::new(None);
static TELESCOPE_RUNTIME: Mutex<Option<tokio::runtime::Runtime>> = Mutex::new(None);
static ENGINE_STOP_TX: Mutex<Option<tokio::sync::oneshot::Sender<()>>> = Mutex::new(None);
static ENGINE_IS_RUNNING: Mutex<Option<Arc<AtomicBool>>> = Mutex::new(None);
static ENGINE_STATE: Mutex<Option<Arc<RwLock<EngineStateData>>>> = Mutex::new(None);
static CAMERA: Mutex<Option<Arc<dyn CameraTrait>>> = Mutex::new(None);
static MAIN_ENGINE_THREAD: Mutex<Option<JoinHandle<()>>> = Mutex::new(None);
static LAST_BUFFER_WARN: Mutex<Option<Instant>> = Mutex::new(None);

// --- FFI Helpers ---

unsafe fn c_str_to_str<'a>(ptr: *const c_char) -> &'a str {
    if ptr.is_null() {
        ""
    } else {
        unsafe { CStr::from_ptr(ptr) }.to_str().unwrap_or("")
    }
}

fn str_to_c_char_ptr(s: &str) -> *mut c_char {
    CString::new(s).unwrap_or_default().into_raw()
}

#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_free_string(ptr: *mut c_char) {
    if !ptr.is_null() {
        unsafe {
            let _ = CString::from_raw(ptr);
        }
    }
}

static LOGGER_INIT: std::sync::Once = std::sync::Once::new();

fn init_ios_logger() {
    LOGGER_INIT.call_once(|| {
        let _ = oslog::OsLogger::new("com.oakamil.olive")
            .level_filter(log::LevelFilter::Info)
            .category_level_filter("engine", log::LevelFilter::Debug)
            .init();
        info!("Olive iOS logging initialized via oslog");
    });
}

// --- Engine Lifecycle & Control Exports ---

#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_start_engine(
    width: u32,
    height: u32,
    motion_sensor_mode: i32,
    downsample_mode: i32,
    lat: f64,
    lon: f64,
    auto_mode: bool,
    initial_fov_deg: f32,
    camera_id: *const c_char,
    initial_zoom: f32,
) -> bool {
    init_ios_logger();
    let camera_id_str = unsafe { c_str_to_str(camera_id) };
    let is_demo = camera_id_str == "demo";
    let is_external = camera_id_str == "external";

    let fov = if initial_fov_deg > 0.0 {
        Some(initial_fov_deg)
    } else {
        None
    };

    let camera: Arc<dyn CameraTrait> = if is_demo {
        use olive_engine::dummy_camera::DummyCamera;
        let mut cam = DummyCamera::new();
        if let Err(e) = cam.start_capture() {
            error!("Failed to start demo camera: {}", e);
            return false;
        }
        apple_camera::set_active_apple_camera(None);
        Arc::new(cam)
    } else {
        let cam = AppleCamera::new(
            width,
            height,
            if is_external { None } else { fov },
            if is_external { 1.0 } else { initial_zoom },
        );
        apple_camera::set_active_apple_camera(Some(cam.clone()));
        cam
    };
    *CAMERA.lock().unwrap() = Some(camera.clone());

    let bundle_dir = std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|p| p.to_path_buf()))
        .unwrap_or_else(|| std::path::PathBuf::from("."));

    let db_candidate = bundle_dir.join("default_database.npz");
    let database_path = if db_candidate.exists() {
        db_candidate.to_string_lossy().to_string()
    } else if std::path::Path::new("default_database.npz").exists() {
        "default_database.npz".to_string()
    } else if std::path::Path::new("app/src/main/assets/default_database.npz").exists() {
        "app/src/main/assets/default_database.npz".to_string()
    } else {
        db_candidate.to_string_lossy().to_string()
    };
    info!(
        "Starting Olive engine with database_path: {}",
        database_path
    );

    let config = olive_engine::engine::EngineConfig {
        gain: None,
        target_centroids: 20,
        database_path,
        isp: false,
        bg_mode: olive_engine::engine::BgMode::Block,
        extractor_type: olive_engine::engine::Extractor::default(),
        crop: None,
        downsample: if is_demo { 0 } else { downsample_mode as u8 },
        auto_mode: auto_mode && !is_demo,
        is_adaptive_camera: !is_demo && !is_external,
        noise_filter: !is_external,
        initial_fov: if is_demo || is_external { None } else { fov },
        use_observer_location: !is_demo,
    };

    let client_connected = Arc::new(AtomicBool::new(false));

    let mut initial_state = EngineStateData::default();
    if (-90.0..=90.0).contains(&lat) && (-180.0..=180.0).contains(&lon) {
        initial_state.client_latitude = Some(lat);
        initial_state.client_longitude = Some(lon);
    }

    let engine_state = Arc::new(RwLock::new(initial_state));
    *ENGINE_STATE.lock().unwrap() = Some(engine_state.clone());

    let imu_type = if motion_sensor_mode != 0 {
        let mut imu_dev = AppleImu::new();
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

    let storage: Option<Arc<dyn PersistentStorage>> = Some(Arc::new(IosStorage { is_external }));

    let (tx, rx) = tokio::sync::oneshot::channel::<()>();
    *ENGINE_STOP_TX.lock().unwrap() = Some(tx);

    let (ready_tx, ready_rx) = std::sync::mpsc::channel::<()>();

    let rt_handle = {
        let mut runtime_lock = RUNTIME.lock().unwrap();
        if runtime_lock.is_none() {
            *runtime_lock = Some(
                tokio::runtime::Builder::new_multi_thread()
                    .enable_all()
                    .build()
                    .unwrap(),
            );
        }
        runtime_lock.as_ref().unwrap().handle().clone()
    };

    let camera_clone = camera.clone();
    let client_conn_clone = client_connected.clone();
    let engine_state_clone = engine_state.clone();
    let telescope_handle = telescope_rt.handle().clone();

    let new_thread = std::thread::spawn(move || {
        rt_handle.block_on(async move {
            let server_cam = camera_clone.clone();
            let server_conn = client_conn_clone.clone();
            let server_st = engine_state_clone.clone();

            let server_handle = tokio::spawn(async move {
                olive_engine::control_server::start_server(server_cam, server_conn, server_st)
                    .await;
            });

            if !std::path::Path::new(&config.database_path).exists() {
                error!(
                    "FATAL: Database path does not exist: {}",
                    &config.database_path
                );
                return;
            }

            info!("Initializing Olive Engine instance...");
            let mut engine = olive_engine::engine::Engine::new(
                camera_clone,
                config,
                client_conn_clone,
                engine_state_clone,
                imu_type,
                Some(telescope_handle),
                storage,
                None,
                None,
            )
            .await;
            info!("Olive Engine created successfully.");

            if (-90.0..=90.0).contains(&lat) && (-180.0..=180.0).contains(&lon) {
                engine.fused_solver.set_observer_location(lat, lon);
            }

            let _ = engine.fused_solver.start_imu();
            let is_running_clone = engine.is_running.clone();
            *ENGINE_IS_RUNNING.lock().unwrap() = Some(is_running_clone.clone());
            let telescope_server_handle = engine.telescope_server_handle.take();

            let engine_thread = std::thread::spawn(move || {
                info!("Olive Engine solver loop starting...");
                let handles = engine.start();
                let _ = handles.0.join();
                let _ = handles.1.join();
                info!("Olive Engine solver loop stopped.");
            });

            let _ = ready_tx.send(());

            // Wait for stop signal
            let _ = rx.await;
            is_running_clone.store(false, Ordering::Relaxed);
            server_handle.abort();
            let _ = server_handle.await;

            if let Some(handle) = telescope_server_handle {
                handle.abort();
                let _ = handle.await;
            }

            let _ = engine_thread.join();
        });
    });

    match ready_rx.recv() {
        Ok(_) => {
            *MAIN_ENGINE_THREAD.lock().unwrap() = Some(new_thread);
            info!("olive_ios_start_engine: Engine successfully started.");
            true
        }
        Err(e) => {
            error!(
                "olive_ios_start_engine: Engine failed to start (thread exited or panicked): {:?}",
                e
            );
            false
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_stop_engine() {
    if let Some(running) = ENGINE_IS_RUNNING.lock().unwrap().take() {
        running.store(false, Ordering::SeqCst);
    }

    if let Some(tx) = ENGINE_STOP_TX.lock().unwrap().take() {
        let _ = tx.send(());
    }

    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        cam.stop_capture();
    }
    apple_camera::set_active_apple_camera(None);

    if let Some(thread) = MAIN_ENGINE_THREAD.lock().unwrap().take() {
        let _ = thread.join();
    }

    *ENGINE_STATE.lock().unwrap() = None;
    *CAMERA.lock().unwrap() = None;
}

#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_get_engine_stats() -> *mut c_char {
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
    str_to_c_char_ptr(&json_str)
}

#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_set_hfr_enabled(enabled: bool) {
    if let Some(state_arc) = ENGINE_STATE.lock().unwrap().as_ref() {
        if let Ok(mut state) = state_arc.write() {
            state.hfr_enabled = enabled;
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_set_engine_sigma(sigma: f32) {
    if let Some(state_arc) = ENGINE_STATE.lock().unwrap().as_ref() {
        if let Ok(mut state) = state_arc.write() {
            state.current_sigma = Some(sigma);
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_set_engine_strict_match(strict: bool) {
    if let Some(state_arc) = ENGINE_STATE.lock().unwrap().as_ref() {
        if let Ok(mut state) = state_arc.write() {
            state.strict_match = strict;
        }
    }
}

// --- Camera Parameter Controls ---

#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_set_camera_exposure(exposure_us: i32) {
    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        cam.set_exposure(exposure_us);
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_get_camera_exposure() -> i32 {
    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        cam.get_exposure()
    } else {
        250_000
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_set_camera_gain(gain: f32) {
    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        cam.set_gain(gain);
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_get_camera_gain() -> f32 {
    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        cam.get_gain()
    } else {
        100.0
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_get_camera_gain_range(
    min_gain: *mut f32,
    max_gain: *mut f32,
    default_gain: *mut f32,
) {
    let (min_g, max_g, def_g) = if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        cam.get_gain_range()
    } else {
        (50.0, 3200.0, 3200.0)
    };
    unsafe {
        if !min_gain.is_null() {
            *min_gain = min_g;
        }
        if !max_gain.is_null() {
            *max_gain = max_g;
        }
        if !default_gain.is_null() {
            *default_gain = def_g;
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_set_camera_focus(distance: f32) {
    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        cam.set_focus(distance);
    }
    if let Some(state_arc) = ENGINE_STATE.lock().unwrap().as_ref() {
        if let Ok(mut state) = state_arc.write() {
            state.focus = Some(distance);
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_get_camera_focus() -> f32 {
    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        cam.get_focus().unwrap_or(OPTICAL_INFINITY_FOCUS)
    } else {
        OPTICAL_INFINITY_FOCUS
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_set_camera_zoom(zoom: f32) {
    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        cam.set_zoom(zoom);
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_get_camera_zoom() -> f32 {
    if let Some(cam) = CAMERA.lock().unwrap().as_ref() {
        cam.get_zoom().unwrap_or(1.0)
    } else {
        1.0
    }
}

// --- Frame Extraction ---

#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_get_latest_frame_into(
    buffer: *mut u8,
    capacity: usize,
    last_timestamp_ms: i64,
) -> i64 {
    if buffer.is_null() || capacity < 8 {
        return 0;
    }

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

            // RGBA requires 4 bytes per pixel + 8 bytes header (width, height)
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
            if capacity >= req_len {
                unsafe {
                    let slice = std::slice::from_raw_parts_mut(buffer, capacity);
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
                }
                return img_ts_ms;
            } else {
                let should_warn = {
                    let mut last = LAST_BUFFER_WARN.lock().unwrap_or_else(|e| e.into_inner());
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
                        "Provided buffer is too small. Need {}, got {}",
                        req_len, capacity
                    );
                }
            }
        }
    }
    0
}

// --- Telescope & Ephemeris Controls ---

#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_set_goto_target(ra: f64, dec: f64, epoch: f64) {
    if let Some(state_arc) = ENGINE_STATE.lock().unwrap().as_ref() {
        if let Ok(mut state) = state_arc.write() {
            state.requested_target_ra = Some(ra);
            state.requested_target_dec = Some(dec);
            state.requested_target_epoch = Some(epoch);
            info!(
                "Target slew requested via iOS C-ABI: RA={}, Dec={}, Epoch={}",
                ra, dec, epoch
            );
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_clear_goto_target() {
    if let Some(state_arc) = ENGINE_STATE.lock().unwrap().as_ref() {
        if let Ok(mut state) = state_arc.write() {
            state.requested_target_ra = None;
            state.requested_target_dec = None;
            state.requested_target_epoch = None;
            state.target_ra = None;
            state.target_dec = None;
            state.requested_target_clear = true;
            info!("Target slew cleared via iOS C-ABI");
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_set_align_target_pixel(x: f64, y: f64) {
    if let Some(state_arc) = ENGINE_STATE.lock().unwrap().as_ref() {
        if let Ok(mut state) = state_arc.write() {
            state.pending_target_pixel_cmd =
                Some(olive_engine::engine::TargetPixelCommand::Set(x, y));
            info!("Align target pixel requested via iOS C-ABI: ({}, {})", x, y);
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_clear_align_target_pixel() {
    if let Some(state_arc) = ENGINE_STATE.lock().unwrap().as_ref() {
        if let Ok(mut state) = state_arc.write() {
            state.pending_target_pixel_cmd = Some(olive_engine::engine::TargetPixelCommand::Clear);
            info!("Clear align target pixel requested via iOS C-ABI");
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_sync_telescope() {
    if let Some(state_arc) = ENGINE_STATE.lock().unwrap().as_ref() {
        if let Ok(mut state) = state_arc.write() {
            state.requested_sync = true;
            info!("Sync telescope requested via iOS C-ABI");
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_sync_telescope_to_target(ra: f64, dec: f64) {
    if let Some(state_arc) = ENGINE_STATE.lock().unwrap().as_ref() {
        if let Ok(mut state) = state_arc.write() {
            state.requested_sync_coords = Some((ra, dec));
            state.requested_sync = true;
            info!(
                "Sync telescope to target ({}, {}) requested via iOS C-ABI",
                ra, dec
            );
        }
    }
}

// --- Ephemeris (Planets, Asteroids, Comets) ---

#[derive(serde::Serialize)]
struct EphemerisObj {
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

fn resolve_bsp_path(bsp_path: *const c_char) -> String {
    let bsp_path_str = unsafe { c_str_to_str(bsp_path) };
    if !bsp_path_str.is_empty() && std::path::Path::new(bsp_path_str).exists() {
        return bsp_path_str.to_string();
    }
    let bundle_dir = std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|p| p.to_path_buf()))
        .unwrap_or_else(|| std::path::PathBuf::from("."));
    let candidate = bundle_dir.join("de421.bsp");
    if candidate.exists() {
        candidate.to_string_lossy().to_string()
    } else {
        "app/src/main/assets/de421.bsp".to_string()
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_get_planets(
    bsp_path: *const c_char,
    time_ms: i64,
    latitude: f64,
    longitude: f64,
) -> *mut c_char {
    use chrono::{Datelike, TimeZone, Timelike};
    use starfield::jplephem::kernel::SpiceKernel;
    use starfield::time::Timescale;

    let actual_bsp = resolve_bsp_path(bsp_path);

    let result = std::panic::catch_unwind(|| {
        let mut kernel = SpiceKernel::open(&actual_bsp).map_err(|e| e.to_string())?;
        let ts = Timescale::default();

        let dt = chrono::Utc
            .timestamp_millis_opt(time_ms)
            .single()
            .ok_or("Invalid time")?;
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
                let (ra_hours, dec_deg, _) = astrometric.radec(None);
                planets.push(EphemerisObj {
                    id,
                    catalog_label: "Planet".to_string(),
                    catalog_entry: common_name.to_string(),
                    ra: ra_hours * 15.0,
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
        Ok::<Vec<EphemerisObj>, String>(planets)
    });

    let json_str = match result {
        Ok(Ok(planets)) => serde_json::to_string(&planets).unwrap_or_else(|_| "[]".to_string()),
        _ => "[]".to_string(),
    };
    str_to_c_char_ptr(&json_str)
}

#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_get_asteroids(
    bsp_path: *const c_char,
    time_ms: i64,
    latitude: f64,
    longitude: f64,
    asteroids_json: *const c_char,
) -> *mut c_char {
    use chrono::{Datelike, TimeZone, Timelike};
    use starfield::constants::{C_AUDAY, GM_SUN};
    use starfield::jplephem::kernel::SpiceKernel;
    use starfield::jplephem_ext::SpiceKernelExt;
    use starfield::positions::Position;
    use starfield::time::Timescale;

    #[derive(serde::Deserialize)]
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

    let actual_bsp = resolve_bsp_path(bsp_path);
    let json_str = unsafe { c_str_to_str(asteroids_json) };

    let result = std::panic::catch_unwind(|| {
        let mut kernel = SpiceKernel::open(&actual_bsp).map_err(|e| e.to_string())?;
        let ts = Timescale::default();

        let dt = chrono::Utc
            .timestamp_millis_opt(time_ms)
            .single()
            .ok_or("Invalid time")?;
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

        let records: Vec<AsteroidRecord> = serde_json::from_str(json_str).unwrap_or_default();
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

            results.push(EphemerisObj {
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

        Ok::<Vec<EphemerisObj>, String>(results)
    });

    let json_str = match result {
        Ok(Ok(asteroids)) => serde_json::to_string(&asteroids).unwrap_or_else(|_| "[]".to_string()),
        Ok(Err(e)) => {
            error!("Asteroids calculation error: {}", e);
            "[]".to_string()
        }
        Err(_) => {
            error!("Asteroids calculation panicked");
            "[]".to_string()
        }
    };
    str_to_c_char_ptr(&json_str)
}

#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_get_comets(
    bsp_path: *const c_char,
    time_ms: i64,
    latitude: f64,
    longitude: f64,
    comets_json: *const c_char,
) -> *mut c_char {
    use chrono::{Datelike, TimeZone, Timelike};
    use starfield::constants::{C_AUDAY, GM_SUN};
    use starfield::jplephem::kernel::SpiceKernel;
    use starfield::jplephem_ext::SpiceKernelExt;
    use starfield::positions::Position;
    use starfield::time::Timescale;

    #[derive(serde::Deserialize)]
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

    let actual_bsp = resolve_bsp_path(bsp_path);
    let json_str = unsafe { c_str_to_str(comets_json) };

    let result = std::panic::catch_unwind(|| {
        let mut kernel = SpiceKernel::open(&actual_bsp).map_err(|e| e.to_string())?;
        let ts = Timescale::default();

        let dt = chrono::Utc
            .timestamp_millis_opt(time_ms)
            .single()
            .ok_or("Invalid time")?;
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

        let records: Vec<CometRecord> = serde_json::from_str(json_str).unwrap_or_default();
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

            results.push(EphemerisObj {
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

        Ok::<Vec<EphemerisObj>, String>(results)
    });

    let json_str = match result {
        Ok(Ok(comets)) => serde_json::to_string(&comets).unwrap_or_else(|_| "[]".to_string()),
        Ok(Err(e)) => {
            error!("Comets calculation error: {}", e);
            "[]".to_string()
        }
        Err(_) => {
            error!("Comets calculation panicked");
            "[]".to_string()
        }
    };
    str_to_c_char_ptr(&json_str)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_c_string_conversion() {
        let orig = "Hello iOS";
        let c_ptr = str_to_c_char_ptr(orig);
        assert!(!c_ptr.is_null());
        let roundtrip = unsafe { c_str_to_str(c_ptr) };
        assert_eq!(orig, roundtrip);
        olive_ios_free_string(c_ptr);
    }

    #[test]
    fn test_stats_and_camera_getters_when_stopped() {
        let stats_ptr = olive_ios_get_engine_stats();
        let stats_str = unsafe { c_str_to_str(stats_ptr) };
        assert_eq!(stats_str, "{}");
        olive_ios_free_string(stats_ptr);

        assert_eq!(olive_ios_get_camera_focus(), OPTICAL_INFINITY_FOCUS);
        assert_eq!(olive_ios_get_camera_zoom(), 1.0);
        assert_eq!(olive_ios_get_camera_gain(), 100.0);
        assert_eq!(olive_ios_get_camera_exposure(), 250_000);
        let (mut min_g, mut max_g, mut def_g) = (0.0f32, 0.0f32, 0.0f32);
        olive_ios_get_camera_gain_range(&mut min_g, &mut max_g, &mut def_g);
        assert_eq!((min_g, max_g, def_g), (50.0, 3200.0, 3200.0));
    }

    #[test]
    fn test_asteroids_and_comets_empty_input() {
        let empty_json = CString::new("[]").unwrap();
        let bsp_path = CString::new("").unwrap();
        let ast_ptr = olive_ios_get_asteroids(
            bsp_path.as_ptr(),
            1700000000000,
            37.0,
            -122.0,
            empty_json.as_ptr(),
        );
        assert!(!ast_ptr.is_null());
        let ast_str = unsafe { c_str_to_str(ast_ptr) };
        assert_eq!(ast_str, "[]");
        olive_ios_free_string(ast_ptr);

        let comet_ptr = olive_ios_get_comets(
            bsp_path.as_ptr(),
            1700000000000,
            37.0,
            -122.0,
            empty_json.as_ptr(),
        );
        assert!(!comet_ptr.is_null());
        let comet_str = unsafe { c_str_to_str(comet_ptr) };
        assert_eq!(comet_str, "[]");
        olive_ios_free_string(comet_ptr);
    }
}
