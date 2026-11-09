// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
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
import org.jetbrains.compose.resources.stringResource
import kotlin.math.hypot
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
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
    planets: List<CatalogObject> = emptyList(),
    comets: List<CatalogObject> = emptyList(),
    asteroids: List<CatalogObject> = emptyList()
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
    val hasValidLocation = rawLat != null && rawLon != null &&
            rawLat != MainViewModel.INVALID_COORD && rawLon != MainViewModel.INVALID_COORD &&
            rawLat in -90.0..90.0 && rawLon in -180.0..360.0
    val lat = if (hasValidLocation) rawLat else 0.0
    val lon = if (hasValidLocation) rawLon else 0.0

    var currentTimeMs by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTimeMs = Clock.System.now().toEpochMilliseconds()
            kotlinx.coroutines.delay(1000)
        }
    }
    val lst = remember(lon, currentTimeMs) {
        if (hasValidLocation) AstrometryUtils.calculateLST(lon, currentTimeMs) else 0.0
    }

    val initialAltAz = remember {
        if (stats.ra != null && stats.dec != null) {
            AstrometryUtils.raDecToAltAz(stats.ra, stats.dec, lat, lst)
        } else {
            Pair(180.0, 45.0) // Facing South, 45° altitude
        }
    }
    var centerAz by remember { mutableDoubleStateOf(initialAltAz.first) }
    var centerAlt by remember { mutableDoubleStateOf(initialAltAz.second) }
    var fov by remember { mutableDoubleStateOf(maxOf(stats.fov ?: 30.0, 50.0)) }
    var isCameraLocked by remember { mutableStateOf(stats.ra != null) }

    // Auto-center on first valid solve if initialized before plate solving begins
    var hasAutoCentered by remember { mutableStateOf(stats.ra != null) }
    LaunchedEffect(stats.ra, stats.dec, lat, lst) {
        if (!hasAutoCentered && stats.ra != null && stats.dec != null) {
            val (az, alt) = AstrometryUtils.raDecToAltAz(stats.ra, stats.dec, lat, lst)
            centerAz = az
            centerAlt = alt
            fov = maxOf(stats.fov ?: 30.0, 50.0)
            @Suppress("AssignedValueIsNeverRead")
            hasAutoCentered = true
            isCameraLocked = true
        }
    }

    LaunchedEffect(stats.ra, stats.dec, isCameraLocked, lat, lst) {
        if (isCameraLocked && stats.ra != null && stats.dec != null) {
            val (az, alt) = AstrometryUtils.raDecToAltAz(stats.ra, stats.dec, lat, lst)
            centerAz = az
            centerAlt = alt
        }
    }

    LaunchedEffect(viewModel.skyMapCenterTarget, lat, lst) {
        val target = viewModel.skyMapCenterTarget ?: return@LaunchedEffect
        val (az, alt) = AstrometryUtils.raDecToAltAz(target.first, target.second, lat, lst)
        centerAz = az
        centerAlt = alt
        isCameraLocked = false
        viewModel.skyMapCenterTarget = null
    }

    val colors = LocalOliveColors.current
    val cutout = WindowInsets.displayCutout.asPaddingValues()
    val safeTop = maxOf(8.dp, cutout.calculateTopPadding())
    val safeStart = maxOf(8.dp, cutout.calculateStartPadding(LocalLayoutDirection.current))
    val safeEnd = maxOf(8.dp, cutout.calculateEndPadding(LocalLayoutDirection.current))

    val is24Hour = is24HourFormat()
    var timeString by remember { mutableStateOf("") }
    LaunchedEffect(is24Hour) {
        while (isActive) {
            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            timeString = formatSystemTime(now, is24Hour)
            delay(1000)
        }
    }

    val textMeasurer = rememberTextMeasurer(cacheSize = 256)
    val catalogPainters = rememberCatalogPainters()

    // Dismiss InfoPopup on Back button before closing sky map
    var selectedObject by remember { mutableStateOf<CatalogObject?>(null) }
    BackHandler(enabled = isExpanded && selectedObject != null) {
        selectedObject = null
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
                fontWeight = FontWeight.SemiBold
            )
        )
    }
    val nadirTextLayout = remember(textMeasurer, nadirText, colors) {
        textMeasurer.measure(
            text = nadirText,
            style = TextStyle(
                color = colors.secondary,
                fontSize = SkyMapTuning.ZENITH_NADIR_FONT_SIZE_SP.sp,
                fontWeight = FontWeight.SemiBold
            )
        )
    }
    val cardinalLayouts = remember(textMeasurer, colors) {
        mapOf(
            "N" to textMeasurer.measure(
                "N",
                style = TextStyle(
                    color = colors.primary,
                    fontSize = SkyMapTuning.CARDINAL_FONT_SIZE_SP.sp,
                    fontWeight = FontWeight.Bold
                )
            ),
            "E" to textMeasurer.measure(
                "E",
                style = TextStyle(
                    color = colors.primary,
                    fontSize = SkyMapTuning.CARDINAL_FONT_SIZE_SP.sp,
                    fontWeight = FontWeight.Bold
                )
            ),
            "S" to textMeasurer.measure(
                "S",
                style = TextStyle(
                    color = colors.primary,
                    fontSize = SkyMapTuning.CARDINAL_FONT_SIZE_SP.sp,
                    fontWeight = FontWeight.Bold
                )
            ),
            "W" to textMeasurer.measure(
                "W",
                style = TextStyle(
                    color = colors.primary,
                    fontSize = SkyMapTuning.CARDINAL_FONT_SIZE_SP.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        )
    }
    val constellationLayouts = remember(textMeasurer, colors) {
        ConstellationData.CONSTELLATIONS.associate { c ->
            c.name to textMeasurer.measure(
                text = c.name,
                style = TextStyle(
                    color = colors.secondary.copy(alpha = 0.85f),
                    fontSize = SkyMapTuning.CONSTELLATION_FONT_SIZE_SP.sp,
                    fontWeight = FontWeight.Medium
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

    // Solar system ephemeris: use provided list or load asynchronously off the main thread
    var solPlanets by remember { mutableStateOf(planets) }
    var solComets by remember { mutableStateOf(comets) }
    var solAsteroids by remember { mutableStateOf(asteroids) }
    LaunchedEffect(planets, comets, asteroids, hasValidLocation) {
        if (planets.isNotEmpty()) solPlanets = planets
        if (comets.isNotEmpty()) solComets = comets
        if (asteroids.isNotEmpty()) solAsteroids = asteroids
        if (hasValidLocation && solPlanets.isEmpty() && solComets.isEmpty() && solAsteroids.isEmpty()) {
            withContext(Dispatchers.IO) {
                solPlanets = viewModel.getPlanets()
                solComets = viewModel.getComets()
                solAsteroids = viewModel.getAsteroids()
            }
        }
    }

    // Tier 2 dynamic background query for dense deep sky objects and stars when zoomed in
    var dynamicCatalogObjects by remember { mutableStateOf<List<CatalogObject>>(emptyList()) }
    var dynamicStars by remember { mutableStateOf<List<CatalogObject>>(emptyList()) }
    val currentTypeFilters = viewModel.catalogTypeFilters
    val currentMagLimit = viewModel.catalogMagLimit
    val isAllTypes =
        currentTypeFilters.isEmpty() || currentTypeFilters == DEFAULT_CATALOG_TYPE_FILTERS

    LaunchedEffect(currentTypeFilters, currentMagLimit, isAllTypes, lat, lst) {
        snapshotFlow { Triple(centerAz, centerAlt, fov) }
            .debounce(200.milliseconds)
            .collectLatest { (az, alt, currentFov) ->
                if (currentFov < 60.0) {
                    val (ra, dec) = AstrometryUtils.altAzToRaDec(az, alt, lat, lst)
                    val searchRadius = (currentFov * 1.35).coerceIn(0.5, 45.0)
                    val queryMagLimit = when {
                        currentFov > 30.0 -> minOf(6.5, currentMagLimit ?: 6.5)
                        currentFov > 15.0 -> minOf(8.0, currentMagLimit ?: 8.0)
                        currentFov > 5.0 -> minOf(10.0, currentMagLimit ?: 10.0)
                        else -> currentMagLimit ?: 12.0
                    }
                    val (dsoObjects, stars) = withContext(Dispatchers.IO) {
                        val dso = viewModel.catalogDb.searchObjects(
                            targetRa = ra,
                            targetDec = dec,
                            maxDistanceDegrees = searchRadius,
                            magLimit = if (currentFov <= 15.0) null else queryMagLimit,
                            typeFilters = if (isAllTypes) null else currentTypeFilters,
                            starFilter = false,
                            visibilityFilter = "Any",
                            sortBy = "brightness",
                            limit = 200
                        )
                        val st = viewModel.catalogDb.searchObjects(
                            targetRa = ra,
                            targetDec = dec,
                            maxDistanceDegrees = searchRadius,
                            magLimit = queryMagLimit,
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

    val effectiveMagLimit = when {
        fov > 60.0 -> minOf(5.0, currentMagLimit ?: 5.0)
        fov > 30.0 -> minOf(6.5, currentMagLimit ?: 6.5)
        fov > 15.0 -> minOf(8.0, currentMagLimit ?: 8.0)
        fov > 10.0 -> minOf(10.0, currentMagLimit ?: 10.0)
        fov > 5.0 -> minOf(12.0, currentMagLimit ?: 12.0)
        else -> currentMagLimit ?: 99.9
    }

    val targetRa = stats.targetRa ?: stats.ra
    val targetDec = stats.targetDec ?: stats.dec

    // Pre-filter and deduplicate candidates outside the Canvas draw loop to avoid allocations at 60fps
    val visibleCandidates = remember(
        solPlanets, solComets, solAsteroids,
        tier1Dso, tier1Stars, dynamicCatalogObjects, dynamicStars,
        currentTypeFilters, currentMagLimit, isAllTypes, effectiveMagLimit, fov,
        targetRa, targetDec
    ) {
        val list = mutableListOf<CatalogObject>()

        fun isCurrentTarget(obj: CatalogObject): Boolean {
            return targetRa != null && targetDec != null &&
                    AstrometryUtils.angularDistance(obj.ra, obj.dec, targetRa, targetDec) < 0.05
        }

        // 1. Solar System
        list.addAll(solPlanets.filter { p ->
            isCurrentTarget(p) || (
                    (isAllTypes || "Planet" in currentTypeFilters || p.objectType in currentTypeFilters) &&
                            (p.magnitude == null || p.magnitude <= effectiveMagLimit)
                    )
        })
        list.addAll(solComets.filter { c ->
            isCurrentTarget(c) || (
                    (isAllTypes || "Comet" in currentTypeFilters || c.objectType in currentTypeFilters) &&
                            (c.magnitude != null && c.magnitude <= effectiveMagLimit)
                    )
        })
        list.addAll(solAsteroids.filter { a ->
            isCurrentTarget(a) || (
                    (isAllTypes || "Asteroid" in currentTypeFilters || a.objectType in currentTypeFilters) &&
                            (a.magnitude != null && a.magnitude <= effectiveMagLimit)
                    )
        })

        // 2. DSOs (Tier 1 Baseline + Dynamic Local Field, deduplicated by ID)
        list.addAll(
            (tier1Dso + dynamicCatalogObjects)
                .filter { obj ->
                    if (isCurrentTarget(obj)) return@filter true
                    val typeMatches =
                        isAllTypes || obj.objectType in currentTypeFilters || obj.broadCategory in currentTypeFilters
                    val magMatches = when {
                        obj.magnitude != null -> obj.magnitude <= effectiveMagLimit
                        else -> fov <= 15.0
                    }
                    typeMatches && magMatches
                }
                .distinctBy { it.id }
        )

        // 3. Stars (Tier 1 + Dynamic, deduplicated by ID)
        list.addAll(
            (tier1Stars + dynamicStars)
                .filter { isCurrentTarget(it) || (it.magnitude ?: 99.0) <= effectiveMagLimit }
                .distinctBy { it.id }
        )

        list
    }

    val allPlacedItems = remember { mutableListOf<PlacedCatalogItem>() }

    // PathEffects pre-allocated
    val horizonDashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f) }
    val fovDashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f) }

    val hitRadiusPx = with(LocalDensity.current) { 36.dp.toPx() }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()

        val projection = remember(centerAz, centerAlt, fov, widthPx, heightPx) {
            SkyMapProjection(
                centerAz = centerAz,
                centerAlt = centerAlt,
                fov = fov,
                viewportWidth = widthPx,
                viewportHeight = heightPx
            )
        }
        val currentProjection by rememberUpdatedState(projection)

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (zoom != 1f) {
                            fov = (fov / zoom.toDouble()).coerceIn(0.5, 180.0)
                        }
                        if (pan != Offset.Zero) {
                            if (isCameraLocked && zoom !in 0.98f..1.02f) {
                                // Pinch-zoom touch noise shouldn't disengage camera lock
                            } else {
                                isCameraLocked = false
                                val proj = currentProjection
                                proj.panBy(pan.x, pan.y)
                                centerAz = proj.centerAz
                                centerAlt = proj.centerAlt
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { tapOffset ->
                        val tapped = findTappedCatalogItem(allPlacedItems, tapOffset, hitRadiusPx)
                        if (tapped != null) {
                            selectedObject = tapped
                        }
                        // Tapping empty space does nothing (preserves selection until dismissed)
                    }
                }
        ) {
            allPlacedItems.clear()
            val placedLabelRects = mutableListOf<Rect>()
            val placedReticleRects = mutableListOf<Rect>()

            // 1. Local Horizon Circle & Below-Horizon Shading (only if observer location is known)
            if (hasValidLocation) {
                val horizonPoints = SkyMapProjection.HORIZON_POINTS
                val screenPoints = horizonPoints.map { projection.toScreen(it.first, it.second) }
                val horizonPath = Path()

                val firstNullIndex = screenPoints.indexOfFirst { it == null }
                if (firstNullIndex == -1) {
                    // Full horizon circle on forward hemisphere -> single closed loop
                    val first = screenPoints[0]
                    if (first != null) {
                        horizonPath.moveTo(first.first, first.second)
                        for (i in 1 until screenPoints.size) {
                            val pt = screenPoints[i] ?: continue
                            horizonPath.lineTo(pt.first, pt.second)
                        }
                        horizonPath.close()
                    }
                } else {
                    // Horizon crosses antipode region -> open arc(s) extending to infinity.
                    // Traverse circularly starting after the null gap to trace contiguous visible arcs.
                    // Never call close() on an open arc to prevent chords across screen.
                    val n = screenPoints.size
                    var inSegment = false
                    for (i in 0 until n) {
                        val pt = screenPoints[(firstNullIndex + 1 + i) % n]
                        if (pt != null) {
                            if (!inSegment) {
                                horizonPath.moveTo(pt.first, pt.second)
                                inSegment = true
                            } else {
                                horizonPath.lineTo(pt.first, pt.second)
                            }
                        } else {
                            inSegment = false
                        }
                    }
                }

                drawPath(
                    path = horizonPath,
                    color = colors.secondary,
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = horizonDashEffect)
                )

                // 2. Cardinal Points (N, E, S, W)
                val cardinals = SkyMapProjection.CARDINAL_POINTS
                for ((cardinal, coords) in cardinals) {
                    val pt = projection.toScreen(coords.first, coords.second)
                    if (pt != null && pt.first in 0f..size.width && pt.second in 0f..size.height) {
                        val textLayout = cardinalLayouts[cardinal]
                        if (textLayout != null) {
                            val cLeft = pt.first - textLayout.size.width / 2f
                            val cTop = pt.second - textLayout.size.height / 2f
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
                val zenithPt = projection.toScreen(zenithAz, zenithAlt)
                if (zenithPt != null && zenithPt.first in -50f..size.width + 50f && zenithPt.second in -50f..size.height + 50f) {
                    val zArm = 10.dp.toPx()
                    val zX = zenithPt.first
                    val zY = zenithPt.second
                    val stroke = 1.5.dp.toPx()
                    drawCircle(
                        colors.secondary,
                        radius = 3.dp.toPx(),
                        center = Offset(zX, zY),
                        style = Stroke(stroke)
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
                val nadirPt = projection.toScreen(nadirAz, nadirAlt)
                if (nadirPt != null && nadirPt.first in -50f..size.width + 50f && nadirPt.second in -50f..size.height + 50f) {
                    val nArm = 8.dp.toPx()
                    val nX = nadirPt.first
                    val nY = nadirPt.second
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
            val constellationPath = Path()
            for (line in ConstellationData.LINES) {
                val p1 = projection.toScreenRaDec(line.ra1, line.dec1, lat, lst) ?: continue
                val p2 = projection.toScreenRaDec(line.ra2, line.dec2, lat, lst) ?: continue

                // Check distance to avoid cross-screen lines if wrap occurs
                if (hypot(p1.first - p2.first, p1.second - p2.second) < widthPx * 0.8f) {
                    constellationPath.moveTo(p1.first, p1.second)
                    constellationPath.lineTo(p2.first, p2.second)
                }
            }
            drawPath(
                path = constellationPath,
                color = colors.secondary,
                style = Stroke(width = 1.5.dp.toPx())
            )

            // 5. Constellation Names
            if (fov in 20.0..130.0) {
                for (constell in ConstellationData.CONSTELLATIONS) {
                    val pt =
                        projection.toScreenRaDec(constell.centerRa, constell.centerDec, lat, lst)
                    if (pt != null && pt.first in 0f..size.width && pt.second in 0f..size.height) {
                        val textLayout = constellationLayouts[constell.name]
                        if (textLayout != null) {
                            val cLeft = pt.first - textLayout.size.width / 2f
                            val cTop = pt.second - textLayout.size.height / 2f
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
            val camCenterRa = stats.centerRa ?: camRa
            val camCenterDec = stats.centerDec ?: camDec
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

            if (camCenterRa != null && camCenterDec != null) {
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

                    val bPath = Path().apply {
                        moveTo(p0.first, p0.second)
                        lineTo(p1.first, p1.second)
                        lineTo(p2.first, p2.second)
                        lineTo(p3.first, p3.second)
                        close()
                    }
                    boxPath = bPath

                    val mat = if (cameraFrame != null) {
                        computeQuadHomography(
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
                        clipPath(bPath) {
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
                        path = bPath,
                        color = boxColor,
                        style = Stroke(width = 2.dp.toPx(), pathEffect = fovDashEffect)
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
                    projection.toScreenRaDec(camRa, camDec, lat, lst)
                        ?.let { (x, y) -> Offset(x, y) }
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

            // Coordinate mapper for catalog objects (applying optical distortion correction inside camera box)
            val projectWithDistortion: (Double, Double) -> Offset? = { objRa, objDec ->
                val stdPt = projection.toScreenRaDec(objRa, objDec, lat, lst)
                if (stdPt == null) {
                    null
                } else {
                    val stdOffset = Offset(stdPt.first, stdPt.second)
                    var screenOffset: Offset? = null
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
                            screenOffset = matrix.map(Offset(px, py))
                        }
                    }
                    screenOffset ?: stdOffset
                }
            }

            // 7. Stars & Catalog Objects Rendering
            val (starObjects, nonStarObjects) = visibleCandidates.partition { it.objectType == "Star" || it.catalogLabel == "Str" }

            // Pass 1: DSOs, Planets, Comets, Asteroids (placed first to establish priority icons and decrowding)
            val dsoCandidates = nonStarObjects.mapNotNull { obj ->
                projectWithDistortion(obj.ra, obj.dec)
                    ?.let { obj to it }
            }
            val placedDsoItems = layoutCatalogItems(
                candidates = dsoCandidates,
                textMeasurer = textMeasurer,
                viewportWidth = size.width,
                viewportHeight = size.height,
                targetRa = targetRa,
                targetDec = targetDec,
                maxLabels = if (fov < 80.0) SkyMapTuning.MAX_DSO_LABELS else 0,
                uiScale = dsoScale,
                fontSizeSp = baseFontSize,
                placedReticles = placedReticleRects,
                placedLabels = placedLabelRects
            )

            for (item in placedDsoItems) {
                val isAbove = !hasValidLocation || AstrometryUtils.raDecToAltAz(
                    item.obj.ra,
                    item.obj.dec,
                    lat,
                    lst
                ).second >= 0.0
                val (iconVector, themedColor) = item.obj.getIconAndColor(
                    colors.primary,
                    colors.secondary
                )
                val baseColor =
                    if (isAbove) themedColor else colors.secondary.copy(alpha = SkyMapTuning.BELOW_HORIZON_ALPHA)
                val screenPt = Offset(item.screenX, item.screenY)

                val painter = catalogPainters[iconVector]

                if (painter != null) {
                    translate(left = screenPt.x - iconSize / 2f, top = screenPt.y - iconSize / 2f) {
                        with(painter) {
                            draw(
                                size = Size(iconSize, iconSize),
                                colorFilter = ColorFilter.tint(baseColor)
                            )
                        }
                    }
                }

                if (item.labelRect != null) {
                    val textLayout = textMeasurer.measure(
                        text = item.obj.displayName,
                        style = TextStyle(
                            color = baseColor,
                            fontSize = (baseFontSize * dsoScale).sp,
                            fontWeight = if (item.isTarget) FontWeight.Bold else FontWeight.Normal
                        )
                    )
                    drawText(
                        textLayout,
                        color = baseColor,
                        topLeft = Offset(item.labelRect.left, item.labelRect.top)
                    )
                }
            }

            // Pass 2: Starfield Dots (rendered under icons, avoiding clumping on top of placed icons or stars)
            val placedStarItems = mutableListOf<PlacedCatalogItem>()
            val placedStarPoints = mutableListOf<Offset>()
            val maxMag = (effectiveMagLimit + 0.5).coerceAtLeast(6.0)
            val minStarDistPx = if (fov < 5.0) 1.2f else 3.0f

            for (obj in starObjects) {
                val screenPt = projectWithDistortion(obj.ra, obj.dec) ?: continue
                if (screenPt.x < -20f || screenPt.x > size.width + 20f || screenPt.y < -20f || screenPt.y > size.height + 20f) continue

                val isAbove = !hasValidLocation || (AstrometryUtils.raDecToAltAz(
                    obj.ra,
                    obj.dec,
                    lat,
                    lst
                ).second >= 0.0)
                val baseColor =
                    if (isAbove) colors.primary else colors.secondary.copy(alpha = SkyMapTuning.BELOW_HORIZON_ALPHA)

                // Decrowding: skip star dot if an icon is already placed here or another star is within minStarDistPx
                if (placedReticleRects.any { it.contains(screenPt) }) continue
                if (placedStarPoints.any {
                        hypot(
                            it.x - screenPt.x,
                            it.y - screenPt.y
                        ) < minStarDistPx
                    }) continue

                placedStarPoints.add(screenPt)
                placedStarItems.add(
                    PlacedCatalogItem(
                        obj = obj,
                        screenX = screenPt.x,
                        screenY = screenPt.y
                    )
                )

                val mag = obj.magnitude ?: 5.0
                val radius = (maxMag - mag).coerceIn(0.8, 5.0).toFloat()
                drawCircle(
                    color = baseColor,
                    radius = radius,
                    center = screenPt
                )
            }

            allPlacedItems.addAll(placedDsoItems)
            allPlacedItems.addAll(placedStarItems)

            // 8. Telescope Alignment Point Reticle (same 4-corner bracket as EngineRunningScreen, scaled to catalog icons)
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
            if (targetRa != null && targetDec != null) {
                val tPt = projection.toScreenRaDec(targetRa, targetDec, lat, lst)
                if (tPt != null) {
                    val s = 14f
                    val stroke = 2.dp.toPx()
                    // Corner brackets
                    drawLine(
                        colors.primary,
                        Offset(tPt.first - s, tPt.second - s),
                        Offset(tPt.first - s + 8f, tPt.second - s),
                        strokeWidth = stroke
                    )
                    drawLine(
                        colors.primary,
                        Offset(tPt.first - s, tPt.second - s),
                        Offset(tPt.first - s, tPt.second - s + 8f),
                        strokeWidth = stroke
                    )
                    drawLine(
                        colors.primary,
                        Offset(tPt.first + s, tPt.second - s),
                        Offset(tPt.first + s - 8f, tPt.second - s),
                        strokeWidth = stroke
                    )
                    drawLine(
                        colors.primary,
                        Offset(tPt.first + s, tPt.second - s),
                        Offset(tPt.first + s, tPt.second - s + 8f),
                        strokeWidth = stroke
                    )
                    drawLine(
                        colors.primary,
                        Offset(tPt.first - s, tPt.second + s),
                        Offset(tPt.first - s + 8f, tPt.second + s),
                        strokeWidth = stroke
                    )
                    drawLine(
                        colors.primary,
                        Offset(tPt.first - s, tPt.second + s),
                        Offset(tPt.first - s, tPt.second + s - 8f),
                        strokeWidth = stroke
                    )
                    drawLine(
                        colors.primary,
                        Offset(tPt.first + s, tPt.second + s),
                        Offset(tPt.first + s - 8f, tPt.second + s),
                        strokeWidth = stroke
                    )
                    drawLine(
                        colors.primary,
                        Offset(tPt.first + s, tPt.second + s),
                        Offset(tPt.first + s, tPt.second + s - 8f),
                        strokeWidth = stroke
                    )

                    // Guide line from camera boresight/alignment reticle to target
                    val guideStart = alignPt ?: if (camRa != null && camDec != null) {
                        projection.toScreenRaDec(camRa, camDec, lat, lst)
                            ?.let { Offset(it.first, it.second) }
                    } else null

                    if (guideStart != null) {
                        drawLine(
                            color = colors.primary.copy(alpha = 0.5f),
                            start = guideStart,
                            end = Offset(tPt.first, tPt.second),
                            strokeWidth = 1.5.dp.toPx(),
                            pathEffect = horizonDashEffect
                        )
                    }
                }
            }

            // 9. Selected Object Highlight Ring
            val selObj = selectedObject
            if (selObj != null) {
                val sPt = projection.toScreenRaDec(selObj.ra, selObj.dec, lat, lst)
                if (sPt != null) {
                    drawCircle(
                        color = colors.primary,
                        radius = 22f,
                        center = Offset(sPt.first, sPt.second),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }
        }

        // Overlay Top-Left: Digital Clock (in cutout row / left ear)
        if (timeString.isNotEmpty()) {
            Text(
                text = timeString,
                style = TextStyle(
                    fontSize = SkyMapTuning.CARDINAL_FONT_SIZE_SP.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = colors.primary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = safeStart + 2.dp, top = 8.dp)
            )
        }

        // Overlay Top Controls: In-line FOV Badge (Left) and Action Buttons (Right)
        val (centerRa, centerDec) = remember(centerAz, centerAlt, lat, lst) {
            AstrometryUtils.altAzToRaDec(centerAz, centerAlt, lat, lst)
        }
        val topControlsY = maxOf(safeTop, 36.dp)

        Card(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = safeStart, top = topControlsY),
            colors = CardDefaults.cardColors(containerColor = colors.surface.copy(alpha = 0.75f)),
            border = BorderStroke(1.dp, colors.outline),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text(
                    text = "RA ${formatRa(centerRa)} Dec ${formatDec(centerDec)}",
                    style = TextStyle(
                        fontSize = SkyMapTuning.BADGE_FONT_SIZE_SP.sp,
                        fontWeight = FontWeight.Normal
                    ),
                    color = colors.primary
                )
                Text(
                    text = "Az ${String.format("%.0f", centerAz)}° Alt ${
                        String.format(
                            "%.0f",
                            centerAlt
                        )
                    }°  FOV ${String.format("%.1f", fov)}°",
                    style = TextStyle(
                        fontSize = SkyMapTuning.BADGE_FONT_SIZE_SP.sp,
                        fontWeight = FontWeight.Normal
                    ),
                    color = colors.secondary
                )
            }
        }

        // Overlay Top-Right: Action buttons ("Center Scope", "Center Target", "Close")
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = topControlsY, end = safeEnd),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (stats.targetRa != null && stats.targetDec != null) {
                OutlinedIconButton(
                    onClick = {
                        isCameraLocked = false
                        val (tAz, tAlt) = AstrometryUtils.raDecToAltAz(
                            stats.targetRa,
                            stats.targetDec,
                            lat,
                            lst
                        )
                        centerAz = tAz
                        centerAlt = tAlt
                        fov = 30.0
                    },
                    border = BorderStroke(1.dp, colors.primary),
                    colors = IconButtonDefaults.outlinedIconButtonColors(
                        containerColor = colors.surface.copy(
                            alpha = 0.6f
                        )
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.GpsFixed,
                        contentDescription = stringResource(Res.string.btn_center_target),
                        tint = colors.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (stats.ra != null && stats.dec != null) {
                OutlinedIconButton(
                    onClick = {
                        isCameraLocked = true
                        val (cAz, cAlt) = AstrometryUtils.raDecToAltAz(
                            stats.ra,
                            stats.dec,
                            lat,
                            lst
                        )
                        centerAz = cAz
                        centerAlt = cAlt
                        fov = maxOf(stats.fov ?: 15.0, 25.0)
                    },
                    border = BorderStroke(
                        1.dp,
                        if (isCameraLocked) colors.primary else colors.outline
                    ),
                    colors = IconButtonDefaults.outlinedIconButtonColors(
                        containerColor = if (isCameraLocked) colors.primary.copy(alpha = 0.25f) else colors.surface.copy(
                            alpha = 0.6f
                        )
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CenterFocusStrong,
                        contentDescription = stringResource(Res.string.btn_center_telescope),
                        tint = colors.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Visual Close/Exit button for iOS & Touch parity
            OutlinedIconButton(
                onClick = onToggleExpanded,
                border = BorderStroke(1.dp, colors.outline),
                colors = IconButtonDefaults.outlinedIconButtonColors(
                    containerColor = colors.surface.copy(
                        alpha = 0.6f
                    )
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(id = Res.string.cd_close),
                    tint = colors.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Overlay Bottom-Right: Zoom +/- Controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedIconButton(
                onClick = { fov = (fov / 1.4).coerceIn(0.5, 180.0) },
                border = BorderStroke(1.dp, colors.outline),
                colors = IconButtonDefaults.outlinedIconButtonColors(
                    containerColor = colors.surface.copy(
                        alpha = 0.6f
                    )
                ),
                shape = CircleShape,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Zoom In", tint = colors.primary)
            }
            OutlinedIconButton(
                onClick = { fov = (fov * 1.4).coerceIn(0.5, 180.0) },
                border = BorderStroke(1.dp, colors.outline),
                colors = IconButtonDefaults.outlinedIconButtonColors(
                    containerColor = colors.surface.copy(
                        alpha = 0.6f
                    )
                ),
                shape = CircleShape,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Zoom Out", tint = colors.primary)
            }
        }

        // Overlay Bottom-Left: Mini Guidance Bubble (shown only when a GoTo is active)
        if (stats.targetRa != null && stats.targetDec != null) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = safeStart, bottom = 8.dp)
                    .height(88.dp)
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
                        isAltAz = viewModel.settingsManager.guidanceMode == "alt_az",
                        isLowPowerMode = false,
                        isImuEnabled = viewModel.settingsManager.motionSensorMode != 0,
                        isCompactLayout = false
                    )
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
                                "Designation: ",
                                fontWeight = FontWeight.Bold,
                                color = colors.primary
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(obj.designation, color = colors.primary)
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    Row {
                        Text("Type: ", fontWeight = FontWeight.Bold, color = colors.primary)
                        Spacer(Modifier.width(4.dp))
                        Text(obj.objectType, color = colors.primary)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row {
                        Text("Magnitude: ", fontWeight = FontWeight.Bold, color = colors.primary)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (obj.magnitude != null) String.format(
                                "%.2f",
                                obj.magnitude
                            ) else "N/A", color = colors.primary
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    if (!obj.constellation.isNullOrBlank()) {
                        Row {
                            Text(
                                "Constellation: ",
                                fontWeight = FontWeight.Bold,
                                color = colors.primary
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(obj.constellation, color = colors.primary)
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    Row {
                        Text("RA (J2000): ", fontWeight = FontWeight.Bold, color = colors.primary)
                        Spacer(Modifier.width(4.dp))
                        Text(formatRa(obj.ra), color = colors.primary)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row {
                        Text("Dec (J2000): ", fontWeight = FontWeight.Bold, color = colors.primary)
                        Spacer(Modifier.width(4.dp))
                        Text(formatDec(obj.dec), color = colors.primary)
                    }
                    Spacer(Modifier.height(12.dp))
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        IconBarItem(
                            icon = Icons.Default.GpsFixed,
                            label = "GoTo",
                            onClick = {
                                viewModel.setGoToTarget(obj.ra, obj.dec)
                                selectedObject = null
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Computes a 3x3 projective homography matrix mapping rectangle [0, srcW] x [0, srcH]
 * into quadrilateral (p0, p1, p2, p3).
 *
 * p0 = Top-Left
 * p1 = Top-Right
 * p2 = Bottom-Right
 * p3 = Bottom-Left
 *
 * Mirrors coordinates along X or Y if [mirrorScaleX] < 0 or [mirrorScaleY] < 0,
 * matching the orientation and mirroring logic in EngineRunningScreen.
 */
private fun computeQuadHomography(
    srcW: Float,
    srcH: Float,
    p0: Pair<Float, Float>,
    p1: Pair<Float, Float>,
    p2: Pair<Float, Float>,
    p3: Pair<Float, Float>,
    mirrorScaleX: Float = 1f,
    mirrorScaleY: Float = 1f
): Matrix {
    var c0 = p0
    var c1 = p1
    var c2 = p2
    var c3 = p3

    if (mirrorScaleX < 0f) {
        val t0 = c0;
        val t3 = c3
        c0 = c1; c1 = t0
        c3 = c2; c2 = t3
    }
    if (mirrorScaleY < 0f) {
        val t0 = c0;
        val t1 = c1
        c0 = c3; c3 = t0
        c1 = c2; c2 = t1
    }

    val x0 = c0.first
    val y0 = c0.second
    val x1 = c1.first
    val y1 = c1.second
    val x2 = c2.first
    val y2 = c2.second
    val x3 = c3.first
    val y3 = c3.second

    val dx1 = x1 - x2
    val dx2 = x3 - x2
    val dx3 = x0 - x1 + x2 - x3
    val dy1 = y1 - y2
    val dy2 = y3 - y2
    val dy3 = y0 - y1 + y2 - y3

    var g = 0f
    var h = 0f
    if (kotlin.math.abs(dx3) >= 1e-4f || kotlin.math.abs(dy3) >= 1e-4f) {
        val det = dx1 * dy2 - dy1 * dx2
        if (kotlin.math.abs(det) >= 1e-7f) {
            g = (dx3 * dy2 - dy3 * dx2) / det
            h = (dx1 * dy3 - dy1 * dx3) / det
        }
    }

    val a = x1 - x0 + g * x1
    val b = x3 - x0 + h * x3
    val c = x0
    val d = y1 - y0 + g * y1
    val e = y3 - y0 + h * y3
    val f = y0

    return Matrix(
        floatArrayOf(
            a / srcW, d / srcW, 0f, g / srcW,
            b / srcH, e / srcH, 0f, h / srcH,
            0f, 0f, 1f, 0f,
            c, f, 0f, 1f
        )
    )
}

