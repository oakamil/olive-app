package com.oakamil.olive

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.tan

data class AltAz(val alt: Double, val az: Double)

object AstrometryUtils {
    /** Formats RA in degrees (wrapping outside [0, 360)) as `HHh MMm SSs`. */
    fun formatRa(raDegrees: Double): String {
        if (!raDegrees.isFinite()) return ""
        val normalizedRa = ((raDegrees % 360.0) + 360.0) % 360.0
        val totalSeconds = round(normalizedRa * 240.0).toInt() % 86400
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return String.format("%02dh %02dm %02ds", h, m, s)
    }

    /** Formats Dec in degrees as `±DD° MM' SS"`. */
    fun formatDec(decDegrees: Double): String {
        if (!decDegrees.isFinite()) return ""
        val totalArcseconds = round(abs(decDegrees) * 3600.0).toLong()
        val sign = if (decDegrees < 0 && totalArcseconds > 0L) "-" else "+"
        val d = totalArcseconds / 3600L
        val m = (totalArcseconds % 3600L) / 60L
        val s = totalArcseconds % 60L
        return String.format("%s%02d° %02d' %02d\"", sign, d, m, s)
    }

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
        if (
            imageWidth <= 0 || imageHeight <= 0 ||
            screenWidth <= 0f || screenHeight <= 0f ||
            !fov.isFinite() || fov <= 0.0 || fov >= 180.0
        ) {
            return null
        }

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
        val tanHalfFov = tan(fovRad / 2.0)
        if (!tanHalfFov.isFinite() || tanHalfFov <= 1e-9) return null

        val imageAspect = imageWidth.toDouble() / imageHeight.toDouble()

        val effectiveImageWidth = if (imageAspect > 1.0) {
            screenHeight * imageAspect
        } else {
            screenWidth.toDouble()
        }

        val scale = if (imageAspect > 1.0) {
            (screenHeight / 2.0) / tanHalfFov
        } else {
            (screenWidth / 2.0) / tanHalfFov
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

        if (distortion != null && distortion != 0.0 && abs(1.0 - distortion) > 1e-9) {
            val cx = screenWidth / 2f
            val cy = screenHeight / 2f
            val dx = finalX - cx
            val dy = finalY - cy
            val rRundist = hypot(dx, dy)
            if (rRundist > 1e-8) {
                val kp = distortion * (2.0 / effectiveImageWidth).pow(2)
                val inv1K = 1.0 / (1.0 - distortion)
                var rDist = rRundist.toDouble()
                var converged = true
                @Suppress("unused")
                for (i in 0 until 10) {
                    val rRundistEst = rDist * (1.0 - kp * rDist.pow(2)) * inv1K
                    val druDrd = (1.0 - 3.0 * kp * rDist.pow(2)) * inv1K
                    if (!druDrd.isFinite() || abs(druDrd) <= 1e-12) {
                        converged = false
                        break
                    }
                    val error = rRundist - rRundistEst
                    rDist += error / druDrd
                    if (!rDist.isFinite()) {
                        converged = false
                        break
                    }
                    if (abs(error) < 1e-4) break
                }
                if (converged) {
                    val distScale = (rDist / rRundist).toFloat()
                    if (distScale.isFinite()) {
                        finalX = cx + dx * distScale
                        finalY = cy + dy * distScale
                    }
                }
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

    /**
     * Converts celestial coordinates (RA, Dec in degrees J2000) to horizontal coordinates (Azimuth, Altitude in degrees).
     * Azimuth: North = 0°, East = 90°, South = 180°, West = 270°.
     * Altitude: -90° (Nadir) to +90° (Zenith).
     */
    fun raDecToAltAz(ra: Double, dec: Double, lat: Double, lst: Double): Pair<Double, Double> {
        val haRad = Math.toRadians((lst - ra) % 360.0)
        val decRad = Math.toRadians(dec)
        val latRad = Math.toRadians(lat)

        val sinAlt = sin(latRad) * sin(decRad) + cos(latRad) * cos(decRad) * cos(haRad)
        val altRad = asin(sinAlt.coerceIn(-1.0, 1.0))

        val y = -cos(decRad) * sin(haRad)
        val x = cos(latRad) * sin(decRad) - sin(latRad) * cos(decRad) * cos(haRad)
        val azRad = atan2(y, x)

        val azDeg = (Math.toDegrees(azRad) % 360.0 + 360.0) % 360.0
        val altDeg = Math.toDegrees(altRad)
        return Pair(azDeg, altDeg)
    }

    /**
     * Converts celestial coordinates (RA, Dec in degrees J2000) to horizontal [AltAz]
     * using precomputed local sidereal time [lst] (degrees).
     */
    fun calculateAltAz(ra: Double, dec: Double, lat: Double, lst: Double): AltAz {
        val (az, alt) = raDecToAltAz(ra, dec, lat, lst)
        return AltAz(alt = alt, az = az)
    }

    /**
     * Converts celestial coordinates (RA, Dec in degrees J2000) to horizontal [AltAz]
     * for an observer at [lat], [lon] (degrees) and timestamp [timeMs] (UTC ms).
     */
    fun calculateAltAz(
        ra: Double,
        dec: Double,
        lat: Double,
        lon: Double,
        timeMs: Long
    ): AltAz = calculateAltAz(ra, dec, lat, calculateLST(lon, timeMs))

    /**
     * Returns the 16-point compass sector index (`0..15`, starting at North `0°` clockwise in `22.5°` steps).
     */
    fun azimuthSectorIndex16(azimuthDeg: Double): Int {
        val normalized = ((azimuthDeg % 360.0) + 360.0) % 360.0
        return (((normalized + 11.25) / 22.5).toInt()) and 0x0F
    }

    /**
     * Converts horizontal coordinates (Azimuth, Altitude in degrees) to celestial coordinates (RA, Dec in degrees J2000).
     */
    fun altAzToRaDec(az: Double, alt: Double, lat: Double, lst: Double): Pair<Double, Double> {
        val azRad = Math.toRadians(az)
        val altRad = Math.toRadians(alt)
        val latRad = Math.toRadians(lat)

        val sinDec = sin(latRad) * sin(altRad) + cos(latRad) * cos(altRad) * cos(azRad)
        val decRad = asin(sinDec.coerceIn(-1.0, 1.0))

        val y = -cos(altRad) * sin(azRad)
        val x = cos(latRad) * sin(altRad) - sin(latRad) * cos(altRad) * cos(azRad)
        val haRad = atan2(y, x)

        val raDeg = ((lst - Math.toDegrees(haRad)) % 360.0 + 360.0) % 360.0
        val decDeg = Math.toDegrees(decRad).coerceIn(-90.0, 90.0)
        return Pair(raDeg, decDeg)
    }

    /**
     * Computes the Sun's geocentric equatorial coordinates (RA, Dec in degrees)
     * using the standard Astronomical Almanac low-precision algorithm (~0.01° accuracy).
     */
    fun getSunCoordinates(epochMs: Long): Pair<Double, Double> {
        val jd = 2440587.5 + epochMs / 86400000.0
        val n = jd - 2451545.0
        val l = (280.460 + 0.9856474 * n) % 360.0
        val gRad = Math.toRadians((357.528 + 0.9856003 * n) % 360.0)
        val lambdaRad = Math.toRadians((l + 1.915 * sin(gRad) + 0.020 * sin(2 * gRad)) % 360.0)
        val epsRad = Math.toRadians(23.439 - 0.0000004 * n)

        val sinDec = sin(epsRad) * sin(lambdaRad)
        val decRad = asin(sinDec.coerceIn(-1.0, 1.0))

        val y = cos(epsRad) * sin(lambdaRad)
        val x = cos(lambdaRad)
        val raRad = atan2(y, x)
        val raDeg = (Math.toDegrees(raRad) % 360.0 + 360.0) % 360.0
        val decDeg = Math.toDegrees(decRad)
        return Pair(raDeg, decDeg)
    }

    /**
     * Creates a CatalogObject representing the Sun at the given epoch timestamp.
     */
    fun getSun(epochMs: Long): CatalogObject {
        val (ra, dec) = getSunCoordinates(epochMs)
        return CatalogObject(
            id = -10,
            catalogLabel = "Solar System",
            catalogEntry = "Sun",
            ra = ra,
            dec = dec,
            constellation = null,
            objectType = "Sun",
            broadCategory = "Sun",
            magnitude = -26.74,
            surfaceBrightness = null,
            angularSize = "32'",
            commonName = "Sun",
            description = "The Sun",
            copyright = null
        )
    }
}
