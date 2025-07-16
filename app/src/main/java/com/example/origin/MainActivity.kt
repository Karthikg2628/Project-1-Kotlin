// MainActivity.kt (Origin App)
package com.example.originapp

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.origin.R
import java.io.IOException
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity(), WebSocketClientManager.WebSocketConnectionListener {

    private lateinit var textViewFileName: TextView
    private lateinit var textViewStatus: TextView
    private lateinit var buttonSelectMp4: Button
    private lateinit var buttonStream: Button
    private lateinit var buttonStopStream: Button
    private lateinit var editTextRemoteIp: TextView // Consider EditText for input

    private var selectedMp4Uri: Uri? = null
    private lateinit var wsClient: WebSocketClientManager
    private var mediaStreamer: MediaStreamer? = null

    // Activity Result API for file selection
    private val pickMp4File = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            selectedMp4Uri = uri
            textViewFileName.text = "Selected: ${getFileName(uri)}"
            buttonStream.isEnabled = true // Enable stream button once file is selected
        } else {
            textViewFileName.text = "No file selected"
            buttonStream.isEnabled = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // You'll create this layout

        textViewFileName = findViewById(R.id.textViewFileName)
        textViewStatus = findViewById(R.id.textViewStatus)
        buttonSelectMp4 = findViewById(R.id.buttonSelectMp4)
        buttonStream = findViewById(R.id.buttonStream)
        buttonStopStream = findViewById(R.id.buttonStopStream)
        editTextRemoteIp = findViewById(R.id.editTextRemoteIp) // Assuming ID

        buttonStream.isEnabled = false // Disable initially
        buttonStopStream.isEnabled = false

        buttonSelectMp4.setOnClickListener {
            pickMp4File.launch("video/mp4")
        }

        buttonStream.setOnClickListener {
            selectedMp4Uri?.let { uri ->
                val ipAddress = editTextRemoteIp.text.toString().trim()
                if (ipAddress.isBlank()) {
                    Toast.makeText(this, "Please enter Remote IP address", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                startStreaming(uri, ipAddress)
            } ?: run {
                Toast.makeText(this, "Please select an MP4 file first", Toast.LENGTH_SHORT).show()
            }
        }

        buttonStopStream.setOnClickListener {
            stopStreaming()
        }

        // Initialize WebSocket client (can be done earlier, but connect on stream start)
        // For demonstration, let's assume a default IP for testing or get it from EditText
        // wsClient = WebSocketClientManager("192.168.1.100", 8080, this)
    }

    private fun startStreaming(mp4Uri: Uri, remoteIp: String) {
        if (mediaStreamer != null && mediaStreamer?.isStreaming?.get() == true) {
            Toast.makeText(this, "Already streaming!", Toast.LENGTH_SHORT).show()
            return
        }

        wsClient = WebSocketClientManager(remoteIp, 8080, this) // Port 8080 assumed for Remote
        wsClient.connect()
        textViewStatus.text = "Connecting to Remote..."
        buttonStream.isEnabled = false
        buttonStopStream.isEnabled = true

        // MediaStreamer will be started on WebSocket connection `onConnected`
    }

    private fun stopStreaming() {
        mediaStreamer?.stopStreaming()
        wsClient.disconnect()
        textViewStatus.text = "Stream Stopped."
        buttonStream.isEnabled = true
        buttonStopStream.isEnabled = false
    }

    // Helper to get file name from Uri
    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        result = cursor.getString(nameIndex)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: "Unknown File"
    }

    // --- WebSocketConnectionListener implementation ---
    override fun onConnected() {
        runOnUiThread {
            textViewStatus.text = "Connected to Remote."
            // Start streaming only after WebSocket is connected
            selectedMp4Uri?.let { uri ->
                mediaStreamer = MediaStreamer(applicationContext, uri, wsClient)
                mediaStreamer?.startStreaming()
                Toast.makeText(this, "Streaming started!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDisconnected(code: Int, reason: String) {
        runOnUiThread {
            textViewStatus.text = "Disconnected: $reason (Code: $code)"
            buttonStream.isEnabled = true
            buttonStopStream.isEnabled = false
            mediaStreamer?.stopStreaming() // Ensure streamer also stops
            mediaStreamer = null
        }
    }

    override fun onMessage(text: String) {
        Log.d("OriginApp", "Received text from Remote: $text")
        // Handle any messages from Remote if necessary
    }

    override fun onMessage(bytes: ByteBuffer) {
        Log.d("OriginApp", "Received bytes from Remote: ${bytes.remaining()} bytes")
        // Handle any binary data from Remote if necessary
    }

    override fun onFailure(t: Throwable, response: okhttp3.Response?) {
        runOnUiThread {
            textViewStatus.text = "Connection Failed: ${t.message}"
            Log.e("OriginApp", "WebSocket connection failed", t)
            buttonStream.isEnabled = true
            buttonStopStream.isEnabled = false
            mediaStreamer?.stopStreaming()
            mediaStreamer = null
        }
    }
}