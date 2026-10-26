package com.oakamil.olive

import android.content.Context
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun TunerScreen(
    viewModel: MainViewModel,
    settingsManager: SettingsManager,
    activeCameraSelection: String,
    onBack: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    var alignTargetX by remember { mutableStateOf<Float?>(null) }
    var alignTargetY by remember { mutableStateOf<Float?>(null) }
    var showAlignDialog by remember { mutableStateOf(false) }
    var showResetAlignDialog by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
        }
    }

    BackHandler {
        viewModel.setEnginePaused(false)
        onBack()
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val context = LocalContext.current
    val view = LocalView.current
    val displayRotation = view.display?.rotation ?: Surface.ROTATION_0

    LaunchedEffect(Unit) {
        viewModel.fetchRealtimeSettings()
    }

    val sensorOrientation = remember(viewModel.engineMode, settingsManager.internalCameraId) {
        if (viewModel.engineMode == MainViewModel.EngineMode.NATIVE) {
            try {
                val cameraManager =
                    context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val characteristics =
                    cameraManager.getCameraCharacteristics(settingsManager.internalCameraId)
                characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            } catch (_: Exception) {
                90
            }
        } else {
            0
        }
    }

    val displayRotationDegrees = when (displayRotation) {
        Surface.ROTATION_0 -> 0
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    val imageRotation = if (viewModel.engineMode == MainViewModel.EngineMode.NATIVE) {
        ((sensorOrientation - displayRotationDegrees + 360) % 360).toFloat()
    } else {
        0f
    }

    @Composable
    fun CameraFeedContent() {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(
                onClick = {
                    viewModel.setEnginePaused(false)
                    onBack()
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(id = R.string.cd_close),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .padding(8.dp)
                .background(MaterialTheme.colorScheme.surface)
                .clipToBounds()
        ) {
            val frame = viewModel.latestFrame
            val layoutW = constraints.maxWidth.toFloat()
            val layoutH = constraints.maxHeight.toFloat()

            val maxOffsetX: Float
            val maxOffsetY: Float
            val baseScale: Float

            if (frame != null) {
                val bW = frame.width.toFloat()
                val bH = frame.height.toFloat()
                val fitPxScale = minOf(layoutW / bW, layoutH / bH)

                val drawnWidth = bW * fitPxScale
                val drawnHeight = bH * fitPxScale
                baseScale = if (imageRotation % 180f != 0f) {
                    minOf(layoutW / drawnHeight, layoutH / drawnWidth)
                } else {
                    1f
                }

                val visualWidth = if (imageRotation % 180f != 0f) drawnHeight else drawnWidth
                val visualHeight = if (imageRotation % 180f != 0f) drawnWidth else drawnHeight

                maxOffsetX = maxOf(0f, (visualWidth * scale * baseScale - layoutW) / 2f)
                maxOffsetY = maxOf(0f, (visualHeight * scale * baseScale - layoutH) / 2f)
            } else {
                maxOffsetX = 0f
                maxOffsetY = 0f
                baseScale = 1f
            }

            val isMirrored = viewModel.engineStats.isMirrored == true
            val isRotated90 = (imageRotation.toInt() % 180 != 0)
            val mirrorX = if (isMirrored && isRotated90) -1f else 1f
            val mirrorY = if (isMirrored && !isRotated90) -1f else 1f

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .transformable(
                        state = rememberTransformableState { zoomChange, panChange, _ ->
                            scale = (scale * zoomChange).coerceIn(1f, 10f)
                            offset = Offset(
                                x = (offset.x + panChange.x * scale).coerceIn(
                                    -maxOffsetX,
                                    maxOffsetX
                                ),
                                y = (offset.y + panChange.y * scale).coerceIn(
                                    -maxOffsetY,
                                    maxOffsetY
                                )
                            )
                        }
                    )
            ) {
                if (frame != null) {
                    val bW = frame.width.toFloat()
                    val bH = frame.height.toFloat()
                    val fitPxScale = minOf(layoutW / bW, layoutH / bH)

                    Image(
                        bitmap = frame,
                        contentDescription = stringResource(id = R.string.cd_live_preview),
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures { tapOffset ->
                                    val cx = layoutW / 2f
                                    val cy = layoutH / 2f

                                    // 1. Find tap offset from center of the screen
                                    val screenDx = tapOffset.x - cx
                                    val screenDy = tapOffset.y - cy

                                    // 2. Remove translation (pan)
                                    val translatedDx = screenDx - offset.x
                                    val translatedDy = screenDy - offset.y

                                    // 3. Remove rotation
                                    val angleRad = -Math.toRadians(imageRotation.toDouble())
                                    val rx =
                                        (translatedDx * cos(angleRad) - translatedDy * sin(
                                            angleRad
                                        )).toFloat()
                                    val ry =
                                        (translatedDx * sin(angleRad) + translatedDy * cos(
                                            angleRad
                                        )).toFloat()

                                    // 4. Remove scale (zoom and base fit)
                                    val totalScale = scale * baseScale
                                    val unscaledX = rx / (totalScale * mirrorX)
                                    val unscaledY = ry / (totalScale * mirrorY)

                                    // 5. Apply to bitmap coordinates
                                    alignTargetX = (unscaledX / fitPxScale) + bW / 2f
                                    alignTargetY = (unscaledY / fitPxScale) + bH / 2f
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

                    val currentBoresightX = (viewModel.engineStats.targetPixelX ?: (bW / 2.0)).toFloat()
                    val currentBoresightY = (viewModel.engineStats.targetPixelY ?: (bH / 2.0)).toFloat()

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
                        val fitPxScale = minOf(size.width / bW, size.height / bH)

                        // Draw current boresight
                        val boresightCanvasX = center.x + (currentBoresightX - bW / 2f) * fitPxScale
                        val boresightCanvasY = center.y + (currentBoresightY - bH / 2f) * fitPxScale

                        val s = 32f / (scale * baseScale)
                        val arm = 14f / (scale * baseScale)
                        val bracketStroke = 3f / (scale * baseScale)
                        val darkRed = Color(0xFFAA0000)

                        // ┌ Top-Left
                        drawLine(
                            darkRed,
                            Offset(boresightCanvasX - s, boresightCanvasY - s),
                            Offset(boresightCanvasX - s + arm, boresightCanvasY - s),
                            strokeWidth = bracketStroke,
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            darkRed,
                            Offset(boresightCanvasX - s, boresightCanvasY - s),
                            Offset(boresightCanvasX - s, boresightCanvasY - s + arm),
                            strokeWidth = bracketStroke,
                            cap = StrokeCap.Round
                        )
                        // ┐ Top-Right
                        drawLine(
                            darkRed,
                            Offset(boresightCanvasX + s, boresightCanvasY - s),
                            Offset(boresightCanvasX + s - arm, boresightCanvasY - s),
                            strokeWidth = bracketStroke,
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            darkRed,
                            Offset(boresightCanvasX + s, boresightCanvasY - s),
                            Offset(boresightCanvasX + s, boresightCanvasY - s + arm),
                            strokeWidth = bracketStroke,
                            cap = StrokeCap.Round
                        )
                        // └ Bottom-Left
                        drawLine(
                            darkRed,
                            Offset(boresightCanvasX - s, boresightCanvasY + s),
                            Offset(boresightCanvasX - s + arm, boresightCanvasY + s),
                            strokeWidth = bracketStroke,
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            darkRed,
                            Offset(boresightCanvasX - s, boresightCanvasY + s),
                            Offset(boresightCanvasX - s, boresightCanvasY + s - arm),
                            strokeWidth = bracketStroke,
                            cap = StrokeCap.Round
                        )
                        // ┘ Bottom-Right
                        drawLine(
                            darkRed,
                            Offset(boresightCanvasX + s, boresightCanvasY + s),
                            Offset(boresightCanvasX + s - arm, boresightCanvasY + s),
                            strokeWidth = bracketStroke,
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            darkRed,
                            Offset(boresightCanvasX + s, boresightCanvasY + s),
                            Offset(boresightCanvasX + s, boresightCanvasY + s - arm),
                            strokeWidth = bracketStroke,
                            cap = StrokeCap.Round
                        )

                        // Conditionally draw the preview target pixel when tapping
                        if (alignTargetX != null && alignTargetY != null) {
                            val canvasX = center.x + (alignTargetX!! - bW / 2f) * fitPxScale
                            val canvasY = center.y + (alignTargetY!! - bH / 2f) * fitPxScale
                            val crosshairSize = 40f / (scale * baseScale)
                            val strokeW = 3f / (scale * baseScale)

                            drawCircle(
                                color = Color.Red,
                                radius = 20f / (scale * baseScale),
                                center = Offset(canvasX, canvasY),
                                style = Stroke(width = strokeW)
                            )
                            drawLine(
                                color = Color.Red,
                                start = Offset(canvasX - crosshairSize, canvasY),
                                end = Offset(canvasX + crosshairSize, canvasY),
                                strokeWidth = strokeW
                            )
                            drawLine(
                                color = Color.Red,
                                start = Offset(canvasX, canvasY - crosshairSize),
                                end = Offset(canvasX, canvasY + crosshairSize),
                                strokeWidth = strokeW
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(id = R.string.status_waiting_for_frame),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun TuningContent() {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            IconBarItem(
                icon = Icons.Default.FilterCenterFocus,
                label = stringResource(R.string.btn_align),
                enabled = alignTargetX != null && alignTargetY != null,
                onClick = { showAlignDialog = true }
            )

            Spacer(modifier = Modifier.width(32.dp))

            IconBarItem(
                icon = Icons.Default.SettingsBackupRestore,
                label = stringResource(R.string.btn_reset),
                onClick = { showResetAlignDialog = true }
            )

        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(
                        stringResource(id = R.string.info_detected_stars) + ": ${viewModel.engineStats.centroidCount}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                var showHfrInfo by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(id = R.string.info_hfr_label) + ": " + String.format(
                            Locale.getDefault(),
                            "%.2f",
                            viewModel.engineStats.hfr
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    IconButton(
                        onClick = { showHfrInfo = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = stringResource(id = R.string.cd_info),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (showHfrInfo) {
                    InfoPopup(
                        title = stringResource(id = R.string.info_hfr_label),
                        onDismiss = { showHfrInfo = false }) {
                        Text(stringResource(id = R.string.info_hfr))
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        TuningControls(viewModel, settingsManager, activeCameraSelection)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isLandscape) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    CameraFeedContent()
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScrollWithScrollbar(rememberScrollState())
                ) {
                    TuningContent()
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                CameraFeedContent()
                Spacer(modifier = Modifier.height(16.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScrollWithScrollbar(rememberScrollState())
                ) {
                    TuningContent()
                }
            }
        }
    }

    if (showAlignDialog) {
        AlertDialog(
            onDismissRequest = { showAlignDialog = false },
            title = { Text(stringResource(R.string.title_confirm_alignment)) },
            text = { Text(stringResource(R.string.inst_confirm_alignment)) },
            confirmButton = {
                Button(onClick = {
                    showAlignDialog = false
                    if (alignTargetX != null && alignTargetY != null) {
                        viewModel.setAlignTargetPixel(
                            alignTargetX!!.toDouble(),
                            alignTargetY!!.toDouble()
                        )
                    }
                    alignTargetX = null
                    alignTargetY = null
                }) {
                    Text(stringResource(R.string.btn_confirm))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showAlignDialog = false }) {
                    Text(stringResource(id = R.string.btn_cancel))
                }
            }
        )
    }

    if (showResetAlignDialog) {
        AlertDialog(
            onDismissRequest = { showResetAlignDialog = false },
            title = { Text(stringResource(R.string.title_reset_alignment)) },
            text = { Text(stringResource(R.string.inst_reset_alignment)) },
            confirmButton = {
                Button(onClick = {
                    showResetAlignDialog = false
                    viewModel.clearAlignTargetPixel()
                    alignTargetX = null
                    alignTargetY = null
                }) {
                    Text(stringResource(R.string.btn_confirm))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showResetAlignDialog = false }) {
                    Text(stringResource(id = R.string.btn_cancel))
                }
            }
        )
    }
}

@Composable
fun TuningControls(
    viewModel: MainViewModel,
    settingsManager: SettingsManager,
    activeCameraSelection: String
) {
    var showStarSensitivityInfo by remember { mutableStateOf(false) }
    var showZoomInfo by remember { mutableStateOf(false) }
    var showExposureInfo by remember { mutableStateOf(false) }
    var showStackingInfo by remember { mutableStateOf(false) }
    var showBrightnessInfo by remember { mutableStateOf(false) }
    var showGainInfo by remember { mutableStateOf(false) }
    var showFocusInfo by remember { mutableStateOf(false) }
    var showNoiseReductionInfo by remember { mutableStateOf(false) }
    var showHotPixelModeInfo by remember { mutableStateOf(false) }

    val gainLabel = if (viewModel.engineMode == MainViewModel.EngineMode.NATIVE) "ISO" else "Gain"

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Star Sensitivity: ${viewModel.starSensitivity.toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            IconButton(
                onClick = { showStarSensitivityInfo = true },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = stringResource(id = R.string.cd_info),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Slider(
            value = viewModel.starSensitivity,
            enabled = settingsManager.cameraSettingsMode != "Auto",
            onValueChange = {
                viewModel.updateStarSensitivity(it)
            },
            valueRange = 1f..100f,
            modifier = Modifier.fillMaxWidth()
        )

        if (activeCameraSelection != "demo") {
            val maxZoom = remember {
                var mZ = 1f
                try {
                    if (viewModel.engineMode == MainViewModel.EngineMode.NATIVE && settingsManager.sensorMode == "Processed") {
                        val jsonArray = JSONArray(viewModel.getAvailableInternalCameras())
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            if (obj.getString("id") == settingsManager.internalCameraId) {
                                mZ =
                                    if (obj.has("max_zoom")) obj.getDouble("max_zoom")
                                        .toFloat() else 1f
                                break
                            }
                        }
                    }
                } catch (_: Exception) {
                }
                mZ
            }

            if (maxZoom > 1f) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(id = R.string.label_zoom) + ": " + stringResource(
                            id = R.string.format_zoom,
                            String.format(Locale.getDefault(), "%.1f", viewModel.currentZoom)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    IconButton(onClick = { showZoomInfo = true }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = stringResource(id = R.string.cd_info),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Slider(
                    value = viewModel.currentZoom,
                    enabled = settingsManager.cameraSettingsMode != "Auto",
                    onValueChange = {
                        viewModel.setZoom(it)
                    },
                    valueRange = 1f..5f,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(id = R.string.label_exposure) + ": " + stringResource(
                        id = R.string.format_ms,
                        String.format(
                            Locale.getDefault(),
                            "%.1f",
                            viewModel.currentExposure / 1000f
                        )
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                IconButton(
                    onClick = { showExposureInfo = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = stringResource(id = R.string.cd_info),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (viewModel.engineMode != MainViewModel.EngineMode.UVC) {
                Slider(
                    value = viewModel.currentExposure,
                    enabled = settingsManager.cameraSettingsMode != "Auto",
                    onValueChange = {
                        viewModel.setExposure(it)
                    },
                    valueRange = 16667f..5000000f,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(id = R.string.label_stacking_frames) + ": ${viewModel.uvcStacking}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    IconButton(
                        onClick = { showStackingInfo = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = stringResource(id = R.string.cd_info),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Slider(
                    value = viewModel.uvcStacking.toFloat(),
                    enabled = settingsManager.cameraSettingsMode != "Auto",
                    onValueChange = {
                        viewModel.updateUvcStacking(it.toInt())
                    },
                    valueRange = 1f..10f,
                    steps = 8,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (viewModel.engineMode == MainViewModel.EngineMode.UVC && viewModel.uvcBrightnessMax > viewModel.uvcBrightnessMin) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(id = R.string.label_brightness) + ": ${viewModel.currentUvcBrightness}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    IconButton(
                        onClick = { showBrightnessInfo = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = stringResource(id = R.string.cd_info),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Slider(
                    value = viewModel.currentUvcBrightness.toFloat(),
                    onValueChange = {
                        viewModel.setUvcBrightness(it.toInt())
                    },
                    valueRange = viewModel.uvcBrightnessMin.toFloat()..viewModel.uvcBrightnessMax.toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    gainLabel + ": ${viewModel.currentGain.toInt()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                IconButton(onClick = { showGainInfo = true }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = stringResource(id = R.string.cd_info),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Slider(
                value = viewModel.currentGain,
                enabled = settingsManager.cameraSettingsMode != "Auto",
                onValueChange = {
                    viewModel.setGain(it)
                },
                valueRange = viewModel.minAnalogGain..viewModel.maxAnalogGain.coerceAtLeast(
                    viewModel.minAnalogGain + 1f
                ),
                modifier = Modifier.fillMaxWidth()
            )

            if (viewModel.engineMode != MainViewModel.EngineMode.UVC) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(id = R.string.label_focus) + ": " + String.format(
                            Locale.getDefault(),
                            "%.2f",
                            viewModel.currentFocus
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    IconButton(
                        onClick = { showFocusInfo = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = stringResource(id = R.string.cd_info),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Slider(
                    value = viewModel.currentFocus,
                    onValueChange = {
                        viewModel.setFocus(it)
                    },
                    valueRange = 0.0f..10.0f,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (viewModel.engineMode == MainViewModel.EngineMode.NATIVE && settingsManager.sensorMode == "Processed") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(id = R.string.label_noise_reduction) + ": ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    val modes = listOf(
                        stringResource(id = R.string.value_off),
                        stringResource(id = R.string.value_fast),
                        stringResource(id = R.string.value_hq)
                    )
                    modes.forEachIndexed { index, name ->
                        RadioButton(
                            selected = viewModel.noiseReductionMode == index,
                            onClick = {
                                viewModel.setNoiseReduction(index)
                            },
                            enabled = settingsManager.cameraSettingsMode != "Auto"
                        )
                        Text(
                            name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    IconButton(
                        onClick = { showNoiseReductionInfo = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = stringResource(id = R.string.cd_info),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(id = R.string.label_hot_pixel_mode) + ": ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    val modes = listOf(
                        stringResource(id = R.string.value_off),
                        stringResource(id = R.string.value_fast),
                        stringResource(id = R.string.value_hq)
                    )
                    modes.forEachIndexed { index, name ->
                        val isSupported = viewModel.supportedHotPixelModes.contains(index)
                        RadioButton(
                            selected = viewModel.hotPixelMode == index,
                            onClick = {
                                viewModel.updateHotPixelMode(index)
                            },
                            enabled = isSupported && settingsManager.cameraSettingsMode != "Auto"
                        )
                        Text(
                            name,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSupported) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onBackground.copy(
                                alpha = 0.38f
                            ),
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    IconButton(
                        onClick = { showHotPixelModeInfo = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = stringResource(id = R.string.cd_info),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        if (showStarSensitivityInfo) {
            InfoPopup(
                title = stringResource(id = R.string.label_star_sensitivity),
                onDismiss = { showStarSensitivityInfo = false }) {
                Text(stringResource(id = R.string.info_star_sensitivity))
            }
        }
        if (showZoomInfo) {
            InfoPopup(
                title = stringResource(id = R.string.label_zoom),
                onDismiss = { showZoomInfo = false }) {
                Text(stringResource(id = R.string.info_zoom))
            }
        }
        if (showExposureInfo) {
            InfoPopup(
                title = stringResource(id = R.string.label_exposure),
                onDismiss = { showExposureInfo = false }) {
                Text(stringResource(id = R.string.info_exposure))
            }
        }
        if (showStackingInfo) {
            InfoPopup(
                title = stringResource(id = R.string.label_stacking_frames),
                onDismiss = { showStackingInfo = false }) {
                Text(stringResource(id = R.string.info_stacking))
            }
        }
        if (showBrightnessInfo) {
            InfoPopup(
                title = stringResource(id = R.string.label_brightness),
                onDismiss = { showBrightnessInfo = false }) {
                Text(stringResource(id = R.string.info_brightness))
            }
        }
        if (showGainInfo) {
            InfoPopup(title = gainLabel, onDismiss = { showGainInfo = false }) {
                if (gainLabel == "ISO") {
                    Text(AnnotatedString.fromHtml(stringResource(id = R.string.info_iso)))
                } else {
                    Text(stringResource(id = R.string.info_gain))
                }
            }
        }
        if (showFocusInfo) {
            InfoPopup(
                title = stringResource(id = R.string.label_focus),
                onDismiss = { showFocusInfo = false }) {
                Text(stringResource(id = R.string.info_focus))
            }
        }
        if (showNoiseReductionInfo) {
            InfoPopup(
                title = stringResource(id = R.string.label_noise_reduction),
                onDismiss = { showNoiseReductionInfo = false }) {
                Text(stringResource(id = R.string.info_noise_reduction))
            }
        }
        if (showHotPixelModeInfo) {
            InfoPopup(
                title = stringResource(id = R.string.label_hot_pixel_mode),
                onDismiss = { showHotPixelModeInfo = false }) {
                Text(stringResource(id = R.string.info_hot_pixel_mode))
            }
        }

    }
}

