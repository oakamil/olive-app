// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import kotlin.math.round

@Composable
fun SkyMapSettingsDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val windowController = LocalPlatformWindowController.current
    val capabilities = LocalPlatformCapabilities.current
    val settingsManager = viewModel.settingsManager
    val activeCameraSelection =
        if (viewModel.engineMode == MainViewModel.EngineMode.UVC) "uvc" else settingsManager.cameraSelection

    var activeInfoDialog by remember { mutableStateOf<String?>(null) }

    InfoPopup(
        title = stringResource(Res.string.title_settings),
        onDismiss = onDismiss
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Night Vision
            Text(
                text = stringResource(Res.string.theme_night_vision),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            val isNightVision = viewModel.currentTheme != "blue"
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                RadioButton(
                    selected = isNightVision,
                    onClick = { viewModel.currentTheme = "red" },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = MaterialTheme.colorScheme.primary,
                        unselectedColor = MaterialTheme.colorScheme.onBackground
                    )
                )
                Text(
                    text = stringResource(Res.string.value_on),
                    modifier = Modifier.padding(start = 4.dp, end = 20.dp),
                    color = MaterialTheme.colorScheme.onBackground
                )

                RadioButton(
                    selected = !isNightVision,
                    onClick = { viewModel.currentTheme = "blue" },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = MaterialTheme.colorScheme.primary,
                        unselectedColor = MaterialTheme.colorScheme.onBackground
                    )
                )
                Text(
                    text = stringResource(Res.string.value_off),
                    modifier = Modifier.padding(start = 4.dp, end = 20.dp),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            // 2. Guidance Mode
            Text(
                text = stringResource(Res.string.label_guidance_mode),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                listOf(
                    "alt_az" to stringResource(Res.string.value_alt_az_offset),
                    "equatorial" to stringResource(Res.string.value_equatorial_ra_dec)
                ).forEach { (value, label) ->
                    RadioButton(
                        selected = viewModel.guidanceMode == value,
                        onClick = { viewModel.guidanceMode = value },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = MaterialTheme.colorScheme.primary,
                            unselectedColor = MaterialTheme.colorScheme.onBackground
                        )
                    )
                    Text(
                        text = label,
                        modifier = Modifier.padding(start = 4.dp, end = 20.dp),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            // 3. Show Map Position
            Text(
                text = stringResource(Res.string.label_show_map_position),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                RadioButton(
                    selected = viewModel.showMapPosition,
                    onClick = { viewModel.showMapPosition = true },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = MaterialTheme.colorScheme.primary,
                        unselectedColor = MaterialTheme.colorScheme.onBackground
                    )
                )
                Text(
                    text = stringResource(Res.string.value_on),
                    modifier = Modifier.padding(start = 4.dp, end = 20.dp),
                    color = MaterialTheme.colorScheme.onBackground
                )

                RadioButton(
                    selected = !viewModel.showMapPosition,
                    onClick = { viewModel.showMapPosition = false },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = MaterialTheme.colorScheme.primary,
                        unselectedColor = MaterialTheme.colorScheme.onBackground
                    )
                )
                Text(
                    text = stringResource(Res.string.value_off),
                    modifier = Modifier.padding(start = 4.dp, end = 20.dp),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            // 4. Show Stats
            Text(
                text = stringResource(Res.string.label_show_stats),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                RadioButton(
                    selected = viewModel.showStats,
                    onClick = { viewModel.showStats = true },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = MaterialTheme.colorScheme.primary,
                        unselectedColor = MaterialTheme.colorScheme.onBackground
                    )
                )
                Text(
                    text = stringResource(Res.string.value_on),
                    modifier = Modifier.padding(start = 4.dp, end = 20.dp),
                    color = MaterialTheme.colorScheme.onBackground
                )

                RadioButton(
                    selected = !viewModel.showStats,
                    onClick = { viewModel.showStats = false },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = MaterialTheme.colorScheme.primary,
                        unselectedColor = MaterialTheme.colorScheme.onBackground
                    )
                )
                Text(
                    text = stringResource(Res.string.value_off),
                    modifier = Modifier.padding(start = 4.dp, end = 20.dp),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            // 5. Sliders (Screen Brightness, Low Power Timeout, Zoom, Star Sensitivity, Exposure / Stacking)
            if (activeCameraSelection != "cedar") {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                )

                // Screen Brightness
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

                // Low Power Timeout
                var lowPowerTimeoutSeconds by remember {
                    mutableIntStateOf(settingsManager.lowPowerTimeoutSeconds)
                }
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
                                if (mins % 1.0 == 0.0) mins.toInt().toString() else mins.toString()
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

                // Zoom Slider
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
                                    String.format("%.1f", viewModel.currentZoom)
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            IconButton(
                                onClick = { activeInfoDialog = "zoom" },
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

                // Camera Manual Settings: Star Sensitivity & Exposure / Stacking
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
                            onClick = { activeInfoDialog = "star_sensitivity" },
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
                        onValueChange = { viewModel.updateStarSensitivity(it) },
                        valueRange = 1f..100f,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (activeCameraSelection == "internal") {
                        var mainScreenExposure by remember {
                            val rawExp = viewModel.engine.getNativeCameraExposure()
                            val initialExp =
                                if (rawExp > 0) (rawExp / 1000000f).coerceIn(0.1f, 2.0f) else 0.5f
                            mutableFloatStateOf(initialExp)
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
                                onClick = { activeInfoDialog = "exposure" },
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
                                onClick = { activeInfoDialog = "stacking" },
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
                            onValueChange = { viewModel.updateUvcStacking(it.toInt()) },
                            valueRange = 1f..10f,
                            steps = 8,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } // End of isAutoMode check
            }
        }
    }

    // Contextual Info Popups
    when (activeInfoDialog) {
        "zoom" -> InfoPopup(
            title = stringResource(id = Res.string.label_zoom),
            onDismiss = { activeInfoDialog = null }
        ) {
            Text(AnnotatedString.fromHtml(stringResource(id = Res.string.info_zoom)))
        }

        "star_sensitivity" -> InfoPopup(
            title = stringResource(id = Res.string.label_star_sensitivity),
            onDismiss = { activeInfoDialog = null }
        ) {
            Text(AnnotatedString.fromHtml(stringResource(id = Res.string.info_star_sensitivity)))
        }

        "exposure" -> InfoPopup(
            title = stringResource(id = Res.string.label_exposure),
            onDismiss = { activeInfoDialog = null }
        ) {
            Text(AnnotatedString.fromHtml(stringResource(id = Res.string.info_exposure)))
        }

        "stacking" -> InfoPopup(
            title = stringResource(id = Res.string.label_stacking_frames),
            onDismiss = { activeInfoDialog = null }
        ) {
            Text(AnnotatedString.fromHtml(stringResource(id = Res.string.info_stacking)))
        }
    }
}
