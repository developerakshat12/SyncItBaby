package com.example.greetingcard.sync

import com.example.greetingcard.util.LogUtils

private const val TAG = "AdaptivePollManager"

class AdaptivePollManager(
    private val minIntervalMs: Long = MIN_POLL_INTERVAL_MS,
    private val maxIntervalMs: Long = MAX_POLL_INTERVAL_MS,
    private val burstProbes: Int = BURST_PROBES,
    private val steadyProbes: Int = STEADY_PROBES
) {
    companion object {
        const val MIN_POLL_INTERVAL_MS = 5_000L // 5 seconds
        const val MAX_POLL_INTERVAL_MS = 60_000L // 60 seconds
        const val BURST_PROBES = 16
        const val STEADY_PROBES = 4

        const val LOW_JITTER_THRESHOLD_NS = 1_000_000L // 1.0 ms
        const val HIGH_JITTER_THRESHOLD_NS = 3_000_000L // 3.0 ms
    }

    private var currentIntervalMs: Long = minIntervalMs
    private var isBurstMode: Boolean = true
    private var consecutiveStableRounds: Int = 0

    @Synchronized
    fun onRoundCompleted(
        peerJitterNs: Long,
        rttNs: Long,
        isConverged: Boolean,
        hadStepDiscontinuity: Boolean
    ) {
        if (hadStepDiscontinuity) {
            currentIntervalMs = minIntervalMs
            isBurstMode = true
            consecutiveStableRounds = 0
            LogUtils.d(TAG, "Step discontinuity: reset to min interval ${currentIntervalMs}ms (Burst mode)")
            return
        }

        if (!isConverged) {
            currentIntervalMs = minIntervalMs
            isBurstMode = true
            consecutiveStableRounds = 0
            LogUtils.d(TAG, "Filter converging: holding min interval ${currentIntervalMs}ms (Burst mode)")
            return
        }

        // Once converged, transition off initial burst mode
        isBurstMode = false

        if (peerJitterNs > HIGH_JITTER_THRESHOLD_NS) {
            // High network jitter -> reduce interval to track more closely
            currentIntervalMs = maxOf(minIntervalMs, (currentIntervalMs * 0.5).toLong())
            consecutiveStableRounds = 0
            LogUtils.d(TAG, "High jitter (${peerJitterNs / 1_000_000.0}ms): dropped interval to ${currentIntervalMs}ms")
        } else if (peerJitterNs < LOW_JITTER_THRESHOLD_NS) {
            // Low jitter -> increment stable rounds and expand interval
            consecutiveStableRounds++
            if (consecutiveStableRounds >= 2) {
                currentIntervalMs = minOf(maxIntervalMs, (currentIntervalMs * 1.5).toLong())
                LogUtils.d(TAG, "Low jitter (${peerJitterNs / 1_000_000.0}ms, stable count=$consecutiveStableRounds): expanded interval to ${currentIntervalMs}ms")
            }
        } else {
            // Nominal jitter (1ms - 3ms) -> hold current interval
            consecutiveStableRounds = maxOf(1, consecutiveStableRounds)
        }
    }

    @Synchronized
    fun getNextPollIntervalMs(): Long = currentIntervalMs

    @Synchronized
    fun getProbeCount(): Int = if (isBurstMode) burstProbes else steadyProbes

    @Synchronized
    fun isBurst(): Boolean = isBurstMode

    @Synchronized
    fun reset() {
        currentIntervalMs = minIntervalMs
        isBurstMode = true
        consecutiveStableRounds = 0
    }
}

