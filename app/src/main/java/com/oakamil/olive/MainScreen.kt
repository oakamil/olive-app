package com.oakamil.olive

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.json.JSONArray

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    settingsManager: SettingsManager,
    isCameraConnected: Boolean,
    activeCameraSelection: String,
    isStopping: Boolean,
    isStarting: Boolean,
    onCameraSelectionChanged: (String) -> Unit,
    onNavigateToTuner: () -> Unit,
    onEnterPipMode: () -> Unit,
    onToggleEngine: () -> Unit
) {
    var mainScreenExposure by remember { mutableFloatStateOf(1.0f) }

    var cameraSelection by remember { mutableStateOf(activeCameraSelection) }
    var uvcFps by remember { mutableIntStateOf(settingsManager.uvcFps) }
    var extractor by remember { mutableStateOf(settingsManager.extractor) }
    var internalCameraId by remember { mutableStateOf(settingsManager.internalCameraId) }
    var guidanceMode by remember { mutableStateOf(settingsManager.guidanceMode) }

    val currentOnCameraSelectionChanged by rememberUpdatedState(onCameraSelectionChanged)
    LaunchedEffect(cameraSelection) {
        currentOnCameraSelectionChanged(cameraSelection)
    }
    LaunchedEffect(uvcFps) {
        settingsManager.uvcFps = uvcFps
    }
    LaunchedEffect(extractor) {
        settingsManager.extractor = extractor
    }
    LaunchedEffect(internalCameraId) {
        settingsManager.internalCameraId = internalCameraId
    }
    LaunchedEffect(guidanceMode) {
        settingsManager.guidanceMode = guidanceMode
    }

    // React to external camera plugging in
    LaunchedEffect(activeCameraSelection) {
        if (cameraSelection != activeCameraSelection) {
            cameraSelection = activeCameraSelection
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (viewModel.isEngineRunning) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
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
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Camera Mode")
                }
            }
        } else {
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
            Spacer(modifier = Modifier.height(32.dp))
        }

        if (viewModel.isEngineRunning) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    GuidanceDisplay(viewModel.engineStats, settingsManager.guidanceMode == "alt_az")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            StatsDisplay(viewModel.engineStats)
            Spacer(modifier = Modifier.weight(1f))

            if (activeCameraSelection == "internal") {
                LaunchedEffect(viewModel.isEngineRunning) {
                    // Wait a moment for the camera to actually start and the JNI reference to be set
                    kotlinx.coroutines.delay(1000)
                    val rawExp = OliveEngine.getNativeCameraExposure()
                    android.util.Log.d("OliveUI", "Initial exposure from native: $rawExp")
                    mainScreenExposure = (rawExp / 1000000f).coerceIn(0.2f, 5.0f)
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
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
                }
            }

            OutlinedButton(
                onClick = onEnterPipMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Picture-in-Picture Mode")
            }
        } else {
            // Configuration mode
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Camera Selection",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { cameraSelection = "internal" }
                        .padding(vertical = 8.dp)
                ) {
                    RadioButton(
                        selected = cameraSelection == "internal",
                        onClick = { cameraSelection = "internal" },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = MaterialTheme.colorScheme.primary,
                            unselectedColor = MaterialTheme.colorScheme.onBackground
                        )
                    )
                    Text(
                        "Internal Camera",
                        modifier = Modifier.padding(start = 8.dp),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                if (cameraSelection == "internal") {
                    val camerasList = remember {
                        val camerasJson = viewModel.getAvailableInternalCameras()
                        val list = mutableListOf<Pair<String, String>>()
                        list.add(Pair("auto", "Auto-Select"))
                        try {
                            val jsonArray = JSONArray(camerasJson)
                            for (i in 0 until jsonArray.length()) {
                                val obj = jsonArray.getJSONObject(i)
                                val id = obj.getString("id")
                                val fov = obj.getDouble("fov")
                                list.add(
                                    Pair(
                                        id,
                                        "Camera $id (${
                                            String.format(
                                                java.util.Locale.US,
                                                "%.1f",
                                                fov
                                            )
                                        }° FOV)"
                                    )
                                )
                            }
                        } catch (_: Exception) {
                        }
                        list
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 40.dp)
                    ) {
                        Text("Camera: ", color = MaterialTheme.colorScheme.onBackground)
                        var expanded by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier.border(
                                1.dp,
                                Color.Red,
                                shape = RoundedCornerShape(4.dp)
                            )
                        ) {
                            TextButton(onClick = { expanded = true }) {
                                val currentText =
                                    camerasList.find { it.first == internalCameraId }?.second
                                        ?: "Auto-Select"
                                Text(currentText, color = MaterialTheme.colorScheme.primary)
                            }
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false },
                                modifier = Modifier.border(
                                    1.dp,
                                    Color.Red,
                                    shape = RoundedCornerShape(4.dp)
                                )
                            ) {
                                camerasList.forEach { (id, label) ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                label,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        },
                                        onClick = {
                                            internalCameraId = id
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = isCameraConnected) {
                            if (isCameraConnected) cameraSelection = "external"
                        }
                        .padding(vertical = 8.dp)
                ) {
                    RadioButton(
                        selected = cameraSelection == "external",
                        onClick = { if (isCameraConnected) cameraSelection = "external" },
                        enabled = isCameraConnected,
                        colors = RadioButtonDefaults.colors(
                            selectedColor = MaterialTheme.colorScheme.primary,
                            unselectedColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            disabledSelectedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
                            disabledUnselectedColor = MaterialTheme.colorScheme.onBackground.copy(
                                alpha = 0.38f
                            )
                        )
                    )
                    Text(
                        text = "External (USB/UVC) Camera",
                        modifier = Modifier.padding(start = 8.dp),
                        color = if (isCameraConnected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onBackground.copy(
                            alpha = 0.38f
                        )
                    )
                }

                if (cameraSelection == "external") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 40.dp)
                    ) {
                        Text("FPS: ", color = MaterialTheme.colorScheme.onBackground)
                        var expandedFps by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier.border(
                                1.dp,
                                Color.Red,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                            )
                        ) {
                            TextButton(onClick = { expandedFps = true }) {
                                Text("$uvcFps", color = MaterialTheme.colorScheme.primary)
                            }
                            DropdownMenu(
                                expanded = expandedFps,
                                onDismissRequest = { expandedFps = false },
                                modifier = Modifier.border(
                                    1.dp,
                                    Color.Red,
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                                )
                            ) {
                                listOf(5, 10, 15, 20, 30).forEach { fps ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "$fps",
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        },
                                        onClick = {
                                            uvcFps = fps
                                            expandedFps = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                if (!isCameraConnected) {
                    Text(
                        text = "No external camera detected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 48.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    "Extractor",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    listOf("Olive", "Cedar").forEach { ext ->
                        RadioButton(
                            selected = extractor == ext,
                            onClick = { extractor = ext },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = MaterialTheme.colorScheme.primary,
                                unselectedColor = MaterialTheme.colorScheme.onBackground
                            )
                        )
                        Text(
                            ext,
                            modifier = Modifier.padding(start = 8.dp, end = 16.dp),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    "Guidance Mode",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    listOf(
                        "alt_az" to "Alt-Az",
                        "equatorial" to "Equatorial"
                    ).forEach { (value, label) ->
                        RadioButton(
                            selected = guidanceMode == value,
                            onClick = { guidanceMode = value },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = MaterialTheme.colorScheme.primary,
                                unselectedColor = MaterialTheme.colorScheme.onBackground
                            )
                        )
                        Text(
                            label,
                            modifier = Modifier.padding(start = 8.dp, end = 16.dp),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))
        }

        Button(
            onClick = onToggleEngine,
            modifier = Modifier.padding(16.dp),
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
                    viewModel.isEngineRunning -> "Stop Engine"
                    else -> "Start Engine"
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
