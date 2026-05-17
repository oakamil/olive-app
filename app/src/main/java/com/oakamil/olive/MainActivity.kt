package com.oakamil.olive

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDeviceConnection
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

// Night vision theme colors
val PureRed = Color(0xFFFF0000)
val PureBlack = Color(0xFF000000)

private val NightVisionColorScheme = darkColorScheme(
    primary = PureRed,
    onPrimary = PureBlack,
    background = PureBlack,
    onBackground = PureRed,
    surface = PureBlack,
    onSurface = PureRed,
    secondary = PureRed,
    onSecondary = PureBlack,
    tertiary = PureRed,
    onTertiary = PureBlack,
    surfaceVariant = PureBlack,
    onSurfaceVariant = PureRed,
    outline = PureRed
)

@Composable
fun NightVisionTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NightVisionColorScheme,
        content = content
    )
}

class SettingsManager(context: Context) {
    private val prefs = context.getSharedPreferences("olive_settings", Context.MODE_PRIVATE)

    var cameraSelection: String
        get() = prefs.getString("camera_selection", "internal") ?: "internal"
        set(value) = prefs.edit().putString("camera_selection", value).apply()

    var imuIntegration: Boolean
        get() = prefs.getBoolean("imu_integration", false)
        set(value) = prefs.edit().putBoolean("imu_integration", value).apply()
}

class MainActivity : ComponentActivity() {

    private lateinit var usbManager: UsbManager
    private val ACTION_USB_PERMISSION = "com.oakamil.olive.USB_PERMISSION"
    private lateinit var settingsManager: SettingsManager

    // State variables for Jetpack Compose
    private var isCameraConnected by mutableStateOf(false)
    private enum class EngineMode { STOPPED, UVC, NATIVE }
    private var engineMode by mutableStateOf(EngineMode.STOPPED)
    private var uvcConnection: UsbDeviceConnection? = null
    private val isEngineRunning: Boolean
        get() = engineMode != EngineMode.STOPPED
    private var connectedDevice: UsbDevice? = null
    private var engineStats by mutableStateOf(EngineStats())

    private var currentScreen by mutableStateOf("main")

    // Stats
    data class EngineStats(
        val status: String = "Idle",
        val ra: Double? = null,
        val dec: Double? = null,
        val roll: Double? = null,
        val centroidCount: Int = 0,
        val exposureMs: Double = 0.0,
        val postProcessMs: Double = 0.0,
        val extractMs: Double = 0.0,
        val solveMs: Double = 0.0,
        val solveRateHz: Double = 0.0
    )

    // Rust JNI functions
    private external fun initJni(filesDir: String)
    private external fun startEngine(fd: Int, imuEnabled: Boolean)
    private external fun startNativeCameraEngine(imuEnabled: Boolean)
    private external fun stopEngine()
    private external fun getEngineStats(): String
    private external fun setNativeCameraExposure(exposureUs: Int)
    private external fun setNativeCameraGain(gain: Float)
    private external fun setNativeCameraFocus(distance: Float)
    private external fun setNativeCameraNoiseReduction(mode: Int)
    private external fun getNativeCameraExposure(): Int
    private external fun getNativeCameraGain(): Float
    private external fun getMinNativeCameraGain(): Float
    private external fun getMaxNativeCameraGain(): Float
    private external fun getLatestFrame(): ByteArray?
    private external fun setEnginePaused(paused: Boolean)

    // Tuning state
    private var currentExposure by mutableStateOf(100000f)
    private var currentGain by mutableStateOf(100f)
    private var minAnalogGain by mutableStateOf(100f)
    private var maxAnalogGain by mutableStateOf(3200f)
    private var currentFocus by mutableStateOf(0.0f)
    private var noiseReductionMode by mutableStateOf(0) // 0=OFF, 1=FAST, 2=HQ
    private var latestFrame by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)

    private fun updateStats() {
        val json = getEngineStats()
        try {
            val obj = org.json.JSONObject(json)
            engineStats = EngineStats(
                status = obj.optString("status", "Idle"),
                ra = if (obj.isNull("ra")) null else obj.optDouble("ra"),
                dec = if (obj.isNull("dec")) null else obj.optDouble("dec"),
                roll = if (obj.isNull("roll")) null else obj.optDouble("roll"),
                centroidCount = obj.optInt("centroids", 0),
                exposureMs = obj.optDouble("exposure_us", 0.0) / 1000.0,
                postProcessMs = obj.optDouble("post_process_ms", 0.0),
                extractMs = obj.optDouble("extract_ms", 0.0),
                solveMs = obj.optDouble("solve_ms", 0.0),
                solveRateHz = obj.optDouble("solve_rate_hz", 0.0)
            )
        } catch (e: Exception) {
            // Ignore parse errors
        }
    }

    private fun fetchLatestFrame() {
        val bytes = getLatestFrame() ?: return
        if (bytes.size < 8) return

        val width = ((bytes[0].toInt() and 0xFF) or
                    ((bytes[1].toInt() and 0xFF) shl 8) or
                    ((bytes[2].toInt() and 0xFF) shl 16) or
                    ((bytes[3].toInt() and 0xFF) shl 24))
        
        val height = ((bytes[4].toInt() and 0xFF) or
                     ((bytes[5].toInt() and 0xFF) shl 8) or
                     ((bytes[6].toInt() and 0xFF) shl 16) or
                     ((bytes[7].toInt() and 0xFF) shl 24))

        val pixelCount = width * height
        if (bytes.size < pixelCount + 8) return

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(pixelCount)
        
        var sum = 0L
        for (i in 0 until pixelCount) {
            val gray = bytes[i + 8].toInt() and 0xFF
            sum += gray
            pixels[i] = android.graphics.Color.rgb(gray, gray, gray)
        }
        
        val avg = if (pixelCount > 0) sum / pixelCount else 0
        android.util.Log.d("OliveUI", "Decoded frame ${width}x${height}, avg pixel val: $avg")
        
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        latestFrame = bitmap.asImageBitmap()
    }

    init {
        System.loadLibrary("olive_jni")
    }

    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startNativeCameraEngine(settingsManager.imuIntegration)
                engineMode = EngineMode.NATIVE
            }
        }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }

                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.apply {
                                openDeviceAndStartEngine(this)
                            }
                        } else {
                            engineMode = EngineMode.STOPPED
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    checkForCameras()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    checkForCameras()
                    if (!isCameraConnected && engineMode == EngineMode.UVC) {
                        stopEngine()
                        uvcConnection?.close()
                        uvcConnection = null
                        engineMode = EngineMode.STOPPED
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
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        copyAssetDatabase()
        initJni(filesDir.absolutePath)
        
        settingsManager = SettingsManager(this)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Context.RECEIVER_NOT_EXPORTED
        } else {
            0
        }
        registerReceiver(usbReceiver, filter, flags)

        checkForCameras()

        setContent {
            LaunchedEffect(isEngineRunning, currentScreen) {
                if (isEngineRunning) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    while (true) {
                        updateStats()
                        if (currentScreen == "tuner") {
                            fetchLatestFrame()
                            kotlinx.coroutines.delay(33) // 30 FPS for tuner
                        } else {
                            kotlinx.coroutines.delay(500) // 2 FPS for main screen
                        }
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
                    when (currentScreen) {
                        "main" -> MainScreen()
                        "settings" -> SettingsScreen()
                        "tuner" -> TunerScreen()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        if (isEngineRunning) {
            stopEngine()
        }
        uvcConnection?.close()
        uvcConnection = null
    }

    private fun checkForCameras() {
        val deviceList: HashMap<String, UsbDevice> = usbManager.deviceList
        val camera = deviceList.values.firstOrNull { isUvcDevice(it) } ?: deviceList.values.firstOrNull()
        if (camera != null) {
            isCameraConnected = true
            connectedDevice = camera
        } else {
            isCameraConnected = false
            connectedDevice = null
            if (settingsManager.cameraSelection == "external") {
                settingsManager.cameraSelection = "internal"
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

    private fun toggleEngine() {
        if (isEngineRunning) {
            stopEngine()
            uvcConnection?.close()
            uvcConnection = null
            engineMode = EngineMode.STOPPED
        } else {
            val useExternal = settingsManager.cameraSelection == "external" && connectedDevice != null
            if (useExternal) {
                val device = connectedDevice!!
                if (usbManager.hasPermission(device)) {
                    openDeviceAndStartEngine(device)
                } else {
                    val permissionIntent = PendingIntent.getBroadcast(
                        this, 0, Intent(ACTION_USB_PERMISSION),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    usbManager.requestPermission(device, permissionIntent)
                }
            } else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    startNativeCameraEngine(settingsManager.imuIntegration)
                    engineMode = EngineMode.NATIVE
                } else {
                    requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        }
    }

    private fun openDeviceAndStartEngine(device: UsbDevice) {
        uvcConnection?.close()
        val connection = usbManager.openDevice(device)
        if (connection != null) {
            uvcConnection = connection
            val fd = connection.fileDescriptor
            startEngine(fd, settingsManager.imuIntegration)
            engineMode = EngineMode.UVC
        }
    }

    @Composable
    fun MainScreen() {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (isEngineRunning) {
                    Button(
                        onClick = { 
                            setEnginePaused(true)
                            minAnalogGain = getMinNativeCameraGain()
                            maxAnalogGain = getMaxNativeCameraGain().coerceAtLeast(minAnalogGain + 1f)
                            currentGain = getNativeCameraGain().coerceIn(minAnalogGain, maxAnalogGain)
                            currentExposure = getNativeCameraExposure().toFloat().coerceIn(16667f, 5000000f)
                            currentScreen = "tuner" 
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text("Camera Mode")
                    }
                } else {
                    Spacer(modifier = Modifier.width(8.dp))
                }
                
                Button(
                    onClick = { currentScreen = "settings" },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Settings")
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            if (isEngineRunning) {
                StatsDisplay(engineStats)
                Spacer(modifier = Modifier.weight(1f))
            } else {
                Spacer(modifier = Modifier.weight(1f))
                val currentModeStr = if (settingsManager.cameraSelection == "external" && isCameraConnected) "UVC Camera Selected" else "Internal Camera Selected"
                
                Text(
                    text = currentModeStr,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 16.dp),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            Button(
                onClick = { toggleEngine() },
                modifier = Modifier.padding(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(if (isEngineRunning) "Stop Engine" else "Start Engine")
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    @Composable
    fun TunerScreen() {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                Button(
                    onClick = { 
                        setEnginePaused(false)
                        currentScreen = "main" 
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Back")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Live Preview
            latestFrame?.let {
                androidx.compose.foundation.Image(
                    bitmap = it,
                    contentDescription = "Live Preview",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .padding(8.dp)
                        .background(MaterialTheme.colorScheme.surface)
                )
            } ?: Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .padding(8.dp)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                Text("Waiting for frame...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Tuning Sliders
            TuningControls()

            Spacer(modifier = Modifier.height(16.dp))

            Text("Detected Centroids: ${engineStats.centroidCount}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
    }

    @Composable
    fun TuningControls() {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text("Exposure: ${currentExposure.toInt()} us", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onBackground)
            Slider(
                value = currentExposure,
                onValueChange = { 
                    currentExposure = it
                    setNativeCameraExposure(it.toInt())
                },
                valueRange = 16667f..5000000f,
                modifier = Modifier.fillMaxWidth()
            )

            Text("Gain: ${currentGain.toInt()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onBackground)
            Slider(
                value = currentGain,
                onValueChange = { 
                    currentGain = it
                    setNativeCameraGain(it)
                },
                valueRange = minAnalogGain..maxAnalogGain.coerceAtLeast(minAnalogGain + 1f),
                modifier = Modifier.fillMaxWidth()
            )

            Text("Focus: ${String.format("%.2f", currentFocus)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onBackground)
            Slider(
                value = currentFocus,
                onValueChange = { 
                    currentFocus = it
                    setNativeCameraFocus(it)
                },
                valueRange = 0.0f..10.0f,
                modifier = Modifier.fillMaxWidth()
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Noise Reduction: ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onBackground)
                val modes = listOf("OFF", "FAST", "HQ")
                modes.forEachIndexed { index, name ->
                    RadioButton(
                        selected = noiseReductionMode == index,
                        onClick = {
                            noiseReductionMode = index
                            setNativeCameraNoiseReduction(index)
                        }
                    )
                    Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(end = 8.dp))
                }
            }
        }
    }

    @Composable
    fun StatsDisplay(stats: EngineStats) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start
        ) {
            Text("Engine Status: ${stats.status}", color = MaterialTheme.colorScheme.primary)
            Text("Detected Stars: ${stats.centroidCount}", color = MaterialTheme.colorScheme.onBackground)
            Spacer(modifier = Modifier.height(8.dp))
            Text("RA: ${stats.ra?.let { String.format("%.4f", it) } ?: "-"}", color = MaterialTheme.colorScheme.onBackground)
            Text("Dec: ${stats.dec?.let { String.format("%.4f", it) } ?: "-"}", color = MaterialTheme.colorScheme.onBackground)
            Text("Roll: ${stats.roll?.let { String.format("%.4f", it) } ?: "-"}", color = MaterialTheme.colorScheme.onBackground)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Exposure: ${String.format("%.2f", stats.exposureMs)} ms", color = MaterialTheme.colorScheme.onBackground)
            Text("Post-Process: ${String.format("%.1f", stats.postProcessMs)} ms", color = MaterialTheme.colorScheme.onBackground)
            Text("Extraction: ${String.format("%.1f", stats.extractMs)} ms", color = MaterialTheme.colorScheme.onBackground)
            Text("Solve: ${String.format("%.1f", stats.solveMs)} ms", color = MaterialTheme.colorScheme.onBackground)
            Text("Solve Rate: ${String.format("%.2f", stats.solveRateHz)} Hz", color = MaterialTheme.colorScheme.onBackground)
        }
    }

    @Composable
    fun SettingsScreen() {
        var cameraSelection by remember { mutableStateOf(settingsManager.cameraSelection) }
        var imuIntegration by remember { mutableStateOf(settingsManager.imuIntegration) }

        LaunchedEffect(cameraSelection) {
            settingsManager.cameraSelection = cameraSelection
        }
        LaunchedEffect(imuIntegration) {
            settingsManager.imuIntegration = imuIntegration
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                Button(
                    onClick = { currentScreen = "main" },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Back")
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
            
            Text("Settings", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
            
            Spacer(modifier = Modifier.height(32.dp))

            Text("Camera Selection", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { cameraSelection = "internal" }.padding(vertical = 8.dp)
            ) {
                RadioButton(
                    selected = cameraSelection == "internal",
                    onClick = { cameraSelection = "internal" },
                    colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary, unselectedColor = MaterialTheme.colorScheme.onBackground)
                )
                Text("Internal Camera", modifier = Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.onBackground)
            }
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable(enabled = isCameraConnected) {
                    if (isCameraConnected) cameraSelection = "external"
                }.padding(vertical = 8.dp)
            ) {
                RadioButton(
                    selected = cameraSelection == "external",
                    onClick = { if (isCameraConnected) cameraSelection = "external" },
                    enabled = isCameraConnected,
                    colors = RadioButtonDefaults.colors(
                        selectedColor = MaterialTheme.colorScheme.primary, 
                        unselectedColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        disabledSelectedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
                        disabledUnselectedColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f)
                    )
                )
                Text(
                    text = "External (USB/UVC) Camera", 
                    modifier = Modifier.padding(start = 8.dp),
                    color = if (isCameraConnected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f)
                )
            }
            if (!isCameraConnected) {
                Text(
                    text = "No external camera detected. Selection locked to internal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 48.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text("IMU Integration", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                Text("Enable Phone IMU", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onBackground)
                Switch(
                    checked = imuIntegration,
                    onCheckedChange = { imuIntegration = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurface,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        }
    }
}
