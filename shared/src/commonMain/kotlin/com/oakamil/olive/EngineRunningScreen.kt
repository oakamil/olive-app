package com.oakamil.olive

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin.Companion.Round
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.tan

private class LabelLayoutState {
    var activeItems: List<PlacedCatalogItem> = emptyList()
    var lastLayoutRa: Double? = null
    var lastLayoutDec: Double? = null
    var lastLayoutFov: Double? = null
    var lastTargetRa: Double? = null
    var lastTargetDec: Double? = null
    var lastManualScale: Float? = null
    var lastUiRotation: Float? = null
    var lastAllObjectsCount: Int = 0
}

private const val LABEL_REDRAW_FOV_DELTA = 0.05
private const val MAX_LABELS = 20
private const val UI_SCALE = 1.5f

@OptIn(ExperimentalTextApi::class)
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
    val colors = LocalOliveColors.current
    var mainScreenExposure by remember { mutableFloatStateOf(1.0f) }
    var isEnteringPip by remember { mutableStateOf(false) }
    var showTelescopeControlInfo by remember { mutableStateOf(false) }
    var showStarSensitivityInfo by remember { mutableStateOf(false) }
    var showZoomInfo by remember { mutableStateOf(false) }
    var showExposureInfo by remember { mutableStateOf(false) }
    var showStackingInfo by remember { mutableStateOf(false) }
    val showCatalog = viewModel.showCatalog
    val coroutineScope = rememberCoroutineScope()
    var lastInteractionTime by remember {
        mutableLongStateOf(
            Clock.System.now().toEpochMilliseconds()
        )
    }
    val catalogPainters = rememberCatalogPainters()
    val isLowPowerMode = viewModel.isLowPowerMode
    var lowPowerTimeoutSeconds by remember { mutableIntStateOf(settingsManager.lowPowerTimeoutSeconds) }

    val capabilities = LocalPlatformCapabilities.current
    val windowController = LocalPlatformWindowController.current

    val stats = viewModel.engineStats

    var planets by remember { mutableStateOf<List<CatalogObject>>(emptyList()) }
    var comets by remember { mutableStateOf<List<CatalogObject>>(emptyList()) }
    var asteroids by remember { mutableStateOf<List<CatalogObject>>(emptyList()) }

    LaunchedEffect(Unit) {
        while (isActive) {
            withContext(Dispatchers.IO) {
                planets = viewModel.getPlanets()
                comets = viewModel.getComets()
                asteroids = viewModel.getAsteroids()
            }
            delay(300_000)
        }
    }

    var targetObjName by remember(
        stats.targetRa,
        stats.targetDec
    ) { mutableStateOf<String?>(null) }

    LaunchedEffect(stats.targetRa, stats.targetDec, planets, comets, asteroids) {
        if (stats.targetRa != null && stats.targetDec != null) {
            val targetRa = stats.targetRa
            val targetDec = stats.targetDec

            val solarSystemMatch = (planets + comets + asteroids).firstOrNull {
                AstrometryUtils.angularDistance(it.ra, it.dec, targetRa, targetDec) < 0.5
            }

            if (solarSystemMatch != null) {
                targetObjName = solarSystemMatch.displayName
            } else {
                withContext(Dispatchers.IO) {
                    val obj = viewModel.catalogDb.searchObjects(
                        targetRa = targetRa,
                        targetDec = targetDec,
                        limit = 1,
                        sortBy = "distance"
                    ).firstOrNull()
                    targetObjName = if (obj != null && AstrometryUtils.angularDistance(
                            obj.ra,
                            obj.dec,
                            targetRa,
                            targetDec
                        ) < 0.5
                    ) {
                        obj.displayName
                    } else {
                        null
                    }
                }
            }
        } else {
            targetObjName = null
        }
    }

    var showSyncDialog by remember { mutableStateOf(false) }

    if (showSyncDialog) {
        AlertDialog(
            onDismissRequest = {
                @Suppress("AssignedValueIsNeverRead")
                showSyncDialog = false
            },
            title = { Text(stringResource(Res.string.title_confirm_alignment)) },
            text = { Text(stringResource(Res.string.msg_confirm_alignment)) },
            confirmButton = {
                Button(onClick = {
                    @Suppress("AssignedValueIsNeverRead")
                    showSyncDialog = false
                    viewModel.syncTelescope(stats.targetRa, stats.targetDec)
                }) {
                    Text(stringResource(Res.string.btn_confirm))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    @Suppress("AssignedValueIsNeverRead")
                    showSyncDialog = false
                }) {
                    Text(stringResource(id = Res.string.btn_cancel))
                }
            }
        )
    }

    LaunchedEffect(
        lastInteractionTime,
        showCatalog,
        lowPowerTimeoutSeconds,
        viewModel.isCameraStuck
    ) {
        if (!showCatalog && !viewModel.isCameraStuck && lowPowerTimeoutSeconds > 0) {
            delay(lowPowerTimeoutSeconds * 1000L)
            viewModel.isLowPowerMode = true
        }
    }

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

    val controlsAlpha by animateFloatAsState(
        targetValue = if (isLowPowerMode) 0f else 1f,
        animationSpec = tween(durationMillis = 500),
        label = "alpha"
    )

    var isSkyMapExpanded by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = isSkyMapExpanded) {
        isSkyMapExpanded = false
    }

    BackHandler(enabled = !isSkyMapExpanded && !isStopping && !isStarting) {
        onToggleEngine()
    }

    val isLandscape = windowController.isLandscape

    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        @Suppress("AssignedValueIsNeverRead")
                        lastInteractionTime = Clock.System.now().toEpochMilliseconds()
                        if (viewModel.isLowPowerMode) {
                            viewModel.isLowPowerMode = false
                            ev.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {


        @Composable
        fun GuidanceContent() {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                colors = CardDefaults.cardColors(containerColor = Color.Black),
                border = BorderStroke(
                    2.dp,
                    if (isLowPowerMode) Color.Transparent else colors.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    GuidanceDisplay(
                        viewModel.engineStats,
                        settingsManager.guidanceMode == "alt_az",
                        isLowPowerMode,
                        settingsManager.motionSensorMode != 0,
                        isCompactLayout = false
                    )
                }
            }
        }

        @Composable
        fun CompactInfoArea() {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    GuidanceDisplay(
                        viewModel.engineStats,
                        settingsManager.guidanceMode == "alt_az",
                        isLowPowerMode,
                        settingsManager.motionSensorMode != 0,
                        isCompactLayout = true
                    )
                }

                if (stats.targetRa != null && stats.targetDec != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val targetName = targetObjName ?: stringResource(
                            Res.string.format_ra_dec,
                            String.format("%.2f", stats.targetRa),
                            String.format("%.2f", stats.targetDec)
                        )

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Color.Black.copy(
                                    alpha = 0.6f
                                )
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                val baseStyle =
                                    LocalTextStyle.current.merge(
                                        MaterialTheme.typography.titleMedium
                                    )
                                BasicText(
                                    text = targetName,
                                    style = baseStyle.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                    autoSize = TextAutoSize.StepBased(
                                        minFontSize = 10.sp,
                                        maxFontSize = baseStyle.fontSize
                                    )
                                )
                                val isTargetVisible = remember(
                                    stats.centerRa,
                                    stats.centerDec,
                                    stats.ra,
                                    stats.dec,
                                    stats.fov,
                                    stats.targetRa,
                                    stats.targetDec,
                                    stats.isImuEstimate,
                                    stats.solvedCrop
                                ) {
                                    val isStale =
                                        stats.solvedCrop == "Failed" && !stats.isImuEstimate
                                    if (stats.fov == null || isStale) false
                                    else {
                                        val projRa =
                                            if (stats.isImuEstimate || stats.centerRa == null) stats.ra else stats.centerRa
                                        val projDec =
                                            if (stats.isImuEstimate || stats.centerDec == null) stats.dec else stats.centerDec
                                        if (projRa == null || projDec == null) false
                                        else {
                                            var raDiff = abs(stats.targetRa - projRa)
                                            if (raDiff > 180.0) raDiff = 360.0 - raDiff
                                            val decDiff = abs(stats.targetDec - projDec)
                                            val dist = hypot(
                                                raDiff * cos(Math.toRadians(projDec)),
                                                decDiff
                                            )
                                            dist < stats.fov * 0.75
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))
                                IconButton(
                                    onClick = { viewModel.clearGoToTarget() },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.Cancel,
                                        contentDescription = stringResource(Res.string.btn_cancel_goto),
                                        tint = colors.primary
                                    )
                                }

                                if (isTargetVisible) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    IconButton(
                                        onClick = {
                                            @Suppress("AssignedValueIsNeverRead")
                                            showSyncDialog = true
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.FilterCenterFocus,
                                            contentDescription = stringResource(Res.string.btn_align),
                                            tint = colors.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        @Composable
        fun MainVideoContent() {
            val frame = viewModel.latestFrame
            val roll =
                stats.roll ?: 0.0 // offsetAngle is for the guidance arrow, not image roll!
            var lastValidUiRotation by remember { mutableFloatStateOf(roll.toFloat()) }
            var uiRotation = lastValidUiRotation
            val lat = viewModel.currentLat
            val lon = viewModel.currentLon
            val isPositionStale = stats.solvedCrop == "Failed" && !stats.isImuEstimate

            if (!isPositionStale && stats.ra != null && stats.dec != null && lat != null && lon != null &&
                lat != MainViewModel.INVALID_COORD && lon != MainViewModel.INVALID_COORD
            ) {
                val lst =
                    AstrometryUtils.calculateLST(lon, Clock.System.now().toEpochMilliseconds())
                val q = AstrometryUtils.calculateParallacticAngle(
                    stats.ra,
                    stats.dec,
                    lat,
                    lst
                )
                val newRot = (roll + q).toFloat()
                uiRotation = newRot
                lastValidUiRotation = newRot
            }
            val ra = stats.ra
            val dec = stats.dec
            val baseFovRad = Math.toRadians(stats.fov ?: 15.0)
            val aspect =
                if (frame != null) frame.width.toDouble() / frame.height.toDouble() else 1.0
            val effectiveFovRad = if (aspect > 1.0) {
                2.0 * atan(tan(baseFovRad / 2.0) / aspect)
            } else {
                baseFovRad
            }
            val fov = Math.toDegrees(effectiveFovRad)
            var tappedObject by remember { mutableStateOf<CatalogObject?>(null) }


            var visibleDsos by remember { mutableStateOf<List<CatalogObject>>(emptyList()) }

            val fovVal = stats.fov ?: 15.0
            val stepSize = maxOf(0.1, fovVal * 0.2) // Trigger update every 20% of FOV
            val raGridKey = ra?.let { floor(it / stepSize) }
            val decGridKey = dec?.let { floor(it / stepSize) }
            val fovGridKey =
                floor(fovVal / 5.0) // Only re-trigger on major zoom changes

            val currentTypeFilters = viewModel.catalogTypeFilters
            val currentMagLimit = viewModel.catalogMagLimit
            val isAllTypes =
                currentTypeFilters.size >= MainViewModel.DEFAULT_CATALOG_TYPE_FILTERS.size

            LaunchedEffect(
                raGridKey,
                decGridKey,
                fovGridKey,
                currentTypeFilters,
                currentMagLimit
            ) {
                if (ra != null && dec != null) {
                    withContext(Dispatchers.IO) {
                        // Screen corner is at ~0.707x FOV from center.
                        // 0.9x FOV gives us a 0.2x buffer matching our step size.
                        val searchRadius = fovVal * 0.65

                        val stars = viewModel.catalogDb.searchObjects(
                            targetRa = ra,
                            targetDec = dec,
                            maxDistanceDegrees = searchRadius,
                            magLimit = currentMagLimit,
                            typeFilters = if (isAllTypes) null else currentTypeFilters,
                            starFilter = true,
                            visibilityFilter = "Any",
                            sortBy = "brightness",
                            limit = 400
                        )

                        val dsos = viewModel.catalogDb.searchObjects(
                            targetRa = ra,
                            targetDec = dec,
                            maxDistanceDegrees = searchRadius,
                            magLimit = currentMagLimit,
                            typeFilters = if (isAllTypes) null else currentTypeFilters,
                            starFilter = false,
                            visibilityFilter = "Any",
                            sortBy = "brightness",
                            limit = 400
                        )
                        visibleDsos = stars + dsos
                    }
                }
            }

            val targetLabelFormat = stringResource(Res.string.target_label_format)

            val filteredPlanets = remember(planets, currentTypeFilters, currentMagLimit) {
                planets.filter { p ->
                    val passesType =
                        isAllTypes || "Planet" in currentTypeFilters || p.objectType in currentTypeFilters
                    val passesMag =
                        currentMagLimit == null || (p.magnitude != null && p.magnitude <= currentMagLimit)
                    passesType && passesMag
                }
            }

            val filteredComets = remember(comets, currentTypeFilters, currentMagLimit) {
                comets.filter { c ->
                    val passesType =
                        isAllTypes || "Comet" in currentTypeFilters || c.objectType in currentTypeFilters
                    val passesMag =
                        currentMagLimit == null || (c.magnitude != null && c.magnitude <= currentMagLimit)
                    passesType && passesMag
                }
            }

            val filteredAsteroids = remember(asteroids, currentTypeFilters, currentMagLimit) {
                asteroids.filter { a ->
                    val passesType =
                        isAllTypes || "Asteroid" in currentTypeFilters || a.objectType in currentTypeFilters
                    val passesMag =
                        currentMagLimit == null || (a.magnitude != null && a.magnitude <= currentMagLimit)
                    passesType && passesMag
                }
            }

            val allObjects =
                (filteredPlanets + filteredComets + filteredAsteroids + visibleDsos).toMutableList()
            val targetRaVal = stats.targetRa
            val targetDecVal = stats.targetDec
            if (targetRaVal != null && targetDecVal != null) {
                val hasTarget = allObjects.any {
                    AstrometryUtils.angularDistance(
                        it.ra,
                        it.dec,
                        targetRaVal,
                        targetDecVal
                    ) < 0.05
                }
                if (!hasTarget) {
                    val targetName = targetObjName ?: String.format(
                        targetLabelFormat,
                        targetRaVal,
                        targetDecVal
                    )
                    allObjects.add(
                        CatalogObject(
                            id = -1,
                            catalogLabel = "Target",
                            catalogEntry = targetName,
                            ra = targetRaVal,
                            dec = targetDecVal,
                            constellation = null,
                            objectType = "Target",
                            broadCategory = "Target",
                            magnitude = null,
                            surfaceBrightness = null,
                            angularSize = null,
                            commonName = targetName,
                            description = null,
                            copyright = null
                        )
                    )
                }
            }
            allObjects.sortWith(
                compareBy<CatalogObject> { obj ->
                    val isTarget = targetRaVal != null && targetDecVal != null &&
                            AstrometryUtils.angularDistance(
                                obj.ra,
                                obj.dec,
                                targetRaVal,
                                targetDecVal
                            ) < 0.05
                    if (isTarget) -1 else getCatalogPriority(obj)
                }.thenBy { it.magnitude ?: 99.0 }
            )

            Column(
                modifier = if (isLandscape) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // 2. Camera Feed
                Box(
                    modifier = if (isLandscape) {
                        Modifier
                            .weight(1f) // Binds strictly to available height
                            .aspectRatio(1f)
                            .clipToBounds()
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clipToBounds()
                    }
                ) {
                    val currentUiRotation by rememberUpdatedState(uiRotation)
                    val alignTargetPixel =
                        if (stats.targetPixelX != null && stats.targetPixelY != null) {
                            Pair(stats.targetPixelX, stats.targetPixelY)
                        } else {
                            null
                        }

                    BoxWithConstraints(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        val density = LocalDensity.current
                        val screenSize =
                            Size(
                                constraints.maxWidth.toFloat(),
                                constraints.maxHeight.toFloat()
                            )
                        val bW = frame?.width?.toFloat() ?: 1f
                        val bH = frame?.height?.toFloat() ?: 1f

                        val isMirrored = stats.isMirrored == true
                        val sensorOrientation =
                            if (viewModel.engineMode == MainViewModel.EngineMode.NATIVE) {
                                windowController.getCameraSensorOrientation(settingsManager.internalCameraId)
                            } else {
                                0
                            }
                        val isRotated90 = (sensorOrientation % 180 != 0)
                        val mirrorScaleX = if (isMirrored && isRotated90) -1f else 1f
                        val mirrorScaleY = if (isMirrored && !isRotated90) -1f else 1f

                        val rawTargetPxX = (alignTargetPixel?.first ?: (bW / 2.0)).toFloat()
                        val rawTargetPxY = (alignTargetPixel?.second ?: (bH / 2.0)).toFloat()
                        val targetPxX = if (mirrorScaleX < 0f) bW - rawTargetPxX else rawTargetPxX
                        val targetPxY = if (mirrorScaleY < 0f) bH - rawTargetPxY else rawTargetPxY
                        val baseScale = maxOf(screenSize.width / bW, screenSize.height / bH)

                        val fovZoom = if ((stats.fov ?: 0.0) > 0) ((stats.fov
                            ?: 0.0) / 15.0).toFloat() else 1f
                        val reqScaleX =
                            if (targetPxX > 0 && targetPxX < bW) screenSize.width / (2f * baseScale * minOf(
                                targetPxX,
                                bW - targetPxX
                            )) else 0f
                        val reqScaleY =
                            if (targetPxY > 0 && targetPxY < bH) screenSize.height / (2f * baseScale * minOf(
                                targetPxY,
                                bH - targetPxY
                            )) else 0f
                        val boresightZoom = maxOf(reqScaleX, reqScaleY)

                        val smartScale = maxOf(fovZoom, boresightZoom).coerceIn(1f, 3f)
                        val finalScale = smartScale * UI_SCALE


                        val dx = targetPxX - (bW / 2f)
                        val dy = targetPxY - (bH / 2f)
                        var tx = -dx * baseScale * finalScale
                        var ty = -dy * baseScale * finalScale

                        val rad = Math.toRadians(currentUiRotation.toDouble())
                        val cosR = cos(rad).toFloat()
                        val sinR = sin(rad).toFloat()
                        val rotSw = abs(screenSize.width * cosR) + abs(screenSize.height * sinR)
                        val rotSh = abs(screenSize.width * sinR) + abs(screenSize.height * cosR)

                        val maxTx = maxOf(0f, (bW * baseScale * finalScale - rotSw) / 2f)
                        val maxTy = maxOf(0f, (bH * baseScale * finalScale - rotSh) / 2f)
                        tx = tx.coerceIn(-maxTx, maxTx)
                        ty = ty.coerceIn(-maxTy, maxTy)

                        val screenTx = tx * cosR - ty * sinR
                        val screenTy = tx * sinR + ty * cosR

                        var manualScale by remember { mutableFloatStateOf(1f) }
                        var manualOffset by remember { mutableStateOf(Offset.Zero) }
                        var isManuallyPanned by remember { mutableStateOf(false) }

                        val currentUiRotationRad = Math.toRadians(currentUiRotation.toDouble())
                        val rotatedWidth = abs(bW * cos(currentUiRotationRad)) + abs(
                            bH * sin(currentUiRotationRad)
                        )
                        val rotatedHeight = abs(bW * sin(currentUiRotationRad)) + abs(
                            bH * cos(currentUiRotationRad)
                        )
                        val coverScale = maxOf(
                            screenSize.width / rotatedWidth,
                            screenSize.height / rotatedHeight
                        ).toFloat()
                        val minManualScale = coverScale / (baseScale * finalScale)

                        val clampedManualScale = maxOf(manualScale, minManualScale)
                        val currentEffectiveScale = finalScale * clampedManualScale
                        val currentMaxBoundTx = maxOf(
                            0f,
                            (bW * baseScale * currentEffectiveScale - rotSw) / 2f
                        )
                        val currentMaxBoundTy = maxOf(
                            0f,
                            (bH * baseScale * currentEffectiveScale - rotSh) / 2f
                        )

                        LaunchedEffect(
                            minManualScale,
                            clampedManualScale,
                            currentMaxBoundTx,
                            currentMaxBoundTy,
                            screenTx,
                            screenTy
                        ) {
                            if (manualScale < minManualScale) {
                                manualScale = minManualScale
                            }
                            val intendedTx = screenTx + manualOffset.x
                            val intendedTy = screenTy + manualOffset.y
                            val screenDxClamp =
                                currentMaxBoundTx * abs(cosR) + currentMaxBoundTy * abs(sinR)
                            val screenDyClamp =
                                currentMaxBoundTx * abs(sinR) + currentMaxBoundTy * abs(cosR)
                            val clampedTx =
                                intendedTx.coerceIn(-screenDxClamp, screenDxClamp)
                            val clampedTy =
                                intendedTy.coerceIn(-screenDyClamp, screenDyClamp)
                            if (clampedTx != intendedTx || clampedTy != intendedTy) {
                                manualOffset = Offset(
                                    clampedTx - screenTx,
                                    clampedTy - screenTy
                                )
                            }
                        }

                        if (frame != null) {
                            val imgWidthDp = with(density) { (bW * baseScale).toDp() }
                            val imgHeightDp = with(density) { (bH * baseScale).toDp() }
                            Image(
                                bitmap = frame,
                                contentDescription = stringResource(id = Res.string.cd_live_preview),
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier
                                    .requiredSize(imgWidthDp, imgHeightDp)
                                    .graphicsLayer {
                                        translationX = screenTx + manualOffset.x
                                        translationY = screenTy + manualOffset.y
                                        rotationZ = currentUiRotation
                                        scaleX = finalScale * clampedManualScale * mirrorScaleX
                                        scaleY = finalScale * clampedManualScale * mirrorScaleY
                                    }
                            )

                            val textMeasurer = rememberTextMeasurer(cacheSize = 256)
                            val primaryColor = MaterialTheme.colorScheme.primary
                            val placedScreenItems =
                                remember { mutableListOf<PlacedCatalogItem>() }
                            val labelLayoutState = remember { LabelLayoutState() }

                            // Base UI scaling factor to preserve comfortable element sizing previously scaled by the 1.414 rotation safety margin
                            val uiScale = 1.414f

                            val currentScreenTx by rememberUpdatedState(screenTx)
                            val currentScreenTy by rememberUpdatedState(screenTy)
                            val currentCosR by rememberUpdatedState(cosR)
                            val currentSinR by rememberUpdatedState(sinR)
                            val currentFinalScale by rememberUpdatedState(finalScale)
                            val currentBaseScale by rememberUpdatedState(baseScale)
                            val currentBW by rememberUpdatedState(bW)
                            val currentBH by rememberUpdatedState(bH)
                            val currentScreenSize by rememberUpdatedState(screenSize)
                            val currentManualScale by rememberUpdatedState(clampedManualScale)
                            val currentMinManualScale by rememberUpdatedState(minManualScale)
                            val currentManualOffset by rememberUpdatedState(manualOffset)
                            val currentMirrorScaleX by rememberUpdatedState(mirrorScaleX)
                            val currentMirrorScaleY by rememberUpdatedState(mirrorScaleY)

                            Canvas(
                                modifier = Modifier
                                    .fillMaxSize()

                                    .pointerInput(Unit) {
                                        detectTransformGestures { centroid, pan, zoom, _ ->
                                            if (zoom != 1f || pan != Offset.Zero) {
                                                isManuallyPanned = true
                                                val oldScale =
                                                    maxOf(manualScale, currentMinManualScale)
                                                manualScale = (oldScale * zoom).coerceIn(
                                                    currentMinManualScale,
                                                    5f
                                                )
                                                val effectiveScale =
                                                    currentFinalScale * manualScale
                                                val rotSw =
                                                    abs(currentScreenSize.width * currentCosR) + abs(
                                                        currentScreenSize.height * currentSinR
                                                    )
                                                val rotSh =
                                                    abs(currentScreenSize.width * currentSinR) + abs(
                                                        currentScreenSize.height * currentCosR
                                                    )
                                                val maxBoundTx = maxOf(
                                                    0f,
                                                    (currentBW * currentBaseScale * effectiveScale - rotSw) / 2f
                                                )
                                                val maxBoundTy = maxOf(
                                                    0f,
                                                    (currentBH * currentBaseScale * effectiveScale - rotSh) / 2f
                                                )
                                                val screenDxClamp =
                                                    maxBoundTx * abs(currentCosR) + maxBoundTy * abs(
                                                        currentSinR
                                                    )
                                                val screenDyClamp =
                                                    maxBoundTx * abs(currentSinR) + maxBoundTy * abs(
                                                        currentCosR
                                                    )

                                                val centerOffset = centroid - Offset(
                                                    currentScreenSize.width / 2f,
                                                    currentScreenSize.height / 2f
                                                )
                                                val adjustedPan =
                                                    (manualOffset + centerOffset) * (manualScale / oldScale) - centerOffset + pan
                                                val intendedTx = currentScreenTx + adjustedPan.x
                                                val intendedTy = currentScreenTy + adjustedPan.y
                                                manualOffset = Offset(
                                                    x = intendedTx.coerceIn(
                                                        -screenDxClamp,
                                                        screenDxClamp
                                                    ) - currentScreenTx,
                                                    y = intendedTy.coerceIn(
                                                        -screenDyClamp,
                                                        screenDyClamp
                                                    ) - currentScreenTy
                                                )
                                            }
                                        }
                                    }
                                    .pointerInput(Unit) {
                                        detectTapGestures { tapOffset ->
                                            val closest = findTappedCatalogItem(
                                                placedScreenItems,
                                                tapOffset,
                                                40f * uiScale,
                                                12f * uiScale
                                            )

                                            if (closest != null) {
                                                tappedObject = closest
                                            } else {
                                                val cx = currentScreenSize.width / 2f
                                                val cy = currentScreenSize.height / 2f
                                                val rx =
                                                    tapOffset.x - currentScreenTx - currentManualOffset.x
                                                val ry =
                                                    tapOffset.y - currentScreenTy - currentManualOffset.y
                                                val px =
                                                    cx + (rx - cx) * currentCosR + (ry - cy) * currentSinR
                                                val py =
                                                    cy - (rx - cx) * currentSinR + (ry - cy) * currentCosR
                                                val effectiveScale =
                                                    currentFinalScale * currentManualScale
                                                val ix =
                                                    cx + (px - cx) / (effectiveScale * currentMirrorScaleX)
                                                val iy =
                                                    cy + (py - cy) / (effectiveScale * currentMirrorScaleY)
                                                val tpx =
                                                    (ix - cx) / currentBaseScale + currentBW / 2f
                                                val tpy =
                                                    (iy - cy) / currentBaseScale + currentBH / 2f

                                                if (tpx in 0f..currentBW && tpy in 0f..currentBH) {
                                                    viewModel.setAlignTargetPixel(
                                                        tpx.toDouble(),
                                                        tpy.toDouble()
                                                    )
                                                    tappedObject = null
                                                }
                                            }
                                        }
                                    }
                            ) {
                                placedScreenItems.clear()
                                val cx = screenSize.width / 2f
                                val cy = screenSize.height / 2f

                                fun toPhysical(x: Float, y: Float): Offset {
                                    val effectiveScale = finalScale * clampedManualScale
                                    val px = cx + (x - cx) * effectiveScale
                                    val py = cy + (y - cy) * effectiveScale
                                    val rx = cx + (px - cx) * cosR - (py - cy) * sinR
                                    val ry = cy + (px - cx) * sinR + (py - cy) * cosR
                                    return Offset(
                                        rx + screenTx + manualOffset.x,
                                        ry + screenTy + manualOffset.y
                                    )
                                }

                                val boresightProjX = cx + (targetPxX - bW / 2f) * baseScale
                                val boresightProjY = cy + (targetPxY - bH / 2f) * baseScale
                                val boresightPhysical =
                                    toPhysical(boresightProjX, boresightProjY)

                                val ra = stats.ra
                                val dec = stats.dec
                                val isPositionStale =
                                    stats.solvedCrop == "Failed" && !stats.isImuEstimate

                                if (ra != null && dec != null && !isPositionStale) {
                                    val lastRa = labelLayoutState.lastLayoutRa
                                    val lastDec = labelLayoutState.lastLayoutDec
                                    val distMoved =
                                        if (lastRa != null && lastDec != null) {
                                            AstrometryUtils.angularDistance(
                                                ra,
                                                dec,
                                                lastRa,
                                                lastDec
                                            )
                                        } else {
                                            Double.MAX_VALUE
                                        }

                                    val lastFov = labelLayoutState.lastLayoutFov
                                    val fovChanged = lastFov == null ||
                                            abs(fov - lastFov) >= fov * LABEL_REDRAW_FOV_DELTA

                                    val targetChanged =
                                        targetRaVal != labelLayoutState.lastTargetRa ||
                                                targetDecVal != labelLayoutState.lastTargetDec

                                    val lastScale = labelLayoutState.lastManualScale
                                    val scaleChanged =
                                        lastScale == null ||
                                                abs(clampedManualScale - lastScale) / lastScale > 0.2f

                                    val lastRot = labelLayoutState.lastUiRotation
                                    val rotationChanged =
                                        lastRot == null ||
                                                abs(currentUiRotation - lastRot) > 5f

                                    val objectsChanged =
                                        allObjects.size != labelLayoutState.lastAllObjectsCount

                                    val shouldRelayout =
                                        labelLayoutState.activeItems.isEmpty() ||
                                                distMoved >= fov * LABEL_REDRAW_FOV_DELTA ||
                                                fovChanged ||
                                                targetChanged ||
                                                scaleChanged ||
                                                rotationChanged ||
                                                objectsChanged

                                    if (shouldRelayout) {
                                        val candidates = allObjects.mapNotNull { obj ->
                                            val projRa =
                                                if (stats.isImuEstimate || stats.centerRa == null) ra else stats.centerRa
                                            val projDec =
                                                if (stats.isImuEstimate || stats.centerDec == null) dec else stats.centerDec

                                            val dist = AstrometryUtils.angularDistance(
                                                obj.ra,
                                                obj.dec,
                                                projRa,
                                                projDec
                                            )
                                            if (dist > fov * 1.5) return@mapNotNull null

                                            val projCx =
                                                if (!stats.isImuEstimate && stats.centerRa != null) cx else boresightProjX
                                            val projCy =
                                                if (!stats.isImuEstimate && stats.centerDec != null) cy else boresightProjY

                                            val proj = AstrometryUtils.projectToScreen(
                                                obj.ra,
                                                obj.dec,
                                                projRa,
                                                projDec,
                                                roll,
                                                fov,
                                                screenSize.width,
                                                screenSize.height,
                                                frame.width,
                                                frame.height,
                                                projCx,
                                                projCy,
                                                stats.distortion
                                            ) ?: return@mapNotNull null

                                            val phys = toPhysical(proj.first, proj.second)
                                            if (phys.x in 0f..size.width && phys.y in 0f..size.height) {
                                                obj to phys
                                            } else {
                                                null
                                            }
                                        }

                                        labelLayoutState.activeItems = layoutCatalogItems(
                                            candidates = candidates,
                                            textMeasurer = textMeasurer,
                                            viewportWidth = size.width,
                                            viewportHeight = size.height,
                                            targetRa = targetRaVal,
                                            targetDec = targetDecVal,
                                            maxLabels = MAX_LABELS,
                                            uiScale = uiScale,
                                            fontSizeSp = 12f
                                        )

                                        labelLayoutState.lastLayoutRa = ra
                                        labelLayoutState.lastLayoutDec = dec
                                        labelLayoutState.lastLayoutFov = fov
                                        labelLayoutState.lastTargetRa = targetRaVal
                                        labelLayoutState.lastTargetDec = targetDecVal
                                        labelLayoutState.lastManualScale = clampedManualScale
                                        labelLayoutState.lastUiRotation = currentUiRotation
                                        labelLayoutState.lastAllObjectsCount = allObjects.size
                                    }

                                    // Draw active labels every frame with updated coordinates
                                    for (item in labelLayoutState.activeItems) {
                                        val isTarget =
                                            item.isTarget || (targetRaVal != null && targetDecVal != null &&
                                                    AstrometryUtils.angularDistance(
                                                        item.obj.ra,
                                                        item.obj.dec,
                                                        targetRaVal,
                                                        targetDecVal
                                                    ) < 0.05)
                                        val projRa =
                                            if (stats.isImuEstimate || stats.centerRa == null) ra else stats.centerRa
                                        val projDec =
                                            if (stats.isImuEstimate || stats.centerDec == null) dec else stats.centerDec

                                        val dist = AstrometryUtils.angularDistance(
                                            item.obj.ra,
                                            item.obj.dec,
                                            projRa,
                                            projDec
                                        )
                                        if (dist > fov * 1.5) continue

                                        val projCx =
                                            if (!stats.isImuEstimate && stats.centerRa != null) cx else boresightProjX
                                        val projCy =
                                            if (!stats.isImuEstimate && stats.centerDec != null) cy else boresightProjY

                                        val proj = AstrometryUtils.projectToScreen(
                                            item.obj.ra,
                                            item.obj.dec,
                                            projRa,
                                            projDec,
                                            roll,
                                            fov,
                                            screenSize.width,
                                            screenSize.height,
                                            frame.width,
                                            frame.height,
                                            projCx,
                                            projCy,
                                            stats.distortion
                                        ) ?: continue

                                        val physPt = toPhysical(proj.first, proj.second)

                                        if (physPt.x < -100f || physPt.x > size.width + 100f ||
                                            physPt.y < -100f || physPt.y > size.height + 100f
                                        ) {
                                            continue
                                        }

                                        var labelRect: Rect? = null
                                        if (item.labelOffset != null) {
                                            val labelTopLeft = physPt + item.labelOffset
                                            labelRect = Rect(
                                                labelTopLeft.x,
                                                labelTopLeft.y,
                                                labelTopLeft.x + item.labelWidth,
                                                labelTopLeft.y + item.labelHeight
                                            )
                                        }

                                        placedScreenItems.add(
                                            PlacedCatalogItem(
                                                obj = item.obj,
                                                screenX = physPt.x,
                                                screenY = physPt.y,
                                                isTarget = isTarget,
                                                labelOffset = item.labelOffset,
                                                labelWidth = item.labelWidth,
                                                labelHeight = item.labelHeight,
                                                labelRect = labelRect
                                            )
                                        )

                                        val (iconVector, defaultColor) = item.obj.getIconAndColor(colors.primary, colors.secondary)
                                        val iconColor =
                                            if (isTarget) colors.primary else defaultColor
                                        val iconPainter = catalogPainters[iconVector]
                                        val iconSize = 24f * uiScale

                                        if (iconPainter != null) {
                                            translate(
                                                left = physPt.x - iconSize / 2f,
                                                top = physPt.y - iconSize / 2f
                                            ) {
                                                with(iconPainter) {
                                                    draw(
                                                        size = Size(iconSize, iconSize),
                                                        colorFilter = ColorFilter.tint(iconColor)
                                                    )
                                                }
                                            }
                                        }

                                        // Target box
                                        if (isTarget) {
                                            val s = 18f * uiScale
                                            val stroke = 3f * uiScale
                                            drawLine(
                                                colors.primary,
                                                Offset(physPt.x - s, physPt.y - s),
                                                Offset(
                                                    physPt.x - s + 10f * uiScale,
                                                    physPt.y - s
                                                ), strokeWidth = stroke
                                            )
                                            drawLine(
                                                colors.primary,
                                                Offset(physPt.x - s, physPt.y - s),
                                                Offset(
                                                    physPt.x - s,
                                                    physPt.y - s + 10f * uiScale
                                                ), strokeWidth = stroke
                                            )
                                            drawLine(
                                                colors.primary,
                                                Offset(physPt.x + s, physPt.y - s),
                                                Offset(
                                                    physPt.x + s - 10f * uiScale,
                                                    physPt.y - s
                                                ), strokeWidth = stroke
                                            )
                                            drawLine(
                                                colors.primary,
                                                Offset(physPt.x + s, physPt.y - s),
                                                Offset(
                                                    physPt.x + s,
                                                    physPt.y - s + 10f * uiScale
                                                ), strokeWidth = stroke
                                            )
                                            drawLine(
                                                colors.primary,
                                                Offset(physPt.x - s, physPt.y + s),
                                                Offset(
                                                    physPt.x - s + 10f * uiScale,
                                                    physPt.y + s
                                                ), strokeWidth = stroke
                                            )
                                            drawLine(
                                                colors.primary,
                                                Offset(physPt.x - s, physPt.y + s),
                                                Offset(
                                                    physPt.x - s,
                                                    physPt.y + s - 10f * uiScale
                                                ), strokeWidth = stroke
                                            )
                                            drawLine(
                                                colors.primary,
                                                Offset(physPt.x + s, physPt.y + s),
                                                Offset(
                                                    physPt.x + s - 10f * uiScale,
                                                    physPt.y + s
                                                ), strokeWidth = stroke
                                            )
                                            drawLine(
                                                colors.primary,
                                                Offset(physPt.x + s, physPt.y + s),
                                                Offset(
                                                    physPt.x + s,
                                                    physPt.y + s - 10f * uiScale
                                                ), strokeWidth = stroke
                                            )
                                        }

                                        if (item.labelOffset != null && labelRect != null) {
                                            val objColor =
                                                if (isTarget) colors.primary else primaryColor
                                            val textLayout = textMeasurer.measure(
                                                text = item.obj.displayName,
                                                style = TextStyle(
                                                    color = objColor,
                                                    fontSize = (12f * uiScale).sp,
                                                    fontWeight = if (isTarget) FontWeight.Bold else FontWeight.Normal
                                                )
                                            )
                                            drawText(
                                                textLayoutResult = textLayout,
                                                color = objColor,
                                                topLeft = Offset(
                                                    labelRect.left,
                                                    labelRect.top
                                                )
                                            )
                                        }
                                    }
                                }

                                val isOffScreen =
                                    boresightPhysical.x < 0 || boresightPhysical.x > screenSize.width || boresightPhysical.y < 0 || boresightPhysical.y > screenSize.height
                                if (isOffScreen) {
                                    val screenDx = boresightPhysical.x - screenSize.width / 2f
                                    val screenDy = boresightPhysical.y - screenSize.height / 2f
                                    val pad = 40f * uiScale
                                    val fX =
                                        if (abs(screenDx) > 0) (screenSize.width / 2f - pad) / abs(
                                            screenDx
                                        ) else Float.MAX_VALUE
                                    val fY =
                                        if (abs(screenDy) > 0) (screenSize.height / 2f - pad) / abs(
                                            screenDy
                                        ) else Float.MAX_VALUE
                                    val f = minOf(fX, fY)
                                    val clampedX = screenSize.width / 2f + screenDx * f
                                    val clampedY = screenSize.height / 2f + screenDy * f
                                    val angle = Math.toDegrees(
                                        atan2(
                                            screenDy.toDouble(),
                                            screenDx.toDouble()
                                        )
                                    ).toFloat()

                                    rotate(
                                        degrees = angle,
                                        pivot = Offset(clampedX, clampedY)
                                    ) {
                                        val arm = 20f * uiScale
                                        val stroke = 6f * uiScale
                                        val clr = colors.secondary
                                        drawLine(
                                            clr,
                                            Offset(clampedX, clampedY),
                                            Offset(clampedX - arm, clampedY - arm),
                                            strokeWidth = stroke,
                                            cap = StrokeCap.Round
                                        )
                                        drawLine(
                                            clr,
                                            Offset(clampedX, clampedY),
                                            Offset(clampedX - arm, clampedY + arm),
                                            strokeWidth = stroke,
                                            cap = StrokeCap.Round
                                        )
                                        val off = 15f * uiScale
                                        drawLine(
                                            clr,
                                            Offset(clampedX - off, clampedY),
                                            Offset(clampedX - off - arm, clampedY - arm),
                                            strokeWidth = stroke,
                                            cap = StrokeCap.Round
                                        )
                                        drawLine(
                                            clr,
                                            Offset(clampedX - off, clampedY),
                                            Offset(clampedX - off - arm, clampedY + arm),
                                            strokeWidth = stroke,
                                            cap = StrokeCap.Round
                                        )
                                    }
                                } else {
                                    val s = 32f * uiScale
                                    val arm = 20f * uiScale
                                    val stroke = 3f * uiScale
                                    val clr = colors.secondary
                                    drawLine(
                                        clr,
                                        Offset(
                                            boresightPhysical.x - s,
                                            boresightPhysical.y - s
                                        ),
                                        Offset(
                                            boresightPhysical.x - s + arm,
                                            boresightPhysical.y - s
                                        ), strokeWidth = stroke, cap = StrokeCap.Round
                                    )
                                    drawLine(
                                        clr,
                                        Offset(
                                            boresightPhysical.x - s,
                                            boresightPhysical.y - s
                                        ),
                                        Offset(
                                            boresightPhysical.x - s,
                                            boresightPhysical.y - s + arm
                                        ), strokeWidth = stroke, cap = StrokeCap.Round
                                    )
                                    drawLine(
                                        clr,
                                        Offset(
                                            boresightPhysical.x + s,
                                            boresightPhysical.y - s
                                        ),
                                        Offset(
                                            boresightPhysical.x + s - arm,
                                            boresightPhysical.y - s
                                        ), strokeWidth = stroke, cap = StrokeCap.Round
                                    )
                                    drawLine(
                                        clr,
                                        Offset(
                                            boresightPhysical.x + s,
                                            boresightPhysical.y - s
                                        ),
                                        Offset(
                                            boresightPhysical.x + s,
                                            boresightPhysical.y - s + arm
                                        ), strokeWidth = stroke, cap = StrokeCap.Round
                                    )
                                    drawLine(
                                        clr,
                                        Offset(
                                            boresightPhysical.x - s,
                                            boresightPhysical.y + s
                                        ),
                                        Offset(
                                            boresightPhysical.x - s + arm,
                                            boresightPhysical.y + s
                                        ), strokeWidth = stroke, cap = StrokeCap.Round
                                    )
                                    drawLine(
                                        clr,
                                        Offset(
                                            boresightPhysical.x - s,
                                            boresightPhysical.y + s
                                        ),
                                        Offset(
                                            boresightPhysical.x - s,
                                            boresightPhysical.y + s - arm
                                        ), strokeWidth = stroke, cap = StrokeCap.Round
                                    )
                                    drawLine(
                                        clr,
                                        Offset(
                                            boresightPhysical.x + s,
                                            boresightPhysical.y + s
                                        ),
                                        Offset(
                                            boresightPhysical.x + s - arm,
                                            boresightPhysical.y + s
                                        ), strokeWidth = stroke, cap = StrokeCap.Round
                                    )
                                    drawLine(
                                        clr,
                                        Offset(
                                            boresightPhysical.x + s,
                                            boresightPhysical.y + s
                                        ),
                                        Offset(
                                            boresightPhysical.x + s,
                                            boresightPhysical.y + s - arm
                                        ), strokeWidth = stroke, cap = StrokeCap.Round
                                    )
                                }
                            }
                        }

                        // Removing qualifier causes a compilation error
                        androidx.compose.animation.AnimatedVisibility(
                            visible = isManuallyPanned,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                        ) {
                            OutlinedIconButton(
                                onClick = {
                                    manualScale = 1f
                                    manualOffset = Offset.Zero
                                    isManuallyPanned = false
                                },
                                border = BorderStroke(
                                    1.5.dp,
                                    colors.primary
                                ),
                                colors = IconButtonDefaults.outlinedIconButtonColors(
                                    contentColor = colors.primary,
                                    containerColor = Color.Transparent
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FilterCenterFocus,
                                    contentDescription = stringResource(Res.string.btn_recenter),
                                    tint = colors.primary
                                )
                            }
                        }

                        OutlinedIconButton(
                            onClick = { isSkyMapExpanded = true },
                            border = BorderStroke(1.5.dp, colors.primary),
                            colors = IconButtonDefaults.outlinedIconButtonColors(
                                contentColor = colors.primary,
                                containerColor = colors.background.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.TravelExplore,
                                contentDescription = stringResource(Res.string.btn_sky_map),
                                tint = colors.primary
                            )
                        }
                    }

                    val obj = tappedObject
                    if (obj != null) {
                        InfoPopup(
                            title = obj.displayName,
                            onDismiss = { tappedObject = null }
                        ) {
                            Column {
                                if (!obj.commonName.isNullOrBlank() && !obj.isFakeCommonName) {
                                    Row {
                                        Text(
                                            stringResource(Res.string.label_designation),
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(obj.designation)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                                Row {
                                    Text(
                                        stringResource(Res.string.label_type),
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(obj.objectType)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row {
                                    Text(
                                        stringResource(Res.string.label_magnitude),
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    val mag =
                                        obj.magnitude?.let {
                                            String.format(
                                                "%.2f",
                                                it
                                            )
                                        }
                                            ?: stringResource(Res.string.value_not_available)
                                    Text(mag)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                if (!obj.constellation.isNullOrBlank()) {
                                    Row {
                                        Text(
                                            stringResource(Res.string.label_constellation),
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(obj.constellation)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                                Row {
                                    Text(
                                        stringResource(Res.string.ra_j2000_label),
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(formatRa(obj.ra))
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row {
                                    Text(
                                        stringResource(Res.string.dec_j2000_label),
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(formatDec(obj.dec))
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    IconBarItem(
                                        icon = Icons.Default.GpsFixed,
                                        label = stringResource(Res.string.btn_goto),
                                        onClick = {
                                            viewModel.setGoToTarget(
                                                obj.ra,
                                                obj.dec
                                            )
                                            tappedObject = null
                                        }
                                    )
                                }
                            }
                        }
                    }
                }


            }
        }

        @Composable
        fun StatsContent() {
            var showFullStats by remember { mutableStateOf(false) }

            CollapsedStatsDisplay(stats = viewModel.engineStats) {
                showFullStats = true
            }

            if (showFullStats) {
                InfoPopup(
                    title = stringResource(Res.string.title_engine_info),
                    onDismiss = { showFullStats = false }
                ) {
                    StatsDisplay(
                        stats = viewModel.engineStats,
                        detectedIpAddress = detectedIpAddress,
                        activeCameraSelection = activeCameraSelection,
                        onInfoClick = {
                            showFullStats = false
                            showTelescopeControlInfo = true
                        }
                    )
                }
            }

        }

        @Composable
        fun SlidersContent() {
            if (activeCameraSelection == "cedar") return

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                val initialBrightness = remember { windowController.getScreenBrightness() }
                var screenBrightness by remember { mutableFloatStateOf(initialBrightness) }
                var brightnessChanged by remember { mutableStateOf(false) }

                LaunchedEffect(screenBrightness, brightnessChanged) {
                    if (brightnessChanged) {
                        windowController.setScreenBrightness(screenBrightness)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(
                            Res.string.format_percent,
                            stringResource(Res.string.label_screen_brightness),
                            (screenBrightness * 100).toInt()
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                Slider(
                    value = screenBrightness,
                    onValueChange = {
                        brightnessChanged = true
                        screenBrightness = it
                    },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val formattedTime = when (lowPowerTimeoutSeconds) {
                        0 -> stringResource(Res.string.value_off)
                        in 1..59 -> stringResource(
                            Res.string.format_time_sec,
                            lowPowerTimeoutSeconds
                        )

                        else -> {
                            val mins = lowPowerTimeoutSeconds / 60.0
                            val minStr =
                                if (mins % 1.0 == 0.0) mins.toInt()
                                    .toString() else mins.toString()
                            stringResource(Res.string.format_time_min, minStr)
                        }
                    }
                    val labelText = stringResource(
                        Res.string.format_label_value_string,
                        stringResource(Res.string.label_low_power_timeout),
                        formattedTime
                    )
                    Text(
                        labelText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                Slider(
                    value = lowPowerTimeoutSeconds.toFloat(),
                    onValueChange = {
                        val newTimeout = it.toInt()
                        lowPowerTimeoutSeconds = newTimeout
                        settingsManager.lowPowerTimeoutSeconds = newTimeout
                    },
                    valueRange = 0f..300f,
                    steps = 9,
                    modifier = Modifier.fillMaxWidth()
                )

                val showZoom = activeCameraSelection == "internal" &&
                        settingsManager.cameraSettingsMode != "Auto"
                if (showZoom) {
                    val maxZoom = remember {
                        var mZ = 1f
                        try {
                            if (settingsManager.cameraSettingsMode == "Auto" || settingsManager.sensorMode == "Processed") {
                                val jsonArray =
                                    JSONArray(viewModel.getAvailableInternalCameras())
                                val targetId =
                                    if (settingsManager.cameraSettingsMode == "Auto" && !capabilities.isApple) "0" else settingsManager.internalCameraId
                                for (i in 0 until jsonArray.length()) {
                                    val obj = jsonArray.getJSONObject(i)
                                    if (obj.getString("id") == targetId) {
                                        mZ = if (obj.has("max_zoom")) obj.getDouble("max_zoom")
                                            .toFloat() else 1f
                                        break
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            // Fallback to 1f if camera characteristics JSON parsing fails
                        }
                        mZ
                    }

                    if (maxZoom >= 1.1f) {
                        val stepsCount =
                            (round((maxZoom - 1.0f) * 10f).toInt() - 1).coerceAtLeast(0)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(id = Res.string.label_zoom) + ": " + stringResource(
                                    id = Res.string.format_zoom,
                                    String.format(
                                        "%.1f",
                                        viewModel.currentZoom
                                    )
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
                                    contentDescription = stringResource(id = Res.string.cd_info),
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Slider(
                            value = viewModel.currentZoom.coerceIn(1.0f, maxZoom),
                            onValueChange = {
                                val stepped = round(it * 10.0f) / 10.0f
                                viewModel.setZoom(stepped)
                            },
                            valueRange = 1.0f..maxZoom,
                            steps = stepsCount,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                if (settingsManager.cameraSettingsMode != "Auto") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(
                                Res.string.format_percent,
                                stringResource(Res.string.label_star_sensitivity),
                                viewModel.starSensitivity.toInt()
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        IconButton(
                            onClick = { showStarSensitivityInfo = true },
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
                    Slider(
                        value = viewModel.starSensitivity,
                        onValueChange = {
                            viewModel.updateStarSensitivity(it)
                        },
                        valueRange = 1f..100f,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (activeCameraSelection == "internal") {
                        LaunchedEffect(viewModel.isEngineRunning) {
                            // Wait a moment for the camera to actually start and the JNI reference to be set
                            delay(1000)
                            val rawExp = viewModel.engine.getNativeCameraExposure()
                            Log.i("OliveUI", "Initial exposure from native: $rawExp")
                            mainScreenExposure = (rawExp / 1000000f).coerceIn(0.1f, 2.0f)
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(
                                    Res.string.format_exposure_seconds,
                                    stringResource(Res.string.stat_exposure),
                                    mainScreenExposure
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            IconButton(
                                onClick = { showExposureInfo = true },
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
                                stringResource(id = Res.string.label_stacking_frames) + ": ${viewModel.uvcStacking}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            IconButton(
                                onClick = { showStackingInfo = true },
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
        fun ActionButtons(isVertical: Boolean = false, modifier: Modifier = Modifier) {
            @Composable
            fun Buttons(itemModifier: Modifier = Modifier) {
                // Catalog
                IconBarItem(
                    modifier = itemModifier,
                    icon = Icons.Default.AutoAwesome,
                    label = stringResource(Res.string.btn_catalog),
                    onClick = { viewModel.showCatalog = true }
                )

                // Camera / Align
                IconBarItem(
                    modifier = itemModifier,
                    icon = Icons.Outlined.PhotoCamera,
                    label = stringResource(id = Res.string.btn_camera_align),
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
                                    .coerceIn(16667f, 5000000f)
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

                if (capabilities.supportsPip) {
                    // Picture in Picture
                    IconBarItem(
                        modifier = itemModifier,
                        icon = Icons.Default.PictureInPicture,
                        label = if (isEnteringPip) stringResource(id = Res.string.btn_entering_pip) else "Picture\nin Picture",
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
                }

                // Stop Engine
                IconBarItem(
                    modifier = itemModifier,
                    icon = Icons.Outlined.Cancel,
                    label = when {
                        isStopping -> stringResource(id = Res.string.btn_stopping)
                        isStarting -> stringResource(id = Res.string.btn_starting)
                        else -> stringResource(id = Res.string.btn_stop_engine)
                    },
                    enabled = !isStopping && !isStarting,
                    onClick = onToggleEngine
                )
            }

            if (isVertical) {
                Column(
                    modifier = modifier
                        .fillMaxHeight()
                        .width(76.dp)
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.SpaceEvenly,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Buttons()
                }
            } else {
                Row(
                    modifier = modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 0.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Top
                ) {
                    Buttons(itemModifier = Modifier.weight(1f))
                }
            }
        }


        @Composable
        fun CameraOrGuidance(isLowPowerMode: Boolean, modifier: Modifier = Modifier) {
            val ratio = if (isLowPowerMode) 16f / 9f else 1f
            Crossfade(
                targetState = isLowPowerMode,
                label = "cameraFade",
                modifier = modifier
            ) { lowPower ->
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier.aspectRatio(ratio),
                        contentAlignment = Alignment.Center
                    ) {
                        if (lowPower) {
                            GuidanceContent()
                        } else {
                            MainVideoContent()
                        }
                    }
                }
            }
        }

        @Composable
        fun ControlsArea(
            isLowPowerMode: Boolean,
            alphaValue: Float,
            scrollState: ScrollState
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = alphaValue }
                        .pointerInput(isLowPowerMode) {
                            if (isLowPowerMode) {
                                awaitPointerEventScope {
                                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                }
                            }
                        }
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScrollWithScrollbar(scrollState)
                    ) {
                        CompactInfoArea()
                        Spacer(Modifier.height(8.dp))
                        StatsContent()
                        Spacer(Modifier.height(8.dp))
                        SlidersContent()
                    }
                    Spacer(Modifier.height(8.dp))
                    ActionButtons()
                    Spacer(Modifier.height(8.dp))
                }

                if (isLowPowerMode) {
                    Box(Modifier.graphicsLayer { alpha = (1f - alphaValue).coerceIn(0f, 1f) }) {
                        DigitalClock()
                    }
                }
            }
        }

        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
        ) {
            val isWideLayout =
                maxWidth > maxHeight && maxWidth >= 480.dp

            if (isSkyMapExpanded) {
                if (isWideLayout) {
                    Row(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            SkyMapView(
                                viewModel = viewModel,
                                stats = stats,
                                isExpanded = true,
                                onToggleExpanded = { isSkyMapExpanded = false },
                                modifier = Modifier.fillMaxSize(),
                                planets = planets,
                                comets = comets,
                                asteroids = asteroids
                            )
                        }
                        ActionButtons(
                            isVertical = true,
                            modifier = Modifier.fillMaxHeight()
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            SkyMapView(
                                viewModel = viewModel,
                                stats = stats,
                                isExpanded = true,
                                onToggleExpanded = { isSkyMapExpanded = false },
                                modifier = Modifier.fillMaxSize(),
                                planets = planets,
                                comets = comets,
                                asteroids = asteroids
                            )
                        }
                        ActionButtons(
                            isVertical = false,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            } else if (isWideLayout) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Camera gets half width, sized natively to largest square possible
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(), contentAlignment = Alignment.Center
                    ) {
                        CameraOrGuidance(isLowPowerMode, Modifier.fillMaxSize())
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        ControlsArea(isLowPowerMode, controlsAlpha, rememberScrollState())
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 1. Camera Box (Takes max 50% height, wrapped tightly to its aspect ratio)
                    Box(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CameraOrGuidance(isLowPowerMode, Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.height(8.dp))
                    // 2. Controls Box (Takes the remaining 50%+ space)
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        ControlsArea(isLowPowerMode, controlsAlpha, rememberScrollState())
                    }
                }
            }
        }

        if (showTelescopeControlInfo) {
            InfoPopup(
                title = stringResource(id = Res.string.heading_telescope_control),
                onDismiss = { showTelescopeControlInfo = false }) {
                val infoRes = if (capabilities.isApple) {
                    Res.string.info_telescope_control_ios
                } else {
                    Res.string.info_telescope_control
                }
                Text(AnnotatedString.fromHtml(stringResource(id = infoRes)))
            }
        }

        if (showStarSensitivityInfo) {
            InfoPopup(
                title = stringResource(id = Res.string.label_star_sensitivity),
                onDismiss = { showStarSensitivityInfo = false }) {
                Text(AnnotatedString.fromHtml(stringResource(id = Res.string.info_star_sensitivity)))
            }
        }
        if (showZoomInfo) {
            InfoPopup(
                title = stringResource(id = Res.string.label_zoom),
                onDismiss = { showZoomInfo = false }) {
                Text(AnnotatedString.fromHtml(stringResource(id = Res.string.info_zoom)))
            }
        }
        if (showExposureInfo) {
            InfoPopup(
                title = stringResource(id = Res.string.label_exposure),
                onDismiss = { showExposureInfo = false }) {
                Text(AnnotatedString.fromHtml(stringResource(id = Res.string.info_exposure)))
            }
        }
        if (showStackingInfo) {
            InfoPopup(
                title = stringResource(id = Res.string.label_stacking_frames),
                onDismiss = { showStackingInfo = false }) {
                Text(AnnotatedString.fromHtml(stringResource(id = Res.string.info_stacking)))
            }
        }

        if (showCatalog) {
            SkyCatalogDialog(
                viewModel = viewModel,
                onDismiss = { viewModel.showCatalog = false },
                onGoTo = { ra, dec, epoch ->
                    viewModel.setGoToTarget(ra, dec, epoch)
                    viewModel.showCatalog = false
                },
                onCenter = if (isSkyMapExpanded) { ra, dec ->
                    viewModel.skyMapCenterTarget = Pair(ra, dec)
                    viewModel.showCatalog = false
                } else null
            )
        }
    }
}

@Composable
fun DigitalClock() {
    val is24Hour = is24HourFormat()
    var timeString by remember { mutableStateOf("") }
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

@Composable
fun CollapsedStatsDisplay(stats: EngineStats, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val isGoodSolve =
                stats.solvedCrop != "Failed" && !stats.isImuEstimate && stats.solvedCrop != null
            val primaryColor = MaterialTheme.colorScheme.primary

            @Composable
            fun StatCol(top: @Composable () -> Unit, bottom: String) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    top()
                    Spacer(modifier = Modifier.height(2.dp))
                    val baseStyle =
                        LocalTextStyle.current.merge(MaterialTheme.typography.labelMedium)
                    BasicText(
                        text = bottom,
                        style = baseStyle.copy(
                            color = primaryColor,
                            textAlign = TextAlign.Center
                        ),
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(
                            minFontSize = 4.sp,
                            maxFontSize = baseStyle.fontSize
                        )
                    )
                }
            }

            // 1. Status
            StatCol(
                top = {
                    Icon(
                        if (stats.status == "Calibrating" || stats.status == "Calibrating Focus") Icons.Outlined.Loop
                        else if (isGoodSolve) Icons.Outlined.Check
                        else Icons.Outlined.ErrorOutline,
                        contentDescription = stringResource(Res.string.cd_solve_status),
                        tint = primaryColor,
                        modifier = Modifier.size(20.dp)
                    )
                },
                bottom = " "
            )

            // 2. Stars
            StatCol(
                top = {
                    Icon(
                        Icons.Outlined.StarOutline,
                        contentDescription = stringResource(Res.string.cd_stars),
                        modifier = Modifier.size(20.dp),
                        tint = primaryColor
                    )
                },
                bottom = "${stats.centroidCount}"
            )

            // 3. RA
            StatCol(
                top = {
                    Text(
                        stringResource(Res.string.stat_ra),
                        fontWeight = FontWeight.Bold,
                        color = primaryColor,
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                bottom = stats.ra?.let { String.format("%.1f", it) } ?: "-"
            )

            // 4. Dec
            StatCol(
                top = {
                    Text(
                        stringResource(Res.string.stat_dec),
                        fontWeight = FontWeight.Bold,
                        color = primaryColor,
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                bottom = stats.dec?.let { String.format("%.1f", it) } ?: "-"
            )

            // 5. Exposure
            StatCol(
                top = {
                    Icon(
                        Icons.Outlined.PhotoCamera,
                        contentDescription = stringResource(Res.string.stat_exposure),
                        modifier = Modifier.size(20.dp),
                        tint = primaryColor
                    )
                },
                bottom = "${round(stats.exposureMs).toInt()}"
            )

            // 6. Pipeline
            val pipelineTotalMs = stats.postProcessMs + stats.extractMs + stats.solveMs
            val pipelineStr = if (pipelineTotalMs < 100.0) {
                stringResource(Res.string.format_pipeline_ms, round(pipelineTotalMs).toInt())
            } else {
                stringResource(Res.string.format_pipeline_s, pipelineTotalMs / 1000f)
            }
            StatCol(
                top = {
                    Icon(
                        Icons.Outlined.HourglassEmpty,
                        contentDescription = stringResource(Res.string.cd_pipeline_time),
                        modifier = Modifier.size(20.dp),
                        tint = primaryColor
                    )
                },
                bottom = pipelineStr
            )
        }
    }
}

@Composable
fun StatsDisplay(
    stats: EngineStats,
    detectedIpAddress: String,
    activeCameraSelection: String,
    onInfoClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row {
            Text(stringResource(id = Res.string.stat_status), fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                when (stats.status) {
                    "Solving" -> stringResource(id = Res.string.stat_solving)
                    "Slewing" -> stringResource(id = Res.string.stat_slewing)
                    "Idle" -> stringResource(id = Res.string.stat_idle)
                    "Camera Mode" -> stringResource(id = Res.string.btn_camera_mode)
                    "Calibrating Focus" -> stringResource(id = Res.string.stat_calibrating_focus)
                    else -> stats.status
                }
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row {
            Text(stringResource(id = Res.string.stat_star_count), fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(4.dp))
            Text("${stats.centroidCount}")
        }
        Spacer(modifier = Modifier.height(8.dp))

        Row {
            Text(stringResource(id = Res.string.ra_j2000_label), fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(4.dp))
            Text(stats.ra?.let { formatRa(it) } ?: "-")
        }
        Spacer(modifier = Modifier.height(4.dp))

        Row {
            Text(stringResource(id = Res.string.dec_j2000_label), fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(4.dp))
            Text(stats.dec?.let { formatDec(it) } ?: "-")
        }
        Spacer(modifier = Modifier.height(4.dp))

        stats.fov?.let { fovVal ->
            Row {
                Text(stringResource(id = Res.string.stat_fov), fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    stringResource(
                        id = Res.string.format_degree,
                        String.format("%.2f", fovVal)
                    )
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        Row {
            Text(stringResource(id = Res.string.stat_exposure), fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                stringResource(
                    id = Res.string.format_ms,
                    String.format("%.2f", stats.exposureMs)
                )
            )
        }
        Spacer(modifier = Modifier.height(4.dp))

        Row {
            Text(stringResource(id = Res.string.stat_post_process), fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                stringResource(
                    id = Res.string.format_ms,
                    String.format("%.1f", stats.postProcessMs)
                )
            )
        }
        Spacer(modifier = Modifier.height(4.dp))

        Row {
            Text(stringResource(id = Res.string.stat_extraction), fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                stringResource(
                    id = Res.string.format_ms,
                    String.format("%.1f", stats.extractMs)
                )
            )
        }
        Spacer(modifier = Modifier.height(4.dp))

        Row {
            Text(stringResource(id = Res.string.stat_solve), fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                stringResource(
                    id = Res.string.format_ms,
                    String.format("%.1f", stats.solveMs)
                )
            )
        }
        Spacer(modifier = Modifier.height(4.dp))

        Row {
            Text(stringResource(id = Res.string.stat_solve_rate), fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                stringResource(
                    id = Res.string.format_hz,
                    String.format("%.1f", stats.solveRateHz)
                )
            )
        }
        Spacer(modifier = Modifier.height(4.dp))

        Row {
            Text(stringResource(id = Res.string.stat_last_solve), fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(4.dp))
            val cropString = when (stats.solvedCrop) {
                "Center2x", "Center" -> stringResource(id = Res.string.crop_center_2x)
                "Center2xHighSens", "CenterHighSens" -> stringResource(id = Res.string.crop_center_2x_hs)
                "Center1_5x", "CenterWide" -> stringResource(id = Res.string.crop_center_1_5x)
                "Center1_5xHighSens", "CenterWideHighSens" -> stringResource(id = Res.string.crop_center_1_5x_hs)
                "Top" -> stringResource(id = Res.string.crop_top)
                "Bottom" -> stringResource(id = Res.string.crop_bottom)
                "Left" -> stringResource(id = Res.string.crop_left)
                "Right" -> stringResource(id = Res.string.crop_right)
                "Full" -> stringResource(id = Res.string.crop_full)
                "Failed" -> stringResource(id = Res.string.crop_failed)

                "TopHighSens" -> stringResource(
                    id = Res.string.format_high_sens,
                    stringResource(id = Res.string.crop_top)
                )

                "BottomHighSens" -> stringResource(
                    id = Res.string.format_high_sens,
                    stringResource(id = Res.string.crop_bottom)
                )

                "LeftHighSens" -> stringResource(
                    id = Res.string.format_high_sens,
                    stringResource(id = Res.string.crop_left)
                )

                "RightHighSens" -> stringResource(
                    id = Res.string.format_high_sens,
                    stringResource(id = Res.string.crop_right)
                )

                "FullHighSens" -> stringResource(
                    id = Res.string.format_high_sens,
                    stringResource(id = Res.string.crop_full)
                )

                else -> stats.solvedCrop ?: "-"
            }
            Text(cropString)
        }

        if (activeCameraSelection != "cedar") {
            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.stat_ip_address), fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(4.dp))
                Text(detectedIpAddress)
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onInfoClick,
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
        }
    }
}

