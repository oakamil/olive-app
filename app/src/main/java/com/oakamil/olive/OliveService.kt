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
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import android.net.wifi.WifiManager
import java.net.InetAddress
import java.net.InetSocketAddress

class OliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val CHANNEL_ID = "OliveServiceChannel"

    override fun onCreate() {
        super.onCreate()

        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("OliveAlpacaMulticastLock")
        multicastLock?.setReferenceCounted(true)
        multicastLock?.acquire()

        createNotificationChannel()
        startServers()
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
        multicastLock?.release()
        releaseWakeLock()
        stopServers()
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

    private var lx200Thread: Thread? = null
    private var lx200Fd: java.io.FileDescriptor? = null
    private var lastLx200DisconnectTime: Long = 0

    private var alpacaThread: Thread? = null
    private var alpacaThreadV6: Thread? = null
    private var alpacaFd: java.io.FileDescriptor? = null
    private var alpacaFdV6: java.io.FileDescriptor? = null

    private fun startServers() {
        lx200Thread = Thread {
            try {
                lx200Fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0)
                val fd = lx200Fd!!
                Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_REUSEADDR, 1)
                Os.bind(fd, InetSocketAddress(InetAddress.getByName("0.0.0.0"), 4030))
                Os.listen(fd, 50)

                while (!Thread.currentThread().isInterrupted) {
                    try {
                        Log.d("OliveService", "LX200 waiting for accept...")
                        val clientFd = Os.accept(fd, null)
                        Log.d("OliveService", "LX200 accepted socket!")
                        
                        val now = System.currentTimeMillis()
                        if (now - lastLx200DisconnectTime > 5000) {
                            OliveEngine.resetTelescopeServer()
                        }
                        
                        try {
                            val pfd = android.os.ParcelFileDescriptor.dup(clientFd)
                            val inputStream = android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
                            val outputStream = java.io.FileOutputStream(clientFd)
                            
                            val buffer = ByteArray(1024)
                            while (!Thread.currentThread().isInterrupted) {
                                val bytesRead = inputStream.read(buffer)
                                if (bytesRead == -1) break
                                if (bytesRead > 0) {
                                    val data = buffer.copyOfRange(0, bytesRead)
                                    val response = OliveEngine.processTelescopeData(data)
                                    if (response != null && response.isNotEmpty()) {
                                        outputStream.write(response)
                                        outputStream.flush()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("OliveService", "LX200 error during connection", e)
                        } finally {
                            lastLx200DisconnectTime = System.currentTimeMillis()
                            try {
                                Os.close(clientFd)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("OliveService", "LX200 error during accept", e)
                    }
                }
            } catch (e: Exception) {
                if (!Thread.currentThread().isInterrupted) {
                    Log.e("OliveService", "LX200 server FATAL error", e)
                }
            }
        }
        lx200Thread?.start()

        alpacaThread = Thread {
            try {
                alpacaFd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0)
                val fd = alpacaFd!!
                Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_REUSEADDR, 1)
                Os.bind(fd, InetSocketAddress(InetAddress.getByName("0.0.0.0"), 11111))
                Os.listen(fd, 50)

                while (!Thread.currentThread().isInterrupted) {
                    try {
                        Log.d("OliveService", "Alpaca V4 waiting for accept...")
                        val clientFd = Os.accept(fd, null)
                        Log.d("OliveService", "Alpaca V4 accepted socket!")
                        try {
                            val pfd = android.os.ParcelFileDescriptor.dup(clientFd)
                            val nativeFd = pfd.detachFd()
                            Log.d(
                                "OliveService",
                                "Alpaca V4 handing off nativeFd $nativeFd"
                            )
                            OliveEngine.handleAlpacaConnection(nativeFd)
                        } catch (e: Exception) {
                            Log.e("OliveService", "Alpaca V4 error during handoff", e)
                        } finally {
                            try {
                                Os.close(clientFd)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("OliveService", "Alpaca V4 error during accept", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("OliveService", "Alpaca V4 server FATAL error", e)
            }
        }
        alpacaThread?.start()

        alpacaThreadV6 = Thread {
            try {
                alpacaFdV6 = Os.socket(OsConstants.AF_INET6, OsConstants.SOCK_STREAM, 0)
                val fd = alpacaFdV6!!
                Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_REUSEADDR, 1)
                try {
                    Os.setsockoptInt(fd, OsConstants.IPPROTO_IPV6, OsConstants.IPV6_V6ONLY, 1)
                } catch (e: Exception) {
                    Log.e("OliveService", "Could not set IPV6_V6ONLY", e)
                }
                Os.bind(fd, InetSocketAddress(InetAddress.getByName("::"), 11111))
                Os.listen(fd, 50)

                while (!Thread.currentThread().isInterrupted) {
                    try {
                        Log.d("OliveService", "Alpaca V6 waiting for accept...")
                        val clientFd = Os.accept(fd, null)
                        Log.d("OliveService", "Alpaca V6 accepted socket!")
                        try {
                            val pfd = android.os.ParcelFileDescriptor.dup(clientFd)
                            val nativeFd = pfd.detachFd()
                            Log.d(
                                "OliveService",
                                "Alpaca V6 handing off nativeFd $nativeFd"
                            )
                            OliveEngine.handleAlpacaConnection(nativeFd)
                        } catch (e: Exception) {
                            Log.e("OliveService", "Alpaca V6 error during handoff", e)
                        } finally {
                            try {
                                Os.close(clientFd)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("OliveService", "Alpaca V6 error during accept", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("OliveService", "Alpaca V6 server FATAL error", e)
            }
        }
        alpacaThreadV6?.start()
        // UDP Discovery is now handled directly by Rust to avoid startup race conditions.
    }

    private fun stopServers() {
        lx200Thread?.interrupt()
        alpacaThread?.interrupt()
        alpacaThreadV6?.interrupt()
        try {
            lx200Fd?.let {
                try { Os.shutdown(it, OsConstants.SHUT_RDWR) } catch (_: Exception) {}
                Os.close(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            alpacaFd?.let {
                try { Os.shutdown(it, OsConstants.SHUT_RDWR) } catch (_: Exception) {}
                Os.close(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            alpacaFdV6?.let {
                try { Os.shutdown(it, OsConstants.SHUT_RDWR) } catch (_: Exception) {}
                Os.close(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        lx200Thread = null
        alpacaThread = null
        alpacaThreadV6 = null
        lx200Fd = null
        alpacaFd = null
        alpacaFdV6 = null
    }
}
