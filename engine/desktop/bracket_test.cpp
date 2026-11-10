// Synthetic exposure-bracket test for the burst merge: turns an 8-bit PPM into a
// high-contrast scene (the right side lit up to 16x brighter), samples it as noisy
// 10-bit RGGB frames (short reference frames plus one longer, shifted frame), then
// develops single frames, the equal-exposure burst and the bracket.
//
//   bracket_test --filmsim filmsim.lut --spectra spectra-em.lut --in a.ppm --out-dir dir
//                [--stops 2] [--shorts 3] [--set "stage film=0" ...]
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iostream>
#include <iterator>
#include <random>
#include <string>
#include <vector>

#include "burst.h"
#include "film_engine.h"

namespace {

bool read_file(const std::string &path, std::vector<uint8_t> &bytes)
{
  std::ifstream in(path, std::ios::binary);
  if(!in) return false;
  bytes.assign(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
  return true;
}

bool load_ppm(const std::string &path, int &w, int &h, std::vector<uint8_t> &rgb)
{
  std::ifstream in(path, std::ios::binary);
  std::string magic;
  int maxval = 0;
  in >> magic >> w >> h >> maxval;
  in.get();
  if(magic != "P6" || maxval != 255) return false;
  rgb.resize(static_cast<size_t>(w) * h * 3);
  in.read(reinterpret_cast<char *>(rgb.data()), static_cast<std::streamsize>(rgb.size()));
  return static_cast<bool>(in);
}

bool write_ppm(const std::string &path, const film::DevelopResult &r)
{
  std::ofstream out(path, std::ios::binary);
  out << "P6\n" << r.width << " " << r.height << "\n255\n";
  for(size_t i = 0; i < r.rgba8.size(); i += 4) out.write(reinterpret_cast<const char *>(&r.rgba8[i]), 3);
  return static_cast<bool>(out);
}

float srgb_to_linear(float v) { return v <= 0.04045f ? v / 12.92f : std::pow((v + 0.055f) / 1.055f, 2.4f); }

// Scene radiance (linear, relative): the photo with a light falloff that makes its
// right side up to 16x (4 stops) brighter than the left.
std::vector<float> scene(int w, int h, const std::vector<uint8_t> &rgb)
{
  std::vector<float> s(static_cast<size_t>(w) * h * 3);
  for(int y = 0; y < h; y++)
    for(int x = 0; x < w; x++)
    {
      const float u = static_cast<float>(x) / (w - 1);
      const float t = std::clamp((u - 0.35f) / 0.6f, 0.0f, 1.0f);
      const float light = std::exp2(4.0f * t * t * (3 - 2 * t));
      for(int c = 0; c < 3; c++)
      {
        const size_t i = (static_cast<size_t>(y) * w + x) * 3 + c;
        s[i] = srgb_to_linear(rgb[i] / 255.0f) * light;
      }
    }
  return s;
}

constexpr float kWhite = 1023.0f, kBlack = 64.0f;
float kNoiseS = 2.5e-4f, kNoiseO = 4e-6f; // variance = s x + o, x in [0, 1]; --noise scales both
const float kSensitivity[3] = {0.5f, 1.0f, 0.6f};   // camera RGB response (inverse white balance)

// One RGGB frame of the scene at `exposure`, shifted by (dx, dy) pixels, with shot and read noise.
// Optional mixed lighting for the flash test: the scene's light tinted per channel, plus
// a neutral flash falling off from the image centre with `flash` at its peak.
float g_tint[3] = {1, 1, 1};

film::RawImage capture(const std::vector<float> &s, int w, int h, float exposure, int dx, int dy, uint32_t seed,
                       float noise = 1.0f, float flash = 0.0f, float roll_deg = 0.0f)
{
  film::RawImage raw;
  raw.width = w & ~1;
  raw.height = h & ~1;
  raw.cfa.resize(static_cast<size_t>(raw.width) * raw.height);
  raw.white = kWhite;
  for(float &b : raw.black) b = kBlack;
  // the camera balances the ambient light (sensitivity times tint)
  raw.wb[0] = 1.0f / (kSensitivity[0] * g_tint[0]) * g_tint[1];
  raw.wb[1] = 1.0f;
  raw.wb[2] = 1.0f / (kSensitivity[2] * g_tint[2]) * g_tint[1];
  raw.noise_scale = kNoiseS;
  raw.noise_offset = kNoiseO;
  std::mt19937 rng(seed);
  std::normal_distribution<float> normal(0.0f, 1.0f);
  for(int y = 0; y < raw.height; y++)
    for(int x = 0; x < raw.width; x++)
    {
      const int c = (x & 1) + (y & 1); // R, G, G, B for RGGB
      // the camera rolled by roll_deg about the frame centre, then shifted
      const float cr = std::cos(roll_deg * 0.0174533f), sr = std::sin(roll_deg * 0.0174533f);
      const float rx = 0.5f * w + cr * (x - 0.5f * w) - sr * (y - 0.5f * h);
      const float ry = 0.5f * h + sr * (x - 0.5f * w) + cr * (y - 0.5f * h);
      const int sx = std::clamp(static_cast<int>(std::lround(rx)) + dx, 0, w - 1);
      const int sy = std::clamp(static_cast<int>(std::lround(ry)) + dy, 0, h - 1);
      const float scene = s[(static_cast<size_t>(sy) * w + sx) * 3 + c];
      const float ux = (sx - 0.5f * w) / (0.35f * w), uy = (sy - 0.45f * h) / (0.35f * w);
      const float falloff = flash > 0.0f ? flash / (1.0f + 4.0f * (ux * ux + uy * uy)) : 0.0f;
      // flash light reflects off the scene's own colour (albedo ~ the photo)
      const float v = (scene * g_tint[c] + falloff * std::min(scene, 1.0f)) * kSensitivity[c] * exposure;
      const float noisy = v + noise * std::sqrt(kNoiseS * std::max(v, 0.0f) + kNoiseO) * normal(rng);
      const float code = kBlack + noisy * (kWhite - kBlack);
      raw.cfa[static_cast<size_t>(y) * raw.width + x] = static_cast<uint16_t>(std::clamp(std::lround(code), 0L, 1023L));
    }
  return raw;
}

bool load_lut(const std::string &path, film::Lut &lut, std::string &error)
{
  std::vector<uint8_t> bytes;
  if(!read_file(path, bytes))
  {
    error = "cannot read " + path;
    return false;
  }
  return film::parse_lut(bytes.data(), bytes.size(), lut, error);
}

} // namespace

int main(int argc, char **argv)
{
  std::string filmsim_path, spectra_path, in_path, out_dir = ".";
  float stops = 2.0f;
  int shorts = 3;
  float ltm = 0.5f;
  float flash_peak = 3.0f;
  float flash_roll = 0.0f;
  int motion = 0;
  float chroma = 0.0f;
  float luma = 0.0f;
  std::vector<std::string> sets;
  for(int i = 1; i < argc; i++)
  {
    const std::string arg = argv[i];
    auto next = [&]() { return i + 1 < argc ? std::string(argv[++i]) : std::string(); };
    if(arg == "--filmsim") filmsim_path = next();
    else if(arg == "--spectra") spectra_path = next();
    else if(arg == "--in") in_path = next();
    else if(arg == "--out-dir") out_dir = next();
    else if(arg == "--stops") stops = std::stof(next());
    else if(arg == "--shorts") shorts = std::clamp(std::stoi(next()), 1, 5);
    else if(arg == "--set") sets.push_back(next());
    else if(arg == "--ltm") ltm = std::stof(next());
    else if(arg == "--chroma") chroma = std::stof(next());
    else if(arg == "--denoise") luma = std::stof(next());
    else if(arg == "--flash-peak") flash_peak = std::stof(next());
    else if(arg == "--flash-roll") flash_roll = std::stof(next());
    else if(arg == "--motion") motion = std::stoi(next());
    else if(arg == "--noise")
    {
      const float k = std::stof(next());
      kNoiseS *= k;
      kNoiseO *= k * k;
    }
  }
  bool flash_test = false;
  for(int i = 1; i < argc; i++) if(std::string(argv[i]) == "--flash") flash_test = true;
  int w = 0, h = 0;
  std::vector<uint8_t> rgb;
  if(!load_ppm(in_path, w, h, rgb))
  {
    std::cerr << "need --in <binary P6 ppm>\n";
    return 1;
  }
  std::string error;
  film::Lut filmsim, spectra;
  if(!load_lut(filmsim_path, filmsim, error) || !load_lut(spectra_path, spectra, error))
  {
    std::cerr << error << "\n";
    return 1;
  }
  auto engine = film::FilmEngine::create(filmsim, spectra, error);
  if(!engine)
  {
    std::cerr << error << "\n";
    return 1;
  }
  film::FilmParams params;
  film::Stages stages;
  for(const std::string &s : sets)
  {
    const size_t eq = s.find('=');
    const std::string name = s.substr(0, eq);
    const float value = std::stof(s.substr(eq + 1));
    if(!film::set_stage(stages, name, value) && !film::set_param(params, name, value))
    {
      std::cerr << "unknown parameter " << name << "\n";
      return 1;
    }
  }

  if(flash_test)
  { // warm room light, a neutral flash: ambient frames and one flash frame, same exposure
    g_tint[0] = 1.6f; g_tint[1] = 1.0f; g_tint[2] = 0.5f;
    const std::vector<float> sc = scene(w, h, rgb);
    const float exposure = 0.05f;
    std::vector<film::RawImage> fr;
    fr.push_back(capture(sc, w, h, exposure, 0, 0, 3));
    fr.push_back(capture(sc, w, h, exposure, 0, 0, 4));
    fr.push_back(capture(sc, w, h, exposure, 12, -6, 5, 1.0f, flash_peak, flash_roll));
    { // what alignment makes of it, as the develop does (flash frame: same exposure)
      std::vector<const film::RawImage *> v{&fr[0], &fr[2]};
      std::vector<char> same{0, 2};
      film::BurstAlignment al;
      film::align_burst(v, al, error, &same);
      const int tiles = al.tiles_x * al.tiles_y;
      std::printf("flash frame (true shift -12, +6): tile offsets");
      for(int t = 0; t < tiles; t += std::max(1, tiles / 8)) std::printf(" (%d,%d)", al.offsets[(tiles + t) * 2], al.offsets[(tiles + t) * 2 + 1]);
      std::printf("\n");
    }
    std::vector<const film::RawImage *> ptr;
    for(const film::RawImage &f : fr) ptr.push_back(&f);
    struct Mode { const char *name; bool on; float ambient, target; bool wb; };
    const Mode modes[] = {{"flash_off", false, 1, 0, false}, {"flash_fill", true, 1, 0.12f, true},
                          {"flash_balanced", true, 1, 0.35f, true}, {"flash_overpower", true, 0.25f, 0.35f, true},
                          {"flash_overpower_nowb", true, 0.25f, 0.35f, false}};
    for(const Mode &m : modes)
    {
      film::SourceImage src;
      src.burst = ptr.data();
      src.burst_count = m.on ? 3 : 2;
      film::DevelopOptions options;
      options.max_long_edge = std::max(w, h);
      options.stages = stages;
      options.exposure_ev = 2.0f;
      options.flash.enabled = m.on;
      options.flash.ambient = m.ambient;
      options.flash.target = m.target;
      if(m.wb) // flash colour (neutral light) -> ambient white: tint per channel
        for(int c = 0; c < 3; c++) options.flash.wb[c] = g_tint[c] / g_tint[1];
      film::DevelopResult result;
      if(!engine->develop(src, params, options, result, error))
      {
        std::cerr << m.name << ": " << error << "\n";
        return 1;
      }
      write_ppm(out_dir + "/" + m.name + ".ppm", result);
      if(std::string(m.name) == "flash_overpower") std::fwrite(result.note.data(), 1, result.note.size(), stdout);
      std::printf("%s done\n", m.name);
    }
    // Single flash: an ambient frame, then a flash frame at half its exposure (as the HAL
    // lowers exposure for a flash); white balanced for daylight, the room, or per area.
    {
      film::RawImage amb = capture(sc, w, h, exposure, 0, 0, 7);
      film::RawImage fl = capture(sc, w, h, exposure * 0.5f, 4, -2, 8, 1.0f, 6.0f);
      const float n_amb[3] = {kSensitivity[0] * g_tint[0], 1.0f, kSensitivity[2] * g_tint[2]};
      const float n_fl[3] = {kSensitivity[0], 1.0f, kSensitivity[2]};
      auto balance = [](film::RawImage &r, const float *n) {
        const float m = std::min({1 / n[0], 1 / n[1], 1 / n[2]});
        for(int c = 0; c < 3; c++) r.wb[c] = (1 / n[c]) / m;
      };
      struct Case { const char *name; const float *global; bool map; };
      const Case cases[] = {{"single_daylight", n_fl, false}, {"single_room", n_amb, false}, {"single_auto", n_fl, true}};
      for(const Case &c : cases)
      {
        balance(fl, c.global);
        std::vector<const film::RawImage *> pair{&amb, &fl};
        film::SourceImage src;
        if(c.map)
        {
          src.burst = pair.data();
          src.burst_count = 2;
        }
        else src.raw = &fl;
        film::DevelopOptions options;
        options.max_long_edge = std::max(w, h);
        options.stages = stages;
        options.exposure_ev = 2.5f;
        options.flash_white.enabled = c.map;
        for(int k = 0; k < 3; k++)
        {
          options.flash_white.ambient[k] = n_amb[k];
          options.flash_white.flash[k] = n_fl[k];
        }
        film::DevelopResult result;
        if(!engine->develop(src, params, options, result, error))
        {
          std::cerr << c.name << ": " << error << "\n";
          return 1;
        }
        write_ppm(out_dir + "/" + c.name + ".ppm", result);
        std::printf("%s done\n", c.name);
      }
    }
    return 0;
  }

  // Short frames expose the left (dim) side at ~18% grey and clip only the brightest
  // highlights on the right; the long frame is `stops` brighter and shifted.
  const std::vector<float> s = scene(w, h, rgb);
  const float short_exposure = 0.12f;
  const float long_exposure = short_exposure * std::exp2(stops);
  std::vector<film::RawImage> frames;
  for(int i = 0; i < shorts; i++) frames.push_back(capture(s, w, h, short_exposure, 0, 0, 11 + i));
  frames.push_back(capture(s, w, h, long_exposure, 6, -4, 97));
  // --motion: something moves between the frames - a box in the middle of every frame
  // after the first is replaced by what lies `motion` pixels per frame to its left.
  if(motion > 0)
    for(size_t i = 1; i < frames.size(); i++)
    {
      film::RawImage &f = frames[i];
      const film::RawImage copy = f;
      const int shift = (motion * static_cast<int>(i)) & ~1; // keep the CFA phase
      for(int y = f.height * 2 / 5; y < f.height * 3 / 5; y++)
        for(int x = f.width * 2 / 5; x < f.width * 3 / 5; x++)
          f.cfa[static_cast<size_t>(y) * f.width + x] = copy.cfa[static_cast<size_t>(y) * f.width + x - shift];
    }
  // What HDR off shoots: one frame at the metered exposure, 1.7 stops over the shorts.
  const float under = 1.7f;
  const film::RawImage metered = capture(s, w, h, short_exposure * std::exp2(under), 0, 0, 5);
  // Noise-free reference for measuring what the merge leaves.
  const film::RawImage clean = capture(s, w, h, short_exposure, 0, 0, 1, 0.0f);

  std::vector<const film::RawImage *> bracket;
  for(const film::RawImage &f : frames) bracket.push_back(&f);
  film::BurstAlignment alignment;
  if(!film::align_burst(bracket, alignment, error))
  {
    std::cerr << error << "\n";
    return 1;
  }
  std::printf("true long/short ratio %.3f (%.2f stops)\n", std::exp2(stops), stops);
  for(size_t f = 0; f < bracket.size(); f++)
    std::printf("frame %zu: gain %.4f (ratio %.3f) offset tile0 (%d, %d)\n", f, alignment.gain[f],
                1.0 / alignment.gain[f], alignment.offsets[f * alignment.tiles_x * alignment.tiles_y * 2],
                alignment.offsets[f * alignment.tiles_x * alignment.tiles_y * 2 + 1]);

  struct Case
  {
    const char *name;
    std::vector<const film::RawImage *> frames;
    float ev;
    float ltm = 0.0f;
  };
  std::vector<const film::RawImage *> burst(bracket.begin(), bracket.begin() + shorts);
  const std::vector<Case> cases = {
      {"metered", {&metered}, 1.5f - under},
      {"short", {bracket[0]}, 1.5f},
      {"burst", burst, 1.5f},
      {"bracket", bracket, 1.5f},
      {"long", {bracket.back()}, 1.5f - stops},
      {"bracket_ltm", bracket, 1.5f, ltm},
      {"metered_ltm", {&metered}, 1.5f - under, ltm},
      {"clean", {&clean}, 1.5f},
      {"clean_ltm", {&clean}, 1.5f, ltm},
  };
  for(const Case &c : cases)
  {
    film::SourceImage src;
    if(c.frames.size() == 1) src.raw = c.frames[0];
    else
    {
      src.burst = c.frames.data();
      src.burst_count = static_cast<int>(c.frames.size());
    }
    film::DevelopOptions options;
    options.exposure_ev = c.ev;
    options.max_long_edge = std::max(w, h);
    options.stages = stages;
    options.local_tone = c.ltm;
    options.chroma_denoise = c.frames[0] == &clean ? 0.0f : chroma;
    options.raw_denoise = c.frames[0] == &clean ? 0.0f : luma;
    film::DevelopResult result;
    if(!engine->develop(src, params, options, result, error))
    {
      std::cerr << c.name << ": " << error << "\n";
      return 1;
    }
    const std::string path = out_dir + "/" + c.name + ".ppm";
    write_ppm(path, result);
    std::printf("%s: %d frames, %.1f ms -> %s\n", c.name, static_cast<int>(c.frames.size()), result.gpu_ms, path.c_str());
  }
  return 0;
}
