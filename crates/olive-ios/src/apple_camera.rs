// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

use log::info;
use olive_engine::{CameraConfig, CameraTrait, Image};
use std::sync::{
    Arc, Condvar, Mutex,
    atomic::{AtomicI32, AtomicU32, Ordering},
};
use std::time::{Duration, Instant, SystemTime};

pub const OPTICAL_INFINITY_FOCUS: f32 = 0.92;
pub const MIN_EXPOSURE_US: i32 = 16_667;
pub const MAX_EXPOSURE_US: i32 = 1_000_000;

pub type AppleCameraControlCallback =
    extern "C" fn(exposure_us: i32, gain: f32, focus: f32, zoom: f32);

static CAMERA_CONTROL_CB: Mutex<Option<AppleCameraControlCallback>> = Mutex::new(None);
static ACTIVE_APPLE_CAMERA: Mutex<Option<Arc<AppleCamera>>> = Mutex::new(None);

#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_register_camera_control_callback(cb: AppleCameraControlCallback) {
    *CAMERA_CONTROL_CB.lock().unwrap() = Some(cb);
}

#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_unregister_camera_control_callback() {
    *CAMERA_CONTROL_CB.lock().unwrap() = None;
}

pub fn set_active_apple_camera(camera: Option<Arc<AppleCamera>>) {
    *ACTIVE_APPLE_CAMERA.lock().unwrap() = camera;
}

pub fn get_active_apple_camera() -> Option<Arc<AppleCamera>> {
    ACTIVE_APPLE_CAMERA.lock().unwrap().clone()
}

pub struct AppleCamera {
    config: Mutex<CameraConfig>,
    exposure_us: AtomicI32,
    gain: AtomicI32,
    focus: AtomicU32,
    zoom: AtomicU32,
    last_image: Mutex<Option<Arc<Image>>>,
    image_cvar: Condvar,
    saved_state: Mutex<Option<(i32, f32, f32)>>,
}

impl AppleCamera {
    pub fn new(width: u32, height: u32, initial_fov: Option<f32>) -> Arc<Self> {
        let config = CameraConfig {
            model: "Apple AVFoundation Camera".to_string(),
            width,
            height,
            fps: 1,
            crop: None,
            initial_fov,
        };

        Arc::new(Self {
            config: Mutex::new(config),
            exposure_us: AtomicI32::new(250_000),
            gain: AtomicI32::new(100),
            focus: AtomicU32::new(OPTICAL_INFINITY_FOCUS.to_bits()),
            zoom: AtomicU32::new(1.0f32.to_bits()),
            last_image: Mutex::new(None),
            image_cvar: Condvar::new(),
            saved_state: Mutex::new(None),
        })
    }

    pub fn push_frame(&self, image: Arc<Image>) {
        let mut lock = self.last_image.lock().unwrap();
        *lock = Some(image);
        self.image_cvar.notify_all();
    }
}

impl CameraTrait for AppleCamera {
    fn get_config(&self) -> Option<CameraConfig> {
        Some(self.config.lock().unwrap().clone())
    }

    fn start_capture(&mut self) -> Result<(), String> {
        info!("AppleCamera capture started");
        Ok(())
    }

    fn stop_capture(&self) {
        info!("AppleCamera capture stopped");
        self.image_cvar.notify_all();
    }

    fn get_gain(&self) -> f32 {
        self.gain.load(Ordering::Relaxed) as f32
    }

    fn get_gain_range(&self) -> (f32, f32, f32) {
        // iPhone min ISO ~50, max ISO ~3200, analog limit ~3200
        (50.0, 3200.0, 3200.0)
    }

    fn set_gain(&self, gain: f32) {
        self.gain.store(gain as i32, Ordering::SeqCst);
        let cb = { *CAMERA_CONTROL_CB.lock().unwrap() };
        if let Some(cb) = cb {
            cb(-1, gain, -1.0, -1.0);
        }
    }

    fn get_exposure(&self) -> i32 {
        self.exposure_us.load(Ordering::Relaxed)
    }

    fn set_exposure(&self, exposure_us: i32) {
        self.exposure_us.store(exposure_us, Ordering::SeqCst);
        let cb = { *CAMERA_CONTROL_CB.lock().unwrap() };
        if let Some(cb) = cb {
            cb(exposure_us, -1.0, -1.0, -1.0);
        }
    }

    fn get_exposure_range(&self) -> Option<(i32, i32)> {
        Some((MIN_EXPOSURE_US, MAX_EXPOSURE_US))
    }

    fn get_focus(&self) -> Option<f32> {
        Some(f32::from_bits(self.focus.load(Ordering::Relaxed)))
    }

    fn set_focus(&self, distance: f32) {
        self.focus.store(distance.to_bits(), Ordering::SeqCst);
        let cb = { *CAMERA_CONTROL_CB.lock().unwrap() };
        if let Some(cb) = cb {
            cb(-1, -1.0, distance, -1.0);
        }
    }

    fn get_zoom(&self) -> Option<f32> {
        Some(f32::from_bits(self.zoom.load(Ordering::Relaxed)))
    }

    fn set_zoom(&self, zoom: f32) {
        self.zoom.store(zoom.to_bits(), Ordering::SeqCst);
        let cb = { *CAMERA_CONTROL_CB.lock().unwrap() };
        if let Some(cb) = cb {
            cb(-1, -1.0, -1.0, zoom);
        }
    }

    fn wait_for_next_capture(
        &self,
        last_timestamp: Option<SystemTime>,
        timeout: Option<Duration>,
    ) -> Option<Arc<Image>> {
        let mut guard = self.last_image.lock().unwrap();
        let start = Instant::now();
        loop {
            if let Some(ref img) = *guard {
                if last_timestamp.map_or(true, |t| img.timestamp > t) {
                    return Some(Arc::clone(img));
                }
            }
            let remaining = match timeout {
                Some(t) => {
                    let elapsed = start.elapsed();
                    if elapsed >= t {
                        return None;
                    }
                    t - elapsed
                }
                None => Duration::from_millis(1500),
            };
            let (new_guard, wait_res) = self.image_cvar.wait_timeout(guard, remaining).unwrap();
            guard = new_guard;
            if wait_res.timed_out() && timeout.is_some() {
                // Final check before returning None
                if let Some(ref img) = *guard {
                    if last_timestamp.map_or(true, |t| img.timestamp > t) {
                        return Some(Arc::clone(img));
                    }
                }
                return None;
            }
        }
    }

    fn get_last_capture(&self) -> Option<Arc<Image>> {
        self.last_image.lock().unwrap().clone()
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
        let exp = self.get_exposure();
        let g = self.get_gain();
        let f = self.get_focus().unwrap_or(OPTICAL_INFINITY_FOCUS);
        *self.saved_state.lock().unwrap() = Some((exp, g, f));
    }

    fn restore_state(&self) {
        if let Some((exp, g, f)) = *self.saved_state.lock().unwrap() {
            self.set_exposure(exp);
            self.set_gain(g);
            self.set_focus(f);
        }
    }
}

/// Push a camera luminance frame from Swift / AVFoundation.
///
/// If `stride == width`, performs a direct block copy.
/// If `stride > width`, strips row padding row-by-row into a contiguous buffer of `width * height` bytes.
#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_push_camera_frame(
    buffer: *const u8,
    width: u32,
    height: u32,
    stride: u32,
    _timestamp_ns: i64,
    applied_exposure_us: i32,
    applied_iso: f32,
) {
    if buffer.is_null() || width == 0 || height == 0 || stride < width {
        return;
    }

    let total_pixels = (width * height) as usize;
    let mut data = Vec::with_capacity(total_pixels);

    unsafe {
        if stride == width {
            let slice = std::slice::from_raw_parts(buffer, total_pixels);
            data.extend_from_slice(slice);
        } else {
            for row in 0..height {
                let row_ptr = buffer.add((row * stride) as usize);
                let row_slice = std::slice::from_raw_parts(row_ptr, width as usize);
                data.extend_from_slice(row_slice);
            }
        }
    }

    let midpoint_offset_us = (applied_exposure_us.max(0) as u64) / 2;
    let frame_timestamp = SystemTime::now()
        .checked_sub(Duration::from_micros(midpoint_offset_us))
        .unwrap_or_else(SystemTime::now);

    let image = Arc::new(Image {
        data,
        width,
        height,
        timestamp: frame_timestamp,
        exposure_time_us: applied_exposure_us,
        gain: applied_iso,
        post_process_time: Duration::ZERO,
        frame_interval_us: 0,
        metadata_valid: true,
    });

    if let Some(camera) = get_active_apple_camera() {
        camera.push_frame(image);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_optical_infinity_initial_focus() {
        let camera = AppleCamera::new(1920, 1080, Some(65.0));
        let focus = camera.get_focus().expect("Focus should be available");
        assert!((focus - OPTICAL_INFINITY_FOCUS).abs() < 1e-4);
    }

    #[test]
    fn test_atomic_focus_bits() {
        let camera = AppleCamera::new(1920, 1080, None);
        camera.set_focus(0.918);
        let f = camera.get_focus().unwrap();
        assert!((f - 0.918).abs() < 1e-4);
    }

    #[test]
    fn test_stride_aware_frame_push() {
        let camera = AppleCamera::new(4, 2, None);
        set_active_apple_camera(Some(camera.clone()));

        // 4 pixels per row with 2 bytes padding -> stride = 6
        let raw_buffer: [u8; 12] = [
            10, 20, 30, 40, 255, 255, // row 0 + pad
            50, 60, 70, 80, 255, 255, // row 1 + pad
        ];

        olive_ios_push_camera_frame(raw_buffer.as_ptr(), 4, 2, 6, 0, 33000, 100.0);

        let img = camera
            .get_last_capture()
            .expect("Should have received image");
        assert_eq!(img.width, 4);
        assert_eq!(img.height, 2);
        assert_eq!(img.data, vec![10, 20, 30, 40, 50, 60, 70, 80]);

        set_active_apple_camera(None);
    }

    #[test]
    fn test_exposure_range_and_adjustment() {
        let camera = AppleCamera::new(1920, 1080, None);
        assert_eq!(camera.get_exposure_range(), Some((16_667, 1_000_000)));

        camera.set_exposure(500_000);
        assert_eq!(camera.get_exposure(), 500_000);

        // Adjust sensitivity up by 2.5x -> should clamp to 1_000_000
        assert!(camera.adjust_sensitivity(2.5));
        assert_eq!(camera.get_exposure(), 1_000_000);

        // Further increase returns false because already at max
        assert!(!camera.adjust_sensitivity(1.5));
        assert_eq!(camera.get_exposure(), 1_000_000);
    }
}
