package com.oakamil.olive

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun TunerScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
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
                androidx.compose.foundation.Image(
                    bitmap = it,
                    contentDescription = "Live Preview",
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        )
                )
            }
        } ?: Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .padding(8.dp)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Text("Waiting for frame...", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Tuning Sliders
        TuningControls(viewModel)

        Spacer(modifier = Modifier.height(16.dp))

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
}

@Composable
fun TuningControls(viewModel: MainViewModel) {
    Column(modifier = Modifier.fillMaxWidth()) {
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
