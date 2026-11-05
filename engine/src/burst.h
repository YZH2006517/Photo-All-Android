// Burst alignment for HDR+-style merging (after Hasinoff et al. 2016, "Burst
// photography for high dynamic range and low-light imaging on mobile cameras"),
// extended to exposure brackets (short reference frames plus longer ones).
#pragma once
#include <cstdint>
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
bool align_burst(const std::vector<const RawImage *> &frames, BurstAlignment &out, std::string &error,
                 const std::vector<char> *same_exposure = nullptr);

// Brightness the flash adds: the 98th percentile, over 2x2 quads lit by it, of
// (flash - ambient) quad means normalised to [0, 1], with `flash` registered to
// `ambient` by alignment frame `index`. 0 when the flash reached nothing.
float flash_level(const RawImage &ambient, const RawImage &flash, const BurstAlignment &alignment, int index);

// Exposure of `flash` relative to `ambient` (flash ~ k ambient where the flash added
// nothing): a low percentile of quad ratios, since the flash only ever adds light.
float flash_ambient_scale(const RawImage &flash, const RawImage &ambient, const BurstAlignment &alignment, int index);

} // namespace film
