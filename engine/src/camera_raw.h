#pragma once
// Camera RAW files that are not DNG, read with LibRaw into a RawImage (see camera_raw.cpp).
#include <cstddef>
#include <cstdint>
#include <string>

#include "dng.h"

namespace film {

// Bayer camera RAWs only (no X-Trans, Foveon or linear formats). False with `error` set when
// LibRaw cannot open or unpack the file, or it is not a Bayer mosaic.
bool parse_camera_raw(const uint8_t *data, size_t size, RawImage &out, std::string &error);

} // namespace film
