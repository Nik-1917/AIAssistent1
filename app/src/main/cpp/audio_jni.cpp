#include <jni.h>
#include <algorithm>
#include <cstdint>
#include <vector>
#include "lame.h"
#include "webrtc/modules/audio_processing/aec/include/echo_cancellation.h"
#include "webrtc/modules/audio_processing/ns/include/noise_suppression.h"
static void fail(JNIEnv* e, const char* message) {
    e->ThrowNew(e->FindClass("java/lang/IllegalStateException"), message);
}
#define JNI_METHOD(name) Java_com_example_aiassistent1_data_provider_NativeAudio_##name
extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(createEncoder)(JNIEnv* e, jobject, jint rate, jint bitrate) {
    auto p = lame_init();
    if (!p) { fail(e, "LAME allocation failed"); return 0; }
    if (lame_set_in_samplerate(p, rate) < 0 || lame_set_num_channels(p, 1) < 0 ||
        lame_set_brate(p, bitrate) < 0 || lame_set_quality(p, 3) < 0) {
        lame_close(p); fail(e, "Invalid LAME configuration"); return 0;
    }
    lame_set_bWriteVbrTag(p, 0);
    if (lame_init_params(p) < 0) { lame_close(p); fail(e, "LAME init failed"); return 0; }
    return reinterpret_cast<jlong>(p);
}
extern "C" JNIEXPORT jbyteArray JNICALL JNI_METHOD(encode)(JNIEnv* e, jobject, jlong h, jshortArray pcm, jint count, jboolean flush) {
    auto p = reinterpret_cast<lame_t>(h);
    if (!p || count < 0 || count > 65536 || (!flush && (!pcm || count > e->GetArrayLength(pcm)))) {
        fail(e, "Invalid MP3 buffer"); return nullptr;
    }
    std::vector<unsigned char> out(7200 + count * 5 / 4);
    int n;
    if (flush) n = lame_encode_flush(p, out.data(), out.size());
    else {
        std::vector<short> in(count);
        e->GetShortArrayRegion(pcm, 0, count, in.data());
        if (e->ExceptionCheck()) return nullptr;
        n = lame_encode_buffer(p, in.data(), in.data(), count, out.data(), out.size());
    }
    if (n < 0) { fail(e, "LAME encoding failed"); return nullptr; }
    auto result = e->NewByteArray(n);
    if (result) e->SetByteArrayRegion(result, 0, n, reinterpret_cast<jbyte*>(out.data()));
    return result;
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(closeEncoder)(JNIEnv*, jobject, jlong h) { if (h) lame_close(reinterpret_cast<lame_t>(h)); }
struct Processing {
    void* aec = WebRtcAec_Create();
    NsHandle* ns = WebRtcNs_Create();
    bool echo;
    explicit Processing(bool enabled): echo(enabled) {}
    ~Processing() { if(aec) WebRtcAec_Free(aec); if(ns) WebRtcNs_Free(ns); }
};
extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(createProcessor)(JNIEnv* e, jobject, jboolean echo) {
    auto p = new Processing(echo);
    if (!p->aec || !p->ns || WebRtcAec_Init(p->aec, 16000, 16000) != 0 || WebRtcNs_Init(p->ns, 16000) != 0) {
        delete p; fail(e, "WebRTC initialization failed"); return 0;
    }
    WebRtcNs_set_policy(p->ns, 1);
    return reinterpret_cast<jlong>(p);
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(processFrame)(JNIEnv* e, jobject, jlong h, jfloatArray data, jboolean reverse, jint delay) {
    auto p = reinterpret_cast<Processing*>(h);
    if (!p || !data || e->GetArrayLength(data) != 160) { fail(e, "WebRTC requires 10 ms frames at 16 kHz"); return; }
    float input[160], clean[160], output[160];
    e->GetFloatArrayRegion(data, 0, 160, input);
    if (e->ExceptionCheck()) return;
    for (float& x : input) x = std::clamp(x, -1.0f, 1.0f) * 32768.0f;
    if (reverse) {
        if (p->echo && WebRtcAec_BufferFarend(p->aec, input, 160) != 0) fail(e, "AEC render processing failed");
        return;
    }
    const float* in[] = {input}; float* mid[] = {clean};
    if (p->echo) {
        if (WebRtcAec_Process(p->aec, in, 1, mid, 160, std::clamp(delay, 0, 500), 0) != 0) {
            fail(e, "AEC capture processing failed"); return;
        }
    } else std::copy(input, input + 160, clean);
    const float* nsIn[] = {clean}; float* nsOut[] = {output};
    WebRtcNs_Analyze(p->ns, clean);
    WebRtcNs_Process(p->ns, nsIn, 1, nsOut);
    for (float& x : output) x = std::clamp(x / 32768.0f, -1.0f, 1.0f);
    e->SetFloatArrayRegion(data, 0, 160, output);
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(closeProcessor)(JNIEnv*, jobject, jlong h) { delete reinterpret_cast<Processing*>(h); }
