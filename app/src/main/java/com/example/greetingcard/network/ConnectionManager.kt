package com.example.greetingcard.network

import android.util.Log
import com.example.greetingcard.PeerInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "ConnectionManager"

private fun safeLogD(tag: String, message: String) {
    runCatching { Log.d(tag, message) }.onFailure { println("[$tag] $message") }
}

private fun safeLogW(tag: String, message: String) {
    runCatching { Log.w(tag, message) }.onFailure { println("[$tag] WARN: $message") }
}

const val DEGRADED_LATENCY_MS = 250L

class ConnectionManager {

    private val _localState = MutableStateFlow(DeviceState.DISCONNECTED)
    val localState: StateFlow<DeviceState> = _localState.asStateFlow()

    private val _peers = MutableStateFlow<List<PeerInfo>>(emptyList())
    val peers: StateFlow<List<PeerInfo>> = _peers.asStateFlow()

    fun transitionLocalState(newState: DeviceState) {
        val currentState = _localState.value
        if (currentState == newState) return

        if (isValidTransition(currentState, newState)) {
            safeLogD(TAG, "Local state transition: $currentState -> $newState")
            _localState.value = newState
        } else {
            safeLogW(TAG, "Invalid local state transition attempted: $currentState -> $newState (forcing transition)")
            _localState.value = newState
        }
    }

    fun registerPeer(id: String, address: String, name: String, initialState: DeviceState = DeviceState.AUTHENTICATING) {
        _peers.update { list ->
            val existing = list.find { it.id == id }
            if (existing != null) {
                list.map {
                    if (it.id == id) it.copy(address = address, name = name, state = initialState, lastSeenTimestamp = System.currentTimeMillis())
                    else it
                }
            } else {
                list + PeerInfo(
                    id = id,
                    address = address,
                    name = name,
                    state = initialState,
                    lastSeenTimestamp = System.currentTimeMillis(),
                )
            }
        }
        safeLogD(TAG, "Registered peer $name ($id) in state $initialState")
    }

    fun transitionPeerState(peerId: String, newState: DeviceState) {
        _peers.update { list ->
            list.map { peer ->
                if (peer.id == peerId) {
                    val current = peer.state
                    if (current != newState) {
                        safeLogD(TAG, "Peer [${peer.name}] state transition: $current -> $newState")
                    }
                    peer.copy(state = newState, lastSeenTimestamp = System.currentTimeMillis())
                } else peer
            }
        }
    }

    fun updatePeerPing(peerId: String, pingMs: Long) {
        _peers.update { list ->
            list.map { peer ->
                if (peer.id == peerId) {
                    val nextState = when {
                        pingMs > DEGRADED_LATENCY_MS && peer.state == DeviceState.CONNECTED -> DeviceState.DEGRADED
                        pingMs <= DEGRADED_LATENCY_MS && peer.state == DeviceState.DEGRADED -> DeviceState.CONNECTED
                        else -> peer.state
                    }
                    if (nextState != peer.state) {
                        safeLogD(TAG, "Peer [${peer.name}] state auto-adjusted by ping ($pingMs ms): ${peer.state} -> $nextState")
                    }
                    peer.copy(
                        lastPingMs = pingMs,
                        state = nextState,
                        lastSeenTimestamp = System.currentTimeMillis()
                    )
                } else peer
            }
        }
    }

    fun removePeer(peerId: String) {
        _peers.update { list -> list.filter { it.id != peerId } }
        safeLogD(TAG, "Removed peer $peerId")
    }

    fun reset() {
        safeLogD(TAG, "Resetting ConnectionManager state")
        _localState.value = DeviceState.DISCONNECTED
        _peers.value = emptyList()
    }

    companion object {

        fun isValidTransition(from: DeviceState, to: DeviceState): Boolean {
            if (from == to) return true
            // Any state can transition to DISCONNECTED or FAILED on shutdown/error
            if (to == DeviceState.DISCONNECTED || to == DeviceState.FAILED) return true

            return when (from) {
                DeviceState.DISCONNECTED -> to in listOf(DeviceState.DISCOVERED, DeviceState.CONNECTING)
                DeviceState.DISCOVERED -> to in listOf(DeviceState.CONNECTING)
                DeviceState.CONNECTING -> to in listOf(DeviceState.AUTHENTICATING, DeviceState.RECONNECTING, DeviceState.FAILED)
                DeviceState.AUTHENTICATING -> to in listOf(DeviceState.CONNECTED, DeviceState.RECONNECTING, DeviceState.FAILED)
                DeviceState.CONNECTED -> to in listOf(DeviceState.BUFFERING, DeviceState.READY, DeviceState.PLAYING, DeviceState.DEGRADED, DeviceState.RECONNECTING)
                DeviceState.BUFFERING -> to in listOf(DeviceState.READY, DeviceState.PLAYING, DeviceState.CONNECTED, DeviceState.DEGRADED, DeviceState.RECONNECTING)
                DeviceState.READY -> to in listOf(DeviceState.PLAYING, DeviceState.BUFFERING, DeviceState.CONNECTED, DeviceState.DEGRADED, DeviceState.RECONNECTING)
                DeviceState.PLAYING -> to in listOf(DeviceState.READY, DeviceState.BUFFERING, DeviceState.CONNECTED, DeviceState.DEGRADED, DeviceState.RECONNECTING)
                DeviceState.DEGRADED -> to in listOf(DeviceState.CONNECTED, DeviceState.BUFFERING, DeviceState.READY, DeviceState.PLAYING, DeviceState.RECONNECTING)
                DeviceState.RECONNECTING -> to in listOf(DeviceState.AUTHENTICATING, DeviceState.CONNECTED, DeviceState.FAILED)
                DeviceState.FAILED -> to in listOf(DeviceState.CONNECTING, DeviceState.DISCONNECTED)
            }
        }
    }
}

