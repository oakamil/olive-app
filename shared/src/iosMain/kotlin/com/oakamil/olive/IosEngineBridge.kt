// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.ui.graphics.asComposeImageBitmap
import com.oakamil.olive.cinterop.olive_ios_clear_align_target_pixel
import com.oakamil.olive.cinterop.olive_ios_clear_goto_target
import com.oakamil.olive.cinterop.olive_ios_free_string
import com.oakamil.olive.cinterop.olive_ios_get_asteroids
import com.oakamil.olive.cinterop.olive_ios_get_available_cameras
import com.oakamil.olive.cinterop.olive_ios_get_camera_brightness
import com.oakamil.olive.cinterop.olive_ios_get_camera_exposure
import com.oakamil.olive.cinterop.olive_ios_get_camera_focus
import com.oakamil.olive.cinterop.olive_ios_get_camera_gain
import com.oakamil.olive.cinterop.olive_ios_get_camera_gain_range
import com.oakamil.olive.cinterop.olive_ios_get_camera_zoom
import com.oakamil.olive.cinterop.olive_ios_get_comets
import com.oakamil.olive.cinterop.olive_ios_get_engine_stats
import com.oakamil.olive.cinterop.olive_ios_get_external_camera_formats
import com.oakamil.olive.cinterop.olive_ios_get_latest_frame_into
import com.oakamil.olive.cinterop.olive_ios_get_planets
import com.oakamil.olive.cinterop.olive_ios_set_align_target_pixel
import com.oakamil.olive.cinterop.olive_ios_set_camera_brightness
import com.oakamil.olive.cinterop.olive_ios_set_camera_exposure
import com.oakamil.olive.cinterop.olive_ios_set_camera_focus
import com.oakamil.olive.cinterop.olive_ios_set_camera_gain
import com.oakamil.olive.cinterop.olive_ios_set_camera_stack_count
import com.oakamil.olive.cinterop.olive_ios_set_camera_zoom
import com.oakamil.olive.cinterop.olive_ios_set_engine_paused
import com.oakamil.olive.cinterop.olive_ios_set_engine_sigma
import com.oakamil.olive.cinterop.olive_ios_set_engine_strict_match
import com.oakamil.olive.cinterop.olive_ios_set_goto_target
import com.oakamil.olive.cinterop.olive_ios_start_engine
import com.oakamil.olive.cinterop.olive_ios_start_hardware_capture
import com.oakamil.olive.cinterop.olive_ios_stop_engine
import com.oakamil.olive.cinterop.olive_ios_stop_hardware_capture
import com.oakamil.olive.cinterop.olive_ios_sync_telescope
import com.oakamil.olive.cinterop.olive_ios_sync_telescope_to_target
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import platform.posix.free

@OptIn(ExperimentalForeignApi::class)
class IosEngineBridge : EngineBridge {

    private var frameCapacity = 2048 * 1536 * 4 + 8
    private var frameRawBuffer = ByteArray(frameCapacity)

    private fun ensureFrameCapacity(requiredBytes: Int) {
        if (frameCapacity < requiredBytes) {
            val newCap = requiredBytes.coerceAtLeast(52 * 1024 * 1024)
            frameCapacity = newCap
            frameRawBuffer = ByteArray(newCap)
        }
    }


    override fun startNativeCameraEngine(
        motionSensorMode: Int,
        sensorMode: String,
        internalCameraId: String,
        downsampleMode: Int,
        lat: Double,
        lon: Double,
        settingsManager: SettingsManager,
        autoMode: Boolean,
        initialZoom: Float
    ) {
        val isDemo = internalCameraId == "demo"
        val camerasJson = getAvailableInternalCameras()
        var selectedFov = 0.0f
        var selectedMaxZoom = 1.0f
        var selectedWidth = 2016u
        var selectedHeight = 1512u
        try {
            val jsonArray = JSONArray(camerasJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                if (obj.getString("id") == internalCameraId) {
                    val f = obj.getDouble("fov").toFloat()
                    if (f >= 10.0f) {
                        selectedFov = f
                    }
                    val mz = obj.optDouble("max_zoom", 1.0).toFloat()
                    if (mz >= 1.0f) {
                        selectedMaxZoom = mz
                    }
                    if (obj.has("width") && obj.has("height")) {
                        val w = obj.optInt("width", 2016)
                        val h = obj.optInt("height", 1512)
                        if (w > 0 && h > 0) {
                            selectedWidth = w.toUInt()
                            selectedHeight = h.toUInt()
                        }
                    }
                    break
                }
            }
        } catch (_: Exception) {
        }

        val effectiveZoom =
            if (isDemo) 1.0f else initialZoom.coerceIn(1.0f, maxOf(1.0f, selectedMaxZoom))

        val started = olive_ios_start_engine(
            selectedWidth,
            selectedHeight,
            if (isDemo) 0 else motionSensorMode,
            if (isDemo) 0 else downsampleMode,
            lat,
            lon,
            if (isDemo) false else autoMode,
            selectedFov,
            internalCameraId,
            effectiveZoom
        )
        check(started) { "Failed to initialize Olive iOS engine (check database path and permissions)" }

        if (!isDemo) {
            val hwStarted = olive_ios_start_hardware_capture(
                internalCameraId,
                selectedWidth.toInt(),
                selectedHeight.toInt(),
                30,
                null,
                effectiveZoom
            )
            if (!hwStarted) {
                stopEngine()
                error("Failed to start iOS internal camera capture")
            }
        }
    }

    override fun startExternalCameraEngine(
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
    ) {
        val reqWidth = if (width > 0) width else settingsManager.uvcWidth
        val reqHeight = if (height > 0) height else settingsManager.uvcHeight
        val reqFps = if (uvcFps > 0) uvcFps else settingsManager.uvcFps
        val reqFormat = settingsManager.uvcFormat

        val requiredBytes = reqWidth * reqHeight * 4 + 8
        ensureFrameCapacity(requiredBytes)

        val w = if (reqWidth > 0) reqWidth.toUInt() else 1920u
        val h = if (reqHeight > 0) reqHeight.toUInt() else 1080u
        val started = olive_ios_start_engine(
            w,
            h,
            motionSensorMode,
            downsampleMode,
            lat,
            lon,
            autoMode,
            0.0f,
            "external",
            1.0f
        )
        check(started) { "Failed to initialize Olive iOS external UVC engine" }

        val hwStarted = olive_ios_start_hardware_capture(
            "external",
            reqWidth,
            reqHeight,
            reqFps,
            reqFormat,
            1.0f
        )
        if (!hwStarted) {
            stopEngine()
            error("Failed to start iOS external camera capture")
        }
    }

    override fun stopEngine() {
        olive_ios_stop_engine()
        olive_ios_stop_hardware_capture()
    }

    override fun getEngineStats(): String {
        val ptr = olive_ios_get_engine_stats() ?: return "{}"
        val stats = ptr.toKString()
        olive_ios_free_string(ptr)
        return stats
    }

    override fun setNativeCameraExposure(exposureUs: Int) {
        olive_ios_set_camera_exposure(exposureUs)
    }

    override fun setNativeCameraGain(gain: Float) {
        olive_ios_set_camera_gain(gain)
    }

    override fun setNativeCameraFocus(distance: Float) {
        olive_ios_set_camera_focus(distance)
    }

    override fun setNativeCameraNoiseReduction(mode: Int) {
        // Handled automatically by AVFoundation
    }

    override fun getNativeCameraExposure(): Int {
        return olive_ios_get_camera_exposure()
    }

    override fun getNativeCameraGainRange(): FloatArray = memScoped {
        val minGain = alloc<FloatVar>()
        val maxGain = alloc<FloatVar>()
        val defaultGain = alloc<FloatVar>()
        olive_ios_get_camera_gain_range(minGain.ptr, maxGain.ptr, defaultGain.ptr)
        floatArrayOf(minGain.value, maxGain.value, defaultGain.value)
    }

    override fun getNativeCameraGain(): Float {
        return olive_ios_get_camera_gain()
    }

    override fun getNativeCameraNoiseReduction(): Int {
        return 0
    }

    override fun getNativeCameraBrightnessRange(): IntArray {
        return intArrayOf(0, 100, 50)
    }

    override fun getNativeCameraBrightness(): Int {
        return olive_ios_get_camera_brightness()
    }

    override fun setNativeCameraBrightness(brightness: Int) {
        olive_ios_set_camera_brightness(brightness)
    }

    override fun setNativeCameraZoomRatio(zoom: Float) {
        olive_ios_set_camera_zoom(zoom)
    }

    override fun getNativeCameraZoomRatio(): Float {
        return olive_ios_get_camera_zoom()
    }

    override fun getNativeCameraFocus(): Float {
        return olive_ios_get_camera_focus()
    }

    override fun getNativeCameraSupportedHotPixelModes(): IntArray {
        return intArrayOf(0)
    }

    override fun setNativeCameraHotPixelMode(mode: Int) {
        // Not used on iOS
    }

    override fun getNativeCameraHotPixelMode(): Int {
        return 0
    }

    override fun getAvailableInternalCameras(): String {
        val ptr = olive_ios_get_available_cameras()
            ?: return "[{\"id\":\"0\",\"name\":\"Back Camera (Wide)\",\"fov\":65.0,\"supports_raw\":false}]"
        val res = ptr.toKString()
        free(ptr)
        return res
    }

    override fun getAvailableExternalCameraFormats(): String {
        val ptr = olive_ios_get_external_camera_formats() ?: return "[]"
        val res = ptr.toKString()
        free(ptr)
        return res
    }

    override fun getLatestFrame(lastTimestamp: Long): LatestFrame? {
        return frameRawBuffer.usePinned { pinned ->
            val ptr = pinned.addressOf(0).reinterpret<UByteVar>()
            val newTimestamp = olive_ios_get_latest_frame_into(
                ptr,
                frameCapacity.toULong(),
                lastTimestamp
            )
            if (newTimestamp <= lastTimestamp || newTimestamp == 0L) {
                return null
            }

            val width = (frameRawBuffer[0].toInt() and 0xFF) or
                    ((frameRawBuffer[1].toInt() and 0xFF) shl 8) or
                    ((frameRawBuffer[2].toInt() and 0xFF) shl 16) or
                    ((frameRawBuffer[3].toInt() and 0xFF) shl 24)
            val height = (frameRawBuffer[4].toInt() and 0xFF) or
                    ((frameRawBuffer[5].toInt() and 0xFF) shl 8) or
                    ((frameRawBuffer[6].toInt() and 0xFF) shl 16) or
                    ((frameRawBuffer[7].toInt() and 0xFF) shl 24)

            if (width <= 0 || height <= 0 || width > 4096 || height > 4096) {
                return null
            }

            val pixelByteCount = width * height * 4
            if (pixelByteCount + 8 > frameCapacity) {
                return null
            }

            val imageInfo = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
            val pixels = frameRawBuffer.copyOfRange(8, 8 + pixelByteCount)

            val skiaBitmap = Bitmap()
            skiaBitmap.installPixels(imageInfo, pixels, width * 4)
            skiaBitmap.setImmutable()
            val composeBitmap = skiaBitmap.asComposeImageBitmap()
            LatestFrame(newTimestamp, composeBitmap)
        }
    }

    override fun setEnginePaused(paused: Boolean) {
        olive_ios_set_engine_paused(paused)
    }

    override fun setEngineSigma(sigma: Float) {
        olive_ios_set_engine_sigma(sigma)
    }

    override fun setEngineStrictMatch(strict: Boolean) {
        olive_ios_set_engine_strict_match(strict)
    }

    override fun updateUvcStacking(frames: Int) {
        olive_ios_set_camera_stack_count(frames.coerceAtLeast(1).toULong())
    }

    override fun getPlanets(bspPath: String, timeMs: Long, lat: Double, lon: Double): String {
        val ptr = olive_ios_get_planets(bspPath, timeMs, lat, lon) ?: return "[]"
        val res = ptr.toKString()
        olive_ios_free_string(ptr)
        return res
    }

    override fun getAsteroids(
        bspPath: String,
        timeMs: Long,
        lat: Double,
        lon: Double,
        asteroidsJson: String
    ): String {
        val ptr = olive_ios_get_asteroids(bspPath, timeMs, lat, lon, asteroidsJson) ?: return "[]"
        val res = ptr.toKString()
        olive_ios_free_string(ptr)
        return res
    }

    override fun getComets(
        bspPath: String,
        timeMs: Long,
        lat: Double,
        lon: Double,
        jsonArray: String
    ): String {
        val ptr = olive_ios_get_comets(bspPath, timeMs, lat, lon, jsonArray) ?: return "[]"
        val res = ptr.toKString()
        olive_ios_free_string(ptr)
        return res
    }

    override fun setGoToTarget(ra: Double, dec: Double, epoch: Double) {
        olive_ios_set_goto_target(ra, dec, epoch)
    }

    override fun clearGoToTarget() {
        olive_ios_clear_goto_target()
    }

    override fun setAlignTargetPixel(x: Double, y: Double) {
        olive_ios_set_align_target_pixel(x, y)
    }

    override fun clearAlignTargetPixel() {
        olive_ios_clear_align_target_pixel()
    }

    override fun syncTelescope() {
        olive_ios_sync_telescope()
    }

    override fun syncTelescopeToTarget(ra: Double, dec: Double) {
        olive_ios_sync_telescope_to_target(ra, dec)
    }
}
