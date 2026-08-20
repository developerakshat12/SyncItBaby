package com.example.greetingcard.protocol

import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.CRC32

data class Packet(
    val header: PacketHeader,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Packet

        if (header != other.header) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    fun payloadAsString(): String = String(payload, StandardCharsets.UTF_8)

    companion object {
        val EMPTY_PAYLOAD = ByteArray(0)

        fun computeChecksum(payload: ByteArray): Int {
            if (payload.isEmpty()) return 0
            val crc = CRC32()
            crc.update(payload)
            return (crc.value and 0xFFFFFFFFL).toInt()
        }

        fun build(
            packetType: Short,
            payload: ByteArray = EMPTY_PAYLOAD,
            streamId: Int = SyncConstants.STREAM_CONTROL,
            sequenceNumber: Long = 0L,
            timestampNs: Long = 0L,
            sessionUuid: UUID = UUID(0L, 0L)
        ): Packet {
            require(payload.size <= SyncConstants.MAX_PAYLOAD_SIZE) {
                "Payload size ${payload.size} exceeds maximum allowed ${SyncConstants.MAX_PAYLOAD_SIZE}"
            }
            val checksum = computeChecksum(payload)
            val header = PacketHeader.create(
                sessionUuid = sessionUuid,
                streamId = streamId,
                sequenceNumber = sequenceNumber,
                timestampNs = timestampNs,
                packetType = packetType,
                payloadLength = payload.size,
                checksum = checksum
            )
            return Packet(header, payload)
        }

        fun buildString(
            packetType: Short,
            text: String,
            streamId: Int = SyncConstants.STREAM_CONTROL,
            sequenceNumber: Long = 0L,
            timestampNs: Long = 0L,
            sessionUuid: UUID = UUID(0L, 0L)
        ): Packet {
            val payload = text.toByteArray(StandardCharsets.UTF_8)
            return build(
                packetType = packetType,
                payload = payload,
                streamId = streamId,
                sequenceNumber = sequenceNumber,
                timestampNs = timestampNs,
                sessionUuid = sessionUuid
            )
        }
    }
}

