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
import androidx.core.graphics.createBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainViewModel(application: Application) : AndroidViewModel(application) {
    var uvcWrapper: UvcCameraWrapper? = null

    enum class EngineMode { STOPPED, UVC, NATIVE }

    var engineMode by mutableStateOf(EngineMode.STOPPED)
        private set

    val isEngineRunning: Boolean
        get() = engineMode != EngineMode.STOPPED

    var engineStats by mutableStateOf(EngineStats())
        private set

    enum class LocationRefreshStatus { IDLE, REFRESHING, SUCCESS, FAILURE }

    var currentLat by mutableStateOf<Double?>(null)
    var currentLon by mutableStateOf<Double?>(null)
    val hasValidLocation: Boolean
        get() = isValidCoordinate(currentLat, currentLon)
    var showLocationDialog by mutableStateOf(false)
    var locationRefreshStatus by mutableStateOf(LocationRefreshStatus.IDLE)

    val settingsManager = SettingsManager(application)
    val catalogDb = CatalogDatabaseHelper(application)

    // Catalog State
    var catalogSearchQuery by mutableStateOf("")

    private val _catalogSelectedCategory = mutableStateOf(settingsManager.catalogSelectedCategory)
    var catalogSelectedCategory: String
        get() = _catalogSelectedCategory.value
        set(value) {
            _catalogSelectedCategory.value = value
            settingsManager.catalogSelectedCategory = value
        }

    private val _catalogSortBy = mutableStateOf(settingsManager.catalogSortBy)
    var catalogSortBy: String
        get() = _catalogSortBy.value
        set(value) {
            _catalogSortBy.value = value
            settingsManager.catalogSortBy = value
        }

    private val _catalogMagLimit = mutableStateOf(settingsManager.catalogMagLimit)
    var catalogMagLimit: Double?
        get() = _catalogMagLimit.value
        set(value) {
            _catalogMagLimit.value = value
            settingsManager.catalogMagLimit = value
        }

    private val _catalogTypeFilters = mutableStateOf(settingsManager.catalogTypeFilters)
    var catalogTypeFilters: Set<String>
        get() = _catalogTypeFilters.value
        set(value) {
            _catalogTypeFilters.value = value
            settingsManager.catalogTypeFilters = value
        }

    private val _catalogVisibilityFilter = mutableStateOf(settingsManager.catalogVisibilityFilter)
    var catalogVisibilityFilter: String
        get() = _catalogVisibilityFilter.value
        set(value) {
            _catalogVisibilityFilter.value = value
            settingsManager.catalogVisibilityFilter = value
        }

    private val _landscapeKeyboardOnRight = mutableStateOf(settingsManager.landscapeKeyboardOnRight)
    var landscapeKeyboardOnRight: Boolean
        get() = _landscapeKeyboardOnRight.value
        set(value) {
            _landscapeKeyboardOnRight.value = value
            settingsManager.landscapeKeyboardOnRight = value
        }

    // Tuning state
    var currentExposure by mutableFloatStateOf(33000f)
    var currentGain by mutableFloatStateOf(50f)
    var minAnalogGain by mutableFloatStateOf(100f)
    var maxAnalogGain by mutableFloatStateOf(3200f)
    var currentFocus by mutableFloatStateOf(0.0f)
    var currentZoom by mutableFloatStateOf(1.0f)
    var noiseReductionMode by mutableIntStateOf(0)
    var hotPixelMode by mutableIntStateOf(0)
    var supportedHotPixelModes by mutableStateOf<List<Int>>(emptyList())

    // UVC controls
    var uvcBrightnessMin by mutableIntStateOf(0)
    var uvcBrightnessMax by mutableIntStateOf(100)
    var currentUvcBrightness by mutableIntStateOf(50)
    var latestFrame by mutableStateOf<ImageBitmap?>(null)
    var isCameraStuck by mutableStateOf(false)

    @Volatile
    private var lastFrameReceivedRealtime = 0L

    private val _isLowPowerMode = mutableStateOf(false)
    var isLowPowerMode: Boolean
        get() = _isLowPowerMode.value
        set(value) {
            val wasLowPower = _isLowPowerMode.value
            if (wasLowPower && !value) {
                // Reset watchdog timestamp before exiting low-power state
                lastFrameReceivedRealtime = android.os.SystemClock.elapsedRealtime()
            }
            _isLowPowerMode.value = value
        }

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
                centerRa = if (obj.isNull("center_ra")) null else obj.getDouble("center_ra"),
                centerDec = if (obj.isNull("center_dec")) null else obj.getDouble("center_dec"),
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
                targetDec = if (obj.isNull("target_dec")) null else obj.getDouble("target_dec"),
                isImuEstimate = obj.optBoolean("is_imu_estimate", false),
                isMirrored = if (obj.isNull("is_mirrored")) null else obj.getBoolean("is_mirrored"),
                solvedCrop = if (obj.isNull("solved_crop")) null else obj.getString("solved_crop"),
                targetPixelX = if (obj.isNull("target_pixel_x")) null else obj.getDouble("target_pixel_x"),
                targetPixelY = if (obj.isNull("target_pixel_y")) null else obj.getDouble("target_pixel_y"),
                distortion = if (obj.isNull("distortion")) null else obj.getDouble("distortion")
            )

            if (settingsManager.cameraSettingsMode == "Auto" && !obj.isNull("current_sigma")) {
                val currentSigmaVal = obj.getDouble("current_sigma").toFloat()
                starSensitivity = 100f * (5.0f - currentSigmaVal) / 3.0f
            }
        } catch (_: Exception) {
            // Ignore parse errors, engine might be returning empty string
        }
    }

    private var frameBuffer: ByteBuffer? = null

    private var lastFrameTimestamp = 0L

    private fun fetchLatestFrame() {
        var targetSize = 52 * 1024 * 1024 // 52MB safe default for internal camera and up to 13MP
        if (engineMode == EngineMode.UVC) {
            val pw = uvcWrapper?.previewWidth ?: 640
            val ph = uvcWrapper?.previewHeight ?: 480
            targetSize = pw * ph * 4 + 1024
        }
        if (frameBuffer == null || frameBuffer!!.capacity() < targetSize) {
            frameBuffer = ByteBuffer.allocateDirect(targetSize).order(ByteOrder.LITTLE_ENDIAN)
        }
        val buffer = frameBuffer ?: return

        val ts = OliveEngine.getLatestFrameInto(buffer, lastFrameTimestamp)
        if (ts <= 0L || ts <= lastFrameTimestamp) return

        buffer.rewind()
        val width = buffer.int
        val height = buffer.int


        val pixelCount = width * height
        if (pixelCount !in 1..25000000) return

        val bitmap = createBitmap(width, height)
        bitmap.copyPixelsFromBuffer(buffer)
        val imageBitmapToPublish = bitmap.asImageBitmap()
        viewModelScope.launch(Dispatchers.Main) {
            if (isActive) {
                latestFrame = imageBitmapToPublish
                lastFrameTimestamp = ts
                lastFrameReceivedRealtime = android.os.SystemClock.elapsedRealtime()
                isCameraStuck = false // Reset stuck state if frames resume
            }
        }
    }

    fun dismissCameraStuckDialog() {
        isCameraStuck = false
        // Offset the timer by 10s so the 5s timeout takes 15s to trigger again
        lastFrameReceivedRealtime = android.os.SystemClock.elapsedRealtime() + 10000L
    }

    private fun startPolling() {
        lastFrameReceivedRealtime = android.os.SystemClock.elapsedRealtime()

        statsJob?.cancel()
        frameJob?.cancel()

        statsJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    updateStats()
                    fetchRealtimeSettings()
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Error in updateStats: ${e.message}")
                }
                delay(SCREEN_UPDATE_INTERVAL_MS)
            }
        }

        frameJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (!isLowPowerMode) {
                    try {
                        fetchLatestFrame()
                    } catch (e: Exception) {
                        Log.e("MainViewModel", "Error in fetchLatestFrame: ${e.message}")
                    }

                    if (isEngineRunning) {
                        val timeSinceLastFrame =
                            android.os.SystemClock.elapsedRealtime() - lastFrameReceivedRealtime
                        if (timeSinceLastFrame > CAMERA_STUCK_TIMEOUT_MS) {
                            isCameraStuck = true
                        }
                    }
                }
                delay(SCREEN_UPDATE_INTERVAL_MS)
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
        latestFrame = null
        lastFrameTimestamp = 0L

        // Reset watchdog state so stale timestamps from a previous session
        // don't immediately trigger a false-positive UI timeout.
        isCameraStuck = false
        lastFrameReceivedRealtime = android.os.SystemClock.elapsedRealtime()
        currentLat = lat
        currentLon = lon
        val downsampling = if (cameraId == "demo") 0 else settingsManager.downsampling

        val motionSensorMode = if (cameraId == "demo") 0 else settingsManager.motionSensorMode
        val isAutoSettings =
            if (cameraId == "demo") false else settingsManager.cameraSettingsMode == "Auto"

        viewModelScope.launch(Dispatchers.IO) {
            val forcedSensorMode = if (isAutoSettings) "Processed" else settingsManager.sensorMode
            val forcedDownsampling = if (isAutoSettings) 2 else downsampling
            val forcedExtractor = if (isAutoSettings) "Olive" else settingsManager.extractor
            val forcedCameraId = if (isAutoSettings && cameraId != "demo") "0" else cameraId

            OliveEngine.startNativeCameraEngine(
                motionSensorMode,
                forcedExtractor,
                forcedSensorMode,
                forcedCameraId,
                forcedDownsampling,
                lat,
                lon,
                timeMs,
                settingsManager,
                isAutoSettings
            )

            launch(Dispatchers.Main) {
                engineMode = EngineMode.NATIVE

                val gainRange = OliveEngine.getNativeCameraGainRange()
                val (minG, maxG, defaultG) = if (gainRange != null && gainRange.size >= 3) {
                    Triple(gainRange[0], gainRange[1], gainRange[2])
                } else {
                    Triple(100f, 3200f, 3200f)
                }
                minAnalogGain = minG
                maxAnalogGain = maxG

                if (isAutoSettings) {
                    setZoom(1.0f)
                    setFocus(0.0f)
                    updateUvcStacking(1)
                } else {
                    var savedGain = settingsManager.manualGain
                    if (savedGain < 0f) savedGain = defaultG
                    savedGain = savedGain.coerceIn(minAnalogGain, maxAnalogGain)
                    setGain(savedGain)

                    setZoom(settingsManager.manualZoomRatio)
                    setFocus(settingsManager.manualFocus)
                    setNoiseReduction(settingsManager.manualNoiseReduction)
                    updateHotPixelMode(settingsManager.manualHotPixelMode)
                    setExposure(settingsManager.manualExposureUs)

                    updateStarSensitivity(settingsManager.manualStarSensitivity)
                    updateUvcStacking(settingsManager.manualUvcStacking)
                }

                supportedHotPixelModes =
                    OliveEngine.getNativeCameraSupportedHotPixelModes().toList()
                if (!isAutoSettings) {
                    OliveEngine.setEngineSigma(currentSigma)
                }
                OliveEngine.setEngineStrictMatch(false) // Phone camera has wide FOV, needs generous thresholds
                startPolling()
            }
        }
    }

    fun startUvcEngine(
        lat: Double = 200.0,
        lon: Double = 200.0,
        timeMs: Long = 0L
    ) {
        latestFrame = null
        lastFrameTimestamp = 0L

        // Reset watchdog state so stale timestamps from a previous session
        // don't immediately trigger a false-positive UI timeout.
        isCameraStuck = false
        lastFrameReceivedRealtime = android.os.SystemClock.elapsedRealtime()
        currentLat = lat
        currentLon = lon
        viewModelScope.launch(Dispatchers.IO) {
            if (isEngineRunning) {
                OliveEngine.stopEngine()
            }

            val isAutoSettings = settingsManager.cameraSettingsMode == "Auto"
            OliveEngine.startExternalCameraEngine(
                uvcWrapper?.previewWidth ?: 640,
                uvcWrapper?.previewHeight ?: 480,
                settingsManager.motionSensorMode,
                settingsManager.extractor,
                settingsManager.downsampling,
                lat,
                lon,
                timeMs,
                settingsManager,
                isAutoSettings,
                settingsManager.uvcFps,
                uvcWrapper
            )

            launch(Dispatchers.Main) {
                engineMode = EngineMode.UVC

                uvcBrightnessMin = 0
                uvcBrightnessMax = 100
                minAnalogGain = 0f
                maxAnalogGain = 100f

                uvcWrapper?.startZeroCopyStream()
                delay(300) // Wait for hardware to initialize limits

                if (isAutoSettings) {
                    val fps = settingsManager.uvcFps.coerceAtLeast(1)
                    setExposure((1000000 / fps).toFloat())

                    updateStarSensitivity(50f)
                    updateUvcStacking(1)

                    // Auto mode: Kotlin does NOT touch brightness or gain at startup.
                    // Rust autonomously calibrates baseline brightness, sets gain to 100f,
                    // and locks calibration. fetchRealtimeSettings() automatically syncs to UI.
                } else {
                    setExposure(settingsManager.manualExposureUs)
                    updateStarSensitivity(settingsManager.manualStarSensitivity)
                    updateUvcStacking(settingsManager.manualUvcStacking)

                    // Manual mode: preserve hardware brightness, apply manual gain
                    val hwBrightness = uvcWrapper?.getHardwareBrightness()
                    if (hwBrightness != null && hwBrightness in 0..100) {
                        currentUvcBrightness = hwBrightness
                    }

                    var savedGain = settingsManager.manualGain
                    if (savedGain < 0f) savedGain = 50f
                    savedGain = savedGain.coerceIn(0f, 100f)
                    setGain(savedGain)
                }

                OliveEngine.setEngineSigma(currentSigma)
                OliveEngine.setEngineStrictMatch(true) // SC2210 has tight FOV, use strict thresholds

                startPolling()
            }
        }
    }

    fun setExposure(us: Float) {
        currentExposure = us
        if (settingsManager.cameraSettingsMode == "Manual") {
            settingsManager.manualExposureUs = us
        }
        viewModelScope.launch(Dispatchers.IO) {
            OliveEngine.setNativeCameraExposure(us.toInt())
        }
    }

    fun updateStarSensitivity(s: Float) {
        starSensitivity = s
        if (settingsManager.cameraSettingsMode == "Manual") {
            settingsManager.manualStarSensitivity = s
        }
        val sigma = currentSigma
        viewModelScope.launch(Dispatchers.IO) {
            OliveEngine.setEngineSigma(sigma)
        }
    }

    fun fetchRealtimeSettings() {
        if (engineMode == EngineMode.NATIVE) {
            if (settingsManager.cameraSettingsMode == "Auto") {
                val gain = OliveEngine.getNativeCameraGain()
                if (!gain.isNaN() && gain > 0) {
                    currentGain = gain
                }
                val exp = OliveEngine.getNativeCameraExposure()
                if (exp > 0) {
                    currentExposure = exp.toFloat()
                }
                noiseReductionMode = OliveEngine.getNativeCameraNoiseReduction()
            }
            hotPixelMode = OliveEngine.getNativeCameraHotPixelMode()
        } else if (engineMode == EngineMode.UVC) {
            if (settingsManager.cameraSettingsMode == "Auto") {
                val hwGain = uvcWrapper?.getHardwareGain()
                if (hwGain != null && hwGain in 0..100) {
                    currentGain = hwGain.toFloat()
                }
            }
            val hwBrightness = uvcWrapper?.getHardwareBrightness()
            if (hwBrightness != null && hwBrightness in 0..100) {
                currentUvcBrightness = hwBrightness
            }
        }
    }

    fun setZoom(zoom: Float) {
        currentZoom = zoom
        if (settingsManager.cameraSettingsMode == "Manual") {
            settingsManager.manualZoomRatio = zoom
        }
        viewModelScope.launch(Dispatchers.IO) {
            OliveEngine.setNativeCameraZoomRatio(zoom)
        }
    }

    fun setGain(gain: Float) {
        currentGain = gain
        if (settingsManager.cameraSettingsMode == "Manual") {
            settingsManager.manualGain = gain
        }
        viewModelScope.launch(Dispatchers.IO) {
            OliveEngine.setNativeCameraGain(gain)
        }
    }

    fun setFocus(focus: Float) {
        currentFocus = focus
        if (settingsManager.cameraSettingsMode == "Manual") {
            settingsManager.manualFocus = focus
        }
        viewModelScope.launch(Dispatchers.IO) {
            OliveEngine.setNativeCameraFocus(focus)
        }
    }

    fun setNoiseReduction(mode: Int) {
        noiseReductionMode = mode
        if (settingsManager.cameraSettingsMode == "Manual") {
            settingsManager.manualNoiseReduction = mode
        }
        viewModelScope.launch(Dispatchers.IO) {
            OliveEngine.setNativeCameraNoiseReduction(mode)
        }
    }

    fun updateHotPixelMode(mode: Int) {
        hotPixelMode = mode
        if (settingsManager.cameraSettingsMode == "Manual") {
            settingsManager.manualHotPixelMode = mode
        }
        viewModelScope.launch(Dispatchers.IO) {
            OliveEngine.setNativeCameraHotPixelMode(mode)
        }
    }

    fun setUvcBrightness(brightness: Int) {
        currentUvcBrightness = brightness
        if (settingsManager.cameraSettingsMode == "Manual") {
            settingsManager.manualUvcBrightness = brightness
        }
        viewModelScope.launch(Dispatchers.IO) {
            OliveEngine.setNativeCameraBrightness(brightness)
        }
    }

    fun setEnginePaused(paused: Boolean) {
        OliveEngine.setEnginePaused(paused)
    }

    fun updateUvcStacking(frames: Int) {
        uvcStacking = frames.coerceIn(1, 10)
        if (settingsManager.cameraSettingsMode == "Manual") {
            settingsManager.manualUvcStacking = uvcStacking
        }
        OliveEngine.updateUvcStacking(uvcStacking)
    }

    fun setGoToTarget(ra: Double, dec: Double, epoch: Double = 2000.0) {
        if (isEngineRunning) {
            OliveEngine.setGoToTarget(ra, dec, epoch)
            Log.i("MainViewModel", "GoTo Target set in JNI: RA=$ra, Dec=$dec, Epoch=$epoch")
        } else {
            Log.w("MainViewModel", "Engine not running, cannot set GoTo Target: RA=$ra, Dec=$dec")
        }
    }

    fun clearGoToTarget() {
        if (isEngineRunning) {
            OliveEngine.clearGoToTarget()
            Log.i("MainViewModel", "GoTo Target cleared in JNI")
        } else {
            Log.w("MainViewModel", "Engine not running, cannot clear GoTo Target")
        }
    }

    fun syncTelescope(ra: Double? = null, dec: Double? = null) {
        if (isEngineRunning) {
            if (ra != null && dec != null) {
                OliveEngine.syncTelescopeToTarget(ra, dec)
                Log.i(
                    "MainViewModel",
                    "Sync Telescope to target requested in JNI: RA=$ra, Dec=$dec"
                )
            } else {
                OliveEngine.syncTelescope()
                Log.i("MainViewModel", "Sync Telescope requested in JNI")
            }
        } else {
            Log.w("MainViewModel", "Engine not running, cannot sync telescope")
        }
    }

    fun setAlignTargetPixel(x: Double, y: Double) {
        if (isEngineRunning) {
            OliveEngine.setAlignTargetPixel(x, y)
            Log.i("MainViewModel", "Align Target set in JNI: x=$x, y=$y")
        } else {
            Log.w("MainViewModel", "Engine not running, cannot set align target: x=$x, y=$y")
        }
    }

    fun getPlanets(): List<CatalogObject> {
        val lat = currentLat ?: return emptyList()
        val lon = currentLon ?: return emptyList()
        if (!isValidCoordinate(lat, lon)) return emptyList()

        val bspPath = getApplication<Application>().getDatabasePath("de421.bsp").absolutePath
        val timeMs = System.currentTimeMillis()

        val json = OliveEngine.getPlanets(bspPath, timeMs, lat, lon)
        if (json.isBlank() || json == "[]") return emptyList()

        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<CatalogObject>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    CatalogObject(
                        id = obj.optInt("id"),
                        catalogLabel = obj.optString("catalogLabel"),
                        catalogEntry = obj.optString("catalogEntry"),
                        ra = obj.optDouble("ra"),
                        dec = obj.optDouble("dec"),
                        constellation = if (obj.has("constellation") && !obj.isNull("constellation")) obj.optString(
                            "constellation"
                        ) else null,
                        objectType = obj.optString("objectType"),
                        broadCategory = obj.optString("broadCategory"),
                        magnitude = if (obj.has("magnitude") && !obj.isNull("magnitude")) obj.optDouble(
                            "magnitude"
                        ) else null,
                        surfaceBrightness = if (obj.has("surfaceBrightness") && !obj.isNull("surfaceBrightness")) obj.optDouble(
                            "surfaceBrightness"
                        ) else null,
                        angularSize = if (obj.has("angularSize") && !obj.isNull("angularSize")) obj.optString(
                            "angularSize"
                        ) else null,
                        commonName = if (obj.has("commonName") && !obj.isNull("commonName")) obj.optString(
                            "commonName"
                        ) else null,
                        description = if (obj.has("description") && !obj.isNull("description")) obj.optString(
                            "description"
                        ) else null,
                        copyright = if (obj.has("copyright") && !obj.isNull("copyright")) obj.optString(
                            "copyright"
                        ) else null
                    )
                )
            }
            list
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to parse planets: ${e.message}")
            emptyList()
        }
    }

    fun getAsteroids(): List<CatalogObject> {
        val lat = currentLat ?: return emptyList()
        val lon = currentLon ?: return emptyList()
        if (!isValidCoordinate(lat, lon)) return emptyList()

        val bspPath = getApplication<Application>().getDatabasePath("de421.bsp").absolutePath
        val timeMs = System.currentTimeMillis()
        val asteroidsJson = catalogDb.getAsteroidsJson()

        val json = OliveEngine.getAsteroids(bspPath, timeMs, lat, lon, asteroidsJson)
        if (json.isBlank() || json == "[]") return emptyList()

        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<CatalogObject>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    CatalogObject(
                        id = obj.optInt("id"),
                        catalogLabel = obj.optString("catalogLabel"),
                        catalogEntry = obj.optString("catalogEntry"),
                        ra = obj.optDouble("ra"),
                        dec = obj.optDouble("dec"),
                        constellation = if (obj.has("constellation") && !obj.isNull("constellation")) obj.optString(
                            "constellation"
                        ) else null,
                        objectType = obj.optString("objectType"),
                        broadCategory = obj.optString("broadCategory"),
                        magnitude = if (obj.has("magnitude") && !obj.isNull("magnitude")) obj.optDouble(
                            "magnitude"
                        ) else null,
                        surfaceBrightness = if (obj.has("surfaceBrightness") && !obj.isNull("surfaceBrightness")) obj.optDouble(
                            "surfaceBrightness"
                        ) else null,
                        angularSize = if (obj.has("angularSize") && !obj.isNull("angularSize")) obj.optString(
                            "angularSize"
                        ) else null,
                        commonName = if (obj.has("commonName") && !obj.isNull("commonName")) obj.optString(
                            "commonName"
                        ) else null,
                        description = if (obj.has("description") && !obj.isNull("description")) obj.optString(
                            "description"
                        ) else null,
                        copyright = if (obj.has("copyright") && !obj.isNull("copyright")) obj.optString(
                            "copyright"
                        ) else null
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getComets(): List<CatalogObject> {
        val lat = currentLat ?: return emptyList()
        val lon = currentLon ?: return emptyList()
        if (!isValidCoordinate(lat, lon)) return emptyList()

        val bspPath = getApplication<Application>().getDatabasePath("de421.bsp").absolutePath
        val timeMs = System.currentTimeMillis()
        val cometsJson = catalogDb.getCometsJson()

        val json = OliveEngine.getComets(bspPath, timeMs, lat, lon, cometsJson)
        if (json.isBlank() || json == "[]") return emptyList()

        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<CatalogObject>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    CatalogObject(
                        id = obj.optInt("id"),
                        catalogLabel = obj.optString("catalogLabel"),
                        catalogEntry = obj.optString("catalogEntry"),
                        ra = obj.optDouble("ra"),
                        dec = obj.optDouble("dec"),
                        constellation = if (obj.has("constellation") && !obj.isNull("constellation")) obj.optString(
                            "constellation"
                        ) else null,
                        objectType = obj.optString("objectType"),
                        broadCategory = obj.optString("broadCategory"),
                        magnitude = if (obj.has("magnitude") && !obj.isNull("magnitude")) obj.optDouble(
                            "magnitude"
                        ) else null,
                        surfaceBrightness = if (obj.has("surfaceBrightness") && !obj.isNull("surfaceBrightness")) obj.optDouble(
                            "surfaceBrightness"
                        ) else null,
                        angularSize = if (obj.has("angularSize") && !obj.isNull("angularSize")) obj.optString(
                            "angularSize"
                        ) else null,
                        commonName = if (obj.has("commonName") && !obj.isNull("commonName")) obj.optString(
                            "commonName"
                        ) else null,
                        description = if (obj.has("description") && !obj.isNull("description")) obj.optString(
                            "description"
                        ) else null,
                        copyright = if (obj.has("copyright") && !obj.isNull("copyright")) obj.optString(
                            "copyright"
                        ) else null
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun refreshComets(onComplete: () -> Unit, onNetworkError: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = catalogDb.refreshComets()
            launch(Dispatchers.Main) {
                if (success) {
                    onComplete()
                } else {
                    onNetworkError()
                }
            }
        }
    }

    fun stopEngineSync() {
        OliveEngine.stopEngine()

        engineMode = EngineMode.STOPPED
        isLowPowerMode = false
        stopPolling()
        frameBuffer = null

        uvcWrapper?.release()
        uvcWrapper = null
    }

    override fun onCleared() {
        super.onCleared()
        catalogDb.closeDatabase()
    }

    companion object {
        const val INVALID_COORD = 200.0
        const val CAMERA_STUCK_TIMEOUT_MS = 5000L
        const val SCREEN_UPDATE_RATE_HZ = 10
        const val SCREEN_UPDATE_INTERVAL_MS = 1000L / SCREEN_UPDATE_RATE_HZ

        fun isValidCoordinate(lat: Double?, lon: Double?): Boolean {
            if (lat == null || lon == null) return false
            return lat in -90.0..90.0 && lon in -180.0..180.0
        }

        val DEFAULT_CATALOG_TYPE_FILTERS = setOf(
            "Asterism", "Asteroid", "Cluster + Nebula", "Comet", "Dark Nebula",
            "Galaxy", "Globular Cluster", "Multiple Star", "Nebula", "Open Cluster",
            "Planet", "Planetary Nebula", "Star"
        )
    }
}
