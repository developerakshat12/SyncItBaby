package com.example.greetingcard.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

class CalibrationPcmSource(
    val sampleRate: Int = 48000,
    val channels: Int = 2,
    val durationSeconds: Int = 10
) : PcmSource {

    private val bytesPerSample = 2 // 16-bit PCM
    private val bytesPerFrame = channels * bytesPerSample
    private val totalFrames = sampleRate * durationSeconds
    private val totalBytes = totalFrames * bytesPerFrame

    private val pcmData: ByteArray = ByteArray(totalBytes)
    private var readPositionBytes: Int = 0

    init {
        generateCalibrationPattern()
    }

    private fun generateCalibrationPattern() {
        val buffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)
        val beepFreq = 440.0 // A4 tone
        val beepDurationFrames = (sampleRate * 0.050).toInt() // 50 ms beep
        val gapDurationFrames = (sampleRate * 0.100).toInt()  // 100 ms gap

        val frameAmplitude = 16000.0 // Audible volume without clipping

        for (sec in 1..durationSeconds) {
            val secondStartFrame = (sec - 1) * sampleRate
            val numBeeps = sec

            for (b in 0 until numBeeps) {
                val beepStartFrame = secondStartFrame + b * (beepDurationFrames + gapDurationFrames)
                if (beepStartFrame + beepDurationFrames > secondStartFrame + sampleRate) {
                    break // Prevent overflowing past the 1-second boundary
                }

                for (f in 0 until beepDurationFrames) {
                    val frameIndex = beepStartFrame + f
                    if (frameIndex >= totalFrames) break

                    val t = f.toDouble() / sampleRate
                    // Apply subtle smooth envelope at the edges of the 50ms beep to prevent click artifacts
                    val envelope = when {
                        f < 100 -> f / 100.0
                        f > beepDurationFrames - 100 -> (beepDurationFrames - f) / 100.0
                        else -> 1.0
                    }
                    val sampleVal = (sin(2.0 * Math.PI * beepFreq * t) * frameAmplitude * envelope).toInt().toShort()

                    val byteOffset = frameIndex * bytesPerFrame
                    buffer.position(byteOffset)
                    for (c in 0 until channels) {
                        buffer.putShort(sampleVal)
                    }
                }
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, size: Int): Int {
        val remainingBytes = totalBytes - readPositionBytes
        if (remainingBytes <= 0) {
            return -1 // End of Stream
        }

        val bytesToCopy = minOf(size, remainingBytes)
        System.arraycopy(pcmData, readPositionBytes, buffer, offset, bytesToCopy)
        readPositionBytes += bytesToCopy
        return bytesToCopy
    }

    override fun release() {
        // No-op for synthetic in-memory source
    }
}

