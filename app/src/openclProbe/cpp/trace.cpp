#define CL_TARGET_OPENCL_VERSION 300
#include <CL/cl.h>
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

// Diagnostic plugin only. Capture one completed model matrix kernel per phase.
static std::atomic<int> phase{0};
static std::mutex trace_mutex;
static std::string evidence[3];

extern "C" __attribute__((visibility("default"))) void assistant_cl_phase(int value) {
    std::lock_guard<std::mutex> lock(trace_mutex);
    if (value == 0) { evidence[1].clear(); evidence[2].clear(); }
    phase.store(value);
}

extern "C" __attribute__((visibility("default"))) const char * assistant_cl_evidence(int which) {
    std::lock_guard<std::mutex> lock(trace_mutex);
    static thread_local std::string snapshot;
    snapshot = evidence[which];
    return snapshot.c_str();
}

extern "C" cl_int __real_clEnqueueNDRangeKernel(cl_command_queue, cl_kernel, cl_uint, const size_t *,
    const size_t *, const size_t *, cl_uint, const cl_event *, cl_event *);
extern "C" cl_int __wrap_clEnqueueNDRangeKernel(cl_command_queue queue, cl_kernel kernel, cl_uint dim,
    const size_t * offset, const size_t * global, const size_t * local, cl_uint waits,
    const cl_event * wait_list, cl_event * returned) {
    const int current = phase.load();
    char name[256] = {};
    const bool matrix = current > 0 && clGetKernelInfo(kernel, CL_KERNEL_FUNCTION_NAME, sizeof(name), name, nullptr) == CL_SUCCESS &&
        (strstr(name, "mul_mv") || strstr(name, "mul_mm") || strstr(name, "mul_mat") ||
         strstr(name, "gemv") || strstr(name, "gemm") || strstr(name, "matmul"));
    cl_event owned = nullptr;
    cl_event * out = matrix && !returned ? &owned : returned;
    const cl_int result = __real_clEnqueueNDRangeKernel(queue, kernel, dim, offset, global, local, waits, wait_list, out);
    if (matrix && result == CL_SUCCESS && out && *out) {
        cl_int status = -1;
        cl_device_id device = nullptr;
        cl_device_type type = 0;
        if (clWaitForEvents(1, out) == CL_SUCCESS &&
            clGetEventInfo(*out, CL_EVENT_COMMAND_EXECUTION_STATUS, sizeof(status), &status, nullptr) == CL_SUCCESS &&
            status == CL_COMPLETE && clGetCommandQueueInfo(queue, CL_QUEUE_DEVICE, sizeof(device), &device, nullptr) == CL_SUCCESS &&
            clGetDeviceInfo(device, CL_DEVICE_TYPE, sizeof(type), &type, nullptr) == CL_SUCCESS && (type & CL_DEVICE_TYPE_GPU)) {
            std::lock_guard<std::mutex> lock(trace_mutex);
            evidence[current] = name;
            phase.store(0);
        }
    }
    if (owned) clReleaseEvent(owned);
    return result;
}

extern "C" cl_int __real_clBuildProgram(cl_program, cl_uint, const cl_device_id *, const char *,
    void (CL_CALLBACK *)(cl_program, void *), void *);
extern "C" cl_int __wrap_clBuildProgram(cl_program program, cl_uint count, const cl_device_id * devices,
    const char * options, void (CL_CALLBACK * callback)(cl_program, void *), void * user) {
    size_t source_bytes = 0;
    std::string kernel = "unknown";
    if (clGetProgramInfo(program, CL_PROGRAM_SOURCE, 0, nullptr, &source_bytes) == CL_SUCCESS &&
        source_bytes > 0 && source_bytes < 1024 * 1024) {
        std::string source(source_bytes, '\0');
        if (clGetProgramInfo(program, CL_PROGRAM_SOURCE, source_bytes, source.data(), nullptr) == CL_SUCCESS) {
            size_t start = source.find("kernel_");
            if (start != std::string::npos) {
                size_t end = source.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_", start);
                kernel = source.substr(start, end - start);
            }
        }
    }
    __android_log_print(ANDROID_LOG_INFO, "AssistantOpenCL", "Building OpenCL program %s", kernel.c_str());
    const cl_int result = __real_clBuildProgram(program, count, devices, options, callback, user);
    if (result == CL_SUCCESS) return result;
    size_t bytes = 0;
    if (clGetProgramInfo(program, CL_PROGRAM_DEVICES, 0, nullptr, &bytes) == CL_SUCCESS && bytes >= sizeof(cl_device_id)) {
        std::vector<cl_device_id> used(bytes / sizeof(cl_device_id));
        if (clGetProgramInfo(program, CL_PROGRAM_DEVICES, bytes, used.data(), nullptr) == CL_SUCCESS &&
            clGetProgramBuildInfo(program, used[0], CL_PROGRAM_BUILD_LOG, 0, nullptr, &bytes) == CL_SUCCESS && bytes > 0) {
            std::vector<char> log(bytes + 1);
            if (clGetProgramBuildInfo(program, used[0], CL_PROGRAM_BUILD_LOG, bytes, log.data(), nullptr) == CL_SUCCESS)
                __android_log_print(ANDROID_LOG_ERROR, "AssistantOpenCL", "clBuildProgram %d, %s, %s: %.2900s",
                                    result, kernel.c_str(), options ? options : "", log.data());
        }
    }
    return result;
}
