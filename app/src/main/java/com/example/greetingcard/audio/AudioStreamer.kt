package com.example.greetingcard.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.example.greetingcard.protocol.AudioStreamConfig
import com.example.greetingcard.protocol.AudioSyncStartConfig
import com.example.greetingcard.protocol.Packet
import com.example.greetingcard.protocol.SyncConstants
import com.example.greetingcard.sync.TimeDomainConverter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private const val TAG = "AudioStreamer"
private const val TELEMETRY_TAG = "CaptureTelemetry"

class AudioStreamer(
    private val scope: CoroutineScope,
    private val timeDomainConverter: TimeDomainConverter,
    private val sendPacket: suspend (Packet) -> Unit,
    private val getSessionId: () -> UUID,
    private val getRttNs: () -> Long = { 0L }
) {
    private var streamingJob: Job? = null

    // Config constants for Opus streaming
    private val targetSampleRate = 48000
    private val targetChannels = 2
    private val targetBitrate = 128000

    // Recent packet cache for NACK retransmissions (~2.5s of audio frames)
    private val packetCache = java.util.concurrent.ConcurrentHashMap<Long, Packet>()
    private val maxPacketCacheSize = 128

    private val sequenceNumber = java.util.concurrent.atomic.AtomicLong(0L)

    @Volatile var isStreamingActive: Boolean = false
        private set
    @Volatile var currentStreamConfig: AudioStreamConfig? = null
        private set
    @Volatile var currentSyncStartConfig: AudioSyncStartConfig? = null
        private set

    fun getCurrentSequenceNumber(): Long = sequenceNumber.get()

    fun getCachedPacket(sequenceNumber: Long): Packet? = packetCache[sequenceNumber]

    companion object {

        const val OPUS_LOOKAHEAD_NS = 4_400_000L

        const val HAL_CALIBRATION_FRAMES = 30
    }

    fun startStreaming(file: File) {
        if (streamingJob?.isActive == true) {
            Log.w(TAG, "Already streaming, stopping previous stream first")
            stopStreaming()
        }

        streamingJob = scope.launch(Dispatchers.IO) {
            try {
                // Get duration for config
                val extractor = MediaExtractor()
                extractor.setDataSource(file.absolutePath)
                var durationUs = 0L
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                        durationUs = format.getLong(MediaFormat.KEY_DURATION)
                        break
                    }
                }
                extractor.release()

                val pcmSource = FileDecoderPcmSource(file)
                runStreamingLoop(pcmSource, file.name, (durationUs / 1000).toInt(), isLiveCapture = false)
            } catch (e: Exception) {
                Log.e(TAG, "Streaming error", e)
            }
        }
    }

    fun startStreamingFromCapture(pcmSource: PcmSource) {
        if (streamingJob?.isActive == true) {
            Log.w(TAG, "Already streaming, stopping previous stream first")
            stopStreaming()
        }

        streamingJob = scope.launch(Dispatchers.IO) {
            try {
                runStreamingLoop(pcmSource, "Device Audio", 0, isLiveCapture = true)
            } catch (e: Exception) {
                Log.e(TAG, "Streaming error", e)
            }
        }
    }

    fun startCalibration(durationSeconds: Int = 10) {
        if (streamingJob?.isActive == true) {
            Log.w(TAG, "Already streaming, stopping previous calibration first")
            stopStreaming()
        }

        streamingJob = scope.launch(Dispatchers.IO) {
            try {
                val pcmSource = CalibrationPcmSource(
                    sampleRate = targetSampleRate,
                    channels = targetChannels,
                    durationSeconds = durationSeconds
                )
                runStreamingLoop(
                    pcmSource = pcmSource,
                    trackName = "Calibration (${durationSeconds}s)",
                    totalDurationMs = durationSeconds * 1000,
                    isLiveCapture = false
                )
            } catch (e: Exception) {
                Log.e(TAG, "Calibration streaming error", e)
            }
        }
    }

    private suspend fun runStreamingLoop(
        pcmSource: PcmSource,
        trackName: String,
        totalDurationMs: Int,
        isLiveCapture: Boolean
    ) {
        var encoder: MediaCodec? = null

        try {
            isStreamingActive = true
            packetCache.clear()

            // 1. Send AudioStreamConfig Packet (always immediate — peer sets up decoder + JitterBuffer)
            val config = AudioStreamConfig(
                sampleRate = targetSampleRate,
                channels = targetChannels,
                bitrate = targetBitrate,
                frameDurationUs = 20000,
                totalDurationMs = totalDurationMs,
                trackName = trackName
            )
            currentStreamConfig = config

            val startPacket = Packet.build(
                packetType = SyncConstants.TYPE_AUDIO_STREAM_START,
                payload = config.toByteArray(),
                streamId = SyncConstants.STREAM_AUDIO,
                sessionUuid = getSessionId()
            )
            sendPacket(startPacket)

            // 2. Compute adaptive START_AT margin
            val p95RttNs = getRttNs()
            val adaptiveMarginNs = maxOf(2_000_000_000L, p95RttNs * 3)

            // For file mode: compute final anchor immediately and send sync start now.
            // For live capture: use a preliminary anchor for initial chunk PTS;
            //   the real sync start is deferred until HAL calibration completes.
            var firstFrameLeaderTimeNs = timeDomainConverter.currentLeaderTimeNs + adaptiveMarginNs
            var syncStartSent = false

            if (!isLiveCapture) {
                val startAtLeaderTimeNs = firstFrameLeaderTimeNs
                Log.d(TAG, "File stream: adaptiveMargin=${adaptiveMarginNs / 1_000_000}ms, startAt=$startAtLeaderTimeNs")

                val syncStartConfig = AudioSyncStartConfig(
                    startAtLeaderTimeNs = startAtLeaderTimeNs,
                    firstFrameLeaderTimeNs = firstFrameLeaderTimeNs,
                    sampleRate = targetSampleRate,
                    channels = targetChannels
                )
                currentSyncStartConfig = syncStartConfig

                val syncStartPacket = Packet.build(
                    packetType = SyncConstants.TYPE_AUDIO_SYNC_START,
                    payload = syncStartConfig.toByteArray(),
                    streamId = SyncConstants.STREAM_AUDIO,
                    sessionUuid = getSessionId()
                )
                sendPacket(syncStartPacket)
                syncStartSent = true
            } else {
                Log.d(TAG, "Live capture: deferring sync start until HAL calibration completes ($HAL_CALIBRATION_FRAMES frames)")
            }

            // 3. Setup Opus Encoder
            val encoderFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, targetSampleRate, targetChannels)
            encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            sequenceNumber.set(0L)
            val encoderBufferInfo = MediaCodec.BufferInfo()
            var isEncoderEOS = false
            var isSourceEOS = false
            var totalBytesProcessed = 0L

            // Latency Telemetry tracking
            val inputPtsQueueTimeNs = java.util.concurrent.ConcurrentHashMap<Long, Long>()
            var lastTelemetryLogFrame = 0L
            var accumulatedReadDurationNs = 0L
            var accumulatedHalLagNs = 0L
            var accumulatedEncoderLatencyNs = 0L
            var telemetrySampleCount = 0

            // Dynamic HAL capture lag calibration state (live capture only)
            var captureLagSamples = 0
            var captureLagAccumulator = 0L
            var liveChunksRead = 0

            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                // Read PCM Data -> Encoder input
                if (!isSourceEOS) {
                    val encoderInputIndex = encoder.dequeueInputBuffer(10000)
                    if (encoderInputIndex >= 0) {
                        val encoderInputBuf = encoder.getInputBuffer(encoderInputIndex)
                        encoderInputBuf?.clear()

                        val capacity = encoderInputBuf?.capacity() ?: 4096
                        val tempBuf = ByteArray(capacity)

                        val bytesRead = pcmSource.read(tempBuf, 0, tempBuf.size)
                        val readTelemetry = pcmSource.lastReadTelemetry

                        if (bytesRead < 0) {
                            encoder.queueInputBuffer(encoderInputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isSourceEOS = true
                        } else if (bytesRead > 0) {
                            encoderInputBuf?.put(tempBuf, 0, bytesRead)

                            // Calculate presentationTimeUs based on bytes processed
                            val framesProcessed = totalBytesProcessed / (targetChannels * 2)
                            val presentationTimeUs = (framesProcessed * 1_000_000L) / targetSampleRate

                            val queueTimeNs = System.nanoTime()
                            inputPtsQueueTimeNs[presentationTimeUs] = queueTimeNs

                            if (readTelemetry != null) {
                                accumulatedReadDurationNs += readTelemetry.readDurationNs
                                readTelemetry.hardwareCaptureTimestampNs?.let { hwNs ->
                                    val halLag = (readTelemetry.readCompletedTimestampNs - hwNs).coerceAtLeast(0L)
                                    accumulatedHalLagNs += halLag

                                    // Calibration: accumulate HAL lag samples for live capture
                                    if (isLiveCapture && captureLagSamples < HAL_CALIBRATION_FRAMES) {
                                        captureLagAccumulator += halLag
                                        captureLagSamples++
                                    }
                                }
                            }

                            if (isLiveCapture && !syncStartSent) {
                                liveChunksRead++
                                if (captureLagSamples >= HAL_CALIBRATION_FRAMES || liveChunksRead >= 10) {
                                    val lockedHalLagNs = if (captureLagSamples > 0) captureLagAccumulator / captureLagSamples else 20_000_000L
                                    val totalCompensationNs = lockedHalLagNs + OPUS_LOOKAHEAD_NS
                                    Log.d("AnchorIsolation", "lockedHalLagNs=$lockedHalLagNs, OPUS_LOOKAHEAD_NS=$OPUS_LOOKAHEAD_NS, applied=$totalCompensationNs, samples=$captureLagSamples, chunks=$liveChunksRead")

                                    // Shift the anchor backward by the total pipeline delay.
                                    // This makes all subsequent chunk PTS values align with
                                    // the actual moment the source app played the audio.
                                    firstFrameLeaderTimeNs -= totalCompensationNs

                                    // Compute a fresh start-at time (must be in the future)
                                    val startAtLeaderTimeNs = timeDomainConverter.currentLeaderTimeNs + adaptiveMarginNs

                                    Log.d(TAG, "HAL calibration locked: halLag=%.2f ms, opusLookahead=%.2f ms, totalCompensation=%.2f ms, correctedAnchor=$firstFrameLeaderTimeNs, startAt=$startAtLeaderTimeNs"
                                        .format(lockedHalLagNs / 1_000_000.0, OPUS_LOOKAHEAD_NS / 1_000_000.0, totalCompensationNs / 1_000_000.0))

                                    val syncStartConfig = AudioSyncStartConfig(
                                        startAtLeaderTimeNs = startAtLeaderTimeNs,
                                        firstFrameLeaderTimeNs = firstFrameLeaderTimeNs,
                                        sampleRate = targetSampleRate,
                                        channels = targetChannels
                                    )
                                    currentSyncStartConfig = syncStartConfig

                                    val syncStartPacket = Packet.build(
                                        packetType = SyncConstants.TYPE_AUDIO_SYNC_START,
                                        payload = syncStartConfig.toByteArray(),
                                        streamId = SyncConstants.STREAM_AUDIO,
                                        sessionUuid = getSessionId()
                                    )
                                    sendPacket(syncStartPacket)
                                    syncStartSent = true
                                }
                            }

                            encoder.queueInputBuffer(encoderInputIndex, 0, bytesRead, presentationTimeUs, 0)
                            totalBytesProcessed += bytesRead
                        } else {
                            encoder.queueInputBuffer(encoderInputIndex, 0, 0, 0, 0)
                        }
                    }
                }

                // Encoder output -> Network
                var encoderOutputIndex = encoder.dequeueOutputBuffer(encoderBufferInfo, 10000)
                while (encoderOutputIndex >= 0) {
                    val dequeueTimeNs = System.nanoTime()
                    val queueTimeNs = inputPtsQueueTimeNs.remove(encoderBufferInfo.presentationTimeUs)
                    if (queueTimeNs != null) {
                        accumulatedEncoderLatencyNs += (dequeueTimeNs - queueTimeNs).coerceAtLeast(0L)
                        telemetrySampleCount++
                    }

                    val encodedBuf = encoder.getOutputBuffer(encoderOutputIndex)
                    if (encodedBuf != null && encoderBufferInfo.size > 0) {
                        val chunkData = ByteArray(encoderBufferInfo.size)
                        encodedBuf.get(chunkData)

                        // PTS = anchor + elapsed audio time.  No per-chunk subtraction —
                        // the anchor itself already incorporates the capture compensation.
                        val chunkPresentationNs = firstFrameLeaderTimeNs + (encoderBufferInfo.presentationTimeUs * 1000)
                        val seqNum = sequenceNumber.getAndIncrement()

                        val audioPacket = Packet.build(
                            packetType = SyncConstants.TYPE_AUDIO_DATA,
                            payload = chunkData,
                            streamId = SyncConstants.STREAM_AUDIO,
                            sequenceNumber = seqNum,
                            timestampNs = chunkPresentationNs,
                            sessionUuid = getSessionId()
                        )

                        // Store in rolling cache for NACK retransmission requests
                        packetCache[seqNum] = audioPacket
                        if (packetCache.size > maxPacketCacheSize) {
                            val cutoff = seqNum - maxPacketCacheSize
                            packetCache.keys.removeIf { it < cutoff }
                        }

                        sendPacket(audioPacket)

                        // Periodic Telemetry Logging (~every 50 frames / 1s)
                        if (seqNum - lastTelemetryLogFrame >= 50 && telemetrySampleCount > 0) {
                            val avgReadMs = (accumulatedReadDurationNs / telemetrySampleCount.toDouble()) / 1_000_000.0
                            val avgHalLagMs = (accumulatedHalLagNs / telemetrySampleCount.toDouble()) / 1_000_000.0
                            val avgEncoderMs = (accumulatedEncoderLatencyNs / telemetrySampleCount.toDouble()) / 1_000_000.0
                            val leaderNow = timeDomainConverter.currentLeaderTimeNs
                            val leadAheadMs = (chunkPresentationNs - leaderNow) / 1_000_000.0

                            Log.i(
                                TELEMETRY_TAG,
                                "[CaptureTelemetry] frame=$seqNum | ReadBlock: %.2f ms | HAL Capture Lag: %.2f ms | Encoder Latency: %.2f ms | Pacing Lead: %.2f ms | SyncSent: $syncStartSent"
                                    .format(avgReadMs, avgHalLagMs, avgEncoderMs, leadAheadMs)
                            )

                            lastTelemetryLogFrame = seqNum
                            accumulatedReadDurationNs = 0L
                            accumulatedHalLagNs = 0L
                            accumulatedEncoderLatencyNs = 0L
                            telemetrySampleCount = 0
                        }

                        // Simple Pacing
                        val leaderNow = timeDomainConverter.currentLeaderTimeNs
                        val timeUntilPresentationNs = chunkPresentationNs - leaderNow

                        if (timeUntilPresentationNs > 500_000_000L) {
                            val delayMs = (timeUntilPresentationNs - 500_000_000L) / 1_000_000L
                            if (delayMs > 0) {
                                delay(delayMs)
                            }
                        }
                    }

                    encoder.releaseOutputBuffer(encoderOutputIndex, false)

                    if ((encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isEncoderEOS = true
                        break
                    }

                    encoderOutputIndex = encoder.dequeueOutputBuffer(encoderBufferInfo, 0)
                }

                if (isSourceEOS && isEncoderEOS) {
                    break
                }
            }
        } finally {
            isStreamingActive = false
            currentStreamConfig = null
            currentSyncStartConfig = null
            packetCache.clear()

            withContext(NonCancellable) {
                val stopPacket = Packet.build(
                    packetType = SyncConstants.TYPE_AUDIO_STREAM_STOP,
                    streamId = SyncConstants.STREAM_AUDIO,
                    sessionUuid = getSessionId()
                )
                try {
                    sendPacket(stopPacket)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send STOP packet", e)
                }
            }

            try {
                encoder?.stop()
                encoder?.release()
                pcmSource.release()
            } catch (e: Exception) {
                Log.e(TAG, "Cleanup error", e)
            }
        }
    }

    fun stopStreaming() {
        streamingJob?.cancel()
        streamingJob = null
    }
}

