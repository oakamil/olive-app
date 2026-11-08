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
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import org.jetbrains.compose.resources.stringResource
import kotlin.math.hypot
import kotlin.time.Duration.Companion.milliseconds

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
    var centerRa by remember { mutableDoubleStateOf(stats.ra ?: 180.0) }
    var centerDec by remember { mutableDoubleStateOf(stats.dec ?: 20.0) }
    var fov by remember { mutableDoubleStateOf(maxOf(stats.fov ?: 30.0, 50.0)) }

    // Auto-center on first valid solve if initialized before plate solving begins
    var hasAutoCentered by remember { mutableStateOf(stats.ra != null) }
    LaunchedEffect(stats.ra, stats.dec) {
        if (!hasAutoCentered && stats.ra != null && stats.dec != null) {
            centerRa = stats.ra
            centerDec = stats.dec
            fov = maxOf(stats.fov ?: 30.0, 50.0)
            hasAutoCentered = true
        }
    }

    val rawLat = viewModel.currentLat
    val rawLon = viewModel.currentLon
    val hasValidLocation = rawLat != null && rawLon != null &&
            rawLat != MainViewModel.INVALID_COORD && rawLon != MainViewModel.INVALID_COORD &&
            rawLat in -90.0..90.0 && rawLon in -180.0..360.0
    val lat = if (hasValidLocation) rawLat!! else 0.0
    val lon = if (hasValidLocation) rawLon!! else 0.0

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

    val coroutineScope = rememberCoroutineScope()
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
    val zenithTextLayout = remember(textMeasurer, zenithText) {
        textMeasurer.measure(
            text = zenithText,
            style = TextStyle(color = DarkRed, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        )
    }
    val nadirTextLayout = remember(textMeasurer, nadirText) {
        textMeasurer.measure(
            text = nadirText,
            style = TextStyle(color = DarkRed, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        )
    }
    val cardinalLayouts = remember(textMeasurer) {
        mapOf(
            "N" to textMeasurer.measure(
                "N",
                style = TextStyle(color = PureRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            ),
            "E" to textMeasurer.measure(
                "E",
                style = TextStyle(color = PureRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            ),
            "S" to textMeasurer.measure(
                "S",
                style = TextStyle(color = PureRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            ),
            "W" to textMeasurer.measure(
                "W",
                style = TextStyle(color = PureRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            )
        )
    }
    val constellationLayouts = remember(textMeasurer) {
        ConstellationData.CONSTELLATIONS.associate { c ->
            c.name to textMeasurer.measure(
                text = c.name,
                style = TextStyle(
                    color = DarkRed.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            )
        }
    }

    // Tier 1 in-memory objects (Bright stars & Messier)
    var tier1Stars by remember { mutableStateOf<List<CatalogObject>>(emptyList()) }
    var tier1Messier by remember { mutableStateOf<List<CatalogObject>>(emptyList()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            tier1Stars = viewModel.catalogDb.searchObjects(
                starFilter = true,
                magLimit = 5.0,
                visibilityFilter = "Any",
                sortBy = "brightness",
                limit = 1000
            )
            tier1Messier = viewModel.catalogDb.searchObjects(
                catalogFilter = "M",
                visibilityFilter = "Any",
                limit = 110
            )
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

    // Tier 2 dynamic background query for dense deep sky objects when zoomed in
    var dynamicCatalogObjects by remember { mutableStateOf<List<CatalogObject>>(emptyList()) }
    val currentTypeFilters = viewModel.catalogTypeFilters
    val currentMagLimit = viewModel.catalogMagLimit
    val isAllTypes =
        currentTypeFilters.isEmpty() || currentTypeFilters == DEFAULT_CATALOG_TYPE_FILTERS

    LaunchedEffect(currentTypeFilters, currentMagLimit, isAllTypes) {
        snapshotFlow { Triple(centerRa, centerDec, fov) }
            .debounce(200.milliseconds)
            .collectLatest { (ra, dec, currentFov) ->
                if (currentFov < 60.0) {
                    withContext(Dispatchers.IO) {
                        val searchRadius = (currentFov * 0.75).coerceIn(5.0, 45.0)
                        val objects = viewModel.catalogDb.searchObjects(
                            targetRa = ra,
                            targetDec = dec,
                            maxDistanceDegrees = searchRadius,
                            magLimit = currentMagLimit,
                            typeFilters = if (isAllTypes) null else currentTypeFilters,
                            starFilter = false,
                            visibilityFilter = "Any",
                            sortBy = "brightness",
                            limit = 200
                        )
                        dynamicCatalogObjects = objects
                    }
                } else {
                    dynamicCatalogObjects = emptyList()
                }
            }
    }

    val starMagCutoff = when {
        fov > 90.0 -> 4.2
        fov > 45.0 -> 5.0
        else -> currentMagLimit ?: 6.5
    }

    // Pre-filter and deduplicate candidates outside the Canvas draw loop to avoid allocations at 60fps
    val visibleCandidates = remember(
        solPlanets, solComets, solAsteroids,
        tier1Messier, tier1Stars, dynamicCatalogObjects,
        currentTypeFilters, currentMagLimit, isAllTypes, starMagCutoff
    ) {
        val list = mutableListOf<CatalogObject>()

        // 1. Solar System
        list.addAll(solPlanets.filter { p ->
            (isAllTypes || "Planet" in currentTypeFilters || p.objectType in currentTypeFilters) &&
                    (currentMagLimit == null || (p.magnitude != null && p.magnitude <= currentMagLimit))
        })
        list.addAll(solComets.filter { c ->
            (isAllTypes || "Comet" in currentTypeFilters || c.objectType in currentTypeFilters) &&
                    (currentMagLimit == null || (c.magnitude != null && c.magnitude <= currentMagLimit))
        })
        list.addAll(solAsteroids.filter { a ->
            (isAllTypes || "Asteroid" in currentTypeFilters || a.objectType in currentTypeFilters) &&
                    (currentMagLimit == null || (a.magnitude != null && a.magnitude <= currentMagLimit))
        })

        // 2. Messier & DSOs
        list.addAll(tier1Messier.filter { m ->
            (isAllTypes || m.objectType in currentTypeFilters || m.broadCategory in currentTypeFilters) &&
                    (currentMagLimit == null || (m.magnitude != null && m.magnitude <= currentMagLimit))
        })

        // 3. Dynamic Tier 2 DSOs
        list.addAll(dynamicCatalogObjects)

        // 4. Stars
        list.addAll(tier1Stars.filter { (it.magnitude ?: 99.0) <= starMagCutoff })

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
            .background(PureBlack)
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()

        val projection = remember(centerRa, centerDec, fov, widthPx, heightPx) {
            SkyMapProjection(
                centerRa = centerRa,
                centerDec = centerDec,
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
                            fov = (fov / zoom.toDouble()).coerceIn(4.0, 180.0)
                        }
                        if (pan != Offset.Zero) {
                            val proj = currentProjection
                            proj.panBy(pan.x, pan.y)
                            centerRa = proj.centerRa
                            centerDec = proj.centerDec
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
                val horizonPoints = SkyMapProjection.computeHorizonPoints(lat, lst, steps = 72)
                val horizonPath = Path()
                var firstPt: Offset? = null
                var prevPt: Offset? = null

                for ((hRa, hDec) in horizonPoints) {
                    val pt = projection.toScreen(hRa, hDec)
                    if (pt != null) {
                        val off = Offset(pt.first, pt.second)
                        if (firstPt == null) {
                            horizonPath.moveTo(off.x, off.y)
                            firstPt = off
                        } else if (prevPt != null && hypot(
                                off.x - prevPt.x,
                                off.y - prevPt.y
                            ) < widthPx * 0.5f
                        ) {
                            horizonPath.lineTo(off.x, off.y)
                        } else {
                            horizonPath.moveTo(off.x, off.y)
                        }
                        prevPt = off
                    } else {
                        prevPt = null
                    }
                }
                if (firstPt != null && prevPt != null && hypot(
                        firstPt.x - prevPt.x,
                        firstPt.y - prevPt.y
                    ) < widthPx * 0.5f
                ) {
                    horizonPath.close()
                }

                drawPath(
                    path = horizonPath,
                    color = DarkRed,
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = horizonDashEffect)
                )

                // 2. Cardinal Points (N, E, S, W)
                val cardinals = SkyMapProjection.computeCardinalPoints(lat, lst)
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
                                color = PureRed,
                                topLeft = Offset(cLeft, cTop)
                            )
                        }
                    }
                }

                // 3. Zenith & Nadir (Simple crosshair + title-case label in DarkRed)
                val (zenithRa, zenithDec) = SkyMapProjection.getZenith(lst, lat)
                val zenithPt = projection.toScreen(zenithRa, zenithDec)
                if (zenithPt != null && zenithPt.first in -50f..size.width + 50f && zenithPt.second in -50f..size.height + 50f) {
                    val zArm = 8.dp.toPx()
                    val zX = zenithPt.first
                    val zY = zenithPt.second
                    // Simple crosshair (+)
                    drawLine(
                        DarkRed,
                        Offset(zX - zArm, zY),
                        Offset(zX + zArm, zY),
                        strokeWidth = 1.5.dp.toPx()
                    )
                    drawLine(
                        DarkRed,
                        Offset(zX, zY - zArm),
                        Offset(zX, zY + zArm),
                        strokeWidth = 1.5.dp.toPx()
                    )
                    val zTop = zY - zenithTextLayout.size.height / 2f
                    val zLeft = zX + 6.dp.toPx()
                    placedLabelRects.add(
                        Rect(
                            zLeft - 2f,
                            zTop - 2f,
                            zLeft + zenithTextLayout.size.width + 2f,
                            zTop + zenithTextLayout.size.height + 2f
                        )
                    )
                    drawText(zenithTextLayout, color = DarkRed, topLeft = Offset(zLeft, zTop))
                }

                val (nadirRa, nadirDec) = SkyMapProjection.getNadir(lst, lat)
                val nadirPt = projection.toScreen(nadirRa, nadirDec)
                if (nadirPt != null && nadirPt.first in -50f..size.width + 50f && nadirPt.second in -50f..size.height + 50f) {
                    val nArm = 8.dp.toPx()
                    val nX = nadirPt.first
                    val nY = nadirPt.second
                    // Simple crosshair (+)
                    drawLine(
                        DarkRed,
                        Offset(nX - nArm, nY),
                        Offset(nX + nArm, nY),
                        strokeWidth = 1.5.dp.toPx()
                    )
                    drawLine(
                        DarkRed,
                        Offset(nX, nY - nArm),
                        Offset(nX, nY + nArm),
                        strokeWidth = 1.5.dp.toPx()
                    )
                    val nTop = nY - nadirTextLayout.size.height / 2f
                    val nLeft = nX + 6.dp.toPx()
                    placedLabelRects.add(
                        Rect(
                            nLeft - 2f,
                            nTop - 2f,
                            nLeft + nadirTextLayout.size.width + 2f,
                            nTop + nadirTextLayout.size.height + 2f
                        )
                    )
                    drawText(nadirTextLayout, color = DarkRed, topLeft = Offset(nLeft, nTop))
                }
            }

            // 4. Batched Constellation Stick Figures (Single Path in DarkRed)
            val constellationPath = Path()
            for (line in ConstellationData.LINES) {
                val p1 = projection.toScreen(line.ra1, line.dec1) ?: continue
                val p2 = projection.toScreen(line.ra2, line.dec2) ?: continue

                // Check distance to avoid cross-screen lines if wrap occurs
                if (hypot(p1.first - p2.first, p1.second - p2.second) < widthPx * 0.8f) {
                    constellationPath.moveTo(p1.first, p1.second)
                    constellationPath.lineTo(p2.first, p2.second)
                }
            }
            drawPath(
                path = constellationPath,
                color = DarkRed,
                style = Stroke(width = 1.5.dp.toPx())
            )

            // 5. Constellation Names
            if (fov in 20.0..130.0) {
                for (constell in ConstellationData.CONSTELLATIONS) {
                    val pt = projection.toScreen(constell.centerRa, constell.centerDec)
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
                                color = DarkRed.copy(alpha = 0.85f),
                                topLeft = Offset(cLeft, cTop)
                            )
                        }
                    }
                }
            }

            // 6. Stars & Catalog Objects Rendering
            val (starObjects, nonStarObjects) = visibleCandidates.partition { it.objectType == "Star" || it.catalogLabel == "Str" }

            // Pass 1: DSOs, Planets, Comets, Asteroids (placed first to establish priority icons and decrowding)
            val dsoCandidates = nonStarObjects.mapNotNull { obj ->
                projection.toScreen(obj.ra, obj.dec)?.let { (x, y) -> obj to Offset(x, y) }
            }
            val placedDsoItems = layoutCatalogItems(
                candidates = dsoCandidates,
                textMeasurer = textMeasurer,
                viewportWidth = size.width,
                viewportHeight = size.height,
                targetRa = stats.ra,
                targetDec = stats.dec,
                maxLabels = if (fov < 80.0) 25 else 0,
                fontSizeSp = 10f,
                placedReticles = placedReticleRects,
                placedLabels = placedLabelRects
            )

            for (item in placedDsoItems) {
                val isAbove = !hasValidLocation || SkyMapProjection.isAboveHorizon(
                    item.obj.ra,
                    item.obj.dec,
                    lat,
                    lst
                )
                val baseColor = if (isAbove) PureRed else DarkRed.copy(alpha = 0.45f)
                val screenPt = Offset(item.screenX, item.screenY)

                val (iconVector, _) = item.obj.getIconAndColor()
                val painter = catalogPainters[iconVector]
                val iconSize = 18f

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
                            fontSize = 10.sp,
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
            for (obj in starObjects) {
                val pt = projection.toScreen(obj.ra, obj.dec) ?: continue
                if (pt.first < -20f || pt.first > size.width + 20f || pt.second < -20f || pt.second > size.height + 20f) continue

                val isAbove =
                    !hasValidLocation || SkyMapProjection.isAboveHorizon(obj.ra, obj.dec, lat, lst)
                val baseColor = if (isAbove) PureRed else DarkRed.copy(alpha = 0.45f)
                val screenPt = Offset(pt.first, pt.second)

                // Decrowding: skip star dot if an icon is already placed here or another star is within 3px
                if (placedReticleRects.any { it.contains(screenPt) }) continue
                if (placedStarPoints.any {
                        hypot(
                            it.x - screenPt.x,
                            it.y - screenPt.y
                        ) < 3f
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
                val radius =
                    ((6.5 - mag).coerceIn(1.0, 5.5) * (if (fov < 45.0) 1.3 else 1.0)).toFloat()
                drawCircle(
                    color = baseColor,
                    radius = radius,
                    center = screenPt
                )
            }

            allPlacedItems.clear()
            allPlacedItems.addAll(placedDsoItems)
            allPlacedItems.addAll(placedStarItems)

            // 7. Rotated Dashed Camera FOV Box
            val camRa = stats.ra
            val camDec = stats.dec
            if (camRa != null && camDec != null) {
                val camFov = stats.fov ?: 8.0
                val camRoll = stats.roll ?: 0.0
                val aspect =
                    viewModel.latestFrame?.let { it.width.toDouble() / it.height.toDouble() }
                        ?: 1.333
                val corners = SkyMapProjection.computeCameraFovCorners(
                    camRa,
                    camDec,
                    camFov,
                    camRoll,
                    imageAspect = aspect
                )

                val cornerPoints = corners.mapNotNull { projection.toScreen(it.first, it.second) }
                if (cornerPoints.size == 4) {
                    val boxPath = Path().apply {
                        moveTo(cornerPoints[0].first, cornerPoints[0].second)
                        lineTo(cornerPoints[1].first, cornerPoints[1].second)
                        lineTo(cornerPoints[2].first, cornerPoints[2].second)
                        lineTo(cornerPoints[3].first, cornerPoints[3].second)
                        close()
                    }
                    val isEstimate = stats.isImuEstimate || stats.solvedCrop == "Failed"
                    val boxColor = if (isEstimate) DarkRed.copy(alpha = 0.7f) else PureRed

                    drawPath(
                        path = boxPath,
                        color = boxColor,
                        style = Stroke(width = 2.dp.toPx(), pathEffect = fovDashEffect)
                    )
                }

                // Boresight Crosshair
                val bPt = projection.toScreen(camRa, camDec)
                if (bPt != null) {
                    val bArm = 10f
                    drawLine(
                        DarkRed,
                        Offset(bPt.first - bArm, bPt.second),
                        Offset(bPt.first + bArm, bPt.second),
                        strokeWidth = 2f
                    )
                    drawLine(
                        DarkRed,
                        Offset(bPt.first, bPt.second - bArm),
                        Offset(bPt.first, bPt.second + bArm),
                        strokeWidth = 2f
                    )
                }
            }

            // 8. Target Marker & Bearing Line
            val targetRa = stats.targetRa
            val targetDec = stats.targetDec
            if (targetRa != null && targetDec != null) {
                val tPt = projection.toScreen(targetRa, targetDec)
                if (tPt != null) {
                    val s = 14f
                    val stroke = 2.dp.toPx()
                    // Corner brackets
                    drawLine(
                        PureRed,
                        Offset(tPt.first - s, tPt.second - s),
                        Offset(tPt.first - s + 8f, tPt.second - s),
                        strokeWidth = stroke
                    )
                    drawLine(
                        PureRed,
                        Offset(tPt.first - s, tPt.second - s),
                        Offset(tPt.first - s, tPt.second - s + 8f),
                        strokeWidth = stroke
                    )
                    drawLine(
                        PureRed,
                        Offset(tPt.first + s, tPt.second - s),
                        Offset(tPt.first + s - 8f, tPt.second - s),
                        strokeWidth = stroke
                    )
                    drawLine(
                        PureRed,
                        Offset(tPt.first + s, tPt.second - s),
                        Offset(tPt.first + s, tPt.second - s + 8f),
                        strokeWidth = stroke
                    )
                    drawLine(
                        PureRed,
                        Offset(tPt.first - s, tPt.second + s),
                        Offset(tPt.first - s + 8f, tPt.second + s),
                        strokeWidth = stroke
                    )
                    drawLine(
                        PureRed,
                        Offset(tPt.first - s, tPt.second + s),
                        Offset(tPt.first - s, tPt.second + s - 8f),
                        strokeWidth = stroke
                    )
                    drawLine(
                        PureRed,
                        Offset(tPt.first + s, tPt.second + s),
                        Offset(tPt.first + s - 8f, tPt.second + s),
                        strokeWidth = stroke
                    )
                    drawLine(
                        PureRed,
                        Offset(tPt.first + s, tPt.second + s),
                        Offset(tPt.first + s, tPt.second + s - 8f),
                        strokeWidth = stroke
                    )

                    // Guide line from camera boresight to target
                    if (camRa != null && camDec != null) {
                        val cPt = projection.toScreen(camRa, camDec)
                        if (cPt != null) {
                            drawLine(
                                color = PureRed.copy(alpha = 0.5f),
                                start = Offset(cPt.first, cPt.second),
                                end = Offset(tPt.first, tPt.second),
                                strokeWidth = 1.5.dp.toPx(),
                                pathEffect = horizonDashEffect
                            )
                        }
                    }
                }
            }

            // 9. Selected Object Highlight Ring
            val selObj = selectedObject
            if (selObj != null) {
                val sPt = projection.toScreen(selObj.ra, selObj.dec)
                if (sPt != null) {
                    drawCircle(
                        color = PureRed,
                        radius = 22f,
                        center = Offset(sPt.first, sPt.second),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }
        }

        // Overlay Top-Left: Coordinate & FOV Badge
        Card(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = PureBlack.copy(alpha = 0.7f)),
            border = BorderStroke(1.dp, DarkRed),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text(
                    text = "RA ${formatRa(centerRa)} Dec ${formatDec(centerDec)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = PureRed
                )
                Text(
                    text = "FOV ${String.format("%.1f", fov)}°",
                    style = MaterialTheme.typography.labelSmall,
                    color = DarkRed
                )
            }
        }

        // Overlay Top-Right: Action buttons ("Center Scope", "Center Target", "Close")
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (stats.targetRa != null && stats.targetDec != null) {
                OutlinedIconButton(
                    onClick = {
                        centerRa = stats.targetRa
                        centerDec = stats.targetDec
                        fov = 30.0
                    },
                    border = BorderStroke(1.dp, PureRed),
                    colors = IconButtonDefaults.outlinedIconButtonColors(
                        containerColor = PureBlack.copy(
                            alpha = 0.6f
                        )
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.GpsFixed,
                        contentDescription = stringResource(Res.string.btn_center_target),
                        tint = PureRed,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (stats.ra != null && stats.dec != null) {
                OutlinedIconButton(
                    onClick = {
                        centerRa = stats.ra
                        centerDec = stats.dec
                        fov = maxOf(stats.fov ?: 15.0, 25.0)
                    },
                    border = BorderStroke(1.dp, DarkRed),
                    colors = IconButtonDefaults.outlinedIconButtonColors(
                        containerColor = PureBlack.copy(
                            alpha = 0.6f
                        )
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CenterFocusStrong,
                        contentDescription = stringResource(Res.string.btn_center_telescope),
                        tint = PureRed,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Visual Close/Exit button for iOS & Touch parity
            OutlinedIconButton(
                onClick = onToggleExpanded,
                border = BorderStroke(1.dp, DarkRed),
                colors = IconButtonDefaults.outlinedIconButtonColors(
                    containerColor = PureBlack.copy(
                        alpha = 0.6f
                    )
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(id = Res.string.cd_close),
                    tint = PureRed,
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
                onClick = { fov = (fov / 1.4).coerceIn(4.0, 180.0) },
                border = BorderStroke(1.dp, DarkRed),
                colors = IconButtonDefaults.outlinedIconButtonColors(
                    containerColor = PureBlack.copy(
                        alpha = 0.6f
                    )
                ),
                shape = CircleShape,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Zoom In", tint = PureRed)
            }
            OutlinedIconButton(
                onClick = { fov = (fov * 1.4).coerceIn(4.0, 180.0) },
                border = BorderStroke(1.dp, DarkRed),
                colors = IconButtonDefaults.outlinedIconButtonColors(
                    containerColor = PureBlack.copy(
                        alpha = 0.6f
                    )
                ),
                shape = CircleShape,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Zoom Out", tint = PureRed)
            }
        }

        // Overlay Bottom-Left: Toggle back to Camera View
        OutlinedIconButton(
            onClick = onToggleExpanded,
            border = BorderStroke(1.5.dp, PureRed),
            colors = IconButtonDefaults.outlinedIconButtonColors(
                containerColor = PureBlack.copy(alpha = 0.7f),
                contentColor = PureRed
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Videocam,
                contentDescription = stringResource(Res.string.btn_camera_view),
                tint = PureRed
            )
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
                            Text("Designation: ", fontWeight = FontWeight.Bold, color = PureRed)
                            Spacer(Modifier.width(4.dp))
                            Text(obj.designation, color = PureRed)
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    Row {
                        Text("Type: ", fontWeight = FontWeight.Bold, color = PureRed)
                        Spacer(Modifier.width(4.dp))
                        Text(obj.objectType, color = PureRed)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row {
                        Text("Magnitude: ", fontWeight = FontWeight.Bold, color = PureRed)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (obj.magnitude != null) String.format(
                                "%.2f",
                                obj.magnitude
                            ) else "N/A", color = PureRed
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    if (!obj.constellation.isNullOrBlank()) {
                        Row {
                            Text("Constellation: ", fontWeight = FontWeight.Bold, color = PureRed)
                            Spacer(Modifier.width(4.dp))
                            Text(obj.constellation, color = PureRed)
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    Row {
                        Text("RA (J2000): ", fontWeight = FontWeight.Bold, color = PureRed)
                        Spacer(Modifier.width(4.dp))
                        Text(formatRa(obj.ra), color = PureRed)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row {
                        Text("Dec (J2000): ", fontWeight = FontWeight.Bold, color = PureRed)
                        Spacer(Modifier.width(4.dp))
                        Text(formatDec(obj.dec), color = PureRed)
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
