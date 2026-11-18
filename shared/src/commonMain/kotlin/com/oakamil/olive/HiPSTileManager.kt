// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Resolved texture and ancestor order for rendering a tile. */
data class ResolvedHiPSTile(
    val loadedOrder: Int,
    val bitmap: ImageBitmap
)

data class HiPSPackToc(
    val maxOrder: Int,
    val offsets: LongArray,
    val lengths: IntArray
) {
    val tileCount: Int
        get() = offsets.size

    fun isValid(fileLength: Long, expectedMaxOrder: Int = Healpix.MAX_ORDER): Boolean {
        if (maxOrder != expectedMaxOrder) return false
        val dataStart = HEADER_BYTES.toLong() + tileCount.toLong() * TOC_ENTRY_BYTES
        if (fileLength < dataStart) return false
        var anyPositive = false
        for (i in 0 until tileCount) {
            val len = lengths[i]
            if (len <= 0) continue
            anyPositive = true
            val off = offsets[i]
            if (off < dataStart || off > fileLength - len.toLong()) return false
        }
        return anyPositive
    }

    companion object {
        private const val MAGIC = "OLVHIPS1"
        const val HEADER_BYTES = 28
        const val TOC_ENTRY_BYTES = 16
        const val TILE_FORMAT_JPEG = 2

        fun buildHeaderBytes(
            maxOrder: Int = Healpix.MAX_ORDER,
            tileWidth: Int = 512,
            tileFormat: Int = TILE_FORMAT_JPEG,
            tileCount: Int = Healpix.totalTilesUpTo(maxOrder)
        ): ByteArray {
            val out = ByteArray(HEADER_BYTES)
            for (i in 0 until 8) {
                out[i] = MAGIC[i].code.toByte()
            }
            putU16Le(out, 8, 1) // version
            putU16Le(out, 10, maxOrder)
            putU16Le(out, 12, tileWidth)
            putU16Le(out, 14, tileFormat)
            // bytes 16..19 flags = 0 (ICRS/Equatorial NESTED)
            putU32Le(out, 20, tileCount)
            // bytes 24..27 reserved = 0
            return out
        }

        fun parseHeader(headerBytes: ByteArray): Int? {
            if (headerBytes.size < HEADER_BYTES) return null
            for (i in 0 until 8) {
                if (headerBytes[i].toInt().toChar() != MAGIC[i]) return null
            }
            val version = u16Le(headerBytes, 8)
            val maxOrder = u16Le(headerBytes, 10)
            val tileWidth = u16Le(headerBytes, 12)
            val tileFormat = u16Le(headerBytes, 14)
            val tileCount = u32Le(headerBytes, 20)
            if (version != 1 ||
                tileFormat != TILE_FORMAT_JPEG ||
                maxOrder !in Healpix.MIN_ORDER..Healpix.MAX_ORDER ||
                tileWidth <= 0 ||
                tileCount != Healpix.totalTilesUpTo(maxOrder)
            ) {
                return null
            }
            return maxOrder
        }

        fun parse(headerBytes: ByteArray, tocBytes: ByteArray): HiPSPackToc? {
            val maxOrder = parseHeader(headerBytes) ?: return null
            return parseToc(maxOrder, tocBytes)
        }

        fun readFrom(source: HiPSAssetSource): HiPSPackToc? {
            val headerBytes = source.readRange(0L, HEADER_BYTES) ?: return null
            val maxOrder = parseHeader(headerBytes) ?: return null
            val tocBytes = source.readRange(
                HEADER_BYTES.toLong(),
                Healpix.totalTilesUpTo(maxOrder) * TOC_ENTRY_BYTES
            ) ?: return null
            return parseToc(maxOrder, tocBytes)
        }

        private fun parseToc(maxOrder: Int, tocBytes: ByteArray): HiPSPackToc? {
            val tileCount = Healpix.totalTilesUpTo(maxOrder)
            if (tocBytes.size < tileCount * TOC_ENTRY_BYTES) return null
            val offsets = LongArray(tileCount)
            val lengths = IntArray(tileCount)
            for (i in 0 until tileCount) {
                val base = i * TOC_ENTRY_BYTES
                offsets[i] = u64Le(tocBytes, base)
                lengths[i] = u32Le(tocBytes, base + 8)
            }
            return HiPSPackToc(maxOrder, offsets, lengths)
        }

        fun buildTocBytes(offsets: LongArray, lengths: IntArray): ByteArray {
            require(offsets.size == lengths.size)
            val out = ByteArray(offsets.size * TOC_ENTRY_BYTES)
            for (i in offsets.indices) {
                val base = i * TOC_ENTRY_BYTES
                putU64Le(out, base, offsets[i])
                putU32Le(out, base + 8, lengths[i])
                // bytes 12..15 flags/reserved = 0
            }
            return out
        }

        fun validatePack(
            source: HiPSAssetSource,
            fileLength: Long,
            expectedMaxOrder: Int = Healpix.MAX_ORDER
        ): Boolean = try {
            if (fileLength < HEADER_BYTES.toLong()) {
                false
            } else {
                readFrom(source)?.isValid(fileLength, expectedMaxOrder) == true
            }
        } finally {
            source.close()
        }

        private fun putU16Le(b: ByteArray, o: Int, v: Int) {
            b[o] = (v and 0xFF).toByte()
            b[o + 1] = ((v ushr 8) and 0xFF).toByte()
        }

        fun putU32Le(b: ByteArray, o: Int, v: Int) {
            b[o] = (v and 0xFF).toByte()
            b[o + 1] = ((v ushr 8) and 0xFF).toByte()
            b[o + 2] = ((v ushr 16) and 0xFF).toByte()
            b[o + 3] = ((v ushr 24) and 0xFF).toByte()
        }

        fun putU64Le(b: ByteArray, o: Int, v: Long) {
            for (i in 0 until 8) {
                b[o + i] = ((v ushr (8 * i)) and 0xFFL).toByte()
            }
        }

        private fun u16Le(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

        internal fun u32Le(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or
                    ((b[o + 1].toInt() and 0xFF) shl 8) or
                    ((b[o + 2].toInt() and 0xFF) shl 16) or
                    ((b[o + 3].toInt() and 0xFF) shl 24)

        internal fun u64Le(b: ByteArray, o: Int): Long {
            var res = 0L
            for (i in 0 until 8) {
                res = res or ((b[o + i].toLong() and 0xFFL) shl (8 * i))
            }
            return res
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as HiPSPackToc

        if (maxOrder != other.maxOrder) return false
        if (!offsets.contentEquals(other.offsets)) return false
        if (!lengths.contentEquals(other.lengths)) return false
        if (tileCount != other.tileCount) return false

        return true
    }

    override fun hashCode(): Int {
        var result = maxOrder
        result = 31 * result + offsets.contentHashCode()
        result = 31 * result + lengths.contentHashCode()
        result = 31 * result + tileCount
        return result
    }
}

/**
 * Hysteresis LOD selector + asynchronous `Dispatchers.IO` HiPS tile decoder and LRU tile cache.
 *
 * Order 0 (`12` tiles) is preloaded on startup and pinned permanently so zooming or panning fast
 * never leaves blank holes (`pix ushr 2` parent fallback resolves upward cleanly to Order 0).
 */
class HiPSTileManager(
    private val assetSource: HiPSAssetSource,
    private val openHqSource: (() -> HiPSAssetSource)? = null,
    initialHqIndex: HiPSHqIndex = HiPSHqIndex.EMPTY
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val decodeWakeup = Channel<Unit>(Channel.CONFLATED)
    private val hqSourceMutex = Mutex()
    private var activeHqSource: HiPSAssetSource? = null

    @Volatile
    private var pendingTargets: IntArray = IntArray(0)

    @Volatile
    private var settledKeysSnapshot: Set<Int> = emptySet()

    @Volatile
    private var hqIndexSnapshot: HiPSHqIndex = initialHqIndex

    /** Incremented on Main whenever decoded tiles become available so Compose redraws. */
    var cacheVersion by mutableIntStateOf(0)
        private set

    /** True on Main once the initial Order 0 preload pass has completed. */
    var isBaseReady by mutableStateOf(false)
        private set

    // Accessed strictly from the UI/Main thread; keys use Healpix.packTileKey(order, ipix)
    private val order0Tiles = arrayOfNulls<ImageBitmap?>(12)
    private val dynamicTiles = LinkedHashMap<Int, ImageBitmap>(MAX_DYNAMIC_TILES + 8)
    private var decodedHqKeys = IntArray(0)
    private val activeRequestedKeys = hashSetOf<Int>()
    private val failedKeys = HashSet<Int>()
    private val hqFaceScratch = IntArray(3)
    private val hqVecScratch = DoubleArray(3)
    private var currentLodOrder = 1

    init {
        startWorkers()
    }

    private fun startWorkers() {
        scope.launch {
            try {
                val activeToc = HiPSPackToc.readFrom(assetSource)
                if (activeToc == null) {
                    withContext(Dispatchers.Main) { isBaseReady = true }
                    return@launch
                }
                // Pin all 12 Order 0 base tiles so parent fallback always succeeds
                val decodedBase = ArrayList<Pair<Int, ImageBitmap>>(12)
                for (ipix in 0 until 12) {
                    if (!isActive) break
                    val bmp = decodeTileBlocking(activeToc, Healpix.packTileKey(0, ipix))
                    if (bmp != null) {
                        decodedBase.add(ipix to bmp)
                    }
                }
                if (isActive) {
                    withContext(Dispatchers.Main) {
                        for ((ipix, bmp) in decodedBase) {
                            order0Tiles[ipix] = bmp
                        }
                        refreshSettledSnapshotOnMain()
                        isBaseReady = true
                        cacheVersion++
                    }
                }

                @Suppress("unused")
                for (ignored in decodeWakeup) {
                    var batchCount = 0
                    while (isActive) {
                        val targets = pendingTargets
                        val settled = settledKeysSnapshot
                        var nextKey = -1
                        for (key in targets) {
                            if (key !in settled) {
                                nextKey = key
                                break
                            }
                        }
                        if (nextKey < 0) break

                        val bmp = decodeTileBlocking(activeToc, nextKey)
                        withContext(Dispatchers.Main) {
                            if (bmp != null) {
                                insertDynamicOnMain(nextKey, bmp)
                                batchCount++
                                if (batchCount >= 2) {
                                    batchCount = 0
                                    cacheVersion++
                                }
                            } else {
                                if (failedKeys.size > 128) failedKeys.clear()
                                failedKeys.add(nextKey)
                                refreshSettledSnapshotOnMain()
                            }
                        }
                    }
                    if (batchCount > 0) {
                        withContext(Dispatchers.Main) {
                            cacheVersion++
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    assetSource.close()
                    hqSourceMutex.withLock {
                        activeHqSource?.close()
                        activeHqSource = null
                    }
                }
            }
        }
    }

    private suspend fun decodeTileBlocking(activeToc: HiPSPackToc, packedKey: Int): ImageBitmap? {
        val order = Healpix.tileKeyOrder(packedKey)
        val ipix = Healpix.tileKeyIpix(packedKey)
        if (order <= activeToc.maxOrder) {
            val gIdx = Healpix.globalIndex(order, ipix)
            if (gIdx in 0 until activeToc.tileCount) {
                val off = activeToc.offsets[gIdx]
                val len = activeToc.lengths[gIdx]
                if (len > 0) {
                    val bytes = assetSource.readRange(off, len)
                    if (bytes != null) return assetSource.decodeTile(bytes)
                }
            }
        }
        val hqIdx = hqIndexSnapshot
        val entryIdx = hqIdx.findEntryIndex(packedKey)
        if (entryIdx < 0) return null
        val (hqSrc, bytes) = hqSourceMutex.withLock {
            if (hqIndexSnapshot !== hqIdx || hqIdx.size == 0) return null
            val src = activeHqSource ?: openHqSource?.invoke()?.also { activeHqSource = it }
            ?: return null
            val raw = src.readRange(hqIdx.offsets[entryIdx], hqIdx.lengths[entryIdx]) ?: return null
            src to raw
        }
        return hqSrc.decodeTile(bytes)
    }

    private fun isCachedOnMain(packedKey: Int): Boolean {
        if (Healpix.tileKeyOrder(packedKey) == 0) {
            val ipix = Healpix.tileKeyIpix(packedKey)
            return ipix in order0Tiles.indices && order0Tiles[ipix] != null
        }
        return dynamicTiles.containsKey(packedKey)
    }

    private fun refreshSettledSnapshotOnMain() {
        val next = HashSet<Int>(12 + dynamicTiles.size + failedKeys.size)
        for (i in 0 until 12) {
            if (order0Tiles[i] != null) next.add(Healpix.packTileKey(0, i))
        }
        var hqCount = 0
        for (k in dynamicTiles.keys) {
            next.add(k)
            if (Healpix.tileKeyOrder(k) > Healpix.MAX_ORDER) hqCount++
        }
        next.addAll(failedKeys)
        settledKeysSnapshot = next

        if (hqCount == 0) {
            if (decodedHqKeys.isNotEmpty()) decodedHqKeys = IntArray(0)
        } else {
            val hqArr = IntArray(hqCount)
            var idx = 0
            for (k in dynamicTiles.keys) {
                if (Healpix.tileKeyOrder(k) > Healpix.MAX_ORDER) {
                    hqArr[idx++] = k
                }
            }
            hqArr.sort()
            decodedHqKeys = hqArr
        }
    }

    private fun insertDynamicOnMain(packedKey: Int, bitmap: ImageBitmap) {
        val order = Healpix.tileKeyOrder(packedKey)
        if (order == 0) {
            order0Tiles[Healpix.tileKeyIpix(packedKey)] = bitmap
            refreshSettledSnapshotOnMain()
            return
        }
        if (order > Healpix.MAX_ORDER && !hqIndexSnapshot.containsKey(packedKey)) {
            return
        }
        dynamicTiles.remove(packedKey)
        while (dynamicTiles.size >= MAX_DYNAMIC_TILES) {
            val evictKey = dynamicTiles.keys.firstOrNull { it !in activeRequestedKeys }
                ?: dynamicTiles.keys.firstOrNull()
                ?: break
            dynamicTiles.remove(evictKey)
        }
        dynamicTiles[packedKey] = bitmap
        refreshSettledSnapshotOnMain()
    }

    /** Updates the sparse HQ index snapshot on Main and invalidates Compose draw state. */
    fun updateHqIndexOnMain(nextIndex: HiPSHqIndex) {
        hqIndexSnapshot = nextIndex
        failedKeys.clear()
        if (nextIndex.size == 0) {
            val iter = dynamicTiles.entries.iterator()
            while (iter.hasNext()) {
                if (Healpix.tileKeyOrder(iter.next().key) > Healpix.MAX_ORDER) {
                    iter.remove()
                }
            }
        }
        refreshSettledSnapshotOnMain()
        cacheVersion++
    }

    /** Closes any open HQ file handle before `hq/` is deleted on disk. */
    suspend fun releaseHqSource() {
        hqSourceMutex.withLock {
            activeHqSource?.close()
            activeHqSource = null
        }
    }

    /** Select active HiPS order (`0..MAX_ORDER`, or up to `HQ_MAX_ORDER` when HQ tiles exist). */
    fun selectTargetOrder(fovDeg: Double, viewportWidthPx: Float, viewportHeightPx: Float): Int {
        val minDim = minOf(viewportWidthPx, viewportHeightPx).coerceAtLeast(1f).toDouble()
        val screenArcsecPerPx = (fovDeg * 3600.0) / minDim
        val maxOrder = if (openHqSource != null && hqIndexSnapshot.size > 0) {
            Healpix.HQ_MAX_ORDER
        } else {
            Healpix.MAX_ORDER
        }
        currentLodOrder = computeNextOrderWithHysteresis(
            currentOrder = currentLodOrder,
            screenArcsecPerPx = screenArcsecPerPx,
            fovDeg = fovDeg,
            maxOrder = maxOrder
        )
        return currentLodOrder
    }

    /**
     * Request asynchronous decoding for [count] base tiles in [visibleIpix] at [baseOrder], plus
     * any installed sparse HQ descendants up to [desiredOrder]. Must be called from Main/UI thread.
     */
    fun requestVisibleTiles(
        baseOrder: Int,
        visibleIpix: IntArray,
        count: Int,
        desiredOrder: Int = baseOrder,
        centerRaDeg: Double = 0.0,
        centerDecDeg: Double = 0.0,
        viewRadiusDeg: Double = 180.0
    ) {
        activeRequestedKeys.clear()
        val baseCap = minOf(count, MAX_DYNAMIC_TILES)
        val missing = ArrayList<Int>(baseCap + 16)
        for (i in 0 until baseCap) {
            val key = Healpix.packTileKey(baseOrder, visibleIpix[i])
            enqueueOrTouchOnMain(key, missing)
        }
        if (desiredOrder > Healpix.MAX_ORDER &&
            baseOrder == Healpix.MAX_ORDER &&
            openHqSource != null &&
            activeRequestedKeys.size < MAX_DYNAMIC_TILES
        ) {
            val hqIdx = hqIndexSnapshot
            if (hqIdx.size > 0) {
                val raRad = centerRaDeg * (PI / 180.0)
                val decRad = centerDecDeg * (PI / 180.0)
                val cosDec = cos(decRad)
                val vx = cosDec * cos(raRad)
                val vy = cosDec * sin(raRad)
                val vz = sin(decRad)

                val candidates = ArrayList<Pair<Double, Int>>(32)
                val maxChildOrder = minOf(desiredOrder, Healpix.HQ_MAX_ORDER)
                for (childOrder in (Healpix.MAX_ORDER + 1)..maxChildOrder) {
                    val tileHalfDiagDeg = 60.0 / (1 shl childOrder).toDouble()
                    val coneRad =
                        ((viewRadiusDeg + tileHalfDiagDeg) * (PI / 180.0)).coerceAtMost(PI)
                    val minCos = cos(coneRad)
                    val shift = 2 * (childOrder - Healpix.MAX_ORDER)
                    for (i in 0 until baseCap) {
                        val ipix4 = visibleIpix[i]
                        val minKey = Healpix.packTileKey(childOrder, ipix4 shl shift)
                        val maxKey = Healpix.packTileKey(childOrder, ((ipix4 + 1) shl shift) - 1)
                        hqIdx.forEachKeyInRange(minKey, maxKey) { hqKey ->
                            val dot = Healpix.tileCenterDot(
                                childOrder,
                                Healpix.tileKeyIpix(hqKey),
                                vx,
                                vy,
                                vz,
                                hqFaceScratch,
                                hqVecScratch
                            )
                            if (dot >= minCos) {
                                candidates.add(dot to hqKey)
                            }
                        }
                    }
                }
                candidates.sortByDescending { it.first }
                for ((_, hqKey) in candidates) {
                    if (activeRequestedKeys.size >= MAX_DYNAMIC_TILES) break
                    enqueueOrTouchOnMain(hqKey, missing)
                }
            }
        }
        if (missing.isNotEmpty()) {
            pendingTargets = missing.toIntArray()
            decodeWakeup.trySend(Unit)
        } else if (pendingTargets.isNotEmpty()) {
            pendingTargets = IntArray(0)
        }
    }

    private fun enqueueOrTouchOnMain(packedKey: Int, missing: ArrayList<Int>) {
        activeRequestedKeys.add(packedKey)
        if (!isCachedOnMain(packedKey) && packedKey !in failedKeys) {
            missing.add(packedKey)
        } else if (Healpix.tileKeyOrder(packedKey) > 0) {
            val existing = dynamicTiles.remove(packedKey)
            if (existing != null) dynamicTiles[packedKey] = existing
        }
    }

    /** Returns an already-decoded tile at exact `(order, ipix)` on Main without parent fallback. */
    fun getDecodedExactTile(order: Int, ipix: Int): ImageBitmap? =
        if (order == 0) {
            order0Tiles.getOrNull(ipix)
        } else {
            dynamicTiles[Healpix.packTileKey(order, ipix)]
        }

    /** True if any HQ tile strictly finer than [order] (up to [maxOrder]) under [ipix] is decoded on Main. */
    fun hasDecodedHqDescendant(order: Int, ipix: Int, maxOrder: Int): Boolean {
        val hqKeys = decodedHqKeys
        if (hqKeys.isEmpty() || order >= maxOrder) return false
        val startOrder = maxOf(order + 1, Healpix.MAX_ORDER + 1)
        for (childOrder in startOrder..maxOrder) {
            val shift = 2 * (childOrder - order)
            val minKey = Healpix.packTileKey(childOrder, ipix shl shift)
            val maxKey = Healpix.packTileKey(childOrder, ((ipix + 1) shl shift) - 1)
            val raw = hqKeys.binarySearchKey(minKey)
            if (raw >= 0) return true
            val ins = -raw - 1
            if (ins < hqKeys.size && hqKeys[ins] <= maxKey) return true
        }
        return false
    }

    /**
     * Resolve `(targetOrder, targetIpix)` bitmap on UI thread, falling back cleanly up the
     * `pix ushr 2` parent hierarchy down to pinned Order 0 when finer tiles are still decoding.
     */
    fun resolveTile(targetOrder: Int, targetIpix: Int): ResolvedHiPSTile? {
        for (loadedOrder in targetOrder downTo 0) {
            val ancestorIpix = targetIpix ushr (2 * (targetOrder - loadedOrder))
            val bmp = getDecodedExactTile(loadedOrder, ancestorIpix)
            if (bmp != null) return ResolvedHiPSTile(loadedOrder, bmp)
        }
        return null
    }

    fun close() {
        decodeWakeup.close()
        scope.cancel()
    }

    companion object {
        const val MAX_DYNAMIC_TILES = 96

        private val PROMOTE_ARCSEC = doubleArrayOf(0.0, 360.0, 175.0, 85.0, 42.5, 26.0)
        private val PROMOTE_FOV = doubleArrayOf(0.0, 105.0, 52.0, 26.0, 13.0, 9.0)
        private val DEMOTE_ARCSEC = doubleArrayOf(0.0, 420.0, 205.0, 100.0, 50.0, 30.0)
        private val DEMOTE_FOV = doubleArrayOf(0.0, 115.0, 58.0, 30.0, 15.0, 10.5)

        private fun scaledThreshold(table: DoubleArray, order: Int): Double =
            if (order < table.size) {
                table[order]
            } else {
                table.last() / (1 shl (order - (table.size - 1))).toDouble()
            }

        /** Pure hysteresis LOD transition logic for testability across `0..maxOrder`. */
        fun computeNextOrderWithHysteresis(
            currentOrder: Int,
            screenArcsecPerPx: Double,
            fovDeg: Double,
            maxOrder: Int = Healpix.MAX_ORDER
        ): Int {
            var order = currentOrder.coerceIn(0, maxOrder)
            for (target in (order + 1)..maxOrder) {
                if (screenArcsecPerPx < scaledThreshold(PROMOTE_ARCSEC, target) &&
                    fovDeg < scaledThreshold(PROMOTE_FOV, target)
                ) {
                    order = target
                } else {
                    break
                }
            }
            while (order > 0) {
                if (screenArcsecPerPx > scaledThreshold(DEMOTE_ARCSEC, order) ||
                    fovDeg > scaledThreshold(DEMOTE_FOV, order)
                ) {
                    order--
                } else {
                    break
                }
            }
            return order.coerceIn(0, maxOrder)
        }

        /**
         * Effective HQ overlay ceiling: allows `+1` order lookahead once zoomed past [Healpix.MAX_ORDER]
         * (`5 -> 6`, `6 -> 7`, `7 -> 8`) so sparse leaf cutouts step in smoothly without tiny `O8`
         * minification artifacts at wide FOV.
         */
        fun effectiveHqOrder(desiredOrder: Int): Int =
            if (desiredOrder > Healpix.MAX_ORDER) {
                minOf(desiredOrder + 1, Healpix.HQ_MAX_ORDER)
            } else {
                desiredOrder
            }
    }
}
