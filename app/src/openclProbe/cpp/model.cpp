#include "report.h"
#include "llama.h"
#include "ggml-cpu.h"
#include <android/log.h>
#include <dlfcn.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <memory>
#include <vector>

using Clock = std::chrono::steady_clock;
static int64_t micros(Clock::time_point since) {
    return std::chrono::duration_cast<std::chrono::microseconds>(Clock::now() - since).count();
}
struct ModelLog {
    ggml_log_callback old = nullptr;
    void * old_data = nullptr;
    std::atomic<int> layers{0};
    std::atomic<int> total_layers{0};
    ModelLog() {
        llama_log_get(&old, &old_data);
        llama_log_set([](ggml_log_level level, const char * message, void * data) {
            int actual = 0, total = 0;
            auto p = strstr(message, "offloaded ");
            if (p && sscanf(p, "offloaded %d/%d layers to GPU", &actual, &total) == 2) {
                static_cast<ModelLog *>(data)->layers.store(actual);
                static_cast<ModelLog *>(data)->total_layers.store(total);
            }
            if (level == GGML_LOG_LEVEL_ERROR || p || strstr(message, "model buffer size ="))
                __android_log_write(ANDROID_LOG_INFO, "AssistantOpenCL", message);
        }, this);
    }
    ~ModelLog() { llama_log_set(old, old_data); }
};

struct OpenClBackend {
    using Mark = void (*)(int);
    using Evidence = const char * (*)(int);
    Mark mark = nullptr;
    Evidence evidence = nullptr;
    ggml_backend_dev_t device = nullptr;
    std::unique_ptr<void, decltype(&dlclose)> plugin{nullptr, dlclose};
    ggml_backend_reg_t owned_registration = nullptr;

    ~OpenClBackend() {
        if (owned_registration) ggml_backend_unload(owned_registration);
    }

    void select() {
        plugin.reset(dlopen("libggml-opencl.so", RTLD_NOW | RTLD_LOCAL));
        if (!plugin) {
            const char * reason = dlerror();
            throw std::runtime_error(reason ? reason : "OpenCL plugin unavailable");
        }
        mark = reinterpret_cast<Mark>(dlsym(plugin.get(), "assistant_cl_phase"));
        evidence = reinterpret_cast<Evidence>(dlsym(plugin.get(), "assistant_cl_evidence"));
        if (!mark || !evidence) throw std::runtime_error("Diagnostic plugin symbols unavailable");
        // llama_supports_gpu_offload() checks registered devices even with an explicit model device list.
        auto reg = ggml_backend_reg_by_name("OpenCL");
        if (!reg) {
            owned_registration = ggml_backend_load("libggml-opencl.so");
            reg = owned_registration;
        }
        if (!reg || strcmp(ggml_backend_reg_name(reg), "OpenCL") != 0)
            throw std::runtime_error("OpenCL registry unavailable");
        for (size_t i = 0; i < ggml_backend_reg_dev_count(reg); ++i) {
            auto dev = ggml_backend_reg_dev_get(reg, i);
            auto type = ggml_backend_dev_type(dev);
            if (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) { device = dev; break; }
        }
        if (!device) throw std::runtime_error("GGML did not accept an OpenCL GPU");
        mark(0);
    }
};

struct PromptRun {
    int64_t prefill_us = 0;
    int64_t decode_us = 0;
    int generated = 0;
    bool reached_eos = false;
    std::string reply;
};

static PromptRun generate(llama_context * ctx, const llama_vocab * vocab, std::vector<llama_token> & input,
        int limit, OpenClBackend * observer, const ProbeReport & report, bool warm) {
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(), llama_sampler_free);
    if (!sampler) throw std::runtime_error("Sampler allocation failed");
    PromptRun result;
    report.set("stage", warm ? "model.warm-prefill" : "model.prefill");
    if (observer) observer->mark(1);
    const auto prefill = Clock::now();
    const size_t batch = llama_n_batch(ctx);
    for (size_t offset = 0; offset < input.size(); offset += batch) {
        const int n = static_cast<int>(std::min(batch, input.size() - offset));
        if (llama_decode(ctx, llama_batch_get_one(input.data() + offset, n))) throw std::runtime_error("Prefill failed");
    }
    llama_synchronize(ctx);
    result.prefill_us = micros(prefill);
    if (observer) {
        report.set("prefillMatrixKernel", observer->evidence(1));
        observer->mark(2);
    }
    report.set("stage", warm ? "model.warm-decode" : "model.decode");
    const auto decode = Clock::now();
    while (result.generated < limit) {
        auto token = llama_sampler_sample(sampler.get(), ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) {
            result.reached_eos = true;
            break;
        }
        std::vector<char> piece(256);
        int size = llama_token_to_piece(vocab, token, piece.data(), static_cast<int>(piece.size()), 0, true);
        if (size < 0) {
            piece.resize(-size);
            size = llama_token_to_piece(vocab, token, piece.data(), static_cast<int>(piece.size()), 0, true);
        }
        if (size < 0) throw std::runtime_error("Token detokenization failed");
        result.reply.append(piece.data(), size);
        ++result.generated;
        if (llama_decode(ctx, llama_batch_get_one(&token, 1))) throw std::runtime_error("Decode failed");
    }
    llama_synchronize(ctx);
    result.decode_us = micros(decode);
    if (result.reply.empty()) throw std::runtime_error("Empty model reply");
    return result;
}

static void inference(const ProbeReport & report, const std::string & path, const std::string & prompt,
        int layers, int limit, bool warm_compare) {
    report.set("stage", "model.backend");
    const auto init_start = Clock::now();
    ModelLog log;
    if (!ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU)) ggml_backend_register(ggml_backend_cpu_reg());
    llama_backend_init();
    OpenClBackend cl;
    if (layers) cl.select();
    report.set("device", cl.device ? ggml_backend_dev_description(cl.device) : "CPU");
    report.set("stage", "model.weights");
    ggml_backend_dev_t devices[] = {cl.device, nullptr};
    auto mp = llama_model_default_params();
    mp.devices = devices; mp.n_gpu_layers = layers; mp.use_mmap = true;
    std::unique_ptr<llama_model, decltype(&llama_model_free)> model(llama_model_load_from_file(path.c_str(), mp), llama_model_free);
    if (!model) throw std::runtime_error("GGUF load failed");
    report.set("offloadedLayers", std::to_string(log.layers.load()));
    if (layers) report.set("totalLayers", std::to_string(log.total_layers.load()));
    if (layers && log.layers.load() == 0) throw std::runtime_error("No layers offloaded");
    report.set("stage", "model.context");
    auto cp = llama_context_default_params();
    cp.n_ctx = 2048; cp.n_batch = 64; cp.n_ubatch = 64; cp.n_seq_max = 1;
    cp.n_rs_seq = 16; cp.n_outputs_max = 1; cp.n_threads = cp.n_threads_batch = 4;
    cp.offload_kqv = cp.op_offload = layers > 0;
    std::unique_ptr<llama_context, decltype(&llama_free)> ctx(llama_init_from_model(model.get(), cp), llama_free);
    if (!ctx) throw std::runtime_error("Context allocation failed");
    report.set("initializationUs", std::to_string(micros(init_start)));
    auto vocab = llama_model_get_vocab(model.get());
    const int expected = -llama_tokenize(vocab, prompt.data(), static_cast<int>(prompt.size()), nullptr, 0, true, true);
    if (expected <= 0 || expected + limit >= static_cast<int>(cp.n_ctx)) throw std::runtime_error("Test prompt exceeds context");
    std::vector<llama_token> input(expected);
    if (llama_tokenize(vocab, prompt.data(), static_cast<int>(prompt.size()), input.data(), expected, true, true) != expected)
        throw std::runtime_error("Tokenization failed");
    report.set("promptTokens", std::to_string(expected));
    auto result = generate(ctx.get(), vocab, input, limit, layers ? &cl : nullptr, report, false);
    report.set("prefillUs", std::to_string(result.prefill_us));
    report.set("decodeUs", std::to_string(result.decode_us));
    report.set("generatedTokens", std::to_string(result.generated));
    report.set("stopReason", result.reached_eos ? "EOS" : "TOKEN_LIMIT");
    report.set("reply", result.reply);
    if (layers) {
        report.set("decodeMatrixKernel", cl.evidence(2));
        if (!*cl.evidence(1) || !*cl.evidence(2)) throw std::runtime_error("Matrix execution on GPU unconfirmed");
        cl.mark(0);
    }
    if (warm_compare) {
        // Reuse model and compiled kernels, clear sequence state, then time the same prompt without event tracing.
        llama_memory_clear(llama_get_memory(ctx.get()), true);
        auto warm = generate(ctx.get(), vocab, input, limit, nullptr, report, true);
        report.set("warmPrefillUs", std::to_string(warm.prefill_us));
        report.set("warmDecodeUs", std::to_string(warm.decode_us));
        report.set("warmGeneratedTokens", std::to_string(warm.generated));
        report.set("warmStopReason", warm.reached_eos ? "EOS" : "TOKEN_LIMIT");
        report.set("warmReplyMatches", warm.reply == result.reply ? "true" : "false");
        if (warm.reply != result.reply) report.set("warmReply", warm.reply);
    }
    report.set("status", "PASS");
}

static std::string utf8(JNIEnv * env, jbyteArray bytes) {
    std::string value(env->GetArrayLength(bytes), '\0');
    env->GetByteArrayRegion(bytes, 0, static_cast<jsize>(value.size()), reinterpret_cast<jbyte *>(value.data()));
    return value;
}
extern "C" JNIEXPORT void JNICALL Java_com_example_aiassistent1_data_engine_OpenClProbeNative_infer(
        JNIEnv * env, jobject, jbyteArray path, jbyteArray prompt, jint layers, jint limit, jboolean warm, jobject sink) {
    ProbeReport report(env, sink);
    try { inference(report, utf8(env, path), utf8(env, prompt), layers, limit, warm); }
    catch (const std::exception & e) { if (!env->ExceptionCheck()) report.set("error", e.what()); }
}
