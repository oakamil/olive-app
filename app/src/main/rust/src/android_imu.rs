use nalgebra::Vector3;
use ndk_sys::*;
use olive_engine::imu::ImuDevice;
use std::ptr;

pub struct AndroidImu {
    manager: *mut ASensorManager,
    queue: *mut ASensorEventQueue,
    sensor: *const ASensor,
}

unsafe impl Send for AndroidImu {}
unsafe impl Sync for AndroidImu {}

impl AndroidImu {
    pub fn new() -> Self {
        Self {
            manager: ptr::null_mut(),
            queue: ptr::null_mut(),
            sensor: ptr::null(),
        }
    }
}

impl ImuDevice for AndroidImu {
    fn init(&mut self) -> Result<(), String> {
        unsafe {
            // ALooper must be prepared on this thread.
            let mut looper = ALooper_forThread();
            if looper.is_null() {
                looper = ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS as i32);
            }
            if looper.is_null() {
                return Err("Failed to get or prepare ALooper".to_string());
            }

            self.manager = ASensorManager_getInstance();
            if self.manager.is_null() {
                return Err("Failed to get ASensorManager".to_string());
            }

            self.sensor = ASensorManager_getDefaultSensor(
                self.manager,
                ASENSOR_TYPE_GYROSCOPE_UNCALIBRATED as i32,
            );
            if self.sensor.is_null() {
                return Err("Failed to get ASENSOR_TYPE_GYROSCOPE_UNCALIBRATED".to_string());
            }

            self.queue =
                ASensorManager_createEventQueue(self.manager, looper, 1, None, ptr::null_mut());
            if self.queue.is_null() {
                return Err("Failed to create ASensorEventQueue".to_string());
            }

            let status = ASensorEventQueue_enableSensor(self.queue, self.sensor);
            if status < 0 {
                return Err(format!("Failed to enable sensor: {}", status));
            }

            // Set rate to 10ms (10,000 microseconds)
            let rate_status = ASensorEventQueue_setEventRate(self.queue, self.sensor, 10_000);
            if rate_status < 0 {
                log::warn!("Failed to set sensor event rate: {}", rate_status);
            }
        }
        Ok(())
    }

    fn poll_gyro(&mut self) -> Result<Option<Vector3<f64>>, String> {
        unsafe {
            let mut fd: i32 = 0;
            let mut events: i32 = 0;
            let mut data: *mut std::ffi::c_void = ptr::null_mut();

            // Non-blocking poll
            let ident = ALooper_pollOnce(0, &mut fd, &mut events, &mut data);

            if ident >= 0 {
                let mut event = std::mem::MaybeUninit::<ASensorEvent>::zeroed();
                // Read all available events, keeping the latest one
                let mut latest_gyro: Option<Vector3<f64>> = None;

                while ASensorEventQueue_getEvents(self.queue, event.as_mut_ptr(), 1) > 0 {
                    let ev = event.assume_init();
                    if ev.type_ == ASENSOR_TYPE_GYROSCOPE_UNCALIBRATED as i32 {
                        let x = ev.u.uncalibrated_gyro.x_uncalib as f64;
                        let y = ev.u.uncalibrated_gyro.y_uncalib as f64;
                        let z = ev.u.uncalibrated_gyro.z_uncalib as f64;
                        latest_gyro = Some(Vector3::new(x, y, z));
                    }
                }
                if latest_gyro.is_some() {
                    return Ok(latest_gyro);
                }
            }
        }
        Ok(None)
    }

    fn revive(&mut self) -> Result<(), String> {
        self.init()
    }
}

impl Drop for AndroidImu {
    fn drop(&mut self) {
        unsafe {
            if !self.queue.is_null() {
                if !self.sensor.is_null() {
                    ASensorEventQueue_disableSensor(self.queue, self.sensor);
                }
                ASensorManager_destroyEventQueue(self.manager, self.queue);
            }
        }
    }
}
