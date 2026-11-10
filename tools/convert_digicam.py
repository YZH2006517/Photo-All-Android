"""Convert Marcs Digicam Emulator's camera LUTs into app LUT assets.

marc-gronenberg/marcs-digicam-emulator (MIT) models eight CCD compact cameras of
2002-2009 (sensor colour, the hard digital highlight, tone curve and the maker's
style) and bakes each into a 37^3 LUT: input the scene-linear colour on a log2
shaper over 13 stops around 0.18 (-8 .. +5), output sRGB-encoded, u16 little-endian,
[b][g][r][rgb]. The plug-in feeds 8-bit sRGB photos to it decoded to linear light;
this does the same for every point of the app's 33^3 grid (sRGB in) and writes the
result as RGB8 (red fastest, sRGB out) for the viewfinder and the LUT list; the
original tables are copied beside them for engine/Digicam.kt, which adds the
emulator's spatial stages (noise, blooming, fringing, sharpening) to stills.

    python tools/convert_digicam.py [path/to/digicam]   (default third_party/digicam)
"""
from __future__ import annotations

import json
import shutil
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
SOURCE = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "third_party" / "digicam"
OUT = ROOT / "android" / "app" / "src" / "main" / "assets" / "luts"
SIZE = 33
N = 37
STOPS_LO, STOPS_HI = -8.0, 5.0
GROUP = "CCD 数码相机"

# key -> label (the emulator's neutral names: era and class, with its character)
CAMERAS = [
    ("pocket2002", "口袋机 2002 · CMYG 暖色"),
    ("prosumer2003", "准专业机 2003 · CMYG 柔和"),
    ("compact2004", "卡片机 2004 · 暖肤色浓红"),
    ("compact2005", "卡片机 2005 · 冷调偏品红"),
    ("compact2006", "卡片机 2006 · 鲜艳蓝绿"),
    ("compact2007", "卡片机 2007 · 中性微黄绿"),
    ("ultracompact2008", "超薄卡片机 2008 · 高反差"),
    ("prosumer2009", "准专业机 2009 · 干净克制"),
]


def srgb_to_linear(x):
    return np.where(x <= 0.04045, x / 12.92, ((x + 0.055) / 1.055) ** 2.4)


def sample(lut, p):
    """Trilinear lookup; p is (..., 3) positions in cells along r, g, b."""
    p = np.clip(p, 0, N - 1)
    i0 = np.minimum(np.floor(p).astype(int), N - 2)
    f = p - i0
    out = np.zeros(p.shape[:-1] + (3,))
    for dr in (0, 1):
        for dg in (0, 1):
            for db in (0, 1):
                w = (f[..., 0] if dr else 1 - f[..., 0]) * (f[..., 1] if dg else 1 - f[..., 1]) * \
                    (f[..., 2] if db else 1 - f[..., 2])
                out += w[..., None] * lut[i0[..., 2] + db, i0[..., 1] + dg, i0[..., 0] + dr]
    return out


def main():
    g = np.linspace(0, 1, SIZE)
    b, gg, r = np.meshgrid(g, g, g, indexing="ij")  # red fastest when flattened
    grid = np.stack([r, gg, b], axis=-1).reshape(-1, 3)
    lin = np.maximum(srgb_to_linear(grid), 1e-9)
    shaper = (np.log2(lin) - np.log2(0.18) - STOPS_LO) / (STOPS_HI - STOPS_LO)
    (OUT / "digicam").mkdir(parents=True, exist_ok=True)
    entries = []
    for key, label in CAMERAS:
        raw = np.frombuffer((SOURCE / "lut" / f"{key}.lut").read_bytes(), "<u2").reshape(N, N, N, 3)
        lut = raw.astype(np.float64) / 65535.0
        out = sample(lut, shaper * (N - 1))
        data = np.round(np.clip(out, 0, 1) * 255).astype(np.uint8)
        (OUT / "digicam" / f"{key}.rgb").write_bytes(data.tobytes())
        entries.append({"id": f"digicam/{key}", "label": label, "group": GROUP})
    shutil.copy(SOURCE / "LICENSE", OUT / "digicam" / "LICENSE")
    # the original 37^3 tables too: stills run the whole camera (noise, blooming, fringing,
    # noise reduction, sharpening) in engine/Digicam.kt, a port of the emulator's filter
    for key, _ in CAMERAS:
        shutil.copy(SOURCE / "lut" / f"{key}.lut", OUT / "digicam" / f"{key}.lut")
    manifest_path = OUT / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    rest = [e for e in manifest["luts"] if e.get("group") != GROUP]
    manifest["luts"] = entries + rest
    # the CCD cameras and whatever else already led the films (tools/convert_editorhub.py)
    manifest["leading"] = len(entries) + sum(1 for e in rest if e["id"].startswith("editorhub/"))
    manifest.pop("camera", None)
    revision = (SOURCE / "REVISION").read_text().strip() if (SOURCE / "REVISION").exists() else "?"
    manifest["digicam_source"] = f"https://github.com/marc-gronenberg/marcs-digicam-emulator ({revision}), MIT"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")
    print(f"{len(entries)} CCD cameras, {len(manifest['luts'])} LUTs in all")


if __name__ == "__main__":
    main()
