// Offline, fixed-shape experimental encoder. Uses the existing Sherpa ORT shared library.
#include <jni.h>
#include <dlfcn.h>
#include <array>
#include <cmath>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>
#include "third_party/onnxruntime/onnxruntime_c_api.h"

namespace {
constexpr size_t kFeatures = 101 * 40;
constexpr size_t kEmbedding = 64;

struct Runtime {
    const OrtApi* api;
    std::string version;
    Runtime() {
        // Deliberately retained for process lifetime: Sherpa shares this runtime.
        void* library = dlopen("libonnxruntime.so", RTLD_NOW | RTLD_LOCAL);
        if (!library) throw std::runtime_error("Existing ONNX Runtime is unavailable");
        using GetBase = const OrtApiBase* (ORT_API_CALL*)();
        auto getBase = reinterpret_cast<GetBase>(dlsym(library, "OrtGetApiBase"));
        if (!getBase) { dlclose(library); throw std::runtime_error("ORT C API export is missing"); }
        const OrtApiBase* base = getBase();
        api = base->GetApi(ORT_API_VERSION);
        if (!api) { dlclose(library); throw std::runtime_error("ORT C API 22 is unsupported"); }
        version = base->GetVersionString();
    }
};

Runtime& runtime() { static Runtime instance; return instance; }

void checked(OrtStatus* status) {
    if (!status) return;
    const auto* api = runtime().api;
    std::string message = api->GetErrorMessage(status);
    api->ReleaseStatus(status);
    throw std::runtime_error(message);
}

template<typename T> using Owned = std::unique_ptr<T, void (ORT_API_CALL*)(T*)>;

struct Session {
    const OrtApi* api = runtime().api;
    Owned<OrtEnv> env{nullptr, api->ReleaseEnv};
    Owned<OrtSession> session{nullptr, api->ReleaseSession};
    Owned<OrtMemoryInfo> memory{nullptr, api->ReleaseMemoryInfo};
    std::mutex inferenceMutex;

    explicit Session(const std::vector<uint8_t>& bytes) {
        OrtEnv* rawEnv = nullptr;
        checked(api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "MetricKws", &rawEnv));
        env.reset(rawEnv);
        OrtSessionOptions* rawOptions = nullptr;
        checked(api->CreateSessionOptions(&rawOptions));
        Owned<OrtSessionOptions> options(rawOptions, api->ReleaseSessionOptions);
        checked(api->SetIntraOpNumThreads(options.get(), 1));
        checked(api->SetInterOpNumThreads(options.get(), 1));
        checked(api->SetSessionExecutionMode(options.get(), ORT_SEQUENTIAL));
        checked(api->SetSessionGraphOptimizationLevel(options.get(), ORT_ENABLE_EXTENDED));
        OrtSession* rawSession = nullptr;
        checked(api->CreateSessionFromArray(env.get(), bytes.data(), bytes.size(), options.get(), &rawSession));
        session.reset(rawSession);
        validate(true, "features", {1, 101, 40});
        validate(false, "embedding", {1, 64});
        OrtMemoryInfo* rawMemory = nullptr;
        checked(api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &rawMemory));
        memory.reset(rawMemory);
    }

    void validate(bool input, const char* expectedName, const std::vector<int64_t>& expectedShape) {
        size_t count = 0;
        checked(input ? api->SessionGetInputCount(session.get(), &count)
                      : api->SessionGetOutputCount(session.get(), &count));
        if (count != 1) throw std::runtime_error("KWS model must have exactly one input/output");
        OrtAllocator* allocator = nullptr;
        checked(api->GetAllocatorWithDefaultOptions(&allocator));
        char* rawName = nullptr;
        checked(input ? api->SessionGetInputName(session.get(), 0, allocator, &rawName)
                      : api->SessionGetOutputName(session.get(), 0, allocator, &rawName));
        std::string name(rawName);
        allocator->Free(allocator, rawName);
        if (name != expectedName) throw std::runtime_error("Unexpected KWS tensor name");
        OrtTypeInfo* rawType = nullptr;
        checked(input ? api->SessionGetInputTypeInfo(session.get(), 0, &rawType)
                      : api->SessionGetOutputTypeInfo(session.get(), 0, &rawType));
        Owned<OrtTypeInfo> type(rawType, api->ReleaseTypeInfo);
        const OrtTensorTypeAndShapeInfo* tensor = nullptr;
        checked(api->CastTypeInfoToTensorInfo(type.get(), &tensor));
        if (!tensor) throw std::runtime_error("KWS requires a tensor");
        ONNXTensorElementDataType dtype;
        checked(api->GetTensorElementType(tensor, &dtype));
        size_t dimensions = 0;
        checked(api->GetDimensionsCount(tensor, &dimensions));
        if (dtype != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT || dimensions != expectedShape.size())
            throw std::runtime_error("Invalid KWS tensor type/rank");
        std::vector<int64_t> shape(dimensions);
        checked(api->GetDimensions(tensor, shape.data(), shape.size()));
        if (shape != expectedShape) throw std::runtime_error("Invalid KWS tensor shape");
    }

    std::array<float, kEmbedding> infer(std::array<float, kFeatures>& features) {
        std::lock_guard<std::mutex> lock(inferenceMutex);
        constexpr int64_t shape[] = {1, 101, 40};
        OrtValue* rawInput = nullptr;
        checked(api->CreateTensorWithDataAsOrtValue(memory.get(), features.data(), features.size() * sizeof(float),
            shape, 3, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &rawInput));
        Owned<OrtValue> input(rawInput, api->ReleaseValue);
        const OrtValue* inputs[] = {input.get()};
        const char* inputNames[] = {"features"};
        const char* outputNames[] = {"embedding"};
        OrtValue* rawOutput = nullptr;
        OrtStatus* runStatus = api->Run(session.get(), nullptr, inputNames, inputs, 1, outputNames, 1, &rawOutput);
        Owned<OrtValue> output(rawOutput, api->ReleaseValue);
        checked(runStatus);
        OrtTensorTypeAndShapeInfo* rawInfo = nullptr;
        checked(api->GetTensorTypeAndShape(output.get(), &rawInfo));
        Owned<OrtTensorTypeAndShapeInfo> info(rawInfo, api->ReleaseTensorTypeAndShapeInfo);
        size_t count = 0;
        checked(api->GetTensorShapeElementCount(info.get(), &count));
        if (count != kEmbedding) throw std::runtime_error("Invalid KWS output length");
        float* data = nullptr;
        checked(api->GetTensorMutableData(output.get(), reinterpret_cast<void**>(&data)));
        std::array<float, kEmbedding> result{};
        for (size_t i = 0; i < result.size(); ++i) {
            if (!std::isfinite(data[i])) throw std::runtime_error("Nonfinite KWS output");
            result[i] = data[i];
        }
        return result;
    }
};

std::mutex sessionsMutex;
std::unordered_map<jlong, std::shared_ptr<Session>> sessions;
jlong nextHandle = 1;

void fail(JNIEnv* env, const std::exception& error) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_aiassistent1_data_provider_MetricKwsNative_create(JNIEnv* env, jobject, jbyteArray model) {
    try {
        if (!model) throw std::runtime_error("Missing KWS model");
        const jsize size = env->GetArrayLength(model);
        if (size < 1 || size > 2 * 1024 * 1024) throw std::runtime_error("Invalid KWS model size");
        std::vector<uint8_t> bytes(size);
        env->GetByteArrayRegion(model, 0, size, reinterpret_cast<jbyte*>(bytes.data()));
        if (env->ExceptionCheck()) return 0;
        auto session = std::make_shared<Session>(bytes);
        std::lock_guard<std::mutex> lock(sessionsMutex);
        const jlong handle = nextHandle++;
        sessions.emplace(handle, std::move(session));
        return handle;
    } catch (const std::exception& error) { fail(env, error); return 0; }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_aiassistent1_data_provider_MetricKwsNative_infer(JNIEnv* env, jobject, jlong handle, jfloatArray input) {
    try {
        if (!input || env->GetArrayLength(input) != kFeatures) throw std::runtime_error("Invalid KWS input length");
        std::shared_ptr<Session> session;
        {
            std::lock_guard<std::mutex> lock(sessionsMutex);
            auto found = sessions.find(handle);
            if (found == sessions.end()) throw std::runtime_error("KWS session is closed");
            session = found->second;
        }
        std::array<float, kFeatures> features{};
        env->GetFloatArrayRegion(input, 0, kFeatures, features.data());
        if (env->ExceptionCheck()) return nullptr;
        for (float value : features) if (!std::isfinite(value)) throw std::runtime_error("Nonfinite KWS input");
        auto output = session->infer(features);
        features.fill(0.f);
        auto result = env->NewFloatArray(kEmbedding);
        if (result) env->SetFloatArrayRegion(result, 0, kEmbedding, output.data());
        output.fill(0.f);
        return result;
    } catch (const std::exception& error) { fail(env, error); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_aiassistent1_data_provider_MetricKwsNative_close(JNIEnv*, jobject, jlong handle) {
    std::shared_ptr<Session> released;
    {
        std::lock_guard<std::mutex> lock(sessionsMutex);
        const auto found = sessions.find(handle);
        if (found == sessions.end()) return;
        released = std::move(found->second);
        sessions.erase(found);
    } // A concurrent inference retains its session until Run completes.
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_aiassistent1_data_provider_MetricKwsNative_runtimeVersion(JNIEnv* env, jobject) {
    try { return env->NewStringUTF(runtime().version.c_str()); }
    catch (const std::exception& error) { fail(env, error); return nullptr; }
}
