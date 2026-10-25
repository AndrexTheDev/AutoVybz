#!/usr/bin/env python3
"""Pack the AutoEq headphone database into one compact binary asset.

Canonical sources (kept in git):  tools/autoeq/data/index.json + tools/autoeq/data/profiles/**.txt
Generated output (committed):     app/src/main/assets/autoeq/autoeq.bin
Reader:                            autoeq/AutoEqPack.kt (+ AutoEqDatabase)

Why: 8850 tiny .txt assets + a 1.1 MB index.json cost ~2.9 MB inside the APK
(~1.7 MB deflated payload + ~1.1 MB ZIP local/central headers for 8851 entries
+ the deflated index) and slow asset merging on every build. Every bundled
profile is perfectly regular — Preamp + 10 filters in the fixed order
LSC, PK x4, HSC, PK x4, values on exact grids (preamp/gain 0.1 dB, Q 0.01,
Fc integer Hz) — so it packs LOSSLESSLY into 61 bytes per profile. The whole
database becomes ~758 KB raw / ~450 KB deflated in the APK.

Losslessness is verified, not assumed: after writing, this script decodes its
own output and proves for every value that the float32 the app computes from
the packed integer (IEEE-correctly-rounded int/scale division) is bit-identical
to the float32 Java's Float.parseFloat produced from the original text
(computed here with exact rational arithmetic). It also proves every entry's
legacy `path` is exactly reconstructible from (source, rig, type, name), which
the app relies on (AutoEqEntry.path is used as a cache key). Any future dataset
that breaks the regularity assumptions fails loudly here instead of silently
losing data.

Format "AEQ1" (all integers little-endian):
  char[4]  magic "AEQ1"
  sources: u8 count, then per string: u8 len + UTF-8 bytes   (sorted)
  rigs:    u8 count, same                                    (sorted)
  types:   u8 count, same                                    (sorted)
  entries: u32 count, then per entry (index.json order preserved):
             u8 nameLen + UTF-8 name + u8 sourceIdx + u8 typeIdx + u8 rigIdx
  profiles: count x 61 bytes, same order as entries:
             i8 preamp*10, then 10 x { u16 fcHz, i16 gain*10, u16 q*100 }
             gain -32768 is the sentinel for "-0.0" (sign of zero preserved).
             Filter types are implicit: LSC, PK, PK, PK, PK, HSC, PK, PK, PK, PK.

Run after updating the dataset in tools/autoeq/data — CI re-runs it and fails
on `git diff` if app/src/main/assets/autoeq/autoeq.bin is stale.
"""
import json
import re
import struct
import sys
from decimal import Decimal
from fractions import Fraction
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "tools/autoeq/data"
INDEX = DATA / "index.json"
PROFILES = DATA / "profiles"
OUT = ROOT / "app/src/main/assets/autoeq/autoeq.bin"

FILTER_TYPES = ["LSC", "PK", "PK", "PK", "PK", "HSC", "PK", "PK", "PK", "PK"]
NEG_ZERO = -32768  # sentinel gain*10 value encoding the text "-0.0"

PREAMP_RE = re.compile(r"Preamp: (-?\d+\.\d) dB")
FILTER_RE = re.compile(r"Filter (\d+): ON (LSC|PK|HSC) Fc (\d+) Hz Gain (-?\d+\.\d) dB Q (\d+\.\d\d)")


def fail(msg: str) -> "SystemExit":
    raise SystemExit(f"pack_autoeq: {msg}")


def scaled(text: str, scale: int, what: str) -> int:
    """Decimal text * scale, requiring an exact integer result."""
    v = Decimal(text) * scale
    if v != v.to_integral_value():
        fail(f"{what}: {text!r} is not on the 1/{scale} grid")
    return int(v)


def parse_profile(path: Path) -> tuple[int, list[tuple[int, int, int]], list[tuple[str, str, str, str]]]:
    """Strictly parse one profile. Returns (preamp*10, [(fc, gain*10, q*100) x10],
    [(fcText, gainText, qText, preampText)...]) — the text forms feed the
    bit-exactness proof. Any deviation from the regular shape is fatal."""
    lines = path.read_text(encoding="utf-8").splitlines()
    if len(lines) != 11:
        fail(f"{path}: expected exactly 11 lines, got {len(lines)}")
    m = PREAMP_RE.fullmatch(lines[0])
    if not m:
        fail(f"{path}: bad preamp line {lines[0]!r}")
    preamp_text = m.group(1)
    preamp10 = scaled(preamp_text, 10, f"{path} preamp")
    if not -128 <= preamp10 <= 127:
        fail(f"{path}: preamp {preamp_text} does not fit i8*10")
    filters: list[tuple[int, int, int]] = []
    texts: list[tuple[str, str, str]] = []
    for i, line in enumerate(lines[1:]):
        m = FILTER_RE.fullmatch(line)
        if not m:
            fail(f"{path}: bad filter line {line!r}")
        num, ftype, fc_t, gain_t, q_t = m.groups()
        if int(num) != i + 1:
            fail(f"{path}: filter numbering off at {line!r}")
        if ftype != FILTER_TYPES[i]:
            fail(f"{path}: filter {i + 1} is {ftype}, expected {FILTER_TYPES[i]} (fixed-order assumption)")
        fc = int(fc_t)
        gain10 = NEG_ZERO if gain_t == "-0.0" else scaled(gain_t, 10, f"{path} gain")
        q100 = scaled(q_t, 100, f"{path} q")
        # Binary ranges AND the ranges AutoEqParser accepts (reader output must equal parser output).
        if not (1 <= fc <= 65535 and 1 <= fc <= 100000):
            fail(f"{path}: Fc {fc} out of range")
        if gain10 != NEG_ZERO and not (-300 <= gain10 <= 300 and -32767 <= gain10 <= 32767):
            fail(f"{path}: gain {gain_t} out of range")
        if not (1 <= q100 <= 2000):
            fail(f"{path}: Q {q_t} out of parser range 0.01..20")
        filters.append((fc, gain10, q100))
        texts.append((fc_t, gain_t, q_t))
    return preamp10, filters, (preamp_text, texts)


# ---- exact float32 rounding (for the bit-exactness proof) -------------------

def f32_bits_to_fraction(bits: int) -> Fraction:
    return Fraction(struct.unpack("<f", struct.pack("<I", bits))[0])


def nearest_f32_bits(value: Fraction) -> int:
    """IEEE-754 round-to-nearest-even of an exact rational to float32, as bits.
    Only used for the small finite values in this dataset (no Inf/NaN/subnormal edge)."""
    if value == 0:
        return 0
    approx = struct.unpack("<I", struct.pack("<f", float(value)))[0]
    best = None
    for cand in (approx - 1, approx, approx + 1):
        if cand < 0 or cand > 0x7F7FFFFF and value > 0 or cand > 0xFF7FFFFF:
            continue
        try:
            frac = f32_bits_to_fraction(cand)
        except (OverflowError, struct.error):
            continue
        err = abs(frac - value)
        if best is None or err < best[0] or (err == best[0] and cand % 2 == 0):
            best = (err, cand)
    assert best is not None
    return best[1]


def java_parse_float_bits(text: str) -> int:
    """Bits of Float.parseFloat(text): correct rounding of the exact decimal."""
    if Decimal(text) == 0:
        return 0x80000000 if text.startswith("-") else 0
    return nearest_f32_bits(Fraction(Decimal(text)))


def app_div_bits(int_val: int, scale: int) -> int:
    """Bits of the app's `intVal / scale.toFloat()` reconstruction."""
    if int_val == NEG_ZERO and scale == 10:
        return 0x80000000  # -0.0f sentinel
    return nearest_f32_bits(Fraction(int_val, scale))


def verify_bits(parsed, profiles_int) -> None:
    """For every value: float32(app reconstruction) == float32(original text parse)."""
    (preamp10, filters, (preamp_text, texts)) = parsed
    if profiles_int[0] != preamp10:
        fail("verify: preamp round-trip mismatch")
    if app_div_bits(preamp10, 10) != java_parse_float_bits(preamp_text):
        fail(f"verify: preamp {preamp_text} not bit-exact")
    if profiles_int[1] != filters:
        fail("verify: filter round-trip mismatch")
    for (fc, gain10, q100), (fc_t, gain_t, q_t) in zip(filters, texts):
        if nearest_f32_bits(Fraction(fc)) != java_parse_float_bits(fc_t):
            fail(f"verify: Fc {fc_t} not bit-exact")
        if app_div_bits(gain10, 10) != java_parse_float_bits(gain_t):
            fail(f"verify: gain {gain_t} not bit-exact")
        if app_div_bits(q100, 100) != java_parse_float_bits(q_t):
            fail(f"verify: Q {q_t} not bit-exact")


def derive_path(source: str, rig: str, typ: str, name: str) -> str:
    mid = f"{rig} " if rig else ""
    return f"{source}/{mid}{typ}/{name}.txt"


def put_str(out: bytearray, s: str, what: str) -> None:
    e = s.encode("utf-8")
    if len(e) > 255:
        fail(f"{what}: string too long for u8 length: {s!r}")
    out.append(len(e))
    out += e


def decode(data: bytes):
    """Reference decoder — mirrors AutoEqPack.kt; used to verify our own output."""
    if data[:4] != b"AEQ1":
        fail("verify: bad magic")
    pos = 4

    def read_str() -> str:
        nonlocal pos
        n = data[pos]
        pos += 1
        s = data[pos:pos + n].decode("utf-8")
        pos += n
        return s

    dicts = []
    for _ in range(3):
        count = data[pos]
        pos += 1
        dicts.append([read_str() for _ in range(count)])
    sources, rigs, types = dicts
    (count,) = struct.unpack_from("<I", data, pos)
    pos += 4
    entries = []
    for _ in range(count):
        name = read_str()
        si, ti, ri = data[pos], data[pos + 1], data[pos + 2]
        pos += 3
        entries.append((name, sources[si], types[ti], rigs[ri]))
    profiles = []
    for _ in range(count):
        (preamp10,) = struct.unpack_from("<b", data, pos)
        pos += 1
        filters = []
        for _ in range(10):
            fc, gain10, q100 = struct.unpack_from("<HhH", data, pos)
            pos += 6
            filters.append((fc, gain10, q100))
        profiles.append((preamp10, filters))
    if pos != len(data):
        fail(f"verify: trailing bytes ({len(data) - pos})")
    return entries, profiles


def main() -> int:
    index = json.loads(INDEX.read_text(encoding="utf-8"))
    if not isinstance(index, list) or not index:
        fail("index.json is not a non-empty list")

    on_disk = {p.relative_to(PROFILES).as_posix() for p in PROFILES.rglob("*.txt")}
    if len(on_disk) != len(index):
        fail(f"index has {len(index)} entries but {len(on_disk)} .txt files on disk")

    sources = sorted({e["s"] for e in index})
    rigs = sorted({e["r"] for e in index})
    types = sorted({e["t"] for e in index})
    for name, d in (("sources", sources), ("rigs", rigs), ("types", types)):
        if len(d) > 255:
            fail(f"too many {name} for u8 dictionary")
    smap = {s: i for i, s in enumerate(sources)}
    rmap = {s: i for i, s in enumerate(rigs)}
    tmap = {s: i for i, s in enumerate(types)}

    out = bytearray(b"AEQ1")
    for d in (sources, rigs, types):
        out.append(len(d))
        for s in d:
            put_str(out, s, "dictionary")
    out += struct.pack("<I", len(index))
    for e in index:
        put_str(out, e["n"], "entry name")
        out += bytes((smap[e["s"]], tmap[e["t"]], rmap[e["r"]]))

    parsed_all = []
    for e in index:
        if derive_path(e["s"], e["r"], e["t"], e["n"]) != e["p"]:
            fail(f"path not derivable for entry {e!r} (reader reconstructs AutoEqEntry.path)")
        if e["p"] not in on_disk:
            fail(f"missing profile file for {e['p']}")
        parsed = parse_profile(PROFILES / e["p"])
        parsed_all.append(parsed)
        out += struct.pack("<b", parsed[0])
        for fc, gain10, q100 in parsed[1]:
            out += struct.pack("<HhH", fc, gain10, q100)

    blob = bytes(out)

    # Self-verification: structural round-trip + float bit-exactness for every value.
    entries, profiles = decode(blob)
    if len(entries) != len(index):
        fail("verify: entry count mismatch")
    for i, (e, (name, source, typ, rig)) in enumerate(zip(index, entries)):
        if (name, source, typ, rig) != (e["n"], e["s"], e["t"], e["r"]):
            fail(f"verify: entry {i} mismatch")
        if derive_path(source, rig, typ, name) != e["p"]:
            fail(f"verify: entry {i} path mismatch")
    for parsed, prof in zip(parsed_all, profiles):
        verify_bits(parsed, prof)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(blob)
    print(f"pack_autoeq: {len(index)} profiles -> {OUT.relative_to(ROOT)} ({len(blob)} bytes, verified lossless)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
