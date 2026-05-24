package com.oakamil.olive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
fun MainScreen(
    viewModel: MainViewModel,
    settingsManager: SettingsManager,
    isCameraConnected: Boolean,
    activeCameraSelection: String,
    isStopping: Boolean,
    onNavigateToSettings: () -> Unit,
    onNavigateToTuner: () -> Unit,
    onEnterPipMode: () -> Unit,
    onToggleEngine: () -> Unit
) {
    var mainScreenExposure by remember { mutableFloatStateOf(1.0f) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (viewModel.isEngineRunning) {
                Button(
                    onClick = {
                        viewModel.setEnginePaused(true)
                        val range = OliveEngine.getNativeCameraGainRange()
                        if (range != null && range.size >= 2) {
                            viewModel.minAnalogGain = range[0]
                            viewModel.maxAnalogGain = range[1].coerceAtLeast(viewModel.minAnalogGain + 1f)
                        } else {
                            viewModel.minAnalogGain = 100f
                            viewModel.maxAnalogGain = 3200f
                        }
                        viewModel.currentGain = OliveEngine.getNativeCameraGain().coerceIn(viewModel.minAnalogGain, viewModel.maxAnalogGain)
                        viewModel.currentExposure = OliveEngine.getNativeCameraExposure().toFloat().coerceIn(16667f, 5000000f)
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
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Camera Mode")
                }
            } else {
                Spacer(modifier = Modifier.width(8.dp))
            }

            if (!viewModel.isEngineRunning) {
                Button(
                    onClick = onNavigateToSettings,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Settings")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (viewModel.isEngineRunning) {
            Card(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                Column(modifier = Modifier.padding(8.dp)) {
                    GuidanceDisplay(viewModel.engineStats, settingsManager.guidanceMode == "alt_az")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            StatsDisplay(viewModel.engineStats)
            Spacer(modifier = Modifier.weight(1f))
        } else {
            Spacer(modifier = Modifier.weight(1f))
            val currentModeStr = if (activeCameraSelection == "external" && isCameraConnected) "UVC Camera Selected" else "Internal Camera Selected"

            Text(
                text = currentModeStr,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 16.dp),
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        if (viewModel.isEngineRunning) {
            if (activeCameraSelection == "internal") {
                LaunchedEffect(viewModel.isEngineRunning) {
                    // Wait a moment for the camera to actually start and the JNI reference to be set
                    kotlinx.coroutines.delay(1000)
                    val rawExp = OliveEngine.getNativeCameraExposure()
                    android.util.Log.d("OliveUI", "Initial exposure from native: $rawExp")
                    mainScreenExposure = (rawExp / 1000000f).coerceIn(0.2f, 5.0f)
                }
                
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text("Exposure: ${String.format(java.util.Locale.US, "%.1f", mainScreenExposure)} s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onBackground)
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
                }
            }

            OutlinedButton(
                onClick = onEnterPipMode,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Picture-in-Picture Mode")
            }
        }

        Button(
            onClick = onToggleEngine,
            modifier = Modifier.padding(16.dp),
            enabled = !isStopping,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text(
                when {
                    isStopping -> "Stopping..."
                    viewModel.isEngineRunning -> "Stop Engine"
                    else -> "Start Engine"
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
