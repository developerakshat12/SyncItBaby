package com.example.greetingcard.audio

import android.util.Log
import com.example.greetingcard.sync.TimeDomainConverter
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val TAG = "JitterBuffer"

private fun safeLogD(tag: String, message: String) {
    runCatching { Log.d(tag, message) }.onFailure { println("[$tag] $message") }
}

private fun safeLogW(tag: String, message: String) {
    runCatching { Log.w(tag, message) }.onFailure { println("[$tag] WARN: $message") }
}

enum class BufferHealth {
    CRITICAL,
    LOW,
    NOMINAL,
    HIGH
}

class JitterBuffer(
    private val timeDomainConverter: TimeDomainConverter,
    private val bufferDepthMs: Long = 200L,
    private val sampleRate: Int = 48000
) {
    private val lock = ReentrantLock()
    private val chunks = ArrayDeque<PcmChunk>()

    // Total duration of queued PCM frames in nanoseconds
    private var queuedDurationNs = 0L
    private val minPreBufferDurationNs = bufferDepthMs * 1_000_000L
    private val maxQueueDurationNs = 5_000_000_000L // 5 seconds max queue size for scheduled playback

    // Expose queued duration in milliseconds for buffer readiness checks
    val queuedDurationMs: Long
        get() = lock.withLock { queuedDurationNs / 1_000_000L }

    val minPreBufferDurationMs: Long
        get() = bufferDepthMs

    val isPreBufferingActive: Boolean
        get() = lock.withLock { isPreBuffering }

    // We keep track of the chunk currently being read
    private var activeChunk: PcmChunk? = null

    // For initial pre-buffering to absorb startup network jitter
    private var isPreBuffering = true

    // Frames to bytes conversion (assuming 16-bit stereo)
    private val bytesPerFrame = 4
    private val maxAggressiveCorrection = 8
    private var scratchBuffer = ByteArray(4096 * bytesPerFrame)

    // Volume tracking for smooth fade-in and fade-out to prevent popping artifacts
    @Volatile var currentVolume: Float = 1.0f
        private set

    @Volatile var driftCorrectionSamples: Int = 0

    @Volatile var cumulativeDriftCorrection: Long = 0L

    private var pendingStallFrames: Int = 0

    val trimCommandQueue = ConcurrentLinkedQueue<Int>()

    // Default fade length in frames (~5ms at 48kHz = 240 frames)
    private val fadeFramesCount = (sampleRate * 5) / 1000

    fun applyTrimAdjustment(deltaFrames: Int) {
        lock.withLock {
            if (deltaFrames == 0) return
            if (deltaFrames > 0) {
                val fadeFrames = 64.coerceAtLeast(16)
                val fadeBytes = fadeFrames * bytesPerFrame
                val currentBuf = ByteArray(fadeBytes)
                val targetBuf = ByteArray(fadeBytes)

                val currentOk = readFrames(currentBuf, 0, fadeFrames)
                if (currentOk) {
                    bulkSkip(deltaFrames)
                    val targetOk = readFrames(targetBuf, 0, fadeFrames)
                    if (targetOk) {
                        for (i in 0 until fadeFrames) {
                            val alpha = i.toFloat() / (fadeFrames - 1).coerceAtLeast(1)
                            for (ch in 0 until 2) {
                                val sampleIdx = (i * 2 + ch) * 2
                                if (sampleIdx + 1 < fadeBytes) {
                                    val sA = ((currentBuf[sampleIdx + 1].toInt() shl 8) or (currentBuf[sampleIdx].toInt() and 0xFF)).toShort()
                                    val sB = ((targetBuf[sampleIdx + 1].toInt() shl 8) or (targetBuf[sampleIdx].toInt() and 0xFF)).toShort()
                                    val blended = ((1.0f - alpha) * sA + alpha * sB).toInt().coerceIn(-32768, 32767).toShort()
                                    targetBuf[sampleIdx] = (blended.toInt() and 0xFF).toByte()
                                    targetBuf[sampleIdx + 1] = ((blended.toInt() shr 8) and 0xFF).toByte()
                                }
                            }
                        }
                        // If activeChunk has remaining unread frames, push it back to the head of chunks
                        if (activeChunk != null) {
                            val remainingChunk = activeChunk
                            activeChunk = null
                            if (remainingChunk != null && !remainingChunk.isEndOfChunk()) {
                                chunks.addFirst(remainingChunk)
                                queuedDurationNs += remainingChunk.durationNs
                            }
                        }

                        val blendedChunk = PcmChunk(
                            pcmData = targetBuf,
                            presentationTimeNs = highestPtsNsSeen,
                            frames = fadeFrames,
                            sampleRate = sampleRate
                        )
                        activeChunk = blendedChunk
                        queuedDurationNs += blendedChunk.durationNs
                    }
                } else {
                    bulkSkip(deltaFrames)
                }
            } else {
                val stallFrames = kotlin.math.abs(deltaFrames)
                pendingStallFrames += stallFrames
                cumulativeDriftCorrection -= stallFrames
                safeLogD(TAG, "applyTrimAdjustment: queued $stallFrames frames stall")
            }
        }
    }

    fun getBufferHealth(): BufferHealth {
        return lock.withLock {
            val queuedMs = queuedDurationNs / 1_000_000L
            when {
                queuedMs < 40 -> BufferHealth.CRITICAL           // < 40ms (danger of underrun)
                queuedMs < (bufferDepthMs / 2) -> BufferHealth.LOW // < half of target depth
                queuedMs > (bufferDepthMs * 1.5) -> BufferHealth.HIGH // > 1.5x target depth
                else -> BufferHealth.NOMINAL
            }
        }
    }

    fun clampCorrection(rawCorrection: Int): Int {
        val health = getBufferHealth()
        return when {
            // Dropping frames (speeding up) when buffer is low -> clamp hard to prevent underrun
            rawCorrection < 0 && health == BufferHealth.CRITICAL -> 0
            rawCorrection < 0 && health == BufferHealth.LOW -> rawCorrection.coerceIn(-1, 0)

            // Inserting frames (slowing down) when buffer is high -> clamp to prevent overflow
            rawCorrection > 0 && health == BufferHealth.HIGH -> rawCorrection.coerceIn(0, 1)

            else -> rawCorrection
        }
    }

    fun bulkSkip(frames: Int): Int {
        return lock.withLock {
            if (frames <= 0) return@withLock 0
            var remaining = frames
            val tempBuf = ByteArray(960 * bytesPerFrame)
            while (remaining > 0) {
                val toRead = remaining.coerceAtMost(960)
                val ok = readFrames(tempBuf, 0, toRead)
                if (!ok) break // Buffer exhausted
                remaining -= toRead
            }
            val skipped = frames - remaining
            cumulativeDriftCorrection += skipped
            safeLogD(TAG, "bulkSkip: discarded $skipped frames (requested $frames)")
            skipped
        }
    }

    fun bulkStall(frames: Int) {
        lock.withLock {
            if (frames <= 0) return@withLock
            pendingStallFrames += frames
            cumulativeDriftCorrection -= frames
            safeLogD(TAG, "bulkStall: queued $frames frames of silence")
        }
    }

    fun peekFirstChunkPresentationTimeNs(): Long? {
        return lock.withLock {
            activeChunk?.presentationTimeNs ?: chunks.peekFirst()?.presentationTimeNs
        }
    }

    fun popChunk(): PcmChunk? {
        return lock.withLock {
            if (activeChunk != null) {
                val chunk = activeChunk
                activeChunk = null
                chunk
            } else {
                val chunk = chunks.pollFirst()
                if (chunk != null) {
                    queuedDurationNs = (queuedDurationNs - chunk.durationNs).coerceAtLeast(0L)
                }
                chunk
            }
        }
    }

    // Track highest PTS to drop out-of-order late arrivals / duplicates
    private var highestPtsNsSeen = -1L

    fun addChunk(chunk: PcmChunk): Boolean {
        lock.withLock {
            if (highestPtsNsSeen != -1L && chunk.presentationTimeNs <= highestPtsNsSeen) {
                safeLogW(TAG, "Dropping stale/duplicate chunk: PTS=${chunk.presentationTimeNs} <= highestSeen=$highestPtsNsSeen (seq=${chunk.sequenceNumber})")
                return false
            }
            highestPtsNsSeen = chunk.presentationTimeNs

            chunks.addLast(chunk)
            queuedDurationNs += chunk.durationNs

            // Garbage collection: if queue grows too large (e.g. stalled playback), drop oldest frames
            while (queuedDurationNs > maxQueueDurationNs && chunks.isNotEmpty()) {
                val dropped = chunks.removeFirst()
                queuedDurationNs -= dropped.durationNs
                safeLogW(TAG, "Queue overflow: dropping oldest chunk to prevent latency build-up")
            }

            // If pre-buffering and we have accumulated target depth, transition to playback
            if (isPreBuffering && queuedDurationNs >= minPreBufferDurationNs) {
                isPreBuffering = false
                safeLogD(TAG, "Pre-buffering complete ($bufferDepthMs ms accumulated). Ready for playback.")
            }
            return true
        }
    }

    fun clear() {
        trimCommandQueue.clear()
        lock.withLock {
            chunks.clear()
            activeChunk = null
            queuedDurationNs = 0L
            isPreBuffering = true
            currentVolume = 1.0f
            highestPtsNsSeen = -1L
            driftCorrectionSamples = 0
            cumulativeDriftCorrection = 0L
            pendingStallFrames = 0
        }
    }

    fun getChunk(outputBuffer: ByteArray, requestedFrames: Int): Boolean {
        // Drain pending UI trim commands safely on the audio thread
        var pendingTrim = trimCommandQueue.poll()
        while (pendingTrim != null) {
            applyTrimAdjustment(pendingTrim)
            pendingTrim = trimCommandQueue.poll()
        }

        lock.withLock {
            // Handle pending macro-stall frames (leading correction)
            if (pendingStallFrames > 0) {
                val stallCount = minOf(pendingStallFrames, requestedFrames)
                writeSilence(outputBuffer, 0, stallCount)
                pendingStallFrames -= stallCount

                if (stallCount < requestedFrames) {
                    val remainingFrames = requestedFrames - stallCount
                    readFrames(outputBuffer, stallCount * bytesPerFrame, remainingFrames)
                }
                return true
            }

            // If still pre-buffering or buffer is completely empty
            if (isPreBuffering || (activeChunk == null && chunks.isEmpty())) {
                writeSilence(outputBuffer, 0, requestedFrames)
                if (chunks.isEmpty() && !isPreBuffering) {
                    // Buffer underrun occurred — enter pre-buffering to re-absorb jitter
                    isPreBuffering = true
                    currentVolume = 0.0f
                    safeLogD(TAG, "Buffer underrun: re-entering pre-buffering state")
                }
                return false
            }

            val correction = driftCorrectionSamples
            val success: Boolean

            when {
                correction > 0 && requestedFrames > 1 -> {
                    // Strided Insert (Duplicate) sample(s):
                    // Read (requestedFrames - insertCount) frames from buffer,
                    // then distribute duplicated frames across the buffer at stride intervals (working backwards)
                    val insertCount = correction.coerceAtMost(requestedFrames - 1).coerceAtMost(maxAggressiveCorrection)
                    val readFramesCount = requestedFrames - insertCount
                    val readOk = readFrames(outputBuffer, 0, readFramesCount)
                    if (readOk) {
                        val stride = readFramesCount / insertCount
                        var srcIdx = readFramesCount - 1
                        var dstIdx = requestedFrames - 1

                        for (i in insertCount - 1 downTo 0) {
                            val insertSrcIdx = (i + 1) * stride - 1
                            while (srcIdx > insertSrcIdx && srcIdx >= 0 && dstIdx >= 0) {
                                System.arraycopy(outputBuffer, srcIdx * bytesPerFrame, outputBuffer, dstIdx * bytesPerFrame, bytesPerFrame)
                                srcIdx--
                                dstIdx--
                            }
                            if (srcIdx >= 0 && dstIdx >= 0) {
                                System.arraycopy(outputBuffer, srcIdx * bytesPerFrame, outputBuffer, dstIdx * bytesPerFrame, bytesPerFrame)
                                dstIdx--
                                if (dstIdx >= 0) {
                                    System.arraycopy(outputBuffer, srcIdx * bytesPerFrame, outputBuffer, dstIdx * bytesPerFrame, bytesPerFrame)
                                    dstIdx--
                                }
                                srcIdx--
                            }
                        }
                        while (srcIdx >= 0 && dstIdx >= 0) {
                            System.arraycopy(outputBuffer, srcIdx * bytesPerFrame, outputBuffer, dstIdx * bytesPerFrame, bytesPerFrame)
                            srcIdx--
                            dstIdx--
                        }

                        driftCorrectionSamples = 0
                        cumulativeDriftCorrection -= correction
                        success = true
                    } else {
                        success = false
                    }
                }
                correction < 0 -> {
                    // Strided Drop sample(s):
                    // Read (requestedFrames + safeDropCount) frames into scratchBuffer,
                    // then copy to outputBuffer while skipping 1 frame every stride interval.
                    val queuedFrames = (queuedDurationNs * sampleRate / 1_000_000_000L).toInt()
                    val safeDropCount = minOf(kotlin.math.abs(correction), maxOf(0, queuedFrames - requestedFrames)).coerceAtMost(maxAggressiveCorrection)
                    val totalFramesToRead = requestedFrames + safeDropCount

                    val requiredScratchBytes = totalFramesToRead * bytesPerFrame
                    if (scratchBuffer.size < requiredScratchBytes) {
                        scratchBuffer = ByteArray(requiredScratchBytes)
                    }

                    val readOk = readFrames(scratchBuffer, 0, totalFramesToRead)
                    if (readOk) {
                        if (safeDropCount > 0) {
                            val stride = totalFramesToRead / safeDropCount
                            var srcIdx = 0
                            var dstIdx = 0
                            var dropsDone = 0

                            while (srcIdx < totalFramesToRead && dstIdx < requestedFrames) {
                                val isDropPoint = (dropsDone < safeDropCount) && (srcIdx == (dropsDone + 1) * stride - 1)
                                if (isDropPoint) {
                                    dropsDone++
                                    srcIdx++
                                } else {
                                    System.arraycopy(scratchBuffer, srcIdx * bytesPerFrame, outputBuffer, dstIdx * bytesPerFrame, bytesPerFrame)
                                    srcIdx++
                                    dstIdx++
                                }
                            }
                            while (dstIdx < requestedFrames && srcIdx < totalFramesToRead) {
                                System.arraycopy(scratchBuffer, srcIdx * bytesPerFrame, outputBuffer, dstIdx * bytesPerFrame, bytesPerFrame)
                                srcIdx++
                                dstIdx++
                            }
                        } else {
                            System.arraycopy(scratchBuffer, 0, outputBuffer, 0, requestedFrames * bytesPerFrame)
                        }

                        driftCorrectionSamples = 0
                        cumulativeDriftCorrection -= correction
                        success = true
                    } else {
                        success = false
                    }
                }
                else -> {
                    // Nominal read
                    success = readFrames(outputBuffer, 0, requestedFrames)
                    if (correction != 0) {
                        driftCorrectionSamples = 0
                    }
                }
            }

            // Apply smooth fade-in if resuming playback from silence/pre-buffer
            if (success && currentVolume < 1.0f) {
                val framesToFade = requestedFrames.coerceAtMost(fadeFramesCount)
                applyVolumeRamp(outputBuffer, 0, framesToFade, currentVolume, 1.0f)
                currentVolume = 1.0f
            }

            return success
        }
    }

    private fun readFrames(outputBuffer: ByteArray, outputOffset: Int, requestedFrames: Int): Boolean {
        var framesRead = 0
        var offset = outputOffset

        while (framesRead < requestedFrames) {
            if (activeChunk == null) {
                activeChunk = chunks.pollFirst()
                if (activeChunk != null) {
                    queuedDurationNs = (queuedDurationNs - activeChunk!!.durationNs).coerceAtLeast(0L)
                }
            }

            if (activeChunk == null) {
                // Underrun occurred before fulfilling requested frames — fade out remaining to silence
                writeFadeToSilence(outputBuffer, offset, requestedFrames - framesRead)
                isPreBuffering = true
                currentVolume = 0.0f
                return false
            }

            val read = activeChunk!!.readFrames(outputBuffer, offset, requestedFrames - framesRead)
            framesRead += read
            offset += read * bytesPerFrame

            if (activeChunk!!.isEndOfChunk()) {
                activeChunk = null
            }
        }

        return true
    }

    fun writeFadeToSilence(buffer: ByteArray, offset: Int, frames: Int) {
        val fadePortion = frames.coerceAtMost(fadeFramesCount)
        if (fadePortion > 0) {
            applyVolumeRamp(buffer, offset, fadePortion, startVol = 1.0f, endVol = 0.0f)
        }
        val remainingFrames = frames - fadePortion
        if (remainingFrames > 0) {
            writeSilence(buffer, offset + fadePortion * bytesPerFrame, remainingFrames)
        }
    }

    fun writeFadeIn(buffer: ByteArray, offset: Int, frames: Int) {
        val fadePortion = frames.coerceAtMost(fadeFramesCount)
        if (fadePortion > 0) {
            applyVolumeRamp(buffer, offset, fadePortion, startVol = 0.0f, endVol = 1.0f)
        }
    }

    fun applyVolumeRamp(buffer: ByteArray, offset: Int, frames: Int, startVol: Float, endVol: Float) {
        val channels = (bytesPerFrame / 2).coerceAtLeast(1)
        for (frame in 0 until frames) {
            val alpha = if (frames > 1) frame.toFloat() / (frames - 1) else 1.0f
            val vol = startVol + alpha * (endVol - startVol)
            val frameOffset = offset + frame * bytesPerFrame

            for (ch in 0 until channels) {
                val sampleOffset = frameOffset + ch * 2
                if (sampleOffset + 1 < buffer.size) {
                    val low = buffer[sampleOffset].toInt() and 0xFF
                    val high = buffer[sampleOffset + 1].toInt()
                    val sample = (high shl 8) or low
                    val scaled = (sample * vol).toInt().coerceIn(-32768, 32767)
                    buffer[sampleOffset] = (scaled and 0xFF).toByte()
                    buffer[sampleOffset + 1] = ((scaled shr 8) and 0xFF).toByte()
                }
            }
        }
    }

    private fun writeSilence(buffer: ByteArray, offset: Int, frames: Int) {
        val bytes = frames * bytesPerFrame
        if (bytes > 0 && offset + bytes <= buffer.size) {
            buffer.fill(0, offset, offset + bytes)
        }
    }
}

