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
        updateNotification("Sending ${file.name}...")

        // Create metadata
        val metadata = FileTransferMetadata(
            fileName = file.name,
            fileSize = file.length(),
            mimeType = Utils.getMimeType(file.name)
        )

        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), Config.SOCKET_TIMEOUT)
              val inputStream = FileInputStream(file).buffered()
            val outputStream = socket.getOutputStream().buffered()
            
            // Send metadata first
            Utils.sendMetadata(outputStream, metadata)
            
            // Send file with progress tracking
            val success = sendFileWithProgress(inputStream, outputStream, metadata)
            
            if (success) {
                Log.d(TAG, "File sent successfully")
                updateNotification("File sent successfully")
            } else {
                Log.e(TAG, "Failed to send file")
                updateNotification("Failed to send file")
            }
            
            // Keep notification for a brief moment
            delay(3000)
            
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send file", e)
            updateNotification("Failed to send file: ${e.message}")
            delay(5000)
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing socket", e)
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

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                bytesTransferred += bytesRead

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
            }

            outputStream.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error during file transfer", e)
            false
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
