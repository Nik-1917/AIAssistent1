include(FetchContent)

# This is the GGML revision used by the audited Llamatik 1.10.1 ABI.
# Build only its optional Vulkan plugin. The base/CPU/llama libraries come from
# the existing AAR, so there is one backend registry in the process.
set(ASSISTANT_GPU_CACHE "${CMAKE_CURRENT_LIST_DIR}/../../../../../build/llama-gpu")
file(MAKE_DIRECTORY "${ASSISTANT_GPU_CACHE}")

function(assistant_gpu_source name archive url checksum)
    string(SUBSTRING "${checksum}" 0 8 source_revision)
    set(source_dir "${ASSISTANT_GPU_CACHE}/src-${name}-${source_revision}")
    # Never replace files while another ABI is compiling its shaders.
    if(EXISTS "${source_dir}/.assistant-source-complete")
        set(${name}_SOURCE_DIR "${source_dir}" PARENT_SCOPE)
        return()
    endif()
    set(local_archive "${ASSISTANT_GPU_CACHE}/${archive}")
    if(EXISTS "${local_archive}")
        set(url "${local_archive}")
    endif()
    FetchContent_Declare(${name}
        URL "${url}"
        URL_HASH "SHA256=${checksum}"
        SUBBUILD_DIR "${ASSISTANT_GPU_CACHE}/populate-${name}-${source_revision}"
        BINARY_DIR "${ASSISTANT_GPU_CACHE}/unbuilt-${name}-${source_revision}"
        SOURCE_DIR "${source_dir}")
    FetchContent_GetProperties(${name})
    if(NOT ${name}_POPULATED)
        FetchContent_Populate(${name})
    endif()
    file(WRITE "${source_dir}/.assistant-source-complete" "${checksum}\n")
    set(${name}_SOURCE_DIR "${${name}_SOURCE_DIR}" PARENT_SCOPE)
endfunction()

file(LOCK "${ASSISTANT_GPU_CACHE}/sources.lock" GUARD FILE TIMEOUT 600)
assistant_gpu_source(assistant_ggml llama-961e9a3e.tar.gz
    "https://codeload.github.com/ggml-org/llama.cpp/tar.gz/961e9a3e46ca4cf7e6e86cfceb5b5e32084bf5f0"
    e30caabcde296788d7113f8e5985282753825bfa75d1ae8f3db419a803e45ea1)
assistant_gpu_source(assistant_spirv spirv-headers.tar.gz
    "https://codeload.github.com/KhronosGroup/SPIRV-Headers/tar.gz/refs/tags/vulkan-sdk-1.4.321.0"
    5bbea925663d4cd2bab23efad53874f2718248a73dcaf9dd21dff8cb48e602fc)
assistant_gpu_source(assistant_vulkan vulkan-headers.tar.gz
    "https://codeload.github.com/KhronosGroup/Vulkan-Headers/tar.gz/refs/tags/vulkan-sdk-1.4.321.0"
    17f8ff30fd79fb7531efcb7c78c02c17a595208d482a150f06836b0ca97ef8f2)

set(ggml_source "${assistant_ggml_SOURCE_DIR}/ggml")
foreach(header ggml.h ggml-backend.h ggml-cpu.h ggml-alloc.h ggml-opt.h)
    file(READ "${CMAKE_CURRENT_LIST_DIR}/../third_party/llama_api/${header}" audited)
    file(READ "${ggml_source}/include/${header}" downloaded)
    string(REPLACE "\r\n" "\n" audited "${audited}")
    string(REPLACE "\r\n" "\n" downloaded "${downloaded}")
    if(NOT audited STREQUAL downloaded)
        message(FATAL_ERROR "GPU plugin ABI differs from audited ${header}")
    endif()
endforeach()

# Upstream uses pointer-sized Vulkan handles in two places. On 32-bit Android,
# non-dispatchable handles are uint64_t instead. Keep the C++ wrappers for the
# copy command and explicitly convert only the diagnostic handle values.
set(vulkan_source "${ggml_source}/src/ggml-vulkan/ggml-vulkan.cpp")
file(READ "${vulkan_source}" vulkan_content)
set(original_vulkan_content "${vulkan_content}")
string(REPLACE "<< buf->buffer" "<< (uint64_t)(VkBuffer)buf->buffer" vulkan_content "${vulkan_content}")
string(REPLACE "copyBuffer((VkBuffer)staging_buffer->buffer, (VkBuffer)dst->buffer, slices)"
    "copyBuffer(staging_buffer->buffer, dst->buffer, slices)" vulkan_content "${vulkan_content}")
if(NOT vulkan_content STREQUAL original_vulkan_content)
    file(WRITE "${vulkan_source}" "${vulkan_content}")
endif()

# Android's NDK supplies the host shader compiler and the Vulkan loader stub.
set(Vulkan_INCLUDE_DIR "${assistant_vulkan_SOURCE_DIR}/include" CACHE PATH "" FORCE)
find_library(assistant_vulkan_loader vulkan REQUIRED)
set(Vulkan_LIBRARY "${assistant_vulkan_loader}" CACHE FILEPATH "" FORCE)
if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
    set(shader_host windows-x86_64)
    set(shader_suffix .exe)
elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
    set(shader_host darwin-x86_64)
else()
    set(shader_host linux-x86_64)
endif()
set(Vulkan_GLSLC_EXECUTABLE "${ANDROID_NDK}/shader-tools/${shader_host}/glslc${shader_suffix}" CACHE FILEPATH "" FORCE)

add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)
set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES
    INTERFACE_INCLUDE_DIRECTORIES "${assistant_spirv_SOURCE_DIR}/include")
file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/spirv-config")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/spirv-config/SPIRV-HeadersConfig.cmake" "set(SPIRV-Headers_FOUND TRUE)\n")
set(SPIRV-Headers_DIR "${CMAKE_CURRENT_BINARY_DIR}/spirv-config")

function(ggml_add_backend_library target)
    add_library(${target} SHARED ${ARGN})
    target_compile_features(${target} PRIVATE cxx_std_17)
    target_compile_definitions(${target} PRIVATE GGML_BACKEND_BUILD GGML_BACKEND_DL GGML_SHARED)
    target_include_directories(${target} PRIVATE "${ggml_source}/include" "${ggml_source}/src")
    target_link_libraries(${target} PRIVATE SPIRV-Headers::SPIRV-Headers)
    target_link_libraries(${target} PRIVATE "${ASSISTANT_GGML_PREBUILT_DIR}/jni/${ANDROID_ABI}/libggml-base.so")
endfunction()

# Use MSBuild's host toolchain on Windows without requiring Android Studio to
# inherit a Visual Studio developer shell. Shader sources/kernels are unchanged.
if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
    set(vswhere "$ENV{ProgramFiles\(x86\)}/Microsoft Visual Studio/Installer/vswhere.exe")
    execute_process(COMMAND "${vswhere}" -latest -products *
        -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64
        -property installationPath OUTPUT_VARIABLE vs_path OUTPUT_STRIP_TRAILING_WHITESPACE)
    file(GLOB host_compilers "${vs_path}/VC/Tools/MSVC/*/bin/Hostx64/x64/cl.exe")
    list(SORT host_compilers COMPARE NATURAL ORDER DESCENDING)
    list(GET host_compilers 0 HOST_C_COMPILER)
    set(HOST_CXX_COMPILER "${HOST_C_COMPILER}")
    file(TO_CMAKE_PATH "${HOST_C_COMPILER}" HOST_C_COMPILER)
    file(TO_CMAKE_PATH "${HOST_CXX_COMPILER}" HOST_CXX_COMPILER)
    if(vs_path MATCHES "2019")
        set(host_generator "Visual Studio 16 2019")
    else()
        set(host_generator "Visual Studio 17 2022")
    endif()
    set(host_toolchain_template "${ggml_source}/src/ggml-vulkan/cmake/host-toolchain.cmake.in")
    set(host_toolchain_content "set(CMAKE_BUILD_TYPE Release)\nset(CMAKE_C_COMPILER \"@HOST_C_COMPILER@\")\nset(CMAKE_CXX_COMPILER \"@HOST_CXX_COMPILER@\")\n")
    file(READ "${host_toolchain_template}" old_host_toolchain)
    if(NOT old_host_toolchain STREQUAL host_toolchain_content)
        file(WRITE "${host_toolchain_template}" "${host_toolchain_content}")
    endif()
    set(vulkan_cmake "${ggml_source}/src/ggml-vulkan/CMakeLists.txt")
    file(READ "${vulkan_cmake}" vulkan_build)
    if(NOT vulkan_build MATCHES "CMAKE_GENERATOR_PLATFORM x64")
        string(REPLACE "SOURCE_DIR \${CMAKE_CURRENT_SOURCE_DIR}/vulkan-shaders"
            "CMAKE_GENERATOR \"${host_generator}\"\n        CMAKE_GENERATOR_PLATFORM x64\n        SOURCE_DIR \${CMAKE_CURRENT_SOURCE_DIR}/vulkan-shaders"
            vulkan_build "${vulkan_build}")
        file(WRITE "${vulkan_cmake}" "${vulkan_build}")
    endif()
endif()
file(LOCK "${ASSISTANT_GPU_CACHE}/sources.lock" RELEASE)
add_subdirectory("${ggml_source}/src/ggml-vulkan" "${CMAKE_CURRENT_BINARY_DIR}/vulkan-961e9a3e")
target_sources(ggml-vulkan PRIVATE "${CMAKE_CURRENT_LIST_DIR}/driver_info.cpp")
