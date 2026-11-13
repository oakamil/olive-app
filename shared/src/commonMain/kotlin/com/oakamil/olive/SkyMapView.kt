// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.round
import kotlin.time.Duration.Companion.milliseconds


// =========================================================================
// Sky Map Sizing & Typography Tuning Constants
// =========================================================================
object SkyMapTuning {
    // Catalog Icons & Labels
    val BASE_DSO_ICON_SIZE_DP =
        14.dp              // Base icon size at wide FOV (compact relative to text)
    const val BASE_DSO_LABEL_FONT_SIZE_SP = 13.5f  // Base catalog label font size

    // Zoom Scaling Tiers for Catalog Items
    const val DSO_SCALE_WIDE = 1.0f                // Wide FOV (>= 45°)
    const val DSO_SCALE_MID = 1.20f                // Constellation zoom (10° - 45°)
    const val DSO_SCALE_DEEP = 1.40f               // Deep telescopic zoom (< 10°)

    // Non-Catalog Sky Map Typography (Fixed)
    const val CONSTELLATION_FONT_SIZE_SP = 13f
    const val ZENITH_NADIR_FONT_SIZE_SP = 12.5f
    const val CARDINAL_FONT_SIZE_SP = 14.5f
    const val BADGE_FONT_SIZE_SP = 12.5f

    // Visual Controls
    const val BELOW_HORIZON_ALPHA = 0.85f
    const val MAX_DSO_LABELS = 25
}

@OptIn(FlowPreview::class)
@Composable
fun SkyMapView(
    viewModel: MainViewModel,
    stats: EngineStats,
    modifier: Modifier = Modifier,
    isStarting: Boolean = false,
    isStopping: Boolean = false,
    detectedIpAddress: String = ""
) {
    val rawLat = viewModel.currentLat
    val rawLon = viewModel.currentLon
    val windowController = LocalPlatformWindowController.current
    val sensorOrientation =
        if (viewModel.engineMode == MainViewModel.EngineMode.NATIVE) {
            windowController.getCameraSensorOrientation(viewModel.settingsManager.internalCameraId)
        } else {
            0
        }
    val hasValidLocation = viewModel.hasValidLocation
    val lat = if (hasValidLocation && rawLat != null) rawLat else 0.0
    val lon = if (hasValidLocation && rawLon != null) rawLon else 0.0

    val is24Hour = is24HourFormat()
    var currentTimeMs by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    var timeString by remember(is24Hour) {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        mutableStateOf(formatSystemTime(now, is24Hour))
    }
    LaunchedEffect(is24Hour) {
        while (isActive) {
            val nowInstant = Clock.System.now()
            val nowMs = nowInstant.toEpochMilliseconds()
            if (nowMs - currentTimeMs >= 10_000L) {
                currentTimeMs = nowMs
            }
            val now = nowInstant.toLocalDateTime(TimeZone.currentSystemDefault())
            timeString = formatSystemTime(now, is24Hour)
            delay(1000)
        }
    }
    val lst = remember(lon, currentTimeMs) {
        if (hasValidLocation) AstrometryUtils.calculateLST(lon, currentTimeMs) else 0.0
    }

    fun getAlignmentPointRaDec(): Pair<Double, Double>? {
        val cameraFrame = viewModel.latestFrame
        val camCenterRa = stats.centerRa ?: stats.ra
        val camCenterDec = stats.centerDec ?: stats.dec
        if (!stats.isImuEstimate && cameraFrame != null && stats.targetPixelX != null && stats.targetPixelY != null &&
            camCenterRa != null && camCenterDec != null
        ) {
            val baseFovRad = Math.toRadians(stats.fov ?: 8.0)
            val aspect = cameraFrame.width.toDouble() / cameraFrame.height.toDouble()
            val effectiveFovRad =
                if (aspect > 1.0) 2.0 * kotlin.math.atan(kotlin.math.tan(baseFovRad / 2.0) / aspect) else baseFovRad
            val camFov = Math.toDegrees(effectiveFovRad)
            val camRoll = stats.roll ?: 0.0

            return SkyMapProjection.unprojectCameraPixel(
                pixelX = stats.targetPixelX,
                pixelY = stats.targetPixelY,
                imageWidth = cameraFrame.width.toDouble(),
                imageHeight = cameraFrame.height.toDouble(),
                centerRa = camCenterRa,
                centerDec = camCenterDec,
                fovDeg = camFov,
                rollDeg = camRoll,
                imageAspect = aspect
            )
        }
        return if (stats.ra != null && stats.dec != null) Pair(stats.ra, stats.dec) else null
    }

    val initialAltAz = remember {
        val alignCoord = getAlignmentPointRaDec()
        if (alignCoord != null) {
            AstrometryUtils.raDecToAltAz(alignCoord.first, alignCoord.second, lat, lst)
        } else {
            Pair(viewModel.skyMapCenterAz ?: 180.0, viewModel.skyMapCenterAlt ?: 45.0)
        }
    }

    val isCameraLocked = viewModel.skyMapCameraLocked
    val isReversed = viewModel.reverseLayout

    LaunchedEffect(Unit) {
        if (viewModel.skyMapCenterAz == null) viewModel.skyMapCenterAz = initialAltAz.first
        if (viewModel.skyMapCenterAlt == null) viewModel.skyMapCenterAlt = initialAltAz.second
    }

    // Auto-center on alignment point when camera lock is active and solves are available
    LaunchedEffect(
        stats.ra,
        stats.dec,
        stats.centerRa,
        stats.centerDec,
        stats.fov,
        stats.roll,
        stats.targetPixelX,
        stats.targetPixelY,
        stats.isImuEstimate,
        viewModel.skyMapCameraLocked,
        lat,
        lst
    ) {
        if (viewModel.skyMapCameraLocked) {
            val alignCoord = getAlignmentPointRaDec()
            if (alignCoord != null) {
                val (az, alt) = AstrometryUtils.raDecToAltAz(
                    alignCoord.first,
                    alignCoord.second,
                    lat,
                    lst
                )
                val curAz = viewModel.skyMapCenterAz
                val curAlt = viewModel.skyMapCenterAlt
                val dAz =
                    if (curAz != null) kotlin.math.abs(((az - curAz) % 360.0 + 540.0) % 360.0 - 180.0) else 999.0
                val dAlt = if (curAlt != null) kotlin.math.abs(alt - curAlt) else 999.0
                if (dAz >= 0.005 || dAlt >= 0.005) {
                    viewModel.skyMapCenterAz = az
                    viewModel.skyMapCenterAlt = alt
                }
            }
        }
    }

    LaunchedEffect(viewModel.skyMapCenterTarget, lat, lst) {
        val target = viewModel.skyMapCenterTarget ?: return@LaunchedEffect
        val (az, alt) = AstrometryUtils.raDecToAltAz(target.first, target.second, lat, lst)
        viewModel.skyMapCenterAz = az
        viewModel.skyMapCenterAlt = alt
        viewModel.skyMapCameraLocked = false
        viewModel.skyMapCenterTarget = null
    }

    val colors = LocalOliveColors.current
    val cutout = WindowInsets.displayCutout.asPaddingValues()
    val safeTop = maxOf(8.dp, cutout.calculateTopPadding())
    val safeStart = maxOf(8.dp, cutout.calculateStartPadding(LocalLayoutDirection.current))
    val safeEnd = maxOf(8.dp, cutout.calculateEndPadding(LocalLayoutDirection.current))

    val textMeasurer = rememberTextMeasurer(cacheSize = 256)
    val catalogPainters = rememberCatalogPainters()

    // Solar system ephemeris: load asynchronously off the main thread and refresh on location/comet updates
    var solPlanets by remember { mutableStateOf<List<CatalogObject>>(emptyList()) }
    var solComets by remember { mutableStateOf<List<CatalogObject>>(emptyList()) }
    var solAsteroids by remember { mutableStateOf<List<CatalogObject>>(emptyList()) }
    LaunchedEffect(lat, lon, hasValidLocation, viewModel.cometsRefreshVersion) {
        if (hasValidLocation) {
            while (isActive) {
                withContext(Dispatchers.IO) {
                    solPlanets = viewModel.getPlanets()
                    solComets = viewModel.getComets()
                    solAsteroids = viewModel.getAsteroids()
                }
                delay(300_000)
            }
        }
    }

    // Dismiss popups on Back button
    var selectedObject by remember { mutableStateOf<CatalogObject?>(null) }
    var showFullStats by remember { mutableStateOf(false) }
    var showTelescopeControlInfo by remember { mutableStateOf(false) }
    var showTargetPopup by remember { mutableStateOf(false) }
    var showSyncDialog by remember { mutableStateOf(false) }

    var targetObjName by remember(
        stats.targetRa,
        stats.targetDec
    ) { mutableStateOf<String?>(null) }
    var targetObject by remember(
        stats.targetRa,
        stats.targetDec
    ) { mutableStateOf<CatalogObject?>(null) }

    LaunchedEffect(viewModel.isLowPowerMode) {
        if (viewModel.isLowPowerMode) {
            selectedObject = null
            showTargetPopup = false
            showFullStats = false
            showTelescopeControlInfo = false
            showSyncDialog = false
        }
    }

    LaunchedEffect(
        viewModel.isEngineRunning,
        stats.targetRa,
        stats.targetDec,
        solPlanets,
        solComets,
        solAsteroids
    ) {
        if (!viewModel.isEngineRunning) {
            showFullStats = false
            showTelescopeControlInfo = false
        }
        if (viewModel.isEngineRunning && stats.targetRa != null && stats.targetDec != null) {
            val targetRa = stats.targetRa
            val targetDec = stats.targetDec

            val solarSystemMatch = (solPlanets + solComets + solAsteroids).firstOrNull {
                AstrometryUtils.angularDistance(it.ra, it.dec, targetRa, targetDec) < 0.5
            }

            if (solarSystemMatch != null) {
                targetObjName = solarSystemMatch.displayName
                targetObject = solarSystemMatch
            } else {
                withContext(Dispatchers.IO) {
                    val obj = viewModel.catalogDb.searchObjects(
                        targetRa = targetRa,
                        targetDec = targetDec,
                        limit = 1,
                        sortBy = "distance"
                    ).firstOrNull()
                    if (obj != null && AstrometryUtils.angularDistance(
                            obj.ra,
                            obj.dec,
                            targetRa,
                            targetDec
                        ) < 0.5
                    ) {
                        targetObjName = obj.displayName
                        targetObject = obj
                    } else {
                        targetObjName = null
                        targetObject = null
                    }
                }
            }
        } else {
            targetObjName = null
            targetObject = null
            showTargetPopup = false
            showSyncDialog = false
        }
    }

    BackHandler(enabled = selectedObject != null || showTargetPopup || showFullStats || showTelescopeControlInfo || showSyncDialog) {
        when {
            showSyncDialog -> showSyncDialog = false
            showTelescopeControlInfo -> showTelescopeControlInfo = false
            showFullStats -> showFullStats = false
            showTargetPopup -> showTargetPopup = false
            selectedObject != null -> selectedObject = null
        }
    }

    // Pre-measured static text layouts to avoid allocations during drawing
    val zenithText = stringResource(Res.string.label_zenith)
    val nadirText = stringResource(Res.string.label_nadir)
    val zenithTextLayout = remember(textMeasurer, zenithText, colors) {
        textMeasurer.measure(
            text = zenithText,
            style = TextStyle(
                color = colors.secondary,
                fontSize = SkyMapTuning.ZENITH_NADIR_FONT_SIZE_SP.sp,
                fontWeight = FontWeight.SemiBold,
                shadow = Shadow.None,
                textDecoration = TextDecoration.None
            )
        )
    }
    val nadirTextLayout = remember(textMeasurer, nadirText, colors) {
        textMeasurer.measure(
            text = nadirText,
            style = TextStyle(
                color = colors.secondary,
                fontSize = SkyMapTuning.ZENITH_NADIR_FONT_SIZE_SP.sp,
                fontWeight = FontWeight.SemiBold,
                shadow = Shadow.None,
                textDecoration = TextDecoration.None
            )
        )
    }
    val cardinalLayouts = remember(textMeasurer, colors) {
        listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW").associateWith { dir ->
            textMeasurer.measure(
                dir,
                style = TextStyle(
                    color = colors.primary,
                    fontSize = SkyMapTuning.CARDINAL_FONT_SIZE_SP.sp,
                    fontWeight = FontWeight.Bold,
                    shadow = Shadow.None,
                    textDecoration = TextDecoration.None
                )
            )
        }
    }
    val constellationLayouts = remember(textMeasurer, colors) {
        ConstellationData.CONSTELLATIONS.associate { c ->
            c.name to textMeasurer.measure(
                text = c.name,
                style = TextStyle(
                    color = colors.secondary.copy(alpha = 0.85f),
                    fontSize = SkyMapTuning.CONSTELLATION_FONT_SIZE_SP.sp,
                    fontWeight = FontWeight.Medium,
                    shadow = Shadow.None,
                    textDecoration = TextDecoration.None
                )
            )
        }
    }

    // Tier 1 in-memory objects (Bright stars <= 5.0 mag & all Messier + bright showcase DSOs)
    var tier1Stars by remember { mutableStateOf<List<CatalogObject>>(emptyList()) }
    var tier1Dso by remember { mutableStateOf<List<CatalogObject>>(emptyList()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            tier1Stars = viewModel.catalogDb.searchObjects(
                starFilter = true,
                magLimit = 5.0,
                visibilityFilter = "Any",
                sortBy = "brightness",
                limit = 2000
            )
            val messier = viewModel.catalogDb.searchObjects(
                catalogFilter = "M",
                visibilityFilter = "Any",
                limit = 110
            )
            val brightDso = viewModel.catalogDb.searchObjects(
                starFilter = false,
                magLimit = 5.0,
                visibilityFilter = "Any",
                sortBy = "brightness",
                limit = 50
            )
            tier1Dso = (messier + brightDso).distinctBy { it.id }
        }
    }

    // Tier 2 dynamic background query for dense deep sky objects and stars when zoomed in
    var dynamicCatalogObjects by remember { mutableStateOf<List<CatalogObject>>(emptyList()) }
    var dynamicStars by remember { mutableStateOf<List<CatalogObject>>(emptyList()) }
    val currentTypeFilters = viewModel.catalogTypeFilters
    val currentMagLimit = viewModel.catalogMagLimit
    val isAllTypes =
        currentTypeFilters.isEmpty() || currentTypeFilters == DEFAULT_CATALOG_TYPE_FILTERS

    val currentLst by rememberUpdatedState(lst)

    val effectiveMagLimit by remember(currentMagLimit) {
        derivedStateOf {
            val currentFov = viewModel.skyMapFov
            when {
                currentFov > 60.0 -> minOf(5.0, currentMagLimit ?: 5.0)
                currentFov > 30.0 -> minOf(6.5, currentMagLimit ?: 6.5)
                currentFov > 15.0 -> minOf(8.0, currentMagLimit ?: 8.0)
                currentFov > 10.0 -> minOf(11.0, currentMagLimit ?: 11.0)
                currentFov > 5.0 -> minOf(13.0, currentMagLimit ?: 13.0)
                else -> currentMagLimit ?: 99.9
            }
        }
    }
    val isDeepFov by remember {
        derivedStateOf { viewModel.skyMapFov <= 15.0 }
    }

    val targetRa = stats.targetRa
    val targetDec = stats.targetDec

    // Pre-filter, sort, and partition candidates outside the Canvas draw loop to avoid allocations at 60fps
    val (visibleNonStarCandidates, visibleStarCandidates) = remember(
        solPlanets, solComets, solAsteroids,
        tier1Dso, tier1Stars, dynamicCatalogObjects, dynamicStars,
        currentTypeFilters, currentMagLimit, isAllTypes, effectiveMagLimit, isDeepFov,
        targetRa, targetDec
    ) {
        val nonStars = mutableListOf<CatalogObject>()
        val stars = mutableListOf<CatalogObject>()

        fun isCurrentTarget(obj: CatalogObject): Boolean {
            return targetRa != null && targetDec != null &&
                    AstrometryUtils.angularDistance(obj.ra, obj.dec, targetRa, targetDec) < 0.05
        }

        // 1. Solar System
        if (hasValidLocation) {
            val sunObj = AstrometryUtils.getSun(Clock.System.now().toEpochMilliseconds())
            if (isAllTypes || "Planet" in currentTypeFilters || "Sun" in currentTypeFilters) {
                nonStars.add(sunObj)
            }
        }
        nonStars.addAll(solPlanets.filter { p ->
            isCurrentTarget(p) || (
                    (isAllTypes || "Planet" in currentTypeFilters || p.objectType in currentTypeFilters) &&
                            (p.magnitude == null || p.magnitude <= effectiveMagLimit)
                    )
        })
        nonStars.addAll(solComets.filter { c ->
            isCurrentTarget(c) || (
                    (isAllTypes || "Comet" in currentTypeFilters || c.objectType in currentTypeFilters) &&
                            (c.magnitude != null && c.magnitude <= effectiveMagLimit)
                    )
        })
        nonStars.addAll(solAsteroids.filter { a ->
            isCurrentTarget(a) || (
                    (isAllTypes || "Asteroid" in currentTypeFilters || a.objectType in currentTypeFilters) &&
                            (a.magnitude != null && a.magnitude <= effectiveMagLimit)
                    )
        })

        // 2. DSOs: strictly non-star objects
        nonStars.addAll(
            (tier1Dso + dynamicCatalogObjects)
                .filter { obj ->
                    if (obj.objectType.contains("Star")) return@filter false
                    if (isCurrentTarget(obj)) return@filter true
                    val typeMatches =
                        isAllTypes || obj.objectType in currentTypeFilters || obj.broadCategory in currentTypeFilters
                    val magMatches = when {
                        obj.magnitude != null -> obj.magnitude <= effectiveMagLimit
                        else -> isDeepFov
                    }
                    typeMatches && magMatches
                }
                .distinctBy { it.id }
        )

        // 3. Stars & Multiple Stars: all star-types, including Messier multiple stars (M40)
        stars.addAll(
            (tier1Stars + dynamicStars + tier1Dso.filter { it.objectType.contains("Star") })
                .filter { obj ->
                    if (isCurrentTarget(obj)) return@filter true
                    val typeMatches =
                        isAllTypes || obj.objectType in currentTypeFilters || obj.broadCategory in currentTypeFilters
                    val magMatches = when {
                        obj.magnitude != null -> obj.magnitude <= effectiveMagLimit
                        else -> isDeepFov
                    }
                    typeMatches && magMatches
                }
                .distinctBy { it.id }
        )

        val candidateComparator = compareBy<CatalogObject> { getCatalogPriority(it) }
            .thenBy { it.magnitude ?: 99.0 }
        nonStars.sortWith(candidateComparator)
        stars.sortWith(candidateComparator)

        Pair(nonStars, stars)
    }

    val nonStarAltAz = remember(visibleNonStarCandidates, lat, lst) {
        val arr = DoubleArray(visibleNonStarCandidates.size * 2)
        for (i in visibleNonStarCandidates.indices) {
            val obj = visibleNonStarCandidates[i]
            val (az, alt) = AstrometryUtils.raDecToAltAz(obj.ra, obj.dec, lat, lst)
            arr[i * 2] = az
            arr[i * 2 + 1] = alt
        }
        arr
    }

    val starAltAz = remember(visibleStarCandidates, lat, lst) {
        val arr = DoubleArray(visibleStarCandidates.size * 2)
        for (i in visibleStarCandidates.indices) {
            val obj = visibleStarCandidates[i]
            val (az, alt) = AstrometryUtils.raDecToAltAz(obj.ra, obj.dec, lat, lst)
            arr[i * 2] = az
            arr[i * 2 + 1] = alt
        }
        arr
    }

    val constellationLineAltAz = remember(lat, lst) {
        val lines = ConstellationData.LINES
        val arr = DoubleArray(lines.size * 4)
        for (i in lines.indices) {
            val line = lines[i]
            val (az1, alt1) = AstrometryUtils.raDecToAltAz(line.ra1, line.dec1, lat, lst)
            val (az2, alt2) = AstrometryUtils.raDecToAltAz(line.ra2, line.dec2, lat, lst)
            val base = i * 4
            arr[base] = az1
            arr[base + 1] = alt1
            arr[base + 2] = az2
            arr[base + 3] = alt2
        }
        arr
    }

    val constellationCenterAltAz = remember(lat, lst) {
        val constells = ConstellationData.CONSTELLATIONS
        val arr = DoubleArray(constells.size * 2)
        for (i in constells.indices) {
            val c = constells[i]
            val (az, alt) = AstrometryUtils.raDecToAltAz(c.centerRa, c.centerDec, lat, lst)
            arr[i * 2] = az
            arr[i * 2 + 1] = alt
        }
        arr
    }

    val placedCatalogItems = remember { mutableListOf<PlacedCatalogItem>() }
    val placedCatalogIsAbove = remember { mutableListOf<Boolean>() }
    val drawnStars = remember { mutableListOf<CatalogObject>() }
    val drawnStarIsAbove = remember { mutableListOf<Boolean>() }
    val placedLabelRects = remember { mutableListOf<Rect>() }
    val placedReticleRects = remember { mutableListOf<Rect>() }
    val placedStarXs = remember { mutableListOf<Float>() }
    val placedStarYs = remember { mutableListOf<Float>() }
    val placedCatalogObjIds = remember { hashSetOf<Int>() }
    val catalogCandidates = remember { mutableListOf<Pair<CatalogObject, Offset>>() }
    val lastCatalogLayoutKey = remember { arrayOfNulls<Any>(1) }

    // Reusable Path and Array buffers to eliminate per-frame 60 FPS draw allocations
    val horizonPath = remember { Path() }
    val constellationPath = remember { Path() }
    val camBoxPath = remember { Path() }
    val horizonScreenXs = remember { FloatArray(SkyMapProjection.HORIZON_POINTS.size) }
    val horizonScreenYs = remember { FloatArray(SkyMapProjection.HORIZON_POINTS.size) }
    val horizonScreenValid = remember { BooleanArray(SkyMapProjection.HORIZON_POINTS.size) }

    // PathEffects pre-allocated
    val horizonDashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f) }
    val fovDashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f) }
    val targetDottedEffect = remember { PathEffect.dashPathEffect(floatArrayOf(3f, 9f), 0f) }

    val density = LocalDensity.current
    val strokeHorizon = remember(density, horizonDashEffect) {
        Stroke(
            width = with(density) { 1.5.dp.toPx() },
            pathEffect = horizonDashEffect
        )
    }
    val strokeFovBox = remember(density, fovDashEffect) {
        Stroke(
            width = with(density) { 2.dp.toPx() },
            pathEffect = fovDashEffect
        )
    }
    val strokeConstellation = remember(density) { Stroke(width = with(density) { 1.5.dp.toPx() }) }
    val strokeZenithCircle = remember(density) { Stroke(width = with(density) { 1.5.dp.toPx() }) }
    val strokeSelectionCircle = remember(density) { Stroke(width = with(density) { 2.dp.toPx() }) }

    val hitRadiusPx = with(LocalDensity.current) { 36.dp.toPx() }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val currentWidthPx by rememberUpdatedState(widthPx)
        val currentHeightPx by rememberUpdatedState(heightPx)

        LaunchedEffect(
            currentTypeFilters,
            currentMagLimit,
            isAllTypes,
            lat,
            lon,
            hasValidLocation
        ) {
            snapshotFlow {
                val az = viewModel.skyMapCenterAz ?: initialAltAz.first
                val alt = viewModel.skyMapCenterAlt ?: initialAltAz.second
                val currentFov = viewModel.skyMapFov
                val stepSize = maxOf(0.1, currentFov * 0.15)
                val azGrid = kotlin.math.floor(az / stepSize).toInt()
                val altGrid = kotlin.math.floor(alt / stepSize).toInt()
                val fovGrid = kotlin.math.floor(
                    kotlin.math.ln(currentFov.coerceAtLeast(0.5)) / kotlin.math.ln(1.15)
                ).toInt()
                Triple(azGrid, altGrid, fovGrid)
            }
                .debounce(30.milliseconds)
                .collectLatest {
                    val az = viewModel.skyMapCenterAz ?: initialAltAz.first
                    val alt = viewModel.skyMapCenterAlt ?: initialAltAz.second
                    val currentFov = viewModel.skyMapFov
                    if (currentFov < 60.0) {
                        val (ra, dec) = AstrometryUtils.altAzToRaDec(az, alt, lat, currentLst)
                        val stepSize = maxOf(0.1, currentFov * 0.15)
                        val cornerRadius = SkyMapProjection.cornerRadiusDeg(
                            currentFov,
                            currentWidthPx,
                            currentHeightPx
                        )
                        val searchRadius = (cornerRadius + stepSize).coerceIn(0.5, 45.0)
                        val queryMagLimit = when {
                            currentFov > 30.0 -> minOf(6.5, currentMagLimit ?: 6.5)
                            currentFov > 15.0 -> minOf(8.0, currentMagLimit ?: 8.0)
                            currentFov > 10.0 -> minOf(11.0, currentMagLimit ?: 11.0)
                            currentFov > 5.0 -> minOf(13.0, currentMagLimit ?: 13.0)
                            else -> currentMagLimit ?: 99.9
                        }
                        val (dsoObjects, stars) = withContext(Dispatchers.IO) {
                            val dso = viewModel.catalogDb.searchObjects(
                                targetRa = ra,
                                targetDec = dec,
                                maxDistanceDegrees = searchRadius,
                                magLimit = if (currentFov <= 5.0) currentMagLimit else queryMagLimit,
                                includeNullMagnitude = currentFov <= 15.0 && currentMagLimit == null,
                                typeFilters = if (isAllTypes) null else currentTypeFilters,
                                starFilter = false,
                                visibilityFilter = "Any",
                                sortBy = if (currentFov <= 15.0) "priority" else "brightness",
                                limit = 500
                            )
                            val st = viewModel.catalogDb.searchObjects(
                                targetRa = ra,
                                targetDec = dec,
                                maxDistanceDegrees = searchRadius,
                                magLimit = queryMagLimit,
                                includeNullMagnitude = currentFov <= 15.0 && currentMagLimit == null,
                                starFilter = true,
                                visibilityFilter = "Any",
                                sortBy = "brightness",
                                limit = 500
                            )
                            Pair(dso, st)
                        }
                        dynamicCatalogObjects = dsoObjects
                        dynamicStars = stars
                    } else {
                        dynamicCatalogObjects = emptyList()
                        dynamicStars = emptyList()
                    }
                }
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (zoom != 1f) {
                            viewModel.skyMapFov =
                                (viewModel.skyMapFov / zoom.toDouble()).coerceIn(0.5, 180.0)
                        }
                        if (pan != Offset.Zero) {
                            if (viewModel.skyMapCameraLocked && zoom !in 0.98f..1.02f) {
                                // Pinch-zoom touch noise shouldn't disengage camera lock
                            } else {
                                viewModel.skyMapCameraLocked = false
                                val curAz = viewModel.skyMapCenterAz ?: initialAltAz.first
                                val curAlt = viewModel.skyMapCenterAlt ?: initialAltAz.second
                                val proj = SkyMapProjection(
                                    centerAz = curAz,
                                    centerAlt = curAlt,
                                    fov = viewModel.skyMapFov,
                                    viewportWidth = currentWidthPx,
                                    viewportHeight = currentHeightPx
                                )
                                proj.panBy(pan.x, pan.y)
                                viewModel.skyMapCenterAz = proj.centerAz
                                viewModel.skyMapCenterAlt = proj.centerAlt
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { tapOffset ->
                        val tapped =
                            findTappedCatalogItem(placedCatalogItems, tapOffset, hitRadiusPx)
                                ?: findTappedStar(
                                    drawnStars,
                                    placedStarXs,
                                    placedStarYs,
                                    tapOffset,
                                    hitRadiusPx
                                )
                        if (tapped != null) {
                            selectedObject = tapped
                        }
                        // Tapping empty space does nothing (preserves selection until dismissed)
                    }
                }
        ) {
            val centerAz = viewModel.skyMapCenterAz ?: initialAltAz.first
            val centerAlt = viewModel.skyMapCenterAlt ?: initialAltAz.second
            val fov = viewModel.skyMapFov
            val projection = SkyMapProjection(
                centerAz = centerAz,
                centerAlt = centerAlt,
                fov = fov,
                viewportWidth = size.width,
                viewportHeight = size.height
            )

            placedLabelRects.clear()
            placedReticleRects.clear()

            // 1. Local Horizon Circle & Below-Horizon Shading (only if observer location is known)
            if (hasValidLocation) {
                val horizonPoints = SkyMapProjection.HORIZON_POINTS
                val n = horizonPoints.size
                var firstNullIndex = -1
                for (i in 0 until n) {
                    val hp = horizonPoints[i]
                    val pt = projection.toScreenOffset(hp.first, hp.second)
                    if (pt.isSpecified) {
                        horizonScreenXs[i] = pt.x
                        horizonScreenYs[i] = pt.y
                        horizonScreenValid[i] = true
                    } else {
                        horizonScreenValid[i] = false
                        if (firstNullIndex == -1) {
                            firstNullIndex = i
                        }
                    }
                }
                horizonPath.reset()

                if (firstNullIndex == -1) {
                    // Full horizon circle on forward hemisphere -> single closed loop
                    if (horizonScreenValid[0]) {
                        horizonPath.moveTo(horizonScreenXs[0], horizonScreenYs[0])
                        for (i in 1 until n) {
                            if (!horizonScreenValid[i]) continue
                            horizonPath.lineTo(horizonScreenXs[i], horizonScreenYs[i])
                        }
                        horizonPath.close()
                    }
                } else {
                    // Horizon crosses antipode region -> open arc(s) extending to infinity.
                    // Traverse circularly starting after the null gap to trace contiguous visible arcs.
                    // Never call close() on an open arc to prevent chords across screen.
                    var inSegment = false
                    for (i in 0 until n) {
                        val idx = (firstNullIndex + 1 + i) % n
                        if (horizonScreenValid[idx]) {
                            if (!inSegment) {
                                horizonPath.moveTo(horizonScreenXs[idx], horizonScreenYs[idx])
                                inSegment = true
                            } else {
                                horizonPath.lineTo(horizonScreenXs[idx], horizonScreenYs[idx])
                            }
                        } else {
                            inSegment = false
                        }
                    }
                }

                drawPath(
                    path = horizonPath,
                    color = colors.secondary,
                    style = strokeHorizon
                )

                // 2. Cardinal Points (N, E, S, W)
                val cardinals = SkyMapProjection.CARDINAL_POINTS
                for ((cardinal, coords) in cardinals) {
                    val pt = projection.toScreenOffset(coords.first, coords.second)
                    if (pt.isSpecified && pt.x in 0f..size.width && pt.y in 0f..size.height) {
                        val textLayout = cardinalLayouts[cardinal]
                        if (textLayout != null) {
                            val cLeft = pt.x - textLayout.size.width / 2f
                            val cTop = pt.y - textLayout.size.height / 2f
                            placedLabelRects.add(
                                Rect(
                                    cLeft - 4f,
                                    cTop - 4f,
                                    cLeft + textLayout.size.width + 4f,
                                    cTop + textLayout.size.height + 4f
                                )
                            )
                            drawText(
                                textLayoutResult = textLayout,
                                color = colors.primary,
                                topLeft = Offset(cLeft, cTop)
                            )
                        }
                    }
                }

                // 3. Zenith & Nadir (Simple crosshair + title-case label in secondary color)
                val (zenithAz, zenithAlt) = SkyMapProjection.getZenith()
                val zenithPt = projection.toScreenOffset(zenithAz, zenithAlt)
                if (zenithPt.isSpecified && zenithPt.x in -50f..size.width + 50f && zenithPt.y in -50f..size.height + 50f) {
                    val zArm = 10.dp.toPx()
                    val zX = zenithPt.x
                    val zY = zenithPt.y
                    val stroke = 1.5.dp.toPx()
                    drawCircle(
                        colors.secondary,
                        radius = 3.dp.toPx(),
                        center = Offset(zX, zY),
                        style = strokeZenithCircle
                    )
                    drawLine(
                        colors.secondary,
                        Offset(zX - zArm, zY),
                        Offset(zX + zArm, zY),
                        strokeWidth = stroke
                    )
                    drawLine(
                        colors.secondary,
                        Offset(zX, zY - zArm),
                        Offset(zX, zY + zArm),
                        strokeWidth = stroke
                    )
                    val zTop = zY - zenithTextLayout.size.height / 2f
                    val zLeft = zX + 8.dp.toPx()
                    drawText(
                        zenithTextLayout,
                        color = colors.secondary,
                        topLeft = Offset(zLeft, zTop)
                    )
                }

                val (nadirAz, nadirAlt) = SkyMapProjection.getNadir()
                val nadirPt = projection.toScreenOffset(nadirAz, nadirAlt)
                if (nadirPt.isSpecified && nadirPt.x in -50f..size.width + 50f && nadirPt.y in -50f..size.height + 50f) {
                    val nArm = 8.dp.toPx()
                    val nX = nadirPt.x
                    val nY = nadirPt.y
                    val stroke = 1.5.dp.toPx()
                    drawLine(
                        colors.secondary,
                        Offset(nX - nArm, nY),
                        Offset(nX + nArm, nY),
                        strokeWidth = stroke
                    )
                    drawLine(
                        colors.secondary,
                        Offset(nX, nY - nArm),
                        Offset(nX, nY + nArm),
                        strokeWidth = stroke
                    )
                    val nTop = nY - nadirTextLayout.size.height / 2f
                    val nLeft = nX + 6.dp.toPx()
                    drawText(
                        nadirTextLayout,
                        color = colors.secondary,
                        topLeft = Offset(nLeft, nTop)
                    )
                }
            }

            // 4. Batched Constellation Stick Figures (Single Path in DarkRed)
            constellationPath.reset()
            val maxLineLen = widthPx * 0.8f
            val maxLineLenSq = maxLineLen * maxLineLen
            val lineCount = ConstellationData.LINES.size
            for (i in 0 until lineCount) {
                val base = i * 4
                val p1 = projection.toScreenOffset(
                    constellationLineAltAz[base],
                    constellationLineAltAz[base + 1]
                )
                if (p1.isUnspecified) continue
                val p2 = projection.toScreenOffset(
                    constellationLineAltAz[base + 2],
                    constellationLineAltAz[base + 3]
                )
                if (p2.isUnspecified) continue

                // Check distance to avoid cross-screen lines if wrap occurs
                val dx = p1.x - p2.x
                val dy = p1.y - p2.y
                if (dx * dx + dy * dy < maxLineLenSq) {
                    constellationPath.moveTo(p1.x, p1.y)
                    constellationPath.lineTo(p2.x, p2.y)
                }
            }
            drawPath(
                path = constellationPath,
                color = colors.secondary,
                style = strokeConstellation
            )

            // 5. Constellation Names
            if (fov in 20.0..130.0) {
                val constells = ConstellationData.CONSTELLATIONS
                for (i in constells.indices) {
                    val constell = constells[i]
                    val pt = projection.toScreenOffset(
                        constellationCenterAltAz[i * 2],
                        constellationCenterAltAz[i * 2 + 1]
                    )
                    if (pt.isSpecified && pt.x in 0f..size.width && pt.y in 0f..size.height) {
                        val textLayout = constellationLayouts[constell.name]
                        if (textLayout != null) {
                            val cLeft = pt.x - textLayout.size.width / 2f
                            val cTop = pt.y - textLayout.size.height / 2f
                            placedLabelRects.add(
                                Rect(
                                    cLeft - 2f,
                                    cTop - 2f,
                                    cLeft + textLayout.size.width + 2f,
                                    cTop + textLayout.size.height + 2f
                                )
                            )
                            drawText(
                                textLayoutResult = textLayout,
                                color = colors.secondary.copy(alpha = 0.85f),
                                topLeft = Offset(cLeft, cTop)
                            )
                        }
                    }
                }
            }

            // 6. Camera FOV Box & Live Video Preview (rendered before stars/labels so overlays remain visible)
            val camRa = stats.ra
            val camDec = stats.dec
            val cameraFrame = viewModel.latestFrame

            val isMirrored = stats.isMirrored == true
            val isRotated90 = (sensorOrientation % 180 != 0)
            val mirrorScaleX = if (isMirrored && isRotated90) -1f else 1f
            val mirrorScaleY = if (isMirrored && !isRotated90) -1f else 1f

            val baseFovRad = Math.toRadians(stats.fov ?: 8.0)
            val aspect =
                cameraFrame?.let { it.width.toDouble() / it.height.toDouble() } ?: 1.333
            val effectiveFovRad = if (aspect > 1.0) {
                2.0 * kotlin.math.atan(kotlin.math.tan(baseFovRad / 2.0) / aspect)
            } else {
                baseFovRad
            }
            val camFov = Math.toDegrees(effectiveFovRad)
            val camRoll = stats.roll ?: 0.0

            val (camCenterRa, camCenterDec) = run {
                if (stats.isImuEstimate) {
                    if (stats.targetPixelX != null && stats.targetPixelY != null &&
                        camRa != null && camDec != null && cameraFrame != null
                    ) {
                        SkyMapProjection.unprojectCameraPixel(
                            pixelX = cameraFrame.width.toDouble() - stats.targetPixelX,
                            pixelY = cameraFrame.height.toDouble() - stats.targetPixelY,
                            imageWidth = cameraFrame.width.toDouble(),
                            imageHeight = cameraFrame.height.toDouble(),
                            centerRa = camRa,
                            centerDec = camDec,
                            fovDeg = camFov,
                            rollDeg = camRoll,
                            imageAspect = aspect
                        )
                    } else {
                        Pair(camRa, camDec)
                    }
                } else {
                    Pair(stats.centerRa ?: camRa, stats.centerDec ?: camDec)
                }
            }

            val dsoScale = when {
                fov < 10.0 -> SkyMapTuning.DSO_SCALE_DEEP
                fov < 45.0 -> SkyMapTuning.DSO_SCALE_MID
                else -> SkyMapTuning.DSO_SCALE_WIDE
            }
            val baseFontSize = SkyMapTuning.BASE_DSO_LABEL_FONT_SIZE_SP
            val iconSize = (SkyMapTuning.BASE_DSO_ICON_SIZE_DP * dsoScale).toPx()

            var boxPath: Path? = null
            var matrix: Matrix? = null
            var camBoxRect: Rect? = null
            var alignPt: Offset? = null

            if (viewModel.isEngineRunning && camCenterRa != null && camCenterDec != null) {
                val corners = SkyMapProjection.computeCameraFovCorners(
                    camCenterRa,
                    camCenterDec,
                    camFov,
                    camRoll,
                    imageAspect = aspect
                )
                val cornerPoints =
                    corners.mapNotNull { projection.toScreenRaDec(it.first, it.second, lat, lst) }
                if (cornerPoints.size == 4) {
                    val p0 = cornerPoints[0]
                    val p1 = cornerPoints[1]
                    val p2 = cornerPoints[2]
                    val p3 = cornerPoints[3]

                    val minX = minOf(p0.first, p1.first, p2.first, p3.first)
                    val maxX = maxOf(p0.first, p1.first, p2.first, p3.first)
                    val minY = minOf(p0.second, p1.second, p2.second, p3.second)
                    val maxY = maxOf(p0.second, p1.second, p2.second, p3.second)
                    camBoxRect = Rect(minX - 20f, minY - 20f, maxX + 20f, maxY + 20f)

                    camBoxPath.reset()
                    camBoxPath.moveTo(p0.first, p0.second)
                    camBoxPath.lineTo(p1.first, p1.second)
                    camBoxPath.lineTo(p2.first, p2.second)
                    camBoxPath.lineTo(p3.first, p3.second)
                    camBoxPath.close()
                    boxPath = camBoxPath

                    val mat = if (cameraFrame != null) {
                        SkyMapProjection.computeQuadHomography(
                            srcW = cameraFrame.width.toFloat(),
                            srcH = cameraFrame.height.toFloat(),
                            p0 = p0,
                            p1 = p1,
                            p2 = p2,
                            p3 = p3,
                            mirrorScaleX = mirrorScaleX,
                            mirrorScaleY = mirrorScaleY
                        )
                    } else null
                    matrix = mat

                    // Live Camera Video stretched to fit the camera bounding box exactly
                    if (mat != null && cameraFrame != null) {
                        clipPath(camBoxPath) {
                            withTransform({
                                transform(mat)
                            }) {
                                drawImage(
                                    image = cameraFrame,
                                    srcOffset = IntOffset.Zero,
                                    srcSize = IntSize(cameraFrame.width, cameraFrame.height),
                                    dstOffset = IntOffset.Zero,
                                    dstSize = IntSize(cameraFrame.width, cameraFrame.height)
                                )
                            }
                        }
                    }

                    // Dashed FOV border framing the camera view
                    val isEstimate = stats.isImuEstimate || stats.solvedCrop == "Failed"
                    val boxColor =
                        if (isEstimate) colors.secondary.copy(alpha = 0.7f) else colors.primary
                    drawPath(
                        path = camBoxPath,
                        color = boxColor,
                        style = strokeFovBox
                    )
                }
            }

            // Compute alignment reticle point and register in decrowding list
            if (boxPath != null) {
                alignPt = if (matrix != null && cameraFrame != null) {
                    val rawTargetPxX = (stats.targetPixelX ?: (cameraFrame.width / 2.0)).toFloat()
                    val rawTargetPxY = (stats.targetPixelY ?: (cameraFrame.height / 2.0)).toFloat()
                    matrix.map(Offset(rawTargetPxX, rawTargetPxY))
                } else if (camRa != null && camDec != null) {
                    val pt = projection.toScreenRaDecOffset(camRa, camDec, lat, lst)
                    if (pt.isSpecified) pt else null
                } else null

                if (alignPt != null) {
                    val s = iconSize / 2f
                    placedReticleRects.add(
                        Rect(
                            alignPt.x - s,
                            alignPt.y - s,
                            alignPt.x + s,
                            alignPt.y + s
                        )
                    )
                }
            }

            // 7. Stars & Catalog Objects Rendering (gated so stationary redraws reuse screen layout)
            val maxMag = (effectiveMagLimit + 0.5).coerceAtLeast(6.0)
            val camBoxActive =
                camBoxRect != null && matrix != null && cameraFrame != null && camCenterRa != null && camCenterDec != null
            val layoutKey = listOf(
                centerAz,
                centerAlt,
                fov,
                size.width,
                size.height,
                hasValidLocation,
                lat,
                lst,
                visibleNonStarCandidates,
                visibleStarCandidates,
                targetRa,
                targetDec,
                alignPt,
                camBoxActive,
                if (camBoxActive) camCenterRa else null,
                if (camBoxActive) camCenterDec else null,
                if (camBoxActive) camFov else null,
                if (camBoxActive) camRoll else null,
                if (camBoxActive) stats.distortion else null,
                if (camBoxActive) mirrorScaleX else null,
                if (camBoxActive) mirrorScaleY else null,
                if (camBoxActive) cameraFrame.width else null,
                if (camBoxActive) cameraFrame.height else null
            )

            if (layoutKey != lastCatalogLayoutKey[0]) {
                lastCatalogLayoutKey[0] = layoutKey
                placedCatalogItems.clear()
                placedCatalogIsAbove.clear()
                drawnStars.clear()
                drawnStarIsAbove.clear()
                placedStarXs.clear()
                placedStarYs.clear()
                placedCatalogObjIds.clear()
                catalogCandidates.clear()

                // Coordinate mapper for catalog objects (applying optical distortion correction inside camera box)
                fun projectCandidate(
                    az: Double,
                    alt: Double,
                    objRa: Double,
                    objDec: Double
                ): Offset {
                    val stdOffset = projection.toScreenOffset(az, alt)
                    if (stdOffset.isUnspecified) return Offset.Unspecified
                    if (camBoxRect != null && camBoxRect.contains(stdOffset) &&
                        matrix != null && cameraFrame != null && camCenterRa != null && camCenterDec != null
                    ) {
                        val camPix = AstrometryUtils.projectToScreen(
                            ra = objRa,
                            dec = objDec,
                            centerRa = camCenterRa,
                            centerDec = camCenterDec,
                            roll = camRoll,
                            fov = camFov,
                            screenWidth = cameraFrame.width.toFloat(),
                            screenHeight = cameraFrame.height.toFloat(),
                            imageWidth = cameraFrame.width,
                            imageHeight = cameraFrame.height,
                            centerPixelX = cameraFrame.width / 2f,
                            centerPixelY = cameraFrame.height / 2f,
                            distortion = stats.distortion
                        )
                        if (camPix != null && camPix.first in 0f..cameraFrame.width.toFloat() && camPix.second in 0f..cameraFrame.height.toFloat()) {
                            val px =
                                if (mirrorScaleX < 0f) cameraFrame.width.toFloat() - camPix.first else camPix.first
                            val py =
                                if (mirrorScaleY < 0f) cameraFrame.height.toFloat() - camPix.second else camPix.second
                            return matrix.map(Offset(px, py))
                        }
                    }
                    return stdOffset
                }

                val starLabelMagLimit = when {
                    fov <= 15.0 -> effectiveMagLimit
                    fov <= 25.0 -> 5.0
                    fov <= 50.0 -> 4.0
                    else -> 3.0
                }

                // Pass 1: Catalog Objects with labels (DSOs, Planets, Comets, Asteroids + Eligible Stars)
                for (i in 0 until visibleNonStarCandidates.size) {
                    val obj = visibleNonStarCandidates[i]
                    val pt = projectCandidate(
                        nonStarAltAz[i * 2],
                        nonStarAltAz[i * 2 + 1],
                        obj.ra,
                        obj.dec
                    )
                    if (pt.isSpecified && pt.x in -20f..(size.width + 20f) && pt.y in -20f..(size.height + 20f)) {
                        catalogCandidates.add(obj to pt)
                    }
                }
                for (i in 0 until visibleStarCandidates.size) {
                    val star = visibleStarCandidates[i]
                    val isEligible = star.catalogLabel == "M" ||
                            (!star.commonName.isNullOrBlank() && !star.isFakeCommonName) ||
                            (star.magnitude != null && star.magnitude <= starLabelMagLimit) ||
                            (star.magnitude == null && fov <= 15.0)
                    if (isEligible) {
                        val pt = projectCandidate(
                            starAltAz[i * 2],
                            starAltAz[i * 2 + 1],
                            star.ra,
                            star.dec
                        )
                        if (pt.isSpecified && pt.x in -20f..(size.width + 20f) && pt.y in -20f..(size.height + 20f)) {
                            catalogCandidates.add(star to pt)
                        }
                    }
                }

                val computedCatalogItems = layoutCatalogItems(
                    candidates = catalogCandidates,
                    textMeasurer = textMeasurer,
                    viewportWidth = size.width,
                    viewportHeight = size.height,
                    targetRa = targetRa,
                    targetDec = targetDec,
                    maxLabels = if (fov < 80.0) SkyMapTuning.MAX_DSO_LABELS else 0,
                    uiScale = dsoScale,
                    fontSizeSp = baseFontSize,
                    placedReticles = placedReticleRects,
                    placedLabels = placedLabelRects,
                    primaryColor = colors.primary,
                    secondaryColor = colors.secondary
                )
                placedCatalogItems.addAll(computedCatalogItems)
                for (i in 0 until placedCatalogItems.size) {
                    val item = placedCatalogItems[i]
                    placedCatalogObjIds.add(item.obj.id)
                    val isAbove = !hasValidLocation || AstrometryUtils.raDecToAltAz(
                        item.obj.ra,
                        item.obj.dec,
                        lat,
                        lst
                    ).second >= 0.0
                    placedCatalogIsAbove.add(isAbove)
                }

                // Pass 2: Starfield Dots decrowding
                val minStarDistPx = if (fov < 5.0) 1.2f else 3.0f
                val minStarDistSq = minStarDistPx * minStarDistPx

                for (i in 0 until visibleStarCandidates.size) {
                    val obj = visibleStarCandidates[i]
                    val az = starAltAz[i * 2]
                    val alt = starAltAz[i * 2 + 1]
                    val screenPt = projectCandidate(az, alt, obj.ra, obj.dec)
                    if (screenPt.isUnspecified ||
                        screenPt.x < -20f || screenPt.x > size.width + 20f ||
                        screenPt.y < -20f || screenPt.y > size.height + 20f
                    ) continue

                    val isPlacedCatalogStar = obj.id in placedCatalogObjIds
                    val isMultipleStarIcon = obj.objectType != "Star" && fov <= 15.0
                    if (isPlacedCatalogStar && isMultipleStarIcon) continue

                    // Decrowding: skip star dot if an icon is already placed here or another star is within minStarDistPx
                    if (!isPlacedCatalogStar) {
                        var inReticle = false
                        for (rIdx in 0 until placedReticleRects.size) {
                            if (placedReticleRects[rIdx].contains(screenPt)) {
                                inReticle = true
                                break
                            }
                        }
                        if (inReticle) continue

                        var tooClose = false
                        val numPoints = placedStarXs.size
                        for (pIdx in 0 until numPoints) {
                            val px = placedStarXs[pIdx]
                            val dx = kotlin.math.abs(px - screenPt.x)
                            if (dx >= minStarDistPx) continue
                            val py = placedStarYs[pIdx]
                            val dy = kotlin.math.abs(py - screenPt.y)
                            if (dy >= minStarDistPx) continue
                            if (dx * dx + dy * dy < minStarDistSq) {
                                tooClose = true
                                break
                            }
                        }
                        if (tooClose) continue
                    }

                    placedStarXs.add(screenPt.x)
                    placedStarYs.add(screenPt.y)
                    drawnStars.add(obj)
                    drawnStarIsAbove.add(!hasValidLocation || alt >= 0.0)
                }
            }

            // Draw Pass 1: Placed Catalog Items & Labels
            for (i in 0 until placedCatalogItems.size) {
                val item = placedCatalogItems[i]
                val isAbove = placedCatalogIsAbove[i]
                val (iconVector, themedColor) = item.obj.getIconAndColor(
                    colors.primary,
                    colors.secondary
                )
                val baseColor =
                    if (isAbove) themedColor else colors.secondary.copy(alpha = SkyMapTuning.BELOW_HORIZON_ALPHA)

                val isMultipleStar =
                    item.obj.objectType == "Multiple Star" || item.obj.objectType == "Double Star"
                val isStarDot = item.obj.objectType == "Star" || (isMultipleStar && fov > 15.0)
                val isMoon = item.obj.displayName == "Moon" || item.obj.catalogEntry == "Moon"
                val effectiveIcon = if (isMoon) Icons.Filled.Bedtime else iconVector
                val painter = if (!isStarDot) catalogPainters[effectiveIcon] else null

                if (painter != null) {
                    translate(
                        left = item.screenX - iconSize / 2f,
                        top = item.screenY - iconSize / 2f
                    ) {
                        with(painter) {
                            draw(
                                size = Size(iconSize, iconSize),
                                colorFilter = ColorFilter.tint(baseColor)
                            )
                        }
                    }
                }

                val labelRect = item.labelRect
                val textLayout = item.textLayout
                if (labelRect != null && textLayout != null) {
                    drawText(
                        textLayout,
                        color = baseColor,
                        topLeft = Offset(labelRect.left, labelRect.top)
                    )
                }
            }

            // Draw Pass 2: Decrowded Starfield Dots & Multiple Star Icons
            for (i in 0 until drawnStars.size) {
                val obj = drawnStars[i]
                val sx = placedStarXs[i]
                val sy = placedStarYs[i]
                val isAbove = drawnStarIsAbove[i]
                val baseColor =
                    if (isAbove) colors.primary else colors.secondary.copy(alpha = SkyMapTuning.BELOW_HORIZON_ALPHA)
                val isMultipleStarIcon = obj.objectType != "Star" && fov <= 15.0

                if (isMultipleStarIcon) {
                    val (iconVector, _) = obj.getIconAndColor(colors.primary, colors.secondary)
                    val painter = catalogPainters[iconVector]
                    if (painter != null) {
                        translate(
                            left = sx - iconSize / 2f,
                            top = sy - iconSize / 2f
                        ) {
                            with(painter) {
                                draw(
                                    size = Size(iconSize, iconSize),
                                    colorFilter = ColorFilter.tint(baseColor)
                                )
                            }
                        }
                    }
                } else {
                    val mag = obj.magnitude ?: 5.0
                    val radius = (maxMag - mag).coerceIn(0.8, 5.0).toFloat()
                    drawCircle(
                        color = baseColor,
                        radius = radius,
                        center = Offset(sx, sy)
                    )
                }
            }

            // 8. Telescope Alignment Point Reticle (4-corner bracket, scaled to catalog icons)
            if (alignPt != null) {
                val s = iconSize / 2f
                val arm = s * 0.6f
                val stroke = 1.5.dp.toPx()
                val reticleColor = colors.secondary

                // Top-Left corner
                drawLine(
                    reticleColor,
                    Offset(alignPt.x - s, alignPt.y - s),
                    Offset(alignPt.x - s + arm, alignPt.y - s),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
                drawLine(
                    reticleColor,
                    Offset(alignPt.x - s, alignPt.y - s),
                    Offset(alignPt.x - s, alignPt.y - s + arm),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )

                // Top-Right corner
                drawLine(
                    reticleColor,
                    Offset(alignPt.x + s, alignPt.y - s),
                    Offset(alignPt.x + s - arm, alignPt.y - s),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
                drawLine(
                    reticleColor,
                    Offset(alignPt.x + s, alignPt.y - s),
                    Offset(alignPt.x + s, alignPt.y - s + arm),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )

                // Bottom-Left corner
                drawLine(
                    reticleColor,
                    Offset(alignPt.x - s, alignPt.y + s),
                    Offset(alignPt.x - s + arm, alignPt.y + s),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
                drawLine(
                    reticleColor,
                    Offset(alignPt.x - s, alignPt.y + s),
                    Offset(alignPt.x - s, alignPt.y + s - arm),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )

                // Bottom-Right corner
                drawLine(
                    reticleColor,
                    Offset(alignPt.x + s, alignPt.y + s),
                    Offset(alignPt.x + s - arm, alignPt.y + s),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
                drawLine(
                    reticleColor,
                    Offset(alignPt.x + s, alignPt.y + s),
                    Offset(alignPt.x + s, alignPt.y + s - arm),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }

            // 9. Target Marker & Bearing Line
            val targetRa = stats.targetRa
            val targetDec = stats.targetDec
            if (viewModel.isEngineRunning && targetRa != null && targetDec != null) {
                val tPt = projection.toScreenRaDecOffset(targetRa, targetDec, lat, lst)
                if (tPt.isSpecified) {
                    val s = 14f
                    val stroke = 2.dp.toPx()
                    // Corner brackets
                    drawLine(
                        colors.primary,
                        Offset(tPt.x - s, tPt.y - s),
                        Offset(tPt.x - s + 8f, tPt.y - s),
                        strokeWidth = stroke
                    )
                    drawLine(
                        colors.primary,
                        Offset(tPt.x - s, tPt.y - s),
                        Offset(tPt.x - s, tPt.y - s + 8f),
                        strokeWidth = stroke
                    )
                    drawLine(
                        colors.primary,
                        Offset(tPt.x + s, tPt.y - s),
                        Offset(tPt.x + s - 8f, tPt.y - s),
                        strokeWidth = stroke
                    )
                    drawLine(
                        colors.primary,
                        Offset(tPt.x + s, tPt.y - s),
                        Offset(tPt.x + s, tPt.y - s + 8f),
                        strokeWidth = stroke
                    )
                    drawLine(
                        colors.primary,
                        Offset(tPt.x - s, tPt.y + s),
                        Offset(tPt.x - s + 8f, tPt.y + s),
                        strokeWidth = stroke
                    )
                    drawLine(
                        colors.primary,
                        Offset(tPt.x - s, tPt.y + s),
                        Offset(tPt.x - s, tPt.y + s - 8f),
                        strokeWidth = stroke
                    )
                    drawLine(
                        colors.primary,
                        Offset(tPt.x + s, tPt.y + s),
                        Offset(tPt.x + s - 8f, tPt.y + s),
                        strokeWidth = stroke
                    )
                    drawLine(
                        colors.primary,
                        Offset(tPt.x + s, tPt.y + s),
                        Offset(tPt.x + s, tPt.y + s - 8f),
                        strokeWidth = stroke
                    )

                    // Guide line from camera boresight/alignment reticle to target
                    val guideStart = alignPt ?: if (camRa != null && camDec != null) {
                        val pt = projection.toScreenRaDecOffset(camRa, camDec, lat, lst)
                        if (pt.isSpecified) pt else null
                    } else null

                    if (guideStart != null) {
                        drawLine(
                            color = colors.primary.copy(alpha = 0.5f),
                            start = guideStart,
                            end = tPt,
                            strokeWidth = 1.5.dp.toPx(),
                            pathEffect = targetDottedEffect
                        )
                    }
                }
            }

            // 9. Selected Object Highlight Ring
            val selObj = selectedObject
            if (selObj != null) {
                val sPt = projection.toScreenRaDecOffset(selObj.ra, selObj.dec, lat, lst)
                if (sPt.isSpecified) {
                    drawCircle(
                        color = colors.primary,
                        radius = 22f,
                        center = sPt,
                        style = strokeSelectionCircle
                    )
                }
            }
        }

        val topControlsY = maxOf(safeTop, 36.dp)

        // Overlay Top Controls: Digital Clock (in cutout row / left or right ear, rendered at bottom of unsafe cutout area)
        if (timeString.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(if (isReversed) Alignment.TopEnd else Alignment.TopStart)
                    .padding(
                        start = if (isReversed) 0.dp else safeStart + 2.dp,
                        end = if (isReversed) safeEnd + 2.dp else 0.dp
                    )
                    .height(topControlsY),
                contentAlignment = if (isReversed) Alignment.BottomEnd else Alignment.BottomStart
            ) {
                Text(
                    text = timeString,
                    style = TextStyle(
                        fontSize = SkyMapTuning.CARDINAL_FONT_SIZE_SP.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = colors.primary,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
        }

        // Overlay Top Controls: In-line FOV Badge (Left/Right) and Action Buttons (Right/Left)
        if (viewModel.showMapPosition || (viewModel.showStats && viewModel.isEngineRunning)) {
            Column(
                modifier = Modifier
                    .align(if (isReversed) Alignment.TopEnd else Alignment.TopStart)
                    .padding(
                        start = if (isReversed) 0.dp else safeStart,
                        end = if (isReversed) safeEnd else 0.dp,
                        top = topControlsY
                    )
                    .width(IntrinsicSize.Max),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Map Position Bubble (Untappable, outline border)
                if (viewModel.showMapPosition) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = colors.surface.copy(alpha = 0.75f)),
                        border = BorderStroke(1.dp, colors.outline),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        val badgeAz = viewModel.skyMapCenterAz ?: initialAltAz.first
                        val badgeAlt = viewModel.skyMapCenterAlt ?: initialAltAz.second
                        val badgeFov = viewModel.skyMapFov
                        val (centerRa, centerDec) = remember(badgeAz, badgeAlt, lat, lst) {
                            AstrometryUtils.altAzToRaDec(badgeAz, badgeAlt, lat, lst)
                        }
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Map,
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                val raStr = String.format("%.2f°", centerRa)
                                val decStr = String.format("%+.2f°", centerDec)
                                Text(
                                    text = "RA $raStr Dec $decStr",
                                    style = TextStyle(
                                        fontSize = SkyMapTuning.BADGE_FONT_SIZE_SP.sp,
                                        fontWeight = FontWeight.Normal
                                    ),
                                    color = colors.primary,
                                    maxLines = 1
                                )
                                Text(
                                    text = "Az ${String.format("%.0f", badgeAz)}° Alt ${
                                        String.format(
                                            "%.0f",
                                            badgeAlt
                                        )
                                    }°  FOV ${String.format("%.1f", badgeFov)}°",
                                    style = TextStyle(
                                        fontSize = SkyMapTuning.BADGE_FONT_SIZE_SP.sp,
                                        fontWeight = FontWeight.Normal
                                    ),
                                    color = colors.secondary,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                // Stats Bubble (Tappable, primary border, shown when engine is running and showStats enabled)
                if (viewModel.showStats && viewModel.isEngineRunning) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showFullStats = true },
                        colors = CardDefaults.cardColors(containerColor = colors.surface.copy(alpha = 0.75f)),
                        border = BorderStroke(1.dp, colors.primary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val currentStats = viewModel.engineStats
                            val isGoodSolve =
                                currentStats.solvedCrop != "Failed" && !currentStats.isImuEstimate && currentStats.solvedCrop != null
                            val statusIcon =
                                if (currentStats.status == "Calibrating" || currentStats.status == "Calibrating Focus") Icons.Outlined.Loop
                                else if (isGoodSolve) Icons.Outlined.Check
                                else Icons.Outlined.ErrorOutline
                            Icon(
                                imageVector = statusIcon,
                                contentDescription = stringResource(Res.string.cd_solve_status),
                                tint = colors.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                val raStr =
                                    currentStats.ra?.let { String.format("%.2f°", it) } ?: "-"
                                val decStr =
                                    currentStats.dec?.let { String.format("%+.2f°", it) } ?: "-"
                                Text(
                                    text = "RA $raStr Dec $decStr",
                                    style = TextStyle(
                                        fontSize = SkyMapTuning.BADGE_FONT_SIZE_SP.sp,
                                        fontWeight = FontWeight.Normal
                                    ),
                                    color = colors.primary,
                                    maxLines = 1
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Outlined.StarOutline,
                                            contentDescription = stringResource(Res.string.cd_stars),
                                            tint = colors.secondary,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(Modifier.width(2.dp))
                                        Text(
                                            text = "${currentStats.centroidCount}",
                                            style = TextStyle(fontSize = SkyMapTuning.BADGE_FONT_SIZE_SP.sp),
                                            color = colors.secondary
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Outlined.PhotoCamera,
                                            contentDescription = stringResource(Res.string.stat_exposure),
                                            tint = colors.secondary,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(Modifier.width(2.dp))
                                        Text(
                                            text = "${round(currentStats.exposureMs).toInt()}",
                                            style = TextStyle(fontSize = SkyMapTuning.BADGE_FONT_SIZE_SP.sp),
                                            color = colors.secondary
                                        )
                                    }
                                    val pipelineTotalMs =
                                        currentStats.postProcessMs + currentStats.extractMs + currentStats.solveMs
                                    val pipelineStr = if (pipelineTotalMs < 100.0) {
                                        stringResource(
                                            Res.string.format_pipeline_ms,
                                            round(pipelineTotalMs).toInt()
                                        )
                                    } else {
                                        stringResource(
                                            Res.string.format_pipeline_s,
                                            pipelineTotalMs / 1000f
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Outlined.HourglassEmpty,
                                            contentDescription = stringResource(Res.string.cd_pipeline_time),
                                            tint = colors.secondary,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(Modifier.width(2.dp))
                                        Text(
                                            text = pipelineStr,
                                            style = TextStyle(fontSize = SkyMapTuning.BADGE_FONT_SIZE_SP.sp),
                                            color = colors.secondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Overlay Top Action buttons ("Center Scope", "Center Target")
        Row(
            modifier = Modifier
                .align(if (isReversed) Alignment.TopStart else Alignment.TopEnd)
                .padding(
                    top = topControlsY,
                    start = if (isReversed) safeStart else 0.dp,
                    end = if (isReversed) 0.dp else safeEnd
                ),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (viewModel.isEngineRunning && stats.targetRa != null && stats.targetDec != null) {
                val canCenterTarget = !isStarting && !isStopping
                OutlinedIconButton(
                    onClick = {
                        viewModel.skyMapCameraLocked = false
                        val (tAz, tAlt) = AstrometryUtils.raDecToAltAz(
                            stats.targetRa,
                            stats.targetDec,
                            lat,
                            lst
                        )
                        viewModel.skyMapCenterAz = tAz
                        viewModel.skyMapCenterAlt = tAlt
                    },
                    enabled = canCenterTarget,
                    border = BorderStroke(
                        1.dp,
                        if (canCenterTarget) colors.primary else colors.outline.copy(alpha = 0.4f)
                    ),
                    colors = IconButtonDefaults.outlinedIconButtonColors(
                        containerColor = if (canCenterTarget) colors.surface.copy(alpha = 0.6f) else colors.surface.copy(
                            alpha = 0.3f
                        ),
                        disabledContainerColor = colors.surface.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.GpsFixed,
                        contentDescription = stringResource(Res.string.btn_center_target),
                        tint = if (canCenterTarget) colors.primary else colors.secondary.copy(alpha = 0.4f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            val hasSolve =
                viewModel.isEngineRunning && !isStarting && !isStopping && stats.ra != null && stats.dec != null
            OutlinedIconButton(
                onClick = {
                    viewModel.skyMapCameraLocked = true
                    val alignCoord = getAlignmentPointRaDec()
                    if (alignCoord != null) {
                        val (cAz, cAlt) = AstrometryUtils.raDecToAltAz(
                            alignCoord.first,
                            alignCoord.second,
                            lat,
                            lst
                        )
                        viewModel.skyMapCenterAz = cAz
                        viewModel.skyMapCenterAlt = cAlt
                    } else if (stats.ra != null && stats.dec != null) {
                        val (cAz, cAlt) = AstrometryUtils.raDecToAltAz(
                            stats.ra,
                            stats.dec,
                            lat,
                            lst
                        )
                        viewModel.skyMapCenterAz = cAz
                        viewModel.skyMapCenterAlt = cAlt
                    }
                },
                enabled = hasSolve,
                border = BorderStroke(
                    1.dp,
                    if (hasSolve) colors.primary else colors.outline.copy(alpha = 0.4f)
                ),
                colors = IconButtonDefaults.outlinedIconButtonColors(
                    containerColor = if (hasSolve && isCameraLocked) colors.primary.copy(alpha = 0.25f)
                    else if (hasSolve) colors.surface.copy(alpha = 0.6f)
                    else colors.surface.copy(alpha = 0.3f),
                    disabledContainerColor = colors.surface.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CenterFocusStrong,
                    contentDescription = stringResource(Res.string.btn_center_telescope),
                    tint = if (hasSolve) colors.primary else colors.secondary.copy(alpha = 0.4f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Overlay Bottom Zoom +/- Controls
        Column(
            modifier = Modifier
                .align(if (isReversed) Alignment.BottomStart else Alignment.BottomEnd)
                .padding(
                    start = if (isReversed) safeStart else 0.dp,
                    end = if (isReversed) 0.dp else safeEnd,
                    bottom = 8.dp
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedIconButton(
                onClick = {
                    viewModel.skyMapFov = (viewModel.skyMapFov / 1.4).coerceIn(0.5, 180.0)
                },
                border = BorderStroke(1.dp, colors.outline),
                colors = IconButtonDefaults.outlinedIconButtonColors(
                    containerColor = colors.surface.copy(
                        alpha = 0.6f
                    )
                ),
                shape = CircleShape,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(Res.string.cd_zoom_in),
                    tint = colors.primary
                )
            }
            OutlinedIconButton(
                onClick = {
                    viewModel.skyMapFov = (viewModel.skyMapFov * 1.4).coerceIn(0.5, 180.0)
                },
                border = BorderStroke(1.dp, colors.outline),
                colors = IconButtonDefaults.outlinedIconButtonColors(
                    containerColor = colors.surface.copy(
                        alpha = 0.6f
                    )
                ),
                shape = CircleShape,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.Remove,
                    contentDescription = stringResource(Res.string.cd_zoom_out),
                    tint = colors.primary
                )
            }
        }

        // Overlay Bottom: Target Object Bubble & Mini Guidance Bubble (shown only when a GoTo is active)
        if (viewModel.isEngineRunning && stats.targetRa != null && stats.targetDec != null) {
            val guidanceWidth = 88.dp * 16f / 9f
            val targetDisplayName = targetObjName ?: stringResource(
                Res.string.format_ra_dec,
                String.format("%.2f", stats.targetRa),
                String.format("%.2f", stats.targetDec)
            )

            Column(
                modifier = Modifier
                    .align(if (isReversed) Alignment.BottomEnd else Alignment.BottomStart)
                    .padding(
                        start = if (isReversed) 0.dp else safeStart,
                        end = if (isReversed) safeEnd else 0.dp,
                        bottom = 8.dp
                    )
                    .width(guidanceWidth),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Target Object Bubble (Tappable, primary border)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showTargetPopup = true },
                    colors = CardDefaults.cardColors(containerColor = colors.surface.copy(alpha = 0.85f)),
                    border = BorderStroke(1.dp, colors.primary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = targetDisplayName,
                            style = TextStyle(
                                fontSize = SkyMapTuning.BADGE_FONT_SIZE_SP.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = colors.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Mini Guidance Bubble (Untappable, outline border)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                    colors = CardDefaults.cardColors(containerColor = colors.surface.copy(alpha = 0.85f)),
                    border = BorderStroke(1.dp, colors.outline),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        GuidanceDisplay(
                            stats = stats,
                            isAltAz = viewModel.guidanceMode == "alt_az",
                            isLowPowerMode = false,
                            isImuEnabled = viewModel.settingsManager.motionSensorMode != 0,
                            isCompactLayout = false
                        )
                    }
                }
            }
        }


        // Selected Object Info Popup
        val obj = selectedObject
        if (obj != null) {
            InfoPopup(
                title = obj.displayName,
                onDismiss = { selectedObject = null }
            ) {
                Column {
                    if (!obj.commonName.isNullOrBlank() && !obj.isFakeCommonName) {
                        Row {
                            Text(
                                "${stringResource(Res.string.label_designation)}: ",
                                fontWeight = FontWeight.Bold,
                                color = colors.primary
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(obj.designation, color = colors.primary)
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    Row {
                        Text(
                            "${stringResource(Res.string.label_type)}: ",
                            fontWeight = FontWeight.Bold,
                            color = colors.primary
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(obj.objectType, color = colors.primary)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row {
                        Text(
                            "${stringResource(Res.string.label_magnitude)}: ",
                            fontWeight = FontWeight.Bold,
                            color = colors.primary
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (obj.magnitude != null) String.format(
                                "%.2f",
                                obj.magnitude
                            ) else stringResource(Res.string.value_not_available),
                            color = colors.primary
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    if (!obj.constellation.isNullOrBlank()) {
                        Row {
                            Text(
                                "${stringResource(Res.string.label_constellation)}: ",
                                fontWeight = FontWeight.Bold,
                                color = colors.primary
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(obj.constellation, color = colors.primary)
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    Row {
                        Text(
                            "${stringResource(Res.string.ra_j2000_label)}: ",
                            fontWeight = FontWeight.Bold,
                            color = colors.primary
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(formatRa(obj.ra), color = colors.primary)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row {
                        Text(
                            "${stringResource(Res.string.dec_j2000_label)}: ",
                            fontWeight = FontWeight.Bold,
                            color = colors.primary
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(formatDec(obj.dec), color = colors.primary)
                    }
                    val isAbove = !viewModel.hasValidLocation || AstrometryUtils.raDecToAltAz(
                        obj.ra,
                        obj.dec,
                        lat,
                        lst
                    ).second >= 0.0
                    if (viewModel.isEngineRunning && isAbove) {
                        Spacer(Modifier.height(12.dp))
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            IconBarItem(
                                icon = Icons.Default.GpsFixed,
                                label = stringResource(Res.string.btn_goto),
                                onClick = {
                                    viewModel.setGoToTarget(obj.ra, obj.dec)
                                    selectedObject = null
                                }
                            )
                        }
                    } else if (viewModel.isEngineRunning && !isAbove) {
                        Spacer(Modifier.height(12.dp))
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(Res.string.status_below_horizon),
                                color = colors.primary,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }

        // Target Object Info Popup (opened by tapping Target Object Bubble)
        if (showTargetPopup && viewModel.isEngineRunning && stats.targetRa != null && stats.targetDec != null) {
            val targetDisplayName = targetObjName ?: stringResource(
                Res.string.format_ra_dec,
                String.format("%.2f", stats.targetRa),
                String.format("%.2f", stats.targetDec)
            )
            val target = targetObject

            InfoPopup(
                title = targetDisplayName,
                onDismiss = { showTargetPopup = false }
            ) {
                Column {
                    if (target != null) {
                        if (!target.commonName.isNullOrBlank() && !target.isFakeCommonName) {
                            Row {
                                Text(
                                    "${stringResource(Res.string.label_designation)}: ",
                                    fontWeight = FontWeight.Bold,
                                    color = colors.primary
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(target.designation, color = colors.primary)
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                        Row {
                            Text(
                                "${stringResource(Res.string.label_type)}: ",
                                fontWeight = FontWeight.Bold,
                                color = colors.primary
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(target.objectType, color = colors.primary)
                        }
                        Spacer(Modifier.height(4.dp))
                        Row {
                            Text(
                                "${stringResource(Res.string.label_magnitude)}: ",
                                fontWeight = FontWeight.Bold,
                                color = colors.primary
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (target.magnitude != null) String.format(
                                    "%.2f",
                                    target.magnitude
                                ) else stringResource(Res.string.value_not_available),
                                color = colors.primary
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        if (!target.constellation.isNullOrBlank()) {
                            Row {
                                Text(
                                    "${stringResource(Res.string.label_constellation)}: ",
                                    fontWeight = FontWeight.Bold,
                                    color = colors.primary
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(target.constellation, color = colors.primary)
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                        Row {
                            Text(
                                "${stringResource(Res.string.ra_j2000_label)}: ",
                                fontWeight = FontWeight.Bold,
                                color = colors.primary
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(formatRa(target.ra), color = colors.primary)
                        }
                        Spacer(Modifier.height(4.dp))
                        Row {
                            Text(
                                "${stringResource(Res.string.dec_j2000_label)}: ",
                                fontWeight = FontWeight.Bold,
                                color = colors.primary
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(formatDec(target.dec), color = colors.primary)
                        }
                    } else {
                        Row {
                            Text(
                                "${stringResource(Res.string.ra_j2000_label)}: ",
                                fontWeight = FontWeight.Bold,
                                color = colors.primary
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(formatRa(stats.targetRa), color = colors.primary)
                        }
                        Spacer(Modifier.height(4.dp))
                        Row {
                            Text(
                                "${stringResource(Res.string.dec_j2000_label)}: ",
                                fontWeight = FontWeight.Bold,
                                color = colors.primary
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(formatDec(stats.targetDec), color = colors.primary)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconBarItem(
                            icon = Icons.Default.Stop,
                            label = stringResource(Res.string.btn_stop),
                            onClick = {
                                viewModel.clearGoToTarget()
                                showTargetPopup = false
                            }
                        )
                        val liveStats = viewModel.engineStats
                        if (liveStats.isTargetVisible) {
                            IconBarItem(
                                icon = Icons.Default.FilterCenterFocus,
                                label = stringResource(Res.string.btn_align),
                                onClick = {
                                    showSyncDialog = true
                                }
                            )
                        }
                    }
                }
            }
        }

        // Align Confirmation Dialog
        if (showSyncDialog && viewModel.isEngineRunning) {
            OliveAlertDialog(
                title = stringResource(Res.string.title_confirm_alignment),
                text = stringResource(Res.string.msg_confirm_alignment),
                confirmButtonText = stringResource(Res.string.btn_confirm),
                onConfirm = {
                    showSyncDialog = false
                    showTargetPopup = false
                    viewModel.syncTelescope(
                        viewModel.engineStats.targetRa,
                        viewModel.engineStats.targetDec
                    )
                },
                dismissButtonText = stringResource(Res.string.btn_cancel),
                onDismiss = { showSyncDialog = false }
            )
        }

        // Engine Stats Full Popup
        if (showFullStats && viewModel.isEngineRunning) {
            InfoPopup(
                title = stringResource(Res.string.title_engine_info),
                onDismiss = { showFullStats = false }
            ) {
                StatsDisplay(
                    stats = viewModel.engineStats,
                    detectedIpAddress = detectedIpAddress,
                    onInfoClick = {
                        showFullStats = false
                        showTelescopeControlInfo = true
                    }
                )
            }
        }

        // Telescope Control Info Popup
        if (showTelescopeControlInfo) {
            val capabilities = LocalPlatformCapabilities.current
            InfoPopup(
                title = stringResource(id = Res.string.heading_telescope_control),
                onDismiss = { showTelescopeControlInfo = false }
            ) {
                val infoRes = if (capabilities.isApple) {
                    Res.string.info_telescope_control_ios
                } else {
                    Res.string.info_telescope_control
                }
                Text(AnnotatedString.fromHtml(stringResource(id = infoRes)))
            }
        }
    }
}


