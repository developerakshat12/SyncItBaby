package com.example.greetingcard.network

import com.example.greetingcard.PeerInfo
import kotlinx.coroutines.flow.StateFlow

interface SocketService {

    val connectionManager: ConnectionManager

    val localDeviceState: StateFlow<DeviceState>

    val peers: StateFlow<List<PeerInfo>>

    val statusMessage: StateFlow<String>

    val timeDomainConverter: com.example.greetingcard.sync.TimeDomainConverter

    val clockSyncState: StateFlow<com.example.greetingcard.sync.ClockSyncState>

    val rendererTelemetry: StateFlow<List<com.example.greetingcard.audio.TelemetryData>>

    val currentHostDeviceId: StateFlow<String?>

    fun setManualTrimMs(trimMs: Double)

    fun triggerClockSync()

    suspend fun startLeader(port: Int = DEFAULT_PORT)

    suspend fun joinLeader(host: String, port: Int = DEFAULT_PORT)

    fun startAudioStream(file: java.io.File)

    fun startAudioCapture(resultCode: Int, data: android.content.Intent)

    fun startCalibrationSession(durationSeconds: Int = 10)

    fun stopAudioStream()

    fun stop()

    companion object {
        const val DEFAULT_PORT = 9876
    }
}

