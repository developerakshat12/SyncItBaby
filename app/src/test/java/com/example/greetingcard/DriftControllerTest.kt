package com.example.greetingcard

import com.example.greetingcard.audio.DriftController
import com.example.greetingcard.audio.SyncPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DriftControllerTest {

    private lateinit var controller: DriftController

    @Before
    fun setUp() {
        controller = DriftController(
            sampleRate = 48000,
            errorSmoothingAlpha = 0.5,
            kp = 1e-9,
            ki = 1e-11,
            maxIntegral = 200e-6,
            deadZoneNs = 50_000L, // 0.05ms
            aggressiveErrorThresholdNs = 1_000_000L, // 1.0ms
            convergenceConsecutiveCount = 2 // 2 consecutive checks for fast testing
        )
    }

    @Test
    fun `default deadZoneNs is 50 microseconds`() {
        val defaultController = DriftController()
        assertEquals(50_000L, defaultController.deadZoneNs)
    }

    @Test
    fun `sub-millisecond phase error below dead zone emits 0 nominal`() {
        var nowNs = 1_000_000_000L
        controller.update(nowNs, expectedFrame = 0L, actualFrame = 0L)

        // Advance 200ms with a small 0.02ms error (1 frame @ 48kHz = ~20,833ns < 50,000ns dead zone)
        nowNs += 200_000_000L
        val correction = controller.update(nowNs, expectedFrame = 1001L, actualFrame = 1000L)

        assertEquals(0, correction)
        assertEquals(0, controller.lastCorrectionSamples)
    }

    @Test
    fun `lagging playback in aggressive mode emits -2 to drop samples and quickly catch up`() {
        var nowNs = 1_000_000_000L
        controller.update(nowNs, expectedFrame = 0L, actualFrame = 0L)

        // Advance 200ms with 0.2ms error (9.6 frames @ 48kHz = 200,000ns = 4x deadZone)
        nowNs += 200_000_000L
        val correction = controller.update(nowNs, expectedFrame = 1010L, actualFrame = 1000L)

        // In aggressive mode capped at maxAggressiveCorrection = 2, emits -2
        assertEquals(-2, correction)
        assertEquals(-2, controller.lastCorrectionSamples)
        assertTrue(controller.smoothedErrorNs > 50_000.0)
        assertTrue(controller.isInAggressiveWindow(nowNs))
    }

    @Test
    fun `leading playback in aggressive mode emits +2 to insert samples and slow down`() {
        var nowNs = 1_000_000_000L
        controller.update(nowNs, expectedFrame = 0L, actualFrame = 0L)

        // Advance 200ms with -0.2ms error (leading by 10 frames)
        nowNs += 200_000_000L
        val correction = controller.update(nowNs, expectedFrame = 1000L, actualFrame = 1010L)

        // In aggressive mode capped at maxAggressiveCorrection = 2, emits +2
        assertEquals(2, correction)
        assertEquals(2, controller.lastCorrectionSamples)
        assertTrue(controller.smoothedErrorNs < -50_000.0)
    }

    @Test
    fun `aggressive mode transitions to fine mode upon consecutive dead zone convergence`() {
        var nowNs = 1_000_000_000L
        controller.update(nowNs, expectedFrame = 0L, actualFrame = 0L)
        assertTrue(controller.isInAggressiveWindow(nowNs))

        // First dead zone update (count = 1)
        nowNs += 200_000_000L
        controller.update(nowNs, phaseErrorNs = 20_000L)
        assertEquals(SyncPhase.AGGRESSIVE, controller.syncPhase)

        // Second consecutive dead zone update (count = 2 >= convergenceConsecutiveCount)
        nowNs += 200_000_000L
        controller.update(nowNs, phaseErrorNs = 20_000L)
        assertEquals(SyncPhase.FINE, controller.syncPhase)
        assertFalse(controller.isInAggressiveWindow(nowNs))

        // In FINE mode with moderate error (200µs <= 1ms), emits ±1 steady-state
        nowNs += 200_000_000L
        val fineCorrection = controller.update(nowNs, phaseErrorNs = 200_000L)
        assertEquals(-1, fineCorrection)
    }

    @Test
    fun `fine mode regresses to aggressive mode when error exceeds 1ms threshold`() {
        var nowNs = 1_000_000_000L
        controller.update(nowNs, expectedFrame = 0L, actualFrame = 0L)

        // Converge into FINE mode
        nowNs += 200_000_000L
        controller.update(nowNs, phaseErrorNs = 10_000L)
        nowNs += 200_000_000L
        controller.update(nowNs, phaseErrorNs = 10_000L)
        assertEquals(SyncPhase.FINE, controller.syncPhase)

        // Error jumps above 1ms (2_000_000ns)
        nowNs += 200_000_000L
        val aggressiveCorrection = controller.update(nowNs, phaseErrorNs = 2_000_000L)
        assertEquals(SyncPhase.AGGRESSIVE, controller.syncPhase)
        assertTrue("Aggressive correction must be between -6 and -2", aggressiveCorrection in -6..-2)
    }

    @Test
    fun `anti-windup allows fast recovery when error direction is reversed from saturation`() {
        var nowNs = 1_000_000_000L
        controller.update(nowNs, expectedFrame = 0L, actualFrame = 0L)

        // 1. Drive controller deep into positive lag saturation for 10 consecutive seconds
        for (i in 1..50) {
            nowNs += 200_000_000L
            controller.update(nowNs, expectedFrame = 100_000L, actualFrame = 0L)
        }
        // Integral term must be clamped to maxIntegral (200e-6)
        assertEquals(200e-6, controller.integralTerm, 1e-9)

        // 2. Reverse error direction (device is now running ahead)
        // Advance 2 steps so EMA filter transitions into negative territory
        nowNs += 200_000_000L
        controller.update(nowNs, expectedFrame = 0L, actualFrame = 100_000L)
        nowNs += 200_000_000L
        val reversedCorrection = controller.update(nowNs, expectedFrame = 0L, actualFrame = 100_000L)

        // Decision should immediately switch to +N (insert sample) without getting stuck in positive windup
        assertTrue(reversedCorrection > 0)
    }

    @Test
    fun `direct phaseErrorNs overload calculates sample decisions correctly`() {
        var nowNs = 1_000_000_000L
        controller.update(nowNs, phaseErrorNs = 0L)

        nowNs += 200_000_000L
        val laggingDecision = controller.update(nowNs, phaseErrorNs = 200_000L) // +200µs lag in aggressive mode
        assertEquals(-2, laggingDecision)

        // Reset and test leading direction
        controller.reset()
        controller.update(nowNs, phaseErrorNs = 0L)
        nowNs += 200_000_000L
        val leadingDecision = controller.update(nowNs, phaseErrorNs = -200_000L) // -200µs lead in aggressive mode
        assertEquals(2, leadingDecision)
    }

    @Test
    fun `reset clears internal controller state and restarts aggressive window`() {
        var nowNs = 1_000_000_000L
        controller.update(nowNs, expectedFrame = 0L, actualFrame = 0L)
        nowNs += 200_000_000L
        controller.update(nowNs, expectedFrame = 50_000L, actualFrame = 0L)

        controller.reset()

        assertEquals(0.0, controller.smoothedErrorNs, 1e-9)
        assertEquals(0.0, controller.integralTerm, 1e-9)
        assertEquals(0, controller.lastCorrectionSamples)
        assertEquals(SyncPhase.STARTUP, controller.syncPhase)
        assertFalse(controller.isInAggressiveWindow(nowNs)) // 0 until first update
    }
}

