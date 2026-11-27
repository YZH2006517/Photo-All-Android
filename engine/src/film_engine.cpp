#include "film_engine.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <thread>
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

void (*g_stage_hook)(const char *) = nullptr;

void report_stage(const char *stage)
{
  if(g_stage_hook) g_stage_hook(stage);
}

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
  kBlur, kDownsample, kDecode, kEncode, kRaw, kFusion, kChroma, kDnHalf, kDnDowncov, kDnDown, kDnAssemble, kDnDoub, kFlashmix, kFlashfrac, kFlashwb,
  kDiffpick, kDiffuse, kFinish, kKernelCount
};

struct KernelSpec
{
  const char *name;
  const char *bindings;
  uint32_t push_size;
};

constexpr KernelSpec kKernels[kKernelCount] = {
    {"setup", "SBS", 16},      {"dirlut", "IB", 0},       {"fast", "SBSI", 0},
    {"expose", "SSBII", 0},    {"develop", "SBSSI", 0},   {"halin", "SBI", 0},
    {"halout", "SBSISSSSSSS", 0}, {"scatter", "SISSSS", 0}, {"negprint", "SSBI", 0},
    {"blur", "SI", 16},        {"downsample", "SI", 4},   {"decode", "SI", 96},
    {"encode", "SIS", 112},     {"raw", "SSI", 128},
    {"fusion", "SSSSI", 32},   {"chroma", "SSSI", 16},
    {"dn_half", "SI", 48},     {"dn_downcov", "SII", 16}, {"dn_down", "SI", 16},
    {"dn_assemble", "SSSSSI", 32}, {"dn_doub", "SSSI", 64},  {"flashmix", "SSI", 128},
    {"flashfrac", "SSI", 64},  {"flashwb", "SSI", 80},
    {"diffpick", "SI", 16},    {"diffuse", "SSSI", 32},  {"finish", "SSI", 16},
};
constexpr int kMaxBurst = 6;
// Timestamps: one before the passes and one after each, up to this many.
constexpr uint32_t kMaxQueries = 512;
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

// ---------------------------------------------------------------------------
// The film's own light: the scene (D65, Rec.2020's white) is brought to the stock's
// reference illuminant by a chromatic adaptation in CAT16 cone space (Li et al. 2017,
// full adaptation), as the reference spektrafilm does, instead of filmsim's spectral
// relighting. For a tungsten stock the two differ a lot (greens); measured on the chart,
// Vision3 200T / 500T go from ΔE 5.0 / 5.5 to 2.3 / 2.5 against the reference.

constexpr double kRec2020ToXyz[9] = {0.636958, 0.144617, 0.168881, 0.262700, 0.677998,
                                     0.059302, 0.000000, 0.028073, 1.060985};
constexpr double kCat16[9] = {0.401288, 0.650173, -0.051461, -0.250268, 1.204414,
                              0.045854, -0.002079, 0.048952, 0.953127};
constexpr double kD65Xy[2] = {0.3127, 0.3290};
constexpr double kD55Xy[2] = {0.33242, 0.34743};      // CIE D55
constexpr double kTungstenXy[2] = {0.45073, 0.40805}; // incandescent, about 2800 K
constexpr float kD65Cct = 6504.0f;                    // filmsim: relighting to this is none

void mul3(const double *a, const double *b, double *out)
{
  for(int r = 0; r < 3; r++)
    for(int c = 0; c < 3; c++) out[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c];
}

void inv3(const double *m, double *out)
{
  const double a = m[4] * m[8] - m[5] * m[7], b = m[5] * m[6] - m[3] * m[8], c = m[3] * m[7] - m[4] * m[6];
  const double det = m[0] * a + m[1] * b + m[2] * c;
  out[0] = a / det; out[1] = (m[2] * m[7] - m[1] * m[8]) / det; out[2] = (m[1] * m[5] - m[2] * m[4]) / det;
  out[3] = b / det; out[4] = (m[0] * m[8] - m[2] * m[6]) / det; out[5] = (m[2] * m[3] - m[0] * m[5]) / det;
  out[6] = c / det; out[7] = (m[1] * m[6] - m[0] * m[7]) / det; out[8] = (m[0] * m[4] - m[1] * m[3]) / det;
}

// Rec.2020 (D65) -> Rec.2020 numbers of the same scene adapted to the film's white.
void film_adaptation(int film, float out[9])
{
  const double *xy = film == 13 || film == 14 ? kTungstenXy : kD55Xy; // Vision3 200T, 500T
  auto lms = [](const double *w, double *l) {
    const double xyz[3] = {w[0] / w[1], 1.0, (1.0 - w[0] - w[1]) / w[1]};
    for(int r = 0; r < 3; r++) l[r] = kCat16[r * 3] * xyz[0] + kCat16[r * 3 + 1] * xyz[1] + kCat16[r * 3 + 2] * xyz[2];
  };
  double src[3], dst[3];
  lms(kD65Xy, src);
  lms(xy, dst);
  double cat_inv[9], xyz_to_rgb[9], scaled[9], t[9], u[9], m[9];
  inv3(kCat16, cat_inv);
  inv3(kRec2020ToXyz, xyz_to_rgb);
  for(int r = 0; r < 3; r++)
    for(int c = 0; c < 3; c++) scaled[r * 3 + c] = kCat16[r * 3 + c] * dst[r] / src[r];
  mul3(scaled, kRec2020ToXyz, t);
  mul3(cat_inv, t, u);
  mul3(xyz_to_rgb, u, m);
  for(int k = 0; k < 9; k++) out[k] = static_cast<float>(m[k]);
}

// filmsim constants from main.c.
constexpr float kScatterCoreUm = 1.9333333f;
constexpr float kGrainSizeMatch = 0.25f;
constexpr float kGrainAmountMatch = 3.6f;
constexpr float kHalationMatch = 0.3f;
constexpr float kScatterMatch = 1.75f;
// The DIR inhibitors' long tail (the reference's diffusion_tail_um and its weight).
constexpr float kCouplerTailUm = 200.0f;
constexpr float kCouplerTailWeight = 0.06f;
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
  VkImageUsageFlags usage = 0;
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
  Warp warp;                // flash pair: the flash (or ambient) frame registered as a whole
  int crop[4] = {0, 0, 0, 0}; // flash mix: raw pixels to drop (left top right bottom), no flash data there
  float flash_gain = 0.0f;  // flash mix: gain on the flash-only light of the frame being developed
  float flash_ambient_k = 1.0f; // flash white: flash frame exposure over the ambient frame's
  // HDR tone mapping (exposure fusion): the three synthetic exposures and the scale that
  // keeps mid grey, chosen by develop() from the frame's highlights.
  float tone_exposure[3] = {1.0f, 1.0f, 1.0f};
  float tone_norm = 1.0f;
  float merge_strength = 4.0f; // HDR+ merge: the noise term's multiplier (Hasinoff's c)
  // CPU time per stage of the develop, "stage ms" each, for the record.
  std::chrono::steady_clock::time_point lap_start;
  std::string laps;
  void lap(const char *stage)
  {
    const auto now = std::chrono::steady_clock::now();
    char item[64];
    std::snprintf(item, sizeof(item), "%s%s %.0f", laps.empty() ? "" : "，", stage,
                  std::chrono::duration<double, std::milli>(now - lap_start).count());
    laps += item;
    lap_start = now;
  }
  // The develop's record: "section\tkey\tvalue" lines, filled by develop() and build_graph().
  std::string record;
  void note(const char *section, const char *key, const char *format, ...)
  {
    char value[256];
    va_list args;
    va_start(args, format);
    const int n = std::vsnprintf(value, sizeof(value), format, args);
    va_end(args);
    if(n >= static_cast<int>(sizeof(value)))
    { // cut short: never in the middle of a character (JNI refuses broken UTF-8 outright)
      size_t end = sizeof(value) - 1;
      while(end > 0 && (static_cast<unsigned char>(value[end]) & 0xC0) == 0x80) end--;
      value[end] = '\0';
    }
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
      for(Image &image : image_cache) destroy(image);
      destroy(cached_staging);
      destroy(cached_readback);
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

  // GPU memory kept from the last develop: the next one of the same kind (a burst of the
  // same size, say) takes its images and buffers instead of allocating them again.
  std::vector<Image> image_cache;
  Buffer cached_staging, cached_readback;

  bool take_image(Image &image, uint32_t width, uint32_t height, VkFormat format, VkImageUsageFlags usage,
                  std::string &error)
  {
    for(auto it = image_cache.begin(); it != image_cache.end(); ++it)
      if(it->width == width && it->height == height && it->format == format && it->usage == usage)
      {
        image = *it;
        image_cache.erase(it);
        return true;
      }
    if(!create_image(image, width, height, format, usage, error)) return false;
    image.usage = usage;
    return true;
  }

  bool take_buffer(Buffer &cached, Buffer &buffer, VkDeviceSize size, VkBufferUsageFlags usage, std::string &error)
  {
    if(cached.buffer && cached.size == size)
    {
      buffer = cached;
      cached = {};
      return true;
    }
    destroy(cached);
    return create_buffer(buffer, size, usage, true, error);
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
      query_info.queryCount = kMaxQueries;
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
      const ShaderBlob *blob = find_shader(info.safe_shaders ? (std::string(spec.name) + "_safe").c_str() : spec.name);
      if(!blob)
      {
        error = std::string("missing SPIR-V for ") + spec.name;
        return false;
      }
      Pipeline &p = pipelines[k];
      report_stage(spec.name);
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

  // A diffusion filter in front of the lens (diffpick.comp, diffuse.comp): what it spreads
  // (all the light, or the highlights from a soft threshold), a near and a far blur of it,
  // laid back over the scene with a share of the direct light taken away. Sizes are shares
  // of the frame (a filter sits in front of the lens, so it scales with the picture).
  //
  // Pro-Mist, Black Pro-Mist and Cinebloom follow the reference spektrafilm's filters: all the
  // light is spread and none is made (loss = gain), and the two blurs are fitted to the
  // radial profile its filter gives a bright spot (+6 stops, 8 px across a 2400 px frame,
  // strength 0.5; Portra 400 on Portra Endura). Its three-scale PSF (core, halo, bloom) does
  // not fit two Gaussians exactly: Pro-Mist is within 0.05 of it from 2 to 56 px out, Black
  // Pro-Mist within 0.03, Cinebloom within 0.07 (the bloom a little long). Glimmerglass, which
  // the reference has not, keeps its own look.
  int diffuse(Graph &g, int input, uint32_t w, uint32_t h, const Stages &s)
  {
    struct Preset { const char *name; float threshold, near_frac, far_frac, near_w, loss, gain, warm; };
    static constexpr Preset kPresets[] = {
        {"", 0, 0, 0, 0, 0, 0, 0},
        // a broad halo and a long veil: lower contrast, haze over everything
        {"柔焦（Pro-Mist）", 0.0f, 0.004f, 0.016f, 0.42f, 0.52f, 0.52f, 0.0f},
        // a tight halo (95 um on the frame, about 6 px at 2400) and a short bloom
        {"黑柔（Black Pro-Mist）", 0.0f, 0.0025f, 0.009f, 0.55f, 0.32f, 0.32f, 0.0f},
        // tight sparkling halos round the brightest points
        {"微光（Glimmerglass）", 0.9f, 0.003f, 0.012f, 0.75f, 0.04f, 0.6f, 0.0f},
        // the bloom carrying most of it, wide and warm
        {"泛光（Cinebloom）", 0.0f, 0.003f, 0.016f, 0.2f, 0.5f, 0.5f, 0.35f},
    };
    const Preset &p = kPresets[std::clamp(s.diffusion, 1, 4)];
    const float long_edge = static_cast<float>(std::max(w, h));
    struct { float threshold, knee, pad0, pad1; } pick{p.threshold, 2.0f, 0, 0};
    const int picked = g.image(w, h);
    g.pixels(kDiffpick, {img(input), img(picked)}, picked, pick);
    const int near = g.blur(picked, p.near_frac * s.diff_size * long_edge, true);
    const int far = g.blur(picked, p.far_frac * s.diff_size * long_edge, true);
    const float warm = std::clamp(p.warm + s.diff_warm, -1.0f, 1.0f);
    struct { float tint[4]; float amount, near_w, loss, gain; } mix{
        {1.0f + 0.18f * warm, 1.0f, 1.0f - 0.22f * warm, 0.0f}, s.diff_amount, p.near_w, p.loss, p.gain};
    static_assert(sizeof(mix) == 32, "diffuse push constants");
    const int out = g.image(w, h);
    g.pixels(kDiffuse, {img(input), img(near), img(far), img(out)}, out, mix);
    note("光学", "扩散滤镜", "%s · 强度 %.2f · 大小 ×%.2f · 冷暖 %+.2f", p.name, s.diff_amount, s.diff_size, warm);
    return out;
  }

  // The scanner (finish.comp): its softness, sharpening on luminance, black and white points;
  // nothing at all while they are at rest.
  int scanner_finish(Graph &g, int input, uint32_t w, uint32_t h, const Stages &s)
  {
    const bool soft = s.scan_blur > 0.05f, sharp = s.scan_sharp > 0.0f;
    const bool levels = s.black > 0.0f || std::abs(s.white - 1.0f) > 1e-4f;
    if(!soft && !sharp && !levels) return input;
    const int base = soft ? g.blur(input, s.scan_blur, false) : input;
    const int wide = sharp ? g.blur(base, s.scan_radius, false) : base;
    struct { float sharpen, black, white, pad; } push{s.scan_sharp, s.black, std::max(s.white, s.black + 0.05f), 0};
    const int out = g.image(w, h);
    g.pixels(kFinish, {img(base), img(wide), img(out)}, out, push);
    note("扫描", "扫描仪", "柔化 %.1f px · 锐化 %.2f（半径 %.1f px）· 黑点 %.3f · 白点 %.2f",
         s.scan_blur, s.scan_sharp, s.scan_radius, s.black, s.white);
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

  // Exposure fusion (see fusion.comp): synthetic exposures of the scene's luminance,
  // a Laplacian pyramid down to 16-32 px, collapsed with each level's blended weights;
  // the result scales the scene's colour.
  int local_tone(Graph &g, int input, uint32_t w, uint32_t h)
  {
    struct
    {
      int32_t mode;
      float norm, max_gain, min_gain;
      float exposure[4];
    } push{0, tone_norm, 8.0f, 1.0f / 16.0f, {tone_exposure[0], tone_exposure[1], tone_exposure[2], 0.0f}};
    static_assert(sizeof(push) == 32, "fusion push constants");
    std::vector<int> I{g.image(w, h)}, W{-1};
    g.pixels(kFusion, {img(input), img(input), img(input), img(input), img(I[0])}, I[0], push);
    uint32_t lw = w, lh = h;
    while(std::min(lw, lh) > 32 && I.size() < 12)
    {
      lw = (lw + 1) / 2;
      lh = (lh + 1) / 2;
      const int i = g.image(lw, lh), wi = g.image(lw, lh);
      push.mode = 1;
      g.pixels(kFusion, {img(I.back()), img(I.back()), img(I.back()), img(I.back()), img(i)}, i, push);
      // level 1's weights from the full-size exposures; deeper ones from the level above
      const int from = W.size() == 1 ? I[0] : W.back();
      push.mode = W.size() == 1 ? 2 : 1;
      g.pixels(kFusion, {img(from), img(from), img(from), img(from), img(wi)}, wi, push);
      I.push_back(i);
      W.push_back(wi);
    }
    const size_t top = I.size() - 1;
    if(top == 0) return input; // too small to fuse
    int r = g.image(g.images[I[top]].width, g.images[I[top]].height);
    push.mode = 3;
    g.pixels(kFusion, {img(I[top]), img(W[top]), img(I[top]), img(I[top]), img(r)}, r, push);
    for(size_t l = top - 1; l >= 1; l--)
    {
      const int rl = g.image(g.images[I[l]].width, g.images[I[l]].height);
      push.mode = 4;
      g.pixels(kFusion, {img(I[l]), img(I[l + 1]), img(W[l]), img(r), img(rl)}, rl, push);
      r = rl;
    }
    const int out = g.image(w, h);
    push.mode = 5;
    g.pixels(kFusion, {img(input), img(I[0]), img(I[1]), img(r), img(out)}, out, push);
    return out;
  }

  // filmsim_make_plan(): the film (and, unless scanning, the print) as compute passes.
  int develop_film(Graph &g, const FilmParams &params, int input, uint32_t w, uint32_t h)
  {
    if(adapt_input)
    { // the decode pass as a plain matrix: linear in, no tone, no white balance
      struct
      {
        Rows m;
        int32_t srgb_eotf;
        float gain;
        float pad[2];
        float tone[4];
        float tone2[4];
      } push{{}, 0, 1.0f, {0, 0}, {0, 1, 1, 1}, {1, 1, 1, 1}};
      static_assert(sizeof(push) == 96, "decode push constants");
      float m[9];
      film_adaptation(params.film, m);
      push.m = rows(m);
      const int adapted = g.image(w, h);
      g.pixels(kDecode, {img(input), img(adapted)}, adapted, push);
      input = adapted;
    }
    const bool print_neg = params.process == kProcessPrintNegative;
    const bool couplers = params.couplers > 0;
    const bool halation = params.halation != 0;
    const bool scatter = params.scat_amt > 0.0f;
    const bool use_curvewarp = couplers && !print_neg;
    const bool fastpath = !print_neg && !couplers && !halation;
    const float frame_um = params.frame_um > 0.0f ? params.frame_um : FILMSIM_FRAME_WIDTH_UM;
    const float um_to_px = static_cast<float>(std::max(w, h)) / frame_um;
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
      int coupler_blur = couplers ? g.blur(coupler, cp_sigma, true) : coupler;
      if(couplers && cp_sigma > 0.0f)
      { // the inhibitors' long reach, as the reference's: 6% of them spread far (an exponential
        // tail of 200 um, as a Gaussian of the same spread), the rest as before
        const int tail = g.blur(coupler, kCouplerTailUm * 1.41421356f * um_to_px, true);
        // at the near blur's own size (diffuse.comp reads its first input texel for texel)
        const LogicalImage near_size = g.images[coupler_blur];
        const int mixed = g.image(near_size.width, near_size.height);
        struct { float tint[4]; float amount, near_w, loss, gain; } mix{
            {1.0f, 1.0f, 1.0f, 0.0f}, 1.0f, 1.0f, kCouplerTailWeight, kCouplerTailWeight};
        // diffuse.comp: in * (1 - loss) + mix(far, near, near_w) * gain; here in = near = the
        // near blur, so near_w = 0 for the tail: in * (1 - w) + tail * w
        mix.near_w = 0.0f;
        g.pixels(kDiffuse, {img(coupler_blur), img(coupler_blur), img(tail), img(mixed)}, mixed, mix);
        coupler_blur = mixed;
      }
      const int dev1 = g.image(w, h);
      if(halation) g.pixels(kHalin, {img(exposure), kPrep, img(dev1)}, dev1);
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
                           img(hal[0]), img(hal[1]), img(hal[2]), img(hal[3]), img(hal[4]), img(hal[5]),
                           img(coupler_blur)},
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
    // How much of the denoise strengths a RAW needs: its own noise at mid grey (the DNG noise
    // model at the raw level that prints as mid grey, after this develop's exposure; merged
    // frames lower it). A clean daylight frame (noise under 1 %) gets none, so its fine detail
    // and colour stay; from 5 % (high ISO, low light) the full strengths, as set.
    float nr_factor = 1.0f;
    float nr_noise = 0.0f;
    if(source.raw)
    {
      const RawImage &r = *source.raw;
      const float s = r.noise_scale > 0 ? r.noise_scale : 2e-4f;
      const float o = r.noise_scale > 0 ? r.noise_offset : 2e-6f;
      const float mid = std::clamp(0.184f / std::exp2(options.exposure_ev), 1e-4f, 1.0f);
      const float merged = source.burst_count >= 2 && !options.flash.enabled ? 0.5f * source.burst_count : 1.0f;
      nr_noise = std::sqrt(s * mid + o) / mid / std::sqrt(std::max(merged, 1.0f));
      nr_factor = std::clamp((nr_noise - 0.01f) / 0.04f, 0.0f, 1.0f);
    }
    const float raw_nr = options.raw_denoise * nr_factor, chroma_nr = options.chroma_denoise * nr_factor;
    struct
    {
      Rows m;
      int32_t srgb_eotf;
      float gain;
      float pad[2];
      float tone[4];
      float tone2[4];
    } decode{rows(source.space == ColourSpace::Srgb        ? kSrgbToRec2020
                  : source.space == ColourSpace::DisplayP3 ? kP3ToRec2020
                                                           : kIdentity),
             source.rgba8 ? 1 : 0, std::exp2(options.exposure_ev), {0, 0}, {0, 1, 1, 1}, {1, 1, 1, 1}};
    static_assert(sizeof(decode) == 96, "decode push constants");
    if(source.rgba8 && (options.stages.temp != 0.0f || options.stages.tint != 0.0f))
    {
      // warmer: red up, blue down; greener: green up; overall luminance kept
      const float r = std::exp2(0.30f * options.stages.temp), b = std::exp2(-0.30f * options.stages.temp);
      const float gr = std::exp2(0.20f * options.stages.tint);
      const float y = 0.2126f * r + 0.7152f * gr + 0.0722f * b;
      decode.tone2[1] = r / y;
      decode.tone2[2] = gr / y;
      decode.tone2[3] = b / y;
      note("场景还原", "白平衡（色温 / 色调）", "%+.2f / %+.2f", options.stages.temp, options.stages.tint);
    }
    if(source.rgba8 && options.stages.restore > 0.0f)
    {
      // its tone constants always; its auto exposure only while metering (imports may turn it off)
      const float restore_gain = choose_restore(source, std::min(options.stages.restore, 1.0f), decode.tone, decode.tone2);
      if(options.stages.meter) decode.gain *= restore_gain;
      else note("场景还原", "自动曝光", "关（不测光，亮度按原图）");
    }
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
      // no burst merge (the HDR+ merge was taken out): the reference frame stands alone
      float noise_gain = 1.0f; // how much the mixes scaled the signal (and so its noise)
      if(white_map)
      {
        const RawImage &amb = *source.burst[1];
        struct
        {
          float f_black[4], a_black[4];
          float f_white, a_white, ambient_scale, pad;
          float warp[4]; // a b tx ty: the ambient frame registered to the flash frame
        } frac{};
        static_assert(sizeof(frac) == 64, "flashfrac push constants");
        std::memcpy(frac.f_black, push.black, sizeof(frac.f_black));
        std::memcpy(frac.a_black, amb.black, sizeof(frac.a_black));
        frac.f_white = push.wb_white[3];
        frac.a_white = amb.white;
        frac.ambient_scale = flash_ambient_k;
        frac.warp[0] = warp.a;
        frac.warp[1] = warp.b;
        frac.warp[2] = warp.tx;
        frac.warp[3] = warp.ty;
        const int share = g.image(raw.width / 2, raw.height / 2);
        g.pixels(kFlashfrac, {sensor, upload(1), img(share)}, share, frac);
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
          float noise_s, noise_o, scale, pad;
          float warp[4]; // a b tx ty: the flash frame registered to the ambient one
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
        mix.warp[0] = warp.a;
        mix.warp[1] = warp.b;
        mix.warp[2] = warp.tx;
        mix.warp[3] = warp.ty;
        const int mixed = g.image(raw.width, raw.height, VK_FORMAT_R32_UINT);
        g.pixels(kFlashmix, {sensor, upload(ambient_frames), img(mixed)}, mixed, mix);
        sensor = img(mixed);
        for(float &b : push.black) b = 0.0f;
        push.wb_white[3] = kMergeScale;
        noise_gain = std::max(options.flash.ambient, flash_gain);
      }
      note("降噪", "按噪声自适应", "中灰噪声 %.1f%%，降噪用设定的 %.0f%%", 100.0f * nr_noise, 100.0f * nr_factor);
      if(raw_nr > 0.0f && raw.width >= 64 && raw.height >= 64)
      {
        // Noise model of the reference frame (DNG NoiseProfile, normalised units). A merge
        // averages frames: the variance drops by the frames' total light relative to the
        // reference (sum of 1 / gain), counted at half since robust weights and alignment
        // keep a real merge from reaching it.
        const float s = raw.noise_scale > 0 ? raw.noise_scale : 2e-4f;
        const float o = raw.noise_scale > 0 ? raw.noise_offset : 2e-6f;
        float frames = 1.0f; // one frame: nothing merged
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
        note("降噪", "RAW 降噪 (vkdt)", "强度 %.2f，σ 刻度 κ %.1f，合并等效帧 %.2f，信号增益 %.2f", raw_nr, kappa,
             frames * noise_gain, noise_gain);
        note("降噪", "使用的噪声模型 (s o)", "%.4g %.4g", s, o);
        const int denoised = raw_denoise(g, sensor, raw.width, raw.height, push.black, push.wb_white[3], raw.cfa_colour,
                                         wb, kappa2 * o * o_gain / frames, kappa2 * s / frames, std::min(raw_nr, 1.0f));
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
        decode.tone[0] = 0.0f;
        decode.tone2[1] = decode.tone2[2] = decode.tone2[3] = 1.0f;
        g.pixels(kDecode, {img(developed), img(input)}, input, decode);
      }
    }
    else g.pixels(kDecode, {kUpload, img(input)}, input, decode);

    int scene = input;
    note("降噪", "彩色降噪", chroma_nr > 0 ? "%.2f" : "关", chroma_nr);
    note("影调", "HDR 影调压缩（曝光融合）", options.local_tone > 0 ? "%.2f" : "关", options.local_tone);
    if(!options.stages.film) note("影调", "不用胶片时的影调", options.stages.display && source.rgba8 ? "原样（成品照片）" : "中性曲线（场景光）");
    note("冲洗曝光", "工作尺寸", "%ux%u（长边上限 %d）", w, h, options.max_long_edge);
    if(chroma_nr > 0.0f)
      scene = chroma_denoise(g, scene, w, h, std::min(chroma_nr, 1.0f));
    if(options.local_tone > 0.0f) scene = local_tone(g, scene, w, h);
    // the lens filter and the scanner belong to the film: without it (直出) the picture is left alone
    if(options.stages.film && options.stages.diffusion > 0 && options.stages.diff_amount > 0.0f)
      scene = diffuse(g, scene, w, h, options.stages);

    // Film off: the scene itself under the camera-like display curve. The same for a raw
    // and for the viewfinder's frames (the camera is asked for a plain sRGB curve, so
    // they are scene light too): the viewfinder then shows what the shot will be.
    int developed = scene;
    // a finished picture (an import) without film: as it came, no tone curve over it
    int mode = options.stages.display && source.rgba8 ? 0 : 1;
    if(options.stages.film)
    {
      developed = develop_film(g, params, scene, w, h);
      // grain is laid down and then spread a little by the developer, as the reference does
      if(params.grain != 0 && params.process != kProcessPrintNegative)
        developed = g.blur(developed, options.stages.grain_blur, false);
      // Print off: slides are positive already; negatives get the lab-scan inversion.
      mode = !options.stages.print && !is_positive_film(params.film) ? 2 : 0;
      // "stage negative": the scan as it is, no inversion (encode.comp's mode 3 passes it through)
      if(mode == 2 && options.stages.negative) mode = 3;
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
              static_cast<float>(mode),
              // the film's output softly into the display's gamut (see encode.comp)
              options.stages.film && options.stages.gamut && !options.calibrate ? 1.0f : 0.0f},
             {}};
    static_assert(sizeof(encode) == 112, "encode push constants");
    if(mode == 2)
    {
      std::memcpy(encode.scan, scan_cal, sizeof(scan_cal));
      note("冲洗曝光", "直接扫描校准", "中灰 %.2f/%.2f/%.2f，斜率 %.3f/%.3f/%.3f，片基 %.2f/%.2f/%.2f 档", scan_cal[0][0], scan_cal[0][1],
           scan_cal[0][2], scan_cal[1][0], scan_cal[1][1], scan_cal[1][2], scan_cal[2][0], scan_cal[2][1], scan_cal[2][2]);
    }
    // a print or a slide: its own base white, its deepest density black (see calibrate_print)
    if(mode == 0 && (print_m != 1.0f || print_q != 0.0f))
    {
      encode.scan[0][0] = print_q;
      encode.scan[0][1] = print_m;
      encode.scan[0][2] = 1.0f;
    }
    if(options.stages.film) developed = scanner_finish(g, developed, w, h, options.stages);
    g.pixels(kEncode, {img(developed), img(output), kCreative}, output, encode);
    return g;
  }

  // Scene restore of a camera-rendered 8-bit source at `strength` (0..1), measured on the
  // source itself in linear light (transfer decoded first, as everything after the door
  // is linear). Fills decode.comp's tone constants; returns an exposure gain that moves
  // the frame's centre-weighted log-average part of the way to a typical key.
  float choose_restore(const SourceImage &src, float strength, float tone[4], float tone2[4])
  {
    static float eotf[256];
    static bool ready = false;
    if(!ready)
    {
      for(int i = 0; i < 256; i++)
      {
        const float v = i / 255.0f;
        eotf[i] = v <= 0.04045f ? v / 12.92f : std::pow((v + 0.055f) / 1.055f, 2.4f);
      }
      ready = true;
    }
    const int w = src.width, h = src.height;
    const int step = std::max(1, std::max(w, h) / 256);
    double log_sum = 0.0, weight_sum = 0.0;
    size_t clipped = 0, count = 0;
    for(int y = step / 2; y < h; y += step)
      for(int x = step / 2; x < w; x += step)
      {
        const uint8_t *px = src.rgba8 + (static_cast<size_t>(y) * w + x) * 4;
        const float lum = 0.2126f * eotf[px[0]] + 0.7152f * eotf[px[1]] + 0.0722f * eotf[px[2]];
        if(std::max(px[0], std::max(px[1], px[2])) >= 250) clipped++;
        count++;
        const float dx = 2.0f * x / w - 1.0f, dy = 2.0f * y / h - 1.0f;
        const double wt = 1.0 / (1.0 + dx * dx + dy * dy); // centre-weighted, like a camera meter
        log_sum += wt * std::log(std::max(lum, 1e-4f));
        weight_sum += wt;
      }
    const float clip = count ? static_cast<float>(clipped) / count : 0.0f;
    const float slope = 1.0f - 0.14f * strength;              // the phone's extra mid-tone contrast
    const float sat = 1.0f - 0.12f * strength;                // and its extra saturation
    const float knee = 0.30f;                                 // where a phone's shoulder starts
    const float top = 0.18f * std::pow(1.0f / 0.18f, slope);  // 1.0 after the slope
    // The scene white 1.0 stood for: more headroom where more of the frame is clipped.
    const float stops = strength * (1.2f + 1.3f * std::clamp(clip / 0.05f, 0.0f, 1.0f));
    const float white = std::max(std::exp2(stops), top + 0.05f);
    const float a = 1.0f / (1.0f / (top - knee) - 1.0f / (white - knee));
    tone[0] = 1.0f;
    tone[1] = slope;
    tone[2] = knee;
    tone[3] = a;
    tone2[0] = sat;
    const float key = weight_sum > 0 ? static_cast<float>(std::exp(log_sum / weight_sum)) : 0.18f;
    const float placed = 0.18f * std::pow(key / 0.18f, slope); // the key after the slope
    const float ev = strength * std::clamp(0.5f * std::log2(0.12f / std::max(placed, 1e-4f)), -0.75f, 0.75f);
    note("场景还原", "强度", "%.2f", strength);
    note("场景还原", "画面亮度 key（线性）", "%.4f → %.4f", key, placed);
    note("场景还原", "过曝比例", "%.2f%%", clip * 100.0f);
    note("场景还原", "高光余量 / 斜率 / 饱和", "%+.2f EV / %.2f / %.2f", std::log2(white), slope, sat);
    note("场景还原", "自动曝光", "%+.2f EV", ev);
    return std::exp2(ev);
  }

  // The exposure fusion's synthetic exposures at `strength` (0..1): the dark one brings
  // the brightest 0.5% of the frame (its luminance at the develop's exposure) down to 0.9,
  // the bright one opens the shadows by up to 3 stops (HDR+ uses about 2); mid grey is
  // kept where it was.
  void choose_tone(const RawImage *raw, float exposure_ev, float strength)
  {
    float high = 0.0f;
    if(raw) high = raw_luminance_quantile(*raw, 0.995f) * std::exp2(exposure_ev);
    const float dark = high > 0.9f ? std::max(1.0f / 16.0f, std::pow(0.9f / high, strength)) : 1.0f;
    const float bright = std::exp2(3.0f * strength);
    tone_exposure[0] = dark;
    tone_exposure[1] = 1.0f;
    tone_exposure[2] = bright;
    // What the fusion makes of an even mid-grey area, so it can be put back at 0.18.
    float sum = 0.0f, wsum = 0.0f;
    for(float e : tone_exposure)
    {
      const float i = std::pow(std::clamp(0.18f * e, 0.0f, 1.0f), 1.0f / 2.2f);
      const float wt = std::exp(-12.5f * (i - 0.5f) * (i - 0.5f)) + 1e-6f;
      sum += wt * i;
      wsum += wt;
    }
    tone_norm = 0.18f / std::pow(sum / wsum, 2.2f);
    note("影调", "高光亮度 (99.5%)", raw ? "%.3f" : "无 RAW", high);
    note("影调", "虚拟曝光（暗 / 原 / 亮）", "%+.2f / 0 / %+.2f EV", std::log2(dark), std::log2(bright));
    note("影调", "中灰校正", "%.3f", tone_norm);
  }

  // Drops the margins in `crop` (raw pixels of a raw_w x raw_h frame) from the developed
  // image, then trims the longer side so the frame keeps its aspect ratio. More than 15%
  // per side is not cropped (the margin then holds the ambient frame alone).
  void crop_result(DevelopResult &result, int raw_w, int raw_h)
  {
    if(!(crop[0] || crop[1] || crop[2] || crop[3]) || raw_w <= 0 || raw_h <= 0) return;
    if(crop[0] + crop[2] > 0.15 * raw_w || crop[1] + crop[3] > 0.15 * raw_h) return;
    const double sx = static_cast<double>(result.width) / raw_w, sy = static_cast<double>(result.height) / raw_h;
    double x0 = crop[0] * sx, x1 = result.width - crop[2] * sx;
    double y0 = crop[1] * sy, y1 = result.height - crop[3] * sy;
    const double aspect = static_cast<double>(result.width) / result.height;
    if((x1 - x0) / (y1 - y0) > aspect)
    {
      const double keep = (y1 - y0) * aspect, mid = 0.5 * (x0 + x1);
      x0 = mid - 0.5 * keep;
      x1 = mid + 0.5 * keep;
    }
    else
    {
      const double keep = (x1 - x0) / aspect, mid = 0.5 * (y0 + y1);
      y0 = mid - 0.5 * keep;
      y1 = mid + 0.5 * keep;
    }
    const int ix0 = std::clamp(static_cast<int>(std::ceil(x0)), 0, result.width - 1);
    const int iy0 = std::clamp(static_cast<int>(std::ceil(y0)), 0, result.height - 1);
    const int w = std::clamp(static_cast<int>(std::floor(x1)) - ix0, 1, result.width - ix0);
    const int h = std::clamp(static_cast<int>(std::floor(y1)) - iy0, 1, result.height - iy0);
    std::vector<uint8_t> out(static_cast<size_t>(w) * h * 4);
    for(int y = 0; y < h; y++)
      std::memcpy(&out[static_cast<size_t>(y) * w * 4], &result.rgba8[(static_cast<size_t>(iy0 + y) * result.width + ix0) * 4],
                  static_cast<size_t>(w) * 4);
    result.rgba8 = std::move(out);
    result.width = w;
    result.height = h;
  }

  bool develop(const SourceImage &source, const FilmParams &raw_params, const DevelopOptions &options,
               DevelopResult &result, std::string &error)
  {
    SourceImage src = source;
    alignment = {};
    record.clear();
    laps.clear();
    lap_start = std::chrono::steady_clock::now();
    // A linear DNG (Samsung Expert RAW, Apple ProRAW): demosaiced already, so no mosaic work;
    // its camera RGB balanced, into Rec.2020, and on as scene light. Metered the reference's
    // way here (its centre-weighted mean to mid grey, never below the file's own exposure).
    float linear_ev = 0.0f;
    bool linear = false;
    // (the app hands even a single frame over as a burst of one)
    if(!src.raw && src.burst && src.burst_count == 1 && src.burst[0]->channels == 3)
    {
      src.raw = src.burst[0];
      src.burst = nullptr;
      src.burst_count = 0;
    }
    if(src.raw && src.raw->channels == 3)
    {
      if(src.burst && src.burst_count > 1)
      {
        error = "linear DNGs are not merged";
        return false;
      }
      const RawImage &r = *src.raw;
      if(r.cfa.size() != static_cast<size_t>(r.width) * r.height * 3)
      {
        error = "linear DNG data does not match its size";
        return false;
      }
      int f = 1; // a box of f x f while it is still twice the working size
      while(std::max(r.width, r.height) / (2 * f) >= options.max_long_edge && f < 16) f *= 2;
      const int w = r.width / f, h = r.height / f;
      linear_buffer.assign(static_cast<size_t>(w) * h * 4, 1.0f);
      const float *m = r.camera_to_rec2020;
      float scale[3];
      for(int c = 0; c < 3; c++) scale[c] = r.wb[c] / std::max(r.white - r.black[c], 1.0f) / float(f * f);
      for(int y = 0; y < h; y++)
        for(int x = 0; x < w; x++)
        {
          float v[3] = {0, 0, 0};
          for(int dy = 0; dy < f; dy++)
            for(int dx = 0; dx < f; dx++)
            {
              const uint16_t *s3 = &r.cfa[(static_cast<size_t>(y * f + dy) * r.width + x * f + dx) * 3];
              for(int c = 0; c < 3; c++) v[c] += std::max(float(s3[c]) - r.black[c], 0.0f);
            }
          // balanced, held at white as the mosaic's develop holds it (blown highlights neutral)
          for(int c = 0; c < 3; c++) v[c] = std::min(v[c] * scale[c], 1.0f);
          float *o = &linear_buffer[(static_cast<size_t>(y) * w + x) * 4];
          for(int k = 0; k < 3; k++) o[k] = std::max(0.0f, m[k * 3] * v[0] + m[k * 3 + 1] * v[1] + m[k * 3 + 2] * v[2]);
        }
      if(options.match_key != 0.0f)
      { // the centre-weighted mean (sigma 0.2 of the long edge), as raw_center_mean
        const float longest = float(std::max(w, h));
        double sum = 0, weights = 0;
        for(int y = 0; y < h; y += 4)
          for(int x = 0; x < w; x += 4)
          {
            const float *o = &linear_buffer[(static_cast<size_t>(y) * w + x) * 4];
            const double dx = (x + 0.5 - w / 2.0) / longest, dy = (y + 0.5 - h / 2.0) / longest;
            const double wt = std::exp(-(dx * dx + dy * dy) / (2 * 0.2 * 0.2));
            sum += wt * (0.2627 * o[0] + 0.6780 * o[1] + 0.0593 * o[2]);
            weights += wt;
          }
        const float mean = weights > 0 ? float(sum / weights) : 0.0f;
        if(mean > 0.0f) linear_ev = std::clamp(-std::log2(mean / 0.184f), 0.0f, 6.0f);
        note("冲洗曝光", "线性 DNG 测光（中央重点）", "测光 %.4f → %+.2f EV（不低于文件曝光）", mean, linear_ev);
      }
      note("RAW 数据", "线性 DNG", "%dx%d，已去马赛克（%s），工作尺寸 %dx%d", r.width, r.height,
           f > 1 ? "先按 2 的倍数缩小" : "原尺寸", w, h);
      src.raw = nullptr;
      src.burst = nullptr;
      src.burst_count = 0;
      src.rgba32f = linear_buffer.data();
      src.width = w;
      src.height = h;
      src.space = ColourSpace::LinearRec2020;
      linear = true;
    }
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
        const bool pair = options.flash.enabled || white_map;
        if(!pair && options.frame_exposure.size() >= frames.size() && options.frame_exposure[0] > 0)
        { // A burst whose exposures the camera reports: the GPU aligns; only frames
          // exposed differently get their exposure measured.
          alignment.gain.assign(frames.size(), 1.0f);
          const GainReference reference(*frames[0]);
          std::vector<std::thread> workers;
          for(size_t f = 1; f < frames.size(); f++)
          {
            const float hint = options.frame_exposure[f] / options.frame_exposure[0];
            if(std::fabs(std::log2(std::max(hint, 1e-6f))) >= 0.02f)
              workers.emplace_back([&, f, hint] {
                // an error escaping a std::thread ends the process: the camera's own ratio then
                try
                {
                  alignment.gain[f] = reference.gain(*frames[f], hint);
                }
                catch(...)
                {
                  alignment.gain[f] = hint;
                }
              });
          }
          for(std::thread &t : workers) t.join();
          for(size_t f = 1; f < frames.size(); f++)
          {
            const float hint = options.frame_exposure[f] / options.frame_exposure[0];
            char k[48];
            std::snprintf(k, sizeof(k), "帧 %d 曝光比（请求 / 实测）", static_cast<int>(f));
            note("对齐", k, "%.3f / %.3f", hint, alignment.gain[f] > 0 ? 1.0f / alignment.gain[f] : 0.0f);
          }
        }
        else if(!align_burst(frames, alignment, error, &same, pair)) return false;
      }
    }
    else src.burst_count = 0;
    if(src.burst_count > 1) lap("对齐");
    const bool flash = options.flash.enabled && src.burst_count >= 2;
    warp = {};
    for(int &m : crop) m = 0;
    if((flash || white_map) && !alignment.offsets.empty())
    { // The flash re-lights the scene, which misleads per-tile matching, and per-tile
      // shifts would show their seams in the flash-only light. The frames are a few
      // hundred milliseconds apart: one similarity (shift, roll, zoom) for the whole
      // frame, fitted to the tiles that agree.
      warp = fit_warp(*src.burst[0], *src.burst[src.burst_count - 1], alignment, src.burst_count - 1);
      if(flash)
      { // Where the flash frame has no data the mix cannot be made: crop it away, plus
        // the mix's widest neighbourhood (+-8 samples) and interpolation on that side.
        warp_margins(warp, src.burst[0]->width, src.burst[0]->height, 0, crop);
        for(int &m : crop)
          if(m > 0) m += 12;
      }
    }
    flash_ambient_k = 1.0f;
    if(white_map && src.burst_count == 2) flash_ambient_k = flash_ambient_scale(*src.burst[0], *src.burst[1], warp);
    float level = 0.0f;
    if(flash) level = flash_level(*src.burst[0], *src.burst[src.burst_count - 1], warp);
    if(src.raw)
    {
      src.width = src.raw->width;
      src.height = src.raw->height;
      if(src.raw->cfa.size() != static_cast<size_t>(src.width) * src.height * src.raw->channels)
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
    if(linear)
    {
      opts.exposure_ev += linear_ev;
      opts.match_key = 0.0f;
      // denoised already by the phone that merged it
      opts.raw_denoise = 0.0f;
      opts.chroma_denoise = 0.0f;
    }
    // a file's own push (BaselineExposure), when nothing else sets the brightness: imports
    if(src.raw && opts.stages.baseline && src.raw->baseline_ev != 0.0f)
    {
      opts.exposure_ev += src.raw->baseline_ev;
      note("冲洗曝光", "DNG 基准曝光", "%+.2f EV", src.raw->baseline_ev);
    }
    if(src.raw && opts.match_key < 0.0f)
    {
      // The reference's exposure: spektrafilm meters the frame (centre-weighted mean to 0.184)
      // and prints at its normalised print exposure; at equal exposure this engine's print
      // matches it. The meter reads the picture as developed, lens shading corrected: the
      // reference's own RAW loader skips a DNG's gain map, so on phones it meters (and shows)
      // dark corners. A fixed -0.73 stop once stood in for that, measured on Galaxy DNGs; each
      // phone's map differs (a Red Magic's corners are 2 stops up, a drone's DNG has none).
      const float mean = raw_center_mean(*src.raw);
      if(mean > 0.0f)
      {
        const float metered = std::clamp(-std::log2(mean / 0.184f), -6.0f, 6.0f);
        // Never below the exposure the frame was shot at: an averaging meter takes a bright
        // scene (white walls, sky in the middle) for mid grey and sinks the rest a stop; the
        // camera's own meter had judged that scene already. Lifting a dark frame stays.
        const float ev = std::max(metered, 0.0f);
        opts.exposure_ev += ev;
        if(ev != metered)
          note("冲洗曝光", "原版曝光（中央重点）", "测光 %.4f → %+.2f EV，不低于拍摄曝光：%+.2f EV", mean, metered, ev);
        else
          note("冲洗曝光", "原版曝光（中央重点）", "测光 %.4f → %+.2f EV", mean, ev);
      }
    }
    else if(src.raw && opts.match_key > 0.0f)
    {
      const float key = raw_key(*src.raw);
      if(key > 0.0f)
      {
        float lift = std::clamp(std::log2(opts.match_key / key), -6.0f, 6.0f);
        if(opts.stages.match_lift)
        {
          const float high = raw_luminance_quantile(*src.raw, 0.995f) * std::exp2(opts.exposure_ev);
          const float room = high > 0.0f ? std::log2(0.9f / high) : 3.0f;
          note("冲洗曝光", "只提亮（闪光）", "想要 %+.2f EV，高光余量 %+.2f EV", lift, room);
          lift = std::clamp(std::min(lift, room), 0.0f, 3.0f);
        }
        opts.exposure_ev += lift;
      }
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
      if(flash || white_map)
      {
        note("对齐", "闪光帧整体配准", warp.fitted ? "拟合（平移 + 旋转 + 缩放）" : "只用中位平移（可用分块不足）");
        note("对齐", "平移 / 旋转 / 缩放", "(%.1f, %.1f) px / %.3f° / %.4f", warp.tx, warp.ty, warp.degrees(), warp.scale());
        note("对齐", "参与分块 / 吻合分块 / 残差", "%d / %d / %.2f px", warp.tiles, warp.used, warp.residual);
        if(flash)
          note("对齐", "裁边（左 上 右 下）", "%d %d %d %d px", crop[0], crop[1], crop[2], crop[3]);
      }
    }
    if(src.raw)
    {
      const float key = raw_key(*src.raw);
      if(options.match_key >= 0.0f) note("冲洗曝光", "取景器亮度 key", "%.5f", options.match_key);
      note("冲洗曝光", "参考帧亮度 key", "%.5f", key);
      note("冲洗曝光", "对齐取景器提亮", options.match_key > 0 && key > 0 ? "%+.2f EV" : "未对齐（%.0f）",
           options.match_key > 0 && key > 0 ? opts.exposure_ev - options.exposure_ev : 0.0f);
      note("冲洗曝光", "最终曝光", "%+.2f EV", opts.exposure_ev);
    }
    if(opts.local_tone > 0.0f) choose_tone(src.raw, opts.exposure_ev, std::min(opts.local_tone, 1.0f));
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
    lap(src.burst_count > 1 ? "统计" : "对齐与统计");
    const bool ok = develop_source(src, raw_params, opts, result, error);
    if(ok && flash) crop_result(result, src.width, src.height);
    if(ok)
    {
      note("冲洗结果", "输出尺寸", "%dx%d", result.width, result.height);
      note("冲洗结果", "GPU 计算", "%d 个 pass，GPU %.1f ms，显存峰值 %.1f MB", result.passes, result.gpu_ms,
           result.peak_image_bytes / 1048576.0);
      if(!result.gpu_breakdown.empty()) note("冲洗结果", "GPU 最耗时的步骤", "%s", result.gpu_breakdown.c_str());
      lap("裁边");
      note("冲洗结果", "引擎各步用时", "%s ms", laps.c_str());
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

  // A print is seen against its own paper. The scan's light was normalised to the lamp, as
  // if a perfect white reflector lay beside the print, so the paper's base (about 0.7 of
  // that on Portra Endura) became the print's ceiling and every print topped out near 218 of
  // 255, the reference spektrafilm's default the same. The eye, and a lab scanner, take the
  // paper for white: the scanner's white point goes on the paper (its luminance, found as
  // the scan with and without the base), and the print is exposed denser so that mid grey
  // still prints where the reference prints it. Colour stays; only the range between mid grey and the paper opens
  // up, as the reference's white correction with its print exposure correction does.
  // Black too, as the reference's black correction: the deepest the print (or slide) goes
  // (unexposed film, so the paper's or the slide's full density) is set at the reference's
  // black level, 0.01 on the sRGB curve, instead of floating a little grey. As there, one
  // linear map of luminance, Y' = m Y + q, each pixel scaled by Y'/Y so its colour stays
  // (encode.comp), and the exposure solved so that mid grey comes out where the reference's does.
  struct PrintCalibration
  {
    FilmParams params;
    ColourSpace output;
    bool slide;
    float m, q, ev;
  };
  std::vector<PrintCalibration> print_cache;
  float print_m = 1.0f;  // Y' = m Y + q: the base onto white, the deepest density onto black
  float print_q = 0.0f;
  float print_ev = 0.0f; // the paper exposure (a slide: the film's) that keeps mid grey there

  bool calibrate_print(const FilmParams &base, ColourSpace output, bool slide, std::string &error)
  {
    for(const PrintCalibration &c : print_cache)
      if(c.output == output && c.slide == slide && std::memcmp(&c.params, &base, sizeof(FilmParams)) == 0)
      {
        print_m = c.m;
        print_q = c.q;
        print_ev = c.ev;
        return true;
      }
    // the patches' own develops are not this frame's: its record and timings stay as they were
    struct Keep
    {
      Impl &self;
      std::string record, laps;
      ~Keep() { self.record = std::move(record); self.laps = std::move(laps); }
    } keep{*this, record, laps};
    FilmParams p = base;
    p.grain = 0;
    p.halation = 0;
    p.scat_amt = 0.0f;
    p.glare = 0.0f;
    p.couplers_radius = 0.0f;
    constexpr int kPatch = 32;
    std::vector<float> grid(static_cast<size_t>(kPatch) * kPatch * 4);
    const float base_full = slide ? p.f_base : p.p_base;
    // a flat patch of [scene] grey through this process, exposure moved by [ev], base scaled
    // by [b]; its luminance as scanned
    auto measure = [&](float scene, float ev, float b, float &y) {
      for(size_t i = 0; i < grid.size(); i += 4)
      {
        grid[i] = grid[i + 1] = grid[i + 2] = scene;
        grid[i + 3] = 1.0f;
      }
      FilmParams q = p;
      (slide ? q.ev_film : q.ev_paper) += ev;
      (slide ? q.f_base : q.p_base) = b;
      SourceImage source;
      source.width = kPatch;
      source.height = kPatch;
      source.rgba32f = grid.data();
      source.space = ColourSpace::LinearRec2020;
      DevelopOptions options;
      options.max_long_edge = kPatch;
      options.output = output;
      options.calibrate = true;
      options.stages.print = !slide;
      options.stages.scan_sharp = 0.0f;
      DevelopResult result;
      if(!develop_source(source, q, options, result, error)) return false;
      double sum[3] = {};
      int n = 0;
      for(int yy = 6; yy < kPatch - 6; yy++)
        for(int x = 6; x < kPatch - 6; x++, n++)
          for(int c = 0; c < 3; c++)
            sum[c] += result.rgba8[(static_cast<size_t>(yy) * result.width + x) * 4 + c];
      float lin[3];
      for(int c = 0; c < 3; c++) lin[c] = std::exp2(static_cast<float>(sum[c] / n / 255.0 * 20.0 - 16.0));
      y = 0.2126f * lin[0] + 0.7152f * lin[1] + 0.0722f * lin[2];
      return true;
    };
    // The reference's anchors: an 18.4% grey in the scene prints where the reference prints it,
    // 117.5 of 255 on the sRGB curve (17.8%, its print exposure normalisation, with or without
    // its black/white correction), and the deepest density at its black level (0.01 on the
    // sRGB curve, as linear light).
    constexpr float kMidGrey = 0.184f;
    constexpr float kMidGreyPrinted = 0.1785f;
    constexpr float kBlack = 0.000774f;
    constexpr float kHighlight = kMidGrey * 32.0f; // five stops over: far up the toe, near the base
    // the base: a highlight scanned with its base and without; the deepest: no light at all
    float with_base = 0.0f, bare = 0.0f, deepest = 0.0f, mid = 0.0f;
    if(!measure(kHighlight, 0.0f, base_full, with_base) ||
       !measure(kHighlight, 0.0f, 0.0f, bare) ||
       !measure(0.0f, 0.0f, base_full, deepest) ||
       !measure(kMidGrey, 0.0f, base_full, mid))
      return false;
    const float white = std::clamp(bare > 1e-6f ? with_base / bare : 1.0f, 0.3f, 1.0f);
    const float black = std::clamp(deepest, 0.0f, white * 0.5f);
    const float m = (1.0f - kBlack) / std::max(white - black, 1e-4f);
    const float q = kBlack - m * black;
    // the exposure that prints mid grey there under the map: secant steps in stops
    auto miss = [&](float y) { return std::log2(std::max(m * y + q, 1e-6f) / kMidGreyPrinted); };
    float e0 = 0.0f, f0 = miss(mid);
    float e1 = 0.25f, y1 = 0.0f;
    if(!measure(kMidGrey, e1, base_full, y1)) return false;
    float f1 = miss(y1);
    for(int i = 0; i < 4 && std::abs(f1) > 0.005f && std::abs(f1 - f0) > 1e-5f; i++)
    {
      const float e2 = std::clamp(e1 - f1 * (e1 - e0) / (f1 - f0), -1.5f, 2.5f);
      float y2 = 0.0f;
      if(!measure(kMidGrey, e2, base_full, y2)) return false;
      e0 = e1; f0 = f1;
      e1 = e2; f1 = miss(y2);
    }
    if(print_cache.size() >= 8) print_cache.erase(print_cache.begin());
    print_cache.push_back({base, output, slide, m, q, e1});
    print_m = m;
    print_q = q;
    print_ev = e1;
    return true;
  }
  std::vector<float> linear_buffer; // a linear DNG as scene light (develop's own source then)
  bool adapt_input = false; // this develop brings the scene to the film's white first

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
    // Grain at the reference spektrafilm's level: measured on flat greys (Portra 400, 4000 px),
    // filmsim's own grain is about a third as strong and coarser. "size" and "grain amt" 1
    // stay the user's neutral, these factors put that neutral where the reference is.
    params.grain_size *= kGrainSizeMatch;
    params.grain_amount *= kGrainAmountMatch;
    // Halation and emulsion scatter at the reference's level (fitted on a bright spot at +2.4,
    // +4 and +6 stops, Portra 400 on Portra Endura, now that halation comes before the
    // inhibitors): the user's 1 and 0.2 stay the neutral settings.
    params.halation_amount *= kHalationMatch;
    params.scat_amt = std::min(1.0f, params.scat_amt * kScatterMatch);
    // slides carry far weaker inhibitor couplers than negatives (the reference's own
    // reversal coupler set is about a third of its negative one; fitted on 4 slide stocks)
    // (slides have their own inhibitor matrix in setup.glsl; kPositiveCouplers is no longer used)
    // the film's own light by chromatic adaptation (see film_adaptation); a light the user
    // picked for the film ("film ill", a mismatched film and light) stays filmsim's relight
    adapt_input = params.film_ill == 0.0f && params.process != kProcessPrintNegative;
    if(adapt_input) params.film_ill = kD65Cct;
    // Before this frame's uniforms are written: calibration develops a frame of its own.
    if(scanning && !options.calibrate && !is_positive_film(params.film) &&
       !calibrate_scan(base, options.output, error))
      return false;
    const bool printing = options.stages.film && options.stages.print;
    if(!options.calibrate)
    {
      print_m = 1.0f;
      print_q = 0.0f;
      print_ev = 0.0f;
      // a print, or a slide scanned as it is: both are seen against their own base
      const bool slide = scanning && is_positive_film(params.film);
      if((printing || slide) && !calibrate_print(base, options.output, slide, error)) return false;
      (slide ? params.ev_film : params.ev_paper) += print_ev;
    }
    params.enlarge = 0;
    params.input_colour = 0;
    std::memcpy(params_ubo.mapped, &params, sizeof(params));
    const int32_t global[4] = {0, 1, static_cast<int32_t>(options.seed), 0}; // frame, frame_cnt, hash
    std::memcpy(global_ubo.mapped, global, sizeof(global));

    int output = -1;
    Graph g = build_graph(params, w, h, source, options, output);
    lap("建图");

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
          ok = take_image(image, want.width, want.height, want.format, usage, error);
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
      ok = take_image(uploads[f], source.width, source.height, upload_format,
                      VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, error);
    if(ok)
      ok = take_image(gain, map_w, map_h, VK_FORMAT_R32G32B32A32_SFLOAT,
                      VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, error);
    if(ok) ok = take_buffer(cached_staging, staging, map_offset + map_bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, error);
    if(ok)
      ok = take_buffer(cached_readback, readback, static_cast<VkDeviceSize>(w) * h * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                       error);
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
      lap("分配与暂存");
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
      if(queries) vkCmdResetQueryPool(cmd, queries, 0, kMaxQueries);
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
        if(queries && i + 1 < kMaxQueries)
          vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queries, static_cast<uint32_t>(i + 1));
        // A phone GPU stops a submission that runs for seconds (VK_ERROR_DEVICE_LOST):
        // the passes go in groups.
        if(i + 1 < n_passes && (i + 1) % 16 == 0)
        {
          ok = submit_and_wait(error) && begin(error);
          if(!ok) break;
        }
      }
      // A submission that failed mid-way (a lost device, out of memory) leaves no command
      // buffer recording: nothing more may go into it (Adreno 619 crashed on the copy).
      if(ok)
      {
        memory_barrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                       VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        copy_region(region, w, h);
        vkCmdCopyImageToBuffer(cmd, physical[mapping[output]].image, VK_IMAGE_LAYOUT_GENERAL, readback.buffer, 1,
                               &region);
        memory_barrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                       VK_ACCESS_HOST_READ_BIT);
        ok = submit_and_wait(error);
      }
      lap("GPU");
    }
    if(ok)
    {
      result.width = static_cast<int>(w);
      result.height = static_cast<int>(h);
      result.rgba8.assign(static_cast<const uint8_t *>(readback.mapped),
                          static_cast<const uint8_t *>(readback.mapped) + static_cast<size_t>(w) * h * 4);
      result.passes = static_cast<int>(n_passes);
      result.gpu_ms = 0.0;
      const uint32_t stamped = static_cast<uint32_t>(std::min<size_t>(n_passes + 1, kMaxQueries));
      std::vector<uint64_t> stamps(stamped);
      if(queries && vkGetQueryPoolResults(device, queries, 0, stamped, stamps.size() * sizeof(uint64_t), stamps.data(),
                                          sizeof(uint64_t), VK_QUERY_RESULT_64_BIT) == VK_SUCCESS)
      {
        result.gpu_ms = static_cast<double>(stamps.back() - stamps[0]) * limits.timestampPeriod * 1e-6;
        // Time per kernel, for the record: where the GPU time went.
        std::array<double, kKernelCount> per{};
        for(uint32_t k = 1; k < stamped; k++)
          per[g.passes[k - 1].kernel] += static_cast<double>(stamps[k] - stamps[k - 1]) * limits.timestampPeriod * 1e-6;
        std::vector<int> order(kKernelCount);
        for(int k = 0; k < kKernelCount; k++) order[k] = k;
        std::sort(order.begin(), order.end(), [&](int a, int b) { return per[a] > per[b]; });
        std::string top;
        for(int k = 0; k < 6 && per[order[k]] > 0.05; k++)
        {
          char item[64];
          std::snprintf(item, sizeof(item), "%s%s %.1f", top.empty() ? "" : "，", kKernels[order[k]].name, per[order[k]]);
          top += item;
        }
        result.gpu_breakdown = top + " ms";
      }
    }

    if(pool) vkDestroyDescriptorPool(device, pool, nullptr);
    // Keep this develop's memory for the next one; what it did not use goes.
    for(Image &image : image_cache) destroy(image);
    image_cache.clear();
    if(ok)
    {
      for(Image &image : physical) image_cache.push_back(image);
      for(Image &image : uploads) image_cache.push_back(image);
      image_cache.push_back(gain);
      destroy(cached_staging);
      destroy(cached_readback);
      cached_staging = staging;
      cached_readback = readback;
    }
    else
    {
      for(Image &image : physical) destroy(image);
      for(Image &image : uploads) destroy(image);
      destroy(gain);
      destroy(staging);
      destroy(readback);
    }
    destroy(offsets);
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
                                image.format == VK_FORMAT_R32_UINT || image.format == VK_FORMAT_R32G32B32A32_SFLOAT;
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

// The lens-shading gain at a point (relative coordinates, the pixel's centre) for one 2x2
// position, exactly as raw.comp samples it: the meters see the picture as it is developed.
float raw_gain_at(const RawImage &raw, float rx, float ry, int position)
{
  if(raw.gain_map.empty() || raw.map_width < 2 || raw.map_height < 2) return 1.0f;
  const float sx = raw.map_spacing[0] > 0 ? raw.map_spacing[0] : 1.0f;
  const float sy = raw.map_spacing[1] > 0 ? raw.map_spacing[1] : 1.0f;
  const float gx = std::clamp((rx - raw.map_origin[0]) / sx, 0.0f, float(raw.map_width - 1));
  const float gy = std::clamp((ry - raw.map_origin[1]) / sy, 0.0f, float(raw.map_height - 1));
  const int ix = std::min(int(gx), raw.map_width - 2), iy = std::min(int(gy), raw.map_height - 2);
  const float fx = gx - ix, fy = gy - iy;
  auto g = [&](int x, int y) { return raw.gain_map[(size_t(y) * raw.map_width + x) * 4 + position]; };
  return (g(ix, iy) * (1 - fx) + g(ix + 1, iy) * fx) * (1 - fy) + (g(ix, iy + 1) * (1 - fx) + g(ix + 1, iy + 1) * fx) * fy;
}

float raw_key(const RawImage &raw)
{
  if(raw.channels != 1) return 0.0f; // a linear DNG is metered by develop itself
  // Sample a 128x96 grid of Bayer quads; same weighting as the app's preview meter.
  constexpr int kCols = 128, kRows = 96;
  const int qw = raw.width / 2, qh = raw.height / 2;
  if(qw < kCols || qh < kRows) return 0.0f;
  // Linear Rec.2020 -> Y (CIE Y is the same luminance the preview meter measures).
  const float *m = raw.camera_to_rec2020;
  const float yr = 0.2627f * m[0] + 0.6780f * m[3] + 0.0593f * m[6];
  const float yg = 0.2627f * m[1] + 0.6780f * m[4] + 0.0593f * m[7];
  const float yb = 0.2627f * m[2] + 0.6780f * m[5] + 0.0593f * m[8];
  auto gain_at = [&](float rx, float ry, int position) { return raw_gain_at(raw, rx, ry, position); };
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

float raw_center_mean(const RawImage &raw)
{
  if(raw.channels != 1) return 0.0f; // a linear DNG is metered by develop itself
  constexpr int kCols = 128, kRows = 96;
  const int qw = raw.width / 2, qh = raw.height / 2;
  if(qw < kCols || qh < kRows) return 0.0f;
  const float *m = raw.camera_to_rec2020;
  const float yr = 0.2627f * m[0] + 0.6780f * m[3] + 0.0593f * m[6];
  const float yg = 0.2627f * m[1] + 0.6780f * m[4] + 0.0593f * m[7];
  const float yb = 0.2627f * m[2] + 0.6780f * m[5] + 0.0593f * m[8];
  // coordinates normalised so the long edge spans [-0.5, 0.5], as spektrafilm's
  const float longest = float(std::max(qw, qh));
  double sum = 0.0, weight_sum = 0.0;
  for(int r = 0; r < kRows; r++)
    for(int c = 0; c < kCols; c++)
    {
      const int qx = (c * qw) / kCols, qy = (r * qh) / kRows;
      float rgb[3] = {0, 0, 0}, n[3] = {0, 0, 0};
      for(int position = 0; position < 4; position++)
      {
        const int x = 2 * qx + (position & 1), y = 2 * qy + (position >> 1);
        const float black = raw.black[position];
        float v = (raw.cfa[size_t(y) * raw.width + x] - black) / (raw.white - black);
        const int colour = raw.cfa_colour[position];
        // with the lens shading corrected, as the develop does (a phone's corners can be
        // two stops up): metered on the picture that is printed
        const float gain = raw_gain_at(raw, (x + 0.5f) / raw.width, (y + 0.5f) / raw.height, position);
        rgb[colour] += std::clamp(v * gain * raw.wb[colour], 0.0f, 1.0f);
        n[colour] += 1;
      }
      for(int k = 0; k < 3; k++) rgb[k] = n[k] > 0 ? rgb[k] / n[k] : 0.0f;
      const float y = std::max(0.0f, yr * rgb[0] + yg * rgb[1] + yb * rgb[2]);
      const double dx = ((c + 0.5) / kCols - 0.5) * qw / longest, dy = ((r + 0.5) / kRows - 0.5) * qh / longest;
      const double w = std::exp(-(dx * dx + dy * dy) / (2 * 0.2 * 0.2));
      sum += w * y;
      weight_sum += w;
    }
  return weight_sum > 0 ? static_cast<float>(sum / weight_sum) : 0.0f;
}

float raw_luminance_quantile(const RawImage &raw, float q)
{
  if(raw.channels != 1) return 0.0f; // a linear DNG is metered by develop itself
  // Every 4th Bayer quad each way, rendered as raw_key does (no shading map: the
  // quantile only needs the bright end, near the centre for most frames).
  const float *m = raw.camera_to_rec2020;
  const float yr = 0.2627f * m[0] + 0.6780f * m[3] + 0.0593f * m[6];
  const float yg = 0.2627f * m[1] + 0.6780f * m[4] + 0.0593f * m[7];
  const float yb = 0.2627f * m[2] + 0.6780f * m[5] + 0.0593f * m[8];
  std::vector<float> ys;
  for(int qy = 0; qy < raw.height / 2; qy += 4)
    for(int qx = 0; qx < raw.width / 2; qx += 4)
    {
      float rgb[3] = {0, 0, 0}, n[3] = {0, 0, 0};
      for(int position = 0; position < 4; position++)
      {
        const int x = 2 * qx + (position & 1), y = 2 * qy + (position >> 1);
        const float black = raw.black[position];
        const float v = (raw.cfa[size_t(y) * raw.width + x] - black) / (raw.white - black);
        const int colour = raw.cfa_colour[position];
        rgb[colour] += std::max(v, 0.0f) * raw.wb[colour];
        n[colour] += 1;
      }
      for(int k = 0; k < 3; k++) rgb[k] = n[k] > 0 ? rgb[k] / n[k] : 0.0f;
      ys.push_back(std::max(0.0f, yr * rgb[0] + yg * rgb[1] + yb * rgb[2]));
    }
  if(ys.empty()) return 0.0f;
  auto at = ys.begin() + static_cast<long>(std::clamp(q, 0.0f, 1.0f) * (ys.size() - 1));
  std::nth_element(ys.begin(), at, ys.end());
  return *at;
}

FilmEngine::FilmEngine(std::unique_ptr<Impl> impl) : impl_(std::move(impl)) {}
FilmEngine::~FilmEngine() = default;

void set_stage_hook(void (*hook)(const char *stage)) { g_stage_hook = hook; }

std::unique_ptr<FilmEngine> FilmEngine::create(const Lut &filmsim, const Lut &spectra, std::string &error,
                                               bool burst_kernels, bool safe_shaders)
{
  auto impl = std::make_unique<Impl>();
  impl->info.safe_shaders = safe_shaders;
  impl->info.burst = burst_kernels && !safe_shaders;
  report_stage("vulkan");
  const bool ok = impl->init(filmsim, spectra, error);
  report_stage(ok ? "ready" : "failed");
  if(!ok) return nullptr;
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
