#pragma once
// Camera RAW files that are not DNG, read with LibRaw into a RawImage (see camera_raw.cpp).
#include <cstddef>
#include <cstdint>
#include <string>

#include "dng.h"

namespace film {

// Bayer camera RAWs as a mosaic; Fujifilm X-Trans demosaiced here into linear camera RGB
// (channels 3), at a third of the sensor when that still covers `long_edge` (0: full size).
// False with `error` set when LibRaw cannot open or unpack the file, or it is neither (Foveon,
// linear formats).
bool parse_camera_raw(const uint8_t *data, size_t size, RawImage &out, std::string &error, int long_edge = 0);

} // namespace film
