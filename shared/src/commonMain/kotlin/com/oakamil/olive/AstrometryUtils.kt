package com.oakamil.olive

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

object AstrometryUtils {
    /**
     * Projects RA/Dec to screen X/Y coordinates using Gnomic (tangent plane) projection.
     */
    fun projectToScreen(
        ra: Double,
        dec: Double,
        centerRa: Double,
        centerDec: Double,
        roll: Double,
        fov: Double,
        screenWidth: Float,
        screenHeight: Float,
        imageWidth: Int,
        imageHeight: Int,
        centerPixelX: Float = screenWidth / 2f,
        centerPixelY: Float = screenHeight / 2f,
        distortion: Double? = null
    ): Pair<Float, Float>? {
        val raRad = Math.toRadians(ra)
        val decRad = Math.toRadians(dec)
        val ra0 = Math.toRadians(centerRa)
        val dec0 = Math.toRadians(centerDec)

        val cosc = sin(dec0) * sin(decRad) + cos(dec0) * cos(decRad) * cos(raRad - ra0)

        // If cosc <= 0, the object is behind the focal plane
        if (cosc <= 0) return null

        val xRad = (cos(decRad) * sin(raRad - ra0)) / cosc
        val yRad = (cos(dec0) * sin(decRad) - sin(dec0) * cos(decRad) * cos(raRad - ra0)) / cosc

        // Calculate scale factor (pixels per radian) based on FOV.
        val fovRad = Math.toRadians(fov)
        val imageAspect = imageWidth.toDouble() / imageHeight.toDouble()

        val effectiveImageWidth = if (imageAspect > 1.0) {
            screenHeight * imageAspect
        } else {
            screenWidth.toDouble()
        }

        val scale = if (imageAspect > 1.0) {
            (screenHeight / 2.0) / tan(fovRad / 2.0)
        } else {
            (screenWidth / 2.0) / tan(fovRad / 2.0)
        }

        // Apply scale
        val xPx = -xRad * scale // RA increases East, East is Left
        val yPx = -yRad * scale // y is flipped in screen coordinates

        // Apply roll rotation
        val rollRad = Math.toRadians(-roll) // Tetra3 roll is CCW, Android Canvas is CW
        val xRot = xPx * cos(rollRad) - yPx * sin(rollRad)
        val yRot = xPx * sin(rollRad) + yPx * cos(rollRad)

        // Translate to center pixel
        var finalX = centerPixelX + xRot.toFloat()
        var finalY = centerPixelY + yRot.toFloat()

        if (distortion != null && distortion != 0.0) {
            val cx = screenWidth / 2f
            val cy = screenHeight / 2f
            val dx = finalX - cx
            val dy = finalY - cy
            val rRundist = hypot(dx, dy)
            if (rRundist > 1e-8) {
                val kp = distortion * (2.0 / effectiveImageWidth).pow(2)
                val inv1K = 1.0 / (1.0 - distortion)
                var rDist = rRundist.toDouble()
                @Suppress("unused")
                for (i in 0 until 10) {
                    val rRundistEst = rDist * (1.0 - kp * rDist.pow(2)) * inv1K
                    val druDrd = (1.0 - 3.0 * kp * rDist.pow(2)) * inv1K
                    val error = rRundist - rRundistEst
                    rDist += error / druDrd
                    if (abs(error) < 1e-4) break
                }
                val distScale = (rDist / rRundist).toFloat()
                finalX = cx + dx * distScale
                finalY = cy + dy * distScale
            }
        }

        return Pair(finalX, finalY)
    }

    fun angularDistance(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        val d1 = Math.toRadians(dec1)
        val d2 = Math.toRadians(dec2)
        val dRa = Math.toRadians(ra2 - ra1)
        val c = sin(d1) * sin(d2) + cos(d1) * cos(d2) * cos(dRa)
        return Math.toDegrees(acos(c.coerceIn(-1.0, 1.0)))
    }

    fun calculateLST(lon: Double, utcTimeMs: Long): Double {
        val jd = (utcTimeMs / 86400000.0) + 2440587.5
        val d = jd - 2451545.0
        var lst = 280.46061837 + 360.98564736629 * d + lon
        lst %= 360.0
        if (lst < 0) lst += 360.0
        return lst
    }

    fun calculateParallacticAngle(
        ra: Double,
        dec: Double,
        lat: Double,
        lst: Double
    ): Double {
        val haRad = Math.toRadians(lst - ra)
        val decRad = Math.toRadians(dec)
        val latRad = Math.toRadians(lat)

        val y = sin(haRad)
        val x = cos(decRad) * tan(latRad) - sin(decRad) * cos(haRad)

        val qRad = atan2(y, x)
        return Math.toDegrees(qRad)
    }
}
