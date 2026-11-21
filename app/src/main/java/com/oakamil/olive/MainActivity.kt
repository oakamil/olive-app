package com.oakamil.olive

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.jiangdg.usb.USBMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.round

class MainActivity : ComponentActivity() {
    companion object {
        private const val ACTION_USB_PERMISSION = "com.oakamil.olive.USB_PERMISSION"
        private val BUILT_IN_WEBCAM_REGEX = Regex(
            "integrated|built-?in|internal|front|user.?facing|truevision|wide.?vision|easycamera|bisoncam",
            RegexOption.IGNORE_CASE
        )
    }

    private lateinit var settingsManager: SettingsManager

    private val isCameraConnected: Boolean get() = viewModel.externalCameras.isNotEmpty()
    private var activeCameraSelection by mutableStateOf("internal")
    private var isStopping by mutableStateOf(false)
    private var isStarting by mutableStateOf(false)

    private var connectedDevices: Map<String, UsbDevice>? = null

    private var isInPipMode by mutableStateOf(false)

    private val androidCapabilities: AndroidPlatformCapabilities by lazy {
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        }.getOrDefault("")
        AndroidPlatformCapabilities(
            hasInternalCamera = hasBackFacingCamera(),
            appVersion = version
        )
    }

    private fun hasBackFacingCamera(): Boolean {
        val manager = getSystemService(CAMERA_SERVICE) as? CameraManager ?: return false
        return try {
            manager.cameraIdList.any { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        } catch (e: Exception) {
            Log.w("OliveUI", "Failed to query internal camera availability", e)
            false
        }
    }

    private val viewModel: MainViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val storage = SharedPreferencesStorage(applicationContext)
                val capabilities = androidCapabilities
                val engine = OliveEngine
                val catalogDb = CatalogDatabaseHelper(applicationContext, storage)
                val hipsBackend = AndroidHiPSLocalBackend(applicationContext)
                return MainViewModel(storage, capabilities, engine, catalogDb, hipsBackend) as T
            }
        }
    }
    private var detectedIpAddress by mutableStateOf("127.0.0.1")
    private var showPermissionDialog by mutableStateOf(false)
    private var showLocationPermissionDialog by mutableStateOf(false)
    private var showLocationServicesDisabledDialog by mutableStateOf(false)
    private var showBatteryOptimizationDialog by mutableStateOf(false)
    private var showLocationRequiredDialog by mutableStateOf(false)
    private var pendingLocationRefresh = false
    private val windowController by lazy { AndroidWindowController(this) }
    private var startupTimeoutJob: Job? = null
    private var locationFetchJob: Job? = null

    private val locationPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private val hasLocationPermission: Boolean
        get() = locationPermissions.any {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }


    init {
        System.loadLibrary("olive_jni")
    }

    private val engineTimeoutReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == OliveService.ACTION_ENGINE_TIMED_OUT) {
                Log.w("OliveUI", "Received FGS timeout broadcast; syncing engine shutdown.")
                handleExternalEngineStop()
            }
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isFinishing || isDestroyed) return
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> checkForCameras(attachedUsbDevice(intent))
                UsbManager.ACTION_USB_DEVICE_DETACHED -> checkForCameras()
                ACTION_USB_PERMISSION -> {
                    if (!isStarting || activeCameraSelection != "external") return
                    val device = IntentCompat.getParcelableExtra(
                        intent,
                        UsbManager.EXTRA_DEVICE,
                        UsbDevice::class.java
                    )
                    if (device != null && device.deviceName != viewModel.selectedExternalCameraId) return
                    if (device != null && intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED,
                            false
                        )
                    ) {
                        openAndStartUvcCamera(device)
                    } else {
                        startupTimeoutJob?.cancel()
                        startupTimeoutJob = null
                        isStarting = false
                    }
                }
            }
        }
    }

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val prefs = getPreferences(MODE_PRIVATE)
        if (!prefs.getBoolean("has_prompted_post_notifications", false)) {
            prefs.edit { putBoolean("has_prompted_post_notifications", true) }
            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun getRequiredEnginePermissions(): List<String> {
        return if (activeCameraSelection == "demo") {
            emptyList()
        } else {
            listOf(Manifest.permission.CAMERA)
        }
    }

    private val requestCameraLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                executeStartEngine()
            } else {
                isStarting = false
                showPermissionDialog = true
            }
        }

    private val requestLocationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val granted = permissions.values.any { it }
            if (granted) {
                if (pendingLocationRefresh) {
                    pendingLocationRefresh = false
                    val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
                    if (!LocationManagerCompat.isLocationEnabled(locationManager)) {
                        showLocationServicesDisabledDialog = true
                    } else {
                        fetchLocation(isManualRefresh = true)
                    }
                } else {
                    fetchLocation(isManualRefresh = false)
                }
            } else {
                val wasPending = pendingLocationRefresh
                pendingLocationRefresh = false
                viewModel.locationRefreshStatus = MainViewModel.LocationRefreshStatus.IDLE
                val shouldShowRationale = locationPermissions.any {
                    ActivityCompat.shouldShowRequestPermissionRationale(this@MainActivity, it)
                }
                if (wasPending && !shouldShowRationale) {
                    showLocationPermissionDialog = true
                }
            }
        }


    private fun copyAssetDatabase() {
        val assetName = "default_database.npz"
        val outFile = File(filesDir, assetName)
        val tmpFile = File(filesDir, "$assetName.tmp")
        val prefs = getPreferences(MODE_PRIVATE)
        val currentVersionCode = try {
            val info = packageManager.getPackageInfo(packageName, 0)
            PackageInfoCompat.getLongVersionCode(info)
        } catch (_: Exception) {
            -1L
        }
        val copiedVersionCode = prefs.getLong("copied_asset_db_version", -1L)
        if (outFile.exists() && currentVersionCode > 0L && copiedVersionCode == currentVersionCode) {
            return
        }

        try {
            if (tmpFile.exists()) {
                tmpFile.delete()
            }
            assets.open(assetName).use { inputStream ->
                FileOutputStream(tmpFile).use { outputStream ->
                    val buffer = ByteArray(1024 * 4)
                    var read: Int
                    while (inputStream.read(buffer).also { read = it } != -1) {
                        outputStream.write(buffer, 0, read)
                    }
                    outputStream.flush()
                }
            }
            Files.move(tmpFile.toPath(), outFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            if (currentVersionCode > 0L) {
                prefs.edit { putLong("copied_asset_db_version", currentVersionCode) }
            }
        } catch (e: Exception) {
            tmpFile.delete()
            Log.e("OliveUI", "Failed to copy $assetName", e)
        }
    }

    private fun fetchLocation(isManualRefresh: Boolean = false) {
        if (isManualRefresh) {
            if (viewModel.locationRefreshStatus != MainViewModel.LocationRefreshStatus.IDLE) return
            viewModel.locationRefreshStatus = MainViewModel.LocationRefreshStatus.REFRESHING
            locationFetchJob?.cancel()
        } else {
            if (locationFetchJob?.isActive == true) return
        }

        locationFetchJob = lifecycleScope.launch {
            val startTime = System.currentTimeMillis()
            try {
                val loc = getFreshLocation()
                if (isManualRefresh) {
                    val elapsed = System.currentTimeMillis() - startTime
                    if (elapsed < 500L) {
                        delay(500L - elapsed)
                    }
                }

                if (MainViewModel.isValidCoordinate(loc.first, loc.second)) {
                    val rLat = round(loc.first * 100.0) / 100.0
                    val rLon = round(loc.second * 100.0) / 100.0
                    if (settingsManager.locationMode == "Auto") {
                        viewModel.currentLat = rLat
                        viewModel.currentLon = rLon
                        settingsManager.saveLocation(rLat.toFloat(), rLon.toFloat())
                    }
                    if (isManualRefresh) {
                        viewModel.locationRefreshStatus =
                            MainViewModel.LocationRefreshStatus.SUCCESS
                        delay(1500L)
                    }
                } else if (isManualRefresh) {
                    viewModel.locationRefreshStatus = MainViewModel.LocationRefreshStatus.FAILURE
                    delay(1500L)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("OliveUI", "Error fetching location", e)
                if (isManualRefresh) {
                    viewModel.locationRefreshStatus = MainViewModel.LocationRefreshStatus.FAILURE
                    delay(1500L)
                }
            } finally {
                if (isManualRefresh) {
                    viewModel.locationRefreshStatus = MainViewModel.LocationRefreshStatus.IDLE
                }
            }
        }
    }

    private fun handleRefreshLocation() {
        if (hasLocationPermission) {
            val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
            if (!LocationManagerCompat.isLocationEnabled(locationManager)) {
                showLocationServicesDisabledDialog = true
                return
            }
            fetchLocation(isManualRefresh = true)
        } else {
            pendingLocationRefresh = true
            requestLocationPermissionLauncher.launch(locationPermissions)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.registerReceiver(
            this,
            engineTimeoutReceiver,
            IntentFilter(OliveService.ACTION_ENGINE_TIMED_OUT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            usbReceiver,
            IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        hideSystemUI()

        lifecycleScope.launch(Dispatchers.IO) {
            copyAssetDatabase()
            OliveEngine.initJni(filesDir.absolutePath)
        }

        settingsManager = viewModel.settingsManager
        activeCameraSelection = settingsManager.cameraSelection

        settingsManager.savedLocation?.let { (lat, lon) ->
            viewModel.currentLat = lat
            viewModel.currentLon = lon
        }

        val prefs = getPreferences(MODE_PRIVATE)
        val isFirstRunLocation = prefs.getBoolean("first_run_location_permission", true)
        if (isFirstRunLocation) {
            prefs.edit { putBoolean("first_run_location_permission", false) }
            requestLocationPermissionLauncher.launch(locationPermissions)
        } else if (settingsManager.locationMode == "Auto" && hasLocationPermission) {
            fetchLocation(isManualRefresh = false)
        }


        checkForCameras(if (savedInstanceState == null) attachedUsbDevice(intent) else null)

        setContent {
            CompositionLocalProvider(
                LocalPlatformCapabilities provides androidCapabilities,
                LocalPlatformWindowController provides windowController
            ) {
                LaunchedEffect(viewModel.isEngineRunning) {
                    updateAutoPip(viewModel.isEngineRunning)
                    windowController.setKeepScreenOn(viewModel.isEngineRunning)
                    if (viewModel.isEngineRunning) {
                        startupTimeoutJob?.cancel()
                        startupTimeoutJob = null
                        isStarting = false

                        while (viewModel.isEngineRunning) {
                            detectedIpAddress = getDeviceIpAddress()
                            delay(10000)
                        }
                    } else {
                        windowController.restoreScreenBrightness()
                    }
                }

                OliveTheme(theme = viewModel.currentTheme) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        if (showPermissionDialog) {
                            val denied = getRequiredEnginePermissions().filter {
                                ContextCompat.checkSelfPermission(
                                    this@MainActivity,
                                    it
                                ) != PackageManager.PERMISSION_GRANTED
                            }
                            val shouldShowRationale = denied.any {
                                ActivityCompat.shouldShowRequestPermissionRationale(
                                    this@MainActivity,
                                    it
                                )
                            }
                            val prefs = getPreferences(MODE_PRIVATE)
                            val isFirstRun = prefs.getBoolean("first_run_camera_permissions", true)
                            val isPermanentlyDenied = !shouldShowRationale && !isFirstRun

                            NightVisionDialog(
                                title = stringResource(Res.string.title_permissions_required),
                                text = stringResource(Res.string.desc_permissions_required),
                                confirmButtonText = stringResource(
                                    if (isPermanentlyDenied) Res.string.btn_open_settings else Res.string.btn_continue
                                ),
                                onConfirm = { handlePermissionRequest() },
                                onDismiss = {
                                    showPermissionDialog = false
                                    isStarting = false
                                }
                            )
                        }

                        if (showLocationPermissionDialog) {
                            NightVisionDialog(
                                title = stringResource(Res.string.title_location_permission_required),
                                text = stringResource(Res.string.desc_location_permission_required),
                                confirmButtonText = stringResource(Res.string.btn_open_settings),
                                onConfirm = {
                                    showLocationPermissionDialog = false
                                    val intent =
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.fromParts("package", packageName, null)
                                        }
                                    startActivity(intent)
                                },
                                onDismiss = { showLocationPermissionDialog = false }
                            )
                        }

                        if (showLocationServicesDisabledDialog) {
                            NightVisionDialog(
                                title = stringResource(Res.string.title_location_services_disabled),
                                text = stringResource(Res.string.desc_location_services_disabled),
                                confirmButtonText = stringResource(Res.string.btn_open_settings),
                                onConfirm = {
                                    showLocationServicesDisabledDialog = false
                                    try {
                                        val intent =
                                            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                        startActivity(intent)
                                    } catch (_: Exception) {
                                    }
                                },
                                dismissButtonText = stringResource(Res.string.btn_cancel),
                                onDismiss = { showLocationServicesDisabledDialog = false }
                            )
                        }

                        if (showBatteryOptimizationDialog) {
                            NightVisionDialog(
                                title = stringResource(Res.string.title_battery_optimization),
                                text = stringResource(Res.string.body_battery_optimization),
                                confirmButtonText = stringResource(Res.string.btn_open_settings),
                                onConfirm = {
                                    showBatteryOptimizationDialog = false
                                    getPreferences(MODE_PRIVATE).edit {
                                        putBoolean(
                                            "has_prompted_battery_opt",
                                            true
                                        )
                                    }
                                    val intent =
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = "package:$packageName".toUri()
                                        }
                                    try {
                                        startActivity(intent)
                                    } catch (_: Exception) {
                                    }
                                },
                                dismissButtonText = stringResource(Res.string.btn_skip),
                                onDismiss = {
                                    showBatteryOptimizationDialog = false
                                    getPreferences(MODE_PRIVATE).edit {
                                        putBoolean(
                                            "has_prompted_battery_opt",
                                            true
                                        )
                                    }
                                    toggleEngine() // Skip settings and continue start flow
                                }
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

                        if (isInPipMode) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(2.dp)
                            ) {
                                GuidanceDisplay(
                                    viewModel.engineStats,
                                    settingsManager.guidanceMode == "alt_az",
                                    false,
                                    settingsManager.motionSensorMode != 0
                                )
                            }
                        } else {
                            MainScreen(
                                viewModel = viewModel,
                                settingsManager = settingsManager,
                                isCameraConnected = isCameraConnected,
                                activeCameraSelection = activeCameraSelection,
                                isStopping = isStopping,
                                isStarting = isStarting,
                                detectedIpAddress = detectedIpAddress,
                                onCameraSelectionChanged = { updateCameraSelection(it) },
                                onEnterPipMode = { enterPipMode() },
                                onToggleEngine = { toggleEngine() },
                                onRefreshLocation = { handleRefreshLocation() }
                            )
                        }


                        if (viewModel.isCameraStuck && viewModel.isEngineRunning && !isStopping) {
                            OliveAlertDialog(
                                title = stringResource(Res.string.title_camera_stuck),
                                text = stringResource(Res.string.body_camera_stuck),
                                confirmButtonText = stringResource(Res.string.btn_restart),
                                onConfirm = {
                                    stopEngineFromUi {
                                        val launchIntent =
                                            baseContext.packageManager.getLaunchIntentForPackage(
                                                baseContext.packageName
                                            )
                                        val componentName = launchIntent?.component
                                        if (componentName != null) {
                                            val restartIntent =
                                                Intent.makeRestartActivityTask(componentName)
                                            restartIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                                            baseContext.startActivity(restartIntent)
                                            finishAffinity()
                                        } else {
                                            Runtime.getRuntime().exit(0)
                                        }
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            checkForCameras(attachedUsbDevice(intent))
        }
    }

    // Intercepts configuration changes so we can re-apply full screen
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        windowController.updateConfiguration(newConfig)
        hideSystemUI()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }


    private fun enterPipMode() {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(true)
        }
        enterPictureInPictureMode(builder.build())
    }

    private fun updateAutoPip(enable: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val builder = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .setAutoEnterEnabled(enable)
            setPictureInPictureParams(builder.build())
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        lifecycleScope.launch {
            delay(100)
            isInPipMode = isInPictureInPictureMode
        }
    }


    override fun onResume() {
        super.onResume()
        hideSystemUI()
        checkForCameras()
        if (settingsManager.locationMode == "Auto" && hasLocationPermission && !viewModel.hasValidLocation) {
            fetchLocation(isManualRefresh = false)
        }
        if (viewModel.isEngineRunning && !isStopping) {
            Log.d(
                "OliveUI",
                "App resumed, engine running mode: ${viewModel.engineMode}"
            )
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(engineTimeoutReceiver)
        } catch (_: Exception) {
        }
        try {
            unregisterReceiver(usbReceiver)
        } catch (_: Exception) {
        }
        windowController.restoreScreenBrightness()
        if (isStarting || viewModel.isEngineRunning || viewModel.uvcWrapper != null) {
            val clearGoTo = viewModel.cancelAndResetForDestroy()
            runBlocking(Dispatchers.IO) {
                viewModel.stopEngineNativeSync(clearGoTo = clearGoTo)
            }
        }
        stopBackgroundService()
        super.onDestroy()
    }

    private fun updateCameraSelection(selection: String) {
        settingsManager.cameraSelection = selection
        activeCameraSelection = selection
    }

    private fun attachedUsbDevice(intent: Intent?): UsbDevice? =
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            null
        }

    private fun checkForCameras(attachedDevice: UsbDevice? = null) {
        if (isFinishing || isDestroyed) return
        val devices = (getSystemService(USB_SERVICE) as? UsbManager)?.deviceList?.values
            ?.filter { isUvcDevice(it) }
            ?.sortedWith(
                compareBy<UsbDevice> {
                    BUILT_IN_WEBCAM_REGEX.containsMatchIn(
                        "${it.productName.orEmpty()} ${it.manufacturerName.orEmpty()}"
                    )
                }.thenByDescending { it.deviceId }
            )
            .orEmpty()
        val freshMap = devices.associateBy { it.deviceName }
        val previousIds = connectedDevices?.keys
        connectedDevices = freshMap

        val isUvcBusy = !isStopping && (
                viewModel.engineMode == MainViewModel.EngineMode.UVC ||
                        (isStarting && activeCameraSelection == "external")
                )
        if ((isUvcBusy && viewModel.selectedExternalCameraId !in freshMap) || devices.isEmpty()) {
            handleUvcDisconnect()
        }

        val newlyAttachedId = attachedDevice?.deviceName?.takeIf { it in freshMap }
            ?: previousIds?.let { prev -> devices.firstOrNull { it.deviceName !in prev }?.deviceName }

        val baseNames = devices.map { d ->
            d.productName?.trim()?.takeIf { it.isNotEmpty() }
                ?: d.manufacturerName?.trim()?.takeIf { it.isNotEmpty() }
                ?: String.format(Locale.US, "USB Camera (%04x:%04x)", d.vendorId, d.productId)
        }
        val items = devices.mapIndexed { i, d ->
            val base = baseNames[i]
            MainViewModel.ExternalCamera(
                id = d.deviceName,
                name = if (baseNames.count { it == base } > 1) "$base (${i + 1})" else base
            )
        }
        viewModel.updateExternalCameras(
            cameras = items,
            preferredId = newlyAttachedId,
            lockCurrentSelection = isUvcBusy
        )

        if (devices.isEmpty()) {
            val resolved = androidCapabilities.resolveCameraSelection(activeCameraSelection, false)
            if (resolved != activeCameraSelection) {
                updateCameraSelection(resolved)
            }
        } else if (!isStarting && !isStopping && !viewModel.isEngineRunning &&
            (previousIds == null || newlyAttachedId != null)
        ) {
            updateCameraSelection("external")
        }
    }

    private fun isUvcDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
        }
        return false
    }


    @SuppressLint("MissingPermission")
    private fun getLastKnownLocation(locationManager: LocationManager): Pair<Double, Double> {
        return try {
            val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val bestLoc = if (lastGps != null && lastNetwork != null) {
                if (lastGps.time > lastNetwork.time) lastGps else lastNetwork
            } else {
                lastGps ?: lastNetwork
            }
            if (bestLoc != null) {
                Pair(bestLoc.latitude, bestLoc.longitude)
            } else {
                Pair(MainViewModel.INVALID_COORD, MainViewModel.INVALID_COORD)
            }
        } catch (_: Exception) {
            Pair(MainViewModel.INVALID_COORD, MainViewModel.INVALID_COORD)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun getFreshLocation(): Pair<Double, Double> {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        return withTimeoutOrNull(10000L) {
            suspendCancellableCoroutine { cont ->
                val cancellationSignal = CancellationSignal()
                cont.invokeOnCancellation {
                    cancellationSignal.cancel()
                }
                val executor = ContextCompat.getMainExecutor(this@MainActivity)
                var hasResumed = false

                val handleLocation: (Location?) -> Unit = { loc ->
                    if (!hasResumed && loc != null && cont.isActive) {
                        hasResumed = true
                        cancellationSignal.cancel()
                        cont.resume(Pair(loc.latitude, loc.longitude))
                    }
                }

                val enabledProviders = mutableListOf<String>()
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    enabledProviders.add(LocationManager.GPS_PROVIDER)
                }
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    enabledProviders.add(LocationManager.NETWORK_PROVIDER)
                }

                if (enabledProviders.isEmpty()) {
                    if (cont.isActive) {
                        cont.resume(getLastKnownLocation(locationManager))
                    }
                    return@suspendCancellableCoroutine
                }

                var pendingCount = enabledProviders.size
                for (provider in enabledProviders) {
                    try {
                        LocationManagerCompat.getCurrentLocation(
                            locationManager,
                            provider,
                            cancellationSignal,
                            executor
                        ) { location: Location? ->
                            if (location != null) {
                                handleLocation(location)
                            } else {
                                pendingCount--
                                if (pendingCount <= 0 && !hasResumed && cont.isActive) {
                                    hasResumed = true
                                    cont.resume(getLastKnownLocation(locationManager))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("OliveUI", "Failed to query location provider: $provider", e)
                        pendingCount--
                        if (pendingCount <= 0 && !hasResumed && cont.isActive) {
                            hasResumed = true
                            cont.resume(getLastKnownLocation(locationManager))
                        }
                    }
                }
            }
        } ?: getLastKnownLocation(locationManager)
    }

    private fun getDeviceIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in Collections.list(interfaces)) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                if (name.contains("rmnet") || name.contains("ccmni") || name.contains("tun")) continue

                val addrs = intf.inetAddresses
                for (addr in Collections.list(addrs)) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore
        }
        return "127.0.0.1"
    }

    private fun startBackgroundService(mode: String) {
        val hasCameraPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val promoteForeground = hasCameraPermission && mode != "DEMO"
        val serviceIntent = Intent(this, OliveService::class.java).apply {
            putExtra(OliveService.EXTRA_ENGINE_MODE, mode)
            putExtra(OliveService.EXTRA_PROMOTE_FOREGROUND, promoteForeground)
        }
        if (promoteForeground) {
            ContextCompat.startForegroundService(this, serviceIntent)
            maybeRequestNotificationPermission()
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopBackgroundService() {
        val serviceIntent = Intent(this, OliveService::class.java)
        stopService(serviceIntent)
    }

    private suspend fun stopEngineAndService() {
        startupTimeoutJob?.cancel()
        startupTimeoutJob = null
        viewModel.stopEngine()
        stopBackgroundService()
    }

    private fun stopEngineFromUi(
        afterStop: (() -> Unit)? = null
    ) {
        if (isStopping) return
        isStopping = true
        lifecycleScope.launch {
            try {
                stopEngineAndService()
            } finally {
                isStopping = false
            }
            afterStop?.invoke()
        }
    }

    private fun releaseUvcWrapperAsync() {
        val wrapperToRelease = viewModel.uvcWrapper ?: return
        viewModel.uvcWrapper = null
        lifecycleScope.launch(Dispatchers.IO) {
            wrapperToRelease.release()
        }
    }

    private fun handleExternalEngineStop() {
        startupTimeoutJob?.cancel()
        startupTimeoutJob = null
        isStarting = false
        if (!viewModel.isEngineRunning) return
        stopEngineFromUi()
    }

    private fun handleStartupFailure() {
        startupTimeoutJob?.cancel()
        startupTimeoutJob = null
        isStarting = false
        releaseUvcWrapperAsync()
        stopBackgroundService()
    }

    private fun handleUvcDisconnect() {
        val wasUvcOrStartingExternal =
            viewModel.engineMode == MainViewModel.EngineMode.UVC ||
                    (isStarting && activeCameraSelection == "external")
        if (wasUvcOrStartingExternal) {
            startupTimeoutJob?.cancel()
            startupTimeoutJob = null
            isStarting = false
            stopEngineFromUi()
        } else if (!isStopping) {
            releaseUvcWrapperAsync()
        }
    }

    private fun armStartupTimeout() {
        startupTimeoutJob?.cancel()
        startupTimeoutJob = lifecycleScope.launch {
            delay(5000L)
            if (isStarting && !viewModel.isEngineRunning) {
                Log.w("OliveUI", "Engine startup timed out! Cleaning up.")
                isStarting = false
                startupTimeoutJob = null
                stopEngineFromUi()
            }
        }
    }

    private fun toggleEngine() {
        if (isStarting || isStopping) return

        if (viewModel.isEngineRunning) {
            stopEngineFromUi()
        } else {
            val resolved =
                androidCapabilities.resolveCameraSelection(activeCameraSelection, isCameraConnected)
            if (resolved != activeCameraSelection) {
                updateCameraSelection(resolved)
            }
            val isDemoMode = activeCameraSelection == "demo"
            if (!isDemoMode && !viewModel.hasValidLocation) {
                showLocationRequiredDialog = true
                return
            }

            val permissions = getRequiredEnginePermissions()
            val denied = permissions.filter {
                ContextCompat.checkSelfPermission(
                    this,
                    it
                ) != PackageManager.PERMISSION_GRANTED
            }
            if (denied.isNotEmpty()) {
                val shouldShowRationale = denied.any {
                    ActivityCompat.shouldShowRequestPermissionRationale(this, it)
                }
                val prefs = getPreferences(MODE_PRIVATE)
                val isFirstRun = prefs.getBoolean("first_run_camera_permissions", true)

                if (shouldShowRationale || isFirstRun) {
                    prefs.edit { putBoolean("first_run_camera_permissions", false) }
                    requestCameraLauncher.launch(denied.toTypedArray())
                } else {
                    isStarting = false
                    showPermissionDialog = true
                }
            } else {
                executeStartEngine()
            }
        }
    }

    private fun handlePermissionRequest() {
        val permissions = getRequiredEnginePermissions()
        val denied = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        val shouldShowRationale = denied.any {
            ActivityCompat.shouldShowRequestPermissionRationale(this, it)
        }
        val prefs = getPreferences(MODE_PRIVATE)
        val isFirstRun = prefs.getBoolean("first_run_camera_permissions", true)

        if (shouldShowRationale || isFirstRun) {
            prefs.edit { putBoolean("first_run_camera_permissions", false) }
            showPermissionDialog = false
            requestCameraLauncher.launch(denied.toTypedArray())
        } else {
            showPermissionDialog = false
            val intent =
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            startActivity(intent)
        }
    }

    private fun executeStartEngine() {
        checkForCameras()
        isStarting = true
        val prefs = getPreferences(MODE_PRIVATE)
        val hasPrompted = prefs.getBoolean("has_prompted_battery_opt", false)
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager

        if (!hasPrompted && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
            isStarting = false
            showBatteryOptimizationDialog = true
            return
        }

        lifecycleScope.launch {
            val selection =
                androidCapabilities.resolveCameraSelection(activeCameraSelection, isCameraConnected)
            if (selection != activeCameraSelection) {
                updateCameraSelection(selection)
            }
            val isDemoMode = selection == "demo"
            val lat = viewModel.currentLat ?: MainViewModel.INVALID_COORD
            val lon = viewModel.currentLon ?: MainViewModel.INVALID_COORD
            if (!isDemoMode && !viewModel.hasValidLocation) {
                isStarting = false
                showLocationRequiredDialog = true
                return@launch
            }
            if (!isDemoMode && ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                isStarting = false
                showPermissionDialog = true
                return@launch
            }
            var engineStarted = false

            if (selection == "external") {
                val device = viewModel.selectedExternalCameraId?.let { connectedDevices?.get(it) }
                if (device != null) {
                    engineStarted = requestUsbPermission(device)
                } else {
                    Log.e("OliveUI", "UVC camera selected but none connected!")
                    viewModel.stopEngine()
                }
            } else {
                val camId = if (isDemoMode) "demo" else settingsManager.internalCameraId
                val serviceMode = if (isDemoMode) "DEMO" else "NATIVE"
                armStartupTimeout()
                viewModel.executeStartEngine(
                    cameraId = camId,
                    lat = lat,
                    lon = lon,
                    onFailed = { handleStartupFailure() }
                )
                startBackgroundService(serviceMode)
                engineStarted = true
            }

            if (!engineStarted) {
                isStarting = false
                startupTimeoutJob?.cancel()
                startupTimeoutJob = null
            }
        }
    }

    private fun requestUsbPermission(device: UsbDevice): Boolean {
        val usbManager = getSystemService(USB_SERVICE) as? UsbManager ?: return false
        if (usbManager.hasPermission(device)) {
            openAndStartUvcCamera(device)
            return true
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val permissionIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            flags
        )
        return try {
            usbManager.requestPermission(device, permissionIntent)
            true
        } catch (e: Exception) {
            Log.e("OliveUI", "Failed to request USB permission", e)
            false
        }
    }

    private fun openAndStartUvcCamera(device: UsbDevice) {
        armStartupTimeout()
        lifecycleScope.launch(Dispatchers.IO) {
            var wrapper: UvcCameraWrapper? = null
            var handedOffToEngine = false
            try {
                val monitor = USBMonitor.getInstance(applicationContext)
                val ctrlBlock = runCatching { monitor.openDevice(device) }.getOrNull()
                if (ctrlBlock == null || ctrlBlock.connection == null) {
                    Log.e(
                        "OliveUI",
                        "Failed to open USB device connection for ${device.deviceName}"
                    )
                    runCatching { monitor.resetDevicePermission(device) }
                    withContext(Dispatchers.Main) { handleStartupFailure() }
                    return@launch
                }

                val parsed: List<UvcFormat>
                val best: UvcFormat?
                val initialized: Boolean
                try {
                    parsed = try {
                        val isUsb3 = UvcFormat.isUsb3Descriptors(ctrlBlock.rawDescriptors)
                        Log.i(
                            "OliveUI",
                            "USB Device link speed detected: ${if (isUsb3) "SuperSpeed (USB 3.x)" else "High-Speed (USB 2.0)"}"
                        )
                        UvcFormat.parseRawDescriptors(
                            ctrlBlock.rawDescriptors,
                            isUsb3Override = isUsb3
                        )
                    } catch (e: Exception) {
                        Log.w("OliveUI", "Failed to parse UVC raw descriptors", e)
                        emptyList()
                    }
                    best = if (parsed.isNotEmpty()) {
                        UvcFormat.findBestFormatForFps(
                            viewModel.settingsManager.uvcFps,
                            parsed
                        )
                    } else null
                    val width = best?.width ?: viewModel.settingsManager.uvcWidth
                    val height = best?.height ?: viewModel.settingsManager.uvcHeight
                    val fps = (best?.fps ?: viewModel.settingsManager.uvcFps).coerceAtLeast(1)
                    val format = best?.format ?: viewModel.settingsManager.uvcFormat

                    val candidate = UvcCameraWrapper()
                    wrapper = candidate
                    initialized = candidate.initialize(ctrlBlock, width, height, fps, format)
                } finally {
                    runCatching { ctrlBlock.close() }
                }

                if (!initialized) {
                    wrapper = null
                    withContext(Dispatchers.Main) { handleStartupFailure() }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed || !isStarting) {
                        return@withContext
                    }
                    if (device.deviceName != viewModel.selectedExternalCameraId) {
                        handleStartupFailure()
                        return@withContext
                    }
                    if (parsed.isNotEmpty()) {
                        viewModel.availableUvcFormats = parsed
                        if (best != null) {
                            viewModel.settingsManager.uvcFps = best.fps
                            viewModel.settingsManager.uvcWidth = best.width
                            viewModel.settingsManager.uvcHeight = best.height
                            viewModel.settingsManager.uvcFormat = best.format
                        }
                    }

                    val lat = viewModel.currentLat ?: MainViewModel.INVALID_COORD
                    val lon = viewModel.currentLon ?: MainViewModel.INVALID_COORD
                    handedOffToEngine = true
                    viewModel.startUvcEngine(
                        lat = lat,
                        lon = lon,
                        wrapper = wrapper,
                        onFailed = { handleStartupFailure() }
                    )
                    startBackgroundService("UVC")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("OliveUI", "Error starting UVC camera", e)
                withContext(Dispatchers.Main) { handleStartupFailure() }
            } finally {
                if (!handedOffToEngine) {
                    wrapper?.release()
                }
            }
        }
    }
}