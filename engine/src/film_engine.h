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
  Stages stages;
  bool calibrate = false; // internal: write log2 of the linear result (scan calibration)
};

// Centre-weighted log-average luminance (linear Y) of a raw, as the camera
// pipeline would render it: black level, shading, white balance, colour matrix.
float raw_key(const RawImage &raw);

struct DevelopResult
{
  int width = 0, height = 0;
  std::vector<uint8_t> rgba8;
  double gpu_ms = 0.0;            // 0 when the queue has no timestamps
  uint64_t peak_image_bytes = 0;  // GPU images alive at once for this frame
  int passes = 0;
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
