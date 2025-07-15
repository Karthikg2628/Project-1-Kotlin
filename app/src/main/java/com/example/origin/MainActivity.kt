package com.example.origin

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.*

// Direct import for the StreamingForegroundService class itself
import com.example.origin.StreamingForegroundService

// Static imports for companion object members for direct access
import com.example.origin.StreamingForegroundService.Companion.ACTION_START_VIDEO_PROCESSING
import com.example.origin.StreamingForegroundService.Companion.ACTION_STOP_VIDEO_PROCESSING
import com.example.origin.StreamingForegroundService.Companion.ACTION_SET_LOCAL_SURFACE
import com.example.origin.StreamingForegroundService.Companion.ACTION_CLEAR_LOCAL_SURFACE
import com.example.origin.StreamingForegroundService.Companion.EXTRA_SURFACE


class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var connectionStatus: TextView // This will be used for main status updates
    private lateinit var debugInfo: TextView       // This will be used for debug messages
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var playButton: Button
    private lateinit var pauseButton: Button
    private lateinit var stopPlaybackButton: Button
    private lateinit var localVideoSurface: SurfaceView

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjectionLauncher: ActivityResultLauncher<Intent>? = null

    private var isServiceRunning = false // Track service state

    companion object {
        private const val TAG = "OriginMainActivity"
        private const val DEBUG_INFO_MAX_LENGTH = 500 // Increased length for more debug info

        // Define broadcast actions for service communication
        const val ACTION_STATUS_UPDATE = "com.example.origin.STATUS_UPDATE"
        const val EXTRA_STATUS_MESSAGE = "status_message"
        const val EXTRA_STATUS_COLOR = "status_color"
        const val EXTRA_IS_RUNNING = "is_running" // For server/transfer status

        const val ACTION_DEBUG_INFO = "com.example.origin.DEBUG_INFO"
        const val EXTRA_DEBUG_MESSAGE = "debug_message"

        // New actions for playback control
        const val ACTION_PLAY_VIDEO = "com.example.origin.ACTION_PLAY_VIDEO"
        const val ACTION_PAUSE_VIDEO = "com.example.origin.ACTION_PAUSE_VIDEO"
        const val ACTION_STOP_PLAYBACK = "com.example.origin.ACTION_STOP_PLAYBACK"
        const val ACTION_VIDEO_READY = "com.example.origin.ACTION_VIDEO_READY" // Sent by service when frames are ready for playback
        const val ACTION_PLAYBACK_STATE_UPDATE = "com.example.origin.ACTION_PLAYBACK_STATE_UPDATE"
        const val EXTRA_PLAYBACK_STATE = "playback_state" // e.g., "playing", "paused", "stopped"
    }

    // BroadcastReceiver to receive updates from the service
    private val serviceStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_STATUS_UPDATE -> {
                    val message = intent.getStringExtra(EXTRA_STATUS_MESSAGE) ?: "Unknown status"
                    val color = intent.getIntExtra(EXTRA_STATUS_COLOR, Color.BLACK)
                    val running = intent.getBooleanExtra(EXTRA_IS_RUNNING, false)
                    updateUI {
                        updateConnectionStatus(message, color)
                        isServiceRunning = running
                        startButton.isEnabled = !running
                        stopButton.isEnabled = running
                        // Playback buttons remain disabled until video is ready
                    }
                }
                ACTION_DEBUG_INFO -> {
                    val message = intent.getStringExtra(EXTRA_DEBUG_MESSAGE) ?: ""
                    updateUI { appendDebugInfo(message) } // Changed to appendDebugInfo
                }
                ACTION_VIDEO_READY -> {
                    updateUI {
                        updateConnectionStatus("Video frames ready for playback!", Color.MAGENTA)
                        playButton.isEnabled = true
                        pauseButton.isEnabled = false // Initially paused/stopped
                        stopPlaybackButton.isEnabled = false // Enabled only when playing
                    }
                }
                ACTION_PLAYBACK_STATE_UPDATE -> {
                    val state = intent.getStringExtra(EXTRA_PLAYBACK_STATE)
                    updateUI {
                        when (state) {
                            "playing" -> {
                                playButton.isEnabled = false
                                pauseButton.isEnabled = true
                                stopPlaybackButton.isEnabled = true
                                updateConnectionStatus("Playing video...", Color.GREEN)
                            }
                            "paused" -> {
                                playButton.isEnabled = true
                                pauseButton.isEnabled = false
                                stopPlaybackButton.isEnabled = true
                                updateConnectionStatus("Video paused.", Color.BLUE)
                            }
                            "stopped" -> {
                                playButton.isEnabled = true
                                pauseButton.isEnabled = false
                                stopPlaybackButton.isEnabled = false
                                updateConnectionStatus("Video playback stopped.", Color.GRAY)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupButtonListeners()
        initializeMediaProjection()

        // Setup SurfaceHolder callback
        localVideoSurface.holder.addCallback(this)

        // Register the broadcast receiver
        val filter = IntentFilter().apply {
            addAction(ACTION_STATUS_UPDATE)
            addAction(ACTION_DEBUG_INFO)
            addAction(ACTION_VIDEO_READY)
            addAction(ACTION_PLAYBACK_STATE_UPDATE)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(serviceStatusReceiver, filter)

        updateConnectionStatus("App Ready. IP: ${getLocalIpAddress() ?: "N/A"}", Color.parseColor("#0000FF"))
    }

    private fun initViews() {
        connectionStatus = findViewById(R.id.connectionStatus)
        debugInfo = findViewById(R.id.debugInfo)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        playButton = findViewById(R.id.playButton)
        pauseButton = findViewById(R.id.pauseButton)
        stopPlaybackButton = findViewById(R.id.stopPlaybackButton)
        localVideoSurface = findViewById(R.id.localVideoSurface)

        stopButton.isEnabled = false // Disable stop button initially
        playButton.isEnabled = false
        pauseButton.isEnabled = false
        stopPlaybackButton.isEnabled = false
    }

    private fun setupButtonListeners() {
        startButton.setOnClickListener {
            startVideoProcessingAndTransferService()
        }
        stopButton.setOnClickListener {
            stopVideoProcessingAndTransferService()
        }
        playButton.setOnClickListener {
            sendPlaybackCommand(ACTION_PLAY_VIDEO)
        }
        pauseButton.setOnClickListener {
            sendPlaybackCommand(ACTION_PAUSE_VIDEO)
        }
        stopPlaybackButton.setOnClickListener {
            sendPlaybackCommand(ACTION_STOP_PLAYBACK)
        }
    }

    private fun initializeMediaProjection() {
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            // This callback won't be triggered for file-based video.
        }
    }

    private fun startVideoProcessingAndTransferService() {
        if (isServiceRunning) {
            Log.w(TAG, "Video processing/transfer service is already running.")
            return
        }

        updateUI { updateConnectionStatus("Starting Video Processing Service...", Color.parseColor("#FFA500")) }

        val serviceIntent = Intent(this, StreamingForegroundService::class.java).apply {
            action = ACTION_START_VIDEO_PROCESSING
            putExtra(StreamingForegroundService.EXTRA_VIDEO_RES_ID, R.raw.sample_video) // Pass your video resource ID
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Video Processing Service", e)
            updateUI { updateConnectionStatus("Error starting service: ${e.message}", Color.RED) }
        }
    }

    private fun stopVideoProcessingAndTransferService() {
        if (!isServiceRunning) {
            Log.w(TAG, "Video processing/transfer service is not running.")
            return
        }

        updateUI { updateConnectionStatus("Stopping Video Processing Service...", Color.parseColor("#FFA500")) }
        val serviceIntent = Intent(this, StreamingForegroundService::class.java).apply {
            action = ACTION_STOP_VIDEO_PROCESSING
        }
        stopService(serviceIntent)
    }

    private fun sendPlaybackCommand(action: String) {
        val intent = Intent(this, StreamingForegroundService::class.java).apply {
            this.action = action
        }
        startService(intent) // Send command to running service
    }

    override fun onPause() {
        super.onPause()
        // Consider pausing video playback if app goes to background
        // sendPlaybackCommand(ACTION_PAUSE_VIDEO)
    }

    override fun onDestroy() {
        // Unregister the broadcast receiver
        LocalBroadcastManager.getInstance(this).unregisterReceiver(serviceStatusReceiver)

        // Ensure the service is stopped when the activity is completely destroyed
        stopVideoProcessingAndTransferService()
        super.onDestroy()
    }

    private fun getLocalIpAddress(): String? {
        return try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses) }
                .firstOrNull { it is Inet4Address }?.hostAddress
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP address", e)
            null
        }
    }

    private fun updateUI(action: () -> Unit) {
        if (!isFinishing && !isDestroyed) {
            runOnUiThread(action)
        }
    }

    // Consolidated method for connection status updates
    private fun updateConnectionStatus(message: String, color: Int? = null) {
        if (::connectionStatus.isInitialized) {
            connectionStatus.text = message
            color?.let { connectionStatus.setTextColor(it) }
        }
    }

    // Consolidated method for appending debug info
    private fun appendDebugInfo(message: String) {
        if (::debugInfo.isInitialized) {
            val currentText = debugInfo.text.toString()
            val newText = if (currentText.isEmpty()) message else "$currentText\n$message"

            // Trim to max length to prevent OOM
            debugInfo.text = if (newText.length > DEBUG_INFO_MAX_LENGTH) {
                newText.substring(newText.length - DEBUG_INFO_MAX_LENGTH)
            } else {
                newText
            }

            // Scroll to bottom
            debugInfo.post {
                val scrollAmount = debugInfo.layout?.getLineTop(debugInfo.lineCount)!! - debugInfo.height
                if (scrollAmount > 0) {
                    debugInfo.scrollTo(0, scrollAmount)
                }
            }
        }
    }

    // --- SurfaceHolder.Callback methods ---
    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "Surface created. Notifying service.")
        // Send the Surface to the service so it can render video frames to it
        val serviceIntent = Intent(this, StreamingForegroundService::class.java).apply {
            action = ACTION_SET_LOCAL_SURFACE
            putExtra(EXTRA_SURFACE, holder.surface)
        }
        startService(serviceIntent) // Send command to running service
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "Surface changed: width=$width, height=$height")
        // You might want to notify the service of size changes if it needs to adjust rendering
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "Surface destroyed. Notifying service.")
        // Notify the service that the surface is no longer valid
        val serviceIntent = Intent(this, StreamingForegroundService::class.java).apply {
            action = ACTION_CLEAR_LOCAL_SURFACE
        }
        startService(serviceIntent) // Send command to running service
    }
}
