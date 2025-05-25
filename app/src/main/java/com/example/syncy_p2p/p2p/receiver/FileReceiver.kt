package com.example.syncy_p2p.p2p.receiver

import android.content.Context
import android.media.MediaScannerConnection
import android.util.Log
import com.example.syncy_p2p.p2p.core.Config
import com.example.syncy_p2p.p2p.core.Utils
import com.example.syncy_p2p.files.FileTransferMetadata
import com.example.syncy_p2p.files.FileTransferProgress
import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket

class FileReceiver(
    private val context: Context,
    private val destinationPath: String,
    private val onComplete: (String, FileTransferMetadata?, String) -> Unit, // Added sender address
    private val onError: (String) -> Unit,
    private val onProgress: ((FileTransferProgress) -> Unit)? = null,
    private val forceGalleryScan: Boolean = false
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
            Log.w(TAG, "FileReceiver already running")
            return
        }

        receiverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                isRunning = true
                serverSocket = ServerSocket(Config.DEFAULT_PORT)
                Log.d(TAG, "FileReceiver started on port ${Config.DEFAULT_PORT}")

                val clientSocket = serverSocket?.accept()
                if (clientSocket != null && isRunning) {
                    handleFileTransfer(clientSocket)
                }
            } catch (e: Exception) {
                Log.e(TAG, "FileReceiver error", e)
                withContext(Dispatchers.Main) {
                    onError("File receiver error: ${e.message}")
                }
            } finally {
                isRunning = false
                serverSocket?.close()
                Log.d(TAG, "FileReceiver stopped")
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
            receiverJob?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping FileReceiver", e)
        }
    }    private suspend fun handleFileTransfer(clientSocket: Socket) {
        withContext(Dispatchers.IO) {            try {
                clientSocket.use { socket ->
                    val senderAddress = socket.inetAddress.hostAddress ?: "unknown"
                    val inputStream = socket.getInputStream()
                    
                    // First, receive metadata
                    val metadata = Utils.receiveMetadata(inputStream)
                    if (metadata == null) {
                        Log.e(TAG, "Failed to receive file metadata")
                        withContext(Dispatchers.Main) {
                            onError("Failed to receive file metadata")
                        }
                        return@withContext
                    }
                    
                    Log.d(TAG, "Receiving file: ${metadata.fileName} (${metadata.fileSize} bytes)")
                    
                    // Create destination file
                    val file = File(destinationPath, metadata.fileName)
                    file.parentFile?.mkdirs()
                    
                    val outputStream = FileOutputStream(file)
                    
                    val success = receiveFileWithProgress(inputStream, outputStream, metadata)
                      if (success) {
                        Log.d(TAG, "File received successfully from $senderAddress: ${file.absolutePath}")
                        
                        if (forceGalleryScan) {
                            scanFile(file)
                        }
                        withContext(Dispatchers.Main) {
                            onComplete(file.absolutePath, metadata, senderAddress)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            onError("Failed to save file")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling file transfer", e)
                withContext(Dispatchers.Main) {
                    onError("File transfer error: ${e.message}")
                }
            }
        }
    }

    private fun scanFile(file: File) {
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            null
        ) { path, uri ->
            Log.d(TAG, "File scanned: $path")
        }
    }

    private suspend fun receiveFileWithProgress(
        inputStream: InputStream,
        outputStream: FileOutputStream,
        metadata: FileTransferMetadata
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val buffer = ByteArray(Config.BUFFER_SIZE)
            var bytesReceived = 0L
            var bytesRead: Int

            outputStream.use { output ->
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    bytesReceived += bytesRead

                    // Update progress
                    val progress = FileTransferProgress(
                        fileName = metadata.fileName,
                        bytesTransferred = bytesReceived,
                        totalBytes = metadata.fileSize
                    )

                    // Report progress to callback
                    onProgress?.let { callback ->
                        withContext(Dispatchers.Main) {
                            callback(progress)
                        }
                    }

                    // Break if we've received all expected bytes
                    if (bytesReceived >= metadata.fileSize) {
                        break
                    }
                }
                output.flush()
            }

            // Verify we received the expected amount of data
            bytesReceived == metadata.fileSize || metadata.fileSize == 0L
        } catch (e: Exception) {
            Log.e(TAG, "Error during file reception", e)
            false
        }
    }
}
