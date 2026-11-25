#include <jni.h>
#include <dlfcn.h>
#include <atomic>
#include <algorithm>
#include <climits>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <memory>
#include <set>
#include <sys/stat.h>
#include <android/log.h>
#include "third_party/llama_api/llama.h"

static std::atomic<int> actual_gpu_layers{0};
static std::atomic<int> total_gpu_layers{0};
static std::mutex diagnostics_mutex;
static std::string native_errors;
static void inference_log(ggml_log_level level, const char * text, void *) {
    const char * entry = std::strstr(text, "offloaded ");
    int layers = 0, total = 0;
    if (entry && std::sscanf(entry, "offloaded %d/%d layers to GPU", &layers, &total) == 2) {
        actual_gpu_layers.store(layers);
        total_gpu_layers.store(total);
    }
    if (level == GGML_LOG_LEVEL_ERROR) {
        std::string ascii;
        for (const unsigned char * c = reinterpret_cast<const unsigned char *>(text); *c; ++c) {
            ascii += *c < 128 ? static_cast<char>(*c) : '?';
        }
        __android_log_write(ANDROID_LOG_ERROR, "AssistantLlama", ascii.c_str());
        std::lock_guard<std::mutex> lock(diagnostics_mutex);
        if (native_errors.size() < 1024) native_errors.append(ascii, 0, 1024 - native_errors.size());
    }
}

static std::string native_failure(const char * message) {
    std::lock_guard<std::mutex> lock(diagnostics_mutex);
    return native_errors.empty() ? message : std::string(message) + ": " + native_errors;
}

// ABI pinned to the libllama.so shipped in Llamatik 1.10.1; no second inference library.
#define LLAMA_FUNCTIONS(F) \
    F(llama_backend_init) F(llama_model_default_params) F(llama_model_load_from_file) \
    F(llama_model_free) F(llama_model_get_vocab) F(llama_context_default_params) \
    F(llama_init_from_model) F(llama_free) F(llama_n_ctx) F(llama_n_batch) \
    F(llama_get_memory) F(llama_memory_clear) F(llama_tokenize) F(llama_token_to_piece) \
    F(llama_vocab_is_eog) F(llama_batch_init) F(llama_batch_free) F(llama_decode) \
    F(llama_sampler_chain_default_params) F(llama_sampler_chain_init) F(llama_sampler_chain_add) \
    F(llama_sampler_init_penalties) F(llama_sampler_init_top_k) F(llama_sampler_init_top_p) \
    F(llama_sampler_init_temp) F(llama_sampler_init_dist) F(llama_sampler_sample) F(llama_sampler_free) \
    F(llama_sampler_init_greedy) F(llama_model_n_layer) F(llama_log_set)

struct Api {
#define DECLARE(name) decltype(&name) name = nullptr;
    LLAMA_FUNCTIONS(DECLARE)
#undef DECLARE
    template<class T> static T symbol(void * library, const char * name) {
        auto result = reinterpret_cast<T>(dlsym(library, name));
        if (!result) throw std::runtime_error(std::string("Missing llama API: ") + name);
        return result;
    }
    Api() {
        void * ggml = dlopen("libggml.so", RTLD_NOW | RTLD_GLOBAL);
        void * cpu = dlopen("libggml-cpu.so", RTLD_NOW | RTLD_GLOBAL);
        void * llama = dlopen("libllama.so", RTLD_NOW | RTLD_LOCAL);
        if (!ggml || !cpu || !llama) throw std::runtime_error("Cannot load Llamatik native libraries");
        auto cpu_device = symbol<decltype(&ggml_backend_dev_by_type)>(ggml, "ggml_backend_dev_by_type");
        if (!cpu_device(GGML_BACKEND_DEVICE_TYPE_CPU)) {
            auto reg = symbol<decltype(&ggml_backend_cpu_reg)>(cpu, "ggml_backend_cpu_reg");
            symbol<decltype(&ggml_backend_register)>(ggml, "ggml_backend_register")(reg());
        }
#define LOAD(name) name = symbol<decltype(name)>(llama, #name);
        LLAMA_FUNCTIONS(LOAD)
#undef LOAD
        llama_backend_init();
        llama_log_set(inference_log, nullptr);
        // Keep the handles loaded: audio and other clients can share the backend registry.
    }
};

static Api & api() { static Api value; return value; }

static void * ggml_library() {
    api();
    static void * handle = dlopen("libggml.so", RTLD_NOW | RTLD_GLOBAL);
    return handle;
}

// Inspected and used under operation_mutex. Device registration lives in the worker process.
struct GpuPlugin {
    const char * library;
    const char * registry;
    const char * wire_name;
    void * handle = nullptr;
    ggml_backend_dev_t device = nullptr;
    std::string identity, error;
    bool inspected = false;

    void inspect() {
        if (inspected) return;
        handle = dlopen(library, RTLD_NOW | RTLD_LOCAL);
        if (handle) {
            identity = Api::symbol<const char *(*)()>(handle, "assistant_gpu_driver_identity")();
            error = Api::symbol<const char *(*)()>(handle, "assistant_gpu_support_error")();
        } else {
            error = std::string(registry) + " library unavailable: " + dlerror();
        }
        inspected = true;
    }

    ggml_backend_dev_t select() {
        inspect();
        if (!error.empty()) throw std::runtime_error(error);
        if (device) return device;
        void * ggml = ggml_library();
        auto reg = Api::symbol<decltype(&ggml_backend_reg_by_name)>(ggml, "ggml_backend_reg_by_name")(registry);
        if (!reg) reg = Api::symbol<decltype(&ggml_backend_load)>(ggml, "ggml_backend_load")(library);
        if (!reg || strcmp(Api::symbol<decltype(&ggml_backend_reg_name)>(ggml, "ggml_backend_reg_name")(reg), registry))
            throw std::runtime_error(std::string(registry) + " registry unavailable");
        auto count = Api::symbol<decltype(&ggml_backend_reg_dev_count)>(ggml, "ggml_backend_reg_dev_count")(reg);
        auto get = Api::symbol<decltype(&ggml_backend_reg_dev_get)>(ggml, "ggml_backend_reg_dev_get");
        auto type = Api::symbol<decltype(&ggml_backend_dev_type)>(ggml, "ggml_backend_dev_type");
        for (size_t i = 0; i < count; ++i) {
            auto candidate = get(reg, i);
            auto kind = type(candidate);
            if (kind == GGML_BACKEND_DEVICE_TYPE_GPU || kind == GGML_BACKEND_DEVICE_TYPE_IGPU) {
                device = candidate;
                break;
            }
        }
        return device;
    }
};
static GpuPlugin & gpu_plugin(jint backend) {
    static GpuPlugin vulkan{"libggml-vulkan.so", "Vulkan", "VULKAN"};
    static GpuPlugin opencl{"libggml-opencl.so", "OpenCL", "OPENCL"};
    if (backend == 1) return vulkan;
    if (backend == 2) return opencl;
    throw std::invalid_argument("Unknown GPU backend");
}
static std::mutex operation_mutex;
static std::atomic<bool> cancelled{false};
static llama_model * model = nullptr;
static llama_context * context = nullptr;
static llama_sampler * sampler = nullptr;
static std::string model_path;
static std::string model_stamp;
static bool vocabulary_only = false;
static int loaded_gpu_layers = 0;
static GpuPlugin * loaded_plugin = nullptr;
static std::vector<llama_token> history;
static int prompt_count = 0;
static int generated_count = 0;
static bool needs_replay = false;
static llama_token pending_token = LLAMA_TOKEN_NULL;
static std::string pending_output;
static constexpr int SAFETY = 16;
using InferenceClock = std::chrono::steady_clock;
static int64_t initialization_millis = 0;
static int64_t prefill_nanos = 0, decode_nanos = 0, first_token_nanos = -1;
static int64_t decode_steps = 0, prefill_tokens = 0;
static InferenceClock::time_point generation_started;
static std::set<std::string> verified_gpu_models;
static int64_t elapsed_nanos(InferenceClock::time_point start) {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(InferenceClock::now() - start).count();
}

static void free_sampler() { if (sampler) api().llama_sampler_free(sampler); sampler = nullptr; }
static void free_context() { if (context) api().llama_free(context); context = nullptr; }
static void reset() {
    free_sampler(); free_context();
    if (model) {
        api().llama_model_free(model);
        if (!vocabulary_only) __android_log_print(ANDROID_LOG_INFO, "AssistantLlama",
            "Released model weights on %s", loaded_plugin ? loaded_plugin->wire_name : "CPU");
    }
    model = nullptr; model_path.clear(); history.clear();
    vocabulary_only = false; needs_replay = false; prompt_count = generated_count = 0;
    pending_token = LLAMA_TOKEN_NULL;
    pending_output.clear();
    actual_gpu_layers.store(0); total_gpu_layers.store(0);
    model_stamp.clear();
    loaded_gpu_layers = 0;
    loaded_plugin = nullptr;
}

static std::string bytes(JNIEnv * env, jbyteArray input) {
    if (!input) throw std::invalid_argument("Missing UTF-8 input");
    const auto size = env->GetArrayLength(input);
    std::string result(size, '\0');
    if (size) env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte *>(result.data()));
    return result;
}

static void fail(JNIEnv * env, const char * message) {
    if (!env->ExceptionCheck()) {
        jclass type = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(type, message); // All native diagnostics are ASCII, never model output.
        env->DeleteLocalRef(type);
    }
}

static void prepare(const std::string & path, bool vocab_only, int gpu, GpuPlugin * plugin) {
    struct stat file{};
    if (stat(path.c_str(), &file) != 0) throw std::runtime_error("Model file is unavailable");
    const auto stamp = path + ":" + std::to_string(file.st_ino) + ":" + std::to_string(file.st_size)
        + ":" + std::to_string(file.st_mtime) + ":" + std::to_string(file.st_mtim.tv_nsec);
    if (model && model_stamp == stamp && (vocab_only || (!vocabulary_only && loaded_gpu_layers == gpu && loaded_plugin == plugin))) return;
    reset();
    auto params = api().llama_model_default_params();
    params.vocab_only = vocab_only;
    params.use_mmap = true;
    params.use_mlock = false;
    params.n_gpu_layers = vocab_only ? 0 : gpu;
    ggml_backend_dev_t devices[] = { nullptr, nullptr };
    if (plugin) {
        devices[0] = plugin->select();
        if (!devices[0]) throw std::runtime_error(std::string("No compatible GPU for ") + plugin->registry);
    }
    params.devices = devices;
    params.progress_callback = [](float, void *) { return !cancelled.load(); };
    model = api().llama_model_load_from_file(path.c_str(), params);
    if (!model) throw std::runtime_error(native_failure("Model allocation failed"));
    model_path = path; model_stamp = stamp; vocabulary_only = vocab_only; loaded_gpu_layers = gpu;
    loaded_plugin = plugin;
    if (!vocab_only) __android_log_print(ANDROID_LOG_INFO, "AssistantLlama",
        "Loaded model weights on %s, GPU layers: %d", plugin ? plugin->wire_name : "CPU", actual_gpu_layers.load());
    if (total_gpu_layers.load() == 0) total_gpu_layers.store(api().llama_model_n_layer(model) + 1);
}

static std::vector<llama_token> tokenize(const std::string & prompt) {
    if (!model) throw std::runtime_error("Tokenizer is not prepared");
    if (prompt.size() > INT_MAX) throw std::runtime_error("Prompt is too large");
    const auto * vocab = api().llama_model_get_vocab(model);
    int size = api().llama_tokenize(vocab, prompt.data(), static_cast<int>(prompt.size()), nullptr, 0, true, true);
    if (size == INT_MIN) throw std::runtime_error("Tokenization failed");
    size = std::abs(size);
    std::vector<llama_token> result(size);
    const int actual = api().llama_tokenize(vocab, prompt.data(), static_cast<int>(prompt.size()), result.data(), size, true, true);
    if (actual < 0) throw std::runtime_error("Tokenization failed");
    result.resize(actual);
    return result;
}

static bool abort_decode(void *) { return cancelled.load(); }
static bool allocate_context(int size, int batch, int threads) {
    if (!model || vocabulary_only || size < 32 || batch < 1 || threads < 1) return false;
    free_context();
    auto params = api().llama_context_default_params();
    params.n_ctx = size;
    params.n_batch = std::min(batch, size);
    params.n_ubatch = params.n_batch;
    params.n_seq_max = 1;
    // Preserve the verified recurrent allocation contract of Llamatik 1.10.1.
    params.n_rs_seq = 16;
    params.n_outputs_max = 1; // Only the last prefill token needs logits.
    params.n_threads = params.n_threads_batch = threads;
    params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    params.offload_kqv = loaded_gpu_layers != 0;
    params.op_offload = loaded_gpu_layers != 0;
    params.abort_callback = abort_decode;
    params.abort_callback_data = nullptr;
    context = api().llama_init_from_model(model, params);
    return context != nullptr;
}

static bool decode(const std::vector<llama_token> & tokens, int start_position) {
    const int batch_size = static_cast<int>(api().llama_n_batch(context));
    for (size_t start = 0; start < tokens.size(); start += batch_size) {
        if (cancelled.load()) return false;
        const int count = static_cast<int>(std::min(tokens.size() - start, static_cast<size_t>(batch_size)));
        auto batch = api().llama_batch_init(count, 0, 1);
        batch.n_tokens = count;
        for (int i = 0; i < count; ++i) {
            batch.token[i] = tokens[start + i];
            batch.pos[i] = start_position + static_cast<int>(start) + i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = (start + i + 1 == tokens.size());
        }
        const int result = api().llama_decode(context, batch);
        api().llama_batch_free(batch);
        if (result != 0) return false;
    }
    return true;
}

static void verify_gpu_model() {
    if (!loaded_plugin) return;
    if (actual_gpu_layers.load() == 0) throw std::runtime_error("No model layers were offloaded to GPU");
    const auto key = model_stamp + "|" + loaded_plugin->wire_name + "|" + loaded_plugin->identity + "|" + std::to_string(loaded_gpu_layers);
    if (verified_gpu_models.count(key)) return;
    auto tokens = tokenize("Hello");
    if (tokens.empty() || !decode(tokens, 0)) throw std::runtime_error("GPU prefill verification failed");
    using SamplerPtr = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>;
    SamplerPtr probe(api().llama_sampler_init_greedy(), api().llama_sampler_free);
    if (!probe) throw std::runtime_error("GPU probe sampler allocation failed");
    for (int i = 0; i < 4 && !cancelled.load(); ++i) {
        auto token = api().llama_sampler_sample(probe.get(), context, -1);
        if (api().llama_vocab_is_eog(api().llama_model_get_vocab(model), token)) {
            if (i > 0) break;
            token = tokens.back(); // Exercise a one-token decode even when the probe immediately samples EOS.
        }
        if (!decode(std::vector<llama_token>{token}, static_cast<int>(tokens.size()))) {
            throw std::runtime_error("GPU decode verification failed");
        }
        tokens.push_back(token);
    }
    api().llama_memory_clear(api().llama_get_memory(context), true);
    if (!cancelled.load()) verified_gpu_models.insert(key);
}

static jobjectArray strings(JNIEnv * env, const std::vector<std::string> & values) {
    auto type = env->FindClass("java/lang/String");
    auto result = env->NewObjectArray(static_cast<jsize>(values.size()), type, nullptr);
    env->DeleteLocalRef(type);
    for (size_t i = 0; i < values.size() && !env->ExceptionCheck(); ++i) {
        auto value = env->NewStringUTF(values[i].c_str());
        env->SetObjectArrayElement(result, static_cast<jsize>(i), value);
        env->DeleteLocalRef(value);
    }
    return result;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_example_aiassistent1_data_engine_AutomaticLlamaBridge_gpuDriverInfo(JNIEnv * env, jobject, jint backend) {
    std::lock_guard<std::mutex> lock(operation_mutex);
    try {
        auto & plugin = gpu_plugin(backend);
        plugin.inspect();
        return strings(env, {plugin.identity, plugin.error});
    } catch (const std::exception & e) { fail(env, e.what()); return nullptr; }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_example_aiassistent1_data_engine_AutomaticLlamaBridge_gpuDevice(JNIEnv * env, jobject, jint backend) {
    std::lock_guard<std::mutex> lock(operation_mutex);
    try {
        auto & plugin = gpu_plugin(backend);
        auto device = plugin.select();
        if (!device) return strings(env, {});
        void * ggml = ggml_library();
        auto name = Api::symbol<decltype(&ggml_backend_dev_description)>(ggml, "ggml_backend_dev_description")(device);
        size_t free = 0, total = 0;
        Api::symbol<decltype(&ggml_backend_dev_memory)>(ggml, "ggml_backend_dev_memory")(device, &free, &total);
        auto max_buffer = backend == 2 ? std::to_string(Api::symbol<uint64_t (*)(const char *)>(
            plugin.handle, "assistant_opencl_max_allocation")(name)) : "";
        return strings(env, {name, plugin.identity, std::to_string(free), std::to_string(total), max_buffer});
    } catch (const std::exception & e) { fail(env, e.what()); return nullptr; }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_example_aiassistent1_data_engine_AutomaticLlamaBridge_backendInfo(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(operation_mutex);
    try {
    std::string name = "CPU";
    const bool on_gpu = actual_gpu_layers.load() > 0 && loaded_plugin;
    if (on_gpu) {
        name = Api::symbol<decltype(&ggml_backend_dev_description)>(ggml_library(), "ggml_backend_dev_description")(loaded_plugin->device);
    }
    return strings(env, {on_gpu ? loaded_plugin->wire_name : "CPU", name,
        std::to_string(actual_gpu_layers.load()), std::to_string(total_gpu_layers.load()),
        std::to_string(initialization_millis), context ? "1" : "0"});
    } catch (const std::exception & e) { fail(env, e.what()); return nullptr; }
}

// Token pieces can split a UTF-8 code point. Emit only complete byte sequences.
static size_t complete_utf8(const std::string & text) {
    size_t i = 0;
    while (i < text.size()) {
        const auto c = static_cast<unsigned char>(text[i]);
        const size_t width = c < 0x80 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : (c & 0xF8) == 0xF0 ? 4 : 1;
        if (i + width > text.size()) break;
        i += width;
    }
    return i;
}

static bool emit(JNIEnv * env, jobject callback, jmethodID method, std::string & pending, bool flush) {
    const size_t size = flush ? pending.size() : complete_utf8(pending);
    if (!size) return true;
    auto output = env->NewByteArray(static_cast<jsize>(size));
    if (!output) return false;
    env->SetByteArrayRegion(output, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte *>(pending.data()));
    env->CallVoidMethod(callback, method, output);
    env->DeleteLocalRef(output);
    pending.erase(0, size);
    return !env->ExceptionCheck();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_aiassistent1_data_engine_AutomaticLlamaBridge_begin(JNIEnv *, jobject) { cancelled.store(false); }

extern "C" JNIEXPORT void JNICALL
Java_com_example_aiassistent1_data_engine_AutomaticLlamaBridge_prepare(JNIEnv * env, jobject, jbyteArray path) {
    std::lock_guard<std::mutex> lock(operation_mutex);
    try { prepare(bytes(env, path), true, 0, nullptr); } catch (const std::exception & e) { fail(env, e.what()); }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_aiassistent1_data_engine_AutomaticLlamaBridge_count(JNIEnv * env, jobject, jbyteArray prompt) {
    std::lock_guard<std::mutex> lock(operation_mutex);
    try { return static_cast<jint>(tokenize(bytes(env, prompt)).size()); }
    catch (const std::exception & e) { fail(env, e.what()); return -1; }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_aiassistent1_data_engine_AutomaticLlamaBridge_load(JNIEnv * env, jobject, jbyteArray path, jint size, jint batch, jint threads, jint gpu, jint backend) {
    std::lock_guard<std::mutex> lock(operation_mutex);
    try {
        const auto started = InferenceClock::now();
        { std::lock_guard<std::mutex> diagnostics(diagnostics_mutex); native_errors.clear(); }
        if ((gpu == 0) != (backend == 0)) throw std::invalid_argument("GPU layer count and backend disagree");
        prepare(bytes(env, path), false, gpu, gpu ? &gpu_plugin(backend) : nullptr);
        free_sampler(); history.clear(); needs_replay = false;
        if (!allocate_context(size, batch, threads)) {
            if (gpu != 0) throw std::runtime_error(native_failure("GPU context allocation failed"));
            return 0;
        }
        verify_gpu_model();
        initialization_millis = elapsed_nanos(started) / 1000000;
        return static_cast<jint>(api().llama_n_ctx(context));
    } catch (const std::exception & e) { fail(env, e.what()); return 0; }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_aiassistent1_data_engine_AutomaticLlamaBridge_resize(JNIEnv * env, jobject, jint size, jint batch, jint threads) {
    std::lock_guard<std::mutex> lock(operation_mutex);
    try {
        if (!allocate_context(size, batch, threads)) return 0;
        needs_replay = !history.empty();
        return static_cast<jint>(api().llama_n_ctx(context));
    } catch (const std::exception & e) { fail(env, e.what()); return 0; }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_example_aiassistent1_data_engine_AutomaticLlamaBridge_generate(
        JNIEnv * env, jobject, jbyteArray prompt, jboolean continuation, jfloat temperature,
        jfloat top_p, jint top_k, jfloat penalty, jint max_tokens, jobject callback) {
    std::lock_guard<std::mutex> lock(operation_mutex);
    try {
        if (!context) throw std::runtime_error("Context is not loaded");
        const int capacity = static_cast<int>(api().llama_n_ctx(context));
        if (!continuation) {
            generation_started = InferenceClock::now();
            prefill_nanos = decode_nanos = decode_steps = prefill_tokens = 0;
            first_token_nanos = -1;
            history = tokenize(bytes(env, prompt));
            pending_output.clear();
            pending_token = LLAMA_TOKEN_NULL;
            prompt_count = static_cast<int>(history.size()); generated_count = 0;
            if (prompt_count + SAFETY >= capacity) throw std::runtime_error("Prompt exceeds context; truncation is disabled");
            api().llama_memory_clear(api().llama_get_memory(context), true);
            free_sampler();
            sampler = api().llama_sampler_chain_init(api().llama_sampler_chain_default_params());
            if (!sampler) throw std::runtime_error("Sampler allocation failed");
            api().llama_sampler_chain_add(sampler, api().llama_sampler_init_penalties(-1, penalty, 0.0f, 0.10f));
            api().llama_sampler_chain_add(sampler, api().llama_sampler_init_top_k(top_k));
            api().llama_sampler_chain_add(sampler, api().llama_sampler_init_top_p(top_p, 1));
            api().llama_sampler_chain_add(sampler, api().llama_sampler_init_temp(temperature));
            api().llama_sampler_chain_add(sampler, api().llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
            needs_replay = true;
        }
        if (!sampler) throw std::runtime_error("No generation to continue");
        if (needs_replay) {
            if (static_cast<int>(history.size()) + SAFETY >= capacity) throw std::runtime_error("Continuation exceeds context");
            api().llama_memory_clear(api().llama_get_memory(context), true);
            const auto started = InferenceClock::now();
            if (!decode(history, 0) && !cancelled.load()) throw std::runtime_error("Prompt decode failed");
            prefill_nanos += elapsed_nanos(started);
            prefill_tokens += static_cast<int64_t>(history.size());
            needs_replay = false;
        }
        auto callback_class = env->GetObjectClass(callback);
        auto delta = env->GetMethodID(callback_class, "onBytes", "([B)V");
        env->DeleteLocalRef(callback_class);
        if (!delta) return nullptr;
        const auto * vocab = api().llama_model_get_vocab(model);
        int reason = 1; // context limit, never the initial answer reserve
        while (true) {
            if (cancelled.load()) { reason = 2; break; }
            if (max_tokens > 0 && generated_count >= max_tokens) { reason = 3; break; }
            const auto token = pending_token != LLAMA_TOKEN_NULL ? pending_token : api().llama_sampler_sample(sampler, context, -1);
            pending_token = LLAMA_TOKEN_NULL;
            if (api().llama_vocab_is_eog(vocab, token)) { reason = 0; break; }
            // EOS needs no KV slot. If it is not EOS, retain this exact sampled token across resize.
            if (static_cast<int>(history.size()) >= capacity - SAFETY) {
                pending_token = token;
                break;
            }
            std::vector<char> piece(256);
            int length = api().llama_token_to_piece(vocab, token, piece.data(), static_cast<int>(piece.size()), 0, true);
            if (length < 0) {
                piece.resize(-length);
                length = api().llama_token_to_piece(vocab, token, piece.data(), static_cast<int>(piece.size()), 0, true);
            }
            if (length < 0) throw std::runtime_error("Token decoding failed");
            const auto started = InferenceClock::now();
            if (!decode(std::vector<llama_token>{token}, static_cast<int>(history.size()))) {
                if (cancelled.load()) { reason = 2; break; }
                throw std::runtime_error("Generation decode failed");
            }
            decode_nanos += elapsed_nanos(started);
            ++decode_steps;
            if (first_token_nanos < 0) first_token_nanos = elapsed_nanos(generation_started);
            history.push_back(token); ++generated_count;
            pending_output.append(piece.data(), length);
            if (!emit(env, callback, delta, pending_output, false)) return nullptr;
        }
        if (cancelled.load()) reason = 2;
        if (reason != 1 && !emit(env, callback, delta, pending_output, true)) return nullptr;
        if (reason != 1) free_sampler();
        const jlong values[] = {reason, prompt_count, generated_count, capacity,
            prefill_nanos, decode_nanos, first_token_nanos, decode_steps, prefill_tokens};
        auto result = env->NewLongArray(9);
        if (result) env->SetLongArrayRegion(result, 0, 9, values);
        return result;
    } catch (const std::exception & e) { fail(env, e.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_aiassistent1_data_engine_AutomaticLlamaBridge_cancel(JNIEnv *, jobject) { cancelled.store(true); }

extern "C" JNIEXPORT void JNICALL
Java_com_example_aiassistent1_data_engine_AutomaticLlamaBridge_shutdown(JNIEnv * env, jobject) {
    cancelled.store(true);
    std::lock_guard<std::mutex> lock(operation_mutex);
    try { reset(); } catch (const std::exception & e) { fail(env, e.what()); }
}
