// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

private val RETICLE_CORNERS = arrayOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)
private const val ZOOM_ACTIVE_THRESHOLD = 1.05f

private enum class TunerInfoTopic {
    HFR,
    STAR_SENSITIVITY,
    ZOOM,
    EXPOSURE,
    STACKING,
    BRIGHTNESS,
    GAIN,
    FOCUS,
    NOISE_REDUCTION,
    HOT_PIXEL
}

/**
 * Live Camera & Alignment Tuner modal shown while the engine is running.
 *
 * Enables HFR focus telemetry only while open (`DisposableEffect`), rotates the internal camera
 * feed by `(sensorOrientation - displayRotationDegrees)`, and adapts between a side-by-side
 * landscape split and a scrollable portrait layout.
 */
@Composable
fun TunerDialog(
    viewModel: MainViewModel,
    settingsManager: SettingsManager,
    activeCameraSelection: String,
    isLandscape: Boolean,
    onBack: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    var alignTargetX by remember { mutableStateOf<Float?>(null) }
    var alignTargetY by remember { mutableStateOf<Float?>(null) }
    var showAlignDialog by remember { mutableStateOf(false) }
    var showResetAlignDialog by remember { mutableStateOf(false) }
    var activeInfoTopic by remember { mutableStateOf<TunerInfoTopic?>(null) }

    DisposableEffect(Unit) {
        viewModel.setHfrEnabled(true)
        onDispose {
            viewModel.setHfrEnabled(false)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.fetchRealtimeSettings()
    }

    val onUserInteraction = LocalOnUserInteraction.current
    val extColors = LocalOliveExtendedColors.current
    val windowController = LocalPlatformWindowController.current
    val capabilities = LocalPlatformCapabilities.current
    val displayRotationDegrees = windowController.getDisplayRotationDegrees()
    val controlsScrollState = rememberScrollState()

    val isInternalCamera =
        viewModel.engineMode == MainViewModel.EngineMode.NATIVE && activeCameraSelection != "demo"
    val effectiveCameraId =
        if (viewModel.isAutoEngineSession && !capabilities.isApple) {
            "0"
        } else {
            settingsManager.internalCameraId
        }
    val sensorOrientation = remember(isInternalCamera, effectiveCameraId) {
        if (isInternalCamera) {
            windowController.getCameraSensorOrientation(effectiveCameraId)
        } else {
            0
        }
    }

    val imageRotation = if (isInternalCamera) {
        ((sensorOrientation - displayRotationDegrees + 360) % 360).toFloat()
    } else {
        0f
    }

    @Composable
    fun CameraFeed(modifier: Modifier) {
        CameraViewport(
            viewModel = viewModel,
            imageRotation = imageRotation,
            scale = scale,
            offset = offset,
            alignTargetX = alignTargetX,
            alignTargetY = alignTargetY,
            onTransformChange = { newScale, newOffset ->
                @Suppress("AssignedValueIsNeverRead")
                scale = newScale
                @Suppress("AssignedValueIsNeverRead")
                offset = newOffset
            },
            onAlignTargetSelected = { x, y ->
                alignTargetX = x
                alignTargetY = y
            },
            onResetView = {
                @Suppress("AssignedValueIsNeverRead")
                scale = 1f
                @Suppress("AssignedValueIsNeverRead")
                offset = Offset.Zero
            },
            modifier = modifier
        )
    }

    @Composable
    fun AlignRow() {
        AlignmentActionRow(
            viewModel = viewModel,
            hasPendingTarget = alignTargetX != null && alignTargetY != null,
            onAlignClick = { showAlignDialog = true },
            onResetClick = { showResetAlignDialog = true }
        )
    }

    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val dialogShape = RoundedCornerShape(16.dp)
        val dialogSizeModifier = if (isLandscape) {
            Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .widthIn(max = 960.dp)
                .heightIn(max = 720.dp)
                .fillMaxWidth()
                .fillMaxHeight(0.94f)
        } else {
            Modifier
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .widthIn(max = 540.dp)
                .fillMaxWidth()
                .fillMaxHeight(0.90f)
        }

        ProvideScaledText {
            Surface(
                shape = dialogShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = dialogSizeModifier
                    .border(1.dp, extColors.subtleBorder, dialogShape)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial)
                                onUserInteraction()
                            }
                        }
                    }
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    OliveDialogHeader(
                        title = stringResource(Res.string.btn_camera_align),
                        onDismiss = onBack
                    )

                    if (isLandscape) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CameraFeed(
                                Modifier
                                    .weight(1.15f)
                                    .fillMaxHeight()
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            ) {
                                TelemetryStrip(
                                    viewModel = viewModel,
                                    onShowHfrInfo = { activeInfoTopic = TunerInfoTopic.HFR }
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                AlignRow()
                                HorizontalDivider(
                                    color = extColors.dialogDivider,
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                                TuningControls(
                                    viewModel = viewModel,
                                    settingsManager = settingsManager,
                                    activeCameraSelection = activeCameraSelection,
                                    effectiveCameraId = effectiveCameraId,
                                    scrollState = controlsScrollState,
                                    onShowInfo = { activeInfoTopic = it },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                )
                            }
                        }
                    } else {
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(start = 12.dp, top = 12.dp, end = 4.dp, bottom = 12.dp)
                        ) {
                            val maxCameraHeight = (maxHeight - 88.dp).coerceAtLeast(80.dp)
                            val cameraHeight =
                                (maxHeight * 0.52f).coerceIn(80.dp, minOf(320.dp, maxCameraHeight))
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScrollWithScrollbar(controlsScrollState)
                                    .padding(end = 8.dp, bottom = 8.dp)
                            ) {
                                CameraFeed(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(cameraHeight)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                AlignRow()
                                Spacer(modifier = Modifier.height(6.dp))
                                TelemetryStrip(
                                    viewModel = viewModel,
                                    onShowHfrInfo = { activeInfoTopic = TunerInfoTopic.HFR }
                                )
                                HorizontalDivider(
                                    color = extColors.dialogDivider,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                                TuningControls(
                                    viewModel = viewModel,
                                    settingsManager = settingsManager,
                                    activeCameraSelection = activeCameraSelection,
                                    effectiveCameraId = effectiveCameraId,
                                    onShowInfo = { activeInfoTopic = it },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAlignDialog) {
        OliveAlertDialog(
            title = stringResource(Res.string.title_confirm_alignment),
            text = stringResource(Res.string.inst_confirm_alignment),
            confirmButtonText = stringResource(Res.string.btn_confirm),
            onConfirm = {
                @Suppress("AssignedValueIsNeverRead")
                showAlignDialog = false
                val targetX = alignTargetX
                val targetY = alignTargetY
                if (targetX != null && targetY != null) {
                    viewModel.setAlignTargetPixel(
                        targetX.toDouble(),
                        targetY.toDouble()
                    )
                }
                alignTargetX = null
                alignTargetY = null
            },
            dismissButtonText = stringResource(id = Res.string.btn_cancel),
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                showAlignDialog = false
            }
        )
    }

    if (showResetAlignDialog) {
        OliveAlertDialog(
            title = stringResource(Res.string.title_reset_alignment),
            text = stringResource(Res.string.inst_reset_alignment),
            confirmButtonText = stringResource(Res.string.btn_confirm),
            onConfirm = {
                @Suppress("AssignedValueIsNeverRead")
                showResetAlignDialog = false
                viewModel.clearAlignTargetPixel()
                alignTargetX = null
                alignTargetY = null
            },
            dismissButtonText = stringResource(id = Res.string.btn_cancel),
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                showResetAlignDialog = false
            }
        )
    }

    activeInfoTopic?.let { topic ->
        val isNativeMode = viewModel.engineMode == MainViewModel.EngineMode.NATIVE
        val (title, bodyText, isHtml) = when (topic) {
            TunerInfoTopic.HFR -> Triple(
                stringResource(Res.string.info_hfr_label),
                stringResource(Res.string.info_hfr),
                false
            )

            TunerInfoTopic.STAR_SENSITIVITY -> Triple(
                stringResource(Res.string.label_star_sensitivity),
                stringResource(Res.string.info_star_sensitivity),
                false
            )

            TunerInfoTopic.ZOOM -> Triple(
                stringResource(Res.string.label_zoom),
                stringResource(Res.string.info_zoom),
                false
            )

            TunerInfoTopic.EXPOSURE -> Triple(
                stringResource(Res.string.label_exposure),
                stringResource(Res.string.info_exposure),
                false
            )

            TunerInfoTopic.STACKING -> Triple(
                stringResource(Res.string.label_stacking_frames),
                stringResource(Res.string.info_stacking),
                false
            )

            TunerInfoTopic.BRIGHTNESS -> Triple(
                stringResource(Res.string.label_brightness),
                stringResource(Res.string.info_brightness),
                false
            )

            TunerInfoTopic.GAIN -> Triple(
                stringResource(if (isNativeMode) Res.string.label_iso else Res.string.label_gain),
                stringResource(if (isNativeMode) Res.string.info_iso else Res.string.info_gain),
                isNativeMode
            )

            TunerInfoTopic.FOCUS -> Triple(
                stringResource(Res.string.label_focus),
                stringResource(Res.string.info_focus),
                false
            )

            TunerInfoTopic.NOISE_REDUCTION -> Triple(
                stringResource(Res.string.label_noise_reduction),
                stringResource(Res.string.info_noise_reduction),
                false
            )

            TunerInfoTopic.HOT_PIXEL -> Triple(
                stringResource(Res.string.label_hot_pixel_mode),
                stringResource(Res.string.info_hot_pixel_mode),
                false
            )
        }
        InfoPopup(
            title = title,
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                activeInfoTopic = null
            }
        ) {
            if (isHtml) {
                Text(AnnotatedString.fromHtml(bodyText))
            } else {
                Text(bodyText)
            }
        }
    }
}

@Composable
private fun TelemetryStrip(
    viewModel: MainViewModel,
    onShowHfrInfo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val extColors = LocalOliveExtendedColors.current
    val stats = viewModel.engineStats
    val (statusIcon, statusText, statusColor) = when {
        stats.isCalibratingFocus -> Triple(
            Icons.Outlined.Loop,
            stringResource(Res.string.stat_calibrating_focus),
            extColors.telemetryAccent
        )

        stats.isCalibrating -> Triple(
            Icons.Outlined.Loop,
            stringResource(Res.string.stat_calibrating),
            extColors.telemetryAccent
        )

        stats.hasValidSolve -> Triple(
            Icons.Outlined.Check,
            solvedCropLabel(stats.solvedCrop),
            extColors.fovBoxSolved
        )

        stats.isImuEstimate -> Triple(
            Icons.Outlined.Loop,
            stringResource(Res.string.status_imu),
            extColors.fovBoxEstimate
        )

        stats.solvedCrop == EngineStats.CROP_FAILED -> Triple(
            Icons.Outlined.ErrorOutline,
            stringResource(Res.string.crop_failed),
            extColors.ctaStop
        )

        else -> Triple(
            Icons.Outlined.Loop,
            stringResource(Res.string.stat_solving),
            extColors.telemetryAccent
        )
    }
    val stripShape = RoundedCornerShape(10.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, stripShape)
            .border(1.dp, extColors.subtleBorder, stripShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            Icon(
                imageVector = statusIcon,
                contentDescription = stringResource(Res.string.cd_solve_status),
                tint = statusColor,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = statusColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.StarOutline,
                contentDescription = stringResource(Res.string.cd_stars),
                tint = extColors.hudSecondaryStat,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = "${stats.centroidCount}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            val hfrDisplay = if (stats.hfr > 0.0) {
                String.format("%.2f", stats.hfr)
            } else {
                stringResource(Res.string.value_not_available)
            }
            Text(
                text = stringResource(
                    Res.string.format_label_value_string,
                    stringResource(Res.string.info_hfr_label),
                    hfrDisplay
                ),
                style = MaterialTheme.typography.labelLarge,
                color = extColors.telemetryAccent,
                maxLines = 1,
                softWrap = false
            )
            IconButton(
                onClick = onShowHfrInfo,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = stringResource(Res.string.cd_info),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * Pinch-to-zoom and tap-to-align live camera preview.
 *
 * Coordinate invariants:
 * - `baseScale` compensates for 90°/270° rotated frames so they fit inside the viewport cleanly.
 * - `canPan` is enabled only above [ZOOM_ACTIVE_THRESHOLD] so portrait vertical scroll works
 *   normally at 1x zoom.
 * - Tap coordinates invert `translation -> rotation -> scale/mirror -> fitScale` back to raw
 *   bitmap pixels for boresight alignment.
 * - Overlay reticles divide stroke/arm sizes by `scale * baseScale` to hold constant screen weight.
 */
@Composable
private fun CameraViewport(
    viewModel: MainViewModel,
    imageRotation: Float,
    scale: Float,
    offset: Offset,
    alignTargetX: Float?,
    alignTargetY: Float?,
    onTransformChange: (Float, Offset) -> Unit,
    onAlignTargetSelected: (Float, Float) -> Unit,
    onResetView: () -> Unit,
    modifier: Modifier = Modifier
) {
    val extColors = LocalOliveExtendedColors.current
    val viewportShape = RoundedCornerShape(12.dp)
    BoxWithConstraints(
        modifier = modifier
            .clip(viewportShape)
            .background(PureBlack)
            .border(1.dp, extColors.subtleBorder, viewportShape)
    ) {
        val frame = viewModel.latestFrame
        val layoutW = constraints.maxWidth.toFloat()
        val layoutH = constraints.maxHeight.toFloat()

        val visualWidth: Float
        val visualHeight: Float
        val baseScale: Float
        val maxOffsetX: Float
        val maxOffsetY: Float

        if (frame != null && frame.width > 0 && frame.height > 0 && layoutW > 0f && layoutH > 0f) {
            val bW = frame.width.toFloat()
            val bH = frame.height.toFloat()
            val fitPxScale = minOf(layoutW / bW, layoutH / bH)

            val drawnWidth = bW * fitPxScale
            val drawnHeight = bH * fitPxScale
            val rotated = imageRotation % 180f != 0f
            baseScale = if (rotated) {
                minOf(layoutW / drawnHeight, layoutH / drawnWidth)
            } else {
                1f
            }

            visualWidth = if (rotated) drawnHeight else drawnWidth
            visualHeight = if (rotated) drawnWidth else drawnHeight

            maxOffsetX = maxOf(0f, (visualWidth * scale * baseScale - layoutW) / 2f)
            maxOffsetY = maxOf(0f, (visualHeight * scale * baseScale - layoutH) / 2f)
        } else {
            visualWidth = 0f
            visualHeight = 0f
            baseScale = 1f
            maxOffsetX = 0f
            maxOffsetY = 0f
        }

        LaunchedEffect(maxOffsetX, maxOffsetY) {
            val clampedX = offset.x.coerceIn(-maxOffsetX, maxOffsetX)
            val clampedY = offset.y.coerceIn(-maxOffsetY, maxOffsetY)
            if (clampedX != offset.x || clampedY != offset.y) {
                onTransformChange(scale, Offset(clampedX, clampedY))
            }
        }

        val isMirrored = viewModel.engineStats.isMirrored == true
        val isRotated90 = (imageRotation.toInt() % 180 != 0)
        val mirrorX = if (isMirrored && isRotated90) -1f else 1f
        val mirrorY = if (isMirrored && !isRotated90) -1f else 1f

        val latestVisualW by rememberUpdatedState(visualWidth)
        val latestVisualH by rememberUpdatedState(visualHeight)
        val latestLayoutW by rememberUpdatedState(layoutW)
        val latestLayoutH by rememberUpdatedState(layoutH)
        val latestBaseScale by rememberUpdatedState(baseScale)
        val latestScale by rememberUpdatedState(scale)
        val latestOffset by rememberUpdatedState(offset)
        val latestOnTransformChange by rememberUpdatedState(onTransformChange)
        val latestOnAlignTargetSelected by rememberUpdatedState(onAlignTargetSelected)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .transformable(
                    state = rememberTransformableState { zoomChange, panChange, _ ->
                        val newScale = (latestScale * zoomChange).coerceIn(1f, 10f)
                        val newOffset = if (newScale <= ZOOM_ACTIVE_THRESHOLD) {
                            Offset.Zero
                        } else {
                            val scaleRatio = if (latestScale > 0f) newScale / latestScale else 1f
                            val curMaxX = maxOf(
                                0f,
                                (latestVisualW * newScale * latestBaseScale - latestLayoutW) / 2f
                            )
                            val curMaxY = maxOf(
                                0f,
                                (latestVisualH * newScale * latestBaseScale - latestLayoutH) / 2f
                            )
                            Offset(
                                x = (latestOffset.x * scaleRatio + panChange.x).coerceIn(
                                    -curMaxX,
                                    curMaxX
                                ),
                                y = (latestOffset.y * scaleRatio + panChange.y).coerceIn(
                                    -curMaxY,
                                    curMaxY
                                )
                            )
                        }
                        latestOnTransformChange(newScale, newOffset)
                    },
                    canPan = { latestScale > ZOOM_ACTIVE_THRESHOLD }
                )
        ) {
            if (frame != null) {
                val bW = frame.width.toFloat()
                val bH = frame.height.toFloat()
                val fitPxScale = minOf(layoutW / bW, layoutH / bH)

                val latestBW by rememberUpdatedState(bW)
                val latestBH by rememberUpdatedState(bH)
                val latestFitPxScale by rememberUpdatedState(fitPxScale)
                val latestImageRotation by rememberUpdatedState(imageRotation)
                val latestMirrorX by rememberUpdatedState(mirrorX)
                val latestMirrorY by rememberUpdatedState(mirrorY)

                Image(
                    bitmap = frame,
                    contentDescription = stringResource(id = Res.string.cd_live_preview),
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { tapOffset ->
                                val cx = latestLayoutW / 2f
                                val cy = latestLayoutH / 2f

                                val screenDx = tapOffset.x - cx
                                val screenDy = tapOffset.y - cy

                                val translatedDx = screenDx - latestOffset.x
                                val translatedDy = screenDy - latestOffset.y

                                val angleRad = -Math.toRadians(latestImageRotation.toDouble())
                                val rx =
                                    (translatedDx * cos(angleRad) - translatedDy * sin(
                                        angleRad
                                    )).toFloat()
                                val ry =
                                    (translatedDx * sin(angleRad) + translatedDy * cos(
                                        angleRad
                                    )).toFloat()

                                val totalScale = latestScale * latestBaseScale
                                val unscaledX = rx / (totalScale * latestMirrorX)
                                val unscaledY = ry / (totalScale * latestMirrorY)

                                val px = (unscaledX / latestFitPxScale) + latestBW / 2f
                                val py = (unscaledY / latestFitPxScale) + latestBH / 2f
                                if (px in 0f..latestBW && py in 0f..latestBH) {
                                    latestOnAlignTargetSelected(px, py)
                                }
                            }
                        }
                        .graphicsLayer(
                            scaleX = scale * baseScale * mirrorX,
                            scaleY = scale * baseScale * mirrorY,
                            translationX = offset.x,
                            translationY = offset.y,
                            rotationZ = imageRotation
                        )
                )

                val currentBoresightX =
                    (viewModel.engineStats.targetPixelX ?: (bW / 2.0)).toFloat()
                val currentBoresightY =
                    (viewModel.engineStats.targetPixelY ?: (bH / 2.0)).toFloat()
                val reticleColor = extColors.alignReticle
                val crosshairColor = MaterialTheme.colorScheme.primary

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale * baseScale * mirrorX,
                            scaleY = scale * baseScale * mirrorY,
                            translationX = offset.x,
                            translationY = offset.y,
                            rotationZ = imageRotation
                        )
                ) {
                    val canvasFitScale = minOf(size.width / bW, size.height / bH)

                    val boresightCanvasX =
                        center.x + (currentBoresightX - bW / 2f) * canvasFitScale
                    val boresightCanvasY =
                        center.y + (currentBoresightY - bH / 2f) * canvasFitScale

                    val s = 32f / (scale * baseScale)
                    val arm = 14f / (scale * baseScale)
                    val bracketStroke = 3f / (scale * baseScale)

                    for ((sx, sy) in RETICLE_CORNERS) {
                        val corner = Offset(boresightCanvasX + sx * s, boresightCanvasY + sy * s)
                        drawLine(
                            reticleColor,
                            corner,
                            Offset(corner.x - sx * arm, corner.y),
                            strokeWidth = bracketStroke,
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            reticleColor,
                            corner,
                            Offset(corner.x, corner.y - sy * arm),
                            strokeWidth = bracketStroke,
                            cap = StrokeCap.Round
                        )
                    }

                    if (alignTargetX != null && alignTargetY != null) {
                        val canvasX = center.x + (alignTargetX - bW / 2f) * canvasFitScale
                        val canvasY = center.y + (alignTargetY - bH / 2f) * canvasFitScale
                        val crosshairSize = 40f / (scale * baseScale)
                        val strokeW = 3f / (scale * baseScale)

                        drawCircle(
                            color = crosshairColor,
                            radius = 20f / (scale * baseScale),
                            center = Offset(canvasX, canvasY),
                            style = Stroke(width = strokeW)
                        )
                        drawLine(
                            color = crosshairColor,
                            start = Offset(canvasX - crosshairSize, canvasY),
                            end = Offset(canvasX + crosshairSize, canvasY),
                            strokeWidth = strokeW
                        )
                        drawLine(
                            color = crosshairColor,
                            start = Offset(canvasX, canvasY - crosshairSize),
                            end = Offset(canvasX, canvasY + crosshairSize),
                            strokeWidth = strokeW
                        )
                    }
                }

                if (scale > ZOOM_ACTIVE_THRESHOLD || offset != Offset.Zero) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(
                                Res.string.format_zoom,
                                String.format("%.1f", scale)
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                            modifier = Modifier
                                .background(
                                    extColors.mapOverlaySurface,
                                    RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                        IconButton(
                            onClick = onResetView,
                            modifier = Modifier
                                .size(30.dp)
                                .background(extColors.mapOverlaySurface, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(Res.string.cd_reset_view),
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(id = Res.string.status_waiting_for_frame),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AlignmentActionRow(
    viewModel: MainViewModel,
    hasPendingTarget: Boolean,
    onAlignClick: () -> Unit,
    onResetClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val extColors = LocalOliveExtendedColors.current
    val hasAlignmentOffset =
        viewModel.engineStats.targetPixelX != null ||
                viewModel.engineStats.targetPixelY != null ||
                hasPendingTarget
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = stringResource(
                if (hasPendingTarget) Res.string.hint_confirm_align
                else Res.string.hint_tap_image_to_align
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (hasPendingTarget) MaterialTheme.colorScheme.primary else extColors.secondaryText,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBarItem(
                icon = Icons.Default.FilterCenterFocus,
                label = stringResource(Res.string.btn_align),
                enabled = hasPendingTarget,
                iconSize = 24.dp,
                iconTint = MaterialTheme.colorScheme.primary,
                labelColor = MaterialTheme.colorScheme.primary,
                onClick = onAlignClick
            )
            IconBarItem(
                icon = Icons.Default.SettingsBackupRestore,
                label = stringResource(Res.string.btn_reset),
                enabled = hasAlignmentOffset,
                iconSize = 24.dp,
                onClick = onResetClick
            )
        }
    }
}

@Composable
private fun TuningControlHeader(
    text: String,
    enabled: Boolean,
    onInfoClick: () -> Unit,
    trailingContent: (@Composable () -> Unit)? = null
) {
    val extColors = LocalOliveExtendedColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else extColors.secondaryText,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            IconButton(
                onClick = onInfoClick,
                modifier = Modifier.size(30.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = stringResource(id = Res.string.cd_info),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        trailingContent?.invoke()
    }
}

@Composable
private fun FocusNudgeButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val extColors = LocalOliveExtendedColors.current
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(32.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(18.dp),
            tint = if (enabled) MaterialTheme.colorScheme.primary else extColors.secondaryText
        )
    }
}

@Composable
private fun TuningSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
    steps: Int = 0
) {
    Slider(
        value = value.coerceIn(valueRange),
        onValueChange = onValueChange,
        valueRange = valueRange,
        enabled = enabled,
        steps = steps,
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
            disabledThumbColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            disabledActiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
            disabledInactiveTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun TuningModeSelector(
    label: String,
    selectedIndex: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    onInfoClick: () -> Unit,
    isOptionSupported: (Int) -> Boolean = { true }
) {
    val extColors = LocalOliveExtendedColors.current
    val radioColors = RadioButtonDefaults.colors(
        selectedColor = MaterialTheme.colorScheme.primary,
        unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
        disabledSelectedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
        disabledUnselectedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    )
    val modes = listOf(
        stringResource(id = Res.string.value_off),
        stringResource(id = Res.string.value_fast),
        stringResource(id = Res.string.value_hq)
    )
    val labelColor = if (enabled) MaterialTheme.colorScheme.onSurface else extColors.secondaryText

    TuningControlHeader(
        text = label,
        enabled = enabled,
        onInfoClick = onInfoClick
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        modes.forEachIndexed { index, name ->
            val supported = isOptionSupported(index)
            RadioButton(
                selected = selectedIndex == index,
                onClick = { onSelect(index) },
                enabled = supported && enabled,
                colors = radioColors
            )
            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium,
                color = if (supported) labelColor else labelColor.copy(alpha = 0.38f),
                modifier = Modifier.padding(end = 8.dp)
            )
        }
    }
}

@Composable
private fun TuningControls(
    viewModel: MainViewModel,
    settingsManager: SettingsManager,
    activeCameraSelection: String,
    effectiveCameraId: String,
    scrollState: ScrollState? = null,
    onShowInfo: (TunerInfoTopic) -> Unit,
    modifier: Modifier = Modifier
) {
    val extColors = LocalOliveExtendedColors.current
    val capabilities = LocalPlatformCapabilities.current
    val isDemo = activeCameraSelection == "demo"
    val isAutoMode = viewModel.isAutoEngineSession
    val isNativeMode = viewModel.engineMode == MainViewModel.EngineMode.NATIVE
    val gainLabel =
        stringResource(if (isNativeMode) Res.string.label_iso else Res.string.label_gain)

    val showFocus = !isDemo && viewModel.engineMode != MainViewModel.EngineMode.UVC
    val showBrightness =
        !isDemo && viewModel.engineMode == MainViewModel.EngineMode.UVC && viewModel.uvcBrightnessMax > viewModel.uvcBrightnessMin

    val effectiveMaxZoom = if (!isDemo && activeCameraSelection == "internal"
    ) {
        val maxZoom = remember(
            viewModel.engineMode,
            isAutoMode,
            settingsManager.sensorMode,
            effectiveCameraId
        ) {
            var mZ = 1f
            try {
                if (viewModel.engineMode == MainViewModel.EngineMode.NATIVE &&
                    (isAutoMode || settingsManager.sensorMode == "Processed")
                ) {
                    val jsonArray = JSONArray(viewModel.getAvailableInternalCameras())
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        if (obj.getString("id") == effectiveCameraId) {
                            mZ =
                                if (obj.has("max_zoom")) obj.getDouble("max_zoom").toFloat() else 1f
                            break
                        }
                    }
                }
            } catch (_: Exception) {
                // Ignore JSON parsing errors if internal camera FOV metadata is unavailable
            }
            mZ
        }
        if (isAutoMode) minOf(2.0f, maxZoom) else maxZoom
    } else {
        1.0f
    }
    val showZoom = effectiveMaxZoom >= 1.1f
    val hasOpticalControls = showFocus || showZoom || showBrightness
    val showNativeProcessing =
        !isDemo && isNativeMode && !capabilities.isApple && (isAutoMode || settingsManager.sensorMode == "Processed")

    Column(
        modifier = if (scrollState != null) {
            modifier
                .verticalScrollWithScrollbar(scrollState)
                .padding(end = 10.dp, bottom = 8.dp)
        } else {
            modifier
        }
    ) {
        // Tier 1: Always-interactive optical & framing controls (Focus, Zoom, UVC Brightness)
        if (showFocus) {
            val focusFormat = if (capabilities.isApple) "%.3f" else "%.2f"
            val nudgeStep = if (capabilities.isApple) 0.002f else 0.05f
            val isFocusLocked = viewModel.engineStats.isCalibratingFocus
            val focusValue = String.format(focusFormat, viewModel.currentFocus)
            val focusDisplay = if (isFocusLocked) {
                "$focusValue (${stringResource(Res.string.stat_calibrating_focus)})"
            } else {
                focusValue
            }
            val focusText = stringResource(
                Res.string.format_label_value_string,
                stringResource(Res.string.label_focus),
                focusDisplay
            )
            val nudgeFocus: (Float) -> Unit = { delta ->
                viewModel.setFocus((viewModel.currentFocus + delta).coerceIn(capabilities.focusRange))
            }

            TuningControlHeader(
                text = focusText,
                enabled = !isFocusLocked,
                onInfoClick = { onShowInfo(TunerInfoTopic.FOCUS) },
                trailingContent = {
                    FocusNudgeButton(
                        icon = Icons.Default.Remove,
                        contentDescription = stringResource(Res.string.cd_focus_decrease),
                        enabled = !isFocusLocked,
                        onClick = { nudgeFocus(-nudgeStep) }
                    )
                    FocusNudgeButton(
                        icon = Icons.Default.Add,
                        contentDescription = stringResource(Res.string.cd_focus_increase),
                        enabled = !isFocusLocked,
                        onClick = { nudgeFocus(nudgeStep) }
                    )
                }
            )
            TuningSlider(
                value = viewModel.currentFocus,
                enabled = !isFocusLocked,
                onValueChange = { viewModel.setFocus(it) },
                valueRange = capabilities.focusRange
            )
        }

        if (showZoom) {
            val stepsCount =
                (round((effectiveMaxZoom - 1.0f) * 10f).toInt() - 1).coerceAtLeast(0)
            TuningControlHeader(
                text = stringResource(
                    Res.string.format_label_value_string,
                    stringResource(Res.string.label_zoom),
                    stringResource(
                        Res.string.format_zoom,
                        String.format("%.1f", viewModel.currentZoom)
                    )
                ),
                enabled = true,
                onInfoClick = { onShowInfo(TunerInfoTopic.ZOOM) }
            )
            TuningSlider(
                value = viewModel.currentZoom,
                onValueChange = {
                    val stepped = round(it * 10.0f) / 10.0f
                    viewModel.setZoom(stepped)
                },
                valueRange = 1.0f..effectiveMaxZoom,
                steps = stepsCount
            )
        }

        if (showBrightness) {
            TuningControlHeader(
                text = stringResource(
                    Res.string.format_label_value_string,
                    stringResource(Res.string.label_brightness),
                    viewModel.currentUvcBrightness.toString()
                ),
                enabled = true,
                onInfoClick = { onShowInfo(TunerInfoTopic.BRIGHTNESS) }
            )
            TuningSlider(
                value = viewModel.currentUvcBrightness.toFloat(),
                onValueChange = { viewModel.setUvcBrightness(it.toInt()) },
                valueRange = viewModel.uvcBrightnessMin.toFloat()..viewModel.uvcBrightnessMax.toFloat()
            )
        }

        if (hasOpticalControls) {
            if (isAutoMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HorizontalDivider(
                        modifier = Modifier.weight(1f),
                        color = extColors.dialogDivider
                    )
                    Text(
                        text = stringResource(Res.string.value_auto),
                        style = MaterialTheme.typography.labelSmall,
                        color = extColors.secondaryText,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    HorizontalDivider(
                        modifier = Modifier.weight(1f),
                        color = extColors.dialogDivider
                    )
                }
            } else {
                HorizontalDivider(
                    color = extColors.dialogDivider,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }

        // Tier 2: Solver & exposure controls (interactive in Manual/Demo; live read-only in Auto)
        TuningControlHeader(
            text = stringResource(
                Res.string.format_percent,
                stringResource(Res.string.label_star_sensitivity),
                viewModel.starSensitivity.toInt()
            ),
            enabled = !isAutoMode,
            onInfoClick = { onShowInfo(TunerInfoTopic.STAR_SENSITIVITY) }
        )
        TuningSlider(
            value = viewModel.starSensitivity,
            enabled = !isAutoMode,
            onValueChange = { viewModel.updateStarSensitivity(it) },
            valueRange = 1f..100f
        )

        if (!isDemo) {
            if (viewModel.engineMode != MainViewModel.EngineMode.UVC) {
                TuningControlHeader(
                    text = stringResource(
                        Res.string.format_label_value_string,
                        stringResource(Res.string.label_exposure),
                        stringResource(
                            Res.string.format_ms,
                            String.format("%.1f", viewModel.currentExposure / 1000f)
                        )
                    ),
                    enabled = !isAutoMode,
                    onInfoClick = { onShowInfo(TunerInfoTopic.EXPOSURE) }
                )
                TuningSlider(
                    value = viewModel.currentExposure,
                    enabled = !isAutoMode,
                    onValueChange = { viewModel.setExposure(it) },
                    valueRange = capabilities.exposureRange
                )
            } else {
                TuningControlHeader(
                    text = stringResource(
                        Res.string.format_label_value_string,
                        stringResource(Res.string.label_stacking_frames),
                        viewModel.uvcStacking.toString()
                    ),
                    enabled = !isAutoMode,
                    onInfoClick = { onShowInfo(TunerInfoTopic.STACKING) }
                )
                TuningSlider(
                    value = viewModel.uvcStacking.toFloat(),
                    enabled = !isAutoMode,
                    onValueChange = { viewModel.updateUvcStacking(it.toInt()) },
                    valueRange = 1f..10f,
                    steps = 8
                )
            }

            val gainMax = viewModel.maxAnalogGain.coerceAtLeast(viewModel.minAnalogGain + 1f)
            TuningControlHeader(
                text = stringResource(
                    Res.string.format_label_value_string,
                    gainLabel,
                    viewModel.currentGain.toInt().toString()
                ),
                enabled = !isAutoMode,
                onInfoClick = { onShowInfo(TunerInfoTopic.GAIN) }
            )
            TuningSlider(
                value = viewModel.currentGain,
                enabled = !isAutoMode,
                onValueChange = { viewModel.setGain(it) },
                valueRange = viewModel.minAnalogGain..gainMax
            )

            if (showNativeProcessing) {
                TuningModeSelector(
                    label = stringResource(id = Res.string.label_noise_reduction),
                    selectedIndex = viewModel.noiseReductionMode,
                    enabled = !isAutoMode,
                    onSelect = { viewModel.setNoiseReduction(it) },
                    onInfoClick = { onShowInfo(TunerInfoTopic.NOISE_REDUCTION) }
                )
                TuningModeSelector(
                    label = stringResource(id = Res.string.label_hot_pixel_mode),
                    selectedIndex = viewModel.hotPixelMode,
                    enabled = !isAutoMode,
                    onSelect = { viewModel.updateHotPixelMode(it) },
                    onInfoClick = { onShowInfo(TunerInfoTopic.HOT_PIXEL) },
                    isOptionSupported = { viewModel.supportedHotPixelModes.contains(it) }
                )
            }
        }
    }
}
