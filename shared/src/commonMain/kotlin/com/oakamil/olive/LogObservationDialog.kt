// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

private enum class ObservationMetaField {
    YEAR, MONTH, DAY, HOUR, MINUTE, LAT_DEG, LAT_FRAC, LON_DEG, LON_FRAC
}

/**
 * Modal for logging a new target observation or editing notes on an existing record.
 *
 * Uses [NightVisionKeyboardDock] (suppressing the OS keyboard) alongside optional voice dictation.
 * When [isEditing] is true, timestamp, observer coordinates, and Alt/Az metadata are locked.
 */
@Composable
fun LogObservationDialog(
    targetName: String,
    targetRaDeg: Double,
    targetDecDeg: Double,
    observedAtMs: Long,
    latDeg: Double?,
    lonDeg: Double?,
    altDeg: Double?,
    azDeg: Double?,
    initialNotes: String = "",
    isEditing: Boolean = false,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onSave: (
        observedAtMs: Long,
        latDeg: Double?,
        lonDeg: Double?,
        altDeg: Double?,
        azDeg: Double?,
        notes: String
    ) -> Unit
) {
    val extColors = LocalOliveExtendedColors.current
    val focusManager = LocalFocusManager.current
    val voiceController = rememberVoiceDictationController()

    var currentObservedAtMs by remember { mutableLongStateOf(observedAtMs) }
    var currentLatDeg by remember { mutableStateOf(latDeg) }
    var currentLonDeg by remember { mutableStateOf(lonDeg) }
    var showMetadataEditor by remember { mutableStateOf(false) }

    var notesValue by remember(initialNotes) {
        mutableStateOf(TextFieldValue(initialNotes, TextRange(initialNotes.length)))
    }
    var speechBaseText by remember { mutableStateOf("") }
    var showKeyboard by remember { mutableStateOf(false) }
    val hideKeyboard = {
        showKeyboard = false
        focusManager.clearFocus()
    }

    val currentAltAz = remember(
        isEditing,
        targetRaDeg,
        targetDecDeg,
        currentObservedAtMs,
        currentLatDeg,
        currentLonDeg,
        altDeg,
        azDeg
    ) {
        if (isEditing) {
            if (altDeg != null && azDeg != null) AltAz(altDeg, azDeg) else null
        } else if (MainViewModel.isValidCoordinate(currentLatDeg, currentLonDeg) &&
            currentLatDeg != null && currentLonDeg != null
        ) {
            AstrometryUtils.calculateAltAz(
                targetRaDeg,
                targetDecDeg,
                currentLatDeg!!,
                currentLonDeg!!,
                currentObservedAtMs
            )
        } else {
            null
        }
    }

    val is24Hour = is24HourFormat()
    val amLabel = stringResource(Res.string.label_am)
    val pmLabel = stringResource(Res.string.label_pm)
    val formattedTime = remember(currentObservedAtMs, is24Hour, amLabel, pmLabel) {
        UserDatabase.formatObservationTimestamp(
            currentObservedAtMs,
            is24Hour = is24Hour,
            amLabel = amLabel,
            pmLabel = pmLabel
        )
    }
    val formattedLocation =
        if (MainViewModel.isValidCoordinate(currentLatDeg, currentLonDeg) &&
            currentLatDeg != null && currentLonDeg != null
        ) {
            formatObserverCoordinate(currentLatDeg!!, currentLonDeg!!)
        } else {
            stringResource(Res.string.status_location_not_set)
        }
    val formattedAltAz = currentAltAz?.let { formatAltAz(it.alt, it.az) }

    val startOrToggleMic = {
        hideKeyboard()
        if (!voiceController.isListening) {
            speechBaseText = notesValue.text
        }
        voiceController.toggleListening { sessionText, _ ->
            val combined = appendDictationText(speechBaseText, sessionText)
            notesValue = TextFieldValue(combined, TextRange(combined.length))
        }
    }

    val handleDismissOrHideKeyboard = {
        voiceController.stopListening()
        if (showKeyboard) {
            hideKeyboard()
        } else {
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = handleDismissOrHideKeyboard,
        properties = fullScreenDialogProperties(dismissOnClickOutside = false)
    ) {
        val dialogShape = RoundedCornerShape(16.dp)

        val cardContent: @Composable (Modifier) -> Unit = { cardModifier ->
            Surface(
                shape = dialogShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = cardModifier
                    .border(1.dp, extColors.subtleBorder, dialogShape)
                    .pointerInput(Unit) { detectTapGestures { } }
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OliveDialogHeader(
                        title = stringResource(
                            if (isEditing) Res.string.title_edit_observation
                            else Res.string.title_log_observation
                        ),
                        onDismiss = {
                            voiceController.stopListening()
                            onDismiss()
                        }
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScrollWithScrollbar(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = targetName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        val metaCardShape = RoundedCornerShape(10.dp)
                        val metaBorderColor = if (!isEditing) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            extColors.subtleBorder
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(metaCardShape)
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerHigh,
                                    metaCardShape
                                )
                                .border(1.dp, metaBorderColor, metaCardShape)
                                .then(
                                    if (!isEditing) {
                                        Modifier.clickable {
                                            voiceController.stopListening()
                                            hideKeyboard()
                                            showMetadataEditor = true
                                        }
                                    } else {
                                        Modifier
                                    }
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = formattedTime,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = formattedLocation,
                                style = MaterialTheme.typography.bodySmall,
                                color = extColors.secondaryText
                            )
                            if (formattedAltAz != null) {
                                Text(
                                    text = stringResource(
                                        Res.string.format_label_value_string,
                                        stringResource(Res.string.label_alt_az),
                                        formattedAltAz
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = extColors.secondaryText
                                )
                            }
                        }

                        val clearNotesButton: @Composable () -> Unit = {
                            TextButton(
                                onClick = {
                                    voiceController.stopListening()
                                    speechBaseText = ""
                                    notesValue = TextFieldValue("")
                                }
                            ) {
                                Text(
                                    text = stringResource(Res.string.btn_clear),
                                    color = extColors.ctaStop
                                )
                            }
                        }

                        if (voiceController.isSupported) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                val isListening = voiceController.isListening
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isListening) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceContainerHigh
                                        )
                                        .border(
                                            width = if (isListening) 2.dp else 1.dp,
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = CircleShape
                                        )
                                        .clickable { startOrToggleMic() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (isListening) Icons.Outlined.MicOff else Icons.Outlined.Mic,
                                        contentDescription = stringResource(
                                            if (isListening) Res.string.cd_stop_voice_input
                                            else Res.string.cd_voice_input
                                        ),
                                        tint = if (isListening) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(
                                            if (isListening) {
                                                Res.string.status_listening
                                            } else {
                                                Res.string.status_tap_mic_to_dictate
                                            }
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isListening) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                }
                                if (notesValue.text.isNotEmpty()) {
                                    clearNotesButton()
                                }
                            }
                        }

                        DisableSystemKeyboard(disable = true) {
                            OutlinedTextField(
                                value = notesValue,
                                onValueChange = { updated ->
                                    notesValue = updated
                                },
                                label = { Text(stringResource(Res.string.label_observation_notes)) },
                                placeholder = {
                                    Text(
                                        stringResource(
                                            if (voiceController.isSupported) {
                                                Res.string.placeholder_observation_notes
                                            } else {
                                                Res.string.placeholder_observation_notes_text
                                            }
                                        )
                                    )
                                },
                                trailingIcon = if (!voiceController.isSupported && notesValue.text.isNotEmpty()) {
                                    clearNotesButton
                                } else {
                                    null
                                },
                                minLines = 3,
                                maxLines = 6,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 96.dp)
                                    .onFocusChanged { state ->
                                        if (state.isFocused) {
                                            voiceController.stopListening()
                                            showKeyboard = true
                                        }
                                    }
                            )
                        }
                    }

                    HorizontalDivider(color = extColors.dialogDivider)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                voiceController.stopListening()
                                onDismiss()
                            }
                        ) {
                            Text(
                                text = stringResource(Res.string.btn_cancel),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                voiceController.stopListening()
                                onSave(
                                    currentObservedAtMs,
                                    currentLatDeg,
                                    currentLonDeg,
                                    currentAltAz?.alt,
                                    currentAltAz?.az,
                                    notesValue.text
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text(stringResource(Res.string.btn_save))
                        }
                    }
                }
            }
        }

        val keyboardPanel: @Composable (Boolean, Modifier) -> Unit = { isLandscape, kbModifier ->
            NightVisionKeyboardDock(
                isLandscape = isLandscape,
                viewModel = viewModel,
                onEdit = { transform -> notesValue = transform(notesValue) },
                onClose = hideKeyboard,
                showShiftAndPunctuation = true,
                showNewline = true,
                modifier = kbModifier
            )
        }

        ProvideScaledText {
            NightVisionKeyboardDialogLayout(
                showKeyboard = showKeyboard,
                landscapeKeyboardOnRight = viewModel.landscapeKeyboardOnRight,
                maxCardWidth = 460.dp,
                onScrimClick = handleDismissOrHideKeyboard,
                fillCardHeightInLandscape = true,
                cardContent = cardContent,
                keyboardPanel = keyboardPanel
            )
        }
    }

    if (showMetadataEditor && !isEditing) {
        ObservationMetadataDialog(
            initialTimestampMs = currentObservedAtMs,
            initialLatDeg = currentLatDeg,
            initialLonDeg = currentLonDeg,
            is24Hour = is24Hour,
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                showMetadataEditor = false
            },
            onApply = { newTimestampMs, newLat, newLon ->
                currentObservedAtMs = newTimestampMs
                currentLatDeg = newLat
                currentLonDeg = newLon
                @Suppress("AssignedValueIsNeverRead")
                showMetadataEditor = false
            }
        )
    }

    VoicePermissionDeniedDialog(voiceController)
}

/**
 * Numpad editor for observation date/time and observer coordinates. Rejects future timestamps and
 * uses `replaceOnNextDigit` so the first digit typed after focusing a field overwrites its value.
 */
@Composable
private fun ObservationMetadataDialog(
    initialTimestampMs: Long,
    initialLatDeg: Double?,
    initialLonDeg: Double?,
    is24Hour: Boolean,
    onDismiss: () -> Unit,
    onApply: (timestampMs: Long, latDeg: Double?, lonDeg: Double?) -> Unit
) {
    val extColors = LocalOliveExtendedColors.current
    val tz = remember { TimeZone.currentSystemDefault() }
    val initParts = remember(initialTimestampMs, is24Hour) {
        epochMsToDateTimeParts(initialTimestampMs, is24Hour, tz)
    }

    var yearStr by remember { mutableStateOf(initParts.year) }
    var monthStr by remember { mutableStateOf(initParts.month) }
    var dayStr by remember { mutableStateOf(initParts.day) }
    var isPm by remember { mutableStateOf(initParts.isPm) }
    var hourStr by remember { mutableStateOf(initParts.hour) }
    var minuteStr by remember { mutableStateOf(initParts.minute) }

    val hasValidInitLoc = MainViewModel.isValidCoordinate(initialLatDeg, initialLonDeg) &&
            initialLatDeg != null && initialLonDeg != null
    val initLat = remember(hasValidInitLoc, initialLatDeg) {
        splitDecimalDegrees(if (hasValidInitLoc) initialLatDeg else null)
    }
    val initLon = remember(hasValidInitLoc, initialLonDeg) {
        splitDecimalDegrees(if (hasValidInitLoc) initialLonDeg else null)
    }
    var latNorth by remember { mutableStateOf(initLat.isPositive) }
    var latDegStr by remember { mutableStateOf(initLat.wholeDeg) }
    var latFracStr by remember { mutableStateOf(initLat.fracDeg) }
    var lonEast by remember { mutableStateOf(initLon.isPositive) }
    var lonDegStr by remember { mutableStateOf(initLon.wholeDeg) }
    var lonFracStr by remember { mutableStateOf(initLon.fracDeg) }

    var focusedField by remember { mutableStateOf(ObservationMetaField.HOUR) }
    var replaceOnNextDigit by remember { mutableStateOf(true) }

    fun focus(field: ObservationMetaField) {
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

    val parsedTimestampMs: Long? = run {
        val y = yearStr.toIntOrNull() ?: return@run null
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
    val nowMs = Clock.System.now().toEpochMilliseconds()
    val isInFuture = parsedTimestampMs != null && parsedTimestampMs > nowMs
    val hasValidLocationInput = (latDegStr.isNotBlank() && lonDegStr.isNotBlank()) ||
            (latDegStr.isBlank() && latFracStr.isBlank() && lonDegStr.isBlank() && lonFracStr.isBlank())
    val canSave = parsedTimestampMs != null && !isInFuture && hasValidLocationInput

    InfoPopup(
        title = stringResource(Res.string.title_observation_time_location),
        onDismiss = onDismiss,
        fillMaxWidth = true,
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = stringResource(Res.string.btn_cancel),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = canSave,
                    onClick = {
                        val updatedMs = parsedTimestampMs ?: return@Button
                        if (updatedMs > Clock.System.now().toEpochMilliseconds()) return@Button

                        val hasLocInput = latDegStr.isNotBlank() && lonDegStr.isNotBlank()
                        val (outLat, outLon) = if (hasLocInput) {
                            combineDecimalDegrees(latDegStr, latFracStr, latNorth, 90) to
                                    combineDecimalDegrees(lonDegStr, lonFracStr, lonEast, 180)
                        } else {
                            null to null
                        }
                        onApply(updatedMs, outLat, outLon)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(stringResource(Res.string.btn_save))
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
            val focusedDateTimeField = when (focusedField) {
                ObservationMetaField.YEAR -> DateTimeInputField.YEAR
                ObservationMetaField.MONTH -> DateTimeInputField.MONTH
                ObservationMetaField.DAY -> DateTimeInputField.DAY
                ObservationMetaField.HOUR -> DateTimeInputField.HOUR
                ObservationMetaField.MINUTE -> DateTimeInputField.MINUTE
                else -> null
            }
            DateTimeInputRows(
                yearStr = yearStr,
                monthStr = monthStr,
                dayStr = dayStr,
                hourStr = hourStr,
                minuteStr = minuteStr,
                is24Hour = is24Hour,
                isPm = isPm,
                focusedField = focusedDateTimeField,
                onFocusField = { field ->
                    focus(
                        when (field) {
                            DateTimeInputField.YEAR -> ObservationMetaField.YEAR
                            DateTimeInputField.MONTH -> ObservationMetaField.MONTH
                            DateTimeInputField.DAY -> ObservationMetaField.DAY
                            DateTimeInputField.HOUR -> ObservationMetaField.HOUR
                            DateTimeInputField.MINUTE -> ObservationMetaField.MINUTE
                        }
                    )
                },
                onToggleAmPm = { isPm = !isPm }
            )

            Box(
                modifier = Modifier.height(20.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isInFuture) {
                    Text(
                        text = stringResource(Res.string.error_time_in_future),
                        style = MaterialTheme.typography.bodySmall,
                        color = extColors.ctaStop
                    )
                }
            }

            HorizontalDivider(
                color = extColors.dialogDivider,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Text(
                text = stringResource(Res.string.label_location),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CoordinateBox(
                    value = latDegStr,
                    isFocused = focusedField == ObservationMetaField.LAT_DEG,
                    suffix = "",
                    placeholder = "00",
                    onClick = { focus(ObservationMetaField.LAT_DEG) }
                )
                Text(".", color = extColors.secondaryText)
                CoordinateBox(
                    value = latFracStr,
                    isFocused = focusedField == ObservationMetaField.LAT_FRAC,
                    suffix = "",
                    placeholder = "00",
                    onClick = { focus(ObservationMetaField.LAT_FRAC) }
                )
                Text("°", color = extColors.secondaryText)
                Box(
                    modifier = Modifier
                        .clickable { latNorth = !latNorth }
                        .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = stringResource(
                            if (latNorth) Res.string.dir_north else Res.string.dir_south
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CoordinateBox(
                    value = lonDegStr,
                    isFocused = focusedField == ObservationMetaField.LON_DEG,
                    suffix = "",
                    placeholder = "000",
                    onClick = { focus(ObservationMetaField.LON_DEG) }
                )
                Text(".", color = extColors.secondaryText)
                CoordinateBox(
                    value = lonFracStr,
                    isFocused = focusedField == ObservationMetaField.LON_FRAC,
                    suffix = "",
                    placeholder = "00",
                    onClick = { focus(ObservationMetaField.LON_FRAC) }
                )
                Text("°", color = extColors.secondaryText)
                Box(
                    modifier = Modifier
                        .clickable { lonEast = !lonEast }
                        .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = stringResource(
                            if (lonEast) Res.string.dir_east else Res.string.dir_west
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            NightVisionNumpad(
                onKeyClick = { char ->
                    when (focusedField) {
                        ObservationMetaField.YEAR -> yearStr = appendDigit(yearStr, char, 4, 9999)
                        ObservationMetaField.MONTH -> monthStr = appendDigit(monthStr, char, 2, 12)
                        ObservationMetaField.DAY -> dayStr = appendDigit(dayStr, char, 2, 31)
                        ObservationMetaField.HOUR -> hourStr =
                            appendDigit(hourStr, char, 2, if (is24Hour) 23 else 12)

                        ObservationMetaField.MINUTE -> minuteStr =
                            appendDigit(minuteStr, char, 2, 59)

                        ObservationMetaField.LAT_DEG -> {
                            latDegStr = appendDigit(latDegStr, char, 2, 90)
                            if (latDegStr.toIntOrNull() == 90) latFracStr = "00"
                        }

                        ObservationMetaField.LAT_FRAC -> {
                            if (latDegStr.toIntOrNull() != 90) {
                                latFracStr = appendDigit(latFracStr, char, 2, 99)
                            }
                        }

                        ObservationMetaField.LON_DEG -> {
                            lonDegStr = appendDigit(lonDegStr, char, 3, 180)
                            if (lonDegStr.toIntOrNull() == 180) lonFracStr = "00"
                        }

                        ObservationMetaField.LON_FRAC -> {
                            if (lonDegStr.toIntOrNull() != 180) {
                                lonFracStr = appendDigit(lonFracStr, char, 2, 99)
                            }
                        }
                    }
                },
                onBackspace = {
                    replaceOnNextDigit = false
                    when (focusedField) {
                        ObservationMetaField.YEAR -> yearStr = yearStr.dropLast(1)
                        ObservationMetaField.MONTH -> monthStr = monthStr.dropLast(1)
                        ObservationMetaField.DAY -> dayStr = dayStr.dropLast(1)
                        ObservationMetaField.HOUR -> hourStr = hourStr.dropLast(1)
                        ObservationMetaField.MINUTE -> minuteStr = minuteStr.dropLast(1)
                        ObservationMetaField.LAT_DEG -> latDegStr = latDegStr.dropLast(1)
                        ObservationMetaField.LAT_FRAC -> latFracStr = latFracStr.dropLast(1)
                        ObservationMetaField.LON_DEG -> lonDegStr = lonDegStr.dropLast(1)
                        ObservationMetaField.LON_FRAC -> lonFracStr = lonFracStr.dropLast(1)
                    }
                },
                onNext = {
                    val fields = ObservationMetaField.entries
                    focus(fields[(fields.indexOf(focusedField) + 1) % fields.size])
                }
            )
        }
    }
}
