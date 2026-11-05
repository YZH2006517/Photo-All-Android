"""Draws the launcher icon: a lens seen head-on, gold ring and six aperture blades,
in front of a strip of film. One geometry, written as Android adaptive-icon vector
layers (background / foreground / monochrome) and as an SVG for previewing.

Canvas 108x108 dp (adaptive icon); launchers show about the centre 72x72 and keep
the centre 66 dp circle safe from any mask.

    python tools/make_icon.py [preview.svg]
"""
import math
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")
C = 54.0  # centre

GOLD = ("#F4D58A", "#B8862F")


def f(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")


def circle(r, cx=C, cy=C):
    return f"M{f(cx - r)},{f(cy)}a{f(r)},{f(r)} 0 1,0 {f(2 * r)},0a{f(r)},{f(r)} 0 1,0 {f(-2 * r)},0Z"


def ring(r_out, r_in):
    # even-odd hole: outer circle plus inner circle drawn the other way round
    inner = f"M{f(C - r_in)},{f(C)}a{f(r_in)},{f(r_in)} 0 1,1 {f(2 * r_in)},0a{f(r_in)},{f(r_in)} 0 1,1 {f(-2 * r_in)},0Z"
    return circle(r_out) + inner


def point(r, deg):
    a = math.radians(deg)
    return C + r * math.cos(a), C + r * math.sin(a)  # y down: positive angles turn clockwise


def arc(r, a0, a1):
    x0, y0 = point(r, a0)
    x1, y1 = point(r, a1)
    large = 1 if (a1 - a0) % 360 > 180 else 0
    return f"M{f(x0)},{f(y0)}A{f(r)},{f(r)} 0 {large},1 {f(x1)},{f(y1)}"


def rrect(x, y, w, h, r):
    return (f"M{f(x + r)},{f(y)}h{f(w - 2 * r)}a{f(r)},{f(r)} 0 0,1 {f(r)},{f(r)}v{f(h - 2 * r)}"
            f"a{f(r)},{f(r)} 0 0,1 {f(-r)},{f(r)}h{f(-(w - 2 * r))}a{f(r)},{f(r)} 0 0,1 {f(-r)},{f(-r)}"
            f"v{f(-(h - 2 * r))}a{f(r)},{f(r)} 0 0,1 {f(r)},{f(-r)}Z")


# ----- geometry -------------------------------------------------------------------

R_BARREL = 27.0
R_KNURL_IN = 24.8
R_GOLD_OUT, R_GOLD_IN = 23.6, 21.4
R_GLASS = 20.0
R_APERTURE = 7.6
BLADE_TURN = 18.0  # rotation of the hexagonal opening


def aperture_points():
    return [point(R_APERTURE, BLADE_TURN + 60 * k) for k in range(6)]


def blade_lines():
    """From each corner of the opening, along its edge, out to the glass rim."""
    pts = aperture_points()
    paths = []
    for k in range(6):
        (x0, y0), (x1, y1) = pts[k], pts[(k + 1) % 6]
        dx, dy = x1 - x0, y1 - y0
        n = math.hypot(dx, dy)
        dx, dy = dx / n, dy / n
        # solve |p + t d - c| = R_GLASS - 0.4 for t > 0, starting at the second corner
        px, py = x1 - C, y1 - C
        b = px * dx + py * dy
        cc = px * px + py * py - (R_GLASS - 0.4) ** 2
        t = -b + math.sqrt(b * b - cc)
        paths.append(f"M{f(x1)},{f(y1)}L{f(x1 + t * dx)},{f(y1 + t * dy)}")
    return paths


def hexagon():
    pts = aperture_points()
    return "M" + "L".join(f"{f(x)},{f(y)}" for x, y in pts) + "Z"


def knurl():
    return "".join(
        f"M{f(point(R_KNURL_IN + 0.5, a)[0])},{f(point(R_KNURL_IN + 0.5, a)[1])}"
        f"L{f(point(R_BARREL - 0.5, a)[0])},{f(point(R_BARREL - 0.5, a)[1])}"
        for a in [k * 7.5 for k in range(48)])


def sprockets():
    holes = []
    pitch, w, h = 9.0, 5.2, 4.6
    x = C - pitch * 6 + (pitch - w) / 2
    while x < 108:
        for y in (19.2, 108 - 19.2 - h):
            holes.append(rrect(x, y, w, h, 1.0))
        x += pitch
    return "".join(holes)


# ----- layers: (kind, path, paint) --------------------------------------------------
# paint: ("fill", colour | gradient, alpha) or ("stroke", colour, width, alpha, cap)

def lin(x1, y1, x2, y2, stops):
    return ("linear", x1, y1, x2, y2, stops)


def rad(cx, cy, r, stops):
    return ("radial", cx, cy, r, stops)


BACKGROUND = [
    (f"M0,0h108v108h-108Z", ("fill", lin(0, 0, 0, 108, [(0, "#17120E"), (1, "#0A0807")]), 1)),
    # film base band running behind the lens
    (f"M0,15.5h108v77h-108Z", ("fill", lin(0, 15.5, 0, 92.5, [(0, "#3A2A1B"), (0.5, "#2C2015"), (1, "#3A2A1B")]), 1)),
    (sprockets(), ("fill", "#0B0907", 1)),
    ("M0,26.6h108M0,81.4h108", ("stroke", "#4A3726", 0.5, 1, "butt")),
]

FOREGROUND = [
    # soft shadow under the barrel
    (circle(R_BARREL + 1.6, C, C + 1.2), ("fill", rad(C, C + 1.2, R_BARREL + 1.6, [(0.85, "#99000000"), (1, "#00000000")]), 1)),
    (circle(R_BARREL), ("fill", lin(C, C - R_BARREL, C, C + R_BARREL, [(0, "#2C2C2C"), (1, "#121212")]), 1)),
    (knurl(), ("stroke", "#3C3C3C", 0.8, 1, "butt")),
    (ring(R_GOLD_OUT, R_GOLD_IN), ("fill", lin(C - 18, C - 18, C + 18, C + 18, [(0, GOLD[0]), (1, GOLD[1])]), 1)),
    (circle(R_GOLD_IN), ("fill", "#080808", 1)),
    (circle(R_GLASS), ("fill", rad(C - 5, C - 6, R_GLASS + 6, [(0, "#2E4660"), (0.55, "#101A27"), (1, "#05070B")]), 1)),
    ("".join(blade_lines()), ("stroke", "#24364A", 0.7, 1, "round")),
    (hexagon(), ("fill", rad(C, C, R_APERTURE, [(0, "#000000"), (1, "#0A121C")]), 1)),
    # coating and reflections
    (arc(R_GLASS - 2.4, 18, 62), ("stroke", "#5FC2B8", 1.3, 0.55, "round")),
    (arc(R_GLASS - 4.2, 196, 248), ("stroke", "#FFFFFF", 2.0, 0.7, "round")),
    (arc(R_GLASS - 2.2, 256, 268), ("stroke", "#FFFFFF", 1.0, 0.4, "round")),
    (circle(1.5, *point(R_GLASS - 6.5, 42)), ("fill", "#F0C070", 0.9)),
]

MONOCHROME = [
    (ring(R_BARREL, R_KNURL_IN - 0.4), ("fill", "#000000", 1)),
    (ring(R_GOLD_OUT, R_GOLD_IN), ("fill", "#000000", 1)),
    ("".join(blade_lines()), ("stroke", "#000000", 1.0, 1, "round")),
    (hexagon(), ("fill", "#000000", 1)),
]


# ----- writers ---------------------------------------------------------------------

def android_colour(c, alpha=1.0):
    c = c.lstrip("#")
    if len(c) == 8:  # already ARGB
        return "#" + c.upper()
    return "#" + f"{round(alpha * 255):02X}" + c.upper()


def android_gradient(g):
    if g[0] == "linear":
        _, x1, y1, x2, y2, stops = g
        head = (f'<gradient android:type="linear" android:startX="{f(x1)}" android:startY="{f(y1)}" '
                f'android:endX="{f(x2)}" android:endY="{f(y2)}">')
    else:
        _, cx, cy, r, stops = g
        head = (f'<gradient android:type="radial" android:centerX="{f(cx)}" android:centerY="{f(cy)}" '
                f'android:gradientRadius="{f(r)}">')
    items = "".join(f'<item android:offset="{f(o)}" android:color="{android_colour(c)}"/>' for o, c in stops)
    return head + items + "</gradient>"


def android_vector(layers):
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           "<!-- Generated by tools/make_icon.py; edit the script, not this file. -->",
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    xmlns:aapt="http://schemas.android.com/aapt"',
           '    android:width="108dp" android:height="108dp"',
           '    android:viewportWidth="108" android:viewportHeight="108">']
    for path, paint in layers:
        attrs = [f'android:pathData="{path}"']
        child = ""
        if paint[0] == "fill":
            _, colour, alpha = paint
            attrs.append('android:fillType="evenOdd"')
            if isinstance(colour, tuple):
                child = f'<aapt:attr name="android:fillColor">{android_gradient(colour)}</aapt:attr>'
            else:
                attrs.append(f'android:fillColor="{android_colour(colour, alpha)}"')
        else:
            _, colour, width, alpha, cap = paint
            attrs += [f'android:strokeColor="{android_colour(colour, alpha)}"',
                      f'android:strokeWidth="{f(width)}"', f'android:strokeLineCap="{cap}"']
        if child:
            out.append(f'    <path {" ".join(attrs)}>{child}</path>')
        else:
            out.append(f'    <path {" ".join(attrs)}/>')
    out.append("</vector>")
    return "\n".join(out) + "\n"


def svg_colour(c):
    c = c.lstrip("#")
    if len(c) == 8:
        return f'#{c[2:]}" stop-opacity="{int(c[:2], 16) / 255:.3f}'
    return "#" + c


_svg_count = [0]


def svg(layer_sets, size=108, clip=None):
    _svg_count[0] += 1
    uid = f"i{_svg_count[0]}"
    defs, body, n = [], [], 0
    for layers in layer_sets:
        for path, paint in layers:
            if paint[0] == "fill":
                _, colour, alpha = paint
                if isinstance(colour, tuple):
                    n += 1
                    gid = f"{uid}g{n}"
                    stops = "".join(f'<stop offset="{o}" stop-color="{svg_colour(c)}"/>' for o, c in colour[-1])
                    if colour[0] == "linear":
                        _, x1, y1, x2, y2, _s = colour
                        defs.append(f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{x1}" y1="{y1}" '
                                    f'x2="{x2}" y2="{y2}">{stops}</linearGradient>')
                    else:
                        _, cx, cy, r, _s = colour
                        defs.append(f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" cx="{cx}" cy="{cy}" '
                                    f'r="{r}">{stops}</radialGradient>')
                    fill = f"url(#{gid})"
                else:
                    fill = colour
                body.append(f'<path d="{path}" fill="{fill}" fill-opacity="{alpha}" fill-rule="evenodd"/>')
            else:
                _, colour, width, alpha, cap = paint
                body.append(f'<path d="{path}" fill="none" stroke="{colour}" stroke-opacity="{alpha}" '
                            f'stroke-width="{width}" stroke-linecap="{cap}"/>')
    clip_attr = ""
    if clip:
        defs.append(f'<clipPath id="{uid}m"><path d="{clip}"/></clipPath>')
        clip_attr = f' clip-path="url(#{uid}m)"'
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72" width="{size}" height="{size}">'
            f'<defs>{"".join(defs)}</defs><g{clip_attr}>{"".join(body)}</g></svg>')


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


ADAPTIVE = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
"""

if __name__ == "__main__":
    write(os.path.join(RES, "drawable", "ic_launcher_background.xml"), android_vector(BACKGROUND))
    write(os.path.join(RES, "drawable", "ic_launcher_foreground.xml"), android_vector(FOREGROUND))
    write(os.path.join(RES, "drawable", "ic_launcher_monochrome.xml"), android_vector(MONOCHROME))
    for name in ("ic_launcher", "ic_launcher_round"):
        write(os.path.join(RES, "mipmap-anydpi-v26", name + ".xml"), ADAPTIVE)
    if len(sys.argv) > 1:
        # Preview page: square, squircle-ish rounded and circle masks, plus the themed icon.
        masks = [None, rrect(18, 18, 72, 72, 20), circle(36)]
        tiles = [svg([BACKGROUND, FOREGROUND], 220, m) for m in masks]
        tiles.append(svg([[(circle(36), ("fill", "#E8DEF8", 1))],
                          [(p, ("fill", "#3B2E58", 1) if s[0] == "fill" else ("stroke", "#3B2E58", s[2], 1, s[4]))
                           for p, s in MONOCHROME]], 220, circle(36)))
        small = [svg([BACKGROUND, FOREGROUND], 48, rrect(18, 18, 72, 72, 20))]
        write(sys.argv[1], '<!doctype html><meta charset="utf-8"><title>Icon preview</title>'
              '<body style="margin:0;padding:24px;background:#5a6470;display:flex;gap:24px;align-items:center;flex-wrap:wrap">'
              + "".join(tiles) + "".join(small) + "</body>")
