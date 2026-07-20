package com.oakamil.olive

import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class BluetoothGrpcProxy(private val bluetoothSocket: BluetoothSocket) {
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var proxyThread: Thread? = null
    private var btToTcpThread: Thread? = null
    private var tcpToBtThread: Thread? = null

    @Volatile
    private var isRunning = false

    fun start(): Int {
        serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = serverSocket!!.localPort
        isRunning = true

        proxyThread = thread {
            try {
                while (isRunning) {
                    val socket = serverSocket!!.accept()
                    if (clientSocket != null) {
                        socket.close()
                        continue
                    }
                    clientSocket = socket

                    btToTcpThread = thread {
                        val btIn: InputStream = bluetoothSocket.inputStream
                        val tcpOut: OutputStream = socket.outputStream
                        val buffer = ByteArray(4096)
                        try {
                            while (isRunning) {
                                val bytes = btIn.read(buffer)
                                if (bytes == -1) break
                                tcpOut.write(buffer, 0, bytes)
                                tcpOut.flush()
                            }
                        } catch (e: Exception) {
                            Log.e("BluetoothGrpcProxy", "Error reading from BT", e)
                        } finally {
                            stop()
                        }
                    }

                    tcpToBtThread = thread {
                        val tcpIn: InputStream = socket.inputStream
                        val btOut: OutputStream = bluetoothSocket.outputStream
                        val buffer = ByteArray(4096)
                        try {
                            while (isRunning) {
                                val bytes = tcpIn.read(buffer)
                                if (bytes == -1) break
                                btOut.write(buffer, 0, bytes)
                                btOut.flush()
                            }
                        } catch (e: Exception) {
                            Log.e("BluetoothGrpcProxy", "Error reading from TCP", e)
                        } finally {
                            stop()
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e("BluetoothGrpcProxy", "Server socket error", e)
                }
            }
        }
        return port
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        try {
            clientSocket?.close()
        } catch (_: Exception) {
        }
        try {
            bluetoothSocket.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        clientSocket = null
        btToTcpThread?.interrupt()
        tcpToBtThread?.interrupt()
        proxyThread?.interrupt()
    }
}
