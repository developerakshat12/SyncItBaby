package com.example.greetingcard.audio

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

interface AudioDecoder {
    fun dequeueInputBuffer(timeoutUs: Long): Int
    fun getInputBuffer(index: Int): ByteBuffer?
    fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int)
    fun dequeueOutputBuffer(info: MediaCodec.BufferInfo, timeoutUs: Long): Int
    fun getOutputBuffer(index: Int): ByteBuffer?
    fun releaseOutputBuffer(index: Int, render: Boolean)
    fun stop()
    fun release()
}

interface AudioDecoderFactory {
    fun createOpusDecoder(sampleRate: Int, channels: Int): AudioDecoder
}

fun createOpusMediaFormat(sampleRate: Int, channels: Int): MediaFormat {
    val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channels)

    // csd-0: 19-byte OpusHead structure
    val csd0 = ByteArray(19)
    csd0[0] = 'O'.code.toByte()
    csd0[1] = 'p'.code.toByte()
    csd0[2] = 'u'.code.toByte()
    csd0[3] = 's'.code.toByte()
    csd0[4] = 'H'.code.toByte()
    csd0[5] = 'e'.code.toByte()
    csd0[6] = 'a'.code.toByte()
    csd0[7] = 'd'.code.toByte()
    csd0[8] = 1 // Version 1
    csd0[9] = channels.toByte() // Channel count (1 or 2)
    // Pre-skip: 3840 samples (80ms at 48kHz) in Little-Endian
    val preSkip = 3840
    csd0[10] = (preSkip and 0xFF).toByte()
    csd0[11] = ((preSkip shr 8) and 0xFF).toByte()
    // Input Sample Rate in Little-Endian (48000 = 0xBB80)
    csd0[12] = (sampleRate and 0xFF).toByte()
    csd0[13] = ((sampleRate shr 8) and 0xFF).toByte()
    csd0[14] = ((sampleRate shr 16) and 0xFF).toByte()
    csd0[15] = ((sampleRate shr 24) and 0xFF).toByte()
    // Output gain: 0
    csd0[16] = 0
    csd0[17] = 0
    // Channel mapping family: 0
    csd0[18] = 0

    // csd-1: Pre-skip in nanoseconds (Long in native byte order)
    val preSkipNs = (preSkip.toLong() * 1_000_000_000L) / sampleRate
    val csd1 = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(preSkipNs).array()

    // csd-2: Seek pre-roll in nanoseconds (80ms = 80,000,000ns)
    val seekPreRollNs = 80_000_000L
    val csd2 = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(seekPreRollNs).array()

    format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
    format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1))
    format.setByteBuffer("csd-2", ByteBuffer.wrap(csd2))

    return format
}

class MediaCodecAudioDecoder(private val codec: MediaCodec) : AudioDecoder {
    override fun dequeueInputBuffer(timeoutUs: Long): Int = codec.dequeueInputBuffer(timeoutUs)
    override fun getInputBuffer(index: Int): ByteBuffer? = codec.getInputBuffer(index)
    override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) =
        codec.queueInputBuffer(index, offset, size, presentationTimeUs, flags)
    override fun dequeueOutputBuffer(info: MediaCodec.BufferInfo, timeoutUs: Long): Int =
        codec.dequeueOutputBuffer(info, timeoutUs)
    override fun getOutputBuffer(index: Int): ByteBuffer? = codec.getOutputBuffer(index)
    override fun releaseOutputBuffer(index: Int, render: Boolean) =
        codec.releaseOutputBuffer(index, render)
    override fun stop() = codec.stop()
    override fun release() = codec.release()
}

class DefaultAudioDecoderFactory : AudioDecoderFactory {
    override fun createOpusDecoder(sampleRate: Int, channels: Int): AudioDecoder {
        val format = createOpusMediaFormat(sampleRate, channels)
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        codec.configure(format, null, null, 0)
        codec.start()
        return MediaCodecAudioDecoder(codec)
    }
}

