package com.oakamil.olive

import java.nio.ByteBuffer

object OliveEngine {
    init {
        System.loadLibrary("olive_jni")
    }

    external fun initJni(filesDir: String)
    external fun startEngine(
        fd: Int,
        imuEnabled: Boolean,
        uvcFps: Int,
        extractor: String,
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
    external fun getNativeCameraBrightnessRange(): IntArray?
    external fun setNativeCameraBrightness(brightness: Int)
    external fun setNativeCameraZoomRatio(zoom: Float)
    external fun getAvailableInternalCameras(): String
    external fun getLatestFrameInto(buffer: ByteBuffer): Boolean
    external fun setEnginePaused(paused: Boolean)
    external fun setEngineSigma(sigma: Float)
    external fun updateUvcStacking(frames: Int)
    external fun handleTelescopeConnection(fd: Int)
}
