package com.example.origin

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer

class MediaStreamer(
    private val context: Context,
    private val videoUri: Uri,
    private val wsClient: WSClient
) {

    private var extractor: MediaExtractor? = null
    private var videoTrackIndex: Int = -1
    private var audioTrackIndex: Int = -1
    private var videoTrackFormat: MediaFormat? = null
    private var audioTrackFormat: MediaFormat? = null

    @Volatile
    private var isStreaming = false
    private var streamingThread: Thread? = null

    fun startStreaming() {
        if (isStreaming) {
            Log.w(TAG, "Already streaming.")
            return
        }

        isStreaming = true
        streamingThread = Thread {
            try {
                initializeExtractor()
                sendCodecInfo() // This sends formats and CSD
                // After sending codec info, seek the extractor back to the beginning.
                // This ensures the streaming loop starts from the beginning of the actual media data.
                if (videoTrackIndex != -1) {
                    extractor?.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    Log.d(TAG, "Extractor seeked to start for video track.")
                } else if (audioTrackIndex != -1) {
                    // Only seek if video track wasn't found and audio track exists
                    extractor?.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    Log.d(TAG, "Extractor seeked to start for audio track.")
                }


                streamMediaData()
            } catch (e: Exception) {
                Log.e(TAG, "Error during streaming: ${e.message}", e)
                wsClient.sendMessage(Constants.COMMAND_STOP_STREAMING) // Notify remote of error
            } finally {
                releaseResources()
            }
        }
        streamingThread?.name = "MediaStreamingThread"
        streamingThread?.start()
        Log.d(TAG, "MediaStreamer started.")
    }

    fun stopStreaming() {
        if (!isStreaming) {
            Log.w(TAG, "Not streaming, nothing to stop.")
            return
        }
        isStreaming = false
        streamingThread?.join(1000) // Wait for the thread to finish
        streamingThread = null
        releaseResources()
        wsClient.sendMessage(Constants.COMMAND_STOP_STREAMING) // Notify remote to stop
        Log.d(TAG, "MediaStreamer stopped.")
    }

    private fun initializeExtractor() {
        extractor = MediaExtractor()
        try {
            extractor?.setDataSource(context, videoUri, null)
        } catch (e: Exception) {
            throw IOException("Failed to instantiate extractor. ${e.message}", e)
        }

        for (i in 0 until (extractor?.trackCount ?: 0)) {
            val format = extractor?.getTrackFormat(i)
            val mime = format?.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("video/") == true) {
                videoTrackIndex = i
                videoTrackFormat = format
                Log.d(TAG, "Video track found: $mime")
            } else if (mime?.startsWith("audio/") == true) {
                audioTrackIndex = i
                audioTrackFormat = format
                Log.d(TAG, "Audio track found: $mime")
            }
        }

        if (videoTrackIndex == -1 && audioTrackIndex == -1) {
            throw IOException("No video or audio track found in the media.")
        }

        // IMPORTANT: Select the tracks after finding them
        if (videoTrackIndex != -1) {
            extractor?.selectTrack(videoTrackIndex)
            Log.d(TAG, "Selected video track: $videoTrackIndex")
        }
        if (audioTrackIndex != -1) {
            extractor?.selectTrack(audioTrackIndex)
            Log.d(TAG, "Selected audio track: $audioTrackIndex")
        }
    }

    private fun sendCodecInfo() {
        Log.d(TAG, "Sending Codec Information (Formats and CSD)...")

        // 1. Send Video MediaFormat Data
        videoTrackFormat?.let { format ->
            val mime = format.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_VIDEO_AVC
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            val frameRate = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.getInteger(MediaFormat.KEY_FRAME_RATE) else 30

            wsClient.sendVideoFormat(mime, width, height, frameRate)
            Log.d(TAG, "Sent Video Format Data: $mime, ${width}x${height}, $frameRate fps")
        }

        // 2. Send Audio MediaFormat Data (Consider removing if video-only)
        audioTrackFormat?.let { format ->
            val mime = format.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_AUDIO_AAC
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            wsClient.sendAudioFormat(mime, sampleRate, channelCount)
            Log.d(TAG, "Sent Audio Format Data: $mime, $sampleRate Hz, $channelCount channels")
        }

        // 3. Send CSD Data (Codec Specific Data)
        // Note: For H.264, csd-0 is SPS, csd-1 is PPS. Both are often needed.
        videoTrackFormat?.getByteBuffer("csd-0")?.let { csd0 ->
            csd0.rewind() // Ensure buffer is ready for reading from start
            wsClient.sendVideoCSD(csd0)
            Log.d(TAG, "Sent Video CSD (csd-0), size: ${csd0.limit()}")
        }
        videoTrackFormat?.getByteBuffer("csd-1")?.let { csd1 ->
            csd1.rewind() // Ensure buffer is ready for reading from start
            wsClient.sendVideoCSD(csd1)
            Log.d(TAG, "Sent Video CSD (csd-1), size: ${csd1.limit()}")
        }
        audioTrackFormat?.getByteBuffer("csd-0")?.let { csd0 ->
            csd0.rewind() // Ensure buffer is ready for reading from start
            wsClient.sendAudioCSD(csd0)
            Log.d(TAG, "Sent Audio CSD (csd-0), size: ${csd0.limit()}")
        }

        // 4. Send START_STREAM Command
        wsClient.sendMessage(Constants.COMMAND_START_STREAMING)
        Log.d(TAG, "Sent START_STREAM command.")
    }

    private fun streamMediaData() {
        val buffer = ByteBuffer.allocate(1024 * 1024) // 1MB buffer, adjust as needed for large frames

        while (isStreaming) {
            // Read next sample
            val sampleSize = extractor?.readSampleData(buffer, 0) ?: -1

            if (sampleSize < 0) {
                Log.d(TAG, "End of stream reached. Signalling stop.")
                isStreaming = false // Indicate streaming finished naturally
                wsClient.sendMessage(Constants.COMMAND_STOP_STREAMING) // Notify remote of end of stream
                break // Exit the loop
            }

            val currentTrackIndex = extractor?.sampleTrackIndex ?: -1
            val presentationTimeUs = extractor?.sampleTime ?: 0L
            val sampleFlags = extractor?.sampleFlags ?: 0

            buffer.limit(sampleSize) // Set the buffer's limit to the actual data size
            buffer.rewind() // Reset position to 0 for reading

            // Log.v(TAG, "Read sample from track $currentTrackIndex, size $sampleSize, pts $presentationTimeUs, flags $sampleFlags")

            if (currentTrackIndex == videoTrackIndex) {
                // Ensure IDR (key frame) is handled correctly if any special flag needed
                // For H.264, MediaCodec.BUFFER_FLAG_KEY_FRAME is important.
                // MediaExtractor provides this as MediaExtractor.SAMPLE_FLAG_SYNC.
                // You might want to include this flag in your protocol for advanced receivers.
                // For now, it's just raw frame data + PTS.
                wsClient.sendVideoFrame(buffer, presentationTimeUs)
            } else if (currentTrackIndex == audioTrackIndex) {
                wsClient.sendAudioFrame(buffer, presentationTimeUs)
            } else {
                Log.w(TAG, "Skipping sample from unselected track: $currentTrackIndex")
            }

            // Advance to the next sample in the extractor
            extractor?.advance()
            buffer.clear() // Clear the buffer for the next read operation (resets position to 0, limit to capacity)
        }
        Log.d(TAG, "Media data streaming loop finished.")
    }


    private fun releaseResources() {
        extractor?.release()
        extractor = null
        videoTrackFormat = null
        audioTrackFormat = null
        videoTrackIndex = -1
        audioTrackIndex = -1
        Log.d(TAG, "MediaStreamer resources released.")
    }

    companion object {
        private const val TAG = "MediaStreamer"
    }
}