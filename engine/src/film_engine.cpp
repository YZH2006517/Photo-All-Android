#include "film_engine.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>

#include "burst.h"
#include "film_shaders.h"
#include <cstdarg>
#include <cstdio>
#include "vk_api.h"
#include "vkdt/constants.h"

namespace film {
using namespace vk;

namespace {

#define VK_TRY(expr, what) \
  do { \
    const VkResult vk_try_result = (expr); \
    if(vk_try_result != VK_SUCCESS) \
    { \
      error = std::string(what) + " failed (VkResult " + std::to_string(vk_try_result) + ")"; \
      return false; \
    } \
  } while(0)

// ---------------------------------------------------------------------------
// Kernels. Binding strings list set 1 in order: S sampler, I storage image, B storage buffer.

enum Kernel
{
  kSetup, kDirlut, kFast, kExpose, kDevelop, kHalin, kHalout, kScatter, kNegprint,
  kBlur, kDownsample, kDecode, kEncode, kRaw, kMerge, kLtm, kChroma, kDnHalf, kDnDowncov, kDnDown, kDnAssemble, kDnDoub, kFlashmix, kFlashfrac, kFlashwb, kKernelCount
};

struct KernelSpec
{
  const char *name;
  const char *bindings;
  uint32_t push_size;
};

constexpr KernelSpec kKernels[kKernelCount] = {
    {"setup", "SBS", 16},      {"dirlut", "IB", 0},       {"fast", "SBSI", 0},
    {"expose", "SSBII", 0},    {"develop", "SBSSI", 0},   {"halin", "SBSI", 0},
    {"halout", "SBSISSSSSS", 0}, {"scatter", "SISSSS", 0}, {"negprint", "SSBI", 0},
    {"blur", "SI", 16},        {"downsample", "SI", 4},   {"decode", "SI", 56},
    {"encode", "SIS", 112},     {"raw", "SSI", 128},       {"merge", "SSSSSSBI", 80},
    {"ltm", "SSI", 16},        {"chroma", "SSSI", 16},
    {"dn_half", "SI", 48},     {"dn_downcov", "SII", 16}, {"dn_down", "SI", 16},
    {"dn_assemble", "SSSSSI", 32}, {"dn_doub", "SSSI", 64},  {"flashmix", "SSBI", 128},
    {"flashfrac", "SSBI", 64},  {"flashwb", "SSI", 80},
};
constexpr int kMaxBurst = 6;
// Code value of 1.0 in the merged mosaic: 20 bits, so shadows lifted from a longer
// bracket frame keep their precision below the reference's own quantisation.
constexpr float kMergeScale = 1048575.0f;
// vkdt denoise's luma blend (its default): how much of the denoised luminance replaces
// the original; colour is always fully denoised.
constexpr float kDenoiseLuma = 0.6f;

// Row-major 3x3 primaries conversions between D65 RGB spaces.
constexpr float kSrgbToRec2020[9] = {0.627404f, 0.329283f, 0.043313f, 0.069097f, 0.919540f,
                                     0.011362f, 0.016391f, 0.088013f, 0.895595f};
constexpr float kP3ToRec2020[9] = {0.753833f, 0.198597f, 0.047570f, 0.045744f, 0.941777f,
                                   0.012479f, -0.001210f, 0.017601f, 0.983609f};
constexpr float kRec2020ToSrgb[9] = {1.660491f, -0.587641f, -0.072850f, -0.124550f, 1.132900f,
                                     -0.008349f, -0.018151f, -0.100579f, 1.118730f};
constexpr float kRec2020ToP3[9] = {1.343578f, -0.282180f, -0.061399f, -0.065297f, 1.075788f,
                                   -0.010490f, 0.002822f, -0.019598f, 1.016777f};
constexpr float kIdentity[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};

// filmsim constants from main.c.
constexpr float kScatterCoreUm = 1.9333333f;
constexpr float kScatterTailUm = 9.3666667f;
constexpr float kScatterExpRatio[3] = {0.5360f, 1.5236f, 2.7684f};
constexpr uint32_t kPrepBytes = 2048 * sizeof(float);

constexpr VkFormat kHalf = VK_FORMAT_R16G16B16A16_SFLOAT;
constexpr VkFormat kByte = VK_FORMAT_R8G8B8A8_UNORM;

uint16_t to_half(float value)
{
  uint32_t bits;
  std::memcpy(&bits, &value, sizeof(bits));
  const uint32_t sign = (bits >> 16) & 0x8000u;
  const int32_t exponent = static_cast<int32_t>((bits >> 23) & 0xff) - 127 + 15;
  uint32_t mantissa = bits & 0x7fffffu;
  if(((bits >> 23) & 0xff) == 0xff) return static_cast<uint16_t>(sign | 0x7c00u | (mantissa ? 0x200u : 0));
  if(exponent >= 31) return static_cast<uint16_t>(sign | 0x7c00u);
  if(exponent <= 0)
  {
    if(exponent < -10) return static_cast<uint16_t>(sign);
    mantissa |= 0x800000u;
    const uint32_t shift = static_cast<uint32_t>(14 - exponent);
    uint32_t half = mantissa >> shift;
    if((mantissa >> (shift - 1)) & 1u) half++;
    return static_cast<uint16_t>(sign | half);
  }
  uint32_t half = sign | (static_cast<uint32_t>(exponent) << 10) | (mantissa >> 13);
  if(mantissa & 0x1000u) half++; // round half up; carries into the exponent correctly
  return static_cast<uint16_t>(half);
}

// ---------------------------------------------------------------------------
// The per-frame pass graph, mirroring filmsim's create_nodes().

struct Ref
{
  enum Kind { Image, FilmsimLut, SpectraLut, Prep, Upload, GainMap, Offsets, Creative } kind;
  int id; // image index, or burst frame for Upload
};
Ref img(int id) { return {Ref::Image, id}; }
Ref upload(int frame) { return {Ref::Upload, frame}; }
constexpr Ref kOffsets{Ref::Offsets, -1};
constexpr Ref kFilmsimLut{Ref::FilmsimLut, -1};
constexpr Ref kSpectraLut{Ref::SpectraLut, -1};
constexpr Ref kPrep{Ref::Prep, -1};
constexpr Ref kUpload{Ref::Upload, -1}; // source pixels: rgba8, half float, or the raw CFA
constexpr Ref kGainMap{Ref::GainMap, -1};
constexpr Ref kCreative{Ref::Creative, -1};

struct LogicalImage
{
  uint32_t width, height;
  VkFormat format;
};

struct Pass
{
  Kernel kernel;
  std::vector<Ref> bindings;
  uint32_t groups_x, groups_y;
  std::array<uint8_t, 128> push{};
};

struct Graph
{
  std::vector<LogicalImage> images;
  std::vector<Pass> passes;

  int image(uint32_t width, uint32_t height, VkFormat format = kHalf)
  {
    images.push_back({std::max(1u, width), std::max(1u, height), format});
    return static_cast<int>(images.size()) - 1;
  }

  template <typename Push>
  void pass(Kernel kernel, std::vector<Ref> bindings, uint32_t gx, uint32_t gy, const Push &push)
  {
    static_assert(sizeof(Push) <= 128, "push constants too large"); // Vulkan guarantees 128 bytes
    Pass p{kernel, std::move(bindings), gx, gy, {}};
    std::memcpy(p.push.data(), &push, sizeof(Push));
    passes.push_back(std::move(p));
  }
  void pass(Kernel kernel, std::vector<Ref> bindings, uint32_t gx, uint32_t gy)
  {
    passes.push_back({kernel, std::move(bindings), gx, gy, {}});
  }
  // A dispatch covering image `id` with 8x8 groups.
  void pixels(Kernel kernel, std::vector<Ref> bindings, int id)
  {
    pass(kernel, std::move(bindings), (images[id].width + 7) / 8, (images[id].height + 7) / 8);
  }
  template <typename Push>
  void pixels(Kernel kernel, std::vector<Ref> bindings, int id, const Push &push)
  {
    pass(kernel, std::move(bindings), (images[id].width + 7) / 8, (images[id].height + 7) / 8, push);
  }

  // Gaussian blur of `src` with sigma in pixels of src. When the consumer reads
  // the result bilinearly, wide blurs run on a box-downsampled copy.
  int blur(int src, float sigma, bool allow_lowres)
  {
    if(sigma < 1.0f / 3.0f) return src; // FILMSIM_SIGMA_IS_NOOP
    const LogicalImage source = images[src];
    int factor = 1;
    while(allow_lowres && factor < 16 && sigma / (2.0f * factor) >= 2.0f) factor *= 2;
    int input = src;
    float s = sigma;
    if(factor > 1)
    {
      input = image((source.width + factor - 1) / factor, (source.height + factor - 1) / factor);
      const int32_t f = factor;
      pixels(kDownsample, {img(src), img(input)}, input, f);
      // the box already contributes (f^2-1)/12 of variance in full-res pixels
      s = std::sqrt(std::max(0.0f, sigma * sigma - (factor * factor - 1) / 12.0f)) / factor;
      if(s < 1.0f / 3.0f) return input;
    }
    struct
    {
      int32_t dx, dy;
      float sigma;
      int32_t radius;
    } push{1, 0, s, static_cast<int32_t>(std::ceil(3.0f * s))};
    const LogicalImage size = images[input];
    const int tmp = image(size.width, size.height);
    pixels(kBlur, {img(input), img(tmp)}, tmp, push);
    const int out = image(size.width, size.height);
    push.dx = 0;
    push.dy = 1;
    pixels(kBlur, {img(tmp), img(out)}, out, push);
    return out;
  }
};

struct Rows
{
  float row0[4], row1[4], row2[4];
};
Rows rows(const float *m)
{
  return {{m[0], m[1], m[2], 0}, {m[3], m[4], m[5], 0}, {m[6], m[7], m[8], 0}};
}

// ---------------------------------------------------------------------------

struct Buffer
{
  VkBuffer buffer = VK_NULL_HANDLE;
  VkDeviceMemory memory = VK_NULL_HANDLE;
  VkDeviceSize size = 0;
  void *mapped = nullptr;
};

struct Image
{
  VkImage image = VK_NULL_HANDLE;
  VkDeviceMemory memory = VK_NULL_HANDLE;
  VkImageView view = VK_NULL_HANDLE;
  uint32_t width = 0, height = 0;
  VkFormat format = VK_FORMAT_UNDEFINED;
  VkDeviceSize bytes = 0;
};

struct Pipeline
{
  VkShaderModule module = VK_NULL_HANDLE;
  VkDescriptorSetLayout set1 = VK_NULL_HANDLE;
  VkPipelineLayout layout = VK_NULL_HANDLE;
  VkPipeline pipeline = VK_NULL_HANDLE;
};

} // namespace

struct FilmEngine::Impl
{
  DeviceInfo info;
  VkInstance instance = VK_NULL_HANDLE;
  VkPhysicalDevice physical = VK_NULL_HANDLE;
  VkPhysicalDeviceMemoryProperties memory_props{};
  VkPhysicalDeviceLimits limits{};
  VkDevice device = VK_NULL_HANDLE;
  uint32_t queue_family = 0;
  VkQueue queue = VK_NULL_HANDLE;
  VkCommandPool command_pool = VK_NULL_HANDLE;
  VkCommandBuffer cmd = VK_NULL_HANDLE;
  VkFence fence = VK_NULL_HANDLE;
  VkQueryPool queries = VK_NULL_HANDLE;
  VkSampler linear = VK_NULL_HANDLE, nearest = VK_NULL_HANDLE;
  VkDescriptorSetLayout set0 = VK_NULL_HANDLE;
  std::array<Pipeline, kKernelCount> pipelines{};
  Buffer global_ubo, params_ubo, prep;
  Image filmsim_lut, spectra_lut;
  Image creative_lut;          // RGBA8 atlas n x n*n; 1x1 placeholder when none
  int creative_size = 0;
  float creative_strength = 0.0f;
  BurstAlignment alignment; // of the frame being developed
  float flash_gain = 0.0f;  // flash mix: gain on the flash-only light of the frame being developed
  float flash_ambient_k = 1.0f; // flash white: flash frame exposure over the ambient frame's
  // The develop's record: "section\tkey\tvalue" lines, filled by develop() and build_graph().
  std::string record;
  void note(const char *section, const char *key, const char *format, ...)
  {
    char value[256];
    va_list args;
    va_start(args, format);
    std::vsnprintf(value, sizeof(value), format, args);
    va_end(args);
    record += section;
    record += '\t';
    record += key;
    record += '\t';
    record += value;
    record += '\n';
  }

  ~Impl()
  {
    if(device)
    {
      vkDeviceWaitIdle(device);
      destroy(filmsim_lut);
      destroy(creative_lut);
      destroy(spectra_lut);
      destroy(global_ubo);
      destroy(params_ubo);
      destroy(prep);
      for(Pipeline &p : pipelines)
      {
        if(p.pipeline) vkDestroyPipeline(device, p.pipeline, nullptr);
        if(p.layout) vkDestroyPipelineLayout(device, p.layout, nullptr);
        if(p.set1) vkDestroyDescriptorSetLayout(device, p.set1, nullptr);
        if(p.module) vkDestroyShaderModule(device, p.module, nullptr);
      }
      if(set0) vkDestroyDescriptorSetLayout(device, set0, nullptr);
      if(linear) vkDestroySampler(device, linear, nullptr);
      if(nearest) vkDestroySampler(device, nearest, nullptr);
      if(queries) vkDestroyQueryPool(device, queries, nullptr);
      if(fence) vkDestroyFence(device, fence, nullptr);
      if(command_pool) vkDestroyCommandPool(device, command_pool, nullptr);
      vkDestroyDevice(device, nullptr);
    }
    if(instance) vkDestroyInstance(instance, nullptr);
  }

  // ----- memory -------------------------------------------------------------

  bool memory_type(uint32_t bits, VkMemoryPropertyFlags want, VkMemoryPropertyFlags avoid, uint32_t &index)
  {
    for(uint32_t i = 0; i < memory_props.memoryTypeCount; i++)
    {
      const VkMemoryPropertyFlags flags = memory_props.memoryTypes[i].propertyFlags;
      if((bits & (1u << i)) && (flags & want) == want && !(flags & avoid))
      {
        index = i;
        return true;
      }
    }
    return false;
  }

  bool allocate(const VkMemoryRequirements &req, VkMemoryPropertyFlags want, VkMemoryPropertyFlags prefer,
                VkDeviceMemory &memory, std::string &error)
  {
    uint32_t type;
    if(!memory_type(req.memoryTypeBits, want | prefer, 0, type) &&
       !memory_type(req.memoryTypeBits, want, 0, type))
    {
      error = "no suitable GPU memory type";
      return false;
    }
    VkMemoryAllocateInfo alloc{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    alloc.allocationSize = req.size;
    alloc.memoryTypeIndex = type;
    VK_TRY(vkAllocateMemory(device, &alloc, nullptr, &memory), "vkAllocateMemory");
    return true;
  }

  bool create_buffer(Buffer &buffer, VkDeviceSize size, VkBufferUsageFlags usage, bool host, std::string &error)
  {
    buffer.size = size;
    VkBufferCreateInfo create{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    create.size = size;
    create.usage = usage;
    VK_TRY(vkCreateBuffer(device, &create, nullptr, &buffer.buffer), "vkCreateBuffer");
    VkMemoryRequirements req;
    vkGetBufferMemoryRequirements(device, buffer.buffer, &req);
    const VkMemoryPropertyFlags want = host ? VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
                                            : VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
    const VkMemoryPropertyFlags prefer = host ? VK_MEMORY_PROPERTY_HOST_CACHED_BIT : 0;
    if(!allocate(req, want, prefer, buffer.memory, error)) return false;
    VK_TRY(vkBindBufferMemory(device, buffer.buffer, buffer.memory, 0), "vkBindBufferMemory");
    if(host) VK_TRY(vkMapMemory(device, buffer.memory, 0, VK_WHOLE_SIZE, 0, &buffer.mapped), "vkMapMemory");
    return true;
  }

  void destroy(Buffer &buffer)
  {
    if(buffer.mapped) vkUnmapMemory(device, buffer.memory);
    if(buffer.buffer) vkDestroyBuffer(device, buffer.buffer, nullptr);
    if(buffer.memory) vkFreeMemory(device, buffer.memory, nullptr);
    buffer = {};
  }

  bool create_image(Image &image, uint32_t width, uint32_t height, VkFormat format, VkImageUsageFlags usage,
                    std::string &error)
  {
    image.width = width;
    image.height = height;
    image.format = format;
    VkImageCreateInfo create{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    create.imageType = VK_IMAGE_TYPE_2D;
    create.format = format;
    create.extent = {width, height, 1};
    create.mipLevels = 1;
    create.arrayLayers = 1;
    create.samples = VK_SAMPLE_COUNT_1_BIT;
    create.tiling = VK_IMAGE_TILING_OPTIMAL;
    create.usage = usage;
    create.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VK_TRY(vkCreateImage(device, &create, nullptr, &image.image), "vkCreateImage");
    VkMemoryRequirements req;
    vkGetImageMemoryRequirements(device, image.image, &req);
    image.bytes = req.size;
    if(!allocate(req, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0, image.memory, error)) return false;
    VK_TRY(vkBindImageMemory(device, image.image, image.memory, 0), "vkBindImageMemory");
    VkImageViewCreateInfo view{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    view.image = image.image;
    view.viewType = VK_IMAGE_VIEW_TYPE_2D;
    view.format = format;
    view.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    VK_TRY(vkCreateImageView(device, &view, nullptr, &image.view), "vkCreateImageView");
    return true;
  }

  void destroy(Image &image)
  {
    if(image.view) vkDestroyImageView(device, image.view, nullptr);
    if(image.image) vkDestroyImage(device, image.image, nullptr);
    if(image.memory) vkFreeMemory(device, image.memory, nullptr);
    image = {};
  }

  // ----- command helpers ----------------------------------------------------

  static void to_general(VkCommandBuffer cb, const std::vector<VkImage> &images)
  {
    std::vector<VkImageMemoryBarrier> barriers;
    for(VkImage image : images)
    {
      VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
      b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
      b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
      b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
      b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
      b.image = image;
      b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
      barriers.push_back(b);
    }
    vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0,
                         nullptr, static_cast<uint32_t>(barriers.size()), barriers.data());
  }

  static void memory_barrier(VkCommandBuffer cb, VkPipelineStageFlags src_stage, VkAccessFlags src,
                             VkPipelineStageFlags dst_stage, VkAccessFlags dst)
  {
    VkMemoryBarrier b{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    b.srcAccessMask = src;
    b.dstAccessMask = dst;
    vkCmdPipelineBarrier(cb, src_stage, dst_stage, 0, 1, &b, 0, nullptr, 0, nullptr);
  }

  static void copy_region(VkBufferImageCopy &region, uint32_t width, uint32_t height)
  {
    region = {};
    region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.imageExtent = {width, height, 1};
  }

  bool begin(std::string &error)
  {
    VK_TRY(vkResetCommandPool(device, command_pool, 0), "vkResetCommandPool");
    VkCommandBufferBeginInfo begin_info{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin_info.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VK_TRY(vkBeginCommandBuffer(cmd, &begin_info), "vkBeginCommandBuffer");
    return true;
  }

  bool submit_and_wait(std::string &error)
  {
    VK_TRY(vkEndCommandBuffer(cmd), "vkEndCommandBuffer");
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    VK_TRY(vkResetFences(device, 1, &fence), "vkResetFences");
    VK_TRY(vkQueueSubmit(queue, 1, &submit, fence), "vkQueueSubmit");
    VK_TRY(vkWaitForFences(device, 1, &fence, VK_TRUE, UINT64_MAX), "vkWaitForFences");
    return true;
  }

  // ----- setup --------------------------------------------------------------

  bool init(const Lut &filmsim, const Lut &spectra, std::string &error)
  {
    const char *load_error = nullptr;
    if(!load_loader(&load_error))
    {
      error = load_error;
      return false;
    }
    uint32_t loader_version = VK_API_VERSION_1_0;
    if(vkEnumerateInstanceVersion) vkEnumerateInstanceVersion(&loader_version);
    if(loader_version < VK_API_VERSION_1_1)
    {
      error = "Vulkan 1.1 is required";
      return false;
    }
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "film engine";
    app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo instance_info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    instance_info.pApplicationInfo = &app;
    VK_TRY(vkCreateInstance(&instance_info, nullptr, &instance), "vkCreateInstance");
    if(!load_instance(instance, &load_error))
    {
      error = load_error;
      return false;
    }
    if(!pick_device(error)) return false;

    const float priority = 1.0f;
    VkDeviceQueueCreateInfo queue_info{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    queue_info.queueFamilyIndex = queue_family;
    queue_info.queueCount = 1;
    queue_info.pQueuePriorities = &priority;
    VkDeviceCreateInfo device_info{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    device_info.queueCreateInfoCount = 1;
    device_info.pQueueCreateInfos = &queue_info;
    VK_TRY(vkCreateDevice(physical, &device_info, nullptr, &device), "vkCreateDevice");
    if(!load_device(device, &load_error))
    {
      error = load_error;
      return false;
    }
    vkGetDeviceQueue(device, queue_family, 0, &queue);

    VkCommandPoolCreateInfo pool_info{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pool_info.queueFamilyIndex = queue_family;
    VK_TRY(vkCreateCommandPool(device, &pool_info, nullptr, &command_pool), "vkCreateCommandPool");
    VkCommandBufferAllocateInfo cmd_info{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    cmd_info.commandPool = command_pool;
    cmd_info.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmd_info.commandBufferCount = 1;
    VK_TRY(vkAllocateCommandBuffers(device, &cmd_info, &cmd), "vkAllocateCommandBuffers");
    VkFenceCreateInfo fence_info{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    VK_TRY(vkCreateFence(device, &fence_info, nullptr, &fence), "vkCreateFence");
    if(info.timestamps)
    {
      VkQueryPoolCreateInfo query_info{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};
      query_info.queryType = VK_QUERY_TYPE_TIMESTAMP;
      query_info.queryCount = 2;
      VK_TRY(vkCreateQueryPool(device, &query_info, nullptr, &queries), "vkCreateQueryPool");
    }

    // vkdt binds one linear, mirrored-repeat sampler everywhere. Float32 LUTs are
    // only ever texelFetch'ed, so they get a nearest sampler (no filter support needed).
    VkSamplerCreateInfo sampler{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    sampler.magFilter = sampler.minFilter = VK_FILTER_LINEAR;
    sampler.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    sampler.addressModeU = sampler.addressModeV = sampler.addressModeW = VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT;
    sampler.maxLod = 0.0f;
    VK_TRY(vkCreateSampler(device, &sampler, nullptr, &linear), "vkCreateSampler");
    sampler.magFilter = sampler.minFilter = VK_FILTER_NEAREST;
    VK_TRY(vkCreateSampler(device, &sampler, nullptr, &nearest), "vkCreateSampler");

    if(!create_pipelines(error)) return false;
    if(!create_buffer(global_ubo, 16, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, true, error)) return false;
    if(!create_buffer(params_ubo, sizeof(FilmParams), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, true, error)) return false;
    if(!create_buffer(prep, kPrepBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false, error)) return false;
    return upload_lut(filmsim, filmsim_lut, error) && upload_lut(spectra, spectra_lut, error) &&
           set_creative(nullptr, 0, 0.0f, error);
  }

  /** Replaces the creative LUT (RGB8, red fastest, size^3); null clears it. */
  bool set_creative(const uint8_t *rgb, int size, float strength, std::string &error)
  {
    if(device) vkDeviceWaitIdle(device);
    destroy(creative_lut);
    const bool any = rgb && size >= 2;
    creative_size = any ? size : 0;
    creative_strength = any ? std::clamp(strength, 0.0f, 1.0f) : 0.0f;
    Lut atlas;
    atlas.width = any ? size : 1;
    atlas.height = any ? size * size : 1;
    atlas.channels = 4;
    // Packed as RGBA8 bytes; upload_lut only needs the byte layout and format below.
    const size_t texels = size_t(atlas.width) * atlas.height;
    atlas.texels.assign(texels * 4, 255);
    if(any)
      for(size_t i = 0; i < texels; i++) std::memcpy(&atlas.texels[i * 4], rgb + i * 3, 3);
    return upload_image(atlas, VK_FORMAT_R8G8B8A8_UNORM, creative_lut, error);
  }

  bool pick_device(std::string &error)
  {
    uint32_t count = 0;
    vkEnumeratePhysicalDevices(instance, &count, nullptr);
    std::vector<VkPhysicalDevice> devices(count);
    vkEnumeratePhysicalDevices(instance, &count, devices.data());
    std::string rejected;
    int best_rank = -1;
    for(VkPhysicalDevice candidate : devices)
    {
      VkPhysicalDeviceSubgroupProperties subgroup{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_PROPERTIES};
      VkPhysicalDeviceProperties2 props{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
      props.pNext = &subgroup;
      vkGetPhysicalDeviceProperties2(candidate, &props);
      const VkPhysicalDeviceProperties &p = props.properties;
      std::string why;
      if(p.apiVersion < VK_API_VERSION_1_1) why = "Vulkan 1.0 only";
      else if(!(subgroup.supportedStages & VK_SHADER_STAGE_COMPUTE_BIT) ||
              (subgroup.supportedOperations & (VK_SUBGROUP_FEATURE_BASIC_BIT | VK_SUBGROUP_FEATURE_ARITHMETIC_BIT)) !=
                  (VK_SUBGROUP_FEATURE_BASIC_BIT | VK_SUBGROUP_FEATURE_ARITHMETIC_BIT))
        why = "no compute subgroup arithmetic";
      else if(!format_ok(candidate, kHalf, VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT |
                                               VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT))
        why = "no rgba16f storage";
      else if(!format_ok(candidate, kByte, VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT |
                                               VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT))
        why = "no rgba8 storage";
      else if(!format_ok(candidate, VK_FORMAT_R32G32B32A32_SFLOAT, VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT))
        why = "no rgba32f sampling";

      uint32_t families = 0;
      vkGetPhysicalDeviceQueueFamilyProperties(candidate, &families, nullptr);
      std::vector<VkQueueFamilyProperties> family_props(families);
      vkGetPhysicalDeviceQueueFamilyProperties(candidate, &families, family_props.data());
      int family = -1;
      for(uint32_t i = 0; i < families && family < 0; i++)
        if(family_props[i].queueFlags & VK_QUEUE_COMPUTE_BIT) family = static_cast<int>(i);
      if(why.empty() && family < 0) why = "no compute queue";
      if(!why.empty())
      {
        rejected += std::string(p.deviceName) + ": " + why + "; ";
        continue;
      }
      const int rank = p.deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU     ? 3
                       : p.deviceType == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU ? 2
                                                                                 : 1;
      if(rank <= best_rank) continue;
      best_rank = rank;
      physical = candidate;
      queue_family = static_cast<uint32_t>(family);
      limits = p.limits;
      info.name = p.deviceName;
      info.api_version = p.apiVersion;
      info.subgroup_size = subgroup.subgroupSize;
      info.timestamps = p.limits.timestampComputeAndGraphics && family_props[family].timestampValidBits > 0;
    }
    if(!physical)
    {
      error = "no usable Vulkan GPU (" + (rejected.empty() ? std::string("none found") : rejected) + ")";
      return false;
    }
    vkGetPhysicalDeviceMemoryProperties(physical, &memory_props);
    return true;
  }

  static bool format_ok(VkPhysicalDevice device, VkFormat format, VkFormatFeatureFlags need)
  {
    VkFormatProperties props;
    vkGetPhysicalDeviceFormatProperties(device, format, &props);
    return (props.optimalTilingFeatures & need) == need;
  }

  bool create_pipelines(std::string &error)
  {
    VkDescriptorSetLayoutBinding uniforms[2] = {};
    for(uint32_t i = 0; i < 2; i++)
    {
      uniforms[i].binding = i;
      uniforms[i].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
      uniforms[i].descriptorCount = 1;
      uniforms[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    VkDescriptorSetLayoutCreateInfo set0_info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    set0_info.bindingCount = 2;
    set0_info.pBindings = uniforms;
    VK_TRY(vkCreateDescriptorSetLayout(device, &set0_info, nullptr, &set0), "vkCreateDescriptorSetLayout");

    for(int k = 0; k < kKernelCount; k++)
    {
      const KernelSpec &spec = kKernels[k];
      const ShaderBlob *blob = find_shader(spec.name);
      if(!blob)
      {
        error = std::string("missing SPIR-V for ") + spec.name;
        return false;
      }
      Pipeline &p = pipelines[k];
      VkShaderModuleCreateInfo module_info{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
      module_info.codeSize = blob->size;
      module_info.pCode = reinterpret_cast<const uint32_t *>(blob->code);
      VK_TRY(vkCreateShaderModule(device, &module_info, nullptr, &p.module), spec.name);

      std::vector<VkDescriptorSetLayoutBinding> bindings;
      for(uint32_t i = 0; spec.bindings[i]; i++)
      {
        VkDescriptorSetLayoutBinding b{};
        b.binding = i;
        b.descriptorType = descriptor_type(spec.bindings[i]);
        b.descriptorCount = 1;
        b.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        bindings.push_back(b);
      }
      VkDescriptorSetLayoutCreateInfo set1_info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
      set1_info.bindingCount = static_cast<uint32_t>(bindings.size());
      set1_info.pBindings = bindings.data();
      VK_TRY(vkCreateDescriptorSetLayout(device, &set1_info, nullptr, &p.set1), spec.name);

      const VkDescriptorSetLayout sets[2] = {set0, p.set1};
      VkPushConstantRange range{VK_SHADER_STAGE_COMPUTE_BIT, 0, spec.push_size};
      VkPipelineLayoutCreateInfo layout_info{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
      layout_info.setLayoutCount = 2;
      layout_info.pSetLayouts = sets;
      layout_info.pushConstantRangeCount = spec.push_size ? 1 : 0;
      layout_info.pPushConstantRanges = &range;
      VK_TRY(vkCreatePipelineLayout(device, &layout_info, nullptr, &p.layout), spec.name);

      VkComputePipelineCreateInfo pipeline_info{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
      pipeline_info.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
      pipeline_info.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
      pipeline_info.stage.module = p.module;
      pipeline_info.stage.pName = "main";
      pipeline_info.layout = p.layout;
      VK_TRY(vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pipeline_info, nullptr, &p.pipeline), spec.name);
    }
    return true;
  }

  static VkDescriptorType descriptor_type(char kind)
  {
    return kind == 'S'   ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
           : kind == 'I' ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                         : VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
  }

  bool upload_lut(const Lut &lut, Image &image, std::string &error)
  {
    static const VkFormat half_formats[] = {VK_FORMAT_R16_SFLOAT, VK_FORMAT_R16G16_SFLOAT,
                                            VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R16G16B16A16_SFLOAT};
    static const VkFormat float_formats[] = {VK_FORMAT_R32_SFLOAT, VK_FORMAT_R32G32_SFLOAT,
                                             VK_FORMAT_R32G32B32A32_SFLOAT, VK_FORMAT_R32G32B32A32_SFLOAT};
    return upload_image(lut, (lut.half ? half_formats : float_formats)[lut.channels - 1], image, error);
  }

  /** Creates a sampled image in [format] holding [lut]'s texel bytes as-is. */
  bool upload_image(const Lut &lut, VkFormat format, Image &image, std::string &error)
  {
    if(!create_image(image, lut.width, lut.height, format,
                     VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, error))
      return false;
    Buffer staging;
    if(!create_buffer(staging, lut.texels.size(), VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true, error)) return false;
    std::memcpy(staging.mapped, lut.texels.data(), lut.texels.size());
    bool ok = begin(error);
    if(ok)
    {
      to_general(cmd, {image.image});
      VkBufferImageCopy region;
      copy_region(region, lut.width, lut.height);
      vkCmdCopyBufferToImage(cmd, staging.buffer, image.image, VK_IMAGE_LAYOUT_GENERAL, 1, &region);
      memory_barrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                     VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
      ok = submit_and_wait(error);
    }
    destroy(staging);
    return ok;
  }

  // ----- frame --------------------------------------------------------------

  // Raw denoise on the mosaic before demosaicing, adapted from vkdt's denoise module
  // (shaders/denoise): half-size rgb, four levels of edge-aware a-trous wavelets with
  // noise-normalised soft shrinkage, then the finest band on the full mosaic. Values are
  // normalised with `black` / `white` on read; the noise model is variance a + b x in
  // those units. Returns the denoised mosaic as integers spanning 0..kMergeScale.
  int raw_denoise(Graph &g, Ref mosaic, uint32_t mw, uint32_t mh, const float black[4], float white,
                  const int cfa[4], const float wb[3], float noise_a, float noise_b, float strength)
  {
    const uint32_t hw = mw / 2, hh = mh / 2;
    struct
    {
      float black[4];
      float white;
      int32_t pad[3];
      int32_t cfa[4];
    } half_push{};
    std::memcpy(half_push.black, black, sizeof(half_push.black));
    half_push.white = white;
    for(int i = 0; i < 4; i++) half_push.cfa[i] = cfa[i];
    const int half = g.image(hw, hh);
    g.pixels(kDnHalf, {mosaic, img(half)}, half, half_push);

    struct
    {
      float noise_a, noise_b;
      int32_t level, block;
    } down_push{noise_a, noise_b, 0, 2};
    int level[4];
    level[0] = g.image(hw, hh);
    const int cov = g.image(hw, hh); // structure tensor, unused further on
    g.pixels(kDnDowncov, {img(half), img(level[0]), img(cov)}, level[0], down_push);
    for(int i = 1; i < 4; i++)
    {
      down_push.level = i;
      level[i] = g.image(hw, hh);
      g.pixels(kDnDown, {img(level[i - 1]), img(level[i])}, level[i], down_push);
    }

    struct
    {
      float wb[4];
      float noise_a, noise_b, strength, luma;
    } assemble_push{{wb[0], wb[1], wb[2], 1.0f}, noise_a, noise_b, strength, kDenoiseLuma};
    const int assembled = g.image(hw, hh);
    g.pixels(kDnAssemble, {img(half), img(level[0]), img(level[1]), img(level[2]), img(level[3]), img(assembled)},
             assembled, assemble_push);

    struct
    {
      float black[4];
      float white, noise_a, noise_b, strength;
      int32_t cfa[4];
      float luma, detail, scale, pad;
    } doub_push{};
    std::memcpy(doub_push.black, black, sizeof(doub_push.black));
    doub_push.white = white;
    doub_push.noise_a = noise_a;
    doub_push.noise_b = noise_b;
    doub_push.strength = strength;
    for(int i = 0; i < 4; i++) doub_push.cfa[i] = cfa[i];
    doub_push.luma = kDenoiseLuma;
    doub_push.detail = 1.0f;
    doub_push.scale = kMergeScale;
    static_assert(sizeof(doub_push) == 64, "dn_doub push constants");
    const int out = g.image(mw, mh, VK_FORMAT_R32_UINT);
    g.pixels(kDnDoub, {mosaic, img(assembled), img(half), img(out)}, out, doub_push);
    return out;
  }

  // Chroma noise reduction of the scene-linear `input` (see chroma.comp): colour from a
  // Gaussian blur of 0.3% of the long edge (12 px at 4000), at luminance edges from one
  // of 0.05% (2 px); luminance untouched.
  int chroma_denoise(Graph &g, int input, uint32_t w, uint32_t h, float strength)
  {
    struct
    {
      float strength, floor_, edge_lo, edge_hi;
    } push{strength, 0.02f, 0.8f, 2.0f};
    const float sigma_frac = 0.003f;
    const float long_edge = static_cast<float>(std::max(w, h));
    const int blurred = g.blur(input, sigma_frac * long_edge, true);
    const int narrow = g.blur(input, 0.0005f * long_edge, false);
    const int out = g.image(w, h);
    g.pixels(kChroma, {img(input), img(blurred), img(narrow), img(out)}, out, push);
    return out;
  }

  // Local tone compression of the scene-linear `input` (see ltm.comp): guided filter
  // of log luminance at 1/4 resolution with a radius of about 2% of the long edge.
  int local_tone(Graph &g, int input, uint32_t w, uint32_t h, float strength)
  {
    struct
    {
      int32_t mode;
      float strength, eps, anchor;
    } push{0, strength, 0.3f, std::log2(0.18f)};
    const int factor = 4;
    const int small = g.image((w + factor - 1) / factor, (h + factor - 1) / factor);
    const int32_t f = factor;
    g.pixels(kDownsample, {img(input), img(small)}, small, f);
    const int stats = g.image(g.images[small].width, g.images[small].height);
    g.pixels(kLtm, {img(small), img(small), img(stats)}, stats, push);
    const float sigma = 0.02f * static_cast<float>(std::max(w, h)) / factor;
    const int mean_stats = g.blur(stats, sigma, true);
    const int coef = g.image(g.images[mean_stats].width, g.images[mean_stats].height);
    push.mode = 1;
    g.pixels(kLtm, {img(mean_stats), img(mean_stats), img(coef)}, coef, push);
    const int mean_coef = g.blur(coef, sigma * g.images[coef].width / g.images[small].width, true);
    const int out = g.image(w, h);
    push.mode = 2;
    g.pixels(kLtm, {img(input), img(mean_coef), img(out)}, out, push);
    return out;
  }

  // filmsim_make_plan(): the film (and, unless scanning, the print) as compute passes.
  int develop_film(Graph &g, const FilmParams &params, int input, uint32_t w, uint32_t h)
  {
    const bool print_neg = params.process == kProcessPrintNegative;
    const bool couplers = params.couplers > 0;
    const bool halation = params.halation != 0;
    const bool scatter = params.scat_amt > 0.0f;
    const bool use_curvewarp = couplers && !print_neg;
    const bool fastpath = !print_neg && !couplers && !halation;
    const float um_to_px = static_cast<float>(std::max(w, h)) / FILMSIM_FRAME_WIDTH_UM;
    const float cp_sigma = params.couplers_radius * um_to_px;
    const float hal_sigma = params.halation_radius * um_to_px;
    const int hal_bounces = std::clamp(params.hal_bnc, 1, FILMSIM_MAX_HAL_BOUNCES);

    const int32_t sizes[4] = {static_cast<int32_t>(w), static_cast<int32_t>(h), static_cast<int32_t>(w),
                              static_cast<int32_t>(h)};
    g.pass(kSetup, {kFilmsimLut, kPrep, kSpectraLut}, 1, 1, sizes);
    int cwarp = -1;
    if(use_curvewarp)
    {
      cwarp = g.image(FILMSIM_LOGEXP_GRID, 1);
      g.pass(kDirlut, {img(cwarp), kPrep}, 1, 1);
    }

    int developed = -1;
    if(print_neg)
    {
      developed = g.image(w, h);
      g.pixels(kNegprint, {img(input), kSpectraLut, kPrep, img(developed)}, developed);
    }
    else if(fastpath)
    {
      developed = g.image(w, h);
      g.pixels(kFast, {img(input), kPrep, kSpectraLut, img(developed)}, developed);
    }
    else
    {
      const int coupler = couplers ? g.image(w, h) : g.image(1, 1);
      const int exposure = g.image(w, h);
      g.pixels(kExpose, {img(input), kSpectraLut, kPrep, img(coupler), img(exposure)}, exposure);
      // Coupler inhibition is read with texture(): a low-resolution blur is fine.
      const int coupler_blur = couplers ? g.blur(coupler, cp_sigma, true) : coupler;
      const int dev1 = g.image(w, h);
      if(halation) g.pixels(kHalin, {img(exposure), kPrep, img(coupler_blur), img(dev1)}, dev1);
      else g.pixels(kDevelop, {img(exposure), kPrep, img(cwarp), img(coupler_blur), img(dev1)}, dev1);
      developed = dev1;
      if(halation)
      {
        int source_exposure = dev1;
        if(scatter)
        { // scatter reads its blurs with texelFetch: keep them at full resolution
          const float um[4] = {kScatterCoreUm, kScatterTailUm * kScatterExpRatio[0],
                               kScatterTailUm * kScatterExpRatio[1], kScatterTailUm * kScatterExpRatio[2]};
          int blurred[4];
          for(int k = 0; k < 4; k++) blurred[k] = g.blur(dev1, um[k] * um_to_px, false);
          source_exposure = g.image(w, h);
          g.pixels(kScatter, {img(dev1), img(source_exposure), img(blurred[0]), img(blurred[1]),
                              img(blurred[2]), img(blurred[3])}, source_exposure);
        }
        int hal[FILMSIM_MAX_HAL_BOUNCES];
        for(int k = 0; k < FILMSIM_MAX_HAL_BOUNCES; k++)
          hal[k] = k < hal_bounces ? g.blur(source_exposure, hal_sigma * std::sqrt(k + 1.0f), true) : hal[0];
        developed = g.image(w, h);
        g.pixels(kHalout, {img(source_exposure), kPrep, cwarp >= 0 ? img(cwarp) : kFilmsimLut, img(developed),
                           img(hal[0]), img(hal[1]), img(hal[2]), img(hal[3]), img(hal[4]), img(hal[5])},
                 developed);
      }
    }

    return developed;
  }

  Graph build_graph(const FilmParams &params, uint32_t w, uint32_t h, const SourceImage &source,
                    const DevelopOptions &options, int &output)
  {
    Graph g;
    const int input = g.image(w, h);
    struct
    {
      Rows m;
      int32_t srgb_eotf;
      float gain;
    } decode{rows(source.space == ColourSpace::Srgb        ? kSrgbToRec2020
                  : source.space == ColourSpace::DisplayP3 ? kP3ToRec2020
                                                           : kIdentity),
             source.rgba8 ? 1 : 0, std::exp2(options.exposure_ev)};
    if(source.raw)
    {
      const RawImage &raw = *source.raw;
      struct
      {
        float black[4];
        float wb_white[4];
        Rows m;
        int32_t cfa[4];
        float map[4];
        int32_t map_size[4];
      } push{};
      static_assert(sizeof(push) == 128, "raw push constants");
      std::memcpy(push.black, raw.black, sizeof(push.black));
      push.wb_white[0] = raw.wb[0];
      push.wb_white[1] = raw.wb[1];
      push.wb_white[2] = raw.wb[2];
      push.wb_white[3] = raw.white;
      push.m = rows(raw.camera_to_rec2020);
      push.m.row0[3] = std::exp2(options.exposure_ev);
      std::memcpy(push.cfa, raw.cfa_colour, sizeof(push.cfa));
      const bool map = !raw.gain_map.empty();
      push.map[0] = raw.map_origin[0];
      push.map[1] = raw.map_origin[1];
      push.map[2] = raw.map_spacing[0] > 0 ? raw.map_spacing[0] : 1.0f;
      push.map[3] = raw.map_spacing[1] > 0 ? raw.map_spacing[1] : 1.0f;
      push.map_size[0] = raw.map_width;
      push.map_size[1] = raw.map_height;
      push.map_size[2] = map && raw.map_width >= 2 && raw.map_height >= 2;
      Ref sensor = upload(0);
      // With a flash mix the last frame is the flash frame: only the others are merged.
      const bool flash = options.flash.enabled && source.burst_count >= 2;
      // Flash white balance: frame 0 is the flash frame, frame 1 the ambient one (not merged).
      const bool white_map = options.flash_white.enabled && !flash && source.burst_count == 2;
      const int ambient_frames = white_map ? 1 : std::min(source.burst_count, kMaxBurst) - (flash ? 1 : 0);
      if(ambient_frames > 1)
      { // merge first; the merged mosaic is black-subtracted, at frame 0's exposure, and spans 0..kMergeScale
        const int frames = ambient_frames;
        note("合并", "合并帧数", "%d", frames);
        const int merged = g.image(raw.width, raw.height, VK_FORMAT_R32_UINT);
        struct
        {
          float black[4];
          float white, noise_s, noise_o, strength;
          int32_t frames, tiles_x, tiles_y, tile;
          float gain[6];
          float scale, pad;
        } merge{};
        static_assert(sizeof(merge) == 80, "merge push constants");
        std::memcpy(merge.black, raw.black, sizeof(merge.black));
        merge.white = raw.white;
        // Without a NoiseProfile assume a typical phone sensor at mid ISO.
        merge.noise_s = raw.noise_scale > 0 ? raw.noise_scale : 2e-4f;
        merge.noise_o = raw.noise_scale > 0 ? raw.noise_offset : 2e-6f;
        merge.strength = 8.0f;
        merge.frames = frames;
        merge.tiles_x = alignment.tiles_x;
        merge.tiles_y = alignment.tiles_y;
        merge.tile = alignment.tile;
        for(int i = 0; i < 6; i++)
          merge.gain[i] = i < frames && i < static_cast<int>(alignment.gain.size()) ? alignment.gain[i] : 1.0f;
        merge.scale = kMergeScale;
        std::vector<Ref> bindings;
        for(int i = 0; i < kMaxBurst; i++) bindings.push_back(upload(i < frames ? i : 0));
        bindings.push_back(kOffsets);
        bindings.push_back(img(merged));
        g.pixels(kMerge, bindings, merged, merge);
        sensor = img(merged);
        for(float &b : push.black) b = 0.0f;
        push.wb_white[3] = kMergeScale;
      }
      float noise_gain = 1.0f; // how much the mixes scaled the signal (and so its noise)
      if(white_map)
      {
        const RawImage &amb = *source.burst[1];
        struct
        {
          float f_black[4], a_black[4];
          float f_white, a_white, ambient_scale;
          int32_t ambient_index, tiles_x, tiles_y, tile, pad;
        } frac{};
        static_assert(sizeof(frac) == 64, "flashfrac push constants");
        std::memcpy(frac.f_black, push.black, sizeof(frac.f_black));
        std::memcpy(frac.a_black, amb.black, sizeof(frac.a_black));
        frac.f_white = push.wb_white[3];
        frac.a_white = amb.white;
        frac.ambient_scale = flash_ambient_k;
        frac.ambient_index = 1;
        frac.tiles_x = alignment.tiles_x;
        frac.tiles_y = alignment.tiles_y;
        frac.tile = alignment.tile;
        const int share = g.image(raw.width / 2, raw.height / 2);
        g.pixels(kFlashfrac, {sensor, upload(1), kOffsets, img(share)}, share, frac);
        // Smooth: the white changes with the lighting, not with texture or misregistration.
        const int smooth = g.blur(share, 0.015f * static_cast<float>(std::max(raw.width, raw.height)) / 2.0f, true);
        struct
        {
          float black[4], n_ambient[4], n_flash[4];
          int32_t cfa[4];
          float white, scale, pad0, pad1;
        } wbp{};
        static_assert(sizeof(wbp) == 80, "flashwb push constants");
        std::memcpy(wbp.black, push.black, sizeof(wbp.black));
        for(int c = 0; c < 3; c++)
        {
          wbp.n_ambient[c] = options.flash_white.ambient[c];
          wbp.n_flash[c] = options.flash_white.flash[c];
        }
        for(int i = 0; i < 4; i++) wbp.cfa[i] = raw.cfa_colour[i];
        wbp.white = push.wb_white[3];
        wbp.scale = 0.5f * kMergeScale;
        const int balanced = g.image(raw.width, raw.height, VK_FORMAT_R32_UINT);
        g.pixels(kFlashwb, {sensor, img(smooth), img(balanced)}, balanced, wbp);
        sensor = img(balanced);
        for(float &b : push.black) b = 0.0f;
        push.wb_white[3] = 0.5f * kMergeScale;
      }
      if(flash)
      {
        const RawImage &fr = *source.burst[ambient_frames];
        struct
        {
          float a_black[4], f_black[4], wb[4];
          float a_white, f_white, ambient_gain, flash_gain;
          int32_t cfa[4];
          float noise_s, noise_o, scale;
          int32_t flash_index, tiles_x, tiles_y, tile, pad;
          float wbg[4];
        } mix{};
        static_assert(sizeof(mix) == 128, "flashmix push constants");
        for(int c = 0; c < 3; c++) mix.wbg[c] = raw.wb[c];
        std::memcpy(mix.a_black, push.black, sizeof(mix.a_black));
        std::memcpy(mix.f_black, fr.black, sizeof(mix.f_black));
        for(int c = 0; c < 3; c++) mix.wb[c] = options.flash.wb[c];
        mix.a_white = push.wb_white[3];
        mix.f_white = fr.white;
        mix.ambient_gain = options.flash.ambient;
        mix.flash_gain = flash_gain;
        for(int i = 0; i < 4; i++) mix.cfa[i] = raw.cfa_colour[i];
        mix.noise_s = raw.noise_scale > 0 ? raw.noise_scale : 2e-4f;
        mix.noise_o = raw.noise_scale > 0 ? raw.noise_offset : 2e-6f;
        mix.scale = kMergeScale;
        mix.flash_index = ambient_frames;
        mix.tiles_x = alignment.tiles_x;
        mix.tiles_y = alignment.tiles_y;
        mix.tile = alignment.tile;
        const int mixed = g.image(raw.width, raw.height, VK_FORMAT_R32_UINT);
        g.pixels(kFlashmix, {sensor, upload(ambient_frames), kOffsets, img(mixed)}, mixed, mix);
        sensor = img(mixed);
        for(float &b : push.black) b = 0.0f;
        push.wb_white[3] = kMergeScale;
        noise_gain = std::max(options.flash.ambient, flash_gain);
      }
      if(options.raw_denoise > 0.0f && raw.width >= 64 && raw.height >= 64)
      {
        // Noise model of the reference frame (DNG NoiseProfile, normalised units). A merge
        // averages frames: the variance drops by the frames' total light relative to the
        // reference (sum of 1 / gain), counted at half since robust weights and alignment
        // keep a real merge from reaching it.
        const float s = raw.noise_scale > 0 ? raw.noise_scale : 2e-4f;
        const float o = raw.noise_scale > 0 ? raw.noise_offset : 2e-6f;
        float frames = 1.0f;
        if(source.burst_count > 1)
        {
          float light = 0.0f;
          for(int i = 0; i < ambient_frames; i++)
            light += i < static_cast<int>(alignment.gain.size()) && alignment.gain[i] > 0 ? 1.0f / alignment.gain[i] : 1.0f;
          frames = 1.0f + 0.5f * (light - 1.0f);
        }
        const float o_gain = noise_gain;
        const float wb[3] = {raw.wb[0], raw.wb[1], raw.wb[2]};
        // vkdt fits its noise profile on values normalised to [0, 1] but compares it with
        // coefficients in 16-bit units, so its sigma is effectively 65535 / (white - black)
        // times the normalised one, and the shrinkage threshold and detail test are tuned at
        // that scale. Scaling the variance the same way keeps vkdt's behaviour for a strength.
        const float range = raw.white - 0.25f * (raw.black[0] + raw.black[1] + raw.black[2] + raw.black[3]);
        const float kappa = range > 0.0f ? 65535.0f / range : 1.0f;
        // Scaling the signal by k scales the noise variance s x + o into k s x + k^2 o.
        const float kappa2 = kappa * kappa;
        frames /= noise_gain;
        note("降噪", "RAW 降噪 (vkdt)", "强度 %.2f，σ 刻度 κ %.1f，合并等效帧 %.2f，信号增益 %.2f", options.raw_denoise, kappa,
             frames * noise_gain, noise_gain);
        note("降噪", "使用的噪声模型 (s o)", "%.4g %.4g", s, o);
        const int denoised = raw_denoise(g, sensor, raw.width, raw.height, push.black, push.wb_white[3], raw.cfa_colour,
                                         wb, kappa2 * o * o_gain / frames, kappa2 * s / frames, std::min(options.raw_denoise, 1.0f));
        sensor = img(denoised);
        for(float &b : push.black) b = 0.0f;
        push.wb_white[3] = kMergeScale;
      }
      const bool native = static_cast<uint32_t>(raw.width) == w && static_cast<uint32_t>(raw.height) == h;
      const int developed = native ? input : g.image(raw.width, raw.height);
      g.pixels(kRaw, {sensor, kGainMap, img(developed)}, developed, push);
      if(!native)
      {
        decode.m = rows(kIdentity);
        decode.srgb_eotf = 0;
        decode.gain = 1.0f;
        g.pixels(kDecode, {img(developed), img(input)}, input, decode);
      }
    }
    else g.pixels(kDecode, {kUpload, img(input)}, input, decode);

    int scene = input;
    note("降噪", "彩色降噪", options.chroma_denoise > 0 ? "%.2f" : "关", options.chroma_denoise);
    note("影调", "HDR 影调压缩", options.local_tone > 0 ? "%.2f" : "关", options.local_tone);
    note("冲洗曝光", "工作尺寸", "%ux%u（长边上限 %d）", w, h, options.max_long_edge);
    if(options.chroma_denoise > 0.0f)
      scene = chroma_denoise(g, scene, w, h, std::min(options.chroma_denoise, 1.0f));
    if(options.local_tone > 0.0f) scene = local_tone(g, scene, w, h, std::min(options.local_tone, 1.0f));

    // Film off: the scene itself under a neutral curve (raw) or as shot (display-referred).
    int developed = scene;
    int mode = source.raw ? 1 : 0;
    if(options.stages.film)
    {
      developed = develop_film(g, params, scene, w, h);
      // Print off: slides are positive already; negatives get the lab-scan inversion.
      mode = !options.stages.print && !is_positive_film(params.film) ? 2 : 0;
    }
    if(options.calibrate) mode = 3;

    output = g.image(w, h, kByte);
    struct
    {
      Rows m;
      float lut[4]; // strength, edge size, mode
      float scan[3][4]; // negative scan: mid grey log2 transmittance, slope, toe
    } encode{rows(options.output == ColourSpace::DisplayP3 ? kRec2020ToP3 : kRec2020ToSrgb),
             {creative_size > 1 && !options.calibrate ? creative_strength : 0.0f, static_cast<float>(creative_size),
              static_cast<float>(mode), 0},
             {}};
    static_assert(sizeof(encode) == 112, "encode push constants");
    if(mode == 2) std::memcpy(encode.scan, scan_cal, sizeof(scan_cal));
    g.pixels(kEncode, {img(developed), img(output), kCreative}, output, encode);
    return g;
  }

  bool develop(const SourceImage &source, const FilmParams &raw_params, const DevelopOptions &options,
               DevelopResult &result, std::string &error)
  {
    SourceImage src = source;
    alignment = {};
    record.clear();
    // Flash white balance: develop the flash frame (given last), compare the ambient one.
    const RawImage *white_order[2] = {nullptr, nullptr};
    const bool white_map = options.flash_white.enabled && !options.flash.enabled && source.burst &&
                           source.burst_count == 2;
    if(white_map)
    {
      white_order[0] = source.burst[1];
      white_order[1] = source.burst[0];
      src.burst = white_order;
    }
    if(src.burst && src.burst_count > 0)
    {
      if(src.raw)
      {
        error = "pass either raw or burst";
        return false;
      }
      src.raw = src.burst[0];
      src.burst_count = std::min(src.burst_count, kMaxBurst);
      if(src.burst_count > 1)
      {
        const std::vector<const RawImage *> frames(src.burst, src.burst + src.burst_count);
        for(const RawImage *f : frames)
          if(f->cfa.size() != src.raw->cfa.size() || f->width != src.raw->width)
          {
            error = "burst frames differ in size";
            return false;
          }
        // A flash frame shares the ambient frames' exposure but not their light: it is
        // matched by structure. So is the ambient frame against a flash reference.
        std::vector<char> same(frames.size(), 0);
        if(options.flash.enabled || white_map) same.back() = 2;
        if(!align_burst(frames, alignment, error, &same)) return false;
      }
    }
    else src.burst_count = 0;
    const bool flash = options.flash.enabled && src.burst_count >= 2;
    if((flash || white_map) && !alignment.offsets.empty())
    { // The flash re-lights the scene, which misleads per-tile matching: tiles of one
      // frame then disagree and the flash-only light shows their seams. The frames are
      // a fraction of a second apart, so one global shift (the tiles' median) is used.
      const int tiles = alignment.tiles_x * alignment.tiles_y;
      int32_t *o = &alignment.offsets[static_cast<size_t>(src.burst_count - 1) * tiles * 2];
      std::vector<int32_t> xs, ys;
      for(int t = 0; t < tiles; t++) { xs.push_back(o[2 * t]); ys.push_back(o[2 * t + 1]); }
      std::nth_element(xs.begin(), xs.begin() + tiles / 2, xs.end());
      std::nth_element(ys.begin(), ys.begin() + tiles / 2, ys.end());
      const int32_t mx = xs[tiles / 2] & ~1, my = ys[tiles / 2] & ~1; // keep the CFA phase
      for(int t = 0; t < tiles; t++) { o[2 * t] = mx; o[2 * t + 1] = my; }
    }
    flash_ambient_k = 1.0f;
    if(white_map && src.burst_count == 2) flash_ambient_k = flash_ambient_scale(*src.burst[0], *src.burst[1], alignment, 1);
    float level = 0.0f;
    if(flash) level = flash_level(*src.burst[0], *src.burst[src.burst_count - 1], alignment, src.burst_count - 1);
    if(src.raw)
    {
      src.width = src.raw->width;
      src.height = src.raw->height;
      if(src.raw->cfa.size() != static_cast<size_t>(src.width) * src.height)
      {
        error = "raw image data does not match its size";
        return false;
      }
    }
    if(src.width <= 0 || src.height <= 0 || (!!src.rgba8 + !!src.rgba32f + !!src.raw) != 1)
    {
      error = "source needs a size and exactly one of rgba8 / rgba32f / raw";
      return false;
    }
    DevelopOptions opts = options;
    if(src.raw && opts.match_key > 0.0f)
    {
      const float key = raw_key(*src.raw);
      if(key > 0.0f) opts.exposure_ev += std::clamp(std::log2(opts.match_key / key), -6.0f, 6.0f);
    }
    // Flash gain: the develop scales the raw by 2^ev, so the flash-only light at its 98th
    // percentile lands on the target with this gain.
    flash_gain = 0.0f;
    if(flash && level > 0.0f)
      flash_gain = std::clamp(opts.flash.target / (level * std::exp2(opts.exposure_ev)), 0.0f, opts.flash.max_gain);
    opts.flash.enabled = flash;
    // The record: what each raw frame carried, how the frames were registered, exposure.
    const int frame_count = src.raw ? std::max(1, src.burst_count) : 0;
    for(int i = 0; i < frame_count; i++)
    {
      const RawImage &r = src.burst_count > 0 ? *src.burst[i] : *src.raw;
      char k[64];
      std::snprintf(k, sizeof(k), "帧 %d 尺寸 / 白电平 / 黑电平", i);
      note("RAW 数据", k, "%dx%d / %.0f / %.0f %.0f %.0f %.0f", r.width, r.height, r.white, r.black[0], r.black[1], r.black[2], r.black[3]);
      std::snprintf(k, sizeof(k), "帧 %d 白平衡增益 (R G B)", i);
      note("RAW 数据", k, "%.4f %.4f %.4f", r.wb[0], r.wb[1], r.wb[2]);
      std::snprintf(k, sizeof(k), "帧 %d 噪声模型 (s o)", i);
      note("RAW 数据", k, "%.4g %.4g", r.noise_scale, r.noise_offset);
      std::snprintf(k, sizeof(k), "帧 %d 画面亮度 key", i);
      note("RAW 数据", k, "%.5f", raw_key(r));
      if(i == 0)
      {
        static const char rgb[] = "RGB";
        note("RAW 数据", "CFA 排列", "%c%c%c%c", rgb[r.cfa_colour[0]], rgb[r.cfa_colour[1]], rgb[r.cfa_colour[2]], rgb[r.cfa_colour[3]]);
        note("RAW 数据", "方向 (TIFF)", "%d", r.orientation);
        note("RAW 数据", "镜头阴影图", r.gain_map.empty() ? "无" : "%dx%d", r.map_width, r.map_height);
        const float *m = r.camera_to_rec2020;
        note("RAW 数据", "相机 → Rec.2020 矩阵", "%.3f %.3f %.3f / %.3f %.3f %.3f / %.3f %.3f %.3f", m[0], m[1], m[2], m[3], m[4],
             m[5], m[6], m[7], m[8]);
      }
    }
    if(!alignment.offsets.empty())
    {
      const int tiles = alignment.tiles_x * alignment.tiles_y;
      note("对齐", "分块", "%dx%d 块，每块 %d px", alignment.tiles_x, alignment.tiles_y, alignment.tile);
      for(int f = 1; f < src.burst_count; f++)
      {
        // median tile offset and spread: what registered the frame
        std::vector<int32_t> xs, ys;
        for(int t = 0; t < tiles; t++)
        {
          xs.push_back(alignment.offsets[(static_cast<size_t>(f) * tiles + t) * 2]);
          ys.push_back(alignment.offsets[(static_cast<size_t>(f) * tiles + t) * 2 + 1]);
        }
        const auto [x0, x1] = std::minmax_element(xs.begin(), xs.end());
        const auto [y0, y1] = std::minmax_element(ys.begin(), ys.end());
        std::nth_element(xs.begin(), xs.begin() + tiles / 2, xs.end());
        std::nth_element(ys.begin(), ys.begin() + tiles / 2, ys.end());
        const float g = f < static_cast<int>(alignment.gain.size()) ? alignment.gain[f] : 1.0f;
        char k[48];
        std::snprintf(k, sizeof(k), "帧 %d", f);
        note("对齐", k, "位移中位 (%d, %d) px，范围 x %d…%d y %d…%d，曝光比 %.3f", xs[tiles / 2], ys[tiles / 2], *x0, *x1, *y0,
             *y1, g > 0 ? 1.0f / g : 0.0f);
      }
    }
    if(src.raw)
    {
      const float key = raw_key(*src.raw);
      note("冲洗曝光", "取景器亮度 key", "%.5f", options.match_key);
      note("冲洗曝光", "参考帧亮度 key", "%.5f", key);
      note("冲洗曝光", "对齐取景器提亮", options.match_key > 0 && key > 0 ? "%+.2f EV" : "未对齐（%.0f）",
           options.match_key > 0 && key > 0 ? std::clamp(std::log2(options.match_key / key), -6.0f, 6.0f) : 0.0f);
      note("冲洗曝光", "最终曝光", "%+.2f EV", opts.exposure_ev);
    }
    if(flash)
    {
      note("闪光", "合成", "环境光增益 %.3f，目标 %.3f，上限 %.2f", opts.flash.ambient, opts.flash.target, opts.flash.max_gain);
      note("闪光", "闪光单独亮度 (98%)", "%.4f", level);
      note("闪光", "闪光增益", "%.3f", flash_gain);
      note("闪光", "闪光 → 环境白 (R G B)", "%.3f %.3f %.3f", opts.flash.wb[0], opts.flash.wb[1], opts.flash.wb[2]);
    }
    if(white_map)
    {
      note("闪光", "分区白平衡", "环境 %.3f %.3f %.3f / 闪光 %.3f %.3f %.3f", options.flash_white.ambient[0],
           options.flash_white.ambient[1], options.flash_white.ambient[2], options.flash_white.flash[0],
           options.flash_white.flash[1], options.flash_white.flash[2]);
      note("闪光", "闪光帧 / 环境帧曝光比", "%.3f", flash_ambient_k);
    }
    const bool ok = develop_source(src, raw_params, opts, result, error);
    if(ok)
    {
      note("冲洗结果", "输出尺寸", "%dx%d", result.width, result.height);
      note("冲洗结果", "GPU 计算", "%d 个 pass，GPU %.1f ms，显存峰值 %.1f MB", result.passes, result.gpu_ms,
           result.peak_image_bytes / 1048576.0);
    }
    result.note = record;
    return ok;
  }

  // Negative scan calibration: unexposed, mid grey and two stops over, developed and
  // scanned, give each channel's mid-grey level, contrast and base for the inversion.
  struct ScanCalibration
  {
    FilmParams params;
    ColourSpace output;
    float cal[3][4];
  };
  std::vector<ScanCalibration> scan_cache;
  float scan_cal[3][4] = {};

  bool calibrate_scan(const FilmParams &base, ColourSpace output, std::string &error)
  {
    for(const ScanCalibration &c : scan_cache)
      if(c.output == output && std::memcmp(&c.params, &base, sizeof(FilmParams)) == 0)
      {
        std::memcpy(scan_cal, c.cal, sizeof(scan_cal));
        return true;
      }
    FilmParams p = base;
    p.grain = 0;
    p.halation = 0;
    p.scat_amt = 0.0f;
    p.glare = 0.0f;
    p.couplers_radius = 0.0f;
    constexpr int kPatch = 64;
    const float levels[3] = {0.0f, 0.18f, 0.72f};
    std::vector<float> grid(static_cast<size_t>(3 * kPatch) * kPatch * 4);
    for(int y = 0; y < kPatch; y++)
      for(int x = 0; x < 3 * kPatch; x++)
      {
        float *t = &grid[(static_cast<size_t>(y) * 3 * kPatch + x) * 4];
        t[0] = t[1] = t[2] = levels[x / kPatch];
        t[3] = 1.0f;
      }
    SourceImage source;
    source.width = 3 * kPatch;
    source.height = kPatch;
    source.rgba32f = grid.data();
    source.space = ColourSpace::LinearRec2020;
    DevelopOptions options;
    options.max_long_edge = 3 * kPatch;
    options.output = output;
    options.calibrate = true;
    DevelopResult result;
    if(!develop_source(source, p, options, result, error)) return false;
    // Mean log2 level of each patch's centre (the dither averages out).
    float level[3][3] = {};
    for(int k = 0; k < 3; k++)
    {
      double sum[3] = {};
      int n = 0;
      for(int y = 8; y < kPatch - 8; y++)
        for(int x = k * kPatch + 8; x < (k + 1) * kPatch - 8; x++, n++)
          for(int c = 0; c < 3; c++)
            sum[c] += result.rgba8[(static_cast<size_t>(y) * result.width + x) * 4 + c];
      for(int c = 0; c < 3; c++) level[k][c] = static_cast<float>(sum[c] / n / 255.0 * 20.0 - 16.0);
    }
    ScanCalibration entry{base, output, {}};
    for(int c = 0; c < 3; c++)
    {
      const float mid = level[1][c];
      const float slope = std::max(0.05f, (mid - level[2][c]) / 2.0f); // two stops apart
      entry.cal[0][c] = mid;
      entry.cal[1][c] = slope;
      entry.cal[2][c] = std::min(-1.0f, (mid - level[0][c]) / slope); // base, stops below mid
    }
    if(scan_cache.size() >= 8) scan_cache.erase(scan_cache.begin());
    scan_cache.push_back(entry);
    std::memcpy(scan_cal, entry.cal, sizeof(scan_cal));
    return true;
  }

  bool develop_source(const SourceImage &source, const FilmParams &raw_params, const DevelopOptions &options,
                      DevelopResult &result, std::string &error)
  {
    if(source.rgba8 && source.space == ColourSpace::LinearRec2020)
    {
      error = "8-bit sources must be sRGB or Display P3 encoded";
      return false;
    }
    if(options.output == ColourSpace::LinearRec2020)
    {
      error = "8-bit output must be sRGB or Display P3";
      return false;
    }
    const uint32_t long_edge = static_cast<uint32_t>(std::max(source.width, source.height));
    const uint32_t cap = std::min<uint32_t>(std::max(16, options.max_long_edge), limits.maxImageDimension2D);
    if(static_cast<uint32_t>(std::max(source.width, source.height)) > limits.maxImageDimension2D)
    {
      error = "source larger than the GPU's maximum image size";
      return false;
    }
    const double scale = std::min(1.0, static_cast<double>(cap) / long_edge);
    const uint32_t w = std::max(1u, static_cast<uint32_t>(std::lround(source.width * scale)));
    const uint32_t h = std::max(1u, static_cast<uint32_t>(std::lround(source.height * scale)));

    const bool scanning = options.stages.film && !options.stages.print;
    FilmParams base = raw_params;
    if(scanning) base.process = kProcessScanNegative;
    FilmParams params = resolve_auto_balance(base);
    // Before this frame's uniforms are written: calibration develops a frame of its own.
    if(scanning && !options.calibrate && !is_positive_film(params.film) &&
       !calibrate_scan(base, options.output, error))
      return false;
    params.enlarge = 0;
    params.input_colour = 0;
    std::memcpy(params_ubo.mapped, &params, sizeof(params));
    const int32_t global[4] = {0, 1, static_cast<int32_t>(options.seed), 0}; // frame, frame_cnt, hash
    std::memcpy(global_ubo.mapped, global, sizeof(global));

    int output = -1;
    Graph g = build_graph(params, w, h, source, options, output);

    // Lifetime-based reuse: a logical image takes a free physical image of the
    // same size and format at its first write and returns it after its last read.
    const size_t n_images = g.images.size(), n_passes = g.passes.size();
    std::vector<int> first(n_images, -1), last(n_images, -1);
    for(size_t i = 0; i < n_passes; i++)
      for(const Ref &ref : g.passes[i].bindings)
        if(ref.kind == Ref::Image)
        {
          if(first[ref.id] < 0) first[ref.id] = static_cast<int>(i);
          last[ref.id] = static_cast<int>(i);
        }
    last[output] = static_cast<int>(n_passes);

    std::vector<Image> physical;
    std::vector<int> mapping(n_images, -1);
    std::vector<int> free_list;
    Buffer staging, readback;
    VkDescriptorPool pool = VK_NULL_HANDLE;
    std::vector<Image> uploads;
    Image gain;
    Buffer offsets;
    bool ok = true;
    uint64_t live = 0;
    result.peak_image_bytes = 0;
    for(size_t i = 0; ok && i < n_passes; i++)
    {
      for(size_t id = 0; ok && id < n_images; id++)
      {
        if(first[id] != static_cast<int>(i)) continue;
        const LogicalImage &want = g.images[id];
        auto match = std::find_if(free_list.begin(), free_list.end(), [&](int p) {
          return physical[p].width == want.width && physical[p].height == want.height &&
                 physical[p].format == want.format;
        });
        if(match != free_list.end())
        {
          mapping[id] = *match;
          free_list.erase(match);
        }
        else
        {
          Image image;
          VkImageUsageFlags usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
          if(static_cast<int>(id) == output) usage |= VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
          ok = create_image(image, want.width, want.height, want.format, usage, error);
          physical.push_back(image);
          mapping[id] = static_cast<int>(physical.size()) - 1;
        }
        if(ok) live += physical[mapping[id]].bytes;
      }
      result.peak_image_bytes = std::max(result.peak_image_bytes, live);
      for(size_t id = 0; ok && id < n_images; id++)
        if(last[id] == static_cast<int>(i))
        {
          free_list.push_back(mapping[id]);
          live -= physical[mapping[id]].bytes;
        }
    }

    // Source pixels: rgba8 as-is, float converted to half on the way up, raw CFA
    // as 16-bit integers (one image per burst frame), then the lens shading gain
    // map (1x1 when absent). Burst tile offsets go to a small storage buffer.
    const int frames = source.raw ? std::max(1, source.burst_count) : 1;
    const size_t texels = static_cast<size_t>(source.width) * source.height;
    const size_t frame_bytes = source.raw ? texels * 2 : source.rgba8 ? texels * 4 : texels * 8;
    const VkFormat upload_format = source.raw ? VK_FORMAT_R16_UINT : source.rgba8 ? kByte : kHalf;
    const bool has_map = source.raw && !source.raw->gain_map.empty();
    const uint32_t map_w = has_map ? source.raw->map_width : 1, map_h = has_map ? source.raw->map_height : 1;
    const size_t map_offset = (frame_bytes * frames + 15) & ~size_t(15);
    const size_t map_bytes = static_cast<size_t>(map_w) * map_h * 16;
    uploads.resize(frames);
    for(int f = 0; ok && f < frames; f++)
      ok = create_image(uploads[f], source.width, source.height, upload_format,
                        VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, error);
    if(ok)
      ok = create_image(gain, map_w, map_h, VK_FORMAT_R32G32B32A32_SFLOAT,
                        VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, error);
    if(ok) ok = create_buffer(staging, map_offset + map_bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true, error);
    if(ok) ok = create_buffer(readback, static_cast<VkDeviceSize>(w) * h * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT, true, error);
    if(ok)
      ok = create_buffer(offsets, std::max<size_t>(16, alignment.offsets.size() * sizeof(int32_t)),
                         VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, error);
    if(ok)
    {
      uint8_t *staged = static_cast<uint8_t *>(staging.mapped);
      if(source.raw)
        for(int f = 0; f < frames; f++)
          std::memcpy(staged + f * frame_bytes, (frames > 1 ? source.burst[f] : source.raw)->cfa.data(), frame_bytes);
      else if(source.rgba8) std::memcpy(staged, source.rgba8, frame_bytes);
      else
      {
        uint16_t *dst = reinterpret_cast<uint16_t *>(staged);
        for(size_t i = 0; i < texels * 4; i++) dst[i] = to_half(source.rgba32f[i]);
      }
      if(has_map) std::memcpy(staged + map_offset, source.raw->gain_map.data(), map_bytes);
      else
      {
        const float one[4] = {1, 1, 1, 1};
        std::memcpy(staged + map_offset, one, sizeof(one));
      }
      if(!alignment.offsets.empty())
        std::memcpy(offsets.mapped, alignment.offsets.data(), alignment.offsets.size() * sizeof(int32_t));
    }

    // Descriptor sets: one shared set 0, one set 1 per pass.
    if(ok)
    {
      uint32_t counts[3] = {0, 0, 0};
      for(const Pass &pass : g.passes)
        for(const char *b = kKernels[pass.kernel].bindings; *b; b++)
          counts[*b == 'S' ? 0 : *b == 'I' ? 1 : 2]++;
      const VkDescriptorPoolSize sizes[4] = {
          {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, std::max(1u, counts[0])},
          {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, std::max(1u, counts[1])},
          {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, std::max(1u, counts[2])},
          {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 2}};
      VkDescriptorPoolCreateInfo pool_info{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
      pool_info.maxSets = static_cast<uint32_t>(n_passes) + 1;
      pool_info.poolSizeCount = 4;
      pool_info.pPoolSizes = sizes;
      const VkResult r = vkCreateDescriptorPool(device, &pool_info, nullptr, &pool);
      if(r != VK_SUCCESS)
      {
        error = "vkCreateDescriptorPool failed (VkResult " + std::to_string(r) + ")";
        ok = false;
      }
    }
    VkDescriptorSet set0_handle = VK_NULL_HANDLE;
    std::vector<VkDescriptorSet> sets(n_passes);
    if(ok) ok = write_descriptors(g, mapping, physical, uploads, gain, offsets, pool, set0_handle, sets, error);

    if(ok) ok = begin(error);
    if(ok)
    {
      std::vector<VkImage> fresh{gain.image};
      for(const Image &image : uploads) fresh.push_back(image.image);
      for(const Image &image : physical) fresh.push_back(image.image);
      to_general(cmd, fresh);
      if(queries) vkCmdResetQueryPool(cmd, queries, 0, 2);
      VkBufferImageCopy region;
      for(int f = 0; f < frames; f++)
      {
        copy_region(region, source.width, source.height);
        region.bufferOffset = f * frame_bytes;
        vkCmdCopyBufferToImage(cmd, staging.buffer, uploads[f].image, VK_IMAGE_LAYOUT_GENERAL, 1, &region);
      }
      copy_region(region, map_w, map_h);
      region.bufferOffset = map_offset;
      vkCmdCopyBufferToImage(cmd, staging.buffer, gain.image, VK_IMAGE_LAYOUT_GENERAL, 1, &region);
      memory_barrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                     VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
      if(queries) vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queries, 0);
      for(size_t i = 0; i < n_passes; i++)
      {
        const Pass &pass = g.passes[i];
        const Pipeline &p = pipelines[pass.kernel];
        const VkDescriptorSet bound[2] = {set0_handle, sets[i]};
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, p.pipeline);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, p.layout, 0, 2, bound, 0, nullptr);
        if(kKernels[pass.kernel].push_size)
          vkCmdPushConstants(cmd, p.layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, kKernels[pass.kernel].push_size,
                             pass.push.data());
        vkCmdDispatch(cmd, pass.groups_x, pass.groups_y, 1);
        // Physical images are reused across logical ones, so order reads and
        // writes as well as writes and reads.
        memory_barrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                       VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                       VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
      }
      if(queries) vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queries, 1);
      memory_barrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                     VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
      copy_region(region, w, h);
      vkCmdCopyImageToBuffer(cmd, physical[mapping[output]].image, VK_IMAGE_LAYOUT_GENERAL, readback.buffer, 1,
                             &region);
      memory_barrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                     VK_ACCESS_HOST_READ_BIT);
      ok = submit_and_wait(error);
    }
    if(ok)
    {
      result.width = static_cast<int>(w);
      result.height = static_cast<int>(h);
      result.rgba8.assign(static_cast<const uint8_t *>(readback.mapped),
                          static_cast<const uint8_t *>(readback.mapped) + static_cast<size_t>(w) * h * 4);
      result.passes = static_cast<int>(n_passes);
      result.gpu_ms = 0.0;
      uint64_t stamps[2];
      if(queries && vkGetQueryPoolResults(device, queries, 0, 2, sizeof(stamps), stamps, sizeof(uint64_t),
                                          VK_QUERY_RESULT_64_BIT) == VK_SUCCESS)
        result.gpu_ms = static_cast<double>(stamps[1] - stamps[0]) * limits.timestampPeriod * 1e-6;
    }

    if(pool) vkDestroyDescriptorPool(device, pool, nullptr);
    for(Image &image : physical) destroy(image);
    for(Image &image : uploads) destroy(image);
    destroy(gain);
    destroy(offsets);
    destroy(staging);
    destroy(readback);
    return ok;
  }

  bool write_descriptors(const Graph &g, const std::vector<int> &mapping, const std::vector<Image> &physical,
                         const std::vector<Image> &uploads, const Image &gain, const Buffer &offsets,
                         VkDescriptorPool pool, VkDescriptorSet &set0_handle, std::vector<VkDescriptorSet> &sets,
                         std::string &error)
  {
    VkDescriptorSetAllocateInfo alloc{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    alloc.descriptorPool = pool;
    alloc.descriptorSetCount = 1;
    alloc.pSetLayouts = &set0;
    VK_TRY(vkAllocateDescriptorSets(device, &alloc, &set0_handle), "vkAllocateDescriptorSets");

    // Stable storage for the infos referenced by the writes.
    size_t total = 2;
    for(const Pass &pass : g.passes) total += pass.bindings.size();
    std::vector<VkDescriptorImageInfo> image_infos;
    std::vector<VkDescriptorBufferInfo> buffer_infos;
    image_infos.reserve(total);
    buffer_infos.reserve(total);
    std::vector<VkWriteDescriptorSet> writes;
    writes.reserve(total);

    auto write_buffer = [&](VkDescriptorSet set, uint32_t binding, VkDescriptorType type, const Buffer &buffer) {
      buffer_infos.push_back({buffer.buffer, 0, buffer.size});
      VkWriteDescriptorSet w{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
      w.dstSet = set;
      w.dstBinding = binding;
      w.descriptorCount = 1;
      w.descriptorType = type;
      w.pBufferInfo = &buffer_infos.back();
      writes.push_back(w);
    };
    write_buffer(set0_handle, 0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, global_ubo);
    write_buffer(set0_handle, 1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, params_ubo);

    for(size_t i = 0; i < g.passes.size(); i++)
    {
      const Pass &pass = g.passes[i];
      const KernelSpec &spec = kKernels[pass.kernel];
      alloc.pSetLayouts = &pipelines[pass.kernel].set1;
      VK_TRY(vkAllocateDescriptorSets(device, &alloc, &sets[i]), "vkAllocateDescriptorSets");
      for(uint32_t b = 0; b < pass.bindings.size(); b++)
      {
        const Ref &ref = pass.bindings[b];
        const VkDescriptorType type = descriptor_type(spec.bindings[b]);
        if(ref.kind == Ref::Prep || ref.kind == Ref::Offsets)
        {
          write_buffer(sets[i], b, type, ref.kind == Ref::Prep ? prep : offsets);
          continue;
        }
        const Image &image = ref.kind == Ref::Image        ? physical[mapping[ref.id]]
                             : ref.kind == Ref::FilmsimLut ? filmsim_lut
                             : ref.kind == Ref::SpectraLut ? spectra_lut
                             : ref.kind == Ref::GainMap    ? gain
                             : ref.kind == Ref::Creative   ? creative_lut
                                                           : uploads[std::clamp(ref.id, 0, int(uploads.size()) - 1)];
        // Float32 and integer textures are only texelFetch'ed: no filtering support needed.
        const bool unfiltered = ref.kind == Ref::FilmsimLut || ref.kind == Ref::SpectraLut ||
                                ref.kind == Ref::GainMap || image.format == VK_FORMAT_R16_UINT ||
                                image.format == VK_FORMAT_R32_UINT;
        image_infos.push_back({unfiltered ? nearest : linear, image.view, VK_IMAGE_LAYOUT_GENERAL});
        VkWriteDescriptorSet w{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
        w.dstSet = sets[i];
        w.dstBinding = b;
        w.descriptorCount = 1;
        w.descriptorType = type;
        w.pImageInfo = &image_infos.back();
        writes.push_back(w);
      }
    }
    vkUpdateDescriptorSets(device, static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);
    return true;
  }
};

// ---------------------------------------------------------------------------

bool parse_lut(const uint8_t *data, size_t size, Lut &out, std::string &error)
{
  struct Header
  {
    uint32_t magic;
    uint16_t version;
    uint8_t channels;
    uint8_t datatype;
    uint32_t wd, ht;
  } header;
  static_assert(sizeof(Header) == 16, "lut header is 16 bytes");
  if(size < sizeof(header))
  {
    error = "lut file too small";
    return false;
  }
  std::memcpy(&header, data, sizeof(header));
  if(header.magic != 1234 || header.version != 2)
  {
    error = "not a vkdt v2 lut file";
    return false;
  }
  if(header.datatype > 1 || header.channels < 1 || header.channels > 4)
  {
    error = "unsupported lut layout";
    return false;
  }
  const size_t texel = header.datatype == 0 ? 2 : 4;
  const size_t count = static_cast<size_t>(header.wd) * header.ht;
  if(size - sizeof(header) < count * header.channels * texel)
  {
    error = "lut file truncated";
    return false;
  }
  out.width = header.wd;
  out.height = header.ht;
  out.half = header.datatype == 0;
  out.channels = header.channels == 3 ? 4 : header.channels;
  const uint8_t *src = data + sizeof(header);
  if(header.channels != 3) out.texels.assign(src, src + count * header.channels * texel);
  else
  { // like i-lut: pad rgb to rgba, alpha left zero
    out.texels.assign(count * 4 * texel, 0);
    for(size_t k = 0; k < count; k++) std::memcpy(&out.texels[k * 4 * texel], src + k * 3 * texel, 3 * texel);
  }
  return true;
}

float raw_key(const RawImage &raw)
{
  // Sample a 128x96 grid of Bayer quads; same weighting as the app's preview meter.
  constexpr int kCols = 128, kRows = 96;
  const int qw = raw.width / 2, qh = raw.height / 2;
  if(qw < kCols || qh < kRows) return 0.0f;
  // Linear Rec.2020 -> Y (CIE Y is the same luminance the preview meter measures).
  const float *m = raw.camera_to_rec2020;
  const float yr = 0.2627f * m[0] + 0.6780f * m[3] + 0.0593f * m[6];
  const float yg = 0.2627f * m[1] + 0.6780f * m[4] + 0.0593f * m[7];
  const float yb = 0.2627f * m[2] + 0.6780f * m[5] + 0.0593f * m[8];
  auto gain_at = [&](float rx, float ry, int position) {
    if(raw.gain_map.empty() || raw.map_width < 2 || raw.map_height < 2) return 1.0f;
    const float gx = std::clamp((rx - raw.map_origin[0]) / raw.map_spacing[0], 0.0f, float(raw.map_width - 1));
    const float gy = std::clamp((ry - raw.map_origin[1]) / raw.map_spacing[1], 0.0f, float(raw.map_height - 1));
    const int ix = std::min(int(gx), raw.map_width - 2), iy = std::min(int(gy), raw.map_height - 2);
    const float fx = gx - ix, fy = gy - iy;
    auto g = [&](int x, int y) { return raw.gain_map[(size_t(y) * raw.map_width + x) * 4 + position]; };
    return (g(ix, iy) * (1 - fx) + g(ix + 1, iy) * fx) * (1 - fy) + (g(ix, iy + 1) * (1 - fx) + g(ix + 1, iy + 1) * fx) * fy;
  };
  double log_sum = 0.0, weight_sum = 0.0;
  for(int r = 0; r < kRows; r++)
    for(int c = 0; c < kCols; c++)
    {
      const int qx = (c * qw) / kCols, qy = (r * qh) / kRows;
      const float rx = (2.0f * qx + 1.0f) / raw.width, ry = (2.0f * qy + 1.0f) / raw.height;
      float rgb[3] = {0, 0, 0}, n[3] = {0, 0, 0};
      for(int position = 0; position < 4; position++)
      {
        const int x = 2 * qx + (position & 1), y = 2 * qy + (position >> 1);
        const float black = raw.black[position];
        float v = (raw.cfa[size_t(y) * raw.width + x] - black) / (raw.white - black);
        const int colour = raw.cfa_colour[position];
        v = std::clamp(v * gain_at(rx, ry, position) * raw.wb[colour], 0.0f, 1.0f);
        rgb[colour] += v;
        n[colour] += 1;
      }
      for(int k = 0; k < 3; k++) rgb[k] = n[k] > 0 ? rgb[k] / n[k] : 0.0f;
      const float y = std::max(0.0f, yr * rgb[0] + yg * rgb[1] + yb * rgb[2]);
      const double dx = (c + 0.5) / kCols - 0.5, dy = (r + 0.5) / kRows - 0.5;
      const double w = 1.0 + 3.0 * std::exp(-(dx * dx + dy * dy) / (2 * 0.18 * 0.18));
      log_sum += w * std::log(y + 1e-4);
      weight_sum += w;
    }
  return static_cast<float>(std::exp(log_sum / weight_sum) - 1e-4);
}

FilmEngine::FilmEngine(std::unique_ptr<Impl> impl) : impl_(std::move(impl)) {}
FilmEngine::~FilmEngine() = default;

std::unique_ptr<FilmEngine> FilmEngine::create(const Lut &filmsim, const Lut &spectra, std::string &error)
{
  auto impl = std::make_unique<Impl>();
  if(!impl->init(filmsim, spectra, error)) return nullptr;
  return std::unique_ptr<FilmEngine>(new FilmEngine(std::move(impl)));
}

bool FilmEngine::develop(const SourceImage &source, const FilmParams &params, const DevelopOptions &options,
                         DevelopResult &result, std::string &error)
{
  return impl_->develop(source, params, options, result, error);
}

bool FilmEngine::bake_preview_lut(const FilmParams &params, int size, ColourSpace output, std::vector<uint8_t> &rgba,
                                  std::string &error, const Stages &stages)
{
  if(size < 2 || size > 128)
  {
    error = "preview lut size must be 2..128";
    return false;
  }
  FilmParams p = params;
  p.grain = 0;
  p.halation = 0;
  p.scat_amt = 0.0f;
  p.glare = 0.0f;
  p.couplers_radius = 0.0f; // keep the per-pixel DIR coupler colour, drop its diffusion

  // A size x size^2 image whose texels walk the encoded RGB cube in 3D-texture
  // order; fed as linear light so no 8-bit quantisation enters the grid.
  const size_t n = static_cast<size_t>(size);
  std::vector<float> grid(n * n * n * 4);
  for(size_t b = 0; b < n; b++)
    for(size_t g = 0; g < n; g++)
      for(size_t r = 0; r < n; r++)
      {
        float *texel = &grid[(r + g * n + b * n * n) * 4];
        const size_t index[3] = {r, g, b};
        for(int c = 0; c < 3; c++)
        {
          const float v = static_cast<float>(index[c]) / static_cast<float>(size - 1);
          texel[c] = v <= 0.04045f ? v / 12.92f : std::pow((v + 0.055f) / 1.055f, 2.4f);
        }
        texel[3] = 1.0f;
      }
  SourceImage source;
  source.width = size;
  source.height = size * size;
  source.rgba32f = grid.data();
  source.space = ColourSpace::Srgb;
  DevelopOptions options;
  options.max_long_edge = size * size;
  options.output = output;
  options.stages = stages;
  DevelopResult result;
  if(!develop(source, p, options, result, error)) return false;
  rgba = std::move(result.rgba8);
  return true;
}

bool FilmEngine::set_creative_lut(const uint8_t *rgb, int size, float strength, std::string &error)
{
  return impl_->set_creative(rgb, size, strength, error);
}

const DeviceInfo &FilmEngine::device() const { return impl_->info; }

} // namespace film
