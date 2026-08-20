package com.example.greetingcard.sync

data class NtpSample(
    val t1: Long,
    val t2: Long,
    val t3: Long,
    val t4: Long
) {

    val rttNs: Long
        get() = (t4 - t1) - (t3 - t2)

    val offsetNs: Long
        get() = ((t2 - t1) + (t3 - t4)) / 2
}

data class NtpResult(
    val medianOffsetNs: Long,
    val medianRttNs: Long,
    val validSampleCount: Int,
    val totalSampleCount: Int
)

object NtpMath {

    const val DEFAULT_MAX_RTT_NS = 500_000_000L

    fun calculateOffsetNs(sample: NtpSample): Long = sample.offsetNs

    fun filterAndCalculateBestOfNAverage(
        samples: List<NtpSample>,
        maxRttNs: Long = DEFAULT_MAX_RTT_NS,
        bestN: Int = 3
    ): NtpResult? {
        if (samples.isEmpty()) return null

        // 1. Filter out invalid/negative RTTs or extreme dropouts
        val validSamples = samples.filter { it.rttNs in 0..maxRttNs }
        if (validSamples.isEmpty()) return null

        // 2. Select the lowest-RTT N samples
        val sortedByRtt = validSamples.sortedBy { it.rttNs }
        val keepCount = bestN.coerceIn(1, sortedByRtt.size)
        val bestSamples = sortedByRtt.take(keepCount)

        // 3. Compute average offset and average RTT across the best N samples
        val avgOffset = (bestSamples.map { it.offsetNs }.sum().toDouble() / bestSamples.size).toLong()
        val avgRtt = (bestSamples.map { it.rttNs }.sum().toDouble() / bestSamples.size).toLong()

        return NtpResult(
            medianOffsetNs = avgOffset,
            medianRttNs = avgRtt,
            validSampleCount = bestSamples.size,
            totalSampleCount = samples.size
        )
    }

    fun filterAndCalculateMedian(
        samples: List<NtpSample>,
        maxRttNs: Long = DEFAULT_MAX_RTT_NS,
        retainLowestRttFraction: Double = 0.5
    ): NtpResult? {
        if (samples.isEmpty()) return null

        // 1. Filter out invalid/negative RTTs or extreme dropouts
        val validSamples = samples.filter { it.rttNs in 0..maxRttNs }
        if (validSamples.isEmpty()) return null

        // 2. Select the lowest-RTT fraction (BeatSync / NTP pattern)
        val sortedByRtt = validSamples.sortedBy { it.rttNs }
        val keepCount = (sortedByRtt.size * retainLowestRttFraction).toInt().coerceAtLeast(1)
        val bestSamples = sortedByRtt.take(keepCount)

        // 3. Compute median offset from the best-RTT subset
        val sortedByOffset = bestSamples.sortedBy { it.offsetNs }
        val medianOffset = if (sortedByOffset.size % 2 == 1) {
            sortedByOffset[sortedByOffset.size / 2].offsetNs
        } else {
            val mid1 = sortedByOffset[sortedByOffset.size / 2 - 1].offsetNs
            val mid2 = sortedByOffset[sortedByOffset.size / 2].offsetNs
            (mid1 + mid2) / 2
        }

        // 4. Compute median RTT of best samples
        val bestSortedByRtt = bestSamples.sortedBy { it.rttNs }
        val medianRtt = if (bestSortedByRtt.size % 2 == 1) {
            bestSortedByRtt[bestSortedByRtt.size / 2].rttNs
        } else {
            val mid1 = bestSortedByRtt[bestSortedByRtt.size / 2 - 1].rttNs
            val mid2 = bestSortedByRtt[bestSortedByRtt.size / 2].rttNs
            (mid1 + mid2) / 2
        }

        return NtpResult(
            medianOffsetNs = medianOffset,
            medianRttNs = medianRtt,
            validSampleCount = bestSamples.size,
            totalSampleCount = samples.size
        )
    }
}

