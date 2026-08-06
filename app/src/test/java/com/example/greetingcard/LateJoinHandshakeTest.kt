package com.example.greetingcard

import com.example.greetingcard.protocol.AudioNackPayload
import com.example.greetingcard.protocol.AudioStreamConfig
import com.example.greetingcard.protocol.AudioSyncStartConfig
import com.example.greetingcard.protocol.Packet
import com.example.greetingcard.protocol.SyncConstants
import com.example.greetingcard.protocol.SyncStateConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class LateJoinHandshakeTest {

    @Test
    fun `SyncStateConfig serializes and deserializes active streaming state accurately with sequence number`() {
        val config = SyncStateConfig(
            isStreaming = true,
            sampleRate = 48000,
            channels = 2,
            bitrate = 128000,
            frameDurationUs = 20000,
            totalDurationMs = 180000,
            trackName = "Synchronized Symphony.mp3",
            startAtLeaderTimeNs = 1_700_000_002_000_000_000L,
            firstFrameLeaderTimeNs = 1_700_000_000_000_000_000L,
            currentSequenceNumber = 9876543210L
        )

        val bytes = config.toByteArray()
        val deserialized = SyncStateConfig.fromByteArray(bytes)

        assertTrue(deserialized.isStreaming)
        assertEquals(48000, deserialized.sampleRate)
        assertEquals(2, deserialized.channels)
        assertEquals(128000, deserialized.bitrate)
        assertEquals(20000, deserialized.frameDurationUs)
        assertEquals(180000, deserialized.totalDurationMs)
        assertEquals("Synchronized Symphony.mp3", deserialized.trackName)
        assertEquals(1_700_000_002_000_000_000L, deserialized.startAtLeaderTimeNs)
        assertEquals(1_700_000_000_000_000_000L, deserialized.firstFrameLeaderTimeNs)
        assertEquals(9876543210L, deserialized.currentSequenceNumber)
    }

    @Test
    fun `SyncStateConfig deserializes legacy 41-byte payloads defaulting sequence number to 0L`() {
        // Manually build a legacy 41-byte payload without currentSequenceNumber
        val buffer = java.nio.ByteBuffer.allocate(41)
        buffer.put(1.toByte()) // isStreaming = true
        buffer.putInt(48000)
        buffer.putInt(2)
        buffer.putInt(128000)
        buffer.putInt(20000)
        buffer.putInt(60000)
        buffer.putLong(100L)
        buffer.putLong(200L)
        buffer.putInt(0) // nameLength = 0

        val deserialized = SyncStateConfig.fromByteArray(buffer.array())
        assertTrue(deserialized.isStreaming)
        assertEquals(0L, deserialized.currentSequenceNumber)
        assertEquals(100L, deserialized.startAtLeaderTimeNs)
        assertEquals(200L, deserialized.firstFrameLeaderTimeNs)
    }

    @Test
    fun `SyncStateConfig serializes and deserializes idle state accurately`() {
        val config = SyncStateConfig(isStreaming = false)
        val bytes = config.toByteArray()
        val deserialized = SyncStateConfig.fromByteArray(bytes)

        assertFalse(deserialized.isStreaming)
        assertEquals(48000, deserialized.sampleRate)
        assertEquals(2, deserialized.channels)
        assertEquals("", deserialized.trackName)
        assertEquals(0L, deserialized.currentSequenceNumber)
    }

    @Test
    fun `AudioNackPayload serializes and deserializes missing sequence number`() {
        val nack = AudioNackPayload(missingSequenceNumber = 123456789L)
        val bytes = nack.toByteArray()
        val deserialized = AudioNackPayload.fromByteArray(bytes)

        assertEquals(123456789L, deserialized.missingSequenceNumber)
    }

    @Test
    fun `Framed packet builds for Late Join handshake types`() {
        val sessionUuid = UUID.randomUUID()

        // 1. TYPE_REQUEST_SYNC_STATE
        val reqPacket = Packet.build(
            packetType = SyncConstants.TYPE_REQUEST_SYNC_STATE,
            streamId = SyncConstants.STREAM_CONTROL,
            sessionUuid = sessionUuid
        )
        assertEquals(SyncConstants.TYPE_REQUEST_SYNC_STATE, reqPacket.header.packetType)

        // 2. TYPE_SYNC_STATE
        val syncState = SyncStateConfig(isStreaming = true, trackName = "Live Jam")
        val statePacket = Packet.build(
            packetType = SyncConstants.TYPE_SYNC_STATE,
            payload = syncState.toByteArray(),
            streamId = SyncConstants.STREAM_CONTROL,
            sessionUuid = sessionUuid
        )
        assertEquals(SyncConstants.TYPE_SYNC_STATE, statePacket.header.packetType)
        val parsedState = SyncStateConfig.fromByteArray(statePacket.payload)
        assertEquals("Live Jam", parsedState.trackName)

        // 3. TYPE_SYNC_STATE_ACK
        val ackPacket = Packet.build(
            packetType = SyncConstants.TYPE_SYNC_STATE_ACK,
            streamId = SyncConstants.STREAM_CONTROL,
            sessionUuid = sessionUuid
        )
        assertEquals(SyncConstants.TYPE_SYNC_STATE_ACK, ackPacket.header.packetType)

        // 4. TYPE_AUDIO_NACK
        val nackPacket = Packet.build(
            packetType = SyncConstants.TYPE_AUDIO_NACK,
            payload = AudioNackPayload(42L).toByteArray(),
            streamId = SyncConstants.STREAM_AUDIO,
            sessionUuid = sessionUuid
        )
        assertEquals(SyncConstants.TYPE_AUDIO_NACK, nackPacket.header.packetType)
        val parsedNack = AudioNackPayload.fromByteArray(nackPacket.payload)
        assertEquals(42L, parsedNack.missingSequenceNumber)
    }
}

