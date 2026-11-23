"""Builds report.zip for upload to SCF: app.py and scf_bootstrap (executable, LF endings)."""
import os
import zipfile

here = os.path.dirname(os.path.abspath(__file__))
out = os.path.join(here, "report.zip")
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for name, mode in (("app.py", 0o644), ("scf_bootstrap", 0o755)):
        data = open(os.path.join(here, name), "rb").read().replace(b"\r\n", b"\n")
        info = zipfile.ZipInfo(name)
        info.external_attr = (0o100000 | mode) << 16  # a regular file with its permissions
        info.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(info, data)
print("written", out)
