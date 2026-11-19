package com.oakamil.olive

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

open class MainViewModel(
    val storage: KeyValueStorage,
    val capabilities: PlatformCapabilities,
    var engine: EngineBridge,
    val catalogDb: CatalogDatabase,
    private val hipsLocalBackend: HiPSLocalBackend? = null
) : ViewModel() {
    var hipsTileManager by mutableStateOf<HiPSTileManager?>(null)
        private set
    var isSkyPackDownloaded by mutableStateOf(false)
        private set
    var skyStorageBytes by mutableLongStateOf(0L)
        private set
    var hipsDownloadState by mutableStateOf<HiPSDownloadState>(HiPSDownloadState.Idle)
        private set
    var showHiPSDownloadConfirm by mutableStateOf(false)
    var showHiPSDownloadProgress by mutableStateOf(false)
    private var hipsDownloadJob: Job? = null
    private var hipsCleanupJob: Job? = null

    class HqCatalogConfirmRequest(
        val categoryTitle: String,
        val shortCode: String,
        val missingKeys: IntArray
    )

    var hqIndex by mutableStateOf(HiPSHqIndex.EMPTY)
        private set
    var hqStorageBytes by mutableLongStateOf(0L)
        private set
    var hqDownloadState by mutableStateOf<HiPSHqDownloadState>(HiPSHqDownloadState.Idle)
        private set
    var pendingHqCatalogConfirm by mutableStateOf<HqCatalogConfirmRequest?>(null)
    var showHqDownloadProgress by mutableStateOf(false)
    var showRemoveHqConfirm by mutableStateOf(false)
    private var hqDownloadJob: Job? = null
    private var hqCleanupJob: Job? = null

    @Volatile
    private var suppressHqFlush = false

    private val activePreviewManagers = mutableSetOf<HiPSTileManager>()

    @Volatile
    var uvcWrapper: UvcCameraHandler? = null
    var availableUvcFormats by mutableStateOf(UvcFormat.FALLBACK_FORMATS)

    @Volatile
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

    private val _showSkyBackground = mutableStateOf(settingsManager.showSkyBackground)
    var showSkyBackground: Boolean
        get() = _showSkyBackground.value
        set(value) {
            val effective = value && isSkyPackDownloaded
            if (effective == _showSkyBackground.value) return
            _showSkyBackground.value = effective
            settingsManager.showSkyBackground = effective
            if (effective) {
                ensureHiPSTileManagerOpen()
            } else {
                hipsTileManager?.close()
                hipsTileManager = null
            }
        }

    init {
        settingsManager.savedLocation?.let { (lat, lon) ->
            currentLat = lat
            currentLon = lon
        }
        if (hipsLocalBackend != null) {
            hqCleanupJob = viewModelScope.launch {
                syncHqStorageState(hipsLocalBackend)
            }
            hipsCleanupJob = viewModelScope.launch {
                refreshHiPSPackStateAsync()
            }
        } else {
            showSkyBackground = false
        }
    }

    private fun updateHqStateOnMain(index: HiPSHqIndex, storageBytes: Long) {
        hqIndex = index
        hqStorageBytes = storageBytes
        hipsTileManager?.updateHqIndexOnMain(index)
        activePreviewManagers.forEach { it.updateHqIndexOnMain(index) }
    }

    private suspend fun syncHiPSStorageState(backend: HiPSLocalBackend): Boolean {
        val (packBytes, stagedBytes) = withContext(Dispatchers.IO) {
            val size = backend.downloadedPackSizeBytes()
            if (size > 0L) size to 0L
            else 0L to backend.getStagedStats().totalBytes
        }
        val downloaded = packBytes > 0L
        isSkyPackDownloaded = downloaded
        skyStorageBytes = if (downloaded) packBytes else stagedBytes
        return downloaded
    }

    private suspend fun syncHqStorageState(backend: HiPSLocalBackend) {
        val (loadedIndex, bytes) = withContext(Dispatchers.IO) {
            backend.loadHqIndex() to backend.hqStorageSizeBytes()
        }
        updateHqStateOnMain(loadedIndex, bytes)
    }

    private suspend fun refreshHiPSPackStateAsync() {
        val backend = hipsLocalBackend ?: return
        if (syncHiPSStorageState(backend) && _showSkyBackground.value) {
            ensureHiPSTileManagerOpen()
        } else {
            showSkyBackground = false
        }
    }

    private fun ensureHiPSTileManagerOpen() {
        val backend = hipsLocalBackend ?: return
        if (hipsTileManager != null || !isSkyPackDownloaded) return
        hipsTileManager = HiPSTileManager(
            assetSource = backend.openDownloadedAssetSource(),
            openHqSource = { backend.openHqAssetSource() },
            initialHqIndex = hqIndex
        )
    }

    fun openPreviewTileManager(): HiPSTileManager? {
        val backend = hipsLocalBackend ?: return null
        if (!isSkyPackDownloaded) return null
        return HiPSTileManager(
            assetSource = backend.openDownloadedAssetSource(),
            openHqSource = { backend.openHqAssetSource() },
            initialHqIndex = hqIndex
        ).also { activePreviewManagers.add(it) }
    }

    fun closePreviewTileManager(manager: HiPSTileManager) {
        activePreviewManagers.remove(manager)
        manager.close()
    }

    fun requestSkyBackgroundChange(enabled: Boolean) {
        if (!enabled) {
            showSkyBackground = false
            return
        }
        if (isSkyPackDownloaded) {
            showSkyBackground = true
            return
        }
        if (hipsDownloadJob?.isActive == true) {
            showHiPSDownloadProgress = true
            return
        }
        val backend = hipsLocalBackend
        if (backend == null) {
            showHiPSDownloadConfirm = true
            return
        }
        viewModelScope.launch {
            hipsCleanupJob?.join()
            if (syncHiPSStorageState(backend)) {
                showSkyBackground = true
            } else {
                showHiPSDownloadConfirm = true
            }
        }
    }

    fun startDss2Download() {
        showHiPSDownloadConfirm = false
        showHiPSDownloadProgress = true
        if (hipsDownloadJob?.isActive == true) return
        val backend = hipsLocalBackend
        if (backend == null) {
            hipsDownloadState = HiPSDownloadState.Failed(
                completedTiles = 0,
                totalTiles = Healpix.TOTAL_TILES,
                reason = HiPSFailureReason.StorageBackendUnavailable
            )
            return
        }

        hipsDownloadJob = viewModelScope.launch {
            hipsCleanupJob?.join()
            val ok = HiPSDownloadManager.downloadDss2(backend) { state ->
                hipsDownloadState = state
                when (state) {
                    is HiPSDownloadState.Downloading -> skyStorageBytes = state.downloadedBytes
                    is HiPSDownloadState.Failed -> showHiPSDownloadProgress = true
                    else -> {}
                }
            }
            if (ok && syncHiPSStorageState(backend)) {
                hipsDownloadState = HiPSDownloadState.Idle
                showHiPSDownloadProgress = false
                showSkyBackground = true
            }
        }
    }

    private fun launchHiPSCleanup(block: suspend (HiPSLocalBackend) -> Unit) {
        val activeJob = hipsDownloadJob
        val priorCleanup = hipsCleanupJob
        hipsDownloadJob = null
        hipsDownloadState = HiPSDownloadState.Idle
        showHiPSDownloadProgress = false
        val backend = hipsLocalBackend ?: return
        hipsCleanupJob = viewModelScope.launch {
            activeJob?.cancelAndJoin()
            priorCleanup?.cancelAndJoin()
            block(backend)
        }
    }

    fun cancelDss2Download() {
        launchHiPSCleanup { backend ->
            syncHiPSStorageState(backend)
        }
    }

    fun removeDownloadedSkyPack() {
        showSkyBackground = false
        activePreviewManagers.toList().forEach { closePreviewTileManager(it) }
        isSkyPackDownloaded = false
        skyStorageBytes = 0L
        launchHiPSCleanup { backend ->
            val removed = withContext(Dispatchers.IO) {
                backend.deleteDownloadedPackAndStaging()
            }
            if (!removed) {
                refreshHiPSPackStateAsync()
            }
        }
    }

    fun objectHqTargetId(obj: CatalogObject): String =
        "obj_${obj.catalogLabel}_${obj.catalogEntry}_${obj.id}"

    /** Computes missing HQ tile keys for [shortCode], including cross-catalog co-located sizes. */
    suspend fun planCatalogMissingHqKeys(shortCode: String): IntArray {
        hipsCleanupJob?.join()
        hqCleanupJob?.join()
        val skyPackReady = isSkyPackDownloaded
        val hqSnap = hqIndex
        return withContext(Dispatchers.IO) {
            val objects = catalogDb.searchObjects(
                catalogFilter = shortCode,
                limit = Int.MAX_VALUE,
                offset = 0
            )
            HiPSObjectPlanner.planMissingForCatalog(
                objects = objects,
                isBasePackAvailable = skyPackReady,
                hqIndex = hqSnap,
                coLocatedLookup = { catalogDb.getCoLocatedObjects(it) }
            )
        }
    }

    fun downloadObjectHqImage(obj: CatalogObject, coLocated: List<CatalogObject>) {
        val activeState = hqDownloadState
        val targetId = objectHqTargetId(obj)
        if (hqDownloadJob?.isActive == true) {
            if (activeState is HiPSHqDownloadState.Downloading && activeState.isBulkCatalog) {
                showHqDownloadProgress = true
                return
            }
            if (activeState is HiPSHqDownloadState.Downloading && activeState.targetId == targetId) {
                return
            }
        }
        val major = HiPSObjectPlanner.resolveMajorAxisArcmin(obj, coLocated)
        val plan = HiPSObjectPlanner.planCutout(obj.ra, obj.dec, major)
        val missing = HiPSObjectPlanner.filterMissingKeys(
            plan.requiredKeys,
            isSkyPackDownloaded,
            hqIndex
        )
        if (missing.isEmpty()) return
        startHqDownloadInternal(
            targetId = targetId,
            isBulkCatalog = false,
            catalogTitle = null,
            missingKeys = missing,
            cancelPriorSingleJob = true
        )
    }

    fun requestCatalogHqDownload(
        categoryTitle: String,
        shortCode: String,
        missingKeys: IntArray
    ) {
        val activeState = hqDownloadState
        if (hqDownloadJob?.isActive == true &&
            activeState is HiPSHqDownloadState.Downloading &&
            activeState.isBulkCatalog
        ) {
            showHqDownloadProgress = true
            return
        }
        if (missingKeys.isEmpty()) return
        pendingHqCatalogConfirm = HqCatalogConfirmRequest(categoryTitle, shortCode, missingKeys)
    }

    fun confirmCatalogHqDownload() {
        val req = pendingHqCatalogConfirm ?: return
        pendingHqCatalogConfirm = null
        showHqDownloadProgress = true
        startHqDownloadInternal(
            targetId = "cat_${req.shortCode}",
            isBulkCatalog = true,
            catalogTitle = req.categoryTitle,
            missingKeys = req.missingKeys,
            cancelPriorSingleJob = true
        )
    }

    fun retryFailedHqDownload() {
        val failed = hqDownloadState as? HiPSHqDownloadState.Failed ?: return
        if (hqDownloadJob?.isActive == true) return
        if (failed.isBulkCatalog && failed.targetId.startsWith("cat_")) {
            val shortCode = failed.targetId.removePrefix("cat_")
            hqDownloadState = HiPSHqDownloadState.Downloading(
                targetId = failed.targetId,
                isBulkCatalog = true,
                catalogTitle = failed.catalogTitle,
                completedTiles = 0,
                totalTiles = failed.totalTiles,
                downloadedBytes = 0L
            )
            showHqDownloadProgress = true
            hqDownloadJob = viewModelScope.launch {
                val missing = planCatalogMissingHqKeys(shortCode)
                if (missing.isEmpty()) {
                    hqDownloadState = HiPSHqDownloadState.Idle
                    showHqDownloadProgress = false
                } else {
                    runHqDownloadJobBody(
                        targetId = failed.targetId,
                        isBulkCatalog = true,
                        catalogTitle = failed.catalogTitle,
                        rawMissingKeys = missing
                    )
                }
            }
        } else {
            hqDownloadState = HiPSHqDownloadState.Idle
        }
    }

    private fun startHqDownloadInternal(
        targetId: String,
        isBulkCatalog: Boolean,
        catalogTitle: String?,
        missingKeys: IntArray,
        cancelPriorSingleJob: Boolean = false
    ) {
        val priorJob = hqDownloadJob
        if (priorJob?.isActive == true && !cancelPriorSingleJob) return
        val backend = hipsLocalBackend
        if (backend == null) {
            hqDownloadState = HiPSHqDownloadState.Failed(
                targetId = targetId,
                isBulkCatalog = isBulkCatalog,
                catalogTitle = catalogTitle,
                completedTiles = 0,
                totalTiles = missingKeys.size,
                reason = HiPSFailureReason.StorageBackendUnavailable
            )
            if (isBulkCatalog) showHqDownloadProgress = true
            return
        }
        hqDownloadState = HiPSHqDownloadState.Downloading(
            targetId = targetId,
            isBulkCatalog = isBulkCatalog,
            catalogTitle = catalogTitle,
            completedTiles = 0,
            totalTiles = missingKeys.size,
            downloadedBytes = 0L
        )
        hqDownloadJob = viewModelScope.launch {
            priorJob?.cancelAndJoin()
            hipsCleanupJob?.join()
            hqCleanupJob?.join()
            runHqDownloadJobBody(targetId, isBulkCatalog, catalogTitle, missingKeys)
        }
    }

    private suspend fun runHqDownloadJobBody(
        targetId: String,
        isBulkCatalog: Boolean,
        catalogTitle: String?,
        rawMissingKeys: IntArray
    ) {
        val backend = hipsLocalBackend ?: return
        suppressHqFlush = false
        syncHqStorageState(backend)
        val missingKeys = HiPSObjectPlanner.filterMissingKeys(
            rawMissingKeys,
            isSkyPackDownloaded,
            hqIndex
        )
        if (missingKeys.isEmpty()) {
            hqDownloadState = HiPSHqDownloadState.Idle
            showHqDownloadProgress = false
            return
        }
        val outcome = HiPSDownloadManager.downloadHqTiles(
            backend = backend,
            missingKeys = missingKeys,
            initialIndex = hqIndex,
            flushOnExit = { !suppressHqFlush },
            onProgress = { done, total, bytes ->
                if (!suppressHqFlush) {
                    hqDownloadState = HiPSHqDownloadState.Downloading(
                        targetId = targetId,
                        isBulkCatalog = isBulkCatalog,
                        catalogTitle = catalogTitle,
                        completedTiles = done,
                        totalTiles = total,
                        downloadedBytes = bytes
                    )
                }
            },
            onBatchCommitted = { updatedIndex, diskBytes ->
                if (!suppressHqFlush) {
                    updateHqStateOnMain(updatedIndex, diskBytes)
                }
            }
        )
        if (outcome.storageFailed || outcome.completedTiles < outcome.totalTiles) {
            val reason = if (outcome.storageFailed) {
                HiPSFailureReason.StorageWriteFailed
            } else {
                HiPSFailureReason.TileDownloadFailed(outcome.totalTiles - outcome.completedTiles)
            }
            hqDownloadState = HiPSHqDownloadState.Failed(
                targetId = targetId,
                isBulkCatalog = isBulkCatalog,
                catalogTitle = catalogTitle,
                completedTiles = outcome.completedTiles,
                totalTiles = outcome.totalTiles,
                reason = reason
            )
            if (isBulkCatalog) showHqDownloadProgress = true
        } else {
            hqDownloadState = HiPSHqDownloadState.Idle
            showHqDownloadProgress = false
        }
    }

    private fun launchHqCleanup(block: suspend (HiPSLocalBackend) -> Unit) {
        val activeJob = hqDownloadJob
        val priorCleanup = hqCleanupJob
        hqDownloadJob = null
        hqDownloadState = HiPSHqDownloadState.Idle
        showHqDownloadProgress = false
        val backend = hipsLocalBackend ?: return
        hqCleanupJob = viewModelScope.launch {
            activeJob?.cancelAndJoin()
            priorCleanup?.cancelAndJoin()
            block(backend)
        }
    }

    fun cancelHqDownload() {
        launchHqCleanup { backend ->
            syncHqStorageState(backend)
        }
    }

    fun removeHqImages() {
        showRemoveHqConfirm = false
        suppressHqFlush = true
        updateHqStateOnMain(HiPSHqIndex.EMPTY, 0L)
        launchHqCleanup { backend ->
            try {
                updateHqStateOnMain(HiPSHqIndex.EMPTY, 0L)
                hipsTileManager?.releaseHqSource()
                for (mgr in activePreviewManagers.toList()) {
                    mgr.releaseHqSource()
                }
                val removed = withContext(Dispatchers.IO) {
                    backend.deleteHqStorage()
                }
                if (!removed) {
                    syncHqStorageState(backend)
                }
            } finally {
                suppressHqFlush = false
            }
        }
    }

    /** Decodes all leaf tiles in [requiredKeys] from the HQ store off the Main thread. */
    suspend fun loadCutoutTiles(requiredKeys: IntArray): List<Pair<Int, ImageBitmap>> {
        val backend = hipsLocalBackend ?: return emptyList()
        hqCleanupJob?.join()
        val hqSnap = hqIndex
        if (hqSnap.size == 0 || requiredKeys.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            val hqSrc = backend.openHqAssetSource()
            try {
                val decoded = ArrayList<Pair<Int, ImageBitmap>>(requiredKeys.size)
                for (key in requiredKeys) {
                    if (!isActive || suppressHqFlush) break
                    val entryIdx = hqSnap.findEntryIndex(key)
                    if (entryIdx < 0) continue
                    val raw = hqSrc.readRange(hqSnap.offsets[entryIdx], hqSnap.lengths[entryIdx])
                        ?: continue
                    val bmp = hqSrc.decodeTile(raw)
                    if (bmp != null) {
                        decoded.add(Healpix.tileKeyIpix(key) to bmp)
                    }
                }
                decoded
            } finally {
                hqSrc.close()
            }
        }
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

    @Volatile
    private var isLowPowerSnapshot = false
    var isLowPowerMode: Boolean
        get() = _isLowPowerMode.value
        set(value) {
            val wasLowPower = _isLowPowerMode.value
            if (wasLowPower && !value) {
                lastFrameReceivedRealtime = getElapsedRealtimeMs()
            }
            isLowPowerSnapshot = value
            _isLowPowerMode.value = value
        }

    var starSensitivity by mutableFloatStateOf(50f)
    var uvcStacking by mutableIntStateOf(1)

    val currentSigma: Float
        get() {
            val s = starSensitivity / 100f
            return 5.0f - (s * 3.0f)
        }

    private data class StatsSnapshot(
        val stats: EngineStats,
        val currentSigma: Float?
    )

    private data class RealtimeSettingsSnapshot(
        val gain: Float? = null,
        val exposure: Float? = null,
        val noiseReduction: Int? = null,
        val hotPixelMode: Int? = null,
        val uvcBrightness: Int? = null
    )

    private var lastCalibratedFocus: Float? = null
    private var statsJob: Job? = null
    private var frameJob: Job? = null
    private val engineLifecycleMutex = Mutex()
    private var engineStartJob: Job? = null

    fun getAvailableInternalCameras(): String {
        return engine.getAvailableInternalCameras()
    }

    private fun readStatsSnapshot(): StatsSnapshot? {
        val json = engine.getEngineStats()
        return try {
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

            val calibratedFocus =
                if (obj.isNull("focus")) null else obj.getDouble("focus").toFloat()
            val stats = EngineStats(
                status = obj.optString("status", EngineStatus.IDLE.rawValue),
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
                focus = calibratedFocus
            )
            val currentSigma =
                if (obj.isNull("current_sigma")) null else obj.getDouble("current_sigma").toFloat()
            StatsSnapshot(stats, currentSigma)
        } catch (_: Exception) {
            null
        }
    }

    private fun applyStatsSnapshot(snapshot: StatsSnapshot) {
        engineStats = snapshot.stats
        snapshot.stats.focus?.let { calibratedFocus ->
            if (calibratedFocus != lastCalibratedFocus) {
                lastCalibratedFocus = calibratedFocus
                currentFocus = calibratedFocus
                if (settingsManager.cameraSettingsMode == "Auto") {
                    settingsManager.manualFocus = calibratedFocus
                }
            }
        }
        snapshot.currentSigma?.let { currentSigmaVal ->
            if (settingsManager.cameraSettingsMode == "Auto") {
                starSensitivity = 100f * (5.0f - currentSigmaVal) / 3.0f
            }
        }
    }

    @Volatile
    private var lastFrameTimestamp = 0L

    fun dismissCameraStuckDialog() {
        // Offset deadline before clearing flag so frameJob cannot observe !isCameraStuck with stale baseline.
        lastFrameReceivedRealtime = getElapsedRealtimeMs() + 10000L
        isCameraStuck = false
    }

    private fun startPolling(activeMode: EngineMode) {
        lastFrameReceivedRealtime = getElapsedRealtimeMs()

        statsJob?.cancel()
        frameJob?.cancel()

        statsJob = viewModelScope.launch(Dispatchers.IO) {
            var lastStatsErrorTime = 0L
            while (isActive) {
                try {
                    val statsSnapshot = readStatsSnapshot()
                    val settingsSnapshot = readRealtimeSettings(
                        mode = activeMode,
                        isAuto = settingsManager.cameraSettingsMode == "Auto",
                        wrapper = uvcWrapper
                    )
                    withContext(Dispatchers.Main) {
                        if (isActive && isEngineRunning) {
                            statsSnapshot?.let { applyStatsSnapshot(it) }
                            if (engineMode == activeMode) {
                                applyRealtimeSettings(settingsSnapshot)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
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
            var lastReportedStuckBaseline = -1L
            while (isActive) {
                if (!isLowPowerSnapshot) {
                    var markStuckBaseline: Long? = null
                    try {
                        val sinceLast = lastFrameTimestamp
                        val stuckCheckBaseline = lastFrameReceivedRealtime
                        val frame = engine.getLatestFrame(sinceLast)
                        if (frame != null && frame.timestamp > sinceLast) {
                            withContext(Dispatchers.Main) {
                                if (isActive && isEngineRunning) {
                                    latestFrame = frame.imageBitmap
                                    lastFrameTimestamp = frame.timestamp
                                    lastFrameReceivedRealtime = getElapsedRealtimeMs()
                                    isCameraStuck = false
                                }
                            }
                        } else if (stuckCheckBaseline != lastReportedStuckBaseline) {
                            val timeSinceLastFrame =
                                getElapsedRealtimeMs() - stuckCheckBaseline
                            if (timeSinceLastFrame > CAMERA_STUCK_TIMEOUT_MS) {
                                markStuckBaseline = stuckCheckBaseline
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val now = getElapsedRealtimeMs()
                        if (now - lastFrameErrorTime > 3000L) {
                            lastFrameErrorTime = now
                            Log.e("MainViewModel", "Error in fetchLatestFrame: ${e.message}")
                        }
                    }
                    if (markStuckBaseline != null) {
                        lastReportedStuckBaseline = markStuckBaseline
                        withContext(Dispatchers.Main) {
                            if (isActive && isEngineRunning && !isCameraStuck &&
                                lastFrameReceivedRealtime == markStuckBaseline
                            ) {
                                isCameraStuck = true
                            }
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
        statsJob = null
        frameJob = null
    }

    private suspend fun stopPollingAndJoin() {
        val activeStatsJob = statsJob
        val activeFrameJob = frameJob
        statsJob = null
        frameJob = null
        activeStatsJob?.cancelAndJoin()
        activeFrameJob?.cancelAndJoin()
    }

    private fun resetEngineUiState() {
        engineMode = EngineMode.STOPPED
        isLowPowerMode = false
        currentZoom = 1.0f
        skyMapCameraLocked = true
        engineStats = EngineStats()
        latestFrame = null
        isCameraStuck = false
        lastFrameTimestamp = 0L
        lastCalibratedFocus = null
    }

    private fun prepareStartUiState(lat: Double, lon: Double, initialZoom: Float) {
        latestFrame = null
        lastFrameTimestamp = 0L
        isCameraStuck = false
        lastFrameReceivedRealtime = getElapsedRealtimeMs()
        lastCalibratedFocus = null
        currentLat = lat
        currentLon = lon
        currentZoom = initialZoom
    }

    fun executeStartEngine(
        cameraId: String,
        lat: Double = INVALID_COORD,
        lon: Double = INVALID_COORD,
        onFailed: (() -> Unit)? = null
    ) {
        val isDemo = cameraId == "demo"
        val isAutoSettings =
            if (isDemo) false else settingsManager.cameraSettingsMode == "Auto"
        val initialZoom = if (isDemo) 1.0f else {
            if (isAutoSettings) {
                if (ENABLE_AUTO_ZOOM) settingsManager.autoZoomRatio.coerceIn(1.0f, 2.0f) else 1.0f
            } else {
                settingsManager.manualZoomRatio.coerceAtLeast(1.0f)
            }
        }
        val downsampling = if (isDemo) 0 else settingsManager.downsampling
        val motionSensorMode = if (isDemo) 0 else settingsManager.motionSensorMode
        val forcedSensorMode = if (isAutoSettings) "Processed" else settingsManager.sensorMode
        val forcedDownsampling = if (isAutoSettings) {
            if (capabilities.isApple) 1 else 2 // 1 = None (iOS camera outputs 2016x1512 directly), 2 = 2x2 (Android 12MP -> 2000x1500)
        } else {
            if (downsampling == 0) 1 else downsampling // 0 ("Off" in UI) maps to 1 (None) in engine
        }
        val forcedCameraId = if (isAutoSettings && cameraId != "demo") {
            if (capabilities.isApple) cameraId else "0"
        } else cameraId

        val prevStart = engineStartJob
        prevStart?.cancel()
        engineStartJob = viewModelScope.launch(Dispatchers.Main) {
            prevStart?.join()
            engineLifecycleMutex.withLock {
                if (!isActive) return@withLock
                if (isEngineRunning || uvcWrapper != null) {
                    stopEngineInternalLocked()
                }
                if (!isActive) return@withLock
                prepareStartUiState(lat, lon, initialZoom)

                try {
                    withContext(Dispatchers.IO) {
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
                    }
                } catch (e: CancellationException) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        engine.stopEngine()
                    }
                    throw e
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Failed to start camera engine", e)
                    stopEngineInternalLocked()
                    onFailed?.invoke()
                    return@withLock
                }

                if (!isActive) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        engine.stopEngine()
                    }
                    resetEngineUiState()
                    return@withLock
                }

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
                startPolling(EngineMode.NATIVE)
            }
        }
    }

    fun startUvcEngine(
        lat: Double = 200.0,
        lon: Double = 200.0,
        wrapper: UvcCameraHandler? = uvcWrapper,
        onFailed: (() -> Unit)? = null
    ) {
        val isAutoSettings = settingsManager.cameraSettingsMode == "Auto"
        val motionSensorMode = settingsManager.motionSensorMode
        val configuredUvcFps = settingsManager.uvcFps
        val configuredUvcWidth = settingsManager.uvcWidth
        val configuredUvcHeight = settingsManager.uvcHeight
        val configuredDownsampling = settingsManager.downsampling

        val prevStart = engineStartJob
        prevStart?.cancel()
        engineStartJob = viewModelScope.launch(
            context = Dispatchers.Main,
            start = CoroutineStart.UNDISPATCHED
        ) {
            var releaseWrapperOnExit = true
            var externalEngineStarted = false
            try {
                prevStart?.join()
                engineLifecycleMutex.withLock {
                    if (!isActive) return@withLock
                    if (isEngineRunning || (uvcWrapper != null && uvcWrapper !== wrapper)) {
                        stopEngineInternalLocked()
                    }
                    if (!isActive) return@withLock
                    uvcWrapper = wrapper
                    prepareStartUiState(lat, lon, 1.0f)

                    val uvcWidth = wrapper?.previewWidth ?: configuredUvcWidth
                    val uvcHeight = wrapper?.previewHeight ?: configuredUvcHeight
                    val autoDownsampling =
                        if (uvcWidth.toLong() * uvcHeight.toLong() < 1_250_000L) 0 else 2
                    val forcedDownsampling =
                        if (isAutoSettings) autoDownsampling else configuredDownsampling

                    try {
                        withContext(Dispatchers.IO) {
                            engine.startExternalCameraEngine(
                                uvcWidth,
                                uvcHeight,
                                motionSensorMode,
                                forcedDownsampling,
                                lat,
                                lon,
                                settingsManager,
                                isAutoSettings,
                                configuredUvcFps,
                                wrapper
                            )
                            externalEngineStarted = true
                        }
                    } catch (e: CancellationException) {
                        externalEngineStarted = true
                        throw e
                    } catch (e: Exception) {
                        Log.e("MainViewModel", "Failed to start UVC engine", e)
                        stopEngineInternalLocked()
                        releaseWrapperOnExit = false
                        onFailed?.invoke()
                        return@withLock
                    }

                    if (!isActive) {
                        resetEngineUiState()
                        return@withLock
                    }

                    engineMode = EngineMode.UVC
                    skyMapCameraLocked = true

                    uvcBrightnessMin = 0
                    uvcBrightnessMax = 100
                    minAnalogGain = 0f
                    maxAnalogGain = 100f

                    val fps = configuredUvcFps.coerceAtLeast(1)
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
                    wrapper?.startZeroCopyStream(initialGain.toInt())

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

                    startPolling(EngineMode.UVC)
                    releaseWrapperOnExit = false
                }
            } finally {
                if (releaseWrapperOnExit) {
                    if (uvcWrapper === wrapper) {
                        uvcWrapper = null
                    }
                    withContext(NonCancellable + Dispatchers.IO) {
                        if (externalEngineStarted) {
                            engine.stopEngine()
                        }
                        wrapper?.release()
                    }
                }
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

    private fun readRealtimeSettings(
        mode: EngineMode,
        isAuto: Boolean,
        wrapper: UvcCameraHandler?
    ): RealtimeSettingsSnapshot {
        return when (mode) {
            EngineMode.NATIVE -> {
                var gain: Float? = null
                var exposure: Float? = null
                var noiseReduction: Int? = null
                if (isAuto) {
                    val g = engine.getNativeCameraGain()
                    if (!g.isNaN() && g > 0) gain = g
                    val exp = engine.getNativeCameraExposure()
                    if (exp > 0) exposure = exp.toFloat()
                    noiseReduction = engine.getNativeCameraNoiseReduction()
                }
                RealtimeSettingsSnapshot(
                    gain = gain,
                    exposure = exposure,
                    noiseReduction = noiseReduction,
                    hotPixelMode = engine.getNativeCameraHotPixelMode()
                )
            }

            EngineMode.UVC if wrapper?.isPipelineReady == true -> {
                var gain: Float? = null
                if (isAuto) {
                    val hwGain = wrapper.getHardwareGain()
                    if (hwGain != null && hwGain in 0..100) gain = hwGain.toFloat()
                }
                val hwBrightness = wrapper.getHardwareBrightness()
                RealtimeSettingsSnapshot(
                    gain = gain,
                    uvcBrightness = if (hwBrightness != null && hwBrightness in 0..100) hwBrightness else null
                )
            }

            else -> RealtimeSettingsSnapshot()
        }
    }

    private fun applyRealtimeSettings(snapshot: RealtimeSettingsSnapshot) {
        snapshot.gain?.let { currentGain = it }
        snapshot.exposure?.let { currentExposure = it }
        snapshot.noiseReduction?.let { noiseReductionMode = it }
        snapshot.hotPixelMode?.let { hotPixelMode = it }
        snapshot.uvcBrightness?.let { currentUvcBrightness = it }
    }

    suspend fun fetchRealtimeSettings() {
        val mode = engineMode
        if (mode == EngineMode.STOPPED) return
        val snapshot = withContext(Dispatchers.IO) {
            readRealtimeSettings(
                mode = mode,
                isAuto = settingsManager.cameraSettingsMode == "Auto",
                wrapper = uvcWrapper
            )
        }
        withContext(Dispatchers.Main) {
            if (isEngineRunning && engineMode == mode) {
                applyRealtimeSettings(snapshot)
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

    fun getPlanets(
        latValue: Double? = currentLat,
        lonValue: Double? = currentLon
    ): List<CatalogObject> {
        val lat = latValue ?: return emptyList()
        val lon = lonValue ?: return emptyList()
        if (!isValidCoordinate(lat, lon)) return emptyList()

        val bspPath = catalogDb.getBspPath()
        val timeMs = Clock.System.now().toEpochMilliseconds()

        val json = engine.getPlanets(bspPath, timeMs, lat, lon)
        if (json.isBlank() || json == "[]") return emptyList()

        return parseCatalogObjectsFromJson(json)
    }

    fun getAsteroids(
        latValue: Double? = currentLat,
        lonValue: Double? = currentLon
    ): List<CatalogObject> {
        val lat = latValue ?: return emptyList()
        val lon = lonValue ?: return emptyList()
        if (!isValidCoordinate(lat, lon)) return emptyList()

        val bspPath = catalogDb.getBspPath()
        val timeMs = Clock.System.now().toEpochMilliseconds()
        val asteroidsJson = catalogDb.getAsteroidsJson()

        val json = engine.getAsteroids(bspPath, timeMs, lat, lon, asteroidsJson)
        if (json.isBlank() || json == "[]") return emptyList()

        return parseCatalogObjectsFromJson(json)
    }

    fun getComets(
        latValue: Double? = currentLat,
        lonValue: Double? = currentLon
    ): List<CatalogObject> {
        val lat = latValue ?: return emptyList()
        val lon = lonValue ?: return emptyList()
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

    private suspend fun stopEngineInternalLocked() {
        stopPollingAndJoin()
        val wasRunning = withContext(Dispatchers.Main) {
            val running = isEngineRunning
            resetEngineUiState()
            running
        }
        withContext(Dispatchers.IO) {
            if (wasRunning) {
                engine.clearGoToTarget()
                Log.i("MainViewModel", "GoTo Target cleared")
            }
            engine.stopEngine()
            uvcWrapper?.release()
            uvcWrapper = null
        }
    }

    suspend fun stopEngine() {
        val activeStart = withContext(Dispatchers.Main.immediate) {
            val start = engineStartJob
            engineStartJob = null
            start?.cancel()
            start
        }
        activeStart?.join()
        engineLifecycleMutex.withLock {
            stopEngineInternalLocked()
        }
    }

    // Main-thread pre-teardown for non-suspending Android lifecycle hooks such as onDestroy().
    fun cancelAndResetForDestroy(): Boolean {
        engineStartJob?.cancel()
        engineStartJob = null
        stopPolling()
        val wasRunning = isEngineRunning
        resetEngineUiState()
        return wasRunning
    }

    // Blocking IO teardown paired with cancelAndResetForDestroy() inside MainActivity.onDestroy().
    fun stopEngineNativeSync(clearGoTo: Boolean) {
        if (clearGoTo) {
            engine.clearGoToTarget()
            Log.i("MainViewModel", "GoTo Target cleared")
        }
        engine.stopEngine()
        uvcWrapper?.release()
        uvcWrapper = null
    }

    fun dispose() {
        suppressHqFlush = true
        fovSaveJob?.cancel()
        settingsManager.skyMapFov = _skyMapFov.doubleValue
        hipsDownloadJob?.cancel()
        hipsDownloadJob = null
        hipsCleanupJob?.cancel()
        hipsCleanupJob = null
        hqDownloadJob?.cancel()
        hqDownloadJob = null
        hqCleanupJob?.cancel()
        hqCleanupJob = null
        viewModelScope.cancel()
        hipsTileManager?.close()
        hipsTileManager = null
        activePreviewManagers.forEach { it.close() }
        activePreviewManagers.clear()
        CoroutineScope(Dispatchers.IO).launch {
            catalogDb.closeDatabase()
        }
    }

    override fun onCleared() {
        super.onCleared()
        dispose()
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
