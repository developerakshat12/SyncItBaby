package com.example.greetingcard

import com.example.greetingcard.audio.JitterBuffer
import com.example.greetingcard.audio.PcmChunk
import com.example.greetingcard.sync.DefaultTimeDomainConverter
import com.example.greetingcard.sync.Ticker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin

class JitterBufferTrimTest {

    private class SyntheticTicker : Ticker {
        var timeNs: Long = 10_000_000_000L
        override fun readNanos(): Long = timeNs
        override fun convertMonotonicNanosToTickerNs(monotonicNs: Long): Long = monotonicNs
    }

    private lateinit var ticker: SyntheticTicker
    private lateinit var converter: DefaultTimeDomainConverter
    private lateinit var jitterBuffer: JitterBuffer

    private val sampleRate = 48000
    private val channels = 2
    private val bytesPerFrame = channels * 2

    @Before
    fun setUp() {
        ticker = SyntheticTicker()
        converter = DefaultTimeDomainConverter(ticker)
        jitterBuffer = JitterBuffer(converter, bufferDepthMs = 200, sampleRate = sampleRate)
    }

    private fun generateSineWaveChunk(startFrame: Long, framesCount: Int, freq: Double = 440.0): PcmChunk {
        val pcm = ByteArray(framesCount * bytesPerFrame)
        val buf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until framesCount) {
            val t = (startFrame + f).toDouble() / sampleRate
            val sampleVal = (sin(2.0 * Math.PI * freq * t) * 16000.0).toInt().toShort()
            buf.putShort(sampleVal) // Left
            buf.putShort(sampleVal) // Right
        }
        val ptsNs = (startFrame * 1_000_000_000L) / sampleRate
        return PcmChunk(pcm, presentationTimeNs = ptsNs, frames = framesCount, sampleRate = sampleRate)
    }

    @Test
    fun `applyTrimAdjustment with positive delta advances cumulativeDriftCorrection and blends audio`() {
        // Pre-fill buffer with 500ms of continuous 440Hz sine wave (24,000 frames)
        var currentFrame = 0L
        for (i in 0 until 25) {
            val chunk = generateSineWaveChunk(currentFrame, 960)
            jitterBuffer.addChunk(chunk)
            currentFrame += 960
        }

        // Apply +240 frames trim (+5.0 ms skip)
        jitterBuffer.applyTrimAdjustment(240)

        assertEquals(240L, jitterBuffer.cumulativeDriftCorrection)

        // Read 960 output frames
        val output = ByteArray(960 * bytesPerFrame)
        val ok = jitterBuffer.getChunk(output, 960)
        assertTrue(ok)

        // Verify that sample-to-sample derivative step across the crossfaded output has no catastrophic click jump
        val buf = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)
        var prevSample = buf.getShort()
        buf.getShort() // Right channel

        var maxDelta = 0
        while (buf.hasRemaining()) {
            val left = buf.getShort()
            val right = buf.getShort()
            val delta = abs(left - prevSample)
            if (delta > maxDelta) maxDelta = delta
            prevSample = left
        }

        // Under 440Hz sine at 16000 amplitude, normal max step is ~2 * pi * 440 / 48000 * 16000 = 921
        // Crossfade guarantees max step is bounded well within smooth range (< 6000), preventing high-frequency clicks
        assertTrue("Max derivative delta ($maxDelta) should be bounded without sharp click step", maxDelta < 6000)
    }

    @Test
    fun `applyTrimAdjustment with negative delta inserts silence stall and tracks negative cumulativeDriftCorrection`() {
        // Pre-fill buffer
        for (i in 0 until 15) {
            val chunk = generateSineWaveChunk((i * 960).toLong(), 960)
            jitterBuffer.addChunk(chunk)
        }

        // Apply -240 frames trim (-5.0 ms stall)
        jitterBuffer.applyTrimAdjustment(-240)

        assertEquals(-240L, jitterBuffer.cumulativeDriftCorrection)

        val output = ByteArray(960 * bytesPerFrame)
        val ok = jitterBuffer.getChunk(output, 960)
        assertTrue(ok)

        // First 240 frames (480 samples = 960 bytes) must be silence
        val buf = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until 240) {
            val left = buf.getShort()
            val right = buf.getShort()
            assertEquals("Stall frame $f must be silence", 0.toShort(), left)
            assertEquals("Stall frame $f must be silence", 0.toShort(), right)
        }
    }

    @Test
    fun `trimCommandQueue is drained safely inside getChunk audio loop`() {
        // Pre-fill buffer
        for (i in 0 until 15) {
            val chunk = generateSineWaveChunk((i * 960).toLong(), 960)
            jitterBuffer.addChunk(chunk)
        }

        // UI thread pushes a trim change of +48 frames (+1.0 ms)
        jitterBuffer.trimCommandQueue.offer(48)
        assertEquals(0L, jitterBuffer.cumulativeDriftCorrection) // Not processed yet

        // Audio thread runs getChunk()
        val output = ByteArray(960 * bytesPerFrame)
        val ok = jitterBuffer.getChunk(output, 960)
        assertTrue(ok)

        // Verified processed on audio thread
        assertEquals(48L, jitterBuffer.cumulativeDriftCorrection)
        assertTrue(jitterBuffer.trimCommandQueue.isEmpty())
    }
}

