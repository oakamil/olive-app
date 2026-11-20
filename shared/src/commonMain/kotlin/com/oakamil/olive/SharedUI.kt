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
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource

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
    headerAction: (@Composable () -> Unit)? = null,
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (headerAction != null) {
                            Spacer(modifier = Modifier.width(4.dp))
                            headerAction()
                        }
                    }
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
fun ObjectInfoPopup(
    coords: Pair<Double, Double>,
    catalogObject: CatalogObject?,
    viewModel: MainViewModel,
    timeMs: Long,
    onDismiss: () -> Unit,
    altAz: AltAz? = null,
    onCloseToMap: () -> Unit = onDismiss,
) {
    val extColors = LocalOliveExtendedColors.current
    var coLocated by remember(catalogObject) { mutableStateOf<List<CatalogObject>>(emptyList()) }
    var coLocatedReady by remember(catalogObject) {
        mutableStateOf(
            catalogObject == null ||
                    CatalogDatabase.isDynamicObject(catalogObject) ||
                    HiPSObjectPlanner.parseExtentArcmin(catalogObject.angularSize) != null
        )
    }
    var notesExpanded by remember(catalogObject) { mutableStateOf(false) }
    var showAlignConfirm by remember(coords, catalogObject) { mutableStateOf(false) }
    LaunchedEffect(catalogObject) {
        if (catalogObject != null && !CatalogDatabase.isDynamicObject(catalogObject)) {
            coLocated = withContext(Dispatchers.IO) {
                viewModel.catalogDb.getCoLocatedObjects(catalogObject)
            }
        }
        coLocatedReady = true
    }

    val title = catalogObject?.displayName ?: stringResource(
        Res.string.format_ra_dec,
        String.format("%.2f", coords.first),
        String.format("%.2f", coords.second),
    )

    val stats = viewModel.engineStats
    val isRunning = viewModel.isEngineRunning
    val isAbove = altAz == null || altAz.alt >= 0.0
    val isSelectedTarget = isRunning && stats.isTargetMatch(coords.first, coords.second)
    val canGoTo = isAbove && isRunning && stats.ra != null && stats.dec != null && !isSelectedTarget
    val canAlign = isAbove && isRunning && stats.isCoordInCameraView(coords.first, coords.second)

    InfoPopup(
        title = title,
        onDismiss = onDismiss,
        fillMaxWidth = true,
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBarItem(
                    icon = Icons.Default.CenterFocusStrong,
                    label = stringResource(Res.string.crop_center),
                    onClick = {
                        viewModel.skyMapCenterTarget = coords
                        onCloseToMap()
                    },
                )
                if (canGoTo) {
                    IconBarItem(
                        icon = Icons.Default.GpsFixed,
                        label = stringResource(Res.string.btn_goto),
                        iconTint = MaterialTheme.colorScheme.primary,
                        labelColor = MaterialTheme.colorScheme.primary,
                        onClick = {
                            viewModel.setGoToTarget(coords.first, coords.second)
                            onCloseToMap()
                        },
                    )
                }
                if (canAlign) {
                    IconBarItem(
                        icon = Icons.Default.FilterCenterFocus,
                        label = stringResource(Res.string.btn_align),
                        onClick = { showAlignConfirm = true },
                    )
                }
                if (isSelectedTarget) {
                    IconBarItem(
                        icon = Icons.Default.Stop,
                        label = stringResource(Res.string.btn_stop),
                        iconTint = extColors.ctaStop,
                        labelColor = extColors.ctaStop,
                        onClick = {
                            viewModel.clearGoToTarget()
                            onDismiss()
                        },
                    )
                }
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val metadata = remember(catalogObject, coLocated) {
                catalogObject?.let {
                    CatalogObjectInfoFormatter.extractMetadata(it, coLocated)
                }
            }
            if (catalogObject != null && metadata != null) {
                metadata.designation?.let {
                    LabeledValueRow(infoLabel(Res.string.label_designation), it)
                }
                metadata.alsoKnownAs?.let {
                    LabeledValueRow(infoLabel(Res.string.label_also_known_as), it)
                }
                LabeledValueRow(
                    infoLabel(Res.string.label_type),
                    getLocalizedObjectType(catalogObject.objectType),
                )
                val magValue = catalogObject.magnitude?.let { String.format("%.2f", it) }
                    ?: stringResource(Res.string.value_not_available)
                LabeledValueRow(infoLabel(Res.string.label_magnitude), magValue)
                metadata.surfaceBrightness?.let { sb ->
                    LabeledValueRow(
                        infoLabel(Res.string.label_surface_brightness),
                        stringResource(
                            Res.string.format_surface_brightness,
                            String.format("%.1f", sb)
                        ),
                    )
                }
                localizedAngularSize(metadata.angularSizeTokens)?.let {
                    LabeledValueRow(infoLabel(Res.string.label_angular_size), it)
                }
                catalogObject.constellation?.takeIf { it.isNotBlank() }?.let {
                    LabeledValueRow(infoLabel(Res.string.label_constellation), it)
                }
                metadata.distance?.let {
                    LabeledValueRow(infoLabel(Res.string.label_distance), it)
                }
            }
            val displayRa = catalogObject?.ra ?: coords.first
            val displayDec = catalogObject?.dec ?: coords.second
            LabeledValueRow(
                infoLabel(Res.string.ra_j2000_label),
                AstrometryUtils.formatRa(displayRa)
            )
            LabeledValueRow(
                infoLabel(Res.string.dec_j2000_label),
                AstrometryUtils.formatDec(displayDec)
            )
            altAz?.let {
                LabeledValueRow(
                    infoLabel(Res.string.label_alt_az),
                    stringResource(
                        Res.string.format_alt_az,
                        String.format("%.1f", it.alt),
                        String.format("%.1f", it.az),
                        localizedCompassDirection(it.az),
                    ),
                )
            }
            metadata?.notes?.let { notes ->
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { notesExpanded = !notesExpanded }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = infoLabel(Res.string.label_notes),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (notesExpanded) {
                            Icons.Default.KeyboardArrowUp
                        } else {
                            Icons.Default.KeyboardArrowDown
                        },
                        contentDescription = stringResource(
                            if (notesExpanded) Res.string.cd_collapse_notes else Res.string.cd_expand_notes
                        ),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                if (notesExpanded) {
                    Text(
                        text = notes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (catalogObject != null && coLocatedReady) {
                ObjectHiPSPreview(
                    catalogObject = catalogObject,
                    coLocated = coLocated,
                    viewModel = viewModel,
                    timeMs = timeMs,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    if (showAlignConfirm && isRunning) {
        OliveAlertDialog(
            title = stringResource(Res.string.title_confirm_alignment),
            text = stringResource(Res.string.msg_confirm_alignment, title),
            confirmButtonText = stringResource(Res.string.btn_confirm),
            onConfirm = {
                @Suppress("AssignedValueIsNeverRead")
                showAlignConfirm = false
                viewModel.syncTelescope(coords.first, coords.second)
                onCloseToMap()
            },
            dismissButtonText = stringResource(Res.string.btn_cancel),
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                showAlignConfirm = false
            },
        )
    }
}

@Composable
private fun infoLabel(res: StringResource): String =
    stringResource(Res.string.format_info_label, stringResource(res))

@Composable
private fun localizedAngularSize(tokens: List<String>?): String? {
    if (tokens.isNullOrEmpty()) return null
    var formatted: String? = null
    for (token in tokens) {
        val part = if (CatalogObjectInfoFormatter.isBareNumber(token)) {
            stringResource(Res.string.format_arcmin, token)
        } else {
            token
        }
        formatted =
            formatted?.let { stringResource(Res.string.format_dimensions, it, part) } ?: part
    }
    return formatted
}

@Composable
fun LabeledValueRow(
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
        LabeledValueRow(
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
        LabeledValueRow(
            label = stringResource(id = Res.string.stat_star_count),
            value = "${stats.centroidCount}"
        )
        Spacer(modifier = Modifier.height(8.dp))

        LabeledValueRow(
            label = stringResource(id = Res.string.ra_j2000_label),
            value = stats.ra?.let { AstrometryUtils.formatRa(it) } ?: "-"
        )
        Spacer(modifier = Modifier.height(4.dp))

        LabeledValueRow(
            label = stringResource(id = Res.string.dec_j2000_label),
            value = stats.dec?.let { AstrometryUtils.formatDec(it) } ?: "-"
        )
        Spacer(modifier = Modifier.height(4.dp))

        stats.fov?.let { fovVal ->
            LabeledValueRow(
                label = stringResource(id = Res.string.stat_fov),
                value = stringResource(
                    id = Res.string.format_degree,
                    String.format("%.2f", fovVal)
                )
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        LabeledValueRow(
            label = stringResource(id = Res.string.stat_exposure),
            value = stringResource(
                id = Res.string.format_ms,
                String.format("%.2f", stats.exposureMs)
            )
        )
        Spacer(modifier = Modifier.height(4.dp))

        LabeledValueRow(
            label = stringResource(id = Res.string.stat_post_process),
            value = stringResource(
                id = Res.string.format_ms,
                String.format("%.1f", stats.postProcessMs)
            )
        )
        Spacer(modifier = Modifier.height(4.dp))

        LabeledValueRow(
            label = stringResource(id = Res.string.stat_extraction),
            value = stringResource(
                id = Res.string.format_ms,
                String.format("%.1f", stats.extractMs)
            )
        )
        Spacer(modifier = Modifier.height(4.dp))

        LabeledValueRow(
            label = stringResource(id = Res.string.stat_solve),
            value = stringResource(
                id = Res.string.format_ms,
                String.format("%.1f", stats.solveMs)
            )
        )
        Spacer(modifier = Modifier.height(4.dp))

        LabeledValueRow(
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
        LabeledValueRow(
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



