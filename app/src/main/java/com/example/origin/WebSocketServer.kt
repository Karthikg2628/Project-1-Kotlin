package com.example.origin

import android.util.Log
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

// This class manages the WebSocket client connection for the origin app.
// It sends media data (formats, CSD, and actual frames) to the remote app.
class WSClient(private val remoteIp: String, private val listener: WSClientListener) {

    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null

    // Listener interface for WebSocket client events and data reception
    interface WSClientListener {
        fun onConnected()
        fun onDisconnected(code: Int, reason: String)
        fun onMessage(text: String)
        fun onMessage(bytes: ByteString)
        fun onFailure(t: Throwable, response: Response?)
    }

    /**
     * Connects to the WebSocket server at the specified IP and port.
     */
    fun connect() {
        if (webSocket != null) {
            Log.w(TAG, "WebSocket is already connected or connecting.")
            return
        }

        client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS) // Disable read timeout for streaming
            .pingInterval(30, TimeUnit.SECONDS) // Send pings to keep connection alive
            .build()

        // IMPORTANT: Use ws:// for unencrypted local connections.
        // If you intend to use wss://, you'll need to handle SSL/TLS certificates.
        val request = Request.Builder()
            .url("ws://$remoteIp:8080") // Construct WebSocket URL
            .build()

        Log.d(TAG, "Attempting to connect to WebSocket: ${request.url}")

        webSocket = client?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected to ${response.request.url}")
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received text message: $text")
                listener.onMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d(TAG, "Received binary message, size: ${bytes.size}")
                listener.onMessage(bytes)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code / $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code / $reason")
                this@WSClient.webSocket = null // Clear reference
                listener.onDisconnected(code, reason) // Pass code and reason
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                listener.onFailure(t, response)
                this@WSClient.webSocket = null // Clear reference on failure
            }
        })
    }

    /**
     * Disconnects the WebSocket connection.
     */
    fun disconnect() {
        webSocket?.close(1000, "App closing") // Close with normal closure code
        webSocket = null
        client?.dispatcher?.executorService?.shutdown() // Shut down OkHttp's thread pool
        Log.d(TAG, "WebSocket disconnected.")
    }

    /**
     * Sends a text message over the WebSocket.
     */
    fun sendMessage(message: String) {
        webSocket?.send(message)
        Log.d(TAG, "Sent text message: $message")
    }

    /**
     * Sends binary data over the WebSocket.
     * The first byte of the ByteBuffer should indicate the packet type (e.g., video frame, CSD).
     */
    fun sendBinaryMessage(data: ByteBuffer) {
        // Ensure the buffer is ready for reading (position 0, limit at current data end)
        // This is handled by .flip() in the calling methods, but good to ensure here too.
        data.rewind() // Make sure we start reading from the beginning
        webSocket?.send(data.toByteString()) // Convert ByteBuffer to ByteString for sending
        // Log.d(TAG, "Sent binary message, size: ${data.remaining()}") // Avoid excessive logging in high-throughput loops
    }

    /**
     * Sends video MediaFormat data.
     * Format: PACKET_TYPE_VIDEO_FORMAT_DATA | mimeLength (int) | mime (bytes) | width (int) | height (int) | frameRate (int)
     */
    fun sendVideoFormat(mime: String, width: Int, height: Int, frameRate: Int) {
        val mimeBytes = mime.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(1 + 4 + mimeBytes.size + 4 + 4 + 4) // Type + mimeLength + mime + width + height + frameRate
        buffer.put(Constants.PACKET_TYPE_VIDEO_FORMAT_DATA)
        buffer.putInt(mimeBytes.size)
        buffer.put(mimeBytes)
        buffer.putInt(width)
        buffer.putInt(height)
        buffer.putInt(frameRate)
        buffer.flip() // Prepare for reading
        sendBinaryMessage(buffer)
        Log.d(TAG, "Sent video format: $mime, ${width}x${height}, $frameRate fps")
    }

    /**
     * Sends audio MediaFormat data.
     * Format: PACKET_TYPE_AUDIO_FORMAT_DATA | mimeLength (int) | mime (bytes) | sampleRate (int) | channelCount (int)
     */
    fun sendAudioFormat(mime: String, sampleRate: Int, channelCount: Int) {
        val mimeBytes = mime.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(1 + 4 + mimeBytes.size + 4 + 4) // Type + mimeLength + mime + sampleRate + channelCount
        buffer.put(Constants.PACKET_TYPE_AUDIO_FORMAT_DATA)
        buffer.putInt(mimeBytes.size)
        buffer.put(mimeBytes)
        buffer.putInt(sampleRate)
        buffer.putInt(channelCount)
        buffer.flip() // Prepare for reading
        sendBinaryMessage(buffer)
        Log.d(TAG, "Sent audio format: $mime, $sampleRate Hz, $channelCount channels")
    }

    /**
     * Sends video Codec Specific Data (CSD).
     * Format: PACKET_TYPE_VIDEO_CSD | csdDataLength (int) | csdData (bytes)
     * CRITICAL FIX: Added csdDataLength (4 bytes)
     */
    fun sendVideoCSD(csdData: ByteBuffer) {
        val dataLength = csdData.remaining()
        val buffer = ByteBuffer.allocate(1 + 4 + dataLength) // Type + CSD Data Length + CSD data
        buffer.put(Constants.PACKET_TYPE_VIDEO_CSD)
        buffer.putInt(dataLength) // CRITICAL: Put the length of the CSD data
        buffer.put(csdData)
        buffer.flip()
        sendBinaryMessage(buffer)
        Log.d(TAG, "Sent video CSD, size: $dataLength")
    }

    /**
     * Sends audio Codec Specific Data (CSD).
     * Format: PACKET_TYPE_AUDIO_CSD | csdDataLength (int) | csdData (bytes)
     * CRITICAL FIX: Added csdDataLength (4 bytes)
     */
    fun sendAudioCSD(csdData: ByteBuffer) {
        val dataLength = csdData.remaining()
        val buffer = ByteBuffer.allocate(1 + 4 + dataLength) // Type + CSD Data Length + CSD data
        buffer.put(Constants.PACKET_TYPE_AUDIO_CSD)
        buffer.putInt(dataLength) // CRITICAL: Put the length of the CSD data
        buffer.put(csdData)
        buffer.flip()
        sendBinaryMessage(buffer)
        Log.d(TAG, "Sent audio CSD, size: $dataLength")
    }

    /**
     * Sends a video frame.
     * Format: PACKET_TYPE_VIDEO | presentationTimeUs (long) | frameData (bytes)
     */
    fun sendVideoFrame(frameData: ByteBuffer, presentationTimeUs: Long) {
        val buffer = ByteBuffer.allocate(1 + 8 + frameData.remaining()) // Type + PTS + frame data
        buffer.put(Constants.PACKET_TYPE_VIDEO)
        buffer.putLong(presentationTimeUs)
        buffer.put(frameData)
        buffer.flip()
        sendBinaryMessage(buffer)
        // Log.d(TAG, "Sent video frame, size: ${frameData.remaining()}, pts: $presentationTimeUs")
    }

    /**
     * Sends an audio frame.
     * Format: PACKET_TYPE_AUDIO | presentationTimeUs (long) | frameData (bytes)
     */
    fun sendAudioFrame(frameData: ByteBuffer, presentationTimeUs: Long) {
        val buffer = ByteBuffer.allocate(1 + 8 + frameData.remaining()) // Type + PTS + frame data
        buffer.put(Constants.PACKET_TYPE_AUDIO)
        buffer.putLong(presentationTimeUs)
        buffer.put(frameData)
        buffer.flip()
        sendBinaryMessage(buffer)
        // Log.d(TAG, "Sent audio frame, size: ${frameData.remaining()}, pts: $presentationTimeUs")
    }

    companion object {
        private const val TAG = "WSClient"
    }
}