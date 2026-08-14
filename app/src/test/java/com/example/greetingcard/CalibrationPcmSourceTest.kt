package com.example.greetingcard

import com.example.greetingcard.audio.CalibrationPcmSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationPcmSourceTest {

    @Test
    fun `calibration source generates expected byte count for 10 second duration`() {
        val sampleRate = 48000
        val channels = 2
        val durationSeconds = 10
        val expectedBytes = sampleRate * channels * 2 * durationSeconds // 1,920,000 bytes

        val source = CalibrationPcmSource(
            sampleRate = sampleRate,
            channels = channels,
            durationSeconds = durationSeconds
        )

        var totalBytesRead = 0
        val buffer = ByteArray(4096)
        while (true) {
            val bytesRead = source.read(buffer, 0, buffer.size)
            if (bytesRead < 0) break
            totalBytesRead += bytesRead
        }

        assertEquals(expectedBytes, totalBytesRead)
        // Additional reads after EOF must return -1
        assertEquals(-1, source.read(buffer, 0, buffer.size))
    }

    @Test
    fun `calibration source audio contains non-zero sound samples`() {
        val source = CalibrationPcmSource(
            sampleRate = 48000,
            channels = 2,
            durationSeconds = 1
        )

        val buffer = ByteArray(48000 * 4)
        val bytesRead = source.read(buffer, 0, buffer.size)
        assertEquals(48000 * 4, bytesRead)

        var nonZeroCount = 0
        for (b in buffer) {
            if (b != 0.toByte()) {
                nonZeroCount++
            }
        }
        // At 440Hz 50ms beep, we expect thousands of non-zero audio bytes
        assertTrue("Expected non-zero audio bytes but got $nonZeroCount", nonZeroCount > 1000)
    }
}

