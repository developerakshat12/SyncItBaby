package com.example.greetingcard.audio

import com.example.greetingcard.sync.TimeDomainConverter

class PlaybackScheduler(
    val sampleRate: Int = 48000,
    val targetPlayoutLatencyNs: Long = 0L
) {

    fun calculateCalibratedLocalStartNs(
        startAtLeaderTimeNs: Long,
        timeDomainConverter: TimeDomainConverter,
        dacLatencyNs: Long = 0L
    ): Long {
        val startAtLocal = timeDomainConverter.localTimeForLeaderTimeNs(startAtLeaderTimeNs)
        return (startAtLocal - dacLatencyNs).coerceAtLeast(0L)
    }

    fun calculateWaitDurationNs(
        calibratedLocalStartNs: Long,
        currentLocalTimeNs: Long
    ): Long {
        return (calibratedLocalStartNs - currentLocalTimeNs).coerceAtLeast(0L)
    }

    fun projectExpectedFrame(
        currentLocalTimeNs: Long,
        firstFrameLeaderTimeNs: Long,
        timeDomainConverter: TimeDomainConverter,
        manualTrimOffsetNs: Long = 0L
    ): Long {
        val adjustedLocalTimeNs = currentLocalTimeNs + manualTrimOffsetNs
        val leaderNow = timeDomainConverter.leaderTimeForLocalTimeNs(adjustedLocalTimeNs)
        val elapsedLeaderNs = leaderNow - firstFrameLeaderTimeNs - targetPlayoutLatencyNs
        return ((elapsedLeaderNs * sampleRate) / 1_000_000_000L).coerceAtLeast(0L)
    }
}

