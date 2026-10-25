package com.oakamil.olive

import android.Manifest
import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.location.LocationManagerCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.jiangdg.usb.USBMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import kotlin.coroutines.resume
import kotlin.math.round

class MainActivity : ComponentActivity() {
    private var usbMonitor: USBMonitor? = null
    private lateinit var settingsManager: SettingsManager

    private var isCameraConnected by mutableStateOf(false)
    private var activeCameraSelection by mutableStateOf("internal")
    private var isStopping by mutableStateOf(false)
    private var isStarting by mutableStateOf(false)

    private var connectedDevice: UsbDevice? = null

    private var currentScreen by mutableStateOf("main")
    private var isInPipMode by mutableStateOf(false)

    private val viewModel: MainViewModel by viewModels()
    private var detectedIpAddress by mutableStateOf("127.0.0.1")
    private var showPermissionDialog by mutableStateOf(false)
    private var showLocationPermissionDialog by mutableStateOf(false)
    private var showLocationServicesDisabledDialog by mutableStateOf(false)
    private var showBatteryOptimizationDialog by mutableStateOf(false)
    private var showLocationRequiredDialog by mutableStateOf(false)
    private var pendingLocationRefresh = false
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

    private fun getRequiredEnginePermissions(): List<String> {
        return mutableListOf(Manifest.permission.CAMERA)
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
        if (!outFile.exists()) {
            try {
                assets.open(assetName).use { inputStream ->
                    FileOutputStream(outFile).use { outputStream ->
                        val buffer = ByteArray(1024 * 4)
                        var read: Int
                        while (inputStream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                        outputStream.flush()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
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

        hideSystemUI()

        lifecycleScope.launch(Dispatchers.IO) {
            copyAssetDatabase()
            OliveEngine.initJni(filesDir.absolutePath)
        }

        settingsManager = SettingsManager(this.applicationContext)
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


        initUsbMonitor()
        checkForCameras()

        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED && isCameraConnected) {
            updateCameraSelection("external")
        }

        setContent {
            LaunchedEffect(viewModel.isEngineRunning) {
                updateAutoPip(viewModel.isEngineRunning)
                if (viewModel.isEngineRunning) {
                    startupTimeoutJob?.cancel()
                    startupTimeoutJob = null
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    isStarting = false

                    try {
                        while (viewModel.isEngineRunning) {
                            detectedIpAddress = getDeviceIpAddress()
                            delay(10000)
                        }
                    } finally {
                        // Cleanup if needed
                    }
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            NightVisionTheme {
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
                            title = stringResource(id = R.string.title_permissions_required),
                            text = stringResource(id = R.string.desc_permissions_required),
                            confirmButtonText = stringResource(
                                id = if (isPermanentlyDenied) R.string.btn_open_settings else R.string.btn_continue
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
                            title = stringResource(id = R.string.title_location_permission_required),
                            text = stringResource(id = R.string.desc_location_permission_required),
                            confirmButtonText = stringResource(id = R.string.btn_open_settings),
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
                            title = stringResource(id = R.string.title_location_services_disabled),
                            text = stringResource(id = R.string.desc_location_services_disabled),
                            confirmButtonText = stringResource(id = R.string.btn_open_settings),
                            onConfirm = {
                                showLocationServicesDisabledDialog = false
                                try {
                                    val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                    startActivity(intent)
                                } catch (_: Exception) {
                                }
                            },
                            dismissButtonText = stringResource(id = R.string.btn_cancel),
                            onDismiss = { showLocationServicesDisabledDialog = false }
                        )
                    }

                    if (showBatteryOptimizationDialog) {
                        NightVisionDialog(
                            title = getString(R.string.title_battery_optimization),
                            text = getString(R.string.body_battery_optimization),
                            confirmButtonText = getString(R.string.btn_open_settings),
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
                            dismissButtonText = getString(R.string.btn_skip),
                            onDismiss = {
                                showBatteryOptimizationDialog = false
                                getPreferences(MODE_PRIVATE).edit {
                                    putBoolean(
                                        "has_prompted_battery_opt",
                                        true
                                    )
                                }
                                executeStartEngine() // Skip settings and just start anyway
                            }
                        )
                    }

                    if (showLocationRequiredDialog) {
                        InfoPopup(
                            title = stringResource(R.string.title_observer_location),
                            onDismiss = { showLocationRequiredDialog = false }
                        ) {
                            Text(
                                stringResource(R.string.info_location),
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
                        when (currentScreen) {
                            "main" -> MainScreen(
                                viewModel = viewModel,
                                settingsManager = settingsManager,
                                isCameraConnected = isCameraConnected,
                                activeCameraSelection = activeCameraSelection,
                                isStopping = isStopping,
                                isStarting = isStarting,
                                detectedIpAddress = detectedIpAddress,
                                onCameraSelectionChanged = { updateCameraSelection(it) },
                                onNavigateToTuner = { currentScreen = "tuner" },
                                onEnterPipMode = { enterPipMode() },
                                onToggleEngine = { toggleEngine() },
                                onRefreshLocation = { handleRefreshLocation() }
                            )

                            "tuner" -> TunerScreen(
                                viewModel = viewModel,
                                settingsManager = settingsManager,
                                activeCameraSelection = activeCameraSelection,
                                onBack = { currentScreen = "main" }
                            )
                        }
                    }


                    if (viewModel.isCameraStuck && viewModel.isEngineRunning && !isStopping) {
                        AlertDialog(
                            onDismissRequest = { /* Force user choice */ },
                            title = {
                                Text(
                                    getString(R.string.title_camera_stuck),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            text = {
                                Text(
                                    getString(R.string.body_camera_stuck),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        val intent =
                                            baseContext.packageManager.getLaunchIntentForPackage(
                                                baseContext.packageName
                                            )
                                        val componentName = intent?.component
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
                                ) {
                                    Text(getString(R.string.btn_restart))
                                }
                            },
                            dismissButton = {
                                OutlinedButton(onClick = { viewModel.dismissCameraStuckDialog() }) {
                                    Text(getString(R.string.btn_cancel))
                                }
                            },
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Bypass USBMonitor's broken polling loop by catching the OS intent directly
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            checkForCameras()
            if (isCameraConnected) {
                updateCameraSelection("external")
            }
        }
    }

    // Intercepts configuration changes so we can re-apply full screen
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
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
        if (settingsManager.locationMode == "Auto" && hasLocationPermission && !viewModel.hasValidLocation) {
            fetchLocation(isManualRefresh = false)
        }
        if (viewModel.isEngineRunning && !isStopping) {
            // Ensure engine is actually alive or restart if needed
            // For now just logged, but we could add auto-restart logic here
            Log.d(
                "OliveUI",
                "App resumed, engine running mode: ${viewModel.engineMode}"
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.uvcWrapper?.release()
        viewModel.uvcWrapper = null

        if (viewModel.isEngineRunning) {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                viewModel.stopEngineSync()
            }
            stopBackgroundService()
        }

        try {
            usbMonitor?.unregister()
            usbMonitor?.destroy()
        } catch (e: Exception) {
            Log.w("OliveUI", "Error destroying USBMonitor in onDestroy", e)
        } finally {
            usbMonitor = null
        }
    }

    private fun updateCameraSelection(selection: String) {
        settingsManager.cameraSelection = selection
        activeCameraSelection = selection
    }

    private fun checkForCameras() {
        if (isFinishing || isDestroyed) return
        val usbManager = getSystemService(USB_SERVICE) as? UsbManager
        val deviceList = usbManager?.deviceList?.values?.toList() ?: emptyList()
        val camera = deviceList.firstOrNull { isUvcDevice(it) }
        if (camera != null) {
            isCameraConnected = true
            connectedDevice = camera
            updateCameraSelection("external")
        } else {
            isCameraConnected = false
            connectedDevice = null
            if (activeCameraSelection == "external") {
                updateCameraSelection("internal")
            }
        }
    }

    private fun isUvcDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == 14 || (device.deviceClass == 239 && device.deviceSubclass == 2)) return true
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == 14) return true
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
        val serviceIntent = Intent(this, OliveService::class.java).apply {
            putExtra("ENGINE_MODE", mode)
        }
        startService(serviceIntent)
    }

    private fun stopBackgroundService() {
        val serviceIntent = Intent(this, OliveService::class.java)
        stopService(serviceIntent)
    }

    private fun toggleEngine() {
        if (isStopping) return // Ignore if already stopping

        if (viewModel.isEngineRunning) {
            startupTimeoutJob?.cancel()
            startupTimeoutJob = null
            isStopping = true
            currentScreen = "main"
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    viewModel.stopEngineSync()
                }
                viewModel.uvcWrapper?.release()
                viewModel.uvcWrapper = null
                stopBackgroundService()
                isStopping = false
            }
        } else {
            if (!viewModel.hasValidLocation) {
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
            val lat = viewModel.currentLat ?: 0.0
            val lon = viewModel.currentLon ?: 0.0
            if (!viewModel.hasValidLocation) {
                isStarting = false
                showLocationRequiredDialog = true
                return@launch
            }
            val timeMs = System.currentTimeMillis()
            var engineStarted = false

            startupTimeoutJob?.cancel()
            startupTimeoutJob = lifecycleScope.launch {
                delay(5000L) // Wait for USB permission & engine startup
                if (isStarting) {
                    Log.w("OliveUI", "Engine startup timed out! Cleaning up.")
                    isStarting = false
                    withContext(Dispatchers.IO) {
                        viewModel.stopEngineSync()
                    }
                }
            }

            if (activeCameraSelection == "external") {
                checkForCameras() // Ensure connectedDevice is up to date
                val device = connectedDevice
                if (device != null) {
                    if (usbMonitor == null || usbMonitor?.isRegistered != true) {
                        initUsbMonitor()
                    }
                    // USBMonitor handles both requesting permission AND initiating connection if already granted
                    usbMonitor?.requestPermission(device)
                    engineStarted = true // Pending permission or connection, keep isStarting true
                } else {
                    Log.e("OliveUI", "UVC camera selected but none connected!")
                    viewModel.stopEngineSync()
                }
            } else if (ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                if (activeCameraSelection == "demo") {
                    viewModel.updateStarSensitivity(100f)
                } else {
                    viewModel.updateStarSensitivity(50f)
                }
                val camId =
                    if (activeCameraSelection == "demo") "demo" else settingsManager.internalCameraId
                viewModel.executeStartEngine(camId, lat, lon, timeMs)
                startBackgroundService("NATIVE")
                engineStarted = true
            }

            if (!engineStarted) {
                isStarting = false
                startupTimeoutJob?.cancel()
                startupTimeoutJob = null
            }
        }
    }


    private fun initUsbMonitor() {
        val monitor = USBMonitor.getInstance(applicationContext)
        usbMonitor = monitor
        monitor.setOnDeviceConnectListener(object : USBMonitor.OnDeviceConnectListener {
            override fun onAttach(device: UsbDevice?) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        checkForCameras()
                    }
                }
            }

            override fun onDetach(device: UsbDevice?) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        checkForCameras()
                    }
                }
            }

            override fun onConnect(
                device: UsbDevice?,
                ctrlBlock: USBMonitor.UsbControlBlock?,
                createNew: Boolean
            ) {
                if (device != null && ctrlBlock != null) {
                    try {
                        val parsed = UvcFormat.parseRawDescriptors(ctrlBlock.rawDescriptors)
                        if (parsed.isNotEmpty()) {
                            viewModel.availableUvcFormats = parsed
                            val best = UvcFormat.findBestFormatForFps(
                                viewModel.settingsManager.uvcFps,
                                parsed
                            )
                            viewModel.settingsManager.uvcFps = best.fps
                            viewModel.settingsManager.uvcWidth = best.width
                            viewModel.settingsManager.uvcHeight = best.height
                            viewModel.settingsManager.uvcFormat = best.format
                        }
                    } catch (e: Exception) {
                        Log.w("OliveUI", "Failed to parse UVC raw descriptors", e)
                    }

                    viewModel.uvcWrapper?.release()
                    val wrapper = UvcCameraWrapper()
                    wrapper.initialize(
                        ctrlBlock,
                        viewModel.settingsManager.uvcWidth,
                        viewModel.settingsManager.uvcHeight,
                        viewModel.settingsManager.uvcFps.coerceAtLeast(1),
                        viewModel.settingsManager.uvcFormat
                    )
                    viewModel.uvcWrapper = wrapper

                    if (isStarting) {
                        lifecycleScope.launch {
                            val lat = viewModel.currentLat ?: 0.0
                            val lon = viewModel.currentLon ?: 0.0
                            viewModel.startUvcEngine(
                                lat,
                                lon,
                                System.currentTimeMillis()
                            )
                            startBackgroundService("UVC")
                        }
                    }
                }
            }

            override fun onDisconnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                viewModel.uvcWrapper?.release()
                viewModel.uvcWrapper = null
                if (viewModel.engineMode == MainViewModel.EngineMode.UVC) {
                    if (!isStopping) {
                        runOnUiThread { toggleEngine() }
                    }
                }
            }

            override fun onCancel(device: UsbDevice?) {
                runOnUiThread {
                    isStarting = false
                }
            }
        })
        try {
            monitor.register()
        } catch (e: Exception) {
            Log.e("OliveUI", "Failed to register USBMonitor", e)
        }
    }


}