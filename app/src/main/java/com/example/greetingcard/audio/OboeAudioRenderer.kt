package com.example.greetingcard.audio

import android.util.Log

private const val TAG = "OboeAudioRenderer"

data class OboeTelemetryRecord(
    val timestampNs: Long,
    val framePosition: Long,
    val latencyMillis: Double,
    val underrunCount: Long,
    val ringBufferDepthFrames: Int,
    val isMMap: Boolean
)

class OboeAudioRenderer(
    val sampleRate: Int = 48000,
    val channels: Int = 2
) {
    private var enginePtr: Long = 0L
    private val rawTelemetryBuffer = LongArray(16 * 6) // 16 records * 6 fields
    private val timestampBuffer = LongArray(2)

    companion object {
        private var isLibraryLoaded = false

        init {
            try {
                System.loadLibrary("oboe_renderer")
                isLibraryLoaded = true
                Log.i(TAG, "Successfully loaded liboboe_renderer.so")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "liboboe_renderer.so not available: ${e.message}")
                isLibraryLoaded = false
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to load liboboe_renderer.so", e)
                isLibraryLoaded = false
            }
        }

        fun isNativeAvailable(): Boolean = isLibraryLoaded
    }

    fun init(): Boolean {
        if (!isLibraryLoaded) {
            Log.w(TAG, "Cannot init OboeAudioRenderer: native library is not loaded")
            return false
        }
        if (enginePtr != 0L) {
            release()
        }

        try {
            enginePtr = nativeInit(sampleRate, channels)
            if (enginePtr == 0L) {
                Log.e(TAG, "nativeInit returned null pointer")
                return false
            }

            val opened = nativeOpenStream(enginePtr)
            if (!opened) {
                Log.e(TAG, "nativeOpenStream failed")
                nativeRelease(enginePtr)
                enginePtr = 0L
                return false
            }

            Log.i(TAG, "OboeAudioRenderer initialized successfully (enginePtr=$enginePtr, isMMap=${isMMap()})")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "Exception during native initialization", e)
            if (enginePtr != 0L) {
                runCatching { nativeRelease(enginePtr) }
                enginePtr = 0L
            }
            return false
        }
    }

    fun start(): Boolean {
        if (enginePtr == 0L) return false
        return nativeStart(enginePtr)
    }

    fun pause() {
        if (enginePtr != 0L) {
            nativePause(enginePtr)
        }
    }

    fun stop() {
        if (enginePtr != 0L) {
            nativeStop(enginePtr)
        }
    }

    fun flush() {
        if (enginePtr != 0L) {
            nativeFlush(enginePtr)
        }
    }

    fun release() {
        if (enginePtr != 0L) {
            val ptr = enginePtr
            enginePtr = 0L
            nativeRelease(ptr)
            Log.i(TAG, "OboeAudioRenderer released")
        }
    }

    fun writeAudio(audioData: ByteArray, offset: Int, size: Int): Int {
        if (enginePtr == 0L || size <= 0) return 0
        return nativeWriteAudio(enginePtr, audioData, offset, size)
    }

    fun getHardwareTimestamp(outTimestamp: LongArray): Boolean {
        if (enginePtr == 0L || outTimestamp.size < 2) return false
        return nativeGetHardwareTimestamp(enginePtr, outTimestamp)
    }

    fun getHardwareTimestampPair(): Pair<Long, Long>? {
        if (enginePtr == 0L) return null
        val ok = nativeGetHardwareTimestamp(enginePtr, timestampBuffer)
        return if (ok && timestampBuffer[1] > 0L) {
            Pair(timestampBuffer[0], timestampBuffer[1])
        } else {
            null
        }
    }

    fun getLatencyMillis(): Double {
        if (enginePtr == 0L) return 0.0
        return nativeGetLatencyMillis(enginePtr)
    }

    fun isMMap(): Boolean {
        if (enginePtr == 0L) return false
        return nativeIsMMap(enginePtr)
    }

    fun drainTelemetry(maxEntries: Int = 16): List<OboeTelemetryRecord> {
        if (enginePtr == 0L) return emptyList()
        val count = nativeDrainTelemetry(enginePtr, rawTelemetryBuffer, maxEntries)
        if (count <= 0) return emptyList()

        val result = ArrayList<OboeTelemetryRecord>(count)
        for (i in 0 until count) {
            val base = i * 6
            val ts = rawTelemetryBuffer[base + 0]
            val framePos = rawTelemetryBuffer[base + 1]
            val latencyUs = rawTelemetryBuffer[base + 2]
            val underrun = rawTelemetryBuffer[base + 3]
            val depth = rawTelemetryBuffer[base + 4].toInt()
            val mmap = rawTelemetryBuffer[base + 5] == 1L

            result.add(
                OboeTelemetryRecord(
                    timestampNs = ts,
                    framePosition = framePos,
                    latencyMillis = latencyUs / 1000.0,
                    underrunCount = underrun,
                    ringBufferDepthFrames = depth,
                    isMMap = mmap
                )
            )
        }
        return result
    }

    fun setDriftCorrection(correctionSamples: Int) {
        if (enginePtr != 0L) {
            nativeSetDriftCorrection(enginePtr, correctionSamples)
        }
    }

    // Native JNI functions
    private external fun nativeInit(sampleRate: Int, channels: Int): Long
    private external fun nativeOpenStream(enginePtr: Long): Boolean
    private external fun nativeStart(enginePtr: Long): Boolean
    private external fun nativePause(enginePtr: Long)
    private external fun nativeStop(enginePtr: Long)
    private external fun nativeFlush(enginePtr: Long)
    private external fun nativeRelease(enginePtr: Long)
    private external fun nativeWriteAudio(enginePtr: Long, audioData: ByteArray, offset: Int, lengthInBytes: Int): Int
    private external fun nativeGetHardwareTimestamp(enginePtr: Long, outTimestamp: LongArray): Boolean
    private external fun nativeGetLatencyMillis(enginePtr: Long): Double
    private external fun nativeIsMMap(enginePtr: Long): Boolean
    private external fun nativeDrainTelemetry(enginePtr: Long, outBuffer: LongArray, maxEntries: Int): Int
    private external fun nativeSetDriftCorrection(enginePtr: Long, correctionSamples: Int)
}

