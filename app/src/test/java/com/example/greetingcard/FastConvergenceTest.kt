package com.example.greetingcard

import com.example.greetingcard.audio.DriftController
import com.example.greetingcard.audio.JitterBuffer
import com.example.greetingcard.audio.PcmChunk
import com.example.greetingcard.audio.SyncPhase
import com.example.greetingcard.sync.DefaultTimeDomainConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FastConvergenceTest {

    @Test
    fun `startup seek instantly resolves 15ms offset down to sub-1_5ms on first frame`() {
        val sampleRate = 48000
        val bytesPerFrame = 4
        val timeDomainConverter = DefaultTimeDomainConverter()
        val jitterBuffer = JitterBuffer(timeDomainConverter, bufferDepthMs = 300L, sampleRate = sampleRate)

        // Populate jitter buffer with 500ms of audio
        for (i in 0 until 25) {
            val chunkFrames = 960
            val chunkBytes = ByteArray(chunkFrames * bytesPerFrame)
            jitterBuffer.addChunk(
                PcmChunk(
                    pcmData = chunkBytes,
                    presentationTimeNs = i * 20_000_000L,
                    frames = chunkFrames,
                    sampleRate = sampleRate,
                    sequenceNumber = i.toLong()
                )
            )
        }

        val initialErrorMs = 15.0 // Client is 15ms behind expected frame
        val errorFrames = ((initialErrorMs * sampleRate) / 1000.0).toInt() // 720 frames

        // Apply startup seek (threshold > 1.5ms)
        assertTrue(initialErrorMs > 1.5)
        val skipped = jitterBuffer.bulkSkip(errorFrames)
        assertEquals(720, skipped)
        assertEquals(720L, jitterBuffer.cumulativeDriftCorrection)

        // Residual error after startup seek
        val residualFrames = errorFrames - skipped
        val residualErrorMs = (residualFrames * 1000.0) / sampleRate
        assertEquals(0.0, residualErrorMs, 0.001)
    }

    @Test
    fun `startup seek instantly resolves negative 12ms lead down to sub-1_5ms via bulkStall`() {
        val sampleRate = 48000
        val timeDomainConverter = DefaultTimeDomainConverter()
        val jitterBuffer = JitterBuffer(timeDomainConverter, bufferDepthMs = 300L, sampleRate = sampleRate)

        val initialErrorMs = -12.0 // Client is 12ms ahead of expected frame
        val stallFrames = ((-initialErrorMs * sampleRate) / 1000.0).toInt() // 576 frames

        assertTrue(abs(initialErrorMs) > 1.5)
        jitterBuffer.bulkStall(stallFrames)
        assertEquals(-576L, jitterBuffer.cumulativeDriftCorrection)
    }

    @Test
    fun `drift controller aggressively converges residual 1_2ms error into deadzone within fast window`() {
        val sampleRate = 48000
        val controller = DriftController(
            sampleRate = sampleRate,
            errorSmoothingAlpha = 0.25,
            kp = 2e-8,
            ki = 5e-11,
            deadZoneNs = 50_000L, // 0.05 ms
            aggressiveErrorThresholdNs = 500_000L, // 0.5 ms
            maxAggressiveCorrection = 6
        )

        var currentErrorNs = 1_200_000.0 // 1.2 ms residual error
        var nowNs = 1_000_000_000L
        var chunkIndex = 0
        var enteredDeadZone = false
        var iterationsToDeadZone = 0

        // Simulate 20ms callback chunks
        while (chunkIndex < 40) {
            chunkIndex++
            nowNs += 20_000_000L // 20ms step

            val correctionFrames = controller.update(nowNs, currentErrorNs.toLong())

            // Correct error based on applied sample adjustment (negative correction drops frames and advances clock, reducing positive error)
            val correctedNs = (correctionFrames * 1_000_000_000.0) / sampleRate
            currentErrorNs += correctedNs

            if (abs(currentErrorNs) <= controller.deadZoneNs && !enteredDeadZone) {
                enteredDeadZone = true
                iterationsToDeadZone = chunkIndex
            }
        }

        assertTrue("Controller must enter dead zone (<50µs)", enteredDeadZone)
        assertTrue("Controller must converge in under 20 chunks (<400ms), took $iterationsToDeadZone chunks", iterationsToDeadZone <= 20)
        assertEquals(SyncPhase.FINE, controller.syncPhase)
    }
}

