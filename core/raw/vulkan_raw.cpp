#include "raw_frame.h"

#include "joint_green_spv.h"
#include "film_linear_spv.h"

#include <vulkan/vulkan.h>

#include <algorithm>
#include <cstring>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace spektracam {
namespace {

void check(VkResult result, const char* operation) {
    if (result != VK_SUCCESS) {
        throw std::runtime_error(std::string(operation) + " failed (" + std::to_string(result) + ")");
    }
}

struct alignas(16) Parameters {
    uint32_t width, height;
    float padding0, white;
    std::array<float, 4> black;
    uint32_t bayer, flags;
    std::array<float, 4> whiteBalance;
    float padding1;
    uint32_t hasMatrix;
    std::array<float, 9> matrix;
};
static_assert(offsetof(Parameters, matrix) == 64);
static_assert(sizeof(Parameters) == 112);

constexpr uint32_t stripHeight = 128;
constexpr uint32_t halo = 8;
constexpr std::array<uint32_t, 6> bindings{0, 1, 3, 5, 6, 7};
constexpr VkDescriptorType descriptorType(uint32_t binding) {
    if (binding == 5) return VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    if (binding == 1) return VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC;
    return VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
}

class Session {
public:
    Session() {
        try {
            initialize();
        } catch (...) {
            destroy();
            throw;
        }
    }
    ~Session() { destroy(); }
    Session(const Session&) = delete;
    Session& operator=(const Session&) = delete;

    std::vector<float> develop(const RawFrame& frame);

private:
    struct Buffer {
        VkDevice device;
        VkBuffer buffer = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        VkDeviceSize size = 0;
        void* mapped = nullptr;

        explicit Buffer(VkDevice device) : device(device) {}
        ~Buffer() {
            if (mapped) vkUnmapMemory(device, memory);
            if (buffer) vkDestroyBuffer(device, buffer, nullptr);
            if (memory) vkFreeMemory(device, memory, nullptr);
        }
        Buffer(const Buffer&) = delete;
        Buffer& operator=(const Buffer&) = delete;
        VkDescriptorBufferInfo descriptor() const { return {buffer, 0, size}; }
    };

    void initialize();
    void destroy() noexcept;
    void makeBuffer(Buffer& buffer, VkDeviceSize size, uint32_t binding, bool hostVisible);
    VkPipeline makePipeline(const uint32_t* code, size_t bytes);

    VkInstance instance_ = VK_NULL_HANDLE;
    VkPhysicalDevice physical_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout descriptorLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline joint_ = VK_NULL_HANDLE;
    VkPipeline film_ = VK_NULL_HANDLE;
    VkCommandPool commands_ = VK_NULL_HANDLE;
    VkCommandBuffer command_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptors_ = VK_NULL_HANDLE;
    VkDescriptorSet set_ = VK_NULL_HANDLE;
    VkPhysicalDeviceProperties properties_{};
    VkPhysicalDeviceMemoryProperties memory_{};
    std::mutex mutex_;
};

void Session::initialize() {
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "SpektraCam RAW";
    app.apiVersion = VK_API_VERSION_1_0;
    VkInstanceCreateInfo instanceInfo{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    instanceInfo.pApplicationInfo = &app;
#ifdef __APPLE__
    uint32_t instanceExtensionCount = 0;
    check(vkEnumerateInstanceExtensionProperties(nullptr, &instanceExtensionCount, nullptr), "Vulkan instance extensions");
    std::vector<VkExtensionProperties> instanceExtensions(instanceExtensionCount);
    check(vkEnumerateInstanceExtensionProperties(nullptr, &instanceExtensionCount, instanceExtensions.data()), "Vulkan instance extensions");
    const char* portability = "VK_KHR_portability_enumeration";
    if (std::any_of(instanceExtensions.begin(), instanceExtensions.end(), [&](const auto& extension) {
        return std::strcmp(extension.extensionName, portability) == 0;
    })) {
        instanceInfo.enabledExtensionCount = 1;
        instanceInfo.ppEnabledExtensionNames = &portability;
        instanceInfo.flags = VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR;
    }
#endif
    check(vkCreateInstance(&instanceInfo, nullptr, &instance_), "Create Vulkan instance");
    uint32_t deviceCount = 0;
    check(vkEnumeratePhysicalDevices(instance_, &deviceCount, nullptr), "Enumerate Vulkan devices");
    std::vector<VkPhysicalDevice> devices(deviceCount);
    check(vkEnumeratePhysicalDevices(instance_, &deviceCount, devices.data()), "Enumerate Vulkan devices");
    uint32_t family = 0;
    for (auto candidate : devices) {
        uint32_t familyCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &familyCount, nullptr);
        std::vector<VkQueueFamilyProperties> families(familyCount);
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &familyCount, families.data());
        for (uint32_t i = 0; i < familyCount; ++i) {
            if (families[i].queueCount && (families[i].queueFlags & VK_QUEUE_COMPUTE_BIT)) {
                physical_ = candidate;
                family = i;
                break;
            }
        }
        if (physical_) break;
    }
    if (!physical_) throw std::runtime_error("No Vulkan compute device");
    vkGetPhysicalDeviceProperties(physical_, &properties_);
    vkGetPhysicalDeviceMemoryProperties(physical_, &memory_);

    const float priority = 1;
    VkDeviceQueueCreateInfo queueInfo{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    queueInfo.queueFamilyIndex = family;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &priority;
    VkDeviceCreateInfo deviceInfo{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    deviceInfo.queueCreateInfoCount = 1;
    deviceInfo.pQueueCreateInfos = &queueInfo;
#ifdef __APPLE__
    uint32_t deviceExtensionCount = 0;
    check(vkEnumerateDeviceExtensionProperties(physical_, nullptr, &deviceExtensionCount, nullptr), "Vulkan device extensions");
    std::vector<VkExtensionProperties> deviceExtensions(deviceExtensionCount);
    check(vkEnumerateDeviceExtensionProperties(physical_, nullptr, &deviceExtensionCount, deviceExtensions.data()), "Vulkan device extensions");
    const char* subset = "VK_KHR_portability_subset";
    if (std::any_of(deviceExtensions.begin(), deviceExtensions.end(), [&](const auto& extension) {
        return std::strcmp(extension.extensionName, subset) == 0;
    })) {
        deviceInfo.enabledExtensionCount = 1;
        deviceInfo.ppEnabledExtensionNames = &subset;
    }
#endif
    check(vkCreateDevice(physical_, &deviceInfo, nullptr, &device_), "Create Vulkan device");
    vkGetDeviceQueue(device_, family, 0, &queue_);

    std::array<VkDescriptorSetLayoutBinding, bindings.size()> layoutBindings{};
    for (size_t i = 0; i < bindings.size(); ++i) {
        layoutBindings[i] = {bindings[i], descriptorType(bindings[i]), 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
    }
    VkDescriptorSetLayoutCreateInfo descriptorInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    descriptorInfo.bindingCount = uint32_t(layoutBindings.size());
    descriptorInfo.pBindings = layoutBindings.data();
    check(vkCreateDescriptorSetLayout(device_, &descriptorInfo, nullptr, &descriptorLayout_), "Create RAW descriptor layout");

    VkPushConstantRange strip{VK_SHADER_STAGE_COMPUTE_BIT, 0, 4 * sizeof(uint32_t)};
    VkPipelineLayoutCreateInfo pipelineInfo{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    pipelineInfo.setLayoutCount = 1;
    pipelineInfo.pSetLayouts = &descriptorLayout_;
    pipelineInfo.pushConstantRangeCount = 1;
    pipelineInfo.pPushConstantRanges = &strip;
    check(vkCreatePipelineLayout(device_, &pipelineInfo, nullptr, &pipelineLayout_), "Create RAW pipeline layout");
    joint_ = makePipeline(joint_green_spv, sizeof(joint_green_spv));
    film_ = makePipeline(film_linear_spv, sizeof(film_linear_spv));

    VkCommandPoolCreateInfo commandInfo{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    commandInfo.queueFamilyIndex = family;
    check(vkCreateCommandPool(device_, &commandInfo, nullptr, &commands_), "Create RAW command pool");
    VkCommandBufferAllocateInfo allocateCommands{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    allocateCommands.commandPool = commands_;
    allocateCommands.commandBufferCount = 1;
    check(vkAllocateCommandBuffers(device_, &allocateCommands, &command_), "Allocate RAW command buffer");

    const VkDescriptorPoolSize poolSizes[] = {{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 4},
                                              {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC, 1},
                                              {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1}};
    VkDescriptorPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    poolInfo.maxSets = 1;
    poolInfo.poolSizeCount = 3;
    poolInfo.pPoolSizes = poolSizes;
    check(vkCreateDescriptorPool(device_, &poolInfo, nullptr, &descriptors_), "Create RAW descriptor pool");
    VkDescriptorSetAllocateInfo allocateSet{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    allocateSet.descriptorPool = descriptors_;
    allocateSet.descriptorSetCount = 1;
    allocateSet.pSetLayouts = &descriptorLayout_;
    check(vkAllocateDescriptorSets(device_, &allocateSet, &set_), "Allocate RAW descriptor set");
}

void Session::destroy() noexcept {
    if (device_) {
        if (descriptors_) vkDestroyDescriptorPool(device_, descriptors_, nullptr);
        if (commands_) vkDestroyCommandPool(device_, commands_, nullptr);
        if (film_) vkDestroyPipeline(device_, film_, nullptr);
        if (joint_) vkDestroyPipeline(device_, joint_, nullptr);
        if (pipelineLayout_) vkDestroyPipelineLayout(device_, pipelineLayout_, nullptr);
        if (descriptorLayout_) vkDestroyDescriptorSetLayout(device_, descriptorLayout_, nullptr);
        vkDestroyDevice(device_, nullptr);
    }
    if (instance_) vkDestroyInstance(instance_, nullptr);
}

VkPipeline Session::makePipeline(const uint32_t* code, size_t bytes) {
    VkShaderModuleCreateInfo shaderInfo{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    shaderInfo.codeSize = bytes;
    shaderInfo.pCode = code;
    VkShaderModule shader = VK_NULL_HANDLE;
    check(vkCreateShaderModule(device_, &shaderInfo, nullptr, &shader), "Create RAW shader");
    VkComputePipelineCreateInfo info{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    info.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    info.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    info.stage.module = shader;
    info.stage.pName = "main";
    info.layout = pipelineLayout_;
    VkPipeline pipeline = VK_NULL_HANDLE;
    const VkResult status = vkCreateComputePipelines(device_, VK_NULL_HANDLE, 1, &info, nullptr, &pipeline);
    vkDestroyShaderModule(device_, shader, nullptr);
    if (status != VK_SUCCESS && pipeline) vkDestroyPipeline(device_, pipeline, nullptr);
    check(status, "Create RAW pipeline");
    return pipeline;
}

void Session::makeBuffer(Buffer& buffer, VkDeviceSize size, uint32_t binding, bool hostVisible) {
    if (binding != 5 && binding != 1 && size > properties_.limits.maxStorageBufferRange) {
        throw std::runtime_error("RAW dimensions exceed this GPU's storage buffer limit");
    }
    buffer.size = size;
    VkBufferCreateInfo info{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    info.size = size;
    info.usage = binding == 5 ? VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT : VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    check(vkCreateBuffer(device_, &info, nullptr, &buffer.buffer), "Create RAW buffer");
    VkMemoryRequirements requirements{};
    vkGetBufferMemoryRequirements(device_, buffer.buffer, &requirements);
    const VkMemoryPropertyFlags needed = hostVisible
        ? VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
        : VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
    for (uint32_t i = 0; i < memory_.memoryTypeCount; ++i) {
        if (!(requirements.memoryTypeBits & (1u << i)) ||
            (memory_.memoryTypes[i].propertyFlags & needed) != needed) continue;
        VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        allocation.allocationSize = requirements.size;
        allocation.memoryTypeIndex = i;
        check(vkAllocateMemory(device_, &allocation, nullptr, &buffer.memory), "Allocate RAW memory");
        check(vkBindBufferMemory(device_, buffer.buffer, buffer.memory, 0), "Bind RAW memory");
        if (hostVisible) check(vkMapMemory(device_, buffer.memory, 0, size, 0, &buffer.mapped), "Map RAW memory");
        return;
    }
    throw std::runtime_error("No compatible Vulkan memory type for RAW");
}

std::vector<float> Session::develop(const RawFrame& frame) {
    const std::lock_guard<std::mutex> lock(mutex_);
    const auto pixels = VkDeviceSize(frame.width) * frame.height;
    const auto scratchSize = VkDeviceSize(frame.width) * std::min(frame.height, stripHeight + 2 * halo) * 2 * sizeof(float);
    const uint32_t groupsX = (frame.width + 15) / 16;
    const uint32_t groupsY = (std::min(frame.height, stripHeight + 2 * halo) + 15) / 16;
    if (groupsX > properties_.limits.maxComputeWorkGroupCount[0] ||
        groupsY > properties_.limits.maxComputeWorkGroupCount[1]) {
        throw std::runtime_error("RAW dimensions exceed this GPU's compute dispatch limit");
    }

    Buffer raw(device_), output(device_), cache(device_), params(device_), scratchA(device_), scratchB(device_);
    makeBuffer(raw, (pixels + (frame.vignette ? 8 : 0)) * sizeof(uint32_t), 0, true);
    // Each dispatch addresses one window of the mapped output. This keeps the
    // descriptor range below Vulkan's minimum even for a 12 MP float32 photo.
    const auto stripPixels = VkDeviceSize(frame.width) * std::min(frame.height, stripHeight);
    const auto stripBytes = stripPixels * 3 * sizeof(float);
    const auto alignment = properties_.limits.minStorageBufferOffsetAlignment;
    const auto stride = (stripBytes + alignment - 1) / alignment * alignment;
    const uint32_t strips = (frame.height + stripHeight - 1) / stripHeight;
    if (stripBytes > properties_.limits.maxStorageBufferRange || stride * (strips - 1) > UINT32_MAX) {
        throw std::runtime_error("RAW output exceeds this GPU's storage window limit");
    }
    makeBuffer(output, stride * (strips - 1) + stripBytes, 1, true);
    makeBuffer(cache, stripPixels * 2 * sizeof(uint32_t), 3, false);
    makeBuffer(params, sizeof(Parameters), 5, true);
    makeBuffer(scratchA, scratchSize, 6, false);
    makeBuffer(scratchB, scratchSize, 7, false);
    expandRaw(frame, static_cast<uint32_t*>(raw.mapped));
    Parameters values{};
    values.width = frame.width;
    values.height = frame.height;
    values.white = frame.white;
    values.black = frame.black;
    values.bayer = uint32_t(frame.bayer);
    values.flags = 16u | (frame.vignette ? 256u : 0u) | (frame.highlightChromaBlend ? 1024u : 0u);
    values.whiteBalance = frame.whiteBalance;
    values.hasMatrix = frame.cameraToLinearSrgb.has_value();
    if (frame.cameraToLinearSrgb) values.matrix = *frame.cameraToLinearSrgb;
    std::memcpy(params.mapped, &values, sizeof(values));

    const std::array infos{raw.descriptor(), VkDescriptorBufferInfo{output.buffer, 0, stripBytes}, cache.descriptor(),
                           params.descriptor(), scratchA.descriptor(), scratchB.descriptor()};
    std::array<VkWriteDescriptorSet, bindings.size()> writes{};
    for (size_t i = 0; i < bindings.size(); ++i) {
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = set_;
        writes[i].dstBinding = bindings[i];
        writes[i].descriptorCount = 1;
        writes[i].descriptorType = descriptorType(bindings[i]);
        writes[i].pBufferInfo = &infos[i];
    }
    vkUpdateDescriptorSets(device_, uint32_t(writes.size()), writes.data(), 0, nullptr);

    check(vkResetCommandPool(device_, commands_, 0), "Reset RAW commands");
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    check(vkBeginCommandBuffer(command_, &begin), "Begin RAW commands");
    VkMemoryBarrier between{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    between.srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    between.dstAccessMask = between.srcAccessMask;
    auto syncCompute = [&] {
        vkCmdPipelineBarrier(command_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             0, 1, &between, 0, nullptr, 0, nullptr);
    };
    for (uint32_t stripIndex = 0; stripIndex < strips; ++stripIndex) {
        const uint32_t core = stripIndex * stripHeight;
        const uint32_t outputOffset = uint32_t(stride * stripIndex);
        vkCmdBindDescriptorSets(command_, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout_,
                                0, 1, &set_, 1, &outputOffset);
        vkCmdBindPipeline(command_, VK_PIPELINE_BIND_POINT_COMPUTE, joint_);
        const uint32_t start = core > halo ? core - halo : 0;
        const uint32_t rows = std::min(frame.height, core + stripHeight + halo) - start;
        for (uint32_t stage = 0; stage < 4; ++stage) {
            const uint32_t strip[] = {stage, start, rows, core};
            vkCmdPushConstants(command_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(strip), strip);
            vkCmdDispatch(command_, groupsX, (rows + 15) / 16, 1);
            syncCompute();
        }
        vkCmdBindPipeline(command_, VK_PIPELINE_BIND_POINT_COMPUTE, film_);
        const uint32_t coreRows = std::min(stripHeight, frame.height - core);
        const uint32_t strip[] = {0, core, coreRows, core};
        vkCmdPushConstants(command_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(strip), strip);
        vkCmdDispatch(command_, groupsX, (coreRows + 15) / 16, 1);
        syncCompute();
    }
    VkMemoryBarrier readback{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    readback.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    readback.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    vkCmdPipelineBarrier(command_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                         0, 1, &readback, 0, nullptr, 0, nullptr);
    check(vkEndCommandBuffer(command_), "End RAW commands");

    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &command_;
    check(vkQueueSubmit(queue_, 1, &submit, VK_NULL_HANDLE), "Submit RAW reconstruction");
    check(vkQueueWaitIdle(queue_), "Wait for RAW reconstruction");
    const auto* source = static_cast<const uint8_t*>(output.mapped);
    std::vector<float> rgb(size_t(pixels) * 3);
    for (uint32_t stripIndex = 0; stripIndex < strips; ++stripIndex) {
        const uint32_t core = stripIndex * stripHeight;
        const uint32_t rows = std::min(stripHeight, frame.height - core);
        std::memcpy(rgb.data() + size_t(core) * frame.width * 3, source + stride * stripIndex,
                    size_t(rows) * frame.width * 3 * sizeof(float));
    }
    return rgb;
}

} // namespace

std::vector<float> developRaw(const RawFrame& frame) {
    validateRaw(frame);
    static Session session;
    return session.develop(frame);
}

} // namespace spektracam
