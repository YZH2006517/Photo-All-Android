"""Convert the CC0 LUTs from editor-hub.com into app LUT assets.

Editor Hub releases its Originals, its Movie looks and the FreshLUTs community picks
it hosts as CC0 ("use them anywhere, no strings"). Two community picks carry a
third party's notice (Celluloïd: "(C) 2018 CELLULOÏD"; CineStill 800T: made with
IWLTBAP's generator) and are left out. Each .cube (Rec.709 / sRGB in and out) is
resampled trilinearly to 33^3 RGB8 (red fastest), the engine's creative LUT layout.

The entries go after the CCD cameras and ahead of the film LUTs; the manifest's
"leading" count says how many lead the films (stored LUT choices shift by it).

    python tools/convert_editorhub.py [path]   (default third_party/editorhub)
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
SOURCE = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "third_party" / "editorhub"
OUT = ROOT / "android" / "app" / "src" / "main" / "assets" / "luts"
SIZE = 33

MOVIE = "电影风格"
ORIGINAL = "调色 · EditorHub"
COMMUNITY = "调色 · 社区"
# file stem -> (group, label)
LUTS = {
    "eh-movie-arrakis": (MOVIE, "沙丘 Arrakis"),
    "eh-movie-code-green": (MOVIE, "代码绿 Code Green"),
    "eh-movie-wasteland": (MOVIE, "荒原 Wasteland"),
    "eh-movie-night-drive": (MOVIE, "夜驾 Night Drive"),
    "eh-movie-miami-blue": (MOVIE, "迈阿密蓝 Miami Blue"),
    "eh-movie-stairway": (MOVIE, "阶梯 Stairway"),
    "eh-movie-montmartre": (MOVIE, "蒙马特 Montmartre"),
    "eh-movie-pastel-hotel": (MOVIE, "粉彩酒店 Pastel Hotel"),
    "eh-movie-twilight-musical": (MOVIE, "暮光歌舞 Twilight Musical"),
    "eh-movie-dreamhouse": (MOVIE, "梦幻屋 Dreamhouse"),
    "eh-movie-midsummer": (MOVIE, "仲夏 Midsummer"),
    "eh-movie-rain-city": (MOVIE, "雨城 Rain City"),
    "eh-movie-crimson-hallway": (MOVIE, "猩红走廊 Crimson Hallway"),
    "eh-movie-dons-office": (MOVIE, "广告人办公室 Don's Office"),
    "eh-movie-frontier-cold": (MOVIE, "冷峻边疆 Frontier Cold"),
    "eh-movie-dust-bowl": (MOVIE, "尘暴 Dust Bowl"),
    "eh-movie-amber-haze": (MOVIE, "琥珀雾 Amber Haze"),
    "eh-movie-coral-soft": (MOVIE, "柔珊瑚 Coral Soft"),
    "eh-movie-red-accent": (MOVIE, "独留红色 Red Accent"),
    "eh-movie-bleach-bypass": (MOVIE, "漂白 Bleach Bypass"),
    "eh-blockbuster": (ORIGINAL, "大片 Blockbuster"),
    "eh-deep-teal": (ORIGINAL, "深青 Deep Teal"),
    "eh-midnight": (ORIGINAL, "午夜 Midnight"),
    "eh-neon-punch": (ORIGINAL, "霓虹 Neon Punch"),
    "eh-matte-fade": (ORIGINAL, "哑光褪色 Matte Fade"),
    "eh-golden-hour": (ORIGINAL, "黄金时刻 Golden Hour"),
    "eh-moody-forest": (ORIGINAL, "森林情绪 Moody Forest"),
    "eh-crisp-clean": (ORIGINAL, "清透 Crisp Clean"),
    "fresh-blue-pink": (COMMUNITY, "蓝粉 Blue & Pink"),
    "fresh-cinematic-green": (COMMUNITY, "电影绿 Cinematic Green"),
    "fresh-peaky-blinders": (COMMUNITY, "浴血黑帮 Peaky Blinders"),
    "fresh-severn": (COMMUNITY, "塞文 Severn"),
    "fresh-teal-rec709": (COMMUNITY, "青调 Teal"),
    "fresh-the-woods": (COMMUNITY, "林间 The Woods"),
    "fresh-vintage-warmth": (COMMUNITY, "复古暖调 Vintage Warmth"),
}
GROUPS = {MOVIE, ORIGINAL, COMMUNITY}


def read_cube(path: Path):
    n, lo, hi, values = 0, np.zeros(3), np.ones(3), []
    for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
        t = line.strip()
        if not t or t.startswith("#"):
            continue
        if t[0].isalpha():
            k = t.split()
            if k[0] == "LUT_3D_SIZE":
                n = int(k[1])
            elif k[0] == "DOMAIN_MIN":
                lo = np.array(list(map(float, k[1:4])))
            elif k[0] == "DOMAIN_MAX":
                hi = np.array(list(map(float, k[1:4])))
            continue
        values.append(list(map(float, t.split()[:3])))
    lut = np.array(values, dtype=np.float64)
    assert n and len(lut) == n ** 3, f"{path.name}: {len(lut)} points for size {n}"
    return lut.reshape(n, n, n, 3), n, lo, hi


def resample(lut, n, lo, hi):
    g = np.linspace(0, 1, SIZE)
    b, gg, r = np.meshgrid(g, g, g, indexing="ij")
    x = np.stack([r, gg, b], axis=-1).reshape(-1, 3)
    p = np.clip((x - lo) / (hi - lo), 0, 1) * (n - 1)
    i0 = np.minimum(np.floor(p).astype(int), n - 2)
    f = p - i0
    out = np.zeros_like(x)
    for dr in (0, 1):
        for dg in (0, 1):
            for db in (0, 1):
                w = (f[:, 0] if dr else 1 - f[:, 0]) * (f[:, 1] if dg else 1 - f[:, 1]) * (f[:, 2] if db else 1 - f[:, 2])
                out += w[:, None] * lut[i0[:, 2] + db, i0[:, 1] + dg, i0[:, 0] + dr]
    return np.round(np.clip(out, 0, 1) * 255).astype(np.uint8)


def main():
    (OUT / "editorhub").mkdir(parents=True, exist_ok=True)
    entries = []
    for stem, (group, label) in LUTS.items():
        data = resample(*read_cube(SOURCE / "cube" / f"{stem}.cube"))
        (OUT / "editorhub" / f"{stem}.rgb").write_bytes(data.tobytes())
        entries.append({"id": f"editorhub/{stem}", "label": label, "group": group})
    (OUT / "editorhub" / "LICENSE").write_text(
        "Editor Hub Originals, Movie looks and the FreshLUTs community picks hosted at\n"
        "https://editor-hub.com/assets/luts are released under CC0 1.0 (public domain\n"
        "dedication): \"EditorHub Originals, Movie looks, Community picks and Surprise LUTs\n"
        "are CC0\". Converted to 33^3 RGB8 by tools/convert_editorhub.py.\n", encoding="utf-8")
    path = OUT / "manifest.json"
    manifest = json.loads(path.read_text(encoding="utf-8"))
    luts = [e for e in manifest["luts"] if e.get("group") not in GROUPS]
    ccd = [e for e in luts if e["id"].startswith("digicam/")]
    rest = [e for e in luts if not e["id"].startswith("digicam/")]
    manifest["luts"] = ccd + entries + rest
    manifest["leading"] = len(ccd) + len(entries)
    manifest["editorhub_source"] = "https://editor-hub.com/assets/luts, CC0"
    path.write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")
    print(f"{len(entries)} Editor Hub LUTs; {manifest['leading']} lead the films; {len(manifest['luts'])} in all")


if __name__ == "__main__":
    main()
