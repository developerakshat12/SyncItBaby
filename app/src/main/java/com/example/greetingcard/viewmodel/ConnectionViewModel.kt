package com.example.greetingcard.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.greetingcard.PeerInfo
import com.example.greetingcard.network.SocketService
import com.example.greetingcard.network.SocketServiceImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.greetingcard.audio.PlaybackForegroundService

enum class Role { NONE, HOST, CLIENT }

class ConnectionViewModel(application: Application) : AndroidViewModel(application) {

    private val socketService: SocketService = SocketServiceImpl(context = application)

    // --- Exposed state -------------------------------------------------------

    val isAudioCaptureSupported: Boolean = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q

    val deviceState: StateFlow<com.example.greetingcard.network.DeviceState> = socketService.localDeviceState
    val peers: StateFlow<List<PeerInfo>> = socketService.peers
    val statusMessage: StateFlow<String> = socketService.statusMessage
    val clockSyncState: StateFlow<com.example.greetingcard.sync.ClockSyncState> = socketService.clockSyncState
    val rendererTelemetry: StateFlow<List<com.example.greetingcard.audio.TelemetryData>> = socketService.rendererTelemetry
    val currentHostDeviceId: StateFlow<String?> = socketService.currentHostDeviceId

    private val sharedPrefs by lazy { application.getSharedPreferences("acoustic_trim_prefs", android.content.Context.MODE_PRIVATE) }
    private val _manualTrimMs = MutableStateFlow(0.0f)
    val manualTrimMs: StateFlow<Float> = _manualTrimMs.asStateFlow()

    private val _isDeveloperMode = MutableStateFlow(sharedPrefs.getBoolean("developer_mode", false))
    val isDeveloperMode: StateFlow<Boolean> = _isDeveloperMode.asStateFlow()

    fun setDeveloperMode(enabled: Boolean) {
        _isDeveloperMode.value = enabled
        sharedPrefs.edit().putBoolean("developer_mode", enabled).apply()
    }

    private val _appearanceMode = MutableStateFlow(sharedPrefs.getString("appearance_mode", "System default") ?: "System default")
    val appearanceMode: StateFlow<String> = _appearanceMode.asStateFlow()

    fun setAppearanceMode(mode: String) {
        _appearanceMode.value = mode
        sharedPrefs.edit().putString("appearance_mode", mode).apply()
    }

    private val _role = MutableStateFlow(Role.NONE)
    val role: StateFlow<Role> = _role.asStateFlow()

    init {
        viewModelScope.launch {
            socketService.currentHostDeviceId.collect { hostId ->
                if (hostId != null) {
                    val savedTrim = sharedPrefs.getFloat("acoustic_trim_host_$hostId", sharedPrefs.getFloat("acoustic_trim_default", 0.0f))
                    _manualTrimMs.value = savedTrim
                    socketService.setManualTrimMs(savedTrim.toDouble())
                }
            }
        }
    }

    // --- Commands ------------------------------------------------------------

    fun startHost() {
        _role.value = Role.HOST
        viewModelScope.launch {
            socketService.startLeader()
        }
    }

    fun joinHost(ip: String) {
        _role.value = Role.CLIENT

        // Start Foreground Service to keep receiver active in the background
        val intent = Intent(getApplication(), PlaybackForegroundService::class.java)
        ContextCompat.startForegroundService(getApplication(), intent)

        viewModelScope.launch {
            socketService.joinLeader(host = ip.trim())
        }
    }

    fun triggerClockSync() {
        socketService.triggerClockSync()
    }

    fun setManualTrimMs(trimMs: Float) {
        val rounded = (kotlin.math.round(trimMs * 2.0f) / 2.0f).coerceIn(-10.0f, 10.0f)
        _manualTrimMs.value = rounded
        socketService.setManualTrimMs(rounded.toDouble())
        val hostId = socketService.currentHostDeviceId.value
        if (hostId != null) {
            sharedPrefs.edit().putFloat("acoustic_trim_host_$hostId", rounded).apply()
        } else {
            sharedPrefs.edit().putFloat("acoustic_trim_default", rounded).apply()
        }
    }

    fun startAudioStream(file: File) {
        socketService.startAudioStream(file)
    }

    fun startCalibration(durationSeconds: Int = 10) {
        socketService.startCalibrationSession(durationSeconds)
    }

    fun stopAudioStream() {
        socketService.stopAudioStream()
    }

    fun onProjectionGranted(resultCode: Int, data: android.content.Intent) {
        socketService.startAudioCapture(resultCode, data)
    }

    fun onProjectionDenied() {
        // Fallback to file picker if audio capture denied.
    }

    fun disconnect() {
        socketService.stop()

        // Stop the foreground service
        val intent = Intent(getApplication(), PlaybackForegroundService::class.java)
        getApplication<Application>().stopService(intent)

        _role.value = Role.NONE
    }

    override fun onCleared() {
        super.onCleared()
        socketService.stop()

        // Stop the foreground service
        val intent = Intent(getApplication(), PlaybackForegroundService::class.java)
        getApplication<Application>().stopService(intent)
    }
}

