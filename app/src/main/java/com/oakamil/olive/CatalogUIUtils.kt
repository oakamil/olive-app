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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp

fun getIconAndColorForType(objectType: String): Pair<ImageVector, Color> {
    val defaultColor = Color.Red
    return when (objectType) {
        "Asterism" -> Icons.Filled.Timeline to defaultColor
        "Cluster + Nebula" -> Icons.Filled.BlurCircular to defaultColor
        "Nebula" -> Icons.Filled.Air to defaultColor
        "Dark Nebula" -> Icons.Filled.Cloud to Color(0xFF8B0000) // dark red
        "Double Star" -> Icons.Outlined.AutoAwesome to defaultColor
        "Galaxy" -> Icons.Outlined.Storm to defaultColor
        "Asteroid" -> Icons.Filled.Cookie to defaultColor
        "Planet", "Moon" -> CustomIcons.Planet to defaultColor
        "Globular Cluster" -> Icons.Filled.LensBlur to defaultColor
        "Open Cluster", "Cluster" -> Icons.Filled.ScatterPlot to defaultColor
        "Comet" -> CustomIcons.TrailLength to defaultColor
        "Star" -> Icons.Outlined.StarOutline to defaultColor
        "Planetary Nebula" -> CustomIcons.CircleCircle to defaultColor
        else -> Icons.Filled.LocationSearching to defaultColor
    }
}

fun CatalogObject.getIconAndColor(): Pair<ImageVector, Color> =
    getIconAndColorForType(this.objectType)

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