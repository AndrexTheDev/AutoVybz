#!/usr/bin/env python3
"""Offline mirror of the most failure-prone StringResourceAuditTest rules (run before pushing; CI is the truth)."""
import os, re, sys, glob
ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
RES = os.path.join(ROOT, "app/src/main/res")
ph = re.compile(r"%(\d+\$)?[sdf]")
def load(loc):
    s = open(f"{RES}/{loc}/autovybz_strings.xml", encoding="utf-8").read()
    strings = {m.group(1): (m.group(2), m.group(3)) for m in re.finditer(r'<string name="(\w+)"([^>]*)>(.*?)</string>', s, re.S)}
    plurals = set(re.findall(r'<plurals name="(\w+)"', s))
    return strings, plurals
locs = ["values", "values-de", "values-es"]
data = {l: load(l) for l in locs}
base, bplur = data["values"]
problems = []
for l in locs[1:]:
    st, pl = data[l]
    if set(st) != set(base): problems.append(f"{l}: key diff {sorted(set(st) ^ set(base))}")
    if pl != bplur: problems.append(f"{l}: plural diff")
    for k, (a, t) in st.items():
        if k in base and sorted(ph.findall(t)) != sorted(ph.findall(base[k][1])): problems.append(f"{l}/{k}: placeholder mismatch")
for l in locs:
    for k, (a, t) in data[l][0].items():
        u = t.replace("\\'", "'")
        if 'formatted="false"' not in a and re.search(r"% [A-Za-zÀ-ÿ]", ph.sub("", u)): problems.append(f"{l}/{k}: '% letter' needs formatted=false")
        if 'formatted="false"' in a and ph.search(u): problems.append(f"{l}/{k}: formatted=false with placeholders")
        if re.search(r"\d %", u): problems.append(f"{l}/{k}: normal space before %")
        if ph.search(u) and "%" in ph.sub("", u).replace("%%", ""): problems.append(f"{l}/{k}: stray %")
        if "'" in t and "\\'" not in t: problems.append(f"{l}/{k}: unescaped apostrophe")
        if "..." in t: problems.append(f"{l}/{k}: ...")
        if t != t.strip(): problems.append(f"{l}/{k}: whitespace")
code = "\n".join(open(f, encoding="utf-8").read() for f in glob.glob(f"{ROOT}/app/src/main/java/com/andrexthedev/**/*.kt", recursive=True))
resx = "\n".join(open(f, encoding="utf-8").read() for f in glob.glob(f"{ROOT}/app/src/main/**/*.xml", recursive=True) if not f.endswith("autovybz_strings.xml"))
used = set(re.findall(r"R\.(?:string|plurals)\.(avz_\w+)", code)) | set(re.findall(r"@string/(avz_\w+)", resx))
allk = set(base) | bplur
problems += [f"undefined: {k}" for k in sorted(used - allk)]
problems += [f"unused: {k}" for k in sorted(allk - used)]
for f in glob.glob(f"{ROOT}/app/src/main/java/com/andrexthedev/**/ui/**/*.kt", recursive=True):
    for m in re.finditer(r'\bText\(\s*"([^"$]*[A-Za-zÄÖÜäöüß]{3,}[^"]*)"', open(f, encoding="utf-8").read()):
        if m.group(1) not in ("Auto", "Vybz", "GitHub") and not m.group(1).startswith(("github.com/", "https://")):
            problems.append(f"hardcoded text {os.path.basename(f)}: {m.group(1)}")
print("\n".join(problems) or "strings OK", f"({len(base)} strings)")
sys.exit(1 if problems else 0)
