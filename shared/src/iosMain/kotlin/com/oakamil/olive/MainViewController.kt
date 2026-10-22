// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import com.oakamil.olive.cinterop.olive_ios_get_local_ip
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIViewController

@OptIn(ExperimentalForeignApi::class)
private fun getIosDeviceNetworkEndpoint(): String {
    val ip = olive_ios_get_local_ip()?.toKString() ?: "127.0.0.1"
    val rawHost = NSProcessInfo.processInfo.hostName
    val host = if (rawHost.endsWith(".local")) rawHost else "$rawHost.local"
    return "$host ($ip)"
}

fun MainViewController(): UIViewController = ComposeUIViewController {
    val storage = remember { NSUserDefaultsStorage() }
    val capabilities = remember { ApplePlatformCapabilities }
    val engine = remember { IosEngineBridge() }
    val catalogDb = remember { IosCatalogDatabase() }
    val viewModel = remember { MainViewModel(storage, capabilities, engine, catalogDb) }
    val settingsManager = viewModel.settingsManager
    val windowController = remember { AppleWindowController() }
    DisposableEffect(windowController) {
        onDispose {
            windowController.dispose()
        }
    }

    var currentScreen by remember { mutableStateOf("main") }
    var activeCameraSelection by remember { mutableStateOf(settingsManager.cameraSelection) }
    var isStarting by remember { mutableStateOf(false) }
    var isStopping by remember { mutableStateOf(false) }
    var startupTimeoutJob by remember { mutableStateOf<Job?>(null) }
    var detectedIpAddress by remember { mutableStateOf(getIosDeviceNetworkEndpoint()) }
    var showLocationRequiredDialog by remember { mutableStateOf(false) }
    var showCameraPermissionDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val locationProvider = remember {
        IosLocationProvider(
            onLocationUpdated = { rawLat, rawLon ->
                val rLat = kotlin.math.round(rawLat * 100.0) / 100.0
                val rLon = kotlin.math.round(rawLon * 100.0) / 100.0
                viewModel.currentLat = rLat
                viewModel.currentLon = rLon
                settingsManager.saveLocation(rLat.toFloat(), rLon.toFloat())
                viewModel.locationRefreshStatus = MainViewModel.LocationRefreshStatus.SUCCESS
                coroutineScope.launch {
                    delay(1500L)
                    viewModel.locationRefreshStatus = MainViewModel.LocationRefreshStatus.IDLE
                }
            },
            onError = { err ->
                println("Location error: $err")
                viewModel.locationRefreshStatus = MainViewModel.LocationRefreshStatus.FAILURE
                coroutineScope.launch {
                    delay(1500L)
                    viewModel.locationRefreshStatus = MainViewModel.LocationRefreshStatus.IDLE
                }
            }
        )
    }

    LaunchedEffect(viewModel.isEngineRunning) {
        if (viewModel.isEngineRunning) {
            startupTimeoutJob?.cancel()
            startupTimeoutJob = null
            isStarting = false
            while (viewModel.isEngineRunning) {
                detectedIpAddress = getIosDeviceNetworkEndpoint()
                delay(10000L)
            }
        } else {
            isStarting = false
            detectedIpAddress = getIosDeviceNetworkEndpoint()
        }
    }

    val startEngineAction = {
        if (!isStarting && !isStopping) {
            isStarting = true
            startupTimeoutJob?.cancel()
            startupTimeoutJob = coroutineScope.launch {
                delay(10000L)
                if (isStarting) {
                    isStarting = false
                    withContext(Dispatchers.IO) {
                        viewModel.stopEngineSync()
                    }
                }
            }
            val lat = viewModel.currentLat ?: 0.0
            val lon = viewModel.currentLon ?: 0.0
            val timeMs = Clock.System.now().toEpochMilliseconds()
            val cameraId =
                if (activeCameraSelection == "demo") "demo" else settingsManager.internalCameraId
            viewModel.executeStartEngine(
                cameraId = cameraId,
                lat = lat,
                lon = lon,
                timeMs = timeMs,
                onFailed = {
                    startupTimeoutJob?.cancel()
                    startupTimeoutJob = null
                    isStarting = false
                }
            )
        }
    }

    LaunchedEffect(Unit) {
        val launchArgs =
            platform.Foundation.NSProcessInfo.processInfo.arguments.filterIsInstance<String>()
        if (launchArgs.contains("-testEphemeris")) {
            val asteroids = viewModel.getAsteroids()
            println("EPHEMERIS_TEST_ASTEROIDS: count=${asteroids.size}, first=${asteroids.firstOrNull()?.commonName}")
            val cometsBefore = viewModel.getComets()
            println("EPHEMERIS_TEST_COMETS_BEFORE: count=${cometsBefore.size}")
            val refreshSuccess = catalogDb.refreshComets()
            val cometsAfter = viewModel.getComets()
            println("EPHEMERIS_TEST_COMETS_AFTER: refreshSuccess=$refreshSuccess, count=${cometsAfter.size}, first=${cometsAfter.firstOrNull()?.commonName}")
        }
        if (launchArgs.contains("-autoStartDemo")) {
            activeCameraSelection = "demo"
            startEngineAction()
            if (launchArgs.contains("-openCatalog")) {
                viewModel.showCatalog = true
            }
            val catIdx = launchArgs.indexOf("-openCatalogCategory")
            if (catIdx != -1 && catIdx + 1 < launchArgs.size) {
                viewModel.catalogSelectedCategory = launchArgs[catIdx + 1]
                viewModel.showCatalog = true
            }
        } else if (launchArgs.contains("-autoRefreshLocation")) {
            viewModel.locationRefreshStatus = MainViewModel.LocationRefreshStatus.REFRESHING
            locationProvider.requestLocation()
        } else if (settingsManager.locationMode == "Auto" && !viewModel.hasValidLocation) {
            locationProvider.requestLocation()
        }
    }

    LaunchedEffect(viewModel.isEngineRunning) {
        windowController.setKeepScreenOn(viewModel.isEngineRunning)
    }

    CompositionLocalProvider(
        LocalPlatformCapabilities provides capabilities,
        LocalPlatformWindowController provides windowController
    ) {
        NightVisionTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                when (currentScreen) {
                    "main" -> MainScreen(
                        viewModel = viewModel,
                        settingsManager = settingsManager,
                        isCameraConnected = false,
                        activeCameraSelection = activeCameraSelection,
                        isStopping = isStopping,
                        isStarting = isStarting,
                        detectedIpAddress = detectedIpAddress,
                        onCameraSelectionChanged = { activeCameraSelection = it },
                        onNavigateToTuner = { currentScreen = "tuner" },
                        onEnterPipMode = { },
                        onToggleEngine = {
                            if (isStarting || isStopping) return@MainScreen

                            if (viewModel.isEngineRunning) {
                                startupTimeoutJob?.cancel()
                                startupTimeoutJob = null
                                isStopping = true
                                coroutineScope.launch {
                                    withContext(Dispatchers.IO) {
                                        viewModel.stopEngineSync()
                                    }
                                    isStopping = false
                                }
                            } else {
                                if (!viewModel.hasValidLocation && activeCameraSelection != "demo") {
                                    showLocationRequiredDialog = true
                                    return@MainScreen
                                }
                                if (activeCameraSelection != "demo") {
                                    val authStatus =
                                        AVCaptureDevice.authorizationStatusForMediaType(
                                            AVMediaTypeVideo
                                        )
                                    when (authStatus) {
                                        AVAuthorizationStatusAuthorized -> {
                                            startEngineAction()
                                        }

                                        AVAuthorizationStatusNotDetermined -> {
                                            AVCaptureDevice.requestAccessForMediaType(
                                                AVMediaTypeVideo
                                            ) { granted ->
                                                coroutineScope.launch {
                                                    if (granted) {
                                                        startEngineAction()
                                                    } else {
                                                        showCameraPermissionDialog = true
                                                    }
                                                }
                                            }
                                        }

                                        else -> {
                                            showCameraPermissionDialog = true
                                        }
                                    }
                                } else {
                                    startEngineAction()
                                }
                            }
                        },
                        onRefreshLocation = {
                            if (viewModel.locationRefreshStatus == MainViewModel.LocationRefreshStatus.IDLE) {
                                viewModel.locationRefreshStatus =
                                    MainViewModel.LocationRefreshStatus.REFRESHING
                                locationProvider.requestLocation()
                            }
                        }
                    )

                    "tuner" -> TunerScreen(
                        viewModel = viewModel,
                        settingsManager = settingsManager,
                        activeCameraSelection = activeCameraSelection,
                        onBack = { currentScreen = "main" }
                    )
                }

                if (showLocationRequiredDialog) {
                    InfoPopup(
                        title = stringResource(Res.string.title_observer_location),
                        onDismiss = { showLocationRequiredDialog = false }
                    ) {
                        Text(
                            stringResource(Res.string.info_location),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                if (showCameraPermissionDialog) {
                    NightVisionDialog(
                        title = stringResource(Res.string.title_permissions_required),
                        text = stringResource(Res.string.desc_permissions_required),
                        confirmButtonText = stringResource(Res.string.btn_open_settings),
                        onConfirm = {
                            showCameraPermissionDialog = false
                            val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString)
                            if (url != null) {
                                UIApplication.sharedApplication.openURL(url)
                            }
                        },
                        dismissButtonText = stringResource(Res.string.btn_cancel),
                        onDismiss = { showCameraPermissionDialog = false }
                    )
                }

                if (viewModel.showLocationDialog) {
                    LocationInputDialog(
                        initialLat = viewModel.currentLat,
                        initialLon = viewModel.currentLon,
                        onDismiss = { viewModel.showLocationDialog = false },
                        onSave = { lat, lon ->
                            settingsManager.saveLocation(lat, lon)
                            settingsManager.locationMode = "Manual"
                            viewModel.currentLat = lat.toDouble()
                            viewModel.currentLon = lon.toDouble()
                        }
                    )
                }

                if (viewModel.isCameraStuck && viewModel.isEngineRunning && !isStopping) {
                    AlertDialog(
                        onDismissRequest = { /* Force user choice */ },
                        title = {
                            Text(
                                stringResource(Res.string.title_camera_stuck),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        text = {
                            Text(
                                stringResource(Res.string.body_camera_stuck),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        isStopping = true
                                        withContext(Dispatchers.IO) {
                                            viewModel.stopEngineSync()
                                        }
                                        isStopping = false
                                        startEngineAction()
                                    }
                                }
                            ) {
                                Text(stringResource(Res.string.btn_restart))
                            }
                        },
                        dismissButton = {
                            OutlinedButton(onClick = { viewModel.dismissCameraStuckDialog() }) {
                                Text(stringResource(Res.string.btn_cancel))
                            }
                        }
                    )
                }
            }
        }
    }
}
