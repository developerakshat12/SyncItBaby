package com.example.greetingcard

import com.example.greetingcard.audio.BufferHealth
import com.example.greetingcard.audio.JitterBuffer
import com.example.greetingcard.audio.PcmChunk
import com.example.greetingcard.sync.DefaultTimeDomainConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class JitterBufferTest {

    private lateinit var jitterBuffer: JitterBuffer
    private val sampleRate = 48000
    private val bytesPerFrame = 4 // 16-bit stereo = 2 channels * 2 bytes

    @Before
    fun setUp() {
        val converter = DefaultTimeDomainConverter()
        // Initialize with 0ms pre-buffer depth for immediate read testing
        jitterBuffer = JitterBuffer(converter, bufferDepthMs = 0L, sampleRate = sampleRate)
    }

    private fun createSyntheticPcmChunk(
        startFrameIndex: Int,
        framesCount: Int,
        ptsNs: Long = 1_000_000_000L
    ): PcmChunk {
        val buffer = ByteBuffer.allocate(framesCount * bytesPerFrame).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until framesCount) {
            val sampleVal = (startFrameIndex + i).toShort()
            buffer.putShort(sampleVal) // Left channel
            buffer.putShort(sampleVal) // Right channel
        }
        return PcmChunk(
            pcmData = buffer.array(),
            presentationTimeNs = ptsNs,
            frames = framesCount,
            sampleRate = sampleRate
        )
    }

    private fun readFrameVal(buffer: ByteArray, frameIndex: Int): Short {
        val byteOffset = frameIndex * bytesPerFrame
        val bb = ByteBuffer.wrap(buffer, byteOffset, bytesPerFrame).order(ByteOrder.LITTLE_ENDIAN)
        return bb.short
    }

    @Test
    fun `sample insertion expands output buffer by duplicating frame at stride interval`() {
        // Enqueue 20 frames: values 0 to 19
        jitterBuffer.addChunk(createSyntheticPcmChunk(startFrameIndex = 0, framesCount = 20))

        // Request 10 frames with sample insertion (+1) -> readFramesCount = 9, stride = 9
        // Duplicate occurs at source index 8 (value 8)
        jitterBuffer.driftCorrectionSamples = 1
        val output = ByteArray(10 * bytesPerFrame)
        val success = jitterBuffer.getChunk(output, 10)

        assertTrue(success)
        assertEquals(0, jitterBuffer.driftCorrectionSamples)
        assertEquals(-1L, jitterBuffer.cumulativeDriftCorrection)

        // Frames 0..8 should have values 0..8
        for (i in 0..8) {
            assertEquals(i.toShort(), readFrameVal(output, i))
        }

        // Frame 9 must be a duplicate of frame 8 (value 8)
        assertEquals(8.toShort(), readFrameVal(output, 9))

        // Next read (nominal) for 5 frames should start at value 9 (since only 9 frames were consumed from source)
        val nextOutput = ByteArray(5 * bytesPerFrame)
        val nextSuccess = jitterBuffer.getChunk(nextOutput, 5)

        assertTrue(nextSuccess)
        for (i in 0..4) {
            assertEquals((9 + i).toShort(), readFrameVal(nextOutput, i))
        }
    }

    @Test
    fun `strided multi-sample insertion distributes duplicated frames evenly across buffer`() {
        // Enqueue 20 frames: values 0 to 19
        jitterBuffer.addChunk(createSyntheticPcmChunk(startFrameIndex = 0, framesCount = 20))

        // Request 10 frames with multi-sample insertion (+2) -> readFramesCount = 8, stride = 4
        // Insertion points at source index 3 (value 3) and index 7 (value 7)
        // Resulting 10 frames: [0, 1, 2, 3, 3, 4, 5, 6, 7, 7]
        jitterBuffer.driftCorrectionSamples = 2
        val output = ByteArray(10 * bytesPerFrame)
        val success = jitterBuffer.getChunk(output, 10)

        assertTrue(success)
        assertEquals(0, jitterBuffer.driftCorrectionSamples)
        assertEquals(-2L, jitterBuffer.cumulativeDriftCorrection)

        val expected = shortArrayOf(0, 1, 2, 3, 3, 4, 5, 6, 7, 7)
        for (i in expected.indices) {
            assertEquals("Mismatch at frame $i", expected[i], readFrameVal(output, i))
        }

        // Next nominal read starts at frame 8
        val nextOutput = ByteArray(5 * bytesPerFrame)
        val nextSuccess = jitterBuffer.getChunk(nextOutput, 5)
        assertTrue(nextSuccess)
        for (i in 0..4) {
            assertEquals((8 + i).toShort(), readFrameVal(nextOutput, i))
        }
    }

    @Test
    fun `sample drop skips 1 frame at stride interval`() {
        // Enqueue 30 frames: values 0 to 29
        jitterBuffer.addChunk(createSyntheticPcmChunk(startFrameIndex = 0, framesCount = 30))

        // Request 10 frames with sample drop (-1) -> totalFramesToRead = 11, safeDropCount = 1, stride = 11
        // Frame at source index 10 (value 10) is skipped
        jitterBuffer.driftCorrectionSamples = -1
        val output = ByteArray(10 * bytesPerFrame)
        val success = jitterBuffer.getChunk(output, 10)

        assertTrue(success)
        assertEquals(0, jitterBuffer.driftCorrectionSamples)
        assertEquals(1L, jitterBuffer.cumulativeDriftCorrection)

        // Output should contain frames 0..9
        for (i in 0..9) {
            assertEquals(i.toShort(), readFrameVal(output, i))
        }

        // Next nominal read must start at frame 11 (because frame 10 was skipped)
        val nextOutput = ByteArray(5 * bytesPerFrame)
        val nextSuccess = jitterBuffer.getChunk(nextOutput, 5)

        assertTrue(nextSuccess)
        for (i in 0..4) {
            assertEquals((11 + i).toShort(), readFrameVal(nextOutput, i))
        }
    }

    @Test
    fun `strided multi-sample drop evenly distributes skipped frames`() {
        // Enqueue 30 frames: values 0 to 29
        jitterBuffer.addChunk(createSyntheticPcmChunk(startFrameIndex = 0, framesCount = 30))

        // Request 10 frames with multi-sample drop (-2) -> totalFramesToRead = 12, safeDropCount = 2, stride = 6
        // Skipped at source indices 5 (value 5) and 11 (value 11)
        // Resulting 10 frames: [0, 1, 2, 3, 4, 6, 7, 8, 9, 10]
        jitterBuffer.driftCorrectionSamples = -2
        val output = ByteArray(10 * bytesPerFrame)
        val success = jitterBuffer.getChunk(output, 10)

        assertTrue(success)
        assertEquals(0, jitterBuffer.driftCorrectionSamples)
        assertEquals(2L, jitterBuffer.cumulativeDriftCorrection)

        val expected = shortArrayOf(0, 1, 2, 3, 4, 6, 7, 8, 9, 10)
        for (i in expected.indices) {
            assertEquals("Mismatch at frame $i", expected[i], readFrameVal(output, i))
        }

        // Next nominal read starts at frame 12 (since frames 0..11 were consumed)
        val nextOutput = ByteArray(5 * bytesPerFrame)
        val nextSuccess = jitterBuffer.getChunk(nextOutput, 5)
        assertTrue(nextSuccess)
        for (i in 0..4) {
            assertEquals((12 + i).toShort(), readFrameVal(nextOutput, i))
        }
    }

    @Test
    fun `nominal read consumes exact requested frames without alteration`() {
        jitterBuffer.addChunk(createSyntheticPcmChunk(startFrameIndex = 0, framesCount = 20))

        jitterBuffer.driftCorrectionSamples = 0
        val output = ByteArray(10 * bytesPerFrame)
        val success = jitterBuffer.getChunk(output, 10)

        assertTrue(success)
        for (i in 0..9) {
            assertEquals(i.toShort(), readFrameVal(output, i))
        }

        // Next read starts exactly at frame 10
        val nextOutput = ByteArray(5 * bytesPerFrame)
        val nextSuccess = jitterBuffer.getChunk(nextOutput, 5)
        assertTrue(nextSuccess)
        for (i in 0..4) {
            assertEquals((10 + i).toShort(), readFrameVal(nextOutput, i))
        }
    }

    @Test
    fun `bulkSkip discards requested frames and advances cumulativeDriftCorrection`() {
        jitterBuffer.addChunk(createSyntheticPcmChunk(startFrameIndex = 0, framesCount = 100))

        val skipped = jitterBuffer.bulkSkip(40)
        assertEquals(40, skipped)
        assertEquals(40L, jitterBuffer.cumulativeDriftCorrection)

        // Reading 10 frames should now get frames 40..49
        val output = ByteArray(10 * bytesPerFrame)
        val success = jitterBuffer.getChunk(output, 10)
        assertTrue(success)
        for (i in 0..9) {
            assertEquals((40 + i).toShort(), readFrameVal(output, i))
        }
    }

    @Test
    fun `bulkStall outputs silence and decrements cumulativeDriftCorrection`() {
        jitterBuffer.addChunk(createSyntheticPcmChunk(startFrameIndex = 0, framesCount = 20))

        jitterBuffer.bulkStall(5)
        assertEquals(-5L, jitterBuffer.cumulativeDriftCorrection)

        // Read 10 frames: first 5 are silence (0), next 5 are frames 0..4
        val output = ByteArray(10 * bytesPerFrame)
        val success = jitterBuffer.getChunk(output, 10)
        assertTrue(success)

        for (i in 0..4) {
            assertEquals(0.toShort(), readFrameVal(output, i))
        }
        for (i in 5..9) {
            assertEquals((i - 5).toShort(), readFrameVal(output, i))
        }
    }

    @Test
    fun `clampCorrection protects buffer health under cascade control`() {
        // Buffer is empty -> CRITICAL health
        assertEquals(BufferHealth.CRITICAL, jitterBuffer.getBufferHealth())
        // Dropping frames when CRITICAL must be clamped to 0
        assertEquals(0, jitterBuffer.clampCorrection(-2))
        // Inserting frames is allowed
        assertEquals(2, jitterBuffer.clampCorrection(2))
    }

    @Test
    fun `clear resets driftCorrectionSamples and buffer state`() {
        jitterBuffer.driftCorrectionSamples = 1
        jitterBuffer.bulkStall(10)
        jitterBuffer.clear()
        assertEquals(0, jitterBuffer.driftCorrectionSamples)
        assertEquals(0L, jitterBuffer.cumulativeDriftCorrection)
    }
}

