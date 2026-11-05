// Burst alignment for HDR+-style merging (after Hasinoff et al. 2016, "Burst
// photography for high dynamic range and low-light imaging on mobile cameras").
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
};

// Aligns every frame to frames[0]: coarse-to-fine global translation on a
// Bayer-quad luminance pyramid, then a per-tile refinement.
bool align_burst(const std::vector<const RawImage *> &frames, BurstAlignment &out, std::string &error);

} // namespace film
