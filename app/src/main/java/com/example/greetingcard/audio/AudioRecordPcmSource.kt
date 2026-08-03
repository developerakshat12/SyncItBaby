package com.example.greetingcard.audio

import android.media.AudioRecord
import android.media.AudioTimestamp
import android.util.Log
import kotlin.math.sqrt

class AudioRecordPcmSource(
    private val audioRecord: AudioRecord,
    private val onCaptureBlocked: () -> Unit
) : PcmSource {

    private var silenceFrames = 0L
    private val sampleRate = audioRecord.sampleRate
    // 10 seconds of continuous silence after audio started
    private val silenceThresholdFrames = 10L * sampleRate
    private var hasSeenAudio = false
    private var hasFiredBlockedCallback = false

    private val audioTimestamp = AudioTimestamp()

    @Volatile
    private var _lastReadTelemetry: PcmReadTelemetry? = null
    override val lastReadTelemetry: PcmReadTelemetry?
        get() = _lastReadTelemetry

    override fun read(buffer: ByteArray, offset: Int, size: Int): Int {
        val startNs = System.nanoTime()
        val bytesRead = audioRecord.read(buffer, offset, size, AudioRecord.READ_BLOCKING)
        val endNs = System.nanoTime()

        if (bytesRead < 0) {
            Log.e("AudioRecordPcmSource", "AudioRecord read error: $bytesRead")
            return -1
        }

        // Query hardware capture timestamp via AudioTimestamp.TIMEBASE_MONOTONIC
        var hwTimestampNs: Long? = null
        try {
            val status = audioRecord.getTimestamp(audioTimestamp, AudioTimestamp.TIMEBASE_MONOTONIC)
            if (status == AudioRecord.SUCCESS) {
                hwTimestampNs = audioTimestamp.nanoTime
            }
        } catch (t: Throwable) {
            // AudioTimestamp may not be supported on all device audio routing HALs
        }

        _lastReadTelemetry = PcmReadTelemetry(
            readDurationNs = endNs - startNs,
            hardwareCaptureTimestampNs = hwTimestampNs,
            readCompletedTimestampNs = endNs
        )

        if (bytesRead > 0 && !hasFiredBlockedCallback) {
            // Check RMS for silence detection
            var sum = 0.0
            for (i in 0 until bytesRead step 2) {
                // Read 16-bit PCM sample
                val sample = (buffer[offset + i].toInt() and 0xFF) or (buffer[offset + i + 1].toInt() shl 8)
                val shortSample = sample.toShort()
                sum += (shortSample * shortSample).toDouble()
            }

            val rms = sqrt(sum / (bytesRead / 2))

            // Near-silence threshold
            if (rms < 5.0) {
                // Only count silence if we have previously detected active audio
                // This gives users plenty of time to switch apps after starting capture
                if (hasSeenAudio) {
                    silenceFrames += (bytesRead / 4) // Stereo: 4 bytes per frame

                    if (silenceFrames > silenceThresholdFrames) {
                        Log.w("AudioRecordPcmSource", "Prolonged silence detected! Capture might be blocked.")
                        hasFiredBlockedCallback = true
                        onCaptureBlocked()
                    }
                }
            } else {
                hasSeenAudio = true
                silenceFrames = 0L
            }
        }

        return bytesRead
    }

    override fun release() {
        // audioRecord lifecycle is managed by AudioCaptureService, so we don't release it here.
    }
}

