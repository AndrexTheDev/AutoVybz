#!/usr/bin/env python3
"""Static audit of the built landing page (site/build.py output).

Checks per page: doctype, <html lang> vs. folder, unique <title>, meta description/viewport/charset,
canonical + hreflang alternates (en/de/es/x-default), exactly one <h1> and no skipped heading levels,
alt text on images, labelled links/buttons, duplicate ids, internal links + #anchors resolve, https-only
external links, rel=noopener on target=_blank, no template leftovers, legal pages linked, page weight.
Site-wide: i18n key parity, sitemap/robots/manifest validity, donation addresses + contact on the pages.

Usage: site_audit.py <site-dir> [--markdown out.md]   (exit 1 on errors)
"""
import json, os, re, sys, xml.etree.ElementTree as ET
from html.parser import HTMLParser
from urllib.parse import urlparse

ADDRESSES = ["79KsqtJJdhKFJ9woxnYgtf3nq7HxQveafWBCtC3mxWi8", "bc1qeqzrlfg3edrydk4s0hecakc82gp26n5p7hkc7f",
             "0xBC3fab34f69bc9f6661608C3FB36dDdC313C42F7"]
EMAIL = "hippie.highho@gmail.com"
LANGS = ["en", "de", "es"]
LEGAL = ["imprint", "privacy", "terms", "disclaimer"]


class Page(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.lang = None; self.title = ""; self.in_title = False; self.meta = {}; self.links = []
        self.ids = []; self.headings = []; self.imgs = []; self.anchors = []; self.buttons = []
        self.alternates = {}; self.canonical = None; self._a = None; self._btn = None; self.text = []
        self.scripts = []; self.styles = []

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if "id" in a: self.ids.append(a["id"])
        if tag == "html": self.lang = a.get("lang")
        elif tag == "title": self.in_title = True
        elif tag == "meta":
            k = a.get("name") or a.get("property") or ("charset" if "charset" in a else None)
            if k: self.meta[k] = a.get("content", a.get("charset", ""))
        elif tag == "link":
            if a.get("rel") == "alternate" and a.get("hreflang"): self.alternates[a["hreflang"]] = a.get("href")
            if a.get("rel") == "canonical": self.canonical = a.get("href")
            if a.get("href"): self.links.append(("link", a["href"], a))
            if a.get("rel") == "stylesheet": self.styles.append(a.get("href"))
        elif tag == "script" and a.get("src"):
            self.links.append(("script", a["src"], a)); self.scripts.append(a["src"])
        elif tag == "img":
            self.imgs.append(a); self.links.append(("img", a.get("src", ""), a))
        elif tag == "a":
            self._a = {"attrs": a, "text": ""}
            if a.get("href"): self.links.append(("a", a["href"], a))
        elif tag == "button":
            self._btn = {"attrs": a, "text": ""}
        elif re.fullmatch(r"h[1-6]", tag):
            self.headings.append(int(tag[1]))

    def handle_endtag(self, tag):
        if tag == "title": self.in_title = False
        elif tag == "a" and self._a is not None: self.anchors.append(self._a); self._a = None
        elif tag == "button" and self._btn is not None: self.buttons.append(self._btn); self._btn = None

    def handle_data(self, data):
        if self.in_title: self.title += data
        if self._a is not None: self._a["text"] += data
        if self._btn is not None: self._btn["text"] += data
        self.text.append(data)


def audit(site):
    errors, warnings, info = [], [], []
    pages = {}
    for dp, _, fs in os.walk(site):
        for f in fs:
            if f.endswith(".html"):
                rel = os.path.relpath(os.path.join(dp, f), site)
                raw = open(os.path.join(dp, f), encoding="utf-8").read()
                p = Page(); p.feed(raw); p.raw = raw
                pages[rel] = p
    if not pages:
        return ["no HTML pages found"], [], []
    titles = {}
    for rel, p in sorted(pages.items()):
        expected_lang = rel.split("/")[0] if "/" in rel else "en"
        where = rel
        if not p.raw.lstrip().lower().startswith("<!doctype html>"): errors.append(f"{where}: missing <!doctype html>")
        if p.lang != expected_lang: errors.append(f"{where}: <html lang={p.lang!r}> but expected {expected_lang!r}")
        if not p.title.strip(): errors.append(f"{where}: empty <title>")
        titles.setdefault(p.title.strip(), []).append(rel)
        for m in ("description", "viewport", "charset"):
            if m not in p.meta: errors.append(f"{where}: missing meta {m}")
        if rel != "404.html":
            if not p.canonical: warnings.append(f"{where}: no canonical link")
            missing = [l for l in LANGS + ["x-default"] if l not in p.alternates]
            if missing: errors.append(f"{where}: hreflang alternates missing {missing}")
            if "og:image" not in p.meta: warnings.append(f"{where}: no og:image")
        if p.headings.count(1) != 1: errors.append(f"{where}: {p.headings.count(1)} <h1> elements (need exactly 1)")
        for a, b in zip(p.headings, p.headings[1:]):
            if b > a + 1: errors.append(f"{where}: heading level skips h{a} -> h{b}"); break
        for img in p.imgs:
            if "alt" not in img: errors.append(f"{where}: <img src={img.get('src')}> without alt")
            if not (img.get("width") and img.get("height")): warnings.append(f"{where}: <img src={img.get('src')}> without width/height (layout shift)")
        for a in p.anchors:
            if not a["text"].strip() and not a["attrs"].get("aria-label") and not a["attrs"].get("title"):
                if "<img" not in p.raw: errors.append(f"{where}: link {a['attrs'].get('href')} without text/aria-label")
            if a["attrs"].get("target") == "_blank" and "noopener" not in (a["attrs"].get("rel") or ""):
                errors.append(f"{where}: target=_blank without rel=noopener ({a['attrs'].get('href')})")
        for b in p.buttons:
            if not b["text"].strip() and not b["attrs"].get("aria-label"): errors.append(f"{where}: button without text/aria-label")
        dup = {i for i in p.ids if p.ids.count(i) > 1}
        if dup: errors.append(f"{where}: duplicate ids {sorted(dup)}")
        visible = re.sub(r"<script\b.*?</script>", "", p.raw, flags=re.S)  # JSON-LD legitimately contains "}}"
        if re.search(r"\{\{\s*[\w.]+\s*\}\}|\{\{|\[\[\s*\w+\s*\]\]", visible): errors.append(f"{where}: unreplaced template placeholder")
        # case-sensitive: Spanish "todo" (= all) is fine
        if re.search(r"\bTODO\b|\bFIXME\b|[Ll]orem ipsum", visible): errors.append(f"{where}: TODO / placeholder text")
        for m in re.finditer(r'<script type="application/ld\+json">(.*?)</script>', p.raw, re.S):
            try: json.loads(m.group(1))
            except Exception as e: errors.append(f"{where}: invalid JSON-LD ({e})")
        weight = len(p.raw.encode())
        for kind, href, attrs in p.links:
            u = urlparse(href)
            if u.scheme in ("mailto", "tel", "data", "bitcoin", "ethereum", "solana"): continue
            if u.scheme == "http": errors.append(f"{where}: insecure http link {href}")
            if u.scheme or href.startswith("//"): continue
            if not u.path and u.fragment:
                if u.fragment not in p.ids: errors.append(f"{where}: anchor #{u.fragment} not found")
                continue
            base = os.path.dirname(os.path.join(site, rel))
            target = os.path.normpath(os.path.join(site, u.path.lstrip("/")) if u.path.startswith("/") else os.path.join(base, u.path))
            if os.path.isdir(target): target = os.path.join(target, "index.html")
            if not os.path.exists(target):
                # GitHub Pages project sites live under /AutoVybz/
                alt = os.path.normpath(os.path.join(site, re.sub(r"^/AutoVybz/", "", u.path)))
                if os.path.isdir(alt): alt = os.path.join(alt, "index.html")
                if not os.path.exists(alt): errors.append(f"{where}: broken {kind} link {href}"); continue
                target = alt
            if kind in ("img", "script") or (kind == "link" and attrs.get("rel") in ("stylesheet", "preload")):
                weight += os.path.getsize(target)
            if u.fragment and target.endswith(".html"):
                tp = pages.get(os.path.relpath(target, site))
                if tp and u.fragment not in tp.ids: errors.append(f"{where}: anchor {href} not found in target")
        (errors if weight > 1_000_000 else warnings if weight > 500_000 else info).append(f"{where}: page weight {weight/1024:.0f} KB")
        if rel.endswith("index.html"):
            for addr in ADDRESSES:
                if addr not in p.raw: errors.append(f"{where}: donation address {addr[:10]}… missing")
        if rel != "404.html":
            for lg in LEGAL:
                if not re.search(rf'href="[^"]*{lg}\.html"', p.raw): errors.append(f"{where}: no link to {lg}.html")
        if os.path.basename(rel) == "imprint.html" and EMAIL not in p.raw: errors.append(f"{where}: contact e-mail missing")
    for t, rels in titles.items():
        if len(rels) > 1: warnings.append(f"title {t!r} used by {rels}")

    # i18n parity
    i18n = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "site", "src", "i18n")
    if os.path.isdir(i18n):
        def keys(d, pre=""):
            out = set()
            for k, v in d.items():
                out |= keys(v, pre + k + ".") if isinstance(v, dict) else {pre + k}
            return out
        sets = {l: keys(json.load(open(os.path.join(i18n, f"{l}.json"), encoding="utf-8"))) for l in LANGS}
        for l in LANGS[1:]:
            if sets[l] != sets["en"]:
                errors.append(f"i18n {l}: missing {sorted(sets['en'] - sets[l])[:10]} extra {sorted(sets[l] - sets['en'])[:10]}")
        info.append(f"i18n: {len(sets['en'])} keys in en/de/es")
    # sitemap / robots / manifest
    sm = os.path.join(site, "sitemap.xml")
    try:
        locs = [e.text for e in ET.parse(sm).getroot().iter() if e.tag.endswith("loc")]
        listed = {re.sub(r"^https?://[^/]+/AutoVybz/", "", l) or "index.html" for l in locs}
        listed = {l + "index.html" if l.endswith("/") else l for l in listed}
        for rel in pages:
            if rel != "404.html" and rel not in listed: warnings.append(f"sitemap: {rel} not listed")
        info.append(f"sitemap: {len(locs)} URLs")
    except Exception as e:
        errors.append(f"sitemap.xml invalid: {e}")
    robots = os.path.join(site, "robots.txt")
    if not os.path.exists(robots) or "sitemap" not in open(robots).read().lower(): errors.append("robots.txt missing or without Sitemap")
    try:
        man = json.load(open(os.path.join(site, "manifest.webmanifest")))
        for icon in man.get("icons", []):
            path = os.path.join(site, re.sub(r"^(/AutoVybz)?/", "", icon["src"]))
            if not os.path.exists(path): errors.append(f"manifest icon missing: {icon['src']}")
        for k in ("name", "short_name", "theme_color", "background_color"):
            if k not in man: errors.append(f"manifest: no {k}")
    except Exception as e:
        errors.append(f"manifest.webmanifest invalid: {e}")
    info.append(f"{len(pages)} pages audited")
    return errors, warnings, info


def main():
    site = sys.argv[1]
    md = sys.argv[sys.argv.index("--markdown") + 1] if "--markdown" in sys.argv else None
    errors, warnings, info = audit(site)
    weights = [int(m.group(1)) for i in info for m in [re.search(r"page weight (\d+) KB", i)] if m]
    heaviest = f"heaviest page {max(weights)} KB" if weights else ""
    summary = "; ".join([i for i in info if "pages" in i or "i18n" in i or "sitemap" in i] + ([heaviest] if heaviest else []))
    lines = [f"- {'✅' if not errors else '❌'} **Website audit**: {len(errors)} errors, {len(warnings)} warnings ({summary})"]
    lines += [f"  - ❌ {e}" for e in errors] + [f"  - ⚠️ {w}" for w in warnings[:40]]
    out = "\n".join(lines)
    print(out)
    if md: open(md, "w").write(out + "\n")
    if os.environ.get("GITHUB_ACTIONS") == "true":
        for e in errors: print(f"::error title=Website audit::{e}", file=sys.stderr)
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
