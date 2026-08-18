#include "OboeRenderer.h"
#include <android/log.h>
#include <cstring>

#define LOG_TAG "OboeRenderer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace synccast {

OboeRenderer::OboeRenderer(int32_t sampleRate, int32_t channelCount)
    : sampleRate_(sampleRate),
      channelCount_(channelCount),
      stream_(nullptr),
      underrunCount_(0),
      isPlaying_(false),
      isStreamOpen_(false) {
    LOGI("OboeRenderer constructed (sampleRate=%d, channels=%d)", sampleRate_, channelCount_);
}

OboeRenderer::~OboeRenderer() {
    closeStream();
    LOGI("OboeRenderer destroyed");
}

bool OboeRenderer::openStream() {
    if (isStreamOpen_.load(std::memory_order_acquire)) {
        LOGW("openStream called while stream already open");
        return true;
    }

    oboe::AudioStreamBuilder builder;
    builder.setAudioApi(oboe::AudioApi::AAudio)
           ->setDirection(oboe::Direction::Output)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(oboe::SharingMode::Exclusive)
           ->setFormat(oboe::AudioFormat::I16)
           ->setChannelCount(channelCount_)
           ->setSampleRate(sampleRate_)
           ->setDataCallback(this)
           ->setErrorCallback(this);

    oboe::Result result = builder.openStream(stream_);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open Oboe stream: %s", oboe::convertToText(result));
        stream_ = nullptr;
        isStreamOpen_.store(false, std::memory_order_release);
        return false;
    }

    isStreamOpen_.store(true, std::memory_order_release);
    LOGI("Oboe stream opened successfully (api=%s, sharingMode=%s, framesPerBurst=%d, bufferCapacity=%d)",
         oboe::convertToText(stream_->getAudioApi()),
         oboe::convertToText(stream_->getSharingMode()),
         stream_->getFramesPerBurst(),
         stream_->getBufferCapacityInFrames());

    return true;
}

bool OboeRenderer::start() {
    if (!isStreamOpen_.load(std::memory_order_acquire)) {
        if (!openStream()) {
            return false;
        }
    }

    if (isPlaying_.load(std::memory_order_acquire)) {
        return true;
    }

    oboe::Result result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start Oboe stream: %s", oboe::convertToText(result));
        return false;
    }

    isPlaying_.store(true, std::memory_order_release);
    LOGI("Oboe stream started playback");
    return true;
}

void OboeRenderer::pause() {
    if (stream_ && isPlaying_.load(std::memory_order_acquire)) {
        isPlaying_.store(false, std::memory_order_release);
        stream_->requestPause();
        LOGI("Oboe stream paused");
    }
}

void OboeRenderer::stop() {
    if (stream_ && isPlaying_.load(std::memory_order_acquire)) {
        isPlaying_.store(false, std::memory_order_release);
        stream_->requestStop();
        LOGI("Oboe stream stopped");
    }
}

void OboeRenderer::flush() {
    if (stream_) {
        stream_->requestFlush();
        pcmRingBuffer_.clear();
        LOGI("Oboe stream flushed and PCM ring buffer cleared");
    }
}

void OboeRenderer::closeStream() {
    isPlaying_.store(false, std::memory_order_release);
    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_ = nullptr;
    }
    isStreamOpen_.store(false, std::memory_order_release);
    pcmRingBuffer_.clear();
    telemetryRingBuffer_.clear();
    LOGI("Oboe stream closed");
}

size_t OboeRenderer::writeAudio(const int16_t* data, size_t sampleCount) {
    if (!data || sampleCount == 0) return 0;
    return pcmRingBuffer_.write(data, sampleCount);
}

bool OboeRenderer::getHardwareTimestamp(int64_t* outFramePosition, int64_t* outTimeNs) {
    if (!stream_ || !outFramePosition || !outTimeNs) {
        return false;
    }

    int64_t framePos = 0;
    int64_t timeNs = 0;
    oboe::Result result = stream_->getTimestamp(CLOCK_MONOTONIC, &framePos, &timeNs);
    if (result == oboe::Result::OK && timeNs > 0) {
        *outFramePosition = framePos;
        *outTimeNs = timeNs;
        return true;
    }

    // High-precision fallback for AAudio Legacy/Shared mode where HAL timestamp is unavailable or intermittent
    int64_t framesRead = stream_->getFramesRead();
    if (framesRead >= 0) {
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        *outFramePosition = framesRead;
        *outTimeNs = (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
        return true;
    }

    return false;
}

double OboeRenderer::getLatencyMillis() {
    if (!stream_) return 0.0;
    auto latencyResult = stream_->calculateLatencyMillis();
    if (latencyResult) {
        return latencyResult.value();
    }
    return 0.0;
}

bool OboeRenderer::isMMap() const {
    if (stream_) {
        return (stream_->getAudioApi() == oboe::AudioApi::AAudio &&
                stream_->getSharingMode() == oboe::SharingMode::Exclusive);
    }
    return false;
}

size_t OboeRenderer::drainTelemetry(NativeTelemetryRecord* outArray, size_t maxRecords) {
    if (!outArray || maxRecords == 0) return 0;
    return telemetryRingBuffer_.read(outArray, maxRecords);
}

oboe::DataCallbackResult OboeRenderer::onAudioReady(oboe::AudioStream* audioStream,
                                                    void* audioData,
                                                    int32_t numFrames) {
    const size_t neededSamples = static_cast<size_t>(numFrames * channelCount_);
    int16_t* outputBuffer = static_cast<int16_t*>(audioData);

    // 1. Read pre-corrected PCM samples from the lock-free GC shock absorber ring buffer
    const size_t readSamples = pcmRingBuffer_.read(outputBuffer, neededSamples);

    // 2. Starvation handling: if ring buffer ran dry, fill remainder with silence
    if (readSamples < neededSamples) {
        const size_t missingSamples = neededSamples - readSamples;
        std::memset(outputBuffer + readSamples, 0, missingSamples * sizeof(int16_t));
        underrunCount_.fetch_add(1, std::memory_order_relaxed);
    }

    // 3. Query physical DAC presentation anchor and ALSA hardware latency
    int64_t dacFramePos = 0;
    int64_t dacTimeNs = 0;
    audioStream->getTimestamp(CLOCK_MONOTONIC, &dacFramePos, &dacTimeNs);

    double latencyMs = 0.0;
    auto latencyResult = audioStream->calculateLatencyMillis();
    if (latencyResult) {
        latencyMs = latencyResult.value();
    }

    // 4. Push telemetry record to lock-free SPSC telemetry queue for non-RT JNI draining
    NativeTelemetryRecord record;
    record.timestampNs = dacTimeNs;
    record.framePosition = dacFramePos;
    record.latencyMillis = latencyMs;
    record.underrunCount = underrunCount_.load(std::memory_order_relaxed);
    record.ringBufferDepthFrames = static_cast<int32_t>(pcmRingBuffer_.availableRead() / channelCount_);
    record.isMMap = (audioStream->getAudioApi() == oboe::AudioApi::AAudio &&
                     audioStream->getSharingMode() == oboe::SharingMode::Exclusive) ? 1 : 0;

    telemetryRingBuffer_.write(&record, 1);

    return oboe::DataCallbackResult::Continue;
}

void OboeRenderer::onErrorAfterClose(oboe::AudioStream* audioStream, oboe::Result result) {
    LOGW("Oboe stream closed due to error: %s. Reopening stream...", oboe::convertToText(result));
    isStreamOpen_.store(false, std::memory_order_release);
    isPlaying_.store(false, std::memory_order_release);
    openStream();
}

} // namespace synccast

