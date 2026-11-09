// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.runtime.Composable
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

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
