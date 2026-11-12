package com.oakamil.olive

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.BlurCircular
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.LensBlur
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.ScatterPlot
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Storm
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.hypot

fun getIconAndColorForType(
    objectType: String,
    primaryColor: Color = PureRed,
    secondaryColor: Color = DarkRed
): Pair<ImageVector, Color> {
    return when (objectType) {
        "Asterism" -> Icons.Filled.Timeline to primaryColor
        "Cluster + Nebula" -> Icons.Filled.BlurCircular to primaryColor
        "Nebula" -> Icons.Filled.Air to primaryColor
        "Dark Nebula" -> Icons.Filled.Cloud to secondaryColor
        "Multiple Star", "Double Star" -> Icons.Outlined.AutoAwesome to primaryColor
        "Galaxy" -> Icons.Outlined.Storm to primaryColor
        "Asteroid" -> Icons.Filled.Cookie to primaryColor
        "Planet", "Moon" -> CustomIcons.Planet to primaryColor
        "Globular Cluster" -> Icons.Filled.LensBlur to primaryColor
        "Open Cluster", "Cluster" -> Icons.Filled.ScatterPlot to primaryColor
        "Comet" -> CustomIcons.TrailLength to primaryColor
        "Star" -> Icons.Outlined.StarOutline to primaryColor
        "Planetary Nebula" -> CustomIcons.CircleCircle to primaryColor
        else -> Icons.Filled.LocationSearching to primaryColor
    }
}

fun CatalogObject.getIconAndColor(
    primaryColor: Color = PureRed,
    secondaryColor: Color = DarkRed
): Pair<ImageVector, Color> =
    getIconAndColorForType(this.objectType, primaryColor, secondaryColor)

@Composable
fun rememberCatalogPainters(): Map<ImageVector, Painter> {
    return mapOf(
        Icons.Filled.Timeline to rememberVectorPainter(Icons.Filled.Timeline),
        Icons.Filled.BlurCircular to rememberVectorPainter(Icons.Filled.BlurCircular),
        Icons.Filled.Air to rememberVectorPainter(Icons.Filled.Air),
        Icons.Filled.Cloud to rememberVectorPainter(Icons.Filled.Cloud),
        Icons.Outlined.AutoAwesome to rememberVectorPainter(Icons.Outlined.AutoAwesome),
        Icons.Outlined.Storm to rememberVectorPainter(Icons.Outlined.Storm),
        Icons.Filled.Cookie to rememberVectorPainter(Icons.Filled.Cookie),
        CustomIcons.Planet to rememberVectorPainter(CustomIcons.Planet),
        Icons.Filled.LensBlur to rememberVectorPainter(Icons.Filled.LensBlur),
        Icons.Filled.ScatterPlot to rememberVectorPainter(Icons.Filled.ScatterPlot),
        CustomIcons.TrailLength to rememberVectorPainter(CustomIcons.TrailLength),
        Icons.Outlined.StarOutline to rememberVectorPainter(Icons.Outlined.StarOutline),
        CustomIcons.CircleCircle to rememberVectorPainter(CustomIcons.CircleCircle),
        CustomIcons.Checklist to rememberVectorPainter(CustomIcons.Checklist),
        Icons.Filled.LocationSearching to rememberVectorPainter(Icons.Filled.LocationSearching)
    )
}

object CustomIcons {
    val Planet: ImageVector by lazy {
        ImageVector.Builder(
            name = "Planet",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 960f,
            viewportHeight = 960f
        ).run {
            addGroup(translationY = 960f)
            addPath(
                pathData = addPathNodes("M484.5-553.5Q499-568 499-589t-14.5-35.5Q470-639 449-639t-35.5 14.5Q399-610 399-589t14.5 35.5Q428-539 449-539t35.5-14.5ZM822-80q-42 0-113-35t-152-95q-19 5-38.5 7.5T479-200q-117 0-198-81t-81-198q0-20 3-40t8-39q-59-81-94.5-151.5T81-822q0-27 15-42.5t41-15.5q26 0 67.5 18T319-801q-21 11-39 23t-35 26q-19-11-37-19t-38-17q18 38 38.5 74t43.5 71q38-54 97-85t130-31q117 0 198.5 81.5T759-479q0 71-31.5 130T642-252q35 23 71.5 44t74.5 38q-8-19-16.5-37T752-244q15-17 27-36t22-39q46 78 62.5 116.5T880-138q0 29-16 43.5T822-80ZM577.5-370.5Q589-382 589-399t-11.5-28.5Q566-439 549-439t-28.5 11.5Q509-416 509-399t11.5 28.5Q532-359 549-359t28.5-11.5Zm43-137Q629-516 629-529t-8.5-21.5Q612-559 599-559t-21.5 8.5Q569-542 569-529t8.5 21.5Q586-499 599-499t21.5-8.5Z"),
                fill = SolidColor(Color.Black)
            )
            clearGroup()
            build()
        }
    }

    val Checklist: ImageVector by lazy {
        ImageVector.Builder(
            name = "Checklist",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 960f,
            viewportHeight = 960f
        ).run {
            addGroup(translationY = 960f)
            addPath(
                pathData = addPathNodes("M222-200 80-342l56-56 85 85 170-170 56 57-225 226Zm0-320L80-662l56-56 85 85 170-170 56 57-225 226Zm298 240v-80h360v80H520Zm0-320v-80h360v80H520Z"),
                fill = SolidColor(Color.Black)
            )
            clearGroup()
            build()
        }
    }

    val TrailLength: ImageVector by lazy {
        ImageVector.Builder(
            name = "TrailLength",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 960f,
            viewportHeight = 960f
        ).run {
            addGroup(translationY = 960f)
            addPath(
                pathData = addPathNodes("M280-280v-80h240q-14-17-22.5-37T484-440H360v-80h124q5-23 13.5-43t22.5-37H120v-80h560q83 0 141.5 58.5T880-480q0 83-58.5 141.5T680-280H280ZM80-440v-80h240v80H80Zm40 160v-80h120v80H120Z"),
                fill = SolidColor(Color.Black)
            )
            clearGroup()
            build()
        }
    }

    val CircleCircle: ImageVector by lazy {
        ImageVector.Builder(
            name = "CircleCircle",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 960f,
            viewportHeight = 960f
        ).run {
            addGroup(translationY = 960f)
            addPath(
                pathData = addPathNodes("M324-111.5Q251-143 197-197t-85.5-127Q80-397 80-480t31.5-156Q143-709 197-763t127-85.5Q397-880 480-880t156 31.5Q709-817 763-763t85.5 127Q880-563 880-480t-31.5 156Q817-251 763-197t-127 85.5Q563-80 480-80t-156-31.5Zm297.5-227Q680-397 680-480t-58.5-141.5Q563-680 480-680t-141.5 58.5Q280-563 280-480t58.5 141.5Q397-280 480-280t141.5-58.5Z"),
                fill = SolidColor(Color.Black)
            )
            clearGroup()
            build()
        }
    }
}

val catalogCategories = listOf(
    "Nearest Objects", "All Objects", "Solar System", "Bright Stars",
    "Messier", "Caldwell", "Collinder", "NGC", "IC", "Asteroids", "Comets",
    "Herschel 400", "Barnard", "Sharpless", "Abell", "Arp",
    "SAC Asterisms", "SAC Doubles", "SAC Red Stars", "TAAS 200",
    "Harris", "RASC Doubles", "EGC"
)

const val BRIGHT_STAR_PRIORITY_MAG_LIMIT = 4.0

fun getCatalogPriority(obj: CatalogObject): Int {
    return when (obj.catalogLabel) {
        "Planet", "Moon", "Solar System" -> 2
        "Str" -> {
            val mag = obj.magnitude ?: 99.0
            if (mag <= BRIGHT_STAR_PRIORITY_MAG_LIMIT) 3 else 100
        }

        "M" -> 4
        "C" -> 5
        "Col" -> 6
        "NGC" -> 7
        "IC" -> 8
        "Asteroid" -> 9
        "Comet" -> 10
        "H" -> 11
        "B" -> 12
        "Sh2" -> 13
        "Abl" -> 14
        "Arp" -> 15
        "SaA" -> 16
        "SaM" -> 17
        "SaR" -> 18
        "Ta2" -> 19
        "Har" -> 20
        "RDS" -> 21
        "EGC" -> 22
        else -> {
            when (obj.objectType) {
                "Planet", "Moon" -> 2
                "Asteroid" -> 9
                "Comet" -> 10
                "Star", "Multiple Star" -> {
                    val mag = obj.magnitude ?: 99.0
                    if (mag <= BRIGHT_STAR_PRIORITY_MAG_LIMIT) 3 else 100
                }

                else -> 999
            }
        }
    }
}

@Composable
fun getLocalizedObjectType(type: String): String = when (type) {
    "Star" -> stringResource(Res.string.type_star)
    "Galaxy" -> stringResource(Res.string.type_galaxy)
    "Nebula" -> stringResource(Res.string.type_nebula)
    "Cluster" -> stringResource(Res.string.type_cluster)
    "Planet" -> stringResource(Res.string.type_planet)
    "Asterism" -> stringResource(Res.string.type_asterism)
    "Cluster + Nebula" -> stringResource(Res.string.type_cluster_nebula)
    "Dark Nebula" -> stringResource(Res.string.type_dark_nebula)
    "Multiple Star", "Double Star" -> stringResource(Res.string.type_multiple_star)
    "Globular Cluster" -> stringResource(Res.string.type_globular_cluster)
    "Open Cluster" -> stringResource(Res.string.type_open_cluster)
    "Planetary Nebula" -> stringResource(Res.string.type_planetary_nebula)
    "Asteroid" -> stringResource(Res.string.type_asteroid)
    "Comet" -> stringResource(Res.string.type_comet)
    else -> type
}

data class PlacedCatalogItem(
    val obj: CatalogObject,
    val screenX: Float,
    val screenY: Float,
    val isTarget: Boolean = false,
    val labelOffset: Offset? = null,
    val labelWidth: Float = 0f,
    val labelHeight: Float = 0f,
    val labelRect: Rect? = null,
    val textLayout: TextLayoutResult? = null
)

fun layoutCatalogItems(
    candidates: List<Pair<CatalogObject, Offset>>,
    textMeasurer: TextMeasurer,
    viewportWidth: Float,
    viewportHeight: Float,
    targetRa: Double? = null,
    targetDec: Double? = null,
    maxLabels: Int = 20,
    uiScale: Float = 1.0f,
    fontSizeSp: Float = 12f,
    placedReticles: MutableList<Rect> = mutableListOf(),
    placedLabels: MutableList<Rect> = mutableListOf(),
    primaryColor: Color = PureRed,
    secondaryColor: Color = DarkRed
): List<PlacedCatalogItem> {
    if (candidates.isEmpty()) return emptyList()

    fun isTargetObj(obj: CatalogObject): Boolean =
        targetRa != null && targetDec != null &&
                kotlin.math.abs(obj.dec - targetDec) < 0.05 &&
                AstrometryUtils.angularDistance(obj.ra, obj.dec, targetRa, targetDec) < 0.05

    // 1. Sort: Target first, then catalog priority (M > C > NGC), then brightness
    val sorted = candidates.sortedWith(
        compareByDescending<Pair<CatalogObject, Offset>> { (obj, _) ->
            isTargetObj(obj)
        }.thenBy { (obj, _) ->
            getCatalogPriority(obj)
        }.thenBy { (obj, _) ->
            obj.magnitude ?: 99.0
        }
    )

    // 2. Reticle Decrowding
    val validCandidates = mutableListOf<Pair<CatalogObject, Offset>>()
    for ((obj, pt) in sorted) {
        if (pt.x < -20f || pt.x > viewportWidth + 20f || pt.y < -20f || pt.y > viewportHeight + 20f) continue

        val isTarget = isTargetObj(obj)

        val radius = if (isTarget) 20f * uiScale else 12f * uiScale
        val reticleBox = Rect(pt.x - radius, pt.y - radius, pt.x + radius, pt.y + radius)

        val collides = placedReticles.any { it.overlaps(reticleBox) } || placedLabels.any {
            it.overlaps(reticleBox)
        }
        if (!collides || isTarget) {
            placedReticles.add(reticleBox)
            validCandidates.add(obj to pt)
        }
    }

    // 3. Multi-Slot Label Placement (Right -> Left -> Bottom -> Top -> Clamped Fallback)
    var labelsPlaced = 0
    val result = ArrayList<PlacedCatalogItem>(validCandidates.size)
    val labelDist = 20f * uiScale
    val targetLabelDist = 26f * uiScale
    val pad = 4f * uiScale

    for ((obj, pt) in validCandidates) {
        val isTarget = isTargetObj(obj)

        var acceptedOffset: Offset? = null
        var acceptedRect: Rect? = null
        var acceptedLayout: TextLayoutResult? = null
        var textW = 0f
        var textH = 0f

        if (labelsPlaced < maxLabels || isTarget) {
            val (_, themedColor) = obj.getIconAndColor(primaryColor, secondaryColor)
            val textLayout = textMeasurer.measure(
                text = obj.displayName,
                style = TextStyle(
                    color = themedColor,
                    fontSize = (fontSizeSp * uiScale).sp,
                    fontWeight = if (isTarget) FontWeight.Bold else FontWeight.Normal,
                    shadow = Shadow.None,
                    textDecoration = TextDecoration.None
                )
            )
            textW = textLayout.size.width.toFloat()
            textH = textLayout.size.height.toFloat()
            val dist = if (isTarget) targetLabelDist else labelDist

            // Try 4 cardinal slots without heap list allocations:
            // 0: Right, 1: Left, 2: Bottom, 3: Top
            for (slot in 0..3) {
                val left = when (slot) {
                    0 -> pt.x + dist
                    1 -> pt.x - dist - textW
                    else -> pt.x - textW / 2f
                }
                val top = when (slot) {
                    0, 1 -> pt.y - textH / 2f
                    2 -> pt.y + dist
                    else -> pt.y - dist - textH
                }
                val candRect = Rect(left, top, left + textW, top + textH)

                // Must fit within screen boundaries
                if (candRect.left < 0f || candRect.right > viewportWidth ||
                    candRect.top < 0f || candRect.bottom > viewportHeight
                ) continue

                val inflated = candRect.inflate(pad)
                if (!placedLabels.any { it.overlaps(inflated) } && !placedReticles.any {
                        it.overlaps(
                            inflated
                        )
                    }) {
                    acceptedRect = candRect
                    break
                }
            }

            // Clamped fallback if no natural slot fits entirely inside screen
            if (acceptedRect == null) {
                for (slot in 0..3) {
                    val rawLeft = when (slot) {
                        0 -> pt.x + dist
                        1 -> pt.x - dist - textW
                        else -> pt.x - textW / 2f
                    }
                    val rawTop = when (slot) {
                        0, 1 -> pt.y - textH / 2f
                        2 -> pt.y + dist
                        else -> pt.y - dist - textH
                    }
                    val clampLeft = rawLeft.coerceIn(0f, maxOf(0f, viewportWidth - textW))
                    val clampTop = rawTop.coerceIn(0f, maxOf(0f, viewportHeight - textH))
                    val candRect = Rect(clampLeft, clampTop, clampLeft + textW, clampTop + textH)
                    val inflated = candRect.inflate(pad)

                    if (!placedLabels.any { it.overlaps(inflated) } && !placedReticles.any {
                            it.overlaps(
                                inflated
                            )
                        }) {
                        acceptedRect = candRect
                        break
                    }
                }
            }

            if (acceptedRect != null) {
                acceptedOffset = Offset(acceptedRect.left - pt.x, acceptedRect.top - pt.y)
                acceptedLayout = textLayout
                placedLabels.add(acceptedRect)
                labelsPlaced++
            }
        }

        result.add(
            PlacedCatalogItem(
                obj = obj,
                screenX = pt.x,
                screenY = pt.y,
                isTarget = isTarget,
                labelOffset = acceptedOffset,
                labelWidth = textW,
                labelHeight = textH,
                labelRect = acceptedRect,
                textLayout = acceptedLayout
            )
        )
    }

    return result
}

fun findTappedCatalogItem(
    items: List<PlacedCatalogItem>,
    tapOffset: Offset,
    maxHitRadiusPx: Float,
    labelInflatePx: Float = 12f
): CatalogObject? {
    // 1. Direct hit on inflated label text
    for (item in items) {
        if (item.labelRect?.inflate(labelInflatePx)?.contains(tapOffset) == true) {
            return item.obj
        }
    }

    // 2. Nearest icon center within hit radius
    var closest: CatalogObject? = null
    var minDist = maxHitRadiusPx
    for (item in items) {
        val dist = hypot(item.screenX - tapOffset.x, item.screenY - tapOffset.y)
        if (dist < minDist) {
            minDist = dist
            closest = item.obj
        }
    }
    return closest
}

fun findTappedStar(
    stars: List<CatalogObject>,
    starXs: List<Float>,
    starYs: List<Float>,
    tapOffset: Offset,
    maxHitRadiusPx: Float
): CatalogObject? {
    var closest: CatalogObject? = null
    var minDist = maxHitRadiusPx
    val maxHitRadiusSq = maxHitRadiusPx * maxHitRadiusPx
    val n = minOf(stars.size, starXs.size, starYs.size)
    for (i in 0 until n) {
        val dx = starXs[i] - tapOffset.x
        val dy = starYs[i] - tapOffset.y
        val distSq = dx * dx + dy * dy
        if (distSq < maxHitRadiusSq) {
            val dist = kotlin.math.sqrt(distSq)
            if (dist < minDist) {
                minDist = dist
                closest = stars[i]
            }
        }
    }
    return closest
}

