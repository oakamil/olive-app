// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

import CoreMotion
import Foundation

public final class MotionService {
    public static let shared = MotionService()

    private let motionManager = CMMotionManager()
    private let motionQueue = OperationQueue()
    private let lock = NSLock()
    private var isRunning = false

    private init() {
        motionQueue.name = "com.oakamil.olive.motionQueue"
        motionQueue.qualityOfService = .userInteractive
        motionQueue.maxConcurrentOperationCount = 1
    }

    public func startMotionUpdates() {
        guard motionManager.isGyroAvailable else {
            print("CoreMotion gyroscope is not available on this hardware")
            return
        }

        lock.lock()
        defer { lock.unlock() }
        if isRunning { return }

        // Update at 100 Hz (10ms intervals) matching Android IMU rate
        motionManager.gyroUpdateInterval = 0.01

        motionManager.startGyroUpdates(to: motionQueue) { [weak self] gyroData, error in
            guard let gyroData = gyroData, error == nil else { return }
            self?.handleGyroData(gyroData)
        }

        isRunning = true
    }

    public func stopMotionUpdates() {
        lock.lock()
        defer { lock.unlock() }
        if !isRunning { return }
        motionManager.stopGyroUpdates()
        isRunning = false
    }

    private func handleGyroData(_ gyroData: CMGyroData) {
        let rotationRate = gyroData.rotationRate

        // Unadulterated raw gyroscope rotation rates in rad/s directly from MEMS sensor
        let gx = rotationRate.x
        let gy = rotationRate.y
        let gz = rotationRate.z

        let timestampNs = Int64(gyroData.timestamp * 1_000_000_000.0)

        olive_ios_push_gyro_update(
            gx, gy, gz,
            timestampNs
        )
    }

    public static func registerStorageCallbacks() {
        olive_ios_register_storage_callbacks(
            storageGetCallback,
            storageSetCallback,
            storageRemoveCallback
        )
    }
}

// MARK: - C-ABI Storage Hooks for Rust (crates/olive-ios)

private func storageGetCallback(_ keyPtr: UnsafePointer<CChar>?) -> UnsafeMutablePointer<CChar>? {
    guard let keyPtr = keyPtr else { return nil }
    let key = String(cString: keyPtr)
    guard let value = UserDefaults.standard.string(forKey: key) else { return nil }
    return strdup(value)
}

private func storageSetCallback(_ keyPtr: UnsafePointer<CChar>?, _ valuePtr: UnsafePointer<CChar>?) {
    guard let keyPtr = keyPtr, let valuePtr = valuePtr else { return }
    let key = String(cString: keyPtr)
    let value = String(cString: valuePtr)
    UserDefaults.standard.set(value, forKey: key)
}

private func storageRemoveCallback(_ keyPtr: UnsafePointer<CChar>?) {
    guard let keyPtr = keyPtr else { return }
    let key = String(cString: keyPtr)
    UserDefaults.standard.removeObject(forKey: key)
}
