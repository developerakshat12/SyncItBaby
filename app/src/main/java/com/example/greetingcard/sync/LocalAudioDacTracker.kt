package com.example.greetingcard.sync

import android.media.AudioTimestamp
import android.media.AudioTrack
import com.example.greetingcard.audio.OboeAudioRenderer

class LocalAudioDacTracker(
    private val ticker: Ticker = SystemTicker()
) {
    private val reusableTimestamp = AudioTimestamp()

    fun projectFramePresentationTimeNs(
        audioTrack: AudioTrack,
        targetFramePosition: Long
    ): Long? {
        val success = try {
            audioTrack.getTimestamp(reusableTimestamp)
        } catch (e: Throwable) {
            false
        }

        val sampleRate = audioTrack.sampleRate
        if (!success || sampleRate <= 0) return null

        val framePos = reusableTimestamp.framePosition
        val rawNanoTime = reusableTimestamp.nanoTime // CLOCK_MONOTONIC domain

        return projectHardwareTimestampNs(framePos, rawNanoTime, targetFramePosition, sampleRate)
    }

    fun projectOboeFramePresentationTimeNs(
        oboeRenderer: OboeAudioRenderer,
        targetFramePosition: Long,
        sampleRate: Int
    ): Long? {
        val tsPair = oboeRenderer.getHardwareTimestampPair() ?: return null
        val framePos = tsPair.first
        val rawNanoTime = tsPair.second
        return projectHardwareTimestampNs(framePos, rawNanoTime, targetFramePosition, sampleRate)
    }

    fun projectHardwareTimestampNs(
        hardwareFramePosition: Long,
        hardwareNanoTimeMonotonic: Long,
        targetFramePosition: Long,
        sampleRate: Int
    ): Long? {
        if (hardwareNanoTimeMonotonic <= 0L || sampleRate <= 0) return null

        val tickerNanoTime = ticker.convertMonotonicNanosToTickerNs(hardwareNanoTimeMonotonic)
        val frameDelta = targetFramePosition - hardwareFramePosition
        val offsetFromTimestampNs = (frameDelta * 1_000_000_000L) / sampleRate
        return tickerNanoTime + offsetFromTimestampNs
    }

    fun getLocalDacLatencyNs(
        audioTrack: AudioTrack,
        currentPlaybackHeadPosition: Long
    ): Long {
        val projectedTime = projectFramePresentationTimeNs(audioTrack, currentPlaybackHeadPosition)
            ?: return 0L
        val now = ticker.readNanos()
        return (projectedTime - now).coerceAtLeast(0L)
    }

    fun getLocalOboeDacLatencyNs(
        oboeRenderer: OboeAudioRenderer,
        currentFramePosition: Long,
        sampleRate: Int
    ): Long {
        val latencyMillis = oboeRenderer.getLatencyMillis()
        if (latencyMillis > 0.0) {
            return (latencyMillis * 1_000_000.0).toLong()
        }
        val projectedTime = projectOboeFramePresentationTimeNs(oboeRenderer, currentFramePosition, sampleRate)
            ?: return 0L
        val now = ticker.readNanos()
        return (projectedTime - now).coerceAtLeast(0L)
    }
}

