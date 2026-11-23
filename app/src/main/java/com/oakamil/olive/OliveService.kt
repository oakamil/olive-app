// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import android.Manifest
import android.R.drawable
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.io.FileDescriptor
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Keeps the plate-solving engine and telescope protocol listeners alive while the app is running.
 *
 * Lifecycle:
 * - Promotes to a `FOREGROUND_SERVICE_TYPE_CAMERA` foreground service for `NATIVE`/`UVC` modes
 *   (requires `CAMERA` permission); `DEMO` mode runs without foreground promotion.
 * - Holds a partial wake lock and (when Wi-Fi is available) a multicast lock for Alpaca UDP discovery.
 * - Accepts TCP connections for LX200 (port 4030) and ASCOM Alpaca (port 11111, IPv4 & IPv6) via
 *   `android.system.Os` sockets so accepted Alpaca fds can be detached and handed off to Rust.
 */
class OliveService : Service() {

    companion object {
        const val EXTRA_ENGINE_MODE = "ENGINE_MODE"
        const val EXTRA_PROMOTE_FOREGROUND = "PROMOTE_FOREGROUND"
        const val ACTION_ENGINE_TIMED_OUT = "com.oakamil.olive.ACTION_ENGINE_TIMED_OUT"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val channelId = "OliveServiceChannel"

    override fun onCreate() {
        super.onCreate()

        if (packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)) {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
            if (wifiManager != null) {
                try {
                    multicastLock =
                        wifiManager.createMulticastLock("OliveAlpacaMulticastLock").apply {
                            setReferenceCounted(true)
                            acquire()
                        }
                } catch (e: Exception) {
                    Log.w("OliveService", "Unable to acquire Wi-Fi multicast lock", e)
                    multicastLock = null
                }
            }
        }

        createNotificationChannel()
        startServers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra(EXTRA_ENGINE_MODE) ?: "NATIVE"
        val promoteForeground =
            intent?.getBooleanExtra(EXTRA_PROMOTE_FOREGROUND, mode != "DEMO") ?: (mode != "DEMO")
        val hasCameraPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (promoteForeground) {
            if (!hasCameraPermission) {
                Log.w(
                    "OliveService",
                    "Missing CAMERA permission for foreground service promotion (mode=$mode); stopping service."
                )
                stopSelf(startId)
                return START_NOT_STICKY
            }

            val notification: Notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle(getString(R.string.notif_engine_title))
                .setContentText(getString(R.string.notif_engine_text))
                .setSmallIcon(drawable.ic_menu_camera)
                .build()

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
                } else {
                    startForeground(1, notification)
                }
            } catch (e: Exception) {
                Log.e("OliveService", "Failed to start foreground service (mode=$mode)", e)
                stopSelf(startId)
                return START_NOT_STICKY
            }
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }

        acquireWakeLock()

        return START_NOT_STICKY
    }

    // Android 15+ foreground-service timeout callback: notify UI, stop native engine, release locks.
    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        Log.w(
            "OliveService",
            "Foreground service onTimeout received (fgsType=$fgsType, startId=$startId). Stopping engine and service gracefully."
        )
        sendBroadcast(Intent(ACTION_ENGINE_TIMED_OUT).setPackage(packageName))
        Thread {
            try {
                OliveEngine.stopEngine()
            } catch (e: Exception) {
                Log.w("OliveService", "Error stopping native engine on FGS timeout", e)
            }
        }.start()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        multicastLock?.let { lock ->
            try {
                if (lock.isHeld) {
                    lock.release()
                }
            } catch (e: Exception) {
                Log.w("OliveService", "Error releasing Wi-Fi multicast lock", e)
            }
        }
        multicastLock = null
        releaseWakeLock()
        stopServers()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            channelId,
            getString(R.string.notif_channel_name),
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
    private var lx200Fd: FileDescriptor? = null
    private var lastLx200DisconnectTime: Long = 0

    private var alpacaThread: Thread? = null
    private var alpacaThreadV6: Thread? = null
    private var alpacaFd: FileDescriptor? = null
    private var alpacaFdV6: FileDescriptor? = null

    // Starts LX200 (TCP 4030, byte-forwarded via JNI) and Alpaca (TCP 11111, fd-handed off to Rust).
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
                        val clientFd = Os.accept(fd, null)

                        val now = System.currentTimeMillis()
                        if (now - lastLx200DisconnectTime > 5000) {
                            OliveEngine.resetTelescopeServer()
                        }
                        var inputStream: ParcelFileDescriptor.AutoCloseInputStream? = null
                        var outputStream: ParcelFileDescriptor.AutoCloseOutputStream? = null
                        var readPfd: ParcelFileDescriptor? = null
                        var writePfd: ParcelFileDescriptor? = null
                        var ownsClientFd = true
                        try {
                            readPfd = ParcelFileDescriptor.dup(clientFd)
                            inputStream = ParcelFileDescriptor.AutoCloseInputStream(readPfd)
                            readPfd = null

                            writePfd = ParcelFileDescriptor.dup(clientFd)
                            outputStream = ParcelFileDescriptor.AutoCloseOutputStream(writePfd)
                            writePfd = null

                            try {
                                Os.close(clientFd)
                            } finally {
                                ownsClientFd = false
                            }

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
                            closeQuietly(inputStream)
                            closeQuietly(outputStream)
                            closeQuietly(readPfd)
                            closeQuietly(writePfd)
                            if (ownsClientFd) {
                                try {
                                    Os.close(clientFd)
                                } catch (_: Exception) {
                                }
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

        alpacaThread = startAlpacaServer(
            label = "Alpaca V4",
            isIpv6 = false,
            assignFd = { alpacaFd = it },
            onAcceptFd = OliveEngine::handleAlpacaConnection
        )
        alpacaThreadV6 = startAlpacaServer(
            label = "Alpaca V6",
            isIpv6 = true,
            assignFd = { alpacaFdV6 = it },
            onAcceptFd = OliveEngine::handleAlpacaConnectionV6
        )
        // UDP Discovery runs in the native Rust engine.
    }

    private fun startAlpacaServer(
        label: String,
        isIpv6: Boolean,
        assignFd: (FileDescriptor) -> Unit,
        onAcceptFd: (Int) -> Unit
    ): Thread {
        val thread = Thread {
            try {
                val domain = if (isIpv6) OsConstants.AF_INET6 else OsConstants.AF_INET
                val fd = Os.socket(domain, OsConstants.SOCK_STREAM, 0)
                assignFd(fd)
                Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_REUSEADDR, 1)
                if (isIpv6) {
                    try {
                        Os.setsockoptInt(fd, OsConstants.IPPROTO_IPV6, OsConstants.IPV6_V6ONLY, 1)
                    } catch (e: Exception) {
                        Log.e("OliveService", "Could not set IPV6_V6ONLY", e)
                    }
                }
                val bindAddr = InetAddress.getByName(if (isIpv6) "::" else "0.0.0.0")
                Os.bind(fd, InetSocketAddress(bindAddr, 11111))
                Os.listen(fd, 50)

                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val clientFd = Os.accept(fd, null)
                        var pfd: ParcelFileDescriptor? = null
                        try {
                            // Duplicate and detach so Rust takes sole ownership of the socket fd.
                            pfd = ParcelFileDescriptor.dup(clientFd)
                            val nativeFd = pfd.detachFd()
                            pfd = null
                            Log.d("OliveService", "$label accepted socket (fd=$nativeFd)")
                            onAcceptFd(nativeFd)
                        } catch (e: Exception) {
                            Log.e("OliveService", "$label error during handoff", e)
                        } finally {
                            closeQuietly(pfd)
                            try {
                                Os.close(clientFd)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("OliveService", "$label error during accept", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("OliveService", "$label server FATAL error", e)
            }
        }
        thread.start()
        return thread
    }

    private fun closeQuietly(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {
        }
    }

    private fun stopServers() {
        lx200Thread?.interrupt()
        alpacaThread?.interrupt()
        alpacaThreadV6?.interrupt()
        try {
            lx200Fd?.let {
                try {
                    Os.shutdown(it, OsConstants.SHUT_RDWR)
                } catch (_: Exception) {
                }
                Os.close(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            alpacaFd?.let {
                try {
                    Os.shutdown(it, OsConstants.SHUT_RDWR)
                } catch (_: Exception) {
                }
                Os.close(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            alpacaFdV6?.let {
                try {
                    Os.shutdown(it, OsConstants.SHUT_RDWR)
                } catch (_: Exception) {
                }
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
