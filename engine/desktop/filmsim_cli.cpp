// Desktop harness for the film engine: develops a PPM (8-bit sRGB) or PFM
// (linear Rec.2020 float) image and writes an 8-bit PPM.
//
//   filmsim_cli --filmsim filmsim.lut --spectra spectra-em.lut --in a.ppm --out b.ppm
//               [--long-edge 4096] [--ev 0] [--seed 1] [--p3] [--set "film=2" ...] [--list]
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <iterator>
#include <sstream>
#include <string>
#include <vector>

#include "film_engine.h"

namespace {

bool read_file(const std::string &path, std::vector<uint8_t> &bytes)
{
  std::ifstream in(path, std::ios::binary);
  if(!in) return false;
  bytes.assign(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
  return true;
}

// Netpbm header tokens, skipping comments.
std::string token(std::istream &in)
{
  std::string t;
  while(in >> t)
  {
    if(t[0] != '#') return t;
    std::string rest;
    std::getline(in, rest);
  }
  return {};
}

struct Loaded
{
  int width = 0, height = 0;
  std::vector<uint8_t> rgba8;
  std::vector<float> rgba32f;
};

bool load_image(const std::string &path, Loaded &image, std::string &error)
{
  std::ifstream in(path, std::ios::binary);
  if(!in)
  {
    error = "cannot open " + path;
    return false;
  }
  const std::string magic = token(in);
  image.width = std::stoi(token(in));
  image.height = std::stoi(token(in));
  const std::string scale = token(in);
  in.get(); // single whitespace before the raster
  const size_t count = static_cast<size_t>(image.width) * image.height;
  if(magic == "P6" && scale == "255")
  {
    std::vector<uint8_t> rgb(count * 3);
    in.read(reinterpret_cast<char *>(rgb.data()), static_cast<std::streamsize>(rgb.size()));
    image.rgba8.resize(count * 4);
    for(size_t i = 0; i < count; i++)
    {
      std::memcpy(&image.rgba8[i * 4], &rgb[i * 3], 3);
      image.rgba8[i * 4 + 3] = 255;
    }
  }
  else if(magic == "PF")
  { // bottom-to-top rows; negative scale means little endian
    std::vector<float> rgb(count * 3);
    in.read(reinterpret_cast<char *>(rgb.data()), static_cast<std::streamsize>(rgb.size() * sizeof(float)));
    if(std::stof(scale) > 0)
    {
      error = "big-endian PFM not supported";
      return false;
    }
    image.rgba32f.resize(count * 4);
    for(int y = 0; y < image.height; y++)
      for(int x = 0; x < image.width; x++)
      {
        const float *src = &rgb[(static_cast<size_t>(image.height - 1 - y) * image.width + x) * 3];
        float *dst = &image.rgba32f[(static_cast<size_t>(y) * image.width + x) * 4];
        dst[0] = src[0];
        dst[1] = src[1];
        dst[2] = src[2];
        dst[3] = 1.0f;
      }
  }
  else
  {
    error = "expected binary PPM (P6, maxval 255) or PFM";
    return false;
  }
  if(!in)
  {
    error = "truncated image " + path;
    return false;
  }
  return true;
}

bool write_ppm(const std::string &path, const film::DevelopResult &result)
{
  std::ofstream out(path, std::ios::binary);
  out << "P6\n" << result.width << " " << result.height << "\n255\n";
  for(size_t i = 0; i < result.rgba8.size(); i += 4) out.write(reinterpret_cast<const char *>(&result.rgba8[i]), 3);
  return static_cast<bool>(out);
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
  std::string filmsim_path, spectra_path, in_path, out_path, error;
  std::vector<std::string> burst_paths;
  std::string creative_path;
  float creative_strength = 1.0f;
  film::FilmParams params;
  film::DevelopOptions options;
  for(int i = 1; i < argc; i++)
  {
    const std::string arg = argv[i];
    auto next = [&]() -> std::string {
      if(i + 1 >= argc)
      {
        std::cerr << arg << " needs a value\n";
        std::exit(2);
      }
      return argv[++i];
    };
    if(arg == "--filmsim") filmsim_path = next();
    else if(arg == "--spectra") spectra_path = next();
    else if(arg == "--in") in_path = next();
    else if(arg == "--also") burst_paths.push_back(next()); // more DNGs: HDR burst merge
    else if(arg == "--lut") creative_path = next();         // RGB8 33^3 creative LUT
    else if(arg == "--lut-strength") creative_strength = std::stof(next());
    else if(arg == "--out") out_path = next();
    else if(arg == "--long-edge") options.max_long_edge = std::stoi(next());
    else if(arg == "--ev") options.exposure_ev = std::stof(next());
    else if(arg == "--seed") options.seed = static_cast<uint32_t>(std::stoul(next()));
    else if(arg == "--p3") options.output = film::ColourSpace::DisplayP3;
    else if(arg == "--set")
    {
      const std::string kv = next();
      const size_t eq = kv.find('=');
      if(eq == std::string::npos ||
         !(film::set_stage(options.stages, kv.substr(0, eq), std::stof(kv.substr(eq + 1))) ||
           film::set_param(params, kv.substr(0, eq), std::stof(kv.substr(eq + 1)))))
      {
        std::cerr << "unknown parameter: " << kv << "\n";
        return 2;
      }
    }
    else if(arg == "--list")
    {
      for(int k = 0; k < film::kFilmCount; k++) std::cout << "film=" << k << "  " << film::kFilmStocks[k] << "\n";
      for(int k = 0; k < film::kPaperCount; k++) std::cout << "paper=" << k << "  " << film::kPapers[k] << "\n";
      return 0;
    }
    else
    {
      std::cerr << "unknown option " << arg << "\n";
      return 2;
    }
  }
  if(filmsim_path.empty() || spectra_path.empty() || in_path.empty() || out_path.empty())
  {
    std::cerr << "usage: filmsim_cli --filmsim F --spectra S --in IN --out OUT [--set name=value]...\n";
    return 2;
  }

  film::Lut filmsim, spectra;
  Loaded image;
  film::RawImage raw;
  const bool is_dng = in_path.size() > 4 && (in_path.substr(in_path.size() - 4) == ".dng" ||
                                             in_path.substr(in_path.size() - 4) == ".DNG");
  bool loaded = load_lut(filmsim_path, filmsim, error) && load_lut(spectra_path, spectra, error);
  if(loaded && is_dng)
  {
    std::vector<uint8_t> bytes;
    loaded = read_file(in_path, bytes) && film::parse_dng(bytes.data(), bytes.size(), raw, error);
    if(loaded)
      std::cout << "dng: " << raw.width << "x" << raw.height << " white " << raw.white << " black " << raw.black[0]
                << " wb " << raw.wb[0] << "/" << raw.wb[1] << "/" << raw.wb[2] << " gain map " << raw.map_width << "x"
                << raw.map_height << " orientation " << raw.orientation << "\n";
  }
  else if(loaded) loaded = load_image(in_path, image, error);
  std::vector<film::RawImage> extra(burst_paths.size());
  for(size_t i = 0; loaded && i < burst_paths.size(); i++)
  {
    std::vector<uint8_t> bytes;
    loaded = is_dng && read_file(burst_paths[i], bytes) && film::parse_dng(bytes.data(), bytes.size(), extra[i], error);
  }
  std::vector<const film::RawImage *> burst{&raw};
  for(const film::RawImage &r : extra) burst.push_back(&r);
  if(!loaded)
  {
    std::cerr << (error.empty() ? "cannot read " + in_path : error) << "\n";
    return 1;
  }

  const auto t0 = std::chrono::steady_clock::now();
  auto engine = film::FilmEngine::create(filmsim, spectra, error);
  if(!engine)
  {
    std::cerr << "engine: " << error << "\n";
    return 1;
  }
  const auto t1 = std::chrono::steady_clock::now();
  const film::DeviceInfo &device = engine->device();
  std::cout << "device: " << device.name << " (Vulkan " << ((device.api_version >> 22) & 0x7f) << "."
            << ((device.api_version >> 12) & 0x3ff) << ", subgroup " << device.subgroup_size << ")\n";

  film::SourceImage source;
  source.width = image.width;
  source.height = image.height;
  if(is_dng && burst.size() > 1)
  {
    source.burst = burst.data();
    source.burst_count = static_cast<int>(burst.size());
  }
  else if(is_dng) source.raw = &raw;
  else if(!image.rgba8.empty()) source.rgba8 = image.rgba8.data();
  else
  {
    source.rgba32f = image.rgba32f.data();
    source.space = film::ColourSpace::LinearRec2020;
  }
  if(!creative_path.empty())
  {
    std::vector<uint8_t> table;
    if(!read_file(creative_path, table) || table.size() != 33 * 33 * 33 * 3 ||
       !engine->set_creative_lut(table.data(), 33, creative_strength, error))
    {
      std::cerr << "creative lut: " << (error.empty() ? "expected 33^3 RGB8 " + creative_path : error) << "\n";
      return 1;
    }
  }
  film::DevelopResult result;
  if(!engine->develop(source, params, options, result, error))
  {
    std::cerr << "develop: " << error << "\n";
    return 1;
  }
  const auto t2 = std::chrono::steady_clock::now();
  if(!write_ppm(out_path, result))
  {
    std::cerr << "cannot write " << out_path << "\n";
    return 1;
  }
  auto ms = [](auto a, auto b) { return std::chrono::duration<double, std::milli>(b - a).count(); };
  std::cout << "film: " << film::kFilmStocks[params.film] << " / paper: " << film::kPapers[params.paper] << "\n"
            << "size: " << result.width << "x" << result.height << ", passes: " << result.passes
            << ", peak GPU images: " << result.peak_image_bytes / (1024.0 * 1024.0) << " MiB\n"
            << "init: " << ms(t0, t1) << " ms, develop: " << ms(t1, t2) << " ms, GPU: " << result.gpu_ms << " ms\n";
  return 0;
}
