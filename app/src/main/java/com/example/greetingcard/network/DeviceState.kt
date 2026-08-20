package com.example.greetingcard.network

enum class DeviceState {

    DISCOVERED,

    CONNECTING,

    AUTHENTICATING,

    CONNECTED,

    BUFFERING,

    READY,

    PLAYING,

    DEGRADED,

    RECONNECTING,

    DISCONNECTED,

    FAILED;

    val isConnectedOrActive: Boolean
        get() = this in listOf(CONNECTED, BUFFERING, READY, PLAYING, DEGRADED)

    val isInProgress: Boolean
        get() = this in listOf(CONNECTING, AUTHENTICATING, BUFFERING, RECONNECTING)
}

