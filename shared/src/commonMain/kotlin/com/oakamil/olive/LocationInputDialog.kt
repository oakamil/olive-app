package com.oakamil.olive

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private enum class LocField { LAT_DEG, LAT_FRAC, LON_DEG, LON_FRAC }

@Composable
fun LocationInputDialog(
    initialLat: Double?,
    initialLon: Double?,
    onDismiss: () -> Unit,
    onSave: (latitude: Float, longitude: Float) -> Unit
) {
    val validInitial = if (initialLat != null && initialLon != null &&
        MainViewModel.isValidCoordinate(initialLat, initialLon)
    ) Pair(initialLat, initialLon) else null

    val initLat = remember(validInitial) { splitDecimalDegrees(validInitial?.first) }
    var isNorth by remember { mutableStateOf(initLat.isPositive) }
    var latDeg by remember { mutableStateOf(initLat.wholeDeg) }
    var latFrac by remember { mutableStateOf(initLat.fracDeg) }

    val initLon = remember(validInitial) { splitDecimalDegrees(validInitial?.second) }
    var isEast by remember { mutableStateOf(initLon.isPositive) }
    var lonDeg by remember { mutableStateOf(initLon.wholeDeg) }
    var lonFrac by remember { mutableStateOf(initLon.fracDeg) }

    var focusedField by remember { mutableStateOf(LocField.LAT_DEG) }

    InfoPopup(
        title = stringResource(Res.string.title_observer_location),
        onDismiss = onDismiss
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Latitude Row
            Text(
                stringResource(Res.string.label_latitude),
                style = MaterialTheme.typography.labelLarge
            )
            val extColors = LocalOliveExtendedColors.current
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clickable { isNorth = !isNorth }
                        .border(1.dp, extColors.numpadKeyBorder, RoundedCornerShape(4.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        stringResource(if (isNorth) Res.string.dir_north else Res.string.dir_south),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                CoordinateBox(
                    value = latDeg,
                    isFocused = focusedField == LocField.LAT_DEG,
                    suffix = "°",
                    onClick = { focusedField = LocField.LAT_DEG }
                )
                Text(
                    ".",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                CoordinateBox(
                    value = latFrac,
                    isFocused = focusedField == LocField.LAT_FRAC,
                    suffix = "",
                    onClick = { focusedField = LocField.LAT_FRAC }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Longitude Row
            Text(
                stringResource(Res.string.label_longitude),
                style = MaterialTheme.typography.labelLarge
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clickable { isEast = !isEast }
                        .border(1.dp, extColors.numpadKeyBorder, RoundedCornerShape(4.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        stringResource(if (isEast) Res.string.dir_east else Res.string.dir_west),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                CoordinateBox(
                    value = lonDeg,
                    isFocused = focusedField == LocField.LON_DEG,
                    suffix = "°",
                    placeholder = "000",
                    onClick = { focusedField = LocField.LON_DEG }
                )
                Text(
                    ".",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                CoordinateBox(
                    value = lonFrac,
                    isFocused = focusedField == LocField.LON_FRAC,
                    suffix = "",
                    onClick = { focusedField = LocField.LON_FRAC }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Numpad
            NightVisionNumpad(
                onKeyClick = { char ->
                    val appendDeg = { current: String, max: Int ->
                        val next = if (current == "0") char.toString() else current + char
                        if (next.length <= max.toString().length && (next.toIntOrNull()
                                ?: 0) <= max
                        ) next else current
                    }
                    val appendFrac = { current: String ->
                        if (current.length < 2) current + char else current
                    }
                    when (focusedField) {
                        LocField.LAT_DEG -> {
                            latDeg = appendDeg(latDeg, 90)
                            if ((latDeg.toIntOrNull() ?: 0) == 90) {
                                latFrac = "00"
                            }
                        }

                        LocField.LAT_FRAC -> {
                            if ((latDeg.toIntOrNull() ?: 0) != 90) {
                                latFrac = appendFrac(latFrac)
                            }
                        }

                        LocField.LON_DEG -> {
                            lonDeg = appendDeg(lonDeg, 180)
                            if ((lonDeg.toIntOrNull() ?: 0) == 180) {
                                lonFrac = "00"
                            }
                        }

                        LocField.LON_FRAC -> {
                            if ((lonDeg.toIntOrNull() ?: 0) != 180) {
                                lonFrac = appendFrac(lonFrac)
                            }
                        }
                    }
                },
                onBackspace = {
                    when (focusedField) {
                        LocField.LAT_DEG -> if (latDeg.isNotEmpty()) latDeg = latDeg.dropLast(1)
                        LocField.LAT_FRAC -> if (latFrac.isNotEmpty()) latFrac = latFrac.dropLast(1)
                        LocField.LON_DEG -> if (lonDeg.isNotEmpty()) lonDeg = lonDeg.dropLast(1)
                        LocField.LON_FRAC -> if (lonFrac.isNotEmpty()) lonFrac = lonFrac.dropLast(1)
                    }
                },
                onNext = {
                    focusedField = when (focusedField) {
                        LocField.LAT_DEG -> LocField.LAT_FRAC
                        LocField.LAT_FRAC -> LocField.LON_DEG
                        LocField.LON_DEG -> LocField.LON_FRAC
                        LocField.LON_FRAC -> LocField.LAT_DEG
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    val lat = combineDecimalDegrees(latDeg, latFrac, isNorth, 90).toFloat()
                    val lon = combineDecimalDegrees(lonDeg, lonFrac, isEast, 180).toFloat()
                    onSave(lat, lon)
                    onDismiss()
                },
                enabled = latDeg.isNotEmpty() && lonDeg.isNotEmpty()
            ) {
                Text(stringResource(Res.string.btn_save))
            }
        }
    }
}
