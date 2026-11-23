// filmsim parameters, laid out exactly as vkdt's params_t uniform block (std140,
// see shaders/vkdt/filmsim/head.glsl). Defaults follow filmsim's `params` file.
#pragma once
#include <cstddef>
#include <cstdint>
#include <string_view>

namespace film {

struct alignas(16) FilmParams
{
  int32_t process = 0; // 0 expose film and print, 1 expose and scan negative, 2 print a scanned negative
  int32_t tabs = 0;    // ui only
  int32_t film = 4;    // index into kFilmStocks
  float ev_film = 0.0f;
  float f_base = 1.0f;
  float gamma_film = 1.0f;
  float g_fast = 1.0f;
  float g_slow = 1.0f;
  float exhaust = 0.0f;
  float hl_boost_ev = 0.0f;
  int32_t paper = 3; // index into kPapers
  float ev_paper = 0.0f;
  float p_base = 1.0f;
  float gamma_paper = 1.0f;
  float g_fast_p = 1.0f;
  float g_slow_p = 1.0f;
  float p_exh = 0.0f;
  float glare = 0.0f;
  float filter_c = -1.0f; // -1 / -2: fitted balance, see kBalanceAuto / kBalanceReference
  float filter_m = 0.0f;
  float filter_y = 0.0f;
  float tune_m = 0.0f;
  float tune_y = 0.0f;
  int32_t preflash = 0;
  float pf_ev = -10.0f;
  float pf_m = 0.0f;
  float pf_y = 0.0f;
  int32_t couplers = 1;
  // fitted to the reference spektrafilm's saturation (its colour chart, 14 negatives)
  float cp_amt = 1.0f;
  float langmuir_r = 1.5f;
  float langmuir_g = 1.5f;
  float langmuir_b = 1.5f;
  float couplers_radius = 20.0f; // micrometres on the negative
  int32_t halation = 1;
  float halation_radius = 65.0f; // micrometres on the negative
  float halation_amount = 1.0f;
  float halation_midtones = 0.0f;
  int32_t hal_bnc = 3;
  float hal_dec = 0.5f;
  float scat_amt = 0.2f;
  float halation_strength[4] = {1.0f, 1.0f, 1.0f, 0.0f};
  int32_t grain = 1;
  float grain_size = 1.0f;
  float grain_uniformity = 1.0f;
  int32_t enlarge = 0; // vkdt's 2x/4x output upscaling; this engine always renders 1x
  float scan_ill = 5000.0f;
  float scene_ill = 0.0f;
  float scene_tint = 0.0f;
  float film_ill = 0.0f;
  int32_t input_colour = 0; // 0: Rec.2020 linear, which the decode pass always produces
  // Added here (not in vkdt's filmsim): the frame's long edge on the negative (0: filmsim's
  // 35 mm), which scales grain, halation and coupler spread; the grain's strength; the
  // print's contrast per dye layer (crossover).
  float frame_um = 0.0f;
  float grain_amount = 1.0f;
  float gamma_p_r = 1.0f;
  float gamma_p_g = 1.0f;
  float gamma_p_b = 1.0f;
  // The camera's UV- and IR-cut filters (0 none .. 1 full), the reference's filter_uv / filter_ir;
  // off by default, as there.
  float uv_cut = 0.0f;
  float ir_cut = 0.0f;
};
static_assert(offsetof(FilmParams, halation_strength) == 160, "std140 vec4 alignment");
static_assert(offsetof(FilmParams, input_colour) == 208, "std140 layout drifted from head.glsl");
static_assert(offsetof(FilmParams, gamma_p_b) == 228, "std140 layout drifted from head.glsl");
static_assert(offsetof(FilmParams, ir_cut) == 236, "std140 layout drifted from head.glsl");

constexpr int kProcessPrint = 0;
constexpr int kProcessScanNegative = 1;
constexpr int kProcessPrintNegative = 2;

// Order matches filmsim params.ui, wb.h and the rows of filmsim.lut.
constexpr const char *kFilmStocks[] = {
    "kodak gold 200", "kodak ultramax 400", "kodak ektar 100", "kodak portra 160",
    "kodak portra 400", "kodak portra 800", "kodak portra 800 push 1", "kodak portra 800 push 2",
    "fujifilm c200", "fujifilm xtra 400", "fujifilm pro 400h", "kodak vision3 50d",
    "kodak vision3 250d", "kodak vision3 200t", "kodak vision3 500t", "kodak verita 200d",
    "kodak kodachrome 64 (positive)", "kodak ektachrome 100 (positive)",
    "fujifilm provia 100f (positive)", "fujifilm velvia 100 (positive)",
    "kodak double-x (b/w)", "kodak tri-x (b/w, positive)",
};
constexpr const char *kPapers[] = {
    "kodak endura premier", "kodak supra endura", "kodak ultra endura", "kodak portra endura",
    "kodak ektacolor edge", "fujifilm crystal archive type ii", "kodak 2383", "kodak 2393",
    "kodak 2302 (b/w)",
};
constexpr int kFilmCount = static_cast<int>(sizeof(kFilmStocks) / sizeof(kFilmStocks[0]));
constexpr int kPaperCount = static_cast<int>(sizeof(kPapers) / sizeof(kPapers[0]));

// Sets a parameter by its vkdt name ("ev film", "film", "cp amt", "strength r", ...).
// Returns false for unknown names.
bool set_param(FilmParams &params, std::string_view name, float value);

// filter_c sentinels: -1 neutral for the chosen film (filmsim's auto), -2 the
// reference film's neutral for every film (keeps each stock's own colour).
constexpr float kBalanceAuto = -1.0f;
constexpr float kBalanceReference = -2.0f;
constexpr int kReferenceFilm = 4; // kodak portra 400

// Which parts of the process run. Film off: the scene under a neutral tone curve
// (raw sources) or as shot. Print off: the film scanned directly; slides are already
// positive, negatives are inverted like a lab scanner, calibrated per stock.
struct Stages
{
  bool film = true;
  bool print = true;
  // 8-bit sources only, 0..1: how much of a camera's own rendering (saturation, contrast,
  // the shoulder ending at 1.0) is taken back off before the film, see decode.comp.
  float restore = 0.0f;
  bool baseline = false; // apply a DNG's BaselineExposure ("stage baseline", imports only)
  // 8-bit sources only, -1..1: white balance at the door, in linear light (warmer / greener).
  float temp = 0.0f;
  float tint = 0.0f;
  // Raw sources with match_key: the viewfinder's brightness only lifts the shot, never
  // darkens it, and never pushes its brightest 0.5% past 0.9 (a flash frame: the flash
  // lit the subject, the HAL's exposure often leaves the rest under the viewfinder).
  bool match_lift = false;
  // 8-bit sources that are finished pictures (imports): with the film off they leave as they
  // came, not under the neutral tone curve meant for scene light (raw, the viewfinder).
  bool display = false;
  // A camera's own exposure judgement on an 8-bit source (scene restore's auto exposure):
  // off, the picture's brightness is left as it came (imports, "测光" off).
  bool meter = true;
  // A diffusion filter in front of the lens: 0 none, 1 Pro-Mist, 2 Black Pro-Mist,
  // 3 Glimmerglass, 4 Cinebloom; its amount (0..1), size (x1) and warmth (-1..1).
  int diffusion = 0;
  float diff_amount = 0.5f;
  float diff_size = 1.0f;
  float diff_warm = 0.0f;
  // The scanner: its softness (pixels of blur), sharpening (amount, radius in pixels) and
  // black / white points (linear levels).
  // The reference spektrafilm's scanner sharpens a little (fitted on a photo, with its grain).
  float scan_blur = 0.0f;
  float scan_sharp = 0.3f;
  float scan_radius = 0.7f;
  // The developer's own spread with grain on (spektrafilm's grain blur): a Gaussian of
  // this many output pixels over the developed film, softening the grain's finest speckle.
  float grain_blur = 0.65f;
  // The film's output compressed softly into the display's gamut ("stage gamut"; off: clipped).
  bool gamut = true;
  // Development aid ("stage negative"): a negative scanned as it is, the orange base and all,
  // without the lab's inversion. Not for pictures; for showing what the film holds.
  bool negative = false;
  float black = 0.0f;
  float white = 1.0f;
};
// "stage film" / "stage print" (0 off, otherwise on), "stage restore" (0..1); false for other names.
bool set_stage(Stages &stages, std::string_view name, float value);

// Slide and reversal stocks: their developed film is the positive image.
constexpr bool is_positive_film(int film) { return film >= 16 && film <= 19 || film == 21; }

// Resolves the filter_c sentinels into fitted filter values for the paper.
// The user's ev_paper (or ev_film for scanned negatives) stays an offset on top.
FilmParams resolve_auto_balance(const FilmParams &params);

} // namespace film
