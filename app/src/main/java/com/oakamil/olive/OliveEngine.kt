package com.oakamil.olive

import java.nio.ByteBuffer

object OliveEngine {
    init {
        System.loadLibrary("olive_jni")
    }


    external fun initJni(filesDir: String)


    external fun startNativeCameraEngine(
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

    external fun startExternalCameraEngine(
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

    external fun pushExternalUvcFrame(buffer: ByteBuffer, width: Int, height: Int, timestamp: Long)

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
    external fun getLatestFrameInto(buffer: ByteBuffer, lastTimestamp: Long): Long
    external fun setEnginePaused(paused: Boolean)
    external fun setEngineSigma(sigma: Float)
    external fun setEngineStrictMatch(strict: Boolean)
    external fun updateUvcStacking(frames: Int)
    external fun processTelescopeData(data: ByteArray): ByteArray?
    external fun resetTelescopeServer()
    external fun handleAlpacaConnection(fd: Int)
    external fun getPlanets(bspPath: String, timeMs: Long, lat: Double, lon: Double): String
    external fun getAsteroids(
        bspPath: String,
        timeMs: Long,
        lat: Double,
        lon: Double,
        asteroidsJson: String
    ): String

    external fun getComets(
        bspPath: String,
        timeMs: Long,
        lat: Double,
        lon: Double,
        jsonArray: String
    ): String

    external fun setGoToTarget(ra: Double, dec: Double, epoch: Double)
    external fun clearGoToTarget()
    external fun setAlignTargetPixel(x: Double, y: Double)
    external fun syncTelescope()
    external fun syncTelescopeToTarget(ra: Double, dec: Double)
}
