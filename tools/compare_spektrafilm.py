"""Step-by-step comparison of the film engine against the reference spektrafilm.

Runs the same synthetic inputs through both engines with one stage of the process free at a
time and the rest matched, and writes one JSON line per test:

  color     ColorChecker 24 (linear Rec.2020) through film + paper (or a slide, scanned):
            mean / max CIEDE2000, lightness and chroma per patch
  couplers  the same with the DIR inhibitors off in both
  gamut     the same with each engine's output gamut compression on
  glare     the reference's default print glare (0.03) against ours off and at 0.03
  halation  radial profile round a bright spot (+6 stops, 8 px, 2400 px frame)
  diffusion radial profiles with Pro-Mist, Black Pro-Mist and Cinebloom at strength 0.5
  sharpen   a dark/light edge with and without each scanner's sharpening
  films     the chart for every stock both engines have
  papers    the chart through every paper both engines have (Portra 400)

Both engines are set alike: the reference with its black and white correction on and its
white level at 1.0 (as ours), auto exposure off, grain, halation, glare, gamut compression
and sharpening off unless the test is about them. The reference's code is only called, never
copied into the engine (it is GPL; see the licence notes).

Usage (in the reference's own environment, with the desktop CLI built):
  third_party/spektra-venv/Scripts/python.exe -I tools/compare_spektrafilm.py OUT_DIR color halation ...
"""
import json
import os
import subprocess
import sys

import colour
import numpy as np
from spektrafilm.runtime.process import photo_params, simulate
from spektrafilm.utils.gamut_compression import OutputGamutCompressSpec

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
CLI = os.path.join(ROOT, 'build', 'desktop', 'filmsim_cli.exe')
LUTS = os.path.join(ROOT, 'android', 'app', 'src', 'main', 'assets', 'filmsim')

# Our film and paper indices, in order, by the reference's profile names.
STOCKS = ["kodak_gold_200", "kodak_ultramax_400", "kodak_ektar_100", "kodak_portra_160", "kodak_portra_400",
          "kodak_portra_800", "kodak_portra_800_push1", "kodak_portra_800_push2", "fujifilm_c200",
          "fujifilm_xtra_400", "fujifilm_pro_400h", "kodak_vision3_50d", "kodak_vision3_250d",
          "kodak_vision3_200t", "kodak_vision3_500t", "kodak_verita_200d", "kodak_kodachrome_64",
          "kodak_ektachrome_100", "fujifilm_provia_100f", "fujifilm_velvia_100"]
SLIDES = range(16, 20)
VISION3 = range(11, 15)
PAPERS = ["kodak_endura_premier", "kodak_supra_endura", "kodak_ultra_endura", "kodak_portra_endura",
          "kodak_ektacolor_edge", "fujifilm_crystal_archive_typeii", "kodak_2383", "kodak_2393"]
PORTRA_ENDURA, KODAK_2383 = 3, 6
# Diffusion filters: the reference's family, our Stages::diffusion index.
DIFFUSION = {'pro_mist': 1, 'black_pro_mist': 2, 'cinebloom': 4}
PATCH = 100
RADII = [0, 10, 12, 16, 24, 40, 64, 100, 160, 250]


# ----- inputs ------------------------------------------------------------------------------

def chart():
    cc = colour.CCS_COLOURCHECKERS['ColorChecker24 - After November 2014']
    xyz = colour.xyY_to_XYZ(np.array(list(cc.data.values())))
    xyz = colour.chromatic_adaptation(xyz, colour.xy_to_XYZ(cc.illuminant), colour.xy_to_XYZ([0.3127, 0.3290]))
    rgb = colour.XYZ_to_RGB(xyz, 'ITU-R BT.2020', apply_cctf_encoding=False)
    img = np.zeros((4 * PATCH, 6 * PATCH, 3))
    for i, c in enumerate(rgb):
        y, x = divmod(i, 6)
        img[y * PATCH:(y + 1) * PATCH, x * PATCH:(x + 1) * PATCH] = c
    return img


def spot(width=2400, height=1600, radius=8, ev=6.0):
    yy, xx = np.mgrid[0:height, 0:width]
    img = np.zeros((height, width, 3))
    img[(xx - width / 2) ** 2 + (yy - height / 2) ** 2 <= radius * radius] = 0.184 * 2 ** ev
    return img


def edge(width=1200, height=800):
    img = np.full((height, width, 3), 0.184 / 4)
    img[:, width // 2:] = 0.184 * 2.5
    return img


# ----- the two engines -----------------------------------------------------------------------

def reference(img, film, paper, slide=False, couplers=True, halation=False, glare=False, gamut=False,
              sharpen=False, diffusion=None, **_):
    p = photo_params(STOCKS[film], PAPERS[PORTRA_ENDURA if paper is None else paper])
    p.io.input_color_space = 'ITU-R BT.2020'
    p.io.input_cctf_decoding = False
    p.camera.auto_exposure = False
    p.film_render.dir_couplers.active = couplers
    p.film_render.halation.active = halation
    p.film_render.grain.active = False
    p.film_render.glare.active = glare
    p.print_render.glare.active = glare
    if not gamut:
        p.io.output_gamut_compress = OutputGamutCompressSpec(algorithm='off')
    if not sharpen:
        p.scanner.unsharp_mask = (0.0, 0.0)
    p.scanner.white_correction = True
    p.scanner.black_correction = True
    p.scanner.white_level = 1.0
    p.io.scan_film = slide
    if diffusion:
        p.camera.diffusion_filter.active = True
        p.camera.diffusion_filter.filter_family = diffusion
        p.camera.diffusion_filter.strength = 0.5
    return np.clip(simulate(img, p), 0, 1)


def ours(img, film, paper, out_dir, slide=False, couplers=True, halation=False, glare=False, gamut=False,
         sharpen=False, diffusion=None, extra=()):
    h, w = img.shape[:2]
    src, dst = os.path.join(out_dir, 'in.pfm'), os.path.join(out_dir, 'out.ppm')
    with open(src, 'wb') as f:
        f.write(b'PF\n%d %d\n-1.0\n' % (w, h))
        f.write(img[::-1].astype('<f4').tobytes())
    sets = {'film': film, 'couplers': int(couplers), 'halation': int(halation), 'grain': 0,
            'stage gamut': int(gamut)}
    if slide:
        sets['stage print'] = 0
    else:
        sets['paper'] = paper
    if not glare:
        sets['glare'] = 0
    if not sharpen:
        sets['stage scan sharp'] = 0
    if diffusion:
        sets['stage diffusion'] = DIFFUSION[diffusion]
        sets['stage diff amt'] = 0.5
    args = [a for k, v in sets.items() for a in ('--set', '%s=%g' % (k, v))] + list(extra)
    cmd = [CLI, '--filmsim', os.path.join(LUTS, 'filmsim.lut'), '--spectra', os.path.join(LUTS, 'spectra-em.lut'),
           '--in', src, '--out', dst, '--long-edge', str(max(w, h))] + args
    run = subprocess.run(cmd, capture_output=True)
    if run.returncode:
        raise RuntimeError(run.stderr.decode('utf-8', 'replace'))
    data = open(dst, 'rb').read()
    head = data.split(b'\n', 3)
    ww, hh = map(int, head[1].split())
    return np.frombuffer(head[3], np.uint8).reshape(hh, ww, 3).astype(float) / 255


# ----- measures --------------------------------------------------------------------------------

def lab(srgb):
    return colour.XYZ_to_Lab(colour.RGB_to_XYZ(srgb, 'sRGB', apply_cctf_decoding=True), [0.3127, 0.3290])


def patches(img):
    m = 30
    return np.array([img[y * PATCH + m:(y + 1) * PATCH - m, x * PATCH + m:(x + 1) * PATCH - m].reshape(-1, 3).mean(0)
                     for y in range(4) for x in range(6)])


def profile(img):
    h, w = img.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w]
    r = np.hypot(xx - w / 2, yy - h / 2).astype(int)
    return [img[r == rr].mean(0).round(4).tolist() for rr in RADII]


class Runner:
    def __init__(self, out_dir):
        self.out_dir = out_dir
        self.log = open(os.path.join(out_dir, 'results.jsonl'), 'a')

    def write(self, rec):
        self.log.write(json.dumps(rec) + '\n')
        self.log.flush()

    def chart(self, name, **kw):
        c = chart()
        la, lb = lab(patches(reference(c, **kw))), lab(patches(ours(c, out_dir=self.out_dir, **kw)))
        de = colour.delta_E(la, lb, method='CIE 2000')
        self.write({'test': name, 'dE_mean': float(de.mean()), 'dE_max': float(de.max()), 'dE': de.round(2).tolist(),
                    'L_ref': la[:, 0].round(1).tolist(), 'L_ours': lb[:, 0].round(1).tolist(),
                    'C_ref': np.hypot(la[:, 1], la[:, 2]).round(1).tolist(),
                    'C_ours': np.hypot(lb[:, 1], lb[:, 2]).round(1).tolist()})
        print('%-32s dE %.2f  max %.2f' % (name, de.mean(), de.max()), flush=True)

    def spot(self, name, **kw):
        s = spot()
        self.write({'test': name, 'radii': RADII, 'ref': profile(reference(s, **kw)),
                    'ours': profile(ours(s, out_dir=self.out_dir, **kw))})
        print(name, 'done', flush=True)

    def edge(self, name, **kw):
        e = edge()
        a = reference(e, **kw)[400, 560:640].mean(1)
        b = ours(e, out_dir=self.out_dir, **kw)[400, 560:640].mean(1)
        self.write({'test': name, 'ref': a.round(4).tolist(), 'ours': b.round(4).tolist()})
        print(name, 'done', flush=True)


def main():
    out_dir, which = sys.argv[1], set(sys.argv[2:])
    os.makedirs(out_dir, exist_ok=True)
    run = Runner(out_dir)
    neg = dict(film=4, paper=PORTRA_ENDURA)
    if 'color' in which:
        for f, p in ((4, 3), (0, 1), (2, 5), (5, 3), (10, 5), (12, 6), (14, 6), (8, 0)):
            run.chart('color film%d paper%d' % (f, p), film=f, paper=p)
        for f in SLIDES:
            run.chart('color slide%d' % f, film=f, paper=None, slide=True)
    if 'couplers' in which:
        run.chart('nocouplers film4 paper3', couplers=False, **neg)
        run.chart('nocouplers slide19', film=19, paper=None, slide=True, couplers=False)
    if 'gamut' in which:
        run.chart('gamut film4 paper3', gamut=True, **neg)
        run.chart('gamut slide19', film=19, paper=None, slide=True, gamut=True)
    if 'glare' in which:
        run.chart('glare ours-off', glare=True, **neg)
        run.chart('glare ours-0.03', glare=True, extra=('--set', 'glare=0.03'), **neg)
    if 'halation' in which:
        run.spot('halation film4 paper3', halation=True, **neg)
        run.spot('halation film14 paper6', film=14, paper=KODAK_2383, halation=True)
    if 'diffusion' in which:
        for family in DIFFUSION:
            run.spot('diffusion %s' % family, diffusion=family, **neg)
    if 'sharpen' in which:
        run.edge('sharpen', sharpen=True, **neg)
        run.edge('nosharpen', sharpen=False, **neg)
    if 'films' in which:
        for f in range(len(STOCKS)):
            if f in SLIDES:
                run.chart('films slide%d' % f, film=f, paper=None, slide=True)
            else:
                paper = KODAK_2383 if f in VISION3 else PORTRA_ENDURA
                run.chart('films film%d paper%d' % (f, paper), film=f, paper=paper)
    if 'papers' in which:
        for p in range(len(PAPERS)):
            run.chart('papers film4 paper%d' % p, film=4, paper=p)


if __name__ == '__main__':
    main()
