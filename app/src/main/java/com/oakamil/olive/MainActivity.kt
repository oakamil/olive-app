package com.oakamil.olive

import android.Manifest
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDeviceConnection

import android.os.Build
import android.os.Bundle
import android.location.LocationManager
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.viewModels
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import androidx.core.content.edit

class MainActivity : ComponentActivity() {

    private lateinit var usbManager: UsbManager
    private val ACTION_USB_PERMISSION = "com.oakamil.olive.USB_PERMISSION"
    private lateinit var settingsManager: SettingsManager

    private var isCameraConnected by mutableStateOf(false)
    private var activeCameraSelection by mutableStateOf("internal")
    private var isStopping by mutableStateOf(false)
    private var isStarting by mutableStateOf(false)
    private var uvcConnection: UsbDeviceConnection? = null
    private var connectedDevice: UsbDevice? = null

    private var currentScreen by mutableStateOf("main")
    private var isInPipMode by mutableStateOf(false)

    private val viewModel: MainViewModel by viewModels()
    private var cachedLocation: Pair<Double, Double>? = null
    private var detectedIpAddress by mutableStateOf("127.0.0.1")
    private var showPermissionDialog by mutableStateOf(false)


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

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device: UsbDevice? =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(
                                    UsbManager.EXTRA_DEVICE,
                                    UsbDevice::class.java
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                            }

                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.apply {
                                startUvcCameraEngine(this)
                            }
                        } else {
                            viewModel.stopEngineSync()
                            isStarting = false
                        }
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    checkForCameras()
                    if (isCameraConnected && !viewModel.isEngineRunning) {
                        updateCameraSelection("external")
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                UsbManager.EXTRA_DEVICE,
                                UsbDevice::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                    android.util.Log.d("OliveUI", "USB Device Detached: ${device?.deviceName}")

                    val wasActiveDevice =
                        viewModel.engineMode == MainViewModel.EngineMode.UVC && (device == null || connectedDevice == null || device.deviceName == connectedDevice?.deviceName)

                    checkForCameras()
                    if (wasActiveDevice) {
                        android.util.Log.w(
                            "OliveUI",
                            "Active UVC camera detached! Stopping engine."
                        )
                        if (!isStopping) {
                            toggleEngine()
                        }
                    }
                }
            }
        }
    }

    private fun copyAssetDatabase() {
        val assetName = "default_database.npz"
        val outFile = java.io.File(filesDir, assetName)
        if (!outFile.exists()) {
            try {
                assets.open(assetName).use { inputStream ->
                    java.io.FileOutputStream(outFile).use { outputStream ->
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
        usbManager = getSystemService(USB_SERVICE) as UsbManager

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        ContextCompat.registerReceiver(
            this,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

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
                            title = { Text("Permissions Required") },
                            text = { Text("Olive Finder needs Location and Camera permissions to operate the telescope engine.") },
                            confirmButton = {
                                TextButton(onClick = {
                                    handlePermissionRequest()
                                }) {
                                    Text("Continue")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    showPermissionDialog = false
                                    isStarting = false
                                }) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }

                    if (isInPipMode) {
                        GuidanceDisplay(
                            viewModel.engineStats,
                            settingsManager.guidanceMode == "alt_az"
                        )
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
                                onBack = { currentScreen = "main" }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Re-dispatch USB intents to our receiver if the OS brings us to front
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            usbReceiver.onReceive(this, intent)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val builder = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setAutoEnterEnabled(true)
            }
            enterPictureInPictureMode(builder.build())
        }
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
            android.util.Log.d(
                "OliveUI",
                "App resumed, engine running mode: ${viewModel.engineMode}"
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        if (viewModel.isEngineRunning) {
            CoroutineScope(Dispatchers.IO).launch {
                viewModel.stopEngineSync()
                uvcConnection?.close()
                uvcConnection = null
            }
            stopBackgroundService()
        } else {
            uvcConnection?.close()
            uvcConnection = null
        }
    }

    private fun updateCameraSelection(selection: String) {
        settingsManager.cameraSelection = selection
        activeCameraSelection = selection
    }

    private fun checkForCameras() {
        val deviceList: HashMap<String, UsbDevice> = usbManager.deviceList
        val camera = deviceList.values.firstOrNull { isUvcDevice(it) }
        if (camera != null) {
            isCameraConnected = true
            connectedDevice = camera
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
    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun getClientLocation(): Pair<Double, Double> =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
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
                androidx.core.location.LocationManagerCompat.getCurrentLocation(
                    locationManager,
                    LocationManager.NETWORK_PROVIDER,
                    null as androidx.core.os.CancellationSignal?,
                    executor
                ) { location: android.location.Location? ->
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
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            for (intf in java.util.Collections.list(interfaces)) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                if (name.contains("rmnet") || name.contains("ccmni") || name.contains("tun")) continue

                val addrs = intf.inetAddresses
                for (addr in java.util.Collections.list(addrs)) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
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
                uvcConnection?.close()
                uvcConnection = null
                stopBackgroundService()
                isStopping = false
            }
        } else {
            isStarting = true
            val permissions = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (activeCameraSelection == "internal") {
                permissions.add(Manifest.permission.CAMERA)
            }

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
        )
        if (activeCameraSelection == "internal") {
            permissions.add(Manifest.permission.CAMERA)
        }
        val denied = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        val shouldShowRationale = denied.any {
            androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(this, it)
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
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                }
            startActivity(intent)
        }
    }

    private fun executeStartEngine() {
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
                    if (usbManager.hasPermission(device)) {
                        startUvcCameraEngine(device, lat, lon, timeMs)
                        engineStarted = true
                    } else {
                        val intent = PendingIntent.getBroadcast(
                            this@MainActivity,
                            0,
                            Intent(ACTION_USB_PERMISSION).apply { setPackage(packageName) },
                            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                        usbManager.requestPermission(device, intent)
                        engineStarted = true // Pending permission, don't turn off isStarting yet
                    }
                } else {
                    android.util.Log.e("OliveUI", "UVC camera selected but none connected!")
                    viewModel.stopEngineSync()
                }
            } else if (ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                viewModel.executeStartEngine(lat, lon, timeMs)
                startBackgroundService("NATIVE")
                engineStarted = true
            }

            if (!engineStarted) {
                isStarting = false
            }
        }
    }

    private fun startUvcCameraEngine(
        device: UsbDevice,
        lat: Double = MainViewModel.INVALID_COORD,
        lon: Double = MainViewModel.INVALID_COORD,
        timeMs: Long = 0L
    ) {
        lifecycleScope.launch {
            val connection = withContext(Dispatchers.IO) {
                if (viewModel.isEngineRunning) {
                    viewModel.stopEngineSync()
                }
                uvcConnection?.close()
                val conn = usbManager.openDevice(device)
                if (conn != null) {
                    for (i in 0 until device.interfaceCount) {
                        conn.claimInterface(device.getInterface(i), true)
                    }
                    uvcConnection = conn
                    val fd = conn.fileDescriptor
                    viewModel.startUvcEngine(fd, lat, lon, timeMs)
                }
                conn
            }
            if (connection != null) {
                startBackgroundService("UVC")
            }
        }
    }
}