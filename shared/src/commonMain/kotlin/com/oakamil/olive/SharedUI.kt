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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistAddCheck
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift.Companion.Superscript
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import org.jetbrains.compose.resources.StringResource
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Shared UI building blocks across Olive:
 * - Low-power idle timer propagation across Dialog windows (LocalOnUserInteraction).
 * - Full-screen and scrim-card dialog shells (FullScreenSurfaceDialog, OliveAlertDialog, InfoPopup).
 * - Night-vision numeric/coordinate input controls (CoordinateBox, NightVisionNumpad).
 * - ObjectInfoPopup target detail sheet and StatsDisplay engine telemetry table.
 */

/** Callback invoked on pointer activity inside modal dialogs to reset `MainScreen`'s low-power idle timer. */
val LocalOnUserInteraction = compositionLocalOf { {} }

fun Modifier.notifyUserInteraction(onUserInteraction: () -> Unit): Modifier =
    pointerInput(onUserInteraction) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial)
                onUserInteraction()
            }
        }
    }

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
        ProvideUnscaledText {
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
}

@Composable
fun FullScreenSurfaceDialog(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit
) {
    val onUserInteraction = LocalOnUserInteraction.current
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = fullScreenDialogProperties(dismissOnClickOutside = false)
    ) {
        ProvideScaledText {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .notifyUserInteraction(onUserInteraction),
                color = MaterialTheme.colorScheme.background
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .platformDialogSafeAreaPadding()
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * Centered modal card hosted inside a full-screen dialog with a custom tap scrim so outside taps
 * and [LocalOnUserInteraction] tracking behave identically on Android and Apple targets.
 */
@Composable
private fun OliveScrimCardDialog(
    onDismissRequest: () -> Unit,
    dismissOnBackPress: Boolean,
    dismissOnClickOutside: Boolean,
    surfaceModifier: Modifier,
    content: @Composable () -> Unit
) {
    val onUserInteraction = LocalOnUserInteraction.current
    val extColors = LocalOliveExtendedColors.current
    val currentOnDismiss by rememberUpdatedState(onDismissRequest)
    val cardShape = RoundedCornerShape(16.dp)
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = fullScreenDialogProperties(
            dismissOnBackPress = dismissOnBackPress,
            dismissOnClickOutside = false
        )
    ) {
        ProvideScaledText {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .notifyUserInteraction(onUserInteraction)
                    .then(
                        if (dismissOnClickOutside) {
                            Modifier.pointerInput(Unit) {
                                detectTapGestures { currentOnDismiss() }
                            }
                        } else {
                            Modifier
                        }
                    )
                    .platformDialogSafeAreaPadding(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = cardShape,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = surfaceModifier
                        .border(1.dp, extColors.subtleBorder, cardShape)
                        .pointerInput(Unit) { detectTapGestures { } },
                    content = content
                )
            }
        }
    }
}

/**
 * [DropdownMenu] wrapper that reapplies [ProvideScaledText] inside the popup window, since
 * Android's `PopupLayout` resets `LocalDensity` to the platform default.
 */
@Composable
fun OliveDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier
    ) {
        ProvideScaledText {
            content()
        }
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
    val extColors = LocalOliveExtendedColors.current
    OliveScrimCardDialog(
        onDismissRequest = onDismiss,
        dismissOnBackPress = dismissOnBackPress,
        dismissOnClickOutside = dismissOnClickOutside,
        surfaceModifier = Modifier
            .padding(24.dp)
            .widthIn(max = 440.dp)
            .fillMaxWidth()
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
fun OliveDialogHeader(
    title: String,
    onDismiss: () -> Unit,
    headerAction: (@Composable () -> Unit)? = null
) {
    val extColors = LocalOliveExtendedColors.current
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
}

/**
 * Scrollable modal card with a standard [OliveDialogHeader] and optional pinned [bottomBar].
 * Computes `maxCardHeight` against system bar vertical insets so tall content scrolls cleanly.
 */
@Composable
fun InfoPopup(
    title: String,
    onDismiss: () -> Unit,
    fillMaxWidth: Boolean = false,
    headerAction: (@Composable () -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val extColors = LocalOliveExtendedColors.current
    val windowController = LocalPlatformWindowController.current
    val windowSize = LocalWindowInfo.current.containerSize
    val dialogPadding = 24.dp
    val verticalInsetPx = remember(windowSize, windowController) {
        windowController.getSystemBarsVerticalInsetPx()
    }
    val maxCardHeight = if (windowSize.height > 0) {
        val availableHeightPx = (windowSize.height - verticalInsetPx).coerceAtLeast(0)
        with(LocalDensity.current) {
            (availableHeightPx.toDp() - (dialogPadding * 2 + 1.dp)).coerceAtLeast(160.dp)
        }
    } else {
        Dp.Unspecified
    }
    OliveScrimCardDialog(
        onDismissRequest = onDismiss,
        dismissOnBackPress = true,
        dismissOnClickOutside = true,
        surfaceModifier = Modifier
            .padding(dialogPadding)
            .widthIn(min = 200.dp, max = 440.dp)
            .heightIn(max = maxCardHeight)
            .then(if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier)
    ) {
        Column(
            modifier = if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier.width(
                IntrinsicSize.Max
            )
        ) {
            OliveDialogHeader(
                title = title,
                onDismiss = onDismiss,
                headerAction = headerAction
            )
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
        if (suffix.isNotEmpty()) {
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

    ProvideUnscaledText {
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
}

/**
 * Celestial object detail sheet showing catalog metadata, coordinates, observation history,
 * HiPS sky preview, and quick actions (Center, GoTo, Align, Observing Lists, Log Observation).
 *
 * Defers [ObjectHiPSPreview] and aggregated observation queries until `coLocatedReady` settles
 * so co-located cross-catalog entries (e.g. Messier/NGC pairs) are merged cleanly.
 */
@Composable
fun ObjectInfoPopup(
    coords: Pair<Double, Double>,
    catalogObject: CatalogObject?,
    viewModel: MainViewModel,
    timeMs: Long,
    onDismiss: () -> Unit,
    altAz: AltAz? = null,
    onCloseToMap: () -> Unit = onDismiss,
    initialObservationsExpanded: Boolean = false,
) {
    val extColors = LocalOliveExtendedColors.current
    val coroutineScope = rememberCoroutineScope()
    var coLocated by remember(catalogObject) { mutableStateOf<List<CatalogObject>>(emptyList()) }
    var coLocatedReady by remember(catalogObject) {
        mutableStateOf(
            catalogObject == null ||
                    CatalogDatabase.isDynamicObject(catalogObject) ||
                    HiPSObjectPlanner.parseExtentArcmin(catalogObject.angularSize) != null
        )
    }
    var notesExpanded by remember(catalogObject) { mutableStateOf(false) }
    var listsExpanded by remember(catalogObject) { mutableStateOf(false) }
    var observationsExpanded by remember(catalogObject, initialObservationsExpanded) {
        mutableStateOf(initialObservationsExpanded)
    }
    var showAlignConfirm by remember(coords, catalogObject) { mutableStateOf(false) }
    var showListPicker by remember { mutableStateOf(false) }
    var showCreateListPrompt by remember { mutableStateOf(false) }
    var showLogObservationDialog by remember { mutableStateOf(false) }
    var editingObservation by remember { mutableStateOf<ObservationRecord?>(null) }
    var deletingObservation by remember { mutableStateOf<ObservationRecord?>(null) }
    var memberListIds by remember(coords, catalogObject) { mutableStateOf<Set<Long>>(emptySet()) }
    var targetObservations by remember(coords, catalogObject) {
        mutableStateOf<List<ObservationRecord>>(emptyList())
    }

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

    val targetRef = remember(catalogObject, coords, title) {
        UserDatabase.targetRefFrom(catalogObject, coords, title)
    }

    LaunchedEffect(targetRef, coLocatedReady, coLocated, viewModel.userDataVersion) {
        memberListIds = viewModel.getListIdsContainingObject(targetRef)
        if (coLocatedReady) {
            targetObservations = viewModel.getObservationsForTarget(targetRef, coLocated)
        }
    }

    val manualLists = viewModel.manualLists
    val inAnyManualList = memberListIds.isNotEmpty()
    val hasObservations = targetObservations.isNotEmpty()

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
                val itemModifier = Modifier.weight(1f)
                IconBarItem(
                    modifier = itemModifier,
                    icon = Icons.Default.CenterFocusStrong,
                    label = stringResource(Res.string.crop_center),
                    iconSize = 24.dp,
                    onClick = {
                        viewModel.skyMapCenterTarget = coords
                        onCloseToMap()
                    },
                )
                if (canGoTo) {
                    IconBarItem(
                        modifier = itemModifier,
                        icon = Icons.Default.GpsFixed,
                        label = stringResource(Res.string.btn_goto),
                        iconSize = 24.dp,
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
                        modifier = itemModifier,
                        icon = Icons.Default.FilterCenterFocus,
                        label = stringResource(Res.string.btn_align),
                        iconSize = 24.dp,
                        onClick = { showAlignConfirm = true },
                    )
                }
                if (isSelectedTarget) {
                    IconBarItem(
                        modifier = itemModifier,
                        icon = Icons.Default.Stop,
                        label = stringResource(Res.string.btn_stop),
                        iconSize = 24.dp,
                        iconTint = extColors.ctaStop,
                        labelColor = extColors.ctaStop,
                        onClick = {
                            viewModel.clearGoToTarget()
                            onDismiss()
                        },
                    )
                }
                IconBarItem(
                    modifier = itemModifier,
                    icon = if (inAnyManualList) {
                        Icons.AutoMirrored.Outlined.PlaylistAddCheck
                    } else {
                        Icons.AutoMirrored.Outlined.PlaylistAdd
                    },
                    label = stringResource(Res.string.btn_lists),
                    iconSize = 24.dp,
                    iconTint = if (inAnyManualList) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    labelColor = if (inAnyManualList) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    onClick = {
                        if (manualLists.isEmpty()) {
                            showCreateListPrompt = true
                        } else {
                            showListPicker = true
                        }
                    },
                )
                IconBarItem(
                    modifier = itemModifier,
                    icon = Icons.Outlined.EditNote,
                    label = stringResource(Res.string.btn_log),
                    iconSize = 24.dp,
                    iconTint = if (hasObservations) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    labelColor = if (hasObservations) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    onClick = { showLogObservationDialog = true },
                )
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
                    formatAltAz(it.alt, it.az),
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
            val containingManualLists = manualLists.filter { it.id in memberListIds }
            val containingNightSessions = remember(targetObservations) {
                targetObservations.map { it.observingNight }.distinct()
            }
            val totalListCount = containingManualLists.size + containingNightSessions.size
            if (totalListCount > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { listsExpanded = !listsExpanded }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            Res.string.format_in_lists_header,
                            totalListCount
                        ),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (listsExpanded) {
                            Icons.Default.KeyboardArrowUp
                        } else {
                            Icons.Default.KeyboardArrowDown
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                if (listsExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        containingManualLists.forEach { list ->
                            ObservingListLinkRow(
                                icon = Icons.Outlined.Checklist,
                                iconTint = MaterialTheme.colorScheme.primary,
                                label = list.name,
                                onClick = {
                                    onDismiss()
                                    viewModel.openManualList(list.id)
                                }
                            )
                        }
                        containingNightSessions.forEach { nightKey ->
                            ObservingListLinkRow(
                                icon = Icons.Outlined.History,
                                iconTint = extColors.secondaryText,
                                label = nightKey,
                                onClick = {
                                    onDismiss()
                                    viewModel.openNightSession(nightKey)
                                }
                            )
                        }
                    }
                }
            }
            if (targetObservations.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { observationsExpanded = !observationsExpanded }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            Res.string.format_observations_header,
                            targetObservations.size
                        ),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (observationsExpanded) {
                            Icons.Default.KeyboardArrowUp
                        } else {
                            Icons.Default.KeyboardArrowDown
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                if (observationsExpanded) {
                    val is24Hour = is24HourFormat()
                    val amLabel = stringResource(Res.string.label_am)
                    val pmLabel = stringResource(Res.string.label_pm)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        targetObservations.forEach { obs ->
                            val cardShape = RoundedCornerShape(8.dp)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.surfaceContainerHigh,
                                        cardShape
                                    )
                                    .border(1.dp, extColors.subtleBorder, cardShape)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = UserDatabase.formatObservationTimestamp(
                                            obs.observedAtMs,
                                            is24Hour = is24Hour,
                                            amLabel = amLabel,
                                            pmLabel = pmLabel
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        IconButton(
                                            onClick = { editingObservation = obs },
                                            modifier = Modifier.size(44.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Edit,
                                                contentDescription = stringResource(Res.string.btn_edit),
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = { deletingObservation = obs },
                                            modifier = Modifier.size(44.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Delete,
                                                contentDescription = stringResource(Res.string.cd_delete),
                                                tint = extColors.ctaStop,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                                if (obs.latDeg != null && obs.lonDeg != null &&
                                    MainViewModel.isValidCoordinate(obs.latDeg, obs.lonDeg)
                                ) {
                                    val locText = formatObserverCoordinate(obs.latDeg, obs.lonDeg)
                                    val altAzSuffix = if (obs.altDeg != null && obs.azDeg != null) {
                                        " · " + formatAltAz(obs.altDeg, obs.azDeg)
                                    } else {
                                        ""
                                    }
                                    Text(
                                        text = locText + altAzSuffix,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = extColors.secondaryText
                                    )
                                }
                                Text(
                                    text = obs.notes.ifBlank {
                                        stringResource(Res.string.status_logged_without_notes)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (obs.notes.isBlank()) {
                                        extColors.secondaryText
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }
                        }
                    }
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

    if (showListPicker) {
        ObservingListPickerDialog(
            lists = manualLists,
            memberListIds = memberListIds,
            lastSelectedListId = viewModel.lastSelectedObservingListId,
            onToggleList = { listId, currentlyMember ->
                memberListIds = if (currentlyMember) {
                    memberListIds - listId
                } else {
                    memberListIds + listId
                }
                viewModel.lastSelectedObservingListId = listId
                coroutineScope.launch {
                    if (currentlyMember) {
                        viewModel.removeObjectFromList(
                            listId,
                            targetRef.catalogLabel,
                            targetRef.catalogEntry
                        )
                    } else {
                        viewModel.addObjectToList(listId, targetRef)
                    }
                }
            },
            onCreateNewList = {
                @Suppress("AssignedValueIsNeverRead")
                showListPicker = false
                showCreateListPrompt = true
            },
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                showListPicker = false
            }
        )
    }

    if (showCreateListPrompt) {
        ListNameDialog(
            title = stringResource(Res.string.title_new_list),
            initialName = "",
            viewModel = viewModel,
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                showCreateListPrompt = false
            },
            onConfirm = { name ->
                @Suppress("AssignedValueIsNeverRead")
                showCreateListPrompt = false
                coroutineScope.launch {
                    val newId = viewModel.createManualList(name)
                    if (newId > 0L) {
                        viewModel.addObjectToList(newId, targetRef)
                    }
                }
            }
        )
    }

    if (showLogObservationDialog) {
        val logTimeMs = remember { Clock.System.now().toEpochMilliseconds() }
        val logLat = viewModel.currentLat
        val logLon = viewModel.currentLon
        val logAltAz = remember(logTimeMs, logLat, logLon, coords) {
            if (viewModel.hasValidLocation && logLat != null && logLon != null) {
                AstrometryUtils.calculateAltAz(
                    coords.first,
                    coords.second,
                    logLat,
                    logLon,
                    logTimeMs
                )
            } else {
                altAz
            }
        }
        LogObservationDialog(
            targetName = title,
            targetRaDeg = coords.first,
            targetDecDeg = coords.second,
            observedAtMs = logTimeMs,
            latDeg = logLat,
            lonDeg = logLon,
            altDeg = logAltAz?.alt,
            azDeg = logAltAz?.az,
            viewModel = viewModel,
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                showLogObservationDialog = false
            },
            onSave = { savedTimeMs, savedLat, savedLon, savedAlt, savedAz, notes ->
                @Suppress("AssignedValueIsNeverRead")
                showLogObservationDialog = false
                observationsExpanded = true
                coroutineScope.launch {
                    viewModel.logObservation(
                        target = targetRef,
                        observedAtMs = savedTimeMs,
                        latDeg = savedLat,
                        lonDeg = savedLon,
                        altDeg = savedAlt,
                        azDeg = savedAz,
                        notes = notes
                    )
                }
            }
        )
    }

    val obsToEdit = editingObservation
    if (obsToEdit != null) {
        LogObservationDialog(
            targetName = title,
            targetRaDeg = obsToEdit.raDeg,
            targetDecDeg = obsToEdit.decDeg,
            observedAtMs = obsToEdit.observedAtMs,
            latDeg = obsToEdit.latDeg,
            lonDeg = obsToEdit.lonDeg,
            altDeg = obsToEdit.altDeg,
            azDeg = obsToEdit.azDeg,
            initialNotes = obsToEdit.notes,
            isEditing = true,
            viewModel = viewModel,
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                editingObservation = null
            },
            onSave = { _, _, _, _, _, updatedNotes ->
                val obsId = obsToEdit.id
                @Suppress("AssignedValueIsNeverRead")
                editingObservation = null
                coroutineScope.launch {
                    viewModel.updateObservationNotes(obsId, updatedNotes)
                }
            }
        )
    }

    val obsToDelete = deletingObservation
    if (obsToDelete != null) {
        OliveAlertDialog(
            title = stringResource(Res.string.title_delete_observation),
            text = stringResource(Res.string.msg_delete_observation),
            confirmButtonText = stringResource(Res.string.btn_delete),
            onConfirm = {
                val obsId = obsToDelete.id
                @Suppress("AssignedValueIsNeverRead")
                deletingObservation = null
                coroutineScope.launch {
                    viewModel.deleteObservation(obsId)
                }
            },
            dismissButtonText = stringResource(Res.string.btn_cancel),
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                deletingObservation = null
            }
        )
    }
}

@Composable
private fun ObservingListLinkRow(
    icon: ImageVector,
    iconTint: Color,
    label: String,
    onClick: () -> Unit
) {
    val extColors = LocalOliveExtendedColors.current
    val rowShape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, rowShape)
            .border(1.dp, extColors.subtleBorder, rowShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = stringResource(Res.string.cd_open_list),
            tint = extColors.secondaryText,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
fun formatAltAz(altDeg: Double, azDeg: Double): String =
    stringResource(
        Res.string.format_alt_az,
        String.format("%.1f", altDeg),
        String.format("%.1f", azDeg),
        localizedCompassDirection(azDeg)
    )

data class SplitDegreeCoord(
    val isPositive: Boolean,
    val wholeDeg: String,
    val fracDeg: String
)

fun splitDecimalDegrees(value: Double?): SplitDegreeCoord {
    if (value == null) return SplitDegreeCoord(isPositive = true, wholeDeg = "", fracDeg = "")
    val hundredths = (abs(value) * 100.0).roundToInt()
    return SplitDegreeCoord(
        isPositive = value >= 0.0,
        wholeDeg = (hundredths / 100).toString(),
        fracDeg = (hundredths % 100).toString().padStart(2, '0')
    )
}

fun combineDecimalDegrees(
    wholeStr: String,
    fracStr: String,
    isPositive: Boolean,
    maxWhole: Int
): Double {
    val whole = (wholeStr.toIntOrNull() ?: 0).coerceIn(0, maxWhole)
    val frac = if (whole == maxWhole) {
        0
    } else {
        (fracStr.padEnd(2, '0').toIntOrNull() ?: 0).coerceIn(0, 99)
    }
    val magnitude = whole + frac / 100.0
    return if (isPositive) magnitude else -magnitude
}

@Composable
fun formatObserverCoordinate(latDeg: Double, lonDeg: Double): String {
    val latDir =
        if (latDeg >= 0) stringResource(Res.string.dir_north) else stringResource(Res.string.dir_south)
    val lonDir =
        if (lonDeg >= 0) stringResource(Res.string.dir_east) else stringResource(Res.string.dir_west)
    return stringResource(
        Res.string.format_coordinate,
        abs(latDeg),
        latDir,
        abs(lonDeg),
        lonDir
    )
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
fun solvedCropLabel(solvedCrop: String?): String = when (solvedCrop) {
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

    else -> solvedCrop ?: "-"
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

        LabeledValueRow(
            label = stringResource(id = Res.string.stat_last_solve),
            value = solvedCropLabel(stats.solvedCrop)
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



