package com.example.greetingcard.sync

interface TimeDomainConverter {

    fun leaderTimeForLocalTimeNs(localTimeNs: Long): Long

    fun localTimeForLeaderTimeNs(leaderTimeNs: Long): Long

    val currentLeaderTimeNs: Long

    val offsetNs: Long

    val isSynced: Boolean

    fun updateOffset(rawOffsetNs: Long, smooth: Boolean = true)

    fun reset()
}

class DefaultTimeDomainConverter(
    private val ticker: Ticker = SystemTicker(),
    val kalmanFilter: KalmanClockFilter = KalmanClockFilter(),
    val smoothingAlpha: Double = 0.25 // Retained for backwards-compatibility
) : TimeDomainConverter {

    override fun leaderTimeForLocalTimeNs(localTimeNs: Long): Long {
        return localTimeNs + kalmanFilter.predictOffsetNs(localTimeNs)
    }

    override fun localTimeForLeaderTimeNs(leaderTimeNs: Long): Long {
        val approxLocalTimeNs = leaderTimeNs - kalmanFilter.currentOffsetNs
        return leaderTimeNs - kalmanFilter.predictOffsetNs(approxLocalTimeNs)
    }

    override val currentLeaderTimeNs: Long
        get() = leaderTimeForLocalTimeNs(ticker.readNanos())

    override val offsetNs: Long
        get() = kalmanFilter.predictOffsetNs(ticker.readNanos())

    override val isSynced: Boolean
        get() = kalmanFilter.isSynced

    val driftRatePpm: Double
        get() = kalmanFilter.driftRatePpm

    val isConverged: Boolean
        get() = kalmanFilter.isConverged

    fun updateWithKalman(
        measuredOffsetNs: Long,
        rttNs: Long,
        measurementTimeNs: Long
    ): Pair<Long, Boolean> {
        return kalmanFilter.update(
            measuredOffsetNs = measuredOffsetNs,
            rttNs = rttNs,
            measurementTimeNs = measurementTimeNs
        )
    }

    override fun updateOffset(rawOffsetNs: Long, smooth: Boolean) {
        val nowNs = ticker.readNanos()
        if (!kalmanFilter.isSynced || !smooth) {
            kalmanFilter.reset()
            kalmanFilter.update(rawOffsetNs, rttNs = 0L, measurementTimeNs = nowNs)
        } else {
            kalmanFilter.update(rawOffsetNs, rttNs = 2_000_000L, measurementTimeNs = nowNs)
        }
    }

    override fun reset() {
        kalmanFilter.reset()
    }
}

