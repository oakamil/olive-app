// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.time.TimeSource

private const val MAX_PREVIEW_ZOOM = 4.0f
private const val MIN_MULTI_ORDER_FOV_DEG = 1.0
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val DOUBLE_TAP_TIMEOUT_MS = 300L

/**
 * Displays either a Zenith-up pannable/zoomable DSS2 cutout when all required tiles for
 * [catalogObject] are locally available (running full multi-order HEALPix LOD when the base sky
 * pack is installed for `O0–O4` objects), or a button to download the missing tiles on demand.
 */
@Composable
fun ObjectHiPSPreview(
    catalogObject: CatalogObject,
    coLocated: List<CatalogObject>,
    viewModel: MainViewModel,
    timeMs: Long,
    modifier: Modifier = Modifier
) {
    if (!HiPSObjectPlanner.isEligibleObject(catalogObject)) return

    val majorArcmin = remember(catalogObject, coLocated) {
        HiPSObjectPlanner.resolveMajorAxisArcmin(catalogObject, coLocated)
    }
    val cutoutPlan = remember(catalogObject.ra, catalogObject.dec, majorArcmin) {
        HiPSObjectPlanner.planCutout(catalogObject.ra, catalogObject.dec, majorArcmin)
    }
    val missingKeys = remember(cutoutPlan, viewModel.isSkyPackDownloaded, viewModel.hqIndex) {
        HiPSObjectPlanner.filterMissingKeys(
            cutoutPlan.requiredKeys,
            viewModel.isSkyPackDownloaded,
            viewModel.hqIndex
        )
    }

    if (missingKeys.isNotEmpty()) {
        val targetId = viewModel.objectHqTargetId(catalogObject)
        val dlState = viewModel.hqDownloadState
        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            when {
                dlState is HiPSHqDownloadState.Downloading && dlState.targetId == targetId -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                Res.string.hips_hq_single_downloading,
                                dlState.completedTiles,
                                dlState.totalTiles
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                dlState is HiPSHqDownloadState.Failed && dlState.targetId == targetId -> {
                    Text(
                        text = hipsFailureMessage(dlState.reason),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(
                        onClick = { viewModel.downloadObjectHqImage(catalogObject, coLocated) }
                    ) {
                        Text(text = stringResource(Res.string.hips_action_retry))
                    }
                }

                else -> {
                    OutlinedButton(
                        onClick = { viewModel.downloadObjectHqImage(catalogObject, coLocated) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(Res.string.btn_download_hq_image))
                    }
                }
            }
        }
        return
    }

    val useMultiOrderHealpix =
        viewModel.isSkyPackDownloaded && cutoutPlan.order <= Healpix.MAX_ORDER

    val previewTileManager = remember(cutoutPlan, useMultiOrderHealpix) {
        if (useMultiOrderHealpix) viewModel.openPreviewTileManager() else null
    }
    DisposableEffect(previewTileManager) {
        onDispose {
            previewTileManager?.let { viewModel.closePreviewTileManager(it) }
        }
    }

    var loadedTiles by remember(cutoutPlan, useMultiOrderHealpix) {
        mutableStateOf<List<Pair<Int, ImageBitmap>>?>(null)
    }
    LaunchedEffect(cutoutPlan, useMultiOrderHealpix) {
        if (!useMultiOrderHealpix) {
            loadedTiles = viewModel.loadCutoutTiles(cutoutPlan.requiredKeys)
        }
    }

    val tiles = loadedTiles
    if (useMultiOrderHealpix && previewTileManager == null) return
    if (!useMultiOrderHealpix && tiles != null && tiles.isEmpty()) return

    val rawLat = viewModel.currentLat
    val rawLon = viewModel.currentLon
    val useTopocentric = viewModel.hasValidLocation && rawLat != null && rawLon != null
    val effectiveLat = if (useTopocentric) rawLat else 90.0
    // Intentionally excludes timeMs so live clock ticks do not reset user pan/zoom orientation.
    val effectiveLst = remember(useTopocentric, rawLon, catalogObject) {
        if (useTopocentric) AstrometryUtils.calculateLST(rawLon, timeMs) else 0.0
    }
    val (objAz, objAlt) = remember(
        catalogObject.ra,
        catalogObject.dec,
        effectiveLat,
        effectiveLst
    ) {
        AstrometryUtils.raDecToAltAz(
            catalogObject.ra,
            catalogObject.dec,
            effectiveLat,
            effectiveLst
        )
    }

    val maxZoom = if (useMultiOrderHealpix) {
        maxOf(MAX_PREVIEW_ZOOM, (cutoutPlan.baseFovDeg / MIN_MULTI_ORDER_FOV_DEG).toFloat())
    } else {
        MAX_PREVIEW_ZOOM
    }
    var zoom by remember(cutoutPlan) { mutableFloatStateOf(1f) }
    var panAz by remember(cutoutPlan, objAz) { mutableDoubleStateOf(objAz) }
    var panAlt by remember(cutoutPlan, objAlt) { mutableDoubleStateOf(objAlt) }
    LaunchedEffect(maxZoom) {
        if (zoom > maxZoom) zoom = maxZoom
    }

    val renderState = remember(cutoutPlan) { HiPSRenderState() }
    DisposableEffect(renderState) {
        onDispose { renderState.clear() }
    }

    val extColors = LocalOliveExtendedColors.current
    val isNightVision = viewModel.currentTheme != "blue"
    val currentFovDeg = cutoutPlan.baseFovDeg / zoom.toDouble()
    val isLoading = if (useMultiOrderHealpix) {
        previewTileManager?.isBaseReady != true
    } else {
        tiles == null
    }
    val isTransformed = zoom > 1.01f ||
            abs(panAlt - objAlt) > 1e-3 ||
            abs(((panAz - objAz + 540.0) % 360.0) - 180.0) > 1e-3
    val shape = RoundedCornerShape(12.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(shape)
            .background(extColors.skyBackground)
            .border(1.dp, extColors.subtleBorder, shape)
            .pointerInput(cutoutPlan, objAz, objAlt, maxZoom) {
                val tapClock = TimeSource.Monotonic.markNow()
                var lastTapMs = -10_000L
                var lastTapPos = Offset.Zero
                val slop = viewConfiguration.touchSlop

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var pastSlop = false
                    var zoomAcc = 1f
                    var panAcc = Offset.Zero

                    do {
                        val event = awaitPointerEvent()
                        val canceled = event.changes.any { it.isConsumed }
                        if (!canceled) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (!pastSlop) {
                                zoomAcc *= zoomChange
                                panAcc += panChange
                                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                                val zoomMotion = abs(1f - zoomAcc) * centroidSize
                                if (zoomMotion > slop || panAcc.getDistance() > slop) {
                                    pastSlop = true
                                }
                            }
                            if (pastSlop) {
                                if (zoomChange != 1f) {
                                    zoom = (zoom * zoomChange).coerceIn(1f, maxZoom)
                                }
                                val fovNow = cutoutPlan.baseFovDeg / zoom.toDouble()
                                val maxPanDeg = cutoutPlan.baseFovDeg *
                                        (0.5 * (1.0 - 1.0 / zoom.toDouble()) + 0.08)
                                val (nextAz, nextAlt) = panAndClampZenithUp(
                                    curAzDeg = panAz,
                                    curAltDeg = panAlt,
                                    objAzDeg = objAz,
                                    objAltDeg = objAlt,
                                    deltaX = panChange.x,
                                    deltaY = panChange.y,
                                    fovDeg = fovNow,
                                    minDimPx = minOf(size.width, size.height).coerceAtLeast(1)
                                        .toFloat(),
                                    maxPanDeg = maxPanDeg
                                )
                                panAz = nextAz
                                panAlt = nextAlt
                                event.changes.forEach { change ->
                                    if (change.positionChanged()) {
                                        change.consume()
                                    }
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (!pastSlop) {
                        val nowMs = tapClock.elapsedNow().inWholeMilliseconds
                        if (nowMs - lastTapMs <= DOUBLE_TAP_TIMEOUT_MS &&
                            (down.position - lastTapPos).getDistance() <= slop * 3f
                        ) {
                            if (zoom > 1.05f) {
                                zoom = 1f
                                panAz = objAz
                                panAlt = objAlt
                            } else {
                                zoom = DOUBLE_TAP_ZOOM
                            }
                            lastTapMs = -10_000L
                        } else {
                            lastTapMs = nowMs
                            lastTapPos = down.position
                        }
                    }
                }
            }
    ) {
        if (previewTileManager != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                renderState.render(
                    drawScope = this,
                    tileManager = previewTileManager,
                    projection = SkyMapProjection(
                        centerAz = panAz,
                        centerAlt = panAlt,
                        fov = currentFovDeg,
                        viewportWidth = size.width,
                        viewportHeight = size.height
                    ),
                    latDeg = effectiveLat,
                    lstDeg = effectiveLst,
                    hasValidLocation = false,
                    isNightVision = isNightVision
                )
            }
        } else if (tiles != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                renderState.renderCutout(
                    drawScope = this,
                    order = cutoutPlan.order,
                    tiles = tiles,
                    centerAzDeg = panAz,
                    centerAltDeg = panAlt,
                    fovDeg = currentFovDeg,
                    latDeg = effectiveLat,
                    lstDeg = effectiveLst,
                    isNightVision = isNightVision
                )
            }
        }

        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(22.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Angular FOV scale badge
        val fovArcmin = currentFovDeg * 60.0
        val fovBadgeText = if (fovArcmin < 60.0) {
            val num = if (fovArcmin < 10.0) String.format("%.1f", fovArcmin) else String.format(
                "%.0f",
                fovArcmin
            )
            stringResource(Res.string.format_arcmin, num)
        } else {
            stringResource(Res.string.format_degree, String.format("%.1f", currentFovDeg))
        }
        Text(
            text = fovBadgeText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .background(extColors.mapOverlaySurface, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )

        if (isTransformed) {
            IconButton(
                onClick = {
                    zoom = 1f
                    panAz = objAz
                    panAlt = objAlt
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(32.dp)
                    .background(extColors.mapOverlaySurface, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(Res.string.cd_reset_view),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Pole-safe 3D horizontal unit-vector pan and spherical disc clamp around `(objAzDeg, objAltDeg)`.
 */
fun panAndClampZenithUp(
    curAzDeg: Double,
    curAltDeg: Double,
    objAzDeg: Double,
    objAltDeg: Double,
    deltaX: Float,
    deltaY: Float,
    fovDeg: Double,
    minDimPx: Float,
    maxPanDeg: Double
): Pair<Double, Double> {
    val tanQuarter = tan((fovDeg.coerceIn(0.05, 180.0) * PI / 180.0) * 0.25).coerceAtLeast(1e-9)
    val scale = minDimPx.coerceAtLeast(1f).toDouble() / (4.0 * tanQuarter)
    if (scale <= 0.0) return curAzDeg to curAltDeg

    val az0 = curAzDeg * (PI / 180.0)
    val alt0 = curAltDeg * (PI / 180.0)
    val cosAz0 = cos(az0)
    val sinAz0 = sin(az0)
    val cosAlt0 = cos(alt0)
    val sinAlt0 = sin(alt0)

    // Local camera basis in (North, East, Up) matching EqToScreenProjector
    val fn = cosAlt0 * cosAz0
    val fe = cosAlt0 * sinAz0
    val fu = sinAlt0

    val rn = -sinAz0
    val re = cosAz0

    val un = -sinAlt0 * cosAz0
    val ue = -sinAlt0 * sinAz0
    val uu = cosAlt0

    val stepX = -deltaX.toDouble() / scale
    val stepY = deltaY.toDouble() / scale
    var vn = fn + stepX * rn + stepY * un
    var ve = fe + stepX * re + stepY * ue
    var vu = fu + stepY * uu
    val norm = sqrt(vn * vn + ve * ve + vu * vu).coerceAtLeast(1e-12)
    vn /= norm
    ve /= norm
    vu /= norm

    // Clamp angular separation to maxPanDeg from the object center
    val objAz = objAzDeg * (PI / 180.0)
    val objAlt = objAltDeg * (PI / 180.0)
    val cosObjAlt = cos(objAlt)
    val on = cosObjAlt * cos(objAz)
    val oe = cosObjAlt * sin(objAz)
    val ou = sin(objAlt)

    val maxRad = (maxPanDeg.coerceAtLeast(0.0)) * (PI / 180.0)
    val dot = (vn * on + ve * oe + vu * ou).coerceIn(-1.0, 1.0)
    val ang = acos(dot)
    if (ang > maxRad) {
        var tn = vn - dot * on
        var te = ve - dot * oe
        var tu = vu - dot * ou
        val tNorm = sqrt(tn * tn + te * te + tu * tu)
        if (tNorm > 1e-12) {
            tn /= tNorm
            te /= tNorm
            tu /= tNorm
            val cosM = cos(maxRad)
            val sinM = sin(maxRad)
            vn = cosM * on + sinM * tn
            ve = cosM * oe + sinM * te
            vu = cosM * ou + sinM * tu
        } else {
            vn = on
            ve = oe
            vu = ou
        }
    }

    val nextAlt = (asin(vu.coerceIn(-1.0, 1.0)) * (180.0 / PI)).coerceIn(-90.0, 90.0)
    val horiz = hypot(vn, ve)
    val nextAz = if (horiz > 1e-7) {
        ((atan2(ve, vn) * (180.0 / PI)) % 360.0 + 360.0) % 360.0
    } else {
        curAzDeg
    }
    return nextAz to nextAlt
}
