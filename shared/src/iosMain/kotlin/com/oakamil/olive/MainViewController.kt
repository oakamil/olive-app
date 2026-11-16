// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
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
import com.oakamil.olive.cinterop.olive_ios_has_connected_external_camera
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceDiscoverySession
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInTelephotoCamera
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInUltraWideCamera
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInWideAngleCamera
import platform.AVFoundation.AVCaptureDeviceWasConnectedNotification
import platform.AVFoundation.AVCaptureDeviceWasDisconnectedNotification
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.Foundation.NSBundle
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPad
import platform.UIKit.UIViewController
import kotlin.math.round

@OptIn(ExperimentalForeignApi::class)
private fun hasConnectedExternalCamera(): Boolean =
    runCatching { olive_ios_has_connected_external_camera() }.getOrDefault(false)

private fun hasInternalBackCamera(): Boolean = runCatching {
    val deviceTypes = listOfNotNull(
        AVCaptureDeviceTypeBuiltInWideAngleCamera,
        AVCaptureDeviceTypeBuiltInUltraWideCamera,
        AVCaptureDeviceTypeBuiltInTelephotoCamera
    )
    AVCaptureDeviceDiscoverySession.discoverySessionWithDeviceTypes(
        deviceTypes = deviceTypes,
        mediaType = AVMediaTypeVideo,
        position = AVCaptureDevicePositionBack
    ).devices.isNotEmpty()
}.getOrDefault(false)

@OptIn(ExperimentalForeignApi::class)
private fun getIosDeviceNetworkEndpoint(): String {
    val ip = memScoped {
        val buf = allocArray<ByteVar>(64)
        olive_ios_get_local_ip(buf, 64u)
        buf.toKString().ifEmpty { "127.0.0.1" }
    }
    val rawHost = NSProcessInfo.processInfo.hostName
    val host = if (rawHost.endsWith(".local")) rawHost else "$rawHost.local"
    return "$host ($ip)"
}

private fun refreshExternalCameraFormats(
    engine: EngineBridge,
    viewModel: MainViewModel,
    settingsManager: SettingsManager
) {
    val formatsJson = engine.getAvailableExternalCameraFormats()
    if (formatsJson.isNotEmpty() && formatsJson != "[]") {
        try {
            val list = mutableListOf<UvcFormat>()
            val array = JSONArray(formatsJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val w = obj.optInt("width")
                val h = obj.optInt("height")
                val fps = obj.optInt("fps")
                val fmt = obj.optString("format")
                if (w > 0 && h > 0 && fps > 0) {
                    list.add(UvcFormat(w, h, fps, fmt))
                }
            }
            if (list.isNotEmpty()) {
                val filtered = UvcFormat.filterFormats(list)
                viewModel.availableUvcFormats = filtered
                val best = UvcFormat.findBestFormatForFps(settingsManager.uvcFps, filtered)
                settingsManager.uvcFps = best.fps
                settingsManager.uvcWidth = best.width
                settingsManager.uvcHeight = best.height
                settingsManager.uvcFormat = best.format
                Log.d(
                    "OliveUI",
                    "Refreshed external camera formats: ${filtered.size} options, active: ${best.label}"
                )
            }
        } catch (e: Exception) {
            Log.w("OliveUI", "Failed to parse external camera formats", e)
        }
    }
}

// Public entry point called from Swift (iosApp/ContentView.swift: MainViewControllerKt.MainViewController())
@Suppress("unused", "FunctionName")
fun MainViewController(): UIViewController = ComposeUIViewController {
    val storage = remember { NSUserDefaultsStorage() }
    val isPad = remember { UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPad }
    val hasInternalCamera = remember { hasInternalBackCamera() }
    val capabilities = remember(isPad, hasInternalCamera) {
        val version =
            (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String)
                .orEmpty()
        ApplePlatformCapabilities(
            supportsUsbCamera = isPad,
            hasInternalCamera = hasInternalCamera,
            appVersion = version
        )
    }
    val engine = remember { IosEngineBridge() }
    val catalogDb = remember { IosCatalogDatabase() }
    val hipsBackend = remember { IosHiPSLocalBackend() }
    val viewModel =
        remember { MainViewModel(storage, capabilities, engine, catalogDb, hipsBackend) }
    val settingsManager = viewModel.settingsManager
    val windowController = remember { AppleWindowController() }
    DisposableEffect(windowController, viewModel) {
        onDispose {
            windowController.dispose()
            viewModel.dispose()
        }
    }

    var currentScreen by remember { mutableStateOf("main") }
    var isCameraConnected by remember { mutableStateOf(if (isPad) hasConnectedExternalCamera() else false) }
    val initialCameraSelection = remember(capabilities) {
        val target = if (isCameraConnected) "external" else settingsManager.cameraSelection
        capabilities.resolveCameraSelection(target, isCameraConnected).also {
            if (it != settingsManager.cameraSelection) {
                settingsManager.cameraSelection = it
            }
        }
    }
    var activeCameraSelection by remember { mutableStateOf(initialCameraSelection) }
    var isStarting by remember { mutableStateOf(false) }
    var isStopping by remember { mutableStateOf(false) }
    var startupTimeoutJob by remember { mutableStateOf<Job?>(null) }
    var detectedIpAddress by remember { mutableStateOf(getIosDeviceNetworkEndpoint()) }
    var showLocationRequiredDialog by remember { mutableStateOf(false) }
    var showCameraPermissionDialog by remember { mutableStateOf(false) }
    var showLocationPermissionDialog by remember { mutableStateOf(false) }
    var pendingLocationRefresh by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    fun openAppSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString)
        if (url != null) {
            UIApplication.sharedApplication.openURL(url)
        }
    }

    DisposableEffect(isPad) {
        if (!isPad) return@DisposableEffect onDispose { }

        val nc = NSNotificationCenter.defaultCenter
        val connObs = nc.addObserverForName(
            name = AVCaptureDeviceWasConnectedNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue
        ) { _ ->
            val hasExt = hasConnectedExternalCamera()
            isCameraConnected = hasExt
            if (hasExt) {
                refreshExternalCameraFormats(engine, viewModel, settingsManager)
                if (activeCameraSelection != "external") {
                    activeCameraSelection = "external"
                    settingsManager.cameraSelection = "external"
                }
            }
        }

        val disconnObs = nc.addObserverForName(
            name = AVCaptureDeviceWasDisconnectedNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue
        ) { _ ->
            val hasExt = hasConnectedExternalCamera()
            isCameraConnected = hasExt
            if (!hasExt) {
                viewModel.availableUvcFormats = UvcFormat.FALLBACK_FORMATS
                val resolved = capabilities.resolveCameraSelection(activeCameraSelection, false)
                if (resolved != activeCameraSelection) {
                    activeCameraSelection = resolved
                    settingsManager.cameraSelection = resolved
                    currentScreen = "main"
                    if (viewModel.isEngineRunning) {
                        coroutineScope.launch {
                            withContext(Dispatchers.IO) {
                                viewModel.stopEngineSync()
                            }
                        }
                    }
                }
            }
        }

        onDispose {
            nc.removeObserver(connObs)
            nc.removeObserver(disconnObs)
        }
    }

    LaunchedEffect(isCameraConnected) {
        if (isCameraConnected) {
            refreshExternalCameraFormats(engine, viewModel, settingsManager)
        }
    }

    val locationProvider = remember {
        IosLocationProvider(
            onLocationUpdated = { rawLat, rawLon ->
                @Suppress("AssignedValueIsNeverRead")
                pendingLocationRefresh = false
                val rLat = round(rawLat * 100.0) / 100.0
                val rLon = round(rawLon * 100.0) / 100.0
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
                @Suppress("AssignedValueIsNeverRead")
                pendingLocationRefresh = false
                Log.w("OliveUI", "Location error: $err")
                viewModel.locationRefreshStatus = MainViewModel.LocationRefreshStatus.FAILURE
                coroutineScope.launch {
                    delay(1500L)
                    viewModel.locationRefreshStatus = MainViewModel.LocationRefreshStatus.IDLE
                }
            },
            onPermissionDenied = {
                if (pendingLocationRefresh) {
                    @Suppress("AssignedValueIsNeverRead")
                    pendingLocationRefresh = false
                    viewModel.locationRefreshStatus = MainViewModel.LocationRefreshStatus.IDLE
                    showLocationPermissionDialog = true
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
                if (isStarting && !viewModel.isEngineRunning) {
                    isStarting = false
                    withContext(Dispatchers.IO) {
                        viewModel.stopEngineSync()
                    }
                }
            }
            val effectiveSelection =
                capabilities.resolveCameraSelection(activeCameraSelection, isCameraConnected)
            if (effectiveSelection != activeCameraSelection) {
                activeCameraSelection = effectiveSelection
                settingsManager.cameraSelection = effectiveSelection
            }
            val lat = viewModel.currentLat ?: MainViewModel.INVALID_COORD
            val lon = viewModel.currentLon ?: MainViewModel.INVALID_COORD
            if (effectiveSelection == "external") {
                viewModel.startUvcEngine(
                    lat = lat,
                    lon = lon,
                    onFailed = {
                        startupTimeoutJob?.cancel()
                        startupTimeoutJob = null
                        isStarting = false
                    }
                )
            } else {
                val cameraId =
                    if (effectiveSelection == "demo") "demo" else settingsManager.internalCameraId
                viewModel.executeStartEngine(
                    cameraId = cameraId,
                    lat = lat,
                    lon = lon,
                    onFailed = {
                        startupTimeoutJob?.cancel()
                        startupTimeoutJob = null
                        isStarting = false
                    }
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        val launchArgs =
            NSProcessInfo.processInfo.arguments.filterIsInstance<String>()
        if (launchArgs.contains("-testEphemeris")) {
            val asteroids = viewModel.getAsteroids()
            Log.d(
                "EphemerisTest",
                "EPHEMERIS_TEST_ASTEROIDS: count=${asteroids.size}, first=${asteroids.firstOrNull()?.commonName}"
            )
            val cometsBefore = viewModel.getComets()
            Log.d("EphemerisTest", "EPHEMERIS_TEST_COMETS_BEFORE: count=${cometsBefore.size}")
            val refreshSuccess = catalogDb.refreshComets()
            val cometsAfter = viewModel.getComets()
            Log.d(
                "EphemerisTest",
                "EPHEMERIS_TEST_COMETS_AFTER: refreshSuccess=$refreshSuccess, count=${cometsAfter.size}, first=${cometsAfter.firstOrNull()?.commonName}"
            )
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
        OliveTheme(theme = viewModel.currentTheme) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                when (currentScreen) {
                    "main" -> MainScreen(
                        viewModel = viewModel,
                        settingsManager = settingsManager,
                        isCameraConnected = isCameraConnected,
                        activeCameraSelection = activeCameraSelection,
                        isStopping = isStopping,
                        isStarting = isStarting,
                        detectedIpAddress = detectedIpAddress,
                        onCameraSelectionChanged = { activeCameraSelection = it },
                        onNavigateToTuner = {
                            @Suppress("AssignedValueIsNeverRead")
                            currentScreen = "tuner"
                        },
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
                                val resolved = capabilities.resolveCameraSelection(
                                    activeCameraSelection,
                                    isCameraConnected
                                )
                                if (resolved != activeCameraSelection) {
                                    activeCameraSelection = resolved
                                    settingsManager.cameraSelection = resolved
                                }
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
                                pendingLocationRefresh = true
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
                        onBack = {
                            @Suppress("AssignedValueIsNeverRead")
                            currentScreen = "main"
                        }
                    )
                }

                if (showLocationRequiredDialog) {
                    InfoPopup(
                        title = stringResource(Res.string.title_observer_location),
                        onDismiss = {
                            @Suppress("AssignedValueIsNeverRead")
                            showLocationRequiredDialog = false
                        }
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
                            @Suppress("AssignedValueIsNeverRead")
                            showCameraPermissionDialog = false
                            openAppSettings()
                        },
                        dismissButtonText = stringResource(Res.string.btn_cancel),
                        onDismiss = {
                            @Suppress("AssignedValueIsNeverRead")
                            showCameraPermissionDialog = false
                        }
                    )
                }

                if (showLocationPermissionDialog) {
                    NightVisionDialog(
                        title = stringResource(Res.string.title_location_permission_required),
                        text = stringResource(Res.string.desc_location_permission_required),
                        confirmButtonText = stringResource(Res.string.btn_open_settings),
                        onConfirm = {
                            @Suppress("AssignedValueIsNeverRead")
                            showLocationPermissionDialog = false
                            openAppSettings()
                        },
                        dismissButtonText = stringResource(Res.string.btn_cancel),
                        onDismiss = {
                            @Suppress("AssignedValueIsNeverRead")
                            showLocationPermissionDialog = false
                        }
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
                    OliveAlertDialog(
                        title = stringResource(Res.string.title_camera_stuck),
                        text = stringResource(Res.string.body_camera_stuck),
                        confirmButtonText = stringResource(Res.string.btn_restart),
                        onConfirm = {
                            coroutineScope.launch {
                                isStopping = true
                                withContext(Dispatchers.IO) {
                                    viewModel.stopEngineSync()
                                }
                                isStopping = false
                                startEngineAction()
                            }
                        },
                        dismissButtonText = stringResource(Res.string.btn_cancel),
                        onDismiss = { viewModel.dismissCameraStuckDialog() },
                        showCloseButton = false,
                        dismissOnClickOutside = false
                    )
                }
            }
        }
    }
}
