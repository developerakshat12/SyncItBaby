package com.example.greetingcard.sync

import com.example.greetingcard.util.LogUtils
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.sqrt

private const val TAG = "KalmanClockFilter"

data class KalmanStateSnapshot(
    val thetaNs: Double = 0.0,
    val driftRate: Double = 0.0, // ns drift per ns local time (dimensionless)
    val lastUpdateNs: Long = 0L,
    val p00: Double = 1.0e12,
    val p11: Double = 2.5e-9,
    val isInitialized: Boolean = false,
    val updateCount: Int = 0
) {
    val driftRatePpm: Double
        get() = driftRate * 1_000_000.0

    val uncertaintyNs: Long
        get() = sqrt(maxOf(0.0, p00)).toLong()

    fun predictOffsetNs(targetTimeNs: Long): Long {
        if (!isInitialized) return thetaNs.toLong()
        val dt = (targetTimeNs - lastUpdateNs).toDouble()
        return (thetaNs + (driftRate * dt)).toLong()
    }
}

class KalmanClockFilter(
    private val initialOffsetVariance: Double = 1.0e12, // (1 ms)^2 in ns^2
    private val initialDriftVariance: Double = 2.5e-9, // (50 ppm)^2
    private val qPhase: Double = 1.0e-6, // Phase noise variance rate
    private val qFreq: Double = 5.0e-19 // Frequency drift variance rate
) {
    companion object {
        const val PANIC_THRESHOLD_NS = 10_000_000_000L // 10 seconds: corrupt sample limit
        const val STEP_THRESHOLD_NS = 125_000_000L // 125 milliseconds: clock step reset limit
        const val MIN_MEASUREMENT_NOISE_R = 1.0e10 // (100 us)^2 in ns^2
    }

    private var theta: Double = 0.0
    private var driftRate: Double = 0.0
    private var lastUpdateNs: Long = 0L
    private var isInitialized: Boolean = false
    private var updateCount: Int = 0

    // Covariance matrix P = [[P00, P01], [P10, P11]]
    private var p00: Double = initialOffsetVariance
    private var p01: Double = 0.0
    private var p10: Double = 0.0
    private var p11: Double = initialDriftVariance

    // Thread-safe lock-free snapshot for fast consumer thread queries
    private val snapshotRef = AtomicReference(KalmanStateSnapshot())

    @Synchronized
    fun update(
        measuredOffsetNs: Long,
        rttNs: Long,
        measurementTimeNs: Long
    ): Pair<Long, Boolean> {
        val z = measuredOffsetNs.toDouble()
        val halfRtt = rttNs.toDouble() / 2.0
        val r = maxOf(MIN_MEASUREMENT_NOISE_R, halfRtt * halfRtt)

        // 1. Handle First Initialization
        if (!isInitialized) {
            theta = z
            driftRate = 0.0
            lastUpdateNs = measurementTimeNs
            p00 = r
            p01 = 0.0
            p10 = 0.0
            p11 = initialDriftVariance
            isInitialized = true
            updateCount = 1
            publishSnapshot()
            LogUtils.d(TAG, "Kalman initialized with offset=${measuredOffsetNs / 1_000_000.0}ms")
            return Pair(0L, false)
        }

        val dt = maxOf(0L, measurementTimeNs - lastUpdateNs).toDouble()

        // 2. Predict Step
        val thetaPred = theta + (driftRate * dt)
        val driftPred = driftRate

        // Q process noise matrix
        val q00 = (qPhase * dt) + (qFreq * dt * dt * dt / 3.0)
        val q01 = qFreq * dt * dt / 2.0
        val q11 = qFreq * dt

        val pPred00 = p00 + (2.0 * dt * p01) + (dt * dt * p11) + q00
        val pPred01 = p01 + (dt * p11) + q01
        val pPred10 = pPred01
        val pPred11 = p11 + q11

        // 3. Innovation (Measurement Residual)
        val innovation = z - thetaPred
        val absInnovation = abs(innovation)

        // Panic Threshold check (>10s): Reject outright as corrupted packet
        if (absInnovation > PANIC_THRESHOLD_NS) {
            LogUtils.w(
                TAG,
                "Kalman PANIC: innovation=${innovation / 1_000_000.0}ms exceeds 10s. Sample rejected."
            )
            return Pair(innovation.toLong(), false)
        }

        // Step Threshold check (>125ms): Hard step reset
        if (absInnovation > STEP_THRESHOLD_NS) {
            LogUtils.w(
                TAG,
                "Kalman STEP: innovation=${innovation / 1_000_000.0}ms exceeds 125ms. Resetting filter state."
            )
            theta = z
            driftRate = 0.0
            lastUpdateNs = measurementTimeNs
            p00 = r
            p01 = 0.0
            p10 = 0.0
            p11 = initialDriftVariance
            updateCount++
            publishSnapshot()
            return Pair(innovation.toLong(), true)
        }

        // 4. Update Step with Adaptive Measurement Noise R
        val s = pPred00 + r // Innovation covariance
        val k0 = pPred00 / s // Kalman gain for offset
        val k1 = pPred10 / s // Kalman gain for drift rate

        // State update
        theta = thetaPred + (k0 * innovation)
        driftRate = driftPred + (k1 * innovation)

        // Covariance update (Joseph / simplified symmetric form)
        p00 = maxOf(0.0, (1.0 - k0) * pPred00)
        p01 = (1.0 - k0) * pPred01
        p10 = p01
        p11 = maxOf(0.0, pPred11 - (k1 * pPred01))

        lastUpdateNs = measurementTimeNs
        updateCount++

        publishSnapshot()

        LogUtils.d(
            TAG,
            "[KalmanTelemetry] θ=${theta / 1_000_000.0}ms | f=${driftRatePpm}ppm | K=[$k0, $k1] | R=${sqrt(r) / 1_000_000.0}ms | innov=${innovation / 1_000_000.0}ms"
        )

        return Pair(innovation.toLong(), false)
    }

    fun predictOffsetNs(targetTimeNs: Long): Long {
        return snapshotRef.get().predictOffsetNs(targetTimeNs)
    }

    fun getSnapshot(): KalmanStateSnapshot = snapshotRef.get()

    val isSynced: Boolean
        get() = snapshotRef.get().isInitialized

    val driftRatePpm: Double
        get() = snapshotRef.get().driftRatePpm

    val currentOffsetNs: Long
        get() = snapshotRef.get().thetaNs.toLong()

    val isConverged: Boolean
        get() {
            val snap = snapshotRef.get()
            return snap.isInitialized && snap.updateCount >= 2 && snap.p00 < 4.0e12 // Uncertainty < 2ms
        }

    private fun publishSnapshot() {
        snapshotRef.set(
            KalmanStateSnapshot(
                thetaNs = theta,
                driftRate = driftRate,
                lastUpdateNs = lastUpdateNs,
                p00 = p00,
                p11 = p11,
                isInitialized = isInitialized,
                updateCount = updateCount
            )
        )
    }

    @Synchronized
    fun reset() {
        theta = 0.0
        driftRate = 0.0
        lastUpdateNs = 0L
        isInitialized = false
        updateCount = 0
        p00 = initialOffsetVariance
        p01 = 0.0
        p10 = 0.0
        p11 = initialDriftVariance
        snapshotRef.set(KalmanStateSnapshot())
    }
}

