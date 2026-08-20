#pragma once

#include <oboe/Oboe.h>
#include <memory>
#include <atomic>
#include <cstdint>
#include "SpscRingBuffer.h"

namespace synccast {

class OboeRenderer : public oboe::AudioStreamDataCallback,
                     public oboe::AudioStreamErrorCallback {
public:
    OboeRenderer(int32_t sampleRate = 48000, int32_t channelCount = 2);
    virtual ~OboeRenderer();

    // Stream Lifecycle Management
    bool openStream();
    bool start();
    void pause();
    void stop();
    void flush();
    void closeStream();

    // PCM Audio Ingress (Called by Kotlin JNI Producer)
    size_t writeAudio(const int16_t* data, size_t sampleCount);

    // Hardware Clock & Latency Query (Called by Kotlin JNI)
    bool getHardwareTimestamp(int64_t* outFramePosition, int64_t* outTimeNs);
    double getLatencyMillis();
    bool isMMap() const;

    // Telemetry Egress (Called by Kotlin Dispatchers.Default Coroutine)
    size_t drainTelemetry(NativeTelemetryRecord* outArray, size_t maxRecords);

    // Documented No-Op Stub for Phase 4 (All sample correction happens upstream in Kotlin)
    void setDriftCorrection(int32_t ) {}

    // AudioStreamDataCallback Interface (Real-Time AAudio POSIX Thread)
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* audioStream,
                                         void* audioData,
                                         int32_t numFrames) override;

    // AudioStreamErrorCallback Interface
    void onErrorAfterClose(oboe::AudioStream* audioStream, oboe::Result result) override;

private:
    int32_t sampleRate_;
    int32_t channelCount_;
    std::shared_ptr<oboe::AudioStream> stream_;

    // Lock-free GC shock absorber PCM ring buffer (~1.36s of 16-bit stereo PCM)
    SpscRingBuffer<int16_t, 131072> pcmRingBuffer_;

    // Lock-free telemetry ring buffer for non-RT JNI draining
    SpscRingBuffer<NativeTelemetryRecord, 128> telemetryRingBuffer_;

    std::atomic<int64_t> underrunCount_{0};
    std::atomic<bool> isPlaying_{false};
    std::atomic<bool> isStreamOpen_{false};
};

} // namespace synccast

