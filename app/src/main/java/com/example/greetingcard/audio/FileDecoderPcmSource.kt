package com.example.greetingcard.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer

class FileDecoderPcmSource(file: File) : PcmSource {
    private val extractor = MediaExtractor()
    private val decoder: MediaCodec

    private var isExtractorEOS = false
    private var isDecoderEOS = false

    private val bufferInfo = MediaCodec.BufferInfo()

    // We may decode more PCM than fits in a single read() request, so we keep the remainder
    private var pendingPcmData: ByteArray? = null
    private var pendingPcmOffset = 0

    init {
        extractor.setDataSource(file.absolutePath)

        var trackIndex = -1
        var sourceFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) {
                trackIndex = i
                sourceFormat = format
                break
            }
        }

        if (trackIndex < 0 || sourceFormat == null) {
            throw IllegalStateException("No audio track found in file")
        }

        extractor.selectTrack(trackIndex)

        val sourceMime = sourceFormat.getString(MediaFormat.KEY_MIME)!!
        decoder = MediaCodec.createDecoderByType(sourceMime)
        decoder.configure(sourceFormat, null, null, 0)
        decoder.start()
    }

    override fun read(buffer: ByteArray, offset: Int, size: Int): Int {
        var bytesRead = 0

        while (bytesRead < size && !isDecoderEOS) {
            // 1. Drain pending PCM data if any
            if (pendingPcmData != null) {
                val available = pendingPcmData!!.size - pendingPcmOffset
                val toRead = minOf(size - bytesRead, available)
                System.arraycopy(pendingPcmData!!, pendingPcmOffset, buffer, offset + bytesRead, toRead)

                bytesRead += toRead
                pendingPcmOffset += toRead

                if (pendingPcmOffset >= pendingPcmData!!.size) {
                    pendingPcmData = null
                    pendingPcmOffset = 0
                }

                if (bytesRead >= size) {
                    return bytesRead
                }
            }

            // 2. Feed Extractor -> Decoder
            if (!isExtractorEOS) {
                val inputBufIndex = decoder.dequeueInputBuffer(10000)
                if (inputBufIndex >= 0) {
                    val inputBuf = decoder.getInputBuffer(inputBufIndex)
                    val sampleSize = extractor.readSampleData(inputBuf!!, 0)

                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputBufIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isExtractorEOS = true
                    } else {
                        val presentationTimeUs = extractor.sampleTime
                        decoder.queueInputBuffer(inputBufIndex, 0, sampleSize, presentationTimeUs, 0)
                        extractor.advance()
                    }
                }
            }

            // 3. Drain Decoder -> Pending PCM
            var decoderOutputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
            if (decoderOutputIndex >= 0) {
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    isDecoderEOS = true
                }

                val pcmBuf = decoder.getOutputBuffer(decoderOutputIndex)
                if (pcmBuf != null && bufferInfo.size > 0) {
                    val chunkData = ByteArray(bufferInfo.size)
                    pcmBuf.get(chunkData)
                    pendingPcmData = chunkData
                    pendingPcmOffset = 0
                }
                decoder.releaseOutputBuffer(decoderOutputIndex, false)
            }
        }

        return if (bytesRead == 0 && isDecoderEOS) -1 else bytesRead
    }

    override fun release() {
        try {
            decoder.stop()
            decoder.release()
            extractor.release()
        } catch (e: Exception) {
            // Ignore
        }
    }
}

