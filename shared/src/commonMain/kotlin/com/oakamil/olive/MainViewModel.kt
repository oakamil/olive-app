// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

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

/** Catalog object enriched with observing-list membership or night-session observation metadata. */
data class ResolvedListEntry(
    val catalogObject: CatalogObject,
    val listItemId: Long? = null,
    val observationTimeMs: Long? = null,
    val observationNotePreview: String? = null,
    val observationCount: Int = 0,
    val observationIds: List<Long> = emptyList()
)

/**
 * Shared KMP state holder bridging Compose UI with the native plate-solving engine ([EngineBridge]),
 * internal/UVC cameras, SQLite catalog and user databases, ephemeris queries, and local HiPS sky
 * and HQ object tile storage.
 *
 * Concurrency & lifecycle invariants:
 * - Compose `mutableStateOf` properties are mutated on `Dispatchers.Main`; IO workers read
 *   `@Volatile` snapshots where cross-thread access is required.
 * - Engine start/stop transitions are serialized by `engineLifecycleMutex` (cancelling and joining
 *   any in-flight `engineStartJob` first).
 * - All `userDb` reads, `NonCancellable` writes, and final disposal `close()` are serialized by
 *   `userDbMutex`.
 * - HiPS base-pack and HQ download/cleanup jobs are chained via `hipsCleanupJob` / `hqCleanupJob`
 *   so downloads and tile planners never race against on-disk deletion.
 */
open class MainViewModel(
    val storage: KeyValueStorage,
    val capabilities: PlatformCapabilities,
    var engine: EngineBridge,
    val catalogDb: CatalogDatabase,
    private val hipsLocalBackend: HiPSLocalBackend? = null,
    val userDb: UserDatabase = NoOpUserDatabase
) : ViewModel() {

    // --- HiPS Sky Background & HQ Object Image State ---

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
        val targetId: String,
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

    // Set during HQ deletion/dispose so a cancelled download's NonCancellable exit flush cannot recreate files or overwrite cleared state.
    @Volatile
    private var suppressHqFlush = false

    private val activePreviewManagers = mutableSetOf<HiPSTileManager>()

    // --- External UVC Camera Configuration ---

    data class ExternalCamera(val id: String, val name: String)

    @Volatile
    var uvcWrapper: UvcCameraHandler? = null
    var availableUvcFormats by mutableStateOf(UvcFormat.FALLBACK_FORMATS)
    var externalCameras by mutableStateOf<List<ExternalCamera>>(emptyList())
        private set
    var selectedExternalCameraId by mutableStateOf<String?>(null)
        private set

    fun selectExternalCamera(id: String?) {
        if (selectedExternalCameraId == id) return
        selectedExternalCameraId = id
        availableUvcFormats = UvcFormat.FALLBACK_FORMATS
        val best = UvcFormat.findBestFormatForFps(settingsManager.uvcFps, availableUvcFormats)
        settingsManager.uvcFps = best.fps
        settingsManager.uvcWidth = best.width
        settingsManager.uvcHeight = best.height
        settingsManager.uvcFormat = best.format
    }

    fun updateExternalCameras(
        cameras: List<ExternalCamera>,
        preferredId: String? = null,
        lockCurrentSelection: Boolean = false
    ) {
        externalCameras = cameras
        val ids = cameras.mapTo(HashSet()) { it.id }
        val nextId = when {
            lockCurrentSelection && selectedExternalCameraId in ids -> selectedExternalCameraId
            preferredId != null && preferredId in ids -> preferredId
            selectedExternalCameraId in ids -> selectedExternalCameraId
            else -> cameras.firstOrNull()?.id
        }
        selectExternalCamera(nextId)
    }

    @Volatile
    private var lastLoggedMirrored: Boolean? = null

    private val timeMark = TimeSource.Monotonic.markNow()
    fun getElapsedRealtimeMs(): Long = timeMark.elapsedNow().inWholeMilliseconds

    // --- Engine Mode, Sky Time & Telemetry State ---

    enum class EngineMode { STOPPED, UVC, NATIVE }

    var engineMode by mutableStateOf(EngineMode.STOPPED)
        private set

    @Volatile
    var isAutoEngineSession: Boolean = false
        private set

    val isEngineRunning: Boolean
        get() = engineMode != EngineMode.STOPPED

    var simulatedTimeMs by mutableStateOf<Long?>(null)
        private set

    val isSimulatedTimeActive: Boolean
        get() = !isEngineRunning && simulatedTimeMs != null

    fun effectiveSkyTimeMs(nowMs: Long = Clock.System.now().toEpochMilliseconds()): Long =
        if (isEngineRunning) nowMs else (simulatedTimeMs ?: nowMs)

    fun setSimulatedTime(epochMs: Long) {
        if (isEngineRunning) return
        simulatedTimeMs = epochMs.coerceIn(MIN_SIMULATED_TIME_MS, MAX_SIMULATED_TIME_MS)
    }

    fun resetSimulatedTime() {
        simulatedTimeMs = null
    }

    var engineStats by mutableStateOf(EngineStats())
        private set

    // --- Location & Persisted UI Settings ---

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

    private val _textSize = mutableStateOf(OliveTextSize.fromKey(settingsManager.textSize))
    var textSize: OliveTextSize
        get() = _textSize.value
        set(value) {
            if (_textSize.value == value) return
            _textSize.value = value
            settingsManager.textSize = value.key
        }

    private val _guidanceMode = mutableStateOf(settingsManager.guidanceMode)
    var guidanceMode: String
        get() = _guidanceMode.value
        set(value) {
            _guidanceMode.value = value
            settingsManager.guidanceMode = value
        }

    private val _lowPowerTimeoutSeconds = mutableIntStateOf(settingsManager.lowPowerTimeoutSeconds)
    var lowPowerTimeoutSeconds: Int
        get() = _lowPowerTimeoutSeconds.intValue
        set(value) {
            if (_lowPowerTimeoutSeconds.intValue == value) return
            _lowPowerTimeoutSeconds.intValue = value
            settingsManager.lowPowerTimeoutSeconds = value
        }

    private val _showLiveCamera = mutableStateOf(settingsManager.showLiveCamera)
    var showLiveCamera: Boolean
        get() = _showLiveCamera.value
        set(value) {
            _showLiveCamera.value = value
            settingsManager.showLiveCamera = value
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

    // --- HiPS Storage & Base Sky Pack Lifecycle ---

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

    // Serializes download cancellation and disk cleanup; subsequent downloads/planners join hipsCleanupJob first.
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

    // --- HiPS HQ Object Images ---

    fun objectHqTargetId(obj: CatalogObject): String =
        "obj_${obj.catalogLabel}_${obj.catalogEntry}_${obj.id}"

    /** Computes missing HQ tile keys for [objects], including cross-catalog co-located sizes. */
    suspend fun planObjectsMissingHqKeys(objects: List<CatalogObject>): IntArray {
        if (objects.isEmpty()) return IntArray(0)
        hipsCleanupJob?.join()
        hqCleanupJob?.join()
        val skyPackReady = isSkyPackDownloaded
        val hqSnap = hqIndex
        return withContext(Dispatchers.IO) {
            HiPSObjectPlanner.planMissingForCatalog(
                objects = objects,
                isBasePackAvailable = skyPackReady,
                hqIndex = hqSnap,
                coLocatedLookup = { catalogDb.getCoLocatedObjects(it) }
            )
        }
    }

    /** Computes missing HQ tile keys for [shortCode], including cross-catalog co-located sizes. */
    suspend fun planCatalogMissingHqKeys(shortCode: String): IntArray {
        val objects = withContext(Dispatchers.IO) {
            catalogDb.searchObjects(
                catalogFilter = shortCode,
                limit = Int.MAX_VALUE,
                offset = 0
            )
        }
        return planObjectsMissingHqKeys(objects)
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
        )
    }

    fun requestCatalogHqDownload(
        categoryTitle: String,
        shortCode: String,
        missingKeys: IntArray
    ) = requestBulkHqDownload(
        title = categoryTitle,
        targetId = "cat_$shortCode",
        missingKeys = missingKeys
    )

    fun requestListHqDownload(
        listId: Long,
        listTitle: String,
        missingKeys: IntArray
    ) = requestBulkHqDownload(
        title = listTitle,
        targetId = "list_$listId",
        missingKeys = missingKeys
    )

    private fun requestBulkHqDownload(
        title: String,
        targetId: String,
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
        pendingHqCatalogConfirm = HqCatalogConfirmRequest(title, targetId, missingKeys)
    }

    fun confirmCatalogHqDownload() {
        val req = pendingHqCatalogConfirm ?: return
        pendingHqCatalogConfirm = null
        showHqDownloadProgress = true
        startHqDownloadInternal(
            targetId = req.targetId,
            isBulkCatalog = true,
            catalogTitle = req.categoryTitle,
            missingKeys = req.missingKeys,
        )
    }

    fun retryFailedHqDownload() {
        val failed = hqDownloadState as? HiPSHqDownloadState.Failed ?: return
        if (hqDownloadJob?.isActive == true) return
        val isCat = failed.isBulkCatalog && failed.targetId.startsWith("cat_")
        val isList = failed.isBulkCatalog && failed.targetId.startsWith("list_")
        if (isCat || isList) {
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
                val missing = if (isCat) {
                    planCatalogMissingHqKeys(failed.targetId.removePrefix("cat_"))
                } else {
                    val listId = failed.targetId.removePrefix("list_").toLongOrNull()
                    if (listId != null) {
                        val entries = resolveManualListEntries(listId, currentLat, currentLon)
                        planObjectsMissingHqKeys(entries.map { it.catalogObject })
                    } else {
                        IntArray(0)
                    }
                }
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
        missingKeys: IntArray
    ) {
        val priorJob = hqDownloadJob
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

    // Serializes HQ download cancellation and disk cleanup; planners, cutout loaders, and new downloads join hqCleanupJob first.
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

    // --- Sky Map View & Display Settings ---

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

    // --- Catalog Search & Filter State ---

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

    // --- Observing Lists, Observation Logs & CSV Import/Export ---

    var showObservingLists by mutableStateOf(false)
    var pendingOpenManualListId by mutableStateOf<Long?>(null)
        private set
    var pendingOpenNightSession by mutableStateOf<String?>(null)
        private set
    var manualLists by mutableStateOf<List<ObservingListSummary>>(emptyList())
        private set
    var nightSessions by mutableStateOf<List<NightSessionSummary>>(emptyList())
        private set
    var observedKeys by mutableStateOf<Set<String>>(emptySet())
        private set
    var observedStaticCoLocatedKeys by mutableStateOf<Set<StaticCoLocatedKey>>(emptySet())
        private set
    var userDataVersion by mutableIntStateOf(0)
        private set

    private val _lastSelectedObservingListId =
        mutableLongStateOf(settingsManager.lastSelectedObservingListId)
    var lastSelectedObservingListId: Long
        get() = _lastSelectedObservingListId.longValue
        set(value) {
            _lastSelectedObservingListId.longValue = value
            settingsManager.lastSelectedObservingListId = value
        }

    private val _listsSortBy = mutableStateOf(settingsManager.listsSortBy)
    var listsSortBy: String
        get() = _listsSortBy.value
        set(value) {
            _listsSortBy.value = value
            settingsManager.listsSortBy = value
        }

    private val _listsVisibilityFilter = mutableStateOf(settingsManager.listsVisibilityFilter)
    var listsVisibilityFilter: String
        get() = _listsVisibilityFilter.value
        set(value) {
            _listsVisibilityFilter.value = value
            settingsManager.listsVisibilityFilter = value
        }

    private val _listsObservedFilter = mutableStateOf(settingsManager.listsObservedFilter)
    var listsObservedFilter: String
        get() = _listsObservedFilter.value
        set(value) {
            _listsObservedFilter.value = value
            settingsManager.listsObservedFilter = value
        }

    fun isObjectObserved(obj: CatalogObject): Boolean =
        UserDatabase.matchesObservedSet(obj, observedKeys, observedStaticCoLocatedKeys)

    fun openManualList(listId: Long) {
        pendingOpenManualListId = listId
        pendingOpenNightSession = null
        lastSelectedObservingListId = listId
        showCatalog = false
        showObservingLists = true
    }

    fun openNightSession(observingNight: String) {
        pendingOpenNightSession = observingNight
        pendingOpenManualListId = null
        showCatalog = false
        showObservingLists = true
    }

    fun consumePendingObservingListNavigation() {
        pendingOpenManualListId = null
        pendingOpenNightSession = null
    }

    // Serializes all userDb reads, writes, and teardown close() to prevent concurrent SQLite access.
    private val userDbMutex = Mutex()

    @Volatile
    private var isUserDbDisposed = false

    init {
        refreshUserDataAsync()
    }

    // Runs mutations under NonCancellable so a composable leaving composition mid-write cannot leave DB and UI snapshots out of sync.
    private suspend fun <T> runUserDbWrite(block: suspend () -> T): T =
        withContext(NonCancellable) {
            userDbMutex.withLock {
                block()
            }
        }

    fun refreshUserDataAsync() {
        viewModelScope.launch {
            refreshUserData()
        }
    }

    suspend fun refreshUserData() {
        userDbMutex.withLock {
            refreshUserDataLocked()
        }
    }

    private suspend fun refreshUserDataLocked() {
        if (isUserDbDisposed) return
        val (lists, sessions, allObs) = withContext(Dispatchers.IO) {
            Triple(
                userDb.getManualLists(),
                userDb.getNightSessions(),
                userDb.getAllObservations()
            )
        }
        manualLists = lists
        nightSessions = sessions
        val keys = HashSet<String>(allObs.size)
        val staticKeys = HashSet<StaticCoLocatedKey>(allObs.size)
        for (obs in allObs) {
            keys.add(obs.objectKey)
            if (obs.catalogLabel !in CatalogDatabase.DYNAMIC_CATALOG_LABELS) {
                staticKeys.add(StaticCoLocatedKey(obs.raDeg, obs.decDeg, obs.objectType))
            }
        }
        observedKeys = keys
        observedStaticCoLocatedKeys = staticKeys
        if (lists.none { it.id == lastSelectedObservingListId }) {
            lastSelectedObservingListId = lists.firstOrNull()?.id ?: -1L
        }
        userDataVersion++
    }

    suspend fun createManualList(name: String): Long = runUserDbWrite {
        val id = withContext(Dispatchers.IO) {
            userDb.createManualList(name)
        }
        if (id > 0L) {
            lastSelectedObservingListId = id
            refreshUserDataLocked()
        }
        id
    }

    suspend fun renameManualList(listId: Long, newName: String) {
        runUserDbWrite {
            withContext(Dispatchers.IO) {
                userDb.renameManualList(listId, newName)
            }
            refreshUserDataLocked()
        }
    }

    suspend fun deleteManualList(listId: Long) {
        runUserDbWrite {
            withContext(Dispatchers.IO) {
                userDb.deleteManualList(listId)
            }
            if (lastSelectedObservingListId == listId) {
                lastSelectedObservingListId = -1L
            }
            refreshUserDataLocked()
        }
    }

    fun reorderManualListsInMemory(reordered: List<ObservingListSummary>) {
        manualLists = reordered.mapIndexed { idx, list -> list.copy(sortOrder = idx) }
    }

    fun persistManualListOrderAsync(orderedListIds: List<Long>) {
        viewModelScope.launch {
            runUserDbWrite {
                withContext(Dispatchers.IO) {
                    userDb.reorderManualLists(orderedListIds)
                }
            }
        }
    }

    suspend fun addObjectToList(listId: Long, target: TargetRef): Boolean = runUserDbWrite {
        val added = withContext(Dispatchers.IO) {
            userDb.addObjectToList(listId, target)
        }
        lastSelectedObservingListId = listId
        refreshUserDataLocked()
        added
    }

    suspend fun removeObjectFromList(listId: Long, catalogLabel: String, catalogEntry: String) {
        runUserDbWrite {
            withContext(Dispatchers.IO) {
                userDb.removeObjectFromList(listId, catalogLabel, catalogEntry)
            }
            refreshUserDataLocked()
        }
    }

    fun reorderListItemsAsync(listId: Long, orderedItemIds: List<Long>) {
        viewModelScope.launch {
            runUserDbWrite {
                withContext(Dispatchers.IO) {
                    userDb.reorderListItems(listId, orderedItemIds)
                }
            }
        }
    }

    suspend fun getListIdsContainingObject(target: TargetRef): Set<Long> =
        userDbMutex.withLock {
            withContext(Dispatchers.IO) {
                userDb.getListIdsContainingObject(target.catalogLabel, target.catalogEntry)
            }
        }

    suspend fun getObservationsForNight(observingNight: String): List<ObservationRecord> =
        userDbMutex.withLock {
            withContext(Dispatchers.IO) {
                userDb.getObservationsForNight(observingNight)
            }
        }

    suspend fun getAllObservationsForExport(): List<ObservationRecord> =
        userDbMutex.withLock {
            withContext(Dispatchers.IO) {
                userDb.getAllObservations()
                    .sortedWith(compareBy<ObservationRecord> { it.observedAtMs }.thenBy { it.id })
            }
        }

    suspend fun getObservationsForTarget(
        target: TargetRef,
        coLocated: List<CatalogObject>
    ): List<ObservationRecord> = userDbMutex.withLock {
        withContext(Dispatchers.IO) {
            UserDatabase.filterObservationsForTarget(
                allObservations = userDb.getAllObservations(),
                target = target,
                coLocated = coLocated
            )
        }
    }

    suspend fun logObservation(
        target: TargetRef,
        observedAtMs: Long,
        latDeg: Double?,
        lonDeg: Double?,
        altDeg: Double?,
        azDeg: Double?,
        notes: String
    ): Long = runUserDbWrite {
        val nightKey = UserDatabase.computeObservingNightKey(observedAtMs)
        val id = withContext(Dispatchers.IO) {
            userDb.insertObservation(
                target = target,
                observedAtMs = observedAtMs,
                observingNight = nightKey,
                latDeg = latDeg,
                lonDeg = lonDeg,
                altDeg = altDeg,
                azDeg = azDeg,
                notes = notes.trim()
            )
        }
        refreshUserDataLocked()
        id
    }

    suspend fun updateObservationNotes(observationId: Long, notes: String) {
        runUserDbWrite {
            withContext(Dispatchers.IO) {
                userDb.updateObservationNotes(observationId, notes.trim())
            }
            refreshUserDataLocked()
        }
    }

    suspend fun deleteObservation(observationId: Long) {
        runUserDbWrite {
            withContext(Dispatchers.IO) {
                userDb.deleteObservation(observationId)
            }
            refreshUserDataLocked()
        }
    }

    suspend fun deleteObservations(observationIds: List<Long>) {
        if (observationIds.isEmpty()) return
        runUserDbWrite {
            withContext(Dispatchers.IO) {
                userDb.runInTransaction {
                    for (id in observationIds) {
                        userDb.deleteObservation(id)
                    }
                }
            }
            refreshUserDataLocked()
        }
    }

    data class CsvImportResult(
        val format: CsvImportFormat,
        val primaryListId: Long?,
        val latestImportedNight: String?,
        val listsTouched: Int,
        val objectsAdded: Int,
        val duplicateObjectsSkipped: Int,
        val observationsAdded: Int,
        val duplicateObservationsSkipped: Int
    )

    private fun epochSeconds(ms: Long): Long =
        if (ms >= 0L) ms / 1000L else (ms - 999L) / 1000L

    private fun addTargetsToList(listId: Long, refs: List<TargetRef>): Pair<Int, Int> {
        var added = 0
        var duplicates = 0
        for (ref in refs) {
            if (userDb.addObjectToList(listId, ref)) {
                added++
            } else {
                duplicates++
            }
        }
        return added to duplicates
    }

    suspend fun importParsedCsv(
        parsed: ParsedCsvImport,
        singleListName: String,
        lat: Double?,
        lon: Double?
    ): CsvImportResult = runUserDbWrite {
        val result = withContext(Dispatchers.IO) {
            val resolvedRows = resolveImportedRows(parsed.rows, lat, lon)
            var primaryListId: Long? = null
            var latestNight: String? = null
            var listsTouched = 0
            var objectsAdded = 0
            var duplicateObjectsSkipped = 0
            var observationsAdded = 0
            var duplicateObservationsSkipped = 0

            userDb.runInTransaction {
                when (parsed.format) {
                    CsvImportFormat.SINGLE_LIST -> {
                        val validRefs = resolvedRows.mapNotNull { it.second }
                        if (validRefs.isNotEmpty()) {
                            val listId = userDb.createManualList(singleListName)
                            if (listId > 0L) {
                                primaryListId = listId
                                listsTouched = 1
                                val (added, dups) = addTargetsToList(listId, validRefs)
                                objectsAdded += added
                                duplicateObjectsSkipped += dups
                            }
                        }
                    }

                    CsvImportFormat.FULL_LISTS -> {
                        val grouped = LinkedHashMap<String, MutableList<TargetRef>>()
                        for ((row, ref) in resolvedRows) {
                            val listName = row.listName?.trim().orEmpty()
                            if (listName.isNotEmpty() && ref != null) {
                                grouped.getOrPut(listName) { mutableListOf() }.add(ref)
                            }
                        }
                        val existingByName =
                            userDb.getManualLists().associateBy { it.name }.toMutableMap()
                        for ((listName, refs) in grouped) {
                            val existingId = existingByName[listName]?.id
                            val wasNewList = existingId == null
                            val listId = existingId
                                ?: userDb.createManualList(listName).also { newId ->
                                    if (newId > 0L) {
                                        existingByName[listName] = ObservingListSummary(
                                            id = newId,
                                            name = listName,
                                            itemCount = 0,
                                            sortOrder = existingByName.size
                                        )
                                    }
                                }
                            if (listId <= 0L) continue
                            val (added, dups) = addTargetsToList(listId, refs)
                            objectsAdded += added
                            duplicateObjectsSkipped += dups
                            if (wasNewList || added > 0) {
                                if (primaryListId == null) primaryListId = listId
                                listsTouched++
                            }
                        }
                    }

                    CsvImportFormat.OBSERVATIONS -> {
                        val seenKeys = userDb.getAllObservations()
                            .mapTo(HashSet()) {
                                Triple(it.objectKey, epochSeconds(it.observedAtMs), it.notes.trim())
                            }
                        for ((row, ref) in resolvedRows) {
                            val ts = row.observedAtMs ?: continue
                            if (ref == null) continue
                            val trimmedNotes = row.notes.trim()
                            val dedupKey = Triple(ref.objectKey, epochSeconds(ts), trimmedNotes)
                            if (!seenKeys.add(dedupKey)) {
                                duplicateObservationsSkipped++
                                continue
                            }
                            val nightKey =
                                row.observingNight ?: UserDatabase.computeObservingNightKey(ts)
                            val insertedId = userDb.insertObservation(
                                target = ref,
                                observedAtMs = ts,
                                observingNight = nightKey,
                                latDeg = row.latDeg,
                                lonDeg = row.lonDeg,
                                altDeg = row.altDeg,
                                azDeg = row.azDeg,
                                notes = trimmedNotes
                            )
                            if (insertedId > 0L) {
                                observationsAdded++
                                val prevNight = latestNight
                                if (prevNight == null || nightKey > prevNight) {
                                    latestNight = nightKey
                                }
                            }
                        }
                    }
                }
            }

            CsvImportResult(
                format = parsed.format,
                primaryListId = primaryListId,
                latestImportedNight = latestNight,
                listsTouched = listsTouched,
                objectsAdded = objectsAdded,
                duplicateObjectsSkipped = duplicateObjectsSkipped,
                observationsAdded = observationsAdded,
                duplicateObservationsSkipped = duplicateObservationsSkipped
            )
        }
        if (result.primaryListId != null) {
            lastSelectedObservingListId = result.primaryListId
        }
        if (result.listsTouched > 0 || result.observationsAdded > 0) {
            refreshUserDataLocked()
        }
        result
    }

    private fun resolveImportedRows(
        rows: List<ParsedCsvRow>,
        lat: Double?,
        lon: Double?
    ): List<Pair<ParsedCsvRow, TargetRef?>> {
        if (rows.isEmpty()) return emptyList()
        val parsedPairs = rows.map { row ->
            if (row.designation.isNotBlank()) {
                CatalogDatabase.parseCatalogDesignation(row.designation)
            } else {
                CatalogDatabase.parseCatalogDesignation(row.name)
            }
        }

        val staticQueryPairs = LinkedHashSet<Pair<String, String>>()
        var needsDynamic = false

        rows.forEachIndexed { idx, row ->
            val pair = parsedPairs[idx]
            if (pair != null) {
                if (pair.first in CatalogDatabase.DYNAMIC_CATALOG_LABELS) {
                    needsDynamic = true
                } else {
                    staticQueryPairs.add(pair)
                }
            } else {
                if (row.designation.isNotBlank()) {
                    staticQueryPairs.add("Str" to row.designation)
                    needsDynamic = true
                }
                if (row.name.isNotBlank()) {
                    staticQueryPairs.add("Str" to row.name)
                    needsDynamic = true
                }
            }
        }

        val staticByKey = HashMap<String, CatalogObject>()
        if (staticQueryPairs.isNotEmpty()) {
            for (obj in catalogDb.getObjectsByDesignations(staticQueryPairs.toList())) {
                staticByKey[UserDatabase.objectKey(obj.catalogLabel, obj.catalogEntry)] = obj
            }
        }

        val dynamicByKey = HashMap<String, CatalogObject>()
        val dynamicByNameLower = HashMap<String, CatalogObject>()
        if (needsDynamic) {
            val timeMs = effectiveSkyTimeMs()
            val sun = AstrometryUtils.getSun(timeMs)
            val allDynamic =
                listOf(sun) +
                        getPlanets(lat, lon, timeMs) +
                        getAsteroids(lat, lon, timeMs) +
                        getComets(lat, lon, timeMs)
            for (obj in allDynamic) {
                dynamicByKey[UserDatabase.objectKey(obj.catalogLabel, obj.catalogEntry)] = obj
                dynamicByNameLower.getOrPut(obj.catalogEntry.lowercase()) { obj }
                dynamicByNameLower.getOrPut(obj.displayName.lowercase()) { obj }
            }
        }

        return rows.mapIndexed { idx, row ->
            val pair = parsedPairs[idx]
            val matchedObj = when {
                pair != null -> {
                    val key = UserDatabase.objectKey(pair.first, pair.second)
                    staticByKey[key]
                        ?: dynamicByKey[key]
                        ?: if (pair.first in CatalogDatabase.DYNAMIC_CATALOG_LABELS) {
                            dynamicByNameLower[pair.second.lowercase()]
                        } else {
                            null
                        }
                }

                else -> {
                    val desLower = row.designation.lowercase()
                    val nameLower = row.name.lowercase()
                    dynamicByNameLower[desLower]
                        ?: dynamicByNameLower[nameLower]
                        ?: staticByKey[UserDatabase.objectKey("Str", row.designation)]
                        ?: staticByKey[UserDatabase.objectKey("Str", row.name)]
                }
            }

            val ref = when {
                matchedObj != null -> UserDatabase.targetRefFrom(
                    catalogObject = matchedObj,
                    coords = matchedObj.ra to matchedObj.dec,
                    fallbackTitle = matchedObj.displayName
                )

                row.raDeg != null && row.decDeg != null -> {
                    val fallbackTitle = row.name.ifBlank {
                        row.designation.ifBlank {
                            "${pureFormat("%.4f", row.raDeg)}, ${pureFormat("%.4f", row.decDeg)}"
                        }
                    }
                    val preservedDynamicLabel = when (row.objectType) {
                        "Sun" -> "Solar System"
                        "Planet", "Moon" -> "Planet"
                        "Asteroid", "Comet" -> row.objectType
                        else -> null
                    }
                    when {
                        pair != null -> TargetRef(
                            catalogLabel = pair.first,
                            catalogEntry = pair.second,
                            name = fallbackTitle,
                            objectType = row.objectType.ifBlank { pair.first },
                            raDeg = row.raDeg,
                            decDeg = row.decDeg
                        )

                        preservedDynamicLabel != null &&
                                (row.designation.isNotBlank() || row.name.isNotBlank()) -> TargetRef(
                            catalogLabel = preservedDynamicLabel,
                            catalogEntry = row.designation.ifBlank { row.name },
                            name = fallbackTitle,
                            objectType = row.objectType,
                            raDeg = row.raDeg,
                            decDeg = row.decDeg
                        )

                        else -> UserDatabase.targetRefFrom(
                            catalogObject = null,
                            coords = row.raDeg to row.decDeg,
                            fallbackTitle = fallbackTitle
                        )
                    }
                }

                else -> null
            }
            row to ref
        }
    }

    private fun toResolvedListEntry(
        item: ObservingListItemRecord,
        resolvedMap: Map<String, CatalogObject>
    ): ResolvedListEntry {
        val obj = resolvedMap[item.objectKey] ?: syntheticCatalogObject(
            id = -item.id.toInt().coerceAtLeast(1),
            catalogLabel = item.catalogLabel,
            catalogEntry = item.catalogEntry,
            name = item.fallbackName,
            objectType = item.objectType,
            raDeg = item.raDeg,
            decDeg = item.decDeg
        )
        return ResolvedListEntry(
            catalogObject = obj,
            listItemId = item.id
        )
    }

    suspend fun resolveManualListEntries(
        listId: Long,
        lat: Double?,
        lon: Double?
    ): List<ResolvedListEntry> = userDbMutex.withLock {
        withContext(Dispatchers.IO) {
            val rawItems = userDb.getListItems(listId)
            if (rawItems.isEmpty()) return@withContext emptyList()
            val resolvedMap = resolveCatalogObjectsMap(
                refs = rawItems.map { it.catalogLabel to it.catalogEntry },
                lat = lat,
                lon = lon
            )
            rawItems.map { toResolvedListEntry(it, resolvedMap) }
        }
    }

    suspend fun resolveAllManualListsForExport(
        lat: Double?,
        lon: Double?
    ): List<Pair<String, List<ResolvedListEntry>>> = userDbMutex.withLock {
        withContext(Dispatchers.IO) {
            val lists = userDb.getManualLists()
            if (lists.isEmpty()) return@withContext emptyList()
            val itemsByList = lists.map { list -> list.name to userDb.getListItems(list.id) }
            val allRefs = itemsByList.flatMap { (_, items) ->
                items.map { it.catalogLabel to it.catalogEntry }
            }
            if (allRefs.isEmpty()) return@withContext emptyList()
            val resolvedMap = resolveCatalogObjectsMap(allRefs, lat, lon)
            itemsByList.mapNotNull { (listName, items) ->
                if (items.isEmpty()) {
                    null
                } else {
                    listName to items.map { toResolvedListEntry(it, resolvedMap) }
                }
            }
        }
    }

    suspend fun resolveNightSessionEntries(
        observingNight: String,
        lat: Double?,
        lon: Double?
    ): List<ResolvedListEntry> = userDbMutex.withLock {
        withContext(Dispatchers.IO) {
            val obs = userDb.getObservationsForNight(observingNight)
            if (obs.isEmpty()) return@withContext emptyList()
            val grouped = LinkedHashMap<String, MutableList<ObservationRecord>>()
            for (record in obs) {
                grouped.getOrPut(record.objectKey) { mutableListOf() }.add(record)
            }
            val refs = grouped.values.map { list ->
                val first = list.first()
                first.catalogLabel to first.catalogEntry
            }
            val resolvedMap = resolveCatalogObjectsMap(refs, lat, lon)
            grouped.entries.map { (key, records) ->
                val latest = records.last()
                val preview = records.lastOrNull { it.notes.isNotBlank() }?.notes
                val obj = resolvedMap[key] ?: syntheticCatalogObject(
                    id = -latest.id.toInt().coerceAtLeast(1),
                    catalogLabel = latest.catalogLabel,
                    catalogEntry = latest.catalogEntry,
                    name = latest.targetName,
                    objectType = latest.objectType,
                    raDeg = latest.raDeg,
                    decDeg = latest.decDeg
                )
                ResolvedListEntry(
                    catalogObject = obj,
                    observationTimeMs = latest.observedAtMs,
                    observationNotePreview = preview,
                    observationCount = records.size,
                    observationIds = records.map { it.id }
                )
            }.sortedBy { it.observationTimeMs ?: 0L }
        }
    }

    private fun resolveCatalogObjectsMap(
        refs: List<Pair<String, String>>,
        lat: Double?,
        lon: Double?
    ): Map<String, CatalogObject> {
        val result = HashMap<String, CatalogObject>(refs.size)
        val staticPairs = mutableListOf<Pair<String, String>>()
        var needsPlanets = false
        var needsAsteroids = false
        var needsComets = false
        for ((label, entry) in refs) {
            when (label) {
                "Planet", "Solar System" -> needsPlanets = true
                "Asteroid" -> needsAsteroids = true
                "Comet" -> needsComets = true
                UserDatabase.CUSTOM_CATALOG_LABEL -> {}
                else -> staticPairs.add(label to entry)
            }
        }
        if (staticPairs.isNotEmpty()) {
            for (obj in catalogDb.getObjectsByDesignations(staticPairs)) {
                result[UserDatabase.objectKey(obj.catalogLabel, obj.catalogEntry)] = obj
            }
        }
        val timeMs = effectiveSkyTimeMs()
        if (needsPlanets) {
            val sun = AstrometryUtils.getSun(timeMs)
            result[UserDatabase.objectKey(sun.catalogLabel, sun.catalogEntry)] = sun
            for (obj in getPlanets(lat, lon, timeMs)) {
                result[UserDatabase.objectKey(obj.catalogLabel, obj.catalogEntry)] = obj
            }
        }
        if (needsAsteroids) {
            for (obj in getAsteroids(lat, lon, timeMs)) {
                result[UserDatabase.objectKey(obj.catalogLabel, obj.catalogEntry)] = obj
            }
        }
        if (needsComets) {
            for (obj in getComets(lat, lon, timeMs)) {
                result[UserDatabase.objectKey(obj.catalogLabel, obj.catalogEntry)] = obj
            }
        }
        return result
    }

    private fun syntheticCatalogObject(
        id: Int,
        catalogLabel: String,
        catalogEntry: String,
        name: String,
        objectType: String,
        raDeg: Double,
        decDeg: Double
    ): CatalogObject = CatalogObject(
        id = id,
        catalogLabel = catalogLabel,
        catalogEntry = catalogEntry,
        ra = raDeg,
        dec = decDeg,
        constellation = null,
        objectType = objectType,
        broadCategory = objectType,
        magnitude = null,
        surfaceBrightness = null,
        angularSize = null,
        commonName = name,
        description = null,
        copyright = null
    )

    // --- Camera Tuning, Live Frame & Low-Power State ---

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
    var latestFrameAspect by mutableDoubleStateOf(DEFAULT_CAMERA_ASPECT)
        private set
    var isCameraStuck by mutableStateOf(false)

    @Volatile
    private var lastFrameReceivedRealtime = 0L

    private val _isLowPowerMode = mutableStateOf(false)

    // Volatile mirror for the IO frameJob; exiting low-power resets the stuck-camera deadline.
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

    // Serializes all native/UVC engine start and stop transitions across coroutines.
    private val engineLifecycleMutex = Mutex()
    private var engineStartJob: Job? = null

    fun getAvailableInternalCameras(): String {
        return engine.getAvailableInternalCameras()
    }

    // --- Engine Polling, Telemetry & Stuck-Camera Watchdog ---

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
                if (isAutoEngineSession) {
                    settingsManager.manualFocus = calibratedFocus
                }
            }
        }
        snapshot.currentSigma?.let { currentSigmaVal ->
            if (isAutoEngineSession) {
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
                        isAuto = isAutoEngineSession,
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
                                    val fw = frame.imageBitmap.width
                                    val fh = frame.imageBitmap.height
                                    if (fw > 0 && fh > 0) {
                                        latestFrameAspect = fw.toDouble() / fh.toDouble()
                                    }
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
                            // Verify baseline is unchanged on Main so a concurrent frame or dialog dismissal is never clobbered.
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
        isAutoEngineSession = false
        isLowPowerMode = false
        currentZoom = 1.0f
        skyMapCameraLocked = true
        engineStats = EngineStats()
        latestFrame = null
        latestFrameAspect = DEFAULT_CAMERA_ASPECT
        isCameraStuck = false
        lastFrameTimestamp = 0L
        lastCalibratedFocus = null
    }

    private fun prepareStartUiState(lat: Double, lon: Double, initialZoom: Float) {
        resetSimulatedTime()
        latestFrame = null
        latestFrameAspect = DEFAULT_CAMERA_ASPECT
        lastFrameTimestamp = 0L
        isCameraStuck = false
        lastFrameReceivedRealtime = getElapsedRealtimeMs()
        lastCalibratedFocus = null
        currentLat = lat
        currentLon = lon
        currentZoom = initialZoom
    }

    // --- Native & External UVC Engine Lifecycle ---

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
                settingsManager.autoZoomRatio.coerceIn(1.0f, 2.0f)
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
                isAutoEngineSession = isAutoSettings
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
        // UNDISPATCHED enters the try/finally synchronously so wrapper ownership is always released even if cancelled before dispatch.
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

                    isAutoEngineSession = isAutoSettings
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

    // --- Realtime Camera & Processing Controls ---

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
                var hotPixel: Int? = null
                if (isAuto) {
                    val g = engine.getNativeCameraGain()
                    if (!g.isNaN() && g > 0) gain = g
                    val exp = engine.getNativeCameraExposure()
                    if (exp > 0) exposure = exp.toFloat()
                    noiseReduction = engine.getNativeCameraNoiseReduction()
                    hotPixel = engine.getNativeCameraHotPixelMode()
                }
                RealtimeSettingsSnapshot(
                    gain = gain,
                    exposure = exposure,
                    noiseReduction = noiseReduction,
                    hotPixelMode = hotPixel
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
        val isAuto = isAutoEngineSession
        val wrapper = uvcWrapper
        val (snapshot, gainAndExp) = withContext(Dispatchers.IO) {
            val realtime = readRealtimeSettings(
                mode = mode,
                isAuto = isAuto,
                wrapper = wrapper
            )
            val nativeRanges = if (mode != EngineMode.UVC) {
                engine.getNativeCameraGainRange() to engine.getNativeCameraExposure()
            } else {
                null
            }
            realtime to nativeRanges
        }
        if (isEngineRunning && engineMode == mode) {
            if (gainAndExp != null) {
                val (range, exposureUs) = gainAndExp
                if (range != null && range.size >= 2) {
                    minAnalogGain = range[0]
                    maxAnalogGain = range[1].coerceAtLeast(minAnalogGain + 1f)
                } else {
                    minAnalogGain = 100f
                    maxAnalogGain = 3200f
                }
                if (exposureUs > 0) {
                    currentExposure = exposureUs.toFloat().coerceIn(capabilities.exposureRange)
                }
            }
            applyRealtimeSettings(snapshot)
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

    fun setHfrEnabled(enabled: Boolean) {
        engine.setHfrEnabled(enabled)
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

    // --- GoTo Target, Telescope Sync & Alignment ---

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

    // --- Solar System Ephemeris (Planets, Asteroids, Comets) ---

    fun getPlanets(
        latValue: Double? = currentLat,
        lonValue: Double? = currentLon,
        timeMs: Long = effectiveSkyTimeMs()
    ): List<CatalogObject> {
        val lat = latValue ?: return emptyList()
        val lon = lonValue ?: return emptyList()
        if (!isValidCoordinate(lat, lon)) return emptyList()

        val bspPath = catalogDb.getBspPath()

        val json = engine.getPlanets(bspPath, timeMs, lat, lon)
        if (json.isBlank() || json == "[]") return emptyList()

        return parseCatalogObjectsFromJson(json)
    }

    fun getAsteroids(
        latValue: Double? = currentLat,
        lonValue: Double? = currentLon,
        timeMs: Long = effectiveSkyTimeMs()
    ): List<CatalogObject> {
        val lat = latValue ?: return emptyList()
        val lon = lonValue ?: return emptyList()
        if (!isValidCoordinate(lat, lon)) return emptyList()

        val bspPath = catalogDb.getBspPath()
        val asteroidsJson = catalogDb.getAsteroidsJson()

        val json = engine.getAsteroids(bspPath, timeMs, lat, lon, asteroidsJson)
        if (json.isBlank() || json == "[]") return emptyList()

        return parseCatalogObjectsFromJson(json)
    }

    fun getComets(
        latValue: Double? = currentLat,
        lonValue: Double? = currentLon,
        timeMs: Long = effectiveSkyTimeMs()
    ): List<CatalogObject> {
        val lat = latValue ?: return emptyList()
        val lon = lonValue ?: return emptyList()
        if (!isValidCoordinate(lat, lon)) return emptyList()

        val bspPath = catalogDb.getBspPath()
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

    // --- Engine Shutdown & ViewModel Disposal ---

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

    /**
     * Cancels active jobs, persists any pending debounced FOV write, closes HiPS tile managers,
     * and defers SQLite shutdown on `Dispatchers.IO` (serializing `userDb.close()` behind
     * `userDbMutex` so in-flight `NonCancellable` writes finish cleanly).
     */
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
        isUserDbDisposed = true
        CoroutineScope(Dispatchers.IO).launch {
            catalogDb.closeDatabase()
            userDbMutex.withLock {
                userDb.close()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        dispose()
    }

    companion object {
        const val INVALID_COORD = 200.0
        const val DEFAULT_CAMERA_ASPECT = 4.0 / 3.0
        const val CAMERA_STUCK_TIMEOUT_MS = 15000L
        const val SCREEN_UPDATE_RATE_HZ = 10
        const val SCREEN_UPDATE_INTERVAL_MS = 1000L / SCREEN_UPDATE_RATE_HZ

        fun isValidCoordinate(lat: Double?, lon: Double?): Boolean {
            if (lat == null || lon == null) return false
            return lat in -90.0..90.0 && lon in -180.0..180.0
        }
    }
}
