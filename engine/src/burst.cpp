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
                 const std::vector<char> *same_exposure, bool per_tile)
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
    if(!per_tile)
    {
      for(int t = 0; t < out.tiles_x * out.tiles_y; t++)
      {
        out.offsets[(f * out.tiles_x * out.tiles_y + t) * 2] = 2 * dx;
        out.offsets[(f * out.tiles_x * out.tiles_y + t) * 2 + 1] = 2 * dy;
      }
      continue;
    }
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

namespace {

// Means of 16x16-pixel blocks of black-subtracted samples (all four colours), and
// whether any sample in a block is within 10% of white. A quarter of the pixels (every
// other 2x2 quad each way) is plenty for a block's mean.
void blocks(const RawImage &raw, Plane &mean, Plane &clip)
{
  mean.w = clip.w = raw.width / 16;
  mean.h = clip.h = raw.height / 16;
  mean.v.assign(static_cast<size_t>(mean.w) * mean.h, 0.0f);
  clip.v.assign(mean.v.size(), 0.0f);
  float limit[4];
  for(int i = 0; i < 4; i++) limit[i] = raw.black[i] + 0.9f * (raw.white - raw.black[i]);
  for(int y = 0; y < mean.h * 16; y++)
  {
    if(y & 2) continue; // rows 4k and 4k + 1: every other quad row
    const uint16_t *row = &raw.cfa[static_cast<size_t>(y) * raw.width];
    const int k = 2 * (y & 1);
    for(int bx = 0; bx < mean.w; bx++)
    {
      float sum = 0.0f;
      bool clipped = false;
      for(int x = bx * 16; x < bx * 16 + 16; x += 4) // every other quad column
        for(int d = 0; d < 2; d++)
        {
          const int pos = k + d;
          const uint16_t v = row[x + d];
          sum += v - raw.black[pos];
          clipped = clipped || v >= limit[pos];
        }
      const size_t at = static_cast<size_t>(y / 16) * mean.w + bx;
      mean.v[at] += sum;
      if(clipped) clip.v[at] = 1.0f;
    }
  }
  const float range = 0.25f * (4.0f * raw.white - raw.black[0] - raw.black[1] - raw.black[2] - raw.black[3]);
  for(float &v : mean.v) v /= 64.0f * range; // 0..1 of white (64 samples a block)
}

} // namespace

struct GainReference::Blocks
{
  Plane mean, clip;
};

GainReference::GainReference(const RawImage &ref) : ref_(ref), blocks_(std::make_unique<Blocks>())
{
  blocks(ref, blocks_->mean, blocks_->clip);
}

GainReference::~GainReference() = default;

float measure_gain(const RawImage &ref, const RawImage &img, float hint)
{
  return GainReference(ref).gain(img, hint);
}

float GainReference::gain(const RawImage &img, float hint) const
{
  const RawImage &ref = ref_;
  if(hint <= 0.0f || ref.width != img.width || ref.height != img.height) return 1.0f;
  const Plane &r = blocks_->mean, &rc = blocks_->clip;
  Plane m, mc;
  blocks(img, m, mc);
  // Register the blocks: both at the reference's exposure, flat above the lower clip.
  const float ceiling = 0.85f * std::min(1.0f, 1.0f / hint);
  Plane r_cmp = scaled(r, 1.0f, ceiling), m_cmp = scaled(m, 1.0f / hint, ceiling);
  int dx = 0, dy = 0;
  search(r_cmp, m_cmp, r.w / 10, r.h / 10, r.w * 9 / 10, r.h * 9 / 10, 8, 1, dx, dy);
  // The median ratio of blocks well exposed in both (additive noise keeps it unbiased).
  std::vector<float> ratios;
  for(int y = std::max(0, -dy); y < std::min(r.h, m.h - dy); y++)
    for(int x = std::max(0, -dx); x < std::min(r.w, m.w - dx); x++)
    {
      if(rc.at(x, y) > 0 || mc.at(x + dx, y + dy) > 0) continue;
      const float a = r.at(x, y), b = m.at(x + dx, y + dy);
      if(a >= 0.02f && b > 0) ratios.push_back(b / a);
    }
  if(ratios.size() < 32) return 1.0f / hint;
  auto mid = ratios.begin() + ratios.size() / 2;
  std::nth_element(ratios.begin(), mid, ratios.end());
  return 1.0f / *mid;
}

float Warp::degrees() const { return std::atan2(b, a) * 57.29578f; }
float Warp::scale() const { return std::sqrt(a * a + b * b); }

namespace {

// One tile's registration: its centre in the reference and where it lies in the other
// frame (raw pixels), with how distinct the match was.
struct TileMatch
{
  float px, py, qx, qy, confidence;
};

// Sub-sample offset of a parabola through three costs, -0.5..0.5.
float parabola(double minus, double centre, double plus)
{
  const double d = minus - 2.0 * centre + plus;
  if(d <= 1e-12) return 0.0f;
  return static_cast<float>(std::clamp(0.5 * (minus - plus) / d, -0.5, 0.5));
}

void measure_tiles(const Plane &ref, const Plane &img, const Plane &ref_half, const Plane &img_half,
                   const BurstAlignment &al, const Warp &predict, int width, int height, std::vector<TileMatch> &out)
{
  out.clear();
  const int qt = al.tile / 2; // tile size in quads
  for(int ty = 0; ty < al.tiles_y; ty++)
    for(int tx = 0; tx < al.tiles_x; tx++)
    {
      const int x0 = tx * qt, y0 = ty * qt;
      const int x1 = std::min(x0 + qt, ref.w), y1 = std::min(y0 + qt, ref.h);
      if(x1 - x0 < qt / 2 || y1 - y0 < qt / 2) continue; // a sliver at the edge
      const float px = static_cast<float>(x0 + x1), py = static_cast<float>(y0 + y1); // centre, raw pixels
      float qx, qy;
      predict.map(px, py, width, height, qx, qy);
      // Around the prediction: +-4 at half resolution (+-16 raw), then +-1 per quad.
      int hx = static_cast<int>(std::lround((qx - px) / 4.0f)), hy = static_cast<int>(std::lround((qy - py) / 4.0f));
      search(ref_half, img_half, x0 / 2, y0 / 2, x1 / 2, y1 / 2, 4, 1, hx, hy);
      int dx = 2 * hx, dy = 2 * hy;
      double c0 = 0;
      search(ref, img, x0, y0, x1, y1, 1, 1, dx, dy, &c0);
      const double cxm = sad(ref, img, x0, y0, x1, y1, dx - 1, dy, 1), cxp = sad(ref, img, x0, y0, x1, y1, dx + 1, dy, 1);
      const double cym = sad(ref, img, x0, y0, x1, y1, dx, dy - 1, 1), cyp = sad(ref, img, x0, y0, x1, y1, dx, dy + 1, 1);
      if(c0 <= 0 || c0 > 1e29 || cxm > 1e29 || cxp > 1e29 || cym > 1e29 || cyp > 1e29) continue;
      // A flat or repetitive tile has no clear minimum: it cannot vote.
      const double confidence = std::min(cxm + cxp - 2.0 * c0, cym + cyp - 2.0 * c0) / c0;
      if(confidence < 0.02) continue;
      const float sx = static_cast<float>(dx) + parabola(cxm, c0, cxp);
      const float sy = static_cast<float>(dy) + parabola(cym, c0, cyp);
      out.push_back({px, py, px + 2.0f * sx, py + 2.0f * sy, static_cast<float>(std::min(confidence, 1.0))});
    }
}

// Similarity through the matches, robustly: Tukey weights with a shrinking scale, so a
// moving subject or a mismatched tile drops out while a roll, which moves the corners
// most, is found from the centre outwards.
Warp fit_similarity(const std::vector<TileMatch> &m, const Warp &start, int width, int height)
{
  Warp w = start;
  const double cx = 0.5 * width, cy = 0.5 * height;
  const double scales[] = {24.0, 12.0, 6.0, 4.0, 3.0, 3.0};
  for(double c : scales)
  {
    double n[4][5] = {};
    int count = 0;
    for(const TileMatch &t : m)
    {
      float qx, qy;
      w.map(t.px, t.py, width, height, qx, qy);
      const double r = std::hypot(t.qx - qx, t.qy - qy);
      if(r >= c) continue;
      const double u = 1.0 - (r / c) * (r / c);
      const double wt = t.confidence * u * u;
      const double X = t.px - cx, Y = t.py - cy;
      // unknowns [A B tx ty] (A = a - 1): dx = A X - B Y + tx, dy = B X + A Y + ty
      const double r1[4] = {X, -Y, 1, 0}, r2[4] = {Y, X, 0, 1};
      const double d1 = t.qx - t.px, d2 = t.qy - t.py;
      for(int i = 0; i < 4; i++)
      {
        for(int j = 0; j < 4; j++) n[i][j] += wt * (r1[i] * r1[j] + r2[i] * r2[j]);
        n[i][4] += wt * (r1[i] * d1 + r2[i] * d2);
      }
      count++;
    }
    if(count < 4) break;
    // Gauss-Jordan elimination with partial pivoting.
    bool ok = true;
    for(int i = 0; i < 4 && ok; i++)
    {
      int pivot = i;
      for(int k = i + 1; k < 4; k++)
        if(std::fabs(n[k][i]) > std::fabs(n[pivot][i])) pivot = k;
      if(std::fabs(n[pivot][i]) < 1e-12)
      {
        ok = false;
        break;
      }
      for(int j = 0; j < 5; j++) std::swap(n[i][j], n[pivot][j]);
      for(int k = 0; k < 4; k++)
        if(k != i)
        {
          const double f = n[k][i] / n[i][i];
          for(int j = i; j < 5; j++) n[k][j] -= f * n[i][j];
        }
    }
    if(!ok) break;
    w.a = static_cast<float>(1.0 + n[0][4] / n[0][0]);
    w.b = static_cast<float>(n[1][4] / n[1][1]);
    w.tx = static_cast<float>(n[2][4] / n[2][2]);
    w.ty = static_cast<float>(n[3][4] / n[3][3]);
  }
  // Agreement with the final fit.
  double sum = 0;
  w.used = 0;
  for(const TileMatch &t : m)
  {
    float qx, qy;
    w.map(t.px, t.py, width, height, qx, qy);
    const double r = std::hypot(t.qx - qx, t.qy - qy);
    if(r < 3.0)
    {
      sum += r * r;
      w.used++;
    }
  }
  w.tiles = static_cast<int>(m.size());
  w.residual = w.used ? static_cast<float>(std::sqrt(sum / w.used)) : 0.0f;
  return w;
}

} // namespace

Warp fit_warp(const RawImage &ref, const RawImage &img, const BurstAlignment &al, int index)
{
  Warp median;
  const int tiles = al.tiles_x * al.tiles_y;
  if(tiles <= 0 || al.offsets.size() < static_cast<size_t>(index + 1) * tiles * 2) return median;
  {
    const int32_t *o = &al.offsets[static_cast<size_t>(index) * tiles * 2];
    std::vector<int32_t> xs, ys;
    for(int t = 0; t < tiles; t++)
    {
      xs.push_back(o[2 * t]);
      ys.push_back(o[2 * t + 1]);
    }
    std::nth_element(xs.begin(), xs.begin() + tiles / 2, xs.end());
    std::nth_element(ys.begin(), ys.begin() + tiles / 2, ys.end());
    median.tx = static_cast<float>(xs[tiles / 2] & ~1);
    median.ty = static_cast<float>(ys[tiles / 2] & ~1);
  }
  const float floor = 0.01f * std::min(quad_range(ref), quad_range(img));
  const Plane rs = structure(quads(ref), floor, 24), is = structure(quads(img), floor, 24);
  const Plane rh = half(rs), ih = half(is);
  std::vector<TileMatch> m;
  measure_tiles(rs, is, rh, ih, al, median, ref.width, ref.height, m);
  if(m.size() < 8)
  {
    median.tiles = static_cast<int>(m.size());
    return median;
  }
  Warp w = fit_similarity(m, median, ref.width, ref.height);
  // Search again around the fitted prediction (corner tiles of a roll lie far from the
  // median shift), then refit.
  measure_tiles(rs, is, rh, ih, al, w, ref.width, ref.height, m);
  if(m.size() >= 8) w = fit_similarity(m, w, ref.width, ref.height);
  const bool plausible = std::fabs(w.a - 1.0f) < 0.02f && std::fabs(w.b) < 0.035f; // < 2% zoom, < 2 degrees roll
  if(!plausible || w.used < std::max(8, w.tiles * 3 / 10))
  {
    median.tiles = w.tiles;
    median.used = w.used;
    return median;
  }
  w.fitted = true;
  return w;
}

void warp_margins(const Warp &w, int width, int height, int pad, int margins[4])
{
  const float cx = 0.5f * width, cy = 0.5f * height;
  const float lo_x = static_cast<float>(pad), hi_x = static_cast<float>(width - 1 - pad);
  const float lo_y = static_cast<float>(pad), hi_y = static_cast<float>(height - 1 - pad);
  // q is linear in p: the binding points are at the corners.
  float left = 0, right = static_cast<float>(width - 1), top = 0, bottom = static_cast<float>(height - 1);
  for(float py : {0.0f, static_cast<float>(height - 1)})
  {
    left = std::max(left, cx + (lo_x - cx + w.b * (py - cy) - w.tx) / w.a);
    right = std::min(right, cx + (hi_x - cx + w.b * (py - cy) - w.tx) / w.a);
  }
  for(float px : {0.0f, static_cast<float>(width - 1)})
  {
    top = std::max(top, cy + (lo_y - cy - w.b * (px - cx) - w.ty) / w.a);
    bottom = std::min(bottom, cy + (hi_y - cy - w.b * (px - cx) - w.ty) / w.a);
  }
  margins[0] = static_cast<int>(std::ceil(left));
  margins[1] = static_cast<int>(std::ceil(top));
  margins[2] = std::max(0, width - 1 - static_cast<int>(std::floor(right)));
  margins[3] = std::max(0, height - 1 - static_cast<int>(std::floor(bottom)));
}

float flash_level(const RawImage &ambient, const RawImage &flash, const Warp &warp)
{
  const Plane a = quads(ambient), f = quads(flash);
  const Plane a_clip = clip_mask(ambient), f_clip = clip_mask(flash);
  const float range = quad_range(ambient);
  if(range <= 0) return 0.0f;
  std::vector<float> lit;
  for(int y = 0; y < a.h; y += 2)
    for(int x = 0; x < a.w; x += 2)
    {
      float qx, qy;
      warp.map(2.0f * x + 0.5f, 2.0f * y + 0.5f, ambient.width, ambient.height, qx, qy);
      const int fx = static_cast<int>(std::lround((qx - 0.5f) / 2.0f)), fy = static_cast<int>(std::lround((qy - 0.5f) / 2.0f));
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

float flash_ambient_scale(const RawImage &flash, const RawImage &ambient, const Warp &warp)
{
  // Ratios of 4x4-quad means, both well exposed, the ambient frame registered to the flash.
  const Plane f = half(half(quads(flash))), a = half(half(quads(ambient)));
  const Plane f_clip = half(half(clip_mask(flash), true), true), a_clip = half(half(clip_mask(ambient), true), true);
  const float f_range = quad_range(flash), a_range = quad_range(ambient);
  if(f_range <= 0 || a_range <= 0) return 1.0f;
  std::vector<float> ratios;
  for(int y = 0; y < f.h; y++)
    for(int x = 0; x < f.w; x++)
    {
      float qx, qy; // one sample per 8x8 raw pixels, centred at 8 x + 3.5
      warp.map(8.0f * x + 3.5f, 8.0f * y + 3.5f, flash.width, flash.height, qx, qy);
      const int ax = static_cast<int>(std::lround((qx - 3.5f) / 8.0f)), ay = static_cast<int>(std::lround((qy - 3.5f) / 8.0f));
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
