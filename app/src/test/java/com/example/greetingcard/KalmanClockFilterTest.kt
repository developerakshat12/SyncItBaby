package com.example.greetingcard

import com.example.greetingcard.sync.KalmanClockFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

class KalmanClockFilterTest {

    private lateinit var kalman: KalmanClockFilter

    @Before
    fun setUp() {
        kalman = KalmanClockFilter()
    }

    @Test
    fun `Kalman initializes immediately on first measurement`() {
        assertFalse(kalman.isSynced)

        val t0 = 10_000_000_000L
        val (innov, step) = kalman.update(
            measuredOffsetNs = 5_000_000L, // +5ms
            rttNs = 4_000_000L,
            measurementTimeNs = t0
        )

        assertEquals(0L, innov)
        assertFalse(step)
        assertTrue(kalman.isSynced)
        assertEquals(5_000_000L, kalman.currentOffsetNs)
        assertEquals(5_000_000L, kalman.predictOffsetNs(t0))
    }

    @Test
    fun `Kalman converges rapidly within 3 rounds on constant offset`() {
        var t = 10_000_000_000L
        val trueOffsetNs = 12_000_000L // 12ms

        for (i in 1..4) {
            kalman.update(
                measuredOffsetNs = trueOffsetNs,
                rttNs = 2_000_000L,
                measurementTimeNs = t
            )
            t += 5_000_000_000L // +5s
        }

        assertTrue(kalman.isConverged)
        val predicted = kalman.predictOffsetNs(t)
        val errorNs = abs(predicted - trueOffsetNs)
        assertTrue("Error $errorNs ns should be < 10us", errorNs < 10_000L)
    }

    @Test
    fun `Kalman accurately estimates synthetic 20 ppm crystal drift`() {
        val t0 = 1_000_000_000_000L
        val driftRate = 20.0e-6 // +20 ppm (20 ns per 1 ms, 20 us per 1 sec)
        val baseOffsetNs = 10_000_000L

        // Feed 15 measurements 5 seconds apart
        for (step in 0..14) {
            val elapsedNs = step * 5_000_000_000L
            val currentT = t0 + elapsedNs
            val currentOffset = (baseOffsetNs + (driftRate * elapsedNs)).toLong()

            kalman.update(
                measuredOffsetNs = currentOffset,
                rttNs = 2_000_000L,
                measurementTimeNs = currentT
            )
        }

        val estimatedPpm = kalman.driftRatePpm
        // Should track close to 20.0 ppm
        assertTrue("Estimated ppm ($estimatedPpm) should be within 15..25 ppm", estimatedPpm in 15.0..25.0)

        // Predict offset 5 seconds into the future
        val futureT = t0 + (15 * 5_000_000_000L)
        val expectedFutureOffset = (baseOffsetNs + (driftRate * (15 * 5_000_000_000L))).toLong()
        val predictedFutureOffset = kalman.predictOffsetNs(futureT)

        val predictionErrorUs = abs(predictedFutureOffset - expectedFutureOffset) / 1000.0
        assertTrue("Prediction error ($predictionErrorUs us) should be < 50us", predictionErrorUs < 50.0)
    }

    @Test
    fun `Adaptive measurement noise R downweights high RTT observations`() {
        val t0 = 10_000_000_000L
        kalman.update(10_000_000L, rttNs = 2_000_000L, measurementTimeNs = t0)

        // Test with low RTT update
        val kalmanClean = KalmanClockFilter()
        kalmanClean.update(10_000_000L, rttNs = 2_000_000L, measurementTimeNs = t0)
        kalmanClean.update(20_000_000L, rttNs = 2_000_000L, measurementTimeNs = t0 + 1_000_000_000L)

        // Test with high RTT update (50ms RTT)
        val kalmanNoisy = KalmanClockFilter()
        kalmanNoisy.update(10_000_000L, rttNs = 2_000_000L, measurementTimeNs = t0)
        kalmanNoisy.update(20_000_000L, rttNs = 50_000_000L, measurementTimeNs = t0 + 1_000_000_000L)

        // The noisy sample with 50ms RTT should have less influence on the state than the clean 2ms RTT sample
        val deltaClean = kalmanClean.currentOffsetNs - 10_000_000L
        val deltaNoisy = kalmanNoisy.currentOffsetNs - 10_000_000L
        assertTrue("Noisy update ($deltaNoisy) should be smaller than clean update ($deltaClean)", deltaNoisy < deltaClean)
    }

    @Test
    fun `Step threshold triggers hard reset on 200ms step jump`() {
        val t0 = 10_000_000_000L
        kalman.update(10_000_000L, rttNs = 2_000_000L, measurementTimeNs = t0)
        kalman.update(10_000_000L, rttNs = 2_000_000L, measurementTimeNs = t0 + 5_000_000_000L)

        // Inject +200ms step jump (> 125ms STEP_THRESHOLD)
        val t2 = t0 + 10_000_000_000L
        val (innov, hadStep) = kalman.update(210_000_000L, rttNs = 2_000_000L, measurementTimeNs = t2)

        assertTrue("Step discontinuity must be flagged", hadStep)
        assertEquals(210_000_000L, kalman.currentOffsetNs)
    }

    @Test
    fun `Panic threshold rejects 15s corrupt timestamp outlier`() {
        val t0 = 10_000_000_000L
        kalman.update(10_000_000L, rttNs = 2_000_000L, measurementTimeNs = t0)

        // Inject 15s corrupted offset (> 10s PANIC_THRESHOLD)
        val t1 = t0 + 5_000_000_000L
        val (innov, hadStep) = kalman.update(15_010_000_000L, rttNs = 2_000_000L, measurementTimeNs = t1)

        assertFalse("Panic must not cause step reset", hadStep)
        // Offset must remain uncorrupted near 10ms
        assertEquals(10_000_000L, kalman.currentOffsetNs)
    }

    @Test
    fun `Kalman reset clears all state`() {
        kalman.update(10_000_000L, rttNs = 2_000_000L, measurementTimeNs = 1_000_000_000L)
        assertTrue(kalman.isSynced)

        kalman.reset()
        assertFalse(kalman.isSynced)
        assertEquals(0L, kalman.currentOffsetNs)
    }
}

