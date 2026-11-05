"""Convert the NatronGitHub/clut HaldCLUT presets into app LUT assets.

Each HaldCLUT PNG (level 8: 512x512 = a 64^3 cube, red fastest) is resampled
trilinearly to 33^3 and written as raw RGB8 (red fastest, then green, then
blue), the layout the engine's creative LUT stage reads. A manifest lists
id / label / group; the upstream README (credits, CC BY-SA 4.0) is copied
next to the data.

    python tools/convert_clut.py [path/to/clut]
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SOURCE = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "third_party" / "clut"
OUT = ROOT / "android" / "app" / "src" / "main" / "assets" / "luts"
SIZE = 33

GROUPS = {  # folder -> (order, display group)
    "negative_new": (0, "负片 · 现代"),
    "negative_old": (1, "负片 · 经典"),
    "negative_color": (2, "负片 · 彩色"),
    "colorslide": (3, "反转片"),
    "print": (4, "电影拷贝 · Juan Melara"),
    "instant_consumer": (5, "即时成像 · 消费级"),
    "instant_pro": (6, "即时成像 · 专业"),
    "bw": (7, "黑白"),
    "picturefx": (8, "创意 · PictureFX"),
    "various": (9, "其他"),
}


def label(stem: str) -> str:
    """fuji_160c_- -> "Fuji 160c −": words capitalised, +/- variant marks kept."""
    words = []
    for w in stem.split("_"):
        if w and set(w) <= set("+-"):
            words.append(w.replace("-", "−"))
        elif w:
            words.append(w.replace("-", " ").capitalize())
    return " ".join(words)


def resample(cube: np.ndarray) -> np.ndarray:
    """[b, g, r, 3] float cube of any edge -> SIZE^3 by trilinear interpolation."""
    n = cube.shape[0]
    x = np.linspace(0, n - 1, SIZE)
    i0 = np.minimum(np.floor(x).astype(int), n - 2)
    t = (x - i0).astype(np.float32)
    # Separable: interpolate along r (axis 2), then g (axis 1), then b (axis 0).
    c = cube[:, :, i0] * (1 - t)[None, None, :, None] + cube[:, :, i0 + 1] * t[None, None, :, None]
    c = c[:, i0] * (1 - t)[None, :, None, None] + c[:, i0 + 1] * t[None, :, None, None]
    c = c[i0] * (1 - t)[:, None, None, None] + c[i0 + 1] * t[:, None, None, None]
    return c


def main() -> None:
    revision = subprocess.run(["git", "-C", str(SOURCE), "rev-parse", "HEAD"],
                              capture_output=True, text=True, check=True).stdout.strip()
    OUT.mkdir(parents=True, exist_ok=True)
    entries = []
    for folder, (order, group) in sorted(GROUPS.items(), key=lambda kv: kv[1][0]):
        for png in sorted((SOURCE / folder).glob("*.png")):
            image = np.asarray(Image.open(png).convert("RGB"), dtype=np.float32) / 255.0
            edge = round(image.shape[0] * image.shape[1]) ** (1 / 3)
            n = int(round((image.shape[0] * image.shape[1]) ** (1 / 3)))
            if n ** 3 != image.shape[0] * image.shape[1]:
                print(f"skip {png.name}: not a HaldCLUT ({image.shape})")
                continue
            cube = image.reshape(n, n, n, 3)  # Hald order: r fastest, then g, then b
            data = np.clip(np.round(resample(cube) * 255.0), 0, 255).astype(np.uint8)
            lut_id = f"{folder}/{png.stem}"
            (OUT / folder).mkdir(exist_ok=True)
            (OUT / folder / f"{png.stem}.rgb").write_bytes(data.tobytes())
            entries.append({"id": lut_id, "label": label(png.stem), "group": group})
    (OUT / "manifest.json").write_text(json.dumps({
        "size": SIZE,
        "format": "RGB8, red fastest, sRGB encoded in and out",
        "source": f"https://github.com/NatronGitHub/clut ({revision})",
        "license": "CC BY-SA 4.0",
        "luts": entries,
    }, ensure_ascii=False, indent=1), encoding="utf-8")
    (OUT / "CREDITS.md").write_text((SOURCE / "README.md").read_text(encoding="utf-8") +
        f"\n\nConverted from HaldCLUT level 8 to 33^3 RGB8 (trilinear) by tools/convert_clut.py; source {revision}.\n",
        encoding="utf-8")
    print(f"{len(entries)} LUTs -> {OUT}")


if __name__ == "__main__":
    main()
