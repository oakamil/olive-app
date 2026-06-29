package com.oakamil.olive

import android.content.res.Configuration
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    var showStarSensitivityInfo by remember { mutableStateOf(false) }
    var showZoomInfo by remember { mutableStateOf(false) }
    var showExposureInfo by remember { mutableStateOf(false) }
    var showStackingInfo by remember { mutableStateOf(false) }
    var showCatalog by remember { mutableStateOf(false) }
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
                    .aspectRatio(16f / 9f),
                colors = CardDefaults.cardColors(containerColor = Color.Black),
                border = BorderStroke(2.dp, Color(0xFF4A1010))
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
                        contentDescription = stringResource(id = R.string.cd_info),
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
                        mainScreenExposure = (rawExp / 1000000f).coerceIn(0.2f, 5.0f)
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = { showCatalog = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary
                        )
                    ) {
                        AutoSizeText(stringResource(R.string.btn_catalog))
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
                            onNavigateToTuner()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        AutoSizeText("Camera / Align")
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            isEnteringPip = true
                            onEnterPipMode()
                            coroutineScope.launch {
                                delay(1000)
                                isEnteringPip = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isEnteringPip
                    ) {
                        AutoSizeText(
                            if (isEnteringPip) stringResource(id = R.string.btn_entering_pip) else "Picture-in-Picture"
                        )
                    }

                    Button(
                        onClick = onToggleEngine,
                        modifier = Modifier.weight(1f),
                        enabled = !isStopping && !isStarting,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        AutoSizeText(
                            when {
                                isStopping -> stringResource(id = R.string.btn_stopping)
                                isStarting -> stringResource(id = R.string.btn_starting)
                                else -> stringResource(id = R.string.btn_stop_engine)
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
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
        } else {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                GuidanceContent()
                Spacer(modifier = Modifier.height(16.dp))
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
                onGoTo = { ra, dec ->
                    viewModel.setGoToTarget(ra, dec)
                    showCatalog = false
                }
            )
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
            Text(
                stringResource(id = R.string.stat_status) + ": ${stats.status}",
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
            Text(
                stringResource(id = R.string.stat_fov) + ": " + (
                        stats.fov?.let {
                            stringResource(
                                id = R.string.format_degree,
                                String.format(Locale.getDefault(), "%.2f", it)
                            )
                        } ?: "-"
                        ),
                color = MaterialTheme.colorScheme.onBackground
            )
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
        }
    }
}

@Composable
fun AutoSizeText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified
) {
    val defaultTextStyle = MaterialTheme.typography.labelLarge
    var textStyle by remember { mutableStateOf(defaultTextStyle) }
    var readyToDraw by remember { mutableStateOf(false) }

    Text(
        text = text,
        color = color,
        maxLines = 1,
        softWrap = false,
        style = textStyle,
        modifier = modifier.drawWithContent {
            if (readyToDraw) {
                drawContent()
            }
        },
        onTextLayout = { result ->
            if (result.didOverflowWidth) {
                textStyle = textStyle.copy(fontSize = textStyle.fontSize * 0.9f)
            } else {
                readyToDraw = true
            }
        }
    )
}
