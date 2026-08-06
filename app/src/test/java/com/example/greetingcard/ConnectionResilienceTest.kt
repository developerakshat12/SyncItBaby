package com.example.greetingcard

import com.example.greetingcard.network.ConnectionManager
import com.example.greetingcard.network.DeviceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ConnectionResilienceTest {

    private lateinit var manager: ConnectionManager

    @Before
    fun setUp() {
        manager = ConnectionManager()
    }

    @Test
    fun `consecutive dropped packets reaching threshold transitions peer to DEGRADED`() {
        val peerId = "peer-slow-client"
        manager.registerPeer(peerId, "192.168.43.100", "Slow Phone", DeviceState.CONNECTED)

        val consecutiveDropped = AtomicInteger(0)
        val threshold = 50

        // Simulate 49 dropped packets (below threshold) -> stays CONNECTED
        for (i in 1..49) {
            val count = consecutiveDropped.incrementAndGet()
            if (count >= threshold) {
                manager.transitionPeerState(peerId, DeviceState.DEGRADED)
            }
        }
        assertEquals(DeviceState.CONNECTED, manager.peers.value.first().state)

        // 50th dropped packet -> crosses threshold, transitions to DEGRADED
        val count = consecutiveDropped.incrementAndGet()
        if (count >= threshold) {
            manager.transitionPeerState(peerId, DeviceState.DEGRADED)
        }
        assertEquals(DeviceState.DEGRADED, manager.peers.value.first().state)

        // Successful socket write resets counter and recovers state to CONNECTED
        val previousDropped = consecutiveDropped.getAndSet(0)
        if (previousDropped >= threshold && manager.peers.value.first().state == DeviceState.DEGRADED) {
            manager.transitionPeerState(peerId, DeviceState.CONNECTED)
        }
        assertEquals(DeviceState.CONNECTED, manager.peers.value.first().state)
    }

    @Test
    fun `reconnect state transitions follow valid lifecycle paths`() {
        // Normal connect -> Authenticate -> Connected
        manager.transitionLocalState(DeviceState.CONNECTING)
        manager.transitionLocalState(DeviceState.AUTHENTICATING)
        manager.transitionLocalState(DeviceState.CONNECTED)
        assertEquals(DeviceState.CONNECTED, manager.localState.value)

        // Wi-Fi drop -> Degraded -> Reconnecting -> Disconnected
        manager.transitionLocalState(DeviceState.DEGRADED)
        assertEquals(DeviceState.DEGRADED, manager.localState.value)
        manager.transitionLocalState(DeviceState.RECONNECTING)
        assertEquals(DeviceState.RECONNECTING, manager.localState.value)
        manager.transitionLocalState(DeviceState.DISCONNECTED)
        assertEquals(DeviceState.DISCONNECTED, manager.localState.value)

        // Reconnect cycle -> Connecting -> Authenticating -> Connected
        manager.transitionLocalState(DeviceState.CONNECTING)
        manager.transitionLocalState(DeviceState.AUTHENTICATING)
        manager.transitionLocalState(DeviceState.CONNECTED)
        assertEquals(DeviceState.CONNECTED, manager.localState.value)
    }
}

