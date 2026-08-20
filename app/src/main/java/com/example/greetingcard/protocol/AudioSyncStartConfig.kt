package com.example.greetingcard.protocol

import java.nio.ByteBuffer

data class AudioSyncStartConfig(
    val startAtLeaderTimeNs: Long,
    val firstFrameLeaderTimeNs: Long,
    val sampleRate: Int = 48000,
    val channels: Int = 2
) {

    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(24)
        buffer.putLong(startAtLeaderTimeNs)
        buffer.putLong(firstFrameLeaderTimeNs)
        buffer.putInt(sampleRate)
        buffer.putInt(channels)
        return buffer.array()
    }

    companion object {

        fun fromByteArray(bytes: ByteArray): AudioSyncStartConfig {
            require(bytes.size >= 24) {
                "AudioSyncStartConfig payload too small: ${bytes.size} bytes (expected >= 24)"
            }
            val buffer = ByteBuffer.wrap(bytes)
            val startAt = buffer.long
            val firstFrame = buffer.long
            val sampleRate = buffer.int
            val channels = buffer.int

            return AudioSyncStartConfig(
                startAtLeaderTimeNs = startAt,
                firstFrameLeaderTimeNs = firstFrame,
                sampleRate = sampleRate,
                channels = channels
            )
        }
    }
}

