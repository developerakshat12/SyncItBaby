package com.example.greetingcard.sync

import com.example.greetingcard.protocol.Packet
import com.example.greetingcard.protocol.NtpRequestPayload
import com.example.greetingcard.protocol.NtpResponsePayload
import com.example.greetingcard.protocol.SyncConstants
import com.example.greetingcard.util.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.random.Random

private const val TAG = "NtpEngine"

enum class SyncRole {
    LEADER,
    CLIENT,
    IDLE
}

class NtpEngine(
    val timeDomainConverter: TimeDomainConverter = DefaultTimeDomainConverter(),
    private val ticker: Ticker = SystemTicker(),
    val clockFilter: ClockFilterAlgorithm = ClockFilterAlgorithm(),
    val adaptivePollManager: AdaptivePollManager = AdaptivePollManager()
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _syncState = MutableStateFlow(ClockSyncState())
    val syncState: StateFlow<ClockSyncState> = _syncState.asStateFlow()

    private var currentRole = SyncRole.IDLE
    private var heartbeatJob: Job? = null
    private var activeSyncRoundJob: Job? = null
    private val sequenceCounter = AtomicInteger(1)

    private var lastCalculatedOffsetNs: Long? = null
    private var lastCalculatedOffsetTimestampMs: Long = 0L

    var onLargeOffsetDiscontinuity: ((Long) -> Unit)? = null

    private val pendingProbes = ConcurrentHashMap<Int, Long>() // seq -> t1
    private val collectedSamples = mutableListOf<NtpSample>()

    private var sendPacketHandler: ((Packet) -> Unit)? = null

    private var sendFrameHandler: ((String) -> Unit)? = null

    fun setPacketTransportHandler(handler: (Packet) -> Unit) {
        this.sendPacketHandler = handler
    }

    fun setTransportHandler(handler: (String) -> Unit) {
        this.sendFrameHandler = handler
    }

    fun getRetainedOffsetNs(maxAgeMs: Long = 60_000L): Long? {
        val offset = lastCalculatedOffsetNs ?: return null
        val age = System.currentTimeMillis() - lastCalculatedOffsetTimestampMs
        return if (age in 0..maxAgeMs) offset else null
    }

    fun startAsLeader() {
        stop()
        currentRole = SyncRole.LEADER
        timeDomainConverter.reset()
        clockFilter.reset()
        adaptivePollManager.reset()
        timeDomainConverter.updateOffset(0L, smooth = false)
        _syncState.value = ClockSyncState(
            offsetNs = 0L,
            rttNs = 0L,
            isSynced = true,
            lastSyncTimeMs = System.currentTimeMillis(),
            statusMessage = "Leader (Source of Time)"
        )
        LogUtils.d(TAG, "Started NtpEngine as LEADER")
    }

    fun startAsClient() {
        stop()
        currentRole = SyncRole.CLIENT
        timeDomainConverter.reset()
        clockFilter.reset()
        adaptivePollManager.reset()

        val retainedOffset = getRetainedOffsetNs(60_000L)
        if (retainedOffset != null) {
            timeDomainConverter.updateOffset(retainedOffset, smooth = false)
            LogUtils.d(TAG, "Bootstrapped client with retained NTP offset: ${retainedOffset / 1_000_000.0} ms")
        }

        _syncState.value = ClockSyncState(statusMessage = "Client connecting…")
        LogUtils.d(TAG, "Started NtpEngine as CLIENT")

        startHeartbeat()
    }

    fun triggerSyncRound(probesCount: Int = adaptivePollManager.getProbeCount()) {
        if (currentRole != SyncRole.CLIENT) return

        activeSyncRoundJob?.cancel()
        activeSyncRoundJob = scope.launch {
            LogUtils.d(TAG, "Starting NTP sync round with $probesCount probes")
            _syncState.value = _syncState.value.copy(statusMessage = "Syncing clock…")
            synchronized(collectedSamples) { collectedSamples.clear() }
            pendingProbes.clear()

            val syncId = UUID.randomUUID().toString().take(8)

            for (i in 1..probesCount) {
                if (!isActive) break
                val seq = sequenceCounter.getAndIncrement()
                val t1 = ticker.readNanos()
                pendingProbes[seq] = t1

                // 1. Dispatch binary NTP Request packet if handler registered
                if (sendPacketHandler != null) {
                    val payload = NtpRequestPayload(sequenceNumber = seq, t1Ns = t1).toByteArray()
                    val packet = Packet.build(
                        packetType = SyncConstants.TYPE_NTP_REQ,
                        payload = payload,
                        streamId = SyncConstants.STREAM_NTP
                    )
                    sendPacketHandler?.invoke(packet)
                } else {
                    // Fallback to text frame
                    val frame = "NTP_REQ:$syncId:$seq:$t1"
                    sendFrameHandler?.invoke(frame)
                }

                // Randomized probe spacing (20ms - 50ms) to prevent Wi-Fi queuing contention
                val backoff = Random.nextLong(20, 50)
                delay(backoff)
            }

            delay(300)
            processRoundResults()
        }
    }

    fun handleIncomingPacket(packet: Packet, reply: ((Packet) -> Unit)? = null) {
        when (packet.header.packetType) {
            SyncConstants.TYPE_NTP_REQ -> {
                val t2 = ticker.readNanos()
                val req = NtpRequestPayload.fromByteArray(packet.payload) ?: return
                val t3 = ticker.readNanos()

                val respPayload = NtpResponsePayload(
                    sequenceNumber = req.sequenceNumber,
                    t1Ns = req.t1Ns,
                    t2Ns = t2,
                    t3Ns = t3
                ).toByteArray()

                val respPacket = Packet.build(
                    packetType = SyncConstants.TYPE_NTP_RESP,
                    payload = respPayload,
                    streamId = SyncConstants.STREAM_NTP,
                    sessionUuid = packet.header.sessionUuid
                )
                val sender = reply ?: sendPacketHandler
                sender?.invoke(respPacket)
            }
            SyncConstants.TYPE_NTP_RESP -> {
                if (currentRole == SyncRole.CLIENT) {
                    val t4 = ticker.readNanos()
                    val resp = NtpResponsePayload.fromByteArray(packet.payload) ?: return
                    val sentT1 = pendingProbes.remove(resp.sequenceNumber)
                    if (sentT1 != null) {
                        val sample = NtpSample(
                            t1 = resp.t1Ns,
                            t2 = resp.t2Ns,
                            t3 = resp.t3Ns,
                            t4 = t4
                        )
                        synchronized(collectedSamples) {
                            collectedSamples.add(sample)
                        }
                    }
                }
            }
        }
    }

    fun handleIncomingFrame(frame: String, reply: ((String) -> Unit)? = null) {
        val parts = frame.split(":")
        if (parts.isEmpty()) return

        when (parts[0]) {
            "NTP_REQ", "SNTP_REQ" -> {
                if (parts.size >= 4) {
                    val t2 = ticker.readNanos()
                    val syncId = parts[1]
                    val seq = parts[2]
                    val t1Str = parts[3]
                    val t3 = ticker.readNanos()
                    val respFrame = "NTP_RESP:$syncId:$seq:$t1Str:$t2:$t3"
                    val sender = reply ?: sendFrameHandler
                    sender?.invoke(respFrame)
                }
            }
            "NTP_RESP", "SNTP_RESP" -> {
                if (currentRole == SyncRole.CLIENT && parts.size >= 6) {
                    val t4 = ticker.readNanos()
                    val seq = parts[2].toIntOrNull() ?: return
                    val t1 = parts[3].toLongOrNull() ?: return
                    val t2 = parts[4].toLongOrNull() ?: return
                    val t3 = parts[5].toLongOrNull() ?: return

                    if (pendingProbes.containsKey(seq)) {
                        pendingProbes.remove(seq)
                        val sample = NtpSample(t1 = t1, t2 = t2, t3 = t3, t4 = t4)
                        synchronized(collectedSamples) {
                            collectedSamples.add(sample)
                        }
                    }
                }
            }
        }
    }

    private fun processRoundResults() {
        val samplesCopy = synchronized(collectedSamples) { collectedSamples.toList() }
        val nowNs = ticker.readNanos()

        // 1. Process samples through RFC 5905 Clock Filter (8-slot min-RTT selection & spike suppression)
        val filterResult = clockFilter.processSamples(samplesCopy, nowNs)

        if (filterResult != null) {
            val isFirstSync = !timeDomainConverter.isSynced
            var hadStepReset = false

            // 2. Ingest selected sample into 2-state Kalman filter
            if (timeDomainConverter is DefaultTimeDomainConverter) {
                val (innovationNs, stepReset) = timeDomainConverter.updateWithKalman(
                    measuredOffsetNs = filterResult.offsetNs,
                    rttNs = filterResult.rttNs,
                    measurementTimeNs = nowNs
                )
                hadStepReset = stepReset

                if (!isFirstSync && (hadStepReset || abs(innovationNs) > 10_000_000L)) {
                    LogUtils.w(TAG, "Large NTP offset jump detected: ${abs(innovationNs) / 1_000_000.0} ms (step=$hadStepReset)")
                    onLargeOffsetDiscontinuity?.invoke(timeDomainConverter.offsetNs)
                }
            } else {
                timeDomainConverter.updateOffset(filterResult.offsetNs, smooth = !isFirstSync)
            }

            lastCalculatedOffsetNs = timeDomainConverter.offsetNs
            lastCalculatedOffsetTimestampMs = System.currentTimeMillis()

            // 3. Update Adaptive Polling Manager with jitter feedback
            val isConverged = (timeDomainConverter as? DefaultTimeDomainConverter)?.isConverged ?: true
            adaptivePollManager.onRoundCompleted(
                peerJitterNs = filterResult.peerJitterNs,
                rttNs = filterResult.rttNs,
                isConverged = isConverged,
                hadStepDiscontinuity = hadStepReset
            )

            val ppm = String.format(
                Locale.US,
                "%.1f",
                (timeDomainConverter as? DefaultTimeDomainConverter)?.driftRatePpm ?: 0.0
            )

            val newState = ClockSyncState(
                offsetNs = timeDomainConverter.offsetNs,
                rttNs = filterResult.rttNs,
                isSynced = true,
                lastSyncTimeMs = System.currentTimeMillis(),
                validSampleCount = filterResult.registerDepth,
                totalSampleCount = samplesCopy.size,
                statusMessage = "Synced (${filterResult.registerDepth} slots, $ppm ppm)"
            )
            _syncState.value = newState
            LogUtils.d(
                TAG,
                "NTP sync success: offset=${newState.formattedOffsetMs}, rtt=${newState.formattedRttMs}, jitter=${filterResult.peerJitterNs / 1_000_000.0}ms, drift=$ppm ppm, nextPoll=${adaptivePollManager.getNextPollIntervalMs()}ms"
            )
        } else {
            LogUtils.w(TAG, "NTP sync failed: no valid probes survived out of ${samplesCopy.size}")
            adaptivePollManager.onRoundCompleted(
                peerJitterNs = 5_000_000L,
                rttNs = 100_000_000L,
                isConverged = false,
                hadStepDiscontinuity = false
            )
            if (!timeDomainConverter.isSynced) {
                _syncState.value = _syncState.value.copy(statusMessage = "Sync failed (high latency)")
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            // Random initial stagger delay (0–500ms) on connect to prevent simultaneous Wi-Fi channel bursts
            val initialStaggerMs = Random.nextLong(0, 500)
            delay(initialStaggerMs)

            // Trigger initial burst
            triggerSyncRound(adaptivePollManager.getProbeCount())

            while (isActive) {
                val nextIntervalMs = adaptivePollManager.getNextPollIntervalMs()
                delay(nextIntervalMs)
                triggerSyncRound(adaptivePollManager.getProbeCount())
            }
        }
    }

    fun stop() {
        heartbeatJob?.cancel()
        activeSyncRoundJob?.cancel()
        pendingProbes.clear()
        synchronized(collectedSamples) { collectedSamples.clear() }
        timeDomainConverter.reset()
        clockFilter.reset()
        adaptivePollManager.reset()
        currentRole = SyncRole.IDLE
        _syncState.value = ClockSyncState()
    }
}

