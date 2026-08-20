package com.example.greetingcard.protocol

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

data class AudioStreamConfig(
    val sampleRate: Int = 48000,
    val channels: Int = 2,
    val bitrate: Int = 128000,
    val frameDurationUs: Int = 20000, // 20ms
    val totalDurationMs: Int = 0,
    val trackName: String = ""
) {

    fun toByteArray(): ByteArray {
        val nameBytes = trackName.toByteArray(StandardCharsets.UTF_8)
        // 5 * 4 bytes (Ints) + length of name bytes
        val capacity = 20 + nameBytes.size
        val buffer = ByteBuffer.allocate(capacity)

        buffer.putInt(sampleRate)
        buffer.putInt(channels)
        buffer.putInt(bitrate)
        buffer.putInt(frameDurationUs)
        buffer.putInt(totalDurationMs)
        buffer.put(nameBytes)

        return buffer.array()
    }

    companion object {

        fun fromByteArray(bytes: ByteArray): AudioStreamConfig {
            if (bytes.size < 20) {
                throw IllegalArgumentException("AudioStreamConfig payload too small: ${bytes.size} bytes")
            }
            val buffer = ByteBuffer.wrap(bytes)

            val sampleRate = buffer.int
            val channels = buffer.int
            val bitrate = buffer.int
            val frameDurationUs = buffer.int
            val totalDurationMs = buffer.int

            val nameBytesLength = bytes.size - 20
            val nameBytes = ByteArray(nameBytesLength)
            buffer.get(nameBytes)
            val trackName = String(nameBytes, StandardCharsets.UTF_8)

            return AudioStreamConfig(
                sampleRate = sampleRate,
                channels = channels,
                bitrate = bitrate,
                frameDurationUs = frameDurationUs,
                totalDurationMs = totalDurationMs,
                trackName = trackName
            )
        }
    }
}

