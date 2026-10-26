// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

import AVFoundation
import CoreMedia
import Foundation

public final class CameraService: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    public static let shared = CameraService()

    private let captureSession = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "com.oakamil.olive.cameraSession", qos: .userInitiated)
    private let cameraControlQueue = DispatchQueue(label: "com.oakamil.olive.cameraControl", qos: .userInteractive)

    @Atomic private var currentCameraDevice: AVCaptureDevice? = nil
    private var videoOutput: AVCaptureVideoDataOutput?
    private var isRunning = false

    /// Applied camera parameters tracked in-memory to prevent reading volatile
    /// hardware properties across threads during lock contention.
    @Atomic private var appliedExposureUs: Int32 = 250_000
    @Atomic private var appliedIso: Float = 100.0
    @Atomic private var appliedFocus: Float = 0.92

    /// Default optical infinity baseline for modern iPhone primary lenses.
    /// 1.0 is mechanical overrun ("hyper-infinity") causing star defocus.
    public static let opticalInfinityFocus: Float = 0.92

    private var sessionObservers: [NSObjectProtocol] = []

    private override init() {
        super.init()
    }

    public func startCamera(cameraId: String = "0", completion: @escaping (Result<Void, Error>) -> Void) {
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            if self.isRunning && self.captureSession.isRunning {
                completion(.success(()))
                return
            }

            do {
                try self.setupCaptureSession(cameraId: cameraId)
                self.setupSessionObservers()
                self.registerHardwareControlCallback()
                self.captureSession.startRunning()
                self.isRunning = true
                completion(.success(()))
            } catch {
                completion(.failure(error))
            }
        }
    }

    public func stopCamera() {
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            if !self.isRunning { return }

            self.removeSessionObservers()
            self.unregisterHardwareControlCallback()
            self.captureSession.stopRunning()
            self.isRunning = false
            self.currentCameraDevice = nil
        }
    }

    private func setupSessionObservers() {
        removeSessionObservers()

        let nc = NotificationCenter.default
        let errorObs = nc.addObserver(
            forName: .AVCaptureSessionRuntimeError,
            object: captureSession,
            queue: nil
        ) { [weak self] notification in
            guard let self = self else { return }
            if let error = notification.userInfo?[AVCaptureSessionErrorKey] as? AVError {
                print("AVCaptureSession runtime error: \(error.localizedDescription)")
            }
            self.sessionQueue.async { [weak self] in
                guard let self = self, self.isRunning else { return }
                if !self.captureSession.isRunning {
                    print("Restarting AVCaptureSession after runtime error...")
                    self.captureSession.startRunning()
                }
            }
        }
        sessionObservers.append(errorObs)

        let interruptedObs = nc.addObserver(
            forName: .AVCaptureSessionWasInterrupted,
            object: captureSession,
            queue: nil
        ) { notification in
            let reason = notification.userInfo?[AVCaptureSessionInterruptionReasonKey] as? Int ?? -1
            print("AVCaptureSession was interrupted. Reason code: \(reason)")
        }
        sessionObservers.append(interruptedObs)

        let endedObs = nc.addObserver(
            forName: .AVCaptureSessionInterruptionEnded,
            object: captureSession,
            queue: nil
        ) { [weak self] _ in
            guard let self = self else { return }
            self.sessionQueue.async { [weak self] in
                guard let self = self, self.isRunning else { return }
                if !self.captureSession.isRunning {
                    print("Resuming AVCaptureSession after interruption ended...")
                    self.captureSession.startRunning()
                }
            }
        }
        sessionObservers.append(endedObs)
    }

    private func removeSessionObservers() {
        for obs in sessionObservers {
            NotificationCenter.default.removeObserver(obs)
        }
        sessionObservers.removeAll()
    }

    private func setupCaptureSession(cameraId: String) throws {
        captureSession.beginConfiguration()
        defer { captureSession.commitConfiguration() }

        // Remove any existing inputs/outputs
        for input in captureSession.inputs {
            captureSession.removeInput(input)
        }
        for output in captureSession.outputs {
            captureSession.removeOutput(output)
        }

        captureSession.sessionPreset = .inputPriority

        var deviceTypes: [AVCaptureDevice.DeviceType] = [
            .builtInWideAngleCamera,
            .builtInUltraWideCamera,
            .builtInTelephotoCamera
        ]
        if #available(iOS 17.0, *) {
            deviceTypes.append(.external)
        }
        let discovery = AVCaptureDevice.DiscoverySession(
            deviceTypes: deviceTypes,
            mediaType: .video,
            position: .unspecified
        )

        let isExternalRequest = (cameraId == "external")
        let device: AVCaptureDevice
        if isExternalRequest {
            if #available(iOS 17.0, *), let ext = discovery.devices.first(where: { $0.deviceType == .external }) {
                device = ext
            } else if let defaultCam = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) {
                device = defaultCam
            } else if let first = discovery.devices.first {
                device = first
            } else {
                throw NSError(
                    domain: "com.oakamil.olive",
                    code: -1,
                    userInfo: [NSLocalizedDescriptionKey: "No camera available"]
                )
            }
        } else {
            let targetIndex = Int(cameraId) ?? 0
            if discovery.devices.indices.contains(targetIndex) {
                device = discovery.devices[targetIndex]
            } else if let matched = discovery.devices.first(where: { $0.uniqueID == cameraId }) {
                device = matched
            } else if let defaultCam = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) {
                device = defaultCam
            } else if let first = discovery.devices.first {
                device = first
            } else {
                throw NSError(
                    domain: "com.oakamil.olive",
                    code: -1,
                    userInfo: [NSLocalizedDescriptionKey: "No rear camera available"]
                )
            }
        }
        currentCameraDevice = device

        var isExternalDevice = false
        if #available(iOS 17.0, *) {
            isExternalDevice = (device.deviceType == .external)
        }

        if isExternalDevice {
            // Find highest resolution <= 1080p for external cameras
            let candidateFormats = device.formats.filter { format in
                let dims = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
                return dims.width <= 1920 && dims.height <= 1080
            }
            if let bestFormat = candidateFormats.max(by: { a, b in
                let dimsA = CMVideoFormatDescriptionGetDimensions(a.formatDescription)
                let dimsB = CMVideoFormatDescriptionGetDimensions(b.formatDescription)
                return (dimsA.width * dimsA.height) < (dimsB.width * dimsB.height)
            }) ?? device.formats.first {
                do {
                    try device.lockForConfiguration()
                    device.activeFormat = bestFormat
                    device.unlockForConfiguration()
                } catch {
                    print("Failed to set external device.activeFormat: \(error)")
                }
            }
        } else {
            // Select the best 4:3 format <= 13MP (e.g. 4032x3024) supporting the widest exposure duration
            let candidateFormats = device.formats.filter { format in
                let dims = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
                let pixels = Int(dims.width) * Int(dims.height)
                let isUnder13MP = pixels <= 13_000_000
                let is4to3 = abs(Double(dims.width) / Double(dims.height) - (4.0 / 3.0)) < 0.05
                let subType = CMFormatDescriptionGetMediaSubType(format.formatDescription)
                return isUnder13MP && is4to3 && (subType == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange || subType == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange)
            }

            if let bestFormat = candidateFormats.max(by: { a, b in
                let maxDurA = a.videoSupportedFrameRateRanges.map { $0.maxFrameDuration }.max(by: { CMTimeCompare($0, $1) < 0 }) ?? .zero
                let maxDurB = b.videoSupportedFrameRateRanges.map { $0.maxFrameDuration }.max(by: { CMTimeCompare($0, $1) < 0 }) ?? .zero
                if CMTimeCompare(maxDurA, maxDurB) != 0 {
                    return CMTimeCompare(maxDurA, maxDurB) < 0
                }
                let dimsA = CMVideoFormatDescriptionGetDimensions(a.formatDescription)
                let dimsB = CMVideoFormatDescriptionGetDimensions(b.formatDescription)
                return (dimsA.width * dimsA.height) < (dimsB.width * dimsB.height)
            }) {
                do {
                    try device.lockForConfiguration()
                    device.activeFormat = bestFormat
                    device.unlockForConfiguration()
                } catch {
                    print("Failed to set device.activeFormat to 4:3 12MP: \(error)")
                }
            }
        }

        let input = try AVCaptureDeviceInput(device: device)
        if captureSession.canAddInput(input) {
            captureSession.addInput(input)
        }

        let output = AVCaptureVideoDataOutput()
        if isExternalDevice {
            let dims = CMVideoFormatDescriptionGetDimensions(device.activeFormat.formatDescription)
            output.videoSettings = [
                kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_420YpCbCr8BiPlanarFullRange),
                kCVPixelBufferWidthKey as String: Int(dims.width),
                kCVPixelBufferHeightKey as String: Int(dims.height)
            ]
        } else {
            let targetWidth = 2016
            let targetHeight = 1512
            output.videoSettings = [
                kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_420YpCbCr8BiPlanarFullRange),
                kCVPixelBufferWidthKey as String: targetWidth,
                kCVPixelBufferHeightKey as String: targetHeight
            ]
        }
        output.alwaysDiscardsLateVideoFrames = true

        let frameQueue = DispatchQueue(label: "com.oakamil.olive.cameraFrames", qos: .userInteractive)
        output.setSampleBufferDelegate(self, queue: frameQueue)

        if captureSession.canAddOutput(output) {
            captureSession.addOutput(output)
        }
        self.videoOutput = output

        if let connection = output.connection(with: .video) {
            if connection.isVideoStabilizationSupported {
                connection.preferredVideoStabilizationMode = .off
            }
        }

        // Configure frame rate, exposure, and initial optical infinity focus
        try device.lockForConfiguration()
        defer { device.unlockForConfiguration() }

        if isExternalDevice {
            // Two-Class Startup Engine for external cameras
            let nominalFps: Double = {
                if device.activeVideoMinFrameDuration.isValid && device.activeVideoMinFrameDuration.seconds > 0.0 {
                    let fps = 1.0 / device.activeVideoMinFrameDuration.seconds
                    if fps.isFinite && fps > 0.0 { return fps }
                }
                let ranges = device.activeFormat.videoSupportedFrameRateRanges
                if let maxFps = ranges.map({ $0.maxFrameRate }).filter({ $0.isFinite && $0 > 0.0 }).max() {
                    return maxFps
                }
                return 30.0
            }()

            let nominalExposureUs = Int32(round(1_000_000.0 / nominalFps))
            let targetDuration = CMTime(value: 1, timescale: CMTimeScale(round(nominalFps)))

            let minExp = device.activeFormat.minExposureDuration
            let maxExp = device.activeFormat.maxExposureDuration
            let clampedDuration: CMTime
            if minExp.isValid && maxExp.isValid && minExp.seconds > 0.0 && maxExp.seconds > 0.0 {
                clampedDuration = max(minExp, min(targetDuration, maxExp))
            } else {
                clampedDuration = targetDuration
            }

            let durationSec = CMTimeGetSeconds(clampedDuration)
            if durationSec.isFinite && durationSec > 0.0 {
                self.appliedExposureUs = Int32(round(durationSec * 1_000_000.0))
            } else {
                self.appliedExposureUs = nominalExposureUs
            }

            if device.isExposureModeSupported(.continuousAutoExposure) {
                // Class A (Sony IMX662): Native Hardware AEC
                device.exposureMode = .continuousAutoExposure
                if device.maxExposureTargetBias > 0.0 {
                    device.setExposureTargetBias(device.maxExposureTargetBias, completionHandler: nil)
                }
                olive_ios_set_camera_is_ae(true)
            } else if device.isExposureModeSupported(.custom) {
                // Class B (SmartSens SC2210): Fixed-Iris Shutter Priority + Max ISO
                device.setExposureModeCustom(duration: clampedDuration, iso: device.activeFormat.maxISO, completionHandler: nil)
                olive_ios_set_camera_is_ae(false)
            } else if device.isExposureModeSupported(.autoExpose) {
                device.exposureMode = .autoExpose
                olive_ios_set_camera_is_ae(true)
            } else if device.isExposureModeSupported(.locked) {
                device.exposureMode = .locked
                olive_ios_set_camera_is_ae(false)
            }
        } else {
            // Internal iPhone configuration
            if device.activeFormat.isVideoHDRSupported {
                device.automaticallyAdjustsVideoHDREnabled = false
                device.isVideoHDREnabled = false
            }

            if device.isLowLightBoostSupported {
                device.automaticallyEnablesLowLightBoostWhenAvailable = false
            }

            // Select the frame rate range that supports the widest exposure range
            let ranges = device.activeFormat.videoSupportedFrameRateRanges
            if let maxRange = ranges.max(by: { CMTimeCompare($0.maxFrameDuration, $1.maxFrameDuration) < 0 }) {
                device.activeVideoMaxFrameDuration = maxRange.maxFrameDuration
            }
            // Cap max frame rate to 30 FPS to prevent 60 FPS flooding and excessive CPU/memory load
            if ranges.contains(where: { $0.maxFrameRate >= 30.0 }) {
                device.activeVideoMinFrameDuration = CMTime(value: 1, timescale: 30)
            }

            // Lock to optical infinity baseline
            if device.isFocusModeSupported(.locked) {
                device.setFocusModeLocked(lensPosition: CameraService.opticalInfinityFocus, completionHandler: nil)
            }
        }
    }

    private func registerHardwareControlCallback() {
        let callback: AppleCameraControlCallback = { exposureUs, gain, focus, zoom in
            CameraService.shared.handleHardwareControlRequest(exposureUs: exposureUs, gain: gain, focus: focus, zoom: zoom)
        }
        olive_ios_register_camera_control_callback(callback)
    }

    private func unregisterHardwareControlCallback() {
        olive_ios_unregister_camera_control_callback()
    }

    private func handleHardwareControlRequest(exposureUs: Int32, gain: Float, focus: Float, zoom: Float) {
        cameraControlQueue.async { [weak self] in
            guard let self = self, let device = self.currentCameraDevice else { return }

            do {
                try device.lockForConfiguration()
                defer { device.unlockForConfiguration() }

                // 1. Clamped Manual Focus (0.0 to 1.0)
                if focus >= 0.0 && device.isFocusModeSupported(.locked) {
                    let clampedFocus = min(max(focus, 0.0), 1.0)
                    if abs(clampedFocus - self.appliedFocus) > 0.001 {
                        self.appliedFocus = clampedFocus
                        device.setFocusModeLocked(lensPosition: clampedFocus, completionHandler: nil)
                    }
                }

                // 2. Clamped Exposure & ISO
                if (exposureUs > 0 || gain > 0) && device.isExposureModeSupported(.custom) {
                    let newExp = exposureUs > 0 ? exposureUs : self.appliedExposureUs
                    let newIso = gain > 0 ? gain : self.appliedIso

                    let expDiff = abs(newExp - self.appliedExposureUs)
                    let isoDiff = abs(newIso - self.appliedIso)

                    // Only invoke hardware if parameters have meaningfully changed
                    if expDiff >= 1000 || isoDiff >= 0.5 {
                        self.appliedExposureUs = newExp
                        self.appliedIso = newIso

                        let reqDuration = CMTimeMake(value: Int64(newExp), timescale: 1_000_000)

                        // Expand max frame duration if exposure duration is longer than current max frame duration
                        if CMTimeCompare(reqDuration, device.activeVideoMaxFrameDuration) > 0 {
                            var maxAllowed = device.activeVideoMaxFrameDuration
                            for range in device.activeFormat.videoSupportedFrameRateRanges {
                                if CMTimeCompare(range.maxFrameDuration, maxAllowed) > 0 {
                                    maxAllowed = range.maxFrameDuration
                                }
                            }
                            device.activeVideoMaxFrameDuration = CMTimeCompare(reqDuration, maxAllowed) < 0 ? reqDuration : maxAllowed
                        }

                        let minDuration = device.activeFormat.minExposureDuration
                        let maxDuration = CMTimeCompare(device.activeFormat.maxExposureDuration, device.activeVideoMaxFrameDuration) < 0
                            ? device.activeFormat.maxExposureDuration
                            : device.activeVideoMaxFrameDuration
                        let targetDuration = CMTimeCompare(reqDuration, minDuration) < 0
                            ? minDuration
                            : (CMTimeCompare(reqDuration, maxDuration) > 0 ? maxDuration : reqDuration)

                        let minIso = device.activeFormat.minISO
                        let maxIso = device.activeFormat.maxISO
                        let targetIso = min(max(newIso, minIso), maxIso)

                        device.setExposureModeCustom(duration: targetDuration, iso: targetIso, completionHandler: nil)
                    }
                }

                // 3. Clamped Hardware Zoom Factor
                if zoom > 0.0 {
                    let minZoom = CGFloat(1.0)
                    let maxZoom = min(device.activeFormat.videoMaxZoomFactor, 10.0)
                    let targetZoom = min(max(CGFloat(zoom), minZoom), maxZoom)
                    if abs(targetZoom - device.videoZoomFactor) > 0.05 {
                        device.videoZoomFactor = targetZoom
                    }
                }
            } catch {
                print("Failed to lock AVCaptureDevice for configuration: \(error)")
            }
        }
    }

    // MARK: - AVCaptureVideoDataOutputSampleBufferDelegate

    public func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }

        // Plane 0 is the Y-plane (luminance)
        guard let baseAddress = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0) else { return }

        let width = UInt32(CVPixelBufferGetWidthOfPlane(pixelBuffer, 0))
        let height = UInt32(CVPixelBufferGetHeightOfPlane(pixelBuffer, 0))
        let stride = UInt32(CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0))

        let timestamp = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        let timestampNs = Int64(CMTimeGetSeconds(timestamp) * 1_000_000_000.0)

        if #available(iOS 17.0, *), currentCameraDevice?.deviceType == .external {
            let rawExp = currentCameraDevice?.exposureDuration ?? .zero
            if rawExp.isValid && rawExp.seconds > 0.0 {
                let measuredUs = Int32(round(CMTimeGetSeconds(rawExp) * 1_000_000.0))
                if measuredUs > 0 {
                    self.appliedExposureUs = measuredUs
                }
            }
        }

        let exposure = self.appliedExposureUs
        let iso = self.appliedIso

        olive_ios_push_camera_frame(
            baseAddress.assumingMemoryBound(to: UInt8.self),
            width,
            height,
            stride,
            timestampNs,
            exposure,
            iso
        )
    }
}

/// Simple thread-safe wrapper for Swift primitives across queues
@propertyWrapper
final class Atomic<T> {
    private var value: T
    private let lock = NSLock()

    init(wrappedValue: T) {
        self.value = wrappedValue
    }

    var wrappedValue: T {
        get {
            lock.lock()
            defer { lock.unlock() }
            return value
        }
        set {
            lock.lock()
            defer { lock.unlock() }
            value = newValue
        }
    }
}

// MARK: - C-ABI Hardware Capture Hooks

@_cdecl("olive_ios_get_available_cameras")
public func olive_ios_get_available_cameras() -> UnsafeMutablePointer<CChar>? {
    let deviceTypes: [AVCaptureDevice.DeviceType] = [
        .builtInWideAngleCamera,
        .builtInUltraWideCamera,
        .builtInTelephotoCamera
    ]
    let discovery = AVCaptureDevice.DiscoverySession(
        deviceTypes: deviceTypes,
        mediaType: .video,
        position: .back
    )

    var cameras: [[String: Any]] = []
    for (index, device) in discovery.devices.enumerated() {
        let fov = Double(device.activeFormat.videoFieldOfView)
        let maxZoom = Double(device.activeFormat.videoMaxZoomFactor)
        let camDict: [String: Any] = [
            "id": "\(index)",
            "name": device.localizedName,
            "unique_id": device.uniqueID,
            "lens_facing": "back",
            "sensor_orientation": 90,
            "fov": fov > 0 ? fov : 65.0,
            "max_zoom": maxZoom > 1.0 ? maxZoom : 1.0,
            "supports_raw": false,
            "width": 2016,
            "height": 1512
        ]
        cameras.append(camDict)
    }

    if cameras.isEmpty {
        cameras.append([
            "id": "0",
            "name": "Back Camera (Wide)",
            "unique_id": "default",
            "lens_facing": "back",
            "sensor_orientation": 90,
            "fov": 65.0,
            "max_zoom": 1.0,
            "supports_raw": false,
            "width": 2016,
            "height": 1512
        ])
    }

    guard let jsonData = try? JSONSerialization.data(withJSONObject: cameras, options: []),
          let jsonString = String(data: jsonData, encoding: .utf8) else {
        return strdup("[]")
    }
    return strdup(jsonString)
}

@_cdecl("olive_ios_start_hardware_capture")
public func olive_ios_start_hardware_capture(cameraId: UnsafePointer<CChar>?) {
    let idStr = cameraId != nil ? String(cString: cameraId!) : "0"
    CameraService.shared.startCamera(cameraId: idStr) { _ in }
    MotionService.shared.startMotionUpdates()
}

@_cdecl("olive_ios_stop_hardware_capture")
public func olive_ios_stop_hardware_capture() {
    CameraService.shared.stopCamera()
    MotionService.shared.stopMotionUpdates()
}
