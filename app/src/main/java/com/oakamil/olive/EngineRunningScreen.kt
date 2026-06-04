package com.oakamil.olive

import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun EngineRunningScreen(
    viewModel: MainViewModel,
    settingsManager: SettingsManager,
    detectedIpAddress: String,
    activeCameraSelection: String,
    isStopping: Boolean,
    isStarting: Boolean,
    onNavigateToTuner: () -> Unit,
    onEnterPipMode: () -> Unit,
    onToggleEngine: () -> Unit
) {
    var mainScreenExposure by remember { mutableFloatStateOf(1.0f) }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

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
            Text(
                "Olive Finder",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth(if (isLandscape) 0.5f else 1f)
                .aspectRatio(16f / 9f)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                GuidanceDisplay(viewModel.engineStats, settingsManager.guidanceMode == "alt_az")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        StatsDisplay(viewModel.engineStats)

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "IP Address: $detectedIpAddress Port: 4030",
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.weight(1f))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
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

            if (activeCameraSelection == "internal") {
                LaunchedEffect(viewModel.isEngineRunning) {
                    // Wait a moment for the camera to actually start and the JNI reference to be set
                    kotlinx.coroutines.delay(1000)
                    val rawExp = OliveEngine.getNativeCameraExposure()
                    android.util.Log.d("OliveUI", "Initial exposure from native: $rawExp")
                    mainScreenExposure = (rawExp / 1000000f).coerceIn(0.2f, 5.0f)
                }

                Text(
                    "Exposure: ${
                        String.format(
                            java.util.Locale.US,
                            "%.1f",
                            mainScreenExposure
                        )
                    } s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Slider(
                    value = mainScreenExposure,
                    onValueChange = {
                        val stepped = kotlin.math.round(it * 10.0f) / 10.0f
                        mainScreenExposure = stepped
                        viewModel.setExposure(stepped * 1000000)
                    },
                    valueRange = 0.2f..3.0f,
                    steps = 27,
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (activeCameraSelection == "uvc" || viewModel.engineMode.name == "UVC") {
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
        }

        Button(
            onClick = {
                viewModel.setEnginePaused(true)
                val range = OliveEngine.getNativeCameraGainRange()
                if (range != null && range.size >= 2) {
                    viewModel.minAnalogGain = range[0]
                    viewModel.maxAnalogGain =
                        range[1].coerceAtLeast(viewModel.minAnalogGain + 1f)
                } else {
                    viewModel.minAnalogGain = 100f
                    viewModel.maxAnalogGain = 3200f
                }
                viewModel.currentGain = OliveEngine.getNativeCameraGain()
                    .coerceIn(viewModel.minAnalogGain, viewModel.maxAnalogGain)
                viewModel.currentExposure = OliveEngine.getNativeCameraExposure().toFloat()
                    .coerceIn(16667f, 5000000f)
                val bRange = OliveEngine.getNativeCameraBrightnessRange()
                if (bRange != null && bRange.size >= 3) {
                    viewModel.uvcBrightnessMin = bRange[0]
                    viewModel.uvcBrightnessMax = bRange[1]
                    viewModel.currentUvcBrightness = bRange[2]
                } else {
                    viewModel.uvcBrightnessMin = 0
                    viewModel.uvcBrightnessMax = 0
                }
                onNavigateToTuner()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text("Camera Mode")
        }

        OutlinedButton(
            onClick = onEnterPipMode,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text("Picture-in-Picture Mode")
        }

        Button(
            onClick = onToggleEngine,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth(),
            enabled = !isStopping && !isStarting,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text(
                when {
                    isStopping -> "Stopping..."
                    isStarting -> "Starting..."
                    else -> "Stop Engine"
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
