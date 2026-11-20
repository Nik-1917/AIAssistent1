#define CL_TARGET_OPENCL_VERSION 200
#include <CL/cl.h>
#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>

namespace {
struct Device {
    std::string name;
    cl_ulong max_allocation = 0;
};
struct Driver {
    std::string identity;
    std::string error = "No compatible Adreno OpenCL GPU";
    std::vector<Device> devices;
};

std::string property(cl_device_id device, cl_device_info key) {
    size_t size = 0;
    if (clGetDeviceInfo(device, key, 0, nullptr, &size) != CL_SUCCESS || !size) return {};
    std::vector<char> value(size + 1);
    return clGetDeviceInfo(device, key, size, value.data(), nullptr) == CL_SUCCESS ? value.data() : "";
}

Driver inspect() {
    Driver result;
    cl_uint platforms_count = 0;
    if (clGetPlatformIDs(0, nullptr, &platforms_count) != CL_SUCCESS || !platforms_count) return result;
    std::vector<cl_platform_id> platforms(platforms_count);
    if (clGetPlatformIDs(platforms_count, platforms.data(), nullptr) != CL_SUCCESS) return result;
    for (auto platform : platforms) {
        cl_uint count = 0;
        if (clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, 0, nullptr, &count) != CL_SUCCESS || !count) continue;
        std::vector<cl_device_id> devices(count);
        if (clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, count, devices.data(), nullptr) != CL_SUCCESS) continue;
        for (auto id : devices) {
            auto name = property(id, CL_DEVICE_NAME);
            auto version = property(id, CL_DEVICE_VERSION);
            result.identity += name + "/" + version + "/" + property(id, CL_DRIVER_VERSION) + "|";
            if (name.find("Adreno") == std::string::npos && name.find("Qualcomm") == std::string::npos &&
                version.find("Adreno") == std::string::npos) continue;
            cl_ulong maximum = 0;
            if (clGetDeviceInfo(id, CL_DEVICE_MAX_MEM_ALLOC_SIZE, sizeof(maximum), &maximum, nullptr) != CL_SUCCESS) continue;
            result.devices.push_back({name, maximum});
        }
    }
    if (!result.devices.empty()) result.error.clear();
    return result;
}
const Driver & driver() { static const Driver value = inspect(); return value; }
}

extern "C" __attribute__((visibility("default"))) const char * assistant_gpu_driver_identity() {
    return driver().identity.c_str();
}
extern "C" __attribute__((visibility("default"))) const char * assistant_gpu_support_error() {
    return driver().error.c_str();
}
extern "C" __attribute__((visibility("default"))) uint64_t assistant_opencl_max_allocation(const char * name) {
    // Identical descriptions can occur on multi-GPU devices. Respect the smaller limit.
    cl_ulong limit = UINT64_MAX;
    for (const auto & dev : driver().devices) if (dev.name == name) limit = std::min(limit, dev.max_allocation);
    return limit == UINT64_MAX ? 0 : limit;
}
