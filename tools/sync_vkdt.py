"""Import vkdt's filmsim module into the engine tree.

Copies the filmsim compute shaders and every header they include, applies the
mobile patch (explicit rgba16f on write-only storage images, so devices without
shaderStorageImageWriteWithoutFormat work), copies the auto white-balance
tables, and unpacks the spektrafilm profile LUT into the Android assets.

    python tools/sync_vkdt.py [path/to/vkdt]

The vkdt checkout itself is never modified.
"""
from __future__ import annotations

import io
import lzma
import re
import subprocess
import sys
import tarfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VKDT = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "third_party" / "vkdt"
MODULES = VKDT / "src" / "pipe" / "modules"
FILMSIM = MODULES / "filmsim"
SHADER_OUT = ROOT / "engine" / "shaders" / "vkdt"
TABLE_OUT = ROOT / "engine" / "src" / "vkdt"
ASSET_OUT = ROOT / "android" / "app" / "src" / "main" / "assets" / "filmsim"

INCLUDE = re.compile(r'^\s*#include\s+"([^"]+)"', re.M)
# Every filmsim output is declared "rgba" "f16" in main.c.
WRITE_IMAGE = re.compile(r"layout\((set = 1, binding = \d+)\) uniform writeonly restrict image2D")


def resolve(name: str, current: Path) -> Path:
    """Match glslc's search order: including file's folder, then -I modules."""
    for base in (current.parent, MODULES):
        candidate = (base / name).resolve()
        if candidate.is_file():
            return candidate
    raise FileNotFoundError(f"{name} included from {current}")


def collect() -> list[Path]:
    pending = sorted(FILMSIM.glob("*.comp"))
    seen: set[Path] = set()
    while pending:
        path = pending.pop()
        if path in seen:
            continue
        seen.add(path)
        for name in INCLUDE.findall(path.read_text(encoding="utf-8")):
            pending.append(resolve(name, path))
    return sorted(seen)


def main() -> None:
    revision = subprocess.run(
        ["git", "-C", str(VKDT), "rev-parse", "HEAD"], capture_output=True, text=True, check=True
    ).stdout.strip()

    files = collect()
    for source in files:
        target = SHADER_OUT / source.relative_to(MODULES)
        target.parent.mkdir(parents=True, exist_ok=True)
        text = source.read_text(encoding="utf-8")
        if source.suffix == ".comp":
            text = WRITE_IMAGE.sub(r"layout(\1, rgba16f) uniform writeonly restrict image2D", text)
        target.write_text(text, encoding="utf-8", newline="\n")

    TABLE_OUT.mkdir(parents=True, exist_ok=True)
    for name in ("wb.h", "pos_wb.h", "constants.h"):
        (TABLE_OUT / name).write_bytes((FILMSIM / name).read_bytes())

    ASSET_OUT.mkdir(parents=True, exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(lzma.decompress((VKDT / "src" / "filmsim.lut.xz").read_bytes()))) as tar:
        member = tar.getmember("filmsim.lut")
        if not member.isfile():
            raise ValueError("filmsim.lut is not a regular file")
        (ASSET_OUT / "filmsim.lut").write_bytes(tar.extractfile(member).read())

    for licence in ("LICENCE.bsd2", "LICENCE.gpl3"):
        (SHADER_OUT / licence).write_bytes((VKDT / licence).read_bytes())
    (SHADER_OUT / "REVISION").write_text(
        f"vkdt {revision}\n"
        "Copied by tools/sync_vkdt.py. Patch: write-only storage images declare rgba16f.\n"
        "filmsim implements spektrafilm by Andrea Volpato; profile data CC BY-SA 4.0.\n",
        encoding="utf-8",
    )
    print(f"vkdt {revision[:12]}: {len(files)} shader files, filmsim.lut -> {ASSET_OUT}")
    if not (ASSET_OUT / "spectra-em.lut").is_file():
        print("spectra-em.lut missing: build it with vkdt's src/tools/spec/mkspectra.c")


if __name__ == "__main__":
    main()
