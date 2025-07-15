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
            // REMOVED: stopVideoProcessingAndStreaming(internalCall = true) // This was causing immediate cancellation

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
                    2 // Max images to buffer
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
                imageReaderForRemote?.setOnImageAvailableListener({ reader ->
                    var image: Image? = null
                    try {
                        image = reader.acquireLatestImage()
                        if (image != null) {
                            Log.d(TAG, "ImageReader: Acquired image.")

                            // --- OPTIMIZATION FOR REMOTE STREAMING ---
                            // Only send if there's an open WebSocket connection
                            if (videoWebSocketServer?.isOpen == true) {
                                val remoteJpegBytes = convertYUV420888ToJpeg(image, JPEG_QUALITY)
                                if (remoteJpegBytes != null && remoteJpegBytes.isNotEmpty()) {
                                    videoWebSocketServer?.sendFrameBytes(remoteJpegBytes)
                                    isTransferringInProgress.set(true)
                                    Log.d(TAG, "ImageReader: Sent ${remoteJpegBytes.size} bytes to remote.")
                                } else {
                                    Log.e(TAG, "ImageReader: Failed to convert YUV Image to JPEG for remote or JPEG bytes are empty.")
                                }
                            } else {
                                Log.d(TAG, "ImageReader: No active WebSocket connection, skipping remote send.")
                            }


                            // --- LOCAL DISPLAY (if surface is available) ---
                            localDisplaySurface?.let { surface ->
                                val localBitmap = convertYUV420888ToBitmap(image)
                                if (localBitmap != null) {
                                    serviceScope.launch(Dispatchers.Main) {
                                        var canvas: android.graphics.Canvas? = null
                                        try {
                                            canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                surface.lockHardwareCanvas()
                                            } else {
                                                surface.lockCanvas(null)
                                            }
                                            if (canvas != null) {
                                                canvas.drawColor(Color.BLACK) // Clear canvas
                                                val scaleX = canvas.width.toFloat() / localBitmap.width
                                                val scaleY = canvas.height.toFloat() / localBitmap.height
                                                val scale = min(scaleX, scaleY)
                                                val scaledWidth = localBitmap.width * scale
                                                val scaledHeight = localBitmap.height * scale
                                                val left = (canvas.width - scaledWidth) / 2
                                                val top = (canvas.height - scaledHeight) / 2
                                                val destRect =
                                                    android.graphics.RectF(left, top, left + scaledWidth, top + scaledHeight)
                                                canvas.drawBitmap(localBitmap, null, destRect, null)
                                                Log.d(TAG, "ImageReader: Drawn to local surface.")
                                            }
                                        } finally {
                                            if (canvas != null) {
                                                surface.unlockCanvasAndPost(canvas)
                                            }
                                            localBitmap.recycle() // Recycle bitmap after drawing
                                        }
                                    }
                                } else {
                                    Log.e(TAG, "ImageReader: Failed to convert Image to Bitmap for local display.")
                                }
                            } ?: Log.d(TAG, "ImageReader: Local display surface is null, skipping local draw.")
                        } else {
                            Log.w(TAG, "ImageReader: Acquired null image.")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "ImageReader: Error capturing/processing image from ImageReader: ${e.message}", e)
                    } finally {
                        image?.close() // IMPORTANT: Close the image to release buffer
                    }
                }, imageReaderHandler) // Pass the Handler here!
                Log.d(TAG, "ImageReader OnImageAvailableListener set successfully.")

                // Configure MediaCodec to output to the ImageReader's surface
                Log.d(TAG, "Creating MediaCodec decoder for MIME: $mime")
                videoDecoder = MediaCodec.createDecoderByType(mime)
                try {
                    videoDecoder?.configure(format, imageReaderSurface, null, 0) // Output to ImageReader's surface
                    Log.d(TAG, "MediaCodec decoder configured.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error configuring MediaCodec: ${e.message}", e)
                    sendStatusUpdate("Error: MediaCodec configuration failed. Device may not support video format.", Color.RED, false)
                    videoDecoder?.release()
                    videoDecoder = null
                    return@launch
                }

                try {
                    videoDecoder?.start()
                    Log.d(TAG, "MediaCodec decoder started.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting MediaCodec: ${e.message}", e)
                    sendStatusUpdate("Error: MediaCodec failed to start. Device may not support video format.", Color.RED, false)
                    videoDecoder?.release()
                    videoDecoder = null
                    return@launch
                }


                val bufferInfo = MediaCodec.BufferInfo()
                var isExtractorDone = false
                var isDecoderDone = false

                // Seek MediaExtractor to start of track to ensure clean playback
                mediaExtractor?.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                Log.d(TAG, "MediaExtractor seeked to start of track.")

                // Synchronize playback start with remote (if applicable)
                if (playbackStartTimeMillis == 0L) {
                    playbackStartTimeMillis = System.currentTimeMillis() + 500 // Give some buffer
                }
                val initialDelay = playbackStartTimeMillis - System.currentTimeMillis()
                if (initialDelay > 0) {
                    Log.d(TAG, "Delaying start of decoding loop by $initialDelay ms for synchronization.")
                    delay(initialDelay)
                }

                Log.d(TAG, "Entering main video decoding loop.")
                var lastFrameOutputTime = System.currentTimeMillis() // Track time for FPS limiting
                // Main decoding loop
                while (isActive && !isDecoderDone) {
                    if (isPlaybackPaused.get()) {
                        delay(100) // Small delay while paused
                        continue
                    }

                    // Input for decoder
                    if (!isExtractorDone) {
                        try {
                            val inputBufferId = videoDecoder?.dequeueInputBuffer(10000) // 10ms timeout
                            if (inputBufferId != null && inputBufferId >= 0) {
                                val inputBuffer = videoDecoder?.getInputBuffer(inputBufferId)
                                val sampleSize = mediaExtractor?.readSampleData(inputBuffer!!, 0)
                                val flags = mediaExtractor?.sampleFlags ?: 0
                                val sampleTime = mediaExtractor?.sampleTime ?: 0 // Get sample time here

                                if (sampleSize != null && sampleSize >= 0) {
                                    videoDecoder?.queueInputBuffer(inputBufferId, 0, sampleSize, sampleTime, flags)
                                    mediaExtractor?.advance()
                                    // Log.d(TAG, "Dequeued input buffer $inputBufferId, queued sample size: $sampleSize, time: $sampleTime us")
                                } else {
                                    Log.d(TAG, "Extractor reached end of stream (input). Queuing EOS.")
                                    videoDecoder?.queueInputBuffer(inputBufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    isExtractorDone = true
                                }
                            } else {
                                // Log.d(TAG, "No input buffer available from decoder (dequeueInputBuffer returned $inputBufferId).")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error dequeuing/queuing input buffer: ${e.message}", e)
                            sendStatusUpdate("Decoder input error: ${e.message}", Color.RED, true)
                            break // Exit loop on error
                        }
                    }

                    // Output from decoder
                    try {
                        val outputBufferId = videoDecoder?.dequeueOutputBuffer(bufferInfo, 10000) // 10ms timeout
                        if (outputBufferId != null && outputBufferId >= 0) {
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                Log.d(TAG, "Decoder reached end of stream (output).")
                                isDecoderDone = true
                            } else {
                                // Calculate when this frame should be displayed/processed
                                val presentationTimeUs = bufferInfo.presentationTimeUs
                                val frameProcessTimeMillis = playbackStartTimeMillis + (presentationTimeUs / 1000)
                                val currentTimeMillis = System.currentTimeMillis()
                                val delayUntilProcess = frameProcessTimeMillis - currentTimeMillis

                                // Frame rate limiting logic
                                val timeSinceLastOutput = currentTimeMillis - lastFrameOutputTime
                                if (timeSinceLastOutput < frameIntervalMillis) {
                                    val delayForFps = frameIntervalMillis - timeSinceLastOutput
                                    // Log.d(TAG, "FPS Limiter: Delaying by $delayForFps ms to meet $desiredOutputFps FPS.")
                                    delay(delayForFps)
                                }
                                lastFrameOutputTime = System.currentTimeMillis() // Update last output time

                                if (delayUntilProcess > 0) {
                                    // Log.d(TAG, "Delaying frame processing by $delayUntilProcess ms for presentation.")
                                    delay(delayUntilProcess)
                                }

                                // Release output buffer to the ImageReader's surface
                                videoDecoder?.releaseOutputBuffer(outputBufferId, true) // true to render to surface
                                Log.d(TAG, "Decoder: Released output buffer $outputBufferId for rendering to ImageReader.")
                            }
                        } else if (outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            // Log.d(TAG, "Decoder: No output buffer available yet (INFO_TRY_AGAIN_LATER).")
                        } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val newFormat = videoDecoder?.outputFormat
                            Log.d(TAG, "Decoder: Output format changed: $newFormat")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error dequeuing/releasing output buffer: ${e.message}", e)
                        sendStatusUpdate("Decoder output error: ${e.message}", Color.RED, true)
                        break // Exit loop on error
                    }
                }
                Log.i(TAG, "Video decoding loop finished.")
                sendPlaybackStateUpdate("stopped")
                isPlaybackActive.set(false)
                isPlaybackPaused.set(false)
                sendStatusUpdate("Video stream complete.", Color.GREEN, true)

            } catch (e: CancellationException) {
                Log.i(TAG, "Video decoding loop cancelled.")
            } catch (e: Exception) {
                Log.e(TAG, "Error during video decoding loop: ${e.message}", e)
                sendPlaybackStateUpdate("stopped")
                isPlaybackActive.set(false)
                isPlaybackPaused.set(false)
                sendStatusUpdate("Video streaming error: ${e.message}", Color.RED, true)
            } finally {
                // Ensure resources are released when the main streaming job finishes or is cancelled
                stopServiceResources() // Call the helper function here
            }
        }
    }

    private fun reconfigureDecoderSurface() {
        serviceScope.launch(Dispatchers.IO) {
            val currentDecoder = videoDecoder
            val currentExtractor = mediaExtractor
            val currentSurface = localDisplaySurface
            val currentVideoTrackIndex = videoTrackIndex // Use the stored track index

            if (currentDecoder == null || currentExtractor == null || currentSurface == null || currentVideoTrackIndex == -1) {
                Log.w(TAG, "Cannot reconfigure decoder surface: components are null or track index invalid.")
                return@launch
            }

            try {
                // Pause playback temporarily
                isPlaybackPaused.set(true)
                sendPlaybackStateUpdate("paused")

                currentDecoder.stop()
                currentDecoder.release()
                // Do NOT release extractor here, it's used by the main loop.

                // Re-create decoder and configure with new surface (ImageReader's surface)
                val format = currentExtractor.getTrackFormat(currentVideoTrackIndex) // Use the stored track index
                val mime = format.getString(MediaFormat.KEY_MIME) ?: return@launch

                val newDecoder = MediaCodec.createDecoderByType(mime)
                try {
                    newDecoder.configure(format, imageReaderSurface, null, 0) // Still output to ImageReader's surface
                    Log.d(TAG, "Decoder reconfigured.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error reconfiguring MediaCodec (configure): ${e.message}", e)
                    sendStatusUpdate("Playback error (reconfig configure): ${e.message}", Color.RED, true)
                    newDecoder.release()
                    return@launch
                }

                try {
                    newDecoder.start()
                    Log.d(TAG, "Decoder reconfigured and started.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting MediaCodec: ${e.message}", e)
                    sendStatusUpdate("Playback error (reconfig start): ${e.message}", Color.RED, true)
                    newDecoder.release()
                    return@launch
                }


                // Update reference
                videoDecoder = newDecoder

                // Resume playback
                isPlaybackPaused.set(false)
                sendPlaybackStateUpdate("playing")
                Log.d(TAG, "Decoder reconfigured and playback resumed with new surface.")

            } catch (e: Exception) {
                Log.e(TAG, "Error reconfiguring decoder surface: ${e.message}", e)
                sendStatusUpdate("Playback error (reconfig general): ${e.message}", Color.RED, true)
                stopVideoProcessingAndStreaming()
            }
        }
    }


    private fun stopVideoProcessingAndStreaming(internalCall: Boolean = false) {
        if (!internalCall && !isVideoProcessingInProgress.get()) {
            Log.d(TAG, "No active video processing to stop (external call).")
            stopServiceResources() // Call helper function
            return
        }
        Log.i(TAG, "Stopping all video processes...")
        serviceScope.launch {
            isPlaybackPaused.set(true) // Pause immediately
            // Cancel the main streaming coroutine
            serviceScope.coroutineContext.cancelChildren() // Cancel all children of serviceScope

            // Give a moment for cancellation to propagate and resources to be released in finally block
            delay(500)

            // Ensure all components are explicitly nullified and server stopped
            videoWebSocketServer?.sendPlaybackCommand(PlaybackCommand.STOP) // Send stop to clients
            videoWebSocketServer?.stopServer()
            videoWebSocketServer = null

            // The resources (decoder, extractor, imageReader) are released in the finally block
            // of the main streaming coroutine. Just ensure flags are reset.
            isVideoProcessingInProgress.set(false)
            isPlaybackActive.set(false)
            isPlaybackPaused.set(false)
            isTransferringInProgress.set(false)
            videoTrackIndex = -1 // Reset track index

            stopForeground(true) // Remove notification
            stopSelf() // Stop the service
            sendStatusUpdate("Service Stopped. Idle.", Color.GRAY, false)
        }
    }

    private fun controlPlayback(command: PlaybackCommand) {
        serviceScope.launch {
            when (command) {
                PlaybackCommand.PLAY -> {
                    if (isPlaybackPaused.get()) {
                        isPlaybackPaused.set(false)
                        sendPlaybackStateUpdate("playing")
                        videoWebSocketServer?.sendPlaybackCommand(PlaybackCommand.PLAY, playbackStartTimeMillis, targetFps)
                    } else if (!isVideoProcessingInProgress.get()) {
                        // If not already processing, start it
                        startVideoProcessingAndStreaming()
                    }
                }
                PlaybackCommand.PAUSE -> {
                    if (!isPlaybackPaused.get() && isVideoProcessingInProgress.get()) {
                        isPlaybackPaused.set(true)
                        sendPlaybackStateUpdate("paused")
                        videoWebSocketServer?.sendPlaybackCommand(PlaybackCommand.PAUSE)
                    }
                }
                PlaybackCommand.STOP -> {
                    stopVideoProcessingAndStreaming()
                }
            }
        }
    }

    private fun compressBitmapToJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        return ByteArrayOutputStream().use { baos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            return@use baos.toByteArray()
        }
    }

    /**
     * Converts an Android Image (YUV_420_888 format) to a Bitmap.
     * This implementation directly processes YUV planes to ARGB.
     */
    private fun convertYUV420888ToBitmap(image: Image): Bitmap? {
        if (image.format != ImageFormat.YUV_420_888) {
            Log.e(TAG, "Invalid image format for YUV to Bitmap conversion: ${image.format}")
            return null
        }

        val width = image.width
        val height = image.height
        val planes = image.planes

        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        yBuffer.rewind()
        uBuffer.rewind()
        vBuffer.rewind()

        val yRowStride = planes[0].rowStride
        val uRowStride = planes[1].rowStride
        val vRowStride = planes[2].rowStride
        val uPixelStride = planes[1].pixelStride
        val vPixelStride = planes[2].pixelStride

        val rgba = IntArray(width * height)

        // Process Y plane
        for (y in 0 until height) {
            for (x in 0 until width) {
                val Y = yBuffer.get(y * yRowStride + x).toInt() and 0xFF

                val uvX = x / 2
                val uvY = y / 2

                // Get U and V values, handling their pixel strides
                val U = uBuffer.get(uvY * uRowStride + uvX * uPixelStride).toInt() and 0xFF
                val V = vBuffer.get(uvY * vRowStride + uvX * vPixelStride).toInt() and 0xFF

                // YUV to RGB conversion (simplified, full range, BT.601)
                val C = Y - 16
                val D = U - 128
                val E = V - 128

                val R = (298 * C + 409 * E + 128) shr 8
                val G = (298 * C - 100 * D - 208 * E + 128) shr 8
                val B = (298 * C + 516 * D + 128) shr 8

                val red = R.coerceIn(0, 255)
                val green = G.coerceIn(0, 255)
                val blue = B.coerceIn(0, 255)

                rgba[y * width + x] = Color.argb(255, red, green, blue)
            }
        }

        return Bitmap.createBitmap(rgba, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Converts an Android Image (YUV_420_888 format) directly to JPEG bytes.
     * This is generally more efficient than converting to Bitmap first for remote transmission.
     */
    private fun convertYUV420888ToJpeg(image: Image, quality: Int): ByteArray? {
        if (image.format != ImageFormat.YUV_420_888) {
            Log.e(TAG, "Invalid image format for YUV to JPEG conversion: ${image.format}")
            return null
        }

        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        yBuffer.rewind()
        uBuffer.rewind()
        vBuffer.rewind()

        // Calculate total size for NV21 format (Y + interleaved VU)
        val ySize = yBuffer.remaining()
        // Chroma planes are typically half width and half height, so their total size is (width/2 * height/2) * 2 = width * height / 2
        val chromaSize = image.width * image.height / 2
        val yuvBytes = ByteArray(ySize + chromaSize)

        yBuffer.get(yuvBytes, 0, ySize)

        // Interleave U and V planes into NV21 format (VU)
        var i = ySize
        val uRowStride = planes[1].rowStride
        val vRowStride = planes[2].rowStride
        val uPixelStride = planes[1].pixelStride
        val vPixelStride = planes[2].pixelStride

        val chromaHeight = image.height / 2
        val chromaWidth = image.width / 2

        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                // NV21 expects V then U
                yuvBytes[i++] = vBuffer.get(row * vRowStride + col * vPixelStride)
                yuvBytes[i++] = uBuffer.get(row * uRowStride + col * uPixelStride)
            }
        }

        val yuvImage = android.graphics.YuvImage(yuvBytes, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        val success = yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)
        if (success) {
            return out.toByteArray()
        }
        return null
    }

    // --- Service Lifecycle and Resource Management ---

    private fun stopServiceResources() {
        Log.i(TAG, "Stopping service resources...")
        // Ensure all components are released and nullified
        videoDecoder?.stop()
        videoDecoder?.release()
        mediaExtractor?.release()
        imageReaderForRemote?.close()
        imageReaderSurface?.release()

        videoDecoder = null
        mediaExtractor = null
        imageReaderForRemote = null
        imageReaderSurface = null

        videoWebSocketServer?.stopServer()
        videoWebSocketServer = null

        isVideoProcessingInProgress.set(false)
        isPlaybackActive.set(false)
        isPlaybackPaused.set(false)
        isTransferringInProgress.set(false)
        videoTrackIndex = -1 // Reset track index

        // Quit the HandlerThread
        imageReaderHandlerThread?.quitSafely()
        imageReaderHandlerThread = null
        imageReaderHandler = null
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // This service does not provide binding
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        stopServiceResources() // Ensure all resources are released
        serviceScope.cancel() // Cancel all coroutines launched by this scope
        super.onDestroy()
    }

    // --- Notification and Communication Helpers ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Video Streaming Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(message: String): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Video Streaming Origin")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Replace with your app's icon
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(message: String) {
        val notification = createNotification(message)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    // These functions send updates to MainActivity via LocalBroadcastManager
    fun sendStatusUpdate(message: String, color: Int, isRunning: Boolean) {
        val intent = Intent(MainActivity.ACTION_STATUS_UPDATE).apply {
            putExtra(MainActivity.EXTRA_STATUS_MESSAGE, message)
            putExtra(MainActivity.EXTRA_STATUS_COLOR, color)
            putExtra(MainActivity.EXTRA_IS_RUNNING, isRunning)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    fun sendDebugInfo(message: String) {
        val intent = Intent(MainActivity.ACTION_DEBUG_INFO).apply {
            putExtra(MainActivity.EXTRA_DEBUG_MESSAGE, message)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    fun sendPlaybackStateUpdate(state: String) {
        val intent = Intent(MainActivity.ACTION_PLAYBACK_STATE_UPDATE).apply {
            putExtra(MainActivity.EXTRA_PLAYBACK_STATE, state)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
}

// Enum for playback commands
enum class PlaybackCommand {
    PLAY, PAUSE, STOP
}
