// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Planned DSS2 cutout parameters and rotation-invariant leaf tile keys for a catalog object.
 */
class HiPSObjectCutoutPlan(
    val order: Int,
    val baseFovDeg: Double,
    val requiredKeys: IntArray
)

/**
 * Prepared atomic append payload for `dss2_hq.dat` + `dss2_hq.idx`.
 */
class PreparedHqAppend(
    val updatedIndex: HiPSHqIndex,
    val payloadsToWrite: List<ByteArray>,
    val indexBytes: ByteArray
)

/** Binary search on a sorted [IntArray]; returns the matching index or `-(insertionPoint + 1)`. */
fun IntArray.binarySearchKey(target: Int): Int {
    var low = 0
    var high = size - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val midVal = this[mid]
        when {
            midVal < target -> low = mid + 1
            midVal > target -> high = mid - 1
            else -> return mid
        }
    }
    return -(low + 1)
}

/**
 * Immutable sorted index of sparse HQ tiles (`Orders 0..HQ_MAX_ORDER`) stored in `dss2_hq.dat`.
 *
 * Keys are packed via [Healpix.packTileKey] (`(order shl 20) or ipix`), so ascending integer order
 * sorts naturally by `(order, ipix)` and keeps every ancestor's `NESTED` descendants contiguous.
 */
class HiPSHqIndex(
    val keys: IntArray,
    val offsets: LongArray,
    val lengths: IntArray,
    val dataEndOffset: Long
) {
    val size: Int
        get() = keys.size

    fun containsKey(packedKey: Int): Boolean = keys.binarySearchKey(packedKey) >= 0

    fun findEntryIndex(packedKey: Int): Int = keys.binarySearchKey(packedKey)

    inline fun forEachKeyInRange(minKey: Int, maxKey: Int, action: (Int) -> Unit) {
        if (keys.isEmpty() || minKey > maxKey) return
        val raw = keys.binarySearchKey(minKey)
        var i = if (raw >= 0) raw else -raw - 1
        while (i < keys.size) {
            val k = keys[i]
            if (k > maxKey) break
            action(k)
            i++
        }
    }

    companion object {
        private const val MAGIC = "OLVHQIDX"
        private const val VERSION = 1
        const val HEADER_BYTES = 16
        const val ENTRY_BYTES = 16

        val EMPTY = HiPSHqIndex(IntArray(0), LongArray(0), IntArray(0), 0L)

        /**
         * Parses `OLVHQIDX` v1 bytes (16B header + sorted 16B `(key u32, len u32, offset u64)`
         * entries) and validates strict key monotonicity and bounds against [dataFileLength].
         * Returns [EMPTY] if corrupt or truncated.
         */
        fun parse(indexBytes: ByteArray?, dataFileLength: Long): HiPSHqIndex {
            if (indexBytes == null || indexBytes.size < HEADER_BYTES || dataFileLength < 0L) {
                return EMPTY
            }
            for (i in 0 until 8) {
                if (indexBytes[i].toInt().toChar() != MAGIC[i]) return EMPTY
            }
            val version = HiPSPackToc.u32Le(indexBytes, 8)
            val count = HiPSPackToc.u32Le(indexBytes, 12)
            if (version != VERSION || count < 0) return EMPTY
            val expectedBytes = HEADER_BYTES.toLong() + count.toLong() * ENTRY_BYTES.toLong()
            if (indexBytes.size.toLong() < expectedBytes) return EMPTY
            if (count == 0) return EMPTY

            val keys = IntArray(count)
            val offsets = LongArray(count)
            val lengths = IntArray(count)
            var prevKey = -1
            var maxEnd = 0L

            for (i in 0 until count) {
                val base = HEADER_BYTES + i * ENTRY_BYTES
                val key = HiPSPackToc.u32Le(indexBytes, base)
                val len = HiPSPackToc.u32Le(indexBytes, base + 4)
                val off = HiPSPackToc.u64Le(indexBytes, base + 8)
                if (key <= prevKey || len <= 0 || off < 0L || off > dataFileLength - len.toLong()) {
                    return EMPTY
                }
                val order = Healpix.tileKeyOrder(key)
                val ipix = Healpix.tileKeyIpix(key)
                if (order !in Healpix.MIN_ORDER..Healpix.HQ_MAX_ORDER ||
                    ipix !in 0 until Healpix.tileCountForOrder(order)
                ) {
                    return EMPTY
                }
                keys[i] = key
                lengths[i] = len
                offsets[i] = off
                prevKey = key
                val end = off + len.toLong()
                if (end > maxEnd) maxEnd = end
            }
            return HiPSHqIndex(keys, offsets, lengths, maxEnd)
        }

        fun serialize(index: HiPSHqIndex): ByteArray {
            val count = index.size
            val out = ByteArray(HEADER_BYTES + count * ENTRY_BYTES)
            for (i in 0 until 8) {
                out[i] = MAGIC[i].code.toByte()
            }
            HiPSPackToc.putU32Le(out, 8, VERSION)
            HiPSPackToc.putU32Le(out, 12, count)
            for (i in 0 until count) {
                val base = HEADER_BYTES + i * ENTRY_BYTES
                HiPSPackToc.putU32Le(out, base, index.keys[i])
                HiPSPackToc.putU32Le(out, base + 4, index.lengths[i])
                HiPSPackToc.putU64Le(out, base + 8, index.offsets[i])
            }
            return out
        }

        /**
         * Deduplicates [newItems] against [existing], assigns sequential byte offsets from
         * `existing.dataEndOffset`, and returns the merged index + serialized bytes for atomic commit.
         */
        fun prepareAppend(
            existing: HiPSHqIndex,
            newItems: List<Pair<Int, ByteArray>>
        ): PreparedHqAppend? {
            if (newItems.isEmpty()) return null
            val sortedUnique = newItems
                .filter { (key, bytes) -> bytes.isNotEmpty() && !existing.containsKey(key) }
                .sortedBy { it.first }
                .distinctBy { it.first }
            if (sortedUnique.isEmpty()) return null

            val addCount = sortedUnique.size
            val payloads = ArrayList<ByteArray>(addCount)
            val addKeys = IntArray(addCount)
            val addOffsets = LongArray(addCount)
            val addLengths = IntArray(addCount)
            var cursor = existing.dataEndOffset
            for (i in 0 until addCount) {
                val (key, bytes) = sortedUnique[i]
                addKeys[i] = key
                addOffsets[i] = cursor
                addLengths[i] = bytes.size
                payloads.add(bytes)
                cursor += bytes.size.toLong()
            }

            val total = existing.size + addCount
            val mergedKeys = IntArray(total)
            val mergedOffsets = LongArray(total)
            val mergedLengths = IntArray(total)
            var a = 0
            var b = 0
            var out = 0
            while (a < existing.size && b < addCount) {
                if (existing.keys[a] < addKeys[b]) {
                    mergedKeys[out] = existing.keys[a]
                    mergedOffsets[out] = existing.offsets[a]
                    mergedLengths[out] = existing.lengths[a]
                    a++
                } else {
                    mergedKeys[out] = addKeys[b]
                    mergedOffsets[out] = addOffsets[b]
                    mergedLengths[out] = addLengths[b]
                    b++
                }
                out++
            }
            while (a < existing.size) {
                mergedKeys[out] = existing.keys[a]
                mergedOffsets[out] = existing.offsets[a]
                mergedLengths[out] = existing.lengths[a]
                a++
                out++
            }
            while (b < addCount) {
                mergedKeys[out] = addKeys[b]
                mergedOffsets[out] = addOffsets[b]
                mergedLengths[out] = addLengths[b]
                b++
                out++
            }

            val updated = HiPSHqIndex(mergedKeys, mergedOffsets, mergedLengths, cursor)
            return PreparedHqAppend(updated, payloads, serialize(updated))
        }
    }
}

/**
 * Pure object eligibility, dynamic HEALPix order (`0..8`), and rotation-invariant tile planner.
 */
object HiPSObjectPlanner {
    private const val FOV_MULTIPLIER = 2.2
    private const val MIN_FOV_ARCMIN = 8.0
    private const val MAX_FOV_ARCMIN = 3600.0
    private const val ORDER0_ARCMIN_PER_PX = 6.871 // ~58.6° / 512 px for an Order-0 HEALPix tile
    private const val TARGET_CUTOUT_PX = 320.0
    private const val DISC_RADIUS_PAD = 1.12
    private const val DISC_GRID_STEPS = 13
    private const val DISC_RING_SAMPLES = 24

    private val ELIGIBLE_OBJECT_TYPES = setOf(
        "Galaxy",
        "Nebula",
        "Planetary Nebula",
        "Cluster + Nebula",
        "Globular Cluster",
        "Open Cluster",
        "Dark Nebula",
        "Asterism"
    )

    private val ELIGIBLE_SUB_CATALOGS = setOf(
        "M", "C", "H", "Ta2", "B", "Sh2", "Abl", "Arp", "Har", "EGC", "Col", "SaA", "IC", "NGC"
    )

    private val DEFAULT_SIZE_ARCMIN = mapOf(
        "Planetary Nebula" to 1.0,
        "Galaxy" to 1.5,
        "Globular Cluster" to 5.0,
        "Open Cluster" to 8.0,
        "Nebula" to 15.0,
        "Cluster + Nebula" to 15.0,
        "Dark Nebula" to 20.0,
        "Asterism" to 25.0
    )

    private val EXTENT_SPLIT_REGEX = Regex("\\s*[x×]\\s*", RegexOption.IGNORE_CASE)
    private val EXTENT_TOKEN_REGEX = Regex("^(\\d+(?:\\.\\d+)?)\\s*([\"'d°]?)$")

    fun isEligibleObject(obj: CatalogObject?): Boolean {
        if (obj == null || CatalogDatabase.isDynamicObject(obj)) return false
        return obj.objectType in ELIGIBLE_OBJECT_TYPES
    }

    fun isEligibleSubCatalog(shortCode: String?): Boolean =
        shortCode != null && shortCode in ELIGIBLE_SUB_CATALOGS

    fun parseExtentArcmin(raw: String?): Double? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        var maxArcmin = 0.0
        var any = false
        for (part in trimmed.split(EXTENT_SPLIT_REGEX)) {
            val match = EXTENT_TOKEN_REGEX.matchEntire(part.trim()) ?: continue
            val value = match.groupValues[1].toDoubleOrNull() ?: continue
            if (!value.isFinite() || value <= 0.0) continue
            val arcmin = when (match.groupValues[2]) {
                "\"" -> value / 60.0
                "d", "°" -> value * 60.0
                else -> value
            }
            if (arcmin > maxArcmin) maxArcmin = arcmin
            any = true
        }
        return if (any) maxArcmin else null
    }

    fun resolveMajorAxisArcmin(
        primary: CatalogObject,
        coLocated: List<CatalogObject> = emptyList()
    ): Double {
        parseExtentArcmin(primary.angularSize)?.let { return it }
        for (other in coLocated) {
            if (!isEligibleObject(other)) continue
            parseExtentArcmin(other.angularSize)?.let { return it }
        }
        return DEFAULT_SIZE_ARCMIN[primary.objectType] ?: 5.0
    }

    fun selectOrderAndFovDeg(majorArcmin: Double): Pair<Int, Double> {
        val safeMajor = if (majorArcmin.isFinite() && majorArcmin > 0.0) majorArcmin else 5.0
        val baseFovArcmin = (FOV_MULTIPLIER * safeMajor).coerceIn(MIN_FOV_ARCMIN, MAX_FOV_ARCMIN)
        var selectedOrder = Healpix.HQ_MAX_ORDER
        for (order in Healpix.MIN_ORDER..Healpix.HQ_MAX_ORDER) {
            val pxSpan = baseFovArcmin * (1 shl order).toDouble() / ORDER0_ARCMIN_PER_PX
            if (pxSpan >= TARGET_CUTOUT_PX) {
                selectedOrder = order
                break
            }
        }
        return selectedOrder to (baseFovArcmin / 60.0)
    }

    /**
     * Plans the optimal order, initial FOV, and circumscribed stereographic disc of leaf tiles so
     * Zenith-up orientation remains 100% covered across all 360° of sidereal rotation.
     */
    fun planCutout(raDeg: Double, decDeg: Double, majorArcmin: Double): HiPSObjectCutoutPlan {
        val (order, baseFovDeg) = selectOrderAndFovDeg(majorArcmin)
        val fovRad = baseFovDeg * (PI / 180.0)
        val cornerRad = 2.0 * atan(sqrt(2.0) * tan(fovRad * 0.25))
        val tanMax = (tan(cornerRad * 0.5) * DISC_RADIUS_PAD).coerceAtMost(2.5)

        val ra0 = raDeg * (PI / 180.0)
        val dec0 = decDeg.coerceIn(-90.0, 90.0) * (PI / 180.0)
        val sinD0 = sin(dec0)
        val cosD0 = cos(dec0)
        val rawKeys = IntArray(DISC_GRID_STEPS * DISC_GRID_STEPS + DISC_RING_SAMPLES + 1)
        var count = 0

        fun addTangentSample(x: Double, y: Double) {
            val rho = hypot(x, y)
            val sampleRa: Double
            val sampleDec: Double
            if (rho < 1e-12) {
                sampleRa = raDeg
                sampleDec = decDeg
            } else {
                val c = 2.0 * atan(rho)
                val sinC = sin(c)
                val cosC = cos(c)
                val dec = asin((cosC * sinD0 + y * sinC * cosD0 / rho).coerceIn(-1.0, 1.0))
                val ra = ra0 + atan2(x * sinC, rho * cosD0 * cosC - y * sinD0 * sinC)
                sampleRa = ((ra * (180.0 / PI)) % 360.0 + 360.0) % 360.0
                sampleDec = (dec * (180.0 / PI)).coerceIn(-90.0, 90.0)
            }
            rawKeys[count++] =
                Healpix.packTileKey(order, Healpix.ang2pixNest(order, sampleRa, sampleDec))
        }

        addTangentSample(0.0, 0.0)
        val tanLimitSq = tanMax * tanMax
        for (iy in 0 until DISC_GRID_STEPS) {
            val y = -tanMax + 2.0 * tanMax * iy.toDouble() / (DISC_GRID_STEPS - 1).toDouble()
            for (ix in 0 until DISC_GRID_STEPS) {
                val x = -tanMax + 2.0 * tanMax * ix.toDouble() / (DISC_GRID_STEPS - 1).toDouble()
                if (x * x + y * y <= tanLimitSq) {
                    addTangentSample(x, y)
                }
            }
        }
        for (s in 0 until DISC_RING_SAMPLES) {
            val theta = (2.0 * PI * s.toDouble()) / DISC_RING_SAMPLES.toDouble()
            addTangentSample(tanMax * cos(theta), tanMax * sin(theta))
        }

        rawKeys.sort(0, count)
        var unique = 0
        for (i in 0 until count) {
            if (unique == 0 || rawKeys[i] != rawKeys[unique - 1]) {
                rawKeys[unique++] = rawKeys[i]
            }
        }
        return HiPSObjectCutoutPlan(
            order = order,
            baseFovDeg = baseFovDeg,
            requiredKeys = rawKeys.copyOf(unique)
        )
    }

    fun filterMissingKeys(
        requiredKeys: IntArray,
        isBasePackAvailable: Boolean,
        hqIndex: HiPSHqIndex
    ): IntArray {
        var missingCount = 0
        for (key in requiredKeys) {
            if (!isTileAvailable(key, isBasePackAvailable, hqIndex)) {
                missingCount++
            }
        }
        if (missingCount == 0) return IntArray(0)
        val out = IntArray(missingCount)
        var idx = 0
        for (key in requiredKeys) {
            if (!isTileAvailable(key, isBasePackAvailable, hqIndex)) {
                out[idx++] = key
            }
        }
        return out
    }

    fun isTileAvailable(
        packedKey: Int,
        isBasePackAvailable: Boolean,
        hqIndex: HiPSHqIndex
    ): Boolean {
        if (isBasePackAvailable && Healpix.tileKeyOrder(packedKey) <= Healpix.MAX_ORDER) {
            return true
        }
        return hqIndex.containsKey(packedKey)
    }

    /**
     * Plans deduplicated missing tile keys across [objects], using [coLocatedLookup] when an
     * eligible row lacks its own `angularSize` so bulk planning matches [ObjectInfoPopup] 1:1.
     */
    fun planMissingForCatalog(
        objects: List<CatalogObject>,
        isBasePackAvailable: Boolean,
        hqIndex: HiPSHqIndex,
        coLocatedLookup: ((CatalogObject) -> List<CatalogObject>)? = null
    ): IntArray {
        val eligible = objects.filter { isEligibleObject(it) }
        if (eligible.isEmpty()) return IntArray(0)

        val sizeByCoord = HashMap<String, Double>(eligible.size)
        for (obj in eligible) {
            val parsed = parseExtentArcmin(obj.angularSize) ?: continue
            val coordKey = "${obj.ra}_${obj.dec}_${obj.objectType}"
            val prev = sizeByCoord[coordKey]
            if (prev == null || parsed > prev) {
                sizeByCoord[coordKey] = parsed
            }
        }

        val missingSet = HashSet<Int>(eligible.size * 3)
        for (obj in eligible) {
            val coordKey = "${obj.ra}_${obj.dec}_${obj.objectType}"
            val major = sizeByCoord[coordKey] ?: run {
                val resolved = if (coLocatedLookup != null) {
                    resolveMajorAxisArcmin(obj, coLocatedLookup(obj))
                } else {
                    DEFAULT_SIZE_ARCMIN[obj.objectType] ?: 5.0
                }
                sizeByCoord[coordKey] = resolved
                resolved
            }
            val plan = planCutout(obj.ra, obj.dec, major)
            for (key in plan.requiredKeys) {
                if (!isTileAvailable(key, isBasePackAvailable, hqIndex)) {
                    missingSet.add(key)
                }
            }
        }
        if (missingSet.isEmpty()) return IntArray(0)
        val result = IntArray(missingSet.size)
        var i = 0
        for (k in missingSet) {
            result[i++] = k
        }
        result.sort()
        return result
    }
}
