package com.example.greetingcard

import com.example.greetingcard.sync.NtpMath
import com.example.greetingcard.sync.NtpSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NtpMathTest {

    @Test
    fun `calculateOffsetNs accurately computes symmetric offset`() {
        // Case 1: Client and Leader identical clocks, 10ms RTT symmetric (5ms up, 5ms down)
        // t1 = 100ms, t2 = 105ms, t3 = 105ms, t4 = 110ms
        val sample1 = NtpSample(
            t1 = 100_000_000L,
            t2 = 105_000_000L,
            t3 = 105_000_000L,
            t4 = 110_000_000L
        )
        assertEquals(10_000_000L, sample1.rttNs)
        assertEquals(0L, NtpMath.calculateOffsetNs(sample1))
        assertEquals(0L, sample1.offsetNs)

        // Case 2: Leader clock is ahead by +20ms, 10ms RTT symmetric (5ms up, 5ms down)
        // t1 = 100ms, t2 = 125ms, t3 = 125ms, t4 = 110ms
        val sample2 = NtpSample(
            t1 = 100_000_000L,
            t2 = 125_000_000L,
            t3 = 125_000_000L,
            t4 = 110_000_000L
        )
        assertEquals(10_000_000L, sample2.rttNs)
        assertEquals(20_000_000L, NtpMath.calculateOffsetNs(sample2))
        assertEquals(20_000_000L, sample2.offsetNs)
    }

    @Test
    fun `filterAndCalculateBestOfNAverage filters dropouts and averages lowest RTT probes`() {
        val s1 = NtpSample(100_000_000L, 102_000_000L, 102_000_000L, 104_000_000L) // RTT = 4ms, offset = 0ms
        val s2 = NtpSample(100_000_000L, 103_000_000L, 103_000_000L, 106_000_000L) // RTT = 6ms, offset = 0ms
        val s3 = NtpSample(100_000_000L, 104_000_000L, 104_000_000L, 108_000_000L) // RTT = 8ms, offset = 0ms
        val s4 = NtpSample(100_000_000L, 150_000_000L, 150_000_000L, 200_000_000L) // RTT = 100ms (high queueing tail)

        val result = NtpMath.filterAndCalculateBestOfNAverage(listOf(s1, s2, s3, s4), bestN = 3)
        assertNotNull(result)
        assertEquals(3, result!!.validSampleCount)
        assertEquals(4, result.totalSampleCount)
        // Average RTT of 4ms, 6ms, 8ms is 6ms
        assertEquals(6_000_000L, result.medianRttNs)
        assertEquals(0L, result.medianOffsetNs)
    }

    @Test
    fun `empty sample list returns null`() {
        assertNull(NtpMath.filterAndCalculateBestOfNAverage(emptyList()))
        assertNull(NtpMath.filterAndCalculateMedian(emptyList()))
    }
}

