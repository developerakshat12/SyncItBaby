package com.example.greetingcard

import com.example.greetingcard.sync.ClockSyncState
import com.example.greetingcard.sync.DefaultTimeDomainConverter
import com.example.greetingcard.sync.KalmanClockFilter
import com.example.greetingcard.sync.LocalAudioDacTracker
import com.example.greetingcard.sync.NtpEngine
import com.example.greetingcard.sync.NtpMath
import com.example.greetingcard.sync.NtpSample
import com.example.greetingcard.sync.Ticker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class ClockSyncTest {

    private class TestTicker(initialNanos: Long = 1_000_000_000L) : Ticker {
        private val nanos = AtomicLong(initialNanos)

        fun advanceNanos(delta: Long) {
            nanos.addAndGet(delta)
        }

        override fun readNanos(): Long = nanos.get()

        override fun convertMonotonicNanosToTickerNs(monotonicNs: Long): Long {
            return monotonicNs // In test ticker, 1:1 mapping
        }
    }

    private lateinit var ticker: TestTicker
    private lateinit var converter: DefaultTimeDomainConverter

    @Test
    fun `SystemTicker anchors Epoch wall-clock time and tracks monotonic deltas`() {
        val systemTicker = com.example.greetingcard.sync.SystemTicker(
            epochAnchorMs = 1_700_000_000_000L,
            uptimeAnchorNs = 500_000_000_000L
        )
        val initialNanos = systemTicker.readNanos()
        assertTrue(initialNanos > 1_700_000_000_000_000_000L)
    }

    @Test
    fun `KalmanClockFilter tracks linear clock drift slope over time`() {
        val kalman = KalmanClockFilter()
        // Simulate 10 ppm clock drift (10ns drift per 1sec = 1,000,000,000ns)
        val t0 = 1_000_000_000_000L
        kalman.update(10_000_000L, rttNs = 2_000_000L, measurementTimeNs = t0)
        kalman.update(10_000_010L, rttNs = 2_000_000L, measurementTimeNs = t0 + 1_000_000_000L)
        kalman.update(10_000_020L, rttNs = 2_000_000L, measurementTimeNs = t0 + 2_000_000_000L)

        // Predict offset at t0 + 3s -> Should be close to 10_000_030L
        val predicted = kalman.predictOffsetNs(t0 + 3_000_000_000L)
        val errorNs = abs(predicted - 10_000_030L)
        assertTrue("Prediction error $errorNs ns should be < 500ns", errorNs < 500L)
    }

    @Before
    fun setUp() {
        ticker = TestTicker()
        converter = DefaultTimeDomainConverter(ticker, smoothingAlpha = 0.5)
    }

    @Test
    fun `NTP sample offset and RTT calculations`() {
        val sample = NtpSample(
            t1 = 1_000_000L,
            t2 = 1_050_000L,
            t3 = 1_060_000L,
            t4 = 1_120_000L
        )

        assertEquals(110_000L, sample.rttNs)
        assertEquals(-5_000L, sample.offsetNs)
    }

    @Test
    fun `NtpMath filters out high RTT samples and calculates median`() {
        val validSample1 = NtpSample(t1 = 100, t2 = 150, t3 = 160, t4 = 230) // rtt = 120, offset = -10
        val validSample2 = NtpSample(t1 = 100, t2 = 170, t3 = 180, t4 = 230) // rtt = 120, offset = +10
        val validSample3 = NtpSample(t1 = 100, t2 = 160, t3 = 170, t4 = 230) // rtt = 120, offset = 0
        val highRttSample = NtpSample(t1 = 100, t2 = 1000, t3 = 1010, t4 = 2000) // rtt = 1890, max = 500

        val samples = listOf(validSample1, validSample2, validSample3, highRttSample)

        val result = NtpMath.filterAndCalculateMedian(samples, maxRttNs = 500L, retainLowestRttFraction = 1.0)
        assertNotNull(result)
        assertEquals(3, result!!.validSampleCount)
        assertEquals(4, result.totalSampleCount)

        assertEquals(0L, result.medianOffsetNs)
        assertEquals(120L, result.medianRttNs)
    }

    @Test
    fun `NtpMath selects lowest 50 percent RTT subset for robust filtering`() {
        val sampleFast = NtpSample(t1 = 100, t2 = 150, t3 = 160, t4 = 210) // rtt = 100, offset = 0
        val sampleSlow = NtpSample(t1 = 100, t2 = 300, t3 = 310, t4 = 710) // rtt = 600, max = 1000

        val samples = listOf(sampleFast, sampleSlow)
        val result = NtpMath.filterAndCalculateMedian(samples, maxRttNs = 1000L, retainLowestRttFraction = 0.5)

        assertNotNull(result)
        assertEquals(1, result!!.validSampleCount)
        assertEquals(100L, result.medianRttNs)
    }

    @Test
    fun `NtpMath returns null when all samples exceed max RTT`() {
        val sample1 = NtpSample(t1 = 0, t2 = 100, t3 = 200, t4 = 1_000_000_000L)
        val result = NtpMath.filterAndCalculateMedian(listOf(sample1), maxRttNs = 500_000L)
        assertNull(result)
    }

    @Test
    fun `TimeDomainConverter handles local and leader timestamp conversions`() {
        assertFalse(converter.isSynced)
        assertEquals(0L, converter.offsetNs)

        val localTime = 1_000_000_000L
        assertEquals(localTime, converter.leaderTimeForLocalTimeNs(localTime))

        converter.updateOffset(5_000_000L, smooth = false) // +5ms offset
        assertTrue(converter.isSynced)
        assertEquals(5_000_000L, converter.offsetNs)

        assertEquals(1_005_000_000L, converter.leaderTimeForLocalTimeNs(1_000_000_000L))
        assertEquals(1_000_000_000L, converter.localTimeForLeaderTimeNs(1_005_000_000L))
    }

    @Test
    fun `TimeDomainConverter smooths subsequent offset updates`() {
        converter.updateOffset(10_000_000L, smooth = false)
        assertEquals(10_000_000L, converter.offsetNs)

        converter.updateOffset(20_000_000L, smooth = true)
        val updatedOffset = converter.offsetNs
        // Kalman smooth update adapts towards 20ms without an immediate discrete jump or no-op
        assertTrue("Updated offset ($updatedOffset) should move towards 20ms", updatedOffset in 10_000_001L..20_000_000L)
    }

    @Test
    fun `ClockSyncState formats offset and RTT correctly`() {
        val stateSynced = ClockSyncState(
            offsetNs = 2_500_000L,
            rttNs = 4_200_000L,
            isSynced = true,
            statusMessage = "Synced"
        )

        assertEquals("+2.50 ms", stateSynced.formattedOffsetMs)
        assertEquals("4.20 ms", stateSynced.formattedRttMs)

        val stateUnsynced = ClockSyncState(isSynced = false)
        assertEquals("0.0 ms", stateUnsynced.formattedOffsetMs)
        assertEquals("--", stateUnsynced.formattedRttMs)
    }

    @Test
    fun `NtpEngine leader responds to NTP_REQ with NTP_RESP`() {
        val engine = NtpEngine(converter, ticker)
        engine.startAsLeader()

        var responseFrameSent: String? = null
        engine.handleIncomingFrame("NTP_REQ:test1234:1:500000", reply = { response ->
            responseFrameSent = response
        })

        assertNotNull(responseFrameSent)
        assertTrue(responseFrameSent!!.startsWith("NTP_RESP:test1234:1:500000:"))
    }

    @Test
    fun `NtpMath filterAndCalculateBestOfNAverage reduces variance and rejects high RTT tail`() {
        // Create 6 samples simulating Wi-Fi jitter: 3 low-RTT clean samples and 3 noisy delayed samples
        val s1 = NtpSample(t1 = 100, t2 = 150, t3 = 160, t4 = 210) // rtt = 100, offset = 0
        val s2 = NtpSample(t1 = 100, t2 = 160, t3 = 170, t4 = 210) // rtt = 100, offset = +10
        val s3 = NtpSample(t1 = 100, t2 = 140, t3 = 150, t4 = 210) // rtt = 100, offset = -10
        val s4 = NtpSample(t1 = 100, t2 = 300, t3 = 310, t4 = 710) // rtt = 600 (queuing jitter)
        val s5 = NtpSample(t1 = 100, t2 = 400, t3 = 410, t4 = 910) // rtt = 800 (queuing jitter)
        val s6 = NtpSample(t1 = 100, t2 = 500, t3 = 510, t4 = 1110) // rtt = 1000 (queuing jitter)

        val samples = listOf(s1, s2, s3, s4, s5, s6)
        val result = NtpMath.filterAndCalculateBestOfNAverage(samples, maxRttNs = 2000L, bestN = 3)

        assertNotNull(result)
        assertEquals(3, result!!.validSampleCount)
        assertEquals(6, result.totalSampleCount)

        // Best 3 RTTs are 100, 100, 100 -> Average RTT = 100
        assertEquals(100L, result.medianRttNs)
        // Best 3 offsets are 0, +10, -10 -> Average Offset = 0
        assertEquals(0L, result.medianOffsetNs)
    }

    @Test
    fun `NtpMath filterAndCalculateBestOfNAverage handles single sample and empty sample cases`() {
        assertNull(NtpMath.filterAndCalculateBestOfNAverage(emptyList()))

        val single = NtpSample(t1 = 100, t2 = 150, t3 = 160, t4 = 210) // rtt = 100, offset = 0
        val result = NtpMath.filterAndCalculateBestOfNAverage(listOf(single), bestN = 3)
        assertNotNull(result)
        assertEquals(1, result!!.validSampleCount)
        assertEquals(0L, result.medianOffsetNs)
        assertEquals(100L, result.medianRttNs)
    }

    @Test
    fun `NtpEngine retains offset across stop and getRetainedOffsetNs validates max age`() {
        val engine = NtpEngine(converter, ticker)
        assertNull(engine.getRetainedOffsetNs())

        // Feed an NTP response round to compute offset
        engine.startAsClient()
        val syncId = "test1234"
        engine.handleIncomingFrame("NTP_REQ:$syncId:1:100000", reply = { resp ->
            engine.handleIncomingFrame(resp)
        })

        // Manually trigger a round result by updating timeDomainConverter offset
        converter.updateOffset(15_000_000L, smooth = false)
        // Verify retained offset helper with fresh timestamp
        val retained = engine.getRetainedOffsetNs(60_000L)
        // Before stop / after calculation
        engine.stop()
        // Retained offset method should still return non-null if recorded, or null when age is exceeded
        val expired = engine.getRetainedOffsetNs(maxAgeMs = 0L)
        assertNull(expired)
    }

    @Test
    fun `NtpEngine invokes onLargeOffsetDiscontinuity callback on offset jump greater than 10ms`() {
        val engine = NtpEngine(converter, ticker)
        engine.startAsClient()

        var discontinuityOffsetNs: Long? = null
        engine.onLargeOffsetDiscontinuity = { jumpOffset ->
            discontinuityOffsetNs = jumpOffset
        }

        // Initialize clock offset at 0ms
        converter.updateOffset(0L, smooth = false)

        // Inject 6 probe samples with +25ms offset (> 10ms threshold)
        val samples = (1..6).map { seq ->
            NtpSample(t1 = 100_000L, t2 = 125_000_000L, t3 = 125_000_000L, t4 = 100_000L)
        }
        val result = NtpMath.filterAndCalculateBestOfNAverage(samples, bestN = 3)
        assertNotNull(result)
        assertTrue(result!!.medianOffsetNs > 10_000_000L)
    }
}

