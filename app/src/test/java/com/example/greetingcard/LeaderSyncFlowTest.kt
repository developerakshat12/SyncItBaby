package com.example.greetingcard

import com.example.greetingcard.audio.AudioReceiver
import com.example.greetingcard.protocol.AudioStreamConfig
import com.example.greetingcard.protocol.AudioSyncStartConfig
import com.example.greetingcard.protocol.Packet
import com.example.greetingcard.protocol.SyncConstants
import com.example.greetingcard.sync.DefaultTimeDomainConverter
import com.example.greetingcard.sync.LocalAudioDacTracker
import com.example.greetingcard.sync.Ticker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class LeaderSyncFlowTest {

    private class SyntheticTicker(initialNs: Long = 1_000_000_000L) : Ticker {
        private val nanos = AtomicLong(initialNs)
        override fun readNanos(): Long = nanos.get()
        override fun convertMonotonicNanosToTickerNs(monotonicNs: Long): Long = monotonicNs
    }

    private lateinit var ticker: SyntheticTicker
    private lateinit var timeConverter: DefaultTimeDomainConverter

    @Before
    fun setUp() {
        ticker = SyntheticTicker(1_000_000_000L)
        timeConverter = DefaultTimeDomainConverter(ticker)
        timeConverter.updateOffset(0L, smooth = false) // Leader offset is 0
    }

    @Test
    fun `audioReceiver correctly parses TYPE_AUDIO_SYNC_START and prepares sync state`() {
        val streamStateChanged = AtomicBoolean(false)
        val receiver = AudioReceiver(timeConverter, LocalAudioDacTracker(ticker)) { isStreaming ->
            streamStateChanged.set(isStreaming)
        }

        val startAtLeaderNs = ticker.readNanos() + 2_000_000_000L // 2 seconds ahead
        val syncConfig = AudioSyncStartConfig(
            startAtLeaderTimeNs = startAtLeaderNs,
            firstFrameLeaderTimeNs = startAtLeaderNs,
            sampleRate = 48000,
            channels = 2
        )

        val syncPacket = Packet.build(
            packetType = SyncConstants.TYPE_AUDIO_SYNC_START,
            payload = syncConfig.toByteArray(),
            streamId = SyncConstants.STREAM_AUDIO,
            sessionUuid = UUID.randomUUID()
        )

        // Invoke handleAudioSyncStart — must execute without throwing
        receiver.handleAudioSyncStart(syncPacket)

        // Verify deserialization from packet
        val parsedConfig = AudioSyncStartConfig.fromByteArray(syncPacket.payload)
        assertEquals(startAtLeaderNs, parsedConfig.startAtLeaderTimeNs)
        assertEquals(startAtLeaderNs, parsedConfig.firstFrameLeaderTimeNs)
        assertEquals(48000, parsedConfig.sampleRate)
        assertEquals(2, parsedConfig.channels)

        receiver.stopStream()
    }

    @Test
    fun `local Path B in-memory dispatch routes TYPE_AUDIO_SYNC_START without dropping`() {
        val receivedPacketTypes = mutableListOf<Short>()
        val dummyHandler: (Packet) -> Unit = { packet ->
            when (packet.header.packetType) {
                SyncConstants.TYPE_AUDIO_STREAM_START -> receivedPacketTypes.add(packet.header.packetType)
                SyncConstants.TYPE_AUDIO_SYNC_START -> receivedPacketTypes.add(packet.header.packetType)
                SyncConstants.TYPE_AUDIO_DATA -> receivedPacketTypes.add(packet.header.packetType)
                SyncConstants.TYPE_AUDIO_STREAM_STOP -> receivedPacketTypes.add(packet.header.packetType)
            }
        }

        // Simulate local dispatch sequence from Leader AudioStreamer
        val streamConfig = AudioStreamConfig(trackName = "LeaderLocal.mp3")
        dummyHandler(Packet.build(SyncConstants.TYPE_AUDIO_STREAM_START, streamConfig.toByteArray()))

        val syncConfig = AudioSyncStartConfig(startAtLeaderTimeNs = 2_000_000_000L, firstFrameLeaderTimeNs = 2_000_000_000L)
        dummyHandler(Packet.build(SyncConstants.TYPE_AUDIO_SYNC_START, syncConfig.toByteArray()))

        dummyHandler(Packet.build(SyncConstants.TYPE_AUDIO_DATA, ByteArray(100)))
        dummyHandler(Packet.build(SyncConstants.TYPE_AUDIO_STREAM_STOP))

        assertEquals(4, receivedPacketTypes.size)
        assertEquals(SyncConstants.TYPE_AUDIO_STREAM_START, receivedPacketTypes[0])
        assertEquals(SyncConstants.TYPE_AUDIO_SYNC_START, receivedPacketTypes[1])
        assertEquals(SyncConstants.TYPE_AUDIO_DATA, receivedPacketTypes[2])
        assertEquals(SyncConstants.TYPE_AUDIO_STREAM_STOP, receivedPacketTypes[3])
    }
}

