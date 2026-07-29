package com.example.greetingcard

import com.example.greetingcard.network.ConnectionManager
import com.example.greetingcard.network.DEGRADED_LATENCY_MS
import com.example.greetingcard.network.DeviceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConnectionManagerTest {

    private lateinit var manager: ConnectionManager

    @Before
    fun setUp() {
        manager = ConnectionManager()
    }

    @Test
    fun `initial local state is DISCONNECTED`() {
        assertEquals(DeviceState.DISCONNECTED, manager.localState.value)
        assertTrue(manager.peers.value.isEmpty())
    }

    @Test
    fun `valid transition paths succeed`() {
        manager.transitionLocalState(DeviceState.CONNECTING)
        assertEquals(DeviceState.CONNECTING, manager.localState.value)

        manager.transitionLocalState(DeviceState.AUTHENTICATING)
        assertEquals(DeviceState.AUTHENTICATING, manager.localState.value)

        manager.transitionLocalState(DeviceState.CONNECTED)
        assertEquals(DeviceState.CONNECTED, manager.localState.value)
    }

    @Test
    fun `valid transition validation rules`() {
        assertTrue(ConnectionManager.isValidTransition(DeviceState.DISCONNECTED, DeviceState.CONNECTING))
        assertTrue(ConnectionManager.isValidTransition(DeviceState.CONNECTING, DeviceState.AUTHENTICATING))
        assertTrue(ConnectionManager.isValidTransition(DeviceState.AUTHENTICATING, DeviceState.CONNECTED))
        assertTrue(ConnectionManager.isValidTransition(DeviceState.CONNECTED, DeviceState.DEGRADED))
        assertTrue(ConnectionManager.isValidTransition(DeviceState.DEGRADED, DeviceState.RECONNECTING))
        assertTrue(ConnectionManager.isValidTransition(DeviceState.RECONNECTING, DeviceState.DISCONNECTED))

        // Shutdown / failure can be entered from any state
        assertTrue(ConnectionManager.isValidTransition(DeviceState.CONNECTED, DeviceState.DISCONNECTED))
        assertTrue(ConnectionManager.isValidTransition(DeviceState.AUTHENTICATING, DeviceState.FAILED))
    }

    @Test
    fun `peer registration and state transitions`() {
        val peerId = "peer-101"
        manager.registerPeer(peerId, "192.168.1.50", "Pixel 7", DeviceState.AUTHENTICATING)

        assertEquals(1, manager.peers.value.size)
        val peer = manager.peers.value.first()
        assertEquals(peerId, peer.id)
        assertEquals(DeviceState.AUTHENTICATING, peer.state)

        manager.transitionPeerState(peerId, DeviceState.CONNECTED)
        assertEquals(DeviceState.CONNECTED, manager.peers.value.first().state)
    }

    @Test
    fun `high ping latency auto transitions peer to DEGRADED`() {
        val peerId = "peer-102"
        manager.registerPeer(peerId, "192.168.1.51", "Galaxy S23", DeviceState.CONNECTED)

        // Normal ping -> state stays CONNECTED
        manager.updatePeerPing(peerId, 45L)
        assertEquals(DeviceState.CONNECTED, manager.peers.value.first().state)

        // High ping > threshold -> transitions to DEGRADED
        manager.updatePeerPing(peerId, DEGRADED_LATENCY_MS + 100L)
        assertEquals(DeviceState.DEGRADED, manager.peers.value.first().state)

        // Low ping -> recovers to CONNECTED
        manager.updatePeerPing(peerId, 30L)
        assertEquals(DeviceState.CONNECTED, manager.peers.value.first().state)
    }

    @Test
    fun `peer removal and state reset`() {
        manager.registerPeer("p1", "192.168.1.1", "Phone A", DeviceState.CONNECTED)
        assertEquals(1, manager.peers.value.size)

        manager.removePeer("p1")
        assertTrue(manager.peers.value.isEmpty())

        manager.transitionLocalState(DeviceState.CONNECTED)
        manager.registerPeer("p2", "192.168.1.2", "Phone B", DeviceState.CONNECTED)
        manager.reset()

        assertEquals(DeviceState.DISCONNECTED, manager.localState.value)
        assertTrue(manager.peers.value.isEmpty())
    }
}

