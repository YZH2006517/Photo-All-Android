// Camera RAW files that are not DNG (Nikon NEF, Canon CR2/CR3, Sony ARW, Fujifilm RAF,
// Olympus ORF, Panasonic RW2, ...), read with LibRaw (CDDL 1.0, see engine/libraw/README.md)
// into the same RawImage a DNG fills: the Bayer mosaic, its black and white levels, the
// camera's as-shot white balance, its colour matrix and orientation. For a Bayer mosaic only
// LibRaw's unpacking is used; demosaicing and everything after it are the engine's own. A
// Fujifilm X-Trans mosaic (6x6, which the engine's 2x2 demosaic cannot take) is demosaiced
// here instead and handed on as linear camera RGB, as a linear DNG is: for a small result
// each 3x3 cell (always 2 red, 5 green, 2 blue) is averaged into one pixel, for a large one
// LibRaw's own X-Trans demosaic (Markesteijn, one pass) runs at full size.
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

// the black level at a pixel of the visible area, of the colour there
float black_at(const libraw_colordata_t &c, int colour, int row, int col)
{
  float black = float(c.black) + float(c.cblack[std::clamp(colour, 0, 3)]);
  if(c.cblack[4] > 0 && c.cblack[5] > 0)
    black += float(c.cblack[6 + (row % c.cblack[4]) * c.cblack[5] + col % c.cblack[5]]);
  return black;
}

// as-shot white balance, colour matrix and orientation, as the file has them before any
// processing (LibRaw's own processing changes some of them)
void take_colour(const LibRaw &raw, RawImage &out)
{
  const libraw_colordata_t &c = raw.imgdata.color;
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
  switch(raw.imgdata.sizes.flip)
  {
  case 3: out.orientation = 3; break;
  case 5: out.orientation = 8; break;
  case 6: out.orientation = 6; break;
  default: out.orientation = 1; break;
  }
}

// An unpacked X-Trans mosaic into linear camera RGB (channels 3, black 0). `long_edge` is the
// size the develop wants: when a third of the sensor still covers it, 3x3 cells averaged.
bool xtrans_rgb(LibRaw &raw, const std::string &camera, int long_edge, RawImage &out, std::string &error)
{
  const libraw_image_sizes_t &s = raw.imgdata.sizes;
  const libraw_colordata_t &c = raw.imgdata.color;
  const int width = s.width, height = s.height;
  if(width < 192 || height < 192 || s.left_margin + width > s.raw_width || s.top_margin + height > s.raw_height)
  {
    error = "camera RAW has an unexpected frame (" + camera + ")";
    return false;
  }
  out = RawImage{};
  out.channels = 3;
  take_colour(raw, out);
  if(c.maximum <= c.black + 16)
  {
    error = "camera RAW has no usable white level (" + camera + ")";
    return false;
  }
  // the 6x6 pattern's colours and blacks, in visible coordinates
  int colour[6][6];
  float black[6][6];
  float black_sum = 0.0f;
  for(int y = 0; y < 6; y++)
    for(int x = 0; x < 6; x++)
    {
      const int k = raw.COLOR(y, x);
      colour[y][x] = k == 3 ? 1 : std::clamp(k, 0, 2);
      black[y][x] = black_at(c, k, y, x);
      black_sum += black[y][x];
    }
  const float black_mean = black_sum / 36.0f;

  if(long_edge > 0 && (std::max)(width, height) / 3 >= long_edge)
  {
    // the phase at which every 3x3 cell holds all three colours
    int phase_y = -1, phase_x = -1;
    for(int py = 0; py < 3 && phase_y < 0; py++)
      for(int px = 0; px < 3 && phase_y < 0; px++)
      {
        bool whole = true;
        for(int cy = 0; cy < 2 && whole; cy++)
          for(int cx = 0; cx < 2 && whole; cx++)
          {
            int n[3] = {0, 0, 0};
            for(int dy = 0; dy < 3; dy++)
              for(int dx = 0; dx < 3; dx++) n[colour[(py + cy * 3 + dy) % 6][(px + cx * 3 + dx) % 6]]++;
            whole = n[0] > 0 && n[1] > 0 && n[2] > 0;
          }
        if(whole) { phase_y = py; phase_x = px; }
      }
    if(phase_y < 0)
    {
      error = "camera RAW has an unknown X-Trans pattern (" + camera + ")";
      return false;
    }
    const int w = (width - phase_x) / 3, h = (height - phase_y) / 3;
    out.width = w;
    out.height = h;
    out.cfa.resize(size_t(w) * h * 3);
    const size_t pitch = s.raw_pitch / sizeof(uint16_t);
    const uint16_t *src = raw.imgdata.rawdata.raw_image;
    for(int by = 0; by < h; by++)
      for(int bx = 0; bx < w; bx++)
      {
        float sum[3] = {0, 0, 0};
        int n[3] = {0, 0, 0};
        for(int dy = 0; dy < 3; dy++)
          for(int dx = 0; dx < 3; dx++)
          {
            const int row = phase_y + by * 3 + dy, col = phase_x + bx * 3 + dx;
            const float v = float(src[size_t(row + s.top_margin) * pitch + col + s.left_margin]);
            const int k = colour[row % 6][col % 6];
            sum[k] += (std::max)(v - black[row % 6][col % 6], 0.0f);
            n[k]++;
          }
        uint16_t *o = &out.cfa[(size_t(by) * w + bx) * 3];
        for(int k = 0; k < 3; k++) o[k] = uint16_t((std::min)(sum[k] / float(n[k]) + 0.5f, 65535.0f));
      }
    out.white = float(c.maximum) - black_mean;
    return true;
  }

  // full size: LibRaw's demosaic on camera RGB as it is (no white balance, no colour space,
  // linear, no brightening), black taken off and scaled so white is 65535
  libraw_output_params_t &p = raw.imgdata.params;
  p.output_color = 0;
  p.use_camera_wb = 0;
  p.use_auto_wb = 0;
  for(int k = 0; k < 4; k++) p.user_mul[k] = 1.0f;
  p.no_auto_bright = 1;
  p.gamm[0] = 1.0;
  p.gamm[1] = 1.0;
  p.highlight = 0;
  p.user_qual = 2; // X-Trans: Markesteijn, one pass
  p.half_size = 0;
  p.four_color_rgb = 0;
  p.med_passes = 0;
  p.adjust_maximum_thr = 0.0f;
  const int code = raw.dcraw_process();
  if(code != LIBRAW_SUCCESS || !raw.imgdata.image)
  {
    error = "camera RAW X-Trans demosaic failed: " + std::string(libraw_strerror(code)) + " (" + camera + ")";
    return false;
  }
  const int w = raw.imgdata.sizes.iwidth, h = raw.imgdata.sizes.iheight;
  out.width = w;
  out.height = h;
  out.cfa.resize(size_t(w) * h * 3);
  const auto *image = raw.imgdata.image;
  for(size_t i = 0, n = size_t(w) * h; i < n; i++)
    for(int k = 0; k < 3; k++) out.cfa[i * 3 + k] = image[i][k];
  raw.recycle();
  out.white = 65535.0f;
  return true;
}

} // namespace

bool parse_camera_raw(const uint8_t *data, size_t size, RawImage &out, std::string &error, int long_edge)
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
    const int unpacked = raw->unpack();
    if(unpacked != LIBRAW_SUCCESS || !raw->imgdata.rawdata.raw_image)
    {
      error = "camera RAW not supported: " +
              std::string(unpacked != LIBRAW_SUCCESS ? libraw_strerror(unpacked) : "no X-Trans mosaic data") + " (" + camera + ")";
      return false;
    }
    return xtrans_rgb(*raw, camera, long_edge, out, error);
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
    out.black[position] = black_at(c, colour, row, col);
  }
  out.white = float(c.maximum);
  if(out.white <= out.black[0] + 16.0f)
  {
    error = "camera RAW has no usable white level (" + camera + ")";
    return false;
  }

  take_colour(*raw, out);
  return true;
}

} // namespace film
