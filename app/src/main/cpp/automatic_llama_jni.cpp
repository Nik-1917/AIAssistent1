#include <jni.h>
#include <dlfcn.h>
#include <atomic>
#include <algorithm>
#include <climits>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include "third_party/llama_api/llama.h"

// ABI pinned to the libllama.so shipped in Llamatik 1.10.1; no second inference library.
#define LLAMA_FUNCTIONS(F) \
    F(llama_backend_init) F(llama_model_default_params) F(llama_model_load_from_file) \
    F(llama_model_free) F(llama_model_get_vocab) F(llama_context_default_params) \
    F(llama_init_from_model) F(llama_free) F(llama_n_ctx) F(llama_n_batch) \
    F(llama_get_memory) F(llama_memory_clear) F(llama_tokenize) F(llama_token_to_piece) \
    F(llama_vocab_is_eog) F(llama_batch_init) F(llama_batch_free) F(llama_decode) \
    F(llama_sampler_chain_default_params) F(llama_sampler_chain_init) F(llama_sampler_chain_add) \
    F(llama_sampler_init_penalties) F(llama_sampler_init_top_k) F(llama_sampler_init_top_p) \
    F(llama_sampler_init_temp) F(llama_sampler_init_dist) F(llama_sampler_sample) F(llama_sampler_free)

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
        auto count = symbol<decltype(&ggml_backend_dev_count)>(ggml, "ggml_backend_dev_count");
        if (count() == 0) {
            auto reg = symbol<decltype(&ggml_backend_cpu_reg)>(cpu, "ggml_backend_cpu_reg");
            symbol<decltype(&ggml_backend_register)>(ggml, "ggml_backend_register")(reg());
        }
#define LOAD(name) name = symbol<decltype(name)>(llama, #name);
        LLAMA_FUNCTIONS(LOAD)
#undef LOAD
        llama_backend_init();
        // Keep the handles loaded: audio and other clients can share the backend registry.
    }
};

static Api & api() { static Api value; return value; }
static std::mutex operation_mutex;
static std::atomic<bool> cancelled{false};
static llama_model * model = nullptr;
static llama_context * context = nullptr;
static llama_sampler * sampler = nullptr;
static std::string model_path;
static bool vocabulary_only = false;
static int loaded_gpu_layers = 0;
static std::vector<llama_token> history;
static int prompt_count = 0;
static int generated_count = 0;
static bool needs_replay = false;
static llama_token pending_token = LLAMA_TOKEN_NULL;
static std::string pending_output;
static constexpr int SAFETY = 16;

static void free_sampler() { if (sampler) api().llama_sampler_free(sampler); sampler = nullptr; }
static void free_context() { if (context) api().llama_free(context); context = nullptr; }
static void reset() {
    free_sampler(); free_context();
    if (model) api().llama_model_free(model);
    model = nullptr; model_path.clear(); history.clear();
    vocabulary_only = false; needs_replay = false; prompt_count = generated_count = 0;
    pending_token = LLAMA_TOKEN_NULL;
    pending_output.clear();
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

static void prepare(const std::string & path, bool vocab_only, int gpu) {
    if (model && model_path == path && (vocab_only || (!vocabulary_only && loaded_gpu_layers == gpu))) return;
    reset();
    auto params = api().llama_model_default_params();
    params.vocab_only = vocab_only;
    params.use_mmap = true;
    params.use_mlock = false;
    params.n_gpu_layers = vocab_only ? 0 : gpu;
    params.progress_callback = [](float, void *) { return !cancelled.load(); };
    model = api().llama_model_load_from_file(path.c_str(), params);
    if (!model) throw std::runtime_error("Model allocation failed");
    model_path = path; vocabulary_only = vocab_only; loaded_gpu_layers = gpu;
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
    try { prepare(bytes(env, path), true, 0); } catch (const std::exception & e) { fail(env, e.what()); }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_aiassistent1_data_engine_AutomaticLlamaBridge_count(JNIEnv * env, jobject, jbyteArray prompt) {
    std::lock_guard<std::mutex> lock(operation_mutex);
    try { return static_cast<jint>(tokenize(bytes(env, prompt)).size()); }
    catch (const std::exception & e) { fail(env, e.what()); return -1; }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_aiassistent1_data_engine_AutomaticLlamaBridge_load(JNIEnv * env, jobject, jbyteArray path, jint size, jint batch, jint threads, jint gpu) {
    std::lock_guard<std::mutex> lock(operation_mutex);
    try {
        prepare(bytes(env, path), false, gpu);
        free_sampler(); history.clear(); needs_replay = false;
        if (!allocate_context(size, batch, threads)) return 0;
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
        jfloat top_p, jint top_k, jfloat penalty, jobject callback) {
    std::lock_guard<std::mutex> lock(operation_mutex);
    try {
        if (!context) throw std::runtime_error("Context is not loaded");
        const int capacity = static_cast<int>(api().llama_n_ctx(context));
        if (!continuation) {
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
            if (!decode(history, 0) && !cancelled.load()) throw std::runtime_error("Prompt decode failed");
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
            if (!decode(std::vector<llama_token>{token}, static_cast<int>(history.size()))) {
                if (cancelled.load()) { reason = 2; break; }
                throw std::runtime_error("Generation decode failed");
            }
            history.push_back(token); ++generated_count;
            pending_output.append(piece.data(), length);
            if (!emit(env, callback, delta, pending_output, false)) return nullptr;
        }
        if (cancelled.load()) reason = 2;
        if (reason != 1 && !emit(env, callback, delta, pending_output, true)) return nullptr;
        if (reason != 1) free_sampler();
        const jlong values[] = {reason, prompt_count, generated_count, capacity};
        auto result = env->NewLongArray(4);
        if (result) env->SetLongArrayRegion(result, 0, 4, values);
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
