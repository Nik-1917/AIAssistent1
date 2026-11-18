#include <vulkan/vulkan.h>
#include <string>
#include <vector>
#include <cstdio>

// Check core prerequisites before registering a backend in the shared GGML
// registry. Vulkan 1.1 drivers cannot supply Vulkan12 feature structures used
// by this backend, even when Android's loader itself reports Vulkan 1.3.
extern "C" __attribute__((visibility("default"))) const char * assistant_gpu_support_error() {
    static std::string error;
    error = "No compatible Vulkan GPU";
    VkApplicationInfo application{};
    application.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    application.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo create{};
    create.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    create.pApplicationInfo = &application;
    VkInstance instance = VK_NULL_HANDLE;
    if (vkCreateInstance(&create, nullptr, &instance) != VK_SUCCESS) return "Vulkan 1.1 instance unavailable";
    uint32_t count = 0;
    if (vkEnumeratePhysicalDevices(instance, &count, nullptr) == VK_SUCCESS && count <= 64) {
        std::vector<VkPhysicalDevice> devices(count);
        if (vkEnumeratePhysicalDevices(instance, &count, devices.data()) == VK_SUCCESS) {
            for (auto device : devices) {
                VkPhysicalDeviceProperties properties{};
                vkGetPhysicalDeviceProperties(device, &properties);
                if (properties.deviceType != VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU &&
                    properties.deviceType != VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU &&
                    properties.deviceType != VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU) continue;
                if (properties.apiVersion < VK_API_VERSION_1_2) {
                    char version[96];
                    std::snprintf(version, sizeof(version), "GPU Vulkan %u.%u.%u; backend requires Vulkan 1.2",
                        VK_VERSION_MAJOR(properties.apiVersion), VK_VERSION_MINOR(properties.apiVersion),
                        VK_VERSION_PATCH(properties.apiVersion));
                    error = version;
                    continue;
                }
                VkPhysicalDeviceVulkan11Features features11{};
                features11.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES;
                VkPhysicalDeviceFeatures2 features{};
                features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
                features.pNext = &features11;
                vkGetPhysicalDeviceFeatures2(device, &features);
                if (!features11.storageBuffer16BitAccess) {
                    error = "Vulkan GPU does not support required 16-bit storage";
                    continue;
                }
                error.clear();
                break;
            }
        }
    }
    vkDestroyInstance(instance, nullptr);
    return error.c_str();
}

// Driver/pipeline identity invalidates model compatibility records after driver updates.
extern "C" __attribute__((visibility("default"))) const char * assistant_gpu_driver_identity() {
    static std::string identity;
    VkApplicationInfo application{};
    application.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    application.apiVersion = VK_API_VERSION_1_0;
    VkInstanceCreateInfo create{};
    create.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    create.pApplicationInfo = &application;
    VkInstance instance = VK_NULL_HANDLE;
    if (vkCreateInstance(&create, nullptr, &instance) != VK_SUCCESS) return "unavailable";
    uint32_t count = 0;
    vkEnumeratePhysicalDevices(instance, &count, nullptr);
    std::vector<VkPhysicalDevice> devices(count);
    vkEnumeratePhysicalDevices(instance, &count, devices.data());
    identity.clear();
    constexpr char hex[] = "0123456789abcdef";
    for (auto device : devices) {
        VkPhysicalDeviceProperties properties{};
        vkGetPhysicalDeviceProperties(device, &properties);
        identity += std::to_string(properties.vendorID) + "/" + std::to_string(properties.deviceID) + "/" +
                    std::to_string(properties.driverVersion) + "/";
        for (auto byte : properties.pipelineCacheUUID) {
            identity += hex[byte >> 4];
            identity += hex[byte & 15];
        }
    }
    vkDestroyInstance(instance, nullptr);
    return identity.c_str();
}
