// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Stateless decode-time normalizer that flattens per-plate DSS2 sky-fog pedestals on
 * dark and cool-bluish starfields while leaving Milky Way clouds, warm dust, bright nebulae,
 * and saturated stars untouched.
 */
object HiPSTileNormalizer {
    private const val CELL_SIZE = 16
    const val MIN_DIM = CELL_SIZE
    private const val HALF_CELL = CELL_SIZE / 2
    private const val BLEND_RADIUS = CELL_SIZE + HALF_CELL
    private const val SAMPLE_GRID = 4
    private const val SAMPLE_COUNT = SAMPLE_GRID * SAMPLE_GRID
    private const val SAMPLE_STEP = CELL_SIZE / SAMPLE_GRID
    private const val SAMPLE_OFFSET = SAMPLE_STEP / 2
    private const val FLOOR_RANK = 2 // ~15th percentile of 16 block samples
    private const val BYTES_PER_PIXEL = 4
    private const val CHANNEL_MAX = 255
    private const val TARGET_FLOOR = 4
    private const val MAX_SUB = 18
    private const val BRIGHT_FADE_START = 20
    private const val BRIGHT_FADE_END = 36
    private const val WARM_MIN_RED = 10
    private const val WARM_START_DIFF = 3
    private const val WARM_END_DIFF = 10
    private const val EDGE_TOLERANCE = 8
    private const val HIGHLIGHT_TAPER_START = 210
    private const val HIGHLIGHT_TAPER_SPAN = CHANNEL_MAX - HIGHLIGHT_TAPER_START

    /**
     * Normalizes an `[R, G, B, A]` [rgba] buffer (`width * height * 4` bytes) in-place.
     */
    fun normalizeRgba(rgba: ByteArray, width: Int, height: Int) {
        val requiredBytes = width.toLong() * height.toLong() * BYTES_PER_PIXEL.toLong()
        if (width < MIN_DIM || height < MIN_DIM || rgba.size.toLong() < requiredBytes) return

        val gx = (width + CELL_SIZE - 1) / CELL_SIZE
        val gy = (height + CELL_SIZE - 1) / CELL_SIZE
        val cellCount = gx * gy

        val cellR = IntArray(cellCount)
        val cellG = IntArray(cellCount)
        val cellB = IntArray(cellCount)
        val subR = IntArray(cellCount)
        val subG = IntArray(cellCount)
        val subB = IntArray(cellCount)

        estimateCellSubtractions(rgba, width, height, gx, gy, cellR, cellG, cellB, subR, subG, subB)
        applyNormalizedPixels(rgba, width, height, gx, gy, cellR, cellG, cellB, subR, subG, subB)
    }

    private fun estimateCellSubtractions(
        rgba: ByteArray,
        width: Int,
        height: Int,
        gx: Int,
        gy: Int,
        cellR: IntArray,
        cellG: IntArray,
        cellB: IntArray,
        subR: IntArray,
        subG: IntArray,
        subB: IntArray
    ) {
        val sampleR = IntArray(SAMPLE_COUNT)
        val sampleG = IntArray(SAMPLE_COUNT)
        val sampleB = IntArray(SAMPLE_COUNT)

        for (by in 0 until gy) {
            val baseY = by * CELL_SIZE
            for (bx in 0 until gx) {
                val baseX = bx * CELL_SIZE
                var count = 0
                for (sy in 0 until SAMPLE_GRID) {
                    val py = min(height - 1, baseY + SAMPLE_OFFSET + sy * SAMPLE_STEP)
                    val rowOff = py * width
                    for (sx in 0 until SAMPLE_GRID) {
                        val px = min(width - 1, baseX + SAMPLE_OFFSET + sx * SAMPLE_STEP)
                        val p = (rowOff + px) * BYTES_PER_PIXEL
                        sampleR[count] = rgba[p].toInt() and CHANNEL_MAX
                        sampleG[count] = rgba[p + 1].toInt() and CHANNEL_MAX
                        sampleB[count] = rgba[p + 2].toInt() and CHANNEL_MAX
                        count++
                    }
                }
                insertionSort(sampleR)
                insertionSort(sampleG)
                insertionSort(sampleB)

                val r = sampleR[FLOOR_RANK]
                val g = sampleG[FLOOR_RANK]
                val b = sampleB[FLOOR_RANK]
                val idx = by * gx + bx
                cellR[idx] = r
                cellG[idx] = g
                cellB[idx] = b

                val maxC = max(r, max(g, b))
                // Strictly monotonic fade across bright nebulae/clouds so no dark moats form
                var gate = when {
                    maxC <= TARGET_FLOOR || maxC >= BRIGHT_FADE_END -> 0f
                    maxC > BRIGHT_FADE_START ->
                        (BRIGHT_FADE_END - maxC).toFloat() / (BRIGHT_FADE_END - BRIGHT_FADE_START)

                    else -> 1f
                }
                val warmExcess = r - b
                if (gate > 0f && r > WARM_MIN_RED && warmExcess > WARM_START_DIFF) {
                    gate *= if (warmExcess >= WARM_END_DIFF) {
                        0f
                    } else {
                        (WARM_END_DIFF - warmExcess).toFloat() / (WARM_END_DIFF - WARM_START_DIFF)
                    }
                }

                if (gate > 0f) {
                    subR[idx] = min(MAX_SUB, (max(0, r - TARGET_FLOOR) * gate).roundToInt())
                    subG[idx] = min(MAX_SUB, (max(0, g - TARGET_FLOOR) * gate).roundToInt())
                    subB[idx] = min(MAX_SUB, (max(0, b - TARGET_FLOOR) * gate).roundToInt())
                }
            }
        }
    }

    private fun applyNormalizedPixels(
        rgba: ByteArray,
        width: Int,
        height: Int,
        gx: Int,
        gy: Int,
        cellR: IntArray,
        cellG: IntArray,
        cellB: IntArray,
        subR: IntArray,
        subG: IntArray,
        subB: IntArray
    ) {
        val fadeStart = EDGE_TOLERANCE / 2
        val blendSpan = EDGE_TOLERANCE + 1 - fadeStart
        val blendRound = blendSpan ushr 1

        for (by in 0 until gy) {
            val yStart = by * CELL_SIZE
            val yEnd = min(height, yStart + CELL_SIZE)
            val nyMin = max(0, by - 1)
            val nyMax = min(gy - 1, by + 1)

            for (bx in 0 until gx) {
                val xStart = bx * CELL_SIZE
                val xEnd = min(width, xStart + CELL_SIZE)
                val nxMin = max(0, bx - 1)
                val nxMax = min(gx - 1, bx + 1)

                var anySub = false
                for (ny in nyMin..nyMax) {
                    val rowBase = ny * gx
                    for (nx in nxMin..nxMax) {
                        val ni = rowBase + nx
                        if (subR[ni] != 0 || subG[ni] != 0 || subB[ni] != 0) {
                            anySub = true
                            break
                        }
                    }
                    if (anySub) break
                }
                if (!anySub) continue

                for (py in yStart until yEnd) {
                    val rowOff = py * width
                    for (px in xStart until xEnd) {
                        val p = (rowOff + px) * BYTES_PER_PIXEL
                        val pR = rgba[p].toInt() and CHANNEL_MAX
                        val pG = rgba[p + 1].toInt() and CHANNEL_MAX
                        val pB = rgba[p + 2].toInt() and CHANNEL_MAX
                        val pMax = max(pR, max(pG, pB))
                        if (pMax <= TARGET_FLOOR || pMax >= CHANNEL_MAX) continue

                        var sumR = 0
                        var sumG = 0
                        var sumB = 0
                        var weightSum = 0
                        var spSumR = 0
                        var spSumG = 0
                        var spSumB = 0
                        var spWeightSum = 0
                        var minDiff = Int.MAX_VALUE

                        for (ny in nyMin..nyMax) {
                            val wy = BLEND_RADIUS - abs(py - (ny * CELL_SIZE + HALF_CELL))
                            if (wy <= 0) continue
                            val rowBase = ny * gx
                            for (nx in nxMin..nxMax) {
                                val wx = BLEND_RADIUS - abs(px - (nx * CELL_SIZE + HALF_CELL))
                                if (wx <= 0) continue
                                val ni = rowBase + nx
                                val wSpatial = wx * wy
                                spSumR += subR[ni] * wSpatial
                                spSumG += subG[ni] * wSpatial
                                spSumB += subB[ni] * wSpatial
                                spWeightSum += wSpatial

                                val diff = max(
                                    abs(pR - cellR[ni]),
                                    max(abs(pG - cellG[ni]), abs(pB - cellB[ni]))
                                )
                                if (diff < minDiff) minDiff = diff
                                if (diff <= EDGE_TOLERANCE) {
                                    val w = wSpatial * (EDGE_TOLERANCE + 1 - diff)
                                    sumR += subR[ni] * w
                                    sumG += subG[ni] * w
                                    sumB += subB[ni] * w
                                    weightSum += w
                                }
                            }
                        }

                        val spHalf = spWeightSum ushr 1
                        var outSubR = if (spWeightSum > 0) (spSumR + spHalf) / spWeightSum else 0
                        var outSubG = if (spWeightSum > 0) (spSumG + spHalf) / spWeightSum else 0
                        var outSubB = if (spWeightSum > 0) (spSumB + spHalf) / spWeightSum else 0
                        if (weightSum > 0) {
                            val half = weightSum ushr 1
                            val edgeR = (sumR + half) / weightSum
                            val edgeG = (sumG + half) / weightSum
                            val edgeB = (sumB + half) / weightSum
                            if (minDiff <= fadeStart) {
                                outSubR = edgeR
                                outSubG = edgeG
                                outSubB = edgeB
                            } else {
                                val alpha = EDGE_TOLERANCE + 1 - minDiff
                                val beta = blendSpan - alpha
                                outSubR = (edgeR * alpha + outSubR * beta + blendRound) / blendSpan
                                outSubG = (edgeG * alpha + outSubG * beta + blendRound) / blendSpan
                                outSubB = (edgeB * alpha + outSubB * beta + blendRound) / blendSpan
                            }
                        }

                        if (pMax > HIGHLIGHT_TAPER_START) {
                            val t = CHANNEL_MAX - pMax
                            outSubR = outSubR * t / HIGHLIGHT_TAPER_SPAN
                            outSubG = outSubG * t / HIGHLIGHT_TAPER_SPAN
                            outSubB = outSubB * t / HIGHLIGHT_TAPER_SPAN
                        }

                        rgba[p] = max(0, pR - outSubR).toByte()
                        rgba[p + 1] = max(0, pG - outSubG).toByte()
                        rgba[p + 2] = max(0, pB - outSubB).toByte()
                    }
                }
            }
        }
    }

    private fun insertionSort(arr: IntArray) {
        for (i in 1 until arr.size) {
            val v = arr[i]
            var j = i - 1
            while (j >= 0 && arr[j] > v) {
                arr[j + 1] = arr[j]
                j--
            }
            arr[j + 1] = v
        }
    }
}
