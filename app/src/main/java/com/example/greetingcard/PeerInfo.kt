package com.example.greetingcard

import com.example.greetingcard.network.DeviceState

data class PeerInfo(

    val id: String,

    val address: String,

    val name: String,

    val state: DeviceState = DeviceState.DISCOVERED,

    val lastPingMs: Long = 0L,

    val lastSeenTimestamp: Long = System.currentTimeMillis(),
)

