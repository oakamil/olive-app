package com.oakamil.olive

import java.nio.ByteBuffer

object OliveEngine : EngineBridge {
    init {
        System.loadLibrary("olive_jni")
    }

    external override fun initJni(filesDir: String)

    external override fun startNativeCameraEngine(
        motionSensorMode: Int,
        extractor: String,
        sensorMode: String,
        internalCameraId: String,
        downsampleMode: Int,
        lat: Double,
        lon: Double,
        timeMs: Long,
        settingsManager: SettingsManager,
        autoMode: Boolean
    )

    external override fun startExternalCameraEngine(
        width: Int,
        height: Int,
        motionSensorMode: Int,
        extractor: String,
        downsampleMode: Int,
        lat: Double,
        lon: Double,
        timeMs: Long,
        settingsManager: SettingsManager,
        autoMode: Boolean,
        uvcFps: Int,
        uvcWrapper: UvcCameraWrapper?
    )

    external override fun pushExternalUvcFrame(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        timestamp: Long
    )

    external override fun stopEngine()
    external override fun getEngineStats(): String
    external override fun setNativeCameraExposure(exposureUs: Int)
    external override fun setNativeCameraGain(gain: Float)
    external override fun setNativeCameraFocus(distance: Float)
    external override fun setNativeCameraNoiseReduction(mode: Int)
    external override fun getNativeCameraExposure(): Int
    external override fun getNativeCameraGainRange(): FloatArray?
    external override fun getNativeCameraGain(): Float
    external override fun getNativeCameraNoiseReduction(): Int
    external override fun getNativeCameraBrightnessRange(): IntArray?
    external override fun getNativeCameraBrightness(): Int
    external override fun setNativeCameraBrightness(brightness: Int)
    external override fun setNativeCameraZoomRatio(zoom: Float)
    external override fun getNativeCameraZoomRatio(): Float
    external override fun getNativeCameraFocus(): Float
    external override fun getNativeCameraSupportedHotPixelModes(): IntArray
    external override fun setNativeCameraHotPixelMode(mode: Int)
    external override fun getNativeCameraHotPixelMode(): Int
    external override fun getAvailableInternalCameras(): String
    external override fun getLatestFrameInto(buffer: ByteBuffer, lastTimestamp: Long): Long
    external override fun setEnginePaused(paused: Boolean)
    external override fun setEngineSigma(sigma: Float)
    external override fun setEngineStrictMatch(strict: Boolean)
    external override fun updateUvcStacking(frames: Int)
    external override fun processTelescopeData(data: ByteArray): ByteArray?
    external override fun resetTelescopeServer()
    external override fun handleAlpacaConnection(fd: Int)
    external override fun getPlanets(
        bspPath: String,
        timeMs: Long,
        lat: Double,
        lon: Double
    ): String

    external override fun getAsteroids(
        bspPath: String,
        timeMs: Long,
        lat: Double,
        lon: Double,
        asteroidsJson: String
    ): String

    external override fun getComets(
        bspPath: String,
        timeMs: Long,
        lat: Double,
        lon: Double,
        jsonArray: String
    ): String

    external override fun setGoToTarget(ra: Double, dec: Double, epoch: Double)
    external override fun clearGoToTarget()
    external override fun setAlignTargetPixel(x: Double, y: Double)
    external override fun syncTelescope()
    external override fun syncTelescopeToTarget(ra: Double, dec: Double)
}
