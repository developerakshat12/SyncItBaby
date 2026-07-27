package com.example.greetingcard.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

open class InvalidPacketException(message: String, cause: Throwable? = null) : IOException(message, cause)
class ChecksumMismatchException(message: String) : InvalidPacketException(message)
class ProtocolVersionMismatchException(message: String) : InvalidPacketException(message)

object PacketSerializer {

    fun encode(packet: Packet): ByteArray {
        val baos = ByteArrayOutputStream(PacketHeader.HEADER_SIZE_BYTES + packet.payload.size)
        val dos = DataOutputStream(baos)
        writePacket(dos, packet)
        dos.flush()
        return baos.toByteArray()
    }

    fun decode(bytes: ByteArray): Packet {
        val bais = ByteArrayInputStream(bytes)
        val dis = DataInputStream(bais)
        return readPacket(dis)
    }

    fun writePacket(dos: DataOutputStream, packet: Packet) {
        val header = packet.header
        require(packet.payload.size == header.payloadLength) {
            "Payload length mismatch: header specifies ${header.payloadLength}, actual is ${packet.payload.size}"
        }

        // 1. Magic bytes (4 bytes)
        dos.write(header.magic)
        // 2. Protocol version (1 byte)
        dos.writeByte(header.version.toInt())
        // 3. Session ID (16 bytes: 2x Longs)
        dos.writeLong(header.sessionIdHigh)
        dos.writeLong(header.sessionIdLow)
        // 4. Stream ID (4 bytes)
        dos.writeInt(header.streamId)
        // 5. Sequence number (8 bytes)
        dos.writeLong(header.sequenceNumber)
        // 6. Timestamp nanoseconds (8 bytes)
        dos.writeLong(header.timestampNs)
        // 7. Packet Type (2 bytes)
        dos.writeShort(header.packetType.toInt())
        // 8. Payload Length (4 bytes)
        dos.writeInt(header.payloadLength)
        // 9. Checksum (4 bytes - 32-bit CRC32)
        dos.writeInt(header.checksum)

        // 10. Payload bytes
        if (header.payloadLength > 0) {
            dos.write(packet.payload)
        }
        dos.flush()
    }

    fun readPacket(dis: DataInputStream): Packet {
        // 1. Read magic (4 bytes)
        val magic = ByteArray(4)
        dis.readFully(magic)
        if (!magic.contentEquals(SyncConstants.MAGIC_BYTES)) {
            throw InvalidPacketException("Invalid packet magic: expected ${SyncConstants.MAGIC_BYTES.contentToString()}, got ${magic.contentToString()}")
        }

        // 2. Read version (1 byte)
        val version = dis.readByte()
        if (version != SyncConstants.PROTOCOL_VERSION) {
            throw ProtocolVersionMismatchException("Unsupported protocol version: $version (expected ${SyncConstants.PROTOCOL_VERSION})")
        }

        // 3. Session ID (16 bytes)
        val sessionIdHigh = dis.readLong()
        val sessionIdLow = dis.readLong()

        // 4. Stream ID (4 bytes)
        val streamId = dis.readInt()

        // 5. Sequence number (8 bytes)
        val sequenceNumber = dis.readLong()

        // 6. Timestamp nanoseconds (8 bytes)
        val timestampNs = dis.readLong()

        // 7. Packet Type (2 bytes)
        val packetType = dis.readShort()

        // 8. Payload Length (4 bytes)
        val payloadLength = dis.readInt()
        if (payloadLength < 0 || payloadLength > SyncConstants.MAX_PAYLOAD_SIZE) {
            throw InvalidPacketException("Invalid payload length: $payloadLength bytes (max: ${SyncConstants.MAX_PAYLOAD_SIZE})")
        }

        // 9. Checksum (4 bytes)
        val checksum = dis.readInt()

        // 10. Payload
        val payload = if (payloadLength > 0) {
            val bytes = ByteArray(payloadLength)
            dis.readFully(bytes)
            bytes
        } else {
            Packet.EMPTY_PAYLOAD
        }

        // 11. Validate CRC32 Checksum
        val calculatedChecksum = Packet.computeChecksum(payload)
        if (checksum != calculatedChecksum) {
            throw ChecksumMismatchException("CRC32 mismatch: header expected $checksum, calculated $calculatedChecksum")
        }

        val header = PacketHeader(
            magic = magic,
            version = version,
            sessionIdHigh = sessionIdHigh,
            sessionIdLow = sessionIdLow,
            streamId = streamId,
            sequenceNumber = sequenceNumber,
            timestampNs = timestampNs,
            packetType = packetType,
            payloadLength = payloadLength,
            checksum = checksum
        )

        return Packet(header, payload)
    }
}

