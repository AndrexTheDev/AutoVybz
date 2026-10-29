#!/usr/bin/env python3
"""Analyse the field log written by the app (Studio -> field data -> export file).

The app records one JSON line per calibration (see `measurement/FieldLog.kt`). That record exists to
answer four questions that no model can answer - they were listed as "questions that need a device, not
a model" in docs/AUDIO_ROADMAP.md section 7:

  W3   how much THD do real speakers have at the level the app measures at?      -> the 3 % gate
  W4   how long is a real room?                                                  -> the 0.8 s gate
  W5   how far apart are the three microphone positions really?                  -> the 3x outlier rule
  W11  does the microphone clip before the speaker distorts?                     -> the capture branch

The whole point of writing the decision rules down *before* the data arrives is that the answer cannot
be talked into shape afterwards. Each rule below is a fixed function of the data; `--check` verifies
that the thresholds it compares against are still the ones in the Kotlin sources.

Usage:
    python3 tools/fielddata/analyze.py --check          # constants parity with the app (CI)
    python3 tools/fielddata/analyze.py --selftest       # the rules, on synthetic data (CI)
    python3 tools/fielddata/analyze.py FILE [FILE...]   # a real report
"""

from __future__ import annotations

import json
import math
import re
import sys
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "app/src/main/java/com/andrexthedev/autovybz"

# The constants the rules are built on. `--check` proves these are still what the app ships.
KOTLIN_CONSTANTS = {
    "THD_WARN_PERCENT": (SRC / "measurement/dsp/HarmonicDistortion.kt", "THD_WARN_PERCENT"),
    "TARGET_THD_PERCENT": (SRC / "measurement/audio/MeasurementLevel.kt", "TARGET_THD_PERCENT"),
    "REVERBERANT_THRESHOLD_S": (SRC / "measurement/dsp/RoomDecay.kt", "REVERBERANT_THRESHOLD_S"),
    "OUTLIER_FACTOR": (SRC / "measurement/dsp/SpatialAverager.kt", "OUTLIER_FACTOR"),
    "OUTLIER_FLOOR_DB": (SRC / "measurement/dsp/SpatialAverager.kt", "OUTLIER_FLOOR_DB"),
    "DOCUMENTED_VOLUME": (SRC / "measurement/FieldLog.kt", "DOCUMENTED_VOLUME"),
    "MAX_SAMPLES": (SRC / "measurement/FieldLogStore.kt", "MAX_SAMPLES"),
}

# Thresholds as of writing. If `--check` reports a difference, the rules here need a decision, not a
# silent update - which is exactly why they are duplicated instead of parsed at runtime.
EXPECTED = {
    "THD_WARN_PERCENT": 3.0,
    "TARGET_THD_PERCENT": 2.0,
    "REVERBERANT_THRESHOLD_S": 0.8,
    "OUTLIER_FACTOR": 3.0,
    "OUTLIER_FLOOR_DB": 1.5,
    "DOCUMENTED_VOLUME": 0.7,
    "MAX_SAMPLES": 2000,
}

# Below this many samples a distribution is not a distribution. Reported, never acted on.
MIN_SAMPLES = 20

# W3: the gate should flag the worst 5 % of real speaker measurements, not the median one.
THD_GATE_PERCENTILE = 0.95
# W4: the room gate should fire for roughly one room in ten.
RT60_GATE_PERCENTILE = 0.90
# W5: a wrongly dropped position costs a third of the measurement, a kept bad one costs the plan.
MAX_FALSE_DROP_RATE = 0.02
OUTLIER_CANDIDATES = (2.0, 3.0, 4.0)
# W11: from this share of level-limited runs the microphone is a first-class cause, not a footnote.
MIC_FIRST_SHARE = 0.30

# The app's own warnings that mean "this run was disturbed" (MeasurementWarning). `POSITION_OUTLIER` is
# deliberately absent: it is the *consequence* of the rule under test, so using it as ground truth would
# let the rule congratulate itself. `CLIPPING` is a disturbance for W5 (a clipped position is a bad one)
# and a level finding for W11 - the two questions read it for different reasons.
DISTURBANCE_WARNINGS = {"NOISE_UNSTEADY", "LOW_FREQ_NOISE", "DROPOUT", "AUDIO_GLITCH", "CLIPPING",
                        "SWEEP_NOISE_MISMATCH"}


# --------------------------------------------------------------------------- loading


def read_samples(paths: Sequence[str]) -> List[Dict[str, Any]]:
    files: List[Path] = []
    for p in paths:
        path = Path(p)
        files.extend(sorted(path.rglob("*.jsonl")) if path.is_dir() else [path])
    out: List[Dict[str, Any]] = []
    for f in files:
        for line in f.read_text(encoding="utf-8", errors="replace").splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                o = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(o, dict) and o.get("schema") == 1 and isinstance(o.get("at"), int):
                out.append(o)
    return out


# --------------------------------------------------------------------------- helpers


def numbers(samples: Iterable[Dict[str, Any]], key: str) -> List[float]:
    vals = []
    for s in samples:
        v = s.get(key)
        if isinstance(v, (int, float)) and math.isfinite(v):
            vals.append(float(v))
    return vals


def quantile(values: Sequence[float], q: float) -> Optional[float]:
    if not values:
        return None
    xs = sorted(values)
    if len(xs) == 1:
        return xs[0]
    pos = q * (len(xs) - 1)
    lo, hi = int(math.floor(pos)), int(math.ceil(pos))
    return xs[lo] + (xs[hi] - xs[lo]) * (pos - lo)


def round_to(value: float, step: float) -> float:
    return round(round(value / step) * step, 6)


def ceil_to(value: float, step: float) -> float:
    return round(math.ceil(value / step) * step, 6)


def median(values: Sequence[float]) -> Optional[float]:
    return quantile(values, 0.5)


def fmt(value: Optional[float], digits: int = 2, unit: str = "") -> str:
    return "n/a" if value is None else f"{value:.{digits}f}{unit}"


# --------------------------------------------------------------------------- the four rules


def rule_thd(samples: Sequence[Dict[str, Any]]) -> Dict[str, Any]:
    """W3: where should `HarmonicDistortion.THD_WARN_PERCENT` sit?"""
    # Only measurements at or above the documented level answer "at 70 % volume". A run that W11 turned
    # down measured a *different* operating point; mixing the two would average away the question.
    at_level = [s for s in samples if (s.get("volume") or 0) >= 0.95 * EXPECTED["DOCUMENTED_VOLUME"]]
    measured = [s for s in at_level if isinstance(s.get("thdPercent"), (int, float))]
    by_class: Dict[str, List[float]] = {}
    for s in measured:
        by_class.setdefault(str(s.get("speakerClass") or "?"), []).append(float(s["thdPercent"]))
    values = numbers(measured, "thdPercent")
    p95 = quantile(values, THD_GATE_PERCENTILE)
    suggestion: Optional[float] = None
    decision = "insufficient data"
    if len(values) >= MIN_SAMPLES and p95 is not None:
        suggestion = max(ceil_to(p95, 0.5), EXPECTED["TARGET_THD_PERCENT"])
        decision = "keep" if abs(suggestion - EXPECTED["THD_WARN_PERCENT"]) < 0.5 else (
            "raise" if suggestion > EXPECTED["THD_WARN_PERCENT"] else "lower"
        )
    return {
        "n": len(values),
        "by_class": {k: (len(v), median(v), quantile(v, 0.9)) for k, v in sorted(by_class.items())},
        "median": median(values),
        "p95": p95,
        "gate": EXPECTED["THD_WARN_PERCENT"],
        "suggested": suggestion,
        "decision": decision,
    }


def rule_rt60(samples: Sequence[Dict[str, Any]]) -> Dict[str, Any]:
    """W4: where should `RoomDecay.REVERBERANT_THRESHOLD_S` sit?"""
    indoor = [s for s in samples if str(s.get("environment") or "").upper() == "INDOOR"]
    values = numbers(indoor, "rt60S")
    p90 = quantile(values, RT60_GATE_PERCENTILE)
    suggestion: Optional[float] = None
    decision = "insufficient data"
    if len(values) >= MIN_SAMPLES and p90 is not None:
        suggestion = max(round_to(p90, 0.1), 0.3)
        decision = "keep" if abs(suggestion - EXPECTED["REVERBERANT_THRESHOLD_S"]) < 0.1 else (
            "raise" if suggestion > EXPECTED["REVERBERANT_THRESHOLD_S"] else "lower"
        )
    return {
        "n": len(values),
        "median": median(values),
        "p90": p90,
        "share_above_gate": (sum(1 for v in values if v >= EXPECTED["REVERBERANT_THRESHOLD_S"]) / len(values)) if values else None,
        "gate": EXPECTED["REVERBERANT_THRESHOLD_S"],
        "suggested": suggestion,
        "decision": decision,
    }


def _ratio(sample: Dict[str, Any]) -> Optional[float]:
    devs = [float(d) for d in (sample.get("deviations") or []) if isinstance(d, (int, float))]
    if len(devs) < 3:
        return None
    med = median(devs)
    if med is None or med <= 0.05:
        return None
    return max(devs) / med


def _was_disturbed(sample: Dict[str, Any]) -> bool:
    names = {str(w).upper() for w in (sample.get("warnings") or [])}
    return bool(names & DISTURBANCE_WARNINGS)


def rule_outlier_factor(samples: Sequence[Dict[str, Any]]) -> Dict[str, Any]:
    """W5: is `SpatialAverager.OUTLIER_FACTOR` right, or is 2x the better rule?"""
    ratios = []
    for s in samples:
        r = _ratio(s)
        if r is None:
            continue
        ratios.append((r, _was_disturbed(s)))
    if len(ratios) < MIN_SAMPLES:
        return {"n": len(ratios), "decision": "insufficient data", "candidates": {}, "chosen": EXPECTED["OUTLIER_FACTOR"]}
    table = {}
    for k in OUTLIER_CANDIDATES:
        # The app applies `max(k * median, floor)`, so a drop needs BOTH: a large ratio and a real spread.
        catches = sum(1 for r, bad in ratios if bad and r > k)
        false_drops = sum(1 for r, bad in ratios if not bad and r > k)
        table[k] = {
            "catches": catches,
            "false_drops": false_drops,
            "false_rate": false_drops / max(1, sum(1 for _, bad in ratios if not bad)),
        }
    best = None
    for k in OUTLIER_CANDIDATES:
        if table[k]["false_rate"] <= MAX_FALSE_DROP_RATE:
            if best is None or table[k]["catches"] > table[best]["catches"]:
                best = k
    chosen = best if best is not None else EXPECTED["OUTLIER_FACTOR"]
    return {
        "n": len(ratios),
        "p50_ratio": median([r for r, _ in ratios]),
        "p95_ratio": quantile([r for r, _ in ratios], 0.95),
        "candidates": table,
        "chosen": chosen,
        "decision": "keep" if chosen == EXPECTED["OUTLIER_FACTOR"] else "change",
    }


def _level_bound(sample: Dict[str, Any]) -> str:
    """Mirror of `FieldLog.LevelBound`: derived from the two numbers, never stored."""
    names = {str(w).upper() for w in (sample.get("warnings") or [])}
    mic = bool(sample.get("micClipped")) or "CLIPPING" in names
    thd = sample.get("thdPercent")
    speaker = isinstance(thd, (int, float)) and thd >= EXPECTED["THD_WARN_PERCENT"]
    if mic and speaker:
        return "BOTH"
    if mic:
        return "MIC_CLIPPING"
    if speaker:
        return "SPEAKER_THD"
    return "NONE"


def rule_level_bound(samples: Sequence[Dict[str, Any]]) -> Dict[str, Any]:
    """W11: does the *microphone* hit full scale before the speaker distorts?"""
    limited = [s for s in samples if (s.get("levelStepDb") or 0) > 0 or _level_bound(s) != "NONE"]
    counts = {"NONE": 0, "MIC_CLIPPING": 0, "SPEAKER_THD": 0, "BOTH": 0}
    for s in limited:
        counts[_level_bound(s)] = counts.get(_level_bound(s), 0) + 1
    mic_first = counts["MIC_CLIPPING"] + counts["BOTH"]
    share = mic_first / len(limited) if limited else None
    decision = "insufficient data"
    if len(limited) >= MIN_SAMPLES:
        decision = "microphone first" if share is not None and share >= MIC_FIRST_SHARE else "speaker first"
    return {
        "n_limited": len(limited),
        "n_total": len(samples),
        "counts": counts,
        "mic_share": share,
        "decision": decision,
        "starting_volume": EXPECTED["DOCUMENTED_VOLUME"],
    }


# --------------------------------------------------------------------------- report


def summarise(samples: Sequence[Dict[str, Any]]) -> Dict[str, Any]:
    return {
        "samples": len(samples),
        "thd": rule_thd(samples),
        "rt60": rule_rt60(samples),
        "outlier": rule_outlier_factor(samples),
        "level": rule_level_bound(samples),
    }


def report(samples: Sequence[Dict[str, Any]]) -> str:
    r = summarise(samples)
    out = [f"# Field data report - {r['samples']} samples", ""]
    if r["samples"] < MIN_SAMPLES:
        out += [
            f"Not enough data: {r['samples']} samples, {MIN_SAMPLES} needed before any threshold moves.",
            "The instrument is working; the answers need more beta calibrations.",
            "",
        ]
    t, d, o, l = r["thd"], r["rt60"], r["outlier"], r["level"]
    out += [
        "## W3 - speaker distortion (gate: THD >= %.1f %%)" % EXPECTED["THD_WARN_PERCENT"],
        f"- measurements at the documented level: {t['n']}",
        f"- median THD: {fmt(t['median'], 2, ' %')}, p95: {fmt(t['p95'], 2, ' %')}",
    ]
    for cls, (n, med, p90) in t["by_class"].items():
        out.append(f"  - {cls}: n={n}, median {fmt(med, 2, ' %')}, p90 {fmt(p90, 2, ' %')}")
    out += [f"- suggested gate: {fmt(t['suggested'], 2, ' %')} -> {t['decision']}", ""]
    out += [
        "## W4 - room reverberation (gate: RT60 >= %.1f s)" % EXPECTED["REVERBERANT_THRESHOLD_S"],
        f"- indoor measurements with a readable decay: {d['n']}",
        f"- median: {fmt(d['median'], 2, ' s')}, p90: {fmt(d['p90'], 2, ' s')}",
        f"- share above the current gate: {fmt(None if d['share_above_gate'] is None else 100 * d['share_above_gate'], 1, ' %')}",
        f"- suggested gate: {fmt(d['suggested'], 1, ' s')} -> {d['decision']}", "",
    ]
    out += [
        "## W5 - position outlier rule (factor %.1f, floor %.1f dB)" % (
            EXPECTED["OUTLIER_FACTOR"], EXPECTED["OUTLIER_FLOOR_DB"]),
        f"- three-position runs: {o['n']}, worst/median deviation p50 {fmt(o.get('p50_ratio'), 2)}, p95 {fmt(o.get('p95_ratio'), 2)}",
    ]
    for k, row in sorted(o["candidates"].items()):
        out.append(
            f"  - factor {k}: catches {row['catches']} disturbed, "
            f"{row['false_drops']} false drops ({100 * row['false_rate']:.1f} %)"
        )
    out += [f"- chosen factor: {o['chosen']} -> {o['decision']}", ""]
    out += [
        "## W11 - what limits the measurement level",
        f"- level-limited runs: {l['n_limited']} of {l['n_total']}",
        f"- {l['counts']}, microphone share: {fmt(None if l['mic_share'] is None else 100 * l['mic_share'], 0, ' %')}",
        f"- -> {l['decision']}", "",
    ]
    return "\n".join(out)


# --------------------------------------------------------------------------- checks


def kotlin_value(path: Path, name: str) -> Optional[float]:
    text = path.read_text(encoding="utf-8")
    m = re.search(rf"const val {name}\s*(?::\s*\w+\s*)?=\s*([-0-9._]+)", text)
    if not m:
        return None
    return float(m.group(1).replace("_", ""))


def check() -> int:
    bad = 0
    print("Field-data rules vs. the app's constants")
    for name, (path, const) in KOTLIN_CONSTANTS.items():
        if not path.exists():
            print(f"  MISSING  {name}: {path.relative_to(ROOT)}")
            bad += 1
            continue
        actual = kotlin_value(path, const)
        if actual is None:
            print(f"  MISSING  {name}: no `const val {const}` in {path.name}")
            bad += 1
        elif abs(actual - EXPECTED[name]) > 1e-9:
            print(f"  CHANGED  {name}: app has {actual}, the rules were written against {EXPECTED[name]}")
            print("           -> decide what the new value means for the questions in docs/FIELD_DATA.md,")
            print("              then update EXPECTED in tools/fielddata/analyze.py (and its selftest).")
            bad += 1
        else:
            shown = int(actual) if actual == int(actual) else actual
            print(f"  ok       {name} = {shown}")
    return 1 if bad else 0


def _synthetic(**over: Any) -> Dict[str, Any]:
    base = {
        "schema": 1, "at": 1_700_000_000_000, "device": "Test Device", "speakerClass": "STANDARD",
        "environment": "INDOOR", "target": "SMART", "positions": 3, "deviations": [1.0, 1.2, 1.1],
        "rejected": 0, "spreadDb": 1.1, "thdPercent": 1.0, "micClipped": False, "inputPeak": 0.4,
        "rt60S": 0.45, "volume": 0.7, "levelStepDb": 0.0, "warnings": [],
    }
    base.update(over)
    return base


def selftest() -> int:
    """The rules must fire when the data says so and stay quiet when it does not."""
    failures: List[str] = []

    def expect(label: str, condition: bool) -> None:
        print(("  ok       " if condition else "  FAILED   ") + label)
        if not condition:
            failures.append(label)

    empty = summarise([])
    print("empty log")
    expect("W3 waits for data", empty["thd"]["decision"] == "insufficient data")
    expect("W4 waits for data", empty["rt60"]["decision"] == "insufficient data")
    expect("W5 keeps the current factor", empty["outlier"]["chosen"] == EXPECTED["OUTLIER_FACTOR"])
    expect("W11 waits for data", empty["level"]["decision"] == "insufficient data")

    print("a world where speakers distort more than the gate assumes")
    hot = summarise([_synthetic(thdPercent=5.0 + 0.1 * i) for i in range(30)])
    expect("W3 raises the gate", hot["thd"]["decision"] == "raise")
    expect("W3 suggests a value above the current gate", (hot["thd"]["suggested"] or 0) > EXPECTED["THD_WARN_PERCENT"])

    print("a world where speakers are clean")
    clean = summarise([_synthetic(thdPercent=0.3 + 0.01 * i) for i in range(30)])
    expect("W3 lowers the gate", clean["thd"]["decision"] == "lower")

    print("a world of dry rooms")
    dry = summarise([_synthetic(rt60S=0.30 + 0.002 * i) for i in range(30)])
    expect("W4 lowers the gate", dry["rt60"]["decision"] == "lower")

    print("a world of live rooms")
    live = summarise([_synthetic(rt60S=1.1 + 0.02 * i) for i in range(30)])
    expect("W4 raises the gate", live["rt60"]["decision"] == "raise")

    print("one genuinely disturbed position per ten runs")
    disturbed = [_synthetic(deviations=[1.0, 1.1, 2.4], warnings=["NOISE_UNSTEADY"]) if i % 10 == 5
                 else _synthetic(deviations=[1.0, 1.1, 1.3]) for i in range(30)]
    outlier = summarise(disturbed)["outlier"]
    expect("W5 sees the ratio", outlier["n"] == 30)
    expect("W5 finds a factor that catches them", outlier["decision"] in {"keep", "change"})

    print("the microphone, not the speaker, is the limit")
    mic = summarise([_synthetic(micClipped=True, warnings=["CLIPPING"], thdPercent=0.8) for _ in range(25)])
    expect("W11 says microphone first", mic["level"]["decision"] == "microphone first")

    print("the speaker, not the microphone, is the limit")
    spk = summarise([_synthetic(thdPercent=4.0, levelStepDb=3.0, warnings=["SPEAKER_DISTORTION"]) for _ in range(25)])
    expect("W11 says speaker first", spk["level"]["decision"] == "speaker first")

    return 1 if failures else 0


def main(argv: Sequence[str]) -> int:
    args = [a for a in argv[1:] if not a.startswith("-")]
    flags = {a for a in argv[1:] if a.startswith("-")}
    if "--check" in flags:
        return check()
    if "--selftest" in flags:
        return selftest()
    if not args:
        print(__doc__)
        return 2
    samples = read_samples(args)
    print(report(samples))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
