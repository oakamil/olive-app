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
            .padding(16.dp),
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

        @Composable
        fun GuidanceContent() {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    GuidanceDisplay(viewModel.engineStats, settingsManager.guidanceMode == "alt_az")
                }
            }
        }

        @Composable
        fun OtherContent() {
            StatsDisplay(viewModel.engineStats)

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "IP Address: $detectedIpAddress Port: 4030",
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

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

                val maxZoom = remember {
                    var mZ = 1f
                    try {
                        if (activeCameraSelection == "internal" && settingsManager.sensorMode == "Processed") {
                            val jsonArray =
                                org.json.JSONArray(viewModel.getAvailableInternalCameras())
                            for (i in 0 until jsonArray.length()) {
                                val obj = jsonArray.getJSONObject(i)
                                if (obj.getString("id") == settingsManager.internalCameraId) {
                                    mZ = if (obj.has("max_zoom")) obj.getDouble("max_zoom")
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
                    Text(
                        "Zoom: ${String.format(java.util.Locale.US, "%.1fx", zoomRatio)}",
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

        }

        if (isLandscape) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    GuidanceContent()
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    OtherContent()
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                GuidanceContent()
                Spacer(modifier = Modifier.height(16.dp))
                OtherContent()
            }
        }
    }
}

@Composable
fun StatsDisplay(stats: EngineStats) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left Column: Status, Stars, RA, Dec
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start
        ) {
            Text("Status: ${stats.status}", color = MaterialTheme.colorScheme.primary)
            Text(
                "Star Count: ${stats.centroidCount}",
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("RA: ${stats.ra?.let { String.format(java.util.Locale.US, "%.4f", it) } ?: "-"}",
                color = MaterialTheme.colorScheme.onBackground)
            Text("Dec: ${stats.dec?.let { String.format(java.util.Locale.US, "%.4f", it) } ?: "-"}",
                color = MaterialTheme.colorScheme.onBackground)
        }

        // Right Column: Performance metrics
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                "Exposure: ${String.format(java.util.Locale.US, "%.2f", stats.exposureMs)} ms",
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "Post-Process: ${String.format(java.util.Locale.US, "%.1f", stats.postProcessMs)} ms",
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "Extraction: ${String.format(java.util.Locale.US, "%.1f", stats.extractMs)} ms",
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "Solve: ${String.format(java.util.Locale.US, "%.1f", stats.solveMs)} ms",
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "Solve Rate: ${String.format(java.util.Locale.US, "%.2f", stats.solveRateHz)} Hz",
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}
