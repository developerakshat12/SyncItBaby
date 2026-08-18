#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <algorithm>

// Hardware cache line size standard for ARM/x86 cache line alignment
#ifndef SPSC_CACHELINE_SIZE
#define SPSC_CACHELINE_SIZE 64
#endif

struct NativeTelemetryRecord {
    int64_t timestampNs;          // DAC hardware monotonic timestamp in CLOCK_MONOTONIC domain
    int64_t framePosition;        // DAC presentation frame position
    double latencyMillis;         // End-to-end hardware/ALSA latency in milliseconds
    int64_t underrunCount;        // Cumulative starvation/underrun count
    int32_t ringBufferDepthFrames;// Queued PCM frames available in native SPSC buffer
    int32_t isMMap;               // 1 if MMAP Exclusive mode active, 0 otherwise
};

template <typename T, size_t Capacity>
class SpscRingBuffer {
    static_assert((Capacity > 0) && ((Capacity & (Capacity - 1)) == 0), "Capacity must be a power of two");

public:
    SpscRingBuffer() : writeIndex_(0), readIndex_(0) {}
    ~SpscRingBuffer() = default;

    // Non-copyable and non-movable
    SpscRingBuffer(const SpscRingBuffer&) = delete;
    SpscRingBuffer& operator=(const SpscRingBuffer&) = delete;

    size_t availableRead() const {
        const size_t writeIdx = writeIndex_.load(std::memory_order_acquire);
        const size_t readIdx = readIndex_.load(std::memory_order_relaxed);
        return writeIdx - readIdx;
    }

    size_t availableWrite() const {
        const size_t readIdx = readIndex_.load(std::memory_order_acquire);
        const size_t writeIdx = writeIndex_.load(std::memory_order_relaxed);
        return Capacity - (writeIdx - readIdx);
    }

    size_t write(const T* data, size_t count) {
        if (data == nullptr || count == 0) return 0;

        const size_t readIdx = readIndex_.load(std::memory_order_acquire);
        const size_t writeIdx = writeIndex_.load(std::memory_order_relaxed);
        const size_t available = Capacity - (writeIdx - readIdx);
        const size_t toWrite = std::min(count, available);

        if (toWrite == 0) return 0;

        const size_t mask = Capacity - 1;
        const size_t startOffset = writeIdx & mask;
        const size_t firstPart = std::min(toWrite, Capacity - startOffset);
        const size_t secondPart = toWrite - firstPart;

        std::memcpy(&buffer_[startOffset], data, firstPart * sizeof(T));
        if (secondPart > 0) {
            std::memcpy(&buffer_[0], data + firstPart, secondPart * sizeof(T));
        }

        writeIndex_.store(writeIdx + toWrite, std::memory_order_release);
        return toWrite;
    }

    size_t read(T* outData, size_t count) {
        if (outData == nullptr || count == 0) return 0;

        const size_t writeIdx = writeIndex_.load(std::memory_order_acquire);
        const size_t readIdx = readIndex_.load(std::memory_order_relaxed);
        const size_t available = writeIdx - readIdx;
        const size_t toRead = std::min(count, available);

        if (toRead == 0) return 0;

        const size_t mask = Capacity - 1;
        const size_t startOffset = readIdx & mask;
        const size_t firstPart = std::min(toRead, Capacity - startOffset);
        const size_t secondPart = toRead - firstPart;

        std::memcpy(outData, &buffer_[startOffset], firstPart * sizeof(T));
        if (secondPart > 0) {
            std::memcpy(outData + firstPart, &buffer_[0], secondPart * sizeof(T));
        }

        readIndex_.store(readIdx + toRead, std::memory_order_release);
        return toRead;
    }

    void clear() {
        readIndex_.store(0, std::memory_order_relaxed);
        writeIndex_.store(0, std::memory_order_relaxed);
    }

private:
    alignas(SPSC_CACHELINE_SIZE) T buffer_[Capacity];

    // Read and write indices are aligned to separate cachelines to prevent false sharing
    alignas(SPSC_CACHELINE_SIZE) std::atomic<size_t> writeIndex_{0};
    alignas(SPSC_CACHELINE_SIZE) std::atomic<size_t> readIndex_{0};
};

