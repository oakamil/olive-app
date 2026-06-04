package com.oakamil.olive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class OliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val CHANNEL_ID = "OliveServiceChannel"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startTelescopeServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra("ENGINE_MODE") ?: "NATIVE"

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Olive Astro Engine")
            .setContentText("Engine is running in background ($mode)")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val type = if (mode == "UVC") {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, type)
            } else {
                startForeground(1, notification)
            }
        } else {
            startForeground(1, notification)
        }

        acquireWakeLock()

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        stopTelescopeServer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Olive Engine Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "OliveAstro::EngineWakeLock"
            )
            wakeLock?.acquire(4 * 60 * 60 * 1000L) // 4 hours timeout
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private var serverThread: Thread? = null
    private var serverSocket: java.net.ServerSocket? = null

    private fun startTelescopeServer() {
        serverThread = Thread {
            try {
                serverSocket = java.net.ServerSocket(4030)
                while (!Thread.currentThread().isInterrupted) {
                    val client = serverSocket?.accept() ?: break
                    try {
                        val pfd = android.os.ParcelFileDescriptor.fromSocket(client)
                        val fd = pfd.detachFd()
                        OliveEngine.handleTelescopeConnection(fd)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        try {
                            client.close()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            } catch (e: Exception) {
                if (!Thread.currentThread().isInterrupted) {
                    e.printStackTrace()
                }
            }
        }
        serverThread?.start()
    }

    private fun stopTelescopeServer() {
        serverThread?.interrupt()
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        serverThread = null
        serverSocket = null
    }
}
