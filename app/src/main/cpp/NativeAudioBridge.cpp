#include <jni.h>
#include <android/log.h>
#include "OboeRenderer.h"

#define LOG_TAG "NativeAudioBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static inline synccast::OboeRenderer* getEngine(jlong ptr) {
    return reinterpret_cast<synccast::OboeRenderer*>(ptr);
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_greetingcard_audio_OboeAudioRenderer_nativeInit(
        JNIEnv* ,
        jobject ,
        jint sampleRate,
        jint channels) {
    try {
        auto* renderer = new synccast::OboeRenderer(sampleRate, channels);
        return reinterpret_cast<jlong>(renderer);
    } catch (...) {
        LOGE("Exception in nativeInit");
        return 0;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_example_greetingcard_audio_OboeAudioRenderer_nativeOpenStream(
        JNIEnv* ,
        jobject ,
        jlong enginePtr) {
    auto* renderer = getEngine(enginePtr);
    if (!renderer) return JNI_FALSE;
    return renderer->openStream() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_greetingcard_audio_OboeAudioRenderer_nativeStart(
        JNIEnv* ,
        jobject ,
        jlong enginePtr) {
    auto* renderer = getEngine(enginePtr);
    if (!renderer) return JNI_FALSE;
    return renderer->start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_greetingcard_audio_OboeAudioRenderer_nativePause(
        JNIEnv* ,
        jobject ,
        jlong enginePtr) {
    auto* renderer = getEngine(enginePtr);
    if (renderer) {
        renderer->pause();
    }
}

JNIEXPORT void JNICALL
Java_com_example_greetingcard_audio_OboeAudioRenderer_nativeStop(
        JNIEnv* ,
        jobject ,
        jlong enginePtr) {
    auto* renderer = getEngine(enginePtr);
    if (renderer) {
        renderer->stop();
    }
}

JNIEXPORT void JNICALL
Java_com_example_greetingcard_audio_OboeAudioRenderer_nativeFlush(
        JNIEnv* ,
        jobject ,
        jlong enginePtr) {
    auto* renderer = getEngine(enginePtr);
    if (renderer) {
        renderer->flush();
    }
}

JNIEXPORT void JNICALL
Java_com_example_greetingcard_audio_OboeAudioRenderer_nativeRelease(
        JNIEnv* ,
        jobject ,
        jlong enginePtr) {
    auto* renderer = getEngine(enginePtr);
    if (renderer) {
        delete renderer;
    }
}

JNIEXPORT jint JNICALL
Java_com_example_greetingcard_audio_OboeAudioRenderer_nativeWriteAudio(
        JNIEnv* env,
        jobject ,
        jlong enginePtr,
        jbyteArray audioData,
        jint offset,
        jint lengthInBytes) {
    auto* renderer = getEngine(enginePtr);
    if (!renderer || !audioData || lengthInBytes <= 0) return 0;

    const jint sampleCount = lengthInBytes / sizeof(int16_t);
    jbyte* pBytes = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(audioData, nullptr));
    if (!pBytes) return 0;

    const int16_t* pSamples = reinterpret_cast<const int16_t*>(pBytes + offset);
    size_t samplesWritten = renderer->writeAudio(pSamples, sampleCount);

    env->ReleasePrimitiveArrayCritical(audioData, pBytes, JNI_ABORT);
    return static_cast<jint>(samplesWritten * sizeof(int16_t));
}

JNIEXPORT jboolean JNICALL
Java_com_example_greetingcard_audio_OboeAudioRenderer_nativeGetHardwareTimestamp(
        JNIEnv* env,
        jobject ,
        jlong enginePtr,
        jlongArray outTimestamp) {
    auto* renderer = getEngine(enginePtr);
    if (!renderer || !outTimestamp) return JNI_FALSE;

    int64_t framePosition = 0;
    int64_t timeNs = 0;
    bool success = renderer->getHardwareTimestamp(&framePosition, &timeNs);
    if (success) {
        jlong temp[2] = { framePosition, timeNs };
        env->SetLongArrayRegion(outTimestamp, 0, 2, temp);
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

JNIEXPORT jdouble JNICALL
Java_com_example_greetingcard_audio_OboeAudioRenderer_nativeGetLatencyMillis(
        JNIEnv* ,
        jobject ,
        jlong enginePtr) {
    auto* renderer = getEngine(enginePtr);
    if (!renderer) return 0.0;
    return renderer->getLatencyMillis();
}

JNIEXPORT jboolean JNICALL
Java_com_example_greetingcard_audio_OboeAudioRenderer_nativeIsMMap(
        JNIEnv* ,
        jobject ,
        jlong enginePtr) {
    auto* renderer = getEngine(enginePtr);
    if (!renderer) return JNI_FALSE;
    return renderer->isMMap() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_example_greetingcard_audio_OboeAudioRenderer_nativeDrainTelemetry(
        JNIEnv* env,
        jobject ,
        jlong enginePtr,
        jlongArray outBuffer,
        jint maxEntries) {
    auto* renderer = getEngine(enginePtr);
    if (!renderer || !outBuffer || maxEntries <= 0) return 0;

    constexpr size_t FIELDS_PER_RECORD = 6;
    NativeTelemetryRecord records[16];
    size_t toDrain = std::min(static_cast<size_t>(maxEntries), static_cast<size_t>(16));
    size_t count = renderer->drainTelemetry(records, toDrain);

    if (count > 0) {
        jlong flat[16 * FIELDS_PER_RECORD];
        for (size_t i = 0; i < count; ++i) {
            flat[i * FIELDS_PER_RECORD + 0] = records[i].timestampNs;
            flat[i * FIELDS_PER_RECORD + 1] = records[i].framePosition;
            // Store latency in microseconds to retain sub-millisecond precision in long
            flat[i * FIELDS_PER_RECORD + 2] = static_cast<int64_t>(records[i].latencyMillis * 1000.0);
            flat[i * FIELDS_PER_RECORD + 3] = records[i].underrunCount;
            flat[i * FIELDS_PER_RECORD + 4] = records[i].ringBufferDepthFrames;
            flat[i * FIELDS_PER_RECORD + 5] = records[i].isMMap;
        }
        env->SetLongArrayRegion(outBuffer, 0, count * FIELDS_PER_RECORD, flat);
    }

    return static_cast<jint>(count);
}

JNIEXPORT void JNICALL
Java_com_example_greetingcard_audio_OboeAudioRenderer_nativeSetDriftCorrection(
        JNIEnv* ,
        jobject ,
        jlong enginePtr,
        jint correctionSamples) {
    // Documented No-Op Stub for Phase 4 (All sample correction happens upstream in Kotlin)
    auto* renderer = getEngine(enginePtr);
    if (renderer) {
        renderer->setDriftCorrection(correctionSamples);
    }
}

} // extern "C"

