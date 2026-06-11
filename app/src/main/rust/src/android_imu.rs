use nalgebra::{UnitQuaternion, Vector3};
use ndk_sys::*;
use olive_engine::imu::ImuDevice;
use std::ptr;
use std::time::Instant;

pub struct AndroidImu {
    manager: *mut ASensorManager,
    queue: *mut ASensorEventQueue,
    sensor: *const ASensor,
    last_engine_poll_time: Option<Instant>,
    last_hardware_timestamp: Option<i64>,
}

unsafe impl Send for AndroidImu {}
unsafe impl Sync for AndroidImu {}

impl AndroidImu {
    pub fn new() -> Self {
        Self {
            manager: ptr::null_mut(),
            queue: ptr::null_mut(),
            sensor: ptr::null(),
            last_engine_poll_time: None,
            last_hardware_timestamp: None,
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

            self.sensor =
                ASensorManager_getDefaultSensor(self.manager, ASENSOR_TYPE_GYROSCOPE as i32);
            if self.sensor.is_null() {
                return Err("Failed to get ASENSOR_TYPE_GYROSCOPE".to_string());
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

                let mut accumulated_q = UnitQuaternion::identity();
                let mut processed_events = 0;
                let mut last_gyro: Option<Vector3<f64>> = None;

                while ASensorEventQueue_getEvents(self.queue, event.as_mut_ptr(), 1) > 0 {
                    let ev = event.assume_init();
                    if ev.type_ == ASENSOR_TYPE_GYROSCOPE as i32 {
                        let x = ev
                            .__bindgen_anon_1
                            .__bindgen_anon_1
                            .vector
                            .__bindgen_anon_1
                            .__bindgen_anon_1
                            .x as f64;
                        let y = ev
                            .__bindgen_anon_1
                            .__bindgen_anon_1
                            .vector
                            .__bindgen_anon_1
                            .__bindgen_anon_1
                            .y as f64;
                        let z = ev
                            .__bindgen_anon_1
                            .__bindgen_anon_1
                            .vector
                            .__bindgen_anon_1
                            .__bindgen_anon_1
                            .z as f64;
                        let gyro_vec = Vector3::new(x, y, z);
                        last_gyro = Some(gyro_vec);

                        if let Some(last_ts) = self.last_hardware_timestamp {
                            let dt_hw = (ev.timestamp - last_ts) as f64 / 1_000_000_000.0;
                            // Guard against weird backwards or zero timestamps
                            if dt_hw > 0.0 && dt_hw < 1.0 {
                                let delta_q = UnitQuaternion::new(gyro_vec * dt_hw);
                                accumulated_q *= delta_q;
                            }
                        }
                        self.last_hardware_timestamp = Some(ev.timestamp);
                        processed_events += 1;
                    }
                }

                if processed_events > 0 {
                    let now = Instant::now();
                    if let Some(last_engine_time) = self.last_engine_poll_time {
                        let dt_engine = now.duration_since(last_engine_time).as_secs_f64();
                        self.last_engine_poll_time = Some(now);

                        if dt_engine > 0.0001 {
                            let angle = accumulated_q.angle();
                            if angle > 1e-8 {
                                if let Some(axis) = accumulated_q.axis() {
                                    let equivalent_velocity =
                                        axis.into_inner() * (angle / dt_engine);
                                    return Ok(Some(equivalent_velocity));
                                }
                            }
                        }
                    } else {
                        self.last_engine_poll_time = Some(now);
                    }
                    // Return the last instantaneous reading if angle was tiny or first poll
                    return Ok(last_gyro);
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
