// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import com.oakamil.olive.Healpix.MAX_ORDER
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure Kotlin HEALPix NESTED math for Orders `MIN_ORDER..MAX_ORDER` and HiPS tile coordinate conversions.
 *
 * Adjust [MAX_ORDER] to change the maximum HiPS order downloaded and rendered across the app.
 *
 * Equatorial ICRS convention:
 * - Unit vector: `(cos(dec) * cos(ra), cos(dec) * sin(ra), sin(dec))`
 * - HiPS image pixel `(px, py)` in `[0, tileWidth - 1]` maps to continuous tile
 *   coordinates `fx = py / (tileWidth - 1)` (NE axis) and `fy = px / (tileWidth - 1)` (NW axis).
 */
object Healpix {
    const val MIN_ORDER = 0
    const val MAX_ORDER = 4
    const val HQ_MAX_ORDER = 8
    const val TOTAL_TILES = 4 * ((1 shl (2 * (MAX_ORDER + 1))) - 1)

    // At HQ_MAX_ORDER = 8, max ipix is 12 * 4^8 - 1 = 786,431 (< 2^20 = 1,048,576)
    private const val TILE_KEY_IPIX_BITS = 20
    private const val TILE_KEY_IPIX_MASK = (1 shl TILE_KEY_IPIX_BITS) - 1

    fun packTileKey(order: Int, ipix: Int): Int = (order shl TILE_KEY_IPIX_BITS) or ipix

    fun tileKeyOrder(key: Int): Int = key ushr TILE_KEY_IPIX_BITS

    fun tileKeyIpix(key: Int): Int = key and TILE_KEY_IPIX_MASK

    // Standard HEALPix base-pixel row (jrll) and azimuth (jpll) offsets for the 12 base faces.
    private val JRLL = intArrayOf(2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4)
    private val JPLL = intArrayOf(1, 3, 5, 7, 0, 2, 4, 6, 1, 3, 5, 7)

    fun nsideForOrder(order: Int): Int = 1 shl order

    fun orderOffset(order: Int): Int = 4 * ((1 shl (2 * order)) - 1)

    fun totalTilesUpTo(maxOrder: Int): Int = orderOffset(maxOrder + 1)

    fun tileCountForOrder(order: Int): Int = 12 shl (2 * order)

    fun globalIndex(order: Int, ipix: Int): Int = orderOffset(order) + ipix

    /** Decode `(order, ipix)` in NESTED scheme into `(face, ix, iy)` using bit de-interleaving. */
    fun nestToFaceIxIy(order: Int, ipix: Int, out: IntArray) {
        val npface = 1 shl (2 * order)
        val face = ipix ushr (2 * order)
        val pix = ipix and (npface - 1)
        var ix = 0
        var iy = 0
        for (bit in 0 until order) {
            ix = ix or (((pix ushr (2 * bit)) and 1) shl bit)
            iy = iy or (((pix ushr (2 * bit + 1)) and 1) shl bit)
        }
        out[0] = face
        out[1] = ix
        out[2] = iy
    }

    /** Encode `(face, ix, iy)` at [order] in NESTED scheme into `ipix`. */
    fun faceIxIyToNest(order: Int, face: Int, ix: Int, iy: Int): Int {
        var pix = 0
        for (bit in 0 until order) {
            pix = pix or (((ix ushr bit) and 1) shl (2 * bit))
            pix = pix or (((iy ushr bit) and 1) shl (2 * bit + 1))
        }
        return (face shl (2 * order)) or pix
    }

    /**
     * Map continuous normalized base-face coordinates `(fx, fy)` in `[0.0, 1.0]`
     * to ICRS equatorial unit vector `(out[offset], out[offset+1], out[offset+2])`.
     */
    fun faceUvToEqUnitVector(
        face: Int,
        fx: Double,
        fy: Double,
        out: DoubleArray,
        offset: Int = 0
    ) {
        val jr = JRLL[face].toDouble() - fx - fy
        val jpll = JPLL[face].toDouble()
        val diff = fx - fy

        val z: Double
        val phi: Double
        if (jr < 1.0) {
            z = 1.0 - (jr * jr) / 3.0
            phi = if (jr > 1e-12) 0.5 * (jpll * jr + diff) / jr * (PI * 0.5) else 0.0
        } else if (jr > 3.0) {
            val nr = 4.0 - jr
            z = (nr * nr) / 3.0 - 1.0
            phi = if (nr > 1e-12) 0.5 * (jpll * nr + diff) / nr * (PI * 0.5) else 0.0
        } else {
            z = (2.0 - jr) * (2.0 / 3.0)
            phi = 0.5 * (jpll + diff) * (PI * 0.5)
        }

        val zClamped = z.coerceIn(-1.0, 1.0)
        val r = sqrt((1.0 - zClamped * zClamped).coerceAtLeast(0.0))
        out[offset] = r * cos(phi)
        out[offset + 1] = r * sin(phi)
        out[offset + 2] = zClamped
    }

    /** Map continuous base-face `(fx, fy)` to `(raDeg, decDeg)` in degrees. */
    fun faceUvToRaDecDeg(face: Int, fx: Double, fy: Double): Pair<Double, Double> {
        val vec = DoubleArray(3)
        faceUvToEqUnitVector(face, fx, fy, vec, 0)
        val decDeg = (asin(vec[2].coerceIn(-1.0, 1.0)) * 180.0 / PI)
        val raDeg = ((atan2(vec[1], vec[0]) * 180.0 / PI) + 360.0) % 360.0
        return Pair(raDeg, decDeg)
    }

    /** Forward HEALPix NESTED pixel lookup for `(raDeg, decDeg)` at [order]. */
    fun ang2pixNest(order: Int, raDeg: Double, decDeg: Double): Int {
        val nside = nsideForOrder(order)
        val decRad = decDeg * (PI / 180.0)
        val raRad = (((raDeg % 360.0) + 360.0) % 360.0) * (PI / 180.0)
        val z = sin(decRad)
        val za = abs(z)
        val tt = (raRad / (PI * 0.5)) % 4.0

        val face: Int
        val ix: Int
        val iy: Int
        if (za <= 2.0 / 3.0) {
            val temp1 = nside * (0.5 + tt)
            val temp2 = nside * z * 0.75
            val jp = (temp1 - temp2).toInt()
            val jm = (temp1 + temp2).toInt()
            val ifp = jp ushr order
            val ifm = jm ushr order
            face = when {
                ifp == ifm -> ifp or 4
                ifp < ifm -> ifp
                else -> ifm + 8
            }
            ix = jm and (nside - 1)
            iy = nside - (jp and (nside - 1)) - 1
        } else {
            val ntt = min(3, tt.toInt())
            val tp = tt - ntt
            val tmp = nside * sqrt((3.0 * (1.0 - za)).coerceAtLeast(0.0))
            val jp = min(nside - 1, (tp * tmp).toInt())
            val jm = min(nside - 1, ((1.0 - tp) * tmp).toInt())
            if (z >= 0.0) {
                face = ntt
                ix = nside - jm - 1
                iy = nside - jp - 1
            } else {
                face = ntt + 8
                ix = jp
                iy = jm
            }
        }
        return faceIxIyToNest(order, face, ix, iy)
    }

    private val tileCenterVecX = DoubleArray(TOTAL_TILES)
    private val tileCenterVecY = DoubleArray(TOTAL_TILES)
    private val tileCenterVecZ = DoubleArray(TOTAL_TILES)
    private val tileBoundingRad = DoubleArray(TOTAL_TILES)

    init {
        val faceIxIy = IntArray(3)
        val tmpVec = DoubleArray(3)
        // Sample corners, edge midpoints, and center to get tight conservative circumradius
        val samplePts = doubleArrayOf(
            0.0, 0.0,
            1.0, 0.0,
            0.0, 1.0,
            1.0, 1.0,
            0.5, 0.0,
            0.5, 1.0,
            0.0, 0.5,
            1.0, 0.5
        )
        for (order in MIN_ORDER..MAX_ORDER) {
            val npix = tileCountForOrder(order)
            val baseOffset = orderOffset(order)
            val invNside = 1.0 / nsideForOrder(order).toDouble()
            for (ipix in 0 until npix) {
                nestToFaceIxIy(order, ipix, faceIxIy)
                val face = faceIxIy[0]
                val ix = faceIxIy[1]
                val iy = faceIxIy[2]

                faceUvToEqUnitVector(
                    face,
                    (ix + 0.5) * invNside,
                    (iy + 0.5) * invNside,
                    tmpVec,
                    0
                )
                val cx = tmpVec[0]
                val cy = tmpVec[1]
                val cz = tmpVec[2]

                var minDot = 1.0
                var s = 0
                while (s < samplePts.size) {
                    val fx = (ix + samplePts[s]) * invNside
                    val fy = (iy + samplePts[s + 1]) * invNside
                    faceUvToEqUnitVector(face, fx, fy, tmpVec, 0)
                    val d = (cx * tmpVec[0] + cy * tmpVec[1] + cz * tmpVec[2]).coerceIn(-1.0, 1.0)
                    if (d < minDot) minDot = d
                    s += 2
                }
                val gIdx = baseOffset + ipix
                tileCenterVecX[gIdx] = cx
                tileCenterVecY[gIdx] = cy
                tileCenterVecZ[gIdx] = cz
                // Extra 1.5° safety margin handles stereographic curvature across large diamonds
                tileBoundingRad[gIdx] = acos(minDot) + 0.026
            }
        }
    }

    /**
     * Fill [outIndices] with all NESTED `ipix` indices at [order] (`MIN_ORDER..MAX_ORDER`) whose bounding cone
     * intersects a spherical cap of angular radius [viewRadiusDeg] centered at `(centerRaDeg, centerDecDeg)`.
     * Returns number of entries written (order-independent, GC-free).
     */
    fun queryVisibleTiles(
        order: Int,
        centerRaDeg: Double,
        centerDecDeg: Double,
        viewRadiusDeg: Double,
        outIndices: IntArray
    ): Int {
        val clOrder = order.coerceIn(MIN_ORDER, MAX_ORDER)
        val raRad = centerRaDeg * (PI / 180.0)
        val decRad = centerDecDeg * (PI / 180.0)
        val cosDec = cos(decRad)
        val vx = cosDec * cos(raRad)
        val vy = cosDec * sin(raRad)
        val vz = sin(decRad)

        val viewRad = (viewRadiusDeg * (PI / 180.0)).coerceAtLeast(0.0)
        val npix = tileCountForOrder(clOrder)
        val base = orderOffset(clOrder)
        var count = 0
        for (ipix in 0 until npix) {
            val gIdx = base + ipix
            val maxSepRad = viewRad + tileBoundingRad[gIdx]
            if (maxSepRad >= PI) {
                outIndices[count++] = ipix
            } else {
                val minCos = cos(maxSepRad)
                val dot = vx * tileCenterVecX[gIdx] +
                        vy * tileCenterVecY[gIdx] +
                        vz * tileCenterVecZ[gIdx]
                if (dot >= minCos) {
                    outIndices[count++] = ipix
                }
            }
        }
        return count
    }

    /** Dot product between precomputed base-order (`0..MAX_ORDER`) tile center and unit vector `(vx, vy, vz)`. */
    fun baseTileCenterDot(order: Int, ipix: Int, vx: Double, vy: Double, vz: Double): Double {
        val gIdx = globalIndex(order.coerceIn(MIN_ORDER, MAX_ORDER), ipix)
        return vx * tileCenterVecX[gIdx] + vy * tileCenterVecY[gIdx] + vz * tileCenterVecZ[gIdx]
    }

    /** Dot product between arbitrary-order (`0..HQ_MAX_ORDER`) tile center and unit vector `(vx, vy, vz)`. */
    fun tileCenterDot(
        order: Int,
        ipix: Int,
        vx: Double,
        vy: Double,
        vz: Double,
        faceScratch: IntArray,
        vecScratch: DoubleArray
    ): Double {
        if (order <= MAX_ORDER) return baseTileCenterDot(order, ipix, vx, vy, vz)
        nestToFaceIxIy(order, ipix, faceScratch)
        val invNside = 1.0 / nsideForOrder(order).toDouble()
        faceUvToEqUnitVector(
            faceScratch[0],
            (faceScratch[1] + 0.5) * invNside,
            (faceScratch[2] + 0.5) * invNside,
            vecScratch,
            0
        )
        return vx * vecScratch[0] + vy * vecScratch[1] + vz * vecScratch[2]
    }
}
