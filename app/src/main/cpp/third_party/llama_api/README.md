# Pinned llama.cpp ABI

These declarations are used with the existing native libraries in **Llamatik 1.10.1**.
No second set of inference libraries is packaged.

- `llama.h`: audited Llamatik header, SHA-256
  `56b0a7b4de7a20a07e0faf8a5a8c7b85b86fa3a1367385dc98cafe1a6dc7ad46`.
- GGML declarations and license: llama.cpp revision
  `961e9a3e46ca4cf7e6e86cfceb5b5e32084bf5f0`, the `llama.cpp` gitlink
  recorded by the official Llamatik `v1.10.1` release. The audited `llama.h`
  exactly matches that revision too.
- The bridge resolves only public C functions. The model/context parameter structs
  must match the packaged Llamatik ABI; re-audit them when upgrading the dependency.
- Native text crosses JNI as UTF-8 byte arrays, never Modified UTF-8 strings.
