package com.oakamil.olive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift.Companion.Superscript
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

val LocalOnUserInteraction = compositionLocalOf { {} }

@Composable
fun IconBarItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    iconSize: Dp = 28.dp,
    iconTint: Color = MaterialTheme.colorScheme.onSurface,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 4.dp, top = 4.dp, end = 4.dp, bottom = 0.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(iconSize),
                color = iconTint,
                strokeWidth = 3.dp
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (enabled) iconTint else MaterialTheme.colorScheme.onSurface.copy(
                    alpha = 0.38f
                ),
                modifier = Modifier.size(iconSize)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        val baseStyle = LocalTextStyle.current.merge(MaterialTheme.typography.labelSmall)
        val forceSingleLine = !label.contains(" ")

        BasicText(
            text = label,
            style = baseStyle.copy(
                color = if (enabled) labelColor else MaterialTheme.colorScheme.onSurface.copy(
                    alpha = 0.38f
                ),
                textAlign = TextAlign.Center
            ),
            minLines = if (forceSingleLine) 1 else 2,
            maxLines = if (forceSingleLine) 1 else 2,
            modifier = Modifier
                .wrapContentHeight(Alignment.CenterVertically),
            autoSize = TextAutoSize.StepBased(
                minFontSize = 4.sp,
                maxFontSize = baseStyle.fontSize
            )
        )
    }
}

@Composable
fun OliveAlertDialog(
    title: String,
    text: String,
    confirmButtonText: String = stringResource(Res.string.btn_confirm),
    onConfirm: () -> Unit,
    dismissButtonText: String? = stringResource(Res.string.btn_cancel),
    onDismiss: () -> Unit,
    showCloseButton: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    dismissOnBackPress: Boolean = true
) {
    val onUserInteraction = LocalOnUserInteraction.current
    val extColors = LocalOliveExtendedColors.current
    Dialog(
        onDismissRequest = {
            if (dismissOnClickOutside) {
                onDismiss()
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = dismissOnClickOutside,
            dismissOnBackPress = dismissOnBackPress
        )
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .border(
                    1.dp,
                    extColors.subtleBorder,
                    RoundedCornerShape(16.dp)
                )
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            onUserInteraction()
                        }
                    }
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (showCloseButton) {
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(id = Res.string.cd_close),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = extColors.dialogDivider)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (dismissButtonText != null) Arrangement.End else Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (dismissButtonText != null) {
                        TextButton(onClick = onDismiss) {
                            Text(
                                text = dismissButtonText,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(confirmButtonText)
                    }
                }
            }
        }
    }
}

@Composable
fun NightVisionDialog(
    title: String,
    text: String,
    confirmButtonText: String,
    onConfirm: () -> Unit,
    dismissButtonText: String? = null,
    onDismiss: () -> Unit
) = OliveAlertDialog(
    title = title,
    text = text,
    confirmButtonText = confirmButtonText,
    onConfirm = onConfirm,
    dismissButtonText = dismissButtonText,
    onDismiss = onDismiss
)

@Composable
fun InfoPopup(
    title: String,
    onDismiss: () -> Unit,
    fillMaxWidth: Boolean = false,
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val onUserInteraction = LocalOnUserInteraction.current
    val extColors = LocalOliveExtendedColors.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(24.dp)
                .widthIn(min = 200.dp, max = 440.dp)
                .then(if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier)
                .border(
                    1.dp,
                    extColors.subtleBorder,
                    RoundedCornerShape(16.dp)
                )
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            onUserInteraction()
                        }
                    }
                }
        ) {
            Column(
                modifier = if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier.width(
                    IntrinsicSize.Max
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(id = Res.string.cd_close),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                HorizontalDivider(color = extColors.dialogDivider)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .padding(16.dp)
                        .verticalScrollWithScrollbar(rememberScrollState())
                ) {
                    content()
                }
                if (bottomBar != null) {
                    HorizontalDivider(color = extColors.dialogDivider)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        bottomBar()
                    }
                }
            }
        }
    }
}

@Composable
fun CoordinateBox(
    value: String,
    isFocused: Boolean,
    suffix: String,
    placeholder: String = "00",
    onClick: () -> Unit
) {
    val extColors = LocalOliveExtendedColors.current
    val borderColor =
        if (isFocused) MaterialTheme.colorScheme.primary else extColors.subtleBorder
    val borderWidth = if (isFocused) 2.dp else 1.dp

    Row(
        modifier = Modifier
            .clickable { onClick() }
            .border(borderWidth, borderColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val isDeclination = suffix == "°" || suffix == "'" || suffix == "\""
        Text(
            text = value.ifEmpty { placeholder },
            style = MaterialTheme.typography.bodyLarge,
            color = if (value.isEmpty()) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.alignByBaseline()
        )
        Text(
            text = suffix,
            style = if (isDeclination) {
                MaterialTheme.typography.bodyLarge.copy(baselineShift = Superscript)
            } else {
                MaterialTheme.typography.labelSmall
            },
            color = extColors.secondaryText,
            modifier = Modifier
                .padding(start = 2.dp)
                .alignByBaseline()
        )
    }
}

@Composable
fun NightVisionNumpad(
    onKeyClick: (Char) -> Unit,
    onBackspace: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val extColors = LocalOliveExtendedColors.current
    val keys = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("⌫", "0", "→")
    )

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(2.dp)
            .width(200.dp)
    ) {
        keys.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                row.forEach { key ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 2.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainer,
                                RoundedCornerShape(4.dp)
                            )
                            .border(
                                1.dp,
                                extColors.numpadKeyBorder,
                                RoundedCornerShape(4.dp)
                            )
                            .clickable {
                                when (key) {
                                    "⌫" -> onBackspace()
                                    "→" -> onNext()
                                    else -> onKeyClick(key[0])
                                }
                            }
                            .height(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (key == "→") {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = stringResource(Res.string.cd_next),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        } else {
                            Text(
                                text = key,
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatRow(
    label: String,
    value: String
) {
    Row {
        Text(
            label,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(value, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun StatsDisplay(
    stats: EngineStats,
    detectedIpAddress: String = "",
    onInfoClick: (() -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        StatRow(
            label = stringResource(id = Res.string.stat_status),
            value = when (stats.parsedStatus) {
                EngineStatus.SOLVING -> stringResource(id = Res.string.stat_solving)
                EngineStatus.SLEWING -> stringResource(id = Res.string.stat_slewing)
                EngineStatus.IDLE -> stringResource(id = Res.string.stat_idle)
                EngineStatus.CAMERA_MODE -> stringResource(id = Res.string.btn_camera_mode)
                EngineStatus.CALIBRATING -> stringResource(id = Res.string.stat_calibrating)
                EngineStatus.CALIBRATING_FOCUS -> stringResource(id = Res.string.stat_calibrating_focus)
                null -> stats.status
            }
        )
        Spacer(modifier = Modifier.height(4.dp))
        StatRow(
            label = stringResource(id = Res.string.stat_star_count),
            value = "${stats.centroidCount}"
        )
        Spacer(modifier = Modifier.height(8.dp))

        StatRow(
            label = stringResource(id = Res.string.ra_j2000_label),
            value = stats.ra?.let { formatRa(it) } ?: "-"
        )
        Spacer(modifier = Modifier.height(4.dp))

        StatRow(
            label = stringResource(id = Res.string.dec_j2000_label),
            value = stats.dec?.let { formatDec(it) } ?: "-"
        )
        Spacer(modifier = Modifier.height(4.dp))

        stats.fov?.let { fovVal ->
            StatRow(
                label = stringResource(id = Res.string.stat_fov),
                value = stringResource(
                    id = Res.string.format_degree,
                    String.format("%.2f", fovVal)
                )
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        StatRow(
            label = stringResource(id = Res.string.stat_exposure),
            value = stringResource(
                id = Res.string.format_ms,
                String.format("%.2f", stats.exposureMs)
            )
        )
        Spacer(modifier = Modifier.height(4.dp))

        StatRow(
            label = stringResource(id = Res.string.stat_post_process),
            value = stringResource(
                id = Res.string.format_ms,
                String.format("%.1f", stats.postProcessMs)
            )
        )
        Spacer(modifier = Modifier.height(4.dp))

        StatRow(
            label = stringResource(id = Res.string.stat_extraction),
            value = stringResource(
                id = Res.string.format_ms,
                String.format("%.1f", stats.extractMs)
            )
        )
        Spacer(modifier = Modifier.height(4.dp))

        StatRow(
            label = stringResource(id = Res.string.stat_solve),
            value = stringResource(
                id = Res.string.format_ms,
                String.format("%.1f", stats.solveMs)
            )
        )
        Spacer(modifier = Modifier.height(4.dp))

        StatRow(
            label = stringResource(id = Res.string.stat_solve_rate),
            value = stringResource(
                id = Res.string.format_hz,
                String.format("%.1f", stats.solveRateHz)
            )
        )
        Spacer(modifier = Modifier.height(4.dp))

        val cropString = when (stats.solvedCrop) {
            "Center2x", "Center" -> stringResource(id = Res.string.crop_center_2x)
            "Center2xHighSens", "CenterHighSens" -> stringResource(id = Res.string.crop_center_2x_hs)
            "Center1_5x", "CenterWide" -> stringResource(id = Res.string.crop_center_1_5x)
            "Center1_5xHighSens", "CenterWideHighSens" -> stringResource(id = Res.string.crop_center_1_5x_hs)
            "Top" -> stringResource(id = Res.string.crop_top)
            "Bottom" -> stringResource(id = Res.string.crop_bottom)
            "Left" -> stringResource(id = Res.string.crop_left)
            "Right" -> stringResource(id = Res.string.crop_right)
            "Full" -> stringResource(id = Res.string.crop_full)
            EngineStats.CROP_FAILED -> stringResource(id = Res.string.crop_failed)

            "TopHighSens" -> stringResource(
                id = Res.string.format_high_sens,
                stringResource(id = Res.string.crop_top)
            )

            "BottomHighSens" -> stringResource(
                id = Res.string.format_high_sens,
                stringResource(id = Res.string.crop_bottom)
            )

            "LeftHighSens" -> stringResource(
                id = Res.string.format_high_sens,
                stringResource(id = Res.string.crop_left)
            )

            "RightHighSens" -> stringResource(
                id = Res.string.format_high_sens,
                stringResource(id = Res.string.crop_right)
            )

            "FullHighSens" -> stringResource(
                id = Res.string.format_high_sens,
                stringResource(id = Res.string.crop_full)
            )

            else -> stats.solvedCrop ?: "-"
        }
        StatRow(
            label = stringResource(id = Res.string.stat_last_solve),
            value = cropString
        )

        if (detectedIpAddress.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.stat_ip_address),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(detectedIpAddress, color = MaterialTheme.colorScheme.onSurface)
                if (onInfoClick != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = onInfoClick,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = stringResource(id = Res.string.cd_info),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}



