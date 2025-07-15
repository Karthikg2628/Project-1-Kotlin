package com.example.origin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.net.BindException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min // Import min for Math.min replacement

// This service manages the WebSocket server, video frame extraction, and local playback.
class StreamingForegroundService : Service() {

    private var videoWebSocketServer: VideoWebSocketServer? = null
    private var localDisplaySurface: Surface? = null // Surface from MainActivity for local playback

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // State flags for video processing and playback
    private val isVideoProcessingInProgress = AtomicBoolean(false) // True when the main video pipeline is active
    private val isTransferringInProgress = AtomicBoolean(false) // True when sending frames to remote
    private val isPlaybackActive = AtomicBoolean(false) // True when local playback is ongoing
    private val isPlaybackPaused = AtomicBoolean(false) // True if playback is paused

    // Unified MediaCodec components for both local display and remote capture
    private var mediaExtractor: MediaExtractor? = null
    private var videoDecoder: MediaCodec? = null
    private var imageReaderForRemote: android.media.ImageReader? = null // ImageReader to capture frames for remote
    private var imageReaderSurface: Surface? = null // Surface created from ImageReader

    // HandlerThread for ImageReader callbacks
    private var imageReaderHandlerThread: HandlerThread? = null
    private var imageReaderHandler: Handler? = null

    private var playbackStartTimeMillis: Long = 0L // For synchronized playback

    private var videoWidth: Int = 0 // Will be determined from video format
    private var videoHeight: Int = 0 // Will be determined from video format
    private var targetFps: Int = 20 // Default FPS, will be updated from video format, but we'll cap it

    private val desiredOutputFps = 10 // FURTHER REDUCED: Target output FPS for remote streaming
    private var frameIntervalMillis = 1000L / desiredOutputFps // Milliseconds per frame for desired FPS

    private var videoTrackIndex: Int = -1 // Store the selected video track index

    companion object {
        private const val TAG = "StreamingFService"
        private const val NOTIFICATION_CHANNEL_ID = "VideoStreamingChannel"
        private const val NOTIFICATION_ID = 101
        private const val JPEG_QUALITY = 40 // FURTHER REDUCED: Quality for compressing Bitmaps to JPEG
        // Actions for starting/stopping video processing (extraction & transfer)
        const val ACTION_START_VIDEO_PROCESSING = "com.example.origin.ACTION_START_VIDEO_PROCESSING"
        const val ACTION_STOP_VIDEO_PROCESSING = "com.example.origin.ACTION_STOP_VIDEO_PROCESSING"
        // Actions for local Surface management
        const val ACTION_SET_LOCAL_SURFACE = "com.example.origin.ACTION_SET_LOCAL_SURFACE"
        const val ACTION_CLEAR_LOCAL_SURFACE = "com.example.origin.ACTION_CLEAR_LOCAL_SURFACE"
        const val EXTRA_SURFACE = "surface_object" // Key for passing Surface
        // Video resource ID (e.e., R.raw.sample_video)
        const val EXTRA_VIDEO_RES_ID = "video_resource_id"
        // Playback commands (already defined as enum, but for clarity in actions)
        const val ACTION_PLAY_VIDEO = "com.example.origin.ACTION_PLAY_VIDEO"
        const val ACTION_PAUSE_VIDEO = "com.example.origin.ACTION_PAUSE_VIDEO"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        createNotificationChannel() // Ensure channel is created

        // Initialize HandlerThread for ImageReader
        imageReaderHandlerThread = HandlerThread("ImageReaderThread").apply { start() }
        imageReaderHandler = Handler(imageReaderHandlerThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START_VIDEO_PROCESSING -> {
                if (isVideoProcessingInProgress.get()) {
                    Log.w(TAG, "Video processing already in progress. Ignoring START command.")
                    sendStatusUpdate("Service already active.", Color.YELLOW, true)
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, createNotification("Processing video..."))
                startVideoProcessingAndStreaming()
            }
            ACTION_STOP_VIDEO_PROCESSING -> {
                stopVideoProcessingAndStreaming()
            }
            ACTION_SET_LOCAL_SURFACE -> {
                val surface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent?.getParcelableExtra(EXTRA_SURFACE, Surface::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra(EXTRA_SURFACE)
                }

                if (surface != null) {
                    localDisplaySurface = surface
                    Log.d(TAG, "Local display surface set.")
                    // If video processing is active, reconfigure decoder with new surface
                    if (isVideoProcessingInProgress.get() && videoDecoder != null && videoTrackIndex != -1) {
                        Log.d(TAG, "Video processing active, reconfiguring decoder with new surface.")
                        reconfigureDecoderSurface()
                    }
                } else {
                    Log.e(TAG, "Received null surface via ACTION_SET_LOCAL_SURFACE.")
                }
            }
            ACTION_CLEAR_LOCAL_SURFACE -> {
                Log.d(TAG, "Clearing local display surface.")
                localDisplaySurface = null
                // If video processing is active, stop it as display is gone
                if (isVideoProcessingInProgress.get()) {
                    Log.d(TAG, "Surface cleared, stopping video processing.")
                    stopVideoProcessingAndStreaming()
                }
            }
            ACTION_PLAY_VIDEO -> {
                controlPlayback(PlaybackCommand.PLAY)
            }
            ACTION_PAUSE_VIDEO -> {
                controlPlayback(PlaybackCommand.PAUSE)
            }
            MainActivity.ACTION_STOP_PLAYBACK -> {
                controlPlayback(PlaybackCommand.STOP)
            }
            else -> {
                Log.d(TAG, "Unhandled intent action: ${intent?.action}")
            }
        }

        return START_NOT_STICKY
    }

    private fun startVideoProcessingAndStreaming() {
        serviceScope.launch {
            isVideoProcessingInProgress.set(true)
            isPlaybackActive.set(true) // Playback is active as soon as processing starts
            isPlaybackPaused.set(false)
            sendStatusUpdate("Starting server and video stream...", Color.parseColor("#FFA500"), true)
            updateNotification("Streaming video...")

            try {
                // 1. Initialize and start WebSocket server
                Log.d(TAG, "Attempting to start WebSocket server.")
                try {
                    videoWebSocketServer = VideoWebSocketServer(port = 8080, context = applicationContext)
                    videoWebSocketServer?.start()
                    sendStatusUpdate("Server started. Initializing video...", Color.parseColor("#FFA500"), true)
                    Log.d(TAG, "WebSocket server started successfully.")
                } catch (e: BindException) {
                    Log.e(TAG, "WebSocket Error: Address already in use on port 8080. Please ensure no other instance is running.", e)
                    sendStatusUpdate("Error: Port 8080 already in use. Restart app.", Color.RED, false)
                    return@launch // Exit coroutine
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting WebSocket server: ${e.message}", e)
                    sendStatusUpdate("Error starting server: ${e.message}", Color.RED, false)
                    return@launch // Exit coroutine
                }

                // 2. Initialize MediaExtractor and MediaCodec
                val videoUri = Uri.parse("android.resource://" + packageName + "/" + R.raw.sample_video)
                Log.d(TAG, "Setting up MediaExtractor for URI: $videoUri")
                mediaExtractor = MediaExtractor()
                try {
                    mediaExtractor?.setDataSource(applicationContext, videoUri, null)
                    Log.d(TAG, "MediaExtractor data source set successfully.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting MediaExtractor data source: ${e.message}", e)
                    sendStatusUpdate("Error: Could not load video source. Check R.raw.sample_video exists and is valid.", Color.RED, false)
                    return@launch
                }


                var foundVideoTrackIndex = -1 // Use a local variable for initial discovery
                Log.d(TAG, "Searching for video track. Total tracks: ${mediaExtractor?.trackCount ?: 0}")
                for (i in 0 until (mediaExtractor?.trackCount ?: 0)) {
                    val format = mediaExtractor?.getTrackFormat(i)
                    val mime = format?.getString(MediaFormat.KEY_MIME)
                    Log.d(TAG, "Track $i: MIME = $mime")
                    if (mime?.startsWith("video/") == true) {
                        foundVideoTrackIndex = i
                        mediaExtractor?.selectTrack(foundVideoTrackIndex)
                        videoWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                        videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
                        targetFps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                            format.getInteger(MediaFormat.KEY_FRAME_RATE)
                        } else {
                            20 // Default if not specified
                        }
                        Log.d(TAG, "Video track found at index $foundVideoTrackIndex: ${videoWidth}x${videoHeight} @ ${targetFps} FPS")
                        break
                    }
                }
                videoTrackIndex = foundVideoTrackIndex // Assign to class-level variable

                if (videoTrackIndex == -1) {
                    Log.e(TAG, "No video track found in the provided URI.")
                    sendStatusUpdate("Error: No video track found in video file. Is it a valid video?", Color.RED, false)
                    return@launch
                }

                val format = mediaExtractor?.getTrackFormat(videoTrackIndex) ?: run {
                    Log.e(TAG, "MediaFormat is null after selecting track.")
                    sendStatusUpdate("Error: Invalid video format after track selection.", Color.RED, false)
                    return@launch
                }
                val mime = format.getString(MediaFormat.KEY_MIME) ?: run {
                    Log.e(TAG, "MIME type is null from video format.")
                    sendStatusUpdate("Error: Invalid video MIME type.", Color.RED, false)
                    return@launch
                }

                // Create ImageReader for capturing frames for remote
                Log.d(TAG, "Creating ImageReader for remote capture: ${videoWidth}x${videoHeight}, format: YUV_420_888")
                imageReaderForRemote = android.media.ImageReader.newInstance(
                    videoWidth, videoHeight,
                    ImageFormat.YUV_420_888, // Capture YUV for potential smaller size or specific remote needs
                    8 // Increased buffer size from 2 to 8 to prevent image recycling issues
                )
                imageReaderSurface = imageReaderForRemote?.surface
                if (imageReaderSurface == null) {
                    Log.e(TAG, "ImageReader surface is null. Failed to create ImageReader.")
                    sendStatusUpdate("Error: ImageReader surface creation failed.", Color.RED, false)
                    return@launch
                }
                Log.d(TAG, "ImageReader and its surface created successfully.")

                // Set up ImageReader listener to send frames to remote AND draw to local display
                // Pass the handler to ensure callbacks are on the HandlerThread
                Log.d(TAG, "Setting up ImageReader OnImageAvailableListener with handler.")
                imageReaderForRemote?.setOnImageAvailableListe