package com.oakamil.olive

import android.app.Activity
import android.content.res.Configuration
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin.Companion.Round
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import kotlin.math.round

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
    var showStarSensitivityInfo by remember { mutableStateOf(false) }
    var showZoomInfo by remember { mutableStateOf(false) }
    var showExposureInfo by remember { mutableStateOf(false) }
    var showStackingInfo by remember { mutableStateOf(false) }
    var showCatalog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var isLowPowerMode by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val activity = context as? Activity

    LaunchedEffect(lastInteractionTime, showCatalog) {
        if (!showCatalog) {
            delay(30_000)
            isLowPowerMode = true
        }
    }

    DisposableEffect(isLowPowerMode) {
        if (isLowPowerMode) {
            activity?.window?.attributes = activity?.window?.attributes?.apply {
                preferredRefreshRate = 30f
            }
        } else {
            activity?.window?.attributes = activity?.window?.attributes?.apply {
                preferredRefreshRate = 0f
            }
        }
        onDispose {
            activity?.window?.attributes = activity?.window?.attributes?.apply {
                preferredRefreshRate = 0f
            }
        }
    }

    val controlsAlpha by animateFloatAsState(
        targetValue = if (isLowPowerMode) 0f else 1f,
        animationSpec = tween(durationMillis = 500),
        label = "alpha"
    )

    BackHandler(enabled = !isStopping && !isStarting) {
        onToggleEngine()
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        lastInteractionTime = System.currentTimeMillis()
                        if (isLowPowerMode) {
                            isLowPowerMode = false
                            ev.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.alpha(controlsAlpha)) {
            Column {
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
            }
        }

        @Composable
        fun GuidanceContent() {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                colors = CardDefaults.cardColors(containerColor = Color.Black),
                border = BorderStroke(
                    2.dp,
                    if (isLowPowerMode) Color.Transparent else Color(0xFF4A1010)
                )
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    GuidanceDisplay(
                        viewModel.engineStats,
                        settingsManager.guidanceMode == "alt_az",
                        isLowPowerMode,
                        settingsManager.motionSensorMode != 0
                    )
                }
            }
        }

        @Composable
        fun StatsContent() {
            StatsDisplay(viewModel.engineStats)

            Spacer(modifier = Modifier.height(16.dp))
            if (activeCameraSelection != "cedar" && viewModel.engineMode.name != "CEDAR_REMOTE") {
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
                            contentDescription = stringResource(id = R.string.cd_info),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        @Composable
        fun SlidersContent() {
            if (activeCameraSelection == "cedar" || viewModel.engineMode.name == "CEDAR_REMOTE") return

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                val initialBrightness = remember {
                    try {
                        Settings.System.getInt(
                            context.contentResolver,
                            Settings.System.SCREEN_BRIGHTNESS
                        ) / 255f
                    } catch (_: Exception) {
                        0.5f
                    }
                }
                var screenBrightness by remember { mutableFloatStateOf(initialBrightness) }

                LaunchedEffect(screenBrightness) {
                    activity?.window?.attributes = activity?.window?.attributes?.apply {
                        this.screenBrightness = screenBrightness
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Screen Brightness: ${(screenBrightness * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                Slider(
                    value = screenBrightness,
                    onValueChange = { screenBrightness = it },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )

                if (settingsManager.cameraSettingsMode != "Auto") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Star Sensitivity: ${viewModel.starSensitivity.toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        IconButton(
                            onClick = { showStarSensitivityInfo = true },
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(id = R.string.label_zoom) + ": " + stringResource(
                                    id = R.string.format_zoom,
                                    String.format(Locale.getDefault(), "%.1f", zoomRatio)
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            IconButton(
                                onClick = { showZoomInfo = true },
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
                            mainScreenExposure = (rawExp / 1000000f).coerceIn(0.1f, 2.0f)
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Exposure: ${
                                    String.format(
                                        Locale.getDefault(),
                                        "%.1f",
                                        mainScreenExposure
                                    )
                                } s",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            IconButton(
                                onClick = { showExposureInfo = true },
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
                        Slider(
                            value = mainScreenExposure,
                            enabled = settingsManager.cameraSettingsMode != "Auto",
                            onValueChange = {
                                val stepped = round(it * 10.0f) / 10.0f
                                mainScreenExposure = stepped
                                viewModel.setExposure(stepped * 1000000)
                            },
                            valueRange = 0.1f..2.0f,
                            steps = 18,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else if (activeCameraSelection == "uvc" || viewModel.engineMode.name == "UVC") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(id = R.string.label_stacking_frames) + ": ${viewModel.uvcStacking}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            IconButton(
                                onClick = { showStackingInfo = true },
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
                        Slider(
                            value = viewModel.uvcStacking.toFloat(),
                            enabled = settingsManager.cameraSettingsMode != "Auto",
                            onValueChange = {
                                viewModel.updateUvcStacking(it.toInt())
                            },
                            valueRange = 1f..10f,
                            steps = 8,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } // End of isAutoMode check
            }
        }


        @Composable
        fun ActionButtons() {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 0.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Catalog
                IconBarItem(
                    icon = Icons.Default.AutoAwesome,
                    label = stringResource(R.string.btn_catalog),
                    onClick = { showCatalog = true }
                )

                // Camera / Align
                IconBarItem(
                    icon = Icons.Outlined.PhotoCamera,
                    label = stringResource(id = R.string.btn_camera_align),
                    onClick = {
                        viewModel.setEnginePaused(true)
                        if (viewModel.engineMode != MainViewModel.EngineMode.UVC) {
                            val range = OliveEngine.getNativeCameraGainRange()
                            if (range != null && range.size >= 2) {
                                viewModel.minAnalogGain = range[0]
                                viewModel.maxAnalogGain =
                                    range[1].coerceAtLeast(viewModel.minAnalogGain + 1f)
                            } else {
                                viewModel.minAnalogGain = 100f
                                viewModel.maxAnalogGain = 3200f
                            }
                            viewModel.currentExposure =
                                OliveEngine.getNativeCameraExposure().toFloat()
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
                        }
                        onNavigateToTuner()
                    }
                )

                // Picture in Picture
                IconBarItem(
                    icon = Icons.Default.PictureInPicture,
                    label = if (isEnteringPip) stringResource(id = R.string.btn_entering_pip) else "Picture\nin Picture",
                    enabled = !isEnteringPip,
                    onClick = {
                        isEnteringPip = true
                        onEnterPipMode()
                        coroutineScope.launch {
                            delay(1000)
                            isEnteringPip = false
                        }
                    }
                )

                // Stop Engine
                IconBarItem(
                    icon = Icons.Outlined.Cancel,
                    label = when {
                        isStopping -> stringResource(id = R.string.btn_stopping)
                        isStarting -> stringResource(id = R.string.btn_starting)
                        else -> stringResource(id = R.string.btn_stop_engine)
                    },
                    enabled = !isStopping && !isStarting,
                    onClick = onToggleEngine
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
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.alpha(controlsAlpha)) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            BoxWithConstraints(
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .verticalScrollWithScrollbar(rememberScrollState())
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
                    if (isLowPowerMode) {
                        DigitalClock()
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                GuidanceContent()
                Spacer(modifier = Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.alpha(controlsAlpha)) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            BoxWithConstraints(
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .verticalScrollWithScrollbar(rememberScrollState())
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
                    if (isLowPowerMode) {
                        DigitalClock()
                    }
                }
            }
        }

        if (showTelescopeControlInfo) {
            InfoPopup(
                title = stringResource(id = R.string.heading_telescope_control),
                onDismiss = { showTelescopeControlInfo = false }) {
                Text(AnnotatedString.fromHtml(stringResource(id = R.string.info_telescope_control)))
            }
        }

        if (showStarSensitivityInfo) {
            InfoPopup(
                title = stringResource(id = R.string.label_star_sensitivity),
                onDismiss = { showStarSensitivityInfo = false }) {
                Text(AnnotatedString.fromHtml(stringResource(id = R.string.info_star_sensitivity)))
            }
        }
        if (showZoomInfo) {
            InfoPopup(
                title = stringResource(id = R.string.label_zoom),
                onDismiss = { showZoomInfo = false }) {
                Text(AnnotatedString.fromHtml(stringResource(id = R.string.info_zoom)))
            }
        }
        if (showExposureInfo) {
            InfoPopup(
                title = stringResource(id = R.string.label_exposure),
                onDismiss = { showExposureInfo = false }) {
                Text(AnnotatedString.fromHtml(stringResource(id = R.string.info_exposure)))
            }
        }
        if (showStackingInfo) {
            InfoPopup(
                title = stringResource(id = R.string.label_stacking_frames),
                onDismiss = { showStackingInfo = false }) {
                Text(AnnotatedString.fromHtml(stringResource(id = R.string.info_stacking)))
            }
        }

        if (showCatalog) {
            SkyCatalogDialog(
                viewModel = viewModel,
                onDismiss = { showCatalog = false },
                onGoTo = { ra, dec, epoch ->
                    viewModel.setGoToTarget(ra, dec, epoch)
                    showCatalog = false
                }
            )
        }
    }
}

@Composable
fun DigitalClock() {
    var timeString by remember { mutableStateOf("") }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        while (true) {
            val is24Hour = DateFormat.is24HourFormat(context)
            val cal = Calendar.getInstance()
            val format = if (is24Hour) "HH:mm" else "h:mm"
            timeString = DateFormat.format(format, cal).toString()
            delay(1000)
        }
    }
    Text(
        text = timeString,
        style = MaterialTheme.typography.displayLarge.copy(
            drawStyle = Stroke(
                width = 2f,
                join = Round
            )
        ),
        color = MaterialTheme.colorScheme.primary
    )
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
            Text(
                stringResource(id = R.string.stat_status) + ": ${
                    when (stats.status) {
                        "Solving" -> stringResource(id = R.string.stat_solving)
                        "Slewing" -> stringResource(id = R.string.stat_slewing)
                        "Idle" -> stringResource(id = R.string.stat_idle)
                        "Camera Mode" -> stringResource(id = R.string.btn_camera_mode)
                        else -> stats.status
                    }
                }",
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                stringResource(id = R.string.stat_star_count) + ": ${stats.centroidCount}",
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(id = R.string.stat_ra) + ": " + (stats.ra?.let {
                String.format(
                    Locale.getDefault(),
                    "%.4f",
                    it
                )
            } ?: "-"),
                color = MaterialTheme.colorScheme.onBackground)
            Text(stringResource(id = R.string.stat_dec) + ": " + (stats.dec?.let {
                String.format(
                    Locale.getDefault(),
                    "%.4f",
                    it
                )
            } ?: "-"),
                color = MaterialTheme.colorScheme.onBackground)
            stats.fov?.let { fovVal ->
                Text(
                    stringResource(id = R.string.stat_fov) + ": " +
                            stringResource(
                                id = R.string.format_degree,
                                String.format(Locale.getDefault(), "%.2f", fovVal)
                            ),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        // Right Column: Performance metrics
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                stringResource(id = R.string.stat_exposure) + ": " + stringResource(
                    id = R.string.format_ms,
                    String.format(Locale.getDefault(), "%.2f", stats.exposureMs)
                ),
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                stringResource(id = R.string.stat_post_process) + ": " + stringResource(
                    id = R.string.format_ms,
                    String.format(Locale.getDefault(), "%.1f", stats.postProcessMs)
                ),
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                stringResource(id = R.string.stat_extraction) + ": " + stringResource(
                    id = R.string.format_ms,
                    String.format(Locale.getDefault(), "%.1f", stats.extractMs)
                ),
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                stringResource(id = R.string.stat_solve) + ": " + stringResource(
                    id = R.string.format_ms,
                    String.format(Locale.getDefault(), "%.1f", stats.solveMs)
                ),
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                stringResource(id = R.string.stat_solve_rate) + ": " + stringResource(
                    id = R.string.format_hz,
                    String.format(Locale.getDefault(), "%.2f", stats.solveRateHz)
                ),
                color = MaterialTheme.colorScheme.onBackground
            )

            val cropString = when (stats.solvedCrop) {
                "Center" -> stringResource(id = R.string.crop_center)
                "Top" -> stringResource(id = R.string.crop_top)
                "Bottom" -> stringResource(id = R.string.crop_bottom)
                "Left" -> stringResource(id = R.string.crop_left)
                "Right" -> stringResource(id = R.string.crop_right)
                "Full" -> stringResource(id = R.string.crop_full)
                "Failed" -> stringResource(id = R.string.crop_failed)
                "CenterHighSens" -> stringResource(id = R.string.crop_center) + " (HS)"
                "TopHighSens" -> stringResource(id = R.string.crop_top) + " (HS)"
                "BottomHighSens" -> stringResource(id = R.string.crop_bottom) + " (HS)"
                "LeftHighSens" -> stringResource(id = R.string.crop_left) + " (HS)"
                "RightHighSens" -> stringResource(id = R.string.crop_right) + " (HS)"
                "FullHighSens" -> stringResource(id = R.string.crop_full) + " (HS)"
                else -> ""
            }
            if (cropString.isNotEmpty()) {
                Text(
                    stringResource(id = R.string.stat_last_solve) + ": " + cropString,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }
}
