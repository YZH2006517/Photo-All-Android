"""Promo images: real screenshots with a photo in the viewfinder, in a phone, a knocked-out title.

python docs/promo/make.py   (reads raw/*.png and src/*.jpg, writes out/*.png)
"""
import os
import numpy as np
from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont
import cv2

HERE = os.path.dirname(os.path.abspath(__file__))
FONTS = r"C:\Windows\Fonts"
BOLD = os.path.join(FONTS, "Noto Sans SC Bold (TrueType).otf")
MEDIUM = os.path.join(FONTS, "Noto Sans SC Medium (TrueType).otf")
BG = np.array([11, 13, 16])  # the viewfinder's own colour behind the picture
PAPER = (242, 238, 230)
INK = (28, 26, 24)


def cover(photo, w, h):
    """The photo scaled to cover w x h, centred."""
    k = max(w / photo.width, h / photo.height)
    p = photo.resize((round(photo.width * k), round(photo.height * k)), Image.LANCZOS)
    x, y = (p.width - w) // 2, (p.height - h) // 2
    return p.crop((x, y, x + w, y + h))


def viewfinder(shot, photo):
    """The screenshot with the photo in its viewfinder, the overlays (grid, readouts) kept on top."""
    a = np.array(shot.convert("RGB")).astype(np.int32)
    near = (np.abs(a - BG).sum(axis=2) <= 2).astype(np.uint8)  # its slight blue apart from the grey round it
    # the viewfinder: its colour with the grid lines and labels closed over, the biggest region of it
    near = cv2.morphologyEx(near, cv2.MORPH_CLOSE, np.ones((25, 25), np.uint8))
    n, lab, stats, _ = cv2.connectedComponentsWithStats(near, 8)
    big = 1 + np.argmax(stats[1:, cv2.CC_STAT_AREA])
    region = (lab == big).astype(np.uint8)
    region = cv2.morphologyEx(region, cv2.MORPH_CLOSE, np.ones((41, 41), np.uint8))
    x, y, w, h = cv2.boundingRect(region)
    # fill everything enclosed (labels, the histogram's box)
    cnts, _ = cv2.findContours(region, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    mask = np.zeros_like(region)
    cv2.drawContours(mask, cnts, -1, 1, -1)
    pic = np.array(cover(photo, w, h).convert("RGB")).astype(np.float32)
    full = np.zeros_like(a, dtype=np.float32)
    full[y:y + h, x:x + w] = pic
    # what was drawn over the dark viewfinder, screened onto the photo
    over = np.clip(a - BG, 0, 255).astype(np.float32)
    shown = full + over * (1 - full / 255.0)
    out = np.where(mask[..., None] == 1, shown, a).clip(0, 255).astype(np.uint8)
    return Image.fromarray(out)


def rounded(size, r, scale=1):
    m = Image.new("L", (size[0] * scale, size[1] * scale), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, m.width - 1, m.height - 1), r * scale, fill=255)
    return m.resize(size, Image.LANCZOS) if scale > 1 else m


def phone(screen, height=None, width=None):
    """A plain phone round the screenshot: a dark body, a slim bezel, a punch-hole camera."""
    sw, sh = screen.size
    portrait = sh > sw
    k = (height / (sh if portrait else sw)) if portrait and height else (width / sw if width else 1)
    if not portrait and height:
        k = height / sh
    s = screen.resize((round(sw * k), round(sh * k)), Image.LANCZOS)
    short = min(s.size)
    bezel = round(short * 0.028)
    r_screen = round(short * 0.085)
    W, H = s.width + 2 * bezel, s.height + 2 * bezel
    body = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    # the body: graphite, its rim catching the light
    rim = Image.new("RGBA", (W, H), (58, 58, 62, 255))
    body.paste(rim, (0, 0), rounded((W, H), r_screen + bezel, 3))
    inner = Image.new("RGBA", (W - 6, H - 6), (14, 14, 16, 255))
    body.paste(inner, (3, 3), rounded((W - 6, H - 6), r_screen + bezel - 3, 3))
    body.paste(s, (bezel, bezel), rounded(s.size, r_screen, 3))
    # the front camera
    d = ImageDraw.Draw(body)
    cr = max(6, round(short * 0.012))
    cx, cy = (W // 2, bezel + round(short * 0.035)) if portrait else (bezel + round(short * 0.035), H // 2)
    d.ellipse((cx - cr, cy - cr, cx + cr, cy + cr), fill=(6, 6, 8, 255))
    d.ellipse((cx - cr * 0.45, cy - cr * 0.45, cx - cr * 0.05, cy - cr * 0.05), fill=(40, 44, 60, 255))
    # side keys
    key = (46, 46, 50, 255)
    if portrait:
        d.rounded_rectangle((W - 2, H * 0.22, W + 4, H * 0.30), 3, fill=key)
        d.rounded_rectangle((W - 2, H * 0.33, W + 4, H * 0.38), 3, fill=key)
    else:
        d.rounded_rectangle((W * 0.62, -4, W * 0.70, 2), 3, fill=key)
        d.rounded_rectangle((W * 0.73, -4, W * 0.78, 2), 3, fill=key)
    return body


def shadow(img, blur, alpha=0.55, dy=0):
    a = img.split()[-1].point(lambda v: int(v * alpha))
    pad = blur * 3
    sh = Image.new("RGBA", (img.width + 2 * pad, img.height + 2 * pad), (0, 0, 0, 0))
    sh.paste(Image.new("RGBA", img.size, (0, 0, 0, 255)), (pad, pad + dy), a)
    return sh.filter(ImageFilter.GaussianBlur(blur)), pad


def poster(shot_name, photo_name, title, sub, out_name, size):
    shot = Image.open(os.path.join(HERE, "raw", shot_name + ".png"))
    photo = Image.open(os.path.join(HERE, "src", photo_name)).convert("RGB")
    screen = viewfinder(shot, photo)
    PW, PH = size
    landscape = PW > PH
    band_h = round(PH * (0.24 if not landscape else 0.32))
    # the ground: the same photo, soft and dark
    bg = cover(photo, PW, PH).filter(ImageFilter.GaussianBlur(PW // 40))
    bg = Image.blend(bg, Image.new("RGB", bg.size, (12, 11, 10)), 0.45).convert("RGBA")
    # the phone, centred above the band
    room = PH - band_h
    ph = phone(screen, height=round(room * 0.86)) if not landscape else phone(screen, height=round(room * 0.84))
    if ph.width > PW * 0.9:
        ph = ph.resize((round(PW * 0.9), round(ph.height * PW * 0.9 / ph.width)), Image.LANCZOS)
    px, py = (PW - ph.width) // 2, (room - ph.height) // 2 + round(room * 0.03)
    sh, pad = shadow(ph, round(min(PW, PH) * 0.025), 0.6, dy=round(min(PW, PH) * 0.012))
    bg.alpha_composite(sh, (px - pad, py - pad))
    bg.alpha_composite(ph, (px, py))
    # the band: paper, the title cut out of it (the photo shows through), a line of words under it
    band = Image.new("RGBA", (PW, band_h), PAPER + (255,))
    sharp = cover(photo, PW, band_h * 3).crop((0, band_h, PW, band_h * 2))
    # deep enough to read on the paper whatever the photo is (sky, white walls)
    sharp = ImageEnhance.Contrast(ImageEnhance.Brightness(sharp).enhance(0.62)).enhance(1.25).convert("RGBA")
    fs = round(band_h * (0.50 if not landscape else 0.40))
    font = ImageFont.truetype(BOLD, fs)
    while font.getlength(title) > PW * 0.88:
        fs -= 4
        font = ImageFont.truetype(BOLD, fs)
    hole = Image.new("L", band.size, 0)
    hd = ImageDraw.Draw(hole)
    tw = font.getlength(title)
    ty = round(band_h * 0.12)
    hd.text(((PW - tw) / 2, ty), title, font=font, fill=255)
    band.paste(sharp, (0, 0), hole)
    d = ImageDraw.Draw(band)
    small = ImageFont.truetype(MEDIUM, round(fs * 0.24))
    lw = small.getlength(sub)
    d.text(((PW - lw) / 2, ty + fs * 1.32), sub, font=small, fill=INK)
    mark = ImageFont.truetype(BOLD, round(fs * 0.16))
    label = "PHOTO ALL  ·  胶片相机"
    d.text(((PW - mark.getlength(label)) / 2, band_h - fs * 0.42), label, font=mark, fill=(150, 142, 130))
    bg.alpha_composite(band, (0, PH - band_h))
    os.makedirs(os.path.join(HERE, "out"), exist_ok=True)
    bg.convert("RGB").save(os.path.join(HERE, "out", out_name + ".png"), optimize=True)
    print("wrote", out_name)


def stack(items, out_name, width=2400):
    """The landscape screens one above another, each in its phone, no words."""
    phones = []
    for shot_name, photo_name in items:
        shot = Image.open(os.path.join(HERE, "raw", shot_name + ".png"))
        photo = Image.open(os.path.join(HERE, "src", photo_name)).convert("RGB")
        phones.append(phone(viewfinder(shot, photo), height=round(width * 0.86 * 1440 / 3088)))
    gap = round(width * 0.05)
    H = sum(p.height for p in phones) + gap * (len(phones) + 1)
    bg = Image.new("RGBA", (width, H), (20, 19, 18, 255))
    # a soft light from above, as on a desk
    glow = Image.new("L", (width, H), 0)
    ImageDraw.Draw(glow).ellipse((-width * 0.3, -H * 0.25, width * 1.3, H * 0.6), fill=60)
    bg.paste(Image.new("RGBA", bg.size, (60, 56, 50, 255)), (0, 0), glow.filter(ImageFilter.GaussianBlur(width // 6)))
    y = gap
    for p in phones:
        x = (width - p.width) // 2
        sh, pad = shadow(p, round(width * 0.018), 0.65, dy=round(width * 0.008))
        bg.alpha_composite(sh, (x - pad, y - pad))
        bg.alpha_composite(p, (x, y))
        y += p.height + gap
    os.makedirs(os.path.join(HERE, "out"), exist_ok=True)
    bg.convert("RGB").save(os.path.join(HERE, "out", out_name + ".png"), optimize=True)
    print("wrote", out_name)


if __name__ == "__main__":
    stack([("cam_pop", "merlion.jpg"), ("cam_body", "bridge.jpg"), ("cam_paper", "windmills.jpg")], "02_横屏三款")
    poster("cam_portrait", "building.jpg", "胶片，装进手机", "RAW 直出冲洗 · 真实胶片配方 · 每卷 36 张", "01_竖屏取景", (1500, 2700))
