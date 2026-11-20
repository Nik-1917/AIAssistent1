#define CL_TARGET_OPENCL_VERSION 300
#define CL_USE_DEPRECATED_OPENCL_1_2_APIS
#include <CL/cl.h>
#include <dlfcn.h>
#include <memory>
#include <vector>
#include "report.h"

static void check(cl_int status, const char * operation) {
    if (status != CL_SUCCESS) throw std::runtime_error(std::string(operation) + " returned " + std::to_string(status));
}
template<class T> static T symbol(void * library, const char * name) {
    auto fn = reinterpret_cast<T>(dlsym(library, name));
    if (!fn) throw std::runtime_error(std::string("Missing OpenCL symbol ") + name);
    return fn;
}

static void probe(const ProbeReport & report) {
    report.set("stage", "driver.dlopen");
    auto lib = std::unique_ptr<void, decltype(&dlclose)>(dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL), dlclose);
    if (!lib) throw std::runtime_error(dlerror());
#define CL_FN(name) const auto name = symbol<decltype(&::name)>(lib.get(), #name)
    CL_FN(clGetPlatformIDs); CL_FN(clGetPlatformInfo); CL_FN(clGetDeviceIDs); CL_FN(clGetDeviceInfo);
    CL_FN(clCreateContext); CL_FN(clReleaseContext); CL_FN(clCreateCommandQueue); CL_FN(clReleaseCommandQueue);
    CL_FN(clCreateProgramWithSource); CL_FN(clBuildProgram); CL_FN(clGetProgramBuildInfo); CL_FN(clReleaseProgram);
    CL_FN(clCreateKernel); CL_FN(clReleaseKernel); CL_FN(clSetKernelArg); CL_FN(clCreateBuffer); CL_FN(clReleaseMemObject);
    CL_FN(clEnqueueNDRangeKernel); CL_FN(clEnqueueReadBuffer);
#undef CL_FN
    Dl_info library_info{};
    if (dladdr(reinterpret_cast<void *>(clGetPlatformIDs), &library_info) && library_info.dli_fname)
        report.set("driverLibrary", library_info.dli_fname);
    report.set("stage", "driver.enumerate");
    cl_uint count = 0;
    check(clGetPlatformIDs(0, nullptr, &count), "clGetPlatformIDs");
    if (!count) throw std::runtime_error("No OpenCL platforms");
    std::vector<cl_platform_id> platforms(count);
    check(clGetPlatformIDs(count, platforms.data(), nullptr), "clGetPlatformIDs");
    cl_device_id gpu = nullptr;
    cl_platform_id platform = nullptr;
    for (auto p : platforms) {
        const auto result = clGetDeviceIDs(p, CL_DEVICE_TYPE_GPU, 1, &gpu, nullptr);
        if (result == CL_DEVICE_NOT_FOUND) continue;
        check(result, "clGetDeviceIDs");
        platform = p;
        break;
    }
    if (!platform) throw std::runtime_error("No OpenCL GPU");
    auto stringInfo = [&](cl_device_info what) {
        size_t n = 0;
        check(clGetDeviceInfo(gpu, what, 0, nullptr, &n), "device info size");
        std::vector<char> buffer(n + 1);
        check(clGetDeviceInfo(gpu, what, n, buffer.data(), nullptr), "device info");
        return std::string(buffer.data());
    };
    size_t n = 0;
    check(clGetPlatformInfo(platform, CL_PLATFORM_VERSION, 0, nullptr, &n), "platform version size");
    std::vector<char> platform_version(n + 1);
    check(clGetPlatformInfo(platform, CL_PLATFORM_VERSION, n, platform_version.data(), nullptr), "platform version");
    report.set("platformVersion", platform_version.data());
    report.set("device", stringInfo(CL_DEVICE_NAME));
    report.set("vendor", stringInfo(CL_DEVICE_VENDOR));
    report.set("driver", stringInfo(CL_DRIVER_VERSION));
    report.set("deviceVersion", stringInfo(CL_DEVICE_VERSION));
    report.set("openclC", stringInfo(CL_DEVICE_OPENCL_C_VERSION));
    if (clGetDeviceInfo(gpu, CL_DEVICE_OPENCL_C_ALL_VERSIONS, 0, nullptr, &n) == CL_SUCCESS && n >= sizeof(cl_name_version)) {
        std::vector<cl_name_version> versions(n / sizeof(cl_name_version));
        if (clGetDeviceInfo(gpu, CL_DEVICE_OPENCL_C_ALL_VERSIONS, n, versions.data(), nullptr) == CL_SUCCESS) {
            std::string value;
            for (auto & v : versions) value += std::to_string(CL_VERSION_MAJOR(v.version)) + "." +
                std::to_string(CL_VERSION_MINOR(v.version)) + " ";
            report.set("openclCAllVersions", value);
        }
    }
    report.set("extensions", stringInfo(CL_DEVICE_EXTENSIONS));
    cl_bool compiler = CL_FALSE, images = CL_FALSE;
    cl_ulong max_alloc = 0, total = 0;
    check(clGetDeviceInfo(gpu, CL_DEVICE_COMPILER_AVAILABLE, sizeof(compiler), &compiler, nullptr), "compiler availability");
    check(clGetDeviceInfo(gpu, CL_DEVICE_IMAGE_SUPPORT, sizeof(images), &images, nullptr), "image support");
    check(clGetDeviceInfo(gpu, CL_DEVICE_MAX_MEM_ALLOC_SIZE, sizeof(max_alloc), &max_alloc, nullptr), "max allocation");
    check(clGetDeviceInfo(gpu, CL_DEVICE_GLOBAL_MEM_SIZE, sizeof(total), &total, nullptr), "global memory");
    report.set("compilerAvailable", std::to_string(compiler)); report.set("images", std::to_string(images));
    report.set("maxAllocationBytes", std::to_string(max_alloc)); report.set("globalMemoryBytes", std::to_string(total));
    report.set("stage", "driver.compile");
    cl_int err = 0;
    std::unique_ptr<_cl_context, decltype(clReleaseContext)> context(clCreateContext(nullptr, 1, &gpu, nullptr, nullptr, &err), clReleaseContext);
    check(err, "clCreateContext");
    std::unique_ptr<_cl_command_queue, decltype(clReleaseCommandQueue)> queue(clCreateCommandQueue(context.get(), gpu, 0, &err), clReleaseCommandQueue);
    check(err, "clCreateCommandQueue");
    const char * source = "__kernel void verify(__global int* values) { int i=get_global_id(0); values[i]=i*i+7; }";
    std::unique_ptr<_cl_program, decltype(clReleaseProgram)> program(clCreateProgramWithSource(context.get(), 1, &source, nullptr, &err), clReleaseProgram);
    check(err, "clCreateProgramWithSource");
    err = clBuildProgram(program.get(), 1, &gpu, nullptr, nullptr, nullptr);
    if (err != CL_SUCCESS) {
        if (clGetProgramBuildInfo(program.get(), gpu, CL_PROGRAM_BUILD_LOG, 0, nullptr, &n) == CL_SUCCESS && n) {
            std::vector<char> log(n + 1);
            if (clGetProgramBuildInfo(program.get(), gpu, CL_PROGRAM_BUILD_LOG, n, log.data(), nullptr) == CL_SUCCESS)
                report.set("buildLog", std::string(log.data()).substr(0, 3000));
        }
        check(err, "clBuildProgram");
    }
    report.set("stage", "driver.execute");
    std::unique_ptr<_cl_kernel, decltype(clReleaseKernel)> kernel(clCreateKernel(program.get(), "verify", &err), clReleaseKernel);
    check(err, "clCreateKernel");
    cl_int values[16] = {};
    std::unique_ptr<_cl_mem, decltype(clReleaseMemObject)> buffer(clCreateBuffer(context.get(), CL_MEM_WRITE_ONLY, sizeof(values), nullptr, &err), clReleaseMemObject);
    check(err, "clCreateBuffer");
    cl_mem arg = buffer.get();
    check(clSetKernelArg(kernel.get(), 0, sizeof(arg), &arg), "clSetKernelArg");
    size_t work = 16;
    check(clEnqueueNDRangeKernel(queue.get(), kernel.get(), 1, nullptr, &work, nullptr, 0, nullptr, nullptr), "clEnqueueNDRangeKernel");
    check(clEnqueueReadBuffer(queue.get(), buffer.get(), CL_TRUE, 0, sizeof(values), values, 0, nullptr, nullptr), "clEnqueueReadBuffer");
    for (int i = 0; i < 16; ++i) if (values[i] != i*i+7) throw std::runtime_error("Wrong GPU compute result");
    report.set("verifiedValues", "16");
    report.set("status", "PASS");
}

extern "C" JNIEXPORT void JNICALL Java_com_example_aiassistent1_data_engine_OpenClProbeNative_driver(
        JNIEnv * env, jobject, jobject sink) {
    ProbeReport report(env, sink);
    try { probe(report); }
    catch (const std::exception & e) { if (!env->ExceptionCheck()) report.set("error", e.what()); }
}
