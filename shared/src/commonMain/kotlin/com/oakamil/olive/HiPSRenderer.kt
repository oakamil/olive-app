// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * Direct ICRS unit-vector -> Stereographic screen-space projector.
 * Mathematically identical to [AstrometryUtils.raDecToAltAz] followed by [SkyMapProjection.toScreenOffset],
 * precomputing the combined 3x3 equatorial->camera basis once per frame for allocation-free vertex loops.
 */
class EqToScreenProjector {
    private var fwdX = 0.0
    private var fwdY = 0.0
    private var fwdZ = 1.0

    private var rgtX = 1.0
    private var rgtY = 0.0
    private var rgtZ = 0.0

    private var upX = 0.0
    private var upY = 1.0
    private var upZ = 0.0

    private var horUpX = 0.0
    private var horUpY = 0.0
    private var horUpZ = 1.0

    private var scale = 0.0
    private var halfW = 0f
    private var halfH = 0f

    fun configure(
        centerAzDeg: Double,
        centerAltDeg: Double,
        fovDeg: Double,
        viewportWidth: Float,
        viewportHeight: Float,
        frame: SkyHorizontalFrame
    ) {
        val minDim = minOf(viewportWidth, viewportHeight).toDouble()
        val clFov = if (fovDeg.isFinite()) fovDeg.coerceIn(0.1, 359.0) else 60.0
        val tanQuarter = tan((clFov * PI / 180.0) / 4.0).coerceAtLeast(1e-9)
        scale = if (minDim > 0.0 && minDim.isFinite()) minDim / (4.0 * tanQuarter) else 0.0
        halfW = viewportWidth * 0.5f
        halfH = viewportHeight * 0.5f

        horUpX = frame.ux
        horUpY = frame.uy
        horUpZ = frame.uz

        val az0 = centerAzDeg * (PI / 180.0)
        val alt0 = centerAltDeg * (PI / 180.0)
        val cosAz0 = cos(az0)
        val sinAz0 = sin(az0)
        val cosAlt0 = cos(alt0)
        val sinAlt0 = sin(alt0)

        // Camera forward, right, up in horizon coordinates combined directly with precessed J2000 N/E/U
        val fn = cosAlt0 * cosAz0
        val fe = cosAlt0 * sinAz0
        fwdX = fn * frame.nx + fe * frame.ex + sinAlt0 * frame.ux
        fwdY = fn * frame.ny + fe * frame.ey + sinAlt0 * frame.uy
        fwdZ = fn * frame.nz + fe * frame.ez + sinAlt0 * frame.uz

        val rn = -sinAz0
        rgtX = rn * frame.nx + cosAz0 * frame.ex
        rgtY = rn * frame.ny + cosAz0 * frame.ey
        rgtZ = rn * frame.nz + cosAz0 * frame.ez

        val un = -sinAlt0 * cosAz0
        val ue = -sinAlt0 * sinAz0
        upX = un * frame.nx + ue * frame.ex + cosAlt0 * frame.ux
        upY = un * frame.ny + ue * frame.ey + cosAlt0 * frame.uy
        upZ = un * frame.nz + ue * frame.ez + cosAlt0 * frame.uz
    }

    /** Project equatorial unit vector `(qx, qy, qz)` -> packed `screenX, screenY`. Returns `sinAlt`, or `Double.NaN` if antipode-culled. */
    fun project(
        qx: Double,
        qy: Double,
        qz: Double,
        outCoords: FloatArray,
        coordIdx: Int
    ): Double {
        val cosC = fwdX * qx + fwdY * qy + fwdZ * qz
        if (1.0 + cosC < 0.05) return Double.NaN
        val k = 2.0 / (1.0 + cosC)
        val xProj = k * (rgtX * qx + rgtY * qy + rgtZ * qz)
        val yProj = -k * (upX * qx + upY * qy + upZ * qz)
        outCoords[coordIdx] = halfW + (xProj * scale).toFloat()
        outCoords[coordIdx + 1] = halfH + (yProj * scale).toFloat()
        return horUpX * qx + horUpY * qy + horUpZ * qz
    }
}

/**
 * Reusable frame state and preallocated scratch buffers for multi-order HiPS `drawVertices` sky
 * rendering.
 *
 * All projection arrays, mesh vertex lists, `Paint`, and cached `ImageShader`s are reused across
 * frames to minimize draw-pass allocations. Must be used strictly from the Compose UI/draw thread;
 * call [clear] on disposal to release cached GPU shader references.
 */
class HiPSRenderState {
    private val projector = EqToScreenProjector()
    private val visibleIndices = IntArray(Healpix.tileCountForOrder(Healpix.MAX_ORDER))
    private val visibleDots = DoubleArray(Healpix.tileCountForOrder(Healpix.MAX_ORDER))
    private val faceIxIyScratch = IntArray(3)
    private val unitVecScratch = DoubleArray(3)

    private val maxGridVerts = (MAX_SUBDIV + 1) * (MAX_SUBDIV + 1)
    private val gridScreenCoords = FloatArray(maxGridVerts * 2)
    private val gridValid = BooleanArray(maxGridVerts)
    private val gridSinAlt = DoubleArray(maxGridVerts)

    private val vertPositions = ArrayList<Offset>(maxGridVerts)
    private val vertTexCoords = ArrayList<Offset>(maxGridVerts)
    private val vertColors = ArrayList<Color>(maxGridVerts)
    private val vertIndices = ArrayList<Int>(MAX_SUBDIV * MAX_SUBDIV * 6)
    private val vertMap = IntArray(maxGridVerts)

    private val baseShaderCache = HashMap<ImageBitmap, Shader>(16)
    private val dynamicShaderCache =
        LinkedHashMap<ImageBitmap, Shader>(HiPSTileManager.MAX_DYNAMIC_TILES + 8)

    // Disable edge AA to prevent hairline seams between adjacent HEALPix tiles; Screen blends over navy/OLED sky.
    private val tilePaint = Paint().apply {
        isAntiAlias = false
        filterQuality = FilterQuality.Medium
        blendMode = BlendMode.Screen
    }

    fun clear() {
        baseShaderCache.clear()
        dynamicShaderCache.clear()
    }

    private fun getOrCreateShader(bitmap: ImageBitmap, isBaseTile: Boolean): Shader {
        if (isBaseTile) {
            baseShaderCache[bitmap]?.let { return it }
            return ImageShader(bitmap, TileMode.Clamp, TileMode.Clamp).also {
                baseShaderCache[bitmap] = it
            }
        }
        dynamicShaderCache.remove(bitmap)?.let { existing ->
            dynamicShaderCache[bitmap] = existing
            return existing
        }
        while (dynamicShaderCache.size >= HiPSTileManager.MAX_DYNAMIC_TILES) {
            val oldest = dynamicShaderCache.keys.firstOrNull() ?: break
            dynamicShaderCache.remove(oldest)
        }
        return ImageShader(bitmap, TileMode.Clamp, TileMode.Clamp).also {
            dynamicShaderCache[bitmap] = it
        }
    }

    fun render(
        drawScope: DrawScope,
        tileManager: HiPSTileManager,
        projection: SkyMapProjection,
        frame: SkyHorizontalFrame,
        hasValidLocation: Boolean,
        isNightVision: Boolean
    ) {
        // Observe cacheVersion so Compose invalidates draw pass cleanly when new tiles decode
        @Suppress("unused", "UNUSED_VARIABLE")
        val version = tileManager.cacheVersion

        val w = drawScope.size.width
        val h = drawScope.size.height
        if (w <= 0f || h <= 0f) return

        val fovDeg = projection.fov
        val desiredOrder = tileManager.selectTargetOrder(fovDeg, w, h)
        val baseOrder = minOf(desiredOrder, Healpix.MAX_ORDER)
        val maxHqOrder = HiPSTileManager.effectiveHqOrder(desiredOrder)
        val (centerRaDeg, centerDecDeg) = frame.altAzToRaDec(
            projection.centerAz,
            projection.centerAlt
        )
        val viewRadiusDeg = SkyMapProjection.cornerRadiusDeg(fovDeg, w, h)
        val visibleCount = Healpix.queryVisibleTiles(
            baseOrder,
            centerRaDeg,
            centerDecDeg,
            viewRadiusDeg,
            visibleIndices
        )
        if (visibleCount <= 0) return

        // Prioritize center tiles when scheduling async IO decoding
        sortIndicesByCenterProximity(
            visibleIndices,
            visibleCount,
            baseOrder,
            centerRaDeg,
            centerDecDeg
        )
        tileManager.requestVisibleTiles(
            baseOrder = baseOrder,
            visibleIpix = visibleIndices,
            count = visibleCount,
            desiredOrder = maxHqOrder,
            centerRaDeg = centerRaDeg,
            centerDecDeg = centerDecDeg,
            viewRadiusDeg = viewRadiusDeg
        )

        projector.configure(
            centerAzDeg = projection.centerAz,
            centerAltDeg = projection.centerAlt,
            fovDeg = fovDeg,
            viewportWidth = w,
            viewportHeight = h,
            frame = frame
        )

        tilePaint.colorFilter = if (isNightVision) NIGHT_VISION_FILTER else DAY_VISION_FILTER

        val subdiv = subdivForOrder(baseOrder)
        val maxEdgePx = max(w, h) * 3.5f

        drawScope.drawIntoCanvas { canvas ->
            for (i in 0 until visibleCount) {
                val ipix = visibleIndices[i]
                val resolved = tileManager.resolveTile(baseOrder, ipix) ?: continue
                if (maxHqOrder > Healpix.MAX_ORDER) {
                    drawQuadtreeTile(
                        canvas = canvas,
                        tileManager = tileManager,
                        order = baseOrder,
                        ipix = ipix,
                        desiredOrder = maxHqOrder,
                        fallback = resolved,
                        viewportW = w,
                        viewportH = h,
                        maxEdgePx = maxEdgePx,
                        hasValidLocation = hasValidLocation
                    )
                } else {
                    drawResolvedTile(
                        canvas = canvas,
                        targetOrder = baseOrder,
                        targetIpix = ipix,
                        resolved = resolved,
                        subdiv = subdiv,
                        viewportW = w,
                        viewportH = h,
                        maxEdgePx = maxEdgePx,
                        hasValidLocation = hasValidLocation
                    )
                }
            }
        }
    }

    /**
     * Renders a pre-decoded single-order leaf cutout (`tiles` of `(ipix, bitmap)` at [order])
     * oriented Zenith-up via [frame] without below-horizon dimming.
     */
    fun renderCutout(
        drawScope: DrawScope,
        order: Int,
        tiles: List<Pair<Int, ImageBitmap>>,
        centerAzDeg: Double,
        centerAltDeg: Double,
        fovDeg: Double,
        frame: SkyHorizontalFrame,
        isNightVision: Boolean
    ) {
        val w = drawScope.size.width
        val h = drawScope.size.height
        if (w <= 0f || h <= 0f || tiles.isEmpty()) return

        projector.configure(
            centerAzDeg = centerAzDeg,
            centerAltDeg = centerAltDeg,
            fovDeg = fovDeg,
            viewportWidth = w,
            viewportHeight = h,
            frame = frame
        )
        tilePaint.colorFilter = if (isNightVision) NIGHT_VISION_FILTER else DAY_VISION_FILTER
        val subdiv = subdivForOrder(order)
        val maxEdgePx = max(w, h) * 3.5f

        drawScope.drawIntoCanvas { canvas ->
            for ((ipix, bmp) in tiles) {
                drawResolvedTile(
                    canvas = canvas,
                    targetOrder = order,
                    targetIpix = ipix,
                    resolved = ResolvedHiPSTile(order, bmp),
                    subdiv = subdiv,
                    viewportW = w,
                    viewportH = h,
                    maxEdgePx = maxEdgePx,
                    hasValidLocation = false
                )
            }
        }
    }

    /**
     * Recursively subdivides a HEALPix tile (`(ipix shl 2) + 0..3`) whenever finer HQ descendants are
     * decoded underneath it, falling back seamlessly to the finest decoded ancestor in each quadrant.
     */
    private fun drawQuadtreeTile(
        canvas: Canvas,
        tileManager: HiPSTileManager,
        order: Int,
        ipix: Int,
        desiredOrder: Int,
        fallback: ResolvedHiPSTile,
        viewportW: Float,
        viewportH: Float,
        maxEdgePx: Float,
        hasValidLocation: Boolean
    ) {
        val current = if (order > Healpix.MAX_ORDER) {
            tileManager.getDecodedExactTile(order, ipix)?.let { ResolvedHiPSTile(order, it) }
                ?: fallback
        } else {
            fallback
        }
        if (order < desiredOrder && tileManager.hasDecodedHqDescendant(order, ipix, desiredOrder)) {
            val childOrder = order + 1
            val baseChild = ipix shl 2
            for (q in 0..3) {
                drawQuadtreeTile(
                    canvas = canvas,
                    tileManager = tileManager,
                    order = childOrder,
                    ipix = baseChild + q,
                    desiredOrder = desiredOrder,
                    fallback = current,
                    viewportW = viewportW,
                    viewportH = viewportH,
                    maxEdgePx = maxEdgePx,
                    hasValidLocation = hasValidLocation
                )
            }
            return
        }
        drawResolvedTile(
            canvas = canvas,
            targetOrder = order,
            targetIpix = ipix,
            resolved = current,
            subdiv = 2,
            viewportW = viewportW,
            viewportH = viewportH,
            maxEdgePx = maxEdgePx,
            hasValidLocation = hasValidLocation
        )
    }

    private fun drawResolvedTile(
        canvas: Canvas,
        targetOrder: Int,
        targetIpix: Int,
        resolved: ResolvedHiPSTile,
        subdiv: Int,
        viewportW: Float,
        viewportH: Float,
        maxEdgePx: Float,
        hasValidLocation: Boolean
    ) {
        Healpix.nestToFaceIxIy(targetOrder, targetIpix, faceIxIyScratch)
        val face = faceIxIyScratch[0]
        val ix = faceIxIyScratch[1]
        val iy = faceIxIyScratch[2]
        val invNside = 1.0 / Healpix.nsideForOrder(targetOrder).toDouble()
        // When rendering with a coarser ancestor texture (loadedOrder < targetOrder), crop its UV sub-rectangle.
        val levelDiff = targetOrder - resolved.loadedOrder
        val subMask = (1 shl levelDiff) - 1
        val parentScale = 1.0 / (1 shl levelDiff).toDouble()
        val subOffsetFx = (ix and subMask).toDouble()
        val subOffsetFy = (iy and subMask).toDouble()

        val cols = subdiv + 1
        // Half-texel inset (0.5..dim-0.5) avoids sampling outside tile edges under bilinear filtering.
        val texSpanX = (resolved.bitmap.width - 1).coerceAtLeast(1).toFloat()
        val texSpanY = (resolved.bitmap.height - 1).coerceAtLeast(1).toFloat()

        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var anyValid = false

        var vIdx = 0
        for (row in 0..subdiv) {
            val fxLocal = row.toDouble() / subdiv.toDouble()
            val fxFace = (ix + fxLocal) * invNside
            for (col in 0..subdiv) {
                val fyLocal = col.toDouble() / subdiv.toDouble()
                val fyFace = (iy + fyLocal) * invNside
                Healpix.faceUvToEqUnitVector(face, fxFace, fyFace, unitVecScratch, 0)
                val sinAlt = projector.project(
                    unitVecScratch[0],
                    unitVecScratch[1],
                    unitVecScratch[2],
                    gridScreenCoords,
                    vIdx * 2
                )
                if (!sinAlt.isNaN()) {
                    val sx = gridScreenCoords[vIdx * 2]
                    val sy = gridScreenCoords[vIdx * 2 + 1]
                    gridValid[vIdx] = true
                    gridSinAlt[vIdx] = sinAlt
                    if (sx < minX) minX = sx
                    if (sx > maxX) maxX = sx
                    if (sy < minY) minY = sy
                    if (sy > maxY) maxY = sy
                    anyValid = true
                } else {
                    gridValid[vIdx] = false
                }
                vIdx++
            }
        }

        if (!anyValid || maxX < 0f || minX > viewportW || maxY < 0f || minY > viewportH) {
            return
        }

        vertPositions.clear()
        vertTexCoords.clear()
        vertColors.clear()
        vertIndices.clear()
        for (k in 0 until vIdx) vertMap[k] = -1

        var emitCount = 0
        for (row in 0..subdiv) {
            val fxLocal = row.toDouble() / subdiv.toDouble()
            val parentFx = (subOffsetFx + fxLocal) * parentScale
            val ty = 0.5f + parentFx.toFloat() * texSpanY
            for (col in 0..subdiv) {
                val idx = row * cols + col
                if (!gridValid[idx]) continue
                val fyLocal = col.toDouble() / subdiv.toDouble()
                val parentFy = (subOffsetFy + fyLocal) * parentScale
                val tx = 0.5f + parentFy.toFloat() * texSpanX

                val sx = gridScreenCoords[idx * 2]
                val sy = gridScreenCoords[idx * 2 + 1]
                val vertColor = if (!hasValidLocation || gridSinAlt[idx] >= 0.0) {
                    Color.White
                } else {
                    val t = ((-gridSinAlt[idx]) / BELOW_HORIZON_RAMP_SIN).toFloat().coerceIn(0f, 1f)
                    val dim = 1.0f - t * (1.0f - BELOW_HORIZON_DIM_FACTOR)
                    Color(dim, dim, dim, 1f)
                }

                vertPositions.add(Offset(sx, sy))
                vertTexCoords.add(Offset(tx, ty))
                vertColors.add(vertColor)
                vertMap[idx] = emitCount++
            }
        }

        for (row in 0 until subdiv) {
            for (col in 0 until subdiv) {
                val tl = row * cols + col
                val tr = tl + 1
                val bl = tl + cols
                val br = bl + 1
                val iTl = vertMap[tl]
                val iTr = vertMap[tr]
                val iBl = vertMap[bl]
                val iBr = vertMap[br]
                if (iTl < 0 || iTr < 0 || iBl < 0 || iBr < 0) continue

                val x0 = gridScreenCoords[tl * 2]
                val y0 = gridScreenCoords[tl * 2 + 1]
                val x1 = gridScreenCoords[br * 2]
                val y1 = gridScreenCoords[br * 2 + 1]
                val x2 = gridScreenCoords[tr * 2]
                val y2 = gridScreenCoords[tr * 2 + 1]
                val x3 = gridScreenCoords[bl * 2]
                val y3 = gridScreenCoords[bl * 2 + 1]
                // Drop quads stretched across the stereographic antipode singularity.
                val span = max(max(abs(x1 - x0), abs(y1 - y0)), max(abs(x3 - x2), abs(y3 - y2)))
                if (span > maxEdgePx) continue

                vertIndices.add(iTl)
                vertIndices.add(iTr)
                vertIndices.add(iBr)
                vertIndices.add(iTl)
                vertIndices.add(iBr)
                vertIndices.add(iBl)
            }
        }

        if (vertIndices.isEmpty()) return

        tilePaint.shader = getOrCreateShader(resolved.bitmap, resolved.loadedOrder == 0)
        val vertices = Vertices(
            vertexMode = VertexMode.Triangles,
            positions = vertPositions,
            textureCoordinates = vertTexCoords,
            colors = vertColors,
            indices = vertIndices
        )
        canvas.drawVertices(vertices, BlendMode.Modulate, tilePaint)
    }

    // Coarser orders need denser meshes to follow stereographic curvature; orders >= 2 are nearly planar.
    private fun subdivForOrder(order: Int): Int = when (order) {
        0 -> 8
        1 -> 4
        else -> 2
    }

    // In-place insertion sort descending by dot product with view center (zero allocations).
    private fun sortIndicesByCenterProximity(
        indices: IntArray,
        count: Int,
        order: Int,
        centerRaDeg: Double,
        centerDecDeg: Double
    ) {
        if (count <= 1) return
        val raRad = centerRaDeg * (PI / 180.0)
        val decRad = centerDecDeg * (PI / 180.0)
        val cosDec = cos(decRad)
        val vx = cosDec * cos(raRad)
        val vy = cosDec * sin(raRad)
        val vz = sin(decRad)

        for (i in 0 until count) {
            visibleDots[i] = Healpix.baseTileCenterDot(order, indices[i], vx, vy, vz)
        }
        for (i in 1 until count) {
            val idxVal = indices[i]
            val dotVal = visibleDots[i]
            var j = i - 1
            while (j >= 0 && visibleDots[j] < dotVal) {
                indices[j + 1] = indices[j]
                visibleDots[j + 1] = visibleDots[j]
                j--
            }
            indices[j + 1] = idxVal
            visibleDots[j + 1] = dotVal
        }
    }

    companion object {
        private const val MAX_SUBDIV = 8
        private const val BELOW_HORIZON_RAMP_SIN = 0.07 // ~4 deg smooth transition below horizon
        private const val BELOW_HORIZON_DIM_FACTOR = 0.22f

        // Day Vision: clamp normalized sky-fog residual (~4/255 + JPEG noise) with gentle contrast lift over deep navy
        private val DAY_VISION_FILTER = ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    1.08f, 0f, 0f, 0f, -7.5f,
                    0f, 1.08f, 0f, 0f, -7.5f,
                    0f, 0f, 1.08f, 0f, -7.5f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )

        // Night Vision: Rec.709 pure-red luminance boost clamping normalized sky-fog residual (~4/255) to preserve OLED blacks
        private val NIGHT_VISION_FILTER = ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    0.2126f * 1.95f, 0.7152f * 1.95f, 0.0722f * 1.95f, 0f, -13.7f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }
}
