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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var usbManager: UsbManager
    private val ACTION_USB_PERMISSION = "com.oakamil.olive.USB_PERMISSION"

    // State variables for Jetpack Compose
    private var isCameraConnected by mutableStateOf(false)
    private enum class EngineMode { STOPPED, UVC, NATIVE }
    private var engineMode by mutableStateOf(EngineMode.STOPPED)
    private val isEngineRunning: Boolean
        get() = engineMode != EngineMode.STOPPED
    private var connectedDevice: UsbDevice? = null

    // Rust JNI functions
    private external fun startEngine(fd: Int)
    private external fun startNativeCameraEngine()
    private external fun stopEngine()

    init {
        System.loadLibrary("olive_jni")
    }

    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startNativeCameraEngine()
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
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
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
        // Simple heuristic: just look for first UVC device or assume any connected USB device is a camera if we are testing
        val camera = deviceList.values.firstOrNull { isUvcDevice(it) } ?: deviceList.values.firstOrNull()
        if (camera != null) {
            isCameraConnected = true
            connectedDevice = camera
        } else {
            isCameraConnected = false
            connectedDevice = null
        }
    }

    private fun isUvcDevice(device: UsbDevice): Boolean {
        // UVC is Class 14 or Class 239 with subclass 2
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
            val device = connectedDevice
            if (device != null) {
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
                    startNativeCameraEngine()
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
            startEngine(fd)
            engineMode = EngineMode.UVC
        }
    }

    @Composable
    fun MainScreen() {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isCameraConnected) "UVC Camera Connected" else "Using Android Camera",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Button(
                    onClick = { toggleEngine() },
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(if (isEngineRunning) "Stop Engine" else "Start Engine")
                }
            }
        }
    }
}
