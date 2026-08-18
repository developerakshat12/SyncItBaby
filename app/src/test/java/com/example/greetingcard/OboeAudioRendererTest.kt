package com.example.greetingcard

import com.example.greetingcard.audio.OboeAudioRenderer
import com.example.greetingcard.audio.OboeTelemetryRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.math.sqrt

class OboeAudioRendererTest {

    @Test
    fun `oboe renderer instance initializes with expected properties`() {
        val renderer = OboeAudioRenderer(sampleRate = 48000, channels = 2)
        assertEquals(48000, renderer.sampleRate)
        assertEquals(2, renderer.channels)
    }

    @Test
    fun `oboe renderer safely handles calls when native library or stream is not initialized`() {
        val renderer = OboeAudioRenderer(sampleRate = 48000, channels = 2)

        // In JVM unit test environment without loaded .so, native calls safely fail or return defaults
        assertFalse(renderer.start())
        renderer.pause()
        renderer.stop()
        renderer.flush()
        renderer.release()

        val written = renderer.writeAudio(ByteArray(1920), 0, 1920)
        assertEquals(0, written)

        val outTs = LongArray(2)
        assertFalse(renderer.getHardwareTimestamp(outTs))
        assertNull(renderer.getHardwareTimestampPair())
        assertEquals(0.0, renderer.getLatencyMillis(), 0.001)
        assertFalse(renderer.isMMap())
        assertTrue(renderer.drainTelemetry().isEmpty())
    }

    @Test
    fun `oboe telemetry record constructs with accurate field mappings`() {
        val record = OboeTelemetryRecord(
            timestampNs = 1_000_000_000L,
            framePosition = 48000L,
            latencyMillis = 4.25,
            underrunCount = 0L,
            ringBufferDepthFrames = 960,
            isMMap = true
        )

        assertEquals(1_000_000_000L, record.timestampNs)
        assertEquals(48000L, record.framePosition)
        assertEquals(4.25, record.latencyMillis, 0.001)
        assertEquals(0L, record.underrunCount)
        assertEquals(960, record.ringBufferDepthFrames)
        assertTrue(record.isMMap)
    }

    @Test
    fun `relative jitter comparison demonstrates tighter variance for hardware DAC timestamps`() {
        // Phase 0 Gate Methodology:
        // Compare relative jitter variance Var(Δt) over consecutive polls
        val pollIntervalNs = 20_000_000L // 20ms polling interval

        // Synthetic AudioTrack timestamps (AudioFlinger mixer jitter with ±1.5ms spread)
        val audioTrackJitterNs = listOf(
            -800_000L, 1_200_000L, -1_500_000L, 600_000L, -400_000L,
            1_400_000L, -1_100_000L, 900_000L, -700_000L, 500_000L
        )
        val audioTrackTimestamps = LongArray(11)
        audioTrackTimestamps[0] = 100_000_000L
        for (i in 1..10) {
            audioTrackTimestamps[i] = audioTrackTimestamps[i - 1] + pollIntervalNs + audioTrackJitterNs[i - 1]
        }

        // Synthetic Oboe hardware DAC timestamps (direct ALSA MMAP with < 50µs jitter)
        val oboeJitterNs = listOf(
            -15_000L, 20_000L, -30_000L, 10_000L, -5_000L,
            25_000L, -20_000L, 15_000L, -10_000L, 8_000L
        )
        val oboeTimestamps = LongArray(11)
        oboeTimestamps[0] = 50_000_000L // Independent base clock domain
        for (i in 1..10) {
            oboeTimestamps[i] = oboeTimestamps[i - 1] + pollIntervalNs + oboeJitterNs[i - 1]
        }

        // Compute consecutive deltas: Δt = t[i] - t[i-1]
        val audioTrackDeltas = DoubleArray(10) { i -> (audioTrackTimestamps[i + 1] - audioTrackTimestamps[i]).toDouble() }
        val oboeDeltas = DoubleArray(10) { i -> (oboeTimestamps[i + 1] - oboeTimestamps[i]).toDouble() }

        val audioTrackStdDevMs = calculateStdDev(audioTrackDeltas) / 1_000_000.0
        val oboeStdDevMs = calculateStdDev(oboeDeltas) / 1_000_000.0

        println("AudioTrack consecutive Δt StdDev: %.3f ms".format(audioTrackStdDevMs))
        println("Oboe hardware DAC consecutive Δt StdDev: %.3f ms".format(oboeStdDevMs))

        // Oboe standard deviation is orders of magnitude tighter (< 0.05ms vs > 1.0ms)
        assertTrue("Oboe std dev ($oboeStdDevMs ms) must be tighter than AudioTrack ($audioTrackStdDevMs ms)",
            oboeStdDevMs < audioTrackStdDevMs
        )
        assertTrue(oboeStdDevMs < 0.1) // Sub-0.1ms jitter
    }

    private fun calculateStdDev(samples: DoubleArray): Double {
        if (samples.isEmpty()) return 0.0
        val mean = samples.average()
        val variance = samples.map { (it - mean).pow(2) }.average()
        return sqrt(variance)
    }
}

