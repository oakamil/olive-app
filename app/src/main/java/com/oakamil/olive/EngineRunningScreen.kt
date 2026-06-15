package com.oakamil.olive

import android.content.res.Configuration
import android.util.Log
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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

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
    var isEnteringPip by remember { mutableStateOf(false) }
    var showTelescopeControlInfo by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    BackHandler(enabled = !isStopping && !isStarting) {
        onToggleEngine()
    }

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
        fun StatsContent() {
            StatsDisplay(viewModel.engineStats)

            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "IP Address: $detectedIpAddress",
                    color = MaterialTheme.colorScheme.onBackground
                )
                IconButton(
                    onClick = { showTelescopeControlInfo = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = "Info",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        @Composable
        fun SlidersContent() {
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

                if (activeCameraSelection == "internal") {
                    LaunchedEffect(viewModel.isEngineRunning) {
                        // Wait a moment for the camera to actually start and the JNI reference to be set
                        delay(1000)
                        val rawExp = OliveEngine.getNativeCameraExposure()
                        Log.d("OliveUI", "Initial exposure from native: $rawExp")
                        mainScreenExposure = (rawExp / 1000000f).coerceIn(0.2f, 5.0f)
                    }

                    Text(
                        "Exposure: ${
                            String.format(
                                Locale.US,
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
        }

        @Composable
        fun ActionButtons() {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
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
                    onClick = {
                        isEnteringPip = true
                        onEnterPipMode()
                        coroutineScope.launch {
                            delay(1000)
                            isEnteringPip = false
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    enabled = !isEnteringPip
                ) {
                    Text(if (isEnteringPip) "Entering PIP..." else "Picture-in-Picture Mode")
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
                    modifier = Modifier.weight(1f)
                ) {
                    BoxWithConstraints(
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .defaultMinSize(minHeight = maxHeight),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                StatsContent()
                            }
                            Column {
                                Spacer(modifier = Modifier.height(24.dp))
                                SlidersContent()
                            }
                        }
                    }
                    ActionButtons()
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                BoxWithConstraints(
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .defaultMinSize(minHeight = maxHeight),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            GuidanceContent()
                            Spacer(modifier = Modifier.height(16.dp))
                            StatsContent()
                        }
                        Column {
                            Spacer(modifier = Modifier.height(24.dp))
                            SlidersContent()
                        }
                    }
                }
                ActionButtons()
            }
        }

        if (showTelescopeControlInfo) {
            InfoPopup(
                title = "Telescope Control",
                onDismiss = { showTelescopeControlInfo = false }) {
                Text("Olive Finder runs control servers for external connections:\n- Meade LX200 protocol on port 4030\n- ASCOM Alpaca protocol on port 11111\n- ASCOM Alpaca discovery server\n")

                Text(
                    "SkySafari Integration",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "Configuring SkySafari 7/8 Plus or Pro (Alpaca)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(buildAnnotatedString {
                    append("Menu > Settings > Telescope section - Presets\nAdd Preset > ASCOM Alpaca\nManual Configuration:\n- IP Address - 127.0.0.1 if using the same phone as Olive Finder, otherwise the IP address displayed in Olive Finder's screen\n- Port - 11111\nCheck IP and Port for Devices\nNext\n- Preset Name: Olive\n- Set Time & Location: leave this checked only if Olive is on a separate device\n- Readout Rate: 10 per second ")
                    withStyle(style = SpanStyle(fontStyle = FontStyle.Italic)) { append("(Important: swipe back instead of pressing the X)") }
                    append("\nSave Preset\nSelect the new preset > Use This Preset\n")
                })

                Text(
                    "Configuring SkySafari 7/8 Plus or Pro (LX200)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(buildAnnotatedString {
                    append("Menu > Settings > Telescope section - Presets\nAdd Preset > Other Connection > Meade LX200 Classic, Next > ")
                    withStyle(style = SpanStyle(fontStyle = FontStyle.Italic)) { append("Alt-Az. GoTo (Recommended)") }
                    append(", Next\nConnect via WiFi\n- IP Address - localhost if using the same phone as Olive Finder, otherwise the IP address displayed in Olive Finder's screen\n- Port - 4030\nCheck Connection\nNext\n- Preset Name: Olive\n- Set Time & Location: leave this checked only if Olive is on a separate device\n- Readout Rate: 10 per second ")
                    withStyle(style = SpanStyle(fontStyle = FontStyle.Italic)) { append("(Important: swipe back instead of pressing the X)") }
                    append("\nSave Preset\nSelect the new preset > Use This Preset\n")
                })

                Text(
                    "Configuring SkySafari 6 Plus or Pro (LX200)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(buildAnnotatedString {
                    append("Settings > Telescope section - Setup\n- Scope Type -- Meade LX200 Classic\n- Mount Type -- ")
                    withStyle(style = SpanStyle(fontStyle = FontStyle.Italic)) { append("Alt-Az. GoTo (Recommended)") }
                    append("\n- Connect via WiFi\n- IP Address - localhost if using the same phone as Olive Finder, otherwise the IP address displayed in Olive Finder's screen\n- Port - 4030\n- Set Time & Location: leave this checked only if Olive is on a separate device\n- Readout Rate -- 10 per second ")
                    withStyle(style = SpanStyle(fontStyle = FontStyle.Italic)) { append("(Important: swipe back instead of pressing the X)") }
                    append("\nPress X\n")
                })

                Text(
                    "SkySafari Usage",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text("Scope > Connect\nSelect an Object\n- GoTo -> Olive will show guidance to the object\n- Stop -> Stop the current GoTo target\n- Align -> Center object in eyepiece, tap Align to update Olive's alignment*\n")

                Text(
                    "Stellarium Integration",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "Configuring Stellarium+ (LX200)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(buildAnnotatedString {
                    append("Menu > Observing Tools > Telescope Control ")
                    withStyle(style = SpanStyle(fontStyle = FontStyle.Italic)) { append("(toggle on)") }
                    append("\nType - Network\nHost - localhost if using the same phone as Olive Finder, otherwise the IP address displayed in Olive Finder's screen\nToggle Link to on\nTime and Location sync - Turn off if using Olive on the same device\n")
                })

                Text(
                    "Stellarium Usage",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(buildAnnotatedString {
                    append("Menu > Observing Tools > Telescope Control ")
                    withStyle(style = SpanStyle(fontStyle = FontStyle.Italic)) { append("(toggle on)") }
                    append("\nToggle Link to on\nSelect an Object\n- GoTo -> Olive will show guidance to the object\n- Sync -> Center object in eyepiece, tap Sync to update Olive's alignment*\n")
                })

                Text(
                    "* Olive uses a single alignment point to determine the offset of the camera's view to the telescope's view. Alignment only needs to be done once each time the camera is mounted on a telescope.",
                    style = MaterialTheme.typography.bodySmall
                )
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
            Text("RA: ${stats.ra?.let { String.format(Locale.US, "%.4f", it) } ?: "-"}",
                color = MaterialTheme.colorScheme.onBackground)
            Text("Dec: ${stats.dec?.let { String.format(Locale.US, "%.4f", it) } ?: "-"}",
                color = MaterialTheme.colorScheme.onBackground)
            Text(
                "FOV: ${
                    stats.fov?.let {
                        String.format(
                            Locale.US,
                            "%.2f°",
                            it
                        )
                    } ?: "-"
                }",
                color = MaterialTheme.colorScheme.onBackground)
        }

        // Right Column: Performance metrics
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                "Exposure: ${String.format(Locale.US, "%.2f", stats.exposureMs)} ms",
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "Post-Process: ${
                    String.format(
                        Locale.US,
                        "%.1f",
                        stats.postProcessMs
                    )
                } ms",
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
}
