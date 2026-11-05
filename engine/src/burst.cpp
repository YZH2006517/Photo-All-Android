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

Plane half(const Plane &in)
{
  Plane p;
  p.w = in.w / 2;
  p.h = in.h / 2;
  p.v.resize(static_cast<size_t>(p.w) * p.h);
  for(int y = 0; y < p.h; y++)
    for(int x = 0; x < p.w; x++)
      p.v[static_cast<size_t>(y) * p.w + x] =
          0.25f * (in.at(2 * x, 2 * y) + in.at(2 * x + 1, 2 * y) + in.at(2 * x, 2 * y + 1) + in.at(2 * x + 1, 2 * y + 1));
  return p;
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

bool align_burst(const std::vector<const RawImage *> &frames, BurstAlignment &out, std::string &error)
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

  // Pyramid of the reference: quads (L0), then 1/2, 1/4, 1/8.
  auto pyramid = [](const RawImage &raw) {
    std::vector<Plane> p{quads(raw)};
    for(int i = 0; i < 3; i++) p.push_back(half(p.back()));
    return p;
  };
  const std::vector<Plane> ref = pyramid(first);

  for(size_t f = 1; f < frames.size(); f++)
  {
    const std::vector<Plane> img = pyramid(*frames[f]);
    // Global translation, coarse to fine, on the central 80%.
    int dx = 0, dy = 0;
    for(int level = 3; level >= 0; level--)
    {
      const Plane &r = ref[level];
      if(level < 3) { dx *= 2; dy *= 2; }
      const int stride = level == 0 ? 4 : level == 1 ? 2 : 1;
      search(r, img[level], r.w / 10, r.h / 10, r.w * 9 / 10, r.h * 9 / 10, level == 3 ? 8 : 2, stride, dx, dy);
    }

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

} // namespace film
