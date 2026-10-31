package com.oakamil.olive

import androidx.compose.ui.graphics.asImageBitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder

object OliveEngine : EngineBridge {
    init {
        System.loadLibrary("olive_jni")
    }

    external fun initJni(filesDir: String)

    external override fun startNativeCameraEngine(
        motionSensorMode: Int,
        extractor: String,
        sensorMode: String,
        internalCameraId: String,
        downsampleMode: Int,
        lat: Double,
        lon: Double,
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
        settingsManager: SettingsManager,
        autoMode: Boolean,
        uvcFps: Int,
        uvcWrapper: UvcCameraHandler?
    )

    external fun pushExternalUvcFrame(
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

    external fun getLatestFrameInto(buffer: ByteBuffer, lastTimestamp: Long): Long

    private var frameBuffer: ByteBuffer? = null

    override fun getLatestFrame(lastTimestamp: Long): LatestFrame? {
        val targetSize = 52 * 1024 * 1024
        if (frameBuffer == null || frameBuffer!!.capacity() < targetSize) {
            frameBuffer = ByteBuffer.allocateDirect(targetSize).order(ByteOrder.LITTLE_ENDIAN)
        }
        val buffer = frameBuffer ?: return null

        val ts = getLatestFrameInto(buffer, lastTimestamp)
        if (ts <= 0L || ts <= lastTimestamp) return null

        buffer.rewind()
        val width = buffer.int
        val height = buffer.int
        val pixelCount = width * height
        if (pixelCount !in 1..25000000) return null

        val bitmap = android.graphics.Bitmap.createBitmap(
            width,
            height,
            android.graphics.Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return LatestFrame(ts, bitmap.asImageBitmap())
    }

    external override fun setEnginePaused(paused: Boolean)
    external override fun setEngineSigma(sigma: Float)
    external override fun setEngineStrictMatch(strict: Boolean)
    external override fun updateUvcStacking(frames: Int)
    external override fun processTelescopeData(data: ByteArray): ByteArray?
    external override fun resetTelescopeServer()
    external fun handleAlpacaConnection(fd: Int)
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
    external override fun clearAlignTargetPixel()
    external override fun syncTelescope()
    external override fun syncTelescopeToTarget(ra: Double, dec: Double)
}
