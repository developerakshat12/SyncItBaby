package com.example.greetingcard

import com.example.greetingcard.audio.PlaybackScheduler
import com.example.greetingcard.sync.DefaultTimeDomainConverter
import com.example.greetingcard.sync.Ticker
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class PlaybackSchedulerTest {

    private class SyntheticTicker(initialNs: Long = 1_000_000_000L) : Ticker {
        private val nanos = AtomicLong(initialNs)
        fun set(ns: Long) = nanos.set(ns)
        fun advance(deltaNs: Long) = nanos.addAndGet(deltaNs)
        override fun readNanos(): Long = nanos.get()
        override fun convertMonotonicNanosToTickerNs(monotonicNs: Long): Long = monotonicNs
    }

    private lateinit var ticker: SyntheticTicker
    private lateinit var converter: DefaultTimeDomainConverter
    private lateinit var scheduler: PlaybackScheduler

    @Before
    fun setUp() {
        ticker = SyntheticTicker(10_000_000_000L) // 10s monotonic
        converter = DefaultTimeDomainConverter(ticker)
        scheduler = PlaybackScheduler(sampleRate = 48000)
    }

    @Test
    fun `START_AT local conversion accurately factors in clock offset`() {
        // Leader clock is 50ms ahead (offset = +50ms)
        converter.updateOffset(50_000_000L, smooth = false)

        val startAtLeaderNs = 15_000_000_000L // Scheduled for 15s in leader time domain
        // localTime = leaderTime - offset = 15s - 50ms = 14.95s
        val localStart = scheduler.calculateCalibratedLocalStartNs(
            startAtLeaderTimeNs = startAtLeaderNs,
            timeDomainConverter = converter,
            dacLatencyNs = 0L
        )

        assertEquals(14_950_000_000L, localStart)
    }

    @Test
    fun `START_AT local conversion accurately subtracts DAC latency calibration`() {
        converter.updateOffset(0L, smooth = false) // Leader device or 0 offset

        val startAtLeaderNs = 20_000_000_000L
        val dacLatencyNs = 25_000_000L // 25ms physical DAC pipeline buffer latency

        val calibratedStart = scheduler.calculateCalibratedLocalStartNs(
            startAtLeaderTimeNs = startAtLeaderNs,
            timeDomainConverter = converter,
            dacLatencyNs = dacLatencyNs
        )

        assertEquals(19_975_000_000L, calibratedStart)
    }

    @Test
    fun `calculateWaitDurationNs returns exact delta and clamps negative to zero`() {
        val targetLocalStartNs = 15_000_000_000L

        // Current time is 12s -> Wait must be 3s
        val waitBefore = scheduler.calculateWaitDurationNs(targetLocalStartNs, currentLocalTimeNs = 12_000_000_000L)
        assertEquals(3_000_000_000L, waitBefore)

        // Current time is past target (16s) -> Wait must be 0
        val waitAfter = scheduler.calculateWaitDurationNs(targetLocalStartNs, currentLocalTimeNs = 16_000_000_000L)
        assertEquals(0L, waitAfter)
    }

    @Test
    fun `projectExpectedFrame computes exact frame count based on leader time elapsed`() {
        converter.updateOffset(100_000_000L, smooth = false) // +100ms offset

        val firstFrameLeaderNs = 10_000_000_000L // Anchor at 10s leader time
        // At local time 10.9s -> leader time is 10.9s + 0.1s = 11.0s (1.0s elapsed)
        val currentLocalNs = 10_900_000_000L

        val expectedFrame = scheduler.projectExpectedFrame(
            currentLocalTimeNs = currentLocalNs,
            firstFrameLeaderTimeNs = firstFrameLeaderNs,
            timeDomainConverter = converter
        )

        // Exactly 1 second of audio @ 48kHz = 48,000 frames
        assertEquals(48_000L, expectedFrame)
    }

    @Test
    fun `projectExpectedFrame advances frame projection when positive manual trim offset is applied`() {
        converter.updateOffset(0L, smooth = false)

        val firstFrameLeaderNs = 10_000_000_000L
        val currentLocalNs = 11_000_000_000L // 1.0s elapsed nominal
        val trimOffsetNs = 10_000_000L // +10ms acoustic advance

        val expectedFrame = scheduler.projectExpectedFrame(
            currentLocalTimeNs = currentLocalNs,
            firstFrameLeaderTimeNs = firstFrameLeaderNs,
            timeDomainConverter = converter,
            manualTrimOffsetNs = trimOffsetNs
        )

        // 1.010s elapsed @ 48kHz = 48,480 frames (exactly 48,000 + 480 frames)
        assertEquals(48_480L, expectedFrame)
    }
}

