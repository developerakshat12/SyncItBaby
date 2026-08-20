package com.example.greetingcard

import com.example.greetingcard.audio.TelemetryData
import com.example.greetingcard.audio.TelemetryRingBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class TelemetryRingBufferTest {

    @Test
    fun `basic single-threaded offer and poll preserves FIFO order`() {
        val buffer = TelemetryRingBuffer(capacity = 8)
        assertTrue(buffer.isEmpty)
        assertEquals(0, buffer.size)

        for (i in 1..5) {
            val success = buffer.tryOffer(
                timestampNs = i * 1_000_000L,
                expectedFrame = i * 1000L,
                actualFrame = i * 1000L - 10,
                phaseErrorMs = 0.25 * i,
                smoothedErrorNs = 250_000.0 * i,
                correction = if (i % 2 == 0) 1 else -1,
                jitterBufferMs = 200L + i,
                isAggressiveMode = (i == 1),
                timestampValid = true,
                leaderClockTimeNs = i * 1_000_000L - 50_000L
            )
            assertTrue(success)
        }

        assertEquals(5, buffer.size)
        assertFalse(buffer.isEmpty)

        for (i in 1..5) {
            val sample = buffer.poll()
            assertNotNull(sample)
            assertEquals(i * 1_000_000L, sample!!.timestampNs)
            assertEquals(i * 1000L, sample.expectedFrame)
            assertEquals(i * 1000L - 10, sample.actualFrame)
            assertEquals(0.25 * i, sample.phaseErrorMs, 1e-6)
            assertEquals(250_000.0 * i, sample.smoothedErrorNs, 1e-6)
            assertEquals(if (i % 2 == 0) 1 else -1, sample.correction)
            assertEquals(200L + i, sample.jitterBufferMs)
            assertEquals(i == 1, sample.isAggressiveMode)
            assertTrue(sample.timestampValid)
        }

        assertTrue(buffer.isEmpty)
        assertNull(buffer.poll())
    }

    @Test
    fun `buffer overflow drops extra samples silently without blocking or throwing`() {
        val capacity = 4
        val buffer = TelemetryRingBuffer(capacity = capacity)

        // Fill buffer to capacity
        for (i in 1..capacity) {
            val offered = buffer.tryOffer(
                timestampNs = i.toLong(),
                expectedFrame = i.toLong(),
                actualFrame = i.toLong(),
                phaseErrorMs = 0.0,
                smoothedErrorNs = 0.0,
                correction = 0,
                jitterBufferMs = 100L,
                leaderClockTimeNs = 0L
            )
            assertTrue("Expected sample $i to be accepted", offered)
        }

        assertEquals(capacity, buffer.size)

        // Subsequent offers must fail / drop silently
        for (i in (capacity + 1)..(capacity + 5)) {
            val offered = buffer.tryOffer(
                timestampNs = i.toLong(),
                expectedFrame = i.toLong(),
                actualFrame = i.toLong(),
                phaseErrorMs = 0.0,
                smoothedErrorNs = 0.0,
                correction = 0,
                jitterBufferMs = 100L,
                leaderClockTimeNs = 0L
            )
            assertFalse("Expected sample $i to be dropped", offered)
        }

        assertEquals(capacity, buffer.size)

        // Draining retrieves only the original items 1..capacity
        val drained = buffer.drainAll()
        assertEquals(capacity, drained.size)
        for (i in 0 until capacity) {
            assertEquals((i + 1).toLong(), drained[i].expectedFrame)
        }

        assertTrue(buffer.isEmpty)
    }

    @Test
    fun `drainAll returns up to max requested items`() {
        val buffer = TelemetryRingBuffer(capacity = 8)
        for (i in 1..6) {
            buffer.tryOffer(
                timestampNs = i.toLong(),
                expectedFrame = i.toLong(),
                actualFrame = i.toLong(),
                phaseErrorMs = 0.0,
                smoothedErrorNs = 0.0,
                correction = 0,
                jitterBufferMs = 100L,
                leaderClockTimeNs = 0L
            )
        }

        val firstThree = buffer.drainAll(maxItems = 3)
        assertEquals(3, firstThree.size)
        assertEquals(1L, firstThree[0].expectedFrame)
        assertEquals(2L, firstThree[1].expectedFrame)
        assertEquals(3L, firstThree[2].expectedFrame)

        assertEquals(3, buffer.size)

        val remaining = buffer.drainAll()
        assertEquals(3, remaining.size)
        assertEquals(4L, remaining[0].expectedFrame)
        assertEquals(5L, remaining[1].expectedFrame)
        assertEquals(6L, remaining[2].expectedFrame)

        assertTrue(buffer.isEmpty)
    }

    @Test
    fun `concurrent producer and consumer maintain memory visibility and monotonic ordering`() {
        val capacity = 32
        val buffer = TelemetryRingBuffer(capacity = capacity)
        val totalSamples = 10_000
        val producerDone = AtomicBoolean(false)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(2)

        val consumedCount = AtomicInteger(0)
        val droppedCount = AtomicInteger(0)
        val lastConsumedSeq = AtomicInteger(-1)
        var concurrencyViolation = false

        // Producer Thread (simulating audio callback)
        val producer = Thread {
            startLatch.await()
            for (seq in 0 until totalSamples) {
                val success = buffer.tryOffer(
                    timestampNs = seq.toLong(),
                    expectedFrame = seq.toLong(),
                    actualFrame = seq.toLong(),
                    phaseErrorMs = seq.toDouble(),
                    smoothedErrorNs = seq.toDouble() * 1000.0,
                    correction = 0,
                    jitterBufferMs = 200L,
                    isAggressiveMode = (seq % 100 == 0),
                    timestampValid = true,
                    leaderClockTimeNs = 0L
                )
                if (!success) {
                    droppedCount.incrementAndGet()
                }
                // Periodic light yield / spin
                if (seq % 64 == 0) {
                    Thread.yield()
                }
            }
            producerDone.set(true)
            doneLatch.countDown()
        }

        // Consumer Thread (simulating background coroutine drain)
        val consumer = Thread {
            startLatch.await()
            while (!producerDone.get() || !buffer.isEmpty) {
                val sample = buffer.poll()
                if (sample != null) {
                    val seq = sample.expectedFrame.toInt()
                    val prev = lastConsumedSeq.getAndSet(seq)
                    if (seq <= prev) {
                        concurrencyViolation = true
                    }
                    consumedCount.incrementAndGet()
                } else {
                    Thread.yield()
                }
            }
            doneLatch.countDown()
        }

        producer.start()
        consumer.start()
        startLatch.countDown()

        val completed = doneLatch.await(5, TimeUnit.SECONDS)
        assertTrue("Concurrent test timed out", completed)
        assertFalse("Detected sequence reordering / concurrency violation", concurrencyViolation)

        val totalProcessed = consumedCount.get() + droppedCount.get()
        assertEquals(totalSamples, totalProcessed)
        assertTrue("Consumer should have processed at least some samples", consumedCount.get() > 0)
    }

    @Test
    fun `concurrent producer and consumer with batch drainAll under variable pacing`() {
        val capacity = 64
        val buffer = TelemetryRingBuffer(capacity = capacity)
        val totalSamples = 20_000
        val producerDone = AtomicBoolean(false)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(2)

        val consumedSamples = mutableListOf<TelemetryData>()
        val droppedCount = AtomicInteger(0)
        var outOfOrderDetected = false

        val producer = Thread {
            startLatch.await()
            for (seq in 0 until totalSamples) {
                val success = buffer.tryOffer(
                    timestampNs = seq * 1_000L,
                    expectedFrame = seq.toLong(),
                    actualFrame = seq.toLong(),
                    phaseErrorMs = 0.1,
                    smoothedErrorNs = 100_000.0,
                    correction = 0,
                    jitterBufferMs = 150L,
                    isAggressiveMode = false,
                    timestampValid = true,
                    leaderClockTimeNs = 0L
                )
                if (!success) {
                    droppedCount.incrementAndGet()
                }
                if (seq % 128 == 0) {
                    Thread.sleep(0, 100) // 100ns micro-pause
                }
            }
            producerDone.set(true)
            doneLatch.countDown()
        }

        val consumer = Thread {
            startLatch.await()
            var lastSeq = -1L
            while (!producerDone.get() || !buffer.isEmpty) {
                val batch = buffer.drainAll(maxItems = 16)
                if (batch.isNotEmpty()) {
                    for (item in batch) {
                        if (item.expectedFrame <= lastSeq) {
                            outOfOrderDetected = true
                        }
                        lastSeq = item.expectedFrame
                        consumedSamples.add(item)
                    }
                } else {
                    Thread.yield()
                }
            }
            doneLatch.countDown()
        }

        producer.start()
        consumer.start()
        startLatch.countDown()

        val finished = doneLatch.await(5, TimeUnit.SECONDS)
        assertTrue("Batch concurrent stress test timed out", finished)
        assertFalse("Out-of-order samples detected during concurrent batch drain", outOfOrderDetected)
        assertEquals(totalSamples, consumedSamples.size + droppedCount.get())
        assertTrue("Consumer should receive samples under concurrent load", consumedSamples.isNotEmpty())
    }

    @Test
    fun `telemetry list capping retains latest 3 entries`() {
        var telemetryList = emptyList<TelemetryData>()

        for (i in 1..10) {
            val sample = TelemetryData(expectedFrame = i.toLong())
            telemetryList = (telemetryList + sample).takeLast(3)
        }

        assertEquals(3, telemetryList.size)
        assertEquals(8L, telemetryList[0].expectedFrame)
        assertEquals(9L, telemetryList[1].expectedFrame)
        assertEquals(10L, telemetryList[2].expectedFrame)
    }
}

