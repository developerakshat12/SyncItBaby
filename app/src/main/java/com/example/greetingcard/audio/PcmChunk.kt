package com.example.greetingcard.audio

class PcmChunk(
    val pcmData: ByteArray,
    val presentationTimeNs: Long,
    val frames: Int,
    val sampleRate: Int = 48000,
    val sequenceNumber: Long = -1L
) {
    // Current read position in frames
    private var readPositionFrames: Int = 0
    private val bytesPerFrame = pcmData.size / frames
    private val nsPerFrame = 1_000_000_000L / sampleRate.toLong()

    fun readFrames(outputBuffer: ByteArray, outputOffset: Int, requestedFrames: Int): Int {
        val availableFrames = frames - readPositionFrames
        if (availableFrames <= 0) return 0

        val framesToRead = minOf(requestedFrames, availableFrames)
        val bytesToRead = framesToRead * bytesPerFrame
        val srcOffset = readPositionFrames * bytesPerFrame

        System.arraycopy(pcmData, srcOffset, outputBuffer, outputOffset, bytesToRead)
        readPositionFrames += framesToRead

        return framesToRead
    }

    fun seek(framesToSeek: Int) {
        readPositionFrames = (readPositionFrames + framesToSeek).coerceIn(0, frames)
    }

    fun isEndOfChunk(): Boolean = readPositionFrames >= frames

    val currentPresentationTimeNs: Long
        get() = presentationTimeNs + ((readPositionFrames.toLong() * 1_000_000_000L) / sampleRate.toLong())

    val durationNs: Long
        get() = (frames.toLong() * 1_000_000_000L) / sampleRate.toLong()
}

