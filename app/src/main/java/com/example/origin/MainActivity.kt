// MainActivity.kt

package com.example.origin

import android.content.res.AssetFileDescriptor
import android.graphics.*
import android.media.*
import android.os.*
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView // Added import
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers

import okhttp3.*
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Deflater


// Define FrameData if it's a simple data holder
data class FrameData(
    val bitmap: Bitmap,
    val frameId: Int,
    val timestamp: Long
)

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {
    // UI Components - Assuming these are in your R.layout.activity_main
    private lateinit var videoView: SurfaceView
    private lateinit var connectionStatus: TextView
    private lateinit var debugInfo: TextView
    private lateinit var startButton: Button // Assuming you have a start button
    private lateinit var stopButton: Button // Assuming you have a stop button

    // Media Components
    private var mediaExtractor: MediaExtractor? = null
    private var mediaCodec: MediaCodec? = null
    private var imageReader: ImageReader? = null
    private var videoWidth = 1280
    private var videoHeight = 720

    // Networking
    private lateinit var okHttpClient: OkHttpClient
    private var webSocket: WebSocket? = null

    // Coroutine Scope
    private val scope = CoroutineScope(Dispatchers.Main + Job()) // Or Dispatchers.IO for background tasks

    // State & Data
    private val frameQueue = LinkedBlockingQueue<FrameData>(10) // Example size
    private val frameCounter = AtomicInteger(0)
    private val isStreaming = AtomicBoolean(false) // To manage streaming state

    // Constants
    private companion object {
        private const val VIDEO_ASSET = "video.mp4" // Replace with your video asset file name
        private const val DEBUG_INFO_MAX_LENGTH = 200
        private const val TAG = "MainActivity"
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initViews()
        setupVideoSurface()
        setupButtonListeners()
        initializeNetworking() // Renamed from initializeConnection for clarity
    }

    private fun initViews() {
        videoView = findViewById(R.id.videoView) // Replace with your SurfaceView ID
        connectionStatus = findViewById(R.id.connectionStatus) // Replace with your TextView ID
        debugInfo = findViewById(R.id.debugInfo) // Replace with your TextView ID
        startButton = findViewById(R.id.startButton) // Replace with your Button ID
        stopButton = findViewById(R.id.stopButton) // Replace with your Button ID
    }

    private fun setupVideoSurface() {
        videoView.holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "Surface created")
        // If you need to start playback immediately upon surface creation and stream is ready:
        // if (isStreaming.get()) {
        //     scope.launch { startDecodingLoop(holder.surface) }
        // }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "Surface changed to $width x $height")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "Surface destroyed")
        // Consider stopping decoding or playback here if it's tied directly to this surface
    }

    private fun setupButtonListeners() {
        startButton.setOnClickListener {
            startStreaming()
        }
        stopButton.setOnClickListener {
            stopStreaming()
        }
    }

    private fun initializeNetworking() {
        okHttpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS) // Example: for long-lived connections
            .build()
        // Initialize WebSocket connection logic here if needed at startup,
        // or trigger it on demand (e.g., when startStreaming is called)
        updateConnectionStatus("WebSocket Client Initialized. IP: ${getLocalIpAddress() ?: "N/A"}", "#0000FF")
    }


    private suspend fun initializeMediaComponents() {
        try {
            // Ensure media components are released before re-initializing
            releaseMediaComponents()

            mediaExtractor = MediaExtractor().apply {
                assets.openFd(VIDEO_ASSET).use { afd ->
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                }
            }

            val videoTrack = (0 until (mediaExtractor?.trackCount ?: 0))
                .firstOrNull { i ->
                    mediaExtractor?.getTrackFormat(i)?.getString(MediaFormat.KEY_MIME)
                        ?.startsWith("video/") == true
                } ?: throw IOException("No video track found in $VIDEO_ASSET")

            mediaExtractor?.selectTrack(videoTrack)
            val format = mediaExtractor?.getTrackFormat(videoTrack) ?: throw IOException("No format for video track")

            videoWidth = format.getInteger(MediaFormat.KEY_WIDTH)
            videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)

            updateDebugInfo("Video Resolution: $videoWidth x $videoHeight")

            imageReader = ImageReader.newInstance(videoWidth, videoHeight, PixelFormat.RGBA_8888, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        // This callback runs on a Handler thread associated with ImageReader.
                        // If you need to do heavy processing or UI updates, consider dispatching.
                        val bitmap = imageToBitmap(image)
                        image.close()
                        val frameData = FrameData(
                            bitmap = bitmap,
                            frameId = frameCounter.incrementAndGet(),
                            timestamp = System.currentTimeMillis()
                        )
                        if (!frameQueue.offer(frameData)) {
                            frameQueue.poll() // Make space if full
                            frameQueue.offer(frameData)
                        }
                        // If you need to send frames immediately via WebSocket:
                        // sendFrameData(frameData)
                    }
                }, Handler(Looper.getMainLooper())) // Or a background handler
            }
            val surface = imageReader!!.surface

            mediaCodec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply {
                configure(format, surface, null, 0)
                start()
            }
            Log.d(TAG, "MediaComponents Initialized. Decoder: ${mediaCodec?.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing media components", e)
            releaseMediaComponents() // Ensure cleanup on failure
            updateUI {
                updateConnectionStatus("Error initializing media: ${e.message}", "#FF0000")
            }
            throw e // Re-throw to be caught by calling coroutine
        }
    }

    private fun startStreaming() {
        if (isStreaming.compareAndSet(false, true)) {
            scope.launch(Dispatchers.IO) { // Use Dispatchers.IO for blocking media operations
                try {
                    updateUI { updateConnectionStatus("Initializing Media...", "#FFA500") }
                    initializeMediaComponents() // Initialize or re-initialize here
                    updateUI { updateConnectionStatus("Starting stream...", "#00FF00") }
                    Log.d(TAG, "Starting decoding loop...")
                    decodeFrames() // Start the decoding loop
                } catch (e: Exception) {
                    Log.e(TAG, "Streaming failed to start", e)
                    isStreaming.set(false)
                    updateUI {
                        updateConnectionStatus("Streaming error: ${e.message}", "#FF0000")
                    }
                    releaseMediaComponents()
                }
            }
        } else {
            Log.d(TAG, "Stream already running or starting.")
        }
    }


    private suspend fun decodeFrames() {
        val currentJob = scope.coroutineContext[Job] // Get the Job from the scope's context

        val bufferInfo = MediaCodec.BufferInfo()
        mediaCodec?.let { codec ->
            mediaExtractor?.let { extractor ->
                while (isStreaming.get() && (currentJob?.isActive == true)) { // Check coroutine status
                    // Feed input buffers
                    val inputBufferIndex = codec.dequeueInputBuffer(10000) // 10ms timeout
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                // End of stream
                                codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                Log.d(TAG, "EOS queued for input buffer")
                                // isStreaming.set(false) // Or handle looping/restart here
                            } else {
                                codec.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    // Process output buffers
                    var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000) // 10ms timeout
                    while (outputBufferIndex >= 0 && isStreaming.get()) { // Loop for all available outputs
                        // The frame is rendered to the ImageReader's surface automatically
                        // The onImageAvailable listener of ImageReader will be triggered.
                        codec.releaseOutputBuffer(outputBufferIndex, bufferInfo.size != 0) // Render to surface if size > 0

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            Log.d(TAG, "EOS reached on output.")
                            // Optionally loop video by seeking extractor and restarting
                            // For now, we stop streaming
                            // For looping:
                            // extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                            // codec.flush() // Important if reusing codec instance
                            // Or simply stop:
                            isStreaming.set(false)
                            break // Exit the inner while loop for output buffers
                        }
                        outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0) // No timeout for subsequent checks
                    }
                    if (!isStreaming.get()) break // Exit outer while if streaming stopped
                }
            }
        }
        Log.d(TAG, "Decoding loop finished.")
        if (!isStreaming.get()) { // Ensure UI reflects stopped state if loop exited due to isStreaming = false
            updateUI {
                updateConnectionStatus("Stream stopped.", "#AAAAAA")
            }
        }
    }


    // processDecodedFrame is effectively handled by ImageReader.OnImageAvailableListener

    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width // Use image.width here

        // Create a bitmap with the correct width, then copy, then crop if needed
        val tempBitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride, // Full width including padding
            image.height,
            Bitmap.Config.ARGB_8888
        )
        tempBitmap.copyPixelsFromBuffer(buffer)

        // If rowPadding was > 0, the bitmap is wider than the actual image content.
        // Create a new bitmap cropped to the actual image dimensions.
        return if (rowPadding > 0 || tempBitmap.width != videoWidth || tempBitmap.height != videoHeight) {
            Bitmap.createBitmap(tempBitmap, 0, 0, videoWidth, videoHeight).also {
                tempBitmap.recycle() // Recycle the temporary larger bitmap
            }
        } else {
            tempBitmap // Return as is if no padding and dimensions match
        }
    }


    private fun stopStreaming() {
        if (isStreaming.compareAndSet(true, false)) {
            Log.d(TAG, "Stopping stream...")
            scope.launch { // Ensure media release happens, can be on any thread
                releaseMediaComponents()
                frameQueue.clear() // Clear any pending frames
                frameCounter.set(0)
                updateUI {
                    updateConnectionStatus("Stream stopped.", "#AAAAAA")
                    debugInfo.text = "Stream stopped."
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause called")
        stopStreaming()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called")
        scope.cancel() // Cancel all coroutines started by this scope
        stopStreaming() // Ensure streaming is stopped and resources are released
        webSocket?.close(1000, "Activity destroyed") // Use 1000 for normal closure
        // OkHttpClient's dispatcher shuts down automatically with ExecutorService,
        // but explicit shutdown is good practice if you manage the executor elsewhere.
        okHttpClient.dispatcher.executorService.shutdown()
        // releaseMediaComponents() // Already called by stopStreaming if it was running
        imageReader?.close() // Ensure ImageReader is closed
        Log.d(TAG, "Activity destroyed and resources released.")
    }

    private fun releaseMediaComponents() {
        Log.d(TAG, "Releasing media components...")
        try {
            mediaCodec?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "MediaCodec stop failed, possibly already stopped or not started.", e)
        }
        try {
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "MediaCodec release error", e)
        }
        try {
            mediaExtractor?.release()
        } catch (e: Exception) {
            Log.e(TAG, "MediaExtractor release error", e)
        }
        // ImageReader is closed separately in onDestroy or when re-initialized to avoid premature closing
        // imageReader?.close() // Consider if this should be here or handled more carefully

        mediaCodec = null
        mediaExtractor = null
        // imageReader = null // Don't nullify if you expect it to be reused by initializeMediaComponents immediately
        Log.d(TAG, "Media components released.")
    }

    private fun getLocalIpAddress(): String? {
        return try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses) }
                .firstOrNull { it is Inet4Address }?.hostAddress
        } catch (e: Exception) {
            Log.e(TAG, "Could not get IP Address", e)
            null
        }
    }

    private fun updateUI(action: () -> Unit) {
        if (!isFinishing && !isDestroyed) {
            runOnUiThread(action)
        }
    }

    private fun updateConnectionStatus(message: String, color: String? = null) {
        // Ensure TextViews are initialized
        if (::connectionStatus.isInitialized) {
            connectionStatus.text = message
            color?.let { connectionStatus.setTextColor(Color.parseColor(it)) }
        } else {
            Log.w(TAG, "connectionStatus TextView not initialized when trying to update.")
        }
    }

    private fun updateDebugInfo(message: String) {
        if (::debugInfo.isInitialized) {
            debugInfo.text = if (message.length <= DEBUG_INFO_MAX_LENGTH) {
                message
            } else {
                "${message.take(DEBUG_INFO_MAX_LENGTH / 2)}...${message.takeLast(DEBUG_INFO_MAX_LENGTH / 2)}"
            }
        } else {
            Log.w(TAG, "debugInfo TextView not initialized when trying to update.")
        }
    }

    // Example WebSocket integration (very basic)
    private fun connectWebSocket() {
        val request = Request.Builder().url("ws://your_server_ip:your_port").build() // Replace with your WebSocket server URL
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                updateUI { updateConnectionStatus("WebSocket Connected", "#00FF00") }
                // Start sending frames or a hello message
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                updateUI { updateDebugInfo("WS Message: $text") }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Handle binary messages if needed
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                updateUI { updateConnectionStatus("WebSocket Closing: $reason", "#FFA500") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                updateUI { updateConnectionStatus("WebSocket Failure: ${t.message}", "#FF0000") }
                // Consider reconnection logic here
            }
        })
    }

    // If you plan to send FrameData over WebSocket
    private fun sendFrameData(frameData: FrameData) {
        scope.launch(Dispatchers.IO) { // Perform network operation on IO dispatcher
            // Example: Convert bitmap to JPEG and send as ByteString
            val stream = ByteArrayOutputStream()
            frameData.bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream) // Adjust quality as needed
            val byteArray = stream.toByteArray()

            // You might want to wrap this in a custom message format (e.g., JSON with metadata + image bytes)
            // For simplicity, sending raw bytes:
            val success = webSocket?.send(ByteString.of(*byteArray))
            if (success == true) {
                Log.d(TAG, "Frame ${frameData.frameId} sent.")
            } else {
                Log.w(TAG, "Failed to send frame ${frameData.frameId}.")
                // Handle WebSocket send failure (e.g., queue is full, connection closed)
            }
        }
    }
}
