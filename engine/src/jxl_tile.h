#pragma once
// One JPEG XL codestream (a strip or tile of a DNG, compression 52546) to 16-bit samples.
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace film {

// [out]: width x height x channels samples, channels interleaved, row-major.
bool jxl_decode(const uint8_t *p, size_t n, std::vector<uint16_t> &out, int &width, int &height, int &channels,
                std::string &error);

} // namespace film
