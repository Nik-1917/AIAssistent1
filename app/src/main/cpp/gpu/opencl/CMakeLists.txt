# The ICD is a link-time library. Keep its build outside the APK's CMake target graph.
file(LOCK "${ASSISTANT_GPU_CACHE}/sources.lock" GUARD FILE TIMEOUT 600)
assistant_gpu_source(cl_headers opencl-headers-2025.07.22.tar.gz
    "https://codeload.github.com/KhronosGroup/OpenCL-Headers/tar.gz/refs/tags/v2025.07.22"
    98f0a3ea26b4aec051e533cb1750db2998ab8e82eda97269ed6efe66ec94a240)
assistant_gpu_source(cl_icd opencl-icd-loader-2025.07.22.tar.gz
    "https://codeload.github.com/KhronosGroup/OpenCL-ICD-Loader/tar.gz/refs/tags/v2025.07.22"
    dff7a0b11ad5b63a669358e3476e3dc889a4a361674e5b69b267b944d0794142)
file(LOCK "${ASSISTANT_GPU_CACHE}/sources.lock" RELEASE)
set(link_dir "${CMAKE_CURRENT_BINARY_DIR}/link-only")
execute_process(COMMAND "${CMAKE_COMMAND}" -S "${cl_icd_SOURCE_DIR}" -B "${link_dir}" -G Ninja
    "-DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}" "-DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}"
    "-DANDROID_ABI=${ANDROID_ABI}" "-DANDROID_PLATFORM=android-${ANDROID_PLATFORM_LEVEL}"
    "-DOPENCL_ICD_LOADER_HEADERS_DIR=${cl_headers_SOURCE_DIR}" -DOPENCL_ICD_LOADER_BUILD_SHARED_LIBS=ON
    -DOPENCL_ICD_LOADER_BUILD_TESTING=OFF -DENABLE_OPENCL_LAYERS=OFF -DCMAKE_BUILD_TYPE=Release
    COMMAND_ERROR_IS_FATAL ANY)
execute_process(COMMAND "${CMAKE_COMMAND}" --build "${link_dir}" --target OpenCL COMMAND_ERROR_IS_FATAL ANY)
set(OpenCL_INCLUDE_DIR "${cl_headers_SOURCE_DIR}" CACHE PATH "" FORCE)
set(OpenCL_LIBRARY "${link_dir}/libOpenCL.so" CACHE FILEPATH "" FORCE)
set(GGML_OPENCL_TARGET_VERSION 300)
set(GGML_OPENCL_EMBED_KERNELS ON)
set(GGML_OPENCL_USE_ADRENO_KERNELS ON)
add_subdirectory("${ggml_source}/src/ggml-opencl" backend)
target_sources(ggml-opencl PRIVATE driver_info.cpp optional_buffer.cpp)
target_link_options(ggml-opencl PRIVATE "-Wl,--wrap=clCreateBufferWithProperties")
target_link_libraries(ggml-opencl PRIVATE log dl)

if(ASSISTANT_OPENCL_DIAGNOSTIC AND CMAKE_BUILD_TYPE STREQUAL "Debug")
    set(probe_dir "${CMAKE_CURRENT_LIST_DIR}/../../../../openclProbe/cpp")
    target_sources(ggml-opencl PRIVATE "${probe_dir}/trace.cpp")
    target_link_options(ggml-opencl PRIVATE "-Wl,--wrap=clBuildProgram" "-Wl,--wrap=clEnqueueNDRangeKernel")
    set(core_dir "${ASSISTANT_GGML_PREBUILT_DIR}/jni/${ANDROID_ABI}")
    add_library(assistant_opencl_probe SHARED "${probe_dir}/driver.cpp" "${probe_dir}/model.cpp")
    target_compile_features(assistant_opencl_probe PRIVATE cxx_std_17)
    target_include_directories(assistant_opencl_probe PRIVATE "${cl_headers_SOURCE_DIR}" "${ggml_source}/include"
        "${assistant_ggml_SOURCE_DIR}/include")
    target_link_libraries(assistant_opencl_probe PRIVATE dl log "${core_dir}/libllama.so" "${core_dir}/libggml.so"
        "${core_dir}/libggml-base.so" "${core_dir}/libggml-cpu.so")
endif()
