#!/usr/bin/env python3
"""Kenz Elections launcher icon — vector-clean, drawn with PIL.

Design (readable at 48dp, no tiny text):
  - Full-bleed deep-navy diagonal gradient (election-night blue)
  - One subtle glass sheen diagonal, echoing the app's liquid-glass bars
  - Big white bold "K" (Kenz) centred
  - Thick red vote checkmark overlapping the K's lower right
  - One small white star (election cue), top-right
Legacy PNGs go to mipmap-*; the adaptive foreground (transparent, K +
checkmark + star only) goes to drawable-xxxhdpi at 432px (108dp).
"""
import math, os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

BASE = os.path.expanduser("~/workspace/rightnews-live/app/src/main/res")
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
NAVY_TOP = (10, 27, 74)      # #0A1B4A
NAVY_BOT = (29, 78, 216)     # #1D4ED8
RED = (239, 45, 59)          # vote-check red
WHITE = (255, 255, 255)


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def gradient(size):
    # Small diagonal gradient, upscaled smooth — fast and band-free.
    small = Image.new("RGB", (256, 256))
    px = small.load()
    for y in range(256):
        for x in range(256):
            px[x, y] = lerp(NAVY_TOP, NAVY_BOT, (x + y) / 510.0)
    return small.resize((size, size), Image.BICUBIC)


def star(draw, cx, cy, r_outer, r_inner, fill):
    pts = []
    for i in range(10):
        r = r_outer if i % 2 == 0 else r_inner
        ang = math.pi / 2 + i * math.pi / 5  # point up first
        # PIL y grows downwards, so flip the angle sign for a star that points up
        pts.append((cx + r * math.cos(ang), cy - r * math.sin(ang)))
    draw.polygon(pts, fill=fill)


def draw_mark(draw, s, k_font_size, k_cx, k_cy, check_scale=1.0):
    """Draw K + checkmark + star on `draw` at scale s (canvas = s px)."""
    f = ImageFont.truetype(FONT, int(k_font_size))
    # K, centred
    bbox = draw.textbbox((0, 0), "K", font=f)
    w, h = bbox[2] - bbox[0], bbox[3] - bbox[1]
    draw.text((k_cx - w / 2 - bbox[0], k_cy - h / 2 - bbox[1]), "K",
              font=f, fill=WHITE)
    # Vote checkmark, lower-right of the K: white outline, then red core.
    # Coordinates are fractions of the canvas so every size stays identical.
    p1 = (0.600 * s, 0.650 * s)
    p2 = (0.710 * s, 0.775 * s)
    p3 = (0.915 * s, 0.475 * s)
    w_out = int(0.088 * s * check_scale)
    w_in = int(0.056 * s * check_scale)
    draw.line([p1, p2, p3], fill=WHITE, width=w_out, joint="curve")
    draw.line([p1, p2, p3], fill=RED, width=w_in, joint="curve")
    # Small star, top-right — kept clear of the checkmark.
    star(draw, 0.815 * s, 0.185 * s, 0.055 * s, 0.022 * s, WHITE)


def main():
    S = 1024
    img = gradient(S).convert("RGBA")
    # Glass sheen: soft white diagonal band, blurred.
    sheen = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    sd = ImageDraw.Draw(sheen)
    sd.polygon([(0, int(0.55 * S)), (int(0.45 * S), 0), (int(0.75 * S), 0),
                (int(0.30 * S), S)], fill=(255, 255, 255, 26))
    sheen = sheen.filter(ImageFilter.GaussianBlur(60))
    img.alpha_composite(sheen)
    d = ImageDraw.Draw(img)
    draw_mark(d, S, 660, S / 2 - 20, S / 2 - 10)
    # Preview master
    preview = os.path.expanduser("~/workspace/your_files/rightnews-live/kenz-elections-icon-1024.png")
    os.makedirs(os.path.dirname(preview), exist_ok=True)
    img.save(preview)

    # Legacy mipmap PNGs (full-bleed; launcher mask crops the corners).
    for dpi, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                    ("xxhdpi", 144), ("xxxhdpi", 192)]:
        dpath = os.path.join(BASE, f"mipmap-{dpi}")
        os.makedirs(dpath, exist_ok=True)
        small = img.resize((px, px), Image.LANCZOS)
        small.save(os.path.join(dpath, "ic_launcher.png"))
        small.save(os.path.join(dpath, "ic_launcher_round.png"))

    # Adaptive foreground: transparent 432px, mark inside the 66dp safe zone.
    F = 432
    fg = Image.new("RGBA", (F, F), (0, 0, 0, 0))
    fd = ImageDraw.Draw(fg)
    # The mark drawing uses canvas fractions; shrink it into the safe zone
    # by drawing on a 432 canvas but with the K/check centred and smaller.
    f = ImageFont.truetype(FONT, 250)
    bbox = fd.textbbox((0, 0), "K", font=f)
    w, h = bbox[2] - bbox[0], bbox[3] - bbox[1]
    fd.text((F / 2 - 8 - w / 2 - bbox[0], F / 2 - 6 - h / 2 - bbox[1]),
            "K", font=f, fill=WHITE)
    p1 = (0.555 * F, 0.620 * F)
    p2 = (0.660 * F, 0.730 * F)
    p3 = (0.850 * F, 0.490 * F)
    fd.line([p1, p2, p3], fill=WHITE, width=44, joint="curve")
    fd.line([p1, p2, p3], fill=RED, width=28, joint="curve")
    star(fd, 0.770 * F, 0.230 * F, 22, 9, WHITE)
    dpath = os.path.join(BASE, "drawable-xxxhdpi")
    os.makedirs(dpath, exist_ok=True)
    fg.save(os.path.join(dpath, "ic_launcher_foreground.png"))
    print("icon written:", preview)


if __name__ == "__main__":
    main()
