package com.example.greetingcard.audio

data class PcmReadTelemetry(
    val readDurationNs: Long = 0L,
    val hardwareCaptureTimestampNs: Long? = null,
    val readCompletedTimestampNs: Long = 0L
)

interface PcmSource {

    fun read(buffer: ByteArray, offset: Int, size: Int): Int

    val lastReadTelemetry: PcmReadTelemetry?
        get() = null

    fun release()
}

