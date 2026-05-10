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
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
    private val isEngineRunning: Boolean
        get() = engineMode != EngineMode.STOPPED
    private var connectedDevice: UsbDevice? = null

    private var currentScreen by mutableStateOf("main")

    // Rust JNI functions
    private external fun initJni(filesDir: String)
    private external fun startEngine(fd: Int, imuEnabled: Boolean)
    private external fun startNativeCameraEngine(imuEnabled: Boolean)
    private external fun stopEngine()

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
                        engineMode = EngineMode.STOPPED
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
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
            NightVisionTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when (currentScreen) {
                        "main" -> MainScreen()
                        "settings" -> SettingsScreen()
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
        val connection = usbManager.openDevice(device)
        if (connection != null) {
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
                horizontalArrangement = Arrangement.End
            ) {
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
            
            Spacer(modifier = Modifier.weight(1f))

            val currentModeStr = if (settingsManager.cameraSelection == "external" && isCameraConnected) "UVC Camera Selected" else "Internal Camera Selected"
            
            Text(
                text = currentModeStr,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 16.dp),
                color = MaterialTheme.colorScheme.onBackground
            )
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
            
            Spacer(modifier = Modifier.weight(1f))
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
