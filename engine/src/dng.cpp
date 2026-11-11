#include "dng.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <map>

namespace film {
namespace {

using Mat3 = std::array<double, 9>;

Mat3 mul(const Mat3 &a, const Mat3 &b)
{
  Mat3 c{};
  for(int i = 0; i < 3; i++)
    for(int j = 0; j < 3; j++)
      for(int k = 0; k < 3; k++) c[i * 3 + j] += a[i * 3 + k] * b[k * 3 + j];
  return c;
}

bool invert(const Mat3 &m, Mat3 &out)
{
  const double det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) +
                     m[2] * (m[3] * m[7] - m[4] * m[6]);
  if(std::fabs(det) < 1e-12) return false;
  out = {(m[4] * m[8] - m[5] * m[7]) / det, (m[2] * m[7] - m[1] * m[8]) / det, (m[1] * m[5] - m[2] * m[4]) / det,
         (m[5] * m[6] - m[3] * m[8]) / det, (m[0] * m[8] - m[2] * m[6]) / det, (m[2] * m[3] - m[0] * m[5]) / det,
         (m[3] * m[7] - m[4] * m[6]) / det, (m[1] * m[6] - m[0] * m[7]) / det, (m[0] * m[4] - m[1] * m[3]) / det};
  return true;
}

std::array<double, 3> apply(const Mat3 &m, const std::array<double, 3> &v)
{
  return {m[0] * v[0] + m[1] * v[1] + m[2] * v[2], m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
          m[6] * v[0] + m[7] * v[1] + m[8] * v[2]};
}

// Bradford chromatic adaptation between two XYZ whites.
Mat3 bradford(const std::array<double, 3> &from, const std::array<double, 3> &to)
{
  const Mat3 b = {0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296};
  Mat3 bi;
  invert(b, bi);
  const auto s = apply(b, from), d = apply(b, to);
  const Mat3 scale = {d[0] / s[0], 0, 0, 0, d[1] / s[1], 0, 0, 0, d[2] / s[2]};
  return mul(bi, mul(scale, b));
}

constexpr std::array<double, 3> kD50 = {0.96422, 1.0, 0.82521};
constexpr std::array<double, 3> kD65 = {0.95047, 1.0, 1.08883};
constexpr Mat3 kXyzToRec2020 = {1.7166512, -0.3556708, -0.2533663, -0.6666844, 1.6164812,
                                0.0157685, 0.0176399,  -0.0427706, 0.9421031};

struct Entry
{
  uint16_t type = 0;
  uint32_t count = 0;
  uint32_t offset = 0; // value position in the file
};
using Ifd = std::map<uint16_t, Entry>;

class Tiff
{
public:
  Tiff(const uint8_t *data, size_t size) : data_(data), size_(size) {}

  bool header(uint32_t &first)
  {
    if(size_ < 8) return false;
    if(data_[0] == 'I' && data_[1] == 'I') big_ = false;
    else if(data_[0] == 'M' && data_[1] == 'M') big_ = true;
    else return false;
    if(u16(2) != 42) return false;
    first = u32(4);
    return true;
  }

  bool ifd(uint32_t at, Ifd &out, uint32_t *next = nullptr)
  {
    if(at + 2 > size_) return false;
    const uint16_t n = u16(at);
    if(at + 2 + n * 12ull + 4 > size_) return false;
    for(uint16_t i = 0; i < n; i++)
    {
      const uint32_t e = at + 2 + i * 12;
      Entry entry;
      entry.type = u16(e + 2);
      entry.count = u32(e + 4);
      const uint64_t bytes = static_cast<uint64_t>(type_size(entry.type)) * entry.count;
      entry.offset = bytes <= 4 ? e + 8 : u32(e + 8);
      if(entry.offset + bytes > size_) continue; // ignore broken entries
      out[u16(e)] = entry;
    }
    if(next) *next = u32(at + 2 + n * 12);
    return true;
  }

  std::vector<double> values(const Entry &e) const
  {
    std::vector<double> v;
    const uint32_t step = type_size(e.type);
    for(uint32_t i = 0; i < e.count; i++)
    {
      const uint32_t at = e.offset + i * step;
      switch(e.type)
      {
      case 1: case 7: v.push_back(data_[at]); break;
      case 3: v.push_back(u16(at)); break;
      case 8: v.push_back(static_cast<int16_t>(u16(at))); break;
      case 4: case 13: v.push_back(u32(at)); break;
      case 9: v.push_back(static_cast<int32_t>(u32(at))); break;
      case 5: v.push_back(u32(at + 4) ? static_cast<double>(u32(at)) / u32(at + 4) : 0.0); break;
      case 10:
        v.push_back(u32(at + 4) ? static_cast<double>(static_cast<int32_t>(u32(at))) /
                                      static_cast<int32_t>(u32(at + 4))
                                : 0.0);
        break;
      case 11: { float f; uint32_t b = u32(at); std::memcpy(&f, &b, 4); v.push_back(f); break; }
      case 12: { double d; uint64_t b = (static_cast<uint64_t>(u32(big_ ? at : at + 4)) << 32) | u32(big_ ? at + 4 : at); std::memcpy(&d, &b, 8); v.push_back(d); break; }
      default: break;
      }
    }
    return v;
  }

  uint16_t u16(size_t at) const
  {
    return big_ ? static_cast<uint16_t>(data_[at] << 8 | data_[at + 1])
                : static_cast<uint16_t>(data_[at] | data_[at + 1] << 8);
  }
  uint32_t u32(size_t at) const
  {
    return big_ ? (uint32_t(data_[at]) << 24 | uint32_t(data_[at + 1]) << 16 | uint32_t(data_[at + 2]) << 8 | data_[at + 3])
                : (uint32_t(data_[at + 3]) << 24 | uint32_t(data_[at + 2]) << 16 | uint32_t(data_[at + 1]) << 8 | data_[at]);
  }
  bool big() const { return big_; }
  const uint8_t *data() const { return data_; }
  size_t size() const { return size_; }

private:
  static uint32_t type_size(uint16_t type)
  {
    switch(type)
    {
    case 3: case 8: return 2;
    case 4: case 9: case 11: case 13: return 4;
    case 5: case 10: case 12: return 8;
    default: return 1;
    }
  }
  const uint8_t *data_;
  size_t size_;
  bool big_ = false;
};

// OpcodeList data is always big-endian.
uint32_t be32(const uint8_t *p) { return uint32_t(p[0]) << 24 | uint32_t(p[1]) << 16 | uint32_t(p[2]) << 8 | p[3]; }
double be64f(const uint8_t *p)
{
  uint64_t b = 0;
  for(int i = 0; i < 8; i++) b = b << 8 | p[i];
  double d;
  std::memcpy(&d, &b, 8);
  return d;
}
float be32f(const uint8_t *p)
{
  uint32_t b = be32(p);
  float f;
  std::memcpy(&f, &b, 4);
  return f;
}

// GainMap opcodes covering one 2x2 position each (Android's form) -> RGBA map.
void read_gain_maps(const uint8_t *p, size_t size, RawImage &out)
{
  if(size < 4) return;
  const uint32_t count = be32(p);
  size_t at = 4;
  int filled = 0;
  for(uint32_t k = 0; k < count && at + 16 <= size; k++)
  {
    const uint32_t id = be32(p + at), bytes = be32(p + at + 12);
    const uint8_t *q = p + at + 16;
    at += 16 + bytes;
    if(at > size) return;
    if(id != 9 || bytes < 76) continue;
    const uint32_t top = be32(q), left = be32(q + 4), row_pitch = be32(q + 24), col_pitch = be32(q + 28);
    const uint32_t points_v = be32(q + 32), points_h = be32(q + 36), map_planes = be32(q + 72);
    if(row_pitch != 2 || col_pitch != 2 || top > 1 || left > 1 || map_planes != 1) continue;
    if(bytes < 76 + points_v * points_h * 4ull) continue;
    if(filled == 0)
    {
      out.map_width = static_cast<int>(points_h);
      out.map_height = static_cast<int>(points_v);
      out.map_spacing[1] = static_cast<float>(be64f(q + 40));
      out.map_spacing[0] = static_cast<float>(be64f(q + 48));
      out.map_origin[1] = static_cast<float>(be64f(q + 56));
      out.map_origin[0] = static_cast<float>(be64f(q + 64));
      out.gain_map.assign(points_h * points_v * 4, 1.0f);
    }
    else if(out.map_width != static_cast<int>(points_h) || out.map_height != static_cast<int>(points_v))
      continue;
    const int position = static_cast<int>(top * 2 + left);
    for(uint32_t i = 0; i < points_h * points_v; i++) out.gain_map[i * 4 + position] = be32f(q + 76 + i * 4);
    filled++;
  }
  if(filled == 0)
  {
    out.map_width = out.map_height = 0;
    out.gain_map.clear();
  }
}

/**
 * Lossless JPEG (ITU T.81 process 14, SOF3), as DNG compression 7 stores raw data: Huffman
 * coded differences from a predictor, one or more interleaved components, optional point
 * transform and restart markers. Decodes [n] bytes at [p] to [out], row-major with the
 * components interleaved ([cols] = width x components, [rows] lines).
 */
bool ljpeg_decode(const uint8_t *p, size_t n, std::vector<uint16_t> &out, int &cols, int &rows, std::string &error)
{
  struct Huff
  {
    bool set = false;
    int maxcode[18], valptr[17], mincode[17];
    uint8_t vals[256];
    int16_t fast[512]; // 9-bit lookahead: (length << 8 | value), -1 when longer
  } huff[4];
  int precision = 0, width = 0, height = 0, ncomp = 0, predictor = 1, pt = 0, restart = 0;
  int comp_table[4] = {0, 0, 0, 0};
  size_t pos = 2, scan = 0;
  if(n < 4 || p[0] != 0xFF || p[1] != 0xD8)
  {
    error = "lossless JPEG: no SOI";
    return false;
  }
  while(pos + 4 <= n)
  {
    if(p[pos] != 0xFF) { pos++; continue; }
    const uint8_t marker = p[pos + 1];
    if(marker == 0xFF) { pos++; continue; }
    const size_t len = (size_t(p[pos + 2]) << 8) | p[pos + 3];
    const uint8_t *s = p + pos + 4;
    if(pos + 2 + len > n) break;
    if(marker == 0xC3)
    {
      precision = s[0];
      height = (s[1] << 8) | s[2];
      width = (s[3] << 8) | s[4];
      ncomp = s[5];
    }
    else if(marker == 0xC4)
    {
      size_t k = 0;
      while(k + 17 <= len - 2)
      {
        const int id = s[k] & 3;
        Huff &h = huff[id];
        int counts[17] = {0}, total = 0;
        for(int i = 1; i <= 16; i++) { counts[i] = s[k + i]; total += counts[i]; }
        if(total > 256 || k + 17 + total > len - 2) break;
        std::memcpy(h.vals, s + k + 17, total);
        int code = 0, at = 0;
        for(int l = 1; l <= 16; l++)
        {
          h.valptr[l] = at;
          h.mincode[l] = code;
          code += counts[l];
          at += counts[l];
          h.maxcode[l] = counts[l] ? code - 1 : -1;
          code <<= 1;
        }
        h.maxcode[17] = 0x7fffffff;
        std::fill(h.fast, h.fast + 512, int16_t(-1));
        for(int l = 1; l <= 9; l++)
          for(int c = h.mincode[l]; c <= h.maxcode[l]; c++)
          {
            const int v = h.vals[h.valptr[l] + c - h.mincode[l]];
            const int shift = 9 - l;
            for(int f = 0; f < (1 << shift); f++) h.fast[(c << shift) | f] = int16_t((l << 8) | v);
          }
        h.set = true;
        k += 17 + total;
      }
    }
    else if(marker == 0xDD)
      restart = (s[0] << 8) | s[1];
    else if(marker == 0xDA)
    {
      const int ns = s[0];
      for(int i = 0; i < ns && i < 4; i++) comp_table[i] = (s[2 + i * 2] >> 4) & 3;
      predictor = s[1 + ns * 2];
      pt = s[3 + ns * 2] & 15;
      scan = pos + 2 + len;
      break;
    }
    pos += 2 + len;
  }
  if(!scan || !width || !height || ncomp < 1 || ncomp > 4 || precision < 2 || precision > 16)
  {
    error = "lossless JPEG: header incomplete";
    return false;
  }
  for(int c = 0; c < ncomp; c++)
    if(!huff[comp_table[c]].set)
    {
      error = "lossless JPEG: Huffman table missing";
      return false;
    }

  // the bit stream: 0xFF00 stands for 0xFF; a marker ends the data (zeros are fed after it)
  size_t at = scan;
  uint32_t acc = 0;
  int have = 0;
  auto fill = [&]() {
    while(have <= 24)
    {
      uint32_t b = 0;
      if(at < n)
      {
        b = p[at];
        if(b == 0xFF)
        {
          const uint8_t next = at + 1 < n ? p[at + 1] : 0;
          if(next == 0x00) at += 2;
          else b = 0; // a marker: stay before it
        }
        else at++;
      }
      acc |= b << (24 - have);
      have += 8;
    }
  };
  auto bits = [&](int k) -> int {
    if(k == 0) return 0;
    fill();
    const int v = int(acc >> (32 - k));
    acc <<= k;
    have -= k;
    return v;
  };
  auto decode = [&](const Huff &h) -> int {
    fill();
    const int16_t f = h.fast[acc >> 23];
    if(f >= 0)
    {
      const int l = f >> 8;
      acc <<= l;
      have -= l;
      return f & 0xff;
    }
    int code = int(acc >> 23); // 9 bits
    acc <<= 9;
    have -= 9;
    int l = 9;
    while(code > h.maxcode[l] && l < 16)
    {
      fill();
      code = (code << 1) | int(acc >> 31);
      acc <<= 1;
      have--;
      l++;
    }
    if(code > h.maxcode[l]) return 0;
    return h.vals[h.valptr[l] + code - h.mincode[l]];
  };
  auto restart_here = [&]() {
    // drop the partial byte, step over RSTn
    acc = 0;
    have = 0;
    while(at + 1 < n && !(p[at] == 0xFF && p[at + 1] >= 0xD0 && p[at + 1] <= 0xD7)) at++;
    if(at + 1 < n) at += 2;
  };

  cols = width * ncomp;
  rows = height;
  out.assign(size_t(cols) * rows, 0);
  const int initial = 1 << (precision - pt - 1);
  int until_restart = restart;
  bool fresh = true; // first line after the start or a restart
  for(int y = 0; y < height; y++)
  {
    uint16_t *row = out.data() + size_t(y) * cols;
    const uint16_t *up = y ? row - cols : nullptr;
    for(int x = 0; x < width; x++)
    {
      if(restart && until_restart == 0)
      {
        restart_here();
        until_restart = restart;
        fresh = true;
      }
      for(int c = 0; c < ncomp; c++)
      {
        const int ssss = decode(huff[comp_table[c]]);
        int diff;
        if(ssss == 16) diff = 32768;
        else
        {
          diff = bits(ssss);
          if(ssss && diff < (1 << (ssss - 1))) diff -= (1 << ssss) - 1;
        }
        const int i = x * ncomp + c;
        int pred;
        if(fresh && x == 0) pred = initial;
        else if(fresh || !up) pred = row[i - ncomp];
        else if(x == 0) pred = up[i];
        else
        {
          const int ra = row[i - ncomp], rb = up[i], rc = up[i - ncomp];
          switch(predictor)
          {
          case 1: pred = ra; break;
          case 2: pred = rb; break;
          case 3: pred = rc; break;
          case 4: pred = ra + rb - rc; break;
          case 5: pred = ra + ((rb - rc) >> 1); break;
          case 6: pred = rb + ((ra - rc) >> 1); break;
          case 7: pred = (ra + rb) >> 1; break;
          default: pred = ra; break;
          }
        }
        row[i] = uint16_t((pred + diff) & 0xffff);
      }
      if(restart) until_restart--;
    }
    fresh = false;
  }
  if(pt)
    for(uint16_t &v : out) v = uint16_t(v << pt);
  return true;
}

} // namespace

bool parse_dng(const uint8_t *data, size_t size, RawImage &out, std::string &error, const float *neutral_override)
{
  Tiff tiff(data, size);
  uint32_t first = 0;
  if(!tiff.header(first))
  {
    error = "not a TIFF/DNG file";
    return false;
  }
  Ifd ifd0;
  if(!tiff.ifd(first, ifd0))
  {
    error = "broken DNG directory";
    return false;
  }
  // The CFA image lives in IFD0 or one of its SubIFDs.
  std::vector<Ifd> candidates{ifd0};
  if(ifd0.count(330))
    for(double offset : tiff.values(ifd0[330]))
    {
      Ifd sub;
      if(tiff.ifd(static_cast<uint32_t>(offset), sub)) candidates.push_back(sub);
    }
  const Ifd *raw = nullptr;
  for(const Ifd &c : candidates)
  {
    auto photometric = c.find(262);
    if(photometric != c.end() && tiff.values(photometric->second)[0] == 32803) raw = &c;
  }
  if(!raw)
  {
    error = "DNG has no Bayer CFA image";
    return false;
  }
  auto tag = [&](uint16_t id) -> std::vector<double> {
    auto it = raw->find(id);
    if(it != raw->end()) return tiff.values(it->second);
    it = ifd0.find(id);
    return it != ifd0.end() ? tiff.values(it->second) : std::vector<double>{};
  };

  const auto width = tag(256), height = tag(257), bits = tag(258), compression = tag(259);
  const auto offsets = tag(273), counts = tag(279);
  const auto tile_w = tag(322), tile_h = tag(323), tile_offsets = tag(324), tile_counts = tag(325);
  const bool tiled = offsets.empty() && !tile_offsets.empty() && tile_offsets.size() == tile_counts.size() &&
                     !tile_w.empty() && !tile_h.empty();
  if(width.empty() || height.empty() || (!tiled && (offsets.empty() || offsets.size() != counts.size())))
  {
    error = "DNG image layout missing";
    return false;
  }
  // Uncompressed samples of 8 to 16 bits. 16 bits are words in the file's byte order;
  // fewer are packed most significant bit first, each row padded to a whole byte (TIFF),
  // as some phones' DNGs come (seen on a custom Xiaomi ROM). Compression 7 is lossless
  // JPEG, in strips or tiles (another custom Xiaomi ROM writes its camera DNGs so).
  const int bps = bits.empty() ? 16 : static_cast<int>(bits[0]);
  const int comp = compression.empty() ? 1 : static_cast<int>(compression[0]);
  if((comp != 1 && comp != 7) || bps < 8 || bps > 16 || (tiled && comp != 7))
  {
    error = "unsupported DNG: compression " + std::to_string(comp) + ", " + std::to_string(bps) + " bits per sample" +
            (tiled ? ", tiled" : "");
    return false;
  }
  out.width = static_cast<int>(width[0]);
  out.height = static_cast<int>(height[0]);
  const size_t samples = static_cast<size_t>(out.width) * out.height;
  out.cfa.resize(samples);
  if(comp == 7)
  {
    // each strip or tile one lossless JPEG; its samples fill the piece row by row
    const auto &offs = tiled ? tile_offsets : offsets;
    const auto &cnts = tiled ? tile_counts : counts;
    const int pw = tiled ? static_cast<int>(tile_w[0]) : out.width;
    const int ph = tiled ? static_cast<int>(tile_h[0]) : 0; // strips: as many rows as decoded
    const int across = tiled ? (out.width + pw - 1) / pw : 1;
    int strip_y = 0;
    std::vector<uint16_t> piece;
    for(size_t s = 0; s < offs.size(); s++)
    {
      const size_t at = static_cast<size_t>(offs[s]), n = static_cast<size_t>(cnts[s]);
      if(at + n > size)
      {
        error = "DNG lossless JPEG piece outside the file";
        return false;
      }
      int cols = 0, rows = 0;
      if(!ljpeg_decode(data + at, n, piece, cols, rows, error)) return false;
      const int x0 = tiled ? static_cast<int>(s % across) * pw : 0;
      const int y0 = tiled ? static_cast<int>(s / across) * ph : strip_y;
      const size_t total = piece.size();
      const int piece_rows = static_cast<int>(total / pw);
      for(int r = 0; r < piece_rows; r++)
      {
        const int y = y0 + r;
        if(y >= out.height) break;
        const int w = std::min(pw, out.width - x0);
        if(w <= 0) break;
        std::memcpy(&out.cfa[static_cast<size_t>(y) * out.width + x0], piece.data() + static_cast<size_t>(r) * pw,
                    static_cast<size_t>(w) * sizeof(uint16_t));
      }
      if(!tiled) strip_y += piece_rows;
    }
  }
  else if(bps == 16)
  {
    size_t written = 0;
    for(size_t s = 0; s < offsets.size(); s++)
    {
      const size_t at = static_cast<size_t>(offsets[s]), n = static_cast<size_t>(counts[s]) / 2;
      if(at + n * 2 > size || written + n > samples)
      {
        error = "DNG strip outside the file";
        return false;
      }
      for(size_t i = 0; i < n; i++) out.cfa[written + i] = tiff.u16(at + i * 2);
      written += n;
    }
    if(written != samples)
    {
      error = "DNG image data truncated";
      return false;
    }
  }
  else
  {
    std::vector<uint8_t> packed;
    for(size_t s = 0; s < offsets.size(); s++)
    {
      const size_t at = static_cast<size_t>(offsets[s]), n = static_cast<size_t>(counts[s]);
      if(at + n > size)
      {
        error = "DNG strip outside the file";
        return false;
      }
      packed.insert(packed.end(), data + at, data + at + n);
    }
    const size_t row_bytes = (static_cast<size_t>(out.width) * bps + 7) / 8;
    if(packed.size() < row_bytes * out.height)
    {
      error = "DNG image data truncated (" + std::to_string(bps) + "-bit)";
      return false;
    }
    const uint32_t mask = (1u << bps) - 1;
    for(int y = 0; y < out.height; y++)
    {
      const uint8_t *row = packed.data() + row_bytes * y;
      for(int x = 0; x < out.width; x++)
      {
        const size_t bit = static_cast<size_t>(x) * bps, byte = bit / 8;
        uint32_t window = 0;
        for(size_t k = 0; k < 3; k++) window = (window << 8) | (byte + k < row_bytes ? row[byte + k] : 0);
        out.cfa[static_cast<size_t>(y) * out.width + x] =
            static_cast<uint16_t>((window >> (24 - bps - static_cast<int>(bit % 8))) & mask);
      }
    }
  }

  const auto pattern = tag(33422);
  if(pattern.size() == 4)
    for(int i = 0; i < 4; i++) out.cfa_colour[i] = std::clamp(static_cast<int>(pattern[i]), 0, 2);
  const auto black = tag(50714);
  for(int i = 0; i < 4; i++) out.black[i] = black.empty() ? 0.0f : static_cast<float>(black[black.size() == 4 ? i : 0]);
  const auto white = tag(50717);
  out.white = white.empty() ? static_cast<float>((1u << bps) - 1) : static_cast<float>(white[0]);
  const auto orientation = tag(274);
  out.orientation = orientation.empty() ? 1 : static_cast<int>(orientation[0]);
  // NoiseProfile: (S, O) per plane, variance = S * x + O for x in [0, 1]; averaged.
  const auto noise = tag(51041);
  if(noise.size() >= 2 && noise.size() % 2 == 0)
  {
    double s = 0, o = 0;
    for(size_t i = 0; i < noise.size(); i += 2) { s += noise[i]; o += noise[i + 1]; }
    out.noise_scale = static_cast<float>(s / (noise.size() / 2));
    out.noise_offset = static_cast<float>(o / (noise.size() / 2));
  }

  // White balance from the as-shot neutral, normalised so the smallest gain is 1.
  auto neutral = tag(50728);
  if(neutral_override) neutral = {neutral_override[0], neutral_override[1], neutral_override[2]};
  if(neutral.size() != 3 || neutral[0] <= 0 || neutral[1] <= 0 || neutral[2] <= 0) neutral = {1, 1, 1};
  const double inv_min = std::min({1 / neutral[0], 1 / neutral[1], 1 / neutral[2]});
  for(int c = 0; c < 3; c++) out.wb[c] = static_cast<float>((1 / neutral[c]) / inv_min);

  // Prefer the D65 calibration (illuminant 21), else the second, else the first.
  const auto illuminant1 = tag(50778), illuminant2 = tag(50779);
  const bool d65_first = illuminant1.size() == 1 && illuminant1[0] == 21;
  const bool use2 = !d65_first && !illuminant2.empty();
  auto pick = [&](uint16_t a, uint16_t b) {
    auto first_choice = tag(use2 ? b : a);
    return first_choice.size() == 9 ? first_choice : tag(use2 ? a : b);
  };
  const auto forward = pick(50964, 50965);
  const auto colour = pick(50721, 50722);
  Mat3 camera_to_d65;
  if(forward.size() == 9)
  { // FM: white-balanced camera -> XYZ D50
    Mat3 fm;
    std::copy(forward.begin(), forward.end(), fm.begin());
    camera_to_d65 = mul(bradford(kD50, kD65), fm);
  }
  else if(colour.size() == 9)
  { // CM: XYZ -> camera; balanced camera c' = c / neutral
    Mat3 cm, xyz_from_camera;
    std::copy(colour.begin(), colour.end(), cm.begin());
    if(!invert(cm, xyz_from_camera))
    {
      error = "singular DNG colour matrix";
      return false;
    }
    const Mat3 n = {neutral[0], 0, 0, 0, neutral[1], 0, 0, 0, neutral[2]};
    const Mat3 m = mul(xyz_from_camera, n);
    auto scene_white = apply(m, {1, 1, 1});
    const double y = scene_white[1];
    for(double &v : scene_white) v /= y;
    camera_to_d65 = mul(bradford(scene_white, kD65), m);
    for(double &v : camera_to_d65) v /= y;
  }
  else
  {
    error = "DNG has no colour calibration";
    return false;
  }
  const Mat3 to_rec2020 = mul(kXyzToRec2020, camera_to_d65);
  for(int i = 0; i < 9; i++) out.camera_to_rec2020[i] = static_cast<float>(to_rec2020[i]);

  auto opcodes = ifd0.find(51009);
  auto raw_opcodes = raw->find(51009);
  const Entry *list = raw_opcodes != raw->end() ? &raw_opcodes->second : opcodes != ifd0.end() ? &opcodes->second : nullptr;
  if(list) read_gain_maps(data + list->offset, list->count, out);
  return true;
}

} // namespace film
