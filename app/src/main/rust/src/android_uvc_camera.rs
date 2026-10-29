use jni::{JavaVM, objects::GlobalRef};
use olive_engine::{CameraConfig, CameraTrait, CameraType, Image};
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU32, AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::SystemTime;

pub struct AndroidUvcCamera {
    config: Mutex<CameraConfig>,
    exposure_us: AtomicI32,
    gain: AtomicI32,
    brightness: AtomicI32,
    stack_count: AtomicUsize,

    // Autonomous calibration state (Auto Mode Only)
    cal_phase: AtomicU32, // 0 = Settle Brightness, 1 = Verify at Gain 100, 2 = Final Lock
    calibrated: AtomicBool,
    settle_frames: AtomicU32,
    cal_attempts: AtomicU32,

    // Accumulation state
    accumulator: Mutex<Vec<u8>>,
    current_stack_idx: AtomicUsize,

    // The final image given to the Engine with Condition Variable for blocking
    last_image: Mutex<Option<Arc<Image>>>,
    image_cvar: Condvar,

    jvm: Option<JavaVM>,
    uvc_wrapper: Option<GlobalRef>,
    is_running: std::sync::atomic::AtomicBool,
}

impl AndroidUvcCamera {
    pub fn new(
        width: u32,
        height: u32,
        fps: u32,
        jvm: Option<JavaVM>,
        uvc_wrapper: Option<GlobalRef>,
        is_auto: bool,
    ) -> Self {
        let initial_b = if let (Some(jvm), Some(wrapper)) = (&jvm, &uvc_wrapper) {
            if let Ok(mut env) = jvm.attach_current_thread() {
                let res = env.call_method(wrapper.as_obj(), "getBrightness", "()I", &[]);
                let _ = env.exception_clear();
                res.ok().and_then(|v| v.i().ok()).unwrap_or(50)
            } else {
                50
            }
        } else {
            50
        };

        Self {
            config: Mutex::new(CameraConfig {
                camera_type: CameraType::External,
                model: "External UVC".to_string(),
                width,
                height,
                fps,
                crop: None,
                initial_fov: None,
            }),
            exposure_us: AtomicI32::new((1_000_000 / fps.max(1)) as i32),
            gain: AtomicI32::new(100),
            brightness: AtomicI32::new(initial_b),
            cal_phase: AtomicU32::new(0),
            calibrated: AtomicBool::new(!is_auto), // Manual mode bypasses calibration, Auto mode runs it
            settle_frames: AtomicU32::new(10),     // Universal 10 initial settle frames on boot
            cal_attempts: AtomicU32::new(0),
            stack_count: AtomicUsize::new(1),
            accumulator: Mutex::new(Vec::new()),
            current_stack_idx: AtomicUsize::new(0),
            last_image: Mutex::new(None),
            image_cvar: Condvar::new(),
            jvm,
            uvc_wrapper,
            is_running: std::sync::atomic::AtomicBool::new(true),
        }
    }

    fn measure_p5_and_median(y_plane: &[u8]) -> (i32, i32) {
        let mut hist = [0u32; 256];
        let mut count = 0u32;
        for &val in y_plane.iter().step_by(8) {
            hist[val as usize] += 1;
            count += 1;
        }

        if count == 0 {
            return (10, 10);
        }

        let target_p5 = (count * 5) / 100;
        let target_median = count / 2;
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

        (p5.unwrap_or(0), median.unwrap_or(0))
    }

    pub fn push_external_frame(&self, nv21_data: &[u8], width: u32, height: u32) {
        let y_plane_size = (width * height) as usize;
        if nv21_data.len() < y_plane_size {
            return;
        }

        // --- Fast Autonomous Calibration State Machine (Auto Mode Only) ---
        if !self.calibrated.load(Ordering::Relaxed) {
            let remaining = self.settle_frames.load(Ordering::Relaxed);
            if remaining > 0 {
                self.settle_frames.store(remaining - 1, Ordering::Relaxed);
                return; // DROP FRAME: flushing hardware pipeline
            }

            // After initial settle frames have flushed the cold startup buffer,
            // query if the camera is running pure Auto-Exposure (Mode 8 or Mode 2).
            let is_ae = if let (Some(jvm), Some(wrapper)) = (&self.jvm, &self.uvc_wrapper) {
                if let Ok(mut env) = jvm.attach_current_thread() {
                    let res = env.call_method(wrapper.as_obj(), "isAutoExposure", "()Z", &[]);
                    let _ = env.exception_clear();
                    res.ok().and_then(|v| v.z().ok()).unwrap_or(false)
                } else {
                    false
                }
            } else {
                false
            };

            if is_ae {
                // Mode 8 (Aperture Priority Auto-Exposure - Sony IMX662):
                // Initial 10 frames already flushed; bypass brightness calibration so Sony AEC runs cleanly.
                log::info!(
                    "Camera in Auto-Exposure mode: bypassing brightness calibration, locking."
                );
                self.calibrated.store(true, Ordering::Relaxed);
                // Proceed to deliver this settled frame to the Engine!
            } else {
                let y_plane = &nv21_data[0..y_plane_size];
                let (p5, median) = Self::measure_p5_and_median(y_plane);
                let phase = self.cal_phase.load(Ordering::Relaxed);

                match phase {
                    0 => {
                        // Phase 0: Settle Baseline Brightness
                        let attempts = self.cal_attempts.fetch_add(1, Ordering::Relaxed);

                        if median > 100 {
                            // Daytime/bright scene: skip brightness cal, set gain to 100
                            log::info!(
                                "Daytime scene detected (median={}). Skipping brightness cal.",
                                median
                            );
                            self.set_gain(100.0);
                            self.settle_frames.store(3, Ordering::Relaxed);
                            self.cal_phase.store(2, Ordering::Relaxed); // Verify/lock
                            return;
                        }

                        if attempts < 3 && (p5 < 5 || p5 > 14) {
                            let current_b = self.brightness.load(Ordering::Relaxed);
                            let delta = ((p5 - 10) as f32 / 1.5).round() as i32;
                            let step = if delta == 0 {
                                if p5 > 14 { 1 } else { -1 }
                            } else {
                                delta
                            };
                            let new_b = (current_b - step).clamp(0, 100);

                            if new_b != current_b {
                                log::info!(
                                    "Auto-calibrating brightness: P5={}, adjusting {} -> {}",
                                    p5,
                                    current_b,
                                    new_b
                                );
                                self.set_brightness(new_b);
                                self.settle_frames.store(3, Ordering::Relaxed);
                                return; // DROP FRAME
                            }
                        }

                        // Brightness settled! Now transition to Phase 1: set gain to 100
                        log::info!("Brightness settled at P5={}. Setting gain to 100...", p5);
                        self.set_gain(100.0);
                        self.settle_frames.store(3, Ordering::Relaxed);
                        self.cal_phase.store(1, Ordering::Relaxed);
                        return; // DROP FRAME while gain settles
                    }
                    1 => {
                        // Phase 1: Verify at Gain 100
                        if p5 >= 5 && p5 <= 14 {
                            log::info!(
                                "Calibration verified at Gain 100: P5={} (Optimal). Locking calibration.",
                                p5
                            );
                            self.calibrated.store(true, Ordering::Relaxed);
                            // Do not return: pass this settled frame directly to Engine!
                        } else {
                            // Rare trim step if gain caused unexpected pedestal shift
                            log::info!("Post-gain trim needed at Gain 100: P5={}", p5);
                            let current_b = self.brightness.load(Ordering::Relaxed);
                            let delta = ((p5 - 10) as f32 / 1.5).round() as i32;
                            let step = if delta == 0 {
                                if p5 > 14 { 1 } else { -1 }
                            } else {
                                delta
                            };
                            let new_b = (current_b - step).clamp(0, 100);

                            self.set_brightness(new_b);
                            self.settle_frames.store(3, Ordering::Relaxed);
                            self.cal_phase.store(2, Ordering::Relaxed);
                            return; // DROP FRAME
                        }
                    }
                    _ => {
                        // Final Lock Phase
                        log::info!("Calibration locked: final P5={}", p5);
                        self.calibrated.store(true, Ordering::Relaxed);
                        // Do not return: pass this frame to Engine!
                    }
                }
            }
        }

        let target_stack = self.stack_count.load(Ordering::Relaxed).max(1);
        let mut accum = self.accumulator.lock().unwrap();

        if accum.len() != y_plane_size {
            *accum = vec![0; y_plane_size];
            self.current_stack_idx.store(0, Ordering::Relaxed);
        }

        let idx = self.current_stack_idx.fetch_add(1, Ordering::Relaxed);

        if idx == 0 {
            accum.copy_from_slice(&nv21_data[0..y_plane_size]);
        } else {
            for (a, &b) in accum.iter_mut().zip(&nv21_data[0..y_plane_size]) {
                *a = a.saturating_add(b);
            }
        }

        if idx + 1 >= target_stack {
            let current_exposure = self.exposure_us.load(Ordering::Relaxed);
            let total_exposure_us = (current_exposure * (target_stack as i32)) as u64;
            let half_exposure = std::time::Duration::from_micros(total_exposure_us / 2);
            let pipeline_latency = std::time::Duration::from_millis(30);

            let timestamp = SystemTime::now()
                .checked_sub(half_exposure + pipeline_latency)
                .unwrap_or_else(SystemTime::now);

            let img = Image {
                data: accum.clone(),
                width,
                height,
                timestamp,
                exposure_time_us: total_exposure_us as i32,
                gain: 1.0,
                post_process_time: std::time::Duration::from_millis(0),
                frame_interval_us: 0,
                metadata_valid: true,
                zoom: 1.0,
            };

            let mut lock = self.last_image.lock().unwrap();
            *lock = Some(Arc::new(img));
            self.image_cvar.notify_all(); // Wake up wait_for_next_capture

            self.current_stack_idx.store(0, Ordering::Relaxed);
        }
    }
}

impl CameraTrait for AndroidUvcCamera {
    fn get_config(&self) -> Option<CameraConfig> {
        Some(self.config.lock().unwrap().clone())
    }

    fn set_stack_count(&self, count: usize) {
        self.stack_count
            .store(count.clamp(1, 10), Ordering::Relaxed);
    }

    fn wait_for_next_capture(
        &self,
        last_timestamp: Option<SystemTime>,
        timeout: Option<std::time::Duration>,
    ) -> Option<Arc<Image>> {
        let mut lock = self.last_image.lock().unwrap();
        loop {
            if !self.is_running.load(Ordering::Relaxed) {
                return None;
            }
            if let Some(img) = &*lock {
                if last_timestamp.is_none() || Some(img.timestamp) != last_timestamp {
                    return Some(img.clone());
                }
            }
            if let Some(duration) = timeout {
                let (new_lock, result) = self.image_cvar.wait_timeout(lock, duration).unwrap();
                lock = new_lock;
                if result.timed_out() {
                    if !self.is_running.load(Ordering::Relaxed) {
                        return None;
                    }
                    if let Some(img) = &*lock {
                        if last_timestamp.is_none() || Some(img.timestamp) != last_timestamp {
                            return Some(img.clone());
                        }
                    }
                    return None;
                }
            } else {
                lock = self.image_cvar.wait(lock).unwrap();
            }
        }
    }

    fn get_last_capture(&self) -> Option<Arc<Image>> {
        self.last_image.lock().unwrap().clone()
    }

    fn get_gain_range(&self) -> (f32, f32, f32) {
        (0.0, 100.0, 100.0)
    }
    fn get_brightness_range(&self) -> Option<(i32, i32, i32)> {
        Some((0, 100, 50))
    }
    fn get_brightness(&self) -> Option<i32> {
        Some(self.brightness.load(Ordering::Relaxed))
    }
    fn set_brightness(&self, brightness: i32) {
        self.brightness.store(brightness, Ordering::Relaxed);
        if let (Some(jvm), Some(wrapper)) = (&self.jvm, &self.uvc_wrapper) {
            if let Ok(mut env) = jvm.attach_current_thread() {
                let _ = env.call_method(
                    wrapper.as_obj(),
                    "setBrightness",
                    "(I)V",
                    &[jni::objects::JValue::Int(brightness)],
                );
                let _ = env.exception_clear();
            }
        }
    }
    fn get_gain(&self) -> f32 {
        self.gain.load(Ordering::Relaxed) as f32
    }
    fn get_exposure(&self) -> i32 {
        self.exposure_us.load(Ordering::Relaxed)
    }
    fn set_gain(&self, gain: f32) {
        self.gain.store(gain as i32, Ordering::Relaxed);

        if let (Some(jvm), Some(wrapper)) = (&self.jvm, &self.uvc_wrapper) {
            if let Ok(mut env) = jvm.attach_current_thread() {
                let _ = env.call_method(
                    wrapper.as_obj(),
                    "setGain",
                    "(I)V",
                    &[jni::objects::JValue::Int(gain as i32)],
                );
                let _ = env.exception_clear();
            }
        }
    }
    fn set_exposure(&self, exposure_us: i32) {
        self.exposure_us.store(exposure_us, Ordering::Relaxed);
    }
    fn start_capture(&mut self) -> Result<(), String> {
        Ok(())
    }
    fn stop_capture(&self) {
        self.is_running.store(false, Ordering::Relaxed);
        self.image_cvar.notify_all();
    }
    fn adjust_sensitivity(&self, _factor: f64) -> bool {
        false
    }
    fn save_state(&self) {}
    fn restore_state(&self) {}
}
