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
 * Conformal Stereographic Projection for full sky map rendering, gestures, and astrometric calculations.
 */
class SkyMapProjection(
    var centerRa: Double,
    var centerDec: Double,
    var fov: Double,
    var viewportWidth: Float,
    var viewportHeight: Float,
    var rotationRad: Double = 0.0
) {
    /**
     * Projects celestial coordinate (RA, Dec in degrees J2000) to viewport (x, y) pixels.
     * Applies antipode culling (1 + cos(c) >= 0.05) to prevent division by zero near the rear hemisphere.
     */
    fun toScreen(ra: Double, dec: Double): Pair<Float, Float>? {
        val raRad = Math.toRadians(ra)
        val decRad = Math.toRadians(dec)
        val ra0Rad = Math.toRadians(centerRa)
        val dec0Rad = Math.toRadians(centerDec)

        val dRa = Math.toRadians(((ra - centerRa) % 360.0 + 540.0) % 360.0 - 180.0)
        val cosC = sin(dec0Rad) * sin(decRad) + cos(dec0Rad) * cos(decRad) * cos(dRa)

        // Strict antipode culling to prevent distortion & division by zero
        if (1.0 + cosC < 0.05) return null

        val k = 2.0 / (1.0 + cosC)
        val xProj = -k * cos(decRad) * sin(dRa)
        val yProj = -k * (cos(dec0Rad) * sin(decRad) - sin(dec0Rad) * cos(decRad) * cos(dRa))

        // Optional view rotation (e.g. around Zenith/center)
        val xRot = if (rotationRad != 0.0) xProj * cos(rotationRad) - yProj * sin(rotationRad) else xProj
        val yRot = if (rotationRad != 0.0) xProj * sin(rotationRad) + yProj * cos(rotationRad) else yProj

        val scale = minOf(viewportWidth, viewportHeight) / (4.0 * tan(Math.toRadians(fov) / 4.0))
        val screenX = (viewportWidth / 2f) + (xRot * scale).toFloat()
        val screenY = (viewportHeight / 2f) + (yRot * scale).toFloat()

        return Pair(screenX, screenY)
    }

    /**
     * Inverse projection from screen pixel coordinates (screenX, screenY) to celestial (RA, Dec in degrees).
     */
    fun toSky(screenX: Float, screenY: Float): Pair<Double, Double> {
        if (viewportWidth <= 0f || viewportHeight <= 0f) {
            return Pair((centerRa % 360.0 + 360.0) % 360.0, centerDec)
        }
        val scale = minOf(viewportWidth, viewportHeight) / (4.0 * tan(Math.toRadians(fov) / 4.0))
        if (scale <= 1e-6) {
            return Pair((centerRa % 360.0 + 360.0) % 360.0, centerDec)
        }
        val xRel = (screenX - viewportWidth / 2f) / scale
        val yRel = (screenY - viewportHeight / 2f) / scale

        // Invert rotation
        val xProj = if (rotationRad != 0.0) xRel * cos(-rotationRad) - yRel * sin(-rotationRad) else xRel.toDouble()
        val yProj = if (rotationRad != 0.0) xRel * sin(-rotationRad) + yRel * cos(-rotationRad) else yRel.toDouble()

        val rho = hypot(xProj, yProj)
        if (rho < 1e-9) return Pair((centerRa % 360.0 + 360.0) % 360.0, centerDec)

        val c = 2.0 * atan(rho / 2.0)
        val sinC = sin(c)
        val cosC = cos(c)
        val dec0Rad = Math.toRadians(centerDec)
        val ra0Rad = Math.toRadians(centerRa)

        val decRad = asin((cosC * sin(dec0Rad) - (yProj * sinC * cos(dec0Rad) / rho)).coerceIn(-1.0, 1.0))
        val raRad = ra0Rad + atan2(-xProj * sinC, rho * cos(dec0Rad) * cosC + yProj * sin(dec0Rad) * sinC)

        val raDeg = (Math.toDegrees(raRad) % 360.0 + 360.0) % 360.0
        val decDeg = Math.toDegrees(decRad).coerceIn(-90.0, 90.0)
        return Pair(raDeg, decDeg)
    }

    /**
     * Shifts map center by screen pixel delta, taking into account current FOV zoom,
     * declination compression, and applying continuous pole reflection kinematics.
     */
    fun panBy(deltaScreenX: Float, deltaScreenY: Float) {
        val scale = minOf(viewportWidth, viewportHeight) / (4.0 * tan(Math.toRadians(fov) / 4.0))
        if (scale <= 0.0) return

        val dDec = Math.toDegrees(deltaScreenY.toDouble() / scale)
        val cosDec = cos(Math.toRadians(centerDec)).coerceAtLeast(0.01)
        val dRa = Math.toDegrees(deltaScreenX.toDouble() / (scale * cosDec))

        var newDec = centerDec + dDec
        var newRa = centerRa + dRa

        // Continuous reflection across poles so panning does not lock up at +/- 90 degrees
        if (newDec > 90.0) {
            newDec = 180.0 - newDec
            newRa += 180.0
        } else if (newDec < -90.0) {
            newDec = -180.0 - newDec
            newRa += 180.0
        }

        centerDec = newDec.coerceIn(-90.0, 90.0)
        centerRa = (newRa % 360.0 + 360.0) % 360.0
    }

    companion object {
        /**
         * Returns Zenith celestial coordinates (RA, Dec) for given Local Sidereal Time and latitude.
         */
        fun getZenith(lst: Double, lat: Double): Pair<Double, Double> {
            return Pair((lst % 360.0 + 360.0) % 360.0, lat.coerceIn(-90.0, 90.0))
        }

        /**
         * Returns Nadir celestial coordinates (RA, Dec) for given Local Sidereal Time and latitude.
         */
        fun getNadir(lst: Double, lat: Double): Pair<Double, Double> {
            return Pair(((lst + 180.0) % 360.0 + 360.0) % 360.0, (-lat).coerceIn(-90.0, 90.0))
        }

        /**
         * Checks whether an object (RA, Dec) is above the local observer horizon.
         */
        fun isAboveHorizon(ra: Double, dec: Double, lat: Double, lst: Double): Boolean {
            val ha = Math.toRadians((lst - ra) % 360.0)
            val decRad = Math.toRadians(dec)
            val latRad = Math.toRadians(lat)
            val sinAlt = sin(decRad) * sin(latRad) + cos(decRad) * cos(latRad) * cos(ha)
            return sinAlt >= 0.0
        }

        /**
         * Computes points along the local horizon great circle (Alt = 0 degrees) in J2000 celestial coordinates.
         */
        fun computeHorizonPoints(lat: Double, lst: Double, steps: Int = 72): List<Pair<Double, Double>> {
            val points = mutableListOf<Pair<Double, Double>>()
            val latRad = Math.toRadians(lat)
            val cosLat = cos(latRad)
            val sinLat = sin(latRad)

            for (i in 0 until steps) {
                val azDeg = (i * 360.0) / steps
                val azRad = Math.toRadians(azDeg)
                val cosAz = cos(azRad)
                val sinAz = sin(azRad)

                val sinDec = cosLat * cosAz
                val decRad = asin(sinDec.coerceIn(-1.0, 1.0))

                val haRad = atan2(-sinAz, -sinLat * cosAz)
                val raRad = Math.toRadians(lst) - haRad
                val raDeg = (Math.toDegrees(raRad) % 360.0 + 360.0) % 360.0
                val decDeg = Math.toDegrees(decRad)
                points.add(Pair(raDeg, decDeg))
            }
            return points
        }

        /**
         * Computes the 4 Cardinal direction points (N, E, S, W) on the horizon in J2000 celestial coordinates.
         */
        fun computeCardinalPoints(lat: Double, lst: Double): List<Pair<String, Pair<Double, Double>>> {
            fun azToRaDec(azDeg: Double): Pair<Double, Double> {
                val azRad = Math.toRadians(azDeg)
                val latRad = Math.toRadians(lat)
                val sinDec = cos(latRad) * cos(azRad)
                val decRad = asin(sinDec.coerceIn(-1.0, 1.0))
                val haRad = atan2(-sin(azRad), -sin(latRad) * cos(azRad))
                val raRad = Math.toRadians(lst) - haRad
                return Pair((Math.toDegrees(raRad) % 360.0 + 360.0) % 360.0, Math.toDegrees(decRad))
            }

            return listOf(
                Pair("N", azToRaDec(0.0)),
                Pair("E", azToRaDec(90.0)),
                Pair("S", azToRaDec(180.0)),
                Pair("W", azToRaDec(270.0))
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
                Pair( halfTanX, -halfTanY),
                Pair( halfTanX,  halfTanY),
                Pair(-halfTanX,  halfTanY)
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

                val dec = asin((cosC * sinDec0 + (yRad * sinC * cosDec0 / rho)).coerceIn(-1.0, 1.0))
                val ra = ra0Rad + atan2(xRad * sinC, rho * cosDec0 * cosC - yRad * sinDec0 * sinC)

                val raDeg = (Math.toDegrees(ra) % 360.0 + 360.0) % 360.0
                val decDeg = Math.toDegrees(dec).coerceIn(-90.0, 90.0)
                celestialCorners.add(Pair(raDeg, decDeg))
            }
            return celestialCorners
        }
    }
}
