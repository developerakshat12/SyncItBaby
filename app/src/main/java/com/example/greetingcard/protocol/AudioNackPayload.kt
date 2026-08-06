package com.example.greetingcard.protocol

import java.nio.ByteBuffer

data class AudioNackPayload(
    val missingSequenceNumber: Long
) {
    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(8)
        buffer.putLong(missingSequenceNumber)
        return buffer.array()
    }

    companion object {
        fun fromByteArray(bytes: ByteArray): AudioNackPayload {
            require(bytes.size >= 8) {
                "AudioNackPayload too small: ${bytes.size} bytes (expected >= 8)"
            }
            val buffer = ByteBuffer.wrap(bytes)
            return AudioNackPayload(buffer.long)
        }
    }
}

