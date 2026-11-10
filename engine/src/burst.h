// Burst alignment for HDR+-style merging (after Hasinoff et al. 2016, "Burst
// photography for high dynamic range and low-light imaging on mobile cameras"),
// extended to exposure brackets (short reference frames plus longer ones).
#pragma once
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "dng.h"

namespace film {

struct BurstAlignment
{
  int tile = 256;              // tile size in raw pixels
  int tiles_x = 0, tiles_y = 0;
  std::vector<int32_t> offsets; // [frame][tile] (dx, dy) in raw pixels, always even; frame 0 is zero
  // [frame] multiplier bringing that frame's black-subtracted, normalised values to the
  // exposure of frames[0]: 1 for equal exposures, 1/4 for a frame exposed two stops longer.
  std::vector<float> gain;
};

// Aligns every frame to frames[0]: coarse-to-fine global translation on a
// Bayer-quad luminance pyramid, then a per-tile refinement. Frames exposed
// differently are measured against the reference first (median ratio of quads
// well exposed in both) and compared at the reference's exposure, with clipped
// regions flattened so they do not pull the search.
// `same_exposure` (per frame, may be null): 1 skips the ratio measurement for a frame
// known to share the reference's exposure; 2 also matches it by lighting-independent
// structure (log minus local mean), for a frame lit differently, e.g. by a flash.
// `per_tile` false: the global translation and exposure only (every tile gets the global
// offset); the burst merge aligns tiles itself on the GPU.
bool align_burst(const std::vector<const RawImage *> &frames, BurstAlignment &out, std::string &error,
                 const std::vector<char> *same_exposure = nullptr, bool per_tile = true);

// The scale bringing `img` to the exposure of `ref` (about 1 / hint, where hint is the
// exposure of img relative to ref as the camera was asked for): 16x16-pixel block
// means registered globally, then the median ratio of blocks well exposed in both.
// Much cheaper than align_burst, for bursts the GPU aligns.
float measure_gain(const RawImage &ref, const RawImage &img, float hint);

// The same for several frames against one reference: its blocks are summed once, and
// gain() may run on several threads at once.
class GainReference
{
public:
  explicit GainReference(const RawImage &ref);
  ~GainReference();
  float gain(const RawImage &img, float hint) const;

private:
  struct Blocks;
  const RawImage &ref_;
  std::unique_ptr<Blocks> blocks_;
};

// A whole-frame similarity (shift, roll, zoom) registering one frame to the reference,
// in raw pixels about the image centre c:
//   q = c + [a -b; b a] (p - c) + t
// Two frames a few hundred milliseconds apart (a flash pair) differ by more than a
// shift: a slight roll of the hand moves the corners by several pixels.
struct Warp
{
  float a = 1.0f, b = 0.0f, tx = 0.0f, ty = 0.0f;
  bool fitted = false;      // false: the median tile shift only
  int tiles = 0, used = 0;  // textured tiles measured / agreeing with the fit
  float residual = 0.0f;    // RMS distance of the agreeing tiles from the fit, raw pixels
  void map(float x, float y, int width, int height, float &qx, float &qy) const
  {
    const float cx = 0.5f * width, cy = 0.5f * height;
    qx = cx + a * (x - cx) - b * (y - cy) + tx;
    qy = cy + b * (x - cx) + a * (y - cy) + ty;
  }
  float degrees() const;
  float scale() const;
};

// Fits a Warp for frame `index` of `alignment` (a frame lit differently, matched by
// structure): every textured tile is re-measured to a fraction of a pixel around the
// prediction, and the similarity fitted with outliers (moving things, flat tiles that
// matched noise) down-weighted. Falls back to the median tile shift.
Warp fit_warp(const RawImage &ref, const RawImage &img, const BurstAlignment &alignment, int index);

// Margins (raw pixels: left, top, right, bottom) the reference must lose so that every
// remaining pixel, plus `pad` around it, maps inside the other frame.
void warp_margins(const Warp &warp, int width, int height, int pad, int margins[4]);

// Brightness the flash adds: the 98th percentile, over 2x2 quads lit by it, of
// (flash - ambient) quad means normalised to [0, 1], with `flash` registered to
// `ambient` by `warp`. 0 when the flash reached nothing.
float flash_level(const RawImage &ambient, const RawImage &flash, const Warp &warp);

// Exposure of `flash` relative to `ambient` (flash ~ k ambient where the flash added
// nothing): a low percentile of quad ratios, since the flash only ever adds light.
// `warp` registers the ambient frame to the flash frame.
float flash_ambient_scale(const RawImage &flash, const RawImage &ambient, const Warp &warp);

} // namespace film
