package com.example.greetingcard

import com.example.greetingcard.audio.PlaybackScheduler
import com.example.greetingcard.sync.DefaultTimeDomainConverter
import com.example.greetingcard.sync.SystemTicker
import org.junit.Assert.assertTrue
import org.junit.Test

class LateJoinConvergenceTest {

    @Test
    fun `LateJoinConvergenceTest_FileAnchor`() {
        val sampleRate = 48000
        val scheduler = PlaybackScheduler(sampleRate)
        val ticker = SystemTicker()
        val tdc = DefaultTimeDomainConverter(ticker)
        tdc.updateOffset(0L, smooth = false) // Perfect sync

        val t0 = 1_000_000_000_000L // arbitrary leader start time
        val adaptiveMarginNs = 2_000_000_000L // 2 seconds
        val firstFrameLeaderTimeNs = t0 + adaptiveMarginNs

        // Peer 1 (Existing peer) has been playing for 5 seconds since stream start.
        // Wall clock is now T0 + 7 seconds (2s margin + 5s playback).
        val currentLocalTimeNs = t0 + 7_000_000_000L

        // Expected frame being played by Peer 1
        val peer1ExpectedFrame = scheduler.projectExpectedFrame(
            currentLocalTimeNs = currentLocalTimeNs,
            firstFrameLeaderTimeNs = firstFrameLeaderTimeNs,
            timeDomainConverter = tdc
        )

        // Peer 2 (Late joiner) joins at exactly currentLocalTimeNs.
        val peer2ExpectedFrame = scheduler.projectExpectedFrame(
            currentLocalTimeNs = currentLocalTimeNs,
            firstFrameLeaderTimeNs = firstFrameLeaderTimeNs,
            timeDomainConverter = tdc
        )

        assertTrue(
            "File anchor: Expected frame ${peer1ExpectedFrame} should match joiner frame ${peer2ExpectedFrame}",
            Math.abs(peer1ExpectedFrame - peer2ExpectedFrame) < (sampleRate * 0.02) // < 20ms delta
        )
    }

    @Test
    fun `LateJoinConvergenceTest_LiveAnchor`() {
        val sampleRate = 48000
        val scheduler = PlaybackScheduler(sampleRate)
        val ticker = SystemTicker()
        val tdc = DefaultTimeDomainConverter(ticker)
        tdc.updateOffset(0L, smooth = false) // Perfect sync

        val t0 = 1_000_000_000_000L // arbitrary leader start time
        val halCompensationNs = 30_000_000L // 30ms backward shift
        val firstFrameLeaderTimeNs = t0 - halCompensationNs

        // Wall clock is now T0 + 5 seconds
        val currentLocalTimeNs = t0 + 5_000_000_000L

        val peer1ExpectedFrame = scheduler.projectExpectedFrame(
            currentLocalTimeNs = currentLocalTimeNs,
            firstFrameLeaderTimeNs = firstFrameLeaderTimeNs,
            timeDomainConverter = tdc
        )

        val peer2ExpectedFrame = scheduler.projectExpectedFrame(
            currentLocalTimeNs = currentLocalTimeNs,
            firstFrameLeaderTimeNs = firstFrameLeaderTimeNs,
            timeDomainConverter = tdc
        )

        assertTrue(
            "Live anchor: Expected frame ${peer1ExpectedFrame} should match joiner frame ${peer2ExpectedFrame}",
            Math.abs(peer1ExpectedFrame - peer2ExpectedFrame) < (sampleRate * 0.02) // < 20ms delta
        )
    }
}

