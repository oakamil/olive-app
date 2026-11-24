// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlin.math.round

/**
 * Settings modal for display/theme preferences (Night Vision, HiPS sky background and HQ image
 * storage, guidance mode, HUD overlays, reverse layout), screen brightness, low-power timeout,
 * and live manual camera controls while the plate-solving engine is running.
 */
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
        onDismiss = onDismiss,
        fillMaxWidth = true,
        headerAction = {
            IconButton(
                onClick = { activeInfoDialog = "about" },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Help,
                    contentDescription = stringResource(Res.string.cd_about),
                    modifier = Modifier.size(26.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Night Vision
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

            // Sky Background
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(Res.string.label_sky_background),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                IconButton(
                    onClick = { activeInfoDialog = "sky_background" },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = stringResource(Res.string.cd_info),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                RadioButton(
                    selected = viewModel.showSkyBackground,
                    onClick = { viewModel.requestSkyBackgroundChange(true) },
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
                    selected = !viewModel.showSkyBackground,
                    onClick = { viewModel.requestSkyBackgroundChange(false) },
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
            val dlState = viewModel.hipsDownloadState
            val isDownloadingOrPacking =
                dlState is HiPSDownloadState.Downloading || dlState is HiPSDownloadState.Packing
            val hasStoredData = viewModel.skyStorageBytes > 0L

            if (isDownloadingOrPacking || hasStoredData) {
                val statusText = when (dlState) {
                    is HiPSDownloadState.Downloading -> {
                        val pct =
                            ((dlState.completedTiles * 100) / dlState.totalTiles.coerceAtLeast(1)).coerceIn(
                                0,
                                100
                            )
                        stringResource(Res.string.hips_downloading_inline, pct)
                    }

                    is HiPSDownloadState.Packing -> stringResource(Res.string.hips_packing_inline)
                    else -> stringResource(
                        Res.string.hips_status_stored,
                        HiPSDownloadManager.formatMbValue(viewModel.skyStorageBytes)
                    )
                }
                HiPSStorageStatusRow(
                    statusText = statusText,
                    isActive = isDownloadingOrPacking,
                    onShowProgress = if (isDownloadingOrPacking) {
                        { viewModel.showHiPSDownloadProgress = true }
                    } else null,
                    onRemove = { viewModel.removeDownloadedSkyPack() }
                )
            }

            // High-Quality Object Images Storage
            val hqDlState = viewModel.hqDownloadState
            val isHqBulkDownloading =
                hqDlState is HiPSHqDownloadState.Downloading && hqDlState.isBulkCatalog
            val hasHqStored = viewModel.hqStorageBytes > 0L
            if (isHqBulkDownloading || hasHqStored) {
                Text(
                    text = stringResource(Res.string.label_hq_object_images),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                val hqStatusText = if (isHqBulkDownloading) {
                    val pct =
                        ((hqDlState.completedTiles * 100) / hqDlState.totalTiles.coerceAtLeast(1)).coerceIn(
                            0,
                            100
                        )
                    stringResource(Res.string.hips_downloading_inline, pct)
                } else {
                    stringResource(
                        Res.string.hips_status_stored,
                        HiPSDownloadManager.formatMbValue(viewModel.hqStorageBytes)
                    )
                }
                HiPSStorageStatusRow(
                    statusText = hqStatusText,
                    isActive = isHqBulkDownloading,
                    onShowProgress = if (isHqBulkDownloading) {
                        { viewModel.showHqDownloadProgress = true }
                    } else null,
                    onRemove = { viewModel.showRemoveHqConfirm = true }
                )
            }

            // Guidance Mode
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(Res.string.label_guidance_mode),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                IconButton(
                    onClick = { activeInfoDialog = "guidance_mode" },
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

            // Show Live Camera
            Text(
                text = stringResource(Res.string.label_show_live_camera),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                RadioButton(
                    selected = viewModel.showLiveCamera,
                    onClick = { viewModel.showLiveCamera = true },
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
                    selected = !viewModel.showLiveCamera,
                    onClick = { viewModel.showLiveCamera = false },
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

            // Show Map Position
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

            // Show Stats
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

            // Reverse Layout
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(Res.string.label_reverse_layout),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                IconButton(
                    onClick = { activeInfoDialog = "reverse_layout" },
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                RadioButton(
                    selected = viewModel.reverseLayout,
                    onClick = { viewModel.reverseLayout = true },
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
                    selected = !viewModel.reverseLayout,
                    onClick = { viewModel.reverseLayout = false },
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

            // Sliders (Text Size, Screen Brightness, Low Power Timeout, Zoom, Star Sensitivity, Exposure / Stacking)
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = LocalOliveExtendedColors.current.dialogDivider
            )

            // Text Size
            val textSizeOptions = OliveTextSize.entries
            var textSizeSliderIndex by remember(viewModel.textSize) {
                mutableIntStateOf(textSizeOptions.indexOf(viewModel.textSize).coerceAtLeast(0))
            }
            val textSizeLabelRes = when (textSizeOptions[textSizeSliderIndex]) {
                OliveTextSize.SMALL -> Res.string.value_text_size_small
                OliveTextSize.NORMAL -> Res.string.value_text_size_normal
                OliveTextSize.LARGE -> Res.string.value_text_size_large
                OliveTextSize.EXTRA_LARGE -> Res.string.value_text_size_extra_large
            }
            Text(
                stringResource(
                    Res.string.format_label_value_string,
                    stringResource(Res.string.label_text_size),
                    stringResource(textSizeLabelRes)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Slider(
                value = textSizeSliderIndex.toFloat(),
                onValueChange = { raw ->
                    textSizeSliderIndex = round(raw).toInt().coerceIn(0, textSizeOptions.lastIndex)
                },
                onValueChangeFinished = {
                    viewModel.textSize = textSizeOptions[textSizeSliderIndex]
                },
                valueRange = 0f..textSizeOptions.lastIndex.toFloat(),
                steps = textSizeOptions.size - 2,
                modifier = Modifier.fillMaxWidth()
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
            val lowPowerTimeoutSeconds = viewModel.lowPowerTimeoutSeconds
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
                    viewModel.lowPowerTimeoutSeconds = round(it).toInt()
                },
                valueRange = 0f..300f,
                steps = 9,
                modifier = Modifier.fillMaxWidth()
            )

            // Camera Adjustment Sliders (only available when engine is running)
            if (viewModel.isEngineRunning) {
                // Zoom Slider
                val showZoom = activeCameraSelection == "internal" &&
                        settingsManager.cameraSettingsMode != "Auto"
                if (showZoom) {
                    val maxZoom = remember(
                        viewModel.engineMode,
                        settingsManager.cameraSettingsMode,
                        settingsManager.sensorMode,
                        settingsManager.internalCameraId,
                        capabilities.isApple
                    ) {
                        var mZ = 1f
                        try {
                            if (settingsManager.sensorMode == "Processed") {
                                val jsonArray =
                                    JSONArray(viewModel.getAvailableInternalCameras())
                                val targetId = settingsManager.internalCameraId
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
                        val minExpSec =
                            (round((capabilities.exposureRange.start / 1_000_000f) * 10f) / 10f)
                                .coerceAtLeast(0.1f)
                        val maxExpSec =
                            (capabilities.exposureRange.endInclusive / 1_000_000f)
                                .coerceAtLeast(minExpSec)
                        val expStepsCount =
                            (round((maxExpSec - minExpSec) * 10f).toInt() - 1).coerceAtLeast(0)

                        var mainScreenExposure by remember(
                            capabilities.exposureRange,
                            viewModel.engineMode
                        ) {
                            val rawExp = viewModel.engine.getNativeCameraExposure()
                            val initialExp =
                                if (rawExp > 0) (rawExp / 1_000_000f).coerceIn(
                                    minExpSec,
                                    maxExpSec
                                ) else 0.5f.coerceIn(minExpSec, maxExpSec)
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
                            value = mainScreenExposure.coerceIn(minExpSec, maxExpSec),
                            enabled = settingsManager.cameraSettingsMode != "Auto",
                            onValueChange = {
                                val stepped = (round(it * 10.0f) / 10.0f)
                                    .coerceIn(minExpSec, maxExpSec)
                                mainScreenExposure = stepped
                                viewModel.setExposure(stepped * 1_000_000f)
                            },
                            valueRange = minExpSec..maxExpSec,
                            steps = expStepsCount,
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
                }
            }
        }
    }

    // Contextual Info Popups
    when (activeInfoDialog) {
        "guidance_mode" -> InfoPopup(
            title = stringResource(id = Res.string.label_guidance_mode),
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                activeInfoDialog = null
            }
        ) {
            Text(AnnotatedString.fromHtml(stringResource(id = Res.string.info_guidance_mode)))
        }

        "reverse_layout" -> InfoPopup(
            title = stringResource(id = Res.string.label_reverse_layout),
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                activeInfoDialog = null
            }
        ) {
            Text(AnnotatedString.fromHtml(stringResource(id = Res.string.info_reverse_layout)))
        }

        "zoom" -> InfoPopup(
            title = stringResource(id = Res.string.label_zoom),
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                activeInfoDialog = null
            }
        ) {
            Text(AnnotatedString.fromHtml(stringResource(id = Res.string.info_zoom)))
        }

        "star_sensitivity" -> InfoPopup(
            title = stringResource(id = Res.string.label_star_sensitivity),
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                activeInfoDialog = null
            }
        ) {
            Text(AnnotatedString.fromHtml(stringResource(id = Res.string.info_star_sensitivity)))
        }

        "exposure" -> InfoPopup(
            title = stringResource(id = Res.string.label_exposure),
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                activeInfoDialog = null
            }
        ) {
            Text(AnnotatedString.fromHtml(stringResource(id = Res.string.info_exposure)))
        }

        "stacking" -> InfoPopup(
            title = stringResource(id = Res.string.label_stacking_frames),
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                activeInfoDialog = null
            }
        ) {
            Text(AnnotatedString.fromHtml(stringResource(id = Res.string.info_stacking)))
        }

        "sky_background" -> InfoPopup(
            title = stringResource(Res.string.label_sky_background),
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                activeInfoDialog = null
            }
        ) {
            Text(
                AnnotatedString.fromHtml(
                    stringResource(
                        Res.string.info_sky_background,
                        HiPSDownloadManager.estimatedDownloadMb(),
                        Healpix.MAX_ORDER,
                        HiPSDownloadManager.formatCount(Healpix.TOTAL_TILES)
                    )
                )
            )
        }

        "about" -> InfoPopup(
            title = stringResource(Res.string.title_about_olive),
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                activeInfoDialog = null
            }
        ) {
            val versionDisplay = capabilities.appVersion.takeIf { it.isNotBlank() }
                ?.let { stringResource(Res.string.format_version, it) }
                .orEmpty()
            Text(
                AnnotatedString.fromHtml(
                    stringResource(Res.string.info_about_olive, versionDisplay)
                )
            )
        }
    }
}

@Composable
private fun HiPSStorageStatusRow(
    statusText: String,
    isActive: Boolean,
    onShowProgress: (() -> Unit)?,
    onRemove: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, bottom = 4.dp)
    ) {
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = if (isActive) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f)
            }
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (onShowProgress != null) {
            TextButton(onClick = onShowProgress) {
                Text(text = stringResource(Res.string.hips_action_show))
            }
        }
        TextButton(onClick = onRemove) {
            Text(text = stringResource(Res.string.hips_action_remove))
        }
    }
}
