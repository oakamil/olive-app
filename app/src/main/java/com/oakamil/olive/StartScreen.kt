package com.oakamil.olive

import android.content.res.Configuration
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
fun StartScreen(
    viewModel: MainViewModel,
    settingsManager: SettingsManager,
    isCameraConnected: Boolean,
    activeCameraSelection: String,
    isStopping: Boolean,
    isStarting: Boolean,
    onCameraSelectionChanged: (String) -> Unit,
    onToggleEngine: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var cameraSelection by remember { mutableStateOf(activeCameraSelection) }
    var uvcFps by remember { mutableIntStateOf(settingsManager.uvcFps) }
    var extractor by remember { mutableStateOf(settingsManager.extractor) }
    var sensorMode by remember { mutableStateOf(settingsManager.sensorMode) }
    var internalCameraId by remember { mutableStateOf(settingsManager.internalCameraId) }
    var guidanceMode by remember { mutableStateOf(settingsManager.guidanceMode) }

    val supportsRawMap = remember { mutableMapOf<String, Boolean>() }

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
    LaunchedEffect(sensorMode) {
        settingsManager.sensorMode = sensorMode
    }
    LaunchedEffect(internalCameraId) {
        settingsManager.internalCameraId = internalCameraId
        if (sensorMode == "Raw" && supportsRawMap.isNotEmpty()) {
            if (supportsRawMap[internalCameraId] != true) {
                sensorMode = "Processed"
            }
        }
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

        // Configuration mode
        val cameraSelectionContent: @Composable () -> Unit = {
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
                    try {
                        val jsonArray = JSONArray(camerasJson)
                        val tempCameras = mutableListOf<Triple<String, Double, Boolean>>()
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            val id = obj.getString("id")
                            val fov = obj.getDouble("fov")
                            val supportsRaw =
                                if (obj.has("supports_raw")) obj.getBoolean("supports_raw") else false
                            if (fov >= 10.0) {
                                tempCameras.add(Triple(id, fov, supportsRaw))
                            }
                        }
                        tempCameras.sortBy { it.first.toIntOrNull() ?: Int.MAX_VALUE }
                        for (cam in tempCameras) {
                            supportsRawMap[cam.first] = cam.third
                            list.add(
                                Pair(
                                    cam.first,
                                    "Camera ${cam.first} (${
                                        String.format(
                                            java.util.Locale.US,
                                            "%.1f",
                                            cam.second
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
                                    ?: camerasList.firstOrNull()?.second ?: "Camera 0"
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

        }

        val otherSettingsContent: @Composable () -> Unit = {
            if (cameraSelection == "internal") {
                Text(
                    "Sensor Mode",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                val isRawSupported = supportsRawMap[internalCameraId] ?: false
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    listOf("Raw", "Processed").forEach { mode ->
                        val enabled = mode == "Processed" || isRawSupported
                        RadioButton(
                            selected = sensorMode == mode,
                            onClick = { sensorMode = mode },
                            enabled = enabled,
                            colors = RadioButtonDefaults.colors(
                                selectedColor = MaterialTheme.colorScheme.primary,
                                unselectedColor = MaterialTheme.colorScheme.onBackground
                            )
                        )
                        Text(
                            mode,
                            modifier = Modifier.padding(start = 8.dp, end = 16.dp),
                            color = if (enabled) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onBackground.copy(
                                alpha = 0.5f
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

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

        if (isLandscape) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) { cameraSelectionContent() }
                Column(modifier = Modifier.weight(1f)) { otherSettingsContent() }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                cameraSelectionContent()
                Spacer(modifier = Modifier.height(24.dp))
                otherSettingsContent()
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onToggleEngine,
            modifier = Modifier
                .padding(16.dp)
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
                    else -> "Start Engine"
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
