// Spectral film development on the GPU: vkdt's filmsim (spektrafilm) compute
// passes driven by a small standalone Vulkan host, for Android and desktop tests.
#pragma once
#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "dng.h"
#include "film_params.h"

namespace film {

// vkdt .lut file: 16-byte header, then wd*ht*channels texels (trailing licence text ignored).
struct Lut
{
  uint32_t width = 0, height = 0, channels = 0;
  bool half = false;
  std::vector<uint8_t> texels;
};
bool parse_lut(const uint8_t *data, size_t size, Lut &out, std::string &error);

enum class ColourSpace { Srgb, DisplayP3, LinearRec2020 };

struct SourceImage
{
  int width = 0, height = 0;
  const uint8_t *rgba8 = nullptr;  // display-encoded sRGB or Display P3, tightly packed
  const float *rgba32f = nullptr;  // or scene-linear light in `space` primaries
  const RawImage *raw = nullptr;   // or Bayer sensor data (width/height/space ignored)
  // or a burst of equally exposed raws (up to 6, first is the reference) merged HDR+-style
  const RawImage *const *burst = nullptr;
  int burst_count = 0;
  ColourSpace space = ColourSpace::Srgb;
};

// Flash / no-flash re-lighting (raw bursts only): the last burst frame was taken with
// the flash at the same exposure as the others; the develop mixes ambient and flash light.
struct FlashMix
{
  bool enabled = false;
  float ambient = 1.0f;     // gain on the ambient light (0.25 = two stops darker)
  float target = 0.3f;      // scene-linear level the brightest flash-lit areas (98th
                            // percentile of the flash-only light) are brought to
  float max_gain = 4.0f;    // limit on the flash gain (it also scales the flash's noise)
  float wb[3] = {1, 1, 1};  // flash colour -> ambient white, per camera channel
};

// Single flash white balance (raw only): burst = {ambient frame, flash frame}. The flash
// frame is developed alone; the ambient one tells where the flash lit, and the white is
// blended per area between the two lights' neutrals (camera rgb, as DNG AsShotNeutral).
struct FlashWhite
{
  bool enabled = false;
  float ambient[3] = {1, 1, 1};
  float flash[3] = {1, 1, 1};
};

struct DevelopOptions
{
  int max_long_edge = 4096;   // working resolution; larger sources are area-resampled
  float exposure_ev = 0.0f;   // camera exposure compensation, applied in linear light
  uint32_t seed = 1;          // grain and glare seed; keep it to redevelop identically
  ColourSpace output = ColourSpace::Srgb;
  // Raw sources only: when > 0, scale exposure so the raw's centre-weighted
  // log-average luminance equals this (linear, the viewfinder's measured key),
  // making the develop start from the same brightness the preview showed.
  // exposure_ev is then an offset on top.
  float match_key = 0.0f;
  // Burst frames' exposures (time x gain) relative to each other, as the camera was
  // asked for, when known: frames with the same value need no exposure measurement,
  // the others only a quick one. Empty: measured with align_burst.
  std::vector<float> frame_exposure;
  Stages stages;
  // Local tone compression before the film, 0..1: large-scale brightness pulled towards
  // mid grey (shadows lifted, highlights lowered), local contrast kept. For HDR merges.
  float local_tone = 0.0f;
  // Chroma noise reduction before the film, 0..1: colour smoothed, luminance kept.
  float chroma_denoise = 0.0f;
  // Raw sources: denoise of the mosaic before demosaicing (vkdt's wavelet denoise),
  // driven by the DNG noise profile. Strength as in vkdt (0..1, about 0.3 for clean
  // shadows that keep their detail; 0.5 starts to smear).
  float raw_denoise = 0.0f;
  FlashMix flash;
  FlashWhite flash_white;
  bool calibrate = false; // internal: write log2 of the linear result (scan calibration)
};

// Centre-weighted log-average luminance (linear Y) of a raw, as the camera
// pipeline would render it: black level, shading, white balance, colour matrix.
float raw_key(const RawImage &raw);

// Quantile `q` (0..1) of a raw's luminance (linear Y as raw_key renders it).
float raw_luminance_quantile(const RawImage &raw, float q);

struct DevelopResult
{
  int width = 0, height = 0;
  std::vector<uint8_t> rgba8;
  double gpu_ms = 0.0;            // 0 when the queue has no timestamps
  std::string gpu_breakdown;      // the slowest kernels, "name ms" each
  uint64_t peak_image_bytes = 0;  // GPU images alive at once for this frame
  int passes = 0;
  // Every decision of the develop, one "section\tkey\tvalue" line each (the photo's record).
  std::string note;
};

struct DeviceInfo
{
  std::string name;
  uint32_t api_version = 0;
  uint32_t subgroup_size = 0;
  bool timestamps = false;
};

class FilmEngine
{
public:
  static std::unique_ptr<FilmEngine> create(const Lut &filmsim, const Lut &spectra, std::string &error);
  ~FilmEngine();
  FilmEngine(const FilmEngine &) = delete;
  FilmEngine &operator=(const FilmEngine &) = delete;

  bool develop(const SourceImage &source, const FilmParams &params, const DevelopOptions &options,
               DevelopResult &result, std::string &error);

  // Per-pixel part of the film look as a size^3 RGBA8 3D LUT for live preview:
  // input is the display-encoded camera frame (sRGB transfer), output is encoded
  // in `output`. Texel order r + g*size + b*size^2, i.e. a GL_TEXTURE_3D upload.
  // Spatial effects (halation, scatter, grain, coupler diffusion) cannot live in
  // a LUT and are left to the full develop.
  bool bake_preview_lut(const FilmParams &params, int size, ColourSpace output, std::vector<uint8_t> &rgba,
                        std::string &error, const Stages &stages = {});
  // Creative look applied after the film, on the sRGB-encoded result (and so also in
  // preview LUTs): an RGB8 size^3 table, red fastest, sRGB in and out, blended at
  // [strength]. Null clears it. Applies to every following develop.
  bool set_creative_lut(const uint8_t *rgb, int size, float strength, std::string &error);

  const DeviceInfo &device() const;

  struct Impl;

private:
  explicit FilmEngine(std::unique_ptr<Impl> impl);
  std::unique_ptr<Impl> impl_;
};

} // namespace film
