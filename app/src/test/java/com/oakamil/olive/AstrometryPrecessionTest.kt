// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class AstrometryPrecessionTest {

    private fun utcEpochMs(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 0,
        minute: Int = 0
    ): Long = LocalDateTime(year, month, day, hour, minute)
        .toInstant(TimeZone.UTC)
        .toEpochMilliseconds()

    private fun angularDiffDeg(a: Double, b: Double): Double =
        abs(((a - b) % 360.0 + 540.0) % 360.0 - 180.0)

    @Test
    fun skyHorizontalFrameMatchesUnprecessedAtJ2000() {
        val j2000Ms = utcEpochMs(2000, 1, 1, 12, 0)
        for ((lat, lon) in listOf(37.7749 to -122.4194, 0.0 to 0.0)) {
            val lst = AstrometryUtils.calculateLST(lon, j2000Ms)
            val precessed = AstrometryUtils.horizontalFrame(lat, lon, j2000Ms)
            val unprecessed = SkyHorizontalFrame.unprecessed(lat, lst)

            assertEquals(unprecessed, precessed)
            val (azP, altP) = precessed.raDecToAltAz(83.822, -5.391)
            val (azU, altU) = unprecessed.raDecToAltAz(83.822, -5.391)
            assertEquals(azU, azP, 1e-9)
            assertEquals(altU, altP, 1e-9)
        }
    }

    @Test
    fun skyHorizontalFrameBasisIsOrthonormalAcross1900To2050() {
        val epochs = listOf(
            utcEpochMs(1900, 1, 1),
            utcEpochMs(1950, 7, 1),
            utcEpochMs(2000, 1, 1, 12, 0),
            utcEpochMs(2026, 11, 22),
            utcEpochMs(2050, 12, 31, 23, 59)
        )
        for (epochMs in epochs) {
            val f = AstrometryUtils.horizontalFrame(37.7749, -122.4194, epochMs)
            assertEquals(1.0, f.nx * f.nx + f.ny * f.ny + f.nz * f.nz, 1e-12)
            assertEquals(1.0, f.ex * f.ex + f.ey * f.ey + f.ez * f.ez, 1e-12)
            assertEquals(1.0, f.ux * f.ux + f.uy * f.uy + f.uz * f.uz, 1e-12)
            assertEquals(0.0, f.nx * f.ex + f.ny * f.ey + f.nz * f.ez, 1e-12)
            assertEquals(0.0, f.nx * f.ux + f.ny * f.uy + f.nz * f.uz, 1e-12)
            assertEquals(0.0, f.ex * f.ux + f.ey * f.uy + f.ez * f.uz, 1e-12)
        }
    }

    @Test
    fun skyHorizontalFrameRoundTripsAcrossEpochsAndPoles() {
        val epochs = listOf(
            utcEpochMs(1900, 6, 15, 22, 0),
            utcEpochMs(2000, 1, 1, 12, 0),
            utcEpochMs(2026, 11, 22, 20, 0),
            utcEpochMs(2050, 12, 1, 4, 0)
        )
        val locations = listOf(
            37.7749 to -122.4194,
            -33.8688 to 151.2093,
            0.0 to 0.0,
            90.0 to 0.0,
            -90.0 to 45.0
        )
        val targets = listOf(
            10.6847 to 41.2687,
            37.9545 to 89.2641,
            266.4168 to -29.0078,
            180.0 to -85.0,
            0.0 to 0.0
        )
        for (epochMs in epochs) {
            for ((lat, lon) in locations) {
                val frame = AstrometryUtils.horizontalFrame(lat, lon, epochMs)
                for ((ra, dec) in targets) {
                    val (az, alt) = frame.raDecToAltAz(ra, dec)
                    assertFalse(az.isNaN())
                    assertFalse(alt.isNaN())
                    val (rtRa, rtDec) = frame.altAzToRaDec(az, alt)
                    assertFalse(rtRa.isNaN())
                    assertFalse(rtDec.isNaN())
                    assertEquals("Dec round-trip failed", dec, rtDec, 1e-7)
                    // Scale RA tolerance near celestial poles where cos(dec) is tiny
                    val raTol = 1e-6 / cos(dec * PI / 180.0).coerceAtLeast(1e-3)
                    assertTrue(
                        "RA round-trip failed: expected=$ra got=$rtRa",
                        angularDiffDeg(ra, rtRa) < raTol
                    )
                }
            }
        }
    }

    @Test
    fun polarisPrecessesCloserToTrueNorthCelestialPoleFrom1900To2100() {
        // At the North Pole (lat = +90), a star's altitude equals its exact declination of date.
        val polarisRa = 37.9545
        val polarisDec = 89.2641

        fun poleDistanceArcmin(year: Int): Double {
            val frame = AstrometryUtils.horizontalFrame(90.0, 0.0, utcEpochMs(year, 1, 1, 12, 0))
            val (_, altDeg) = frame.raDecToAltAz(polarisRa, polarisDec)
            return (90.0 - altDeg) * 60.0
        }

        val d1900 = poleDistanceArcmin(1900)
        val d2000 = poleDistanceArcmin(2000)
        val d2026 = poleDistanceArcmin(2026)
        val d2050 = poleDistanceArcmin(2050)
        val d2100 = poleDistanceArcmin(2100)

        assertEquals(73.5, d1900, 1.0)
        assertEquals(44.15, d2000, 0.05)
        assertEquals(37.6, d2026, 0.5)
        assertEquals(32.3, d2050, 0.5)
        assertEquals(27.6, d2100, 0.5)
        assertTrue(d1900 > d2000 && d2000 > d2026 && d2026 > d2050 && d2050 > d2100)
    }

    @Test
    fun vernalEquinoxShiftsByExpectedLieskeRates() {
        // Choose lon so observer LST == 0 at lat == 0 -> zenith is the mean vernal equinox of date.
        fun equinoxOfDateInJ2000(year: Int): Pair<Double, Double> {
            val epochMs = utcEpochMs(year, 1, 1, 12, 0)
            val gmst = AstrometryUtils.calculateLST(0.0, epochMs)
            val lon = if (gmst > 180.0) 360.0 - gmst else -gmst
            return AstrometryUtils.horizontalFrame(0.0, lon, epochMs).zenithRaDecJ2000()
        }

        val (ra2100, dec2100) = equinoxOfDateInJ2000(2100)
        val signedRa2100 = if (ra2100 > 180.0) ra2100 - 360.0 else ra2100
        assertEquals(-1.281, signedRa2100, 0.01)
        assertEquals(-0.557, dec2100, 0.01)

        val (ra1900, dec1900) = equinoxOfDateInJ2000(1900)
        val signedRa1900 = if (ra1900 > 180.0) ra1900 - 360.0 else ra1900
        assertEquals(1.281, signedRa1900, 0.01)
        assertEquals(0.557, dec1900, 0.01)
    }

    @Test
    fun getSunCoordinatesReturnsJ2000AlignedWithSolarEclipses() {
        // 2024-04-08 18:18 UTC total solar eclipse (geocentric Sun J2000: RA ~17.59 deg, Dec ~+7.46 deg;
        // of-date is ~17.91 deg, +7.59 deg — a ~0.34 deg shift).
        val (ra2024, dec2024) = AstrometryUtils.getSunCoordinates(utcEpochMs(2024, 4, 8, 18, 18))
        assertEquals(17.59, ra2024, 0.05)
        assertEquals(7.46, dec2024, 0.05)

        // 1900-05-28 14:50 UTC total solar eclipse (geocentric Sun J2000: RA ~66.41 deg, Dec ~+21.68 deg;
        // of-date is ~64.93 deg, +21.45 deg — a ~1.49 deg shift).
        val (ra1900, dec1900) = AstrometryUtils.getSunCoordinates(utcEpochMs(1900, 5, 28, 14, 50))
        assertEquals(66.41, ra1900, 0.05)
        assertEquals(21.68, dec1900, 0.05)
    }

    @Test
    fun eqToScreenProjectorMatchesPrecessedSkyHorizontalFrameWithNonZeroEz() {
        val epochs = listOf(
            utcEpochMs(1900, 1, 15, 4, 0),
            utcEpochMs(2050, 7, 15, 22, 0)
        )
        val samples = listOf(
            83.8 to -5.4,
            266.4 to -29.0,
            10.68 to 41.27,
            37.95 to 89.26,
            0.0 to 0.0,
            359.5 to -65.0
        )
        val width = 1080f
        val height = 2200f
        val fov = 55.0
        val coords = FloatArray(2)

        for (epochMs in epochs) {
            val frame = AstrometryUtils.horizontalFrame(37.3382, -121.8863, epochMs)
            assertTrue("Expected non-zero precessed ez at $epochMs", abs(frame.ez) > 1e-4)

            for ((centerAz, centerAlt) in listOf(35.0 to 25.0, 195.0 to 42.0, 290.0 to 65.0)) {
                val skyProj = SkyMapProjection(centerAz, centerAlt, fov, width, height)
                val eqProj = EqToScreenProjector().apply {
                    configure(centerAz, centerAlt, fov, width, height, frame)
                }

                for ((ra, dec) in samples) {
                    val (az, alt) = frame.raDecToAltAz(ra, dec)
                    val expected = skyProj.toScreenOffset(az, alt)
                    val raRad = ra * PI / 180.0
                    val decRad = dec * PI / 180.0
                    val qx = cos(decRad) * cos(raRad)
                    val qy = cos(decRad) * sin(raRad)
                    val qz = sin(decRad)
                    val sinAlt = eqProj.project(qx, qy, qz, coords, 0)
                    if (expected.x.isNaN()) {
                        assertTrue(sinAlt.isNaN())
                    } else {
                        assertFalse(sinAlt.isNaN())
                        assertEquals("X mismatch for ($ra,$dec)", expected.x, coords[0], 1e-2f)
                        assertEquals("Y mismatch for ($ra,$dec)", expected.y, coords[1], 1e-2f)
                        assertEquals(
                            "sinAlt mismatch for ($ra,$dec)",
                            sin(alt * PI / 180.0),
                            sinAlt,
                            1e-9
                        )
                    }
                }
            }
        }
    }
}
