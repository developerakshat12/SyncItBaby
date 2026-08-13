package com.example.greetingcard.audio

import androidx.compose.runtime.Immutable
import java.util.concurrent.atomic.AtomicInteger

@Immutable
data class TelemetryData(
    val timestampNs: Long = 0L,
    val expectedFrame: Long = 0L,
    val actualFrame: Long = 0L,
    val phaseErrorMs: Double = 0.0,
    val smoothedErrorNs: Double = 0.0,
    val correction: Int = 0,
    val jitterBufferMs: Long = 0L,
    val isAggressiveMode: Boolean = false,
    val timestampValid: Boolean = true,
    val leaderClockTimeNs: Long = 0L,
    val isMMap: Boolean = false,
    val isHardwareDac: Boolean = false
)

class TelemetrySlot {
    @Volatile var timestampNs: Long = 0L
    @Volatile var expectedFrame: Long = 0L
    @Volatile var actualFrame: Long = 0L
    @Volatile var phaseErrorMs: Double = 0.0
    @Volatile var smoothedErrorNs: Double = 0.0
    @Volatile var correction: Int = 0
    @Volatile var jitterBufferMs: Long = 0L
    @Volatile var isAggressiveMode: Boolean = false
    @Volatile var timestampValid: Boolean = false
    @Volatile var leaderClockTimeNs: Long = 0L
    @Volatile var isMMap: Boolean = false
    @Volatile var isHardwareDac: Boolean = false

    fun set(
        timestampNs: Long,
        expectedFrame: Long,
        actualFrame: Long,
        phaseErrorMs: Double,
        smoothedErrorNs: Double,
        correction: Int,
        jitterBufferMs: Long,
        isAggressiveMode: Boolean,
        timestampValid: Boolean,
        leaderClockTimeNs: Long,
        isMMap: Boolean = false,
        isHardwareDac: Boolean = false
    ) {
        this.timestampNs = timestampNs
        this.expectedFrame = expectedFrame
        this.actualFrame = actualFrame
        this.phaseErrorMs = phaseErrorMs
        this.smoothedErrorNs = smoothedErrorNs
        this.correction = correction
        this.jitterBufferMs = jitterBufferMs
        this.isAggressiveMode = isAggressiveMode
        this.timestampValid = timestampValid
        this.leaderClockTimeNs = leaderClockTimeNs
        this.isMMap = isMMap
        this.isHardwareDac = isHardwareDac
    }

    fun toTelemetryData(): TelemetryData = TelemetryData(
        timestampNs = timestampNs,
        expectedFrame = expectedFrame,
        actualFrame = actualFrame,
        phaseErrorMs = phaseErrorMs,
        smoothedErrorNs = smoothedErrorNs,
        correction = correction,
        jitterBufferMs = jitterBufferMs,
        isAggressiveMode = isAggressiveMode,
        timestampValid = timestampValid,
        leaderClockTimeNs = leaderClockTimeNs,
        isMMap = isMMap,
        isHardwareDac = isHardwareDac
    )
}

class TelemetryRingBuffer(val capacity: Int = 16) {
    init {
        require(capacity > 0 && (capacity and (capacity - 1)) == 0) {
            "Capacity must be a positive power of 2, was $capacity"
        }
    }

    private val mask = capacity - 1
    private val slots = Array(capacity) { TelemetrySlot() }

    private val writeHead = AtomicInteger(0)
    private val readHead = AtomicInteger(0)

    fun tryOffer(
        timestampNs: Long,
        expectedFrame: Long,
        actualFrame: Long,
        phaseErrorMs: Double,
        smoothedErrorNs: Double,
        correction: Int,
        jitterBufferMs: Long,
        isAggressiveMode: Boolean = false,
        timestampValid: Boolean = true,
        leaderClockTimeNs: Long = 0L,
        isMMap: Boolean = false,
        isHardwareDac: Boolean = false
    ): Boolean {
        val currentWrite = writeHead.get()
        val currentRead = readHead.get()

        if (currentWrite - currentRead >= capacity) {
            // Buffer full — drop sample silently to protect audio deadline
            return false
        }

        val slot = slots[currentWrite and mask]
        slot.set(
            timestampNs = timestampNs,
            expectedFrame = expectedFrame,
            actualFrame = actualFrame,
            phaseErrorMs = phaseErrorMs,
            smoothedErrorNs = smoothedErrorNs,
            correction = correction,
            jitterBufferMs = jitterBufferMs,
            isAggressiveMode = isAggressiveMode,
            timestampValid = timestampValid,
            leaderClockTimeNs = leaderClockTimeNs,
            isMMap = isMMap,
            isHardwareDac = isHardwareDac
        )

        // Publish written slot
        writeHead.set(currentWrite + 1)
        return true
    }

    fun poll(): TelemetryData? {
        val currentRead = readHead.get()
        val currentWrite = writeHead.get()

        if (currentRead >= currentWrite) {
            return null
        }

        val slot = slots[currentRead and mask]
        val data = slot.toTelemetryData()
        readHead.set(currentRead + 1)
        return data
    }

    fun drainAll(maxItems: Int = capacity): List<TelemetryData> {
        val list = ArrayList<TelemetryData>(maxItems.coerceAtMost(capacity))
        while (list.size < maxItems) {
            val item = poll() ?: break
            list.add(item)
        }
        return list
    }

    val isEmpty: Boolean
        get() = readHead.get() >= writeHead.get()

    val size: Int
        get() = (writeHead.get() - readHead.get()).coerceAtLeast(0)

    fun clear() {
        readHead.set(writeHead.get())
    }
}

