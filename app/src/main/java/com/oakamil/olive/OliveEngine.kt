package com.oakamil.olive

import java.nio.ByteBuffer

object OliveEngine {
    init {
        System.loadLibrary("olive_jni")
    }

    external fun initJni(filesDir: String)
    external fun startEngine(
        fd: Int,
        rawDescriptors: ByteArray,
        imuEnabled: Boolean,
        uvcFps: Int,
        extractor: String,
        downsampleMode: Int,
        lat: Double,
        lon: Double,
        timeMs: Long,
        settingsManager: SettingsManager
    )

    external fun startNativeCameraEngine(
        imuEnabled: Boolean,
        extractor: String,
        sensorMode: String,
        internalCameraId: String,
        downsampleMode: Int,
        lat: Double,
        lon: Double,
        timeMs: Long,
        settingsManager: SettingsManager
    )

    external fun stopEngine()
    external fun getEngineStats(): String
    external fun setNativeCameraExposure(exposureUs: Int)
    external fun setNativeCameraGain(gain: Float)
    external fun setNativeCameraFocus(distance: Float)
    external fun setNativeCameraNoiseReduction(mode: Int)
    external fun getNativeCameraExposure(): Int
    external fun getNativeCameraGainRange(): FloatArray?
    external fun getNativeCameraGain(): Float
    external fun getNativeCameraBrightnessRange(): IntArray?
    external fun getNativeCameraBrightness(): Int
    external fun setNativeCameraBrightness(brightness: Int)
    external fun setNativeCameraZoomRatio(zoom: Float)
    external fun getNativeCameraZoomRatio(): Float
    external fun getNativeCameraFocus(): Float
    external fun getNativeCameraSupportedHotPixelModes(): IntArray
    external fun setNativeCameraHotPixelMode(mode: Int)
    external fun getNativeCameraHotPixelMode(): Int
    external fun getAvailableInternalCameras(): String
    external fun getLatestFrameInto(buffer: ByteBuffer): Boolean
    external fun setEnginePaused(paused: Boolean)
    external fun setEngineSigma(sigma: Float)
    external fun updateUvcStacking(frames: Int)
    external fun processTelescopeData(data: ByteArray): ByteArray?
    external fun resetTelescopeServer()
    external fun handleAlpacaConnection(fd: Int)
    external fun getPlanets(bspPath: String, timeMs: Long, lat: Double, lon: Double): String
    external fun setGoToTarget(ra: Double, dec: Double)
    external fun setAlignTargetPixel(x: Double, y: Double)
}
