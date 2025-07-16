// MediaStreamer.kt (Origin App)
package com.example.originapp

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class MediaStreamer(
    private val context: Context,
    private val mp4Uri: Uri,
    private val wsClient: WebSocketClientManager
) : Runnable {

    val isStreaming = AtomicBoolean(false)
    private val isStopped = AtomicBoolean(false)

    private var extractor: MediaExtractor? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null

    fun startStreaming() {
        isStreaming.set(true)
        isStopped.set(false)
        Thread(this).start()
    }

    fun stopStreaming() {
        isStreaming.set(false)
        isStopped.set(true) // Signal the thread to stop gracefully
    }

    override fun run() {
        runCatching {
            setupExtractor()
            if (videoTrackIndex == -1 && audioTrackIndex == -1) {
                Log.e(TAG, "No video or audio track found in MP4.")
                return
            }

            // Send Codec Specific Data (CSD) first
            sendCodecSpecificData()

            // Send START command
            wsClient.sendMessage(WebSocketClientManager.COMMAND_START_STREAMING)

            // Start streaming loop
            streamMediaData()
        }.onFailure { e ->
            Log.e(TAG, "Error during media streaming setup or execution", e)
        }.also {
            releaseResources()
            wsClient.sendMessage(WebSocketClientManager.COMMAND_STOP_STREAMING) // Send STOP command
            Log.d(TAG, "MediaStreamer finished.")
        }
    }

    private fun setupExtractor() {
        extractor = MediaExtractor().apply {
            setDataSource(context, mp4Uri, null)

            for (i in 0 until trackCount) {
                val format = getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true && videoTrackIndex == -1) {
                    videoTrackIndex = i
                    videoFormat = format
                    Log.d(TAG, "Video track found: $mime")
                } else if (mime?.startsWith("audio/") == true && audioTrackIndex == -1) {
                    audioTrackIndex = i
                    audioFormat = format
                    Log.d(TAG, "Audio track found: $mime")
                }
            }
        }
    }

    private fun sendCodecSpecificData() {
        // Send video CSD (SPS and PPS for H.264)
        videoFormat?.let { format ->
            format.getByteBuffer("csd-0")?.let { csd ->
                sendPacket(PACKET_TYPE_VIDEO_CSD, 0, csd) // PTS 0 for SPS
            }
            format.getByteBuffer("csd-1")?.let { csd ->
                sendPacket(PACKET_TYPE_VIDEO_CSD, 1, csd) // PTS 1 for PPS
            }
        }

        // Send audio CSD (AudioSpecificConfig for AAC)
        audioFormat?.let { format ->
            format.getByteBuffer("csd-0")?.let { csd ->
                sendPacket(PACKET_TYPE_AUDIO_CSD, 0, csd) // PTS 0 for AAC CSD
            }
        }
    }

    private fun streamMediaData() {
        val buffer = ByteBuffer.allocate(512 * 1024) // 512KB buffer

        extractor?.run {
            if (videoTrackIndex != -1) selectTrack(videoTrackIndex)
            if (audioTrackIndex != -1) selectTrack(audioTrackIndex)

            // Optional: seek to start to ensure correct initial sample times
            seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            while (isStreaming.get() && !isStopped.get()) {
                val trackIndex = sampleTrackIndex
                if (trackIndex == -1) {
                    Log.d(TAG, "End of MP4 stream.")
                    break // All tracks exhausted
                }

                val presentationTimeUs = sampleTime
                val sampleSize = readSampleData(buffer, 0)
                val flags = sampleFlags

                if (sampleSize > 0) {
                    buffer.limit(sampleSize)
                    buffer.rewind() // Rewind buffer before sending

                    when (trackIndex) {
                        videoTrackIndex -> sendPacket(PACKET_TYPE_VIDEO, presentationTimeUs, buffer)
                        audioTrackIndex -> sendPacket(PACKET_TYPE_AUDIO, presentationTimeUs, buffer)
                    }
                } else if (sampleSize == -1) {
                    // Reached end of current track, but other tracks might still have data.
                    // The main loop condition `sampleTrackIndex == -1` will handle global end.
                    Log.d(TAG, "Reached end of current track.")
                }

                advance() // Advance to the next sample
            }
        }
    }

    private fun sendPacket(type: Byte, ptsUs: Long, data: ByteBuffer) {
        // Packet format: [1-byte type] | [8-byte PTS (long)] | [Encoded Data]
        val packet = ByteBuffer.allocate(1 + 8 + data.remaining()).apply {
            put(type)
            putLong(ptsUs)
            put(data)
            flip() // Prepare for reading
        }
        wsClient.sendBytes(packet)
    }

    private fun releaseResources() {
        extractor?.release()
        extractor = null
        // If you were re-encoding using MediaCodec, release encoder here too.
    }

    companion object {
        private const val TAG = "MediaStreamer"

        // Packet types for network transmission
        const val PACKET_TYPE_VIDEO: Byte = 0
        const val PACKET_TYPE_AUDIO: Byte = 1
        const val PACKET_TYPE_VIDEO_CSD: Byte = 2 // Codec Specific Data
        const val PACKET_TYPE_AUDIO_CSD: Byte = 3
    }
}