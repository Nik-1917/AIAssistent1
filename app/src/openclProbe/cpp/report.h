#pragma once
#include <jni.h>
#include <stdexcept>
#include <string>

// Reports a checkpoint to the app before the next native call can terminate the worker.
struct ProbeReport {
    JNIEnv * env;
    jobject sink;
    jmethodID field;
    ProbeReport(JNIEnv * e, jobject s) : env(e), sink(s) {
        auto type = env->GetObjectClass(sink);
        field = env->GetMethodID(type, "field", "(Ljava/lang/String;[B)V");
        env->DeleteLocalRef(type);
    }
    void set(const char * key, const std::string & value) const {
        auto k = env->NewStringUTF(key);
        auto bytes = env->NewByteArray(static_cast<jsize>(value.size()));
        if (k && bytes) {
            env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(value.size()), reinterpret_cast<const jbyte *>(value.data()));
            if (!env->ExceptionCheck()) env->CallVoidMethod(sink, field, k, bytes);
        }
        env->DeleteLocalRef(bytes);
        env->DeleteLocalRef(k);
        if (env->ExceptionCheck()) throw std::runtime_error("Checkpoint write failed");
    }
};
