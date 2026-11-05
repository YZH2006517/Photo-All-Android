#include "burst.h"

#include <algorithm>
#include <climits>
#include <cmath>

namespace film {
namespace {

struct Plane
{
  int w = 0, h = 0;
  std::vector<float> v;
  float at(int x, int y) const { return v[static_cast<size_t>(y) * w + x]; }
};

// One value per 2x2 Bayer quad: black-subtracted sum, so every quad mixes all colours.
Plane quads(const RawImage &raw)
{
  Plane p;
  p.w = raw.width / 2;
  p.h = raw.height / 2;
  p.v.resize(static_cast<size_t>(p.w) * p.h);
  const float black = raw.black[0] + raw.black[1] + raw.black[2] + raw.black[3];
  for(int y = 0; y < p.h; y++)
  {
    const uint16_t *r0 = &raw.cfa[static_cast<size_t>(2 * y) * raw.width];
    const uint16_t *r1 = r0 + raw.width;
    for(int x = 0; x < p.w; x++)
      p.v[static_cast<size_t>(y) * p.w + x] = r0[2 * x] + r0[2 * x + 1] + r1[2 * x] + r1[2 * x + 1] - black;
  }
  return p;
}

// 2x2 box reduction; `max_mode` keeps the largest value instead (for masks).
Plane half(const Plane &in, bool max_mode = false)
{
  Plane p;
  p.w = in.w / 2;
  p.h = in.h / 2;
  p.v.resize(static_cast<size_t>(p.w) * p.h);
  for(int y = 0; y < p.h; y++)
    for(int x = 0; x < p.w; x++)
    {
      const float a = in.at(2 * x, 2 * y), b = in.at(2 * x + 1, 2 * y);
      const float c = in.at(2 * x, 2 * y + 1), d = in.at(2 * x + 1, 2 * y + 1);
      p.v[static_cast<size_t>(y) * p.w + x] = max_mode ? std::max(std::max(a, b), std::max(c, d)) : 0.25f * (a + b + c + d);
    }
  return p;
}

// 1 where any of a quad's four samples is within 10% of white, else 0.
Plane clip_mask(const RawImage &raw)
{
  Plane p;
  p.w = raw.width / 2;
  p.h = raw.height / 2;
  p.v.resize(static_cast<size_t>(p.w) * p.h);
  float limit[4];
  for(int i = 0; i < 4; i++) limit[i] = raw.black[i] + 0.9f * (raw.white - raw.black[i]);
  for(int y = 0; y < p.h; y++)
  {
    const uint16_t *r0 = &raw.cfa[static_cast<size_t>(2 * y) * raw.width];
    const uint16_t *r1 = r0 + raw.width;
    for(int x = 0; x < p.w; x++)
      p.v[static_cast<size_t>(y) * p.w + x] = r0[2 * x] >= limit[0] || r0[2 * x + 1] >= limit[1] ||
                                                      r1[2 * x] >= limit[2] || r1[2 * x + 1] >= limit[3];
  }
  return p;
}

// Black-subtracted range of a quad sum (four samples at white).
float quad_range(const RawImage &raw)
{
  return 4.0f * raw.white - (raw.black[0] + raw.black[1] + raw.black[2] + raw.black[3]);
}

// Lighting-independent structure for matching a frame lit differently (a flash): log of
// the quads minus its local mean over (2 r + 1)^2 quads. Light that varies smoothly
// (falloff, an overall gain) drops out; edges and texture, which the same surfaces show
// under any light, remain.
Plane structure(const Plane &in, float floor, int r)
{
  Plane l = in;
  for(float &v : l.v) v = std::log(std::max(v, floor));
  // box mean through a summed-area table
  std::vector<double> sat(static_cast<size_t>(l.w + 1) * (l.h + 1), 0.0);
  for(int y = 0; y < l.h; y++)
  {
    double row = 0;
    for(int x = 0; x < l.w; x++)
    {
      row += l.at(x, y);
      sat[static_cast<size_t>(y + 1) * (l.w + 1) + x + 1] = sat[static_cast<size_t>(y) * (l.w + 1) + x + 1] + row;
    }
  }
  Plane out = l;
  for(int y = 0; y < l.h; y++)
    for(int x = 0; x < l.w; x++)
    {
      const int x0 = std::max(0, x - r), x1 = std::min(l.w, x + r + 1), y0 = std::max(0, y - r), y1 = std::min(l.h, y + r + 1);
      const double sum = sat[static_cast<size_t>(y1) * (l.w + 1) + x1] - sat[static_cast<size_t>(y0) * (l.w + 1) + x1] -
                         sat[static_cast<size_t>(y1) * (l.w + 1) + x0] + sat[static_cast<size_t>(y0) * (l.w + 1) + x0];
      out.v[static_cast<size_t>(y) * l.w + x] = static_cast<float>(l.at(x, y) - sum / ((x1 - x0) * (y1 - y0)));
    }
  return out;
}

Plane scaled(const Plane &in, float gain, float ceiling)
{
  Plane p = in;
  for(float &v : p.v) v = std::min(v * gain, ceiling);
  return p;
}

// Exposure of `img` relative to `ref` (img ~ ratio * ref) from samples well exposed in
// both, img shifted by (dx, dy): the median ratio, robust to motion and misalignment.
// A dark scene with few such samples falls back to the ratio of sums.
double exposure_ratio(const Plane &ref, const Plane &ref_clip, const Plane &img, const Plane &img_clip, int dx,
                      int dy, float floor)
{
  std::vector<float> ratios;
  double sum_ref = 0, sum_img = 0;
  for(int y = std::max(0, -dy); y < std::min(ref.h, img.h - dy); y++)
    for(int x = std::max(0, -dx); x < std::min(ref.w, img.w - dx); x++)
    {
      if(ref_clip.at(x, y) > 0 || img_clip.at(x + dx, y + dy) > 0) continue;
      const float a = ref.at(x, y), b = img.at(x + dx, y + dy);
      if(a > 0 && b > 0)
      {
        sum_ref += a;
        sum_img += b;
      }
      if(a >= floor && b > 0) ratios.push_back(b / a);
    }
  if(ratios.size() < 64) return sum_ref > 0 && sum_img > 0 ? sum_img / sum_ref : 1.0;
  auto mid = ratios.begin() + ratios.size() / 2;
  std::nth_element(ratios.begin(), mid, ratios.end());
  return *mid;
}

// Mean absolute difference of ref[x, y] and img[x + dx, y + dy] over a box, sampled with a stride.
double sad(const Plane &ref, const Plane &img, int x0, int y0, int x1, int y1, int dx, int dy, int stride)
{
  x0 = std::max(x0, -dx);
  y0 = std::max(y0, -dy);
  x1 = std::min(x1, img.w - dx);
  y1 = std::min(y1, img.h - dy);
  if(x1 <= x0 || y1 <= y0) return 1e30;
  double sum = 0;
  long n = 0;
  for(int y = y0; y < y1; y += stride)
    for(int x = x0; x < x1; x += stride)
    {
      sum += std::fabs(ref.at(x, y) - img.at(x + dx, y + dy));
      n++;
    }
  return n ? sum / n : 1e30;
}

// Best (dx, dy) within +-radius of a start, over a box.
void search(const Plane &ref, const Plane &img, int x0, int y0, int x1, int y1, int radius, int stride, int &dx,
            int &dy, double *best_cost = nullptr)
{
  const int sx = dx, sy = dy;
  double best = 1e30;
  for(int oy = -radius; oy <= radius; oy++)
    for(int ox = -radius; ox <= radius; ox++)
    {
      const double c = sad(ref, img, x0, y0, x1, y1, sx + ox, sy + oy, stride);
      if(c < best)
      {
        best = c;
        dx = sx + ox;
        dy = sy + oy;
      }
    }
  if(best_cost) *best_cost = best;
}

} // namespace

bool align_burst(const std::vector<const RawImage *> &frames, BurstAlignment &out, std::string &error,
                 const std::vector<char> *same_exposure)
{
  if(frames.empty())
  {
    error = "empty burst";
    return false;
  }
  const RawImage &first = *frames[0];
  for(const RawImage *f : frames)
    if(f->width != first.width || f->height != first.height)
    {
      error = "burst frames differ in size";
      return false;
    }
  out.tiles_x = (first.width + out.tile - 1) / out.tile;
  out.tiles_y = (first.height + out.tile - 1) / out.tile;
  const int tiles = out.tiles_x * out.tiles_y;
  out.offsets.assign(frames.size() * tiles * 2, 0);
  out.gain.assign(frames.size(), 1.0f);

  // Pyramid: quads (L0), then 1/2, 1/4, 1/8.
  auto pyramid = [](Plane level0) {
    std::vector<Plane> p{std::move(level0)};
    for(int i = 0; i < 3; i++) p.push_back(half(p.back()));
    return p;
  };
  const Plane ref_quads = quads(first);
  const std::vector<Plane> ref_plain = pyramid(ref_quads);
  const Plane ref_clip = half(half(clip_mask(first), true), true); // at pyramid level 2
  const float ref_range = quad_range(first);

  for(size_t f = 1; f < frames.size(); f++)
  {
    const RawImage &raw = *frames[f];
    const Plane img_quads = quads(raw);
    const Plane img_clip = half(half(clip_mask(raw), true), true);
    // Exposure relative to the reference, first unaligned on the 1/4 level (16 quads
    // per sample, so small shifts barely matter). Within 0.15 stop counts as equal.
    const char kind = same_exposure && f < same_exposure->size() ? (*same_exposure)[f] : 0;
    const bool fixed = kind != 0;
    const bool relit = kind == 2;
    double ratio = fixed ? 1.0 : exposure_ratio(ref_plain[2], ref_clip, half(half(img_quads)), img_clip, 0, 0, 0.03f * ref_range);
    const bool bracketed = !fixed && std::fabs(std::log2(ratio)) >= 0.15;
    float gain = bracketed ? static_cast<float>(1.0 / ratio) : 1.0f;
    // Compare at the reference's exposure; above the lower of the two clip levels both are flat.
    std::vector<Plane> ref_cmp, img;
    if(relit)
    { // lit differently: match structure, not values
      const float floor = 0.01f * std::min(ref_range, quad_range(raw));
      ref_cmp = pyramid(structure(ref_quads, floor, 24));
      img = pyramid(structure(img_quads, floor, 24));
    }
    else if(bracketed)
    {
      const float ceiling = 0.9f * std::min(ref_range, quad_range(raw) * gain);
      ref_cmp = pyramid(scaled(ref_quads, 1.0f, ceiling));
      img = pyramid(scaled(img_quads, gain, ceiling));
    }
    else img = pyramid(img_quads);
    const std::vector<Plane> &ref = bracketed || relit ? ref_cmp : ref_plain;

    // Global translation, coarse to fine, on the central 80%.
    int dx = 0, dy = 0;
    for(int level = 3; level >= 0; level--)
    {
      const Plane &r = ref[level];
      if(level < 3) { dx *= 2; dy *= 2; }
      const int stride = level == 0 ? 4 : level == 1 ? 2 : 1;
      search(r, img[level], r.w / 10, r.h / 10, r.w * 9 / 10, r.h * 9 / 10, level == 3 ? 8 : 2, stride, dx, dy);
    }
    if(bracketed)
    { // remeasure per quad with the frames registered (additive noise keeps the median unbiased)
      ratio = exposure_ratio(ref_quads, clip_mask(first), img_quads, clip_mask(raw), dx, dy, 0.05f * ref_range);
      gain = static_cast<float>(1.0 / ratio);
    }
    out.gain[f] = gain;

    // Per tile: refine around the global estimate at 1/2 then quad resolution.
    // Flat tiles that do not clearly improve keep the global offset.
    const int quad_tile = out.tile / 2;
    for(int ty = 0; ty < out.tiles_y; ty++)
      for(int tx = 0; tx < out.tiles_x; tx++)
      {
        int ldx = dx / 2, ldy = dy / 2;
        const int hx0 = tx * quad_tile / 2, hy0 = ty * quad_tile / 2;
        const int hx1 = std::min(hx0 + quad_tile / 2, ref[1].w), hy1 = std::min(hy0 + quad_tile / 2, ref[1].h);
        search(ref[1], img[1], hx0, hy0, hx1, hy1, 4, 2, ldx, ldy);
        int qdx = ldx * 2, qdy = ldy * 2;
        const int qx0 = tx * quad_tile, qy0 = ty * quad_tile;
        const int qx1 = std::min(qx0 + quad_tile, ref[0].w), qy1 = std::min(qy0 + quad_tile, ref[0].h);
        double tile_cost = 0;
        search(ref[0], img[0], qx0, qy0, qx1, qy1, 1, 2, qdx, qdy, &tile_cost);
        const double global_cost = sad(ref[0], img[0], qx0, qy0, qx1, qy1, dx, dy, 2);
        if(!(tile_cost < 0.97 * global_cost)) { qdx = dx; qdy = dy; }
        int32_t *o = &out.offsets[(f * tiles + ty * out.tiles_x + tx) * 2];
        o[0] = 2 * qdx; // quads -> raw pixels: even, so the CFA phase is kept
        o[1] = 2 * qdy;
      }
  }
  return true;
}

float flash_level(const RawImage &ambient, const RawImage &flash, const BurstAlignment &alignment, int index)
{
  const Plane a = quads(ambient), f = quads(flash);
  const Plane a_clip = clip_mask(ambient), f_clip = clip_mask(flash);
  const float range = quad_range(ambient);
  if(range <= 0 || alignment.tiles_x <= 0) return 0.0f;
  const int tiles = alignment.tiles_x * alignment.tiles_y;
  const int quad_tile = alignment.tile / 2;
  std::vector<float> lit;
  for(int y = 0; y < a.h; y += 2)
    for(int x = 0; x < a.w; x += 2)
    {
      const int tx = std::min(x / quad_tile, alignment.tiles_x - 1), ty = std::min(y / quad_tile, alignment.tiles_y - 1);
      const int32_t *o = &alignment.offsets[(static_cast<size_t>(index) * tiles + ty * alignment.tiles_x + tx) * 2];
      const int fx = x + o[0] / 2, fy = y + o[1] / 2;
      if(fx < 0 || fy < 0 || fx >= f.w || fy >= f.h || a_clip.at(x, y) > 0) continue;
      // A clipped flash quad counts as fully lit: it is the brightest the flash got.
      const float added = f_clip.at(fx, fy) > 0 ? 1.0f : (f.at(fx, fy) - a.at(x, y)) / range;
      if(added > 0.01f) lit.push_back(added);
    }
  if(lit.size() < 256) return 0.0f;
  auto at = lit.begin() + static_cast<long>(lit.size() * 0.98);
  std::nth_element(lit.begin(), at, lit.end());
  return *at;
}

float flash_ambient_scale(const RawImage &flash, const RawImage &ambient, const BurstAlignment &alignment, int index)
{
  // Ratios of 4x4-quad means, both well exposed, the ambient frame registered to the flash.
  const Plane f = half(half(quads(flash))), a = half(half(quads(ambient)));
  const Plane f_clip = half(half(clip_mask(flash), true), true), a_clip = half(half(clip_mask(ambient), true), true);
  const float f_range = quad_range(flash), a_range = quad_range(ambient);
  if(f_range <= 0 || a_range <= 0 || alignment.tiles_x <= 0) return 1.0f;
  const int tiles = alignment.tiles_x * alignment.tiles_y;
  const int32_t *o = &alignment.offsets[static_cast<size_t>(index) * tiles * 2]; // global: all tiles alike
  const int dx = o[0] / 8, dy = o[1] / 8; // raw pixels -> quarter-quad level
  std::vector<float> ratios;
  for(int y = 0; y < f.h; y++)
    for(int x = 0; x < f.w; x++)
    {
      const int ax = x + dx, ay = y + dy;
      if(ax < 0 || ay < 0 || ax >= a.w || ay >= a.h || f_clip.at(x, y) > 0 || a_clip.at(ax, ay) > 0) continue;
      const float fv = f.at(x, y) / f_range, av = a.at(ax, ay) / a_range;
      if(av < 0.02f || fv < 0.005f) continue;
      ratios.push_back(fv / av);
    }
  if(ratios.size() < 64) return 1.0f;
  auto at = ratios.begin() + static_cast<long>(ratios.size() * 0.15);
  std::nth_element(ratios.begin(), at, ratios.end());
  // In range units of each frame: back to the flash frame's own normalisation.
  return *at;
}

} // namespace film
