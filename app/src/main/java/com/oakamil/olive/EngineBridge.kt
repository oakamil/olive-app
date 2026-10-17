package com.oakamil.olive

import java.nio.ByteBuffer

interface EngineBridge {
    fun initJni(filesDir: String)

    fun startNativeCameraEngine(
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

    fun startExternalCameraEngine(
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

    fun pushExternalUvcFrame(buffer: ByteBuffer, width: Int, height: Int, timestamp: Long)

    fun stopEngine()
    fun getEngineStats(): String
    fun setNativeCameraExposure(exposureUs: Int)
    fun setNativeCameraGain(gain: Float)
    fun setNativeCameraFocus(distance: Float)
    fun setNativeCameraNoiseReduction(mode: Int)
    fun getNativeCameraExposure(): Int
    fun getNativeCameraGainRange(): FloatArray?
    fun getNativeCameraGain(): Float
    fun getNativeCameraNoiseReduction(): Int
    fun getNativeCameraBrightnessRange(): IntArray?
    fun getNativeCameraBrightness(): Int
    fun setNativeCameraBrightness(brightness: Int)
    fun setNativeCameraZoomRatio(zoom: Float)
    fun getNativeCameraZoomRatio(): Float
    fun getNativeCameraFocus(): Float
    fun getNativeCameraSupportedHotPixelModes(): IntArray
    fun setNativeCameraHotPixelMode(mode: Int)
    fun getNativeCameraHotPixelMode(): Int
    fun getAvailableInternalCameras(): String
    fun getLatestFrameInto(buffer: ByteBuffer, lastTimestamp: Long): Long
    fun setEnginePaused(paused: Boolean)
    fun setEngineSigma(sigma: Float)
    fun setEngineStrictMatch(strict: Boolean)
    fun updateUvcStacking(frames: Int)
    fun processTelescopeData(data: ByteArray): ByteArray?
    fun resetTelescopeServer()
    fun handleAlpacaConnection(fd: Int)
    fun getPlanets(bspPath: String, timeMs: Long, lat: Double, lon: Double): String
    fun getAsteroids(
        bspPath: String,
        timeMs: Long,
        lat: Double,
        lon: Double,
        asteroidsJson: String
    ): String

    fun getComets(
        bspPath: String,
        timeMs: Long,
        lat: Double,
        lon: Double,
        jsonArray: String
    ): String

    fun setGoToTarget(ra: Double, dec: Double, epoch: Double)
    fun clearGoToTarget()
    fun setAlignTargetPixel(x: Double, y: Double)
    fun syncTelescope()
    fun syncTelescopeToTarget(ra: Double, dec: Double)
}
