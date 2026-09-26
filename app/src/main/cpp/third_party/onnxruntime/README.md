# ONNX Runtime C API header only

Source: https://github.com/microsoft/onnxruntime/blob/v1.22.1/include/onnxruntime/core/session/onnxruntime_c_api.h

Unmodified header; SHA-256 `d683537d0fdc29e977b5520f7f15d87a0ac212ae6d94fa9be8893a52655621ae`.
MIT license in `LICENSE`. API version 22 is requested explicitly; a runtime which does
not support it fails closed. No ORT binary or AAR is added by this directory.

The experimental JNI bridge opens `libonnxruntime.so` already packaged by
`sherpa-onnx-1.13.4.aar`. Its actual version is queried at runtime in instrumentation.
No `pickFirst`, replacement runtime or extra Maven dependency is used.
