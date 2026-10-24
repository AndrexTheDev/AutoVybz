#!/usr/bin/env python3
"""Generates the landing page images (run once after changing logo / OG texts; output is committed).

Requires: pip install pillow qrcode matplotlib   (matplotlib only for its bundled DejaVu font)
"""
import json
from pathlib import Path

import matplotlib
import qrcode
import qrcode.image.svg
from PIL import Image, ImageDraw, ImageFilter, ImageFont

SITE = Path(__file__).resolve().parents[1]
REPO = SITE.parent
OUT = SITE / "static" / "img"
OUT.mkdir(parents=True, exist_ok=True)
FONT_DIR = Path(matplotlib.__file__).parent / "mpl-data" / "fonts" / "ttf"
BOLD = str(FONT_DIR / "DejaVuSans-Bold.ttf")
REG = str(FONT_DIR / "DejaVuSans.ttf")
SITE_URL = "https://andrexthedev.github.io/AutoVybz/"
GREEN, GOLD, BG = (0, 155, 58), (254, 209, 0), (10, 10, 10)

logo_t = Image.open(REPO / "branding" / "autovybz_logo_transparent_1024.png").convert("RGBA")
logo_bg = Image.open(REPO / "branding" / "autovybz_logo_1024.png").convert("RGBA")

for size in (128, 192, 256, 512):
    logo_t.resize((size, size), Image.LANCZOS).save(OUT / f"logo-{size}.png", optimize=True)
logo_bg.resize((180, 180), Image.LANCZOS).convert("RGB").save(OUT / "apple-touch-icon.png", optimize=True)
logo_bg.resize((32, 32), Image.LANCZOS).save(OUT / "favicon-32.png", optimize=True)


def wrap(draw, text, font, width):
    words, lines, cur = text.split(), [], ""
    for w in words:
        test = (cur + " " + w).strip()
        if draw.textlength(test, font=font) <= width:
            cur = test
        else:
            lines.append(cur)
            cur = w
    lines.append(cur)
    return lines


def og(lang):
    t = json.loads((SITE / "src" / "i18n" / f"{lang}.json").read_text(encoding="utf-8"))
    W, H = 1200, 630
    img = Image.new("RGB", (W, H), BG)
    glow = Image.new("RGB", (W, H), BG)
    g = ImageDraw.Draw(glow)
    g.ellipse((700, -150, 1350, 500), fill=(0, 70, 28))
    g.ellipse((-200, 380, 400, 900), fill=(60, 50, 0))
    img = Image.blend(img, glow.filter(ImageFilter.GaussianBlur(120)), 1.0)
    d = ImageDraw.Draw(img)
    # stripes (subtle flag nod)
    d.polygon([(W - 250, H), (W - 170, H), (W, H - 170), (W, H - 250)], fill=GOLD)
    d.polygon([(W - 170, H), (W - 90, H), (W, H - 90), (W, H - 170)], fill=GREEN)
    lg = logo_t.resize((300, 300), Image.LANCZOS)
    img.paste(lg, (850, 110), lg)
    d.text((70, 60), "AutoVybz", font=ImageFont.truetype(BOLD, 44), fill=GOLD)
    f_big = ImageFont.truetype(BOLD, 66)
    y = 150
    for line in wrap(d, t["hero"]["h1_a"], f_big, 740):
        d.text((70, y), line, font=f_big, fill=(255, 255, 255)); y += 78
    line2 = f'{t["hero"]["h1_b"]} 10 {t["hero"]["h1_c"]}'
    for line in wrap(d, line2, f_big, 740):
        d.text((70, y), line, font=f_big, fill=(43, 194, 102)); y += 78
    f_small = ImageFont.truetype(REG, 30)
    d.text((70, y + 30), t["hero"]["badge"].replace("🇯🇲 ", ""), font=f_small, fill=(200, 200, 200))
    d.text((70, H - 70), SITE_URL.replace("https://", "").rstrip("/"), font=ImageFont.truetype(BOLD, 28), fill=GOLD)
    img.save(OUT / f"og-{lang}.png", optimize=True)


for lang in ("en", "de", "es"):
    if (SITE / "src" / "i18n" / f"{lang}.json").exists():
        og(lang)


def qr_svg(data, name):
    q = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, border=2)
    q.add_data(data)
    q.make(fit=True)
    q.make_image(image_factory=qrcode.image.svg.SvgPathFillImage).save(OUT / f"qr-{name}.svg")


qr_svg(SITE_URL, "site")
qr_svg("solana:79KsqtJJdhKFJ9woxnYgtf3nq7HxQveafWBCtC3mxWi8", "sol")
qr_svg("bitcoin:bc1qeqzrlfg3edrydk4s0hecakc82gp26n5p7hkc7f", "btc")
qr_svg("ethereum:0xBC3fab34f69bc9f6661608C3FB36dDdC313C42F7", "eth")
print("assets →", OUT)
