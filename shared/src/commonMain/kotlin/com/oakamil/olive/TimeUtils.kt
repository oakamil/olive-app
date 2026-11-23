// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.runtime.Composable
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

const val MIN_SIMULATED_TIME_YEAR = 1900
const val MAX_SIMULATED_TIME_YEAR = 2050

// ±2-day UTC buffer around 1900-01-01..2050-12-31 (well inside DE421's 1899-07-29..2053-10-09)
// so local dates in 1900..2050 never clip in UTC+1..+14 or UTC-1..-12 timezones.
const val MIN_SIMULATED_TIME_MS = -2_209_161_600_000L // 1899-12-30T00:00:00Z
const val MAX_SIMULATED_TIME_MS = 2_556_316_799_000L  // 2051-01-02T23:59:59Z

enum class SkyTimeStep {
    ONE_MINUTE,
    ONE_HOUR,
    ONE_DAY,
    ONE_MONTH,
    ONE_YEAR
}

@Composable
expect fun is24HourFormat(): Boolean

fun formatSystemTime(
    now: LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
    is24Hour: Boolean
): String {
    val minuteStr = now.minute.toString().padStart(2, '0')
    return if (is24Hour) {
        val hourStr = now.hour.toString().padStart(2, '0')
        "$hourStr:$minuteStr"
    } else {
        val h12 = when (val h = now.hour % 12) {
            0 -> 12
            else -> h
        }
        "$h12:$minuteStr"
    }
}

fun formatLocalTimeOnly(
    epochMs: Long,
    is24Hour: Boolean = true,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
    amLabel: String = "AM",
    pmLabel: String = "PM"
): String {
    val dt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(timeZone)
    val minute = dt.minute.toString().padStart(2, '0')
    return if (is24Hour) {
        val hour = dt.hour.toString().padStart(2, '0')
        "$hour:$minute"
    } else {
        val amPm = if (dt.hour < 12) amLabel else pmLabel
        val hour12 = when (val h = dt.hour % 12) {
            0 -> 12
            else -> h
        }
        "$hour12:$minute $amPm"
    }
}

fun formatSkyDateTime(
    epochMs: Long,
    is24Hour: Boolean = true,
    amLabel: String = "AM",
    pmLabel: String = "PM",
    timeZone: TimeZone = TimeZone.currentSystemDefault()
): String {
    val dt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(timeZone)
    val year = dt.year.toString().padStart(4, '0')
    val month = dt.monthNumber.toString().padStart(2, '0')
    val day = dt.dayOfMonth.toString().padStart(2, '0')
    val timePart = formatLocalTimeOnly(
        epochMs = epochMs,
        is24Hour = is24Hour,
        timeZone = timeZone,
        amLabel = amLabel,
        pmLabel = pmLabel
    )
    return "$year-$month-$day $timePart"
}

fun daysInMonth(year: Int, month: Int): Int = when (month) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    2 -> if ((year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)) 29 else 28
    else -> 30
}

/** Validates a local calendar date/time (12h or 24h) and converts it to UTC epoch ms, or `null` if invalid. */
fun parseLocalDateTimeMillis(
    year: Int,
    month: Int,
    day: Int,
    rawHour: Int,
    minute: Int,
    is24Hour: Boolean,
    isPm: Boolean,
    timeZone: TimeZone = TimeZone.currentSystemDefault()
): Long? {
    if (year < 1 || month !in 1..12 || day !in 1..daysInMonth(year, month)) return null
    val h24 = if (is24Hour) {
        if (rawHour !in 0..23) return null
        rawHour
    } else {
        if (rawHour !in 1..12) return null
        when {
            !isPm && rawHour == 12 -> 0
            isPm && rawHour == 12 -> 12
            isPm -> rawHour + 12
            else -> rawHour
        }
    }
    if (minute !in 0..59) return null
    return runCatching {
        LocalDateTime(year, month, day, h24, minute).toInstant(timeZone).toEpochMilliseconds()
    }.getOrNull()
}

/**
 * Advances [baseEpochMs] by [direction] units of [step] (exact duration for minutes/hours,
 * DST-aware calendar periods for days/months/years), clamped to the DE421-safe `1900..2050` local range.
 */
fun stepSkyTimeMillis(
    baseEpochMs: Long,
    step: SkyTimeStep,
    direction: Int,
    timeZone: TimeZone = TimeZone.currentSystemDefault()
): Long {
    val minLocalMs = LocalDateTime(MIN_SIMULATED_TIME_YEAR, 1, 1, 0, 0)
        .toInstant(timeZone)
        .toEpochMilliseconds()
        .coerceIn(MIN_SIMULATED_TIME_MS, MAX_SIMULATED_TIME_MS)
    val maxLocalMs = LocalDateTime(MAX_SIMULATED_TIME_YEAR, 12, 31, 23, 59)
        .toInstant(timeZone)
        .toEpochMilliseconds()
        .coerceIn(MIN_SIMULATED_TIME_MS, MAX_SIMULATED_TIME_MS)

    val baseInstant = Instant.fromEpochMilliseconds(baseEpochMs)
    val steppedInstant = when (step) {
        SkyTimeStep.ONE_MINUTE -> baseInstant + direction.minutes
        SkyTimeStep.ONE_HOUR -> baseInstant + direction.hours
        SkyTimeStep.ONE_DAY -> baseInstant.plus(DateTimePeriod(days = direction), timeZone)
        SkyTimeStep.ONE_MONTH -> baseInstant.plus(DateTimePeriod(months = direction), timeZone)
        SkyTimeStep.ONE_YEAR -> baseInstant.plus(DateTimePeriod(years = direction), timeZone)
    }
    return steppedInstant.toEpochMilliseconds().coerceIn(minLocalMs, maxLocalMs)
}
