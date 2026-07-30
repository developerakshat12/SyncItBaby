package com.example.greetingcard.sync

import android.os.SystemClock

interface Ticker {

    fun readNanos(): Long

    fun convertMonotonicNanosToTickerNs(monotonicNs: Long): Long
}

class SystemTicker(
    val epochAnchorMs: Long = System.currentTimeMillis(),
    val uptimeAnchorNs: Long = readSystemNanos()
) : Ticker {

    override fun readNanos(): Long {
        val elapsedNs = readSystemNanos() - uptimeAnchorNs
        return (epochAnchorMs * 1_000_000L) + elapsedNs
    }

    override fun convertMonotonicNanosToTickerNs(monotonicNs: Long): Long {
        val nowTickerNs = readNanos()
        val nowMonotonicNs = System.nanoTime()
        return nowTickerNs + (monotonicNs - nowMonotonicNs)
    }

    companion object {
        fun readSystemNanos(): Long {
            return try {
                val nanos = SystemClock.elapsedRealtimeNanos()
                if (nanos > 0L) nanos else System.nanoTime()
            } catch (e: Throwable) {
                System.nanoTime()
            }
        }
    }
}

