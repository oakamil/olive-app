package com.oakamil.olive

import android.content.Context
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.view.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun TunerScreen(
    viewModel: MainViewModel,
    settingsManager: SettingsManager,
    onBack: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val context = LocalContext.current
    val view = LocalView.current
    val displayRotation = view.display?.rotation ?: Surface.ROTATION_0

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
                Text("Back")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Live Preview with Pan/Zoom
        viewModel.latestFrame?.let {
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
                val cW = maxWidth.value
                val cH = maxHeight.value
                val bW = it.width.toFloat()
                val bH = it.height.toFloat()

                val fitScale = minOf(cW / bW, cH / bH)
                val drawnWidth = bW * fitScale
                val drawnHeight = bH * fitScale

                val baseScale = if (imageRotation % 180f != 0f) {
                    minOf(cW / drawnHeight, cH / drawnWidth)
                } else {
                    1f
                }

                androidx.compose.foundation.Image(
                    bitmap = it,
                    contentDescription = "Live Preview",
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale * baseScale,
                            scaleY = scale * baseScale,
                            translationX = offset.x,
                            translationY = offset.y,
                            rotationZ = imageRotation
                        )
                )
            }
        } ?: Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .padding(8.dp)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Text("Waiting for frame...", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    fun TuningContent() {
        TuningControls(viewModel, settingsManager)

        Spacer(modifier = Modifier.height(16.dp))

        if (viewModel.engineMode == MainViewModel.EngineMode.UVC) {
            Text(
                "Exposure: ${String.format(Locale.US, "%.2f", viewModel.engineStats.exposureMs)} s",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Text(
            "Detected Centroids: ${viewModel.engineStats.centroidCount}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            "HFR: ${String.format(Locale.US, "%.2f", viewModel.engineStats.hfr)}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
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
                        .verticalScroll(rememberScrollState())
                ) {
                    TuningContent()
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                CameraFeedContent()
                Spacer(modifier = Modifier.height(16.dp))
                TuningContent()
            }
        }
    }

}

@Composable
fun TuningControls(viewModel: MainViewModel, settingsManager: SettingsManager) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Star Sensitivity: ${viewModel.starSensitivity.toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Slider(
            value = viewModel.starSensitivity,
            onValueChange = {
                viewModel.updateStarSensitivity(it)
            },
            valueRange = 1f..100f,
            modifier = Modifier.fillMaxWidth()
        )

        val maxZoom = remember {
            var mZ = 1f
            try {
                if (viewModel.engineMode == MainViewModel.EngineMode.NATIVE && settingsManager.sensorMode == "Processed") {
                    val jsonArray = org.json.JSONArray(viewModel.getAvailableInternalCameras())
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        if (obj.getString("id") == settingsManager.internalCameraId) {
                            mZ =
                                if (obj.has("max_zoom")) obj.getDouble("max_zoom").toFloat() else 1f
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
            Text(
                "Zoom: ${String.format(Locale.US, "%.1fx", zoomRatio)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
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
            Text(
                "Exposure: ${viewModel.currentExposure.toInt()} us",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Slider(
                value = viewModel.currentExposure,
                onValueChange = {
                    viewModel.setExposure(it)
                },
                valueRange = 16667f..5000000f,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Text(
                "Stacking (Frames): ${viewModel.uvcStacking}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
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
            Text(
                "Brightness: ${viewModel.currentUvcBrightness}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Slider(
                value = viewModel.currentUvcBrightness.toFloat(),
                onValueChange = {
                    viewModel.setUvcBrightness(it.toInt())
                },
                valueRange = viewModel.uvcBrightnessMin.toFloat()..viewModel.uvcBrightnessMax.toFloat(),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Text(
            "Gain: ${viewModel.currentGain.toInt()}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Slider(
            value = viewModel.currentGain,
            onValueChange = {
                viewModel.setGain(it)
            },
            valueRange = viewModel.minAnalogGain..viewModel.maxAnalogGain.coerceAtLeast(viewModel.minAnalogGain + 1f),
            modifier = Modifier.fillMaxWidth()
        )

        if (viewModel.engineMode != MainViewModel.EngineMode.UVC) {
            Text(
                "Focus: ${String.format(Locale.US, "%.2f", viewModel.currentFocus)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Slider(
                value = viewModel.currentFocus,
                onValueChange = {
                    viewModel.setFocus(it)
                },
                valueRange = 0.0f..10.0f,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (viewModel.engineMode != MainViewModel.EngineMode.UVC) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Noise Reduction: ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                val modes = listOf("OFF", "FAST", "HQ")
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
            }
        }
    }
}

@Composable
fun StatsDisplay(stats: EngineStats) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Text("Engine Status: ${stats.status}", color = MaterialTheme.colorScheme.primary)
        Text(
            "Detected Stars: ${stats.centroidCount}",
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text("RA: ${stats.ra?.let { String.format(Locale.US, "%.4f", it) } ?: "-"}",
            color = MaterialTheme.colorScheme.onBackground)
        Text("Dec: ${stats.dec?.let { String.format(Locale.US, "%.4f", it) } ?: "-"}",
            color = MaterialTheme.colorScheme.onBackground)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Exposure: ${String.format(Locale.US, "%.2f", stats.exposureMs)} ms",
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "Post-Process: ${String.format(Locale.US, "%.1f", stats.postProcessMs)} ms",
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "Extraction: ${String.format(Locale.US, "%.1f", stats.extractMs)} ms",
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "Solve: ${String.format(Locale.US, "%.1f", stats.solveMs)} ms",
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "Solve Rate: ${String.format(Locale.US, "%.2f", stats.solveRateHz)} Hz",
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}
