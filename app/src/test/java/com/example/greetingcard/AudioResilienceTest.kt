package com.example.greetingcard

import com.example.greetingcard.audio.AudioReceiver
import com.example.greetingcard.audio.AudioStreamer
import com.example.greetingcard.audio.JitterBuffer
import com.example.greetingcard.audio.PcmChunk
import com.example.greetingcard.protocol.Packet
import com.example.greetingcard.protocol.SyncConstants
import com.example.greetingcard.sync.DefaultTimeDomainConverter
import com.example.greetingcard.sync.LocalAudioDacTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class AudioResilienceTest {

    private lateinit var timeDomainConverter: DefaultTimeDomainConverter
    private lateinit var jitterBuffer: JitterBuffer

    @Before
    fun setUp() {
        timeDomainConverter = DefaultTimeDomainConverter()
        jitterBuffer = JitterBuffer(timeDomainConverter, bufferDepthMs = 100L, sampleRate = 48000)
    }

    private val fakeDecoderFactory = object : com.example.greetingcard.audio.AudioDecoderFactory {
        override fun createOpusDecoder(sampleRate: Int, channels: Int): com.example.greetingcard.audio.AudioDecoder {
            return object : com.example.greetingcard.audio.AudioDecoder {
                override fun dequeueInputBuffer(timeoutUs: Long): Int = -1
                override fun getInputBuffer(index: Int): java.nio.ByteBuffer? = null
                override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {}
                override fun dequeueOutputBuffer(info: android.media.MediaCodec.BufferInfo, timeoutUs: Long): Int = -1
                override fun getOutputBuffer(index: Int): java.nio.ByteBuffer? = null
                override fun releaseOutputBuffer(index: Int, render: Boolean) {}
                override fun stop() {}
                override fun release() {}
            }
        }
    }

    @Test
    fun `AudioReceiver detects sequence gaps and emits NACK requests for missing packets`() {
        val missingNacks = mutableListOf<Long>()
        val receiver = AudioReceiver(
            timeDomainConverter = timeDomainConverter,
            localAudioDacTracker = LocalAudioDacTracker(),
            decoderFactory = fakeDecoderFactory,
            onNackNeeded = { missingSeq -> missingNacks.add(missingSeq) },
            onStreamStateChanged = {}
        )

        // Simulate receiving packets 0, 1, 2 (no gaps)
        receiver.detectSequenceGaps(0L) { missingNacks.add(it) }
        receiver.detectSequenceGaps(1L) { missingNacks.add(it) }
        receiver.detectSequenceGaps(2L) { missingNacks.add(it) }
        assertTrue(missingNacks.isEmpty())

        // Simulate gap: packet 5 arrives (missing 3 and 4)
        receiver.detectSequenceGaps(5L) { missingNacks.add(it) }
        assertEquals(listOf(3L, 4L), missingNacks)

        // Simulate single gap: packet 7 arrives (missing 6)
        receiver.detectSequenceGaps(7L) { missingNacks.add(it) }
        assertEquals(listOf(3L, 4L, 6L), missingNacks)
    }

    @Test
    fun `JitterBuffer applies volume ramp smoothly across 16-bit PCM audio frames`() {
        // Create 240 frames of stereo audio (960 bytes) initialized to max amplitude
        val frames = 240
        val bytesPerFrame = 4
        val buffer = ByteArray(frames * bytesPerFrame)

        // Fill with full-scale positive sample 10000 (0x2710)
        for (i in 0 until frames) {
            val offset = i * bytesPerFrame
            buffer[offset] = 0x10.toByte()
            buffer[offset + 1] = 0x27.toByte()
            buffer[offset + 2] = 0x10.toByte()
            buffer[offset + 3] = 0x27.toByte()
        }

        // Apply fade-out from 1.0 down to 0.0
        jitterBuffer.applyVolumeRamp(buffer, 0, frames, startVol = 1.0f, endVol = 0.0f)

        // Check first sample (near startVol = 1.0): should be near 10000
        val firstSample = (buffer[1].toInt() shl 8) or (buffer[0].toInt() and 0xFF)
        assertEquals(10000, firstSample)

        // Check last sample (near endVol = 0.0): should be 0
        val lastOffset = (frames - 1) * bytesPerFrame
        val lastSample = (buffer[lastOffset + 1].toInt() shl 8) or (buffer[lastOffset].toInt() and 0xFF)
        assertEquals(0, lastSample)
    }

    @Test
    fun `JitterBuffer writeFadeToSilence fades out and writes silence on buffer underrun`() {
        val frames = 480
        val bytesPerFrame = 4
        val buffer = ByteArray(frames * bytesPerFrame)
        buffer.fill(0x55.toByte())

        jitterBuffer.writeFadeToSilence(buffer, 0, frames)

        // End of the buffer should be pure silence (all zeroes)
        for (i in (frames / 2) until frames) {
            val offset = i * bytesPerFrame
            assertEquals(0.toByte(), buffer[offset])
            assertEquals(0.toByte(), buffer[offset + 1])
            assertEquals(0.toByte(), buffer[offset + 2])
            assertEquals(0.toByte(), buffer[offset + 3])
        }
    }

    @Test
    fun `JitterBuffer fades in upon resuming playback after prebuffering`() {
        // Prebuffer 100ms of data (5 chunks of 20ms = 960 frames each)
        val pcmData = ByteArray(960 * 4) { 0x20.toByte() }
        for (i in 0 until 6) {
            jitterBuffer.addChunk(PcmChunk(pcmData.clone(), presentationTimeNs = (i + 1) * 20_000_000L, frames = 960, sampleRate = 48000))
        }

        val outBuffer = ByteArray(960 * 4)
        val result = jitterBuffer.getChunk(outBuffer, 960)
        assertTrue(result)
        // Volume should be 1.0f after smooth fade-in
        assertEquals(1.0f, jitterBuffer.currentVolume, 0.001f)
    }

    @Test
    fun `AudioReceiver initialized with high firstSequenceNumber does not trigger spurious NACKs on first packet`() {
        val missingNacks = mutableListOf<Long>()
        val receiver = AudioReceiver(
            timeDomainConverter = timeDomainConverter,
            localAudioDacTracker = LocalAudioDacTracker(),
            decoderFactory = fakeDecoderFactory,
            onNackNeeded = { missingSeq -> missingNacks.add(missingSeq) },
            onStreamStateChanged = {}
        )

        val streamConfig = com.example.greetingcard.protocol.AudioStreamConfig(
            sampleRate = 48000,
            channels = 2,
            bitrate = 128000,
            frameDurationUs = 20000
        )
        // Late-join peer joins at packet sequence #500
        receiver.startStream(streamConfig.toByteArray(), firstSequenceNumber = 500L)

        // First packet #500 arrives
        receiver.handleAudioData(
            Packet.build(
                packetType = SyncConstants.TYPE_AUDIO_DATA,
                payload = ByteArray(100),
                streamId = SyncConstants.STREAM_AUDIO,
                sequenceNumber = 500L,
                timestampNs = 1_000_000_000L,
                sessionUuid = UUID.randomUUID()
            )
        )
        assertTrue("Should not emit NACKs for packets 0..499", missingNacks.isEmpty())

        // Packet #502 arrives (missing #501)
        receiver.handleAudioData(
            Packet.build(
                packetType = SyncConstants.TYPE_AUDIO_DATA,
                payload = ByteArray(100),
                streamId = SyncConstants.STREAM_AUDIO,
                sequenceNumber = 502L,
                timestampNs = 1_040_000_000L,
                sessionUuid = UUID.randomUUID()
            )
        )
        assertEquals(listOf(501L), missingNacks)
    }

    @Test
    fun `AudioReceiver ignores stale pre-disconnect packets with sequence strictly less than expectedFirstSequenceNumber`() {
        val missingNacks = mutableListOf<Long>()
        val receiver = AudioReceiver(
            timeDomainConverter = timeDomainConverter,
            localAudioDacTracker = LocalAudioDacTracker(),
            decoderFactory = fakeDecoderFactory,
            onNackNeeded = { missingSeq -> missingNacks.add(missingSeq) },
            onStreamStateChanged = {}
        )

        val streamConfig = com.example.greetingcard.protocol.AudioStreamConfig(
            sampleRate = 48000,
            channels = 2,
            bitrate = 128000,
            frameDurationUs = 20000
        )
        receiver.startStream(streamConfig.toByteArray(), firstSequenceNumber = 500L)

        // Stale packet from previous connection arrives (#480)
        receiver.handleAudioData(
            Packet.build(
                packetType = SyncConstants.TYPE_AUDIO_DATA,
                payload = ByteArray(100),
                streamId = SyncConstants.STREAM_AUDIO,
                sequenceNumber = 480L,
                timestampNs = 960_000_000L,
                sessionUuid = UUID.randomUUID()
            )
        )
        // Stale packet dropped: no NACKs triggered, no gap created
        assertTrue(missingNacks.isEmpty())
    }

    @Test
    fun `JitterBuffer peekFirstChunkPresentationTimeNs and popChunk support PTS alignment dropping`() {
        val chunk1 = PcmChunk(ByteArray(960 * 4), presentationTimeNs = 100_000_000L, frames = 960, sampleRate = 48000)
        val chunk2 = PcmChunk(ByteArray(960 * 4), presentationTimeNs = 120_000_000L, frames = 960, sampleRate = 48000)
        val chunk3 = PcmChunk(ByteArray(960 * 4), presentationTimeNs = 140_000_000L, frames = 960, sampleRate = 48000)

        jitterBuffer.addChunk(chunk1)
        jitterBuffer.addChunk(chunk2)
        jitterBuffer.addChunk(chunk3)

        assertEquals(100_000_000L, jitterBuffer.peekFirstChunkPresentationTimeNs())

        // Drop first stale chunk
        val popped1 = jitterBuffer.popChunk()
        assertNotNull(popped1)
        assertEquals(100_000_000L, popped1?.presentationTimeNs)

        // Peek should now show second chunk
        assertEquals(120_000_000L, jitterBuffer.peekFirstChunkPresentationTimeNs())

        // Drop second stale chunk
        val popped2 = jitterBuffer.popChunk()
        assertNotNull(popped2)
        assertEquals(120_000_000L, popped2?.presentationTimeNs)

        // Peek should now show third chunk
        assertEquals(140_000_000L, jitterBuffer.peekFirstChunkPresentationTimeNs())
    }

    @Test
    fun `JitterBuffer drops duplicate and out-of-order stale chunks`() {
        val chunk1 = PcmChunk(ByteArray(960 * 4), presentationTimeNs = 100_000_000L, frames = 960, sampleRate = 48000, sequenceNumber = 1L)
        val chunk2 = PcmChunk(ByteArray(960 * 4), presentationTimeNs = 120_000_000L, frames = 960, sampleRate = 48000, sequenceNumber = 2L)
        val duplicateChunk2 = PcmChunk(ByteArray(960 * 4), presentationTimeNs = 120_000_000L, frames = 960, sampleRate = 48000, sequenceNumber = 2L)
        val staleChunk1 = PcmChunk(ByteArray(960 * 4), presentationTimeNs = 100_000_000L, frames = 960, sampleRate = 48000, sequenceNumber = 1L)
        val chunk3 = PcmChunk(ByteArray(960 * 4), presentationTimeNs = 140_000_000L, frames = 960, sampleRate = 48000, sequenceNumber = 3L)

        assertTrue(jitterBuffer.addChunk(chunk1))
        assertTrue(jitterBuffer.addChunk(chunk2))
        // Exact duplicate PTS <= highestSeen must be dropped
        assertFalse("Duplicate chunk must be rejected", jitterBuffer.addChunk(duplicateChunk2))
        // Older out-of-order chunk must be dropped
        assertFalse("Older out-of-order chunk must be rejected", jitterBuffer.addChunk(staleChunk1))
        // Newer chunk must be accepted
        assertTrue(jitterBuffer.addChunk(chunk3))
    }

    @Test
    fun `JitterBuffer clear resets highestPtsNsSeen so new sessions accept initial chunks`() {
        val chunkSession1 = PcmChunk(ByteArray(960 * 4), presentationTimeNs = 500_000_000L, frames = 960, sampleRate = 48000)
        assertTrue(jitterBuffer.addChunk(chunkSession1))

        jitterBuffer.clear()

        // Session 2 starts with a lower PTS baseline (e.g. 100ms)
        val chunkSession2 = PcmChunk(ByteArray(960 * 4), presentationTimeNs = 100_000_000L, frames = 960, sampleRate = 48000)
        assertTrue("After clear(), lower PTS must be accepted without stale rejection", jitterBuffer.addChunk(chunkSession2))
    }

    @Test
    fun `PendingNackTracker records, resolves, and fires saturation callback on excessive skips`() {
        var resyncTriggered = false
        val tracker = com.example.greetingcard.audio.PendingNackTracker(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            maxConsecutiveSkipsThreshold = 3,
            maxCumulativeSkippedNs = 60_000_000L,
            onSaturationThresholdExceeded = { resyncTriggered = true }
        )

        tracker.recordNack(10L)
        tracker.recordNack(11L)
        tracker.recordNack(12L)
        assertEquals(3, tracker.pendingCount)

        // Skip 1 and 2
        tracker.resolve(10L, wasSkipped = true)
        tracker.resolve(11L, wasSkipped = true)
        assertEquals(1, tracker.pendingCount)
        assertFalse(resyncTriggered)

        // Skip 3 -> hits threshold of 3
        tracker.resolve(12L, wasSkipped = true)
        assertEquals(0, tracker.pendingCount)
        assertTrue("Resync must be triggered when skip threshold is exceeded", resyncTriggered)
    }

    @Test
    fun `AudioReceiver repeatedly starts and stops across multiple reconnect cycles without leaking resources`() {
        val fakeDecoderFactory = object : com.example.greetingcard.audio.AudioDecoderFactory {
            override fun createOpusDecoder(sampleRate: Int, channels: Int): com.example.greetingcard.audio.AudioDecoder {
                return object : com.example.greetingcard.audio.AudioDecoder {
                    override fun dequeueInputBuffer(timeoutUs: Long): Int = -1
                    override fun getInputBuffer(index: Int): java.nio.ByteBuffer? = null
                    override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {}
                    override fun dequeueOutputBuffer(info: android.media.MediaCodec.BufferInfo, timeoutUs: Long): Int = -1
                    override fun getOutputBuffer(index: Int): java.nio.ByteBuffer? = null
                    override fun releaseOutputBuffer(index: Int, render: Boolean) {}
                    override fun stop() {}
                    override fun release() {}
                }
            }
        }

        val receiver = AudioReceiver(
            timeDomainConverter = timeDomainConverter,
            localAudioDacTracker = LocalAudioDacTracker(),
            decoderFactory = fakeDecoderFactory,
            onNackNeeded = {},
            onStreamStateChanged = {}
        )

        val streamConfig = com.example.greetingcard.protocol.AudioStreamConfig(
            sampleRate = 48000,
            channels = 2,
            bitrate = 128000,
            frameDurationUs = 20000
        )

        // Perform 10 rapid connect/disconnect cycles
        for (cycle in 1..10) {
            receiver.startStream(streamConfig.toByteArray(), firstSequenceNumber = cycle * 100L)
            receiver.stopStream()
        }
    }
}

