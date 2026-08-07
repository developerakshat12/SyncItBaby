package com.example.greetingcard.sync

import com.example.greetingcard.util.LogUtils
import kotlin.math.abs
import kotlin.math.sqrt

private const val TAG = "ClockFilterAlgorithm"

data class ClockFilterResult(
    val offsetNs: Long,
    val rttNs: Long,
    val peerJitterNs: Long,
    val dispersionNs: Long,
    val registerDepth: Int,
    val spikeSuppressedCount: Int,
    val sampleTimestampNs: Long
)

private data class FilterEntry(
    val sample: NtpSample,
    val arrivalNs: Long
)

class ClockFilterAlgorithm(
    private val registerSize: Int = REGISTER_SIZE,
    private val spikeThresholdFactor: Double = SPIKE_THRESHOLD_FACTOR,
    private val dispersionGrowthPpm: Double = DISPERSION_GROWTH_PPM
) {
    companion object {
        const val REGISTER_SIZE = 16
        const val SPIKE_THRESHOLD_FACTOR = 3.0 // Ks in RFC 5905
        const val DISPERSION_GROWTH_PPM = 15.0 // 15 ns per 1 ms elapsed (15 ppm)
        const val MIN_JITTER_FLOOR_NS = 200_000.0 // 200 us floor for spike checks
        const val MAX_ALLOWABLE_RTT_NS = 500_000_000L // 500ms safety limit
    }

    private val shiftRegister = mutableListOf<FilterEntry>()
    private var spikeSuppressedTotal = 0

    @Synchronized
    fun processSamples(
        samples: List<NtpSample>,
        currentLocalTimeNs: Long
    ): ClockFilterResult? {
        var roundSpikes = 0

        for (sample in samples) {
            // Validate basic RTT sanity
            if (sample.rttNs !in 0..MAX_ALLOWABLE_RTT_NS) {
                continue
            }

            // Popcorn Spike Suppression (RFC 5905)
            if (isPopcornSpike(sample)) {
                roundSpikes++
                spikeSuppressedTotal++
                LogUtils.d(
                    TAG,
                    "Popcorn spike suppressed: offset=${sample.offsetNs / 1_000_000.0}ms, rtt=${sample.rttNs / 1_000_000.0}ms"
                )
                continue
            }

            // Insert into shift register (evicting oldest if full)
            if (shiftRegister.size >= registerSize) {
                shiftRegister.removeAt(0)
            }
            shiftRegister.add(
                FilterEntry(
                    sample = sample,
                    arrivalNs = currentLocalTimeNs
                )
            )
        }

        return getBestEstimate(currentLocalTimeNs, roundSpikes)
    }

    @Synchronized
    fun processSample(
        sample: NtpSample,
        currentLocalTimeNs: Long
    ): ClockFilterResult? {
        return processSamples(listOf(sample), currentLocalTimeNs)
    }

    private fun isPopcornSpike(candidate: NtpSample): Boolean {
        // Require at least 3 historical samples to have a reliable jitter estimate
        if (shiftRegister.size < 3) return false

        val bestEntry = shiftRegister.minByOrNull { it.sample.rttNs } ?: return false
        val bestOffset = bestEntry.sample.offsetNs
        val minRtt = bestEntry.sample.rttNs

        val jitter = calculateCurrentPeerJitter(bestOffset)
        val threshold = spikeThresholdFactor * maxOf(jitter, MIN_JITTER_FLOOR_NS)
        val offsetDiff = abs(candidate.offsetNs - bestOffset)

        // RFC 5905 condition: large offset excursion AND higher RTT than best
        return offsetDiff > threshold && candidate.rttNs > (minRtt * 1.5)
    }

    private fun calculateCurrentPeerJitter(bestOffsetNs: Long): Double {
        if (shiftRegister.size < 2) return 0.0

        var sumSq = 0.0
        var count = 0
        for (entry in shiftRegister) {
            val diff = entry.sample.offsetNs - bestOffsetNs
            sumSq += diff.toDouble() * diff.toDouble()
            count++
        }
        val divisor = maxOf(1, count - 1)
        return sqrt(sumSq / divisor)
    }

    @Synchronized
    fun getBestEstimate(
        currentLocalTimeNs: Long,
        spikesInLastRound: Int = 0
    ): ClockFilterResult? {
        if (shiftRegister.isEmpty()) return null

        // RFC 5905: Sort entries by RTT ascending -> first is minimum RTT
        val sortedByRtt = shiftRegister.sortedBy { it.sample.rttNs }
        val bestEntry = sortedByRtt.first()
        val bestSample = bestEntry.sample

        val jitter = calculateCurrentPeerJitter(bestSample.offsetNs)
        val elapsedNs = maxOf(0L, currentLocalTimeNs - bestEntry.arrivalNs)
        val dispersionGrowthNs = elapsedNs.toDouble() * (dispersionGrowthPpm * 1e-6)
        val totalDispersion = (dispersionGrowthNs + (bestSample.rttNs / 2.0)).toLong()

        return ClockFilterResult(
            offsetNs = bestSample.offsetNs,
            rttNs = bestSample.rttNs,
            peerJitterNs = jitter.toLong(),
            dispersionNs = totalDispersion,
            registerDepth = shiftRegister.size,
            spikeSuppressedCount = spikesInLastRound,
            sampleTimestampNs = bestEntry.arrivalNs
        )
    }

    @Synchronized
    fun reset() {
        shiftRegister.clear()
        spikeSuppressedTotal = 0
    }

    val activeSampleCount: Int
        @Synchronized get() = shiftRegister.size
}

