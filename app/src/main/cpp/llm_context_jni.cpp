#include <jni.h>
#include <dlfcn.h>
#include <climits>
#include <cstring>
#include <cstdlib>
#include <cerrno>
#include <mutex>
#include <stdexcept>
#include <string>
#include "llama_tokenizer_abi.h"

namespace {
std::mutex mutex;
struct Api {
    void *library = nullptr;
    llama_model_params (*defaults)() = nullptr;
    llama_model *(*load)(const char *, llama_model_params) = nullptr;
    void (*freeModel)(llama_model *) = nullptr;
    const llama_vocab *(*vocab)(const llama_model *) = nullptr;
    int32_t (*contextLimit)(const llama_model *) = nullptr;
    int32_t (*metadata)(const llama_model *, const char *, char *, size_t) = nullptr;
    int32_t (*tokenize)(const llama_vocab *, const char *, int32_t, int32_t *, int32_t, bool, bool) = nullptr;

    template<class T> T symbol(const char *name) {
        auto result = reinterpret_cast<T>(dlsym(library, name));
        if (!result) throw std::runtime_error("Llamatik tokenizer ABI is unavailable");
        return result;
    }
    void init() {
        if (tokenize) return;
        if (!library) library = dlopen("libllama.so", RTLD_NOW | RTLD_LOCAL);
        if (!library) throw std::runtime_error("Cannot open Llamatik's libllama.so");
        defaults = symbol<decltype(defaults)>("llama_model_default_params");
        load = symbol<decltype(load)>("llama_model_load_from_file");
        freeModel = symbol<decltype(freeModel)>("llama_model_free");
        vocab = symbol<decltype(vocab)>("llama_model_get_vocab");
        contextLimit = symbol<decltype(contextLimit)>("llama_model_n_ctx_train");
        metadata = symbol<decltype(metadata)>("llama_model_meta_val_str");
        tokenize = symbol<decltype(tokenize)>("llama_tokenize");
    }
} api;
llama_model *vocabularyModel = nullptr;
std::string vocabularyPath;

int32_t modelContextLimit() {
    const auto loadedLimit = api.contextLimit(vocabularyModel);
    if (loadedLimit > 0) return loadedLimit;
    // При vocab_only гиперпараметры не загружаются; берём предел из исходных метаданных GGUF.
    char architecture[128] = {};
    const auto architectureSize = api.metadata(vocabularyModel, "general.architecture", architecture, sizeof(architecture));
    if (architectureSize <= 0 || architectureSize >= static_cast<int32_t>(sizeof(architecture)))
        throw std::runtime_error("GGUF architecture metadata is unavailable");
    const std::string key = std::string(architecture) + ".context_length";
    char number[32] = {};
    const auto numberSize = api.metadata(vocabularyModel, key.c_str(), number, sizeof(number));
    if (numberSize <= 0 || numberSize >= static_cast<int32_t>(sizeof(number)))
        throw std::runtime_error("GGUF context_length metadata is unavailable");
    errno = 0;
    char *end = nullptr;
    const auto parsed = std::strtoll(number, &end, 10);
    if (errno || end == number || *end != '\0' || parsed <= 0 || parsed > INT_MAX)
        throw std::runtime_error("Invalid GGUF context_length metadata");
    return static_cast<int32_t>(parsed);
}

void release() {
    if (vocabularyModel) api.freeModel(vocabularyModel);
    vocabularyModel = nullptr;
    vocabularyPath.clear();
}

// Повторяем GetStringUTFChars из LlamaBridge.nativeGenerateStream, включая символы вне BMP.
struct JniString {
    JNIEnv *env;
    jstring value;
    const char *bytes;
    JniString(JNIEnv *env, jstring value) : env(env), value(value), bytes(nullptr) {
        if (!value) throw std::runtime_error("Missing tokenizer input");
        bytes = env->GetStringUTFChars(value, nullptr);
        if (!bytes) throw std::runtime_error("Cannot read tokenizer input");
    }
    ~JniString() { if (bytes) env->ReleaseStringUTFChars(value, bytes); }
};
void report(JNIEnv *env, const char *message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}

int32_t countTokens(const char *bytes, bool addSpecial) {
    const auto length = std::strlen(bytes);
    if (length > INT_MAX) throw std::runtime_error("Tokenizer input is too large");
    const auto result = api.tokenize(api.vocab(vocabularyModel), bytes,
        static_cast<int32_t>(length), nullptr, 0, addSpecial, true);
    if (result == INT_MIN) throw std::runtime_error("Tokenizer failed");
    return result < 0 ? -result : result;
}
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_example_aiassistent1_data_engine_NativePromptTokenizer_inspect(
    JNIEnv *env, jobject, jstring path, jstring prompt, jstring userMessageForSizing) {
    std::lock_guard<std::mutex> lock(mutex);
    try {
        api.init();
        JniString modelPath(env, path);
        JniString text(env, prompt);
        if (!vocabularyModel || vocabularyPath != modelPath.bytes) {
            release();
            auto params = api.defaults();
            params.vocab_only = true;
            params.n_gpu_layers = 0;
            params.use_mlock = false;
            vocabularyModel = api.load(modelPath.bytes, params);
            if (!vocabularyModel) throw std::runtime_error("Cannot load GGUF vocabulary");
            vocabularyPath = modelPath.bytes;
        }
        // Приложение формирует ChatML; Llamatik распознаёт его и не меняет перед токенизацией.
        // true/true точно повторяют add_special / parse_special в llama_generate_stream.
        const auto promptTokens = countTokens(text.bytes, true);
        jint messageTokens = -1;
        if (userMessageForSizing) {
            JniString message(env, userMessageForSizing);
            // Текст без шаблона и автоматических BOS/EOS нужен только для выбора режима.
            // Его число не прибавляется к уже посчитанному полному промпту.
            messageTokens = countTokens(message.bytes, false);
        }
        const jint values[] = {promptTokens, modelContextLimit(), messageTokens};
        auto array = env->NewIntArray(3);
        if (array) env->SetIntArrayRegion(array, 0, 3, values);
        return array;
    } catch (const std::exception &error) {
        report(env, error.what());
    } catch (...) {
        report(env, "Tokenizer failed");
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_aiassistent1_data_engine_NativePromptTokenizer_close(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(mutex);
    release();
}
