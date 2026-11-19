// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeJoin.Companion.Round
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    settingsManager: SettingsManager,
    isCameraConnected: Boolean,
    activeCameraSelection: String,
    isStopping: Boolean,
    isStarting: Boolean,
    detectedIpAddress: String,
    onCameraSelectionChanged: (String) -> Unit,
    onNavigateToTuner: () -> Unit,
    onEnterPipMode: () -> Unit,
    onToggleEngine: () -> Unit,
    onRefreshLocation: () -> Unit = {}
) {
    val windowController = LocalPlatformWindowController.current
    val capabilities = LocalPlatformCapabilities.current
    val coroutineScope = rememberCoroutineScope()

    var showStartEngineDialog by remember { mutableStateOf(false) }
    var showSkyMapSettings by remember { mutableStateOf(false) }
    var isEnteringPip by remember { mutableStateOf(false) }

    val isLowPowerMode = viewModel.isLowPowerMode
    val lowPowerTimeoutSeconds = settingsManager.lowPowerTimeoutSeconds
    val lastInteractionTimeMs = remember {
        LongArray(1) { Clock.System.now().toEpochMilliseconds() }
    }

    @Suppress("DEPRECATION")
    val lifecycleOwner = LocalLifecycleOwner.current
    var isAppInForeground by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAppInForeground = true
                lastInteractionTimeMs[0] = Clock.System.now().toEpochMilliseconds()
                viewModel.isLowPowerMode = false
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                isAppInForeground = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(
        isAppInForeground,
        viewModel.isEngineRunning,
        lowPowerTimeoutSeconds,
        viewModel.isCameraStuck,
        viewModel.isLowPowerMode
    ) {
        if (!viewModel.isEngineRunning) {
            viewModel.isLowPowerMode = false
        } else if (isAppInForeground && !viewModel.isCameraStuck && !viewModel.isLowPowerMode && lowPowerTimeoutSeconds > 0) {
            val timeoutMs = lowPowerTimeoutSeconds * 1000L
            val startNow = Clock.System.now().toEpochMilliseconds()
            if (lastInteractionTimeMs[0] < startNow) {
                lastInteractionTimeMs[0] = startNow
            }
            while (isActive) {
                val now = Clock.System.now().toEpochMilliseconds()
                val remaining = timeoutMs - (now - lastInteractionTimeMs[0])
                if (remaining <= 0L) {
                    viewModel.showCatalog = false
                    showSkyMapSettings = false
                    showStartEngineDialog = false
                    viewModel.isLowPowerMode = true
                    break
                }
                delay(remaining)
            }
        }
    }

    val controlsAlpha by animateFloatAsState(
        targetValue = if (isLowPowerMode) 0f else 1f,
        animationSpec = tween(durationMillis = 500),
        label = "alpha"
    )

    DisposableEffect(isLowPowerMode) {
        windowController.setPreferredRefreshRate(if (isLowPowerMode) 30f else 0f)
        onDispose { }
    }

    DisposableEffect(Unit) {
        viewModel.isLowPowerMode = false
        onDispose {
            viewModel.isLowPowerMode = false
            windowController.setPreferredRefreshRate(0f)
            if (!viewModel.isEngineRunning) {
                windowController.restoreScreenBrightness()
            }
        }
    }

    @Composable
    fun GuidanceContent() {
        val extColors = LocalOliveExtendedColors.current
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            colors = CardDefaults.cardColors(
                containerColor = extColors.lowPowerCardSurface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            border = extColors.lowPowerCardBorder?.let { BorderStroke(2.dp, it) }
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                GuidanceDisplay(
                    viewModel.engineStats,
                    viewModel.guidanceMode == "alt_az",
                    isLowPowerMode = true,
                    isImuEnabled = settingsManager.motionSensorMode != 0,
                    isCompactLayout = false
                )
            }
        }
    }

    @Composable
    fun ActionButtons(isVertical: Boolean = false, modifier: Modifier = Modifier) {
        val extColors = LocalOliveExtendedColors.current

        @Composable
        fun Buttons(itemModifier: Modifier = Modifier) {
            // 1. Catalog
            IconBarItem(
                modifier = itemModifier,
                icon = Icons.Default.AutoAwesome,
                label = stringResource(Res.string.btn_catalog),
                onClick = { viewModel.showCatalog = true }
            )

            // 2. Camera / Align
            IconBarItem(
                modifier = itemModifier,
                icon = Icons.Outlined.PhotoCamera,
                label = stringResource(id = Res.string.btn_camera_align),
                enabled = viewModel.isEngineRunning && !isStarting && !isStopping,
                onClick = {
                    viewModel.setEnginePaused(true)
                    if (viewModel.engineMode != MainViewModel.EngineMode.UVC) {
                        val range = viewModel.engine.getNativeCameraGainRange()
                        if (range != null && range.size >= 2) {
                            viewModel.minAnalogGain = range[0]
                            viewModel.maxAnalogGain =
                                range[1].coerceAtLeast(viewModel.minAnalogGain + 1f)
                        } else {
                            viewModel.minAnalogGain = 100f
                            viewModel.maxAnalogGain = 3200f
                        }
                        viewModel.currentExposure =
                            viewModel.engine.getNativeCameraExposure().toFloat()
                                .coerceIn(
                                    capabilities.exposureRange.start,
                                    capabilities.exposureRange.endInclusive
                                )
                        val bRange = viewModel.engine.getNativeCameraBrightnessRange()
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

            // 3. Picture in Picture (if supported)
            if (capabilities.supportsPip) {
                IconBarItem(
                    modifier = itemModifier,
                    icon = Icons.Default.PictureInPicture,
                    label = if (isEnteringPip) stringResource(id = Res.string.btn_entering_pip) else stringResource(
                        id = Res.string.btn_pip
                    ),
                    enabled = viewModel.isEngineRunning && !isStarting && !isStopping && !isEnteringPip,
                    onClick = {
                        @Suppress("AssignedValueIsNeverRead")
                        isEnteringPip = true
                        onEnterPipMode()
                        coroutineScope.launch {
                            delay(1000)
                            @Suppress("AssignedValueIsNeverRead")
                            isEnteringPip = false
                        }
                    }
                )
            }

            // 4. Settings
            IconBarItem(
                modifier = itemModifier,
                icon = Icons.Outlined.Settings,
                label = stringResource(Res.string.title_settings),
                onClick = { showSkyMapSettings = true }
            )

            // 5. Start / Stop Engine
            val isEngineActive = viewModel.isEngineRunning
            val ctaColor =
                if (isEngineActive) extColors.ctaStop else MaterialTheme.colorScheme.primary
            IconBarItem(
                modifier = itemModifier,
                icon = if (isEngineActive) Icons.Outlined.Cancel else Icons.Outlined.PlayCircle,
                label = when {
                    isStopping -> stringResource(id = Res.string.btn_stopping)
                    isStarting -> stringResource(id = Res.string.btn_starting)
                    isEngineActive -> stringResource(id = Res.string.btn_stop_engine)
                    else -> stringResource(id = Res.string.btn_start_engine)
                },
                enabled = !isStopping && !isStarting,
                isLoading = isStarting || isStopping,
                iconTint = ctaColor,
                labelColor = ctaColor,
                onClick = {
                    if (isEngineActive) {
                        onToggleEngine()
                    } else {
                        showStartEngineDialog = true
                    }
                }
            )
        }

        val consumeStrayPointers = Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
        }

        if (isVertical) {
            Row(
                modifier = modifier
                    .fillMaxHeight()
                    .background(extColors.mapOverlaySurface)
                    .then(consumeStrayPointers)
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.End))
            ) {
                VerticalDivider(color = extColors.actionBarBorder)
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(76.dp)
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.SpaceEvenly,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Buttons()
                }
            }
        } else {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .background(extColors.mapOverlaySurface)
                    .then(consumeStrayPointers)
            ) {
                HorizontalDivider(color = extColors.actionBarBorder)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 0.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Top
                ) {
                    Buttons(itemModifier = Modifier.weight(1f))
                }
            }
        }
    }

    val density = LocalDensity.current
    var actionBarWidthDp by remember { mutableStateOf(0.dp) }
    var actionBarHeightDp by remember { mutableStateOf(0.dp) }

    CompositionLocalProvider(
        LocalOnUserInteraction provides {
            lastInteractionTimeMs[0] = Clock.System.now().toEpochMilliseconds()
        }
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            lastInteractionTimeMs[0] = Clock.System.now().toEpochMilliseconds()
                            if (viewModel.isLowPowerMode) {
                                viewModel.isLowPowerMode = false
                                ev.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
        ) {
            val isWideLayout = maxWidth > maxHeight && maxWidth >= 480.dp

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = controlsAlpha }
            ) {
                SkyMapView(
                    viewModel = viewModel,
                    stats = viewModel.engineStats,
                    modifier = Modifier.fillMaxSize(),
                    isStarting = isStarting,
                    isStopping = isStopping,
                    detectedIpAddress = detectedIpAddress,
                    bottomOverlayInset = if (isWideLayout) 0.dp else actionBarHeightDp,
                    endOverlayInset = if (isWideLayout) actionBarWidthDp else 0.dp
                )
                ActionButtons(
                    isVertical = isWideLayout,
                    modifier = Modifier
                        .align(if (isWideLayout) Alignment.CenterEnd else Alignment.BottomCenter)
                        .then(if (isWideLayout) Modifier.fillMaxHeight() else Modifier.fillMaxWidth())
                        .onSizeChanged { size ->
                            with(density) {
                                if (isWideLayout) {
                                    actionBarWidthDp = size.width.toDp()
                                } else {
                                    actionBarHeightDp = size.height.toDp()
                                }
                            }
                        }
                )
            }

            if (isLowPowerMode || controlsAlpha < 1f) {
                val lowPowerAlpha = (1f - controlsAlpha).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .graphicsLayer { alpha = lowPowerAlpha }
                ) {
                    if (isWideLayout) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                contentAlignment = Alignment.Center
                            ) {
                                GuidanceContent()
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                contentAlignment = Alignment.Center
                            ) {
                                DigitalClock()
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                GuidanceContent()
                            }
                            Spacer(Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                DigitalClock()
                            }
                        }
                    }
                }
            }
        }

        if (viewModel.showCatalog) {
            SkyCatalogDialog(
                viewModel = viewModel,
                onDismiss = { viewModel.showCatalog = false },
                onGoTo = { ra, dec, epoch ->
                    viewModel.setGoToTarget(ra, dec, epoch)
                    viewModel.showCatalog = false
                },
                onCenter = { ra, dec ->
                    viewModel.skyMapCenterTarget = Pair(ra, dec)
                    viewModel.showCatalog = false
                }
            )
        }

        if (showSkyMapSettings) {
            SkyMapSettingsDialog(
                viewModel = viewModel,
                onDismiss = {
                    @Suppress("AssignedValueIsNeverRead")
                    showSkyMapSettings = false
                }
            )
        }

        HiPSDownloadDialogs(viewModel = viewModel)

        if (showStartEngineDialog) {
            StartEngineDialog(
                viewModel = viewModel,
                settingsManager = settingsManager,
                isCameraConnected = isCameraConnected,
                activeCameraSelection = activeCameraSelection,
                isStopping = isStopping,
                isStarting = isStarting,
                onCameraSelectionChanged = onCameraSelectionChanged,
                onToggleEngine = onToggleEngine,
                onDismiss = {
                    @Suppress("AssignedValueIsNeverRead")
                    showStartEngineDialog = false
                },
                onRefreshLocation = onRefreshLocation
            )
        }
    }
}

@Composable
fun DigitalClock() {
    val is24Hour = is24HourFormat()
    var timeString by remember(is24Hour) {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        mutableStateOf(formatSystemTime(now, is24Hour))
    }
    LaunchedEffect(is24Hour) {
        while (isActive) {
            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            timeString = formatSystemTime(now, is24Hour)
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
