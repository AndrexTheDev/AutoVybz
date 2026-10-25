#!/usr/bin/env python3
"""Static site generator for the AutoVybz landing page (GitHub Pages).

Stdlib only. Usage:
    python3 site/build.py [--out site/_site] [--stars N]

Languages live in site/src/i18n/<lang>.json, legal texts in site/src/legal/<lang>/<page>.html.
English is served at the root, every other language under /<lang>/. The GitHub star count is
fetched at build time (no request from the visitor's browser) – set GITHUB_TOKEN in CI.
"""
from __future__ import annotations

import argparse
import datetime as _dt
import hashlib
import html
import json
import os
import re
import shutil
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SRC = ROOT / "src"
STATIC = ROOT / "static"

SITE_URL = os.environ.get("AVZ_SITE_URL", "https://andrexthedev.github.io/AutoVybz/")
REPO = "https://github.com/AndrexTheDev/AutoVybz"
REPO_API = "https://api.github.com/repos/AndrexTheDev/AutoVybz"
EMAIL = "hippie.highho@gmail.com"
LANGS = os.environ.get("AVZ_LANGS", "en,de,es").split(",")  # first = default (root)
LEGAL_PAGES = ["imprint", "privacy", "terms", "disclaimer"]
LEGAL_DATE = "2026-10-24"

WALLETS = [
    ("SOL", "Solana", "79KsqtJJdhKFJ9woxnYgtf3nq7HxQveafWBCtC3mxWi8", "sol"),
    ("BTC", "Bitcoin", "bc1qeqzrlfg3edrydk4s0hecakc82gp26n5p7hkc7f", "btc"),
    ("ETH / EVM", "Ethereum & EVM chains", "0xBC3fab34f69bc9f6661608C3FB36dDdC313C42F7", "eth"),
]

TOKEN = re.compile(r"\{\{(?:(\w+):)?([\w.@]+)\}\}")


# ---------------------------------------------------------------- helpers
def esc(s: str) -> str:
    return html.escape(str(s), quote=True)


def strip_tags(s: str) -> str:
    return re.sub(r"<[^>]+>", "", str(s))


def lookup(ctx: dict, path: str):
    cur = ctx
    for part in path.split("."):
        if isinstance(cur, dict) and part in cur:
            cur = cur[part]
        else:
            raise KeyError(path)
    return cur


def app_version() -> str:
    gradle = (ROOT.parent / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    m = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)
    return m.group(1) if m else "dev"


def fetch_stars() -> str:
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "autovybz-site-build"}
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    try:
        with urllib.request.urlopen(urllib.request.Request(REPO_API, headers=headers), timeout=10) as r:
            n = int(json.load(r).get("stargazers_count", 0))
        return f"{n / 1000:.1f}k".replace(".0k", "k") if n >= 1000 else str(n)
    except Exception as e:  # offline build: no number rather than a fake one
        print(f"  (star count unavailable: {e})")
        return "–"


# ---------------------------------------------------------------- list renderers
def share_links(t: dict, url: str) -> str:
    text = f'{t["share"]["text"]} {t["share"]["tags"]}'
    q = urllib.parse.quote
    links = [
        ("whatsapp", "WhatsApp", f"https://wa.me/?text={q(text + ' ' + url)}"),
        ("telegram", "Telegram", f"https://t.me/share/url?url={q(url)}&text={q(text)}"),
        ("x", "X", f"https://x.com/intent/post?text={q(text)}&url={q(url)}"),
        ("bluesky", "Bluesky", f"https://bsky.app/intent/compose?text={q(text + ' ' + url)}"),
        ("reddit", "Reddit", f"https://www.reddit.com/submit?url={q(url)}&title={q(t['share']['text'])}"),
        ("facebook", "Facebook", f"https://www.facebook.com/sharer/sharer.php?u={q(url)}"),
        ("linkedin", "LinkedIn", f"https://www.linkedin.com/sharing/share-offsite/?url={q(url)}"),
        ("email", "E-mail", f"mailto:?subject={q('AutoVybz')}&body={q(text + ' ' + url)}"),
    ]
    out = [f'<button class="sh sh-native" data-share-native hidden>📲 {esc(t["share"]["native"])}</button>']
    out += [f'<a class="sh sh-{k}" href="{esc(h)}" target="_blank" rel="noopener nofollow" data-share>{esc(n)}</a>'
            for k, n, h in links]
    out.append(f'<button class="sh sh-copy" data-copy-link>🔗 {esc(t["share"]["copy"])}</button>')
    return "".join(out)


def render_lists(t: dict, ctx: dict) -> dict:
    L = {}
    L["ticker"] = "".join(f"<span>{esc(x)}</span>" for x in t["ticker"])
    L["facts"] = "".join(
        f'<div class="fact reveal"><div class="fact-n"><b data-countup="{esc(f["n"])}">{esc(f["n"])}</b>'
        f'<span>{esc(f["u"])}</span></div><p>{esc(f["t"])}</p></div>' for f in t["problem"]["facts"])
    L["steps"] = "".join(
        f'<div class="step reveal"><span class="step-n">{i + 1}</span><span class="step-i">{s["i"]}</span>'
        f'<h3>{s["t"]}</h3><p>{s["d"]}</p></div>' for i, s in enumerate(t["how"]["steps"]))
    L["timeline"] = "".join(
        f'<span class="tl tl-{p["c"]}" style="flex:{p["w"]}">{esc(p["t"])}</span>' for p in t["how"]["timeline"])
    L["features"] = "".join(
        f'<div class="card reveal"><span class="card-i">{f["i"]}</span><h3>{f["t"]}</h3><p>{f["d"]}</p></div>'
        for f in t["features"]["items"])
    cols = t["compare"]["cols"]
    head = "<thead><tr>" + "".join(
        f'<th{" class=hl" if i == 3 else ""} scope="col">{c}</th>' for i, c in enumerate(cols)) + "</tr></thead>"
    body = "<tbody>" + "".join(
        "<tr>" + "".join((f'<th scope="row">{c}</th>' if i == 0 else f'<td{" class=hl" if i == 3 else ""}>{c}</td>')
                         for i, c in enumerate(r)) + "</tr>" for r in t["compare"]["rows"]) + "</tbody>"
    L["compare"] = head + body
    L["roast_types"] = "".join(f'<button class="chip" data-value="{k}">{esc(v)}</button>'
                               for k, v in t["roast"]["types"].items())
    L["roast_places"] = "".join(f'<button class="chip" data-value="{k}">{esc(v)}</button>'
                                for k, v in t["roast"]["places"].items())
    L["challenge"] = "".join(f"<li>{esc(s)}</li>" for s in t["challenge"]["steps"])
    L["share"] = share_links(t, ctx["home_url"])
    L["faq"] = "".join(
        f'<details class="qa reveal"><summary>{esc(f["q"])}</summary><p>{f["a"]}</p></details>'
        for f in t["faq"]["items"])
    L["wallets"] = "".join(
        f'<div class="wallet reveal"><img src="{ctx["base"]}assets/img/qr-{key}.svg" width="132" height="132" '
        f'alt="QR {esc(sym)}" loading="lazy"><div class="w-body"><b>{esc(sym)}</b><small>{esc(name)}</small>'
        f'<code>{esc(addr)}</code><button class="btn btn-sm btn-gold" data-copy="{esc(addr)}">'
        f'{esc(t["donate"]["copy"])}</button></div></div>' for sym, name, addr, key in WALLETS)
    return L


# ---------------------------------------------------------------- page assembly
def page_path(lang: str, page: str) -> str:
    """Site-relative output path, e.g. 'index.html', 'de/privacy.html'."""
    prefix = "" if lang == LANGS[0] else f"{lang}/"
    return prefix + f"{page}.html"


def page_url(lang: str, page: str) -> str:
    p = page_path(lang, page)
    return SITE_URL + (p[:-len("index.html")] if p.endswith("index.html") else p)


def jsonld(t: dict, lang: str, version: str) -> str:
    app = {
        "@context": "https://schema.org", "@type": "SoftwareApplication", "name": "AutoVybz",
        "operatingSystem": "Android 9+", "applicationCategory": "MultimediaApplication",
        "description": t["meta"]["description"], "url": page_url(lang, "index"), "inLanguage": lang,
        "softwareVersion": version, "license": "https://www.gnu.org/licenses/gpl-3.0.html",
        "isAccessibleForFree": True, "image": SITE_URL + "assets/img/logo-512.png",
        "author": {"@type": "Person", "name": "AndrexTheDev", "email": EMAIL},
        "offers": {"@type": "Offer", "price": "0", "priceCurrency": "EUR"},
        "codeRepository": REPO,
    }
    faq = {
        "@context": "https://schema.org", "@type": "FAQPage",
        "mainEntity": [{"@type": "Question", "name": f["q"],
                        "acceptedAnswer": {"@type": "Answer", "text": strip_tags(f["a"])}}
                       for f in t["faq"]["items"]],
    }
    return "\n".join(f'<script type="application/ld+json">{json.dumps(o, ensure_ascii=False)}</script>'
                     for o in (app, faq))


def render(template: str, ctx: dict, lists: dict, partials: dict) -> str:
    # includes first (they contain tokens themselves)
    template = re.sub(r"\{\{include:(\w+)\}\}", lambda m: partials[m.group(1)], template)

    def sub(m: re.Match) -> str:
        mode, key = m.group(1), m.group(2)
        if mode == "list":
            return lists[key]
        if mode == "json":
            return json.dumps(lookup(ctx, key), ensure_ascii=False).replace("</", "<\\/")
        val = lookup(ctx, key)
        if mode == "a":
            return esc(strip_tags(val))
        if mode is None:
            return str(val)
        raise ValueError(f"unknown token mode {mode}")

    out = TOKEN.sub(sub, template)
    left = re.findall(r"\{\{[^}]*\}\}", out)
    if left:
        raise ValueError(f"unresolved tokens: {left[:5]}")
    return out


def build(out: Path, stars: str | None) -> None:
    version = app_version()
    stars = stars if stars is not None else fetch_stars()
    texts = {l: json.loads((SRC / "i18n" / f"{l}.json").read_text(encoding="utf-8")) for l in LANGS}
    partials = {p.stem: p.read_text(encoding="utf-8") for p in (SRC / "partials").glob("*.html")}
    tpl_index = (SRC / "index.html").read_text(encoding="utf-8")
    tpl_legal = (SRC / "legal.html").read_text(encoding="utf-8")
    tpl_404 = (SRC / "404.html").read_text(encoding="utf-8")

    if out.exists():
        shutil.rmtree(out)
    shutil.copytree(STATIC, out / "assets")
    css_js = (STATIC / "style.css").read_bytes() + (STATIC / "app.js").read_bytes()
    asset_v = hashlib.sha1(css_js).hexdigest()[:8]
    site_path = urllib.parse.urlparse(SITE_URL).path  # e.g. /AutoVybz/

    def hreflang(page: str) -> str:
        tags = [f'<link rel="alternate" hreflang="{l}" href="{page_url(l, page)}">' for l in LANGS]
        tags.append(f'<link rel="alternate" hreflang="x-default" href="{page_url(LANGS[0], page)}">')
        return "\n".join(tags)

    def switcher(lang: str, page: str, base: str) -> str:
        def href(l: str) -> str:
            p = base + page_path(l, page)
            p = p[:-len("index.html")] if p.endswith("index.html") else p
            return p or "./"
        return "".join(
            f'<a href="{href(l)}" hreflang="{l}" lang="{l}" data-lang="{l}"'
            f'{" aria-current=true" if l == lang else ""}>{esc(texts[l]["lang_name"])}</a>' for l in LANGS)

    sitemap = []
    for lang in LANGS:
        t = texts[lang]
        nested = lang != LANGS[0]
        base = "../" if nested else ""
        common = dict(t)
        common.update(
            lang=lang, LANG=lang.upper(), locale=t["locale"], base=base, base_lang="",
            home="./", site_url=SITE_URL, repo=REPO, email=EMAIL, stars=stars,
            version=version, year=str(_dt.date.today().year), asset_v=asset_v,
            home_url=page_url(lang, "index"),
            og_alternates="\n".join(f'<meta property="og:locale:alternate" content="{texts[l]["locale"]}">'
                                    for l in LANGS if l != lang),
            js_bundle=dict(t["js"], lang=lang, site_url=page_url(lang, "index"),
                           share_text=t["share"]["text"], share_tags=t["share"]["tags"],
                           langs=LANGS, default_lang=LANGS[0], site_path=site_path),
        )
        # ---- home
        ctx = dict(common, page_title=t["meta"]["title"], page_desc=t["meta"]["description"],
                   og_title=t["meta"]["og_title"], og_desc=t["meta"]["og_desc"], robots="index,follow",
                   page_url=page_url(lang, "index"), hreflang=hreflang("index"),
                   lang_switcher=switcher(lang, "index", base), jsonld=jsonld(t, lang, version))
        lists = render_lists(t, ctx)
        write(out / page_path(lang, "index"), render(tpl_index, ctx, lists, partials))
        sitemap.append("index")
        # ---- legal pages
        for page in LEGAL_PAGES:
            body = (SRC / "legal" / lang / f"{page}.html").read_text(encoding="utf-8")
            body = body.replace("{{email}}", EMAIL).replace("{{repo}}", REPO).replace("{{site_url}}", SITE_URL)
            title = t["legal"][page]
            lctx = dict(common, page_title=f"{title} – AutoVybz", page_desc=f"AutoVybz – {title}",
                        og_title=f"{title} – AutoVybz", og_desc=t["meta"]["og_desc"], robots="index,follow",
                        page_url=page_url(lang, page), hreflang=hreflang(page),
                        lang_switcher=switcher(lang, page, base), jsonld="",
                        legal_title=title, legal_date=LEGAL_DATE, legal_body=body)
            write(out / page_path(lang, page), render(tpl_legal, lctx, {}, partials))
    # ---- 404 (served for any path → absolute asset URLs; default language, JS offers others)
    t = texts[LANGS[0]]
    ctx404 = dict(t, lang=LANGS[0], LANG=LANGS[0].upper(), locale=t["locale"], base=site_path,
                  base_lang=site_path, home=site_path, site_url=SITE_URL, repo=REPO, email=EMAIL,
                  stars=stars, version=version, year=str(_dt.date.today().year), asset_v=asset_v,
                  page_title=t["notfound"]["title"], page_desc=t["notfound"]["text"],
                  og_title=t["meta"]["og_title"], og_desc=t["meta"]["og_desc"], robots="noindex",
                  page_url=SITE_URL, hreflang="", og_alternates="", jsonld="",
                  lang_switcher=switcher(LANGS[0], "index", site_path),
                  js_bundle=dict(t["js"], lang=LANGS[0], site_url=SITE_URL, share_text=t["share"]["text"],
                                 share_tags=t["share"]["tags"], langs=LANGS, default_lang=LANGS[0],
                                 site_path=site_path, is404=True))
    write(out / "404.html", render(tpl_404, ctx404, {}, partials))

    # ---- sitemap / robots / manifest / nojekyll
    urls = []
    for page in ["index"] + LEGAL_PAGES:
        alts = "".join(f'<xhtml:link rel="alternate" hreflang="{l}" href="{page_url(l, page)}"/>' for l in LANGS)
        for l in LANGS:
            pr = "1.0" if page == "index" else "0.3"
            urls.append(f"<url><loc>{page_url(l, page)}</loc><lastmod>{_dt.date.today()}</lastmod>"
                        f"<priority>{pr}</priority>{alts}</url>")
    write(out / "sitemap.xml",
          '<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" '
          'xmlns:xhtml="http://www.w3.org/1999/xhtml">' + "".join(urls) + "</urlset>\n")
    write(out / "robots.txt", f"User-agent: *\nAllow: /\nSitemap: {SITE_URL}sitemap.xml\n")
    manifest = {
        "name": "AutoVybz", "short_name": "AutoVybz", "description": texts[LANGS[0]]["meta"]["og_desc"],
        "start_url": site_path, "scope": site_path, "display": "standalone",
        "background_color": "#161C21", "theme_color": "#161C21",
        "icons": [{"src": f"{site_path}assets/img/logo-192.png", "sizes": "192x192", "type": "image/png"},
                  {"src": f"{site_path}assets/img/logo-512.png", "sizes": "512x512", "type": "image/png"}],
    }
    write(out / "manifest.webmanifest", json.dumps(manifest, ensure_ascii=False, indent=1))
    write(out / ".nojekyll", "")
    n = sum(1 for _ in out.rglob("*.html"))
    print(f"built {n} pages ({', '.join(LANGS)}) → {out}  [stars {stars}, v{version}]")


def write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(ROOT / "_site"))
    ap.add_argument("--stars", default=None, help="override star count (skip API call)")
    a = ap.parse_args()
    build(Path(a.out), a.stars)
