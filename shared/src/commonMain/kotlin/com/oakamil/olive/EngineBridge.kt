package com.oakamil.olive

import androidx.compose.ui.graphics.ImageBitmap

class LatestFrame(
    val timestamp: Long,
    val imageBitmap: ImageBitmap
)

interface EngineBridge {
    fun startNativeCameraEngine(
        motionSensorMode: Int,
        sensorMode: String,
        internalCameraId: String,
        downsampleMode: Int,
        lat: Double,
        lon: Double,
        settingsManager: SettingsManager,
        autoMode: Boolean,
        initialZoom: Float = 1.0f
    )

    fun startExternalCameraEngine(
        width: Int,
        height: Int,
        motionSensorMode: Int,
        downsampleMode: Int,
        lat: Double,
        lon: Double,
        settingsManager: SettingsManager,
        autoMode: Boolean,
        uvcFps: Int,
        uvcWrapper: UvcCameraHandler?
    )

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
    fun getAvailableExternalCameraFormats(): String = ""
    fun getLatestFrame(lastTimestamp: Long): LatestFrame?
    fun setEnginePaused(paused: Boolean)
    fun setEngineSigma(sigma: Float)
    fun setEngineStrictMatch(strict: Boolean)
    fun updateUvcStacking(frames: Int)
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
    fun clearAlignTargetPixel()
    fun syncTelescope()
    fun syncTelescopeToTarget(ra: Double, dec: Double)
}
