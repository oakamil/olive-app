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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin.Companion.Round
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.tan

private data class PlacedScreenItem(
    val obj: CatalogObject,
    val screenX: Float,
    val screenY: Float,
    val labelRect: Rect?
)

private data class VisibleCandidate(
    val obj: CatalogObject,
    val canvasX: Float,
    val canvasY: Float,
    val screenX: Float,
    val screenY: Float,
    val isTarget: Boolean
)

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
            activity?.window?.attributes = activity.window?.attributes?.apply {
                preferredRefreshRate = 30f
            }
        } else {
            activity?.window?.attributes = activity.window?.attributes?.apply {
                preferredRefreshRate = 0f
            }
        }
        onDispose {
            activity?.window?.attributes = activity.window?.attributes?.apply {
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
                        settingsManager.motionSensorMode != 0,
                        isCompactLayout = false
                    )
                }
            }
        }

        @Composable
        fun MainVideoContent() {
            var showSyncDialog by remember { mutableStateOf(false) }
            val stats = viewModel.engineStats

            if (showSyncDialog) {
                AlertDialog(
                    onDismissRequest = { showSyncDialog = false },
                    title = { Text(stringResource(R.string.title_confirm_alignment)) },
                    text = { Text(stringResource(R.string.msg_confirm_alignment)) },
                    confirmButton = {
                        Button(onClick = {
                            showSyncDialog = false
                            viewModel.syncTelescope(stats.targetRa, stats.targetDec)
                        }) {
                            Text(stringResource(R.string.btn_confirm))
                        }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = { showSyncDialog = false }) {
                            Text(stringResource(id = R.string.btn_cancel))
                        }
                    }
                )
            }

            val frame = viewModel.latestFrame
            val roll = stats.roll ?: 0.0 // offsetAngle is for the guidance arrow, not image roll!
            var lastValidUiRotation by remember { mutableFloatStateOf(roll.toFloat()) }
            var uiRotation = lastValidUiRotation
            val lat = viewModel.currentLat
            val lon = viewModel.currentLon
            val isPositionStale = stats.solvedCrop == "Failed" && !stats.isImuEstimate

            if (!isPositionStale && stats.ra != null && stats.dec != null && lat != null && lon != null &&
                lat != MainViewModel.INVALID_COORD && lon != MainViewModel.INVALID_COORD
            ) {
                val lst =
                    AstrometryUtils.calculateLST(lon, System.currentTimeMillis())
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

            var visibleDsos by remember { mutableStateOf<List<CatalogObject>>(emptyList()) }

            val fovVal = stats.fov ?: 15.0
            val stepSize = maxOf(0.1, fovVal * 0.2) // Trigger update every 20% of FOV
            val raGridKey = ra?.let { floor(it / stepSize) }
            val decGridKey = dec?.let { floor(it / stepSize) }
            val fovGridKey =
                floor(fovVal / 5.0) // Only re-trigger on major zoom changes

            LaunchedEffect(raGridKey, decGridKey, fovGridKey) {
                if (ra != null && dec != null) {
                    withContext(Dispatchers.IO) {
                        // Screen corner is at ~0.707x FOV from center.
                        // 0.9x FOV gives us a 0.2x buffer matching our step size.
                        val searchRadius = fovVal * 0.9
                        val dsos = viewModel.catalogDb.searchObjects(
                            targetRa = ra,
                            targetDec = dec,
                            maxDistanceDegrees = searchRadius,
                            visibilityFilter = "Any",
                            sortBy = "brightness",
                            limit = 250
                        )
                        visibleDsos = dsos
                    }
                }
            }

            val targetLabelFormat = stringResource(R.string.target_label_format)
            val allObjects = (planets + comets + asteroids + visibleDsos).toMutableList()
            val targetRaVal = stats.targetRa
            val targetDecVal = stats.targetDec
            if (targetRaVal != null && targetDecVal != null) {
                val hasTarget = allObjects.any {
                    AstrometryUtils.angularDistance(it.ra, it.dec, targetRaVal, targetDecVal) < 0.05
                }
                if (!hasTarget) {
                    val targetName = targetObjName ?: String.format(
                        Locale.US,
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
                    when {
                        isTarget -> -1
                        obj.catalogLabel in listOf(
                            "Planet",
                            "Solar System"
                        ) || obj.objectType in listOf("Planet", "Comet", "Asteroid") -> 0

                        !obj.commonName.isNullOrBlank() && !obj.isFakeCommonName -> 1
                        obj.catalogLabel in listOf(
                            "M",
                            "C",
                            "Col",
                            "NGC",
                            "IC",
                            "B",
                            "Sh2",
                            "Abl",
                            "Arp",
                            "H"
                        ) -> 1

                        obj.broadCategory != "Star" -> 1
                        obj.catalogLabel == "Str" -> 2
                        else -> 3
                    }
                }.thenBy { it.magnitude ?: 99.0 }
            )

            Column(modifier = Modifier.fillMaxWidth()) {
                // 2. Camera Feed
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clipToBounds()
                ) {
                    if (frame != null) {
                        Image(
                            bitmap = frame,
                            contentDescription = stringResource(id = R.string.cd_live_preview),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .scale(1.414f)
                                .graphicsLayer { rotationZ = uiRotation }
                        )
                    }

                    if (frame != null) {
                        val textMeasurer = rememberTextMeasurer()
                        val primaryColor = MaterialTheme.colorScheme.primary

                        val currentUiRotation by rememberUpdatedState(
                            uiRotation
                        )
                        val placedScreenItems =
                            remember { mutableListOf<PlacedScreenItem>() }

                        val alignTargetPixel = remember(
                            stats.targetPixelX,
                            stats.targetPixelY,
                            settingsManager.getString("target_pixel"),
                            frame.width,
                            frame.height
                        ) {
                            if (stats.targetPixelX != null && stats.targetPixelY != null) {
                                Pair(stats.targetPixelX, stats.targetPixelY)
                            } else {
                                val alignPixelStr = settingsManager.getString("target_pixel")
                                if (alignPixelStr != null) {
                                    val parts = alignPixelStr.split(",")
                                    if (parts.size == 2) {
                                        val px = parts[0].trim().toDoubleOrNull()
                                        val py = parts[1].trim().toDoubleOrNull()
                                        if (px != null && py != null) Pair(px, py) else null
                                    } else null
                                } else null
                            }
                        }

                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .scale(1.414f)
                                .graphicsLayer { rotationZ = uiRotation }
                                .pointerInput(Unit) {
                                    detectTapGestures { tapOffset ->
                                        val cx = size.width / 2f
                                        val cy = size.height / 2f
                                        val rad = Math.toRadians(currentUiRotation.toDouble())
                                        val cosRot = cos(rad).toFloat()
                                        val sinRot = sin(rad).toFloat()

                                        val tsx =
                                            cx + (tapOffset.x - cx) * cosRot - (tapOffset.y - cy) * sinRot
                                        val tsy =
                                            cy + (tapOffset.x - cx) * sinRot + (tapOffset.y - cy) * cosRot
                                        val tapScreen = Offset(tsx, tsy)

                                        var closest: CatalogObject? = null
                                        var minDist = 30f

                                        for (item in placedScreenItems) {
                                            val hitText = item.labelRect?.inflate(12f)
                                                ?.contains(tapScreen) == true
                                            if (hitText) {
                                                closest = item.obj
                                                break
                                            }
                                            val dist = hypot(item.screenX - tsx, item.screenY - tsy)
                                            if (dist < minDist) {
                                                minDist = dist
                                                closest = item.obj
                                            }
                                        }

                                        if (closest != null) {
                                            tappedObject = closest
                                        }
                                    }
                                }
                        ) {
                            placedScreenItems.clear()
                            val placedLabelRects = mutableListOf<Rect>()
                            val placedReticleRects = mutableListOf<Rect>()

                            val visibleMinX = (size.width - size.width / 1.414f) / 2f
                            val visibleMaxX = size.width - visibleMinX
                            val visibleMinY = (size.height - size.height / 1.414f) / 2f
                            val visibleMaxY = size.height - visibleMinY
                            val margin = visibleMinX + 5f
                            val maxBoundX = size.width - margin
                            val maxBoundY = size.height - margin

                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val rad = Math.toRadians(uiRotation.toDouble())
                            val cosRot = cos(rad).toFloat()
                            val sinRot = sin(rad).toFloat()

                            fun reticleZone(sx: Float, sy: Float, radius: Float = 16f) =
                                Rect(sx - radius, sy - radius, sx + radius, sy + radius)

                            // 1. Render boresight at the alignment pixel in DarkRed (large "crop free" style)
                            val bW = frame.width.toFloat()
                            val bH = frame.height.toFloat()
                            val targetPxX = (alignTargetPixel?.first ?: (bW / 2.0)).toFloat()
                            val targetPxY = (alignTargetPixel?.second ?: (bH / 2.0)).toFloat()
                            val fitPxScale = maxOf(size.width / bW, size.height / bH)
                            val boresightCanvasX =
                                size.width / 2f + (targetPxX - bW / 2f) * fitPxScale
                            val boresightCanvasY =
                                size.height / 2f + (targetPxY - bH / 2f) * fitPxScale

                            // 2. Pre-filter and deduplicate celestial candidates visible in FOV (if plate solved and not stale)
                            if (ra != null && dec != null && !isPositionStale) {
                                val visibleCandidates = mutableListOf<VisibleCandidate>()
                                for (obj in allObjects) {
                                    val isTarget = targetRaVal != null && targetDecVal != null &&
                                            hypot(
                                                obj.ra - targetRaVal,
                                                obj.dec - targetDecVal
                                            ) < 0.05

                                    val projRa =
                                        if (stats.isImuEstimate || stats.centerRa == null) ra else stats.centerRa
                                    val projDec =
                                        if (stats.isImuEstimate || stats.centerDec == null) dec else stats.centerDec

                                    val dist = AstrometryUtils.angularDistance(
                                        obj.ra, obj.dec, projRa, projDec
                                    )
                                    if (dist > fov * 1.5) {
                                        continue
                                    }

                                    val projCx =
                                        if (!stats.isImuEstimate && stats.centerRa != null) cx else boresightCanvasX
                                    val projCy =
                                        if (!stats.isImuEstimate && stats.centerDec != null) cy else boresightCanvasY

                                    val proj = AstrometryUtils.projectToScreen(
                                        obj.ra,
                                        obj.dec,
                                        projRa,
                                        projDec,
                                        roll,
                                        fov,
                                        size.width,
                                        size.height,
                                        frame.width,
                                        frame.height,
                                        projCx,
                                        projCy,
                                        stats.distortion
                                    ) ?: continue

                                    val (x, y) = proj
                                    if (x !in visibleMinX..visibleMaxX || y !in visibleMinY..visibleMaxY) {
                                        continue
                                    }

                                    val sx = cx + (x - cx) * cosRot - (y - cy) * sinRot
                                    val sy = cy + (x - cx) * sinRot + (y - cy) * cosRot

                                    // Deduplicate co-located catalog objects within 25px
                                    val isDuplicate = visibleCandidates.any {
                                        hypot(it.screenX - sx, it.screenY - sy) < 25f
                                    }
                                    if (!isDuplicate || isTarget) {
                                        visibleCandidates.add(
                                            VisibleCandidate(
                                                obj,
                                                x,
                                                y,
                                                sx,
                                                sy,
                                                isTarget
                                            )
                                        )
                                    }
                                }

                                var labelsPlaced = 0
                                val maxLabels = 20

                                for (cand in visibleCandidates) {
                                    if (labelsPlaced >= maxLabels && !cand.isTarget) break

                                    val candReticleBox = reticleZone(
                                        cand.screenX,
                                        cand.screenY,
                                        if (cand.isTarget) 20f else 16f
                                    )

                                    // Avoid drawing a reticle through any already-placed label (unless it's the target)
                                    if (!cand.isTarget && placedLabelRects.any {
                                            it.overlaps(
                                                candReticleBox
                                            )
                                        }) {
                                        continue
                                    }

                                    val labelText = cand.obj.displayName
                                    val textLayout = textMeasurer.measure(
                                        text = labelText,
                                        style = TextStyle(
                                            fontWeight = if (cand.isTarget) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = if (cand.isTarget) 15.sp else 13.sp
                                        )
                                    )
                                    val textW = textLayout.size.width.toFloat()
                                    val textH = textLayout.size.height.toFloat()

                                    // Candidate positions relative to the star in screen space: (dx, dy)
                                    // 24px offset for bold target reticle, 20px for regular reticle
                                    val offsetDist = if (cand.isTarget) 24f else 20f
                                    val candidateOffsets = listOf(
                                        Offset(offsetDist, -textH / 2f),          // Right
                                        Offset(-offsetDist - textW, -textH / 2f), // Left
                                        Offset(-textW / 2f, offsetDist),          // Bottom
                                        Offset(-textW / 2f, -offsetDist - textH)  // Top
                                    )

                                    var acceptedOffset: Offset? = null

                                    for (offset in candidateOffsets) {
                                        val left = cand.screenX + offset.x
                                        val top = cand.screenY + offset.y
                                        val right = left + textW
                                        val bottom = top + textH

                                        // Check screen bounds
                                        if (left < margin || right > maxBoundX || top < margin || bottom > maxBoundY) {
                                            continue
                                        }

                                        val screenTextRect = Rect(left, top, right, bottom)
                                        val paddedTextRect = screenTextRect.inflate(8f)

                                        // 1. Collision check: Must not overlap any already-placed label
                                        if (placedLabelRects.any { paddedTextRect.overlaps(it) }) {
                                            continue
                                        }

                                        // 2. Collision check: Must not overlap any already-placed reticle circle
                                        if (placedReticleRects.any { paddedTextRect.overlaps(it) }) {
                                            continue
                                        }

                                        // 3. Collision check: Must not overlap the reticle zone of ANY other visible candidate star
                                        var collidesWithOtherStar = false
                                        for (other in visibleCandidates) {
                                            if (other === cand) continue
                                            val otherReticle = reticleZone(
                                                other.screenX,
                                                other.screenY,
                                                if (other.isTarget) 20f else 16f
                                            )
                                            if (paddedTextRect.overlaps(otherReticle)) {
                                                collidesWithOtherStar = true
                                                break
                                            }
                                        }
                                        if (collidesWithOtherStar) {
                                            continue
                                        }

                                        acceptedOffset = offset
                                        break
                                    }

                                    val objColor = if (cand.isTarget) Color.Red else primaryColor

                                    if (acceptedOffset != null) {
                                        // 1. Draw reticle circle (bolded for target object)
                                        drawCircle(
                                            color = objColor,
                                            radius = if (cand.isTarget) 14f else 10f,
                                            center = Offset(cand.canvasX, cand.canvasY),
                                            style = Stroke(width = if (cand.isTarget) 3.5f else 2f)
                                        )

                                        // 2. Draw label text upright (bolded for target object)
                                        val localPos = Offset(
                                            cand.canvasX + acceptedOffset.x,
                                            cand.canvasY + acceptedOffset.y
                                        )
                                        rotate(
                                            degrees = -uiRotation,
                                            pivot = Offset(cand.canvasX, cand.canvasY)
                                        ) {
                                            drawText(
                                                textLayoutResult = textLayout,
                                                color = objColor,
                                                topLeft = localPos
                                            )
                                        }

                                        // 3. Register placed screen rectangles
                                        val screenLeft = cand.screenX + acceptedOffset.x
                                        val screenTop = cand.screenY + acceptedOffset.y
                                        val placedLabel = Rect(
                                            screenLeft,
                                            screenTop,
                                            screenLeft + textW,
                                            screenTop + textH
                                        )
                                        placedLabelRects.add(placedLabel)
                                        placedReticleRects.add(candReticleBox)

                                        placedScreenItems.add(
                                            PlacedScreenItem(
                                                cand.obj,
                                                cand.screenX,
                                                cand.screenY,
                                                placedLabel
                                            )
                                        )
                                        labelsPlaced++
                                    } else if (cand.isTarget) {
                                        // If target is in FOV but label couldn't fit at edge of screen,
                                        // still guarantee the target reticle is drawn bold and tappable!
                                        drawCircle(
                                            color = objColor,
                                            radius = 14f,
                                            center = Offset(cand.canvasX, cand.canvasY),
                                            style = Stroke(width = 3.5f)
                                        )
                                        placedReticleRects.add(candReticleBox)
                                        placedScreenItems.add(
                                            PlacedScreenItem(
                                                cand.obj,
                                                cand.screenX,
                                                cand.screenY,
                                                null
                                            )
                                        )
                                    }
                                }
                            }
                            rotate(
                                degrees = -uiRotation,
                                pivot = Offset(boresightCanvasX, boresightCanvasY)
                            ) {
                                val s = 32f  // Half-size for 64x64px box
                                val arm = 14f // Corner arm length
                                val stroke = 3f

                                // ┌ Top-Left
                                drawLine(
                                    DarkRed,
                                    Offset(boresightCanvasX - s, boresightCanvasY - s),
                                    Offset(boresightCanvasX - s + arm, boresightCanvasY - s),
                                    strokeWidth = stroke,
                                    cap = StrokeCap.Round
                                )
                                drawLine(
                                    DarkRed,
                                    Offset(boresightCanvasX - s, boresightCanvasY - s),
                                    Offset(boresightCanvasX - s, boresightCanvasY - s + arm),
                                    strokeWidth = stroke,
                                    cap = StrokeCap.Round
                                )

                                // ┐ Top-Right
                                drawLine(
                                    DarkRed,
                                    Offset(boresightCanvasX + s, boresightCanvasY - s),
                                    Offset(boresightCanvasX + s - arm, boresightCanvasY - s),
                                    strokeWidth = stroke,
                                    cap = StrokeCap.Round
                                )
                                drawLine(
                                    DarkRed,
                                    Offset(boresightCanvasX + s, boresightCanvasY - s),
                                    Offset(boresightCanvasX + s, boresightCanvasY - s + arm),
                                    strokeWidth = stroke,
                                    cap = StrokeCap.Round
                                )

                                // └ Bottom-Left
                                drawLine(
                                    DarkRed,
                                    Offset(boresightCanvasX - s, boresightCanvasY + s),
                                    Offset(boresightCanvasX - s + arm, boresightCanvasY + s),
                                    strokeWidth = stroke,
                                    cap = StrokeCap.Round
                                )
                                drawLine(
                                    DarkRed,
                                    Offset(boresightCanvasX - s, boresightCanvasY + s),
                                    Offset(boresightCanvasX - s, boresightCanvasY + s - arm),
                                    strokeWidth = stroke,
                                    cap = StrokeCap.Round
                                )

                                // ┘ Bottom-Right
                                drawLine(
                                    DarkRed,
                                    Offset(boresightCanvasX + s, boresightCanvasY + s),
                                    Offset(boresightCanvasX + s - arm, boresightCanvasY + s),
                                    strokeWidth = stroke,
                                    cap = StrokeCap.Round
                                )
                                drawLine(
                                    DarkRed,
                                    Offset(boresightCanvasX + s, boresightCanvasY + s),
                                    Offset(boresightCanvasX + s, boresightCanvasY + s - arm),
                                    strokeWidth = stroke,
                                    cap = StrokeCap.Round
                                )
                            }
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
                                            stringResource(R.string.label_designation),
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(obj.designation)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                                Row {
                                    Text(
                                        stringResource(R.string.label_type),
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(obj.objectType)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row {
                                    Text(
                                        stringResource(R.string.label_magnitude),
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    val mag = obj.magnitude?.toString()
                                        ?: stringResource(R.string.value_not_available)
                                    Text(mag)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                if (!obj.constellation.isNullOrBlank()) {
                                    Row {
                                        Text(
                                            stringResource(R.string.label_constellation),
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(obj.constellation)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                                Row {
                                    Text(
                                        stringResource(R.string.ra_j2000_label),
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(formatRa(obj.ra))
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row {
                                    Text(
                                        stringResource(R.string.dec_j2000_label),
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
                                        label = stringResource(R.string.btn_goto),
                                        singleLine = true,
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

                // 3. Info area below camera (fixed height to prevent layout shifts)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(116.dp)
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

                    Spacer(modifier = Modifier.height(4.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (stats.targetRa != null && stats.targetDec != null) {
                            val targetName = targetObjName ?: stringResource(
                                R.string.format_ra_dec,
                                String.format(Locale.US, "%.2f", stats.targetRa),
                                String.format(Locale.US, "%.2f", stats.targetDec)
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
                                    Text(
                                        text = targetName,
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
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

                                    if (isTargetVisible) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        IconButton(
                                            onClick = { showSyncDialog = true },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.FilterCenterFocus,
                                                contentDescription = stringResource(R.string.btn_align),
                                                tint = Color.Red
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
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
                        "${stringResource(R.string.stat_ip_address)}: $detectedIpAddress",
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
                    activity?.window?.attributes = activity.window?.attributes?.apply {
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
                    if (isLowPowerMode) {
                        GuidanceContent()
                    } else {
                        MainVideoContent()
                    }
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
                if (isLowPowerMode) {
                    GuidanceContent()
                } else {
                    MainVideoContent()
                }
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
