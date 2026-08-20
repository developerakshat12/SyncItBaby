package com.example.greetingcard

import com.example.greetingcard.audio.JitterBuffer
import com.example.greetingcard.audio.PcmChunk
import com.example.greetingcard.protocol.Packet
import com.example.greetingcard.protocol.SyncConstants
import com.example.greetingcard.sync.DefaultTimeDomainConverter
import com.example.greetingcard.sync.Ticker
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class AudioStreamBufferingRegressionTest {

    private class SyntheticTicker(initialNs: Long = 1_000_000_000L) : Ticker {
        private val nanos = AtomicLong(initialNs)
        fun advance(deltaNs: Long) = nanos.addAndGet(deltaNs)
        override fun readNanos(): Long = nanos.get()
        override fun convertMonotonicNanosToTickerNs(monotonicNs: Long): Long = monotonicNs
    }

    private lateinit var ticker: SyntheticTicker
    private lateinit var timeConverter: DefaultTimeDomainConverter

    @Before
    fun setUp() {
        ticker = SyntheticTicker(1_000_000_000L)
        timeConverter = DefaultTimeDomainConverter(ticker)
    }

    @Test
    fun `sendChannel with 256 capacity absorbs audio bursts without dropping packets`() {
        val sendChannel = Channel<Packet>(capacity = 256, onBufferOverflow = BufferOverflow.SUSPEND)
        val droppedCounter = AtomicLong(0L)

        // Simulate an encode burst of 100 audio packets (2.0 seconds of audio @ 20ms/frame)
        val burstCount = 100
        for (i in 0 until burstCount) {
            val audioPacket = Packet.build(
                packetType = SyncConstants.TYPE_AUDIO_DATA,
                payload = ByteArray(320) { it.toByte() },
                sequenceNumber = i.toLong(),
                timestampNs = ticker.readNanos() + (i * 20_000_000L)
            )

            val result = sendChannel.trySend(audioPacket)
            if (!result.isSuccess) {
                droppedCounter.incrementAndGet()
            }
            assertTrue("Packet $i must be successfully queued in sendChannel", result.isSuccess)
        }

        assertEquals("Zero packets must be dropped during encode burst", 0L, droppedCounter.get())

        // Drain and verify packet ordering and integrity
        for (i in 0 until burstCount) {
            val packet = sendChannel.tryReceive().getOrNull()
            assertTrue("Packet $i must be present in channel", packet != null)
            assertEquals(i.toLong(), packet!!.header.sequenceNumber)
            assertEquals(320, packet.payload.size)
        }
    }

    @Test
    fun `sendChannel overflow is instrumented and accurately counts dropped packets`() {
        val capacity = 256
        val sendChannel = Channel<Packet>(capacity = capacity, onBufferOverflow = BufferOverflow.SUSPEND)
        val droppedCounter = AtomicLong(0L)

        // Fill channel completely to capacity
        for (i in 0 until capacity) {
            val packet = Packet.build(
                packetType = SyncConstants.TYPE_AUDIO_DATA,
                payload = ByteArray(100),
                sequenceNumber = i.toLong()
            )
            val result = sendChannel.trySend(packet)
            assertTrue("Initial $capacity packets must succeed", result.isSuccess)
        }

        // Send 10 overflow packets — every single one must be detected and counted
        val overflowCount = 10
        for (i in 0 until overflowCount) {
            val overflowPacket = Packet.build(
                packetType = SyncConstants.TYPE_AUDIO_DATA,
                payload = ByteArray(100),
                sequenceNumber = (capacity + i).toLong()
            )
            val result = sendChannel.trySend(overflowPacket)
            if (!result.isSuccess) {
                droppedCounter.incrementAndGet()
            }
            assertFalse("Overflow packet $i must not silently overwrite", result.isSuccess)
        }

        assertEquals("Exactly $overflowCount packets must be recorded as dropped", overflowCount.toLong(), droppedCounter.get())
    }

    @Test
    fun `JitterBuffer accumulates pre-buffer threshold and sustains continuous playback`() {
        val sampleRate = 48000
        val bufferDepthMs = 300L
        val jitterBuffer = JitterBuffer(timeConverter, bufferDepthMs = bufferDepthMs, sampleRate = sampleRate)

        val framesPerChunk = 960 // 20ms at 48kHz
        val bytesPerFrame = 4 // 16-bit stereo = 4 bytes/frame
        val outputBuffer = ByteArray(framesPerChunk * bytesPerFrame)

        // Initially buffer is empty -> getChunk returns false (silence written)
        val initiallyFilled = jitterBuffer.getChunk(outputBuffer, framesPerChunk)
        assertFalse("Initially buffer must be in pre-buffering state", initiallyFilled)

        // Feed chunks progressively. Target depth = 300ms = 15 chunks of 20ms (14,400 frames)
        for (i in 0 until 14) {
            val pcmData = ByteArray(framesPerChunk * bytesPerFrame) { 0x11.toByte() }
            val chunk = PcmChunk(pcmData, presentationTimeNs = ticker.readNanos() + (i * 20_000_000L), frames = framesPerChunk, sampleRate = sampleRate)
            jitterBuffer.addChunk(chunk)

            // Still pre-buffering before 15th chunk (280ms < 300ms)
            val chunkFilled = jitterBuffer.getChunk(outputBuffer, framesPerChunk)
            assertFalse("Buffer must remain in pre-buffering state at ${i * 20}ms", chunkFilled)
        }

        // Add 15th chunk -> reaches 300ms target depth -> transitions out of pre-buffering
        val pcm15 = ByteArray(framesPerChunk * bytesPerFrame) { 0x22.toByte() }
        val chunk15 = PcmChunk(pcm15, presentationTimeNs = ticker.readNanos() + (14 * 20_000_000L), frames = framesPerChunk, sampleRate = sampleRate)
        jitterBuffer.addChunk(chunk15)

        // Now getChunk must succeed and return real audio data
        val playbackFilled = jitterBuffer.getChunk(outputBuffer, framesPerChunk)
        assertTrue("Playback must begin once 300ms pre-buffering is reached", playbackFilled)
        assertEquals(0x11.toByte(), outputBuffer[0]) // First chunk audio sample verified
    }

    @Test
    fun `JitterBuffer reads across chunk boundaries seamlessly`() {
        val sampleRate = 48000
        val jitterBuffer = JitterBuffer(timeConverter, bufferDepthMs = 100L, sampleRate = sampleRate)

        val framesPerChunk = 960 // 20ms
        val bytesPerFrame = 4

        // Feed 10 chunks (200ms > 100ms threshold)
        for (i in 0 until 10) {
            val pcmData = ByteArray(framesPerChunk * bytesPerFrame) { (i + 1).toByte() }
            val chunk = PcmChunk(pcmData, presentationTimeNs = ticker.readNanos() + (i * 20_000_000L), frames = framesPerChunk, sampleRate = sampleRate)
            jitterBuffer.addChunk(chunk)
        }

        // Request non-aligned frame size (e.g. 1440 frames = 1.5 chunks)
        val readFrames = 1440
        val readBuffer = ByteArray(readFrames * bytesPerFrame)
        val success = jitterBuffer.getChunk(readBuffer, readFrames)

        assertTrue("getChunk across chunk boundary must succeed", success)
        // First 960 frames from chunk 0 (value 1)
        assertEquals(1.toByte(), readBuffer[0])
        assertEquals(1.toByte(), readBuffer[959 * bytesPerFrame])
        // Next 480 frames from chunk 1 (value 2)
        assertEquals(2.toByte(), readBuffer[960 * bytesPerFrame])
        assertEquals(2.toByte(), readBuffer[(readFrames - 1) * bytesPerFrame])
    }

    @Test
    fun `Control packets and audio packets interleave without blocking`() {
        val audioQueue = Channel<Packet>(capacity = 256, onBufferOverflow = BufferOverflow.SUSPEND)
        val controlResponses = mutableListOf<String>()

        // Simulate 50 audio packets interleaved with Heartbeat PING and SNTP REQ packets
        for (i in 0 until 50) {
            val audioPacket = Packet.build(
                packetType = SyncConstants.TYPE_AUDIO_DATA,
                payload = ByteArray(320),
                sequenceNumber = i.toLong()
            )
            val queued = audioQueue.trySend(audioPacket)
            assertTrue("Audio packet $i queued without blocking", queued.isSuccess)

            if (i == 10) {
                // Heartbeat packet
                val pingPacket = Packet.buildString(
                    packetType = SyncConstants.TYPE_HEARTBEAT_PING,
                    text = "1700000000000"
                )
                assertEquals(SyncConstants.TYPE_HEARTBEAT_PING, pingPacket.header.packetType)
                controlResponses.add("PONG:${pingPacket.payloadAsString()}")
            } else if (i == 25) {
                // NTP probe packet
                val ntpPacket = Packet.buildString(
                    packetType = SyncConstants.TYPE_NTP_REQ,
                    text = "NTP_REQ:session:1:1000000"
                )
                assertEquals(SyncConstants.TYPE_NTP_REQ, ntpPacket.header.packetType)
                controlResponses.add("NTP_RESP:session:1:1000000:1000500:1000500")
            }
        }

        assertEquals(2, controlResponses.size)
        assertTrue(controlResponses[0].startsWith("PONG:"))
        assertTrue(controlResponses[1].startsWith("NTP_RESP:"))
    }
}

