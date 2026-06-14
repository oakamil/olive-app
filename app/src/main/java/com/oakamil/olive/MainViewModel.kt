package com.oakamil.olive

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.nio.ByteBuffer
import androidx.core.graphics.createBitmap

class MainViewModel(application: Application) : AndroidViewModel(application) {
    enum class EngineMode { STOPPED, UVC, NATIVE }

    var engineMode by mutableStateOf(EngineMode.STOPPED)
        private set

    val isEngineRunning: Boolean
        get() = engineMode != EngineMode.STOPPED

    var engineStats by mutableStateOf(EngineStats())
        private set

    // Tuning state
    var currentExposure by mutableFloatStateOf(50000f)
    var currentGain by mutableFloatStateOf(100f)
    var minAnalogGain by mutableFloatStateOf(100f)
    var maxAnalogGain by mutableFloatStateOf(3200f)
    var currentFocus by mutableFloatStateOf(0.0f)
    var noiseReductionMode by mutableIntStateOf(0)
    var hotPixelMode by mutableIntStateOf(0)
    var supportedHotPixelModes by mutableStateOf<List<Int>>(emptyList())
    var uvcBrightnessMin by mutableIntStateOf(0)
    var uvcBrightnessMax by mutableIntStateOf(0)
    var currentUvcBrightness by mutableIntStateOf(0)
    var latestFrame by mutableStateOf<ImageBitmap?>(null)
    var starSensitivity by mutableFloatStateOf(50f)
    var uvcStacking by mutableIntStateOf(1)

    val currentSigma: Float
        get() {
            val s = starSensitivity / 100f // 0.0 to 1.0
            return if (settingsManager.extractor == "Cedar") {
                15.0f - (s * 14.0f)
            } else {
                5.0f - (s * 3.0f)
            }
        }

    private var statsJob: Job? = null
    private var frameJob: Job? = null

    private val settingsManager = SettingsManager(application)

    fun getAvailableInternalCameras(): String {
        return OliveEngine.getAvailableInternalCameras()
    }

    private fun updateStats() {
        val json = OliveEngine.getEngineStats()
        try {
            val obj = JSONObject(json)
            engineStats = EngineStats(
                status = obj.optString("status", "Idle"),
                ra = if (obj.isNull("ra")) null else obj.getDouble("ra"),
                dec = if (obj.isNull("dec")) null else obj.getDouble("dec"),
                roll = if (obj.isNull("roll")) null else obj.getDouble("roll"),
                fov = if (obj.isNull("fov")) null else obj.getDouble("fov"),
                centroidCount = obj.optInt("centroids", 0),
                exposureMs = obj.optInt("exposure_us", 0).toDouble() / 1000.0,
                postProcessMs = obj.optDouble("post_process_ms", 0.0),
                extractMs = obj.optDouble("extract_ms", 0.0),
                solveMs = obj.optDouble("solve_ms", 0.0),
                solveRateHz = obj.optDouble("solve_rate_hz", 0.0),
                offsetRa = if (obj.isNull("offset_ra")) null else obj.getDouble("offset_ra"),
                offsetDec = if (obj.isNull("offset_dec")) null else obj.getDouble("offset_dec"),
                offsetAz = if (obj.isNull("offset_az")) null else obj.getDouble("offset_az"),
                offsetAlt = if (obj.isNull("offset_alt")) null else obj.getDouble("offset_alt"),
                offsetAngle = if (obj.isNull("offset_angle")) null else obj.getDouble("offset_angle"),
                hfr = obj.optDouble("hfr", 0.0),
                targetRa = if (obj.isNull("target_ra")) null else obj.getDouble("target_ra"),
                targetDec = if (obj.isNull("target_dec")) null else obj.getDouble("target_dec")
            )

            // Removed syncing tuning sliders here to allow them to reflect user-selected values
        } catch (_: Exception) {
            // Ignore parse errors, engine might be returning empty string
        }
    }

    private var frameBuffer: ByteBuffer? = null
    private var backBitmap: android.graphics.Bitmap? = null
    private var frontBitmap: android.graphics.Bitmap? = null
    private val frameHeaderBytes = ByteArray(8)

    private fun fetchLatestFrame() {
        if (frameBuffer == null) {
            frameBuffer = ByteBuffer.allocateDirect(100 * 1024 * 1024)
        }
        val buffer = frameBuffer ?: return
        buffer.clear()

        if (!OliveEngine.getLatestFrameInto(buffer)) return

        buffer.position(0)
        buffer.get(frameHeaderBytes)

        val bytes = frameHeaderBytes

        val width = ((bytes[0].toInt() and 0xFF) or
                ((bytes[1].toInt() and 0xFF) shl 8) or
                ((bytes[2].toInt() and 0xFF) shl 16) or
                ((bytes[3].toInt() and 0xFF) shl 24))

        val height = ((bytes[4].toInt() and 0xFF) or
                ((bytes[5].toInt() and 0xFF) shl 8) or
                ((bytes[6].toInt() and 0xFF) shl 16) or
                ((bytes[7].toInt() and 0xFF) shl 24))

        val pixelCount = width * height
        if (pixelCount !in 1..25000000) return

        val tmp = backBitmap
        backBitmap = frontBitmap
        frontBitmap = tmp

        var bitmap = backBitmap
        if (bitmap == null || bitmap.width != width || bitmap.height != height) {
            bitmap = createBitmap(width, height)
            backBitmap = bitmap
            frontBitmap = createBitmap(width, height)
        }
        bitmap.copyPixelsFromBuffer(buffer)
        val imageBitmapToPublish = bitmap.asImageBitmap()
        viewModelScope.launch(Dispatchers.Main) {
            latestFrame = imageBitmapToPublish
        }
    }

    private fun startPolling() {
        statsJob?.cancel()
        frameJob?.cancel()

        statsJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    updateStats()
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Error in updateStats: ${e.message}")
                }
                delay(200) // 5Hz stats
            }
        }

        frameJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    fetchLatestFrame()
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Error in fetchLatestFrame: ${e.message}")
                }
                delay(100) // 10Hz frames
            }
        }
    }

    private fun stopPolling() {
        statsJob?.cancel()
        frameJob?.cancel()
    }

    fun executeStartEngine(
        cameraId: String,
        lat: Double = INVALID_COORD,
        lon: Double = INVALID_COORD,
        timeMs: Long = 0L
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            OliveEngine.startNativeCameraEngine(
                settingsManager.imuIntegration,
                settingsManager.extractor,
                settingsManager.sensorMode,
                cameraId,
                settingsManager.downsampling,
                lat,
                lon,
                timeMs,
                settingsManager
            )
            OliveEngine.setEngineSigma(currentSigma)

            launch(Dispatchers.Main) {
                engineMode = EngineMode.NATIVE

                val gainRange = OliveEngine.getNativeCameraGainRange()
                if (gainRange != null && gainRange.size >= 3) {
                    minAnalogGain = gainRange[0]
                    maxAnalogGain = gainRange[1]
                    val defaultGain = gainRange[2]
                    setGain(defaultGain)

                    settingsManager.zoomRatio = 1f
                    OliveEngine.setNativeCameraZoomRatio(1f)

                    setFocus(0.0f)
                    val defExp = OliveEngine.getNativeCameraExposure()
                    setExposure(defExp.toFloat())
                    setNoiseReduction(0)
                    val hpModes = OliveEngine.getNativeCameraSupportedHotPixelModes()
                    supportedHotPixelModes = hpModes.toList()
                    updateHotPixelMode(0)
                    updateUvcStacking(1)
                }
                startPolling()
            }
        }
    }

    fun startUvcEngine(fd: Int, rawDescriptors: ByteArray, lat: Double = 200.0, lon: Double = 200.0, timeMs: Long = 0L) {
        viewModelScope.launch(Dispatchers.IO) {
            if (isEngineRunning) {
                OliveEngine.stopEngine()
            }

            OliveEngine.startEngine(
                fd,
                rawDescriptors,
                settingsManager.imuIntegration,
                settingsManager.uvcFps,
                settingsManager.extractor,
                settingsManager.downsampling,
                lat,
                lon,
                timeMs,
                settingsManager
            )
            OliveEngine.setEngineSigma(currentSigma)

            launch(Dispatchers.Main) {
                engineMode = EngineMode.UVC

                var brightRange = OliveEngine.getNativeCameraBrightnessRange()
                var gainRange = OliveEngine.getNativeCameraGainRange()
                var retries = 0

                // Wait up to 2 seconds for UVC ranges to be populated
                while ((brightRange == null || brightRange.size < 3 || brightRange[1] == 100) &&
                    (gainRange == null || gainRange.size < 3 || gainRange[1] == 100f) &&
                    retries < 20
                ) {
                    delay(100)
                    brightRange = OliveEngine.getNativeCameraBrightnessRange()
                    gainRange = OliveEngine.getNativeCameraGainRange()
                    retries++
                }

                if (brightRange != null && brightRange.size >= 3) {
                    uvcBrightnessMin = brightRange[0]
                    uvcBrightnessMax = brightRange[1]
                    val defaultBrightness = brightRange[2]
                    currentUvcBrightness = defaultBrightness
                    OliveEngine.setNativeCameraBrightness(defaultBrightness)
                }

                if (gainRange != null && gainRange.size >= 3) {
                    minAnalogGain = gainRange[0]
                    maxAnalogGain = gainRange[1]
                    val realGain = OliveEngine.getNativeCameraGain()
                    currentGain = if (!realGain.isNaN() && realGain > 0) {
                        realGain
                    } else {
                        gainRange[2]
                    }
                }

                updateUvcStacking(1)
                startPolling()
            }
        }
    }

    fun setExposure(us: Float) {
        currentExposure = us
        viewModelScope.launch(Dispatchers.IO) {
            OliveEngine.setNativeCameraExposure(us.toInt())
        }
    }

    fun updateStarSensitivity(s: Float) {
        starSensitivity = s
        val sigma = currentSigma
        viewModelScope.launch(Dispatchers.IO) {
            OliveEngine.setEngineSigma(sigma)
        }
    }

    fun fetchRealtimeSettings() {
        if (engineMode == EngineMode.NATIVE || engineMode == EngineMode.UVC) {
            val gain = OliveEngine.getNativeCameraGain()
            if (!gain.isNaN() && gain > 0) {
                currentGain = gain
            }
            val brightness = OliveEngine.getNativeCameraBrightness()
            if (brightness != 0) { // Assuming 0 is a default/uninitialized, or we can just set it
                currentUvcBrightness = brightness
            }
            if (engineMode == EngineMode.NATIVE) {
                val focus = OliveEngine.getNativeCameraFocus()
                if (!focus.isNaN() && focus >= 0f) {
                    currentFocus = focus
                }
                val zoom = OliveEngine.getNativeCameraZoomRatio()
                if (!zoom.isNaN() && zoom > 0f) {
                    settingsManager.zoomRatio = zoom
                }
                hotPixelMode = OliveEngine.getNativeCameraHotPixelMode()
            }
        }
    }

    fun setGain(gain: Float) {
        currentGain = gain
        viewModelScope.launch(Dispatchers.IO) {
            OliveEngine.setNativeCameraGain(gain)
        }
    }

    fun setFocus(focus: Float) {
        currentFocus = focus
        viewModelScope.launch(Dispatchers.IO) {
            OliveEngine.setNativeCameraFocus(focus)
        }
    }

    fun setNoiseReduction(mode: Int) {
        noiseReductionMode = mode
        viewModelScope.launch(Dispatchers.IO) {
            OliveEngine.setNativeCameraNoiseReduction(mode)
        }
    }

    fun updateHotPixelMode(mode: Int) {
        hotPixelMode = mode
        viewModelScope.launch(Dispatchers.IO) {
            OliveEngine.setNativeCameraHotPixelMode(mode)
        }
    }

    fun setUvcBrightness(brightness: Int) {
        currentUvcBrightness = brightness
        viewModelScope.launch(Dispatchers.IO) {
            OliveEngine.setNativeCameraBrightness(brightness)
        }
    }

    fun setEnginePaused(paused: Boolean) {
        OliveEngine.setEnginePaused(paused)
    }

    fun updateUvcStacking(frames: Int) {
        uvcStacking = frames.coerceIn(1, 10)
        OliveEngine.updateUvcStacking(uvcStacking)
    }

    fun stopEngineSync() {
        if (isEngineRunning) {
            OliveEngine.stopEngine()
            viewModelScope.launch(Dispatchers.Main) {
                engineMode = EngineMode.STOPPED
                stopPolling()
            }
        }
    }

    companion object {
        const val INVALID_COORD = 200.0
    }
}
