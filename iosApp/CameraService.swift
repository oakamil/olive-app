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

    public func startCamera(
        cameraId: String = "0",
        targetWidth: Int32 = 0,
        targetHeight: Int32 = 0,
        targetFps: Int32 = 0,
        targetFormat: String? = nil,
        initialZoom: Float = 1.0,
        completion: @escaping (Result<Void, Error>) -> Void
    ) {
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            if self.isRunning && self.captureSession.isRunning {
                completion(.success(()))
                return
            }

            do {
                try self.setupCaptureSession(
                    cameraId: cameraId,
                    targetWidth: targetWidth,
                    targetHeight: targetHeight,
                    targetFps: targetFps,
                    targetFormat: targetFormat,
                    initialZoom: initialZoom
                )
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

    private func setupCaptureSession(
        cameraId: String,
        targetWidth: Int32 = 0,
        targetHeight: Int32 = 0,
        targetFps: Int32 = 0,
        targetFormat: String? = nil,
        initialZoom: Float = 1.0
    ) throws {
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
            // Multi-tier candidate format matching for external camera
            let candidateFormats = device.formats.filter { format in
                let dims = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
                guard dims.width > 0 && dims.height > 0 else { return false }

                let subType = CMFormatDescriptionGetMediaSubType(format.formatDescription)
                let isMjpeg = isMjpegCodec(subType: subType)
                let isYuv = isYuvCodec(subType: subType)
                guard isMjpeg || isYuv else { return false }

                let matchesRes: Bool
                if targetWidth > 0 && targetHeight > 0 {
                    matchesRes = (dims.width == targetWidth && dims.height == targetHeight) ||
                                 (abs(dims.width - targetWidth) <= 8 && abs(dims.height - targetHeight) <= 8)
                } else {
                    matchesRes = true
                }

                let matchesFormat: Bool
                if let targetFormat = targetFormat {
                    if isMjpeg || subType == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange {
                        matchesFormat = targetFormat.caseInsensitiveCompare("MJPEG") == .orderedSame
                    } else if isYuv || subType == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange {
                        matchesFormat = targetFormat.caseInsensitiveCompare("YUV2") == .orderedSame
                    } else {
                        matchesFormat = true
                    }
                } else {
                    matchesFormat = true
                }

                let matchesFps: Bool
                if targetFps > 0 {
                    let desired = Double(targetFps)
                    matchesFps = format.videoSupportedFrameRateRanges.contains {
                        desired >= ($0.minFrameRate - 0.1) && desired <= ($0.maxFrameRate + 0.1)
                    }
                } else {
                    matchesFps = true
                }

                return matchesRes && matchesFormat && matchesFps
            }

            // Fallback selection hierarchy: always prioritize full-range '420f' for maximum dynamic range
            let selectedFormat: AVCaptureDevice.Format? = {
                // Priority 1: Candidate matching target resolution & FPS that is full-range '420f'
                if let fullRangeExact = candidateFormats.first(where: {
                    CMFormatDescriptionGetMediaSubType($0.formatDescription) == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
                }) {
                    return fullRangeExact
                }
                // Priority 2: Any candidate matching target resolution & FPS (e.g. video-range '420v')
                if let exact = candidateFormats.first {
                    return exact
                }
                // Fallback 1: match resolution (with ±8px tolerance) and prefer full-range '420f'
                if targetWidth > 0 && targetHeight > 0 {
                    let resMatches = device.formats.filter { format in
                        let dims = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
                        return (dims.width == targetWidth && dims.height == targetHeight) ||
                               (abs(dims.width - targetWidth) <= 8 && abs(dims.height - targetHeight) <= 8)
                    }
                    if let fullRangeMatch = resMatches.first(where: {
                        CMFormatDescriptionGetMediaSubType($0.formatDescription) == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
                    }) {
                        return fullRangeMatch
                    }
                    if let match = resMatches.first { return match }
                }
                // Fallback 2: match resolution
                if targetWidth > 0 && targetHeight > 0 {
                    let resMatches = device.formats.filter { format in
                        let dims = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
                        return dims.width == targetWidth && dims.height == targetHeight
                    }
                    if let match = resMatches.first { return match }
                }
                // Fallback 3: highest resolution format
                return device.formats.max(by: { a, b in
                    let dimsA = CMVideoFormatDescriptionGetDimensions(a.formatDescription)
                    let dimsB = CMVideoFormatDescriptionGetDimensions(b.formatDescription)
                    return (dimsA.width * dimsA.height) < (dimsB.width * dimsB.height)
                }) ?? device.formats.first
            }()

            if let bestFormat = selectedFormat {
                do {
                    try device.lockForConfiguration()
                    device.activeFormat = bestFormat
                    device.unlockForConfiguration()
                    let dims = CMVideoFormatDescriptionGetDimensions(bestFormat.formatDescription)
                    print("[CameraService] Set external device.activeFormat: \(dims.width)x\(dims.height)")
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

            func formatAchievableMaxExposure(_ f: AVCaptureDevice.Format) -> CMTime {
                let maxRangeDur = f.videoSupportedFrameRateRanges.map { $0.maxFrameDuration }.max(by: { CMTimeCompare($0, $1) < 0 }) ?? .zero
                let maxExpDur = f.maxExposureDuration
                if maxRangeDur.isValid && maxRangeDur.seconds > 0.0 && maxExpDur.isValid && maxExpDur.seconds > 0.0 {
                    return min(maxRangeDur, maxExpDur)
                }
                return maxRangeDur.isValid && maxRangeDur.seconds > 0.0 ? maxRangeDur : maxExpDur
            }

            if let bestFormat = candidateFormats.max(by: { a, b in
                let achA = formatAchievableMaxExposure(a)
                let achB = formatAchievableMaxExposure(b)
                if CMTimeCompare(achA, achB) != 0 {
                    return CMTimeCompare(achA, achB) < 0
                }
                let subA = CMFormatDescriptionGetMediaSubType(a.formatDescription)
                let subB = CMFormatDescriptionGetMediaSubType(b.formatDescription)
                if subA != subB {
                    return subB == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
                }
                let dimsA = CMVideoFormatDescriptionGetDimensions(a.formatDescription)
                let dimsB = CMVideoFormatDescriptionGetDimensions(b.formatDescription)
                return (dimsA.width * dimsA.height) < (dimsB.width * dimsB.height)
            }) {
                do {
                    try device.lockForConfiguration()
                    device.activeFormat = bestFormat
                    device.unlockForConfiguration()

                    let achMax = formatAchievableMaxExposure(bestFormat)
                    let minExp = bestFormat.minExposureDuration
                    let minExpUs = Int32(round(minExp.seconds * 1_000_000.0))
                    let maxExpUs = Int32(round(achMax.seconds * 1_000_000.0))
                    olive_ios_set_camera_hardware_limits(minExpUs, maxExpUs, bestFormat.minISO, bestFormat.maxISO)
                    print("[CameraService] Locked active format: maxAchievableExposure=\(maxExpUs)us, minExp=\(minExpUs)us, ISO=[\(bestFormat.minISO), \(bestFormat.maxISO)]")
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
            let activeSubType = CMFormatDescriptionGetMediaSubType(device.activeFormat.formatDescription)
            let pixelFormat = (activeSubType == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange || activeSubType == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange)
                ? activeSubType
                : kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
            output.videoSettings = [
                kCVPixelBufferPixelFormatTypeKey as String: Int(pixelFormat),
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
            // Lock activeVideoMinFrameDuration and activeVideoMaxFrameDuration
            let desiredFps = Double(targetFps > 0 ? targetFps : 30)
            let supportedRanges = device.activeFormat.videoSupportedFrameRateRanges
            if let matchingRange = supportedRanges.first(where: {
                desiredFps >= ($0.minFrameRate - 0.1) && desiredFps <= ($0.maxFrameRate + 0.1)
            }) {
                let targetDuration = CMTime(value: 1, timescale: CMTimeScale(round(desiredFps)))
                // Safe relative ordering check to prevent NSInvalidArgumentException
                if CMTimeCompare(targetDuration, device.activeVideoMaxFrameDuration) > 0 {
                    device.activeVideoMaxFrameDuration = targetDuration
                    device.activeVideoMinFrameDuration = targetDuration
                } else {
                    device.activeVideoMinFrameDuration = targetDuration
                    device.activeVideoMaxFrameDuration = targetDuration
                }
                print("[CameraService] Locked external camera frame duration to \(targetDuration.seconds)s (\(desiredFps) FPS)")
            } else if let maxRange = supportedRanges.max(by: { $0.maxFrameRate < $1.maxFrameRate }) {
                device.activeVideoMinFrameDuration = maxRange.minFrameDuration
                device.activeVideoMaxFrameDuration = maxRange.maxFrameDuration
            }

            let nominalFps: Double = {
                if device.activeVideoMinFrameDuration.isValid && device.activeVideoMinFrameDuration.seconds > 0.0 {
                    let fps = 1.0 / device.activeVideoMinFrameDuration.seconds
                    if fps.isFinite && fps > 0.0 { return fps }
                }
                return desiredFps
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

            // Apply initial hardware zoom
            if initialZoom > 0.0 {
                let minZoom = CGFloat(1.0)
                let maxZoom = min(device.activeFormat.videoMaxZoomFactor, 10.0)
                let targetZoom = min(max(CGFloat(initialZoom), minZoom), maxZoom)
                device.videoZoomFactor = targetZoom
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

                        let minDuration = device.activeFormat.minExposureDuration
                        let formatMaxDuration = device.activeFormat.maxExposureDuration
                        let supportedRanges = device.activeFormat.videoSupportedFrameRateRanges
                        let maxRangeDuration: CMTime = supportedRanges.map { $0.maxFrameDuration }.max(by: { CMTimeCompare($0, $1) < 0 }) ?? formatMaxDuration
                        let minRangeDuration: CMTime = supportedRanges.map { $0.minFrameDuration }.min(by: { CMTimeCompare($0, $1) < 0 }) ?? CMTime(value: 1, timescale: 30)
                        let maxAchievableDuration = min(formatMaxDuration, maxRangeDuration)

                        // Clamp to true achievable hardware shutter bounds
                        let targetDuration = min(max(reqDuration, minDuration), maxAchievableDuration)

                        // Dynamically adjust activeVideoMaxFrameDuration to match targetDuration.
                        // When exposure drops, shorten max frame duration so frame rate speeds up.
                        // When exposure lengthens, expand it up to maxRangeDuration.
                        let desiredFrameDuration = max(minRangeDuration, min(targetDuration, maxRangeDuration))
                        if CMTimeCompare(desiredFrameDuration, device.activeVideoMinFrameDuration) >= 0 {
                            device.activeVideoMaxFrameDuration = desiredFrameDuration
                        }

                        let minIso = device.activeFormat.minISO
                        let maxIso = device.activeFormat.maxISO
                        let targetIso = min(max(newIso, minIso), maxIso)

                        device.setExposureModeCustom(duration: targetDuration, iso: targetIso, completionHandler: nil)

                        // Record actual commanded hardware exposure
                        let appliedUs = Int32(round(CMTimeGetSeconds(targetDuration) * 1_000_000.0))
                        self.appliedExposureUs = appliedUs
                        self.appliedIso = targetIso
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

        if let dev = currentCameraDevice {
            let rawExp = dev.exposureDuration
            if rawExp.isValid && rawExp.seconds > 0.0 {
                let measuredUs = Int32(round(CMTimeGetSeconds(rawExp) * 1_000_000.0))
                if measuredUs > 0 {
                    self.appliedExposureUs = measuredUs
                }
            }
            let rawIso = dev.iso
            if rawIso > 0.0 {
                self.appliedIso = rawIso
            }
        }

        let exposure = self.appliedExposureUs
        let iso = self.appliedIso
        let zoom = Float(self.currentCameraDevice?.videoZoomFactor ?? 1.0)

        olive_ios_push_camera_frame(
            baseAddress.assumingMemoryBound(to: UInt8.self),
            width,
            height,
            stride,
            timestampNs,
            exposure,
            iso,
            zoom
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

// MARK: - Codec FourCC Helpers

private func fourCCToString(_ code: FourCharCode) -> String {
    let bytes: [CChar] = [
        CChar((code >> 24) & 0xff),
        CChar((code >> 16) & 0xff),
        CChar((code >> 8) & 0xff),
        CChar(code & 0xff),
        0
    ]
    return String(cString: bytes)
}

private func isMjpegCodec(subType: FourCharCode) -> Bool {
    if subType == kCMVideoCodecType_JPEG ||
       subType == kCMVideoCodecType_JPEG_OpenDML ||
       subType == 0x4D4A5047 /* 'MJPG' */ ||
       subType == 0x6D6A7067 /* 'mjpg' */ ||
       subType == 0x4156444A /* 'AVDJ' */ {
        return true
    }
    let str = fourCCToString(subType).lowercased()
    return str.contains("jpg") || str.contains("jpeg")
}

private func isYuvCodec(subType: FourCharCode) -> Bool {
    if subType == kCVPixelFormatType_422YpCbCr8_yuvs ||
       subType == kCVPixelFormatType_422YpCbCr8 ||
       subType == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange ||
       subType == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange ||
       subType == 0x79757632 /* 'yuv2' */ ||
       subType == 0x59555932 /* 'YUY2' */ ||
       subType == 0x59555956 /* 'YUYV' */ ||
       subType == 0x55595659 /* 'UYVY' */ ||
       subType == 0x32767579 /* '2vuy' */ {
        return true
    }
    let str = fourCCToString(subType).lowercased()
    return str.contains("yuv") || str.contains("yuy") || str.contains("uyv") || str.contains("420") || str.contains("422")
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

@_cdecl("olive_ios_get_external_camera_formats")
public func olive_ios_get_external_camera_formats() -> UnsafeMutablePointer<CChar>? {
    guard #available(iOS 17.0, *) else {
        return strdup("[]")
    }

    let discovery = AVCaptureDevice.DiscoverySession(
        deviceTypes: [.external],
        mediaType: .video,
        position: .unspecified
    )
    guard let device = discovery.devices.first else {
        return strdup("[]")
    }

    var formatsList: [[String: Any]] = []
    var seenKeys = Set<String>()

    // Helper to check if a full-range '420f' format exists for a given resolution AND frame rate
    let hasFullRangeForDimsAndFps: (Int32, Int32, Int) -> Bool = { w, h, fps in
        device.formats.contains { fmt in
            let dims = CMVideoFormatDescriptionGetDimensions(fmt.formatDescription)
            guard dims.width == w && dims.height == h else { return false }
            let subType = CMFormatDescriptionGetMediaSubType(fmt.formatDescription)
            guard subType == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange else { return false }
            let desired = Double(fps)
            return fmt.videoSupportedFrameRateRanges.contains {
                desired >= ($0.minFrameRate - 0.1) && desired <= ($0.maxFrameRate + 0.1)
            }
        }
    }

    for format in device.formats {
        let dims = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
        guard dims.width > 0 && dims.height > 0 else { continue }

        let subType = CMFormatDescriptionGetMediaSubType(format.formatDescription)
        let fourCC = fourCCToString(subType)
        let lower = fourCC.lowercased()
        if lower.contains("avc") || lower.contains("h264") || lower.contains("hvc") || lower.contains("hevc") {
            continue
        }

        let isMjpeg = isMjpegCodec(subType: subType)
        let isYuv = isYuvCodec(subType: subType)
        guard isMjpeg || isYuv else { continue }

        for range in format.videoSupportedFrameRateRanges {
            let minFps = Int(floor(range.minFrameRate))
            let maxFps = Int(ceil(range.maxFrameRate))
            let candidateFpsList: [Int] = {
                if minFps == maxFps {
                    return [maxFps]
                } else {
                    return [30, 25, 20, 15, 10, 5, 2, 1].filter { stdFps in
                        Double(stdFps) >= (range.minFrameRate - 0.1) && Double(stdFps) <= (range.maxFrameRate + 0.1)
                    }
                }
            }()

            for stdFps in candidateFpsList {
                // If this is video-range '420v' but the device also provides full-range '420f'
                // for this exact resolution and frame rate, skip '420v' so full range is preserved!
                if subType == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange &&
                   hasFullRangeForDimsAndFps(dims.width, dims.height, stdFps) {
                    continue
                }

                // Determine physical wire format:
                // On USB 2.0 (High Speed), max isochronous bandwidth is 24,576,000 bytes/sec.
                // Any mode whose uncompressed rate exceeds 24.576 MB/s is physically transported
                // as compressed MJPEG over the USB cable and decoded to '420f' by Apple's UVC driver.
                // Modes <= 24.576 MB/s can be transported as uncompressed YUV2.
                let uncompressedBytesPerSec = Int64(dims.width) * Int64(dims.height) * 2 * Int64(stdFps)
                let formatName: String
                if isMjpeg || subType == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange {
                    formatName = "MJPEG"
                } else {
                    formatName = (uncompressedBytesPerSec > 24_576_000) ? "MJPEG" : "YUV2"
                }

                let key = "\(dims.width)x\(dims.height)@\(stdFps):\(formatName)"
                if seenKeys.contains(key) { continue }
                seenKeys.insert(key)

                print("[CameraService] Discovered external format: \(dims.width)x\(dims.height) '\(fourCC)' @ \(stdFps) fps -> \(formatName)")
                formatsList.append([
                    "width": Int(dims.width),
                    "height": Int(dims.height),
                    "fps": stdFps,
                    "format": formatName
                ])
            }
        }
    }

    guard let jsonData = try? JSONSerialization.data(withJSONObject: formatsList, options: []),
          let jsonString = String(data: jsonData, encoding: .utf8) else {
        return strdup("[]")
    }
    return strdup(jsonString)
}

@_cdecl("olive_ios_start_hardware_capture")
public func olive_ios_start_hardware_capture(
    cameraId: UnsafePointer<CChar>?,
    width: Int32,
    height: Int32,
    fps: Int32,
    format: UnsafePointer<CChar>?,
    initialZoom: Float
) {
    let idStr = cameraId != nil ? String(cString: cameraId!) : "0"
    let formatStr = format != nil ? String(cString: format!) : nil
    CameraService.shared.startCamera(
        cameraId: idStr,
        targetWidth: width,
        targetHeight: height,
        targetFps: fps,
        targetFormat: formatStr,
        initialZoom: initialZoom
    ) { _ in }
    MotionService.shared.startMotionUpdates()
}

@_cdecl("olive_ios_stop_hardware_capture")
public func olive_ios_stop_hardware_capture() {
    CameraService.shared.stopCamera()
    MotionService.shared.stopMotionUpdates()
}
