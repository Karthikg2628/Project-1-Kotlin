package com.example.origin

import android.content.Context
import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import android.graphics.Color // Needed for Color constants

// This WebSocket server runs on the Origin app to send video frames and commands to connected Remote apps.
class VideoWebSocketServer(port: Int, private val context: Context) : WebSocketServer(InetSocketAddress(port)) {

    // Store connected clients to send data to all of them
    private val connectedClients = CopyOnWriteArrayList<WebSocket>()

    companion object {
        private const val TAG = "VideoWSServer"
    }

    /**
     * Checks if there is at least one active WebSocket connection.
     * This property is used by StreamingForegroundService to decide whether to send frames.
     */
    val isOpen: Boolean
        get() = !connectedClients.isEmpty()

    override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
        conn?.let {
            connectedClients.add(it)
            Log.i(TAG, "New client connected: ${it.remoteSocketAddress}. Total clients: ${connectedClients.size}")
            // Send status update via LocalBroadcastManager through the context (which is the Service)
            (context as? StreamingForegroundService)?.sendStatusUpdate("Client Connected: ${it.remoteSocketAddress}", Color.BLUE, true)
        }
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        conn?.let {
            connectedClients.remove(it)
            Log.i(TAG, "Client disconnected: ${it.remoteSocketAddress}. Reason: $reason (Code: $code). Remaining clients: ${connectedClients.size}")
            // Send status update via LocalBroadcastManager through the context (which is the Service)
            (context as? StreamingForegroundService)?.sendStatusUpdate("Client Disconnected: ${it.remoteSocketAddress}", Color.RED, true)
            if (connectedClients.isEmpty()) {
                (context as? StreamingForegroundService)?.sendStatusUpdate("No clients connected.", Color.GRAY, true)
            }
        }
    }

    override fun onMessage(conn: WebSocket?, message: String?) {
        message?.let {
            Log.d(TAG, "Received message from client ${conn?.remoteSocketAddress}: $it")
            // Send debug info via LocalBroadcastManager through the context (which is the Service)
            (context as? StreamingForegroundService)?.sendDebugInfo("Remote message: $it")
        }
    }

    override fun onMessage(conn: WebSocket?, message: ByteBuffer?) {
        message?.let {
            Log.d(TAG, "Received binary message from client ${conn?.remoteSocketAddress}, size: ${it.remaining()} bytes")
            // Send debug info via LocalBroadcastManager through the context (which is the Service)
            (context as? StreamingForegroundService)?.sendDebugInfo("Received binary message, size: ${it.remaining()} bytes")
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        val errorMessage = ex?.message ?: "Unknown server error"
        Log.e(TAG, "Server error: ${conn?.remoteSocketAddress}: $errorMessage", ex)
        // Send status update via LocalBroadcastManager through the context (which is the Service)
        (context as? StreamingForegroundService)?.sendStatusUpdate("Server Error: ${errorMessage}", Color.RED, true)
    }

    override fun onStart() {
        Log.i(TAG, "WebSocket Server started on port ${port}")
        // No UI update here, as it's done in StreamingForegroundService once all setup is complete
    }

    // Method to send video frames (byte arrays) to all connected clients
    fun sendFrameBytes(frameBytes: ByteArray) {
        if (connectedClients.isNotEmpty()) {
            val buffer = ByteBuffer.wrap(frameBytes)
            connectedClients.forEach { client ->
                if (client.isOpen) { // This checks the individual client's connection state
                    client.send(buffer)
                }
            }
        }
    }

    // Method to send playback commands (text messages) to all connected clients
    fun sendPlaybackCommand(command: PlaybackCommand, startTimeMillis: Long = 0L, targetFps: Int = 0) {
        val message = when (command) {
            PlaybackCommand.PLAY -> "PLAY:$startTimeMillis:$targetFps"
            PlaybackCommand.PAUSE -> "PAUSE"
            PlaybackCommand.STOP -> "STOP"
        }
        if (connectedClients.isNotEmpty()) {
            connectedClients.forEach { client ->
                if (client.isOpen) { // This checks the individual client's connection state
                    client.send(message)
                }
            }
            Log.d(TAG, "Sent command to clients: $message")
        } else {
            Log.d(TAG, "No clients to send command to: $message")
        }
    }

    // Method to stop the WebSocket server
    fun stopServer() {
        // Removed the problematic isStopping() and isStopped() checks as a workaround.
        // The underlying stop() method of WebSocketServer can generally handle being called
        // even if the server is already in a stopping or stopped state.
        Log.i(TAG, "Stopping WebSocket Server...")
        try {
            // Close all connected clients first
            connectedClients.forEach { it.close() }
            connectedClients.clear()
            // Then stop the server itself
            this.stop()
            Log.i(TAG, "WebSocket Server stopped.")
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping WebSocket Server: ${e.message}", e)
            Thread.currentThread().interrupt() // Restore interrupt status
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during server stop: ${e.message}", e)
        }
    }
}
