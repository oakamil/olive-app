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
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import platform.posix.gethostname
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
private fun getIosDeviceNetworkEndpoint(): String = memScoped {
    val ipBuf = allocArray<ByteVar>(64)
    olive_ios_get_local_ip(ipBuf, 64u)
    val ip = ipBuf.toKString().ifEmpty { "127.0.0.1" }

    val hostBuf = allocArray<ByteVar>(256)
    val rawHost = if (gethostname(hostBuf, 255u) == 0) {
        hostBuf[255] = 0
        hostBuf.toKString().trim().ifEmpty { "localhost" }
    } else {
        "localhost"
    }
    val host = if (rawHost.equals("localhost", ignoreCase = true) ||
        rawHost.endsWith(".local", ignoreCase = true)
    ) {
        rawHost
    } else {
        "$rawHost.local"
    }
    "$host ($ip)"
}

private suspend fun refreshExternalCameraFormats(
    engine: EngineBridge,
    viewModel: MainViewModel,
    settingsManager: SettingsManager
) {
    val formatsJson = withContext(Dispatchers.IO) {
        engine.getAvailableExternalCameraFormats()
    }
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
    val appVersion = remember {
        (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String)
            .orEmpty()
    }
    var capabilities by remember {
        mutableStateOf(
            ApplePlatformCapabilities(
                supportsUsbCamera = isPad,
                hasInternalCamera = true,
                appVersion = appVersion
            )
        )
    }
    val engine = remember { IosEngineBridge() }
    val catalogDb = remember { IosCatalogDatabase(storage) }
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
    var isCameraConnected by remember { mutableStateOf(false) }
    var initialDiscoveryCompleted by remember { mutableStateOf(false) }
    var userSelectedCamera by remember { mutableStateOf(false) }
    var activeCameraSelection by remember { mutableStateOf(settingsManager.cameraSelection) }
    var isStarting by remember { mutableStateOf(false) }
    var activeStopCount by remember { mutableStateOf(0) }
    val isStopping = activeStopCount > 0
    var isResolvingToggle by remember { mutableStateOf(false) }
    val startupTimeoutJob = remember { mutableStateOf<Job?>(null) }
    var detectedIpAddress by remember { mutableStateOf("") }
    var showLocationRequiredDialog by remember { mutableStateOf(false) }
    var showCameraPermissionDialog by remember { mutableStateOf(false) }
    var showLocationPermissionDialog by remember { mutableStateOf(false) }
    var pendingLocationRefresh by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val cameraDiscoveryMutex = remember { Mutex() }

    fun updateCameraSelection(selection: String) {
        if (activeCameraSelection != selection) {
            activeCameraSelection = selection
        }
        if (settingsManager.cameraSelection != selection) {
            settingsManager.cameraSelection = selection
        }
    }

    fun clearStartupState() {
        startupTimeoutJob.value?.cancel()
        startupTimeoutJob.value = null
        isStarting = false
    }

    suspend fun stopEngineUi() {
        clearStartupState()
        activeStopCount++
        try {
            viewModel.stopEngine()
        } finally {
            activeStopCount--
        }
    }

    suspend fun syncCameraState(
        autoSwitchExternalOnConnect: Boolean = false,
        forceRefreshFormats: Boolean = false,
        fromHotplug: Boolean = false
    ): ApplePlatformCapabilities {
        return cameraDiscoveryMutex.withLock {
            val (hasInternal, hasExt) = withContext(Dispatchers.IO) {
                hasInternalBackCamera() to if (isPad) hasConnectedExternalCamera() else false
            }
            val wasConnected = isCameraConnected
            isCameraConnected = hasExt
            initialDiscoveryCompleted = true

            val updatedCapabilities = ApplePlatformCapabilities(
                supportsUsbCamera = isPad,
                hasInternalCamera = hasInternal,
                appVersion = appVersion
            )
            capabilities = updatedCapabilities
            if (hasExt) {
                if (!wasConnected || forceRefreshFormats) {
                    refreshExternalCameraFormats(engine, viewModel, settingsManager)
                }
                val canAutoSwitch =
                    autoSwitchExternalOnConnect &&
                            !isStarting &&
                            !isStopping &&
                            !viewModel.isEngineRunning &&
                            (fromHotplug || !userSelectedCamera)
                if (canAutoSwitch) {
                    updateCameraSelection("external")
                }
            } else {
                viewModel.availableUvcFormats = UvcFormat.FALLBACK_FORMATS
                if (!isStarting && !isStopping && !viewModel.isEngineRunning) {
                    updateCameraSelection(
                        updatedCapabilities.resolveCameraSelection(activeCameraSelection, false)
                    )
                }
            }
            updatedCapabilities
        }
    }

    suspend fun resolvedCapabilities(): ApplePlatformCapabilities =
        if (initialDiscoveryCompleted) capabilities else syncCameraState()

    fun openAppSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString)
        if (url != null) {
            UIApplication.sharedApplication.openURL(
                url = url,
                options = emptyMap<Any?, Any?>(),
                completionHandler = null
            )
        }
    }

    LaunchedEffect(isPad) {
        syncCameraState(autoSwitchExternalOnConnect = true)
    }

    DisposableEffect(isPad) {
        if (!isPad) return@DisposableEffect onDispose { }

        val nc = NSNotificationCenter.defaultCenter
        val connObs = nc.addObserverForName(
            name = AVCaptureDeviceWasConnectedNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue
        ) { _ ->
            coroutineScope.launch {
                syncCameraState(
                    autoSwitchExternalOnConnect = true,
                    forceRefreshFormats = true,
                    fromHotplug = true
                )
            }
        }

        val disconnObs = nc.addObserverForName(
            name = AVCaptureDeviceWasDisconnectedNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue
        ) { _ ->
            coroutineScope.launch {
                val updatedCapabilities = syncCameraState()
                if (!isCameraConnected) {
                    val shouldStopExternal =
                        viewModel.engineMode == MainViewModel.EngineMode.UVC ||
                                (isStarting && activeCameraSelection == "external")
                    if (shouldStopExternal) {
                        currentScreen = "main"
                        stopEngineUi()
                    }
                    if (!isCameraConnected) {
                        updateCameraSelection(
                            updatedCapabilities.resolveCameraSelection(activeCameraSelection, false)
                        )
                    }
                }
            }
        }

        onDispose {
            nc.removeObserver(connObs)
            nc.removeObserver(disconnObs)
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
            clearStartupState()
            while (viewModel.isEngineRunning) {
                detectedIpAddress = withContext(Dispatchers.IO) { getIosDeviceNetworkEndpoint() }
                delay(10000L)
            }
        } else {
            detectedIpAddress = withContext(Dispatchers.IO) { getIosDeviceNetworkEndpoint() }
        }
    }

    val startEngineAction = {
        if (!isStarting && !isStopping) {
            isStarting = true
            startupTimeoutJob.value?.cancel()
            startupTimeoutJob.value = coroutineScope.launch {
                delay(10000L)
                if (isStarting && !viewModel.isEngineRunning) {
                    startupTimeoutJob.value = null
                    stopEngineUi()
                }
            }
            coroutineScope.launch {
                val caps = resolvedCapabilities()
                if (!isStarting) return@launch

                updateCameraSelection(
                    caps.resolveCameraSelection(activeCameraSelection, isCameraConnected)
                )
                val lat = viewModel.currentLat ?: MainViewModel.INVALID_COORD
                val lon = viewModel.currentLon ?: MainViewModel.INVALID_COORD
                if (activeCameraSelection == "external") {
                    viewModel.startUvcEngine(
                        lat = lat,
                        lon = lon,
                        onFailed = { clearStartupState() }
                    )
                } else {
                    val cameraId =
                        if (activeCameraSelection == "demo") "demo" else settingsManager.internalCameraId
                    viewModel.executeStartEngine(
                        cameraId = cameraId,
                        lat = lat,
                        lon = lon,
                        onFailed = { clearStartupState() }
                    )
                }
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
                        onCameraSelectionChanged = {
                            userSelectedCamera = true
                            activeCameraSelection = it
                        },
                        onNavigateToTuner = {
                            @Suppress("AssignedValueIsNeverRead")
                            currentScreen = "tuner"
                        },
                        onEnterPipMode = { },
                        onToggleEngine = {
                            if (isStarting || isStopping || isResolvingToggle) return@MainScreen

                            if (viewModel.isEngineRunning) {
                                coroutineScope.launch { stopEngineUi() }
                            } else {
                                userSelectedCamera = true
                                isResolvingToggle = true
                                coroutineScope.launch {
                                    try {
                                        val caps = resolvedCapabilities()
                                        if (isStarting || isStopping || viewModel.isEngineRunning) {
                                            return@launch
                                        }
                                        updateCameraSelection(
                                            caps.resolveCameraSelection(
                                                activeCameraSelection,
                                                isCameraConnected
                                            )
                                        )
                                        if (!viewModel.hasValidLocation && activeCameraSelection != "demo") {
                                            showLocationRequiredDialog = true
                                            return@launch
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
                                    } finally {
                                        isResolvingToggle = false
                                    }
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
                                stopEngineUi()
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
