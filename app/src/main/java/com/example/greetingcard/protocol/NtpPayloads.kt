package com.example.greetingcard.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class NtpRequestPayload(
    val sequenceNumber: Int,
    val t1Ns: Long
) {
    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(sequenceNumber)
        buffer.putLong(t1Ns)
        return buffer.array()
    }

    companion object {
        const val SIZE_BYTES = 12

        fun fromByteArray(bytes: ByteArray): NtpRequestPayload? {
            if (bytes.size < SIZE_BYTES) return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val seq = buffer.getInt()
            val t1 = buffer.getLong()
            return NtpRequestPayload(sequenceNumber = seq, t1Ns = t1)
        }
    }
}

data class NtpResponsePayload(
    val sequenceNumber: Int,
    val t1Ns: Long,
    val t2Ns: Long,
    val t3Ns: Long
) {
    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(sequenceNumber)
        buffer.putLong(t1Ns)
        buffer.putLong(t2Ns)
        buffer.putLong(t3Ns)
        return buffer.array()
    }

    companion object {
        const val SIZE_BYTES = 28

        fun fromByteArray(bytes: ByteArray): NtpResponsePayload? {
            if (bytes.size < SIZE_BYTES) return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val seq = buffer.getInt()
            val t1 = buffer.getLong()
            val t2 = buffer.getLong()
            val t3 = buffer.getLong()
            return NtpResponsePayload(sequenceNumber = seq, t1Ns = t1, t2Ns = t2, t3Ns = t3)
        }
    }
}

