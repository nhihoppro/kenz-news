#!/usr/bin/env python3
"""Kenz Elections launcher icon — Liquid Glass, transparent.

Replaces the v1.3 solid-navy icon (make_icon.py) on Franklin's
2026-11-03 order: "Thiết kế lại icon app theo phong cách Liquid
glass, blur trong suốt".

Design, drawn with PIL (no libraries in the app itself):
  - No opaque slab: the whole icon is a glass squircle whose fill
    is a translucent navy-blue gradient (alpha ~185), so the home
    wallpaper shows through it the way a liquid-glass bar does.
  - Liquid-glass optics, faked in a static PNG the way every
    glass icon does it: a bright top gloss, a soft diagonal
    sheen, two blurred "liquid" blobs inside the glass, a white
    rim stroke, and a faint dark edge at the bottom for depth.
  - The same mark as before, so the app stays recognisable:
    big white bold "K", thick red vote checkmark overlapping its
    lower right, one small white star top-right. The K gets a
    soft dark drop shadow so it floats above the glass.
  - Adaptive icon: background layer is a translucent navy
    gradient (see ic_launcher_background.xml, alpha 0x80), and
    the foreground is the glass squircle + mark at 432px. On
    launchers that composite the icon over wallpaper, the glass
    reads as see-through; on launchers that put it over a solid
    colour, it reads as tinted glass, never as a blank square.
  - Legacy mipmap PNGs composite the translucent background
    gradient + the glass squircle + the mark, full-bleed, so the
    launcher mask crops the corners the same way it did for v1.3.
  - Preview: the icon composited over a busy coloured
    "wallpaper" so the see-through effect is visible before
    installing, saved next to the APK folder.
"""
import math, os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

BASE = os.path.expanduser("~/workspace/rightnews-live/app/src/main/res")
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
RED = (239, 45, 59)
WHITE = (255, 255, 255)


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def glass_gradient(size):
    """Vertical glass gradient, RGBA: light blue-white at the top
    fading to deep navy at the bottom, alpha ~200 throughout so
    the wallpaper still shows through."""
    top_rgb, bot_rgb = (150, 190, 245), (10, 27, 74)
    small = Image.new("RGBA", (64, 256))
    px = small.load()
    for y in range(256):
        rgb = lerp(top_rgb, bot_rgb, y / 255.0)
        alpha = int(205 - 25 * (y / 255.0))  # 205 -> 180
        for x in range(64):
            px[x, y] = rgb + (alpha,)
    return small.resize((size, size), Image.BICUBIC)


def rounded_mask(size, inset, radius):
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    d.rounded_rectangle([inset, inset, size - inset, size - inset],
                        radius=radius, fill=255)
    return m


def star(draw, cx, cy, r_outer, r_inner, fill):
    pts = []
    for i in range(10):
        r = r_outer if i % 2 == 0 else r_inner
        ang = math.pi / 2 + i * math.pi / 5
        pts.append((cx + r * math.cos(ang), cy - r * math.sin(ang)))
    draw.polygon(pts, fill=fill)


def draw_mark(draw, s, k_size, k_cx, k_cy):
    """K + checkmark + star, centred at (k_cx, k_cy) on a canvas s."""
    f = ImageFont.truetype(FONT, int(k_size))
    # Soft drop shadow first, so the K floats above the glass.
    bbox = draw.textbbox((0, 0), "K", font=f)
    w, h = bbox[2] - bbox[0], bbox[3] - bbox[1]
    x, y = k_cx - w / 2 - bbox[0], k_cy - h / 2 - bbox[1]
    draw.text((x, y + 0.008 * s), "K", font=f, fill=(10, 27, 74, 90))
    draw.text((x, y), "K", font=f, fill=WHITE + (255,))
    p1 = (k_cx + 0.100 * s, k_cy + 0.150 * s)
    p2 = (k_cx + 0.210 * s, k_cy + 0.275 * s)
    p3 = (k_cx + 0.415 * s, k_cy - 0.025 * s)
    w_out = int(0.088 * s)
    w_in = int(0.056 * s)
    draw.line([p1, p2, p3], fill=WHITE + (255,), width=w_out, joint="curve")
    draw.line([p1, p2, p3], fill=RED + (255,), width=w_in, joint="curve")
    star(draw, k_cx + 0.315 * s, k_cy - 0.315 * s, 0.055 * s, 0.022 * s, WHITE + (255,))


def glass_squircle(size, inset, radius):
    """Transparent canvas carrying only the glass panel + optics
    (no mark): gradient fill, top gloss, diagonal sheen, liquid
    blobs, white rim, dark bottom edge."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    mask = rounded_mask(size, inset, radius)
    grad = glass_gradient(size)
    img.paste(grad, (0, 0), mask)
    # Liquid blobs: blurred light spots inside the glass.
    blobs = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    bd = ImageDraw.Draw(blobs)
    bd.ellipse([int(0.15 * size), int(0.10 * size), int(0.55 * size), int(0.42 * size)],
               fill=(200, 225, 255, 90))
    bd.ellipse([int(0.55 * size), int(0.55 * size), int(0.95 * size), int(0.90 * size)],
               fill=(120, 160, 230, 70))
    blobs = blobs.filter(ImageFilter.GaussianBlur(int(0.05 * size)))
    blobs.putalpha(Image.composite(blobs.split()[3], Image.new("L", (size, size), 0), mask))
    img.alpha_composite(blobs)
    # Top gloss: bright white band across the upper third.
    gloss = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    gd = ImageDraw.Draw(gloss)
    gd.ellipse([-int(0.2 * size), -int(0.35 * size), int(1.2 * size), int(0.35 * size)],
               fill=(255, 255, 255, 110))
    gloss = gloss.filter(ImageFilter.GaussianBlur(int(0.04 * size)))
    gloss.putalpha(Image.composite(gloss.split()[3], Image.new("L", (size, size), 0), mask))
    img.alpha_composite(gloss)
    # Diagonal sheen, like the app's glass bars.
    sheen = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    sd = ImageDraw.Draw(sheen)
    sd.polygon([(0, int(0.55 * size)), (int(0.45 * size), 0), (int(0.75 * size), 0),
                (int(0.30 * size), size)], fill=(255, 255, 255, 45))
    sheen = sheen.filter(ImageFilter.GaussianBlur(int(0.06 * size)))
    sheen.putalpha(Image.composite(sheen.split()[3], Image.new("L", (size, size), 0), mask))
    img.alpha_composite(sheen)
    # Rim + bottom edge.
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([inset, inset, size - inset, size - inset],
                        radius=radius, outline=(255, 255, 255, 220),
                        width=max(2, int(0.008 * size)))
    return img


def bg_gradient(size):
    """Translucent navy gradient for the area behind the glass
    panel (legacy PNGs + the adaptive background drawable's
    visual equivalent)."""
    small = Image.new("RGBA", (64, 64))
    px = small.load()
    for y in range(64):
        for x in range(64):
            rgb = lerp((10, 27, 74), (29, 78, 216), (x + y) / 126.0)
            px[x, y] = rgb + (128,)
    return small.resize((size, size), Image.BICUBIC)


def compose(size, inset_frac=0.0):
    """Full icon at `size` px: the glass panel alone, full-bleed,
    corners transparent — no background slab behind it."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    inset = int(size * inset_frac)
    img.alpha_composite(glass_squircle(size, inset, int(size * 0.23)))
    d = ImageDraw.Draw(img)
    draw_mark(d, size, int(size * 0.62), size / 2 - 0.02 * size, size / 2 - 0.01 * size)
    return img


def main():
    S = 1024
    icon = compose(S)
    # Preview over a busy "wallpaper" so the see-through reads.
    wall = Image.new("RGB", (S, S))
    wp = wall.load()
    for y in range(S):
        for x in range(S):
            wp[x, y] = lerp((255, 120, 90), (60, 90, 220), (x + y) / 2046.0)
    wall = wall.filter(ImageFilter.GaussianBlur(2))
    blobs = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    bd = ImageDraw.Draw(blobs)
    bd.ellipse([100, 100, 700, 600], fill=(255, 220, 80, 255))
    bd.ellipse([500, 500, 1000, 950], fill=(80, 220, 160, 255))
    bd.ellipse([200, 650, 550, 1000], fill=(180, 90, 255, 255))
    blobs = blobs.filter(ImageFilter.GaussianBlur(120))
    wall = Image.alpha_composite(wall.convert("RGBA"), blobs)
    preview = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    preview.alpha_composite(wall)
    small = icon.resize((768, 768), Image.LANCZOS)
    preview.alpha_composite(small, (128, 128))
    outdir = os.path.expanduser("~/workspace/your_files/rightnews-live")
    os.makedirs(outdir, exist_ok=True)
    preview.save(os.path.join(outdir, "kenz-elections-icon-liquid-glass-1024.png"))
    icon.save(os.path.join(outdir, "kenz-elections-icon-liquid-glass-flat-1024.png"))

    # Legacy mipmap PNGs.
    for dpi, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                    ("xxhdpi", 144), ("xxxhdpi", 192)]:
        dpath = os.path.join(BASE, f"mipmap-{dpi}")
        os.makedirs(dpath, exist_ok=True)
        small = compose(px)
        small.save(os.path.join(dpath, "ic_launcher.png"))
        small.save(os.path.join(dpath, "ic_launcher_round.png"))

    # Adaptive foreground: glass squircle + mark at 432px (108dp).
    # Full-bleed squircle; the launcher mask crops the corners.
    F = 432
    fg = glass_squircle(F, 0, int(F * 0.23))
    fd = ImageDraw.Draw(fg)
    draw_mark(fd, F, int(F * 0.58), F / 2 - 8, F / 2 - 6)
    dpath = os.path.join(BASE, "drawable-xxxhdpi")
    os.makedirs(dpath, exist_ok=True)
    fg.save(os.path.join(dpath, "ic_launcher_foreground.png"))
    print("liquid glass icon written")


if __name__ == "__main__":
    main()
