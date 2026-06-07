package com.oakamil.olive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.system.Os
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import java.net.InetAddress
import java.net.InetSocketAddress

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
            startForeground(1, notification, type)
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
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "Olive Engine Service Channel",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(serviceChannel)
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
    private var serverFd: java.io.FileDescriptor? = null

    private fun startTelescopeServer() {
        serverThread = Thread {
            try {
                serverFd = Os.socket(OsConstants.AF_INET6, OsConstants.SOCK_STREAM, 0)
                val fd = serverFd ?: return@Thread
                Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_REUSEADDR, 1)
                Os.bind(fd, InetSocketAddress(InetAddress.getByName("::"), 4030))
                Os.listen(fd, 50)

                while (!Thread.currentThread().isInterrupted) {
                    val clientFd = Os.accept(fd, null)
                    try {
                        val pfd = ParcelFileDescriptor.dup(clientFd)
                        val nativeFd = pfd.detachFd()
                        OliveEngine.handleTelescopeConnection(nativeFd)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        try {
                            Os.close(clientFd)
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
            serverFd?.let { Os.close(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        serverThread = null
        serverFd = null
    }
}
