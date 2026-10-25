#!/usr/bin/env python3
"""
Generates app/src/main/assets/autovybz/mic_profiles.json.

IMPORTANT - data honesty:
  The archetype curves below are *engineering estimates* of typical MEMS
  smartphone microphones (low-frequency roll-off of the port/ASIC high-pass,
  Helmholtz resonance of the acoustic port). They are NOT lab measurements of
  the listed phones - no open per-model measurement database exists. Every
  entry is therefore tagged "confidence": "estimated".

  Device entries *reference* an archetype ("curveRef") chosen by the acoustic
  design class of the model/series (well-tuned flagship -> flat, narrow-port
  budget phone -> strong resonance, mesh-covered rugged port -> damped,
  tablet/long channel -> early resonance). When someone calibrates a real
  phone against a reference microphone (e.g. miniDSP UMIK-1 with REW), add
  "curves" directly to that device entry and set "confidence": "measured" or
  "community" - inline curves always win over curveRef. See
  docs/MIC_PROFILES.md for the contribution workflow.

Matching rules (MicProfileMatcher in the app):
  * The manufacturer is normalised (lowercase + the alias rules in
    MicProfiles.normaliseManufacturer, mirrored by normalise() below -
    keep the two in sync!).
  * Model regexes are matched case-insensitively against the *whole*
    Build.MODEL string. An entry with an empty model list is the
    manufacturer-wide fallback and loses to any entry with patterns.
  * Between several matching patterned entries of equal confidence, the
    FIRST one in this file wins - so per manufacturer, list specific
    entries before broader ones.

Every device entry carries example Build.MODEL strings; generating runs a
self-test that each example resolves to its own entry (catches shadowing
and ordering bugs). Patterns must stay in the regex subset shared by
Python `re` (this test) and java.util.regex (the app): literals, . * + ?
[..], \\d, groups, alternation, (?!...) lookahead.
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
# Mesh-covered / deeply recessed port (rugged phones): the screen over the port eats the
# resonance and rolls the top end off instead.
HF_DAMPED = [[2000, 0.0], [3150, 0.0], [4000, -0.2], [5000, -0.5], [6300, -0.9], [8000, -1.6],
             [10000, -2.8], [12500, -4.5], [16000, -7.0], [20000, -10.0]]
# Long acoustic channel (tablets, thick slabs): the Helmholtz peak moves down to ~7-8 kHz.
HF_EARLY = [[2000, 0.1], [3150, 0.4], [4000, 0.8], [5000, 1.5], [6300, 2.4], [7500, 3.2],
            [9000, 2.6], [11000, 1.0], [14000, -1.5], [16000, -3.0], [20000, -5.5]]

LF_UNPROCESSED = [[20, -5.0], [25, -3.5], [31.5, -2.4], [40, -1.5], [50, -0.9], [63, -0.5],
                  [80, -0.3], [100, -0.1], [200, 0.0], [1000, 0.0]]
LF_UNPROCESSED_FLAT = [[20, -2.5], [25, -1.8], [31.5, -1.2], [40, -0.7], [50, -0.4], [63, -0.2],
                       [80, -0.1], [100, 0.0], [200, 0.0], [1000, 0.0]]
# VOICE_RECOGNITION: Android CDD forbids AGC/NS here, but most HALs keep a voice high-pass.
LF_VOICE = [[20, -14.0], [25, -11.0], [31.5, -8.5], [40, -6.0], [50, -4.2], [63, -2.8],
            [80, -1.6], [100, -1.0], [125, -0.5], [160, -0.2], [200, 0.0], [1000, 0.0]]
LF_MIC = [[20, -18.0], [31.5, -12.0], [50, -7.0], [80, -3.5], [100, -2.4], [150, -1.0],
          [200, -0.4], [300, 0.0], [1000, 0.0]]

TYPICAL = "mems-bottom-port"
STRONG = "mems-bottom-port-strong-resonance"
FLAT = "mems-bottom-port-flat"
DAMPED = "mems-bottom-port-damped"
EARLY = "mems-early-resonance"


def curves(hf, lf_raw=LF_UNPROCESSED):
    return {
        "UNPROCESSED": lf_raw + hf,
        "VOICE_RECOGNITION": LF_VOICE + hf,
        "MIC": LF_MIC + hf,
    }


ARCHETYPES = {
    TYPICAL: {
        "label": "Generic MEMS bottom-port microphone",
        "curves": curves(HF_TYPICAL),
    },
    STRONG: {
        "label": "MEMS bottom port, narrow acoustic channel (stronger HF resonance)",
        "curves": curves(HF_STRONG),
    },
    FLAT: {
        "label": "MEMS bottom port, wide channel / well-tuned (mild HF resonance)",
        "curves": curves(HF_MILD, LF_UNPROCESSED_FLAT),
    },
    DAMPED: {
        "label": "MEMS port behind mesh / recessed channel (damped HF, rugged phones)",
        "curves": curves(HF_DAMPED),
    },
    EARLY: {
        "label": "MEMS microphone, long acoustic channel / tablet (resonance ~7-8 kHz)",
        "curves": curves(HF_EARLY),
    },
}


def dev(id_, manufacturer, models, label, archetype=None, *examples,
        curves=None, confidence="estimated", notes=None):
    """A device entry referencing an archetype (the normal case), or - for a real
    calibration - with inline `curves` and confidence "measured"/"community" (+
    notes describing the setup); archetype must then be None.
    examples: Build.MODEL strings (manufacturer = entry's), or (rawManufacturer, model)."""
    assert (archetype is None) != (curves is None), id_
    if confidence != "estimated":
        assert curves, f"{id_}: confidence '{confidence}' requires measured inline curves"
    d = {
        "id": id_, "manufacturer": manufacturer, "models": models, "label": label,
        "confidence": confidence,
        "examples": [(manufacturer, e) if isinstance(e, str) else e for e in examples],
    }
    if curves:
        d["curves"] = curves
    else:
        d["curveRef"] = archetype
    if notes:
        d["notes"] = notes
    return d


# Per manufacturer: foldables / specific series first, broad catch-alls later,
# the model-less manufacturer fallback last. Build.MODEL is a marketing name on
# Google/Motorola/most Chinese brands, a model code on Samsung/Sony/OPPO-family,
# mixed on Xiaomi/Huawei/HONOR.
DEVICES = [
    # ---------------------------------------------------------------- Samsung
    dev("samsung-galaxy-z-flip", "samsung", [r"SM-F7\d\d.*"], "Samsung Galaxy Z Flip series", STRONG, "SM-F741B", "SM-F721U1"),
    dev("samsung-galaxy-z-fold", "samsung", [r"SM-F9[0-9][0-9].*"], "Samsung Galaxy Z Fold series", STRONG, "SM-F956B", "SM-F916N"),
    dev("samsung-galaxy-s-fe", "samsung", [r"SM-(G990|S7[12]\d).*"], "Samsung Galaxy S FE series", TYPICAL, "SM-S721B", "SM-G990E"),
    dev("samsung-galaxy-s25", "samsung", [r"SM-S93\d.*"], "Samsung Galaxy S25 series", FLAT, "SM-S938B", "SM-S931U"),
    dev("samsung-galaxy-s24", "samsung", [r"SM-S92\d.*"], "Samsung Galaxy S24 series", FLAT, "SM-S928B", "SM-S921B"),
    dev("samsung-galaxy-s23", "samsung", [r"SM-S91\d.*"], "Samsung Galaxy S23 series", FLAT, "SM-S918B"),
    dev("samsung-galaxy-s22", "samsung", [r"SM-S90\d.*"], "Samsung Galaxy S22 series", FLAT, "SM-S908E"),
    dev("samsung-galaxy-s21", "samsung", [r"SM-G99[1-9].*"], "Samsung Galaxy S21 series", FLAT, "SM-G998B"),
    dev("samsung-galaxy-s", "samsung", [r"SM-S9\d\d.*"], "Samsung Galaxy S series (2022+)", FLAT, "SM-S948B"),
    dev("samsung-galaxy-s-legacy", "samsung", [r"SM-G9[5-8]\d.*"], "Samsung Galaxy S8-S20 series", TYPICAL, "SM-G960F", "SM-G981B"),
    dev("samsung-galaxy-note", "samsung", [r"SM-N9\d\d.*"], "Samsung Galaxy Note series", TYPICAL, "SM-N986B", "SM-N960F"),
    dev("samsung-galaxy-a-upper", "samsung", [r"SM-A(51|52|53|54|55|56|71|72|73)\d.*"], "Samsung Galaxy A5x/A7x series", FLAT, "SM-A556B", "SM-A736B"),
    dev("samsung-galaxy-a", "samsung", [r"SM-A\d{3}.*"], "Samsung Galaxy A series", TYPICAL, "SM-A156B", "SM-A057F"),
    dev("samsung-galaxy-m", "samsung", [r"SM-M\d{3}.*", r"SM-E\d{3}.*"], "Samsung Galaxy M / F series", TYPICAL, "SM-M556B", "SM-E546B"),
    dev("samsung-galaxy-xcover", "samsung", [r"SM-G(39\d|525|556|715|736).*"], "Samsung Galaxy XCover (rugged)", DAMPED, "SM-G736B", "SM-G556B"),
    dev("samsung-galaxy-tab-s", "samsung", [r"SM-(T8|T9|X[789])\d\d.*"], "Samsung Galaxy Tab S series", EARLY, "SM-X710", "SM-T970"),
    dev("samsung-galaxy-tab-a", "samsung", [r"SM-(T[0-9]|X[12]|P6)\d\d.*"], "Samsung Galaxy Tab A / Lite series", EARLY, "SM-X200", "SM-P610"),
    dev("samsung-generic", "samsung", [], "Samsung (other models)", TYPICAL, "SM-J730F", "GT-I9195"),

    # ---------------------------------------------------------------- Google
    dev("google-pixel-fold", "google", [r"Pixel.*Fold.*"], "Google Pixel Fold series", STRONG, "Pixel Fold", "Pixel 9 Pro Fold"),
    dev("google-pixel-tablet", "google", [r"Pixel Tablet.*"], "Google Pixel Tablet", EARLY, "Pixel Tablet"),
    dev("google-pixel-9a", "google", [r"Pixel 9a.*"], "Google Pixel 9a", TYPICAL, "Pixel 9a"),
    dev("google-pixel-9", "google", [r"Pixel 9.*"], "Google Pixel 9 series", FLAT, "Pixel 9 Pro XL", "Pixel 9"),
    dev("google-pixel-8a", "google", [r"Pixel 8a.*"], "Google Pixel 8a", TYPICAL, "Pixel 8a"),
    dev("google-pixel-8", "google", [r"Pixel 8.*"], "Google Pixel 8 series", FLAT, "Pixel 8 Pro"),
    dev("google-pixel-7a", "google", [r"Pixel 7a.*"], "Google Pixel 7a", TYPICAL, "Pixel 7a"),
    dev("google-pixel-7", "google", [r"Pixel 7.*"], "Google Pixel 7 series", FLAT, "Pixel 7 Pro"),
    dev("google-pixel-6a", "google", [r"Pixel 6a.*"], "Google Pixel 6a", TYPICAL, "Pixel 6a"),
    dev("google-pixel-6", "google", [r"Pixel 6.*"], "Google Pixel 6 series", FLAT, "Pixel 6 Pro"),
    dev("google-pixel-10plus", "google", [r"Pixel 1\d.*"], "Google Pixel 10 and newer", FLAT, "Pixel 10 Pro"),
    dev("google-pixel-legacy", "google", [r"Pixel [2-5].*", r"Pixel", r"Pixel XL"], "Google Pixel 1-5", TYPICAL, "Pixel 5", "Pixel 4a", "Pixel"),
    dev("google-generic", "google", [], "Google (other models)", TYPICAL, "Nexus 6P"),

    # ---------------------------------------------------------------- Xiaomi / Redmi / POCO
    dev("xiaomi-mix-fold", "xiaomi", [r".*mix fold.*"], "Xiaomi MIX Fold series", STRONG, "Xiaomi MIX Fold 3"),
    dev("xiaomi-pad", "xiaomi", [r".*pad.*"], "Xiaomi Pad / Redmi Pad", EARLY, "Xiaomi Pad 6", "Redmi Pad SE"),
    dev("xiaomi-flagship", "xiaomi", [r"(xiaomi |mi )?1[0-7].*", r".*(mix|civi).*", r"2[0-9]{3}[0-9a-z]{2,}[gciy]"], "Xiaomi numbered / MIX / Civi flagship series", TYPICAL, "Xiaomi 14 Ultra", "Xiaomi 13T Pro", "Xiaomi MIX 4", "2304FPN6DG"),
    dev("xiaomi-redmi-note", "xiaomi", [r".*redmi note.*"], "Redmi Note series", STRONG, "Redmi Note 13 Pro"),
    dev("xiaomi-poco-f", "xiaomi", [r".*poco f.*"], "POCO F series", TYPICAL, "POCO F6 Pro"),
    dev("xiaomi-poco-xmc", "xiaomi", [r".*poco [xmc].*"], "POCO X / M / C series", STRONG, "POCO X6 Pro", "POCO M6"),
    dev("xiaomi-redmi", "xiaomi", [r".*redmi.*"], "Redmi series", STRONG, "Redmi 13C"),
    dev("xiaomi-redmi-poco", "xiaomi", [r".*(poco|M20|M21|M22).*"], "Redmi / POCO (model codes)", STRONG, "M2101K6G"),
    dev("xiaomi-generic", "xiaomi", [], "Xiaomi (other models)", TYPICAL, ("POCO", "M1805E10A"), ("xiaomi", "Mi A3")),
    dev("blackshark", "blackshark", [r".*shark.*"], "Black Shark", TYPICAL, ("Blackshark", "SHARK KSR-A0")),
    dev("blackshark-generic", "blackshark", [], "Black Shark (other models)", TYPICAL, ("Blackshark", "SKW-A0")),

    # ---------------------------------------------------------------- OnePlus / OPPO / realme (shared CPH model-code space; manufacturer separates them)
    dev("oneplus-nord", "oneplus", [r".*nord.*", r"(AC|BE|DN|EB|GN|DE)2\d{3}"], "OnePlus Nord series", STRONG, "AC2003", "EB2103", "OnePlus Nord 4"),
    dev("oneplus-pad", "oneplus", [r".*pad.*", r"OPD2\d{3}"], "OnePlus Pad series", EARLY, "OPD2403"),
    dev("oneplus-flagship", "oneplus", [r"oneplus (1[0-3]|[7-9]).*", r"(GM|HD|IN|KB|LE|NE|MT)2\d{3}", r"ONEPLUS A[56]\d{3}"], "OnePlus flagship series", FLAT, "NE2213", "LE2123", "KB2003", "ONEPLUS A6013"),
    dev("oneplus-cph", "oneplus", [r"CPH2\d{3}"], "OnePlus (2022+ model codes)", TYPICAL, "CPH2581"),
    dev("oneplus-generic", "oneplus", [], "OnePlus (other models)", TYPICAL, "ONEPLUS A3003"),

    dev("oppo-find-n", "oppo", [r".*find n.*"], "OPPO Find N foldables", STRONG, "OPPO Find N3"),
    dev("oppo-find", "oppo", [r".*find.*"], "OPPO Find X series", FLAT, "OPPO Find X7 Ultra"),
    dev("oppo-pad", "oppo", [r".*pad.*", r"OPD2\d{3}"], "OPPO Pad series", EARLY, "OPD2201"),
    dev("oppo-reno", "oppo", [r".*reno.*"], "OPPO Reno series", TYPICAL, "OPPO Reno11 Pro"),
    dev("oppo-a-k", "oppo", [r"oppo [ak]\d.*"], "OPPO A / K series", STRONG, "OPPO A78"),
    dev("oppo-cph", "oppo", [r"CPH2\d{3}"], "OPPO (model codes)", TYPICAL, "CPH2357"),
    dev("oppo-generic", "oppo", [], "OPPO (other models)", TYPICAL, "PBCM30"),

    dev("realme-gt", "realme", [r".*realme gt.*"], "realme GT series", FLAT, "realme GT 6"),
    dev("realme-narzo", "realme", [r".*narzo.*"], "realme narzo series", STRONG, "realme narzo 70 5G"),
    dev("realme-numbered", "realme", [r"realme 1[0-4].*"], "realme numbered series", STRONG, "realme 12 Pro+"),
    dev("realme-c", "realme", [r"realme c.*"], "realme C series", STRONG, "realme C67"),
    dev("realme-pad", "realme", [r".*pad.*"], "realme Pad series", EARLY, "realme Pad 2"),
    dev("realme-rmx", "realme", [r"RMX\d{4}"], "realme (model codes)", STRONG, "RMX3851"),
    dev("realme-generic", "realme", [], "realme (other models)", STRONG, "RMX1941-Extra"),

    # ---------------------------------------------------------------- vivo / iQOO
    dev("iqoo-neo-z-vivo", "vivo", [r"iqoo (neo|z).*"], "iQOO Neo / Z series", TYPICAL, "iQOO Neo9 Pro"),
    dev("iqoo-flagship-vivo", "vivo", [r"iqoo.*"], "iQOO flagship series", FLAT, "iQOO 12"),
    dev("vivo-pad", "vivo", [r".*pad.*"], "vivo Pad series", EARLY, "vivo Pad3 Pro"),
    dev("vivo-x", "vivo", [r"vivo x.*"], "vivo X flagship series", FLAT, "vivo X100 Pro"),
    dev("vivo-v", "vivo", [r"vivo v\d.*"], "vivo V series", TYPICAL, "vivo V29"),
    dev("vivo-y-t", "vivo", [r"vivo [yt]\d.*"], "vivo Y / T series", STRONG, "vivo Y28", "vivo T2x"),
    dev("vivo-code", "vivo", [r"V\d{4}[A-Z]?"], "vivo (model codes)", TYPICAL, "V2303"),
    dev("vivo-generic", "vivo", [], "vivo (other models)", TYPICAL, "vivo 1808"),
    dev("iqoo-neo-z", "iqoo", [r"iqoo (neo|z).*"], "iQOO Neo / Z series", TYPICAL, ("iQOO", "iQOO Z9 5G")),
    dev("iqoo-flagship", "iqoo", [r"iqoo.*"], "iQOO flagship series", FLAT, ("iQOO", "iQOO 13")),
    dev("iqoo-generic", "iqoo", [], "iQOO (other models)", TYPICAL, ("iQOO", "I2301")),

    # ---------------------------------------------------------------- Motorola
    dev("motorola-razr", "motorola", [r".*razr.*"], "Motorola Razr foldables", STRONG, "motorola razr 40 ultra"),
    dev("motorola-edge", "motorola", [r"motorola edge.*", r"moto(rola)? edge.*"], "Motorola Edge series", TYPICAL, "motorola edge 50 pro"),
    dev("motorola-thinkphone-one", "motorola", [r".*(thinkphone|motorola one).*"], "ThinkPhone / Motorola One", TYPICAL, "ThinkPhone", "motorola one fusion+"),
    dev("motorola-moto-g", "motorola", [r"moto(rola)? g.*"], "Motorola Moto G series", STRONG, "moto g84 5G"),
    dev("motorola-moto-e", "motorola", [r"moto(rola)? e\d.*"], "Motorola Moto E series", STRONG, "moto e13"),
    dev("motorola-xt", "motorola", [r"XT\d{4}.*"], "Motorola (XT model codes)", TYPICAL, "XT1941-4"),
    dev("motorola-generic", "motorola", [], "Motorola (other models)", TYPICAL, "Moto Z2 Play"),

    # ---------------------------------------------------------------- Sony
    dev("sony-xperia", "sony", [r"XQ-.*", r"SO-.*", r"SOG.*", r"SOV.*"], "Sony Xperia (XQ series)", FLAT, "XQ-DQ54", "SO-51C"),
    dev("sony-xperia-legacy", "sony", [r"[GHJ]\d{4}"], "Sony Xperia XZ / 1 (legacy codes)", TYPICAL, "H9436", "J9110"),
    dev("sony-generic", "sony", [], "Sony (other models)", TYPICAL, "D6603"),

    # ---------------------------------------------------------------- Huawei / HONOR
    dev("huawei-matepad", "huawei", [r".*matepad.*"], "Huawei MatePad series", EARLY, "HUAWEI MatePad 11.5"),
    dev("huawei-mate-x", "huawei", [r".*mate (x|xs|xt).*"], "Huawei Mate X foldables", STRONG, "HUAWEI Mate X5"),
    dev("huawei-mate", "huawei", [r".*mate \d.*"], "Huawei Mate series", FLAT, "HUAWEI Mate 60 Pro"),
    dev("huawei-pura-p", "huawei", [r".*(pura|huawei p\d\d).*"], "Huawei Pura / P series", FLAT, "HUAWEI Pura 70", "HUAWEI P40 Pro"),
    dev("huawei-nova", "huawei", [r".*nova.*"], "Huawei nova series", TYPICAL, "HUAWEI nova 12 SE"),
    dev("huawei-enjoy-y", "huawei", [r".*(enjoy|huawei y\d).*"], "Huawei Enjoy / Y series", STRONG, "HUAWEI Y9a"),
    dev("huawei-code", "huawei", [r"[A-Z]{3}-[A-Z0-9]{3,4}"], "Huawei (model codes)", TYPICAL, "ELS-NX9", "ALN-L29"),
    dev("huawei-generic", "huawei", [], "Huawei (other models)", TYPICAL, "FIG-LX1-Extra"),

    dev("honor-pad", "honor", [r".*pad.*"], "HONOR Pad series", EARLY, "HONOR Pad 9"),
    dev("honor-magic-v", "honor", [r".*magic (v|vs|vflip).*"], "HONOR Magic V foldables", STRONG, "HONOR Magic V2"),
    dev("honor-magic", "honor", [r".*magic.*"], "HONOR Magic flagship series", FLAT, "HONOR Magic6 Pro"),
    dev("honor-numbered", "honor", [r"honor [1-9]\d\d?\b.*"], "HONOR numbered series", TYPICAL, "HONOR 200", "HONOR 90"),
    dev("honor-x", "honor", [r"honor x.*"], "HONOR X series", STRONG, "HONOR X9b"),
    dev("honor-code", "honor", [r"[A-Z]{3}-[A-Z0-9]{3,4}"], "HONOR (model codes)", TYPICAL, "ALI-NX1"),
    dev("honor-generic", "honor", [], "HONOR (other models)", TYPICAL, "HONOR View20"),

    # ---------------------------------------------------------------- ASUS / Nothing / Fairphone
    dev("asus-rog", "asus", [r".*rog.*"], "ASUS ROG Phone series", FLAT, "ROG Phone 8 Pro"),
    dev("asus-zenfone", "asus", [r".*zenfone.*"], "ASUS Zenfone series", TYPICAL, "Zenfone 10"),
    dev("asus-code", "asus", [r"ASUS_(AI|I)\d.*"], "ASUS (model codes)", FLAT, "ASUS_AI2401_C", "ASUS_I007D"),
    dev("asus-generic", "asus", [], "ASUS (other models)", TYPICAL, "ASUS_X00TD"),

    dev("nothing-cmf", "nothing", [r".*cmf.*", r"A015"], "CMF by Nothing", TYPICAL, "A015", "CMF Phone 1"),
    dev("nothing-phone-2a", "nothing", [r"A14\d.*", r"phone \(2a.*"], "Nothing Phone (2a) series", TYPICAL, "A142"),
    dev("nothing-phone-12", "nothing", [r"A06[35]", r"phone \([12]\).*"], "Nothing Phone (1) / (2)", TYPICAL, "A063", "A065"),
    dev("nothing-generic", "nothing", [], "Nothing Phone (other models)", TYPICAL, "A024"),
    dev("cmf-generic", "cmf", [], "CMF by Nothing", TYPICAL, ("CMF", "A015-Pro")),

    dev("fairphone", "fairphone", [r"FP[3-6].*", r"fairphone.*"], "Fairphone 3-6", TYPICAL, "FP5", "FP4"),
    dev("fairphone-generic", "fairphone", [], "Fairphone (other models)", TYPICAL, "FP2"),

    # ---------------------------------------------------------------- Nokia / HMD
    dev("nokia-xr", "hmd global", [r"nokia xr.*"], "Nokia XR rugged series", DAMPED, ("HMD Global", "Nokia XR21")),
    dev("nokia-tablet", "hmd global", [r"nokia t\d.*"], "Nokia T tablets", EARLY, ("HMD Global", "Nokia T21")),
    dev("nokia-x", "hmd global", [r"nokia x.*"], "Nokia X series", TYPICAL, ("HMD Global", "Nokia X30 5G")),
    dev("nokia-g-c", "hmd global", [r"nokia [gc]\d.*"], "Nokia G / C series", STRONG, ("HMD Global", "Nokia G42 5G"), ("HMD Global", "Nokia C32")),
    dev("hmd-pulse-skyline", "hmd global", [r"(hmd|pulse|skyline).*"], "HMD (Pulse / Skyline / ...)", TYPICAL, ("HMD", "HMD Skyline")),
    dev("nokia-code", "hmd global", [r"TA-\d{4}"], "Nokia (TA model codes)", STRONG, ("HMD Global", "TA-1394")),
    dev("hmd-nokia", "hmd global", [], "Nokia / HMD (other models)", STRONG, ("HMD Global", "Nokia 7.2")),

    # ---------------------------------------------------------------- Lenovo / ZTE / nubia
    dev("lenovo-legion", "lenovo", [r".*legion.*"], "Lenovo Legion phones", FLAT, "Lenovo Legion Y90"),
    dev("lenovo-tab", "lenovo", [r".*tab.*", r"TB-?[A-Z0-9]{3,}.*"], "Lenovo Tab tablets", EARLY, "Lenovo Tab P12", "TB-9707F"),
    dev("lenovo-generic", "lenovo", [], "Lenovo (other models)", TYPICAL, "Lenovo K13 Note"),

    dev("zte-axon", "zte", [r".*axon.*", r"ZTE A\d{4}.*"], "ZTE Axon series", FLAT, "ZTE Axon 40 Ultra", "ZTE A2023P"),
    dev("zte-blade", "zte", [r".*blade.*"], "ZTE Blade series", STRONG, "ZTE Blade V50"),
    dev("zte-generic", "zte", [], "ZTE (other models)", TYPICAL, "ZTE Z986"),
    dev("nubia-redmagic", "nubia", [r".*red ?magic.*"], "nubia REDMAGIC gaming series", FLAT, "REDMAGIC 10 Pro"),
    dev("nubia-z", "nubia", [r".*nubia z.*", r".*nubia flip.*"], "nubia Z flagship series", FLAT, "nubia Z60 Ultra"),
    dev("nubia-code", "nubia", [r"NX\d{3}[A-Z]"], "nubia (model codes)", TYPICAL, "NX721J"),
    dev("nubia-generic", "nubia", [], "nubia (other models)", TYPICAL, "NX659J-Extra"),

    # ---------------------------------------------------------------- Transsion: TECNO / Infinix / itel
    dev("tecno-phantom", "tecno", [r".*phantom.*"], "TECNO Phantom flagship series", FLAT, ("TECNO MOBILE LIMITED", "TECNO PHANTOM V Fold")),
    dev("tecno-camon", "tecno", [r".*camon.*"], "TECNO Camon series", TYPICAL, ("TECNO MOBILE LIMITED", "TECNO CAMON 30")),
    dev("tecno-pova", "tecno", [r".*pova.*"], "TECNO Pova series", STRONG, ("TECNO MOBILE LIMITED", "TECNO POVA 6 Pro")),
    dev("tecno-spark-pop", "tecno", [r".*(spark|pop).*"], "TECNO Spark / Pop series", STRONG, ("TECNO MOBILE LIMITED", "TECNO SPARK 20")),
    dev("tecno-code", "tecno", [r"TECNO [A-Z]{2}\d.*"], "TECNO (model codes)", STRONG, ("TECNO MOBILE LIMITED", "TECNO BG7")),
    dev("tecno-generic", "tecno", [], "TECNO (other models)", STRONG, ("TECNO MOBILE LIMITED", "TECNO F1")),

    dev("infinix-gt-zero", "infinix", [r".*(infinix gt|zero).*"], "Infinix GT / ZERO series", TYPICAL, ("Infinix Mobility Limited", "Infinix GT 20 Pro"), ("Infinix Mobility Limited", "Infinix ZERO 30")),
    dev("infinix-note", "infinix", [r".*note.*"], "Infinix NOTE series", STRONG, ("Infinix Mobility Limited", "Infinix NOTE 40")),
    dev("infinix-hot-smart", "infinix", [r".*(hot|smart).*"], "Infinix HOT / SMART series", STRONG, ("Infinix Mobility Limited", "Infinix HOT 40i")),
    dev("infinix-code", "infinix", [r"Infinix X\d{3,}.*"], "Infinix (model codes)", STRONG, ("Infinix Mobility Limited", "Infinix X6528")),
    dev("infinix-generic", "infinix", [], "Infinix (other models)", STRONG, ("Infinix Mobility Limited", "Infinix S5")),

    dev("itel-series", "itel", [r"itel [aps]\d.*"], "itel A / P / S series", STRONG, "itel P65", "itel A70"),
    dev("itel-generic", "itel", [], "itel (other models)", STRONG, "itel Vision 3"),

    # ---------------------------------------------------------------- TCL / Alcatel
    dev("tcl-tab", "tcl", [r".*tab.*", r".*nxtpaper 1\d.*"], "TCL Tab / NXTPAPER tablets", EARLY, "TCL TAB 11"),
    dev("tcl-50", "tcl", [r".*tcl 5\d.*"], "TCL 50 series", TYPICAL, "TCL 50 5G"),
    dev("tcl-40-30", "tcl", [r".*tcl [34]\d.*"], "TCL 40 / 30 series", STRONG, "TCL 40 XL"),
    dev("tcl-alcatel", "tcl", [r".*alcatel.*"], "Alcatel (TCL)", STRONG, "Alcatel 1B"),
    dev("tcl-code", "tcl", [r"T\d{3}[A-Z].*"], "TCL (model codes)", TYPICAL, "T790Y"),
    dev("tcl-generic", "tcl", [], "TCL (other models)", TYPICAL, "TCL 10L"),
    dev("alcatel-generic", "alcatel", [], "Alcatel (other models)", STRONG, ("Alcatel", "Alcatel 3X")),

    # ---------------------------------------------------------------- Japanese makers
    dev("sharp-aquos", "sharp", [r"SH-\d.*", r"SHG.*", r".*aquos.*"], "Sharp AQUOS series", TYPICAL, ("SHARP", "SH-51D"), ("SHARP", "AQUOS sense8")),
    dev("sharp-generic", "sharp", [], "Sharp (other models)", TYPICAL, ("SHARP", "S7-SH")),
    dev("fcnt-arrows", "fcnt", [r"F-\d\d[A-Z].*", r".*arrows.*"], "FCNT arrows series", TYPICAL, "F-51B"),
    dev("fcnt-generic", "fcnt", [], "FCNT (other models)", TYPICAL, "FCG01"),
    dev("kyocera-rugged", "kyocera", [r".*(torque|dura).*", r"KY.*", r"E6[89]\d\d"], "Kyocera Torque / DuraForce (rugged)", DAMPED, "E6920", "KY-41C"),
    dev("kyocera-generic", "kyocera", [], "Kyocera (other models)", TYPICAL, "Qua phone QZ"),

    # ---------------------------------------------------------------- Rugged specialists
    dev("cat-phones", "cat", [r".*cat s\d.*"], "CAT phones (rugged)", DAMPED, ("Bullitt Group Limited", "Cat S62 Pro")),
    dev("cat-generic", "cat", [], "CAT (rugged, other models)", DAMPED, ("Bullitt Group Limited", "Cat B40")),
    dev("doogee-tablet", "doogee", [r".*(doogee )?t\d\d .*", r".*tab.*"], "DOOGEE tablets", EARLY, ("DOOGEE", "DOOGEE T30 Pro")),
    dev("doogee-rugged", "doogee", [r".*(doogee )?[sv]\d.*"], "DOOGEE S / V rugged series", DAMPED, ("DOOGEE", "DOOGEE S100"), ("DOOGEE", "DOOGEE V31GT")),
    dev("doogee-generic", "doogee", [], "DOOGEE (other models)", STRONG, ("DOOGEE", "DOOGEE N55")),
    dev("ulefone-armor", "ulefone", [r".*armor.*"], "Ulefone Armor rugged series", DAMPED, "Armor 24"),
    dev("ulefone-note", "ulefone", [r".*note.*"], "Ulefone Note series", STRONG, "Note 17 Pro"),
    dev("ulefone-generic", "ulefone", [], "Ulefone (other models)", STRONG, "Ulefone S10 Pro"),
    dev("oukitel-wp", "oukitel", [r".*(wp|rt)\d.*"], "OUKITEL WP / RT rugged series", DAMPED, "WP33 Pro", "RT7 TITAN"),
    dev("oukitel-generic", "oukitel", [], "OUKITEL (other models)", STRONG, "C38"),
    dev("blackview-tablet", "blackview", [r".*tab.*"], "Blackview Tab tablets", EARLY, "Tab 16 Pro"),
    dev("blackview-bv", "blackview", [r".*bv\d.*"], "Blackview BV rugged series", DAMPED, "BV9300 Pro"),
    dev("blackview-generic", "blackview", [], "Blackview (other models)", STRONG, "A85"),
    dev("oscal-pad", "oscal", [r".*pad.*"], "OSCAL Pad tablets", EARLY, ("OSCAL", "OSCAL Pad 13")),
    dev("oscal-generic", "oscal", [], "OSCAL (other models)", STRONG, ("OSCAL", "OSCAL Tiger 12")),
    dev("agm-generic", "agm", [], "AGM rugged phones", DAMPED, ("AGM", "AGM H5 Pro")),
    dev("umidigi-bison", "umidigi", [r".*bison.*"], "UMIDIGI Bison rugged series", DAMPED, "UMIDIGI BISON GT2 PRO"),
    dev("umidigi-generic", "umidigi", [], "UMIDIGI (other models)", STRONG, "UMIDIGI A15"),
    dev("cubot-kingkong", "cubot", [r".*king ?kong.*"], "CUBOT KingKong rugged series", DAMPED, ("CUBOT", "CUBOT KingKong AX")),
    dev("cubot-generic", "cubot", [], "CUBOT (other models)", STRONG, ("CUBOT", "CUBOT Note 21")),
    dev("fossibot-generic", "fossibot", [], "FOSSiBOT rugged phones", DAMPED, ("FOSSiBOT", "FOSSiBOT F102")),
    dev("iiif150-generic", "iiif150", [], "IIIF150 rugged phones", DAMPED, ("IIIF150", "IIIF150 R2022")),
    dev("crosscall-generic", "crosscall", [], "CROSSCALL rugged phones", DAMPED, "Core-X5"),
    dev("unihertz-jelly", "unihertz", [r".*jelly.*", r".*atom.*"], "Unihertz Jelly / Atom (compact)", TYPICAL, "Jelly Star"),
    dev("unihertz-generic", "unihertz", [], "Unihertz (Tank rugged etc.)", DAMPED, "Tank 3 Pro"),
    dev("hotwav-generic", "hotwav", [], "HOTWAV rugged phones", DAMPED, ("HOTWAV", "HOTWAV Cyber 13 Pro")),

    # ---------------------------------------------------------------- Others
    dev("gigaset-gx", "gigaset", [r".*gx\d.*"], "Gigaset GX (rugged)", DAMPED, ("Gigaset", "GX6 PRO")),
    dev("gigaset-generic", "gigaset", [], "Gigaset (other models)", TYPICAL, ("Gigaset", "GS5")),
    dev("shift-generic", "shift", [], "SHIFTphone", TYPICAL, ("SHIFT", "SHIFT6mq"), ("SHIFT", "SHIFTphone 8")),
    dev("amazon-fire", "amazon", [r"KF[A-Z]{2,}", r".*fire.*"], "Amazon Fire tablets", EARLY, ("Amazon", "KFTRWI")),
    dev("amazon-generic", "amazon", [], "Amazon (other devices)", EARLY, ("Amazon", "SD4930UR")),
    dev("meizu", "meizu", [r".*meizu.*", r"M\d{3,4}[A-Z]?"], "Meizu", TYPICAL, "Meizu 20 Pro", "M1923"),
    dev("meizu-generic", "meizu", [], "Meizu (other models)", TYPICAL, "M6 Note"),
    dev("wiko", "wiko", [r".*wiko.*"], "Wiko", STRONG, ("WIKO", "WIKO T50")),
    dev("wiko-generic", "wiko", [], "Wiko (other models)", STRONG, ("WIKO", "HARRY2")),
    dev("blu-generic", "blu", [], "BLU", STRONG, ("BLU", "BLU G91 Max")),
    dev("lava-generic", "lava", [], "Lava", STRONG, ("LAVA", "Lava Agni 2")),
    dev("micromax-generic", "micromax", [], "Micromax", STRONG, "Micromax IN 2b"),
    dev("symphony-generic", "symphony", [], "Symphony", STRONG, "Symphony Z60"),
    dev("walton-generic", "walton", [], "Walton", STRONG, ("WALTON", "Walton Primo RX8")),
]

# Extra routing cases: manufacturer aliases and cross-checks beyond the
# per-entry examples (expected ids).
EXTRA_TESTS = [
    ("POCO", "POCO F5", "xiaomi-poco-f"),
    ("Redmi", "Redmi Note 12", "xiaomi-redmi-note"),
    ("Samsung Electronics", "SM-S928B", "samsung-galaxy-s24"),
    ("HMD Global Oy", "Nokia X30 5G", "nokia-x"),
    ("Google", "Pixel 8 Pro", "google-pixel-8"),
    ("Fairphone", "FP5", "fairphone"),
    ("samsung", "SM-S921B", "samsung-galaxy-s24"),
    ("UnknownCorp", "X", None),  # None = falls back to the global default
]

CONFIDENCE_ORDINAL = {"measured": 0, "community": 1, "estimated": 2}


def normalise(m: str) -> str:
    """Mirror of MicProfileMatcher.normaliseManufacturer (Kotlin) - keep in sync."""
    s = m.strip().lower()
    if s.startswith("samsung"):
        return "samsung"
    if s in ("redmi", "poco"):
        return "xiaomi"
    if s.startswith("hmd"):
        return "hmd global"
    if s.startswith("tecno"):
        return "tecno"
    if s.startswith("infinix"):
        return "infinix"
    if s.startswith("itel"):
        return "itel"
    if s.startswith("black shark") or s.startswith("blackshark"):
        return "blackshark"
    if s.startswith("bullitt"):
        return "cat"
    if s.startswith("amazon"):
        return "amazon"
    if s.startswith("tcl"):
        return "tcl"
    if s.startswith("gigaset"):
        return "gigaset"
    return s


def match(manufacturer: str, model: str, devices=DEVICES):
    """Mirror of MicProfileMatcher.match (without the default profile)."""
    manu = normalise(manufacturer)
    model = model.strip()
    best = None
    for d in devices:
        if normalise(d["manufacturer"]) != manu:
            continue
        if d["models"] and not any(re.fullmatch(p, model, re.IGNORECASE) for p in d["models"]):
            continue
        key = (0 if d["models"] else 1, CONFIDENCE_ORDINAL[d["confidence"]])
        if best is None or key < best[0]:
            best = (key, d)
    return best[1]["id"] if best else None


def self_test() -> None:
    failures = []
    hit = set()
    cases = []
    for d in DEVICES:
        assert d["examples"], f"{d['id']}: needs at least one example model"
        for manu, model in d["examples"]:
            cases.append((manu, model, d["id"]))
    cases += EXTRA_TESTS

    for manu, model, expected in cases:
        got = match(manu, model)
        if got != expected:
            failures.append(f"  ({manu!r}, {model!r}) -> {got!r}, expected {expected!r}")
        elif got:
            hit.add(got)

    dead = [d["id"] for d in DEVICES if d["id"] not in hit]
    if dead:
        failures.append(f"  unreachable entries (no example resolves to them): {dead}")
    assert not failures, "mic profile routing self-test failed:\n" + "\n".join(failures)
    print(f"self-test: {len(cases)} routing cases OK, all {len(DEVICES)} entries reachable")


def main() -> None:
    self_test()
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
                k: d[k]
                for k in ("id", "manufacturer", "models", "label", "curveRef", "curves", "confidence", "notes")
                if k in d
            }
            for d in DEVICES
        ],
    }
    assert len({d["id"] for d in data["devices"]}) == len(DEVICES), "duplicate ids"
    for d in data["devices"]:
        if "curveRef" in d:
            assert d["curveRef"] in ARCHETYPES, d
    for manu in {normalise(d["manufacturer"]) for d in DEVICES}:
        assert any(normalise(d["manufacturer"]) == manu and not d["models"] for d in DEVICES), \
            f"manufacturer '{manu}' has no fallback entry (models=[])"
    OUT.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(data, indent=2, ensure_ascii=False)
    # keep [frequency, dB] pairs on one line for readability / reviewable diffs
    text = re.sub(r"\[\s+(-?[\d.]+),\s+(-?[\d.]+)\s+\]", r"[\1, \2]", text)
    OUT.write_text(text + "\n", encoding="utf-8")
    print(f"wrote {OUT.relative_to(ROOT)}: {len(DEVICES)} devices, {len(ARCHETYPES)} archetypes")


if __name__ == "__main__":
    main()
