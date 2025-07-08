package com.example.origin

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.media3.common.Player
import java.io.IOException

class FrameController(
    private val context: Context,
    private val exoPlayer: Player
) {
    private var currentFrame = 0
    private var totalFrames = 0
    private val retriever = MediaMetadataRetriever()

    fun initialize(videoPath: String) {
        try {
            // For assets folder files
            val assetFileDescriptor = context.assets.openFd(videoPath)
            retriever.setDataSource(
                assetFileDescriptor.fileDescriptor,
                assetFileDescriptor.startOffset,
                assetFileDescriptor.length
            )
            assetFileDescriptor.close()

            // Get total frames
            totalFrames = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT
            )?.toInt() ?: 0
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun seekToFrame(frame: Int) {
        currentFrame = frame.coerceIn(0, totalFrames - 1)
        // Assuming 30fps video
        val positionMs = (currentFrame * 1000L) / 30
        exoPlayer.seekTo(positionMs)
    }

    fun getCurrentFrame(): Bitmap? {
        try {
            // Get frame at current position (in microseconds)
            return retriever.getFrameAtTime(
                exoPlayer.currentPosition * 1000, // convert ms to μs
                MediaMetadataRetriever.OPTION_CLOSEST
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun release() {
        retriever.release()
    }
}