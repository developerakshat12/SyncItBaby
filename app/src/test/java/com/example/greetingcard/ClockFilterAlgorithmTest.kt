package com.example.greetingcard

import com.example.greetingcard.sync.ClockFilterAlgorithm
import com.example.greetingcard.sync.NtpSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClockFilterAlgorithmTest {

    private lateinit var clockFilter: ClockFilterAlgorithm

    @Before
    fun setUp() {
        clockFilter = ClockFilterAlgorithm()
    }

    @Test
    fun `ClockFilter returns null on empty sample list when register is empty`() {
        val result = clockFilter.processSamples(emptyList(), currentLocalTimeNs = 1_000_000_000L)
        assertNull(result)
    }

    @Test
    fun `ClockFilter selects single minimum-RTT sample out of burst`() {
        val now = 10_000_000_000L

        // 4 samples with different RTTs
        // Sample 1: RTT = 20ms, offset = +5ms
        val s1 = NtpSample(t1 = 100_000_000, t2 = 115_000_000, t3 = 115_000_000, t4 = 120_000_000) // rtt=20ms, offset=+5ms
        // Sample 2: RTT = 4ms, offset = +2ms (BEST)
        val s2 = NtpSample(t1 = 100_000_000, t2 = 104_000_000, t3 = 104_000_000, t4 = 104_000_000) // rtt=4ms, offset=+2ms
        // Sample 3: RTT = 50ms, offset = +12ms
        val s3 = NtpSample(t1 = 100_000_000, t2 = 137_000_000, t3 = 137_000_000, t4 = 150_000_000) // rtt=50ms, offset=+12ms
        // Sample 4: RTT = 10ms, offset = +3ms
        val s4 = NtpSample(t1 = 100_000_000, t2 = 108_000_000, t3 = 108_000_000, t4 = 110_000_000) // rtt=10ms, offset=+3ms

        val result = clockFilter.processSamples(listOf(s1, s2, s3, s4), currentLocalTimeNs = now)
        assertNotNull(result)
        assertEquals(s2.offsetNs, result!!.offsetNs)
        assertEquals(s2.rttNs, result.rttNs)
        assertEquals(4, result.registerDepth)
        assertEquals(0, result.spikeSuppressedCount)
    }

    @Test
    fun `ClockFilter maintains 16-slot shift register and evicts oldest entry`() {
        val now = 10_000_000_000L

        // Ingest 20 samples one by one
        for (i in 1..20) {
            val sample = NtpSample(
                t1 = 0,
                t2 = (i * 1_000_000L),
                t3 = (i * 1_000_000L),
                t4 = 2_000_000L
            )
            clockFilter.processSample(sample, currentLocalTimeNs = now + (i * 100_000_000L))
        }

        assertEquals(16, clockFilter.activeSampleCount)
    }

    @Test
    fun `ClockFilter suppresses popcorn spike when offset deviates and RTT is high`() {
        val now = 10_000_000_000L

        // First seed with 4 consistent samples: RTT = 4ms, offset = 10ms
        val baseSamples = (1..4).map {
            NtpSample(t1 = 0, t2 = 12_000_000, t3 = 12_000_000, t4 = 4_000_000) // rtt=4ms, offset=10ms
        }
        clockFilter.processSamples(baseSamples, currentLocalTimeNs = now)
        assertEquals(4, clockFilter.activeSampleCount)

        // Now inject a popcorn spike: RTT = 80ms (20x min-RTT), offset = 50ms (+40ms jump)
        val spikeSample = NtpSample(t1 = 0, t2 = 90_000_000, t3 = 90_000_000, t4 = 80_000_000) // rtt=80ms, offset=50ms
        val result = clockFilter.processSamples(listOf(spikeSample), currentLocalTimeNs = now + 1_000_000_000L)

        assertNotNull(result)
        // Spike should be suppressed, so register size remains 4 and spikeSuppressedCount is 1
        assertEquals(4, result!!.registerDepth)
        assertEquals(1, result.spikeSuppressedCount)
        // Selected offset should remain the legitimate 10ms sample
        assertEquals(10_000_000L, result.offsetNs)
    }

    @Test
    fun `ClockFilter tracks dispersion growth over elapsed time`() {
        val t0 = 10_000_000_000L // 10s
        val sample = NtpSample(t1 = 0, t2 = 6_000_000, t3 = 6_000_000, t4 = 4_000_000) // rtt=4ms, halfRtt=2ms

        val result1 = clockFilter.processSample(sample, currentLocalTimeNs = t0)
        assertNotNull(result1)
        val initialDispersion = result1!!.dispersionNs

        // Advance local time by 10 seconds (10,000,000,000 ns)
        // 10s * 15 ppm = 150,000 ns = 0.15ms dispersion growth
        val t1 = t0 + 10_000_000_000L
        val result2 = clockFilter.processSamples(emptyList(), currentLocalTimeNs = t1)
        assertNotNull(result2)

        val agedDispersion = result2!!.dispersionNs
        assertTrue("Aged dispersion ($agedDispersion) should exceed initial ($initialDispersion)", agedDispersion > initialDispersion)
        val delta = agedDispersion - initialDispersion
        // Expected ~150,000 ns
        assertTrue("Dispersion growth delta should be ~150us", delta in 140_000L..160_000L)
    }

    @Test
    fun `ClockFilter reset clears shift register`() {
        val sample = NtpSample(t1 = 0, t2 = 6_000_000, t3 = 6_000_000, t4 = 4_000_000)
        clockFilter.processSample(sample, currentLocalTimeNs = 1_000_000_000L)
        assertEquals(1, clockFilter.activeSampleCount)

        clockFilter.reset()
        assertEquals(0, clockFilter.activeSampleCount)
        assertNull(clockFilter.getBestEstimate(currentLocalTimeNs = 1_000_000_000L))
    }
}

