package com.example.syncy_p2p.p2p.sender

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.syncy_p2p.MainActivity
import com.example.syncy_p2p.R
import com.example.syncy_p2p.p2p.core.Config
import com.example.syncy_p2p.p2p.core.Utils
import com.example.syncy_p2p.files.FileTransferMetadata
import com.example.syncy_p2p.files.FileTransferProgress
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

class FileSender : Service() {

    companion object {
        private const val TAG = "SyncyP2P"
        private const val CHANNEL_ID = "FileSenderChannel"
        private const val NOTIFICATION_ID = 102

        const val ACTION_SEND_FILE = "com.example.syncy_p2p.SEND_FILE"
        const val EXTRAS_FILE_PATH = "file_path"
        const val EXTRAS_ADDRESS = "target_address"
        const val EXTRAS_PORT = "target_port"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification("Preparing to send file...")
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CoroutineScope(Dispatchers.IO).launch {
            handleFileIntent(intent)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private suspend fun handleFileIntent(intent: Intent?) {        if (intent?.action != ACTION_SEND_FILE) {
            Log.w(TAG, "Invalid action: ${intent?.action}")
            return
        }

        val filePath = intent.getStringExtra(EXTRAS_FILE_PATH)
        val host = intent.getStringExtra(EXTRAS_ADDRESS)
        val port = intent.getIntExtra(EXTRAS_PORT, Config.DEFAULT_PORT)

        if (filePath.isNullOrEmpty() || host.isNullOrEmpty()) {
            Log.e(TAG, "Missing file path or host address")
            return
        }

        val file = File(filePath)
        
        // Enhanced file validation
        if (!file.exists()) {
            Log.e(TAG, "File does not exist: $filePath")
            updateNotification("File not found")
            delay(3000)
            return
        }
        
        if (!file.canRead()) {
            Log.e(TAG, "Cannot read file: $filePath")
            updateNotification("File access denied")
            delay(3000)
            return
        }
        
        if (file.length() == 0L) {
            Log.e(TAG, "File is empty: $filePath")
            updateNotification("File is empty")
            delay(3000)
            return
        }

        Log.d(TAG, "Sending file ${file.name} (${file.length()} bytes) to $host:$port")
        updateNotification("Sending ${file.name}...")        // Create metadata
        val metadata = FileTransferMetadata(
            fileName = file.name,
            fileSize = file.length(),
            mimeType = Utils.getMimeType(file.name)
        )

        val maxConnectionRetries = Config.MAX_RETRIES
        var connectionSuccess = false
        
        for (connectionAttempt in 1..maxConnectionRetries) {
            val socket = Socket()
            try {                // Configure socket for better stability
                socket.soTimeout = Config.SOCKET_READ_TIMEOUT  // Use longer timeout for data transfer
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.setSoLinger(true, 10)  // Wait up to 10 seconds for clean shutdown
                
                updateNotification("Connecting to device (attempt $connectionAttempt)...")
                Log.d(TAG, "Connection attempt $connectionAttempt to $host:$port")
                
                socket.connect(InetSocketAddress(host, port), Config.SOCKET_TIMEOUT)
                
                updateNotification("Connected! Sending ${file.name}...")
                Log.d(TAG, "Successfully connected to $host:$port")
                
                val inputStream = FileInputStream(file).buffered()
                val outputStream = socket.getOutputStream().buffered()
                
                // Send metadata first
                Utils.sendMetadata(outputStream, metadata)
                
                // Send file with progress tracking and retry logic
                val success = sendFileWithProgress(inputStream, outputStream, metadata)
                
                if (success) {
                    Log.d(TAG, "File sent successfully")
                    updateNotification("File sent successfully")
                    connectionSuccess = true
                } else {
                    Log.e(TAG, "Failed to send file content")
                    updateNotification("Failed to send file content")
                }
                
                // Keep notification for a brief moment
                delay(3000)
                break // Exit retry loop on successful connection
                
            } catch (e: java.net.ConnectException) {
                Log.w(TAG, "Connection attempt $connectionAttempt failed: ${e.message}")
                updateNotification("Connection failed (attempt $connectionAttempt/$maxConnectionRetries)")
                
                if (connectionAttempt < maxConnectionRetries) {
                    val retryDelay = (1000 * connectionAttempt).toLong()
                    delay(retryDelay)
                } else {
                    Log.e(TAG, "All connection attempts failed")
                    updateNotification("Cannot connect to device")
                    delay(5000)
                }
            } catch (e: java.net.SocketTimeoutException) {
                Log.w(TAG, "Connection timeout on attempt $connectionAttempt: ${e.message}")
                updateNotification("Connection timeout (attempt $connectionAttempt/$maxConnectionRetries)")
                
                if (connectionAttempt < maxConnectionRetries) {
                    delay(2000)
                } else {
                    Log.e(TAG, "All connection attempts timed out")
                    updateNotification("Connection timeout - device unreachable")
                    delay(5000)
                }
            } catch (e: IOException) {
                Log.e(TAG, "IO error on connection attempt $connectionAttempt", e)
                updateNotification("Network error during transfer")
                
                if (connectionAttempt < maxConnectionRetries) {
                    delay(1500)
                } else {
                    updateNotification("Transfer failed: ${e.message}")
                    delay(5000)
                }
            } finally {
                try {
                    socket.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing socket on attempt $connectionAttempt", e)
                }
            }
        }
    }    private suspend fun sendFileWithProgress(
        inputStream: java.io.InputStream,
        outputStream: java.io.OutputStream,
        metadata: FileTransferMetadata
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val buffer = ByteArray(Config.BUFFER_SIZE)
            var bytesTransferred = 0L
            var bytesRead: Int
            val maxRetries = Config.MAX_RETRIES
            var consecutiveFailures = 0

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                var chunkSent = false
                var retryCount = 0
                
                // Retry mechanism for each chunk
                while (!chunkSent && retryCount < maxRetries) {
                    try {
                        // Check connection stability before writing
                        if (!isConnectionStable(outputStream)) {
                            Log.w(TAG, "Connection appears unstable, attempting to continue...")
                        }
                        
                        // Write chunk with connection reset protection
                        writeChunkWithRetry(outputStream, buffer, bytesRead)
                        
                        bytesTransferred += bytesRead
                        chunkSent = true
                        consecutiveFailures = 0
                        
                        // Update progress
                        val progress = FileTransferProgress(
                            fileName = metadata.fileName,
                            bytesTransferred = bytesTransferred,
                            totalBytes = metadata.fileSize
                        )

                        // Update notification with progress
                        val percentageText = if (progress.percentage > 0) " (${progress.percentage}%)" else ""
                        updateNotification("Sending ${metadata.fileName}$percentageText")

                        // Small delay to prevent excessive UI updates
                        if (bytesTransferred % (Config.BUFFER_SIZE * 10) == 0L) {
                            delay(10)
                        }
                          } catch (e: java.net.SocketException) {
                        retryCount++
                        consecutiveFailures++
                        Log.w(TAG, "Connection reset during chunk transfer (attempt $retryCount/$maxRetries)", e)
                          if (retryCount < maxRetries) {
                            // Exponential backoff delay using config
                            val delayMs = (Config.RETRY_DELAY_BASE * retryCount * retryCount).toLong()
                            updateNotification("Connection lost, retrying in ${delayMs}ms...")
                            delay(delayMs)
                            
                            // Attempt to reset the position for this chunk
                            try {
                                outputStream.flush()
                            } catch (ignored: Exception) {
                                // Ignore flush errors during retry
                            }
                        }
                    } catch (e: IOException) {
                        retryCount++
                        consecutiveFailures++
                        Log.w(TAG, "IO error during chunk transfer (attempt $retryCount/$maxRetries)", e)
                          if (retryCount < maxRetries) {
                            val delayMs = (Config.RETRY_DELAY_BASE * retryCount).toLong()
                            updateNotification("Network error, retrying...")
                            delay(delayMs)
                        }
                    }
                }
                
                // If chunk failed after all retries, abort transfer
                if (!chunkSent) {
                    Log.e(TAG, "Failed to send chunk after $maxRetries attempts")
                    updateNotification("Transfer failed after multiple retries")
                    return@withContext false
                }
                
                // If too many consecutive failures, abort even if individual chunks succeed
                if (consecutiveFailures > 10) {
                    Log.e(TAG, "Too many consecutive failures, aborting transfer")
                    updateNotification("Transfer aborted due to unstable connection")
                    return@withContext false
                }
            }

            // Final flush with retry
            var flushSuccess = false
            for (attempt in 1..maxRetries) {
                try {
                    outputStream.flush()
                    flushSuccess = true
                    break
                } catch (e: IOException) {
                    Log.w(TAG, "Flush attempt $attempt failed", e)
                    if (attempt < maxRetries) {
                        delay(200)
                    }
                }
            }
            
            if (!flushSuccess) {
                Log.w(TAG, "Final flush failed, but transfer may still be successful")
            }
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error during file transfer", e)
            updateNotification("Transfer failed: ${e.message}")
            false
        }
    }
    
    /**
     * Attempts to detect if the connection is stable by trying a small operation
     */
    private fun isConnectionStable(outputStream: java.io.OutputStream): Boolean {
        return try {
            // Try to flush - this will fail quickly if connection is broken
            outputStream.flush()
            true
        } catch (e: IOException) {
            Log.d(TAG, "Connection stability check failed", e)
            false
        }
    }
      /**
     * Writes a chunk with immediate error detection
     */
    private fun writeChunkWithRetry(
        outputStream: java.io.OutputStream, 
        buffer: ByteArray, 
        bytesToWrite: Int
    ) {
        try {
            outputStream.write(buffer, 0, bytesToWrite)
        } catch (e: java.net.SocketException) {
            // Re-throw socket exceptions for retry logic
            throw e
        } catch (e: IOException) {
            // Re-throw other IO exceptions
            throw e
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                Config.fileChannelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for file sending"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(Config.notificationTitle)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val notification = buildNotification(content)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
