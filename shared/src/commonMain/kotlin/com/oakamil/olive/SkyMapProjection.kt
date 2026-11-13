// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Matrix
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * Conformal Stereographic Projection for full sky map rendering, gestures, and astrometric calculations
 * centered in Topocentric Horizontal (Azimuth, Altitude) coordinates.
 * Zenith (+90° Altitude) is mathematically guaranteed to be directly UP on screen at all times.
 */
class SkyMapProjection(
    var centerAz: Double,
    var centerAlt: Double,
    fov: Double,
    var viewportWidth: Float,
    var viewportHeight: Float,
    rotationRad: Double = 0.0
) {
    var fov: Double = if (fov.isFinite()) fov.coerceIn(0.1, 359.0) else 60.0

    val scale: Double = run {
        val minDim = minOf(viewportWidth, viewportHeight).toDouble()
        val tanQuarter = tan(Math.toRadians(this.fov) / 4.0).coerceAtLeast(1e-9)
        if (minDim > 0.0 && minDim.isFinite()) minDim / (4.0 * tanQuarter) else 0.0
    }

    private var alt0Rad = Math.toRadians(centerAlt)
    private var sinAlt0 = sin(alt0Rad)
    private var cosAlt0 = cos(alt0Rad)
    private val hasRotation = rotationRad != 0.0
    private val cosRot = if (hasRotation) cos(rotationRad) else 1.0
    private val sinRot = if (hasRotation) sin(rotationRad) else 0.0
    private val halfWidth = viewportWidth / 2f
    private val halfHeight = viewportHeight / 2f

    /**
     * Allocation-free projection of horizontal coordinates (Azimuth, Altitude in degrees)
     * to viewport (x, y) pixels as a packed [Offset].
     * Returns [Offset.Unspecified] when antipode-culled (1 + cos(c) < 0.05).
     */
    fun toScreenOffset(az: Double, alt: Double): Offset {
        val altRad = Math.toRadians(alt)
        val sinAlt = sin(altRad)
        val cosAlt = cos(altRad)

        val dAz = Math.toRadians(((az - centerAz) % 360.0 + 540.0) % 360.0 - 180.0)
        val sinDAz = sin(dAz)
        val cosDAz = cos(dAz)
        val cosC = sinAlt0 * sinAlt + cosAlt0 * cosAlt * cosDAz

        // Strict antipode culling to prevent distortion & division by zero
        if (1.0 + cosC < 0.05) return Offset.Unspecified

        val k = 2.0 / (1.0 + cosC)
        // Facing centerAz: increasing Azimuth is to the right (East/South/West clockwise)
        val xProj = k * cosAlt * sinDAz
        val yProj = -k * (cosAlt0 * sinAlt - sinAlt0 * cosAlt * cosDAz)

        val xRot = if (hasRotation) xProj * cosRot - yProj * sinRot else xProj
        val yRot = if (hasRotation) xProj * sinRot + yProj * cosRot else yProj

        val screenX = halfWidth + (xRot * scale).toFloat()
        val screenY = halfHeight + (yRot * scale).toFloat()

        return Offset(screenX, screenY)
    }

    /**
     * Projects horizontal coordinates (Azimuth, Altitude in degrees) to viewport (x, y) pixels.
     * Applies antipode culling (1 + cos(c) >= 0.05) to prevent division by zero near the rear hemisphere.
     */
    fun toScreen(az: Double, alt: Double): Pair<Float, Float>? {
        val pt = toScreenOffset(az, alt)
        return if (pt.isSpecified) Pair(pt.x, pt.y) else null
    }

    /**
     * Allocation-free projection of celestial coordinates (RA, Dec in degrees J2000) to viewport (x, y) pixels
     * using the observer's latitude and Local Sidereal Time.
     */
    fun toScreenRaDecOffset(ra: Double, dec: Double, lat: Double, lst: Double): Offset {
        val (az, alt) = AstrometryUtils.raDecToAltAz(ra, dec, lat, lst)
        return toScreenOffset(az, alt)
    }

    /**
     * Projects celestial coordinates (RA, Dec in degrees J2000) to viewport (x, y) pixels
     * using the observer's latitude and Local Sidereal Time.
     */
    fun toScreenRaDec(ra: Double, dec: Double, lat: Double, lst: Double): Pair<Float, Float>? {
        val (az, alt) = AstrometryUtils.raDecToAltAz(ra, dec, lat, lst)
        return toScreen(az, alt)
    }

    /**
     * Shifts map center by screen pixel delta in Horizontal (Azimuth, Altitude) coordinates.
     * Altitude is strictly clamped in [-90.0, +90.0] degrees:
     * - Once Zenith (+90°) is centered on screen, it cannot be pulled lower (horizontal dragging rotates the sky).
     * - Once Nadir (-90°) is centered on screen, it cannot be pulled higher.
     */
    fun panBy(deltaScreenX: Float, deltaScreenY: Float) {
        if (scale <= 0.0) return

        // Dragging down on screen moves view UP towards Zenith (+Alt)
        val dAlt = Math.toDegrees(deltaScreenY.toDouble() / scale)
        val clampedAlt = centerAlt.coerceIn(-89.0, 89.0)
        val cosAlt = cos(Math.toRadians(clampedAlt)).coerceAtLeast(0.1)
        val rawDAz = Math.toDegrees(-deltaScreenX.toDouble() / (scale * cosAlt))
        val maxDAz = (fov * 0.8).coerceIn(0.2, 45.0)
        val dAz = rawDAz.coerceIn(-maxDAz, maxDAz)

        centerAlt = (centerAlt + dAlt).coerceIn(-90.0, 90.0)
        centerAz = ((centerAz + dAz) % 360.0 + 360.0) % 360.0
        alt0Rad = Math.toRadians(centerAlt)
        sinAlt0 = sin(alt0Rad)
        cosAlt0 = cos(alt0Rad)
    }

    companion object {
        /**
         * Returns Zenith horizontal coordinates (Azimuth, Altitude).
         */
        fun getZenith(): Pair<Double, Double> = Pair(0.0, 90.0)

        /**
         * Returns Nadir horizontal coordinates (Azimuth, Altitude).
         */
        fun getNadir(): Pair<Double, Double> = Pair(0.0, -90.0)

        /**
         * Precomputed points along the local horizon circle (Alt = 0 degrees) in horizontal coordinates.
         */
        val HORIZON_POINTS: List<Pair<Double, Double>> = computeHorizonPoints(72)

        /**
         * Precomputed 4 Cardinal direction points (N, E, S, W) on the horizon in horizontal coordinates.
         */
        val CARDINAL_POINTS: List<Pair<String, Pair<Double, Double>>> = computeCardinalPoints()

        /**
         * Computes points along the local horizon circle (Alt = 0 degrees) in horizontal coordinates.
         */
        fun computeHorizonPoints(steps: Int = 72): List<Pair<Double, Double>> {
            val points = ArrayList<Pair<Double, Double>>(steps)
            for (i in 0 until steps) {
                points.add(Pair((i * 360.0) / steps, 0.0))
            }
            return points
        }

        /**
         * Computes the 8 Cardinal and Ordinal direction points on the horizon in horizontal coordinates.
         */
        fun computeCardinalPoints(): List<Pair<String, Pair<Double, Double>>> {
            return listOf(
                Pair("N", Pair(0.0, 0.0)),
                Pair("NE", Pair(45.0, 0.0)),
                Pair("E", Pair(90.0, 0.0)),
                Pair("SE", Pair(135.0, 0.0)),
                Pair("S", Pair(180.0, 0.0)),
                Pair("SW", Pair(225.0, 0.0)),
                Pair("W", Pair(270.0, 0.0)),
                Pair("NW", Pair(315.0, 0.0))
            )
        }

        /**
         * Computes the 4 celestial corners (Top-Left, Top-Right, Bottom-Right, Bottom-Left)
         * of the camera's sensor rectangular field of view in J2000 coordinates.
         */
        fun computeCameraFovCorners(
            boresightRa: Double,
            boresightDec: Double,
            fovDeg: Double,
            rollDeg: Double,
            imageAspect: Double = 1.0
        ): List<Pair<Double, Double>> {
            val fovRad = Math.toRadians(fovDeg)
            val halfTanY =
                if (imageAspect >= 1.0) tan(fovRad / 2.0) else tan(fovRad / 2.0) / (if (imageAspect > 0.0) imageAspect else 1.0)
            val halfTanX = if (imageAspect >= 1.0) halfTanY * imageAspect else tan(fovRad / 2.0)

            val rollRad = Math.toRadians(-rollDeg)
            val cosR = cos(rollRad)
            val sinR = sin(rollRad)

            // 4 corners: Top-Left, Top-Right, Bottom-Right, Bottom-Left
            val rawCorners = listOf(
                Pair(-halfTanX, -halfTanY),
                Pair(halfTanX, -halfTanY),
                Pair(halfTanX, halfTanY),
                Pair(-halfTanX, halfTanY)
            )

            val ra0Rad = Math.toRadians(boresightRa)
            val dec0Rad = Math.toRadians(boresightDec)
            val sinDec0 = sin(dec0Rad)
            val cosDec0 = cos(dec0Rad)

            val celestialCorners = mutableListOf<Pair<Double, Double>>()
            for ((cx, cy) in rawCorners) {
                // Invert CW roll rotation to camera coordinates
                val xPx = cx * cosR + cy * sinR
                val yPx = -cx * sinR + cy * cosR

                val xRad = -xPx
                val yRad = -yPx

                val rho = hypot(xRad, yRad)
                if (rho < 1e-9) {
                    celestialCorners.add(Pair(boresightRa, boresightDec))
                    continue
                }

                val c = atan(rho)
                val sinC = sin(c)
                val cosC = cos(c)

                val decRad =
                    asin((cosC * sinDec0 + (yRad * sinC * cosDec0 / rho)).coerceIn(-1.0, 1.0))
                val raRad =
                    ra0Rad + atan2(xRad * sinC, rho * cosDec0 * cosC - yRad * sinDec0 * sinC)

                val raDeg = (Math.toDegrees(raRad) % 360.0 + 360.0) % 360.0
                val decDeg = Math.toDegrees(decRad).coerceIn(-90.0, 90.0)
                celestialCorners.add(Pair(raDeg, decDeg))
            }
            return celestialCorners
        }

        /**
         * Unprojects a camera pixel coordinate (pixelX, pixelY) into celestial J2000 (RA, Dec in degrees)
         * using the camera's optical center, vertical FOV, roll, and aspect ratio.
         */
        fun unprojectCameraPixel(
            pixelX: Double,
            pixelY: Double,
            imageWidth: Double,
            imageHeight: Double,
            centerRa: Double,
            centerDec: Double,
            fovDeg: Double,
            rollDeg: Double,
            imageAspect: Double = 1.0
        ): Pair<Double, Double> {
            if (imageWidth <= 0.0 || imageHeight <= 0.0) return Pair(centerRa, centerDec)

            val fovRad = Math.toRadians(fovDeg)
            val halfTanY =
                if (imageAspect >= 1.0) tan(fovRad / 2.0) else tan(fovRad / 2.0) / (if (imageAspect > 0.0) imageAspect else 1.0)
            val halfTanX = if (imageAspect >= 1.0) halfTanY * imageAspect else tan(fovRad / 2.0)

            val cx = ((pixelX - imageWidth / 2.0) / (imageWidth / 2.0)) * halfTanX
            val cy = ((pixelY - imageHeight / 2.0) / (imageHeight / 2.0)) * halfTanY

            val rollRad = Math.toRadians(-rollDeg)
            val cosR = cos(rollRad)
            val sinR = sin(rollRad)

            val xPx = cx * cosR + cy * sinR
            val yPx = -cx * sinR + cy * cosR

            val xRad = -xPx
            val yRad = -yPx

            val rho = hypot(xRad, yRad)
            if (rho < 1e-9) return Pair(centerRa, centerDec)

            val c = atan(rho)
            val sinC = sin(c)
            val cosC = cos(c)

            val ra0Rad = Math.toRadians(centerRa)
            val dec0Rad = Math.toRadians(centerDec)
            val sinDec0 = sin(dec0Rad)
            val cosDec0 = cos(dec0Rad)

            val decRad =
                asin((cosC * sinDec0 + (yRad * sinC * cosDec0 / rho)).coerceIn(-1.0, 1.0))
            val raRad =
                ra0Rad + atan2(xRad * sinC, rho * cosDec0 * cosC - yRad * sinDec0 * sinC)

            val raDeg = (Math.toDegrees(raRad) % 360.0 + 360.0) % 360.0
            val decDeg = Math.toDegrees(decRad).coerceIn(-90.0, 90.0)
            return Pair(raDeg, decDeg)
        }

        /**
         * Computes a 3x3 homography matrix (represented as Compose 4x4 Matrix) mapping
         * the rectangle (0,0)-(srcW, srcH) to the convex quad defined by (p0, p1, p2, p3):
         * p0 = Top-Left
         * p1 = Top-Right
         * p2 = Bottom-Right
         * p3 = Bottom-Left
         *
         * Mirrors coordinates along X or Y if [mirrorScaleX] < 0 or [mirrorScaleY] < 0,
         * matching the camera orientation and mirroring logic.
         */
        fun computeQuadHomography(
            srcW: Float,
            srcH: Float,
            p0: Pair<Float, Float>,
            p1: Pair<Float, Float>,
            p2: Pair<Float, Float>,
            p3: Pair<Float, Float>,
            mirrorScaleX: Float = 1f,
            mirrorScaleY: Float = 1f
        ): Matrix {
            var c0 = p0
            var c1 = p1
            var c2 = p2
            var c3 = p3

            if (mirrorScaleX < 0f) {
                val t0 = c0
                val t3 = c3
                c0 = c1; c1 = t0
                c3 = c2; c2 = t3
            }
            if (mirrorScaleY < 0f) {
                val t0 = c0
                val t1 = c1
                c0 = c3; c3 = t0
                c1 = c2; c2 = t1
            }

            val x0 = c0.first
            val y0 = c0.second
            val x1 = c1.first
            val y1 = c1.second
            val x2 = c2.first
            val y2 = c2.second
            val x3 = c3.first
            val y3 = c3.second

            val dx1 = x1 - x2
            val dx2 = x3 - x2
            val dx3 = x0 - x1 + x2 - x3
            val dy1 = y1 - y2
            val dy2 = y3 - y2
            val dy3 = y0 - y1 + y2 - y3

            var g = 0f
            var h = 0f
            if (kotlin.math.abs(dx3) >= 1e-4f || kotlin.math.abs(dy3) >= 1e-4f) {
                val det = dx1 * dy2 - dy1 * dx2
                if (kotlin.math.abs(det) >= 1e-7f) {
                    g = (dx3 * dy2 - dy3 * dx2) / det
                    h = (dx1 * dy3 - dy1 * dx3) / det
                }
            }

            val a = x1 - x0 + g * x1
            val b = x3 - x0 + h * x3
            val d = y1 - y0 + g * y1
            val e = y3 - y0 + h * y3

            return Matrix(
                floatArrayOf(
                    a / srcW, d / srcW, 0f, g / srcW,
                    b / srcH, e / srcH, 0f, h / srcH,
                    0f, 0f, 1f, 0f,
                    x0, y0, 0f, 1f
                )
            )
        }

        /**
         * Computes the angular distance (in degrees) from the viewport center to the viewport corners
         * in stereographic projection, accounting for both horizontal and vertical viewport dimensions.
         */
        fun cornerRadiusDeg(fovDeg: Double, viewportWidth: Float, viewportHeight: Float): Double {
            val minDim = minOf(viewportWidth, viewportHeight).toDouble()
            if (minDim <= 0.0) return fovDeg * 1.2
            val diagRatio = hypot(viewportWidth.toDouble(), viewportHeight.toDouble()) / minDim
            val fovRad = Math.toRadians(fovDeg)
            return Math.toDegrees(2.0 * atan(diagRatio * tan(fovRad / 4.0)))
        }
    }
}
