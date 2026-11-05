// Vulkan entry points, loaded at runtime from the system loader so the same
// code runs on Android (libvulkan.so) and on a Windows test host (vulkan-1.dll).
#pragma once
#ifndef VK_NO_PROTOTYPES
#define VK_NO_PROTOTYPES
#endif
#include <vulkan/vulkan.h>

#define FILM_VK_GLOBAL(X) \
  X(vkCreateInstance) \
  X(vkEnumerateInstanceVersion)

#define FILM_VK_INSTANCE(X) \
  X(vkDestroyInstance) \
  X(vkEnumeratePhysicalDevices) \
  X(vkGetPhysicalDeviceProperties) \
  X(vkGetPhysicalDeviceProperties2) \
  X(vkGetPhysicalDeviceQueueFamilyProperties) \
  X(vkGetPhysicalDeviceMemoryProperties) \
  X(vkGetPhysicalDeviceFormatProperties) \
  X(vkCreateDevice) \
  X(vkGetDeviceProcAddr)

#define FILM_VK_DEVICE(X) \
  X(vkDestroyDevice) \
  X(vkGetDeviceQueue) \
  X(vkQueueSubmit) \
  X(vkDeviceWaitIdle) \
  X(vkCreateBuffer) \
  X(vkDestroyBuffer) \
  X(vkGetBufferMemoryRequirements) \
  X(vkBindBufferMemory) \
  X(vkCreateImage) \
  X(vkDestroyImage) \
  X(vkGetImageMemoryRequirements) \
  X(vkBindImageMemory) \
  X(vkCreateImageView) \
  X(vkDestroyImageView) \
  X(vkAllocateMemory) \
  X(vkFreeMemory) \
  X(vkMapMemory) \
  X(vkUnmapMemory) \
  X(vkFlushMappedMemoryRanges) \
  X(vkInvalidateMappedMemoryRanges) \
  X(vkCreateSampler) \
  X(vkDestroySampler) \
  X(vkCreateShaderModule) \
  X(vkDestroyShaderModule) \
  X(vkCreateDescriptorSetLayout) \
  X(vkDestroyDescriptorSetLayout) \
  X(vkCreatePipelineLayout) \
  X(vkDestroyPipelineLayout) \
  X(vkCreateComputePipelines) \
  X(vkDestroyPipeline) \
  X(vkCreateDescriptorPool) \
  X(vkDestroyDescriptorPool) \
  X(vkAllocateDescriptorSets) \
  X(vkUpdateDescriptorSets) \
  X(vkCreateCommandPool) \
  X(vkDestroyCommandPool) \
  X(vkResetCommandPool) \
  X(vkAllocateCommandBuffers) \
  X(vkBeginCommandBuffer) \
  X(vkEndCommandBuffer) \
  X(vkCmdBindPipeline) \
  X(vkCmdBindDescriptorSets) \
  X(vkCmdPushConstants) \
  X(vkCmdDispatch) \
  X(vkCmdPipelineBarrier) \
  X(vkCmdCopyBufferToImage) \
  X(vkCmdCopyImageToBuffer) \
  X(vkCmdWriteTimestamp) \
  X(vkCreateQueryPool) \
  X(vkDestroyQueryPool) \
  X(vkCmdResetQueryPool) \
  X(vkGetQueryPoolResults) \
  X(vkCreateFence) \
  X(vkDestroyFence) \
  X(vkWaitForFences) \
  X(vkResetFences)

namespace film::vk {

#define FILM_VK_DECLARE(name) extern PFN_##name name;
FILM_VK_GLOBAL(FILM_VK_DECLARE)
FILM_VK_INSTANCE(FILM_VK_DECLARE)
FILM_VK_DEVICE(FILM_VK_DECLARE)
#undef FILM_VK_DECLARE

// Each returns false with a message when the loader or an entry point is missing.
bool load_loader(const char **error);
bool load_instance(VkInstance instance, const char **error);
bool load_device(VkDevice device, const char **error);

} // namespace film::vk
