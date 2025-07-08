package com.example.origin

import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import java.net.InetSocketAddress
import androidx.media3.common.PlaybackException

class MainActivity : AppCompatActivity() {
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var serverSocket: ServerSocket
    private var clientSocket: Socket? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var playerView: PlayerView
    private lateinit var btnPlay: Button
    private lateinit var btnPause: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.player_view)
        btnPlay = findViewById(R.id.btnPlay)
        btnPause = findViewById(R.id.btnPause)

        initializePlayer()
        startMirroringServer()
    }

    // In OriginActivity's initializePlayer()
    private fun initializePlayer() {
        exoPlayer = ExoPlayer.Builder(this).build()
        playerView.player = exoPlayer

        try {
            val assetFileDescriptor = assets.openFd("video.mp4")
            val mediaItem = MediaItem.fromUri("asset:///video.mp4")
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.play() // Auto-start playback
            assetFileDescriptor.close()
        } catch (e: Exception) {
            Log.e("Origin", "Video initialization failed", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.KITKAT)
    private fun startFrameExtraction() {
        coroutineScope.launch {
            val imageReader = ImageReader.newInstance(
                playerView.width.coerceAtLeast(1),
                playerView.height.coerceAtLeast(1),
                ImageFormat.YUV_420_888,
                2
            )

            imageReader.setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireLatestImage()
                    image?.use { processAndSendFrame(it) }
                } catch (e: Exception) {
                    Log.e("Origin", "Image processing error", e)
                }
            }, null)

            while (exoPlayer.isPlaying) {
                delay(33) // ~30fps
            }

            imageReader.setOnImageAvailableListener(null, null)
            imageReader.close()
        }
    }
    // Scan local network for Origin device
    fun findOriginIp(): String? {
        val prefix = "172.20.5.245"
        return (1..254).firstOrNull { i ->
            try {
                Socket().apply {
                    connect(InetSocketAddress("$prefix$i", 8080), 500)
                }.close()
                true
            } catch (e: Exception) { false }
        }?.let { "$prefix$it" }
    }
    private fun processAndSendFrame(image: Image): Boolean {
        return try {
            val bitmap = image.toBitmap()
            ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                val byteArray = stream.toByteArray()

                clientSocket?.getOutputStream()?.let { output ->
                    with(output) {
                        write(byteArray.size shr 24 and 0xFF)
                        write(byteArray.size shr 16 and 0xFF)
                        write(byteArray.size shr 8 and 0xFF)
                        write(byteArray.size and 0xFF)
                        write(byteArray)
                        flush()
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e("Origin", "Frame processing error", e)
            false
        }
    }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes[0]
        val buffer = plane.buffer
        val width = this.width
        val height = this.height

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    private fun startMirroringServer() {
        coroutineScope.launch {
            try {
                serverSocket = ServerSocket(8080)
                Log.d("Origin", "Server started on port 8080")

                clientSocket = serverSocket.accept()
                Log.d("Origin", "Client connected")

                withContext(Dispatchers.Main) {
                    btnPlay.setOnClickListener {
                        exoPlayer.play()
                        sendControlCommand("PLAY")
                    }

                    btnPause.setOnClickListener {
                        exoPlayer.pause()
                        sendControlCommand("PAUSE")
                    }
                }
            } catch (e: IOException) {
                Log.e("Origin", "Server error", e)
            }
        }
    }

    private fun sendControlCommand(command: String) {
        coroutineScope.launch {
            try {
                clientSocket?.getOutputStream()?.write("CMD:$command".toByteArray())
            } catch (e: IOException) {
                Log.e("Origin", "Command send failed", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel() // Cancel all coroutines
        try {
            clientSocket?.close()
            serverSocket.close()
        } catch (e: IOException) {
            Log.e("Origin", "Socket close error", e)
        }
        exoPlayer.release()
    }
}