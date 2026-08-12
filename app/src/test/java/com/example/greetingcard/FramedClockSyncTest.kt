package com.example.greetingcard

import com.example.greetingcard.protocol.Packet
import com.example.greetingcard.protocol.PacketSerializer
import com.example.greetingcard.protocol.NtpRequestPayload
import com.example.greetingcard.protocol.NtpResponsePayload
import com.example.greetingcard.protocol.SyncConstants
import com.example.greetingcard.sync.DefaultTimeDomainConverter
import com.example.greetingcard.sync.NtpEngine
import com.example.greetingcard.sync.NtpMath
import com.example.greetingcard.sync.NtpSample
import com.example.greetingcard.sync.Ticker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicLong

class FramedClockSyncTest {

    private class SyntheticTicker(initialNs: Long = 1_000_000_000L) : Ticker {
        private val nanos = AtomicLong(initialNs)
        fun advance(deltaNs: Long) = nanos.addAndGet(deltaNs)
        override fun readNanos(): Long = nanos.get()
        override fun convertMonotonicNanosToTickerNs(monotonicNs: Long): Long = monotonicNs
    }

    private lateinit var clientTicker: SyntheticTicker
    private lateinit var leaderTicker: SyntheticTicker
    private lateinit var converter: DefaultTimeDomainConverter

    @Before
    fun setUp() {
        clientTicker = SyntheticTicker(10_000_000_000L)
        leaderTicker = SyntheticTicker(10_005_000_000L) // +5ms offset on leader
        converter = DefaultTimeDomainConverter(clientTicker, smoothingAlpha = 0.5)
    }

    @Test
    fun `Binary NTP request and response payloads serialize and deserialize accurately`() {
        val req = NtpRequestPayload(sequenceNumber = 42, t1Ns = 1234567890L)
        val reqBytes = req.toByteArray()
        assertEquals(NtpRequestPayload.SIZE_BYTES, reqBytes.size)

        val deserializedReq = NtpRequestPayload.fromByteArray(reqBytes)
        assertNotNull(deserializedReq)
        assertEquals(42, deserializedReq!!.sequenceNumber)
        assertEquals(1234567890L, deserializedReq.t1Ns)

        val resp = NtpResponsePayload(
            sequenceNumber = 42,
            t1Ns = 1234567890L,
            t2Ns = 1234570000L,
            t3Ns = 1234571000L
        )
        val respBytes = resp.toByteArray()
        assertEquals(NtpResponsePayload.SIZE_BYTES, respBytes.size)

        val deserializedResp = NtpResponsePayload.fromByteArray(respBytes)
        assertNotNull(deserializedResp)
        assertEquals(42, deserializedResp!!.sequenceNumber)
        assertEquals(1234567890L, deserializedResp.t1Ns)
        assertEquals(1234570000L, deserializedResp.t2Ns)
        assertEquals(1234571000L, deserializedResp.t3Ns)
    }

    @Test
    fun `Binary packet exchange between client and leader NtpEngines processes accurately`() {
        val clientEngine = NtpEngine(converter, clientTicker)
        val leaderEngine = NtpEngine(DefaultTimeDomainConverter(leaderTicker), leaderTicker)
        leaderEngine.startAsLeader()

        var leaderPacketOut: Packet? = null
        leaderEngine.setPacketTransportHandler { packet ->
            leaderPacketOut = packet
        }

        // Client prepares binary request packet
        val t1 = clientTicker.readNanos()
        val reqPacket = Packet.build(
            packetType = SyncConstants.TYPE_NTP_REQ,
            payload = NtpRequestPayload(sequenceNumber = 1, t1Ns = t1).toByteArray(),
            streamId = SyncConstants.STREAM_NTP
        )

        // Leader advances ticker to simulate 0.5ms network traversal
        leaderTicker.advance(500_000L)
        leaderEngine.handleIncomingPacket(reqPacket)

        assertNotNull(leaderPacketOut)
        assertEquals(SyncConstants.TYPE_NTP_RESP, leaderPacketOut!!.header.packetType)

        // Client advances ticker to simulate 1.5ms traversal + leader processing
        clientTicker.advance(1_500_000L)
        clientEngine.handleIncomingPacket(leaderPacketOut!!)

        // Parse response payload and verify sample calculations
        val respPayload = NtpResponsePayload.fromByteArray(leaderPacketOut!!.payload)
        assertNotNull(respPayload)
        val sample = NtpSample(
            t1 = respPayload!!.t1Ns,
            t2 = respPayload.t2Ns,
            t3 = respPayload.t3Ns,
            t4 = clientTicker.readNanos()
        )

        val offsetMs = sample.offsetNs / 1_000_000.0
        assertTrue("Calculated offset ($offsetMs ms) should be within 4.0..6.0 ms", offsetMs in 4.0..6.0)
    }

    @Test
    fun `Framed NTP probe exchange through binary serializer computes accurate clock offset`() {
        val clientEngine = NtpEngine(converter, clientTicker)
        val leaderEngine = NtpEngine(DefaultTimeDomainConverter(leaderTicker), leaderTicker)
        leaderEngine.startAsLeader()

        // 1. Client initiates probe (t1 sampled)
        val t1 = clientTicker.readNanos()
        val ntpReqFrame = "NTP_REQ:session_123:1:$t1"
        val reqPacket = Packet.buildString(
            packetType = SyncConstants.TYPE_NTP_REQ,
            text = ntpReqFrame,
            streamId = SyncConstants.STREAM_NTP
        )

        // 2. Binary wire serialization
        val wireBaos = ByteArrayOutputStream()
        PacketSerializer.writePacket(DataOutputStream(wireBaos), reqPacket)

        // 3. Leader deserializes from stream
        val wireDis = DataInputStream(ByteArrayInputStream(wireBaos.toByteArray()))
        val leaderReceivedPacket = PacketSerializer.readPacket(wireDis)
        assertEquals(SyncConstants.TYPE_NTP_REQ, leaderReceivedPacket.header.packetType)

        // 4. Leader processes probe and produces response (t2, t3 sampled)
        leaderTicker.advance(500_000L) // +0.5ms network traversal
        var responsePacket: Packet? = null
        leaderEngine.handleIncomingFrame(leaderReceivedPacket.payloadAsString()) { replyFrame ->
            responsePacket = Packet.buildString(
                packetType = SyncConstants.TYPE_NTP_RESP,
                text = replyFrame,
                streamId = SyncConstants.STREAM_NTP
            )
        }
        assertNotNull(responsePacket)

        // 5. Serialize response to wire
        val respBaos = ByteArrayOutputStream()
        PacketSerializer.writePacket(DataOutputStream(respBaos), responsePacket!!)

        // 6. Client receives response (t4 sampled)
        clientTicker.advance(1_500_000L) // +1.5ms traversal + leader processing
        val respDis = DataInputStream(ByteArrayInputStream(respBaos.toByteArray()))
        val clientReceivedPacket = PacketSerializer.readPacket(respDis)
        assertEquals(SyncConstants.TYPE_NTP_RESP, clientReceivedPacket.header.packetType)

        // 7. Parse response payload into sample
        val parts = clientReceivedPacket.payloadAsString().split(":")
        assertEquals("NTP_RESP", parts[0])
        val parsedT1 = parts[3].toLong()
        val parsedT2 = parts[4].toLong()
        val parsedT3 = parts[5].toLong()
        val t4 = clientTicker.readNanos()

        val sample = NtpSample(t1 = parsedT1, t2 = parsedT2, t3 = parsedT3, t4 = t4)
        val calculatedOffsetNs = sample.offsetNs
        val calculatedOffsetMs = calculatedOffsetNs / 1_000_000.0

        // Target: offset should be within <20-30ms target (here around +4.75ms)
        assertTrue(calculatedOffsetMs in 4.0..6.0)
        assertTrue(sample.rttNs < 5_000_000L) // RTT < 5ms
    }
}

