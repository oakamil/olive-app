// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

use log::info;
use nalgebra::{UnitQuaternion, Vector3};
use olive_imu::{ImuDevice, SensorEvent};
use std::sync::{Arc, Mutex};
use std::time::Instant;

struct ImuState {
    accumulated_q: UnitQuaternion<f64>,
    accumulated_dt_hw: f64,
    last_gyro: Option<Vector3<f64>>,
    last_hw_timestamp_ns: Option<i64>,
    processed_events: usize,
}

static ACTIVE_IMU_STATE: Mutex<Option<Arc<Mutex<ImuState>>>> = Mutex::new(None);

pub struct AppleImu {
    state: Arc<Mutex<ImuState>>,
    last_engine_poll_time: Option<Instant>,
}

impl AppleImu {
    pub fn new() -> Self {
        let state = Arc::new(Mutex::new(ImuState {
            accumulated_q: UnitQuaternion::identity(),
            accumulated_dt_hw: 0.0,
            last_gyro: None,
            last_hw_timestamp_ns: None,
            processed_events: 0,
        }));
        *ACTIVE_IMU_STATE.lock().unwrap() = Some(state.clone());
        Self {
            state,
            last_engine_poll_time: Some(Instant::now()),
        }
    }
}

impl Default for AppleImu {
    fn default() -> Self {
        Self::new()
    }
}

impl ImuDevice for AppleImu {
    fn init(&mut self) -> Result<(), String> {
        info!("AppleImu initialized (Raw Gyroscope Mode)");
        self.last_engine_poll_time = Some(Instant::now());
        Ok(())
    }

    fn poll(&mut self) -> Result<Vec<SensorEvent>, String> {
        let (accumulated_q, processed_events, last_gyro, dt_hw) = {
            let mut s = self.state.lock().unwrap();
            if s.processed_events == 0 {
                return Ok(Vec::new());
            }
            let q = s.accumulated_q;
            let count = s.processed_events;
            let lg = s.last_gyro;
            let dt = s.accumulated_dt_hw;
            s.accumulated_q = UnitQuaternion::identity();
            s.accumulated_dt_hw = 0.0;
            s.processed_events = 0;
            (q, count, lg, dt)
        };

        if processed_events > 0 {
            let dt = if dt_hw > 0.0001 && dt_hw < 1.0 {
                dt_hw
            } else {
                let now = Instant::now();
                let dt_eng = self
                    .last_engine_poll_time
                    .map(|t| now.duration_since(t).as_secs_f64())
                    .unwrap_or(0.01);
                dt_eng
            };
            self.last_engine_poll_time = Some(Instant::now());

            if dt > 0.0001 && dt < 1.0 {
                let angle = accumulated_q.angle();
                if angle > 1e-8 {
                    if let Some(axis) = accumulated_q.axis() {
                        let equivalent_velocity = axis.into_inner() * (angle / dt);
                        return Ok(vec![SensorEvent {
                            gyro: Some(equivalent_velocity),
                            accel: None,
                            hardware_quaternion: None,
                            dt: Some(dt),
                        }]);
                    }
                }
            }

            if let Some(lg) = last_gyro {
                return Ok(vec![SensorEvent {
                    gyro: Some(lg),
                    accel: None,
                    hardware_quaternion: None,
                    dt: Some(0.0),
                }]);
            }

            return Ok(vec![SensorEvent {
                gyro: Some(Vector3::zeros()),
                accel: None,
                hardware_quaternion: None,
                dt: Some(0.0),
            }]);
        }

        Ok(Vec::new())
    }

    fn revive(&mut self) -> Result<(), String> {
        let mut s = self.state.lock().unwrap();
        s.accumulated_q = UnitQuaternion::identity();
        s.accumulated_dt_hw = 0.0;
        s.last_gyro = None;
        s.last_hw_timestamp_ns = None;
        s.processed_events = 0;
        self.last_engine_poll_time = Some(Instant::now());
        Ok(())
    }
}

impl Drop for AppleImu {
    fn drop(&mut self) {
        *ACTIVE_IMU_STATE.lock().unwrap() = None;
    }
}

/// Push raw hardware gyroscope updates from Swift CoreMotion.
///
/// Accumulates hardware rotational deltas over each hardware time interval dt_hw.
/// When polled by the engine thread, the accumulated delta quaternion is converted
/// to equivalent velocity over dt_engine, guaranteeing 100% sample preservation
/// with zero bias-filter distortion.
#[unsafe(no_mangle)]
pub extern "C" fn olive_ios_push_gyro_update(gx: f64, gy: f64, gz: f64, timestamp_ns: i64) {
    let state = {
        let guard = ACTIVE_IMU_STATE.lock().unwrap();
        guard.as_ref().cloned()
    };

    if let Some(state) = state {
        let mut s = state.lock().unwrap();
        let gyro_vec = Vector3::new(gx, gy, gz);
        s.last_gyro = Some(gyro_vec);

        if let Some(last_ts) = s.last_hw_timestamp_ns {
            let dt_hw = (timestamp_ns - last_ts) as f64 / 1_000_000_000.0;
            // Guard against negative timestamps or large jumps (e.g. app paused)
            if dt_hw > 0.0 && dt_hw < 1.0 {
                let delta_q = UnitQuaternion::new(gyro_vec * dt_hw);
                s.accumulated_q *= delta_q;
                s.accumulated_dt_hw += dt_hw;
            }
        }
        s.last_hw_timestamp_ns = Some(timestamp_ns);
        s.processed_events += 1;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::thread::sleep;
    use std::time::Duration;

    static TEST_LOCK: Mutex<()> = Mutex::new(());

    #[test]
    fn test_gyro_accumulation_and_poll() {
        let _lock = TEST_LOCK.lock().unwrap();
        let mut imu = AppleImu::new();
        imu.init().unwrap();

        // Simulate 5 gyro samples at 100 Hz (10ms apart), rotating around Z at 1.0 rad/s
        let start_time_ns: i64 = 1_000_000_000;
        for i in 0..5 {
            olive_ios_push_gyro_update(0.0, 0.0, 1.0, start_time_ns + i * 10_000_000);
        }

        // Sleep briefly to ensure dt_engine > 0
        sleep(Duration::from_millis(20));

        let events = imu.poll().unwrap();
        assert_eq!(events.len(), 1);
        let ev = &events[0];

        // Total rotation accumulated: 4 intervals of 10ms * 1.0 rad/s = 0.040 rad
        let dt = ev.dt.expect("dt should be present");
        assert!(dt > 0.015);
        let gyro = ev.gyro.expect("gyro should be present");

        // equivalent_velocity * dt should equal the total accumulated angle (~0.040 rad around Z)
        let total_integrated_angle = gyro.z * dt;
        assert!(
            (total_integrated_angle - 0.040).abs() < 1e-4,
            "Expected ~0.040 rad integrated, got {}",
            total_integrated_angle
        );
        assert!(gyro.x.abs() < 1e-6);
        assert!(gyro.y.abs() < 1e-6);

        // Next poll without new events should return empty
        let empty_events = imu.poll().unwrap();
        assert!(empty_events.is_empty());
    }

    #[test]
    fn test_stationary_gyro() {
        let _lock = TEST_LOCK.lock().unwrap();
        let mut imu = AppleImu::new();
        imu.init().unwrap();

        // Push stationary gyro updates (all 0)
        olive_ios_push_gyro_update(0.0, 0.0, 0.0, 1_000_000_000);
        olive_ios_push_gyro_update(0.0, 0.0, 0.0, 1_010_000_000);

        sleep(Duration::from_millis(15));

        let events = imu.poll().unwrap();
        assert_eq!(events.len(), 1);
        let ev = &events[0];
        assert_eq!(ev.dt, Some(0.0));
        let gyro = ev.gyro.unwrap();
        assert_eq!(gyro, Vector3::zeros());
    }

    #[test]
    fn test_backwards_timestamp_guard() {
        let _lock = TEST_LOCK.lock().unwrap();
        let mut imu = AppleImu::new();
        imu.init().unwrap();

        olive_ios_push_gyro_update(0.0, 0.0, 1.0, 1_000_000_000);
        // Backwards timestamp: should be safely ignored
        olive_ios_push_gyro_update(0.0, 0.0, 1.0, 900_000_000);
        // Valid forward timestamp
        olive_ios_push_gyro_update(0.0, 0.0, 1.0, 1_010_000_000);

        sleep(Duration::from_millis(15));
        let events = imu.poll().unwrap();
        assert_eq!(events.len(), 1);
    }
}
