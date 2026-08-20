package com.example.greetingcard.protocol

import java.util.UUID

data class PacketHeader(
    val magic: ByteArray = SyncConstants.MAGIC_BYTES,
    val version: Byte = SyncConstants.PROTOCOL_VERSION,
    val sessionIdHigh: Long = 0L,
    val sessionIdLow: Long = 0L,
    val streamId: Int = SyncConstants.STREAM_CONTROL,
    val sequenceNumber: Long = 0L,
    val timestampNs: Long = 0L,
    val packetType: Short = SyncConstants.TYPE_HANDSHAKE_HELLO,
    val payloadLength: Int = 0,
    val checksum: Int = 0
) {

    val sessionUuid: UUID
        get() = UUID(sessionIdHigh, sessionIdLow)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PacketHeader

        if (!magic.contentEquals(other.magic)) return false
        if (version != other.version) return false
        if (sessionIdHigh != other.sessionIdHigh) return false
        if (sessionIdLow != other.sessionIdLow) return false
        if (streamId != other.streamId) return false
        if (sequenceNumber != other.sequenceNumber) return false
        if (timestampNs != other.timestampNs) return false
        if (packetType != other.packetType) return false
        if (payloadLength != other.payloadLength) return false
        if (checksum != other.checksum) return false

        return true
    }

    override fun hashCode(): Int {
        var result = magic.contentHashCode()
        result = 31 * result + version
        result = 31 * result + sessionIdHigh.hashCode()
        result = 31 * result + sessionIdLow.hashCode()
        result = 31 * result + streamId
        result = 31 * result + sequenceNumber.hashCode()
        result = 31 * result + timestampNs.hashCode()
        result = 31 * result + packetType
        result = 31 * result + payloadLength
        result = 31 * result + checksum
        return result
    }

    companion object {
        const val HEADER_SIZE_BYTES = 51

        fun create(
            sessionUuid: UUID = UUID(0L, 0L),
            streamId: Int = SyncConstants.STREAM_CONTROL,
            sequenceNumber: Long = 0L,
            timestampNs: Long = 0L,
            packetType: Short,
            payloadLength: Int,
            checksum: Int = 0
        ): PacketHeader {
            return PacketHeader(
                magic = SyncConstants.MAGIC_BYTES,
                version = SyncConstants.PROTOCOL_VERSION,
                sessionIdHigh = sessionUuid.mostSignificantBits,
                sessionIdLow = sessionUuid.leastSignificantBits,
                streamId = streamId,
                sequenceNumber = sequenceNumber,
                timestampNs = timestampNs,
                packetType = packetType,
                payloadLength = payloadLength,
                checksum = checksum
            )
        }
    }
}

