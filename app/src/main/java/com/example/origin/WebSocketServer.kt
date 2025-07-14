// app/src/main/java/com/example/origin/WebSocketServer.kt

import org.java_websocket.server.WebSocketServer
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import android.util.Log
import java.net.InetSocketAddress
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.io.ByteArrayOutputStream
import android.graphics.*
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.zip.Deflater
import kotlin.math.max
import kotlin.math.min

class VideoWebSocketServer(
    private val port: Int = 8080,
    private val defaultWidth: Int = 1280,
    private val defaultHeight: Int = 720,
    private val maxFps: Int = 25,
    private val minFps: Int = 15,
    private val maxBandwidthKbps: Int = 1024
) : WebSocketServer(InetSocketAddress(port)) {

    // Thread-safe collections
    private val clients = CopyOnWriteArraySet<WebSocket>()
    private val pausedClients = CopyOnWriteArraySet<WebSocket>()
    private val clientStats = ConcurrentHashMap<WebSocket, ClientStats>()

    // Frame control
    @Volatile
    private var frameIntervalMs = 1000 / maxFps
    private val lagCount = AtomicInteger(0)
    @Volatile
    private var currentMaxBandwidth = maxBandwidthKbps * 1024
    @Volatile
    private var currentQuality = 80
    private var frameCounter = AtomicInteger(0)

    // Reusable objects
    @Volatile
    private var reusableBitmap: Bitmap = Bitmap.createBitmap(defaultWidth, defaultHeight, Bitmap.Config.RGB_565)
    private val reusableCanvas = Canvas(reusableBitmap)
    private val paint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        textSize = 48f
    }

    // Executors
    private val frameExecutor = Executors.newSingleThreadScheduledExecutor()
    private val healthExecutor = Executors.newSingleThreadScheduledExecutor()
    private val statsExecutor = Executors.newSingleThreadScheduledExecutor()

    // Statistics
    private var totalFramesSent = 0L
    private var totalBytesSent = 0L
    private val bandwidthWindow = ConcurrentLinkedDeque<Long>()

    data class ClientStats(
        @Volatile var lastAckTime: Long = System.currentTimeMillis(),
        @Volatile var framesReceived: Int = 0,
        @Volatile var lagReports: Int = 0,
        @Volatile var bandwidthUsage: Long = 0
    )

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        clients.add(conn)
        clientStats[conn] = ClientStats()
        Log.d("WS", "New connection: ${conn.remoteSocketAddress}. Total: ${clients.size}")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        clients.remove(conn)
        pausedClients.remove(conn)
        clientStats.remove(conn)
        Log.d("WS", "Closed: ${conn.remoteSocketAddress}. Reason: $reason")
    }

    override fun onMessage(conn: WebSocket, message: String) {
        when {
            message.startsWith("LAG") -> handleLagSignal(conn)
            message.startsWith("ACK") -> handleAckSignal(conn)
            message == "PAUSE" -> pauseSending(conn)
            message == "RESUME" -> resumeSending(conn)
            message.startsWith("BANDWIDTH") -> adjustBandwidth(message)
            else -> Log.w("WS", "Unknown message: $message")
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e("WS", "Error ${conn?.remoteSocketAddress}", ex)
        conn?.close()
    }

    override fun onStart() {
        Log.i("WS", "Server started on port $port")
        startFrameSending()
        startHealthChecks()
        startStatsLogging()
    }

    fun stopServer() {
        try {
            frameExecutor.shutdownNow()
            healthExecutor.shutdownNow()
            statsExecutor.shutdownNow()

            clients.forEach { it.close(1000, "Server shutdown") }
            stop(1000)

            if (!reusableBitmap.isRecycled) {
                reusableBitmap.recycle()
            }
            Log.i("WS", "Server stopped. Sent $totalFramesSent frames (${totalBytesSent/1024}KB)")
        } catch (e: Exception) {
            Log.e("WS", "Shutdown error", e)
        }
    }

    private fun startFrameSending() {
        frameExecutor.scheduleWithFixedDelay({
            try {
                if (shouldThrottle()) {
                    Log.w("WS", "Throttling due to bandwidth limits")
                    return@scheduleWithFixedDelay
                }

                val frame = generateFrame()
                sendToAllClients(frame)
                updateBandwidthStats(frame.size)
            } catch (e: Exception) {
                Log.e("WS", "Frame generation error", e)
            }
        }, 0, frameIntervalMs.toLong(), TimeUnit.MILLISECONDS)
    }

    private fun generateFrame(): ByteArray {
        val frameNum = frameCounter.incrementAndGet()

        // Draw test pattern (replace with real frame data for production)
        reusableCanvas.drawColor(Color.BLACK)
        val x = (System.currentTimeMillis() % defaultWidth).toFloat()
        val y = (System.currentTimeMillis() % defaultHeight).toFloat()
        reusableCanvas.drawCircle(x, y, 50f, paint)

        // Add debug info
        reusableCanvas.drawText("Frame: $frameNum", 50f, 50f, paint)
        reusableCanvas.drawText("FPS: ${1000/frameIntervalMs}", 50f, 100f, paint)
        reusableCanvas.drawText("Clients: ${clients.size}", 50f, 150f, paint)

        return compressFrameToJpeg(quality = currentQuality)
    }

    private fun compressFrameToJpeg(quality: Int): ByteArray {
        return ByteArrayOutputStream().use { baos ->
            if (!reusableBitmap.isRecycled) {
                reusableBitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            }
            val compressed = baos.toByteArray()

            // Further compress if needed
            if (compressed.size > 100 * 1024) {
                return@use deflateData(compressed)
            }
            return@use compressed
        }
    }

    private fun deflateData(data: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream(data.size / 2)
        val deflater = Deflater(Deflater.BEST_SPEED)
        try {
            deflater.setInput(data)
            deflater.finish()
            val buffer = ByteArray(1024)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                baos.write(buffer, 0, count)
            }
            return baos.toByteArray()
        } finally {
            deflater.end()
            baos.close()
        }
    }

    private fun sendToAllClients(frame: ByteArray) {
        if (frame.isEmpty()) return

        clients.forEach { client ->
            if (client.isOpen && !pausedClients.contains(client)) {
                try {
                    client.send(frame)
                    clientStats[client]?.bandwidthUsage = clientStats[client]?.bandwidthUsage?.plus(frame.size) ?: 0
                    totalFramesSent++
                    totalBytesSent += frame.size
                } catch (e: Exception) {
                    Log.e("WS", "Send failed to ${client.remoteSocketAddress}", e)
                    clients.remove(client)
                    pausedClients.remove(client)
                    clientStats.remove(client)
                    client.close()
                }
            }
        }
    }

    private fun startHealthChecks() {
        healthExecutor.scheduleAtFixedRate({
            val now = System.currentTimeMillis()
            clients.removeAll { conn ->
                try {
                    conn.sendPing()
                    val stats = clientStats[conn]
                    if (stats != null && now - stats.lastAckTime > 15000) {
                        Log.w("WS", "No ACK from ${conn.remoteSocketAddress} in 15s")
                        true
                    } else {
                        false
                    }
                } catch (e: Exception) {
                    true
                }
            }
        }, 5, 5, TimeUnit.SECONDS)
    }

    private fun startStatsLogging() {
        statsExecutor.scheduleAtFixedRate({
            Log.i("STATS", """
                Clients: ${clients.size}
                FPS: ${1000/frameIntervalMs}
                Quality: $currentQuality%
                Bandwidth: ${currentMaxBandwidth/1024}KB/s
                Total Frames: $totalFramesSent
                Total Data: ${totalBytesSent/1024}KB
            """.trimIndent())
        }, 1, 1, TimeUnit.MINUTES)
    }

    private fun shouldThrottle(): Boolean {
        val currentUsage = synchronized(bandwidthWindow) { bandwidthWindow.sum() }
        return currentUsage > currentMaxBandwidth
    }

    private fun updateBandwidthStats(frameSize: Int) {
        synchronized(bandwidthWindow) {
            bandwidthWindow.addLast(frameSize.toLong() * clients.size)
            if (bandwidthWindow.size > 10) {
                bandwidthWindow.removeFirst()
            }
        }
    }

    private fun handleLagSignal(client: WebSocket) {
        clientStats[client]?.lagReports = clientStats[client]?.lagReports?.plus(1) ?: 1
        val lagCount = clientStats.values.sumOf { it.lagReports }

        if (lagCount > clients.size / 3) {
            adjustFrameRate(1000 / minFps)
            currentQuality = max(50, currentQuality - 10)
            Log.w("QOS", "Reducing quality to $currentQuality% due to lag reports")
        }
    }

    private fun handleAckSignal(client: WebSocket) {
        val stats = clientStats[client] ?: return
        stats.lastAckTime = System.currentTimeMillis()
        stats.framesReceived++

        if (stats.lagReports == 0 && currentQuality < 80) {
            currentQuality = min(80, currentQuality + 5)
        }
    }

    private fun adjustFrameRate(newIntervalMs: Int) {
        if (frameIntervalMs != newIntervalMs) {
            frameIntervalMs = newIntervalMs
            Log.i("QOS", "Adjusted frame interval to ${frameIntervalMs}ms (${1000/frameIntervalMs} FPS)")
        }
    }

    private fun adjustBandwidth(message: String) {
        try {
            val requested = message.substringAfter("BANDWIDTH").trim().toInt()
            currentMaxBandwidth = when {
                requested <= 0 -> 256 * 1024
                requested > 8192 -> 8192 * 1024
                else -> requested * 1024
            }
            Log.i("QOS", "Adjusted bandwidth to ${currentMaxBandwidth/1024}KB/s")
        } catch (e: Exception) {
            Log.e("WS", "Invalid BANDWIDTH message: $message")
        }
    }

    private fun pauseSending(client: WebSocket) {
        pausedClients.add(client)
        Log.d("WS", "Paused sending to ${client.remoteSocketAddress}")
    }

    private fun resumeSending(client: WebSocket) {
        pausedClients.remove(client)
        Log.d("WS", "Resumed sending to ${client.remoteSocketAddress}")
    }

    private fun recreateBitmapIfNeeded(width: Int, height: Int) {
        if ((reusableBitmap.width != width || reusableBitmap.height != height) && !reusableBitmap.isRecycled) {
            reusableBitmap.recycle()
            reusableBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            reusableCanvas.setBitmap(reusableBitmap)
            Log.i("WS", "Recreated bitmap: ${width}x$height")
        }
    }
}