package com.example.syncy_p2p.p2p.receiver

import android.util.Log
import com.example.syncy_p2p.p2p.core.Config
import com.example.syncy_p2p.p2p.core.Utils
import kotlinx.coroutines.*
import java.io.IOException
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.Charset

class MessageReceiver(
    private val onMessageReceived: (message: String, senderAddress: String) -> Unit
) {
    companion object {
        private const val TAG = "SyncyP2P"
    }

    private var serverSocket: ServerSocket? = null
    private var receiverJob: Job? = null
    var isRunning = false
        private set

    fun start() {
        if (isRunning) {
            Log.w(TAG, "MessageReceiver already running")
            return
        }

        receiverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                isRunning = true
                serverSocket = ServerSocket(Config.DEFAULT_PORT)
                Log.d(TAG, "MessageReceiver started on port ${Config.DEFAULT_PORT}")

                while (isRunning && !Thread.currentThread().isInterrupted()) {
                    try {
                        val clientSocket = serverSocket?.accept()
                        if (clientSocket != null && isRunning) {
                            launch { handleClient(clientSocket) }
                        }
                    } catch (e: IOException) {
                        if (isRunning) {
                            Log.e(TAG, "Error accepting client connection", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "MessageReceiver error", e)
            } finally {
                isRunning = false
                serverSocket?.close()
                Log.d(TAG, "MessageReceiver stopped")
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
            receiverJob?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping MessageReceiver", e)
        }
    }

    private suspend fun handleClient(clientSocket: Socket) {
        withContext(Dispatchers.IO) {
            try {
                clientSocket.use { socket ->
                    val message = readMessage(socket)
                    val senderAddress = socket.inetAddress.hostAddress ?: "unknown"
                    Log.d(TAG, "Received message from $senderAddress: $message")
                    
                    withContext(Dispatchers.Main) {
                        onMessageReceived(message, senderAddress)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling client", e)
            }
        }
    }

    private fun readMessage(socket: Socket): String {
        val inputStream = socket.getInputStream()
        val reader = InputStreamReader(inputStream, Charset.forName(Utils.CHARSET))
        val buffer = CharArray(Utils.BUFFER_SIZE)
        val sb = StringBuilder()

        reader.use {
            var bytesRead: Int
            while (reader.read(buffer).also { bytesRead = it } != -1) {
                sb.append(buffer, 0, bytesRead)
                // Check if we have a complete message (you might want to implement a proper protocol)
                if (sb.contains("\n") || sb.length > 10000) {
                    break
                }
            }
        }

        return sb.toString().trim()
    }
}
