package com.example.greetingcard.network

import android.util.Log
import com.example.greetingcard.PeerInfo
import com.example.greetingcard.audio.AudioReceiver
import com.example.greetingcard.audio.AudioStreamer
import com.example.greetingcard.audio.TelemetryData
import com.example.greetingcard.protocol.AudioNackPayload
import com.example.greetingcard.protocol.AudioStreamConfig
import com.example.greetingcard.protocol.AudioSyncStartConfig
import com.example.greetingcard.protocol.Packet
import com.example.greetingcard.protocol.PacketSerializer
import com.example.greetingcard.protocol.SyncConstants
import com.example.greetingcard.protocol.SyncStateConfig
import com.example.greetingcard.sync.ClockSyncState
import com.example.greetingcard.sync.NtpEngine
import com.example.greetingcard.sync.TimeDomainConverter
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "SocketServiceImpl"
private const val HEARTBEAT_INTERVAL_MS = 2000L

class SocketServiceImpl(
    private val context: android.content.Context
) : SocketService {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override val connectionManager = ConnectionManager()
    override val localDeviceState: StateFlow<DeviceState> = connectionManager.localState
    override val peers: StateFlow<List<PeerInfo>> = connectionManager.peers

    // --- State flows --------------------------------------------------------

    private val _statusMessage = MutableStateFlow("Idle")
    override val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _rendererTelemetry = MutableStateFlow<List<TelemetryData>>(emptyList())
    override val rendererTelemetry: StateFlow<List<TelemetryData>> = _rendererTelemetry.asStateFlow()

    private val _currentHostDeviceId = MutableStateFlow<String?>(null)
    override val currentHostDeviceId: StateFlow<String?> = _currentHostDeviceId.asStateFlow()

    // --- Clock Sync Engine --------------------------------------------------

    private val ntpEngine = NtpEngine()
    override val timeDomainConverter: TimeDomainConverter = ntpEngine.timeDomainConverter
    override val clockSyncState: StateFlow<ClockSyncState> = ntpEngine.syncState

    // --- Audio Engine -------------------------------------------------------

    private var isCapturingLiveAudio = false
    private val localAudioDacTracker = com.example.greetingcard.sync.LocalAudioDacTracker()

    private val audioReceiver = AudioReceiver(
        timeDomainConverter = timeDomainConverter,
        localAudioDacTracker = localAudioDacTracker,
        onNackNeeded = { missingSeq ->
            Log.d(TAG, "Audio gap detected, requesting NACK for seq #$missingSeq")
            val nackPacket = Packet.build(
                packetType = SyncConstants.TYPE_AUDIO_NACK,
                payload = AudioNackPayload(missingSeq).toByteArray(),
                streamId = SyncConstants.STREAM_AUDIO,
                sessionUuid = currentSessionId
            )
            sendPacketToLeader(nackPacket)
        },
        onStreamStateChanged = { isStreaming ->
            val state = if (isStreaming) DeviceState.BUFFERING else DeviceState.CONNECTED
            connectionManager.transitionLocalState(state)
            if (!isStreaming) _statusMessage.value = "Audio stream ended"
        }
    )

    private val audioStreamer = AudioStreamer(
        scope = scope,
        timeDomainConverter = timeDomainConverter,
        sendPacket = { packet ->
            broadcastPacket(packet)
            // If file streaming on leader, route audio to local receiver so leader speakers also play audio
            // Note: isCapturingLiveAudio mode is correctly exempt (uses hardware passthrough)
            if (!isCapturingLiveAudio) {
                when (packet.header.packetType) {
                    SyncConstants.TYPE_AUDIO_STREAM_START -> audioReceiver.startStream(packet.payload)
                    SyncConstants.TYPE_AUDIO_SYNC_START -> audioReceiver.handleAudioSyncStart(packet)
                    SyncConstants.TYPE_AUDIO_DATA -> audioReceiver.handleAudioData(packet)
                    SyncConstants.TYPE_AUDIO_STREAM_STOP -> audioReceiver.stopStream()
                }
            }
        },
        getSessionId = { currentSessionId },
        getRttNs = {
            val maxPeerPingNs = connectionManager.peers.value
                .map { it.lastPingMs * 1_000_000L }
                .filter { it > 0L }
                .maxOrNull() ?: 0L
            maxOf(ntpEngine.syncState.value.rttNs, maxPeerPingNs)
        }
    )

    init {
        scope.launch(Dispatchers.Default) {
            while (isActive) {
                val samples = audioReceiver.drainRendererTelemetry()
                if (samples.isNotEmpty()) {
                    _rendererTelemetry.value = (_rendererTelemetry.value + samples).takeLast(3)
                }
                delay(100L)
            }
        }
    }

    // --- Internal Connection Structure ---------------------------------------

    private class PeerConnection(
        val peerId: String,
        val socket: Socket,
        val dataOutputStream: DataOutputStream,
        val writeMutex: Mutex = Mutex(),
        onDroppedPacket: ((PeerConnection, Packet) -> Unit)? = null
    ) {
        val consecutiveDroppedPackets = java.util.concurrent.atomic.AtomicInteger(0)
        val droppedPacketsCount = java.util.concurrent.atomic.AtomicLong(0L)
        var isAudioReady: Boolean = false
        var writerJob: Job? = null

        val sendChannel: Channel<Packet> = Channel(
            capacity = 100,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
            onUndeliveredElement = { packet ->
                // Gate increment by checking if socket is open (since onUndeliveredElement fires on close/cancel)
                if (!socket.isClosed) {
                    onDroppedPacket?.invoke(this, packet)
                }
            }
        )
    }

    private fun createPeerConnection(peerId: String, socket: Socket, dos: DataOutputStream): PeerConnection {
        return PeerConnection(peerId, socket, dos) { conn, packet ->
            val currentDropped = conn.consecutiveDroppedPackets.incrementAndGet()
            val totalDropped = conn.droppedPacketsCount.incrementAndGet()
            Log.w(TAG, "sendChannel overflow for peer ${conn.peerId}! Dropped packet seq=${packet.header.sequenceNumber} (consecutive: $currentDropped, total: $totalDropped)")
            // THRESHOLD = 50 equates to ~1s of continuous loss at 20ms frames
            if (currentDropped >= 50) {
                if (conn.peerId == "leader") {
                    connectionManager.transitionLocalState(DeviceState.DEGRADED)
                } else {
                    connectionManager.transitionPeerState(conn.peerId, DeviceState.DEGRADED)
                }
            }
        }
    }

    private val peerConnections = ConcurrentHashMap<String, PeerConnection>()

    private var clientConnection: PeerConnection? = null

    private val pendingSyncStateAckJobs = ConcurrentHashMap<String, Job>()

    private var syncStateHandshakeJob: Job? = null

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var heartbeatJob: Job? = null

    // This device's own display name and persistent device identifier
    private val ownName = android.os.Build.MODEL
    private val sharedPrefs by lazy { context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE) }
    private val hostDeviceUuid: UUID by lazy {
        val saved = sharedPrefs.getString("host_device_uuid", null)
        if (saved != null) {
            try {
                UUID.fromString(saved)
            } catch (e: Exception) {
                val fresh = UUID.randomUUID()
                sharedPrefs.edit().putString("host_device_uuid", fresh.toString()).apply()
                fresh
            }
        } else {
            val fresh = UUID.randomUUID()
            sharedPrefs.edit().putString("host_device_uuid", fresh.toString()).apply()
            fresh
        }
    }
    private val ownDeviceId = hostDeviceUuid.toString().substring(0, 8)
    private var currentSessionId = hostDeviceUuid

    // --- Audio Capture Service Connection ---
    private var audioCaptureService: com.example.greetingcard.audio.AudioCaptureService? = null
    private var isAudioCaptureBound = false

    private fun startStreamingFromRecord(audioRecord: android.media.AudioRecord) {
        val pcmSource = com.example.greetingcard.audio.AudioRecordPcmSource(audioRecord) {
            // Notification for prolonged silence / DRM-blocked app capture
            Log.w(TAG, "Prolonged silence detected during live audio capture.")
            _statusMessage.value = "Audio silence detected (media may be paused or DRM protected)"
        }

        peerConnections.values.forEach { it.isAudioReady = true }
        audioStreamer.startStreamingFromCapture(pcmSource)
        _statusMessage.value = "Capturing device audio..."
    }

    private val captureServiceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            Log.d(TAG, "AudioCaptureService bound")
            val binder = service as? com.example.greetingcard.audio.AudioCaptureService.CaptureBinder
            val boundService = binder?.getService()
            audioCaptureService = boundService

            boundService?.onProjectionStopped = {
                stopAudioStream()
            }

            boundService?.onAudioRecordReady = { audioRecord ->
                startStreamingFromRecord(audioRecord)
            }

            boundService?.getAudioRecord()?.let { audioRecord ->
                startStreamingFromRecord(audioRecord)
            } ?: Log.d(TAG, "Waiting for AudioRecord from AudioCaptureService")
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            Log.d(TAG, "AudioCaptureService disconnected")
            audioCaptureService = null
        }
    }

    init {
        ntpEngine.setPacketTransportHandler { packet ->
            scope.launch(Dispatchers.IO) {
                broadcastPacket(packet)
            }
        }
    }

    // --- SocketService impl -------------------------------------------------

    override suspend fun startLeader(port: Int) {
        withContext(Dispatchers.IO) {
            connectionManager.transitionLocalState(DeviceState.CONNECTING)
            _statusMessage.value = "Starting leader on port $port…"
            Log.d(TAG, "startLeader port=$port")

            try {
                serverSocket = ServerSocket(port)
                currentSessionId = hostDeviceUuid
                _currentHostDeviceId.value = hostDeviceUuid.toString()
                connectionManager.transitionLocalState(DeviceState.CONNECTED)
                _statusMessage.value = "Waiting for peers on port $port…"
                ntpEngine.startAsLeader()

                acceptJob = scope.launch {
                    while (isActive) {
                        try {
                            val socket = serverSocket?.accept() ?: break
                            socket.tcpNoDelay = true // Disable Nagle's algorithm for zero packet delay
                            Log.d(TAG, "Accepted connection from ${socket.inetAddress.hostAddress}")
                            scope.launch { handlePeerSocket(socket) }
                        } catch (e: Exception) {
                            if (isActive) Log.e(TAG, "Accept error", e)
                            break
                        }
                    }
                }

                // Start periodic heartbeat task on leader side
                startHeartbeatTask()

            } catch (e: Exception) {
                val detail = e.message ?: e.javaClass.simpleName
                connectionManager.transitionLocalState(DeviceState.FAILED)
                _statusMessage.value = "Failed to start leader: $detail"
                Log.e(TAG, "startLeader failed", e)
            }
        }
    }

    override suspend fun joinLeader(host: String, port: Int) {
        withContext(Dispatchers.IO) {
            connectionManager.transitionLocalState(DeviceState.CONNECTING)
            _statusMessage.value = "Connecting to $host:$port…"
            Log.d(TAG, "joinLeader host=$host port=$port")

            try {
                val socket = Socket(host, port)
                socket.tcpNoDelay = true // Disable Nagle's algorithm for zero packet delay
                val dos = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                val conn = createPeerConnection("leader", socket, dos)
                clientConnection = conn
                startWriterLoop(conn)

                connectionManager.transitionLocalState(DeviceState.AUTHENTICATING)
                _statusMessage.value = "Authenticating with $host…"

                // Send HELLO packet with persistent device identity
                val helloPacket = Packet.buildString(
                    packetType = SyncConstants.TYPE_HANDSHAKE_HELLO,
                    text = "$ownName#$ownDeviceId",
                    sessionUuid = currentSessionId
                )
                conn.sendChannel.trySend(helloPacket)

                _statusMessage.value = "Connected to leader at $host"
                Log.d(TAG, "Connected to leader, sent HELLO packet")
                ntpEngine.startAsClient()

                // Wait for initial sync convergence with a 10-second timeout
                val initialSyncSuccess = withTimeoutOrNull(10_000L) {
                    while (!ntpEngine.syncState.value.isSynced) {
                        delay(50L)
                    }
                }

                // Initiate Late-Join state synchronization handshake
                startLateJoinHandshake()

                scope.launch { readLoop(socket, id = "leader", name = "Leader") }
            } catch (e: Exception) {
                val detail = e.message ?: e.javaClass.simpleName
                connectionManager.transitionLocalState(DeviceState.RECONNECTING)
                delay(500)
                connectionManager.transitionLocalState(DeviceState.FAILED)
                _statusMessage.value = "Connection failed: $detail"
                Log.e(TAG, "joinLeader failed", e)
            }
        }
    }

    private fun startLateJoinHandshake() {
        syncStateHandshakeJob?.cancel()
        syncStateHandshakeJob = scope.launch(Dispatchers.IO) {
            var attempts = 0
            val maxAttempts = 5
            while (isActive && attempts < maxAttempts) {
                val reqPacket = Packet.build(
                    packetType = SyncConstants.TYPE_REQUEST_SYNC_STATE,
                    streamId = SyncConstants.STREAM_CONTROL,
                    sessionUuid = currentSessionId
                )
                sendPacketToLeader(reqPacket)
                val backoffMs = (300L * (1 shl attempts)).coerceAtMost(2000L)
                delay(backoffMs)
                attempts++
            }
        }
    }

    override fun startAudioStream(file: File) {
        stopAudioStream()
        isCapturingLiveAudio = false
        // Arm all currently connected peers for audio stream reception
        peerConnections.values.forEach { it.isAudioReady = true }
        _statusMessage.value = "Streaming audio to peers..."
        audioStreamer.startStreaming(file)
    }

    override fun startAudioCapture(resultCode: Int, data: android.content.Intent) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            stopAudioStream()
            isCapturingLiveAudio = true
            // Arm all currently connected peers for audio stream reception
            peerConnections.values.forEach { it.isAudioReady = true }
            val intent = android.content.Intent(context, com.example.greetingcard.audio.AudioCaptureService::class.java).apply {
                putExtra("RESULT_CODE", resultCode)
                putExtra("DATA", data)
            }
            context.startForegroundService(intent)

            isAudioCaptureBound = context.bindService(intent, captureServiceConnection, android.content.Context.BIND_AUTO_CREATE)
            _statusMessage.value = "Starting audio capture..."
        }
    }

    override fun startCalibrationSession(durationSeconds: Int) {
        stopAudioStream()
        isCapturingLiveAudio = false
        // Arm all currently connected peers for audio stream reception
        peerConnections.values.forEach { it.isAudioReady = true }
        _statusMessage.value = "Calibration session (${durationSeconds}s)..."
        audioStreamer.startCalibration(durationSeconds)
    }

    override fun stopAudioStream() {
        audioStreamer.stopStreaming()
        audioReceiver.stopStream()
        _rendererTelemetry.value = emptyList()

        if (isAudioCaptureBound) {
            try {
                context.unbindService(captureServiceConnection)
            } catch (e: Exception) {
                Log.w(TAG, "Error unbinding AudioCaptureService", e)
            }
            isAudioCaptureBound = false
        }
        try {
            context.stopService(android.content.Intent(context, com.example.greetingcard.audio.AudioCaptureService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioCaptureService", e)
        }
        audioCaptureService = null
        isCapturingLiveAudio = false
    }

    override fun triggerClockSync() {
        ntpEngine.triggerSyncRound()
    }

    override fun stop() {
        ntpEngine.stop()
        _rendererTelemetry.value = emptyList()

        syncStateHandshakeJob?.cancel()
        syncStateHandshakeJob = null
        pendingSyncStateAckJobs.values.forEach { it.cancel() }
        pendingSyncStateAckJobs.clear()

        stopAudioStream()

        acceptJob?.cancel()
        heartbeatJob?.cancel()
        serverSocket?.runCatching { close() }
        clientConnection?.let { conn ->
            conn.isAudioReady = false
            conn.writerJob?.cancel()
            conn.sendChannel.close()
            conn.socket.runCatching { close() }
        }
        peerConnections.values.forEach { conn ->
            conn.isAudioReady = false
            conn.writerJob?.cancel()
            conn.sendChannel.close()
            conn.socket.runCatching { close() }
        }
        peerConnections.clear()
        clientConnection = null
        serverSocket = null
        connectionManager.reset()
        _currentHostDeviceId.value = null
        _statusMessage.value = "Disconnected"
    }

    // --- Packet Dispatch Helpers --------------------------------------------

    private fun startWriterLoop(conn: PeerConnection) {
        conn.writerJob?.cancel()
        conn.writerJob = scope.launch(Dispatchers.IO) {
            try {
                for (packet in conn.sendChannel) {
                    conn.writeMutex.withLock {
                        PacketSerializer.writePacket(conn.dataOutputStream, packet)
                    }
                    val previousDropped = conn.consecutiveDroppedPackets.getAndSet(0)
                    if (previousDropped >= 50) {
                        if (conn.peerId == "leader") {
                            if (connectionManager.localState.value == DeviceState.DEGRADED) {
                                connectionManager.transitionLocalState(DeviceState.CONNECTED)
                            }
                        } else {
                            val currentPeerState = connectionManager.peers.value.find { it.id == conn.peerId }?.state
                            if (currentPeerState == DeviceState.DEGRADED) {
                                connectionManager.transitionPeerState(conn.peerId, DeviceState.CONNECTED)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (!conn.socket.isClosed) {
                    Log.e(TAG, "Writer loop error for ${conn.peerId}", e)
                    conn.socket.runCatching { close() }
                }
            }
        }
    }

    private fun sendPacketToPeer(peerId: String, packet: Packet) {
        val conn = peerConnections[peerId] ?: return
        val result = conn.sendChannel.trySend(packet)
        if (!result.isSuccess) {
            val totalDropped = conn.droppedPacketsCount.incrementAndGet()
            Log.w(TAG, "sendChannel overflow for peer ${conn.peerId}! Dropped packet seq=${packet.header.sequenceNumber} type=0x${packet.header.packetType.toString(16)} (total dropped: $totalDropped)")
        }
    }

    private fun sendPacketToLeader(packet: Packet) {
        val conn = clientConnection ?: return
        val result = conn.sendChannel.trySend(packet)
        if (!result.isSuccess) {
            val totalDropped = conn.droppedPacketsCount.incrementAndGet()
            Log.w(TAG, "sendChannel overflow for leader connection! Dropped packet seq=${packet.header.sequenceNumber} type=0x${packet.header.packetType.toString(16)} (total dropped: $totalDropped)")
        }
    }

    private fun broadcastPacket(packet: Packet) {
        val isAudio = packet.header.streamId == SyncConstants.STREAM_AUDIO
        peerConnections.values.forEach { conn ->
            // Audio packets (except stream stop) are only dispatched to peers whose audio engine is ready
            if (!isAudio || conn.isAudioReady || packet.header.packetType == SyncConstants.TYPE_AUDIO_STREAM_STOP) {
                val result = conn.sendChannel.trySend(packet)
                if (!result.isSuccess) {
                    val totalDropped = conn.droppedPacketsCount.incrementAndGet()
                    Log.w(TAG, "sendChannel overflow for peer ${conn.peerId}! Dropped packet seq=${packet.header.sequenceNumber} type=0x${packet.header.packetType.toString(16)} (total dropped: $totalDropped)")
                }
            }
        }
        clientConnection?.let { conn ->
            val result = conn.sendChannel.trySend(packet)
            if (!result.isSuccess) {
                val totalDropped = conn.droppedPacketsCount.incrementAndGet()
                Log.w(TAG, "sendChannel overflow for leader connection! Dropped packet seq=${packet.header.sequenceNumber} type=0x${packet.header.packetType.toString(16)} (total dropped: $totalDropped)")
            }
        }
    }

    // --- Socket Handling & Read Loops ---------------------------------------

    private suspend fun handlePeerSocket(socket: Socket) {
        val address = socket.inetAddress.hostAddress ?: "unknown"
        val dos = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

        var peerId = "temp_$address"
        var conn: PeerConnection? = null

        try {
            val dis = DataInputStream(BufferedInputStream(socket.getInputStream()))

            // Read the initial HELLO handshake packet
            val firstPacket = PacketSerializer.readPacket(dis)
            val rawPayload = firstPacket.payloadAsString()

            val (peerName, resolvedPeerId) = if (firstPacket.header.packetType == SyncConstants.TYPE_HANDSHAKE_HELLO) {
                if (rawPayload.contains("#")) {
                    val parts = rawPayload.split("#", limit = 2)
                    parts[0] to "${parts[0]}_${parts[1]}"
                } else {
                    rawPayload to "${rawPayload}_$address"
                }
            } else {
                "Unknown" to "Unknown_$address"
            }

            peerId = resolvedPeerId
            Log.d(TAG, "Peer authenticated: $peerName (ID: $peerId) @ $address")

            conn = createPeerConnection(peerId, socket, dos)

            // If this peer had a previous connection, close the stale socket cleanly
            val existingConn = peerConnections[peerId]
            if (existingConn != null && existingConn.socket != socket) {
                Log.d(TAG, "Replacing old socket for reconnected peer $peerId")
                existingConn.isAudioReady = false
                existingConn.writerJob?.cancel()
                existingConn.sendChannel.close()
                existingConn.socket.runCatching { close() }
            }

            peerConnections[peerId] = conn
            startWriterLoop(conn)

            connectionManager.registerPeer(
                id = peerId,
                address = address,
                name = peerName,
                initialState = DeviceState.CONNECTED
            )
            _statusMessage.value = "Connected peers: ${peers.value.size}"

            // Respond with Leader's HELLO packet
            val leaderHello = Packet.buildString(
                packetType = SyncConstants.TYPE_HANDSHAKE_HELLO,
                text = "$ownName (Leader)",
                sessionUuid = currentSessionId
            )
            conn.sendChannel.trySend(leaderHello)

            // Phase 4: Automatically trigger calibration session for new peer
            if (!audioStreamer.isStreamingActive) {
                startCalibrationSession(15)
            }

            // Continue reading packets
            readLoop(socket, id = peerId, name = peerName, dis = dis)

        } catch (e: Exception) {
            Log.e(TAG, "Peer $peerId error", e)
            connectionManager.transitionPeerState(peerId, DeviceState.DEGRADED)
            delay(300)
            connectionManager.transitionPeerState(peerId, DeviceState.RECONNECTING)
            delay(300)
            connectionManager.transitionPeerState(peerId, DeviceState.FAILED)
        } finally {
            pendingSyncStateAckJobs.remove(peerId)?.cancel()
            // Only prune if this socket is still the active one for this peerId
            val activeConn = peerConnections[peerId]
            if (activeConn == null || activeConn.socket == socket) {
                peerConnections.remove(peerId)
                connectionManager.transitionPeerState(peerId, DeviceState.DISCONNECTED)
                delay(300)
                connectionManager.removePeer(peerId)
                _statusMessage.value = "Connected peers: ${peers.value.size}"
            }
            conn?.isAudioReady = false
            conn?.writerJob?.cancel()
            conn?.sendChannel?.close()
            socket.runCatching { close() }
            Log.d(TAG, "Peer $peerId socket closed")
        }
    }

    private fun readLoop(
        socket: Socket,
        id: String,
        name: String,
        dis: DataInputStream = DataInputStream(BufferedInputStream(socket.getInputStream()))
    ) {
        try {
            while (!socket.isClosed) {
                val packet = try {
                    PacketSerializer.readPacket(dis)
                } catch (e: EOFException) {
                    break
                }

                when (packet.header.packetType) {

                    SyncConstants.TYPE_HANDSHAKE_HELLO -> {
                        val leaderName = packet.payloadAsString()
                        val leaderHostUuid = packet.header.sessionUuid.toString()
                        _currentHostDeviceId.value = leaderHostUuid
                        connectionManager.transitionLocalState(DeviceState.CONNECTED)
                        _statusMessage.value = "Connected to $leaderName"
                    }
                    SyncConstants.TYPE_HEARTBEAT_PING -> {
                        val txMs = packet.payloadAsString()
                        val pongPacket = Packet.buildString(
                            packetType = SyncConstants.TYPE_HEARTBEAT_PONG,
                            text = txMs,
                            sessionUuid = currentSessionId
                        )
                        scope.launch(Dispatchers.IO) {
                            if (clientConnection?.socket == socket) {
                                sendPacketToLeader(pongPacket)
                            } else {
                                sendPacketToPeer(id, pongPacket)
                            }
                        }
                    }
                    SyncConstants.TYPE_HEARTBEAT_PONG -> {
                        val sentTimeMs = packet.payloadAsString().toLongOrNull() ?: 0L
                        if (sentTimeMs > 0) {
                            val rttMs = System.currentTimeMillis() - sentTimeMs
                            connectionManager.updatePeerPing(id, rttMs)
                        }
                    }
                    SyncConstants.TYPE_NTP_REQ, SyncConstants.TYPE_NTP_RESP -> {
                        if (packet.payload.size == com.example.greetingcard.protocol.NtpRequestPayload.SIZE_BYTES ||
                            packet.payload.size == com.example.greetingcard.protocol.NtpResponsePayload.SIZE_BYTES
                        ) {
                            ntpEngine.handleIncomingPacket(packet, reply = { replyPacket ->
                                scope.launch(Dispatchers.IO) {
                                    if (clientConnection?.socket == socket) {
                                        sendPacketToLeader(replyPacket)
                                    } else {
                                        sendPacketToPeer(id, replyPacket)
                                    }
                                }
                            })
                        } else {
                            // Legacy text frame compatibility
                            val frame = packet.payloadAsString()
                            ntpEngine.handleIncomingFrame(frame, reply = { replyFrame ->
                                val replyType = if (replyFrame.startsWith("NTP_RESP:") || replyFrame.startsWith("SNTP_RESP:")) {
                                    SyncConstants.TYPE_NTP_RESP
                                } else {
                                    SyncConstants.TYPE_NTP_REQ
                                }
                                val replyPacket = Packet.buildString(
                                    packetType = replyType,
                                    text = replyFrame,
                                    streamId = SyncConstants.STREAM_NTP,
                                    sessionUuid = currentSessionId
                                )
                                scope.launch(Dispatchers.IO) {
                                    if (clientConnection?.socket == socket) {
                                        sendPacketToLeader(replyPacket)
                                    } else {
                                        sendPacketToPeer(id, replyPacket)
                                    }
                                }
                            })
                        }
                    }
                    SyncConstants.TYPE_REQUEST_SYNC_STATE -> {
                        scope.launch(Dispatchers.IO) {
                            val isStreaming = audioStreamer.isStreamingActive
                            val streamConfig = audioStreamer.currentStreamConfig
                            val syncStartConfig = audioStreamer.currentSyncStartConfig
                            val currentSeq = audioStreamer.getCurrentSequenceNumber()
                            val syncState = SyncStateConfig(
                                isStreaming = isStreaming,
                                sampleRate = streamConfig?.sampleRate ?: 48000,
                                channels = streamConfig?.channels ?: 2,
                                bitrate = streamConfig?.bitrate ?: 128000,
                                frameDurationUs = streamConfig?.frameDurationUs ?: 20000,
                                totalDurationMs = streamConfig?.totalDurationMs ?: 0,
                                trackName = streamConfig?.trackName ?: "",
                                startAtLeaderTimeNs = syncStartConfig?.startAtLeaderTimeNs ?: 0L,
                                firstFrameLeaderTimeNs = syncStartConfig?.firstFrameLeaderTimeNs ?: 0L,
                                currentSequenceNumber = currentSeq
                            )
                            val syncStatePacket = Packet.build(
                                packetType = SyncConstants.TYPE_SYNC_STATE,
                                payload = syncState.toByteArray(),
                                streamId = SyncConstants.STREAM_CONTROL,
                                sessionUuid = currentSessionId
                            )
                            sendPacketToPeer(id, syncStatePacket)

                            // Bounded retry waiting for TYPE_SYNC_STATE_ACK (max 5 attempts with backoff)
                            pendingSyncStateAckJobs[id]?.cancel()
                            pendingSyncStateAckJobs[id] = scope.launch(Dispatchers.IO) {
                                var attempts = 0
                                while (isActive && attempts < 5) {
                                    delay((300L * (1 shl attempts)).coerceAtMost(2000L))
                                    val peerConn = peerConnections[id]
                                    if (peerConn == null || peerConn.isAudioReady) break
                                    attempts++
                                    if (attempts < 5) {
                                        sendPacketToPeer(id, syncStatePacket)
                                    }
                                }
                                pendingSyncStateAckJobs.remove(id)
                            }
                        }
                    }
                    SyncConstants.TYPE_SYNC_STATE -> {
                        // Send ACK back to leader immediately
                        val ackPacket = Packet.build(
                            packetType = SyncConstants.TYPE_SYNC_STATE_ACK,
                            streamId = SyncConstants.STREAM_CONTROL,
                            sessionUuid = currentSessionId
                        )
                        scope.launch(Dispatchers.IO) { sendPacketToLeader(ackPacket) }
                        syncStateHandshakeJob?.cancel()

                        val syncState = try {
                            SyncStateConfig.fromByteArray(packet.payload)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse SyncStateConfig", e)
                            null
                        }

                        if (syncState != null && syncState.isStreaming) {
                            if (connectionManager.localState.value !in listOf(DeviceState.BUFFERING, DeviceState.READY, DeviceState.PLAYING)) {
                                val streamConfig = AudioStreamConfig(
                                    sampleRate = syncState.sampleRate,
                                    channels = syncState.channels,
                                    bitrate = syncState.bitrate,
                                    frameDurationUs = syncState.frameDurationUs,
                                    totalDurationMs = syncState.totalDurationMs,
                                    trackName = syncState.trackName
                                )
                                val syncStartConfig = AudioSyncStartConfig(
                                    startAtLeaderTimeNs = syncState.startAtLeaderTimeNs,
                                    firstFrameLeaderTimeNs = syncState.firstFrameLeaderTimeNs,
                                    sampleRate = syncState.sampleRate,
                                    channels = syncState.channels
                                )
                                _statusMessage.value = "Joined ongoing stream: ${syncState.trackName}"
                                audioReceiver.startStream(streamConfig.toByteArray(), firstSequenceNumber = syncState.currentSequenceNumber)
                                audioReceiver.handleAudioSyncStart(
                                    Packet.build(
                                        packetType = SyncConstants.TYPE_AUDIO_SYNC_START,
                                        payload = syncStartConfig.toByteArray(),
                                        streamId = SyncConstants.STREAM_AUDIO,
                                        sessionUuid = currentSessionId
                                    )
                                )
                            } else {
                                Log.d(TAG, "Duplicate SYNC_STATE received while already playing/buffering — re-ACKed and ignored payload to prevent timing jump")
                            }
                        }
                    }
                    SyncConstants.TYPE_SYNC_STATE_ACK -> {
                        val peerConn = peerConnections[id]
                        if (peerConn != null) {
                            peerConn.isAudioReady = true
                            Log.d(TAG, "Peer $id confirmed sync state (isAudioReady=true)")
                        }
                        pendingSyncStateAckJobs.remove(id)?.cancel()
                    }
                    SyncConstants.TYPE_AUDIO_NACK -> {
                        scope.launch(Dispatchers.IO) {
                            val nack = try {
                                AudioNackPayload.fromByteArray(packet.payload)
                            } catch (e: Exception) {
                                null
                            }
                            if (nack != null) {
                                val retransmitPacket = audioStreamer.getCachedPacket(nack.missingSequenceNumber)
                                if (retransmitPacket != null) {
                                    Log.d(TAG, "Retransmitting audio packet #${nack.missingSequenceNumber} to peer $id")
                                    sendPacketToPeer(id, retransmitPacket)
                                } else {
                                    Log.w(TAG, "Requested NACK packet #${nack.missingSequenceNumber} not found in cache for peer $id")
                                }
                            }
                        }
                    }

                    SyncConstants.TYPE_AUDIO_STREAM_START -> {
                        _statusMessage.value = "Receiving audio stream..."
                        audioReceiver.startStream(packet.payload)
                    }
                    SyncConstants.TYPE_AUDIO_SYNC_START -> {
                        audioReceiver.handleAudioSyncStart(packet)
                    }
                    SyncConstants.TYPE_AUDIO_DATA -> {
                        audioReceiver.handleAudioData(packet)
                    }
                    SyncConstants.TYPE_AUDIO_STREAM_STOP -> {
                        audioReceiver.stopStream()
                    }
                }
            }
        } catch (e: Exception) {
            if (!socket.isClosed) Log.e(TAG, "readLoop error for $name", e)
        } finally {
            if (clientConnection?.socket == socket) {
                Log.d(TAG, "Client socket closed — updating local state machine and stopping audioReceiver")
                audioReceiver.stopStream()
                clientConnection?.isAudioReady = false
                clientConnection?.writerJob?.cancel()
                clientConnection?.sendChannel?.close()
                clientConnection = null
                scope.launch {
                    connectionManager.transitionLocalState(DeviceState.DEGRADED)
                    delay(500)
                    connectionManager.transitionLocalState(DeviceState.RECONNECTING)
                    delay(500)
                    connectionManager.transitionLocalState(DeviceState.DISCONNECTED)
                }
            }
        }
    }

    private fun startHeartbeatTask() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val pingPacket = Packet.buildString(
                    packetType = SyncConstants.TYPE_HEARTBEAT_PING,
                    text = "$now",
                    sessionUuid = currentSessionId
                )
                broadcastPacket(pingPacket)
            }
        }
    }

    override fun setManualTrimMs(trimMs: Double) {
        audioReceiver.setManualTrimMs(trimMs)
    }
}

