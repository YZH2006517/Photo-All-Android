#include "film_params.h"

#include <algorithm>

#include "vkdt/pos_wb.h"
#include "vkdt/wb.h"

namespace film {

static_assert(sizeof(wb) / sizeof(wb[0]) == kFilmCount, "wb.h out of sync with kFilmStocks");
static_assert(sizeof(wb[0]) / sizeof(wb[0][0]) == kPaperCount, "wb.h out of sync with kPapers");
static_assert(sizeof(pos_wb) / sizeof(pos_wb[0]) == kFilmCount, "pos_wb.h out of sync with kFilmStocks");

namespace {

struct Field
{
  std::string_view name;
  size_t offset;
  bool integer;
};

#define FLOAT(n, m) Field{n, offsetof(FilmParams, m), false}
#define INT(n, m) Field{n, offsetof(FilmParams, m), true}
constexpr Field kFields[] = {
    INT("process", process),       INT("film", film),
    FLOAT("ev film", ev_film),     FLOAT("f base", f_base),
    FLOAT("g film", gamma_film),   FLOAT("g fast", g_fast),
    FLOAT("g slow", g_slow),       FLOAT("exhaust", exhaust),
    FLOAT("hl boost", hl_boost_ev), INT("paper", paper),
    FLOAT("ev paper", ev_paper),   FLOAT("p base", p_base),
    FLOAT("g paper", gamma_paper), FLOAT("g fast p", g_fast_p),
    FLOAT("g slow p", g_slow_p),   FLOAT("p exh", p_exh),
    FLOAT("glare", glare),         FLOAT("filter c", filter_c),
    FLOAT("filter m", filter_m),   FLOAT("filter y", filter_y),
    FLOAT("tune m", tune_m),       FLOAT("tune y", tune_y),
    INT("preflash", preflash),     FLOAT("pf ev", pf_ev),
    FLOAT("pf m", pf_m),           FLOAT("pf y", pf_y),
    INT("couplers", couplers),     FLOAT("cp amt", cp_amt),
    FLOAT("lang r", langmuir_r),   FLOAT("lang g", langmuir_g),
    FLOAT("lang b", langmuir_b),   FLOAT("cp rad", couplers_radius),
    INT("halation", halation),     FLOAT("radius", halation_radius),
    FLOAT("hal amt", halation_amount), FLOAT("hal mids", halation_midtones),
    INT("hal bnc", hal_bnc),       FLOAT("hal dec", hal_dec),
    FLOAT("scat amt", scat_amt),   INT("grain", grain),
    FLOAT("size", grain_size),     FLOAT("uniform", grain_uniformity),
    FLOAT("scan ill", scan_ill),   FLOAT("scne ill", scene_ill),
    FLOAT("tint", scene_tint),     FLOAT("film ill", film_ill),
};
#undef FLOAT
#undef INT

} // namespace

bool set_param(FilmParams &params, std::string_view name, float value)
{
  constexpr std::string_view strength = "strength ";
  if(name.substr(0, strength.size()) == strength && name.size() == strength.size() + 1)
  {
    const size_t channel = std::string_view("rgb").find(name.back());
    if(channel == std::string_view::npos) return false;
    params.halation_strength[channel] = value;
    return true;
  }
  for(const Field &field : kFields)
  {
    if(field.name != name) continue;
    char *base = reinterpret_cast<char *>(&params) + field.offset;
    if(field.integer) *reinterpret_cast<int32_t *>(base) = static_cast<int32_t>(value);
    else *reinterpret_cast<float *>(base) = value;
    return true;
  }
  return false;
}

bool set_stage(Stages &stages, std::string_view name, float value)
{
  if(name == "stage film") stages.film = value != 0.0f;
  else if(name == "stage print") stages.print = value != 0.0f;
  else if(name == "stage restore") stages.restore = std::clamp(value, 0.0f, 1.0f);
  else if(name == "stage temp") stages.temp = std::clamp(value, -1.0f, 1.0f);
  else if(name == "stage tint") stages.tint = std::clamp(value, -1.0f, 1.0f);
  else if(name == "stage match lift") stages.match_lift = value != 0.0f;
  else return false;
  return true;
}

FilmParams resolve_auto_balance(const FilmParams &params)
{
  FilmParams out = params;
  out.film = std::clamp(out.film, 0, kFilmCount - 1);
  out.paper = std::clamp(out.paper, 0, kPaperCount - 1);
  if(out.filter_c != kBalanceAuto && out.filter_c != kBalanceReference) return out;
  // Same rule as filmsim_apply_wb_auto: scanned positives balance at the scan,
  // everything else under the enlarger. The reference mode prints every stock
  // with the reference film's neutral filter pack, so each keeps its own colour.
  const int film = out.filter_c == kBalanceReference ? kReferenceFilm : out.film;
  const bool positive = out.process == kProcessScanNegative;
  const float *v = positive ? pos_wb[film] : wb[film][out.paper];
  (positive ? out.ev_film : out.ev_paper) += v[0];
  out.filter_c = v[1];
  out.filter_m = v[2];
  out.filter_y = v[3];
  return out;
}

} // namespace film
