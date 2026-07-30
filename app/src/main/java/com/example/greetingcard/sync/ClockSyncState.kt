package com.example.greetingcard.sync

import androidx.compose.runtime.Immutable
import java.util.Locale

@Immutable
data class ClockSyncState(
    val offsetNs: Long = 0L,
    val rttNs: Long = 0L,
    val isSynced: Boolean = false,
    val lastSyncTimeMs: Long = 0L,
    val validSampleCount: Int = 0,
    val totalSampleCount: Int = 0,
    val statusMessage: String = "Unsynced"
) {

    val formattedOffsetMs: String
        get() {
            if (!isSynced) return "0.0 ms"
            val ms = offsetNs / 1_000_000.0
            val prefix = if (ms >= 0) "+" else ""
            return String.format(Locale.US, "%s%.2f ms", prefix, ms)
        }

    val formattedRttMs: String
        get() {
            if (!isSynced) return "--"
            val ms = rttNs / 1_000_000.0
            return String.format(Locale.US, "%.2f ms", ms)
        }
}

