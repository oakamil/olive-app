package com.oakamil.olive

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

open class MainViewModel(
    val storage: KeyValueStorage,
    val capabilities: PlatformCapabilities,
    var engine: EngineBridge,
    val catalogDb: CatalogDatabase
) : ViewModel() {
    var uvcWrapper: UvcCameraHandler? = null
    var availableUvcFormats by mutableStateOf(UvcFormat.FALLBACK_FORMATS)
    private var lastLoggedMirrored: Boolean? = null

    private val timeMark = TimeSource.Monotonic.markNow()
    fun getElapsedRealtimeMs(): Long = timeMark.elapsedNow().inWholeMilliseconds

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
    var showCatalog by mutableStateOf(false)
    var locationRefreshStatus by mutableStateOf(LocationRefreshStatus.IDLE)

    val settingsManager = SettingsManager(storage, capabilities.defaultFocus)

    private val _currentTheme = mutableStateOf(settingsManager.appTheme)
    var currentTheme: String
        get() = _currentTheme.value
        set(value) {
            _currentTheme.value = value
            settingsManager.appTheme = value
        }

    private val _guidanceMode = mutableStateOf(settingsManager.guidanceMode)
    var guidanceMode: String
        get() = _guidanceMode.value
        set(value) {
            _guidanceMode.value = value
            settingsManager.guidanceMode = value
        }

    private val _showMapPosition = mutableStateOf(settingsManager.showMapPosition)
    var showMapPosition: Boolean
        get() = _showMapPosition.value
        set(value) {
            _showMapPosition.value = value
            settingsManager.showMapPosition = value
        }

    private val _showStats = mutableStateOf(settingsManager.showStats)
    var showStats: Boolean
        get() = _showStats.value
        set(value) {
            _showStats.value = value
            settingsManager.showStats = value
        }

    private val _reverseLayout = mutableStateOf(settingsManager.reverseLayout)
    var reverseLayout: Boolean
        get() = _reverseLayout.value
        set(value) {
            if (_reverseLayout.value != value) {
                _reverseLayout.value = value
                settingsManager.reverseLayout = value
            }
        }

    var skyMapCenterTarget by mutableStateOf<Pair<Double, Double>?>(null)

    private var fovSaveJob: Job? = null
    private val _skyMapFov = mutableDoubleStateOf(settingsManager.skyMapFov)
    var skyMapFov: Double
        get() = _skyMapFov.doubleValue
        set(value) {
            _skyMapFov.doubleValue = value
            fovSaveJob?.cancel()
            fovSaveJob = viewModelScope.launch(Dispatchers.IO) {
                delay(500L)
                settingsManager.skyMapFov = value
            }
        }

    var skyMapCenterAz by mutableStateOf<Double?>(null)
    var skyMapCenterAlt by mutableStateOf<Double?>(null)
    var skyMapCameraLocked by mutableStateOf(true)
    var cometsRefreshVersion by mutableIntStateOf(0)
        private set

    init {
        settingsManager.savedLocation?.let { (lat, lon) ->
            currentLat = lat
            currentLon = lon
        }
    }

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

    // Camera Tuning Parameters
    var currentExposure by mutableFloatStateOf(33000f)
    var currentGain by mutableFloatStateOf(50f)
    var minAnalogGain by mutableFloatStateOf(100f)
    var maxAnalogGain by mutableFloatStateOf(3200f)
    var currentFocus by mutableFloatStateOf(capabilities.defaultFocus)
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
                lastFrameReceivedRealtime = getElapsedRealtimeMs()
            }
            _isLowPowerMode.value = value
        }

    var starSensitivity by mutableFloatStateOf(50f)
    var uvcStacking by mutableIntStateOf(1)

    val currentSigma: Float
        get() {
            val s = starSensitivity / 100f
            return 5.0f - (s * 3.0f)
        }

    private var lastCalibratedFocus: Float? = null
    private var statsJob: Job? = null
    private var frameJob: Job? = null

    fun getAvailableInternalCameras(): String {
        return engine.getAvailableInternalCameras()
    }

    private fun updateStats() {
        val json = engine.getEngineStats()
        try {
            val obj = JSONObject(json)
            val parsedMirrored =
                if (obj.isNull("is_mirrored")) null else obj.getBoolean("is_mirrored")
            if (parsedMirrored != lastLoggedMirrored) {
                lastLoggedMirrored = parsedMirrored
                Log.i(
                    "MainViewModel",
                    "Engine mirroring state updated: is_mirrored=$parsedMirrored"
                )
            }

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
                isMirrored = parsedMirrored,
                solvedCrop = if (obj.isNull("solved_crop")) null else obj.getString("solved_crop"),
                targetPixelX = if (obj.isNull("target_pixel_x")) null else obj.getDouble("target_pixel_x"),
                targetPixelY = if (obj.isNull("target_pixel_y")) null else obj.getDouble("target_pixel_y"),
                distortion = if (obj.isNull("distortion")) null else obj.getDouble("distortion"),
                focus = if (obj.isNull("focus")) null else obj.getDouble("focus").toFloat()
            )

            if (!obj.isNull("focus")) {
                val calibratedFocus = obj.getDouble("focus").toFloat()
                if (calibratedFocus != lastCalibratedFocus) {
                    lastCalibratedFocus = calibratedFocus
                    currentFocus = calibratedFocus
                    if (settingsManager.cameraSettingsMode == "Auto") {
                        settingsManager.manualFocus = calibratedFocus
                    }
                }
            }

            if (settingsManager.cameraSettingsMode == "Auto" && !obj.isNull("current_sigma")) {
                val currentSigmaVal = obj.getDouble("current_sigma").toFloat()
                starSensitivity = 100f * (5.0f - currentSigmaVal) / 3.0f
            }
        } catch (_: Exception) {
        }
    }

    private var lastFrameTimestamp = 0L

    private fun fetchLatestFrame() {
        val frame = engine.getLatestFrame(lastFrameTimestamp)
        if (frame != null && frame.timestamp > lastFrameTimestamp) {
            viewModelScope.launch(Dispatchers.Main) {
                if (isActive) {
                    latestFrame = frame.imageBitmap
                    lastFrameTimestamp = frame.timestamp
                    lastFrameReceivedRealtime = getElapsedRealtimeMs()
                    isCameraStuck = false
                }
            }
        }
    }

    fun dismissCameraStuckDialog() {
        isCameraStuck = false
        // Offset the timer by 10s so the 15s timeout takes 25s to trigger again
        lastFrameReceivedRealtime = getElapsedRealtimeMs() + 10000L
    }

    private fun startPolling() {
        lastFrameReceivedRealtime = getElapsedRealtimeMs()

        statsJob?.cancel()
        frameJob?.cancel()

        statsJob = viewModelScope.launch(Dispatchers.IO) {
            var lastStatsErrorTime = 0L
            while (isActive) {
                try {
                    updateStats()
                    fetchRealtimeSettings()
                } catch (e: Exception) {
                    val now = getElapsedRealtimeMs()
                    if (now - lastStatsErrorTime > 3000L) {
                        lastStatsErrorTime = now
                        Log.e("MainViewModel", "Error in updateStats: ${e.message}")
                    }
                }
                delay(SCREEN_UPDATE_INTERVAL_MS)
            }
        }

        frameJob = viewModelScope.launch(Dispatchers.IO) {
            var lastFrameErrorTime = 0L
            while (isActive) {
                if (!isLowPowerMode) {
                    try {
                        fetchLatestFrame()
                    } catch (e: Exception) {
                        val now = getElapsedRealtimeMs()
                        if (now - lastFrameErrorTime > 3000L) {
                            lastFrameErrorTime = now
                            Log.e("MainViewModel", "Error in fetchLatestFrame: ${e.message}")
                        }
                    }

                    if (isEngineRunning) {
                        val timeSinceLastFrame =
                            getElapsedRealtimeMs() - lastFrameReceivedRealtime
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
        onFailed: (() -> Unit)? = null
    ) {
        latestFrame = null
        lastFrameTimestamp = 0L

        isCameraStuck = false
        lastFrameReceivedRealtime = getElapsedRealtimeMs()
        lastCalibratedFocus = null
        currentLat = lat
        currentLon = lon
        currentZoom = 1.0f
        val isDemo = cameraId == "demo"
        val downsampling = if (isDemo) 0 else settingsManager.downsampling

        val motionSensorMode = if (isDemo) 0 else settingsManager.motionSensorMode
        val isAutoSettings =
            if (isDemo) false else settingsManager.cameraSettingsMode == "Auto"

        val initialZoom = if (isDemo) 1.0f else {
            if (isAutoSettings) {
                if (ENABLE_AUTO_ZOOM) settingsManager.autoZoomRatio.coerceIn(1.0f, 2.0f) else 1.0f
            } else {
                settingsManager.manualZoomRatio.coerceAtLeast(1.0f)
            }
        }
        currentZoom = initialZoom

        viewModelScope.launch(Dispatchers.IO) {
            val forcedSensorMode = if (isAutoSettings) "Processed" else settingsManager.sensorMode
            val forcedDownsampling = if (isAutoSettings) {
                if (capabilities.isApple) 1 else 2 // 1 = None (iOS camera outputs 2016x1512 directly), 2 = 2x2 (Android 12MP -> 2000x1500)
            } else {
                if (downsampling == 0) 1 else downsampling // 0 ("Off" in UI) maps to 1 (None) in engine
            }
            val forcedCameraId = if (isAutoSettings && cameraId != "demo") {
                if (capabilities.isApple) cameraId else "0"
            } else cameraId

            try {
                engine.startNativeCameraEngine(
                    motionSensorMode,
                    forcedSensorMode,
                    forcedCameraId,
                    forcedDownsampling,
                    lat,
                    lon,
                    settingsManager,
                    isAutoSettings,
                    initialZoom
                )
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to start camera engine", e)
                launch(Dispatchers.Main) {
                    stopEngineSync()
                    onFailed?.invoke()
                }
                return@launch
            }

            launch(Dispatchers.Main) {
                isLowPowerMode = false
                engineMode = EngineMode.NATIVE
                skyMapCameraLocked = true

                val gainRange = engine.getNativeCameraGainRange()
                val (minG, maxG, defaultG) = if (gainRange != null && gainRange.size >= 3) {
                    Triple(gainRange[0], gainRange[1], gainRange[2])
                } else {
                    Triple(100f, 3200f, 3200f)
                }
                minAnalogGain = minG
                maxAnalogGain = maxG

                if (isDemo) {
                    starSensitivity = 100f
                    uvcStacking = 1
                    engine.setEngineSigma(currentSigma)
                } else if (isAutoSettings) {
                    val savedFocus = settingsManager.manualFocus
                    val focusToApply =
                        if (savedFocus in capabilities.focusRange) savedFocus else capabilities.defaultFocus
                    setFocus(focusToApply)
                    starSensitivity = 50f
                    uvcStacking = 1
                    engine.setEngineSigma(currentSigma)
                } else {
                    var savedGain = settingsManager.manualGain
                    if (savedGain < 0f) savedGain = defaultG
                    savedGain = savedGain.coerceIn(minAnalogGain, maxAnalogGain)
                    setGain(savedGain)

                    setFocus(settingsManager.manualFocus)
                    setNoiseReduction(settingsManager.manualNoiseReduction)
                    updateHotPixelMode(settingsManager.manualHotPixelMode)
                    setExposure(settingsManager.manualExposureUs)

                    updateStarSensitivity(settingsManager.manualStarSensitivity)
                    updateUvcStacking(settingsManager.manualUvcStacking)
                }

                supportedHotPixelModes =
                    engine.getNativeCameraSupportedHotPixelModes().toList()
                engine.setEngineStrictMatch(false)
                startPolling()
            }
        }
    }

    fun startUvcEngine(
        lat: Double = 200.0,
        lon: Double = 200.0,
        onFailed: (() -> Unit)? = null
    ) {
        latestFrame = null
        lastFrameTimestamp = 0L

        isCameraStuck = false
        lastFrameReceivedRealtime = getElapsedRealtimeMs()
        lastCalibratedFocus = null
        currentLat = lat
        currentLon = lon
        currentZoom = 1.0f
        viewModelScope.launch(Dispatchers.IO) {
            if (isEngineRunning) {
                engine.stopEngine()
            }

            val isAutoSettings = settingsManager.cameraSettingsMode == "Auto"
            val uvcWidth = uvcWrapper?.previewWidth ?: settingsManager.uvcWidth
            val uvcHeight = uvcWrapper?.previewHeight ?: settingsManager.uvcHeight
            val autoDownsampling = if (uvcWidth.toLong() * uvcHeight.toLong() < 1_250_000L) 0 else 2
            val forcedDownsampling =
                if (isAutoSettings) autoDownsampling else settingsManager.downsampling
            try {
                engine.startExternalCameraEngine(
                    uvcWidth,
                    uvcHeight,
                    settingsManager.motionSensorMode,
                    forcedDownsampling,
                    lat,
                    lon,
                    settingsManager,
                    isAutoSettings,
                    settingsManager.uvcFps,
                    uvcWrapper
                )
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to start UVC engine", e)
                launch(Dispatchers.Main) {
                    stopEngineSync()
                    onFailed?.invoke()
                }
                return@launch
            }

            launch(Dispatchers.Main) {
                engineMode = EngineMode.UVC
                skyMapCameraLocked = true

                uvcBrightnessMin = 0
                uvcBrightnessMax = 100
                minAnalogGain = 0f
                maxAnalogGain = 100f

                val fps = settingsManager.uvcFps.coerceAtLeast(1)
                val initialGain = if (isAutoSettings) {
                    100f
                } else {
                    val saved = settingsManager.manualGain
                    if (saved < 0f) 100f else saved.coerceIn(0f, 100f)
                }

                // Both Auto and Manual modes run the same AEC Prime-and-Lock startup sequence:
                // 200ms stabilize -> 750ms Mode 2 prime -> Lock Mode 1 -> 1/fps exposure -> initialGain
                currentExposure = (1_000_000 / fps).toFloat()
                currentGain = initialGain
                uvcWrapper?.startZeroCopyStream(initialGain.toInt())

                val initialBrightness =
                    if (isAutoSettings) 50 else settingsManager.manualUvcBrightness
                setUvcBrightness(initialBrightness)

                if (isAutoSettings) {
                    updateStarSensitivity(50f)
                    updateUvcStacking(1)
                } else {
                    updateStarSensitivity(settingsManager.manualStarSensitivity)
                    updateUvcStacking(settingsManager.manualUvcStacking)
                }

                engine.setEngineSigma(currentSigma)
                engine.setEngineStrictMatch(true)

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
            engine.setNativeCameraExposure(us.toInt())
        }
    }

    fun updateStarSensitivity(s: Float) {
        starSensitivity = s
        if (settingsManager.cameraSettingsMode == "Manual") {
            settingsManager.manualStarSensitivity = s
        }
        val sigma = currentSigma
        viewModelScope.launch(Dispatchers.IO) {
            engine.setEngineSigma(sigma)
        }
    }

    fun fetchRealtimeSettings() {
        if (engineMode == EngineMode.NATIVE) {
            if (settingsManager.cameraSettingsMode == "Auto") {
                val gain = engine.getNativeCameraGain()
                if (!gain.isNaN() && gain > 0) {
                    currentGain = gain
                }
                val exp = engine.getNativeCameraExposure()
                if (exp > 0) {
                    currentExposure = exp.toFloat()
                }
                noiseReductionMode = engine.getNativeCameraNoiseReduction()
            }
            hotPixelMode = engine.getNativeCameraHotPixelMode()
        } else if (engineMode == EngineMode.UVC && uvcWrapper?.isPipelineReady == true) {
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
        val clamped = if (settingsManager.cameraSettingsMode == "Manual") {
            zoom.coerceAtLeast(1.0f)
        } else {
            zoom.coerceIn(1.0f, 2.0f)
        }
        if (currentZoom == clamped) return
        currentZoom = clamped
        if (settingsManager.cameraSettingsMode == "Manual") {
            settingsManager.manualZoomRatio = clamped
        } else {
            settingsManager.autoZoomRatio = clamped
        }
        viewModelScope.launch(Dispatchers.IO) {
            engine.setNativeCameraZoomRatio(clamped)
        }
    }

    fun setGain(gain: Float) {
        currentGain = gain
        if (settingsManager.cameraSettingsMode == "Manual") {
            settingsManager.manualGain = gain
        }
        viewModelScope.launch(Dispatchers.IO) {
            engine.setNativeCameraGain(gain)
        }
    }

    fun setFocus(focus: Float) {
        currentFocus = focus
        settingsManager.manualFocus = focus
        lastCalibratedFocus = focus
        viewModelScope.launch(Dispatchers.IO) {
            engine.setNativeCameraFocus(focus)
        }
    }

    fun setNoiseReduction(mode: Int) {
        noiseReductionMode = mode
        if (settingsManager.cameraSettingsMode == "Manual") {
            settingsManager.manualNoiseReduction = mode
        }
        viewModelScope.launch(Dispatchers.IO) {
            engine.setNativeCameraNoiseReduction(mode)
        }
    }

    fun updateHotPixelMode(mode: Int) {
        hotPixelMode = mode
        if (settingsManager.cameraSettingsMode == "Manual") {
            settingsManager.manualHotPixelMode = mode
        }
        viewModelScope.launch(Dispatchers.IO) {
            engine.setNativeCameraHotPixelMode(mode)
        }
    }

    fun setUvcBrightness(brightness: Int) {
        currentUvcBrightness = brightness
        if (settingsManager.cameraSettingsMode == "Manual") {
            settingsManager.manualUvcBrightness = brightness
        }
        viewModelScope.launch(Dispatchers.IO) {
            engine.setNativeCameraBrightness(brightness)
        }
    }

    fun setEnginePaused(paused: Boolean) {
        if (!paused) {
            lastFrameReceivedRealtime = getElapsedRealtimeMs()
            isCameraStuck = false
        }
        engine.setEnginePaused(paused)
    }

    fun updateUvcStacking(frames: Int) {
        uvcStacking = frames.coerceIn(1, 10)
        if (settingsManager.cameraSettingsMode == "Manual") {
            settingsManager.manualUvcStacking = uvcStacking
        }
        if (engineMode == EngineMode.UVC) {
            val fps = settingsManager.uvcFps.coerceAtLeast(1)
            currentExposure = (1_000_000f / fps) * uvcStacking
        }
        engine.updateUvcStacking(uvcStacking)
    }

    fun setGoToTarget(ra: Double, dec: Double, epoch: Double = 2000.0) {
        if (isEngineRunning) {
            engine.setGoToTarget(ra, dec, epoch)
            Log.i("MainViewModel", "GoTo Target set: RA=$ra, Dec=$dec, Epoch=$epoch")
        } else {
            Log.w("MainViewModel", "Engine not running, cannot set GoTo Target: RA=$ra, Dec=$dec")
        }
    }

    fun clearGoToTarget() {
        if (isEngineRunning) {
            engine.clearGoToTarget()
            Log.i("MainViewModel", "GoTo Target cleared")
        } else {
            Log.w("MainViewModel", "Engine not running, cannot clear GoTo Target")
        }
    }

    fun syncTelescope(ra: Double? = null, dec: Double? = null) {
        if (isEngineRunning) {
            if (ra != null && dec != null) {
                engine.syncTelescopeToTarget(ra, dec)
                Log.i("MainViewModel", "Sync Telescope to target: RA=$ra, Dec=$dec")
            } else {
                engine.syncTelescope()
                Log.i("MainViewModel", "Sync Telescope requested")
            }
        } else {
            Log.w("MainViewModel", "Engine not running, cannot sync telescope")
        }
    }

    fun setAlignTargetPixel(x: Double, y: Double) {
        if (isEngineRunning) {
            engine.setAlignTargetPixel(x, y)
            Log.i("MainViewModel", "Align Target set: x=$x, y=$y")
        } else {
            Log.w("MainViewModel", "Engine not running, cannot set align target: x=$x, y=$y")
        }
    }

    private fun parseCatalogObjectsFromJson(json: String): List<CatalogObject> {
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

    fun clearAlignTargetPixel() {
        if (isEngineRunning) {
            engine.clearAlignTargetPixel()
            Log.i("MainViewModel", "Clear Align Target requested")
        } else {
            Log.w("MainViewModel", "Engine not running, cannot clear align target")
        }
    }

    fun getPlanets(): List<CatalogObject> {
        val lat = currentLat ?: return emptyList()
        val lon = currentLon ?: return emptyList()
        if (!isValidCoordinate(lat, lon)) return emptyList()

        val bspPath = catalogDb.getBspPath()
        val timeMs = Clock.System.now().toEpochMilliseconds()

        val json = engine.getPlanets(bspPath, timeMs, lat, lon)
        if (json.isBlank() || json == "[]") return emptyList()

        return parseCatalogObjectsFromJson(json)
    }

    fun getAsteroids(): List<CatalogObject> {
        val lat = currentLat ?: return emptyList()
        val lon = currentLon ?: return emptyList()
        if (!isValidCoordinate(lat, lon)) return emptyList()

        val bspPath = catalogDb.getBspPath()
        val timeMs = Clock.System.now().toEpochMilliseconds()
        val asteroidsJson = catalogDb.getAsteroidsJson()

        val json = engine.getAsteroids(bspPath, timeMs, lat, lon, asteroidsJson)
        if (json.isBlank() || json == "[]") return emptyList()

        return parseCatalogObjectsFromJson(json)
    }

    fun getComets(): List<CatalogObject> {
        val lat = currentLat ?: return emptyList()
        val lon = currentLon ?: return emptyList()
        if (!isValidCoordinate(lat, lon)) return emptyList()

        val bspPath = catalogDb.getBspPath()
        val timeMs = Clock.System.now().toEpochMilliseconds()
        val cometsJson = catalogDb.getCometsJson()

        val json = engine.getComets(bspPath, timeMs, lat, lon, cometsJson)
        if (json.isBlank() || json == "[]") return emptyList()

        return parseCatalogObjectsFromJson(json)
    }

    fun refreshComets(onComplete: () -> Unit, onNetworkError: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = catalogDb.refreshComets()
            launch(Dispatchers.Main) {
                if (success) {
                    cometsRefreshVersion++
                    onComplete()
                } else {
                    onNetworkError()
                }
            }
        }
    }

    fun stopEngineSync() {
        engine.stopEngine()

        engineMode = EngineMode.STOPPED
        isLowPowerMode = false
        currentZoom = 1.0f
        skyMapCameraLocked = true
        stopPolling()

        engineStats = EngineStats()
        latestFrame = null
        isCameraStuck = false
        lastFrameTimestamp = 0L
        clearGoToTarget()

        uvcWrapper?.release()
        uvcWrapper = null
    }

    override fun onCleared() {
        fovSaveJob?.cancel()
        settingsManager.skyMapFov = _skyMapFov.doubleValue
        super.onCleared()
        catalogDb.closeDatabase()
    }

    companion object {
        const val ENABLE_AUTO_ZOOM = true
        const val INVALID_COORD = 200.0
        const val CAMERA_STUCK_TIMEOUT_MS = 15000L
        const val SCREEN_UPDATE_RATE_HZ = 10
        const val SCREEN_UPDATE_INTERVAL_MS = 1000L / SCREEN_UPDATE_RATE_HZ

        fun isValidCoordinate(lat: Double?, lon: Double?): Boolean {
            if (lat == null || lon == null) return false
            return lat in -90.0..90.0 && lon in -180.0..180.0
        }
    }
}
