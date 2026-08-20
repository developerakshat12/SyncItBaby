package com.example.greetingcard.audio

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.example.greetingcard.protocol.AudioStreamConfig
import com.example.greetingcard.protocol.AudioSyncStartConfig
import com.example.greetingcard.protocol.Packet
import com.example.greetingcard.sync.LocalAudioDacTracker
import com.example.greetingcard.sync.TimeDomainConverter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

private const val TAG = "AudioReceiver"
private const val TELEMETRY_TAG = "ReceiverTelemetry"

class AudioReceiver(
    private val timeDomainConverter: TimeDomainConverter,
    private val localAudioDacTracker: LocalAudioDacTracker = LocalAudioDacTracker(),
    private val decoderFactory: AudioDecoderFactory = DefaultAudioDecoderFactory(),
    private val onNackNeeded: ((Long) -> Unit)? = null,
    private val onStreamStateChanged: (Boolean) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var audioPacketChannel: Channel<Packet>? = null
    private var decodeJob: Job? = null

    private var decoder: AudioDecoder? = null
    private var jitterBuffer: JitterBuffer? = null
    private var audioRenderer: AudioRenderer? = null
    @Volatile private var isStreaming = false
    private var streamChannels = 2
    private var streamSampleRate = 48000

    private var pendingConfig: AudioStreamConfig? = null
    private var pendingSyncConfig: AudioSyncStartConfig? = null
    @Volatile private var activeTrimMs: Double = 0.0

    // Track sequence numbers for NACK gap detection
    private var lastReceivedSeq: Long = -1L
    private var expectedFirstSequenceNumber: Long = 0L

    private val codecLock = Any()

    // NACK lifecycle and skip tracking
    private val nackTracker = PendingNackTracker(
        scope = scope,
        onSaturationThresholdExceeded = {
            Log.w(TAG, "Drift saturation threshold reached in AudioReceiver, requesting renderer resync")
            audioRenderer?.requestResync()
        }
    )

    // Receiver Telemetry state & PTS to sequence mapping
    private val packetReceiveTimeNs = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val ptsToSeqMap = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private var lastReceiverTelemetrySeq = 0L
    private var accumulatedDecodeLatencyNs = 0L
    private var accumulatedLeadTimeNs = 0L
    private var telemetrySampleCount = 0

    fun startStream(configPayload: ByteArray, firstSequenceNumber: Long = 0L) {
        synchronized(codecLock) {
            if (isStreaming) {
                stopStream()
            }

            try {
                val config = AudioStreamConfig.fromByteArray(configPayload)
                Log.d(TAG, "Starting audio stream: $config, firstSequenceNumber=$firstSequenceNumber")

                streamChannels = config.channels
                streamSampleRate = config.sampleRate
                pendingConfig = config

                jitterBuffer = JitterBuffer(timeDomainConverter, bufferDepthMs = 300L, sampleRate = config.sampleRate)

                try {
                    decoder = decoderFactory.createOpusDecoder(config.sampleRate, config.channels)
                } catch (e: Exception) {
                    Log.e(TAG, "AudioDecoder creation failed in startStream", e)
                    stopStream()
                    throw e
                }

                nackTracker.start()

                isStreaming = true
                expectedFirstSequenceNumber = firstSequenceNumber
                lastReceivedSeq = if (firstSequenceNumber > 0L) firstSequenceNumber - 1L else -1L

                val channel = Channel<Packet>(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
                audioPacketChannel = channel
                decodeJob = scope.launch {
                    runDecodeLoop(channel)
                }

                // If sync start config arrived before stream start, start renderer now
                val syncConfig = pendingSyncConfig
                if (syncConfig != null) {
                    initAndStartRenderer(config, syncConfig)
                }

                onStreamStateChanged(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start audio stream", e)
                stopStream()
            }
        }
    }

    fun handleAudioSyncStart(packet: Packet) {
        synchronized(codecLock) {
            try {
                val syncConfig = AudioSyncStartConfig.fromByteArray(packet.payload)
                Log.d(TAG, "Received AudioSyncStartConfig: startAt=${syncConfig.startAtLeaderTimeNs}, firstFrame=${syncConfig.firstFrameLeaderTimeNs}")
                pendingSyncConfig = syncConfig

                val config = pendingConfig
                if (isStreaming && config != null && audioRenderer == null && jitterBuffer != null) {
                    initAndStartRenderer(config, syncConfig)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling TYPE_AUDIO_SYNC_START", e)
            }
        }
    }

    private fun initAndStartRenderer(config: AudioStreamConfig, syncConfig: AudioSyncStartConfig) {
        val jb = jitterBuffer ?: return
        audioRenderer = AudioRenderer(
            jitterBuffer = jb,
            sampleRate = config.sampleRate,
            channels = config.channels,
            timeDomainConverter = timeDomainConverter,
            localAudioDacTracker = localAudioDacTracker,
            startAtLeaderTimeNs = syncConfig.startAtLeaderTimeNs,
            firstFrameLeaderTimeNs = syncConfig.firstFrameLeaderTimeNs
        )
        audioRenderer?.setManualTrimMs(activeTrimMs)
        audioRenderer?.start()
        Log.d(TAG, "AudioRenderer initialized and started with scheduled sync startAt=${syncConfig.startAtLeaderTimeNs}")
    }

    fun detectSequenceGaps(seq: Long, onGapDetected: (Long) -> Unit) {
        if (lastReceivedSeq != -1L && seq > lastReceivedSeq + 1L) {
            val missingCount = seq - lastReceivedSeq - 1L
            Log.w(TAG, "Audio sequence gap detected: missing $missingCount packets between $lastReceivedSeq and $seq")
            for (missingSeq in (lastReceivedSeq + 1L) until seq) {
                if (missingSeq >= expectedFirstSequenceNumber) {
                    nackTracker.recordNack(missingSeq)
                    onGapDetected(missingSeq)
                }
            }
        }
        lastReceivedSeq = maxOf(lastReceivedSeq, seq)
    }

    fun handleAudioData(packet: Packet) {
        if (!isStreaming) return
        val seq = packet.header.sequenceNumber

        // Drop stale pre-disconnect / older packets strictly less than first expected sequence
        if (expectedFirstSequenceNumber > 0L && seq < expectedFirstSequenceNumber) {
            Log.d(TAG, "Dropping stale audio packet #$seq (expectedFirst=$expectedFirstSequenceNumber)")
            return
        }

        detectSequenceGaps(seq) { missingSeq ->
            onNackNeeded?.invoke(missingSeq)
        }
        val arrivalTimeNs = System.nanoTime()
        val presentationUs = packet.header.timestampNs / 1000
        packetReceiveTimeNs[presentationUs] = arrivalTimeNs
        ptsToSeqMap[presentationUs] = seq
        audioPacketChannel?.trySend(packet)
    }

    private suspend fun runDecodeLoop(channel: Channel<Packet>) {
        val pendingPackets = ArrayDeque<Packet>()
        try {
            for (packet in channel) {
                if (!isStreaming) break
                processAudioPacket(packet, pendingPackets)
            }
        } catch (e: Exception) {
            if (isStreaming) {
                Log.e(TAG, "Error in audio decode loop", e)
            }
        }
    }

    private fun processAudioPacket(packet: Packet, pendingPackets: ArrayDeque<Packet>) {
        synchronized(codecLock) {
            val dec = decoder ?: return
            val jb = jitterBuffer ?: return

            try {
                // 1. Drain available output buffers first to free up slots
                drainOutputBuffers(dec, jb)

                // 2. Retry any queued pending packets first
                while (pendingPackets.isNotEmpty()) {
                    val pending = pendingPackets.first()
                    val inputIndex = dec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuf = dec.getInputBuffer(inputIndex)
                        inputBuf?.clear()
                        inputBuf?.put(pending.payload)
                        dec.queueInputBuffer(
                            inputIndex,
                            0,
                            pending.payload.size,
                            pending.header.timestampNs / 1000,
                            0
                        )
                        pendingPackets.removeFirst()
                        drainOutputBuffers(dec, jb)
                    } else {
                        break
                    }
                }

                // 3. Feed current packet if pending queue is empty
                if (pendingPackets.isEmpty()) {
                    var inputBufferIndex = dec.dequeueInputBuffer(10_000)
                    if (inputBufferIndex < 0) {
                        drainOutputBuffers(dec, jb)
                        inputBufferIndex = dec.dequeueInputBuffer(10_000)
                    }

                    if (inputBufferIndex >= 0) {
                        val inputBuffer = dec.getInputBuffer(inputBufferIndex)
                        inputBuffer?.clear()
                        inputBuffer?.put(packet.payload)

                        dec.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            packet.payload.size,
                            packet.header.timestampNs / 1000,
                            0
                        )
                    } else {
                        Log.w(TAG, "Decoder input buffer busy, packet #${packet.header.sequenceNumber} queued for retry")
                        pendingPackets.addLast(packet)
                    }
                } else {
                    Log.w(TAG, "Decoder input buffer busy, packet #${packet.header.sequenceNumber} queued for retry")
                    pendingPackets.addLast(packet)
                }

                // 4. Drain output buffers after feeding input
                drainOutputBuffers(dec, jb)
            } catch (e: Exception) {
                Log.e(TAG, "Error decoding audio data", e)
            }
        }
    }

    private fun drainOutputBuffers(dec: AudioDecoder, jb: JitterBuffer) {
        val bufferInfo = MediaCodec.BufferInfo()
        val bytesPerFrame = streamChannels * 2 // 16-bit samples
        var outputBufferIndex = dec.dequeueOutputBuffer(bufferInfo, 0)

        while (outputBufferIndex >= 0) {
            val nowNs = System.nanoTime()
            val arrivalNs = packetReceiveTimeNs.remove(bufferInfo.presentationTimeUs)
            val seq = ptsToSeqMap.remove(bufferInfo.presentationTimeUs) ?: -1L
            if (arrivalNs != null) {
                accumulatedDecodeLatencyNs += (nowNs - arrivalNs).coerceAtLeast(0L)
                val leaderNow = timeDomainConverter.currentLeaderTimeNs
                val ptsNs = bufferInfo.presentationTimeUs * 1000
                accumulatedLeadTimeNs += (ptsNs - leaderNow)
                telemetrySampleCount++
            }

            val outputBuffer = dec.getOutputBuffer(outputBufferIndex)
            if (outputBuffer != null && bufferInfo.size > 0) {
                val pcmData = ByteArray(bufferInfo.size)
                outputBuffer.get(pcmData)

                val frames = pcmData.size / bytesPerFrame
                val presentationTimeNs = bufferInfo.presentationTimeUs * 1000

                val chunk = PcmChunk(pcmData, presentationTimeNs, frames, streamSampleRate, sequenceNumber = seq)
                val accepted = jb.addChunk(chunk)
                if (seq != -1L) {
                    nackTracker.resolve(seq, wasSkipped = !accepted)
                }

                // Periodic Receiver Telemetry Logging (~every 50 chunks)
                if (telemetrySampleCount >= 50) {
                    val avgDecodeMs = (accumulatedDecodeLatencyNs / telemetrySampleCount.toDouble()) / 1_000_000.0
                    val avgLeadMs = (accumulatedLeadTimeNs / telemetrySampleCount.toDouble()) / 1_000_000.0
                    val queuedJbMs = jb.queuedDurationMs

                    Log.i(
                        TELEMETRY_TAG,
                        "[ReceiverTelemetry] Decode Latency: %.2f ms | Packet Lead Ahead: %.2f ms | JitterBuffer Depth: %d ms"
                            .format(avgDecodeMs, avgLeadMs, queuedJbMs)
                    )

                    accumulatedDecodeLatencyNs = 0L
                    accumulatedLeadTimeNs = 0L
                    telemetrySampleCount = 0
                }
            }
            dec.releaseOutputBuffer(outputBufferIndex, false)
            outputBufferIndex = dec.dequeueOutputBuffer(bufferInfo, 0)
        }
    }

    fun stopStream() {
        synchronized(codecLock) {
            if (!isStreaming && decoder == null) return
            isStreaming = false

            nackTracker.stop()

            audioPacketChannel?.close()
            audioPacketChannel = null

            decodeJob?.cancel()
            decodeJob = null

            audioRenderer?.stop()
            audioRenderer = null

            jitterBuffer?.clear()
            jitterBuffer = null

            pendingConfig = null
            pendingSyncConfig = null
            lastReceivedSeq = -1L
            expectedFirstSequenceNumber = 0L
            packetReceiveTimeNs.clear()
            ptsToSeqMap.clear()
            accumulatedDecodeLatencyNs = 0L
            accumulatedLeadTimeNs = 0L
            telemetrySampleCount = 0

            try {
                decoder?.stop()
                decoder?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping decoder", e)
            }
            decoder = null

            onStreamStateChanged(false)
        }
    }

    fun drainRendererTelemetry(): List<TelemetryData> {
        return audioRenderer?.drainTelemetry() ?: emptyList()
    }

    fun setManualTrimMs(trimMs: Double) {
        synchronized(codecLock) {
            activeTrimMs = trimMs
            audioRenderer?.setManualTrimMs(trimMs)
        }
    }
}

