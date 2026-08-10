package com.example.greetingcard

import com.example.greetingcard.sync.AdaptivePollManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AdaptivePollManagerTest {

    private lateinit var poller: AdaptivePollManager

    @Before
    fun setUp() {
        poller = AdaptivePollManager()
    }

    @Test
    fun `Initial poller state starts in burst mode at minimum 5s interval`() {
        assertEquals(5_000L, poller.getNextPollIntervalMs())
        assertEquals(16, poller.getProbeCount())
        assertTrue(poller.isBurst())
    }

    @Test
    fun `Low jitter expands interval up to 60s and transitions to steady-state probes`() {
        // Round 1: Converged, low jitter (0.5ms)
        poller.onRoundCompleted(
            peerJitterNs = 500_000L,
            rttNs = 2_000_000L,
            isConverged = true,
            hadStepDiscontinuity = false
        )
        // First stable round does not yet scale (requires 2 consecutive)
        assertEquals(5_000L, poller.getNextPollIntervalMs())
        assertFalse(poller.isBurst())
        assertEquals(4, poller.getProbeCount())

        // Round 2: Still low jitter -> scale up by 1.5x (7.5s)
        poller.onRoundCompleted(500_000L, 2_000_000L, isConverged = true, hadStepDiscontinuity = false)
        assertEquals(7_500L, poller.getNextPollIntervalMs())

        // Simulate further stable rounds to reach 60s ceiling
        for (i in 1..10) {
            poller.onRoundCompleted(500_000L, 2_000_000L, isConverged = true, hadStepDiscontinuity = false)
        }
        assertEquals(60_000L, poller.getNextPollIntervalMs())
        assertEquals(4, poller.getProbeCount())
    }

    @Test
    fun `High jitter reduces interval`() {
        // Expand interval to 30s first
        for (i in 1..10) {
            poller.onRoundCompleted(500_000L, 2_000_000L, isConverged = true, hadStepDiscontinuity = false)
        }
        val expanded = poller.getNextPollIntervalMs()
        assertTrue(expanded >= 30_000L)

        // Now inject high jitter (4.5ms > 3.0ms threshold)
        poller.onRoundCompleted(
            peerJitterNs = 4_500_000L,
            rttNs = 20_000_000L,
            isConverged = true,
            hadStepDiscontinuity = false
        )

        val dropped = poller.getNextPollIntervalMs()
        assertTrue("Dropped interval ($dropped) should be half of expanded ($expanded)", dropped < expanded)
    }

    @Test
    fun `Step discontinuity immediately resets interval to 5s and re-enables burst mode`() {
        // Expand interval
        for (i in 1..5) {
            poller.onRoundCompleted(500_000L, 2_000_000L, isConverged = true, hadStepDiscontinuity = false)
        }
        assertTrue(poller.getNextPollIntervalMs() > 5_000L)
        assertFalse(poller.isBurst())

        // Trigger step discontinuity
        poller.onRoundCompleted(
            peerJitterNs = 500_000L,
            rttNs = 2_000_000L,
            isConverged = true,
            hadStepDiscontinuity = true
        )

        assertEquals(5_000L, poller.getNextPollIntervalMs())
        assertTrue(poller.isBurst())
        assertEquals(16, poller.getProbeCount())
    }

    @Test
    fun `Poller reset restores initial 5s burst state`() {
        for (i in 1..5) {
            poller.onRoundCompleted(500_000L, 2_000_000L, isConverged = true, hadStepDiscontinuity = false)
        }
        poller.reset()

        assertEquals(5_000L, poller.getNextPollIntervalMs())
        assertTrue(poller.isBurst())
        assertEquals(16, poller.getProbeCount())
    }
}

