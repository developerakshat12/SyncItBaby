package com.example.greetingcard

import com.example.greetingcard.protocol.ChecksumMismatchException
import com.example.greetingcard.protocol.InvalidPacketException
import com.example.greetingcard.protocol.Packet
import com.example.greetingcard.protocol.PacketHeader
import com.example.greetingcard.protocol.PacketSerializer
import com.example.greetingcard.protocol.SyncConstants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

class PacketFramingTest {

    @Test
    fun `PacketHeader preserves sessionId UUID and all header metadata`() {
        val uuid = UUID.randomUUID()
        val header = PacketHeader.create(
            sessionUuid = uuid,
            streamId = SyncConstants.STREAM_FILE,
            sequenceNumber = 42L,
            timestampNs = 1_700_000_000_123_456L,
            packetType = SyncConstants.TYPE_FILE_CHUNK,
            payloadLength = 1024,
            checksum = 0x12345678
        )

        assertEquals(uuid, header.sessionUuid)
        assertEquals(uuid.mostSignificantBits, header.sessionIdHigh)
        assertEquals(uuid.leastSignificantBits, header.sessionIdLow)
        assertEquals(SyncConstants.STREAM_FILE, header.streamId)
        assertEquals(42L, header.sequenceNumber)
        assertEquals(1_700_000_000_123_456L, header.timestampNs)
        assertEquals(SyncConstants.TYPE_FILE_CHUNK, header.packetType)
        assertEquals(1024, header.payloadLength)
        assertEquals(0x12345678, header.checksum)
    }

    @Test
    fun `Packet build correctly computes 32-bit CRC32 checksum for payload`() {
        val payload = "Hello SyncCast Binary Protocol!".toByteArray(Charsets.UTF_8)
        val packet = Packet.build(
            packetType = SyncConstants.TYPE_CHAT_MESSAGE,
            payload = payload
        )

        val expectedChecksum = Packet.computeChecksum(payload)
        assertEquals(expectedChecksum, packet.header.checksum)
        assertEquals(payload.size, packet.header.payloadLength)
        assertArrayEquals(payload, packet.payload)
    }

    @Test
    fun `PacketSerializer roundtrip encode and decode preserves packet integrity`() {
        val uuid = UUID.randomUUID()
        val originalPayload = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x0A, 0x0B, 0x0C)
        val originalPacket = Packet.build(
            packetType = SyncConstants.TYPE_FILE_CHUNK,
            payload = originalPayload,
            streamId = SyncConstants.STREAM_FILE,
            sequenceNumber = 1001L,
            timestampNs = 987654321L,
            sessionUuid = uuid
        )

        val encodedBytes = PacketSerializer.encode(originalPacket)
        assertTrue(encodedBytes.size >= PacketHeader.HEADER_SIZE_BYTES + originalPayload.size)

        val decodedPacket = PacketSerializer.decode(encodedBytes)
        assertEquals(originalPacket.header, decodedPacket.header)
        assertArrayEquals(originalPacket.payload, decodedPacket.payload)
        assertEquals(uuid, decodedPacket.header.sessionUuid)
    }

    @Test
    fun `PacketSerializer stream readPacket and writePacket works symmetrically`() {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        val packet1 = Packet.buildString(SyncConstants.TYPE_HEARTBEAT_PING, "PING_12345")
        val packet2 = Packet.buildString(SyncConstants.TYPE_HEARTBEAT_PONG, "PONG_12345")

        PacketSerializer.writePacket(dos, packet1)
        PacketSerializer.writePacket(dos, packet2)
        dos.flush()

        val dis = DataInputStream(ByteArrayInputStream(baos.toByteArray()))
        val readPacket1 = PacketSerializer.readPacket(dis)
        val readPacket2 = PacketSerializer.readPacket(dis)

        assertEquals("PING_12345", readPacket1.payloadAsString())
        assertEquals("PONG_12345", readPacket2.payloadAsString())
    }

    @Test
    fun `PacketSerializer rejects packets with invalid magic identifier`() {
        val packet = Packet.buildString(SyncConstants.TYPE_CHAT_MESSAGE, "Test")
        val bytes = PacketSerializer.encode(packet)
        // Corrupt magic byte
        bytes[0] = 'X'.code.toByte()

        assertThrows(InvalidPacketException::class.java) {
            PacketSerializer.decode(bytes)
        }
    }

    @Test
    fun `PacketSerializer detects corrupted payload bytes via CRC32 mismatch`() {
        val packet = Packet.buildString(SyncConstants.TYPE_CHAT_MESSAGE, "Uncorrupted Data")
        val bytes = PacketSerializer.encode(packet)
        // Corrupt last payload byte
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()

        assertThrows(ChecksumMismatchException::class.java) {
            PacketSerializer.decode(bytes)
        }
    }
}

