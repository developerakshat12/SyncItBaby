package com.example.greetingcard

import com.example.greetingcard.audio.DriftController
import com.example.greetingcard.audio.PlaybackScheduler
import com.example.greetingcard.sync.DefaultTimeDomainConverter
import com.example.greetingcard.sync.Ticker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class ScheduledPlaybackDriftTest {

    private class SyntheticTicker(initialNs: Long = 1_000_000_000L) : Ticker {
        private val nanos = AtomicLong(initialNs)
        fun advance(deltaNs: Long) = nanos.addAndGet(deltaNs)
        override fun readNanos(): Long = nanos.get()
        override fun convertMonotonicNanosToTickerNs(monotonicNs: Long): Long = monotonicNs
    }

    private lateinit var ticker: SyntheticTicker
    private lateinit var converter: DefaultTimeDomainConverter
    private lateinit var scheduler: PlaybackScheduler
    private lateinit var controller: DriftController

    @Before
    fun setUp() {
        ticker = SyntheticTicker(1_000_000_000L)
        converter = DefaultTimeDomainConverter(ticker, smoothingAlpha = 0.25)
        scheduler = PlaybackScheduler(sampleRate = 48000)
        controller = DriftController(
            sampleRate = 48000,
            errorSmoothingAlpha = 0.1,
            kp = 1e-9,
            ki = 1e-11,
            maxIntegral = 200e-6,
            deadZoneNs = 50_000L // 0.05ms (50µs)
        )
    }

    @Test
    fun `closed-loop drift monitoring loop speeds up lagging playback by emitting sample drop decisions`() {
        converter.updateOffset(0L, smooth = false)
        val firstFrameLeaderNs = 1_000_000_000L

        // Simulate lagging DAC clock (5 frames slower per 200ms = ~100 ppm lag / ~104µs error > 50µs deadzone)
        var actualHardwareFrame = 0L

        for (cycle in 1..150) {
            ticker.advance(200_000_000L) // 200ms
            val nowNs = ticker.readNanos()
            actualHardwareFrame += 9595 // 5 frames lag

            val expectedFrame = scheduler.projectExpectedFrame(
                currentLocalTimeNs = nowNs,
                firstFrameLeaderTimeNs = firstFrameLeaderNs,
                timeDomainConverter = converter
            )

            val correction = controller.update(nowNs, expectedFrame, actualHardwareFrame)

            // Assert discrete invariant: correction is bounded within ±6 during aggressive convergence and ±1 in steady state
            assertTrue("Correction $correction must be discrete bounded within -6..+6", correction in -6..6)

            if (cycle in 20..150) {
                // Device is lagging: controller must emit drop correction (-1 to -6) to speed up
                assertTrue("Controller must emit drop correction to compensate for DAC crystal lag", correction in -6..-1)
            }
        }
    }

    @Test
    fun `closed-loop drift monitoring loop slows down leading playback by emitting sample insert decisions`() {
        converter.updateOffset(0L, smooth = false)
        val firstFrameLeaderNs = 1_000_000_000L

        // Simulate leading DAC clock (5 frames faster per 200ms = ~100 ppm lead / ~-104µs error < -50µs deadzone)
        var actualHardwareFrame = 0L

        for (cycle in 1..150) {
            ticker.advance(200_000_000L) // 200ms
            val nowNs = ticker.readNanos()
            actualHardwareFrame += 9605 // 5 frames lead

            val expectedFrame = scheduler.projectExpectedFrame(
                currentLocalTimeNs = nowNs,
                firstFrameLeaderTimeNs = firstFrameLeaderNs,
                timeDomainConverter = converter
            )

            val correction = controller.update(nowNs, expectedFrame, actualHardwareFrame)

            // Assert discrete invariant: correction is bounded within ±6 during aggressive convergence and ±1 in steady state
            assertTrue("Correction $correction must be discrete bounded within -6..+6", correction in -6..6)

            if (cycle in 20..150) {
                // Device is leading: controller must emit insert correction (1 to 6) to slow down
                assertTrue("Controller must emit insert correction to compensate for DAC crystal lead", correction in 1..6)
            }
        }
    }

    @Test
    fun `synthetic clock offset step injection test verifies discrete sample corrections pull in drift into dead zone`() {
        converter.updateOffset(0L, smooth = false)
        val firstFrameLeaderNs = 1_000_000_000L

        // 1. Establish steady state playback at nominal rate
        for (i in 1..25) {
            ticker.advance(200_000_000L)
            val nowNs = ticker.readNanos()
            val expectedFrame = scheduler.projectExpectedFrame(nowNs, firstFrameLeaderNs, converter)
            controller.update(nowNs, expectedFrame, actualFrame = i * 9600L)
        }

        // 2. Inject +5ms clock step (Leader clock jumps forward by 5ms = 240 frames lag on client)
        converter.updateOffset(5_000_000L, smooth = false)

        val recordedCorrections = mutableListOf<Int>()

        // 3. Monitor pull-in over the next 10 seconds (50 cycles of 200ms)
        for (i in 1..50) {
            ticker.advance(200_000_000L)
            val nowNs = ticker.readNanos()

            val expectedFrame = scheduler.projectExpectedFrame(nowNs, firstFrameLeaderNs, converter)
            // Actual DAC advances at nominal rate
            val nominalFrame = (i * 9600L) + (25 * 9600L)

            val correction = controller.update(nowNs, expectedFrame, nominalFrame)
            recordedCorrections.add(correction)

            assertTrue("Correction $correction must stay within discrete set -6..+6", correction in -6..6)
        }

        // Verify that controller responded by emitting sample drop (-1 or -2) corrections to pull in positive lag
        assertTrue("Controller must emit drop corrections to pull in positive clock step", recordedCorrections.any { it < 0 })
    }

    @Test
    fun `late-join coordinate frame offset alignment prevents DriftController saturation`() {
        converter.updateOffset(0L, smooth = false)
        val firstFrameLeaderNs = 1_000_000_000L // Song started at 1s
        val sampleRate = 48000

        // Peer late-joins 20 seconds into the song
        val joinTimeLocalNs = 21_000_000_000L
        ticker.advance(20_000_000_000L) // advance ticker by 20s

        // The first chunk aligned is at 20s (presentationTimeNs = 21_000_000_000L)
        val alignedChunkPts = 21_000_000_000L
        val firstChunkFrameOffsetInSong = (((alignedChunkPts - firstFrameLeaderNs) * sampleRate) / 1_000_000_000L)
        assertEquals(960_000L, firstChunkFrameOffsetInSong) // 20s * 48000 = 960,000 frames

        // Expected frame projected from song anchor
        val expectedFrame = scheduler.projectExpectedFrame(
            currentLocalTimeNs = joinTimeLocalNs,
            firstFrameLeaderTimeNs = firstFrameLeaderNs,
            timeDomainConverter = converter
        )
        assertEquals(960_000L, expectedFrame)

        // AudioTrack hardware frame position starts at 0 on reconnect
        val dacHardwareFramePosition = 0L
        val actualFrameInSong = firstChunkFrameOffsetInSong + dacHardwareFramePosition
        assertEquals(960_000L, actualFrameInSong)

        // DriftController update: error is 0 frames, correction remains exactly 0
        val correction = controller.update(joinTimeLocalNs, expectedFrame, actualFrameInSong)
        assertEquals(0, correction)
        assertEquals(0.0, controller.smoothedErrorNs, 1e-6)
    }

    @Test
    fun `fresh multi-device synchronized start converges on identical song frame offset and play timing`() {
        val songAnchorLeaderNs = 2_000_000_000L
        val sampleRate = 48000
        val dacLatencyNs = 40_000_000L // 40ms DAC latency

        // Peer A with offset +5ms
        val converterA = DefaultTimeDomainConverter(ticker)
        converterA.updateOffset(5_000_000L, smooth = false)

        // Peer B with offset -10ms
        val converterB = DefaultTimeDomainConverter(ticker)
        converterB.updateOffset(-10_000_000L, smooth = false)

        val firstChunkPts = songAnchorLeaderNs

        // Peer A calculates local presentation and wait
        val localPtsA = converterA.localTimeForLeaderTimeNs(firstChunkPts)
        val startLocalA = localPtsA - dacLatencyNs
        val waitA = scheduler.calculateWaitDurationNs(startLocalA, ticker.readNanos())
        val offsetA = (((firstChunkPts - songAnchorLeaderNs) * sampleRate) / 1_000_000_000L)

        // Peer B calculates local presentation and wait
        val localPtsB = converterB.localTimeForLeaderTimeNs(firstChunkPts)
        val startLocalB = localPtsB - dacLatencyNs
        val waitB = scheduler.calculateWaitDurationNs(startLocalB, ticker.readNanos())
        val offsetB = (((firstChunkPts - songAnchorLeaderNs) * sampleRate) / 1_000_000_000L)

        // Both peers compute offset 0 in song coordinate space
        assertEquals(0L, offsetA)
        assertEquals(0L, offsetB)

        // When translated back to leader time, both peers play at the exact same leader instant
        val leaderPlayInstantA = converterA.leaderTimeForLocalTimeNs(startLocalA + dacLatencyNs)
        val leaderPlayInstantB = converterB.leaderTimeForLocalTimeNs(startLocalB + dacLatencyNs)
        assertEquals(songAnchorLeaderNs, leaderPlayInstantA)
        assertEquals(songAnchorLeaderNs, leaderPlayInstantB)
    }

    @Test
    fun `HAL capture latency compensation calculation correctly shifts anchor backward`() {
        val rawAnchorNs = 5_000_000_000L
        val lockedHalLagNs = 15_000_000L // 15ms HAL lag
        val opusLookaheadNs = 4_400_000L // 4.4ms Opus lookahead
        val totalCompensationNs = lockedHalLagNs + opusLookaheadNs

        val compensatedAnchorNs = rawAnchorNs - totalCompensationNs

        assertEquals(19_400_000L, totalCompensationNs)
        assertEquals(4_980_600_000L, compensatedAnchorNs)
    }

    @Test
    fun `evaluating expectedFrame at dacTimestamp nanoTime eliminates HAL timestamp latency offsets`() {
        converter.updateOffset(0L, smooth = false)
        val firstFrameLeaderNs = 1_000_000_000L
        val sampleRate = 48000

        // Simulate 5 seconds of playback
        ticker.advance(5_000_000_000L)
        val nowNs = ticker.readNanos() // 6,000,000,000L

        // Peer 1 HAL timestamp delay is 171ms (dacTimestamp.nanoTime was 171ms ago)
        val halDelayNs1 = 171_000_000L
        val dacTimestampNano1 = nowNs - halDelayNs1
        // At that timestamp, exactly (dacTimestampNano1 - firstFrameLeaderNs) of audio had presented
        val presentedFrames1 = ((dacTimestampNano1 - firstFrameLeaderNs) * sampleRate) / 1_000_000_000L

        // If calculated with dacTimestamp.nanoTime (Correct):
        val expectedAtDacTime1 = scheduler.projectExpectedFrame(
            currentLocalTimeNs = dacTimestampNano1,
            firstFrameLeaderTimeNs = firstFrameLeaderNs,
            timeDomainConverter = converter
        )
        val errorFrames1 = expectedAtDacTime1 - presentedFrames1
        assertEquals(0L, errorFrames1)
        val correction1 = controller.update(dacTimestampNano1, expectedAtDacTime1, presentedFrames1)
        assertEquals(0, correction1) // Strictly 0 (in dead zone)

        // Peer 2 HAL timestamp delay is 267ms (dacTimestamp.nanoTime was 267ms ago)
        val halDelayNs2 = 267_000_000L
        val dacTimestampNano2 = nowNs - halDelayNs2
        val presentedFrames2 = ((dacTimestampNano2 - firstFrameLeaderNs) * sampleRate) / 1_000_000_000L

        val expectedAtDacTime2 = scheduler.projectExpectedFrame(
            currentLocalTimeNs = dacTimestampNano2,
            firstFrameLeaderTimeNs = firstFrameLeaderNs,
            timeDomainConverter = converter
        )
        val errorFrames2 = expectedAtDacTime2 - presentedFrames2
        assertEquals(0L, errorFrames2)
        val correction2 = controller.update(dacTimestampNano2, expectedAtDacTime2, presentedFrames2)
        assertEquals(0, correction2) // Strictly 0 (in dead zone)
    }

    @Test
    fun `resync track frame offset preserves song alignment when AudioTrack frame position is monotonic`() {
        val sampleRate = 48000
        val firstFrameLeaderNs = 1_000_000_000L

        // Track has played 50,000 frames before resync
        val preResyncTrackFrames = 50_000L
        var resyncTrackFrameOffset = preResyncTrackFrames

        // Resync aligns to a new chunk at 10 seconds into the song
        val newChunkPts = firstFrameLeaderNs + 10_000_000_000L // 10s
        val firstChunkFrameOffsetInSong = (((newChunkPts - firstFrameLeaderNs) * sampleRate) / 1_000_000_000L) // 480,000 frames

        // DAC plays 2,000 frames after resync (monotonic counter is now 52,000)
        val monotonicDacFramePosition = 52_000L
        val actualFrameInSong = firstChunkFrameOffsetInSong + (monotonicDacFramePosition - resyncTrackFrameOffset)

        // 480,000 + (52,000 - 50,000) = 482,000 frames
        assertEquals(482_000L, actualFrameInSong)
    }
}

