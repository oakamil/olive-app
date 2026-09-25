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
import androidx.compose.material3.TextButton
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
import androidx.core.os.CancellationSignal
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.jiangdg.usb.USBMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import kotlin.coroutines.resume

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
    private var cachedLocation: Pair<Double, Double>? = null
    private var detectedIpAddress by mutableStateOf("127.0.0.1")
    private var showPermissionDialog by mutableStateOf(false)
    private var showBatteryOptimizationDialog by mutableStateOf(false)


    init {
        System.loadLibrary("olive_jni")
    }

    private val requestLocationAndCameraLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                executeStartEngine()
            } else {
                isStarting = false
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hideSystemUI()

        lifecycleScope.launch(Dispatchers.IO) {
            copyAssetDatabase()
            OliveEngine.initJni(filesDir.absolutePath)
        }

        lifecycleScope.launch {
            val loc = getClientLocation()
            if (loc.first != MainViewModel.INVALID_COORD) {
                cachedLocation = loc
            }
        }

        settingsManager = SettingsManager(this.applicationContext)
        activeCameraSelection = settingsManager.cameraSelection


        initUsbMonitor()
        checkForCameras()

        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED && isCameraConnected) {
            updateCameraSelection("external")
        }

        setContent {
            LaunchedEffect(viewModel.isEngineRunning) {
                updateAutoPip(viewModel.isEngineRunning)
                if (viewModel.isEngineRunning) {
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
                        AlertDialog(
                            onDismissRequest = {
                                showPermissionDialog = false
                                isStarting = false
                            },
                            title = { Text(stringResource(id = R.string.title_permissions_required)) },
                            text = { Text(stringResource(id = R.string.desc_permissions_required)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    handlePermissionRequest()
                                }) {
                                    Text(stringResource(id = R.string.btn_continue))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    showPermissionDialog = false
                                    isStarting = false
                                }) {
                                    Text(stringResource(id = R.string.btn_cancel))
                                }
                            }
                        )
                    }

                    if (showBatteryOptimizationDialog) {
                        AlertDialog(
                            onDismissRequest = {
                                showBatteryOptimizationDialog = false
                                isStarting = false
                            },
                            title = { Text(getString(R.string.title_battery_optimization)) },
                            text = { Text(getString(R.string.body_battery_optimization)) },
                            confirmButton = {
                                TextButton(onClick = {
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
                                }) {
                                    Text(getString(R.string.btn_open_settings))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    showBatteryOptimizationDialog = false
                                    getPreferences(MODE_PRIVATE).edit {
                                        putBoolean(
                                            "has_prompted_battery_opt",
                                            true
                                        )
                                    }
                                    executeStartEngine() // Skip settings and just start anyway
                                }) {
                                    Text(getString(R.string.btn_skip))
                                }
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
                                onToggleEngine = { toggleEngine() }
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

        usbMonitor?.unregister()
        usbMonitor?.destroy()
    }

    private fun updateCameraSelection(selection: String) {
        settingsManager.cameraSelection = selection
        activeCameraSelection = selection
    }

    private fun checkForCameras() {
        val deviceList = usbMonitor?.deviceList?.filterIsInstance<UsbDevice>() ?: emptyList()
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

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private suspend fun getClientLocation(): Pair<Double, Double> =
        suspendCancellableCoroutine { cont ->
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
                val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                val lastNetwork =
                    locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

                val bestLoc = if (lastGps != null && lastNetwork != null) {
                    if (lastGps.time > lastNetwork.time) lastGps else lastNetwork
                } else {
                    lastGps ?: lastNetwork
                }

                if (bestLoc != null) {
                    cont.resume(Pair(bestLoc.latitude, bestLoc.longitude))
                    return@suspendCancellableCoroutine
                }

                val executor = ContextCompat.getMainExecutor(this)
                LocationManagerCompat.getCurrentLocation(
                    locationManager,
                    LocationManager.NETWORK_PROVIDER,
                    null as CancellationSignal?,
                    executor
                ) { location: Location? ->
                    if (location != null) {
                        cont.resume(Pair(location.latitude, location.longitude))
                    } else {
                        cont.resume(Pair(MainViewModel.INVALID_COORD, MainViewModel.INVALID_COORD))
                    }
                }
            } else {
                cont.resume(Pair(MainViewModel.INVALID_COORD, MainViewModel.INVALID_COORD))
            }
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
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun stopBackgroundService() {
        val serviceIntent = Intent(this, OliveService::class.java)
        stopService(serviceIntent)
    }

    private fun toggleEngine() {
        if (isStopping) return // Ignore if already stopping

        if (viewModel.isEngineRunning) {
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
            isStarting = true
            val permissions = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ).apply {
                if (Build.VERSION.SDK_INT >= 37) {
                    add("android.permission.ACCESS_LOCAL_NETWORK")
                }
            }
            permissions.add(Manifest.permission.CAMERA)

            val denied = permissions.filter {
                ContextCompat.checkSelfPermission(
                    this,
                    it
                ) != PackageManager.PERMISSION_GRANTED
            }
            if (denied.isNotEmpty()) {
                isStarting = false
                showPermissionDialog = true
            } else {
                executeStartEngine()
            }
        }
    }

    private fun handlePermissionRequest() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).apply {
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            if (Build.VERSION.SDK_INT >= 37) {
                add("android.permission.ACCESS_LOCAL_NETWORK")
            }
        }
        permissions.add(Manifest.permission.CAMERA)
        val denied = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        val shouldShowRationale = denied.any {
            ActivityCompat.shouldShowRequestPermissionRationale(this, it)
        }
        val prefs = getPreferences(MODE_PRIVATE)
        val isFirstRun = prefs.getBoolean("first_run_permissions", true)

        if (shouldShowRationale || isFirstRun) {
            prefs.edit { putBoolean("first_run_permissions", false) }
            showPermissionDialog = false
            requestLocationAndCameraLauncher.launch(permissions.toTypedArray())
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
        val prefs = getPreferences(MODE_PRIVATE)
        val hasPrompted = prefs.getBoolean("has_prompted_battery_opt", false)
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager

        if (!hasPrompted && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
            isStarting = false
            showBatteryOptimizationDialog = true
            return
        }

        lifecycleScope.launch {
            val loc = if (cachedLocation == null || cachedLocation == Pair(
                    MainViewModel.INVALID_COORD,
                    MainViewModel.INVALID_COORD
                )
            ) {
                getClientLocation()
            } else {
                cachedLocation!!
            }
            val lat = loc.first
            val lon = loc.second
            val timeMs = System.currentTimeMillis()
            var engineStarted = false

            if (activeCameraSelection == "external") {
                checkForCameras() // Ensure connectedDevice is up to date
                val device = connectedDevice
                if (device != null) {
                    // USBMonitor handles both requesting permission AND initiating connection if already granted
                    usbMonitor?.requestPermission(device)
                    engineStarted = true // Pending permission or connection, keep isStarting true
                } else {
                    Log.e("OliveUI", "UVC camera selected but none connected!")
                    viewModel.stopEngineSync()
                }
            } else if (activeCameraSelection == "demo" || ContextCompat.checkSelfPermission(
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
            }
        }
    }


    private fun initUsbMonitor() {
        usbMonitor = USBMonitor.getInstance(this)
        usbMonitor?.setOnDeviceConnectListener(object : USBMonitor.OnDeviceConnectListener {
            override fun onAttach(device: UsbDevice?) {
                runOnUiThread { checkForCameras() }
            }

            override fun onDetach(device: UsbDevice?) {
                runOnUiThread { checkForCameras() }
            }

            override fun onConnect(
                device: UsbDevice?,
                ctrlBlock: USBMonitor.UsbControlBlock?,
                createNew: Boolean
            ) {
                if (device != null && ctrlBlock != null) {
                    viewModel.uvcWrapper?.release()
                    val wrapper = UvcCameraWrapper(this@MainActivity, device)
                    wrapper.initialize(ctrlBlock, viewModel.settingsManager.uvcFps.coerceAtLeast(1))
                    viewModel.uvcWrapper = wrapper

                    if (isStarting) {
                        lifecycleScope.launch {
                            val loc = if (cachedLocation == null || cachedLocation == Pair(
                                    MainViewModel.INVALID_COORD,
                                    MainViewModel.INVALID_COORD
                                )
                            ) {
                                getClientLocation()
                            } else {
                                cachedLocation!!
                            }
                            viewModel.startUvcEngine(
                                loc.first,
                                loc.second,
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
        usbMonitor?.register()
    }


}