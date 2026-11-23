// Camera RAW files that are not DNG (Nikon NEF, Canon CR2/CR3, Sony ARW, Fujifilm RAF,
// Olympus ORF, Panasonic RW2, ...), read with LibRaw (CDDL 1.0, see engine/libraw/README.md)
// into the same RawImage a DNG fills: the Bayer mosaic, its black and white levels, the
// camera's as-shot white balance, its colour matrix and orientation. Only LibRaw's unpacking
// is used; demosaicing and everything after it are the engine's own.
#include "camera_raw.h"

#include <algorithm>
#include <cstring>
#include <memory>

#include "libraw/libraw.h"

namespace film {

namespace {

// linear sRGB -> linear Rec.2020 (both D65)
constexpr double kSrgbToRec2020[3][3] = {{0.627404, 0.329283, 0.043313},
                                         {0.069097, 0.919540, 0.011362},
                                         {0.016391, 0.088013, 0.895595}};

std::string camera_name(const LibRaw &raw)
{
  const libraw_iparams_t &id = raw.imgdata.idata;
  std::string name = std::string(id.make) + " " + id.model;
  while(!name.empty() && name.back() == ' ') name.pop_back();
  return name;
}

} // namespace

bool parse_camera_raw(const uint8_t *data, size_t size, RawImage &out, std::string &error)
{
  // LibRaw's object is large (its tables): on the heap, not the caller's stack
  auto raw = std::make_unique<LibRaw>();
  int code = raw->open_buffer(data, size);
  if(code != LIBRAW_SUCCESS)
  {
    error = std::string("camera RAW not readable (LibRaw: ") + libraw_strerror(code) + ")";
    return false;
  }
  const std::string camera = camera_name(*raw);
  const libraw_iparams_t &id = raw->imgdata.idata;
  // Bayer mosaics only: the engine demosaics 2x2 RGGB-type patterns
  if(id.filters == 9)
  {
    error = "camera RAW not supported: Fujifilm X-Trans sensor (" + camera + ")";
    return false;
  }
  if(id.filters < 1000 || id.colors != 3 || id.is_foveon)
  {
    error = "camera RAW not supported: not a Bayer mosaic (" + camera + ")";
    return false;
  }
  code = raw->unpack();
  if(code != LIBRAW_SUCCESS || !raw->imgdata.rawdata.raw_image)
  {
    error = "camera RAW not supported: " +
            std::string(code != LIBRAW_SUCCESS ? libraw_strerror(code) : "no mosaic data (a compressed or linear format)") +
            " (" + camera + ")";
    return false;
  }

  const libraw_image_sizes_t &s = raw->imgdata.sizes;
  const libraw_colordata_t &c = raw->imgdata.color;
  // the visible area, an even size so the 2x2 pattern stays whole
  const int w = s.width & ~1, h = s.height & ~1;
  if(w < 64 || h < 64 || s.left_margin + w > s.raw_width || s.top_margin + h > s.raw_height)
  {
    error = "camera RAW has an unexpected frame (" + camera + ")";
    return false;
  }
  out = RawImage{};
  out.width = w;
  out.height = h;
  out.cfa.resize(size_t(w) * h);
  const size_t pitch = s.raw_pitch / sizeof(uint16_t);
  const uint16_t *src = raw->imgdata.rawdata.raw_image;
  for(int y = 0; y < h; y++)
    std::memcpy(&out.cfa[size_t(y) * w], src + size_t(y + s.top_margin) * pitch + s.left_margin, size_t(w) * sizeof(uint16_t));

  // the 2x2 positions (0,0) (1,0) (0,1) (1,1): colour (LibRaw's second green, 3, is green) and black
  for(int position = 0; position < 4; position++)
  {
    const int col = position & 1, row = position >> 1;
    const int colour = raw->COLOR(row, col);
    out.cfa_colour[position] = colour == 3 ? 1 : std::clamp(colour, 0, 2);
    float black = float(c.black) + float(c.cblack[std::clamp(colour, 0, 3)]);
    if(c.cblack[4] > 0 && c.cblack[5] > 0)
      black += float(c.cblack[6 + (row % c.cblack[4]) * c.cblack[5] + col % c.cblack[5]]);
    out.black[position] = black;
  }
  out.white = float(c.maximum);
  if(out.white <= out.black[0] + 16.0f)
  {
    error = "camera RAW has no usable white level (" + camera + ")";
    return false;
  }

  // the camera's as-shot white balance (daylight when it wrote none), lowest multiplier 1
  float wb[3] = {c.cam_mul[0], c.cam_mul[1], c.cam_mul[2]};
  if(!(wb[0] > 0 && wb[1] > 0 && wb[2] > 0)) { wb[0] = c.pre_mul[0]; wb[1] = c.pre_mul[1]; wb[2] = c.pre_mul[2]; }
  if(!(wb[0] > 0 && wb[1] > 0 && wb[2] > 0)) { wb[0] = wb[1] = wb[2] = 1.0f; }
  const float least = (std::min)({wb[0], wb[1], wb[2]}); // (windows.h's min macro, via libraw.h)
  for(int k = 0; k < 3; k++) out.wb[k] = wb[k] / least;

  // LibRaw's rgb_cam: white-balanced camera RGB -> linear sRGB (from the camera's D65 matrix)
  for(int r = 0; r < 3; r++)
    for(int k = 0; k < 3; k++)
    {
      double v = 0.0;
      for(int j = 0; j < 3; j++) v += kSrgbToRec2020[r][j] * c.rgb_cam[j][k];
      out.camera_to_rec2020[r * 3 + k] = float(v);
    }

  // LibRaw's flip: 0 none, 3 180, 5 90 counter-clockwise, 6 90 clockwise (as TIFF 8 / 6)
  switch(s.flip)
  {
  case 3: out.orientation = 3; break;
  case 5: out.orientation = 8; break;
  case 6: out.orientation = 6; break;
  default: out.orientation = 1; break;
  }
  return true;
}

} // namespace film
