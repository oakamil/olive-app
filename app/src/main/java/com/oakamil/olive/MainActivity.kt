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
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

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

    var guidanceMode: String
        get() = prefs.getString("guidance_mode", "equatorial") ?: "equatorial"
        set(value) = prefs.edit().putString("guidance_mode", value).apply()

    var imuIntegration: Boolean
        get() = prefs.getBoolean("imu_integration", false)
        set(value) = prefs.edit().putBoolean("imu_integration", value).apply()

    var uvcFps: Int
        get() = prefs.getInt("uvc_fps", 20)
        set(value) = prefs.edit().putInt("uvc_fps", value).apply()

    var extractor: String
        get() = prefs.getString("extractor", "Olive") ?: "Olive"
        set(value) = prefs.edit().putString("extractor", value).apply()

    var internalCameraId: String
        get() = prefs.getString("internal_camera_id", "auto") ?: "auto"
        set(value) = prefs.edit().putString("internal_camera_id", value).apply()}

class MainActivity : ComponentActivity() {

    private lateinit var usbManager: UsbManager
    private val ACTION_USB_PERMISSION = "com.oakamil.olive.USB_PERMISSION"
    private lateinit var settingsManager: SettingsManager

    // State variables for Jetpack Compose
    private var isCameraConnected by mutableStateOf(false)
    private enum class EngineMode { STOPPED, UVC, NATIVE }
    private var engineMode by mutableStateOf(EngineMode.STOPPED)
    private var isStopping by mutableStateOf(false)
    private var uvcConnection: UsbDeviceConnection? = null
    private val isEngineRunning: Boolean
        get() = engineMode != EngineMode.STOPPED
    private var connectedDevice: UsbDevice? = null
    private var engineStats by mutableStateOf(EngineStats())

    private var currentScreen by mutableStateOf("main")
    private var isInPipMode by mutableStateOf(false)

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
        val solveRateHz: Double = 0.0,
        val offsetRa: Double? = null,
        val offsetDec: Double? = null,
        val offsetAz: Double? = null,
        val offsetAlt: Double? = null,
        val offsetAngle: Double? = null
    )

    // Rust JNI functions
    private external fun initJni(filesDir: String)
    private external fun startEngine(fd: Int, imuEnabled: Boolean, uvcFps: Int, extractor: String, lat: Double, lon: Double, timeMs: Long)
    private external fun startNativeCameraEngine(imuEnabled: Boolean, extractor: String, internalCameraId: String, lat: Double, lon: Double, timeMs: Long)
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
    private external fun getAvailableInternalCameras(): String
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
                solveRateHz = obj.optDouble("solve_rate_hz", 0.0),
                offsetRa = if (obj.isNull("offset_ra")) null else obj.optDouble("offset_ra"),
                offsetDec = if (obj.isNull("offset_dec")) null else obj.optDouble("offset_dec"),
                offsetAz = if (obj.isNull("offset_az")) null else obj.optDouble("offset_az"),
                offsetAlt = if (obj.isNull("offset_alt")) null else obj.optDouble("offset_alt"),
                offsetAngle = if (obj.isNull("offset_angle")) null else obj.optDouble("offset_angle")
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

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        latestFrame = bitmap.asImageBitmap()
    }

    init {
        System.loadLibrary("olive_jni")
    }

    private val requestLocationAndCameraLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            executeStartEngine()
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
                    if (isCameraConnected && engineMode == EngineMode.NATIVE) {
                        settingsManager.cameraSelection = "external"
                        stopEngine()
                        engineMode = EngineMode.STOPPED
                        executeStartEngine()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    checkForCameras()
                    if (!isCameraConnected && engineMode == EngineMode.UVC) {
                        stopEngine()
                        uvcConnection?.close()
                        uvcConnection = null
                        engineMode = EngineMode.STOPPED
                        executeStartEngine()
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
                            kotlinx.coroutines.delay(200) // Slightly increased for smoother UI updates
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
                    if (isInPipMode) {
                        GuidanceDisplay(engineStats, settingsManager.guidanceMode == "alt_az")
                    } else {
                        when (currentScreen) {
                            "main" -> MainScreen()
                            "settings" -> SettingsScreen()
                            "tuner" -> TunerScreen()
                        }
                    }
                }
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
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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

    private fun getClientLocation(): Pair<Double, Double> {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (location != null) return Pair(location.latitude, location.longitude)
        }
        return Pair(200.0, 200.0) // 200.0 acts as invalid fallback
    }

    private fun toggleEngine() {
        if (isStopping) return // Ignore if already stopping

        if (isEngineRunning) {
            isStopping = true
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    stopEngine()
                }
                uvcConnection?.close()
                uvcConnection = null
                engineMode = EngineMode.STOPPED
                isStopping = false
                engineStats = EngineStats() // Reset stats
                latestFrame = null // Clear preview
            }
        } else {
            val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (settingsManager.cameraSelection == "internal") {
                permissions.add(Manifest.permission.CAMERA)
            }

            val denied = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (denied.isNotEmpty()) {
                requestLocationAndCameraLauncher.launch(denied.toTypedArray())
            } else {
                executeStartEngine()
            }
        }
    }

    private fun executeStartEngine() {
        val (lat, lon) = getClientLocation()
        val timeMs = System.currentTimeMillis()

        if (settingsManager.cameraSelection == "external" && connectedDevice != null) {
            val device = connectedDevice!!
            if (usbManager.hasPermission(device)) {
                openDeviceAndStartEngine(device, lat, lon, timeMs)
            } else {
                val intent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE)
                usbManager.requestPermission(device, intent)
            }
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startNativeCameraEngine(settingsManager.imuIntegration, settingsManager.extractor, settingsManager.internalCameraId, lat, lon, timeMs)
            engineMode = EngineMode.NATIVE
        }
    }

    private fun openDeviceAndStartEngine(device: UsbDevice, lat: Double = 200.0, lon: Double = 200.0, timeMs: Long = 0L) {
        uvcConnection?.close()
        val connection = usbManager.openDevice(device)
        if (connection != null) {
            uvcConnection = connection
            val fd = connection.fileDescriptor
            startEngine(fd, settingsManager.imuIntegration, settingsManager.uvcFps, settingsManager.extractor, lat, lon, timeMs)
            engineMode = EngineMode.UVC
        }
    }

    @Composable
    fun MainScreen() {
        var mainScreenExposure by remember { mutableStateOf(1.0f) }

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

                if (!isEngineRunning) {
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
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (isEngineRunning) {
                Card(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        GuidanceDisplay(engineStats, settingsManager.guidanceMode == "alt_az")
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
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

            if (isEngineRunning) {
                if (settingsManager.cameraSelection == "internal") {
                    LaunchedEffect(isEngineRunning) {
                        // Wait a moment for the camera to actually start and the JNI reference to be set
                        kotlinx.coroutines.delay(1000)
                        val rawExp = getNativeCameraExposure()
                        android.util.Log.d("OliveUI", "Initial exposure from native: $rawExp")
                        mainScreenExposure = (rawExp / 1000000f).coerceIn(0.2f, 5.0f)
                    }
                    
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Text("Exposure: ${String.format("%.1f", mainScreenExposure)} s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onBackground)
                        Slider(
                            value = mainScreenExposure,
                            onValueChange = { 
                                val stepped = Math.round(it * 10.0f) / 10.0f
                                mainScreenExposure = stepped
                                setNativeCameraExposure((stepped * 1000000).toInt())
                            },
                            valueRange = 0.2f..5.0f,
                            steps = 47,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                OutlinedButton(
                    onClick = { enterPipMode() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("Picture-in-Picture Mode")
                }
            }

            Button(
                onClick = { toggleEngine() },
                modifier = Modifier.padding(16.dp),
                enabled = !isStopping,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(
                    when {
                        isStopping -> "Stopping..."
                        isEngineRunning -> "Stop Engine"
                        else -> "Start Engine"
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    @Composable
    private fun GuidanceDisplay(stats: EngineStats, isAltAz: Boolean) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val rot = if (isAltAz) stats.offsetAz else stats.offsetRa
            val tilt = if (isAltAz) stats.offsetAlt else stats.offsetDec
            val angle = stats.offsetAngle

            if (rot != null && tilt != null && angle != null) {
                val offsetStyle = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 48.sp,
                )

                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.weight(1f).padding(end = 4.dp).fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = String.format(Locale.US, "%+6.1f", tilt),
                                color = MaterialTheme.colorScheme.primary,
                                style = offsetStyle
                            )
                        }

                        Box(
                            modifier = Modifier.weight(1f).padding(start = 4.dp).fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = String.format(Locale.US, "%+6.1f", rot),
                                color = MaterialTheme.colorScheme.primary,
                                style = offsetStyle
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.weight(1.5f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.weight(1f).padding(4.dp).fillMaxHeight(),
                            contentAlignment = Alignment.TopStart
                        ) {
                            if (isAltAz) {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    Row(modifier = Modifier.weight(2f).fillMaxWidth()) {
                                        DirectionArrow(if (tilt > 0.0) 270 else 90, false)
                                    }
                                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                        Text("Alt", color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            } else {
                                Text(
                                    if (tilt > 0) "North" else "South",
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Box(
                            modifier = Modifier.weight(2f).fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (kotlin.math.abs(angle) >= 0.05) {
                                TargetArrow(angle)
                            }
                        }

                        Box(
                            modifier = Modifier.weight(1f).padding(4.dp).fillMaxHeight(),
                            contentAlignment = Alignment.TopEnd
                        ) {
                            if (isAltAz) {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    Row(modifier = Modifier.weight(2f).fillMaxWidth()) {
                                        DirectionArrow(if (rot > 0.0) 0 else 180, true)
                                    }
                                    Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                        Text(" Az", color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            } else {
                                Text(
                                    if (rot > 0) " East" else " West",
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = "Awaiting Target Guidance...",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    @Composable
    private fun DirectionArrow(rotation: Int, alignRight: Boolean) {
        Box(
            modifier = Modifier.fillMaxSize().drawWithCache {
                val radius = size.minDimension / 2.5f
                var shapeCenterX = size.minDimension / 2
                if (alignRight) {
                    shapeCenterX = size.width - shapeCenterX
                }
                val shapeCenterY = size.minDimension / 2

                val roundedPolygon = RoundedPolygon(
                    numVertices = 3,
                    radius = radius,
                    centerX = shapeCenterX,
                    centerY = shapeCenterY,
                    rounding = CornerRounding(
                        size.minDimension / 12.5f,
                    )
                )
                val roundedPolygonPath = roundedPolygon.toPath().asComposePath()

                onDrawBehind {
                    rotate(
                        degrees = rotation.toFloat(),
                        pivot = Offset(shapeCenterX, shapeCenterY)
                    ) {
                        drawPath(roundedPolygonPath, PureRed)
                    }
                }
            }
        )
    }

    @Composable
    private fun TargetArrow(rotation: Double) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Straight,
                contentDescription = "Target",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(rotationZ = -rotation.toFloat()),
                tint = PureRed
            )
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
        var uvcFps by remember { mutableStateOf(settingsManager.uvcFps) }
        var extractor by remember { mutableStateOf(settingsManager.extractor) }
        var internalCameraId by remember { mutableStateOf(settingsManager.internalCameraId) }
        var guidanceMode by remember { mutableStateOf(settingsManager.guidanceMode) }

        LaunchedEffect(cameraSelection) {
            settingsManager.cameraSelection = cameraSelection
        }
        LaunchedEffect(imuIntegration) {
            settingsManager.imuIntegration = imuIntegration
        }
        LaunchedEffect(uvcFps) {
            settingsManager.uvcFps = uvcFps
        }
        LaunchedEffect(extractor) {
            settingsManager.extractor = extractor
        }
        LaunchedEffect(internalCameraId) {
            settingsManager.internalCameraId = internalCameraId
        }
        LaunchedEffect(guidanceMode) {
            settingsManager.guidanceMode = guidanceMode
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

            if (cameraSelection == "internal") {
                val camerasJson = getAvailableInternalCameras()
                val camerasList = mutableListOf<Pair<String, String>>()
                camerasList.add(Pair("auto", "Auto-Select"))
                try {
                    val jsonArray = org.json.JSONArray(camerasJson)
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val id = obj.getString("id")
                        val fov = obj.getDouble("fov")
                        camerasList.add(Pair(id, "Camera $id (${String.format("%.1f", fov)}° FOV)"))
                    }
                } catch (e: Exception) {
                    // Ignore parse errors
                }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 40.dp)) {
                    Text("Camera: ", color = MaterialTheme.colorScheme.onBackground)
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { expanded = true }) {
                            val currentText = camerasList.find { it.first == internalCameraId }?.second ?: "Auto-Select"
                            Text(currentText, color = MaterialTheme.colorScheme.primary)
                        }
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                            modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                        ) {
                            camerasList.forEach { (id, label) ->
                                DropdownMenuItem(
                                    text = { Text(label, color = MaterialTheme.colorScheme.onSurface) },
                                    onClick = { 
                                        internalCameraId = id
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
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

            if (cameraSelection == "external") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 40.dp)) {
                    Text("FPS: ", color = MaterialTheme.colorScheme.onBackground)
                    var expandedFps by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { expandedFps = true }) {
                            Text("$uvcFps", color = MaterialTheme.colorScheme.primary)
                        }
                        DropdownMenu(
                            expanded = expandedFps,
                            onDismissRequest = { expandedFps = false },
                            modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                        ) {
                            listOf(5, 10, 15, 20, 30).forEach { fps ->
                                DropdownMenuItem(
                                    text = { Text("$fps", color = MaterialTheme.colorScheme.onSurface) },
                                    onClick = {
                                        uvcFps = fps
                                        expandedFps = false
                                    }
                                )
                            }
                        }
                    }
                }
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

            Text("Extractor", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                listOf("Olive", "Cedar").forEach { ext ->
                    RadioButton(
                        selected = extractor == ext,
                        onClick = { extractor = ext },
                        colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary, unselectedColor = MaterialTheme.colorScheme.onBackground)
                    )
                    Text(ext, modifier = Modifier.padding(start = 8.dp, end = 16.dp), color = MaterialTheme.colorScheme.onBackground)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text("Guidance Mode", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                listOf("equatorial" to "Equatorial", "alt_az" to "Alt-Az").forEach { (value, label) ->
                    RadioButton(
                        selected = guidanceMode == value,
                        onClick = { guidanceMode = value },
                        colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary, unselectedColor = MaterialTheme.colorScheme.onBackground)
                    )
                    Text(label, modifier = Modifier.padding(start = 8.dp, end = 16.dp), color = MaterialTheme.colorScheme.onBackground)
                }
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