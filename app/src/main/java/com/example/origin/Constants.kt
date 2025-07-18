package com.example.origin

object Constants {
    // Packet types for WebSocket communication (MUST MATCH RemoteApp's MediaPlaybackManager)
    const val PACKET_TYPE_VIDEO: Byte = 0x00
    const val PACKET_TYPE_AUDIO: Byte = 0x01
    const val PACKET_TYPE_VIDEO_CSD: Byte = 0x02 // Codec Specific Data for Video
    const val PACKET_TYPE_AUDIO_CSD: Byte = 0x03 // Codec Specific Data for Audio

    // New packet types for serialized MediaFormat objects
    const val PACKET_TYPE_VIDEO_FORMAT_DATA: Byte = 0x04
    const val PACKET_TYPE_AUDIO_FORMAT_DATA: Byte = 0x05

    // Commands for WebSocket text messages
    const val COMMAND_START_STREAMING = "START_STREAM"
    const val COMMAND_STOP_STREAMING = "STOP_STREAM"
}