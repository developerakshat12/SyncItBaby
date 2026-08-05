package com.example.greetingcard

import com.example.greetingcard.protocol.AudioSyncStartConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioSyncStartConfigTest {

    @Test
    fun `serialize and deserialize round trip preserves all fields`() {
        val original = AudioSyncStartConfig(
            startAtLeaderTimeNs = 1_700_000_000_000_000_000L,
            firstFrameLeaderTimeNs = 1_700_000_000_000_000_000L,
            sampleRate = 48000,
            channels = 2
        )

        val bytes = original.toByteArray()
        assertEquals(24, bytes.size)

        val deserialized = AudioSyncStartConfig.fromByteArray(bytes)
        assertEquals(original.startAtLeaderTimeNs, deserialized.startAtLeaderTimeNs)
        assertEquals(original.firstFrameLeaderTimeNs, deserialized.firstFrameLeaderTimeNs)
        assertEquals(original.sampleRate, deserialized.sampleRate)
        assertEquals(original.channels, deserialized.channels)
    }

    @Test
    fun `deserialize throws on truncated payload`() {
        val invalidBytes = ByteArray(20)
        assertThrows(IllegalArgumentException::class.java) {
            AudioSyncStartConfig.fromByteArray(invalidBytes)
        }
    }
}

