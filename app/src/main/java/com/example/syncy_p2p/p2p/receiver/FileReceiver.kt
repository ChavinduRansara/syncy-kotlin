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
    }    private var serverSocket: ServerSocket? = null
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
                serverSocket = ServerSocket(Config.FILE_PORT).apply {
                    soTimeout = 0  // No timeout for accept() - wait indefinitely
                    reuseAddress = true
                }
                Log.d(TAG, "FileReceiver started on port ${Config.FILE_PORT}")

                // Continuously accept multiple file connections during sync operations
                while (isRunning && !Thread.currentThread().isInterrupted()) {
                    try {
                        Log.d(TAG, "Waiting for file transfer connection...")
                        val clientSocket = serverSocket?.accept()
                        if (clientSocket != null && isRunning) {
                            Log.d(TAG, "File transfer connection accepted from: ${clientSocket.inetAddress.hostAddress}")
                            
                            // Configure client socket for stability
                            clientSocket.soTimeout = Config.SOCKET_READ_TIMEOUT
                            clientSocket.tcpNoDelay = true
                            clientSocket.keepAlive = true
                            clientSocket.setSoLinger(true, 10)
                            
                            // Handle each file transfer in a separate coroutine to allow multiple concurrent transfers
                            launch { handleFileTransfer(clientSocket) }
                        }
                    } catch (e: IOException) {
                        if (isRunning) {
                            Log.e(TAG, "Error accepting file transfer connection", e)
                        }
                    }
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
            Log.w(TAG, "Error stopping FileReceiver", e)        }
    }

    private suspend fun handleFileTransfer(clientSocket: Socket) {
        withContext(Dispatchers.IO) {
            try {
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
    }    private suspend fun receiveFileWithProgress(
        inputStream: InputStream,
        outputStream: FileOutputStream,
        metadata: FileTransferMetadata
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val buffer = ByteArray(Config.BUFFER_SIZE)
            var bytesReceived = 0L
            var bytesRead: Int
            var consecutiveFailures = 0
            val maxConsecutiveFailures = 5

            outputStream.use { output ->
                while (bytesReceived < metadata.fileSize) {
                    try {
                        // Read with timeout protection
                        bytesRead = inputStream.read(buffer)
                        if (bytesRead == -1) {
                            // Check if we received all expected data
                            if (bytesReceived >= metadata.fileSize) {
                                Log.d(TAG, "Reached end of stream, transfer complete")
                                break
                            } else {
                                Log.w(TAG, "Unexpected end of stream at $bytesReceived/${metadata.fileSize} bytes")
                                // Allow a small tolerance for metadata discrepancies
                                if (metadata.fileSize - bytesReceived < Config.BUFFER_SIZE) {
                                    Log.d(TAG, "Small discrepancy, considering transfer complete")
                                    break
                                }
                                return@withContext false
                            }
                        }
                        
                        output.write(buffer, 0, bytesRead)
                        bytesReceived += bytesRead
                        consecutiveFailures = 0  // Reset failure counter on success

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

                        // Flush periodically to ensure data is written
                        if (bytesReceived % (Config.BUFFER_SIZE * 10) == 0L) {
                            output.flush()
                        }
                        
                    } catch (e: java.net.SocketTimeoutException) {
                        consecutiveFailures++
                        Log.w(TAG, "Socket timeout during receive (failure $consecutiveFailures/$maxConsecutiveFailures)", e)
                        
                        if (consecutiveFailures >= maxConsecutiveFailures) {
                            Log.e(TAG, "Too many consecutive timeouts, aborting transfer")
                            return@withContext false
                        }
                        
                        // Brief pause before retrying
                        delay(100)
                        
                    } catch (e: IOException) {
                        consecutiveFailures++
                        Log.w(TAG, "IO error during receive (failure $consecutiveFailures/$maxConsecutiveFailures)", e)
                        
                        if (consecutiveFailures >= maxConsecutiveFailures) {
                            Log.e(TAG, "Too many consecutive IO errors, aborting transfer")
                            return@withContext false
                        }
                        
                        // Brief pause before retrying
                        delay(200)
                    }
                }
                
                // Final flush
                output.flush()
            }

            // Verify we received the expected amount of data (with small tolerance)
            val success = bytesReceived >= metadata.fileSize || 
                         (metadata.fileSize > 0 && (metadata.fileSize - bytesReceived) < Config.BUFFER_SIZE)
            
            if (success) {
                Log.d(TAG, "File reception completed successfully: $bytesReceived/${metadata.fileSize} bytes")
            } else {
                Log.e(TAG, "File reception incomplete: $bytesReceived/${metadata.fileSize} bytes")
            }
            
            success
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error during file reception", e)
            false
        }
    }
}
