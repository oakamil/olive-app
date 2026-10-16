package com.oakamil.olive

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONArray
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

@Composable
fun StartScreen(
    viewModel: MainViewModel,
    settingsManager: SettingsManager,
    isCameraConnected: Boolean,
    activeCameraSelection: String,
    isStopping: Boolean,
    isStarting: Boolean,
    onCameraSelectionChanged: (String) -> Unit,
    onToggleEngine: () -> Unit,
    onRefreshLocation: () -> Unit = {}
) {
    var cameraSelection by remember { mutableStateOf(activeCameraSelection) }
    var cameraSettingsMode by remember { mutableStateOf(settingsManager.cameraSettingsMode) }
    var internalFps by remember { mutableIntStateOf(settingsManager.internalFps) }
    var uvcFps by remember { mutableIntStateOf(settingsManager.uvcFps) }
    var extractor by remember { mutableStateOf(settingsManager.extractor) }
    var sensorMode by remember { mutableStateOf(settingsManager.sensorMode) }
    var downsampling by remember { mutableIntStateOf(settingsManager.downsampling) }
    var internalCameraId by remember { mutableStateOf(settingsManager.internalCameraId) }
    var guidanceMode by remember { mutableStateOf(settingsManager.guidanceMode) }
    var locationMode by remember { mutableStateOf(settingsManager.locationMode) }
    var explicitMotionSensorMode by remember { mutableStateOf(if (settingsManager.hasExplicitMotionSensorMode) settingsManager.motionSensorMode else null) }
    val motionSensorMode = explicitMotionSensorMode ?: if (cameraSelection == "internal") 3 else 0


    var showCameraSelectionInfo by remember { mutableStateOf(false) }
    var showCameraSettingsInfo by remember { mutableStateOf(false) }
    var showCameraFrameRateInfo by remember { mutableStateOf(false) }
    var showInternalCameraInfo by remember { mutableStateOf(false) }
    var showSensorModeInfo by remember { mutableStateOf(false) }
    var showDownsamplingInfo by remember { mutableStateOf(false) }
    var showExtractorInfo by remember { mutableStateOf(false) }
    var showGuidanceModeInfo by remember { mutableStateOf(false) }
    var showMotionSensorInfo by remember { mutableStateOf(false) }
    var showLocationInfo by remember { mutableStateOf(false) }


    val supportsRawMap = remember { mutableMapOf<String, Boolean>() }

    val currentOnCameraSelectionChanged by rememberUpdatedState(onCameraSelectionChanged)
    LaunchedEffect(cameraSelection) {
        currentOnCameraSelectionChanged(cameraSelection)
    }

    // Update settings manager when local state changes
    LaunchedEffect(
        cameraSelection,
        cameraSettingsMode,
        internalFps,
        uvcFps,
        extractor,
        sensorMode,
        downsampling,
        internalCameraId,
        guidanceMode
    ) {
        settingsManager.cameraSelection = cameraSelection
        settingsManager.cameraSettingsMode = cameraSettingsMode
        settingsManager.internalFps = internalFps
        settingsManager.uvcFps = uvcFps
        settingsManager.extractor = extractor
        settingsManager.sensorMode = sensorMode
        settingsManager.downsampling = downsampling
        settingsManager.internalCameraId = internalCameraId
        settingsManager.guidanceMode = guidanceMode
    }

    LaunchedEffect(internalCameraId) {
        settingsManager.internalCameraId = internalCameraId
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScrollWithScrollbar(rememberScrollState()),
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
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            IconBarItem(
                icon = Icons.Outlined.PlayCircle,
                label = when {
                    isStopping -> stringResource(id = R.string.btn_stopping)
                    isStarting -> stringResource(id = R.string.btn_starting)
                    else -> stringResource(id = R.string.btn_start_engine)
                },
                enabled = !isStopping && !isStarting,
                isLoading = isStarting || isStopping,
                iconSize = 36.dp,
                onClick = onToggleEngine,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        Spacer(modifier = Modifier.height(32.dp))

        // Configuration mode
        val cameraSelectionContent: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(id = R.string.label_camera_selection),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                IconButton(
                    onClick = { showCameraSelectionInfo = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = stringResource(id = R.string.cd_info),
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
                    stringResource(id = R.string.label_internal_camera),
                    modifier = Modifier.padding(start = 8.dp),
                    color = MaterialTheme.colorScheme.onBackground
                )
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
                    text = stringResource(id = R.string.label_external_camera),
                    modifier = Modifier.padding(start = 8.dp),
                    color = if (isCameraConnected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onBackground.copy(
                        alpha = 0.38f
                    )
                )
            }

            if (!isCameraConnected) {
                Text(
                    text = stringResource(id = R.string.label_no_external_camera),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 56.dp)
                )
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
                    text = stringResource(id = R.string.label_demo_camera),
                    modifier = Modifier.padding(start = 8.dp),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }


        }

        val otherSettingsContent: @Composable () -> Unit = {
            if (cameraSelection == "internal" || cameraSelection == "external") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(id = R.string.label_camera_settings),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    IconButton(
                        onClick = { showCameraSettingsInfo = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = stringResource(id = R.string.cd_info),
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
                        "Auto" to stringResource(id = R.string.value_auto),
                        "Manual" to stringResource(id = R.string.value_manual)
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
                                var hasCameraProfile by remember {
                                    mutableStateOf(
                                        settingsManager.getString(
                                            "night_profile_Android_Cam_$internalCameraId"
                                        ) != null
                                    )
                                }

                                LaunchedEffect(internalCameraId) {
                                    hasCameraProfile =
                                        settingsManager.getString("night_profile_Android_Cam_$internalCameraId") != null
                                }

                                if (showResetCameraCalibrationDialog) {
                                    AlertDialog(
                                        onDismissRequest = {
                                            showResetCameraCalibrationDialog = false
                                        },
                                        title = { Text(stringResource(R.string.title_reset_calibration)) },
                                        text = { Text(stringResource(R.string.inst_reset_camera_calibration)) },
                                        confirmButton = {
                                            Button(onClick = {
                                                showResetCameraCalibrationDialog = false
                                                settingsManager.removeString("night_profile_Android_Cam_$internalCameraId")
                                                hasCameraProfile = false
                                            }) {
                                                Text(stringResource(R.string.btn_confirm))
                                            }
                                        },
                                        dismissButton = {
                                            OutlinedButton(onClick = {
                                                showResetCameraCalibrationDialog = false
                                            }) {
                                                Text(stringResource(id = R.string.btn_cancel))
                                            }
                                        }
                                    )
                                }

                                Spacer(modifier = Modifier.width(16.dp))
                                IconBarItem(
                                    icon = Icons.Default.SettingsBackupRestore,
                                    label = stringResource(id = R.string.btn_reset),
                                    onClick = {
                                        showResetCameraCalibrationDialog = true
                                    },
                                    enabled = hasCameraProfile
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            if (cameraSelection == "external") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(id = R.string.label_camera_frame_rate),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    IconButton(
                        onClick = { showCameraFrameRateInfo = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = stringResource(id = R.string.cd_info),
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
                    var expandedFps by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier.border(
                            1.dp,
                            Color.Red,
                            shape = RoundedCornerShape(4.dp)
                        )
                    ) {
                        TextButton(onClick = { expandedFps = true }) {
                            Text(
                                NumberFormat.getInstance()
                                    .format(if (cameraSelection == "external") uvcFps else internalFps),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        DropdownMenu(
                            expanded = expandedFps,
                            onDismissRequest = { expandedFps = false },
                            modifier = Modifier.border(
                                1.dp,
                                Color.Red,
                                shape = RoundedCornerShape(4.dp)
                            )
                        ) {
                            val fpsOptions = if (cameraSelection == "external") listOf(
                                30,
                                20,
                                15,
                                10,
                                5
                            ) else listOf(10, 5, 2, 1)
                            fpsOptions.forEach { fps ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            NumberFormat.getInstance().format(fps),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    onClick = {
                                        if (cameraSelection == "external") {
                                            uvcFps = fps
                                        } else {
                                            internalFps = fps
                                        }
                                        expandedFps = false
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            if (cameraSelection == "internal" && cameraSettingsMode == "Manual") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(id = R.string.label_internal_camera_section),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    IconButton(
                        onClick = { showInternalCameraInfo = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = stringResource(id = R.string.cd_info),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                val fovDegreeFormat = stringResource(id = R.string.format_degree)
                val cameraFovFormat = stringResource(id = R.string.format_camera_fov)
                val camerasList = remember(fovDegreeFormat, cameraFovFormat) {
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
                            val degreeStr = String.format(
                                Locale.getDefault(),
                                fovDegreeFormat,
                                String.format(Locale.getDefault(), "%.1f", cam.second)
                            )
                            list.add(
                                Pair(
                                    cam.first,
                                    String.format(
                                        Locale.getDefault(),
                                        cameraFovFormat,
                                        cam.first,
                                        degreeStr
                                    )
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
                        .padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
                ) {
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
                Spacer(modifier = Modifier.height(24.dp))
            }

            if (cameraSettingsMode == "Manual") {
                if (cameraSelection == "internal") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(id = R.string.label_sensor_mode),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        IconButton(
                            onClick = { showSensorModeInfo = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = stringResource(id = R.string.cd_info),
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
                            "Processed" to stringResource(id = R.string.value_processed),
                            "Raw" to stringResource(id = R.string.value_raw)
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
                            stringResource(id = R.string.label_downsampling),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        IconButton(
                            onClick = { showDownsamplingInfo = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = stringResource(id = R.string.cd_info),
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
                            0 to stringResource(id = R.string.value_off),
                            2 to stringResource(id = R.string.value_2x2),
                            4 to stringResource(id = R.string.value_4x4)
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
                        stringResource(id = R.string.label_extractor),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    IconButton(
                        onClick = { showExtractorInfo = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = stringResource(id = R.string.cd_info),
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
                        "Olive" to stringResource(id = R.string.value_olive),
                        "Cedar" to stringResource(id = R.string.value_cedar)
                    ).forEach { (ext, label) ->
                        RadioButton(
                            selected = extractor == ext,
                            onClick = { extractor = ext },
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
                    stringResource(id = R.string.label_guidance_mode),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                IconButton(
                    onClick = { showGuidanceModeInfo = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = stringResource(id = R.string.cd_info),
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
                    "alt_az" to stringResource(id = R.string.value_alt_az_offset),
                    "equatorial" to stringResource(id = R.string.value_equatorial_ra_dec)
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

            Spacer(modifier = Modifier.height(24.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(id = R.string.label_location),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                IconButton(
                    onClick = { showLocationInfo = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = stringResource(id = R.string.cd_info),
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
                    stringResource(if (currentLat >= 0) R.string.dir_north else R.string.dir_south)
                val lonDir =
                    stringResource(if (currentLon >= 0) R.string.dir_east else R.string.dir_west)
                stringResource(
                    R.string.label_current_location,
                    stringResource(
                        R.string.format_coordinate,
                        abs(currentLat),
                        latDir,
                        abs(currentLon),
                        lonDir
                    )
                )
            } else {
                stringResource(id = R.string.status_location_not_set)
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
                        stringResource(id = R.string.value_auto),
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
                        label = stringResource(id = R.string.btn_refresh),
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
                        stringResource(id = R.string.value_manual),
                        modifier = Modifier.padding(start = 4.dp),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                if (locationMode == "Manual") {
                    IconBarItem(
                        icon = Icons.Default.Edit,
                        label = stringResource(id = R.string.btn_edit_location),
                        onClick = { viewModel.showLocationDialog = true }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (cameraSelection != "demo") {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(id = R.string.label_motion_sensor),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    IconButton(
                        onClick = { showMotionSensorInfo = true },
                        modifier = Modifier.padding(start = 4.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = stringResource(id = R.string.cd_info),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        0 to stringResource(id = R.string.value_off),
                        3 to stringResource(id = R.string.value_on)
                    ).forEach { (value, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable {
                                    explicitMotionSensorMode = value
                                    settingsManager.motionSensorMode = value
                                }
                                .padding(end = 16.dp)
                        ) {
                            RadioButton(
                                selected = motionSensorMode == value,
                                onClick = {
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
                                        settingsManager.getString(
                                            "imu_calibration"
                                        ) != null
                                    )
                                }
                                var showResetCalibrationDialog by remember { mutableStateOf(false) }

                                if (showResetCalibrationDialog) {
                                    AlertDialog(
                                        onDismissRequest = { showResetCalibrationDialog = false },
                                        title = { Text(stringResource(R.string.title_reset_calibration)) },
                                        text = { Text(stringResource(R.string.inst_reset_calibration)) },
                                        confirmButton = {
                                            Button(onClick = {
                                                showResetCalibrationDialog = false
                                                settingsManager.removeString("imu_calibration")
                                                hasCalibration = false
                                            }) {
                                                Text(stringResource(R.string.btn_confirm))
                                            }
                                        },
                                        dismissButton = {
                                            OutlinedButton(onClick = {
                                                showResetCalibrationDialog = false
                                            }) {
                                                Text(stringResource(id = R.string.btn_cancel))
                                            }
                                        }
                                    )
                                }

                                if (cameraSelection != "external") {
                                    Spacer(modifier = Modifier.width(16.dp))
                                    IconBarItem(
                                        icon = Icons.Default.SettingsBackupRestore,
                                        label = stringResource(id = R.string.btn_reset),
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

        Column(modifier = Modifier.fillMaxWidth()) {
            cameraSelectionContent()

            Spacer(modifier = Modifier.height(24.dp))
            otherSettingsContent()
        }



        if (showCameraSettingsInfo) {
            InfoPopup(
                title = stringResource(id = R.string.label_camera_settings),
                onDismiss = { showCameraSettingsInfo = false }) {
                Text(AnnotatedString.fromHtml(stringResource(id = R.string.info_camera_settings)))
            }
        }
        if (showCameraFrameRateInfo) {
            InfoPopup(
                title = stringResource(id = R.string.label_camera_frame_rate),
                onDismiss = { showCameraFrameRateInfo = false }) {
                Text(AnnotatedString.fromHtml(stringResource(id = R.string.info_camera_frame_rate)))
            }
        }
        if (showInternalCameraInfo) {
            InfoPopup(
                title = stringResource(id = R.string.label_internal_camera_section),
                onDismiss = { showInternalCameraInfo = false }) {
                Text(AnnotatedString.fromHtml(stringResource(id = R.string.info_internal_camera)))
            }
        }
        if (showCameraSelectionInfo) {
            InfoPopup(
                title = stringResource(id = R.string.label_camera_selection),
                onDismiss = { showCameraSelectionInfo = false }) {
                Text(AnnotatedString.fromHtml(stringResource(id = R.string.info_camera_selection)))
            }
        }
        if (showSensorModeInfo) {
            InfoPopup(
                title = stringResource(id = R.string.label_sensor_mode),
                onDismiss = { showSensorModeInfo = false }) {
                Text(AnnotatedString.fromHtml(stringResource(id = R.string.info_sensor_mode)))
            }
        }
        if (showDownsamplingInfo) {
            InfoPopup(
                title = stringResource(id = R.string.label_downsampling),
                onDismiss = { showDownsamplingInfo = false }) {
                Text(AnnotatedString.fromHtml(stringResource(id = R.string.info_downsampling)))
            }
        }
        if (showExtractorInfo) {
            InfoPopup(
                title = stringResource(id = R.string.label_extractor),
                onDismiss = { showExtractorInfo = false }) {
                Text(AnnotatedString.fromHtml(stringResource(id = R.string.info_extractor)))
            }
        }
        if (showGuidanceModeInfo) {
            InfoPopup(
                title = stringResource(id = R.string.label_guidance_mode),
                onDismiss = { showGuidanceModeInfo = false }) {
                Text(AnnotatedString.fromHtml(stringResource(id = R.string.info_guidance_mode)))
            }
        }
        if (showMotionSensorInfo) {
            InfoPopup(
                title = stringResource(id = R.string.label_motion_sensor),
                onDismiss = { showMotionSensorInfo = false }) {
                Text(stringResource(id = R.string.info_motion_sensor))
            }
        }
        if (showLocationInfo) {
            InfoPopup(
                title = stringResource(id = R.string.label_location),
                onDismiss = { showLocationInfo = false }) {
                Text(AnnotatedString.fromHtml(stringResource(id = R.string.info_location)))
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
                            contentDescription = stringResource(id = R.string.cd_close),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .verticalScrollWithScrollbar(rememberScrollState())
                ) {
                    content()
                }
            }
        }
    }
}
