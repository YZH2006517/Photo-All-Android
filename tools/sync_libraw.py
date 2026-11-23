"""Copy the parts of LibRaw the engine builds (its decoders, headers, licences) from a checkout
into engine/libraw. LibRaw is used under CDDL 1.0 (its dual licence's other half is LGPL 2.1):
the copied files stay as they are, under their own licence; see engine/libraw/README.md.

usage: python tools/sync_libraw.py [path/to/LibRaw checkout]   (default third_party/LibRaw)
"""
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "third_party" / "LibRaw"
OUT = ROOT / "engine" / "libraw"

if not (SRC / "libraw" / "libraw.h").exists():
    sys.exit(f"no LibRaw checkout at {SRC}")
for keep in ("libraw", "src", "internal"):
    shutil.rmtree(OUT / keep, ignore_errors=True)
    shutil.copytree(SRC / keep, OUT / keep, ignore=shutil.ignore_patterns("*.o", "*.obj"))
for name in ("COPYRIGHT", "LICENSE.CDDL", "LICENSE.LGPL"):
    shutil.copy2(SRC / name, OUT / name)
rev = subprocess.run(["git", "-C", str(SRC), "describe", "--tags", "--always"], capture_output=True, text=True).stdout.strip()
(OUT / "REVISION").write_text(f"LibRaw {rev}\nCopied by tools/sync_libraw.py, unmodified. Used under CDDL 1.0.\n", encoding="utf-8")
print("LibRaw", rev, "->", OUT)
