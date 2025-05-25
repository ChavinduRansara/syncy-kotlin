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
import kotlinx.coroutines.*
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

class MessageSender : Service() {

    companion object {
        private const val TAG = "SyncyP2P"
        private const val CHANNEL_ID = "MessageSenderChannel"
        private const val NOTIFICATION_ID = 101

        const val ACTION_SEND_MESSAGE = "com.example.syncy_p2p.SEND_MESSAGE"
        const val EXTRAS_DATA = "message"
        const val EXTRAS_ADDRESS = "target_address"
        const val EXTRAS_PORT = "target_port"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification("Preparing to send message...")
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CoroutineScope(Dispatchers.IO).launch {
            handleMessageIntent(intent)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private suspend fun handleMessageIntent(intent: Intent?) {
        if (intent?.action != ACTION_SEND_MESSAGE) {
            Log.w(TAG, "Invalid action: ${intent?.action}")
            return
        }

        val message = intent.getStringExtra(EXTRAS_DATA)
        val host = intent.getStringExtra(EXTRAS_ADDRESS)
        val port = intent.getIntExtra(EXTRAS_PORT, Config.DEFAULT_PORT)

        if (message.isNullOrEmpty() || host.isNullOrEmpty()) {
            Log.e(TAG, "Missing message or host address")
            return
        }

        Log.d(TAG, "Sending message to $host:$port")
        updateNotification("Sending message...")

        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), Config.SOCKET_TIMEOUT)
            
            val outputStream = socket.getOutputStream()
            val writer = OutputStreamWriter(outputStream, StandardCharsets.UTF_8)
            
            writer.use {
                it.write(message)
                it.write("\n") // Add newline to indicate end of message
                it.flush()
            }
            
            Log.d(TAG, "Message sent successfully")
            updateNotification("Message sent successfully")
            
            // Keep notification for a brief moment
            delay(2000)
            
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send message", e)
            updateNotification("Failed to send message")
            delay(3000)
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing socket", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                Config.channelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for message sending"
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
