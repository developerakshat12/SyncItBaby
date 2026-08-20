package com.example.greetingcard.audio

import kotlin.math.abs

enum class SyncPhase {
    STARTUP,
    AGGRESSIVE,
    FINE
}

class DriftController(
    val sampleRate: Int = 48000,
    val errorSmoothingAlpha: Double = 0.25,
    val kp: Double = 2e-8,
    val ki: Double = 5e-11,
    val maxIntegral: Double = 200e-6,
    val deadZoneNs: Long = 50_000L, // 0.05 ms (50 µs / ~2.4 frames @ 48kHz)
    val aggressiveErrorThresholdNs: Long = 500_000L, // 0.5 ms error threshold for aggressive mode
    val convergenceConsecutiveCount: Int = 4, // Consecutive observations within dead zone to enter FINE mode
    val maxAggressiveCorrection: Int = 6 // Cap at ±6 frames (strided evenly across 960 frames @ 20ms)
) {
    var smoothedErrorNs: Double = 0.0
        private set

    var integralTerm: Double = 0.0
        private set

    var lastCorrectionSamples: Int = 0
        private set

    var syncPhase: SyncPhase = SyncPhase.STARTUP
        private set

    private var lastCorrectionTimeNs: Long = 0L
    private var firstUpdateNs: Long = 0L
    private var consecutiveDeadZoneCount: Int = 0

    fun update(
        nowNs: Long,
        expectedFrame: Long,
        actualFrame: Long
    ): Int {
        val rawErrorFrames = (expectedFrame - actualFrame).toDouble()
        val rawErrorNs = (rawErrorFrames * 1_000_000_000.0) / sampleRate
        return updateWithRawErrorNs(nowNs, rawErrorNs)
    }

    fun update(
        nowNs: Long,
        phaseErrorNs: Long
    ): Int {
        return updateWithRawErrorNs(nowNs, phaseErrorNs.toDouble())
    }

    private fun updateWithRawErrorNs(
        nowNs: Long,
        rawErrorNs: Double
    ): Int {
        if (lastCorrectionTimeNs == 0L) {
            lastCorrectionTimeNs = nowNs
            firstUpdateNs = nowNs
            smoothedErrorNs = rawErrorNs
            lastCorrectionSamples = 0
            syncPhase = SyncPhase.AGGRESSIVE
            consecutiveDeadZoneCount = 0
            return 0
        }

        val dt = (nowNs - lastCorrectionTimeNs) / 1_000_000_000.0
        lastCorrectionTimeNs = nowNs

        // 1. EMA smoothing to filter out measurement jitter
        smoothedErrorNs = errorSmoothingAlpha * rawErrorNs + (1.0 - errorSmoothingAlpha) * smoothedErrorNs

        // 2. Integral term with Anti-Windup
        val candidateIntegral = integralTerm + (ki * smoothedErrorNs * dt)
        integralTerm = candidateIntegral.coerceIn(-maxIntegral, maxIntegral)

        // 3. State machine transitions
        val absError = abs(smoothedErrorNs)
        if (absError <= deadZoneNs) {
            consecutiveDeadZoneCount++
            if (consecutiveDeadZoneCount >= convergenceConsecutiveCount) {
                syncPhase = SyncPhase.FINE
            }
        } else {
            consecutiveDeadZoneCount = 0
            if (absError > aggressiveErrorThresholdNs) {
                syncPhase = SyncPhase.AGGRESSIVE
            }
        }

        // 4. Decision with state-phase support
        val decision = when {
            smoothedErrorNs > deadZoneNs -> {
                // Client lagging -> Drop frames to catch up / speed up
                if (syncPhase == SyncPhase.AGGRESSIVE) {
                    val scale = (absError / deadZoneNs).toInt().coerceIn(1, maxAggressiveCorrection)
                    -scale
                } else {
                    -1
                }
            }
            smoothedErrorNs < -deadZoneNs -> {
                // Client leading -> Insert frames to delay / slow down
                if (syncPhase == SyncPhase.AGGRESSIVE) {
                    val scale = (absError / deadZoneNs).toInt().coerceIn(1, maxAggressiveCorrection)
                    scale
                } else {
                    1
                }
            }
            else -> 0 // Within dead zone
        }

        lastCorrectionSamples = decision
        return decision
    }

    fun isInAggressiveWindow(nowNs: Long = 0L): Boolean {
        return syncPhase == SyncPhase.AGGRESSIVE
    }

    fun reset() {
        smoothedErrorNs = 0.0
        integralTerm = 0.0
        lastCorrectionSamples = 0
        lastCorrectionTimeNs = 0L
        firstUpdateNs = 0L
        syncPhase = SyncPhase.STARTUP
        consecutiveDeadZoneCount = 0
    }
}

