package com.example.greetingcard.protocol

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

data class SyncStateConfig(
    val isStreaming: Boolean,
    val sampleRate: Int = 48000,
    val channels: Int = 2,
    val bitrate: Int = 128000,
    val frameDurationUs: Int = 20000,
    val totalDurationMs: Int = 0,
    val trackName: String = "",
    val startAtLeaderTimeNs: Long = 0L,
    val firstFrameLeaderTimeNs: Long = 0L,
    val currentSequenceNumber: Long = 0L
) {
    fun toByteArray(): ByteArray {
        val nameBytes = trackName.toByteArray(StandardCharsets.UTF_8)
        // 1 byte (Boolean) + 5 * 4 bytes (Ints) + 3 * 8 bytes (Longs) + 4 bytes (nameLength) + nameBytes
        val capacity = 1 + 20 + 24 + 4 + nameBytes.size
        val buffer = ByteBuffer.allocate(capacity)

        buffer.put(if (isStreaming) 1.toByte() else 0.toByte())
        buffer.putInt(sampleRate)
        buffer.putInt(channels)
        buffer.putInt(bitrate)
        buffer.putInt(frameDurationUs)
        buffer.putInt(totalDurationMs)
        buffer.putLong(startAtLeaderTimeNs)
        buffer.putLong(firstFrameLeaderTimeNs)
        buffer.putLong(currentSequenceNumber)
        buffer.putInt(nameBytes.size)
        buffer.put(nameBytes)

        return buffer.array()
    }

    companion object {
        fun fromByteArray(bytes: ByteArray): SyncStateConfig {
            require(bytes.size >= 41) {
                "SyncStateConfig payload too small: ${bytes.size} bytes (expected >= 41)"
            }
            val buffer = ByteBuffer.wrap(bytes)
            val isStreaming = buffer.get() != 0.toByte()
            val sampleRate = buffer.int
            val channels = buffer.int
            val bitrate = buffer.int
            val frameDurationUs = buffer.int
            val totalDurationMs = buffer.int
            val startAtLeaderTimeNs = buffer.long
            val firstFrameLeaderTimeNs = buffer.long

            // If 49+ bytes, read 8-byte currentSequenceNumber, otherwise default to 0L for legacy compatibility
            val currentSequenceNumber = if (bytes.size >= 49) {
                buffer.long
            } else {
                0L
            }

            val nameLength = buffer.int

            val trackName = if (nameLength > 0 && buffer.remaining() >= nameLength) {
                val nameBytes = ByteArray(nameLength)
                buffer.get(nameBytes)
                String(nameBytes, StandardCharsets.UTF_8)
            } else {
                ""
            }

            return SyncStateConfig(
                isStreaming = isStreaming,
                sampleRate = sampleRate,
                channels = channels,
                bitrate = bitrate,
                frameDurationUs = frameDurationUs,
                totalDurationMs = totalDurationMs,
                trackName = trackName,
                startAtLeaderTimeNs = startAtLeaderTimeNs,
                firstFrameLeaderTimeNs = firstFrameLeaderTimeNs,
                currentSequenceNumber = currentSequenceNumber
            )
        }
    }
}

