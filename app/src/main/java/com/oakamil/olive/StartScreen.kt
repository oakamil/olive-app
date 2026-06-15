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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.size
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
    var imuIntegration by remember { mutableStateOf(settingsManager.imuIntegration) }

    var showCameraSelectionInfo by remember { mutableStateOf(false) }
    var showSensorModeInfo by remember { mutableStateOf(false) }
    var showDownsamplingInfo by remember { mutableStateOf(false) }
    var showExtractorInfo by remember { mutableStateOf(false) }
    var showGuidanceModeInfo by remember { mutableStateOf(false) }

    val supportsRawMap = remember { mutableMapOf<String, Boolean>() }

    val currentOnCameraSelectionChanged by rememberUpdatedState(onCameraSelectionChanged)
    LaunchedEffect(cameraSelection) {
        currentOnCameraSelectionChanged(cameraSelection)
    }

    // Update settings manager when local state changes
    LaunchedEffect(
        cameraSelection,
        uvcFps,
        extractor,
        sensorMode,
        internalCameraId,
        guidanceMode,
        imuIntegration
    ) {
        settingsManager.cameraSelection = cameraSelection
        settingsManager.uvcFps = uvcFps
        settingsManager.extractor = extractor
        settingsManager.sensorMode = sensorMode
        settingsManager.internalCameraId = internalCameraId
        settingsManager.guidanceMode = guidanceMode
        settingsManager.imuIntegration = imuIntegration
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Camera Selection",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                IconButton(
                    onClick = { showCameraSelectionInfo = true },
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
                        .padding(start = 56.dp)
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

            if (!isCameraConnected) {
                Text(
                    text = "No external camera detected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 56.dp)
                )
            }

            if (cameraSelection == "external") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 56.dp)
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

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { cameraSelection = "demo" }
                    .padding(vertical = 8.dp)
            ) {
                RadioButton(
                    selected = cameraSelection == "demo",
                    onClick = { cameraSelection = "demo" },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = MaterialTheme.colorScheme.primary,
                        unselectedColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                )
                Text(
                    text = "Demo Camera",
                    modifier = Modifier.padding(start = 8.dp),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }


        }

        val otherSettingsContent: @Composable () -> Unit = {
            if (cameraSelection == "internal") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Sensor Mode",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    IconButton(
                        onClick = { showSensorModeInfo = true },
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
                val isRawSupported = supportsRawMap[internalCameraId] ?: false
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    listOf("Processed", "Raw").forEach { mode ->
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

            if (cameraSelection != "demo") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Downsampling",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    IconButton(
                        onClick = { showDownsamplingInfo = true },
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
                var downsampling by remember { mutableIntStateOf(settingsManager.downsampling) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    listOf(
                        0 to "Off",
                        2 to "2x2",
                        4 to "4x4"
                    ).forEach { (value, label) ->
                        RadioButton(
                            selected = downsampling == value,
                            onClick = {
                                downsampling = value
                                settingsManager.downsampling = value
                            },
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
                Spacer(modifier = Modifier.height(24.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Extractor",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                IconButton(
                    onClick = { showExtractorInfo = true },
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

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Guidance Mode",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                IconButton(
                    onClick = { showGuidanceModeInfo = true },
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

            /*Spacer(modifier = Modifier.height(24.dp))

            Text(
                "Motion Sensor",
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
                    false to "Off",
                    true to "On"
                ).forEach { (value, label) ->
                    RadioButton(
                        selected = imuIntegration == value,
                        onClick = { imuIntegration = value },
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

            if (imuIntegration) {
                var hasCalibration by remember { mutableStateOf(settingsManager.getString("imu_calibration.txt") != null) }
                Button(
                    onClick = {
                        settingsManager.removeString("imu_calibration.txt")
                        hasCalibration = false
                    },
                    enabled = hasCalibration,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text("Reset Calibration")
                }
            }*/
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

        if (showCameraSelectionInfo) {
            InfoPopup(title = "Camera Selection", onDismiss = { showCameraSelectionInfo = false }) {
                Text(
                    "Internal Camera",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text("Use the device's native camera for imaging the sky. The default phone camera is recommended, but all available cameras are shown in the dropdown.\n")
                Text(
                    "External Camera",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text("Use a camera plugged into the device's USB port. A dropdown is shown to target a specific frame rate for the video feed from the camera. The engine will try to select a feed that matches the given frame rate if available. A lower frame rate results in longer camera exposures.\n")
                Text(
                    "Demo Camera",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text("Cycles through canned images for demo purposes.")
            }
        }
        if (showSensorModeInfo) {
            InfoPopup(title = "Sensor Mode", onDismiss = { showSensorModeInfo = false }) {
                Text("Processed mode (recommended) uses the camera's own algorithms to pre-process the image. Raw mode relies on the engine to do the processing. Raw is only available on select phone models.")
            }
        }
        if (showDownsamplingInfo) {
            InfoPopup(title = "Downsampling", onDismiss = { showDownsamplingInfo = false }) {
                Text("Based on this setting the image will be reduced in size prior to running the plate-solving algorithms. 2x2 is recommended.")
            }
        }
        if (showExtractorInfo) {
            InfoPopup(title = "Extractor", onDismiss = { showExtractorInfo = false }) {
                Text("Determines which algorithm to use for star detection. Cedar (cedar-detect) is recommended for raw mode.")
            }
        }
        if (showGuidanceModeInfo) {
            InfoPopup(title = "Guidance Mode", onDismiss = { showGuidanceModeInfo = false }) {
                Text("The engine's guidance display can show Alt-Az offsets (distance to target) or equatorial RA/Dec offsets.")
            }
        }
    }
}

@Composable
fun InfoPopup(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth()
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    RoundedCornerShape(16.dp)
                )
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    content()
                }
            }
        }
    }
}
