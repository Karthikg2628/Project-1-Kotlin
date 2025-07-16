// WebSocketClientManager.kt (Origin App)
package com.example.origin // Ensure this matches your manifest package

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString // Make sure this import is present
import okio.ByteString.Companion.toByteString
 // <--- ADD THIS IMPORT for the extension function
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

class WebSocketClientManager(
    private val remoteIp: String,
    private val remotePort: Int,
    private val listener: WebSocketConnectionListener?
) {

    private var client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // Disable read timeout for streaming
        .build()
    private var webSocket: WebSocket? = null

    // This property will track if the WebSocket is currently connected.
    fun isConnected(): Boolean {
        return webSocket != null && webSocket!!.send("") // Send a dummy message to check if connected, or maintain a state variable
        // A more robust way is to manage connection state via onOpen/onClosed/onFailure callbacks
        // For simplicity, we'll assume if webSocket is not null and hasn't explicitly closed, it's 'connected' for this check.
        // Or, you can add a private var isCurrentlyConnected = AtomicBoolean(false) and update it in callbacks.
    }


    interface WebSocketConnectionListener {
        fun onConnected()
        fun onDisconnected(code: Int, reason: String)
        fun onMessage(text: String)
        fun onMessage(bytes: ByteBuffer)
        fun onFailure(t: Throwable, response: Response?)
    }

    fun connect() {
        val request = Request.Builder().url("ws://$remoteIp:$remotePort").build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Connected to Remote")
                // (Optional) If you added an AtomicBoolean for connection state:
                // isCurrentlyConnected.set(true)
                listener?.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received text: $text")
                listener?.onMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d(TAG, "Received bytes: ${bytes.size}")
                listener?.onMessage(bytes.asByteBuffer())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Closing: $code / $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Closed: $code / $reason")
                // (Optional) If you added an AtomicBoolean for connection state:
                // isCurrentlyConnected.set(false)
                listener?.onDisconnected(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Failure: ${t.message}", t)
                // (Optional) If you added an AtomicBoolean for connection state:
                // isCurrentlyConnected.set(false)
                listener?.onFailure(t, response)
            }
        })
    }

    fun sendMessage(message: String) {
        webSocket?.send(message)
    }

    fun sendBytes(bytes: ByteBuffer) {
        // --- CORRECTED LINE ---
        // Use the toByteString() extension function directly on the ByteBuffer
        webSocket?.send(bytes.toByteString())
        // --- END CORRECTED LINE ---
    }

    fun disconnect() {
        webSocket?.close(NORMAL_CLOSURE_STATUS, "User initiated disconnect")
        client.dispatcher.executorService.shutdown()
        webSocket = null
        // (Optional) If you added an AtomicBoolean for connection state:
        // isCurrentlyConnected.set(false)
    }

    companion object {
        private const val TAG = "WSClient"
        private const val NORMAL_CLOSURE_STATUS = 1000

        // Define control commands
        const val COMMAND_START_STREAMING = "START_STREAM"
        const val COMMAND_STOP_STREAMING = "STOP_STREAM"
    }
}