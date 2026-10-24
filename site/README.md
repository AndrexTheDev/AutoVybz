# AutoVybz landing page

Static, multilingual landing page for GitHub Pages → **https://andrexthedev.github.io/AutoVybz/**

- No framework, no npm, no cookies, no external fonts, no third-party scripts, no tracking.
- `build.py` (Python stdlib only) renders `src/` into `_site/`; `.github/workflows/pages.yml` builds on every
  change and deploys from `main` (plus a daily rebuild to refresh the star count).

```bash
python3 site/build.py            # → site/_site   (add --stars 0 to skip the GitHub API call)
python3 site/tools/serve.py 8080 # preview at http://localhost:8080/AutoVybz/ (mimics Pages incl. 404)
python3 site/tools/make_assets.py  # regenerate logos, favicons, OG images, QR codes (needs pillow, qrcode, matplotlib)
```

## Structure

| Path | What |
|---|---|
| `src/i18n/<lang>.json` | all page texts (EN master, DE, ES). Keys must be identical in every file. |
| `src/index.html`, `src/legal.html`, `src/404.html`, `src/partials/` | templates (`{{key}}`, `{{a:key}}` attribute-escaped, `{{list:name}}`, `{{json:key}}`, `{{include:partial}}`) |
| `src/legal/<lang>/{imprint,privacy,terms,disclaimer}.html` | legal texts per language |
| `static/style.css`, `static/app.js` | design + interactivity (Web Audio demo, Speaker Roast, share, easter eggs) |
| `static/img/` | generated images (committed) |

## Adding a language

1. Copy `src/i18n/en.json` → `src/i18n/<lang>.json`, translate (keep keys).
2. Copy `src/legal/en/` → `src/legal/<lang>/`, translate.
3. Add the code to `LANGS` in `build.py` and to the OG loop in `tools/make_assets.py`, run both.

Root `/` serves English; on the first visit the page follows the browser language (`navigator.languages`)
unless the visitor picked a language before (stored in `localStorage` key `avz-lang`).

## Growth mechanics (all honest – no fake numbers)

Interactive level-matched A/B demo · Speaker Roast with downloadable/shareable image card · #10SecondVybz
challenge · share links (no SDKs) + Web Share API · QR code for desktop → phone · live star count (build
time) · JSON-LD (SoftwareApplication + FAQPage), OG/Twitter cards per language, hreflang, sitemap ·
easter eggs (Konami code, type "vybz" or "bass") · tab-title nudge · sticky mobile CTA.

Deliberately **not** used: fake download counters, fake reviews/testimonials, fake scarcity or countdowns,
exit-intent pop-ups, tracking pixels – they are deceptive (and in the EU unlawful under the UCPD / UWG).

## Legal note

The legal notice contains name (pseudonym) and e-mail. Depending on your country and on whether the
project counts as "commercial" (e.g. German § 5 DDG, Spanish LSSI art. 10), a full legal notice may
require your **real name and a postal address** – add them to `src/legal/*/imprint.html` if that applies.
The texts are a careful template, not legal advice.
