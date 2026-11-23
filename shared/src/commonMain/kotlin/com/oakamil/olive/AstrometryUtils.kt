package com.oakamil.olive

import androidx.compose.runtime.Immutable
import kotlin.math.PI
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

private const val DEG_TO_RAD = PI / 180.0
private const val RAD_TO_DEG = 180.0 / PI
private const val ARCSEC_TO_RAD = PI / (180.0 * 3600.0)
private const val J2000_EPOCH_MS = 946_728_000_000L

private fun normalizeDeg360(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

private fun vectorToRaDec(x: Double, y: Double, z: Double): Pair<Double, Double> {
    val decDeg = (asin(z.coerceIn(-1.0, 1.0)) * RAD_TO_DEG).coerceIn(-90.0, 90.0)
    val raDeg = normalizeDeg360(atan2(y, x) * RAD_TO_DEG)
    return Pair(raDeg, decDeg)
}

data class AltAz(val alt: Double, val az: Double)

/**
 * Orthogonal 3x3 IAU 1976 / Lieske (Meeus Ch. 21) equatorial precession matrix `P`
 * mapping J2000/ICRS unit vectors to Mean Equator and Equinox of Date (`v_date = P * v_J2000`).
 * Inverse (`date -> J2000`) is the exact transpose `P^T`.
 */
private class PrecessionMatrix(
    private val m00: Double, private val m01: Double, private val m02: Double,
    private val m10: Double, private val m11: Double, private val m12: Double,
    private val m20: Double, private val m21: Double, private val m22: Double
) {
    fun dateToJ2000(x: Double, y: Double, z: Double): Triple<Double, Double, Double> {
        if (this === IDENTITY) return Triple(x, y, z)
        return Triple(
            m00 * x + m10 * y + m20 * z,
            m01 * x + m11 * y + m21 * z,
            m02 * x + m12 * y + m22 * z
        )
    }

    companion object {
        val IDENTITY = PrecessionMatrix(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0
        )

        fun forEpochMs(utcTimeMs: Long): PrecessionMatrix {
            if (utcTimeMs == J2000_EPOCH_MS) return IDENTITY
            val t = (utcTimeMs - J2000_EPOCH_MS) / (36525.0 * 86_400_000.0)
            val t2 = t * t
            val t3 = t2 * t
            val zeta = (2306.2181 * t + 0.30188 * t2 + 0.017998 * t3) * ARCSEC_TO_RAD
            val z = (2306.2181 * t + 1.09468 * t2 + 0.018203 * t3) * ARCSEC_TO_RAD
            val theta = (2004.3109 * t - 0.42665 * t2 - 0.041833 * t3) * ARCSEC_TO_RAD

            val cz = cos(z)
            val sz = sin(z)
            val ct = cos(theta)
            val st = sin(theta)
            val czeta = cos(zeta)
            val szeta = sin(zeta)

            return PrecessionMatrix(
                m00 = cz * ct * czeta - sz * szeta,
                m01 = -cz * ct * szeta - sz * czeta,
                m02 = -cz * st,
                m10 = sz * ct * czeta + cz * szeta,
                m11 = -sz * ct * szeta + cz * czeta,
                m12 = -sz * st,
                m20 = st * czeta,
                m21 = -st * szeta,
                m22 = ct
            )
        }
    }
}

/**
 * Observer's local horizontal `(North, East, Up)` orthonormal basis expressed directly
 * in J2000/ICRS equatorial coordinates, pre-rotated by `P^T` for the observer's epoch.
 */
@Immutable
data class SkyHorizontalFrame(
    val nx: Double, val ny: Double, val nz: Double,
    val ex: Double, val ey: Double, val ez: Double,
    val ux: Double, val uy: Double, val uz: Double
) {
    /** Converts J2000 `(raDeg, decDeg)` to precessed horizontal `(azDeg, altDeg)`. */
    fun raDecToAltAz(raDeg: Double, decDeg: Double): Pair<Double, Double> {
        val raRad = raDeg * DEG_TO_RAD
        val decRad = decDeg * DEG_TO_RAD
        val cosDec = cos(decRad)
        val vx = cosDec * cos(raRad)
        val vy = cosDec * sin(raRad)
        val vz = sin(decRad)

        val n = nx * vx + ny * vy + nz * vz
        val e = ex * vx + ey * vy + ez * vz
        val u = ux * vx + uy * vy + uz * vz

        val altDeg = (asin(u.coerceIn(-1.0, 1.0)) * RAD_TO_DEG).coerceIn(-90.0, 90.0)
        val azDeg = normalizeDeg360(atan2(e, n) * RAD_TO_DEG)
        return Pair(azDeg, altDeg)
    }

    /** Converts J2000 `(raDeg, decDeg)` to precessed horizontal [AltAz]. */
    fun calculateAltAz(raDeg: Double, decDeg: Double): AltAz {
        val (az, alt) = raDecToAltAz(raDeg, decDeg)
        return AltAz(alt = alt, az = az)
    }

    /** Converts horizontal `(azDeg, altDeg)` to J2000 `(raDeg, decDeg)`. */
    fun altAzToRaDec(azDeg: Double, altDeg: Double): Pair<Double, Double> {
        val azRad = azDeg * DEG_TO_RAD
        val altRad = altDeg * DEG_TO_RAD
        val cosAlt = cos(altRad)
        val fn = cosAlt * cos(azRad)
        val fe = cosAlt * sin(azRad)
        val fu = sin(altRad)

        val vx = fn * nx + fe * ex + fu * ux
        val vy = fn * ny + fe * ey + fu * uy
        val vz = fn * nz + fe * ez + fu * uz
        return vectorToRaDec(vx, vy, vz)
    }

    /** Fast altitude-sign check (`sin(alt) > 0`) for J2000 `(raDeg, decDeg)`. */
    fun isAboveHorizon(raDeg: Double, decDeg: Double): Boolean {
        val raRad = raDeg * DEG_TO_RAD
        val decRad = decDeg * DEG_TO_RAD
        val cosDec = cos(decRad)
        return ux * cosDec * cos(raRad) + uy * cosDec * sin(raRad) + uz * sin(decRad) > 0.0
    }

    /** Returns the observer's zenith expressed in J2000 `(raDeg, decDeg)`. */
    fun zenithRaDecJ2000(): Pair<Double, Double> = vectorToRaDec(ux, uy, uz)

    companion object {
        fun unprecessed(latDeg: Double, lstDeg: Double): SkyHorizontalFrame {
            val latRad = latDeg * DEG_TO_RAD
            val lstRad = lstDeg * DEG_TO_RAD
            val sinLat = sin(latRad)
            val cosLat = cos(latRad)
            val sinLst = sin(lstRad)
            val cosLst = cos(lstRad)
            return SkyHorizontalFrame(
                nx = -sinLat * cosLst,
                ny = -sinLat * sinLst,
                nz = cosLat,
                ex = -sinLst,
                ey = cosLst,
                ez = 0.0,
                ux = cosLat * cosLst,
                uy = cosLat * sinLst,
                uz = sinLat
            )
        }
    }
}

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

    fun horizontalFrame(latDeg: Double, lonDeg: Double, utcTimeMs: Long): SkyHorizontalFrame {
        val lstDeg = calculateLST(lonDeg, utcTimeMs)
        val date = SkyHorizontalFrame.unprecessed(latDeg, lstDeg)
        val precession = PrecessionMatrix.forEpochMs(utcTimeMs)
        if (precession === PrecessionMatrix.IDENTITY) return date

        val (nx, ny, nz) = precession.dateToJ2000(date.nx, date.ny, date.nz)
        val (ex, ey, ez) = precession.dateToJ2000(date.ex, date.ey, date.ez)
        val (ux, uy, uz) = precession.dateToJ2000(date.ux, date.uy, date.uz)
        return SkyHorizontalFrame(
            nx = nx, ny = ny, nz = nz,
            ex = ex, ey = ey, ez = ez,
            ux = ux, uy = uy, uz = uz
        )
    }

    /**
     * Converts celestial coordinates (RA, Dec in degrees) to horizontal coordinates (Azimuth, Altitude in degrees)
     * without precession (for epoch-independent or J2000-aligned calls).
     * Azimuth: North = 0°, East = 90°, South = 180°, West = 270°.
     * Altitude: -90° (Nadir) to +90° (Zenith).
     */
    fun raDecToAltAz(ra: Double, dec: Double, lat: Double, lst: Double): Pair<Double, Double> =
        SkyHorizontalFrame.unprecessed(lat, lst).raDecToAltAz(ra, dec)

    /**
     * Converts celestial coordinates (RA, Dec in degrees J2000) to precessed horizontal [AltAz]
     * for an observer at [lat], [lon] (degrees) and timestamp [timeMs] (UTC ms).
     */
    fun calculateAltAz(
        ra: Double,
        dec: Double,
        lat: Double,
        lon: Double,
        timeMs: Long
    ): AltAz = horizontalFrame(lat, lon, timeMs).calculateAltAz(ra, dec)

    /**
     * Returns the 16-point compass sector index (`0..15`, starting at North `0°` clockwise in `22.5°` steps).
     */
    fun azimuthSectorIndex16(azimuthDeg: Double): Int {
        val normalized = ((azimuthDeg % 360.0) + 360.0) % 360.0
        return (((normalized + 11.25) / 22.5).toInt()) and 0x0F
    }

    /**
     * Converts horizontal coordinates (Azimuth, Altitude in degrees) to unprecessed equatorial coordinates (RA, Dec in degrees).
     */
    fun altAzToRaDec(az: Double, alt: Double, lat: Double, lst: Double): Pair<Double, Double> =
        SkyHorizontalFrame.unprecessed(lat, lst).altAzToRaDec(az, alt)

    /**
     * Computes the Sun's geocentric J2000 equatorial coordinates (RA, Dec in degrees)
     * using the Astronomical Almanac low-precision algorithm (~0.01° accuracy) precessed
     * from mean equinox of date back to J2000 so it aligns with J2000 stars, HiPS, and DE421 Moon/planets.
     */
    fun getSunCoordinates(epochMs: Long): Pair<Double, Double> {
        val jd = 2440587.5 + epochMs / 86400000.0
        val n = jd - 2451545.0
        val l = (280.460 + 0.9856474 * n) % 360.0
        val gRad = ((357.528 + 0.9856003 * n) % 360.0) * DEG_TO_RAD
        val lambdaRad = ((l + 1.915 * sin(gRad) + 0.020 * sin(2 * gRad)) % 360.0) * DEG_TO_RAD
        val epsRad = (23.439 - 0.0000004 * n) * DEG_TO_RAD

        // Unit vector in Mean Equator & Equinox of Date
        val sinLambda = sin(lambdaRad)
        val xDate = cos(lambdaRad)
        val yDate = cos(epsRad) * sinLambda
        val zDate = sin(epsRad) * sinLambda

        val (xJ2000, yJ2000, zJ2000) = PrecessionMatrix.forEpochMs(epochMs)
            .dateToJ2000(xDate, yDate, zDate)
        return vectorToRaDec(xJ2000, yJ2000, zJ2000)
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
