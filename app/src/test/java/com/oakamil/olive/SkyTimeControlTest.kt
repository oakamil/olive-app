// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class SkyTimeControlTest {

    @Test
    fun stepSkyTimeMillisHandlesAllFiveStepUnits() {
        val tz = TimeZone.UTC
        val base = LocalDateTime(2026, 6, 15, 22, 30).toInstant(tz).toEpochMilliseconds()

        val plusMin = stepSkyTimeMillis(base, SkyTimeStep.ONE_MINUTE, +1, tz)
        assertEquals(base + 60_000L, plusMin)
        val minusMin = stepSkyTimeMillis(base, SkyTimeStep.ONE_MINUTE, -1, tz)
        assertEquals(base - 60_000L, minusMin)

        val plusHour = stepSkyTimeMillis(base, SkyTimeStep.ONE_HOUR, +1, tz)
        assertEquals(base + 3_600_000L, plusHour)
        val minusHour = stepSkyTimeMillis(base, SkyTimeStep.ONE_HOUR, -1, tz)
        assertEquals(base - 3_600_000L, minusHour)

        val plusDay = Instant.fromEpochMilliseconds(
            stepSkyTimeMillis(base, SkyTimeStep.ONE_DAY, +1, tz)
        ).toLocalDateTime(tz)
        assertEquals(LocalDateTime(2026, 6, 16, 22, 30), plusDay)

        val minusDay = Instant.fromEpochMilliseconds(
            stepSkyTimeMillis(base, SkyTimeStep.ONE_DAY, -1, tz)
        ).toLocalDateTime(tz)
        assertEquals(LocalDateTime(2026, 6, 14, 22, 30), minusDay)

        val plusMonth = Instant.fromEpochMilliseconds(
            stepSkyTimeMillis(base, SkyTimeStep.ONE_MONTH, +1, tz)
        ).toLocalDateTime(tz)
        assertEquals(LocalDateTime(2026, 7, 15, 22, 30), plusMonth)

        val plusYear = Instant.fromEpochMilliseconds(
            stepSkyTimeMillis(base, SkyTimeStep.ONE_YEAR, +1, tz)
        ).toLocalDateTime(tz)
        assertEquals(LocalDateTime(2027, 6, 15, 22, 30), plusYear)
    }

    @Test
    fun stepSkyTimeMillisHandlesMonthEndLeapYearAndDst() {
        val utc = TimeZone.UTC
        val jan31 = LocalDateTime(2026, 1, 31, 21, 0).toInstant(utc).toEpochMilliseconds()
        val febClamped = Instant.fromEpochMilliseconds(
            stepSkyTimeMillis(jan31, SkyTimeStep.ONE_MONTH, +1, utc)
        ).toLocalDateTime(utc)
        assertEquals(LocalDateTime(2026, 2, 28, 21, 0), febClamped)

        val leapDay = LocalDateTime(2024, 2, 29, 20, 15).toInstant(utc).toEpochMilliseconds()
        val nextYear = Instant.fromEpochMilliseconds(
            stepSkyTimeMillis(leapDay, SkyTimeStep.ONE_YEAR, +1, utc)
        ).toLocalDateTime(utc)
        assertEquals(LocalDateTime(2025, 2, 28, 20, 15), nextYear)

        // Across US spring-forward DST (2026-03-08): +1d preserves local clock time,
        // +1h across 01:30 PST -> 03:30 PDT (exact 3600s elapsed, no DST skip)
        val laTz = TimeZone.of("America/Los_Angeles")
        val beforeDst = LocalDateTime(2026, 3, 7, 22, 0).toInstant(laTz).toEpochMilliseconds()
        val nextDayLocal = Instant.fromEpochMilliseconds(
            stepSkyTimeMillis(beforeDst, SkyTimeStep.ONE_DAY, +1, laTz)
        ).toLocalDateTime(laTz)
        assertEquals(LocalDateTime(2026, 3, 8, 22, 0), nextDayLocal)

        val springPreGap = LocalDateTime(2026, 3, 8, 1, 30).toInstant(laTz).toEpochMilliseconds()
        val springOneHourLater = stepSkyTimeMillis(springPreGap, SkyTimeStep.ONE_HOUR, +1, laTz)
        assertEquals(springPreGap + 3_600_000L, springOneHourLater)
        assertEquals(
            LocalDateTime(2026, 3, 8, 3, 30),
            Instant.fromEpochMilliseconds(springOneHourLater).toLocalDateTime(laTz)
        )

        // Across US fall-back DST (2026-11-01): +1h advances elapsed time by exact 3600s
        val fallPreOverlap = LocalDateTime(2026, 11, 1, 0, 30).toInstant(laTz).toEpochMilliseconds()
        val fallPlusTwoHours = stepSkyTimeMillis(
            stepSkyTimeMillis(fallPreOverlap, SkyTimeStep.ONE_HOUR, +1, laTz),
            SkyTimeStep.ONE_HOUR,
            +1,
            laTz
        )
        assertEquals(fallPreOverlap + 7_200_000L, fallPlusTwoHours)
        assertEquals(
            LocalDateTime(2026, 11, 1, 1, 30),
            Instant.fromEpochMilliseconds(fallPlusTwoHours).toLocalDateTime(laTz)
        )
    }

    @Test
    fun stepSkyTimeMillisClampsTo1900And2050AcrossNonUtcTimeZones() {
        val tokyoTz = TimeZone.of("Asia/Tokyo") // UTC+9
        val minBoundary = LocalDateTime(1900, 1, 1, 0, 0).toInstant(tokyoTz).toEpochMilliseconds()
        assertTrue(minBoundary >= MIN_SIMULATED_TIME_MS)
        val clampedMin = stepSkyTimeMillis(minBoundary, SkyTimeStep.ONE_YEAR, -1, tokyoTz)
        assertEquals(minBoundary, clampedMin)
        assertEquals(
            LocalDateTime(1900, 1, 1, 0, 0),
            Instant.fromEpochMilliseconds(clampedMin).toLocalDateTime(tokyoTz)
        )

        val laTz = TimeZone.of("America/Los_Angeles") // UTC-8
        val maxBoundary = LocalDateTime(2050, 12, 31, 23, 59).toInstant(laTz).toEpochMilliseconds()
        assertTrue(maxBoundary <= MAX_SIMULATED_TIME_MS)
        val clampedMax = stepSkyTimeMillis(maxBoundary, SkyTimeStep.ONE_YEAR, +1, laTz)
        assertEquals(maxBoundary, clampedMax)
        assertEquals(
            LocalDateTime(2050, 12, 31, 23, 59),
            Instant.fromEpochMilliseconds(clampedMax).toLocalDateTime(laTz)
        )
    }

    @Test
    fun parseLocalDateTimeMillisValidatesCalendarAndClockFormats() {
        val tz = TimeZone.UTC

        // 24-hour format
        val expected24 = LocalDateTime(2026, 11, 22, 0, 5).toInstant(tz).toEpochMilliseconds()
        assertEquals(
            expected24,
            parseLocalDateTimeMillis(
                2026,
                11,
                22,
                0,
                5,
                is24Hour = true,
                isPm = false,
                timeZone = tz
            )
        )
        assertNull(
            parseLocalDateTimeMillis(
                2026,
                11,
                22,
                24,
                0,
                is24Hour = true,
                isPm = false,
                timeZone = tz
            )
        )

        // 12-hour format: 12 AM = 00:xx, 12 PM = 12:xx, 1 PM = 13:xx
        assertEquals(
            LocalDateTime(2026, 11, 22, 0, 30).toInstant(tz).toEpochMilliseconds(),
            parseLocalDateTimeMillis(
                2026,
                11,
                22,
                12,
                30,
                is24Hour = false,
                isPm = false,
                timeZone = tz
            )
        )
        assertEquals(
            LocalDateTime(2026, 11, 22, 12, 30).toInstant(tz).toEpochMilliseconds(),
            parseLocalDateTimeMillis(
                2026,
                11,
                22,
                12,
                30,
                is24Hour = false,
                isPm = true,
                timeZone = tz
            )
        )
        assertEquals(
            LocalDateTime(2026, 11, 22, 13, 45).toInstant(tz).toEpochMilliseconds(),
            parseLocalDateTimeMillis(
                2026,
                11,
                22,
                1,
                45,
                is24Hour = false,
                isPm = true,
                timeZone = tz
            )
        )
        assertNull(
            parseLocalDateTimeMillis(
                2026,
                11,
                22,
                0,
                15,
                is24Hour = false,
                isPm = false,
                timeZone = tz
            )
        )

        // Calendar validation: 1900 is NOT a leap year, 2000 IS a leap year, Feb 30 invalid
        assertNull(
            parseLocalDateTimeMillis(
                1900,
                2,
                29,
                12,
                0,
                is24Hour = true,
                isPm = false,
                timeZone = tz
            )
        )
        assertNotNull(
            parseLocalDateTimeMillis(
                2000,
                2,
                29,
                12,
                0,
                is24Hour = true,
                isPm = false,
                timeZone = tz
            )
        )
        assertNull(
            parseLocalDateTimeMillis(
                2026,
                2,
                30,
                12,
                0,
                is24Hour = true,
                isPm = false,
                timeZone = tz
            )
        )
    }

    @Test
    fun formatSkyDateTimeFormats24HourAnd12Hour() {
        val tz = TimeZone.UTC
        val midnight = LocalDateTime(2026, 5, 9, 0, 7).toInstant(tz).toEpochMilliseconds()
        val afternoon = LocalDateTime(2026, 5, 9, 15, 42).toInstant(tz).toEpochMilliseconds()

        assertEquals(
            "2026-05-09 00:07",
            formatSkyDateTime(midnight, is24Hour = true, timeZone = tz)
        )
        assertEquals(
            "2026-05-09 12:07 AM",
            formatSkyDateTime(
                midnight,
                is24Hour = false,
                amLabel = "AM",
                pmLabel = "PM",
                timeZone = tz
            )
        )
        assertEquals(
            "2026-05-09 3:42 PM",
            formatSkyDateTime(
                afternoon,
                is24Hour = false,
                amLabel = "AM",
                pmLabel = "PM",
                timeZone = tz
            )
        )
    }

    @Test
    fun buildSearchQuerySqlReflectsSimulatedCurrentTimeMsForAboveHorizon() {
        val tz = TimeZone.UTC
        val t1 = LocalDateTime(2026, 6, 15, 22, 0).toInstant(tz).toEpochMilliseconds()
        val t2 = LocalDateTime(2026, 6, 16, 10, 0).toInstant(tz).toEpochMilliseconds() // +12h

        val (sql1, _) = CatalogDatabase.buildSearchQuerySql(
            searchQuery = "",
            catalogFilter = "M",
            sortBy = "catalog ID",
            magLimit = null,
            includeNullMagnitude = false,
            typeFilters = null,
            starFilter = null,
            visibilityFilter = "Above Horizon",
            lat = 37.7749,
            lon = -122.4194,
            targetRa = null,
            targetDec = null,
            maxDistanceDegrees = null,
            limit = 300,
            offset = 0,
            currentTimeMs = t1
        )
        val (sql2, _) = CatalogDatabase.buildSearchQuerySql(
            searchQuery = "",
            catalogFilter = "M",
            sortBy = "catalog ID",
            magLimit = null,
            includeNullMagnitude = false,
            typeFilters = null,
            starFilter = null,
            visibilityFilter = "Above Horizon",
            lat = 37.7749,
            lon = -122.4194,
            targetRa = null,
            targetDec = null,
            maxDistanceDegrees = null,
            limit = 300,
            offset = 0,
            currentTimeMs = t2
        )

        assertNotEquals(sql1, sql2)
        val (zRa1Deg, zDec1Deg) = AstrometryUtils.horizontalFrame(37.7749, -122.4194, t1)
            .zenithRaDecJ2000()
        val (zRa2Deg, zDec2Deg) = AstrometryUtils.horizontalFrame(37.7749, -122.4194, t2)
            .zenithRaDecJ2000()
        val degToRad = PI / 180.0
        val expectedZRa1Rad = zRa1Deg * degToRad
        val expectedZRa2Rad = zRa2Deg * degToRad
        val sinZDec1 = sin(zDec1Deg * degToRad)
        val cosZDec1 = cos(zDec1Deg * degToRad)
        val sinZDec2 = sin(zDec2Deg * degToRad)
        val cosZDec2 = cos(zDec2Deg * degToRad)
        assertTrue(sql1.contains("* $sinZDec1 +"))
        assertTrue(sql1.contains("* $cosZDec1 * COS($expectedZRa1Rad - ra"))
        assertTrue(sql2.contains("* $sinZDec2 +"))
        assertTrue(sql2.contains("* $cosZDec2 * COS($expectedZRa2Rad - ra"))
    }
}
