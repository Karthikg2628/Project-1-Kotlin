package com.example.origin

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.nio.ByteBuffer
import okio.ByteString
import java.util.concurrent.atomic.AtomicBoolean

// This service will run in the foreground to handle video/audio streaming
class StreamingForegroundService : Service(), WSClient.WSClientListener {

    private var wsClient: WSClient? = null
    private var mediaStreamer: MediaStreamer? = null
    private var streamingUri: Uri? = null
    private var remoteIpAddress: String? = null

    // New: Track connection state locally
    private val _isConnected = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "StreamingForegroundService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "StreamingForegroundService onStartCommand")

        createNotificationChannel()

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Streaming in Progress")
            .setContentText("Connecting to remote...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        // Get data (MP4 URI and Remote IP) from the intent that started the service
        streamingUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_MP4_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") // For older Android versions (API < 33)
            intent?.getParcelableExtra(EXTRA_MP4_URI)
        }
        remoteIpAddress = intent?.getStringExtra(EXTRA_REMOTE_IP)

        if (streamingUri == null || remoteIpAddress == null) {
            Log.e(TAG, "Missing MP4 URI or IP address for streaming. Stopping service.")
            updateNotification("Error: Missing stream data.")
            sendStatusBroadcast("Error: Missing stream data.", android.graphics.Color.RED, false)
            stopSelf() // Stop the service if essential data is missing
            return START_NOT_STICKY
        }

        // Initialize and connect WebSocket client
        // Only connect if not already connected
        if (wsClient == null || !_isConnected.get()) {
            // FIX: Pass only the IP address to WSClient, not the full URI.
            // WSClient expects just the IP to construct "ws://IP:PORT".
            wsClient = WSClient(remoteIpAddress!!, this)
            wsClient?.connect()
            updateNotification("Connecting to Remote: $remoteIpAddress")
            sendStatusBroadcast("Connecting to Remote: $remoteIpAddress", android.graphics.Color.GRAY, true)
        } else {
            // This might happen if the service is restarted by the system and WS is still connected
            Log.d(TAG, "WebSocket already connected, attempting to start MediaStreamer directly.")
            startMediaStreamerIfReady()
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        // This service is not designed for binding, so return null
        return null
    }

    override fun onDestroy() {
        Log.d(TAG, "StreamingForegroundService onDestroy")
        mediaStreamer?.stopStreaming() // Stop the streamer first
        wsClient?.disconnect() // Disconnect WebSocket
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE) // Remove the foreground notification
        sendStatusBroadcast("Stream Stopped.", android.graphics.Color.BLACK, false) // Final status update
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Live Streaming Service Channel", // User-visible name
                NotificationManager.IMPORTANCE_LOW // Low importance notification
            )
            val manager = getSystemService(NotificationManager::class.java) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun updateNotification(content: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Streaming in Progress")
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun startMediaStreamerIfReady() {
        // Only start MediaStreamer if WebSocket is connected, URI and IP are present, and streamer is not already running
        if (_isConnected.get() && streamingUri != null && mediaStreamer == null) {
            mediaStreamer = MediaStreamer(applicationContext, streamingUri!!, wsClient!!)
            mediaStreamer?.startStreaming()
            updateNotification("Streaming to: ${remoteIpAddress}")
            sendStatusBroadcast("Streaming to: ${remoteIpAddress}", android.graphics.Color.GREEN, true)
            Log.d(TAG, "MediaStreamer started.")
        } else {
            Log.w(TAG, "Cannot start MediaStreamer. WS connected: ${_isConnected.get()}, URI present: ${streamingUri != null}, Streamer null: ${mediaStreamer == null}")
        }
    }

    // Helper to send status updates to MainActivity via LocalBroadcastManager
    private fun sendStatusBroadcast(message: String, color: Int, isRunning: Boolean) {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_COLOR, color)
            putExtra(EXTRA_IS_RUNNING, isRunning)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    // Helper to send debug info to MainActivity via LocalBroadcastManager
    private fun sendDebugBroadcast(message: String) {
        val intent = Intent(ACTION_DEBUG_INFO).apply {
            putExtra(EXTRA_MESSAGE, message)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    // --- WSClient.WSClientListener implementation ---
    override fun onConnected() {
        _isConnected.set(true) // Update local connection state
        Log.d(TAG, "WebSocket Connected within service.")
        startMediaStreamerIfReady() // Attempt to start streaming once connected
    }

    override fun onDisconnected(code: Int, reason: String) {
        _isConnected.set(false) // Update local connection state
        Log.d(TAG, "WebSocket Disconnected within service: $reason (Code: $code)")
        updateNotification("Disconnected: $reason")
        sendStatusBroadcast("Disconnected: $reason (Code: $code)", android.graphics.Color.RED, false)
        mediaStreamer?.stopStreaming() // Ensure media streamer stops
        mediaStreamer = null
        stopSelf() // Stop the service itself on disconnect
    }

    override fun onMessage(text: String) {
        Log.d(TAG, "Received text from Remote: $text")
        // Handle any text messages from Remote if necessary
        sendDebugBroadcast("Remote: $text") // Forward to UI as debug info
    }

    override fun onMessage(bytes: ByteString) {
        Log.d(TAG, "Received bytes from Remote: ${bytes.size} bytes")
        // Handle any binary data from Remote if necessary
        sendDebugBroadcast("Remote: Received ${bytes.size} bytes") // Forward to UI as debug info
    }

    override fun onFailure(t: Throwable, response: okhttp3.Response?) {
        _isConnected.set(false) // Update local connection state
        Log.e(TAG, "WebSocket connection failed within service", t)
        updateNotification("Connection Failed: ${t.message}")
        sendStatusBroadcast("Connection Failed: ${t.message}", android.graphics.Color.RED, false)
        mediaStreamer?.stopStreaming() // Ensure media streamer stops
        mediaStreamer = null
        stopSelf() // Stop the service itself on connection failure
    }

    companion object {
        private const val TAG = "StreamingFGService"
        private const val CHANNEL_ID = "LiveStreamChannel" // Unique ID for notification channel
        private const val NOTIFICATION_ID = 101 // Unique ID for the foreground notification

        // Intent extras for passing data to the service
        const val EXTRA_MP4_URI = "com.example.origin.EXTRA_MP4_URI"
        const val EXTRA_REMOTE_IP = "com.example.origin.EXTRA_REMOTE_IP"

        // Actions for broadcast intents (for MainActivity to listen to)
        const val ACTION_STATUS_UPDATE = "com.example.origin.STATUS_UPDATE"
        const val ACTION_DEBUG_INFO = "com.example.origin.DEBUG_INFO"

        // Extras for broadcast intents
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_COLOR = "color"
        const val EXTRA_IS_RUNNING = "isRunning"

        /**
         * Helper method to start the StreamingForegroundService from an Activity or other component.
         * Passes the MP4 URI and Remote IP address to the service.
         */
        fun start(context: Context, mp4Uri: Uri, remoteIp: String) {
            val intent = Intent(context, StreamingForegroundService::class.java).apply {
                putExtra(EXTRA_MP4_URI, mp4Uri)
                putExtra(EXTRA_REMOTE_IP, remoteIp)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.d(TAG, "Attempting to start StreamingForegroundService from Activity.")
        }

        /**
         * Helper method to stop the StreamingForegroundService.
         */
        fun stop(context: Context) {
            val intent = Intent(context, StreamingForegroundService::class.java)
            context.stopService(intent)
            Log.d(TAG, "Attempting to stop StreamingForegroundService from Activity.")
        }
    }
}