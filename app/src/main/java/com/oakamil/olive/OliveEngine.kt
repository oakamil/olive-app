// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * JNI bridge to the native Rust engine (`libolive_jni.so`, implemented in `app/src/main/rust/src/lib.rs`).
 *
 * Exposes engine/camera lifecycle, Camera2 and UVC controls, direct-ByteBuffer frame transfer,
 * LX200/ASCOM Alpaca telescope protocol handoff, and JPL ephemeris queries.
 */
object OliveEngine : EngineBridge {
    init {
        System.loadLibrary("olive_jni")
    }

    external fun initJni(filesDir: String)

    external override fun startNativeCameraEngine(
        motionSensorMode: Int,
        sensorMode: String,
        internalCameraId: String,
        downsampleMode: Int,
        lat: Double,
        lon: Double,
        settingsManager: SettingsManager,
        autoMode: Boolean,
        initialZoom: Float
    )

    external override fun startExternalCameraEngine(
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

    external fun pushExternalUvcFrame(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        timestamp: Long
    )

    // Native libuvc workarounds taking UVCCamera's raw mNativePtr (see Rust lib.rs).
    external fun disableUvcStatusCallback(nativePtr: Long): Boolean
    external fun quiesceUvcStream(nativePtr: Long): Boolean

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

    /**
     * Copies the latest grayscale frame expanded to RGBA_8888 into a direct [ByteBuffer]
     * using the layout `[width: u32 LE][height: u32 LE][RGBA pixels...]`.
     * Returns the frame timestamp in ms, or <= [lastTimestamp] / 0 when no newer frame is ready.
     */
    external fun getLatestFrameInto(buffer: ByteBuffer, lastTimestamp: Long): Long

    // Reused across polls; expected to be called from a single consumer thread/coroutine.
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

        val bitmap = createBitmap(width, height)
        bitmap.copyPixelsFromBuffer(buffer)
        return LatestFrame(ts, bitmap.asImageBitmap())
    }

    external override fun setHfrEnabled(enabled: Boolean)
    external override fun setEngineSigma(sigma: Float)
    external override fun setEngineStrictMatch(strict: Boolean)
    external override fun updateUvcStacking(frames: Int)
    external fun processTelescopeData(data: ByteArray): ByteArray?
    external fun resetTelescopeServer()

    // Transfers ownership of a detached POSIX socket fd to the native Alpaca server (Rust closes it).
    external fun handleAlpacaConnection(fd: Int)
    external fun handleAlpacaConnectionV6(fd: Int)
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
