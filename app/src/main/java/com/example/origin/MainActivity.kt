package com.example.origin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.graphics.Color
import android.widget.EditText // Ensure this is imported for EditText type

// MainActivity now implements ServerStatusListener
class MainActivity : AppCompatActivity(), ServerStatusListener {

    private lateinit var textViewFileName: TextView
    private lateinit var textViewStatus: TextView
    private lateinit var buttonSelectMp4: Button
    private lateinit var buttonStream: Button
    private lateinit var buttonStopStream: Button
    private lateinit var editTextRemoteIp: EditText // Confirmed as EditText in XML

    private var selectedMp4Uri: Uri? = null

    private val statusUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                when (it.action) {
                    StreamingForegroundService.ACTION_STATUS_UPDATE -> {
                        val message = it.getStringExtra(StreamingForegroundService.EXTRA_MESSAGE) ?: "Unknown Status"
                        val color = it.getIntExtra(StreamingForegroundService.EXTRA_COLOR, Color.BLACK)
                        val isRunning = it.getBooleanExtra(StreamingForegroundService.EXTRA_IS_RUNNING, false)
                        sendStatusUpdate(message, color, isRunning)
                    }
                    StreamingForegroundService.ACTION_DEBUG_INFO -> {
                        val message = it.getStringExtra(StreamingForegroundService.EXTRA_MESSAGE) ?: "No debug info"
                        sendDebugInfo(message)
                    }
                }
            }
        }
    }

    private val pickMp4File = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            selectedMp4Uri = uri
            textViewFileName.text = "Selected: ${getFileName(uri)}"
            buttonStream.isEnabled = true
        } else {
            textViewFileName.text = "No file selected"
            buttonStream.isEnabled = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textViewFileName = findViewById(R.id.textViewFileName)
        textViewStatus = findViewById(R.id.textViewStatus)
        buttonSelectMp4 = findViewById(R.id.buttonSelectMp4)
        buttonStream = findViewById(R.id.buttonStream)
        buttonStopStream = findViewById(R.id.buttonStopStream)
        editTextRemoteIp = findViewById(R.id.editTextRemoteIp) // Now casted as EditText

        buttonStream.isEnabled = false
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

        val filter = IntentFilter().apply {
            addAction(StreamingForegroundService.ACTION_STATUS_UPDATE)
            addAction(StreamingForegroundService.ACTION_DEBUG_INFO)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(statusUpdateReceiver, filter)

        textViewStatus.text = "Status: Ready to select MP4"
        textViewStatus.setTextColor(Color.BLACK)
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusUpdateReceiver)
    }

    private fun startStreaming(mp4Uri: Uri, remoteIp: String) {
        StreamingForegroundService.start(this, mp4Uri, remoteIp) // <-- Direct call to service helper
        textViewStatus.text = "Starting Streaming Service..."
        textViewStatus.setTextColor(Color.GRAY)
        buttonStream.isEnabled = false
        buttonStopStream.isEnabled = true
        Toast.makeText(this, "Attempting to start streaming service", Toast.LENGTH_SHORT).show()
    }

    private fun stopStreaming() {
        StreamingForegroundService.stop(this) // <-- Direct call to service helper
        textViewStatus.text = "Stream Stopped."
        textViewStatus.setTextColor(Color.BLACK)
        buttonStream.isEnabled = true
        buttonStopStream.isEnabled = false
        Toast.makeText(this, "Streaming service stopped", Toast.LENGTH_SHORT).show()
    }

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

    override fun sendStatusUpdate(message: String, color: Int, isRunning: Boolean) {
        runOnUiThread {
            textViewStatus.text = "Status: $message"
            textViewStatus.setTextColor(color)
            buttonStream.isEnabled = !isRunning
            buttonStopStream.isEnabled = isRunning
        }
    }

    override fun sendDebugInfo(message: String) {
        runOnUiThread {
            Log.d("MainActivityDebug", message)
        }
    }
}