// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import com.oakamil.olive.HiPSDownloadManager.HQ_FLUSH_BATCH_SIZE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

sealed interface HiPSFailureReason {
    data class TileDownloadFailed(val missingCount: Int) : HiPSFailureReason
    data object PackAssemblyFailed : HiPSFailureReason
    data object StorageWriteFailed : HiPSFailureReason
    data object StorageBackendUnavailable : HiPSFailureReason
}

sealed interface HiPSDownloadState {
    data object Idle : HiPSDownloadState

    data class Downloading(
        val completedTiles: Int,
        val totalTiles: Int,
        val currentOrder: Int,
        val downloadedBytes: Long
    ) : HiPSDownloadState

    data class Packing(
        val packedTiles: Int,
        val totalTiles: Int
    ) : HiPSDownloadState

    data class Failed(
        val completedTiles: Int,
        val totalTiles: Int,
        val reason: HiPSFailureReason
    ) : HiPSDownloadState
}

sealed interface HiPSHqDownloadState {
    data object Idle : HiPSHqDownloadState

    data class Downloading(
        val targetId: String,
        val isBulkCatalog: Boolean,
        val catalogTitle: String?,
        val completedTiles: Int,
        val totalTiles: Int,
        val downloadedBytes: Long
    ) : HiPSHqDownloadState

    data class Failed(
        val targetId: String,
        val isBulkCatalog: Boolean,
        val catalogTitle: String?,
        val completedTiles: Int,
        val totalTiles: Int,
        val reason: HiPSFailureReason
    ) : HiPSHqDownloadState
}

class HqDownloadOutcome(
    val completedTiles: Int,
    val totalTiles: Int,
    val storageFailed: Boolean
)

/**
 * Pure KMP multi-worker downloader and `OLVHIPS1` / `OLVHQIDX` assembler for DSS2 Color
 * (`512x512` JPEG tiles).
 *
 * Preserves staged/committed tiles across network interruptions and cancellations so subsequent
 * downloads resume automatically from where the user left off.
 */
object HiPSDownloadManager {
    private const val WORKER_COUNT = 6
    private const val HQ_FLUSH_BATCH_SIZE = 25

    // Measured per-tile JPEG payload averages on skies.esac.esa.int/DSSColor indexed by order (0..8)
    private val ORDER_AVG_BYTES = longArrayOf(
        48_500L,
        43_100L,
        42_900L,
        43_217L,
        55_518L,
        110_000L,
        138_000L,
        148_000L,
        105_000L
    )

    private val MIRROR_BASE_URLS = listOf(
        "https://skies.esac.esa.int/DSSColor",
        "https://casda.csiro.au/hips/DSS2/color"
    )

    /** Maps a dense `OLVHIPS1` TOC index (`0 until TOTAL_TILES`) back to `(order, ipix)`. */
    fun globalIndexToOrderIpix(globalIdx: Int, maxOrder: Int = Healpix.MAX_ORDER): Pair<Int, Int> {
        for (order in Healpix.MIN_ORDER until maxOrder) {
            val nextOffset = Healpix.orderOffset(order + 1)
            if (globalIdx < nextOffset) {
                return order to (globalIdx - Healpix.orderOffset(order))
            }
        }
        return maxOrder to (globalIdx - Healpix.orderOffset(maxOrder))
    }

    /** Ceiling SI megabytes (`1 MB = 1,000,000 bytes`, matching Android/iOS OS storage UI) so live progress never exceeds the estimate. */
    fun estimatedDownloadMb(maxOrder: Int = Healpix.MAX_ORDER): Int {
        var bytes = HiPSPackToc.HEADER_BYTES.toLong()
        for (order in Healpix.MIN_ORDER..maxOrder) {
            val count = Healpix.tileCountForOrder(order).toLong()
            val perTile = if (order < ORDER_AVG_BYTES.size) {
                ORDER_AVG_BYTES[order]
            } else {
                ORDER_AVG_BYTES.last()
            }
            bytes += count * (perTile + HiPSPackToc.TOC_ENTRY_BYTES.toLong())
        }
        return ((bytes + 999_999L) / 1_000_000L).coerceAtLeast(1L).toInt()
    }

    /** Estimated total bytes (payload + 16B index entry) for a set of packed [Healpix.packTileKey]s. */
    fun estimatedTilesBytes(packedKeys: IntArray): Long {
        var bytes = 0L
        for (key in packedKeys) {
            val order = Healpix.tileKeyOrder(key)
            val perTile = if (order in ORDER_AVG_BYTES.indices) {
                ORDER_AVG_BYTES[order]
            } else {
                ORDER_AVG_BYTES.last()
            }
            bytes += perTile + HiPSHqIndex.ENTRY_BYTES.toLong()
        }
        return bytes
    }

    /** Formats a byte count in decimal SI megabytes (`1,000,000 B`, tenths precision, unitless). */
    fun formatMbValue(bytes: Long): String {
        if (bytes <= 0L) return "0.0"
        val tenths = ((bytes + 50_000L) / 100_000L).coerceAtLeast(1L)
        return "${tenths / 10L}.${tenths % 10L}"
    }

    /** Formats a non-negative tile count with thousands separators (`1020` -> `"1,020"`). */
    fun formatCount(value: Int): String {
        val s = value.toString()
        if (s.length <= 3) return s
        return buildString(s.length + s.length / 3) {
            val rem = s.length % 3
            for (i in s.indices) {
                if (i > 0 && (i - rem) % 3 == 0) append(',')
                append(s[i])
            }
        }
    }

    private fun tileUrl(baseUrl: String, order: Int, ipix: Int): String {
        val trimmed = baseUrl.trimEnd('/')
        val dir = (ipix / 10_000) * 10_000
        return "$trimmed/Norder$order/Dir$dir/Npix$ipix.jpg"
    }

    /** Rejects truncated payloads and HTML error pages by checking size and JPEG SOI (`FF D8 FF`). */
    private fun isValidJpeg(bytes: ByteArray): Boolean {
        if (bytes.size <= 128) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val b2 = bytes[2].toInt() and 0xFF
        return b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF
    }

    /**
     * Fetches a tile with sticky mirror failover: starts at the last known good mirror, rotates
     * through fallbacks (plus one final retry of the starting mirror), and promotes whichever
     * mirror succeeds so concurrent workers switch over.
     */
    private suspend fun fetchValidTileJpeg(
        backend: HiPSLocalBackend,
        mirrorBaseUrls: List<String>,
        order: Int,
        ipix: Int,
        stateMutex: Mutex,
        getPreferredMirror: () -> Int,
        setPreferredMirror: (Int) -> Unit
    ): ByteArray? {
        val startMirror = stateMutex.withLock { getPreferredMirror() }
        for (attempt in 0 until (mirrorBaseUrls.size + 1)) {
            val mirrorIdx = (startMirror + attempt) % mirrorBaseUrls.size
            val url = tileUrl(mirrorBaseUrls[mirrorIdx], order, ipix)
            val candidate = backend.fetchUrlBytes(url)
            if (candidate != null && isValidJpeg(candidate)) {
                if (mirrorIdx != startMirror) {
                    stateMutex.withLock { setPreferredMirror(mirrorIdx) }
                }
                return candidate
            }
            if (attempt == 0) {
                delay(200)
            }
        }
        return null
    }

    /**
     * Downloads all missing base-pack tiles (`Orders 0..MAX_ORDER`) into staging and atomically
     * assembles the `OLVHIPS1` `.pack` file, emitting throttled state updates on [callbackDispatcher].
     */
    suspend fun downloadDss2(
        backend: HiPSLocalBackend,
        mirrorBaseUrls: List<String> = MIRROR_BASE_URLS,
        callbackDispatcher: CoroutineDispatcher = Dispatchers.Main,
        onStateChanged: (HiPSDownloadState) -> Unit
    ): Boolean {
        require(mirrorBaseUrls.isNotEmpty()) { "At least one HiPS mirror URL is required" }

        suspend fun emitState(state: HiPSDownloadState) {
            withContext(callbackDispatcher) {
                onStateChanged(state)
            }
        }

        val totalTiles = Healpix.TOTAL_TILES
        val stagedStats = withContext(Dispatchers.IO) {
            backend.getStagedStats()
        }
        val missingIndices = ArrayList<Int>(totalTiles)
        for (i in 0 until totalTiles) {
            if (!stagedStats.flags[i]) {
                missingIndices.add(i)
            }
        }

        var completedCount = totalTiles - missingIndices.size
        var totalBytes = stagedStats.totalBytes
        val initialOrder = if (missingIndices.isNotEmpty()) {
            globalIndexToOrderIpix(missingIndices.first()).first
        } else {
            Healpix.MAX_ORDER
        }
        emitState(
            HiPSDownloadState.Downloading(
                completedTiles = completedCount,
                totalTiles = totalTiles,
                currentOrder = initialOrder,
                downloadedBytes = totalBytes
            )
        )

        if (missingIndices.isNotEmpty()) {
            val stateMutex = Mutex()
            var nextQueuePos = 0
            var preferredMirrorIdx = 0
            val progressClock = TimeSource.Monotonic.markNow()
            var lastReportMs = -1000L

            withContext(Dispatchers.IO) {
                val workerTotal = minOf(WORKER_COUNT, missingIndices.size)
                coroutineScope {
                    repeat(workerTotal) {
                        launch {
                            while (true) {
                                val gIdx = stateMutex.withLock {
                                    if (nextQueuePos >= missingIndices.size) -1
                                    else missingIndices[nextQueuePos++]
                                }
                                if (gIdx < 0) break

                                val (order, ipix) = globalIndexToOrderIpix(gIdx)
                                val payload = fetchValidTileJpeg(
                                    backend = backend,
                                    mirrorBaseUrls = mirrorBaseUrls,
                                    order = order,
                                    ipix = ipix,
                                    stateMutex = stateMutex,
                                    getPreferredMirror = { preferredMirrorIdx },
                                    setPreferredMirror = { preferredMirrorIdx = it }
                                )
                                if (payload != null && backend.writeStagedTile(gIdx, payload)) {
                                    val reportState = stateMutex.withLock {
                                        completedCount++
                                        totalBytes += payload.size.toLong()
                                        val nowMs = progressClock.elapsedNow().inWholeMilliseconds
                                        if ((nowMs - lastReportMs >= 100L) || (completedCount == totalTiles)) {
                                            lastReportMs = nowMs
                                            HiPSDownloadState.Downloading(
                                                completedTiles = completedCount,
                                                totalTiles = totalTiles,
                                                currentOrder = order,
                                                downloadedBytes = totalBytes
                                            )
                                        } else {
                                            null
                                        }
                                    }
                                    if (reportState != null) {
                                        emitState(reportState)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (completedCount < totalTiles) {
            emitState(
                HiPSDownloadState.Failed(
                    completedTiles = completedCount,
                    totalTiles = totalTiles,
                    reason = HiPSFailureReason.TileDownloadFailed(totalTiles - completedCount)
                )
            )
            return false
        }

        emitState(HiPSDownloadState.Packing(0, totalTiles))

        val packClock = TimeSource.Monotonic.markNow()
        val packProgressChannel = Channel<Int>(Channel.CONFLATED)
        val packed = try {
            coroutineScope {
                val reporterJob = launch(callbackDispatcher) {
                    for (count in packProgressChannel) {
                        onStateChanged(HiPSDownloadState.Packing(count, totalTiles))
                    }
                }
                try {
                    withContext(Dispatchers.IO) {
                        var lastPackReportMs = -1000L
                        backend.assemblePackFromStaging { count ->
                            val nowMs = packClock.elapsedNow().inWholeMilliseconds
                            if (nowMs - lastPackReportMs >= 80L || count == totalTiles) {
                                @Suppress("AssignedValueIsNeverRead")
                                lastPackReportMs = nowMs
                                packProgressChannel.trySend(count)
                            }
                        }
                    }
                } finally {
                    packProgressChannel.close()
                    reporterJob.join()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("HiPSDownload", "Pack assembly error", e)
            false
        }

        if (!packed) {
            emitState(
                HiPSDownloadState.Failed(
                    completedTiles = completedCount,
                    totalTiles = totalTiles,
                    reason = HiPSFailureReason.PackAssemblyFailed
                )
            )
            return false
        }

        emitState(HiPSDownloadState.Idle)
        return true
    }

    /**
     * Multi-worker downloader for sparse HQ tiles (`Orders 0..HQ_MAX_ORDER`).
     * Incrementally appends batches of [HQ_FLUSH_BATCH_SIZE] tiles to `hq/dss2_hq.dat` + `dss2_hq.idx`
     * and flushes any already-downloaded remainder on completion or cancellation.
     */
    suspend fun downloadHqTiles(
        backend: HiPSLocalBackend,
        missingKeys: IntArray,
        initialIndex: HiPSHqIndex,
        mirrorBaseUrls: List<String> = MIRROR_BASE_URLS,
        callbackDispatcher: CoroutineDispatcher = Dispatchers.Main,
        flushOnExit: () -> Boolean = { true },
        onProgress: (completedTiles: Int, totalTiles: Int, downloadedBytes: Long) -> Unit,
        onBatchCommitted: (updatedIndex: HiPSHqIndex, storageBytes: Long) -> Unit
    ): HqDownloadOutcome {
        require(mirrorBaseUrls.isNotEmpty()) { "At least one HiPS mirror URL is required" }
        val totalTiles = missingKeys.size
        if (totalTiles == 0) {
            return HqDownloadOutcome(0, 0, false)
        }

        // Lock order: commitMutex -> stateMutex (never reversed) to keep disk commits atomic without stalling workers.
        val stateMutex = Mutex()
        val commitMutex = Mutex()
        var nextQueuePos = 0
        var preferredMirrorIdx = 0
        var fetchedCount = 0
        var committedCount = 0
        var downloadedBytes = 0L
        var storageFailed = false
        var committedIndex = initialIndex
        val pendingItems = ArrayList<Pair<Int, ByteArray>>(HQ_FLUSH_BATCH_SIZE + WORKER_COUNT)
        val progressClock = TimeSource.Monotonic.markNow()
        var lastReportMs = -1000L

        withContext(callbackDispatcher) {
            onProgress(0, totalTiles, 0L)
        }

        suspend fun flushPending(force: Boolean) {
            commitMutex.withLock {
                val batch: List<Pair<Int, ByteArray>>
                val baseIndex: HiPSHqIndex
                stateMutex.withLock {
                    if (storageFailed || pendingItems.isEmpty() || (!force && pendingItems.size < HQ_FLUSH_BATCH_SIZE)) {
                        return
                    }
                    batch = ArrayList(pendingItems)
                    pendingItems.clear()
                    baseIndex = committedIndex
                }
                val prepared = HiPSHqIndex.prepareAppend(baseIndex, batch) ?: return
                val ok = backend.appendHqBatch(
                    expectedDataEnd = baseIndex.dataEndOffset,
                    payloads = prepared.payloadsToWrite,
                    newIndexBytes = prepared.indexBytes
                )
                if (!ok) {
                    stateMutex.withLock { storageFailed = true }
                    return
                }
                val diskBytes = backend.hqStorageSizeBytes()
                stateMutex.withLock {
                    @Suppress("AssignedValueIsNeverRead")
                    committedIndex = prepared.updatedIndex
                    committedCount += prepared.payloadsToWrite.size
                }
                withContext(callbackDispatcher) {
                    onBatchCommitted(prepared.updatedIndex, diskBytes)
                }
            }
        }

        try {
            withContext(Dispatchers.IO) {
                val workerTotal = minOf(WORKER_COUNT, totalTiles)
                coroutineScope {
                    repeat(workerTotal) {
                        launch {
                            while (true) {
                                val packedKey = stateMutex.withLock {
                                    if (storageFailed || nextQueuePos >= totalTiles) -1
                                    else missingKeys[nextQueuePos++]
                                }
                                if (packedKey < 0) break

                                val order = Healpix.tileKeyOrder(packedKey)
                                val ipix = Healpix.tileKeyIpix(packedKey)
                                val payload = fetchValidTileJpeg(
                                    backend = backend,
                                    mirrorBaseUrls = mirrorBaseUrls,
                                    order = order,
                                    ipix = ipix,
                                    stateMutex = stateMutex,
                                    getPreferredMirror = { preferredMirrorIdx },
                                    setPreferredMirror = { preferredMirrorIdx = it }
                                ) ?: continue

                                var shouldFlush = false
                                var reportSnap: Pair<Int, Long>? = null
                                stateMutex.withLock {
                                    if (!storageFailed) {
                                        pendingItems.add(packedKey to payload)
                                        fetchedCount++
                                        downloadedBytes += payload.size.toLong()
                                        shouldFlush = pendingItems.size >= HQ_FLUSH_BATCH_SIZE
                                        val nowMs = progressClock.elapsedNow().inWholeMilliseconds
                                        if ((nowMs - lastReportMs >= 100L) || (fetchedCount == totalTiles)) {
                                            lastReportMs = nowMs
                                            reportSnap = fetchedCount to downloadedBytes
                                        }
                                    }
                                }
                                reportSnap?.let { (done, bytes) ->
                                    withContext(callbackDispatcher) {
                                        onProgress(done, totalTiles, bytes)
                                    }
                                }
                                if (shouldFlush) {
                                    flushPending(force = false)
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            if (flushOnExit()) {
                withContext(NonCancellable + Dispatchers.IO) {
                    flushPending(force = true)
                }
            }
        }

        return stateMutex.withLock {
            HqDownloadOutcome(
                completedTiles = committedCount,
                totalTiles = totalTiles,
                storageFailed = storageFailed
            )
        }
    }
}
