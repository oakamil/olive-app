package com.oakamil.olive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeJoin.Companion.Round
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import java.util.Locale
import kotlin.math.abs

@Composable
fun GuidanceDisplay(
    stats: EngineStats,
    isAltAz: Boolean,
    isLowPowerMode: Boolean = false,
    isImuEnabled: Boolean = false,
    isCompactLayout: Boolean = false
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val rot = if (isAltAz) stats.offsetAz else stats.offsetRa
        val tilt = if (isAltAz) stats.offsetAlt else stats.offsetDec
        val angle = stats.offsetAngle

        var lastRot by remember { mutableStateOf<Double?>(null) }
        var lastTilt by remember { mutableStateOf<Double?>(null) }
        var lastAngle by remember { mutableStateOf<Double?>(null) }

        LaunchedEffect(rot, tilt, angle, stats.targetRa, stats.targetDec, stats.status) {
            if (stats.targetRa == null && stats.targetDec == null) {
                lastRot = null
                lastTilt = null
                lastAngle = null
            } else {
                if (rot != null) lastRot = rot
                if (tilt != null) lastTilt = tilt
                if (angle != null) lastAngle = angle
            }
        }

        if (stats.targetRa == null && stats.targetDec == null && stats.status != "Solving" && stats.status != "Slewing" && stats.status != "Calibrating" && stats.ra == null) {
            return@Box
        }

        val displayRot = lastRot
        val displayTilt = lastTilt
        val displayAngle = lastAngle

        if (displayRot != null && displayTilt != null) {
            val offsetStyle = MaterialTheme.typography.displayLarge.copy(
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
                fontSize = 200.sp,
                lineHeight = TextUnit.Unspecified,
                drawStyle = if (isLowPowerMode) Stroke(width = 2f, join = Round) else Fill
            )

            val indicatorStyle = MaterialTheme.typography.displayMedium.copy(
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary,
                fontStyle = FontStyle.Italic,
                fontFamily = FontFamily.Monospace,
                fontSize = 200.sp,
                lineHeight = TextUnit.Unspecified,
                drawStyle = if (isLowPowerMode) Stroke(width = 2f, join = Round) else Fill
            )

            Box(modifier = Modifier.fillMaxSize()) {
                if (isCompactLayout) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            if (isAltAz) {
                                DirectionArrow(if (displayTilt > 0.0) 270 else 90, false, isLowPowerMode)
                            } else {
                                BasicText(
                                    text = if (displayTilt > 0) stringResource(id = R.string.label_north) else stringResource(id = R.string.label_south),
                                    style = indicatorStyle,
                                    softWrap = false,
                                    autoSize = TextAutoSize.StepBased()
                                )
                            }
                        }
                        Box(modifier = Modifier.weight(1.5f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            BasicText(
                                text = String.format(Locale.getDefault(), "%-+6.1f", displayTilt),
                                style = offsetStyle,
                                softWrap = false,
                                autoSize = TextAutoSize.StepBased()
                            )
                        }
                        Box(modifier = Modifier.weight(2f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            if (stats.offsetAngle == null && displayAngle != null && (stats.status == "Solving" || stats.status == "Slewing")) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxSize(0.5f), strokeWidth = 4.dp)
                            } else if (stats.offsetAngle != null && abs(stats.offsetAngle) >= 0.05) {
                                TargetArrow(stats.offsetAngle, isLowPowerMode)
                            }
                        }
                        Box(modifier = Modifier.weight(1.5f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            BasicText(
                                text = String.format(Locale.getDefault(), "%+6.1f", displayRot),
                                style = offsetStyle,
                                softWrap = false,
                                autoSize = TextAutoSize.StepBased()
                            )
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            if (isAltAz) {
                                DirectionArrow(if (displayRot > 0.0) 0 else 180, true, isLowPowerMode)
                            } else {
                                BasicText(
                                    text = if (displayRot > 0) stringResource(id = R.string.label_east) else stringResource(id = R.string.label_west),
                                    style = indicatorStyle,
                                    softWrap = false,
                                    autoSize = TextAutoSize.StepBased()
                                )
                            }
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 4.dp)
                                .fillMaxSize(),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            BasicText(
                                text = String.format(Locale.getDefault(), "%-+6.1f", displayTilt),
                                style = offsetStyle,
                                softWrap = false,
                                autoSize = TextAutoSize.StepBased()
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 4.dp)
                                .fillMaxSize(),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            BasicText(
                                text = String.format(Locale.getDefault(), "%+6.1f", displayRot),
                                style = offsetStyle,
                                softWrap = false,
                                autoSize = TextAutoSize.StepBased()
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .weight(1.5f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(4.dp)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.TopStart
                        ) {
                            if (isAltAz) {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    Row(
                                        modifier = Modifier
                                            .weight(2f)
                                            .fillMaxWidth()
                                    ) {
                                        DirectionArrow(
                                            if (displayTilt > 0.0) 270 else 90,
                                            false,
                                            isLowPowerMode
                                        )
                                    }
                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth()
                                    ) {
                                        BasicText(
                                            text = stringResource(id = R.string.label_alt),
                                            style = indicatorStyle,
                                            softWrap = false,
                                            autoSize = TextAutoSize.StepBased()
                                        )
                                    }
                                }
                            } else {
                                BasicText(
                                    text = if (displayTilt > 0) stringResource(id = R.string.label_north) else stringResource(
                                        id = R.string.label_south
                                    ),
                                    style = indicatorStyle,
                                    softWrap = false,
                                    autoSize = TextAutoSize.StepBased()
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .weight(2f)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (stats.offsetAngle == null && displayAngle != null && (stats.status == "Solving" || stats.status == "Slewing")) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.fillMaxSize(0.5f),
                                    strokeWidth = 8.dp
                                )
                            } else if (stats.offsetAngle != null && abs(stats.offsetAngle) >= 0.05) {
                                TargetArrow(stats.offsetAngle, isLowPowerMode)
                            }
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(4.dp)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.TopEnd
                        ) {
                            if (isAltAz) {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    Row(
                                        modifier = Modifier
                                            .weight(2f)
                                            .fillMaxWidth()
                                    ) {
                                        DirectionArrow(
                                            if (displayRot > 0.0) 0 else 180,
                                            true,
                                            isLowPowerMode
                                        )
                                    }
                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        BasicText(
                                            text = " " + stringResource(id = R.string.label_az),
                                            style = indicatorStyle,
                                            softWrap = false,
                                            autoSize = TextAutoSize.StepBased()
                                        )
                                    }
                                }
                            } else {
                                BasicText(
                                    text = " " + if (displayRot > 0) stringResource(id = R.string.label_east) else stringResource(
                                        id = R.string.label_west
                                    ),
                                    style = indicatorStyle,
                                    softWrap = false,
                                    autoSize = TextAutoSize.StepBased()
                                )
                            }
                        }
                    }
                }
                }
            }
        } else {
            if (isCompactLayout) {
                Text(
                    text = stringResource(R.string.no_target),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            } else {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (isImuEnabled && (stats.ra != null || stats.isImuEstimate)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 2.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                if (stats.isImuEstimate) {
                    Icon(
                        imageVector = Icons.Outlined.Sensors,
                        contentDescription = "IMU Estimate",
                        tint = DarkRed,
                        modifier = Modifier.size(12.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.PhotoCamera,
                        contentDescription = "Plate Solving",
                        tint = DarkRed,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun DirectionArrow(rotation: Int, alignRight: Boolean, isOutlined: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawWithCache {
                val radius = size.minDimension / 2.5f
                var shapeCenterX = size.minDimension / 2
                if (alignRight) {
                    shapeCenterX = size.width - shapeCenterX
                }
                val shapeCenterY = size.minDimension / 2

                val roundedPolygon = RoundedPolygon(
                    numVertices = 3,
                    radius = radius,
                    centerX = shapeCenterX,
                    centerY = shapeCenterY,
                    rounding = CornerRounding(
                        size.minDimension / 12.5f,
                    )
                )
                val roundedPolygonPath = roundedPolygon.toPath().asComposePath()

                onDrawBehind {
                    rotate(
                        degrees = rotation.toFloat(),
                        pivot = Offset(shapeCenterX, shapeCenterY)
                    ) {
                        drawPath(
                            path = roundedPolygonPath,
                            color = PureRed,
                            style = if (isOutlined) Stroke(width = size.minDimension / 50f) else Fill
                        )
                    }
                }
            }
    )
}

@Composable
fun TargetArrow(rotation: Double, isOutlined: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = if (isOutlined) R.drawable.ic_straight_100 else R.drawable.ic_straight_400),
            contentDescription = stringResource(id = R.string.cd_target),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(rotationZ = -rotation.toFloat()),
            tint = if (isOutlined) DarkRed else PureRed
        )
    }
}
