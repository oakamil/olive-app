// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

use log::info;
use olive_engine::{CameraConfig, CameraTrait, CameraType, Image};
use std::sync::{
    Arc, Condvar, Mutex,
    atomic::{AtomicBool, AtomicI32, AtomicI64, AtomicU32, AtomicUsize, Ordering},
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
    // Stacking and Autonomous Software Pedestal Trim
    stack_count: AtomicUsize,
    is_ae: AtomicBool,
    settle_frames: AtomicU32,
    calibrated: AtomicBool,
    pedestal_delta: AtomicI32,
    user_brightness: AtomicI32,
    accumulator: Mutex<Vec<u8>>,
    current_stack_idx: AtomicUsize,
    last_timestamp_ns: AtomicI64,
    measured_frame_interval_us: AtomicI64,
}

fn measure_p5_and_median(y_plane: &[u8]) -> (i32, i32) {
    let mut hist = [0u32; 256];
    let mut count = 0u32;
    // Step by 64 bytes to align with ARM64 L1 cache lines (saving memory bandwidth)
    for &val in y_plane.iter().step_by(64) {
        hist[val as usize] += 1;
        count += 1;
    }
    if count == 0 {
        return (10, 10);
    }
    let target_p5 = ((count * 5) / 100).max(1);
    let target_median = (count / 2).max(1);
    let mut cum = 0u32;
    let mut p5 = None;
    let mut median = None;

    for (val, &c) in hist.iter().enumerate() {
        cum += c;
        if p5.is_none() && cum >= target_p5 {
            p5 = Some(val as i32);
        }
        if median.is_none() && cum >= target_median {
            median = Some(val as i32);
            break;
        }
    }
    (p5.unwrap_or(10), median.unwrap_or(10))
}

impl AppleCamera {
    pub fn new(width: u32, height: u32, initial_fov: Option<f32>) -> Arc<Self> {
        let config = CameraConfig {
            camera_type: CameraType::Internal {
                id: "back".to_string(),
            },
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
            stack_count: AtomicUsize::new(1),
            is_ae: AtomicBool::new(false),
            settle_frames: AtomicU32::new(0),
            calibrated: AtomicBool::new(true),
            pedestal_delta: AtomicI32::new(0),
            user_brightness: AtomicI32::new(50),
            accumulator: Mutex::new(Vec::new()),
            current_stack_idx: AtomicUsize::new(0),
            last_timestamp_ns: AtomicI64::new(0),
            measured_frame_interval_us: AtomicI64::new(33_333),
        })
    }

    pub fn set_brightness(&self, val: i32) {
        self.user_brightness
            .store(val.clamp(0, 100), Ordering::Relaxed);
    }

    pub fn get_brightness(&self) -> i32 {
        self.user_brightness.load(Ordering::Relaxed)
    }

    pub fn set_stack_count(&self, count: usize) {
        self.stack_count.store(count.max(1), Ordering::SeqCst);
        self.current_stack_idx.store(0, Ordering::Relaxed);
    }

    pub fn set_is_ae(&self, is_ae: bool) {
        self.is_ae.store(is_ae, Ordering::SeqCst);
        self.settle_frames.store(10, Ordering::SeqCst);
        self.calibrated.store(false, Ordering::SeqCst);
        self.pedestal_delta.store(0, Ordering::SeqCst);
        self.current_stack_idx.store(0, Ordering::SeqCst);
    }

    pub fn push_frame(&self, image: Arc<Image>) {
        let mut lock = self.last_image.lock().unwrap();
        *lock = Some(image);
        self.image_cvar.notify_all();
    }

    pub fn push_raw_luminance(
        &self,
        mut data: Vec<u8>,
        width: u32,
        height: u32,
        frame_timestamp: SystemTime,
        timestamp_ns: i64,
        applied_exposure_us: i32,
        applied_iso: f32,
    ) {
        // Cold startup settle: flush initial 10 frames
        let settle = self.settle_frames.load(Ordering::Relaxed);
        if settle > 0 {
            self.settle_frames.store(settle - 1, Ordering::Relaxed);
            return;
        }

        // Autonomous Pedestal Calibration for Class B (Manual/Shutter Priority)
        if !self.calibrated.load(Ordering::Relaxed) {
            if self.is_ae.load(Ordering::Relaxed) {
                // Class A (Sony IMX662 AEC): Native auto-exposure active.
                // Settle frames flushed; bypass pedestal calibration so AEC meters cleanly.
                self.calibrated.store(true, Ordering::Relaxed);
            } else {
                // Class B (SmartSens SC2210): Measure 5th percentile with 64-byte stride (ARM64 cache line)
                let (p5, _median) = measure_p5_and_median(&data);
                let delta = (10 - p5).clamp(-50, 50);
                info!(
                    "AppleCamera Class B pedestal calibrated: P5={}, delta={}",
                    p5, delta
                );
                self.pedestal_delta.store(delta, Ordering::Relaxed);
                self.calibrated.store(true, Ordering::Relaxed);
            }
        }

        let last_ns = self.last_timestamp_ns.swap(timestamp_ns, Ordering::Relaxed);
        let mut measured_us = 0i64;
        if last_ns > 0 && timestamp_ns > last_ns {
            let delta_us = (timestamp_ns - last_ns) / 1_000;
            // Sane frame rate range: 2 FPS to 200 FPS (5,000 µs to 500,000 µs)
            if (5_000..=500_000).contains(&delta_us) {
                self.measured_frame_interval_us
                    .store(delta_us, Ordering::Relaxed);
                measured_us = delta_us;
            }
        }
        if measured_us == 0 {
            measured_us = self.measured_frame_interval_us.load(Ordering::Relaxed);
        }

        let effective_exposure_us = if applied_exposure_us > 0 {
            applied_exposure_us
        } else {
            measured_us as i32
        };

        self.exposure_us
            .store(effective_exposure_us, Ordering::Relaxed);

        let auto_delta = self.pedestal_delta.load(Ordering::Relaxed);
        let user_offset = self.user_brightness.load(Ordering::Relaxed) - 50;
        let delta = auto_delta + user_offset;

        let apply_delta = |b: u8| -> u8 {
            if delta > 0 {
                b.saturating_add(delta.min(255) as u8)
            } else if delta < 0 {
                b.saturating_sub((-delta).min(255) as u8)
            } else {
                b
            }
        };

        let target_stack = self.stack_count.load(Ordering::Relaxed).max(1);

        if target_stack == 1 {
            if delta != 0 {
                for b in &mut data {
                    *b = apply_delta(*b);
                }
            }
            let image = Arc::new(Image {
                data,
                width,
                height,
                timestamp: frame_timestamp,
                exposure_time_us: effective_exposure_us,
                gain: applied_iso,
                post_process_time: Duration::ZERO,
                frame_interval_us: measured_us,
                metadata_valid: true,
            });
            self.push_frame(image);
        } else {
            let mut accum = self.accumulator.lock().unwrap();
            let total_pixels = (width * height) as usize;
            if accum.len() != total_pixels {
                accum.resize(total_pixels, 0);
                self.current_stack_idx.store(0, Ordering::Relaxed);
            }

            let idx = self.current_stack_idx.load(Ordering::Relaxed);
            if idx == 0 {
                if delta == 0 {
                    accum.copy_from_slice(&data);
                } else {
                    for (dst, &src) in accum.iter_mut().zip(&data) {
                        *dst = apply_delta(src);
                    }
                }
                self.current_stack_idx.store(1, Ordering::Relaxed);
            } else {
                for (dst, &src) in accum.iter_mut().zip(&data) {
                    *dst = dst.saturating_add(apply_delta(src));
                }
                let next_idx = idx + 1;
                if next_idx < target_stack {
                    self.current_stack_idx.store(next_idx, Ordering::Relaxed);
                } else {
                    self.current_stack_idx.store(0, Ordering::Relaxed);
                    let stacked_data = accum.clone();
                    let total_exposure_us =
                        effective_exposure_us.saturating_mul(target_stack as i32);
                    let image = Arc::new(Image {
                        data: stacked_data,
                        width,
                        height,
                        timestamp: frame_timestamp,
                        exposure_time_us: total_exposure_us,
                        gain: applied_iso,
                        post_process_time: Duration::ZERO,
                        frame_interval_us: measured_us,
                        metadata_valid: true,
                    });
                    self.push_frame(image);
                }
            }
        }
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

    fn set_stack_count(&self, count: usize) {
        self.set_stack_count(count);
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
    timestamp_ns: i64,
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

    if let Some(camera) = get_active_apple_camera() {
        camera.push_raw_luminance(
            data,
            width,
            height,
            frame_timestamp,
            timestamp_ns,
            applied_exposure_us,
            applied_iso,
        );
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_set_camera_stack_count(count: usize) {
    if let Some(cam) = get_active_apple_camera() {
        cam.set_stack_count(count);
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_set_camera_is_ae(is_ae: bool) {
    if let Some(cam) = get_active_apple_camera() {
        cam.set_is_ae(is_ae);
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_set_camera_brightness(brightness: i32) {
    if let Some(cam) = get_active_apple_camera() {
        cam.set_brightness(brightness);
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_get_camera_brightness() -> i32 {
    get_active_apple_camera().map_or(50, |c| c.get_brightness())
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

    #[test]
    fn test_multi_frame_stacking() {
        let camera = AppleCamera::new(2, 2, None);
        camera.set_stack_count(2);

        // Frame 1
        let f1 = vec![50u8, 100u8, 150u8, 200u8];
        camera.push_raw_luminance(f1, 2, 2, SystemTime::now(), 0, 100_000, 100.0);
        // After 1 frame, stack of 2 is not complete yet
        assert!(camera.get_last_capture().is_none());

        // Frame 2
        let f2 = vec![60u8, 100u8, 120u8, 100u8];
        camera.push_raw_luminance(f2, 2, 2, SystemTime::now(), 0, 100_000, 100.0);

        // Stack complete: 50+60=110, 100+100=200, 150+120=270->255 (saturating), 200+100=300->255 (saturating)
        let stacked = camera.get_last_capture().expect("Stack should be complete");
        assert_eq!(stacked.data, vec![110, 200, 255, 255]);
        assert_eq!(stacked.exposure_time_us, 200_000); // 100_000 * 2
    }

    #[test]
    fn test_pedestal_trim_calibration() {
        let camera = AppleCamera::new(64, 1, None);
        // Arm Class B external camera mode
        camera.set_is_ae(false);
        assert_eq!(camera.settle_frames.load(Ordering::Relaxed), 10);

        // Flush 10 settle frames
        for _ in 0..10 {
            camera.push_raw_luminance(vec![0; 64], 64, 1, SystemTime::now(), 0, 33000, 100.0);
        }
        assert_eq!(camera.settle_frames.load(Ordering::Relaxed), 0);
        assert!(!camera.calibrated.load(Ordering::Relaxed));

        // Frame 11: Calibration frame with low floor (P5 = 4)
        // Stride 64 samples the first pixel
        let mut cal_frame = vec![4u8; 64];
        cal_frame[0] = 4;
        camera.push_raw_luminance(cal_frame, 64, 1, SystemTime::now(), 0, 33000, 100.0);

        assert!(camera.calibrated.load(Ordering::Relaxed));
        // delta = 10 - 4 = 6
        assert_eq!(camera.pedestal_delta.load(Ordering::Relaxed), 6);

        // Verify the delivered image has the +6 pedestal offset applied (4 + 6 = 10)
        let img = camera
            .get_last_capture()
            .expect("Should have received calibrated image");
        assert_eq!(img.data[0], 10);
    }

    #[test]
    fn test_software_brightness_and_exposure_sync() {
        let camera = AppleCamera::new(2, 2, None);
        // Stacking = 1, calibrated = true, delta = 0
        assert_eq!(camera.get_brightness(), 50);
        assert_eq!(camera.get_exposure(), 250_000);

        // Frame with exposure 33,333 us and default brightness 50 (offset = 0)
        camera.push_raw_luminance(
            vec![20, 30, 40, 50],
            2,
            2,
            SystemTime::now(),
            0,
            33_333,
            100.0,
        );
        assert_eq!(camera.get_exposure(), 33_333);
        let img = camera.get_last_capture().unwrap();
        assert_eq!(img.exposure_time_us, 33_333);
        assert_eq!(img.data, vec![20, 30, 40, 50]);

        // User sets brightness to 60 (offset = +10)
        camera.set_brightness(60);
        assert_eq!(camera.get_brightness(), 60);
        camera.push_raw_luminance(
            vec![20, 30, 250, 50],
            2,
            2,
            SystemTime::now(),
            0,
            33_333,
            100.0,
        );
        let img = camera.get_last_capture().unwrap();
        assert_eq!(img.data, vec![30, 40, 255, 60]); // 250 + 10 saturates to 255

        // User sets brightness to 40 (offset = -10)
        camera.set_brightness(40);
        assert_eq!(camera.get_brightness(), 40);
        camera.push_raw_luminance(
            vec![5, 30, 40, 50],
            2,
            2,
            SystemTime::now(),
            0,
            33_333,
            100.0,
        );
        let img = camera.get_last_capture().unwrap();
        assert_eq!(img.data, vec![0, 20, 30, 40]); // 5 - 10 saturates to 0
    }
}
