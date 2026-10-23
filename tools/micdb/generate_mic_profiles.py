#!/usr/bin/env python3
"""
Generates app/src/main/assets/autovybz/mic_profiles.json.

IMPORTANT - data honesty:
  The archetype curves below are *engineering estimates* of typical bottom-port
  MEMS smartphone microphones (low-frequency roll-off of the port/ASIC high-pass,
  Helmholtz resonance of the acoustic port around 10-13 kHz). They are NOT lab
  measurements of the listed phones. Every entry is therefore tagged
  "confidence": "estimated".

  Device entries only *reference* an archetype ("curveRef"). When someone
  calibrates a real phone against a reference microphone (e.g. miniDSP UMIK-1
  with REW), add "curves" directly to that device entry and set
  "confidence": "measured" or "community" - inline curves always win over
  curveRef, and model-specific entries win over manufacturer-wide ones.
  See docs/MIC_PROFILES.md for the contribution workflow.
"""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "app" / "src" / "main" / "assets" / "autovybz" / "mic_profiles.json"

HF_TYPICAL = [[2000, 0.1], [3150, 0.3], [4000, 0.5], [5000, 0.8], [6300, 1.3], [8000, 2.0],
              [10000, 2.8], [12500, 3.2], [14000, 2.6], [16000, 1.2], [18000, -1.0], [20000, -3.5]]
HF_STRONG = [[2000, 0.2], [3150, 0.5], [4000, 0.8], [5000, 1.3], [6300, 2.0], [8000, 3.0],
             [10000, 4.2], [11500, 4.6], [13000, 3.6], [16000, 1.0], [18000, -2.0], [20000, -5.0]]
HF_MILD = [[2000, 0.0], [3150, 0.1], [4000, 0.2], [5000, 0.4], [6300, 0.7], [8000, 1.1],
           [10000, 1.6], [12500, 1.8], [14000, 1.4], [16000, 0.5], [18000, -1.0], [20000, -2.5]]

LF_UNPROCESSED = [[20, -5.0], [25, -3.5], [31.5, -2.4], [40, -1.5], [50, -0.9], [63, -0.5],
                  [80, -0.3], [100, -0.1], [200, 0.0], [1000, 0.0]]
LF_UNPROCESSED_FLAT = [[20, -2.5], [25, -1.8], [31.5, -1.2], [40, -0.7], [50, -0.4], [63, -0.2],
                       [80, -0.1], [100, 0.0], [200, 0.0], [1000, 0.0]]
# VOICE_RECOGNITION: Android CDD forbids AGC/NS here, but most HALs keep a voice high-pass.
LF_VOICE = [[20, -14.0], [25, -11.0], [31.5, -8.5], [40, -6.0], [50, -4.2], [63, -2.8],
            [80, -1.6], [100, -1.0], [125, -0.5], [160, -0.2], [200, 0.0], [1000, 0.0]]
LF_MIC = [[20, -18.0], [31.5, -12.0], [50, -7.0], [80, -3.5], [100, -2.4], [150, -1.0],
          [200, -0.4], [300, 0.0], [1000, 0.0]]


def curves(hf, lf_raw=LF_UNPROCESSED):
    return {
        "UNPROCESSED": lf_raw + hf,
        "VOICE_RECOGNITION": LF_VOICE + hf,
        "MIC": LF_MIC + hf,
    }


ARCHETYPES = {
    "mems-bottom-port": {
        "label": "Generic MEMS bottom-port microphone",
        "curves": curves(HF_TYPICAL),
    },
    "mems-bottom-port-strong-resonance": {
        "label": "MEMS bottom port, narrow acoustic channel (stronger HF resonance)",
        "curves": curves(HF_STRONG),
    },
    "mems-bottom-port-flat": {
        "label": "MEMS bottom port, wide channel / well-tuned (mild HF resonance)",
        "curves": curves(HF_MILD, LF_UNPROCESSED_FLAT),
    },
}

# (id, manufacturer, [model regexes], label, archetype)
DEVICES = [
    # Samsung
    ("samsung-galaxy-s-2021plus", "samsung", ["SM-S9\\d\\d.*", "SM-G99\\d.*"], "Samsung Galaxy S21-S25 series", "mems-bottom-port-flat"),
    ("samsung-galaxy-s-legacy", "samsung", ["SM-G9[5-8]\\d.*"], "Samsung Galaxy S8-S20 series", "mems-bottom-port"),
    ("samsung-galaxy-z", "samsung", ["SM-F\\d{3}.*"], "Samsung Galaxy Z Flip / Fold", "mems-bottom-port-strong-resonance"),
    ("samsung-galaxy-a", "samsung", ["SM-A\\d{3}.*"], "Samsung Galaxy A series", "mems-bottom-port"),
    ("samsung-galaxy-m", "samsung", ["SM-M\\d{3}.*", "SM-E\\d{3}.*"], "Samsung Galaxy M / F series", "mems-bottom-port"),
    ("samsung-generic", "samsung", [], "Samsung (other models)", "mems-bottom-port"),
    # Google
    ("google-pixel-6plus", "google", ["Pixel ([6-9]|1\\d).*"], "Google Pixel 6 and newer", "mems-bottom-port-flat"),
    ("google-pixel-legacy", "google", ["Pixel [2-5].*", "Pixel"], "Google Pixel 2-5", "mems-bottom-port"),
    ("google-generic", "google", [], "Google (other models)", "mems-bottom-port"),
    # Xiaomi / Redmi / POCO
    ("xiaomi-flagship", "xiaomi", ["(?i)(xiaomi |mi )?1[0-5].*", "(?i)2[0-9]{3}[0-9A-Z]{2,}[GCIY]"], "Xiaomi numbered / flagship series", "mems-bottom-port"),
    ("xiaomi-redmi-poco", "xiaomi", ["(?i).*(redmi|poco|M210|M200).*"], "Redmi / POCO", "mems-bottom-port-strong-resonance"),
    ("xiaomi-generic", "xiaomi", [], "Xiaomi (other models)", "mems-bottom-port"),
    # BBK family
    ("oneplus-generic", "oneplus", [], "OnePlus", "mems-bottom-port"),
    ("oppo-generic", "oppo", [], "OPPO", "mems-bottom-port"),
    ("realme-generic", "realme", [], "realme", "mems-bottom-port-strong-resonance"),
    ("vivo-generic", "vivo", [], "vivo", "mems-bottom-port"),
    # Others
    ("motorola-edge", "motorola", ["(?i)motorola edge.*", "(?i)moto(rola)? edge.*"], "Motorola Edge", "mems-bottom-port"),
    ("motorola-moto-g", "motorola", ["(?i)moto(rola)? g.*"], "Motorola Moto G", "mems-bottom-port-strong-resonance"),
    ("motorola-generic", "motorola", [], "Motorola (other models)", "mems-bottom-port"),
    ("sony-xperia", "sony", ["XQ-.*", "SO-.*", "SOG.*"], "Sony Xperia", "mems-bottom-port-flat"),
    ("sony-generic", "sony", [], "Sony (other models)", "mems-bottom-port"),
    ("nothing-generic", "nothing", [], "Nothing Phone", "mems-bottom-port"),
    ("fairphone", "fairphone", ["FP[3-6].*"], "Fairphone 3-6", "mems-bottom-port"),
    ("huawei-generic", "huawei", [], "Huawei", "mems-bottom-port"),
    ("honor-generic", "honor", [], "HONOR", "mems-bottom-port"),
    ("asus-generic", "asus", [], "ASUS (Zenfone / ROG Phone)", "mems-bottom-port"),
    ("hmd-nokia", "hmd global", [], "Nokia / HMD", "mems-bottom-port-strong-resonance"),
    ("lenovo-generic", "lenovo", [], "Lenovo", "mems-bottom-port"),
    ("zte-generic", "zte", [], "ZTE / nubia", "mems-bottom-port"),
    ("tecno-generic", "tecno", [], "TECNO", "mems-bottom-port-strong-resonance"),
    ("infinix-generic", "infinix", [], "Infinix", "mems-bottom-port-strong-resonance"),
    ("tcl-generic", "tcl", [], "TCL", "mems-bottom-port"),
    ("cmf-generic", "cmf", [], "CMF by Nothing", "mems-bottom-port"),
]


def main() -> None:
    data = {
        "schemaVersion": 1,
        "notice": ("All curves are ESTIMATED archetypes of typical MEMS smartphone microphones, "
                   "not lab measurements of the listed devices. Calibrated contributions welcome - "
                   "see docs/MIC_PROFILES.md."),
        "units": {"frequency": "Hz", "level": "dB relative to 1 kHz (what the mic adds)"},
        "default": "mems-bottom-port",
        "archetypes": ARCHETYPES,
        "devices": [
            {
                "id": i,
                "manufacturer": m,
                "models": models,
                "label": label,
                "curveRef": arch,
                "confidence": "estimated",
            }
            for (i, m, models, label, arch) in DEVICES
        ],
    }
    assert len({d["id"] for d in data["devices"]}) == len(DEVICES), "duplicate ids"
    for d in data["devices"]:
        assert d["curveRef"] in ARCHETYPES, d
    OUT.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(data, indent=2, ensure_ascii=False)
    # keep [frequency, dB] pairs on one line for readability / reviewable diffs
    text = re.sub(r"\[\s+(-?[\d.]+),\s+(-?[\d.]+)\s+\]", r"[\1, \2]", text)
    OUT.write_text(text + "\n", encoding="utf-8")
    print(f"wrote {OUT.relative_to(ROOT)}: {len(DEVICES)} devices, {len(ARCHETYPES)} archetypes")


if __name__ == "__main__":
    main()
