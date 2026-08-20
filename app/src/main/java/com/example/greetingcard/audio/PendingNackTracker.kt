package com.example.greetingcard.audio

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "PendingNackTracker"

class PendingNackTracker(
    private val scope: CoroutineScope,
    private val timeoutNs: Long = 500_000_000L, // 500ms NACK timeout
    private val maxConsecutiveSkipsThreshold: Int = 5, // 5 packets (~100ms at 20ms frames)
    private val maxCumulativeSkippedNs: Long = 100_000_000L, // 100ms
    private val onSaturationThresholdExceeded: () -> Unit
) {
    private val pendingNacks = ConcurrentHashMap<Long, Long>() // seq -> requestTimestampNs
    private val consecutiveSkipped = AtomicInteger(0)
    private val cumulativeSkippedNs = AtomicLong(0L)
    private var timeoutJob: Job? = null

    val pendingCount: Int
        get() = pendingNacks.size

    val consecutiveSkips: Int
        get() = consecutiveSkipped.get()

    fun start() {
        timeoutJob?.cancel()
        timeoutJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(100L)
                checkTimeouts()
            }
        }
    }

    fun recordNack(seq: Long) {
        pendingNacks.putIfAbsent(seq, System.nanoTime())
    }

    fun resolve(seq: Long, wasSkipped: Boolean, frameDurationNs: Long = 20_000_000L) {
        val wasPending = pendingNacks.remove(seq) != null

        if (wasSkipped) {
            val count = consecutiveSkipped.incrementAndGet()
            val totalNs = cumulativeSkippedNs.addAndGet(frameDurationNs)

            Log.d(TAG, "NACK resolved via skip for seq #$seq (consecutive=$count, cumulative=${totalNs / 1_000_000}ms)")

            if (count >= maxConsecutiveSkipsThreshold || totalNs >= maxCumulativeSkippedNs) {
                Log.w(TAG, "Drift saturation threshold exceeded: $count consecutive skips / ${totalNs / 1_000_000}ms cumulative. Triggering resync.")
                consecutiveSkipped.set(0)
                cumulativeSkippedNs.set(0L)
                onSaturationThresholdExceeded()
            }
        } else {
            // Normal packet received & queued
            consecutiveSkipped.set(0)
            if (wasPending) {
                Log.d(TAG, "NACK successfully fulfilled for seq #$seq")
            }
        }
    }

    fun checkTimeouts() {
        val now = System.nanoTime()
        val expired = mutableListOf<Long>()

        for ((seq, requestTime) in pendingNacks) {
            if (now - requestTime >= timeoutNs) {
                expired.add(seq)
            }
        }

        for (seq in expired) {
            Log.w(TAG, "NACK request for seq #$seq timed out after ${timeoutNs / 1_000_000}ms")
            resolve(seq, wasSkipped = true)
        }
    }

    fun stop() {
        timeoutJob?.cancel()
        timeoutJob = null
        pendingNacks.clear()
        consecutiveSkipped.set(0)
        cumulativeSkippedNs.set(0L)
    }
}

