// Reader for the uncompressed Bayer DNGs Android's DngCreator writes (CameraX RAW).
#pragma once
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace film {

// Linear sensor data plus everything needed to reach scene-linear Rec.2020.
struct RawImage
{
  int width = 0, height = 0;
  // 1: a Bayer mosaic (cfa holds width*height samples). 3: a linear DNG, already
  // demosaiced (Samsung Expert RAW, Apple ProRAW): cfa holds width*height*3, RGB interleaved
  // in camera space; black[0..2] and the white level are per channel then.
  int channels = 1;
  std::vector<uint16_t> cfa;         // width*height samples, row-major
  int cfa_colour[4] = {0, 1, 1, 2};  // colour (0 R, 1 G, 2 B) at 2x2 positions (0,0) (1,0) (0,1) (1,1)
  float black[4] = {0, 0, 0, 0};     // per 2x2 position
  float white = 1023.0f;
  float wb[3] = {1, 1, 1};           // multipliers applied to sensor values, min 1
  float camera_to_rec2020[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1}; // white-balanced camera RGB -> linear Rec.2020 D65
  // Lens shading gain maps (DNG OpcodeList2 GainMap), one plane per 2x2 position.
  int map_width = 0, map_height = 0;
  float map_origin[2] = {0, 0}, map_spacing[2] = {1, 1}; // relative to image width/height
  std::vector<float> gain_map;       // map_width*map_height*4, position index fastest
  int orientation = 1;               // TIFF orientation: 1, 3 (180), 6 (90 cw), 8 (270 cw)
  // Sensor noise (DNG NoiseProfile): variance = scale * x + offset, x normalised to [0, 1].
  // Zero when the file has none; burst merging then falls back to a generic model.
  float noise_scale = 0.0f, noise_offset = 0.0f;
  // DNG BaselineExposure (+ BaselineExposureOffset), in stops: how much brighter the file
  // asks to be shown. A phone's own camera app often keeps its RAW 1-3 stops under to save
  // the highlights and writes the push back here; CameraX / DngCreator files have none.
  float baseline_ev = 0.0f;
};

// `neutral` (camera rgb of a neutral, as DNG AsShotNeutral) replaces the file's own when
// given: CameraX can write a stale one into its DNGs. `long_edge`: the size the develop wants,
// which lets a camera RAW that is demosaiced while read (X-Trans) be read smaller (0: full).
bool parse_dng(const uint8_t *data, size_t size, RawImage &out, std::string &error,
               const float *neutral = nullptr, int long_edge = 0);

} // namespace film
