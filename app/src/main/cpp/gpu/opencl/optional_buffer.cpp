#define CL_TARGET_OPENCL_VERSION 300
#include <CL/cl.h>
#include <dlfcn.h>

// This pinned GGML revision calls the 3.0 API only after a failed ordinary allocation
// and an explicit request for Qualcomm large buffers. Resolve it only on that path.
extern "C" cl_mem __wrap_clCreateBufferWithProperties(cl_context context, const cl_mem_properties * properties,
        cl_mem_flags flags, size_t bytes, void * host, cl_int * error) {
    static const auto create = [] {
        void * driver = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
        return driver ? reinterpret_cast<decltype(&clCreateBufferWithProperties)>(
            dlsym(driver, "clCreateBufferWithProperties")) : nullptr;
    }();
    if (create) return create(context, properties, flags, bytes, host, error);
    if (!properties || !properties[0]) return clCreateBuffer(context, flags, bytes, host, error);
    if (error) *error = CL_INVALID_OPERATION;
    return nullptr;
}
