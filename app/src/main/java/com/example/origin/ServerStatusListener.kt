package com.example.origin

import android.graphics.Color

// Interface for callbacks from the WebSocket server to the UI (MainActivity)
interface ServerStatusListener {
    fun sendStatusUpdate(message: String, color: Int, isRunning: Boolean)
    fun sendDebugInfo(message: String)
}
