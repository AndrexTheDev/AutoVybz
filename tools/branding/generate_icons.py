#!/usr/bin/env python3
"""
AutoVybz branding pipeline.

Takes the original logo (branding/autovybz_logo_original.jpg), re-colours it
to the AutoVybz "Jamaica" palette and renders every Android launcher / UI asset.

Re-colouring works by *colour unmixing*: every pixel of the source is
modelled as a convex mix of the three source inks (slate background, neon
green, yellow). The mixing weights are solved per pixel (least squares with
a sum-to-one constraint) and the pixel is then re-composed from the target
inks. This keeps the anti-aliased edges of the artwork intact instead of
producing jagged hue-replacement fringes.

Usage (from the repo root):
    pip install pillow numpy
    python3 tools/branding/generate_icons.py
"""
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "branding" / "autovybz_logo_original.jpg"
RES = ROOT / "app" / "src" / "main" / "res"
OUT_BRANDING = ROOT / "branding"

# Source inks (median of the respective pixel clusters in the original JPG).
SRC_BG = np.array([47.0, 57.0, 66.0])
SRC_GREEN = np.array([26.0, 240.0, 95.0])
SRC_YELLOW = np.array([254.0, 210.0, 3.0])

# AutoVybz theme inks.
BG = np.array([0x12, 0x12, 0x12], dtype=float)      # #121212 charcoal
GREEN = np.array([0x00, 0x9B, 0x3A], dtype=float)   # #009B3A vibrant green
YELLOW = np.array([0xFE, 0xD1, 0x00], dtype=float)  # #FED100 vibrant gold

DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}


def unmix(rgb: np.ndarray) -> np.ndarray:
    """Return (H, W, 3) weights [w_bg, w_green, w_yellow] summing to 1."""
    h, w, _ = rgb.shape
    # 4 equations (R, G, B, sum=1) x 3 unknowns; the sum row is weighted strongly.
    k = 255.0
    a = np.vstack([np.stack([SRC_BG, SRC_GREEN, SRC_YELLOW], axis=1), k * np.ones((1, 3))])
    b = np.concatenate([rgb.reshape(-1, 3).T, k * np.ones((1, h * w))], axis=0)
    weights, *_ = np.linalg.lstsq(a, b, rcond=None)
    weights = np.clip(weights.T, 0.0, 1.0)
    weights /= np.maximum(weights.sum(axis=1, keepdims=True), 1e-6)
    return weights.reshape(h, w, 3)


def recolour(weights: np.ndarray):
    """Opaque image on #121212 and a transparent (ink-only) RGBA image."""
    opaque = weights[..., 0:1] * BG + weights[..., 1:2] * GREEN + weights[..., 2:3] * YELLOW
    ink_alpha = weights[..., 1] + weights[..., 2]
    ink_rgb = (weights[..., 1:2] * GREEN + weights[..., 2:3] * YELLOW) / np.maximum(ink_alpha[..., None], 1e-6)
    rgba = np.dstack([ink_rgb, ink_alpha * 255.0])
    return (
        Image.fromarray(np.clip(opaque, 0, 255).astype(np.uint8), "RGB"),
        Image.fromarray(np.clip(rgba, 0, 255).astype(np.uint8), "RGBA"),
    )


def content_crop(transparent: Image.Image, pad_ratio: float = 0.02) -> Image.Image:
    """Square crop tightly around the artwork (alpha bbox) with a small pad."""
    alpha = np.asarray(transparent)[..., 3]
    ys, xs = np.where(alpha > 24)
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    half = max(x1 - x0, y1 - y0) / 2 * (1 + pad_ratio)
    box = tuple(int(round(v)) for v in (cx - half, cy - half, cx + half, cy + half))
    return transparent.crop(box)


def place(art: Image.Image, canvas_px: int, art_ratio: float, bg=None) -> Image.Image:
    canvas = Image.new("RGBA", (canvas_px, canvas_px), (0, 0, 0, 0) if bg is None else bg)
    size = int(round(canvas_px * art_ratio))
    scaled = art.resize((size, size), Image.LANCZOS)
    off = (canvas_px - size) // 2
    canvas.alpha_composite(scaled, (off, off))
    return canvas


def circle_mask(px: int) -> Image.Image:
    scale = 4
    big = Image.new("L", (px * scale, px * scale), 0)
    ImageDraw.Draw(big).ellipse((0, 0, px * scale - 1, px * scale - 1), fill=255)
    return big.resize((px, px), Image.LANCZOS)


def save_res_webp(img: Image.Image, path) -> None:
    """Resources ship as lossless WebP (exact: keep RGB under alpha=0 -> pixel-identical
    to the PNG, ~40% smaller). Store/branding assets below stay PNG."""
    img.save(path, lossless=True, method=6, exact=True)


def main() -> None:
    src = np.asarray(Image.open(SRC).convert("RGB")).astype(float)
    weights = unmix(src)
    opaque, transparent = recolour(weights)
    art = content_crop(transparent)

    OUT_BRANDING.mkdir(exist_ok=True)
    opaque.resize((1024, 1024), Image.LANCZOS).save(OUT_BRANDING / "autovybz_logo_1024.png", optimize=True)
    art.resize((1024, 1024), Image.LANCZOS).save(OUT_BRANDING / "autovybz_logo_transparent_1024.png", optimize=True)
    # Play-Store style 512 px icon (full-bleed, opaque).
    place(art, 512, 0.78, bg=(0x12, 0x12, 0x12, 255)).convert("RGB").save(
        OUT_BRANDING / "autovybz_store_icon_512.png", optimize=True)

    # UI header asset (density independent, transparent).
    nodpi = RES / "drawable-nodpi"
    nodpi.mkdir(exist_ok=True)
    save_res_webp(art.resize((512, 512), Image.LANCZOS), nodpi / "autovybz_logo.webp")

    for name, d in DENSITIES.items():
        folder = RES / f"mipmap-{name}"
        folder.mkdir(exist_ok=True)
        fg_px = int(round(108 * d))
        # Adaptive icon: 108dp canvas, 66dp safe circle -> keep artwork inside ~62dp.
        fg = place(art, fg_px, 62 / 108)
        save_res_webp(fg, folder / "ic_launcher_foreground.webp")
        # Monochrome (themed icons): Android only uses the alpha channel.
        mono = Image.new("RGBA", fg.size, (255, 255, 255, 0))
        mono.putalpha(fg.getchannel("A"))
        save_res_webp(mono, folder / "ic_launcher_monochrome.webp")
        # Legacy (< API 26) icon: round charcoal badge with the artwork.
        legacy_px = int(round(48 * d))
        badge = place(art, legacy_px, 0.80, bg=(0x12, 0x12, 0x12, 255))
        badge.putalpha(circle_mask(legacy_px))
        save_res_webp(badge, folder / "ic_launcher.webp")
        save_res_webp(badge, folder / "ic_launcher_round.webp")
        stale = folder / "ic_launcher_background.png"
        if stale.exists():
            stale.unlink()  # background is now the @color/ic_launcher_background resource
    print("Branding assets written.")


if __name__ == "__main__":
    main()
