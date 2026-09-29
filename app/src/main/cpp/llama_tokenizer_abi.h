// Минимальный C ABI из llama.cpp 961e9a3e46ca4cf7e6e86cfceb5b5e32084bf5f0.
// Это закреплённый подмодуль Llamatik v1.10.1; лицензия: third_party/llama_tokenizer/LICENSE.
// При обновлении зависимости необходимо сверить структуру и флаги токенизации.
#pragma once
#include <cstdint>

struct llama_model;
struct llama_vocab;
struct ggml_backend_device;
struct llama_model_tensor_buft_override;
struct llama_model_kv_override;
enum llama_split_mode { LLAMA_SPLIT_MODE_NONE = 0, LLAMA_SPLIT_MODE_LAYER = 1, LLAMA_SPLIT_MODE_ROW = 2 };
using llama_progress_callback = bool (*)(float, void *);
struct llama_model_params {
    ggml_backend_device **devices;
    const llama_model_tensor_buft_override *tensor_buft_overrides;
    int32_t n_gpu_layers;
    llama_split_mode split_mode;
    int32_t main_gpu;
    const float *tensor_split;
    llama_progress_callback progress_callback;
    void *progress_callback_user_data;
    const llama_model_kv_override *kv_overrides;
    bool vocab_only;
    bool use_mmap;
    bool use_direct_io;
    bool use_mlock;
    bool check_tensors;
    bool use_extra_bufts;
    bool no_host;
    bool no_alloc;
};
static_assert(sizeof(llama_model_params) == (sizeof(void *) == 8 ? 72 : 44));
