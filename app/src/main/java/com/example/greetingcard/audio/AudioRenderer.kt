package com.example.greetingcard.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.util.Log
import com.example.greetingcard.sync.DefaultTimeDomainConverter
import com.example.greetingcard.sync.LocalAudioDacTracker
import com.example.greetingcard.sync.SystemTicker
import com.example.greetingcard.sync.Ticker
import com.example.greetingcard.sync.TimeDomainConverter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "AudioRenderer"
private const val TELEMETRY_TAG = "RendererTelemetry"

class AudioRenderer(
    private val jitterBuffer: JitterBuffer,
    private val sampleRate: Int = 48000,
    private val channels: Int = 2,
    private val timeDomainConverter: TimeDomainConverter = DefaultTimeDomainConverter(),
    private val localAudioDacTracker: LocalAudioDacTracker = LocalAudioDacTracker(),
    private val ticker: Ticker = SystemTicker(),
    private val startAtLeaderTimeNs: Long = 0L,
    private val firstFrameLeaderTimeNs: Long = 0L
) {
    private var audioTrack: AudioTrack? = null
    private var oboeRenderer: OboeAudioRenderer? = null
    private var isOboeActive = false

    private var playbackThread: Thread? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var telemetryDrainJob: Job? = null

    @Volatile private var isPlaying = false
    @Volatile private var needsResync = false
    @Volatile var manualTrimOffsetNs: Long = 0L
    @Volatile private var currentTrimMs: Double = 0.0

    val driftController = DriftController(sampleRate = sampleRate)
    val playbackScheduler = PlaybackScheduler(sampleRate = sampleRate)
    val telemetryBuffer = TelemetryRingBuffer(capacity = 16)

    fun start() {
        if (isPlaying) return
        isPlaying = true

        // Clean previous instances if any
        cleanupAudioEngines()

        val bytesPerFrame = channels * 2

        // 1. Try initializing Native Oboe Audio Renderer
        var nativeSuccess = false
        if (OboeAudioRenderer.isNativeAvailable()) {
            try {
                val oboe = OboeAudioRenderer(sampleRate = sampleRate, channels = channels)
                if (oboe.init()) {
                    oboeRenderer = oboe
                    isOboeActive = true
                    nativeSuccess = true
                    Log.i(TAG, "Native Oboe audio engine initialized (MMAP=${oboe.isMMap()})")
                } else {
                    Log.w(TAG, "Oboe initialization failed, falling back to AudioTrack")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Exception during Oboe init, falling back to AudioTrack", e)
            }
        }

        // 2. Fallback to Java AudioTrack if Oboe is not available
        if (!nativeSuccess) {
            isOboeActive = false
            val channelConfig = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val calculatedMin = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val minBufferSize = if (calculatedMin > 0) calculatedMin else (sampleRate * bytesPerFrame) / 10
            val bufferSize = (minBufferSize * 2).coerceAtLeast(8192)

            try {
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()

                val track = AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .setEncoding(audioFormat)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()

                audioTrack = track
                val initialHead = try { track.playbackHeadPosition } catch (e: Throwable) { 0 }
                Log.d(TAG, "AudioTrack initialized with playbackHeadPosition=$initialHead")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize AudioTrack fallback", e)
                isPlaying = false
                return
            }
        }

        // 3. Background non-RT telemetry drain coroutine (~100ms interval)
        if (isOboeActive && oboeRenderer != null) {
            telemetryDrainJob = scope.launch {
                while (isActive && isPlaying) {
                    val nativeRecords = oboeRenderer?.drainTelemetry(8) ?: emptyList()
                    for (rec in nativeRecords) {
                        if (rec.underrunCount > 0) {
                            Log.w(TAG, "Native Oboe underruns detected: ${rec.underrunCount}")
                        }
                    }
                    delay(100)
                }
            }
        }

        playbackThread = Thread {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (e: Throwable) {
                // Ignore in JVM / non-Android environment
            }

            val framesPerCallback = ((sampleRate * 20) / 1000).coerceAtLeast(480)
            val outputBuffer = ByteArray(framesPerCallback * bytesPerFrame)

            val initialDacLatencyNs = when {
                isOboeActive && oboeRenderer != null -> {
                    localAudioDacTracker.getLocalOboeDacLatencyNs(oboeRenderer!!, 0L, sampleRate)
                }
                audioTrack != null -> {
                    localAudioDacTracker.getLocalDacLatencyNs(audioTrack!!, 0L)
                }
                else -> 0L
            }

            var firstChunkFrameOffsetInSong = 0L

            // --- Unified PTS-Alignment Loop with Bounded Stale Drop for All Starts & Late-Joins ---
            if (firstFrameLeaderTimeNs > 0L || startAtLeaderTimeNs > 0L) {
                var alignedChunkPts = 0L
                var alignmentAttempts = 0
                val maxAlignmentAttempts = 5
                var futureChunkFound = false

                while (isPlaying && alignmentAttempts < maxAlignmentAttempts && !futureChunkFound) {
                    alignmentAttempts++
                    var droppedChunksCount = 0
                    val maxDropsPerAttempt = 500

                    while (isPlaying && droppedChunksCount < maxDropsPerAttempt) {
                        val headPts = jitterBuffer.peekFirstChunkPresentationTimeNs() ?: break
                        val headLocalPresentationNs = timeDomainConverter.localTimeForLeaderTimeNs(headPts)
                        val headStartLocal = headLocalPresentationNs - initialDacLatencyNs
                        val currentNow = ticker.readNanos()

                        if (headStartLocal > currentNow) {
                            alignedChunkPts = headPts
                            futureChunkFound = true

                            val prefillFrames = framesPerCallback
                            val prefillBuffer = ByteArray(prefillFrames * bytesPerFrame)
                            if (jitterBuffer.getChunk(prefillBuffer, prefillFrames)) {
                                if (isOboeActive && oboeRenderer != null) {
                                    oboeRenderer?.writeAudio(prefillBuffer, 0, prefillBuffer.size)
                                } else {
                                    audioTrack?.write(prefillBuffer, 0, prefillBuffer.size)
                                }
                            }

                            val waitNs = playbackScheduler.calculateWaitDurationNs(headStartLocal, currentNow)
                            if (waitNs > 0) {
                                val sleepMs = (waitNs / 1_000_000L) - 2L
                                if (sleepMs > 0) {
                                    try {
                                        Thread.sleep(sleepMs)
                                    } catch (e: InterruptedException) {
                                        if (!isPlaying) return@Thread
                                    }
                                }
                                while (isPlaying && ticker.readNanos() < headStartLocal) {
                                    // Busy-spin for sub-millisecond precision
                                }
                            }
                            break
                        } else {
                            jitterBuffer.popChunk()
                            droppedChunksCount++
                        }
                    }

                    if (!futureChunkFound && isPlaying) {
                        Log.w(TAG, "PTS-alignment attempt #$alignmentAttempts dropped $droppedChunksCount stale chunks; waiting for pre-buffer window to refill...")
                        try {
                            Thread.sleep(jitterBuffer.minPreBufferDurationMs)
                        } catch (e: InterruptedException) {
                            if (!isPlaying) return@Thread
                        }
                    }
                }

                if (firstFrameLeaderTimeNs > 0L && alignedChunkPts > firstFrameLeaderTimeNs) {
                    firstChunkFrameOffsetInSong = (((alignedChunkPts - firstFrameLeaderTimeNs) * sampleRate) / 1_000_000_000L).coerceAtLeast(0L)
                }
                Log.d(TAG, "Unified PTS alignment complete: firstChunkFrameOffsetInSong=$firstChunkFrameOffsetInSong (alignedChunkPts=$alignedChunkPts, firstFrameLeader=$firstFrameLeaderTimeNs)")
            }

            try {
                if (isOboeActive && oboeRenderer != null) {
                    oboeRenderer?.start()
                } else {
                    audioTrack?.play()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start audio playback stream", e)
                return@Thread
            }

            var lastDriftCheckNs = ticker.readNanos()
            var driftCheckCount = 0
            val dacTimestamp = AudioTimestamp()
            val oboeTsBuffer = LongArray(2)
            var resyncTrackFrameOffset = 0L
            var startupSeekDone = false

            val chunkIntervalNs = (framesPerCallback.toLong() * 1_000_000_000L) / sampleRate
            var nextPacingTimeNs = ticker.readNanos()

            while (isPlaying) {
                try {
                    if (needsResync) {
                        needsResync = false
                        startupSeekDone = false
                        Log.w(TAG, "Executing AudioRenderer in-stream resync via PTS alignment...")
                        driftController.reset()

                        val currentTrackFrame = when {
                            isOboeActive && oboeRenderer != null -> {
                                if (oboeRenderer!!.getHardwareTimestamp(oboeTsBuffer)) oboeTsBuffer[0] else 0L
                            }
                            audioTrack != null -> {
                                val ts = AudioTimestamp()
                                if (audioTrack?.getTimestamp(ts) == true) ts.framePosition else (audioTrack?.playbackHeadPosition?.toLong() ?: 0L)
                            }
                            else -> 0L
                        }
                        resyncTrackFrameOffset = currentTrackFrame

                        if (isOboeActive && oboeRenderer != null) {
                            oboeRenderer?.pause()
                            oboeRenderer?.flush()
                        } else {
                            audioTrack?.runCatching {
                                pause()
                                flush()
                            }
                        }

                        var alignedChunkPts = 0L
                        var alignmentAttempts = 0
                        val maxAlignmentAttempts = 3
                        var futureChunkFound = false

                        while (isPlaying && alignmentAttempts < maxAlignmentAttempts && !futureChunkFound) {
                            alignmentAttempts++
                            var droppedChunksCount = 0
                            val maxDropsPerAttempt = 500

                            while (isPlaying && droppedChunksCount < maxDropsPerAttempt) {
                                val headPts = jitterBuffer.peekFirstChunkPresentationTimeNs() ?: break
                                val headLocalPresentationNs = timeDomainConverter.localTimeForLeaderTimeNs(headPts)
                                val headStartLocal = headLocalPresentationNs - initialDacLatencyNs
                                val currentNow = ticker.readNanos()

                                if (headStartLocal > currentNow) {
                                    alignedChunkPts = headPts
                                    futureChunkFound = true
                                    val prefillFrames = framesPerCallback
                                    val prefillBuffer = ByteArray(prefillFrames * bytesPerFrame)
                                    if (jitterBuffer.getChunk(prefillBuffer, prefillFrames)) {
                                        if (isOboeActive && oboeRenderer != null) {
                                            oboeRenderer?.writeAudio(prefillBuffer, 0, prefillBuffer.size)
                                        } else {
                                            audioTrack?.write(prefillBuffer, 0, prefillBuffer.size)
                                        }
                                    }
                                    val waitNs = playbackScheduler.calculateWaitDurationNs(headStartLocal, currentNow)
                                    if (waitNs > 0) {
                                        val sleepMs = (waitNs / 1_000_000L) - 2L
                                        if (sleepMs > 0) {
                                            try {
                                                Thread.sleep(sleepMs)
                                            } catch (e: InterruptedException) {
                                                if (!isPlaying) return@Thread
                                            }
                                        }
                                        while (isPlaying && ticker.readNanos() < headStartLocal) {}
                                    }
                                    break
                                } else {
                                    jitterBuffer.popChunk()
                                    droppedChunksCount++
                                }
                            }
                            if (!futureChunkFound && isPlaying) {
                                try {
                                    Thread.sleep(jitterBuffer.minPreBufferDurationMs)
                                } catch (e: InterruptedException) {
                                    if (!isPlaying) return@Thread
                                }
                            }
                        }

                        if (firstFrameLeaderTimeNs > 0L && alignedChunkPts > firstFrameLeaderTimeNs) {
                            firstChunkFrameOffsetInSong = (((alignedChunkPts - firstFrameLeaderTimeNs) * sampleRate) / 1_000_000_000L).coerceAtLeast(0L)
                        }
                        if (isOboeActive && oboeRenderer != null) {
                            oboeRenderer?.start()
                        } else {
                            audioTrack?.runCatching { play() }
                        }
                        nextPacingTimeNs = ticker.readNanos()
                    }

                    // 1. Fetch pre-corrected PCM chunk from JitterBuffer (applies strided insert/drop)
                    jitterBuffer.getChunk(outputBuffer, framesPerCallback)

                    // 2. Output audio to Oboe SPSC buffer or AudioTrack
                    if (isOboeActive && oboeRenderer != null) {
                        oboeRenderer?.writeAudio(outputBuffer, 0, outputBuffer.size)

                        // Real-time pacing for non-blocking native SPSC ring buffer:
                        // Paces the Kotlin thread at exact 20ms chunk intervals so JitterBuffer
                        // maintains its target healthy buffer depth (~300ms) without being drained.
                        nextPacingTimeNs += chunkIntervalNs
                        val now = ticker.readNanos()
                        val waitNs = nextPacingTimeNs - now
                        if (waitNs > 1_500_000L) { // > 1.5ms
                            val sleepMs = (waitNs / 1_000_000L) - 1L
                            if (sleepMs > 0) {
                                try {
                                    Thread.sleep(sleepMs)
                                } catch (e: InterruptedException) {
                                    if (!isPlaying) break
                                }
                            }
                        }
                        while (isPlaying && ticker.readNanos() < nextPacingTimeNs) {
                            // Sub-millisecond high-precision busy wait
                        }
                        if (ticker.readNanos() - nextPacingTimeNs > 100_000_000L) {
                            nextPacingTimeNs = ticker.readNanos()
                        }
                    } else {
                        val written = audioTrack?.write(outputBuffer, 0, outputBuffer.size) ?: -1
                        if (written < 0) {
                            Log.w(TAG, "AudioTrack write error: $written")
                        }
                    }

                    // 3. Periodic Closed-Loop Drift Correction (synchronized with 20ms callback chunk write)
                    val nowNs = ticker.readNanos()
                    if (firstFrameLeaderTimeNs > 0L && nowNs - lastDriftCheckNs >= 20_000_000L) {
                        lastDriftCheckNs = nowNs
                        driftCheckCount++

                        var isTsValid = false
                        var dacNanoTime = 0L
                        var dacFramePosition = 0L
                        var isMMapMode = false

                        if (isOboeActive && oboeRenderer != null) {
                            isTsValid = oboeRenderer!!.getHardwareTimestamp(oboeTsBuffer)
                            if (isTsValid && oboeTsBuffer[1] > 0L) {
                                dacFramePosition = oboeTsBuffer[0]
                                dacNanoTime = oboeTsBuffer[1]
                                isMMapMode = oboeRenderer!!.isMMap()
                            } else {
                                isTsValid = false
                            }
                        } else if (audioTrack != null) {
                            val hasTs = try { audioTrack?.getTimestamp(dacTimestamp) ?: false } catch (e: Throwable) { false }
                            if (hasTs && dacTimestamp.nanoTime > 0L) {
                                isTsValid = true
                                dacFramePosition = dacTimestamp.framePosition
                                dacNanoTime = dacTimestamp.nanoTime
                            } else {
                                val headPos = audioTrack?.playbackHeadPosition?.toLong() ?: 0L
                                if (headPos > 0L) {
                                    isTsValid = true
                                    dacFramePosition = headPos
                                    dacNanoTime = ticker.readNanos()
                                }
                            }
                        }

                        if (isTsValid) {
                            val timestampLocalNs = ticker.convertMonotonicNanosToTickerNs(dacNanoTime)
                            val expectedFrame = playbackScheduler.projectExpectedFrame(
                                currentLocalTimeNs = timestampLocalNs,
                                firstFrameLeaderTimeNs = firstFrameLeaderTimeNs,
                                timeDomainConverter = timeDomainConverter,
                                manualTrimOffsetNs = manualTrimOffsetNs
                            )

                            val actualFrameInSong = firstChunkFrameOffsetInSong + (dacFramePosition - resyncTrackFrameOffset) + jitterBuffer.cumulativeDriftCorrection
                            val errorFrames = expectedFrame - actualFrameInSong
                            val rawErrorMs = (errorFrames * 1000.0) / sampleRate

                            if (!startupSeekDone && kotlin.math.abs(rawErrorMs) > 1.5) {
                                startupSeekDone = true
                                val skipFrames = errorFrames.toInt()
                                if (skipFrames > 0) {
                                    val skipped = jitterBuffer.bulkSkip(skipFrames)
                                    Log.i(TAG, "Startup seek: bulk-skipped $skipped frames (${rawErrorMs}ms lag)")
                                } else {
                                    jitterBuffer.bulkStall(-skipFrames)
                                    Log.i(TAG, "Startup seek: bulk-stalled ${-skipFrames} frames (${rawErrorMs}ms lead)")
                                }
                                driftController.reset()
                            } else {
                                startupSeekDone = true
                                if (kotlin.math.abs(rawErrorMs) > 500.0) {
                                    Log.w(TAG, "Catastrophic phase error detected (%.2f ms > 500 ms), triggering hard resync".format(rawErrorMs))
                                    requestResync()
                                } else {
                                    val rawCorrection = driftController.update(timestampLocalNs, expectedFrame, actualFrameInSong)
                                    val correction = jitterBuffer.clampCorrection(rawCorrection)

                                    if (correction != 0) {
                                        jitterBuffer.driftCorrectionSamples = correction
                                    }

                                    // Non-blocking telemetry handoff (~every 200ms = 10 checks at 20ms)
                                    if (driftCheckCount % 10 == 0) {
                                        val isAggressive = driftController.isInAggressiveWindow(timestampLocalNs)
                                        telemetryBuffer.tryOffer(
                                            timestampNs = timestampLocalNs,
                                            expectedFrame = expectedFrame,
                                            actualFrame = actualFrameInSong,
                                            phaseErrorMs = rawErrorMs,
                                            smoothedErrorNs = driftController.smoothedErrorNs,
                                            correction = correction,
                                            jitterBufferMs = jitterBuffer.queuedDurationMs,
                                            isAggressiveMode = isAggressive,
                                            timestampValid = true,
                                            leaderClockTimeNs = timeDomainConverter.leaderTimeForLocalTimeNs(timestampLocalNs),
                                            isMMap = isMMapMode,
                                            isHardwareDac = isOboeActive
                                        )
                                    }

                                    // Periodic Renderer Telemetry Logging (~every 1s = 50 checks at 20ms)
                                    if (driftCheckCount % 50 == 0) {
                                        val actionStr = when {
                                            correction < 0 -> "Drop ($correction)"
                                            correction > 0 -> "Insert (+$correction)"
                                            else -> "None (0)"
                                        }
                                        val sourceTag = if (isOboeActive) "Oboe[MMAP=$isMMapMode]" else "AudioTrack"
                                        Log.i(
                                            TELEMETRY_TAG,
                                            "[RendererTelemetry][$sourceTag] ExpectedFrame: $expectedFrame | ActualFrame: $actualFrameInSong | PhaseError: %.2f ms | Correction: $actionStr | JitterBuffer: %d ms"
                                                .format(rawErrorMs, jitterBuffer.queuedDurationMs)
                                        )
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isPlaying) Log.e(TAG, "Playback loop error", e)
                    break
                }
            }
        }.apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun cleanupAudioEngines() {
        telemetryDrainJob?.cancel()
        telemetryDrainJob = null

        audioTrack?.runCatching {
            stop()
            flush()
            release()
        }
        audioTrack = null

        oboeRenderer?.runCatching {
            stop()
            flush()
            release()
        }
        oboeRenderer = null
        isOboeActive = false
    }

    fun pollTelemetry(): TelemetryData? = telemetryBuffer.poll()

    fun drainTelemetry(maxItems: Int = 16): List<TelemetryData> = telemetryBuffer.drainAll(maxItems)

    fun requestResync() {
        Log.w(TAG, "requestResync() called on AudioRenderer")
        needsResync = true
    }

    fun setManualTrimMs(trimMs: Double) {
        val oldTrimMs = currentTrimMs
        currentTrimMs = trimMs
        manualTrimOffsetNs = (trimMs * 1_000_000.0).toLong()
        val deltaMs = trimMs - oldTrimMs
        val deltaFrames = ((deltaMs * sampleRate) / 1000.0).toInt()
        if (deltaFrames != 0) {
            jitterBuffer.trimCommandQueue.offer(deltaFrames)
            Log.i(TAG, "Queued manual trim delta: $deltaFrames frames ($deltaMs ms) -> target trim: $trimMs ms")
        }
    }

    fun stop() {
        isPlaying = false
        needsResync = false
        try {
            playbackThread?.interrupt()
            playbackThread?.join(500)
        } catch (e: Exception) {
            // Ignore interruption exception on teardown
        }
        playbackThread = null

        cleanupAudioEngines()
        driftController.reset()
        telemetryBuffer.clear()
    }
}

