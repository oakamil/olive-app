// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

internal enum class DateTimeInputField {
    YEAR, MONTH, DAY, HOUR, MINUTE
}

internal data class DateTimeParts(
    val year: String,
    val month: String,
    val day: String,
    val hour: String,
    val minute: String,
    val isPm: Boolean
)

internal fun epochMsToDateTimeParts(
    epochMs: Long,
    is24Hour: Boolean,
    timeZone: TimeZone
): DateTimeParts {
    val dt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(timeZone)
    val hourVal = if (is24Hour) {
        dt.hour
    } else {
        val h12 = dt.hour % 12
        if (h12 == 0) 12 else h12
    }
    return DateTimeParts(
        year = dt.year.toString().padStart(4, '0'),
        month = dt.monthNumber.toString().padStart(2, '0'),
        day = dt.dayOfMonth.toString().padStart(2, '0'),
        hour = hourVal.toString().padStart(2, '0'),
        minute = dt.minute.toString().padStart(2, '0'),
        isPm = dt.hour >= 12
    )
}

internal fun appendBoundedDigits(
    current: String,
    char: Char,
    replaceExisting: Boolean,
    maxLen: Int,
    maxValue: Int
): Pair<String, Boolean> {
    if (char !in '0'..'9') return current to replaceExisting
    val base = if (replaceExisting) "" else current
    val next = base + char
    if (next.length > maxLen) return current to replaceExisting
    val parsed = next.toIntOrNull() ?: return current to replaceExisting
    return if (parsed <= maxValue) {
        next to false
    } else {
        current to replaceExisting
    }
}

@Composable
internal fun DateTimeInputRows(
    yearStr: String,
    monthStr: String,
    dayStr: String,
    hourStr: String,
    minuteStr: String,
    is24Hour: Boolean,
    isPm: Boolean,
    focusedField: DateTimeInputField?,
    onFocusField: (DateTimeInputField) -> Unit,
    onToggleAmPm: () -> Unit
) {
    val extColors = LocalOliveExtendedColors.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoordinateBox(
            value = yearStr,
            isFocused = focusedField == DateTimeInputField.YEAR,
            suffix = "",
            placeholder = stringResource(Res.string.placeholder_year),
            onClick = { onFocusField(DateTimeInputField.YEAR) }
        )
        Text("-", color = extColors.secondaryText)
        CoordinateBox(
            value = monthStr,
            isFocused = focusedField == DateTimeInputField.MONTH,
            suffix = "",
            placeholder = stringResource(Res.string.placeholder_month),
            onClick = { onFocusField(DateTimeInputField.MONTH) }
        )
        Text("-", color = extColors.secondaryText)
        CoordinateBox(
            value = dayStr,
            isFocused = focusedField == DateTimeInputField.DAY,
            suffix = "",
            placeholder = stringResource(Res.string.placeholder_day),
            onClick = { onFocusField(DateTimeInputField.DAY) }
        )
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoordinateBox(
            value = hourStr,
            isFocused = focusedField == DateTimeInputField.HOUR,
            suffix = "",
            placeholder = stringResource(Res.string.placeholder_hour),
            onClick = { onFocusField(DateTimeInputField.HOUR) }
        )
        Text(":", color = extColors.secondaryText)
        CoordinateBox(
            value = minuteStr,
            isFocused = focusedField == DateTimeInputField.MINUTE,
            suffix = "",
            placeholder = stringResource(Res.string.placeholder_minute),
            onClick = { onFocusField(DateTimeInputField.MINUTE) }
        )
        if (!is24Hour) {
            Spacer(Modifier.width(4.dp))
            val amPmShape = RoundedCornerShape(4.dp)
            Box(
                modifier = Modifier
                    .clip(amPmShape)
                    .clickable(role = Role.Button, onClick = onToggleAmPm)
                    .border(1.dp, MaterialTheme.colorScheme.primary, amPmShape)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(if (isPm) Res.string.label_pm else Res.string.label_am),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun rememberFormattedSkyDateTime(epochMs: Long, is24Hour: Boolean): String {
    val amLabel = stringResource(Res.string.label_am)
    val pmLabel = stringResource(Res.string.label_pm)
    return remember(epochMs, is24Hour, amLabel, pmLabel) {
        formatSkyDateTime(
            epochMs = epochMs,
            is24Hour = is24Hour,
            amLabel = amLabel,
            pmLabel = pmLabel
        )
    }
}

@Composable
fun SkyTimeControlOverlay(
    isExpanded: Boolean,
    isSimulatedActive: Boolean,
    effectiveTimeMs: Long,
    selectedStep: SkyTimeStep,
    isReversed: Boolean,
    is24Hour: Boolean,
    onToggleExpanded: () -> Unit,
    onSelectStep: (SkyTimeStep) -> Unit,
    onStep: (direction: Int) -> Unit,
    onOpenPicker: () -> Unit,
    onResetNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    val extColors = LocalOliveExtendedColors.current
    val cardSurface = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)

    if (!isExpanded) {
        val timeButton: @Composable () -> Unit = {
            OutlinedIconButton(
                onClick = onToggleExpanded,
                border = BorderStroke(
                    1.dp,
                    if (isSimulatedActive) MaterialTheme.colorScheme.primary else extColors.mapButtonBorder
                ),
                colors = IconButtonDefaults.outlinedIconButtonColors(
                    containerColor = if (isSimulatedActive) {
                        extColors.mapButtonActiveSurface
                    } else {
                        extColors.mapOverlaySurface
                    }
                ),
                shape = CircleShape,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Schedule,
                    contentDescription = stringResource(Res.string.cd_sky_time),
                    tint = if (isSimulatedActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        }

        if (!isSimulatedActive) {
            Box(modifier = modifier) {
                timeButton()
            }
        } else {
            val formattedDateTime = rememberFormattedSkyDateTime(effectiveTimeMs, is24Hour)
            val statusPill: @Composable (Modifier) -> Unit = { pillModifier ->
                Card(
                    onClick = onToggleExpanded,
                    modifier = pillModifier.heightIn(min = 40.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = cardSurface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .heightIn(min = 40.dp)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = formattedDateTime,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Row(
                modifier = modifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isReversed) {
                    CompactNowChip(
                        isAtNow = false,
                        onClick = onResetNow,
                        minHeight = 40.dp,
                        outlinedSurfaceColor = cardSurface
                    )
                    statusPill(Modifier.weight(1f, fill = false))
                    timeButton()
                } else {
                    timeButton()
                    statusPill(Modifier.weight(1f, fill = false))
                    CompactNowChip(
                        isAtNow = false,
                        onClick = onResetNow,
                        minHeight = 40.dp,
                        outlinedSurfaceColor = cardSurface
                    )
                }
            }
        }
    } else {
        val formattedDateTime = rememberFormattedSkyDateTime(effectiveTimeMs, is24Hour)
        Card(
            modifier = modifier
                .widthIn(max = 320.dp)
                .pointerInput(Unit) { detectTapGestures { } },
            colors = CardDefaults.cardColors(
                containerColor = cardSurface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            border = BorderStroke(
                1.dp,
                if (isSimulatedActive) MaterialTheme.colorScheme.primary else extColors.hudCardBorder
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button, onClick = onOpenPicker)
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = formattedDateTime,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isSimulatedActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = stringResource(Res.string.cd_edit_sky_time),
                            tint = if (isSimulatedActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                extColors.secondaryText
                            },
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    CompactNowChip(
                        isAtNow = !isSimulatedActive,
                        onClick = onResetNow,
                        minHeight = 38.dp
                    )

                    IconButton(
                        onClick = onToggleExpanded,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(Res.string.cd_close),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    TimeStepArrowButton(
                        icon = Icons.Default.ChevronLeft,
                        contentDescription = stringResource(Res.string.cd_step_time_back),
                        onClick = { onStep(-1) }
                    )

                    SkyTimeStep.entries.forEach { step ->
                        val isSelected = step == selectedStep
                        val chipShape = RoundedCornerShape(8.dp)
                        val stepDescription = stepUnitContentDescription(step)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(chipShape)
                                .background(
                                    if (isSelected) extColors.mapButtonActiveSurface else Color.Transparent
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        extColors.mapButtonBorder
                                    },
                                    shape = chipShape
                                )
                                .semantics {
                                    selected = isSelected
                                    contentDescription = stepDescription
                                }
                                .clickable(role = Role.Tab) { onSelectStep(step) }
                                .padding(horizontal = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stepUnitLabel(step),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    TimeStepArrowButton(
                        icon = Icons.Default.ChevronRight,
                        contentDescription = stringResource(Res.string.cd_step_time_forward),
                        onClick = { onStep(1) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TimeStepArrowButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    val extColors = LocalOliveExtendedColors.current
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .width(36.dp)
            .height(38.dp)
            .clip(shape)
            .background(extColors.mapOverlaySurface)
            .border(1.dp, extColors.mapButtonBorder, shape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun CompactNowChip(
    isAtNow: Boolean,
    onClick: () -> Unit,
    minHeight: Dp = 38.dp,
    outlinedSurfaceColor: Color = Color.Transparent
) {
    val extColors = LocalOliveExtendedColors.current
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .heightIn(min = minHeight)
            .clip(shape)
            .background(
                if (isAtNow) extColors.mapButtonActiveSurface else outlinedSurfaceColor
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = shape
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(Res.string.btn_now),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isAtNow) FontWeight.Bold else FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun stepUnitLabel(step: SkyTimeStep): String = when (step) {
    SkyTimeStep.ONE_MINUTE -> stringResource(Res.string.step_unit_1m)
    SkyTimeStep.ONE_HOUR -> stringResource(Res.string.step_unit_1h)
    SkyTimeStep.ONE_DAY -> stringResource(Res.string.step_unit_1d)
    SkyTimeStep.ONE_MONTH -> stringResource(Res.string.step_unit_1mo)
    SkyTimeStep.ONE_YEAR -> stringResource(Res.string.step_unit_1y)
}

@Composable
private fun stepUnitContentDescription(step: SkyTimeStep): String = when (step) {
    SkyTimeStep.ONE_MINUTE -> stringResource(Res.string.cd_step_unit_1m)
    SkyTimeStep.ONE_HOUR -> stringResource(Res.string.cd_step_unit_1h)
    SkyTimeStep.ONE_DAY -> stringResource(Res.string.cd_step_unit_1d)
    SkyTimeStep.ONE_MONTH -> stringResource(Res.string.cd_step_unit_1mo)
    SkyTimeStep.ONE_YEAR -> stringResource(Res.string.cd_step_unit_1y)
}

@Composable
fun SkyDateTimePickerDialog(
    initialEpochMs: Long,
    is24Hour: Boolean,
    onDismiss: () -> Unit,
    onApply: (epochMs: Long) -> Unit
) {
    val extColors = LocalOliveExtendedColors.current
    val tz = remember { TimeZone.currentSystemDefault() }

    val initParts = remember(initialEpochMs, is24Hour) {
        epochMsToDateTimeParts(initialEpochMs, is24Hour, tz)
    }
    var yearStr by remember { mutableStateOf(initParts.year) }
    var monthStr by remember { mutableStateOf(initParts.month) }
    var dayStr by remember { mutableStateOf(initParts.day) }
    var hourStr by remember { mutableStateOf(initParts.hour) }
    var minuteStr by remember { mutableStateOf(initParts.minute) }
    var isPm by remember { mutableStateOf(initParts.isPm) }

    var focusedField by remember { mutableStateOf(DateTimeInputField.HOUR) }
    var replaceOnNextDigit by remember { mutableStateOf(true) }

    fun focus(field: DateTimeInputField) {
        focusedField = field
        replaceOnNextDigit = true
    }

    fun appendDigit(current: String, char: Char, maxLen: Int, maxValue: Int): String {
        val (next, nextReplace) = appendBoundedDigits(
            current = current,
            char = char,
            replaceExisting = replaceOnNextDigit,
            maxLen = maxLen,
            maxValue = maxValue
        )
        @Suppress("AssignedValueIsNeverRead")
        replaceOnNextDigit = nextReplace
        return next
    }

    val parsedYear = yearStr.toIntOrNull()
    val isOutOfRange = parsedYear != null &&
            (parsedYear < MIN_SIMULATED_TIME_YEAR || parsedYear > MAX_SIMULATED_TIME_YEAR)
    val parsedEpochMs: Long? = run {
        val y = parsedYear ?: return@run null
        val m = monthStr.toIntOrNull() ?: return@run null
        val d = dayStr.toIntOrNull() ?: return@run null
        val rawHour = hourStr.toIntOrNull() ?: return@run null
        val min = minuteStr.toIntOrNull() ?: return@run null
        parseLocalDateTimeMillis(
            year = y,
            month = m,
            day = d,
            rawHour = rawHour,
            minute = min,
            is24Hour = is24Hour,
            isPm = isPm,
            timeZone = tz
        )
    }
    val canApply = parsedEpochMs != null && !isOutOfRange

    InfoPopup(
        title = stringResource(Res.string.title_set_sky_time),
        onDismiss = onDismiss,
        fillMaxWidth = true,
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(
                    onClick = {
                        val nowParts = epochMsToDateTimeParts(
                            Clock.System.now().toEpochMilliseconds(),
                            is24Hour,
                            tz
                        )
                        yearStr = nowParts.year
                        monthStr = nowParts.month
                        dayStr = nowParts.day
                        hourStr = nowParts.hour
                        minuteStr = nowParts.minute
                        isPm = nowParts.isPm
                        replaceOnNextDigit = true
                    }
                ) {
                    Text(
                        text = stringResource(Res.string.btn_now),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = stringResource(Res.string.btn_cancel),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = canApply,
                        onClick = {
                            val epochMs = parsedEpochMs ?: return@Button
                            onApply(epochMs)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(stringResource(Res.string.btn_apply))
                    }
                }
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(Res.string.label_date_time),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            DateTimeInputRows(
                yearStr = yearStr,
                monthStr = monthStr,
                dayStr = dayStr,
                hourStr = hourStr,
                minuteStr = minuteStr,
                is24Hour = is24Hour,
                isPm = isPm,
                focusedField = focusedField,
                onFocusField = ::focus,
                onToggleAmPm = { isPm = !isPm }
            )

            Box(
                modifier = Modifier.heightIn(min = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isOutOfRange -> Text(
                        text = stringResource(
                            Res.string.error_sky_time_range,
                            MIN_SIMULATED_TIME_YEAR,
                            MAX_SIMULATED_TIME_YEAR
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = extColors.ctaStop
                    )

                    parsedEpochMs == null -> Text(
                        text = stringResource(Res.string.error_invalid_date_time),
                        style = MaterialTheme.typography.bodySmall,
                        color = extColors.ctaStop
                    )
                }
            }

            NightVisionNumpad(
                onKeyClick = { char ->
                    when (focusedField) {
                        DateTimeInputField.YEAR -> yearStr = appendDigit(yearStr, char, 4, 9999)
                        DateTimeInputField.MONTH -> monthStr = appendDigit(monthStr, char, 2, 12)
                        DateTimeInputField.DAY -> dayStr = appendDigit(dayStr, char, 2, 31)
                        DateTimeInputField.HOUR -> hourStr =
                            appendDigit(hourStr, char, 2, if (is24Hour) 23 else 12)

                        DateTimeInputField.MINUTE -> minuteStr = appendDigit(minuteStr, char, 2, 59)
                    }
                },
                onBackspace = {
                    replaceOnNextDigit = false
                    when (focusedField) {
                        DateTimeInputField.YEAR -> yearStr = yearStr.dropLast(1)
                        DateTimeInputField.MONTH -> monthStr = monthStr.dropLast(1)
                        DateTimeInputField.DAY -> dayStr = dayStr.dropLast(1)
                        DateTimeInputField.HOUR -> hourStr = hourStr.dropLast(1)
                        DateTimeInputField.MINUTE -> minuteStr = minuteStr.dropLast(1)
                    }
                },
                onNext = {
                    val fields = DateTimeInputField.entries
                    focus(fields[(fields.indexOf(focusedField) + 1) % fields.size])
                }
            )
        }
    }
}

@Composable
fun SimulatedTimeBanner(
    epochMs: Long,
    onResetNow: () -> Unit,
    modifier: Modifier = Modifier,
    is24Hour: Boolean = is24HourFormat()
) {
    val extColors = LocalOliveExtendedColors.current
    val formatted = rememberFormattedSkyDateTime(epochMs, is24Hour)
    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            color = extColors.mapButtonActiveSurface,
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource(Res.string.format_simulated_sky_time, formatted),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onResetNow) {
                    Text(
                        text = stringResource(Res.string.btn_now),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
    }
}
