// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlin.math.abs

@Composable
fun StartEngineDialog(
    viewModel: MainViewModel,
    settingsManager: SettingsManager,
    isCameraConnected: Boolean,
    activeCameraSelection: String,
    isStopping: Boolean,
    isStarting: Boolean,
    onCameraSelectionChanged: (String) -> Unit,
    onToggleEngine: () -> Unit,
    onDismiss: () -> Unit,
    onRefreshLocation: () -> Unit = {}
) {
    val capabilities = LocalPlatformCapabilities.current
    var cameraSelection by remember { mutableStateOf(activeCameraSelection) }
    var cameraSettingsMode by remember { mutableStateOf(settingsManager.cameraSettingsMode) }
    var uvcFps by remember { mutableIntStateOf(settingsManager.uvcFps) }
    var uvcWidth by remember { mutableIntStateOf(settingsManager.uvcWidth) }
    var uvcHeight by remember { mutableIntStateOf(settingsManager.uvcHeight) }
    var uvcFormat by remember { mutableStateOf(settingsManager.uvcFormat) }
    var sensorMode by remember { mutableStateOf(settingsManager.sensorMode) }
    var downsampling by remember { mutableIntStateOf(settingsManager.downsampling) }
    var internalCameraId by remember { mutableStateOf(settingsManager.internalCameraId) }
    var locationMode by remember { mutableStateOf(settingsManager.locationMode) }
    var explicitMotionSensorMode by remember {
        mutableStateOf(if (settingsManager.hasExplicitMotionSensorMode) settingsManager.motionSensorMode else null)
    }
    val motionSensorMode = explicitMotionSensorMode ?: if (cameraSelection == "internal") 3 else 0

    var activeInfoDialog by remember { mutableStateOf<String?>(null) }
    val supportsRawMap = remember { mutableMapOf<String, Boolean>() }

    val currentOnCameraSelectionChanged by rememberUpdatedState(onCameraSelectionChanged)
    LaunchedEffect(cameraSelection) {
        currentOnCameraSelectionChanged(cameraSelection)
    }

    // Update settings manager when local state changes
    LaunchedEffect(
        cameraSelection,
        cameraSettingsMode,
        uvcFps,
        uvcWidth,
        uvcHeight,
        uvcFormat,
        sensorMode,
        downsampling,
        internalCameraId
    ) {
        settingsManager.cameraSelection = cameraSelection
        settingsManager.cameraSettingsMode = cameraSettingsMode
        settingsManager.uvcFps = uvcFps
        settingsManager.uvcWidth = uvcWidth
        settingsManager.uvcHeight = uvcHeight
        settingsManager.uvcFormat = uvcFormat
        settingsManager.sensorMode = sensorMode
        settingsManager.downsampling = downsampling
        settingsManager.internalCameraId = internalCameraId
    }

    LaunchedEffect(viewModel.availableUvcFormats) {
        uvcFps = settingsManager.uvcFps
        uvcWidth = settingsManager.uvcWidth
        uvcHeight = settingsManager.uvcHeight
        uvcFormat = settingsManager.uvcFormat
    }

    LaunchedEffect(internalCameraId) {
        if (sensorMode == "Raw" && supportsRawMap.isNotEmpty()) {
            if (supportsRawMap[internalCameraId] != true) {
                sensorMode = "Processed"
            }
        }
    }

    LaunchedEffect(locationMode) {
        settingsManager.locationMode = locationMode
    }

    // React to external camera plugging in
    LaunchedEffect(activeCameraSelection) {
        if (cameraSelection != activeCameraSelection) {
            cameraSelection = activeCameraSelection
        }
    }

    InfoPopup(
        title = stringResource(Res.string.btn_start_engine),
        onDismiss = onDismiss,
        fillMaxWidth = true,
        bottomBar = {
            Button(
                onClick = {
                    onDismiss()
                    onToggleEngine()
                },
                enabled = !isStopping && !isStarting,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(
                    Icons.Outlined.PlayCircle,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(Res.string.btn_start_engine),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Camera Selection
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(id = Res.string.label_camera_selection),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                IconButton(
                    onClick = { activeInfoDialog = "camera_selection" },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = stringResource(id = Res.string.cd_info),
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
                    stringResource(id = Res.string.label_internal_camera),
                    modifier = Modifier.padding(start = 8.dp),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            if (capabilities.supportsUsbCamera) {
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
                        text = stringResource(id = Res.string.label_external_camera),
                        modifier = Modifier.padding(start = 8.dp),
                        color = if (isCameraConnected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onBackground.copy(
                            alpha = 0.38f
                        )
                    )
                }

                if (!isCameraConnected) {
                    Text(
                        text = stringResource(id = Res.string.label_no_external_camera),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 56.dp)
                    )
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
                    text = stringResource(id = Res.string.label_demo_camera),
                    modifier = Modifier.padding(start = 8.dp),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Camera Settings (Auto / Manual)
            if (cameraSelection == "internal" || cameraSelection == "external") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(id = Res.string.label_camera_settings),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    IconButton(
                        onClick = { activeInfoDialog = "camera_settings" },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = stringResource(id = Res.string.cd_info),
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
                        "Auto" to stringResource(id = Res.string.value_auto),
                        "Manual" to stringResource(id = Res.string.value_manual)
                    ).forEach { (value, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable { cameraSettingsMode = value }
                                .padding(end = 16.dp)
                        ) {
                            RadioButton(
                                selected = cameraSettingsMode == value,
                                onClick = { cameraSettingsMode = value },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = MaterialTheme.colorScheme.primary,
                                    unselectedColor = MaterialTheme.colorScheme.onBackground
                                )
                            )
                            Text(
                                label,
                                modifier = Modifier.padding(start = 4.dp),
                                color = MaterialTheme.colorScheme.onBackground
                            )

                            if (value == "Auto" && cameraSelection == "internal" && cameraSettingsMode == "Auto") {
                                var showResetCameraCalibrationDialog by remember {
                                    mutableStateOf(
                                        false
                                    )
                                }
                                val profileKey =
                                    if (capabilities.isApple) "night_profile_Apple AVFoundation Camera" else "night_profile_Android_Cam_$internalCameraId"
                                var hasCameraProfile by remember {
                                    mutableStateOf(settingsManager.getString(profileKey) != null)
                                }

                                LaunchedEffect(internalCameraId) {
                                    hasCameraProfile = settingsManager.getString(profileKey) != null
                                }

                                if (showResetCameraCalibrationDialog) {
                                    OliveAlertDialog(
                                        title = stringResource(Res.string.title_reset_calibration),
                                        text = stringResource(Res.string.inst_reset_camera_calibration),
                                        confirmButtonText = stringResource(Res.string.btn_confirm),
                                        onConfirm = {
                                            showResetCameraCalibrationDialog = false
                                            settingsManager.removeString(profileKey)
                                            hasCameraProfile = false
                                        },
                                        dismissButtonText = stringResource(id = Res.string.btn_cancel),
                                        onDismiss = {
                                            showResetCameraCalibrationDialog = false
                                        }
                                    )
                                }

                                Spacer(modifier = Modifier.width(16.dp))
                                IconBarItem(
                                    icon = Icons.Default.SettingsBackupRestore,
                                    label = stringResource(id = Res.string.btn_reset),
                                    onClick = { showResetCameraCalibrationDialog = true },
                                    enabled = hasCameraProfile
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // External Camera Format
            if (cameraSelection == "external") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(id = Res.string.label_camera_format),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    IconButton(
                        onClick = { activeInfoDialog = "camera_format" },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = stringResource(id = Res.string.cd_info),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
                ) {
                    var expandedFormat by remember { mutableStateOf(false) }
                    val currentFormatLabel = stringResource(Res.string.format_fps, uvcFps)
                    Box(
                        modifier = Modifier.border(
                            1.dp,
                            MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(4.dp)
                        )
                    ) {
                        TextButton(onClick = { expandedFormat = true }) {
                            Text(
                                currentFormatLabel,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        DropdownMenu(
                            expanded = expandedFormat,
                            onDismissRequest = { expandedFormat = false },
                            modifier = Modifier.border(
                                1.dp,
                                MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(4.dp)
                            )
                        ) {
                            val formatOptions = viewModel.availableUvcFormats
                            formatOptions.forEach { fmt ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(Res.string.format_fps, fmt.fps),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    onClick = {
                                        uvcWidth = fmt.width
                                        uvcHeight = fmt.height
                                        uvcFps = fmt.fps
                                        uvcFormat = fmt.format
                                        expandedFormat = false
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // Internal Camera Selector (Manual Mode)
            if (cameraSelection == "internal" && cameraSettingsMode == "Manual") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(id = Res.string.label_internal_camera_section),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    IconButton(
                        onClick = { activeInfoDialog = "internal_camera" },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = stringResource(id = Res.string.cd_info),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                val cameraFovFormat = stringResource(id = Res.string.format_camera_fov)
                val camerasList = remember(cameraFovFormat) {
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
                            val fovStr = String.format("%.1f", cam.second)
                            list.add(
                                Pair(
                                    cam.first,
                                    String.format(
                                        cameraFovFormat,
                                        cam.first,
                                        fovStr
                                    )
                                )
                            )
                        }
                    } catch (_: Exception) {
                        // Ignore JSON parsing errors if internal camera FOV metadata is unavailable
                    }
                    list
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
                ) {
                    var expanded by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier.border(
                            1.dp,
                            MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(4.dp)
                        )
                    ) {
                        TextButton(onClick = { expanded = true }) {
                            val currentText =
                                camerasList.find { it.first == internalCameraId }?.second
                                    ?: camerasList.firstOrNull()?.second
                                    ?: stringResource(Res.string.default_camera_label)
                            Text(currentText, color = MaterialTheme.colorScheme.primary)
                        }
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                            modifier = Modifier.border(
                                1.dp,
                                MaterialTheme.colorScheme.outline,
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
                Spacer(modifier = Modifier.height(24.dp))
            }

            // Manual Mode: Sensor Mode, Downsampling
            if (cameraSettingsMode == "Manual") {
                if (cameraSelection == "internal" && !capabilities.isApple) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(id = Res.string.label_sensor_mode),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        IconButton(
                            onClick = { activeInfoDialog = "sensor_mode" },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = stringResource(id = Res.string.cd_info),
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
                        listOf(
                            "Processed" to stringResource(id = Res.string.value_processed),
                            "Raw" to stringResource(id = Res.string.value_raw)
                        ).forEach { (mode, label) ->
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
                                label,
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
                            stringResource(id = Res.string.label_downsampling),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        IconButton(
                            onClick = { activeInfoDialog = "downsampling" },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = stringResource(id = Res.string.cd_info),
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
                            0 to stringResource(id = Res.string.value_off),
                            2 to stringResource(id = Res.string.value_2x2),
                            4 to stringResource(id = Res.string.value_4x4)
                        ).forEach { (value, label) ->
                            RadioButton(
                                selected = downsampling == value,
                                onClick = { downsampling = value },
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
            }

            // Location
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(id = Res.string.label_location),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                IconButton(
                    onClick = { activeInfoDialog = "location" },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = stringResource(id = Res.string.cd_info),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            val currentLat = viewModel.currentLat
            val currentLon = viewModel.currentLon
            val currentCoordText = if (currentLat != null && currentLon != null &&
                MainViewModel.isValidCoordinate(currentLat, currentLon)
            ) {
                val latDir =
                    stringResource(if (currentLat >= 0) Res.string.dir_north else Res.string.dir_south)
                val lonDir =
                    stringResource(if (currentLon >= 0) Res.string.dir_east else Res.string.dir_west)
                stringResource(
                    Res.string.label_current_location,
                    stringResource(
                        Res.string.format_coordinate,
                        abs(currentLat),
                        latDir,
                        abs(currentLon),
                        lonDir
                    )
                )
            } else {
                stringResource(id = Res.string.status_location_not_set)
            }

            Text(
                text = currentCoordText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                // Auto Option
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { locationMode = "Auto" }
                        .padding(end = 8.dp)
                ) {
                    RadioButton(
                        selected = locationMode == "Auto",
                        onClick = { locationMode = "Auto" },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = MaterialTheme.colorScheme.primary,
                            unselectedColor = MaterialTheme.colorScheme.onBackground
                        )
                    )
                    Text(
                        stringResource(id = Res.string.value_auto),
                        modifier = Modifier.padding(start = 4.dp),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                if (locationMode == "Auto") {
                    val isRefreshing =
                        viewModel.locationRefreshStatus == MainViewModel.LocationRefreshStatus.REFRESHING
                    val refreshIcon = when (viewModel.locationRefreshStatus) {
                        MainViewModel.LocationRefreshStatus.SUCCESS -> Icons.Default.Check
                        MainViewModel.LocationRefreshStatus.FAILURE -> Icons.Default.Close
                        else -> Icons.Default.Refresh
                    }
                    IconBarItem(
                        icon = refreshIcon,
                        label = stringResource(id = Res.string.btn_refresh),
                        isLoading = isRefreshing,
                        enabled = true,
                        onClick = {
                            if (viewModel.locationRefreshStatus == MainViewModel.LocationRefreshStatus.IDLE) {
                                onRefreshLocation()
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Manual Option
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { locationMode = "Manual" }
                        .padding(end = 8.dp)
                ) {
                    RadioButton(
                        selected = locationMode == "Manual",
                        onClick = { locationMode = "Manual" },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = MaterialTheme.colorScheme.primary,
                            unselectedColor = MaterialTheme.colorScheme.onBackground
                        )
                    )
                    Text(
                        stringResource(id = Res.string.value_manual),
                        modifier = Modifier.padding(start = 4.dp),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                if (locationMode == "Manual") {
                    IconBarItem(
                        icon = Icons.Default.Edit,
                        label = stringResource(id = Res.string.btn_edit_location),
                        onClick = { viewModel.showLocationDialog = true }
                    )
                }
            }

            // Motion Sensor (if !demo)
            if (cameraSelection != "demo") {
                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(id = Res.string.label_motion_sensor),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    IconButton(
                        onClick = { activeInfoDialog = "motion_sensor" },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = stringResource(id = Res.string.cd_info),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
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
                        0 to stringResource(id = Res.string.value_off),
                        3 to stringResource(id = Res.string.value_on)
                    ).forEach { (value, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable {
                                    @Suppress("AssignedValueIsNeverRead")
                                    explicitMotionSensorMode = value
                                    settingsManager.motionSensorMode = value
                                }
                                .padding(end = 16.dp)
                        ) {
                            RadioButton(
                                selected = motionSensorMode == value,
                                onClick = {
                                    @Suppress("AssignedValueIsNeverRead")
                                    explicitMotionSensorMode = value
                                    settingsManager.motionSensorMode = value
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = MaterialTheme.colorScheme.primary,
                                    unselectedColor = MaterialTheme.colorScheme.onBackground
                                )
                            )
                            Text(
                                label,
                                modifier = Modifier.padding(start = 4.dp),
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            if (value == 3 && motionSensorMode != 0) {
                                var hasCalibration by remember {
                                    mutableStateOf(
                                        settingsManager.getString("imu_calibration") != null
                                    )
                                }
                                var showResetCalibrationDialog by remember { mutableStateOf(false) }

                                if (showResetCalibrationDialog) {
                                    OliveAlertDialog(
                                        title = stringResource(Res.string.title_reset_calibration),
                                        text = stringResource(Res.string.inst_reset_calibration),
                                        confirmButtonText = stringResource(Res.string.btn_confirm),
                                        onConfirm = {
                                            showResetCalibrationDialog = false
                                            settingsManager.removeString("imu_calibration")
                                            hasCalibration = false
                                        },
                                        dismissButtonText = stringResource(id = Res.string.btn_cancel),
                                        onDismiss = {
                                            showResetCalibrationDialog = false
                                        }
                                    )
                                }

                                if (cameraSelection != "external") {
                                    Spacer(modifier = Modifier.width(16.dp))
                                    IconBarItem(
                                        icon = Icons.Default.SettingsBackupRestore,
                                        label = stringResource(id = Res.string.btn_reset),
                                        onClick = {
                                            showResetCalibrationDialog = true
                                        },
                                        enabled = hasCalibration
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Contextual Info Popups
    when (activeInfoDialog) {
        "camera_selection" -> InfoPopup(
            title = stringResource(id = Res.string.label_camera_selection),
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                activeInfoDialog = null
            }
        ) {
            Text(AnnotatedString.fromHtml(stringResource(id = Res.string.info_camera_selection)))
        }

        "camera_settings" -> InfoPopup(
            title = stringResource(id = Res.string.label_camera_settings),
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                activeInfoDialog = null
            }
        ) {
            Text(AnnotatedString.fromHtml(stringResource(id = Res.string.info_camera_settings)))
        }

        "camera_format" -> InfoPopup(
            title = stringResource(id = Res.string.label_camera_format),
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                activeInfoDialog = null
            }
        ) {
            Text(AnnotatedString.fromHtml(stringResource(id = Res.string.info_camera_format)))
        }

        "internal_camera" -> InfoPopup(
            title = stringResource(id = Res.string.label_internal_camera_section),
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                activeInfoDialog = null
            }
        ) {
            Text(AnnotatedString.fromHtml(stringResource(id = Res.string.info_internal_camera)))
        }

        "sensor_mode" -> InfoPopup(
            title = stringResource(id = Res.string.label_sensor_mode),
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                activeInfoDialog = null
            }
        ) {
            Text(AnnotatedString.fromHtml(stringResource(id = Res.string.info_sensor_mode)))
        }

        "downsampling" -> InfoPopup(
            title = stringResource(id = Res.string.label_downsampling),
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                activeInfoDialog = null
            }
        ) {
            Text(AnnotatedString.fromHtml(stringResource(id = Res.string.info_downsampling)))
        }

        "location" -> InfoPopup(
            title = stringResource(id = Res.string.label_location),
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                activeInfoDialog = null
            }
        ) {
            Text(AnnotatedString.fromHtml(stringResource(id = Res.string.info_location)))
        }

        "motion_sensor" -> InfoPopup(
            title = stringResource(id = Res.string.label_motion_sensor),
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                activeInfoDialog = null
            }
        ) {
            Text(stringResource(id = Res.string.info_motion_sensor))
        }
    }
}
