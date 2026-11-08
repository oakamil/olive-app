// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

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
    var fov: Double,
    var viewportWidth: Float,
    var viewportHeight: Float,
    var rotationRad: Double = 0.0
) {
    /**
     * Projects horizontal coordinates (Azimuth, Altitude in degrees) to viewport (x, y) pixels.
     * Applies antipode culling (1 + cos(c) >= 0.05) to prevent division by zero near the rear hemisphere.
     */
    fun toScreen(az: Double, alt: Double): Pair<Float, Float>? {
        val altRad = Math.toRadians(alt)
        val alt0Rad = Math.toRadians(centerAlt)

        val dAz = Math.toRadians(((az - centerAz) % 360.0 + 540.0) % 360.0 - 180.0)
        val cosC = sin(alt0Rad) * sin(altRad) + cos(alt0Rad) * cos(altRad) * cos(dAz)

        // Strict antipode culling to prevent distortion & division by zero
        if (1.0 + cosC < 0.05) return null

        val k = 2.0 / (1.0 + cosC)
        // Facing centerAz: increasing Azimuth is to the right (East/South/West clockwise)
        val xProj = k * cos(altRad) * sin(dAz)
        val yProj = -k * (cos(alt0Rad) * sin(altRad) - sin(alt0Rad) * cos(altRad) * cos(dAz))

        val xRot =
            if (rotationRad != 0.0) xProj * cos(rotationRad) - yProj * sin(rotationRad) else xProj
        val yRot =
            if (rotationRad != 0.0) xProj * sin(rotationRad) + yProj * cos(rotationRad) else yProj

        val scale = minOf(viewportWidth, viewportHeight) / (4.0 * tan(Math.toRadians(fov) / 4.0))
        val screenX = (viewportWidth / 2f) + (xRot * scale).toFloat()
        val screenY = (viewportHeight / 2f) + (yRot * scale).toFloat()

        return Pair(screenX, screenY)
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
        val scale = minOf(viewportWidth, viewportHeight) / (4.0 * tan(Math.toRadians(fov) / 4.0))
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
         * Computes the 4 Cardinal direction points (N, E, S, W) on the horizon in horizontal coordinates.
         */
        fun computeCardinalPoints(): List<Pair<String, Pair<Double, Double>>> {
            return listOf(
                Pair("N", Pair(0.0, 0.0)),
                Pair("E", Pair(90.0, 0.0)),
                Pair("S", Pair(180.0, 0.0)),
                Pair("W", Pair(270.0, 0.0))
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
            val halfTanY = tan(fovRad / 2.0)
            val halfTanX = halfTanY * (if (imageAspect > 0.0) imageAspect else 1.0)

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
                    ra0Rad + atan2(-xRad * sinC, rho * cosDec0 * cosC - yRad * sinDec0 * sinC)

                val raDeg = (Math.toDegrees(raRad) % 360.0 + 360.0) % 360.0
                val decDeg = Math.toDegrees(decRad).coerceIn(-90.0, 90.0)
                celestialCorners.add(Pair(raDeg, decDeg))
            }
            return celestialCorners
        }
    }
}
