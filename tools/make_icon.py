"""Draws the launcher icon: a 135 film cartridge with an amber label, its leader
curling out of the light-trap lip, on a dark tile. Original artwork for this app.

The drawing is SVG (gradients, noise textures for brushed metal and paper), rendered
by headless Edge/Chrome and scaled with Pillow into Android adaptive-icon layers:

    res/mipmap-*/ic_launcher_foreground.png   the cartridge and film (transparent)
    res/mipmap-*/ic_launcher_background.png   the dark tile
    res/drawable/ic_launcher_monochrome.xml   a silhouette for themed icons (Android 13+)

plus docs/icon/icon_1024.png (the full icon, for the store and README).

Canvas: the adaptive icon's 108 x 108 dp, drawn at 1080 x 1080 (10 units per dp).
Launchers show about the centre 72 dp and keep the centre 66 dp circle from any mask
(round on Pixel, squircles on Xiaomi / Samsung / OPPO): the cartridge stays inside.

    python tools/make_icon.py
"""
import os
import random
import shutil
import subprocess
import sys
import tempfile
import time

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")
DOCS = os.path.join(ROOT, "docs", "icon")
BROWSERS = [r"C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
            r"C:/Program Files/Google/Chrome/Application/chrome.exe", "google-chrome", "chromium"]
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

# Placement of the drawing (natural coordinates below) in the 1080 canvas.
SCALE = 0.84
CENTRE = ((306 + 848) / 2, (242 + 874) / 2)   # of the drawing's bounds
TARGET = (540, 540)   # where it lands: the tile's centre
PLACE = f"translate({TARGET[0]} {TARGET[1]}) scale({SCALE}) translate({-CENTRE[0]} {-CENTRE[1]})"


def bez(p0, p1, p2, p3, t):
    u = 1 - t
    return tuple(u ** 3 * a + 3 * u * u * t * b + 3 * u * t * t * c + t ** 3 * d for a, b, c, d in zip(p0, p1, p2, p3))


def lerp(a, b, s):
    return (a[0] + (b[0] - a[0]) * s, a[1] + (b[1] - a[1]) * s)


# The cartridge's axis, cap half-width and light-trap lip (outer edge flush with the caps).
CX = 470
CAP_R = 164
LIP = (598, CX + CAP_R)

# The leader: top and bottom edges as cubic curves out of the lip, turning away at the end.
TOP = ((LIP[1] - 4, 408), (735, 366), (792, 420), (848, 442))
BOTTOM = ((LIP[1] - 4, 768), (732, 762), (792, 722), (828, 692))


def strip_path():
    d = [f"M{TOP[0][0]},{TOP[0][1]} C{TOP[1][0]},{TOP[1][1]} {TOP[2][0]},{TOP[2][1]} {TOP[3][0]},{TOP[3][1]}"]
    # the cut end: a slight tongue
    d.append(f"Q{(TOP[3][0] + BOTTOM[3][0]) / 2 + 14},{(TOP[3][1] + BOTTOM[3][1]) / 2} {BOTTOM[3][0]},{BOTTOM[3][1]}")
    d.append(f"C{BOTTOM[2][0]},{BOTTOM[2][1]} {BOTTOM[1][0]},{BOTTOM[1][1]} {BOTTOM[0][0]},{BOTTOM[0][1]} Z")
    return " ".join(d)


def perforations():
    """Holes in two rows that follow the curl (narrower as the film turns away)."""
    holes = []
    t = 0.07
    while t < 0.9:
        t2 = t + 0.05
        for s0, s1 in ((0.055, 0.12), (0.88, 0.945)):
            a, b = bez(*TOP, t), bez(*TOP, t2)
            c, d = bez(*BOTTOM, t), bez(*BOTTOM, t2)
            pts = [lerp(a, c, s0), lerp(b, d, s0), lerp(b, d, s1), lerp(a, c, s1)]
            holes.append("M" + " L".join(f"{x:.1f},{y:.1f}" for x, y in pts) + " Z")
        t += 0.1
    return holes


def foreground_svg():
    """The cartridge straight on, at eye level, lit from the top left. Every part is centred
    on the body's axis CX; the lip's outer edge lines up with the caps."""
    rnd = random.Random(7)
    holes = perforations()
    cx, rx = CX, 140                   # body: axis and half width (label 330..610)
    crx = CAP_R                        # caps: half width (308..634)
    post = 44                          # spool post: half width
    top0, top1 = 296, 352              # top cap
    bot0, bot1 = 800, 856              # bottom cap
    specks = "".join(f'<circle cx="{rnd.uniform(336, 600):.0f}" cy="{rnd.uniform(370, 790):.0f}" r="{rnd.uniform(0.8, 2.4):.1f}" fill="#fff6dc" opacity="{rnd.uniform(0.12, 0.35):.2f}"/>' for _ in range(40))
    scratches = "".join(
        f'<path d="M{x:.0f},{y:.0f} l{rnd.uniform(-26, 26):.0f},{rnd.uniform(-2, 2):.0f}" stroke="#e8e2d4" stroke-width="{rnd.uniform(0.6, 1.3):.1f}" opacity="{rnd.uniform(0.12, 0.3):.2f}"/>'
        for x, y in [(rnd.uniform(cx - crx + 12, cx + crx - 12), rnd.uniform(top0 + 8, top1 - 8)) for _ in range(9)]
        + [(rnd.uniform(cx - crx + 12, cx + crx - 12), rnd.uniform(bot0 + 8, bot1 - 8)) for _ in range(9)])
    film_marks = "".join(
        f'<path d="M{x:.0f},{y:.0f} l{rnd.uniform(-14, 14):.0f},{rnd.uniform(-2, 8):.0f}" stroke="#f3c79a" stroke-width="1" opacity="{rnd.uniform(0.2, 0.45):.2f}"/>'
        for x, y in [(rnd.uniform(690, 820), rnd.uniform(470, 700)) for _ in range(10)])
    lip0, lip1 = LIP
    ribs = "".join(f'<rect x="{lip0 + 4}" y="{y}" width="{lip1 - lip0 - 8}" height="3" fill="#2a2725" opacity="0.7"/>' for y in range(366, 792, 14))
    holes_d = " ".join(holes)

    def cap(y0, y1, upper):
        """A cap from the front: a short drum; light from above on its upper edge."""
        h = y1 - y0
        return f"""
  <rect x="{cx - crx}" y="{y0}" width="{2 * crx}" height="{h}" rx="9" fill="url(#metal)"/>
  <rect x="{cx - crx}" y="{y0}" width="{2 * crx}" height="{h}" rx="9" fill="#fff" filter="url(#brushed)"/>
  <rect x="{cx - crx}" y="{y0}" width="{2 * crx}" height="{h}" rx="9" fill="url(#cap-light)"/>
  <rect x="{cx - crx + 6}" y="{y0 + 1.5}" width="{2 * crx - 12}" height="2.5" rx="1.2" fill="#e4dfd5" opacity="{0.6 if upper else 0.4}"/>
  <rect x="{cx - crx}" y="{y0 + h * 0.62:.0f}" width="{2 * crx}" height="2" fill="#000" opacity="0.4"/>
  <rect x="{cx - crx}" y="{y0 + h * 0.62 + 2:.0f}" width="{2 * crx}" height="1.4" fill="#cfcac0" opacity="0.18"/>
  <rect x="{cx - crx}" y="{y1 - 3}" width="{2 * crx}" height="3" rx="1.5" fill="#000" opacity="0.5"/>"""

    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="1080" height="1080" viewBox="0 0 1080 1080">
<defs>
  <!-- blackened steel, key light from the left, a cool rim light on the right -->
  <linearGradient id="metal" x1="{cx - crx}" x2="{cx + crx}" gradientUnits="userSpaceOnUse">
    <stop offset="0" stop-color="#101012"/><stop offset="0.07" stop-color="#2e2d2c"/><stop offset="0.22" stop-color="#6c6964"/>
    <stop offset="0.3" stop-color="#9c9890"/><stop offset="0.38" stop-color="#55534f"/><stop offset="0.7" stop-color="#262524"/>
    <stop offset="0.92" stop-color="#18181a"/><stop offset="0.97" stop-color="#5d6670"/><stop offset="1" stop-color="#1a1b1d"/>
  </linearGradient>
  <linearGradient id="cap-light" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="#fff" stop-opacity="0.16"/><stop offset="0.5" stop-color="#fff" stop-opacity="0"/>
    <stop offset="1" stop-color="#000" stop-opacity="0.35"/>
  </linearGradient>
  <linearGradient id="post" x1="{cx - post}" x2="{cx + post}" gradientUnits="userSpaceOnUse">
    <stop offset="0" stop-color="#0e0e0f"/><stop offset="0.25" stop-color="#4a4845"/><stop offset="0.36" stop-color="#7d7a74"/>
    <stop offset="0.5" stop-color="#2f2e2d"/><stop offset="0.9" stop-color="#141414"/><stop offset="1" stop-color="#3b4046"/>
  </linearGradient>
  <linearGradient id="label" x1="{cx - rx}" x2="{cx + rx}" gradientUnits="userSpaceOnUse">
    <stop offset="0" stop-color="#9a5a0b"/><stop offset="0.1" stop-color="#d48c22"/><stop offset="0.3" stop-color="#f7be4d"/>
    <stop offset="0.55" stop-color="#eda634"/><stop offset="0.85" stop-color="#bf7616"/><stop offset="1" stop-color="#7e4708"/>
  </linearGradient>
  <linearGradient id="spec" x1="{cx - rx}" x2="{cx + rx}" gradientUnits="userSpaceOnUse">
    <stop offset="0.2" stop-color="#fff" stop-opacity="0"/><stop offset="0.27" stop-color="#fff8e6" stop-opacity="0.34"/>
    <stop offset="0.33" stop-color="#fff" stop-opacity="0"/><stop offset="0.9" stop-color="#ffd9a0" stop-opacity="0"/>
    <stop offset="0.96" stop-color="#ffd9a0" stop-opacity="0.12"/><stop offset="1" stop-color="#ffd9a0" stop-opacity="0"/>
  </linearGradient>
  <linearGradient id="under-cap" x1="0" y1="{top1}" x2="0" y2="{top1 + 40}" gradientUnits="userSpaceOnUse">
    <stop offset="0" stop-color="#000" stop-opacity="0.55"/><stop offset="1" stop-color="#000" stop-opacity="0"/>
  </linearGradient>
  <linearGradient id="over-cap" x1="0" y1="{bot0 - 30}" x2="0" y2="{bot0}" gradientUnits="userSpaceOnUse">
    <stop offset="0" stop-color="#000" stop-opacity="0"/><stop offset="1" stop-color="#000" stop-opacity="0.3"/>
  </linearGradient>
  <linearGradient id="film" x1="{lip1}" x2="850" gradientUnits="userSpaceOnUse">
    <stop offset="0" stop-color="#2a1206"/><stop offset="0.1" stop-color="#7a3a18"/><stop offset="0.34" stop-color="#bf6731"/>
    <stop offset="0.5" stop-color="#e9a265"/><stop offset="0.58" stop-color="#d07c40"/><stop offset="0.8" stop-color="#7f3b17"/><stop offset="1" stop-color="#3e1b09"/>
  </linearGradient>
  <linearGradient id="film-light" x1="0" y1="380" x2="0" y2="780" gradientUnits="userSpaceOnUse">
    <stop offset="0" stop-color="#fff" stop-opacity="0.18"/><stop offset="0.5" stop-color="#fff" stop-opacity="0"/><stop offset="1" stop-color="#000" stop-opacity="0.3"/>
  </linearGradient>
  <linearGradient id="velvet" x1="{lip0}" x2="{lip1}" gradientUnits="userSpaceOnUse">
    <stop offset="0" stop-color="#030303"/><stop offset="0.6" stop-color="#1b1918"/><stop offset="1" stop-color="#0a0909"/>
  </linearGradient>
  <filter id="brushed" x="0" y="0" width="100%" height="100%">
    <feTurbulence type="fractalNoise" baseFrequency="0.004 0.6" numOctaves="2" seed="3"/>
    <feColorMatrix values="0 0 0 0 1  0 0 0 0 1  0 0 0 0 1  0 0 0 0.14 0"/>
    <feComposite in2="SourceGraphic" operator="in"/>
  </filter>
  <filter id="paper" x="0" y="0" width="100%" height="100%">
    <feTurbulence type="fractalNoise" baseFrequency="0.85" numOctaves="3" seed="9"/>
    <feColorMatrix values="0 0 0 0 0.25  0 0 0 0 0.15  0 0 0 0 0.05  0 0 0 0.22 0"/>
    <feComposite in2="SourceGraphic" operator="in"/>
  </filter>
  <filter id="grain" x="0" y="0" width="100%" height="100%">
    <feTurbulence type="fractalNoise" baseFrequency="0.7 0.12" numOctaves="2" seed="5"/>
    <feColorMatrix values="0 0 0 0 0.1  0 0 0 0 0.04  0 0 0 0 0  0 0 0 0.3 0"/>
    <feComposite in2="SourceGraphic" operator="in"/>
  </filter>
  <filter id="shadow" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="24"/></filter>
  <filter id="soft" x="-20%" y="-50%" width="140%" height="200%"><feGaussianBlur stdDeviation="6"/></filter>
  <clipPath id="body"><rect x="{cx - rx}" y="{top1}" width="{2 * rx}" height="{bot0 - top1}"/></clipPath>
  <clipPath id="strip"><path d="{strip_path()}"/></clipPath>
</defs>
<g transform="{PLACE}">
  <!-- shadow on the tile, thrown down and to the right -->
  <g opacity="0.65" filter="url(#shadow)" transform="translate(22 30)">
    <rect x="{cx - crx}" y="240" width="{2 * crx}" height="640" rx="20" fill="#000"/>
    <path d="{strip_path()}" fill="#000"/>
  </g>

  <!-- the leader: base colour, light from above, grain, the lip's shadow, holes -->
  <path d="{strip_path()}" fill="url(#film)"/>
  <path d="{strip_path()}" fill="url(#film-light)"/>
  <path d="{strip_path()}" fill="#fff" filter="url(#grain)"/>
  <g clip-path="url(#strip)">
    <rect x="{lip1 - 6}" y="380" width="34" height="420" fill="#000" opacity="0.45" filter="url(#soft)"/>
  </g>
  <path d="M{TOP[0][0]},{TOP[0][1] + 3} C{TOP[1][0]},{TOP[1][1] + 3} {TOP[2][0]},{TOP[2][1] + 3} {TOP[3][0]},{TOP[3][1] + 3}" stroke="#ffc896" stroke-width="2.4" fill="none" opacity="0.6"/>
  <path d="{holes_d}" fill="#120a05" stroke="#120a05" stroke-width="2" stroke-linejoin="round"/>
  {film_marks}

  <!-- the light-trap lip: its outer edge in line with the caps -->
  <rect x="{lip0}" y="{top1 - 4}" width="{lip1 - lip0}" height="{bot0 - top1 + 8}" fill="url(#velvet)"/>
  {ribs}
  <rect x="{lip1 - 4}" y="{top1}" width="3" height="{bot0 - top1}" fill="#3a3632" opacity="0.8"/>

  <!-- body: label, shading, wear -->
  <rect x="{cx - rx}" y="{top1}" width="{2 * rx}" height="{bot0 - top1}" fill="url(#label)"/>
  <rect x="{cx - rx}" y="{top1}" width="{2 * rx}" height="{bot0 - top1}" fill="#fff" filter="url(#paper)"/>
  <g clip-path="url(#body)">
    <text x="352" y="414" font-family="'JetBrains Mono', Consolas, monospace" font-weight="700" font-size="20" letter-spacing="5" fill="#5a3209" opacity="0.85">PHOTO ALL</text>
    <text x="350" y="542" font-family="Fraunces, Cambria, Georgia, serif" font-weight="800" font-size="128" fill="#1b130b" textLength="232" lengthAdjust="spacingAndGlyphs">Film</text>
    <text x="352" y="630" font-family="Fraunces, Cambria, Georgia, serif" font-weight="700" font-size="74" fill="#1b130b" textLength="238" lengthAdjust="spacingAndGlyphs">Camera</text>
    <text x="354" y="702" font-family="Fraunces, Cambria, Georgia, serif" font-weight="700" font-size="40" fill="#9a2511" textLength="150" lengthAdjust="spacingAndGlyphs">ISO 400</text>
    <rect x="352" y="730" width="104" height="22" fill="#b0301a"/>
    <rect x="456" y="730" width="40" height="22" fill="#cf4a1f"/>
    <rect x="496" y="730" width="90" height="22" fill="#e07a2c"/>
    <text x="354" y="784" font-family="'JetBrains Mono', Consolas, monospace" font-weight="700" font-size="15" letter-spacing="3" fill="#5a3209" opacity="0.8">135 · 36 EXP</text>
    {specks}
    <rect x="{cx - rx}" y="{top1}" width="{2 * rx}" height="{bot0 - top1}" fill="url(#spec)"/>
    <rect x="{cx - rx}" y="{top1}" width="{2 * rx}" height="40" fill="url(#under-cap)"/>
    <rect x="{cx - rx}" y="{bot0 - 30}" width="{2 * rx}" height="30" fill="url(#over-cap)"/>
  </g>

  <!-- spool post (above the top cap), caps, and the stub below -->
  <path d="M{cx - post},{top0} L{cx - post},250 Q{cx - post},242 {cx - post + 8},242 L{cx + post - 8},242 Q{cx + post},242 {cx + post},250 L{cx + post},{top0} Z" fill="url(#post)"/>
  <rect x="{cx - post + 6}" y="243" width="{2 * post - 12}" height="2" rx="1" fill="#c9c5bc" opacity="0.45"/>
  <rect x="{cx - 9}" y="242" width="18" height="9" fill="#0a0a0a" opacity="0.9"/>
  <rect x="{cx - post}" y="{top0 - 8}" width="{2 * post}" height="8" fill="#000" opacity="0.35"/>
  {cap(top0, top1, True)}
  {cap(bot0, bot1, False)}
  <rect x="{cx - 24}" y="{bot1}" width="48" height="18" rx="4" fill="url(#post)"/>
  {scratches}
</g>
</svg>"""


def background_svg():
    return '''<svg xmlns="http://www.w3.org/2000/svg" width="1080" height="1080" viewBox="0 0 1080 1080">
<defs>
  <radialGradient id="bg" cx="0.36" cy="0.26" r="0.95">
    <stop offset="0" stop-color="#2e2b27"/><stop offset="0.55" stop-color="#151413"/><stop offset="1" stop-color="#090908"/>
  </radialGradient>
  <filter id="tex" x="0" y="0" width="100%" height="100%">
    <feTurbulence type="fractalNoise" baseFrequency="0.9" numOctaves="3" seed="2"/>
    <feColorMatrix values="0 0 0 0 1  0 0 0 0 1  0 0 0 0 1  0 0 0 0.05 0"/>
    <feComposite in2="SourceGraphic" operator="in"/>
  </filter>
</defs>
<rect width="1080" height="1080" fill="url(#bg)"/>
<rect width="1080" height="1080" fill="#fff" filter="url(#tex)"/>
</svg>'''


def page(svg, size, rounded=False):
    inner = svg if not rounded else f'<div style="width:{size}px;height:{size}px;border-radius:{size * 0.225:.0f}px;overflow:hidden;position:relative">{svg}</div>'
    return f'''<!doctype html><html><head><meta charset="utf-8">
<link href="https://fonts.googleapis.com/css2?family=Fraunces:opsz,wght@9..144,700;9..144,800&family=JetBrains+Mono:wght@700&display=block" rel="stylesheet">
<style>html,body{{margin:0;background:transparent}} svg{{display:block;width:{size}px;height:{size}px}}
.stack{{position:relative;width:{size}px;height:{size}px}} .stack svg{{position:absolute;left:0;top:0}}</style></head>
<body>{inner}</body></html>'''


def render(html, out, size, work):
    browser = next((b for b in BROWSERS if shutil.which(b) or os.path.exists(b)), None)
    if browser is None:
        sys.exit("needs Edge or Chrome for rendering")
    path = os.path.join(work, os.path.basename(out) + ".html")
    open(path, "w", encoding="utf-8").write(html)
    profile = tempfile.mkdtemp(prefix="icon-browser-")
    subprocess.run([browser, "--headless=new", f"--user-data-dir={profile}", "--no-first-run", "--disable-gpu",
                    "--hide-scrollbars", "--default-background-color=00000000", "--force-device-scale-factor=1",
                    f"--window-size={size + 200},{size + 300}", "--virtual-time-budget=10000", f"--screenshot={out}",
                    "file:///" + path.replace("\\", "/")], check=True, capture_output=True, timeout=180)
    for _ in range(60):  # the browser may finish writing after it exits
        if os.path.exists(out) and os.path.getsize(out) > 0:
            time.sleep(0.3)
            Image.open(out).convert("RGBA").crop((0, 0, size, size)).save(out)
            return
        time.sleep(0.5)
    sys.exit(f"render failed: {out}")


def monochrome_xml():
    """Silhouette for themed icons, in dp: the cartridge and the leader with its holes."""
    def tx(x, y):
        return ((x - CENTRE[0]) * SCALE + TARGET[0]) / 10, ((y - CENTRE[1]) * SCALE + TARGET[1]) / 10

    def rect(x, y, w, h):
        (x0, y0), (x1, y1) = tx(x, y), tx(x + w, y + h)
        return f"M{x0:.2f},{y0:.2f}H{x1:.2f}V{y1:.2f}H{x0:.2f}Z"

    shapes = [rect(CX - 44, 242, 88, 56), rect(CX - CAP_R, 296, 2 * CAP_R, 56), rect(330, 352, LIP[1] - 330, 448), rect(CX - CAP_R, 800, 2 * CAP_R, 56), rect(CX - 24, 856, 48, 18)]
    # the leader, as a polygon along its edges
    top = [tx(*bez(*TOP, i / 16)) for i in range(17)]
    bottom = [tx(*bez(*BOTTOM, i / 16)) for i in range(17)]
    pts = top + bottom[::-1]
    strip = "M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in pts) + "Z"
    holes = []
    for h in perforations():
        coords = [tuple(map(float, p.strip("MLZ ").split(","))) for p in h.replace("M", "").replace("Z", "").split("L")]
        holes.append("M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in (tx(*c) for c in coords)) + "Z")
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/make_icon.py; edit the script, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#FF000000" android:pathData="{''.join(shapes)}"/>
    <path android:fillColor="#FF000000" android:fillType="evenOdd" android:pathData="{strip}{''.join(holes)}"/>
</vector>
'''


ADAPTIVE = '''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/make_icon.py. -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
'''


def main():
    work = tempfile.mkdtemp(prefix="icon-")
    fg_png = os.path.join(work, "fg.png")
    bg_png = os.path.join(work, "bg.png")
    full_png = os.path.join(work, "full.png")
    render(page(foreground_svg(), 1080), fg_png, 1080, work)
    render(page(background_svg(), 1080), bg_png, 1080, work)
    # the full icon as a launcher shows it (squircle), for the store and README
    stack = (f'<div class="stack" style="transform:scale(1.5);transform-origin:540px 540px">'
             f'{background_svg()}{foreground_svg()}</div>')
    render(page(stack, 1080, rounded=True), full_png, 1080, work)

    fg, bg = Image.open(fg_png).convert("RGBA"), Image.open(bg_png).convert("RGB")
    for name, px in DENSITIES.items():
        folder = os.path.join(RES, f"mipmap-{name}")
        os.makedirs(folder, exist_ok=True)
        fg.resize((px, px), Image.LANCZOS).save(os.path.join(folder, "ic_launcher_foreground.png"), optimize=True)
        bg.resize((px, px), Image.LANCZOS).save(os.path.join(folder, "ic_launcher_background.png"), optimize=True)
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        open(os.path.join(RES, "mipmap-anydpi-v26", name), "w", encoding="utf-8", newline="\n").write(ADAPTIVE)
    open(os.path.join(RES, "drawable", "ic_launcher_monochrome.xml"), "w", encoding="utf-8", newline="\n").write(monochrome_xml())
    for old in ("ic_launcher_background.xml", "ic_launcher_foreground.xml"):  # the vector layers are replaced
        p = os.path.join(RES, "drawable", old)
        if os.path.exists(p):
            os.remove(p)
    os.makedirs(DOCS, exist_ok=True)
    full = Image.open(full_png).convert("RGBA")
    full.resize((1024, 1024), Image.LANCZOS).save(os.path.join(DOCS, "icon_1024.png"))
    full.resize((512, 512), Image.LANCZOS).save(os.path.join(DOCS, "icon_512.png"))
    fg.save(os.path.join(DOCS, "foreground_1080.png"))
    print("icon written:", ", ".join(DENSITIES), "+ docs/icon")


if __name__ == "__main__":
    main()
