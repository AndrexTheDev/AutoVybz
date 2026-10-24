#!/usr/bin/env python3
"""Re-theme the inherited engine screens (ex Equalizer314) from neutral grey to the AutoVybz slate palette.

Every *neutral grey* colour literal (R == G == B, within ±3) in the engine's layouts, drawables, colour
resources and Kotlin views is replaced by a slate-tinted colour, interpolated along SLATE_RAMP
(ui/theme/AutoVybzPalette.kt). The luminance is mapped through the palette anchors (old charcoal palette ->
new slate palette: background -> background, surface -> surface, grid -> grid, muted text -> muted text, ...),
piecewise linear in relative luminance. So views that paint the old background literally match the new window
background, the luminance order of all greys is preserved (monotonic), and text contrast stays where the
upstream design tuned it. Alpha is kept. Pure black (AMOLED / shadows), pure white (text) and fully transparent
values are left alone.

Idempotent: slate colours are no longer neutral, a second run changes nothing.

    python3 tools/branding/retheme_inherited.py            # dry run: statistics
    python3 tools/branding/retheme_inherited.py --write    # rewrite files
"""
import os
import re
import sys
from collections import Counter

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", ".."))
MAIN = os.path.join(ROOT, "app", "src", "main")
PALETTE = os.path.join(MAIN, "java", "com", "andrexthedev", "autovybz", "ui", "theme", "AutoVybzPalette.kt")

RES_DIRS = ["res/layout", "res/layout-land", "res/drawable", "res/color", "res/values", "res/values-night"]
RES_SKIP = {"autovybz_colors.xml", "autovybz_themes.xml", "themes.xml", "strings.xml", "autovybz_strings.xml"}
KOTLIN_DIR = "java/com/bearinmind"
# Accent literals of the upstream (Material purple) -> AutoVybz brand colours.
ACCENTS = {"BB86FC": "5CF28C", "6200EE": "1EED61", "3700B3": "123A27", "03DAC5": "FCD207", "03DAC6": "FCD207"}


def _lin(c):
    c /= 255.0
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4


def _unlin(v):
    v = max(0.0, min(1.0, v))
    c = v * 12.92 if v <= 0.0031308 else 1.055 * v ** (1 / 2.4) - 0.055
    return max(0, min(255, round(c * 255)))


def lum(rgb):
    r, g, b = (_lin(x) for x in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def load_ramp():
    src = open(PALETTE, encoding="utf-8").read()
    body = src.split("val SLATE_RAMP", 1)[1].split(")", 1)[0]
    ramp = [tuple(int(h[i:i + 2], 16) for i in (2, 4, 6)) for h in re.findall(r"0x([0-9A-Fa-f]{8})", body)]
    if len(ramp) < 8:
        raise SystemExit("SLATE_RAMP not found")
    return ramp


RAMP = load_ramp()
RAMP_L = [lum(c) for c in RAMP]


# (old neutral grey of the charcoal palette, new slate colour) - see AutoVybzPalette history.
ANCHORS = [
    (0x0A, "161C21"),  # BACKGROUND_DEEP
    (0x12, "1E262D"),  # BACKGROUND (intro backdrop)
    (0x18, "26303A"),  # SURFACE
    (0x22, "313C47"),  # SURFACE_HIGH
    (0x2A, "35414C"),  # GRID
    (0x73, "7F8C98"),  # OUTLINE
    (0xA8, "A7B2BC"),  # ON_DARK_MUTED
    (0xED, "EEF2F5"),  # ON_DARK
]


def _hex_rgb(h):
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


_ANCHOR_L = [(0.0, 0.0)] + [(lum((g, g, g)), lum(_hex_rgb(h))) for g, h in ANCHORS] + [(1.0, 1.0)]


def mapped_luminance(grey):
    x = lum((grey, grey, grey))
    for (x0, y0), (x1, y1) in zip(_ANCHOR_L, _ANCHOR_L[1:]):
        if x <= x1:
            return y0 + (y1 - y0) * ((x - x0) / (x1 - x0) if x1 > x0 else 0.0)
    return 1.0


def slate_for(grey):
    """Slate colour for the neutral grey value `grey` (0..255), luminance mapped through ANCHORS."""
    target = mapped_luminance(grey)
    lo_rgb, lo_l = (0, 0, 0), 0.0
    for rgb, l in zip(RAMP + [(255, 255, 255)], RAMP_L + [1.0]):
        if l >= target:
            t = 0.0 if l == lo_l else (target - lo_l) / (l - lo_l)
            # interpolate in linear light, then fix the luminance exactly by scaling
            mix = [_lin(a) * (1 - t) + _lin(b) * t for a, b in zip(lo_rgb, rgb)]
            m_l = 0.2126 * mix[0] + 0.7152 * mix[1] + 0.0722 * mix[2]
            k = target / m_l if m_l > 0 else 0
            out = tuple(_unlin(v * k) for v in mix)
            return out
        lo_rgb, lo_l = rgb, l
    return (255, 255, 255)


def is_neutral(rgb, tol=3):
    return max(rgb) - min(rgb) <= tol


def convert(argb_hex):
    """'AARRGGBB' or 'RRGGBB' -> new hex of the same length, or None if untouched."""
    h = argb_hex.upper()
    alpha, rgb_hex = (h[:2], h[2:]) if len(h) == 8 else ("", h)
    if alpha == "00":
        return None
    if rgb_hex in ACCENTS:
        return alpha + ACCENTS[rgb_hex]
    rgb = tuple(int(rgb_hex[i:i + 2], 16) for i in (0, 2, 4))
    if not is_neutral(rgb) or rgb in ((0, 0, 0), (255, 255, 255)):
        return None
    grey = round(sum(rgb) / 3)
    new = "".join(f"{v:02X}" for v in slate_for(grey))
    return alpha + new


XML_RE = re.compile(r"#([0-9A-Fa-f]{8}|[0-9A-Fa-f]{6})\b")
KT_RE = re.compile(r"0x([0-9A-Fa-f]{8})\b")
KT_PARSE_RE = re.compile(r'(parseColor\(\s*")#([0-9A-Fa-f]{8}|[0-9A-Fa-f]{6})(")')


def process(path, kotlin, write, stats):
    s = open(path, encoding="utf-8").read()
    changed = 0

    def sub_hex(prefix):
        def f(m):
            nonlocal changed
            new = convert(m.group(1))
            if new is None:
                return m.group(0)
            stats[m.group(1).upper()] += 1
            changed += 1
            return prefix + (new if m.group(1).isupper() or not m.group(1).isalpha() else new)
        return f

    if kotlin:
        s2 = KT_RE.sub(sub_hex("0x"), s)

        def fp(m):
            nonlocal changed
            new = convert(m.group(2))
            if new is None:
                return m.group(0)
            stats[m.group(2).upper()] += 1
            changed += 1
            return m.group(1) + "#" + new + m.group(3)

        s2 = KT_PARSE_RE.sub(fp, s2)
    else:
        s2 = XML_RE.sub(sub_hex("#"), s)
    if changed and write:
        open(path, "w", encoding="utf-8").write(s2)
    return changed


def files():
    for d in RES_DIRS:
        base = os.path.join(MAIN, d)
        if not os.path.isdir(base):
            continue
        for n in sorted(os.listdir(base)):
            if n.endswith(".xml") and n not in RES_SKIP:
                yield os.path.join(base, n), False
    for dp, _, fs in os.walk(os.path.join(MAIN, KOTLIN_DIR)):
        for n in sorted(fs):
            if n.endswith(".kt"):
                yield os.path.join(dp, n), True


def main():
    write = "--write" in sys.argv
    stats = Counter()
    per_file = []
    for path, kt in files():
        n = process(path, kt, write, stats)
        if n:
            per_file.append((n, os.path.relpath(path, ROOT)))
    print(f"{'rewrote' if write else 'would rewrite'} {sum(stats.values())} literals in {len(per_file)} files")
    for n, p in sorted(per_file, reverse=True)[:12]:
        print(f"  {n:4d}  {p}")
    print("most frequent:", ", ".join(f"{k}→{convert(k)}×{v}" for k, v in stats.most_common(14)))


if __name__ == "__main__":
    main()
