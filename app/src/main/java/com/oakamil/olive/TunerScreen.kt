package com.oakamil.olive

import android.content.Context
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Canvas
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import java.util.Locale

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

    val imageRotation = ((sensorOrientation - displayRotationDegrees + 360) % 360).toFloat()

    @Composable
    fun CameraFeedContent() {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            Button(
                onClick = {
                    viewModel.setEnginePaused(false)
                    onBack()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(stringResource(id = R.string.btn_back))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Live Preview with Pan/Zoom
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .padding(8.dp)
                .background(MaterialTheme.colorScheme.surface)
                .clipToBounds()
                .transformable(
                    state = rememberTransformableState { zoomChange, panChange, _ ->
                        scale = (scale * zoomChange).coerceIn(1f, 10f)
                        val maxOffset =
                            (scale - 1) * 300f // Approximate bound, fine for preview
                        offset = Offset(
                            x = (offset.x + panChange.x * scale).coerceIn(
                                -maxOffset,
                                maxOffset
                            ),
                            y = (offset.y + panChange.y * scale).coerceIn(-maxOffset, maxOffset)
                        )
                    }
                )
        ) {
            val frame = viewModel.latestFrame
            if (frame != null) {
                val layoutW = constraints.maxWidth.toFloat()
                val layoutH = constraints.maxHeight.toFloat()
                val bW = frame.width.toFloat()
                val bH = frame.height.toFloat()
                val fitPxScale = minOf(layoutW / bW, layoutH / bH)

                val drawnWidth = bW * fitPxScale
                val drawnHeight = bH * fitPxScale
                val baseScale = if (imageRotation % 180f != 0f) {
                    minOf(layoutW / drawnHeight, layoutH / drawnWidth)
                } else {
                    1f
                }

                androidx.compose.foundation.Image(
                    bitmap = frame,
                    contentDescription = stringResource(id = R.string.cd_live_preview),
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { tapOffset ->
                                val cx = layoutW / 2f
                                val cy = layoutH / 2f
                                val x1 = tapOffset.x - cx
                                val y1 = tapOffset.y - cy
                                val x2 = x1 - offset.x
                                val y2 = y1 - offset.y
                                
                                val s = scale * baseScale
                                val x3 = x2 / s
                                val y3 = y2 / s
    
                                val rad = Math.toRadians(-imageRotation.toDouble())
                                val cosA = kotlin.math.cos(rad).toFloat()
                                val sinA = kotlin.math.sin(rad).toFloat()
                                val x4 = x3 * cosA - y3 * sinA
                                val y4 = x3 * sinA + y3 * cosA
    
                                alignTargetX = (x4 / fitPxScale) + bW / 2f
                                alignTargetY = (y4 / fitPxScale) + bH / 2f
                            }
                        }
                        .graphicsLayer(
                            scaleX = scale * baseScale,
                            scaleY = scale * baseScale,
                            translationX = offset.x,
                            translationY = offset.y,
                            rotationZ = imageRotation
                        )
                )
                
                if (alignTargetX != null && alignTargetY != null) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale * baseScale,
                                scaleY = scale * baseScale,
                                translationX = offset.x,
                                translationY = offset.y,
                                rotationZ = imageRotation
                            )
                    ) {
                        val fitPxScale = minOf(size.width / bW, size.height / bH)
                        val canvasX = center.x + (alignTargetX!! - bW / 2f) * fitPxScale
                        val canvasY = center.y + (alignTargetY!! - bH / 2f) * fitPxScale

                        val crosshairSize = 30f / (scale * baseScale)
                        val strokeW = 3f / (scale * baseScale)

                        drawCircle(
                            color = androidx.compose.ui.graphics.Color.Red,
                            radius = 20f / (scale * baseScale),
                            center = Offset(canvasX, canvasY),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW)
                        )
                        drawLine(
                            color = androidx.compose.ui.graphics.Color.Red,
                            start = Offset(canvasX - crosshairSize, canvasY),
                            end = Offset(canvasX + crosshairSize, canvasY),
                            strokeWidth = strokeW
                        )
                        drawLine(
                            color = androidx.compose.ui.graphics.Color.Red,
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

    @Composable
    fun TuningContent() {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Button(
                onClick = { showAlignDialog = true },
                enabled = alignTargetX != null && alignTargetY != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(stringResource(R.string.btn_align))
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Button(
                onClick = { showResetAlignDialog = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                )
            ) {
                Text(stringResource(R.string.btn_reset))
            }
        }
        
        TuningControls(viewModel, settingsManager, activeCameraSelection)

        Spacer(modifier = Modifier.height(16.dp))

        if (viewModel.engineMode == MainViewModel.EngineMode.UVC) {
            Text(
                stringResource(id = R.string.stat_exposure) + ": " + stringResource(
                    id = R.string.format_ms,
                    String.format(Locale.getDefault(), "%.2f", viewModel.engineStats.exposureMs)
                ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Text(
            stringResource(id = R.string.info_detected_stars) + ": ${viewModel.engineStats.centroidCount}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )

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
            IconButton(onClick = { showHfrInfo = true }, modifier = Modifier.size(32.dp)) {
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
                        OliveEngine.setAlignTargetPixel(alignTargetX!!.toDouble(), alignTargetY!!.toDouble())
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
                    val frame = viewModel.latestFrame
                    if (frame != null) {
                        val bW = frame.width.toDouble()
                        val bH = frame.height.toDouble()
                        OliveEngine.setAlignTargetPixel(bW / 2.0, bH / 2.0)
                    }
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
                        val jsonArray = org.json.JSONArray(viewModel.getAvailableInternalCameras())
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
                var zoomRatio by remember { mutableFloatStateOf(settingsManager.zoomRatio) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(id = R.string.label_zoom) + ": " + stringResource(
                            id = R.string.format_zoom,
                            String.format(Locale.getDefault(), "%.1f", zoomRatio)
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
                    value = zoomRatio,
                    onValueChange = {
                        zoomRatio = it
                        settingsManager.zoomRatio = it
                        OliveEngine.setNativeCameraZoomRatio(it)
                    },
                    valueRange = 1f..5f,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (viewModel.engineMode != MainViewModel.EngineMode.UVC) {
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
                Slider(
                    value = viewModel.currentExposure,
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
                            }
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
                            enabled = isSupported
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

