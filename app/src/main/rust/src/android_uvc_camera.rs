use jni::{JavaVM, objects::GlobalRef};
use olive_engine::{CameraConfig, CameraTrait, Image};
use std::sync::atomic::{AtomicI32, AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::SystemTime;

pub struct AndroidUvcCamera {
    config: Mutex<CameraConfig>,
    exposure_us: AtomicI32,
    gain: AtomicI32,
    stack_count: AtomicUsize,

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
    ) -> Self {
        Self {
            config: Mutex::new(CameraConfig {
                model: "External UVC".to_string(),
                width,
                height,
                fps,
                crop: None,
                initial_fov: None,
            }),
            exposure_us: AtomicI32::new((1_000_000 / fps.max(1)) as i32),
            gain: AtomicI32::new(50),
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

    pub fn push_external_frame(&self, nv21_data: &[u8], width: u32, height: u32) {
        let y_plane_size = (width * height) as usize;
        if nv21_data.len() < y_plane_size {
            return;
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
                post_process_time: std::time::Duration::from_millis(0),
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
    fn set_brightness(&self, brightness: i32) {
        if let (Some(jvm), Some(wrapper)) = (&self.jvm, &self.uvc_wrapper) {
            if let Ok(mut env) = jvm.attach_current_thread() {
                let _ = env.call_method(
                    wrapper.as_obj(),
                    "setBrightness",
                    "(I)V",
                    &[jni::objects::JValue::Int(brightness)],
                );
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
