// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

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

        private fun putU32Le(b: ByteArray, o: Int, v: Int) {
            b[o] = (v and 0xFF).toByte()
            b[o + 1] = ((v ushr 8) and 0xFF).toByte()
            b[o + 2] = ((v ushr 16) and 0xFF).toByte()
            b[o + 3] = ((v ushr 24) and 0xFF).toByte()
        }

        private fun putU64Le(b: ByteArray, o: Int, v: Long) {
            for (i in 0 until 8) {
                b[o + i] = ((v ushr (8 * i)) and 0xFFL).toByte()
            }
        }

        private fun u16Le(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

        private fun u32Le(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or
                    ((b[o + 1].toInt() and 0xFF) shl 8) or
                    ((b[o + 2].toInt() and 0xFF) shl 16) or
                    ((b[o + 3].toInt() and 0xFF) shl 24)

        private fun u64Le(b: ByteArray, o: Int): Long {
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
    private val assetSource: HiPSAssetSource
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val decodeWakeup = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var pendingTargets: IntArray = IntArray(0)

    @Volatile
    private var settledGlobalsSnapshot: Set<Int> = emptySet()

    /** Incremented on Main whenever decoded tiles become available so Compose redraws. */
    var cacheVersion by mutableIntStateOf(0)
        private set

    // Accessed strictly from the UI/Main thread
    private val order0Tiles = arrayOfNulls<ImageBitmap?>(12)
    private val dynamicTiles = LinkedHashMap<Int, ImageBitmap>(MAX_DYNAMIC_TILES + 8)
    private val activeRequestedGlobals = hashSetOf<Int>()
    private val failedGlobals = HashSet<Int>()
    private var currentLodOrder = 1

    init {
        startWorkers()
    }

    private fun startWorkers() {
        scope.launch {
            val activeToc = HiPSPackToc.readFrom(assetSource) ?: return@launch
            // Pin all 12 Order 0 base tiles so parent fallback always succeeds
            val decodedBase = ArrayList<Pair<Int, ImageBitmap>>(12)
            for (ipix in 0 until 12) {
                if (!isActive) break
                val bmp = decodeTileBlocking(activeToc, ipix)
                if (bmp != null) {
                    decodedBase.add(ipix to bmp)
                }
            }
            if (decodedBase.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    for ((ipix, bmp) in decodedBase) {
                        order0Tiles[ipix] = bmp
                    }
                    refreshSettledSnapshotOnMain()
                    cacheVersion++
                }
            }

            @Suppress("unused")
            for (ignored in decodeWakeup) {
                var batchCount = 0
                while (isActive) {
                    val targets = pendingTargets
                    val settled = settledGlobalsSnapshot
                    var nextGIdx = -1
                    for (gIdx in targets) {
                        if (gIdx !in settled) {
                            nextGIdx = gIdx
                            break
                        }
                    }
                    if (nextGIdx < 0) break

                    val bmp = decodeTileBlocking(activeToc, nextGIdx)
                    withContext(Dispatchers.Main) {
                        if (bmp != null) {
                            insertDynamicOnMain(nextGIdx, bmp)
                            batchCount++
                            if (batchCount >= 2) {
                                batchCount = 0
                                cacheVersion++
                            }
                        } else {
                            if (failedGlobals.size > 128) failedGlobals.clear()
                            failedGlobals.add(nextGIdx)
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
        }
    }

    private fun decodeTileBlocking(activeToc: HiPSPackToc, globalIdx: Int): ImageBitmap? {
        if (globalIdx !in 0 until activeToc.tileCount) return null
        val off = activeToc.offsets[globalIdx]
        val len = activeToc.lengths[globalIdx]
        if (len <= 0) return null
        val bytes = assetSource.readRange(off, len) ?: return null
        return assetSource.decodeTile(bytes)
    }

    private fun isCachedOnMain(globalIdx: Int): Boolean {
        if (globalIdx < 12) return order0Tiles[globalIdx] != null
        return dynamicTiles.containsKey(globalIdx)
    }

    private fun refreshSettledSnapshotOnMain() {
        val next = HashSet<Int>(12 + dynamicTiles.size + failedGlobals.size)
        for (i in 0 until 12) {
            if (order0Tiles[i] != null) next.add(i)
        }
        next.addAll(dynamicTiles.keys)
        next.addAll(failedGlobals)
        settledGlobalsSnapshot = next
    }

    private fun insertDynamicOnMain(globalIdx: Int, bitmap: ImageBitmap) {
        if (globalIdx < 12) {
            order0Tiles[globalIdx] = bitmap
            refreshSettledSnapshotOnMain()
            return
        }
        dynamicTiles.remove(globalIdx)
        while (dynamicTiles.size >= MAX_DYNAMIC_TILES) {
            val evictKey = dynamicTiles.keys.firstOrNull { it !in activeRequestedGlobals }
                ?: dynamicTiles.keys.firstOrNull()
                ?: break
            dynamicTiles.remove(evictKey)
        }
        dynamicTiles[globalIdx] = bitmap
        refreshSettledSnapshotOnMain()
    }

    /** Select active HiPS order (`0..MAX_ORDER`) with hysteresis based on screen scale and FOV. */
    fun selectTargetOrder(fovDeg: Double, viewportWidthPx: Float, viewportHeightPx: Float): Int {
        val minDim = minOf(viewportWidthPx, viewportHeightPx).coerceAtLeast(1f).toDouble()
        val screenArcsecPerPx = (fovDeg * 3600.0) / minDim
        currentLodOrder = computeNextOrderWithHysteresis(
            currentOrder = currentLodOrder,
            screenArcsecPerPx = screenArcsecPerPx,
            fovDeg = fovDeg
        )
        return currentLodOrder
    }

    /**
     * Request asynchronous decoding for [count] tiles in [visibleIpix] at [order].
     * Tiles should ideally be ordered closest-to-center first. Must be called from Main/UI thread.
     */
    fun requestVisibleTiles(order: Int, visibleIpix: IntArray, count: Int) {
        activeRequestedGlobals.clear()
        val missing = ArrayList<Int>(count)
        for (i in 0 until count) {
            val gIdx = Healpix.globalIndex(order, visibleIpix[i])
            activeRequestedGlobals.add(gIdx)
            if (!isCachedOnMain(gIdx) && gIdx !in failedGlobals) {
                missing.add(gIdx)
            } else if (gIdx >= 12) {
                val existing = dynamicTiles.remove(gIdx)
                if (existing != null) dynamicTiles[gIdx] = existing
            }
        }
        if (missing.isNotEmpty()) {
            pendingTargets = missing.toIntArray()
            decodeWakeup.trySend(Unit)
        } else if (pendingTargets.isNotEmpty()) {
            pendingTargets = IntArray(0)
        }
    }

    /**
     * Resolve `(targetOrder, targetIpix)` bitmap on UI thread, falling back cleanly up the
     * `pix ushr 2` parent hierarchy down to pinned Order 0 when finer tiles are still decoding.
     */
    fun resolveTile(targetOrder: Int, targetIpix: Int): ResolvedHiPSTile? {
        for (loadedOrder in targetOrder downTo 0) {
            val ancestorIpix = targetIpix ushr (2 * (targetOrder - loadedOrder))
            val bmp = if (loadedOrder == 0) {
                order0Tiles[ancestorIpix]
            } else {
                dynamicTiles[Healpix.globalIndex(loadedOrder, ancestorIpix)]
            }
            if (bmp != null) return ResolvedHiPSTile(loadedOrder, bmp)
        }
        return null
    }

    fun close() {
        decodeWakeup.close()
        scope.cancel()
        assetSource.close()
    }

    companion object {
        const val MAX_DYNAMIC_TILES = 48

        private val PROMOTE_ARCSEC = doubleArrayOf(0.0, 360.0, 175.0, 85.0)
        private val PROMOTE_FOV = doubleArrayOf(0.0, 105.0, 52.0, 26.0)
        private val DEMOTE_ARCSEC = doubleArrayOf(0.0, 420.0, 205.0, 100.0)
        private val DEMOTE_FOV = doubleArrayOf(0.0, 115.0, 58.0, 30.0)

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
    }
}
