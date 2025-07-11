package com.example.origin

import android.content.res.AssetFileDescriptor
import android.graphics.*
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.*
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
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
import java.util.zip.DeflaterOutputStream
import android.view.SurfaceView
import android.R.attr.configure
import com.google.common.collect.ComparisonChain.start

private fun Deflater.use(block: (Deflater) -> Unit) {}

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {
    // UI Components
    private lateinit var videoView: SurfaceView
    private lateinit var connectionStatus: TextView
    private lateinit var btnStartStream: Button
    private lateinit var btnStopStream: Button
    private lateinit var debugInfo: TextView
    private var videoSurface: Surface? = null

    // Networking
    private var webSocket: WebSocket? = null
    private val okHttpClient = OkHttpClient.Builder()
        .readTimeout(3, TimeUnit.SECONDS)
        .pingInterval(1, TimeUnit.SECONDS)
        .build()

    // State Management
    private val isStreaming = AtomicBoolean(false)
    private val isConnected = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val frameQueue = LinkedBlockingQueue<FrameData>(5)
    private val frameCounter = AtomicInteger(0)
    private var currentQuality = 70
    private var currentFps = 1
    private var lastFrameTime = System.currentTimeMillis()

    // Media Components
    private var mediaExtractor: MediaExtractor? = null
    private var mediaCodec: MediaCodec? = null
    private val reusableBitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.RGB_565)
    private val reusableCanvas = Canvas(reusableBitmap)
    private val reusableStream = ByteArrayOutputStream(300 * 1024)
    private val paint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        textSize = 48f
    }

    companion object {
        private const val WS_PORT = 8080
        private const val VIDEO_ASSET = "video.mp4"
        private const val TIMEOUT_US = 10_000L
        private const val MAX_FRAME_SIZE_BYTES = 800_000
        private const val MIN_QUALITY = 50
        private const val MAX_QUALITY = 85
        private const val INITIAL_FPS = 24
        private const val DEBUG_INFO_MAX_LENGTH = 100
    }

    private data class FrameData(
        val bitmap: Bitmap,
        val frameId: Int,
        val timestamp: Long
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initViews()
        setupVideoSurface()
        setupButtonListeners()
        initializeConnection()
    }

    private fun initViews() {
        videoView = findViewById(R.id.videoView)
        connectionStatus = findViewById(R.id.connectionStatus)
        btnStartStream = findViewById(R.id.btnStartStream)
        btnStopStream = findViewById(R.id.btnStopStream)
        debugInfo = findViewById(R.id.debugInfo)
    }

    private fun setupVideoSurface() {
        videoView.holder.addCallback(this)
    }

    private fun setupButtonListeners() {
        btnStartStream.setOnClickListener { startStreaming() }
        btnStopStream.setOnClickListener { stopStreaming() }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        videoSurface = holder.surface
        Log.d("Surface", "Surface created")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d("Surface", "Changed to $width x $height")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        videoSurface = null
        Log.d("Surface", "Surface destroyed")
    }

    private fun startStreaming() {
        if (isStreaming.get()) return

        isStreaming.set(true)
        updateUI {
            btnStartStream.isEnabled = false
            btnStopStream.isEnabled = true
            updateConnectionStatus("Status: Streaming", "#FF4CAF50")
        }

        startFrameProcessors()
    }

    private fun stopStreaming() {
        if (!isStreaming.get()) return

        isStreaming.set(false)
        frameQueue.clear()
        updateUI {
            btnStartStream.isEnabled = true
            btnStopStream.isEnabled = false
            updateConnectionStatus("Status: Stopped", "#FFE60B0B")
        }
    }

    private fun initializeConnection() {
        scope.launch(Dispatchers.IO) {
            while (isActive && !isConnected.get()) {
                try {
                    getLocalIpAddress()?.let { ip ->
                        updateDebugInfo("Server IP: $ip:$WS_PORT")

                        val request = Request.Builder()
                            .url("ws://$ip:$WS_PORT")
                            .build()

                        webSocket = okHttpClient.newWebSocket(request, createWebSocketListener())
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.e("Connection", "Error initializing", e)
                }
                delay(5000)
            }
        }
    }

    private fun createWebSocketListener() = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            isConnected.set(true)
            updateUI {
                updateConnectionStatus("Status: Connected", "#FF4CAF50")
                btnStartStream.isEnabled = true
            }
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            handleDisconnection("Connection failed: ${t.message}")
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            handleDisconnection("Connection closed: $reason")
        }

        override fun onMessage(ws: WebSocket, text: String) {
            when (text) {
                "ACK" -> updateStats()
                "LAG" -> adjustQuality(-5)
                "OK" -> adjustQuality(2)
            }
        }
    }

    private fun startFrameProcessors() {
        scope.launch(Dispatchers.IO) {
            try {
                initializeMediaComponents()
                launch { decodeFrames() }
                launch { processFrameQueue() }
            } catch (e: Exception) {
                Log.e("FrameProcessor", "Error", e)
                updateDebugInfo("Error: ${e.message}")
            }
        }
    }

    private suspend fun initializeMediaComponents() {
        try {
            mediaExtractor = MediaExtractor().apply {
                assets.openFd(VIDEO_ASSET).use { afd ->
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                }
            }

            val videoTrack = (0 until (mediaExtractor?.trackCount ?: 0))
                .firstOrNull { i ->
                    mediaExtractor?.getTrackFormat(i)?.getString(MediaFormat.KEY_MIME)
                        ?.startsWith("video/") == true
                } ?: throw IOException("No video track")

            mediaExtractor?.selectTrack(videoTrack)
            val format = mediaExtractor?.getTrackFormat(videoTrack) ?: throw IOException("No format")

            mediaCodec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply {
                configure(format, videoSurface, null, 0)
                start()
            }
        } catch (e: Exception) {
            releaseMediaComponents()
            throw e
        }
    }

    private suspend fun decodeFrames() {
        val bufferInfo = MediaCodec.BufferInfo()
        var isEOS = false

        while (isStreaming.get() && !isEOS) {
            try {
                val codec = mediaCodec ?: break  // Exit if codec is null

                // Handle input buffers
                val inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: continue
                    val sampleSize = mediaExtractor?.readSampleData(inputBuffer, 0) ?: -1

                    when {
                        sampleSize < 0 -> {
                            // End of stream
                            codec.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            isEOS = true
                        }
                        else -> {
                            // Queue valid data
                            codec.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                sampleSize,
                                mediaExtractor?.sampleTime ?: 0,
                                0
                            )
                            mediaExtractor?.advance()
                        }
                    }
                }

                // Handle output buffers
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when (outputBufferIndex) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // Handle format change if needed
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // No output available yet
                    }
                    else -> if (outputBufferIndex >= 0) {
                        try {
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                isEOS = true
                            }
                            if (bufferInfo.size > 0) {
                                processDecodedFrame(outputBufferIndex, bufferInfo)
                            }
                            codec.releaseOutputBuffer(outputBufferIndex, videoSurface != null)
                        } catch (e: Exception) {
                            Log.e("Decode", "Error processing output buffer", e)
                        }
                    }
                }

                delay(1) // Prevent tight loop
            } catch (e: Exception) {
                Log.e("Decode", "Error in decode loop", e)
                break
            }
        }

        // Clean up resources if needed
        mediaCodec?.stop()
    }

    private fun processDecodedFrame(outputBufferIndex: Int, bufferInfo: MediaCodec.BufferInfo) {
        val frameData = FrameData(
            bitmap = captureFrameFromSurface(),
            frameId = frameCounter.incrementAndGet(),
            timestamp = System.currentTimeMillis()
        )

        if (!frameQueue.offer(frameData)) {
            frameQueue.poll() // Remove oldest frame if queue is full
            frameQueue.offer(frameData)
        }
    }

    private fun captureFrameFromSurface(): Bitmap {
        reusableCanvas.drawColor(Color.BLACK)
        val x = (System.currentTimeMillis() % reusableBitmap.width).toFloat()
        val y = (System.currentTimeMillis() % reusableBitmap.height).toFloat()
        reusableCanvas.drawCircle(x, y, 50f, paint)
        reusableCanvas.drawText("Frame: $frameCounter", 50f, 50f, paint)
        return reusableBitmap
    }

    private suspend fun processFrameQueue() {
        while (isStreaming.get()) {
            try {
                val frameData = withTimeoutOrNull(100) { frameQueue.poll() }
                frameData?.let {
                    if (isConnected.get()) {
                        sendFrameToWebSocket(it)
                    }
                }
            } catch (e: Exception) {
                Log.e("Queue", "Error", e)
            }
            delay(1) // Prevent tight loop
        }
    }

    private fun sendFrameToWebSocket(frameData: FrameData) {
        try {
            reusableStream.reset()
            frameData.bitmap.compress(Bitmap.CompressFormat.JPEG, currentQuality, reusableStream)
            val compressedFrame = compressFrame(reusableStream.toByteArray())

            if (compressedFrame.size <= MAX_FRAME_SIZE_BYTES) {
                webSocket?.send(ByteString.of(*compressedFrame))
                updateStats()
            } else {
                adjustQuality(-10)
            }
        } catch (e: Exception) {
            Log.e("WS", "Send error", e)
        }
    }

    private fun compressFrame(data: ByteArray): ByteArray {
        return ByteArrayOutputStream().use { baos ->
            Deflater(Deflater.BEST_SPEED).use { deflater ->
                deflater.setInput(data)
                deflater.finish()
                val buffer = ByteArray(1024)
                while (!deflater.finished()) {
                    val count = deflater.deflate(buffer)
                    baos.write(buffer, 0, count)
                }
            }
            baos.toByteArray()
        }
    }

    private fun updateStats() {
        val currentTime = System.currentTimeMillis()
        val elapsed = currentTime - lastFrameTime
        currentFps = if (elapsed > 0) (1000 / elapsed).coerceAtMost(INITIAL_FPS.toLong()).toInt() else 0
        lastFrameTime = currentTime
        updateDebugInfo("FPS: $currentFps | Quality: $currentQuality%")
    }

    private fun adjustQuality(delta: Int) {
        currentQuality = (currentQuality + delta).coerceIn(MIN_QUALITY, MAX_QUALITY)
        updateStats()
    }

    private fun handleDisconnection(reason: String) {
        isConnected.set(false)
        isStreaming.set(false)
        updateUI {
            updateConnectionStatus(reason, "#FFE60B0B")
            btnStartStream.isEnabled = false
            btnStopStream.isEnabled = false
        }

        scope.launch(Dispatchers.IO) {
            delay(2000)
            if (!isConnected.get()) {
                initializeConnection()
            }
        }
    }

    private fun releaseMediaComponents() {
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaExtractor?.release()
        } catch (e: Exception) {
            Log.e("Media", "Release error", e)
        } finally {
            mediaCodec = null
            mediaExtractor = null
        }
    }

    private fun getLocalIpAddress(): String? {
        return try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses) }
                .firstOrNull { it is Inet4Address }?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    private fun updateUI(action: () -> Unit) {
        if (!isFinishing && !isDestroyed) {
            runOnUiThread(action)
        }
    }

    private fun updateConnectionStatus(message: String, color: String? = null) {
        connectionStatus.text = message
        color?.let { connectionStatus.setTextColor(Color.parseColor(it)) }
    }

    private fun updateDebugInfo(message: String) {
        debugInfo.text = if (message.length <= DEBUG_INFO_MAX_LENGTH) {
            message
        } else {
            "${message.take(DEBUG_INFO_MAX_LENGTH/2)}...${message.takeLast(DEBUG_INFO_MAX_LENGTH/2)}"
        }
    }

    override fun onPause() {
        super.onPause()
        stopStreaming()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        stopStreaming()
        webSocket?.close(10000, "Activity destroyed")
        okHttpClient.dispatcher.executorService.shutdown()
        releaseMediaComponents()
        reusableBitmap.recycle()
    }
}