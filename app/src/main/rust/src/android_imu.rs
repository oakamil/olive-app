use nalgebra::{Quaternion, UnitQuaternion, Vector3};
use ndk_sys::*;
use olive_imu::ImuDevice;
use std::ptr;
use std::time::Instant;

pub struct AndroidImu {
    manager: *mut ASensorManager,
    queue: *mut ASensorEventQueue,
    sensor: *const ASensor,
    last_engine_poll_time: Option<Instant>,
    last_hardware_timestamp: Option<i64>,
    mode: i32,
    last_fused_quat: Option<UnitQuaternion<f64>>,
}

unsafe impl Send for AndroidImu {}
unsafe impl Sync for AndroidImu {}

impl AndroidImu {
    pub fn new(mode: i32) -> Self {
        Self {
            manager: ptr::null_mut(),
            queue: ptr::null_mut(),
            sensor: ptr::null(),
            last_engine_poll_time: None,
            last_hardware_timestamp: None,
            mode,
            last_fused_quat: None,
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

            let sensor_type = match self.mode {
                1 => 11, // ASENSOR_TYPE_ROTATION_VECTOR (Standard)
                2 => 15, // ASENSOR_TYPE_GAME_ROTATION_VECTOR (Gyro + Accel)
                3 => 4,  // ASENSOR_TYPE_GYROSCOPE (Gyro only)
                _ => 0,
            };

            self.sensor = ASensorManager_getDefaultSensor(self.manager, sensor_type);
            if self.sensor.is_null() && self.mode == 2 {
                log::warn!("Game rotation vector not available, falling back to rotation vector");
                self.sensor = ASensorManager_getDefaultSensor(self.manager, 11);
            }

            if self.sensor.is_null() {
                return Err(format!("Failed to get sensor for mode {}", self.mode));
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
                let mut latest_fused_quat: Option<UnitQuaternion<f64>> = None;

                while ASensorEventQueue_getEvents(self.queue, event.as_mut_ptr(), 1) > 0 {
                    let ev = event.assume_init();
                    if ev.type_ == 4 {
                        let data = ev.__bindgen_anon_1.__bindgen_anon_1.data;
                        let gyro_vec = Vector3::new(data[0] as f64, data[1] as f64, data[2] as f64);
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
                    } else if ev.type_ == 15 || ev.type_ == 11 {
                        let data = ev.__bindgen_anon_1.__bindgen_anon_1.data;
                        let x = data[0] as f64;
                        let y = data[1] as f64;
                        let z = data[2] as f64;
                        let w = data[3] as f64;
                        let q = Quaternion::new(w, x, y, z);
                        let unit_q = UnitQuaternion::from_quaternion(q);
                        latest_fused_quat = Some(unit_q);
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
                            if self.mode == 1 || self.mode == 2 {
                                if let Some(current_q) = latest_fused_quat {
                                    if let Some(prev_q) = self.last_fused_quat {
                                        let q_delta = prev_q.conjugate() * current_q;
                                        self.last_fused_quat = Some(current_q);

                                        let angle = q_delta.angle();
                                        if angle > 1e-8 {
                                            if let Some(axis) = q_delta.axis() {
                                                let equivalent_velocity =
                                                    axis.into_inner() * (angle / dt_engine);
                                                return Ok(Some(equivalent_velocity));
                                            }
                                        }
                                    } else {
                                        self.last_fused_quat = Some(current_q);
                                    }
                                }
                            } else if self.mode == 3 {
                                let angle = accumulated_q.angle();
                                if angle > 1e-8 {
                                    if let Some(axis) = accumulated_q.axis() {
                                        let equivalent_velocity =
                                            axis.into_inner() * (angle / dt_engine);
                                        return Ok(Some(equivalent_velocity));
                                    }
                                }
                            }
                        }
                    } else {
                        self.last_engine_poll_time = Some(now);
                        if self.mode == 1 || self.mode == 2 {
                            self.last_fused_quat = latest_fused_quat;
                        }
                    }
                    if self.mode == 3 {
                        return Ok(last_gyro);
                    }
                    return Ok(Some(Vector3::zeros()));
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
