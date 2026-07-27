package com.example.greetingcard.protocol

object SyncConstants {

    val MAGIC_BYTES = byteArrayOf(0x53, 0x59, 0x4E, 0x43)

    const val PROTOCOL_VERSION: Byte = 1

    const val DEFAULT_PORT = 9876

    const val DEFAULT_CHUNK_SIZE = 64 * 1024

    const val MAX_PAYLOAD_SIZE = 1024 * 1024

    // --- Stream IDs ---------------------------------------------------------
    const val STREAM_CONTROL = 1
    const val STREAM_NTP = 2
    const val STREAM_FILE = 3
    const val STREAM_AUDIO = 4

    @Deprecated("Renamed to STREAM_NTP", ReplaceWith("STREAM_NTP"))
    const val STREAM_SNTP = STREAM_NTP

    // --- Packet Types -------------------------------------------------------
    const val TYPE_HANDSHAKE_HELLO: Short = 0x0001
    const val TYPE_HEARTBEAT_PING: Short = 0x0002
    const val TYPE_HEARTBEAT_PONG: Short = 0x0003
    const val TYPE_NTP_REQ: Short = 0x0004
    const val TYPE_NTP_RESP: Short = 0x0005
    const val TYPE_CHAT_MESSAGE: Short = 0x0006

    @Deprecated("Renamed to TYPE_NTP_REQ", ReplaceWith("TYPE_NTP_REQ"))
    const val TYPE_SNTP_REQ = TYPE_NTP_REQ
    @Deprecated("Renamed to TYPE_NTP_RESP", ReplaceWith("TYPE_NTP_RESP"))
    const val TYPE_SNTP_RESP = TYPE_NTP_RESP

    const val TYPE_FILE_HEADER: Short = 0x0010
    const val TYPE_FILE_CHUNK: Short = 0x0011
    const val TYPE_FILE_ACK: Short = 0x0012
    const val TYPE_FILE_RETRANSMIT_REQ: Short = 0x0013
    const val TYPE_FILE_COMPLETE: Short = 0x0014

    const val TYPE_AUDIO_STREAM_START: Short = 0x0020
    const val TYPE_AUDIO_DATA: Short = 0x0021
    const val TYPE_AUDIO_STREAM_STOP: Short = 0x0022
    const val TYPE_AUDIO_SYNC_START: Short = 0x0023
    const val TYPE_AUDIO_NACK: Short = 0x0024
    const val TYPE_REQUEST_SYNC_STATE: Short = 0x0025
    const val TYPE_SYNC_STATE: Short = 0x0026
    const val TYPE_SYNC_STATE_ACK: Short = 0x0027
}

