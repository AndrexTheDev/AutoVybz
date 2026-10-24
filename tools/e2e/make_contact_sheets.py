#!/usr/bin/env python3
"""Builds the temporary screenshot matrix: one HTML page (configs as columns, screens as rows) plus a
JPEG contact sheet per configuration. Usage: make_contact_sheets.py <shots-dir> <out-dir>"""
import html, os, re, sys
from collections import OrderedDict

def main():
    src, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    configs = sorted(d for d in os.listdir(src) if os.path.isdir(os.path.join(src, d)))
    screens = OrderedDict()
    for c in configs:
        for f in sorted(os.listdir(os.path.join(src, c))):
            key = re.sub(r"^t\d+_\w+?_\d+_", "", f[:-4]) if f.endswith(".jpg") else None
            if key:
                screens.setdefault(f[:-4], {})[c] = os.path.relpath(os.path.join(src, c, f), out)
    rows = []
    for shot, per in screens.items():
        cells = "".join(f'<td>{"<a href=%r><img src=%r loading=lazy></a>" % (per[c], per[c]) if c in per else "—"}</td>' for c in configs)
        rows.append(f"<tr><th>{html.escape(shot)}</th>{cells}</tr>")
    page = f"""<!doctype html><meta charset=utf-8><title>AutoVybz screenshot matrix (temporary)</title>
<style>body{{background:#121212;color:#ededed;font:14px sans-serif}}table{{border-collapse:collapse}}
td,th{{border:1px solid #333;padding:4px;vertical-align:top}}img{{width:180px}}th{{position:sticky;top:0;background:#181818}}
tbody th{{position:sticky;left:0;max-width:160px;word-break:break-all;text-align:left;font-weight:normal}}</style>
<h1 style=color:#FED100>AutoVybz – screenshot matrix (temporary, not committed)</h1>
<p>{len(screens)} screens × {len(configs)} configurations</p>
<table><thead><tr><th>screen</th>{''.join(f'<th>{html.escape(c)}</th>' for c in configs)}</tr></thead><tbody>{''.join(rows)}</tbody></table>"""
    open(os.path.join(out, "index.html"), "w").write(page)
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        print("Pillow missing: HTML only"); return
    for c in configs:
        files = sorted(f for f in os.listdir(os.path.join(src, c)) if f.endswith(".jpg"))
        if not files:
            continue
        thumbs = [Image.open(os.path.join(src, c, f)).convert("RGB") for f in files]
        w = 240; scale = [w / t.width for t in thumbs]; h = max(int(t.height * s) for t, s in zip(thumbs, scale)) + 18
        cols = 6; rows_n = (len(thumbs) + cols - 1) // cols
        sheet = Image.new("RGB", (cols * (w + 8) + 8, rows_n * (h + 8) + 40), (18, 18, 18))
        d = ImageDraw.Draw(sheet); d.text((8, 10), f"AutoVybz – {c} – {len(files)} screens", fill=(254, 209, 0))
        for i, (t, f, s) in enumerate(zip(thumbs, files, scale)):
            x = 8 + (i % cols) * (w + 8); y = 36 + (i // cols) * (h + 8)
            sheet.paste(t.resize((w, int(t.height * s))), (x, y + 16)); d.text((x, y + 2), f[:-4][:38], fill=(168, 168, 168))
        sheet.save(os.path.join(out, f"sheet_{c}.jpg"), quality=80)
    print(f"{len(configs)} configs, {len(screens)} screens -> {out}")

if __name__ == "__main__":
    main()
