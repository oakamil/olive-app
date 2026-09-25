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
import kotlin.math.abs

class MainViewModel(application: Application) : AndroidViewModel(application) {
    var uvcWrapper: UvcCameraWrapper? = null

    enum class EngineMode { STOPPED, UVC, NATIVE, CEDAR_REMOTE }

    var engineMode by mutableStateOf(EngineMode.STOPPED)
        private set

    val isEngineRunning: Boolean
        get() = engineMode != EngineMode.STOPPED

    var engineStats by mutableStateOf(EngineStats())
        private set

    var currentLat by mutableStateOf<Double?>(null)
    var currentLon by mutableStateOf<Double?>(null)

    // Catalog State
    var catalogSearchQuery by mutableStateOf("")
    var catalogSelectedCategory by mutableStateOf("Nearest Objects")
    var catalogSortBy by mutableStateOf("catalog ID")
    var catalogMagLimit by mutableStateOf<Double?>(null)
    var catalogTypeFilter by mutableStateOf("All Types")
    var catalogVisibilityFilter by mutableStateOf("Above Horizon")
    var landscapeKeyboardOnRight by mutableStateOf(true)

    // Tuning state
    var currentExposure by mutableFloatStateOf(33000f)
    var currentGain by mutableFloatStateOf(50f)
    var minAnalogGain by mutableFloatStateOf(100f)
    var maxAnalogGain by mutableFloatStateOf(3200f)
    var currentFocus by mutableFloatStateOf(0.0f)
    var noiseReductionMode by mutableIntStateOf(0)
    var hotPixelMode by mutableIntStateOf(0)
    var supportedHotPixelModes by mutableStateOf<List<Int>>(emptyList())
    var isRecording by mutableStateOf(false)
    var isCameraConnecting by mutableStateOf(true)

    // UVC controls
    var uvcBrightnessMin by mutableIntStateOf(0)
    var uvcBrightnessMax by mutableIntStateOf(100)
    var currentUvcBrightness by mutableIntStateOf(50)
    var latestFrame by mutableStateOf<ImageBitmap?>(null)
    var isCameraStuck by mutableStateOf(false)
    private var lastFrameReceivedRealtime = 0L

    // Cedar remote frame parameters
    var remoteBinningFactor: Int = 1
    var remoteImageRectX: Int = 0
    var remoteImageRectY: Int = 0
    var remoteDaylightMode by mutableStateOf(false)
        private set
    var remoteCalibrating by mutableStateOf(false)
        private set
    var remoteSkipFocusActive by mutableStateOf(false)
        private set

    fun updateRemoteDaylightMode(enabled: Boolean) {
        remoteDaylightMode = enabled
        if (engineMode == EngineMode.CEDAR_REMOTE) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val opSettings = cedar.CedarOuterClass.OperationSettings.newBuilder()
                        .setDaylightMode(enabled)
                        .build()
                    cedarClient?.updateOperationSettings(opSettings)
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Failed to set daylight mode", e)
                }
            }
        }
    }

    var starSensitivity by mutableFloatStateOf(50f)
    var uvcStacking by mutableIntStateOf(1)

    var activeBluetoothProxy: BluetoothGrpcProxy? = null
    var cedarClient: CedarClient? = null

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

    val settingsManager = SettingsManager(application)
    val catalogDb = CatalogDatabaseHelper(application)

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
                solvedCrop = if (obj.isNull("solved_crop")) null else obj.getString("solved_crop"),
                targetPixelX = if (obj.isNull("target_pixel_x")) null else obj.getDouble("target_pixel_x"),
                targetPixelY = if (obj.isNull("target_pixel_y")) null else obj.getDouble("target_pixel_y"),
                distortion = if (obj.isNull("distortion")) null else obj.getDouble("distortion")
            )

            // Removed syncing tuning sliders here to allow them to reflect user-selected values
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
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Error in updateStats: ${e.message}")
                }
                delay(100) // ~10Hz stats
            }
        }

        frameJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
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
                delay(33) // ~30Hz frames
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

            if (cameraId != "demo") {
                val expUs = if (isAutoSettings) {
                    val fps = settingsManager.internalFps.coerceAtLeast(1)
                    1000000 / fps
                } else {
                    currentExposure.toInt()
                }
                currentExposure = expUs.toFloat()
                OliveEngine.setNativeCameraExposure(expUs)
            }

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
            OliveEngine.setEngineSigma(currentSigma)
            OliveEngine.setEngineStrictMatch(false) // Phone camera has wide FOV, needs generous thresholds

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
                    val defExp = if (isAutoSettings) {
                        val fps = settingsManager.internalFps.coerceAtLeast(1)
                        1000000 / fps
                    } else {
                        OliveEngine.getNativeCameraExposure()
                    }
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

            val fps = settingsManager.uvcFps.coerceAtLeast(1)
            currentExposure = (1000000f / fps)
            OliveEngine.setNativeCameraExposure(currentExposure.toInt())

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
                uvcWrapper?.startZeroCopyStream()
                kotlinx.coroutines.delay(300) // Wait for hardware to initialize limits

                currentUvcBrightness = 50
                OliveEngine.setNativeCameraBrightness(50)

                val hwGain = uvcWrapper?.getHardwareGain()
                if (hwGain != null && hwGain in 0..100) {
                    currentGain = hwGain.toFloat()
                    OliveEngine.setNativeCameraGain(hwGain.toFloat())
                }

                OliveEngine.updateUvcStacking(uvcStacking)
            }
            OliveEngine.setEngineSigma(currentSigma)
            OliveEngine.setEngineStrictMatch(true) // SC2210 has tight FOV, use strict thresholds

            launch(Dispatchers.Main) {
                engineMode = EngineMode.UVC

                uvcBrightnessMin = 0
                uvcBrightnessMax = 100
                minAnalogGain = 0f
                maxAnalogGain = 100f

                updateUvcStacking(1)
                startPolling()
            }
        }
    }

    fun startCedarRemoteEngine(
        host: String,
        port: Int,
        lat: Double = INVALID_COORD,
        lon: Double = INVALID_COORD,
    ) {
        latestFrame = null
        lastFrameTimestamp = 0L
        currentLat = lat
        currentLon = lon
        engineMode = EngineMode.CEDAR_REMOTE
        updateRemoteStats(EngineStats(status = "Connecting..."))

        val client = CedarClient(host, port)
        client.connect()
        cedarClient = client

        // Push initial operation settings
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val settings = cedar.CedarOuterClass.OperationSettings.newBuilder()
                    .build()
                client.updateOperationSettings(settings)
            } catch (e: Exception) {
                val errorMsg = e.message ?: e.javaClass.simpleName
                Log.e("MainViewModel", "Failed to update operation settings", e)
                updateRemoteStats(EngineStats(status = "ErrSettings: ${errorMsg.take(20)}"))
            }
        }

        frameJob = viewModelScope.launch(Dispatchers.IO) {
            var lastFrameId = 0
            while (isActive) {
                try {
                    val request = cedar.CedarOuterClass.FrameRequest.newBuilder()
                        .setPrevFrameId(lastFrameId)
                        .setNonBlocking(false)
                        .build()
                    val result = client.getFrame(request)
                    lastFrameId = result.frameId

                    val slewReq = if (result.hasSlewRequest()) result.slewRequest else null

                    remoteCalibrating = result.calibrating
                    remoteSkipFocusActive = result.skipFocusActive

                    val stats = EngineStats(
                        status = if (result.calibrating) "Calibrating" else if (result.hasPlateSolution()) "Solved" else "Solving",
                        centroidCount = result.starCountMovingAverage.toInt(),
                        exposureMs = result.exposureTime.seconds * 1000.0 + result.exposureTime.nanos / 1_000_000.0,
                        postProcessMs = result.processingStats.detectOtherLatency.recent?.mean?.times(
                            1000.0
                        ) ?: 0.0,
                        extractMs = result.processingStats.detectLatency.recent?.mean?.times(1000.0)
                            ?: 0.0,
                        solveMs = result.processingStats.solveLatency.recent?.mean?.times(1000.0)
                            ?: 0.0,
                        solveRateHz = if (result.processingStats.solveInterval.recent?.mean != null && result.processingStats.solveInterval.recent.mean > 0) 1.0 / result.processingStats.solveInterval.recent.mean else 0.0,
                        hfr = 0.0,
                        ra = if (result.hasPlateSolution()) result.plateSolution.imageSkyCoord.ra else null,
                        dec = if (result.hasPlateSolution()) result.plateSolution.imageSkyCoord.dec else null,
                        fov = if (result.hasPlateSolution()) result.plateSolution.fov else null,
                        roll = if (result.hasPlateSolution()) result.plateSolution.roll else null,
                        targetRa = if (slewReq != null && slewReq.hasTarget()) slewReq.target.ra else null,
                        targetDec = if (slewReq != null && slewReq.hasTarget()) slewReq.target.dec else null,
                        offsetRa = if (slewReq != null && slewReq.hasOffsetRotationAxis()) slewReq.offsetRotationAxis else null,
                        offsetDec = if (slewReq != null && slewReq.hasOffsetTiltAxis()) slewReq.offsetTiltAxis else null,
                        offsetAz = if (slewReq != null && slewReq.hasOffsetRotationAxis()) slewReq.offsetRotationAxis else null,
                        offsetAlt = if (slewReq != null && slewReq.hasOffsetTiltAxis()) slewReq.offsetTiltAxis else null,
                        offsetAngle = if (slewReq != null && slewReq.hasTargetAngle()) slewReq.targetAngle else null
                    )
                    updateRemoteStats(stats)

                    if (result.hasImage()) {
                        remoteBinningFactor =
                            if (result.image.binningFactor > 0) result.image.binningFactor else 1
                        if (result.image.hasRectangle()) {
                            remoteImageRectX = result.image.rectangle.originX
                            remoteImageRectY = result.image.rectangle.originY
                        } else {
                            remoteImageRectX = 0
                            remoteImageRectY = 0
                        }
                    }

                    if (result.hasImage() && result.image.imageData.size() > 0) {
                        try {
                            val bytes = result.image.imageData.toByteArray()
                            val bmp =
                                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bmp != null) {
                                updateRemoteFrame(bmp.asImageBitmap())
                            }
                        } catch (e: Exception) {
                            Log.e("MainViewModel", "Failed to decode frame", e)
                        }
                    }
                } catch (e: Exception) {
                    val errorMsg = e.message ?: e.javaClass.simpleName
                    Log.e("MainViewModel", "Error fetching cedar frame", e)
                    val friendlyMsg = getApplication<Application>().getString(
                        R.string.stat_error,
                        errorMsg.take(15)
                    )
                    updateRemoteStats(EngineStats(status = friendlyMsg))
                    delay(1000)
                }
            }
        }
    }

    fun updateRemoteStats(stats: EngineStats) {
        engineStats = stats
    }

    fun updateRemoteFrame(bitmap: ImageBitmap) {
        viewModelScope.launch(Dispatchers.Main) {
            latestFrame = bitmap
        }
    }

    fun setExposure(us: Float) {
        currentExposure = us
        viewModelScope.launch(Dispatchers.IO) {
            if (engineMode == EngineMode.UVC) {
                uvcWrapper?.setExposure(us.toInt())
            }
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
        if (engineMode == EngineMode.NATIVE) {
            val gain = OliveEngine.getNativeCameraGain()
            if (!gain.isNaN() && gain > 0) {
                currentGain = gain
            }
            val focus = OliveEngine.getNativeCameraFocus()
            if (!focus.isNaN() && focus >= 0f) {
                currentFocus = focus
            }
            val zoom = OliveEngine.getNativeCameraZoomRatio()
            if (!zoom.isNaN() && zoom > 0f) {
                settingsManager.zoomRatio = zoom
            }
            hotPixelMode = OliveEngine.getNativeCameraHotPixelMode()
        } else if (engineMode == EngineMode.UVC) {
            val hwGain = uvcWrapper?.getHardwareGain()
            if (hwGain != null && hwGain in 0..100) {
                currentGain = hwGain.toFloat()
            }
            val hwBrightness = uvcWrapper?.getHardwareBrightness()
            if (hwBrightness != null && hwBrightness in 0..100) {
                currentUvcBrightness = hwBrightness
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

    fun setGoToTarget(ra: Double, dec: Double, epoch: Double = 2000.0) {
        if (engineMode == EngineMode.CEDAR_REMOTE) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val coordBuilder = cedar_common.CedarCommon.CelestialCoord.newBuilder()
                        .setRa(ra)
                        .setDec(dec)
                    if (abs(epoch - 2000.0) > 0.001) {
                        coordBuilder.setEpoch(epoch)
                    }
                    val req = cedar.CedarOuterClass.ActionRequest.newBuilder()
                        .setInitiateSlew(coordBuilder.build())
                        .build()
                    cedarClient?.initiateAction(req)
                    Log.i(
                        "MainViewModel",
                        "GoTo Target set in Cedar: RA=$ra, Dec=$dec, Epoch=$epoch"
                    )
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Failed to set goto target", e)
                }
            }
        } else if (isEngineRunning) {
            OliveEngine.setGoToTarget(ra, dec, epoch)
            Log.i("MainViewModel", "GoTo Target set in JNI: RA=$ra, Dec=$dec, Epoch=$epoch")
        } else {
            Log.w("MainViewModel", "Engine not running, cannot set GoTo Target: RA=$ra, Dec=$dec")
        }
    }

    fun syncTelescope(ra: Double? = null, dec: Double? = null) {
        if (engineMode == EngineMode.CEDAR_REMOTE) {
            // Not implemented for remote right now
            Log.w("MainViewModel", "syncTelescope not implemented for Cedar remote")
        } else if (isEngineRunning) {
            if (ra != null && dec != null) {
                OliveEngine.syncTelescopeToTarget(ra, dec)
                Log.i("MainViewModel", "Sync Telescope to target requested in JNI: RA=$ra, Dec=$dec")
            } else {
                OliveEngine.syncTelescope()
                Log.i("MainViewModel", "Sync Telescope requested in JNI")
            }
        } else {
            Log.w("MainViewModel", "Engine not running, cannot sync telescope")
        }
    }

    fun setAlignTargetPixel(x: Double, y: Double) {
        if (engineMode == EngineMode.CEDAR_REMOTE) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val fullX = x * remoteBinningFactor
                    val fullY = y * remoteBinningFactor
                    val coord = cedar.CedarOuterClass.ImageCoord.newBuilder()
                        .setX(fullX)
                        .setY(fullY)
                        .build()
                    val req = cedar.CedarOuterClass.ActionRequest.newBuilder()
                        .setDesignateBoresight(coord)
                        .build()
                    cedarClient?.initiateAction(req)
                    Log.i("MainViewModel", "Align Target set in Cedar: x=$x, y=$y")
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Failed to set align target", e)
                }
            }
        } else if (isEngineRunning) {
            OliveEngine.setAlignTargetPixel(x, y)
            Log.i("MainViewModel", "Align Target set in JNI: x=$x, y=$y")
        } else {
            Log.w("MainViewModel", "Engine not running, cannot set Align Target: x=$x, y=$y")
        }
    }

    fun getPlanets(): List<CatalogObject> {
        val bspPath = getApplication<Application>().getDatabasePath("de421.bsp").absolutePath
        val timeMs = System.currentTimeMillis()
        val lat = currentLat ?: 0.0
        val lon = currentLon ?: 0.0

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
        val bspPath = getApplication<Application>().getDatabasePath("de421.bsp").absolutePath
        val timeMs = System.currentTimeMillis()
        val lat = currentLat ?: 0.0
        val lon = currentLon ?: 0.0
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
        val bspPath = getApplication<Application>().getDatabasePath("de421.bsp").absolutePath
        val timeMs = System.currentTimeMillis()
        val lat = currentLat ?: 0.0
        val lon = currentLon ?: 0.0
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
            val success = catalogDb.refreshComets {
                onNetworkError()
            }
            if (success) {
                launch(Dispatchers.Main) {
                    onComplete()
                }
            }
        }
    }

    fun stopEngineSync() {
        if (isEngineRunning && engineMode != EngineMode.CEDAR_REMOTE) {
            OliveEngine.stopEngine()
        }

        engineMode = EngineMode.STOPPED
        stopPolling()
        frameBuffer = null

        cedarClient?.shutdown()
        cedarClient = null
        activeBluetoothProxy?.stop()
        activeBluetoothProxy = null
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
    }
}
