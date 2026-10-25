#!/usr/bin/env python3
"""APK audit: size budget, native libraries for all ABIs, bundled AutoVybz assets (mic DB, targets),
manifest facts via aapt2 (package, launcher, permissions, min/target SDK).
Usage: apk_audit.py <app.apk> [aapt2]   (exit 1 on violations)"""
import json, os, re, subprocess, sys, zipfile

BUDGET_MB = 60
ABIS = ["arm64-v8a", "armeabi-v7a", "x86_64"]

def main():
    apk = sys.argv[1]; aapt2 = sys.argv[2] if len(sys.argv) > 2 else None
    errors, facts = [], []
    size = os.path.getsize(apk) / 1_048_576
    facts.append(f"size {size:.1f} MB (budget {BUDGET_MB} MB)")
    if size > BUDGET_MB: errors.append(f"APK {size:.1f} MB exceeds {BUDGET_MB} MB")
    z = zipfile.ZipFile(apk)
    names = z.namelist()
    dex = [n for n in names if re.fullmatch(r"classes\d*\.dex", n)]
    facts.append(f"{len(dex)} dex files")
    for abi in ABIS:
        libs = [n for n in names if n.startswith(f"lib/{abi}/") and n.endswith(".so")]
        if not libs: errors.append(f"no native libraries for {abi}")
        else: facts.append(f"{abi}: {', '.join(sorted(os.path.basename(l) for l in libs))}")
    mic = [n for n in names if n.startswith("assets/autovybz/") and n.endswith(".json")]
    if not mic: errors.append("assets/autovybz/*.json (mic profile DB) missing")
    for n in mic:
        try: json.loads(z.read(n))
        except Exception as e: errors.append(f"{n} invalid JSON: {e}")
    facts.append(f"{len(mic)} AutoVybz JSON assets, {len([n for n in names if n.startswith('assets/targets/')])} target files")
    big = sorted(((z.getinfo(n).compress_size, n) for n in names), reverse=True)[:5]
    facts.append("largest entries: " + ", ".join(f"{n} {s/1024:.0f} KB" for s, n in big))
    if aapt2 and os.path.exists(aapt2):
        out = subprocess.run([aapt2, "dump", "badging", apk], capture_output=True, text=True).stdout
        pkg = re.search(r"package: name='([^']+)'.*versionName='([^']*)'", out)
        if not pkg or pkg.group(1) != "com.andrexthedev.autovybz": errors.append(f"unexpected package {pkg.group(1) if pkg else '?'}")
        else: facts.append(f"package {pkg.group(1)} {pkg.group(2)}")
        la = re.search(r"launchable-activity: name='([^']+)'", out)
        if not la or not la.group(1).endswith("AutoVybzActivity"): errors.append(f"launcher is {la.group(1) if la else '?'}")
        sdk = re.search(r"(?<![A-Za-z])(?:minSdkVersion|sdkVersion):'(\d+)'", out); tsdk = re.search(r"targetSdkVersion:'(\d+)'", out)
        facts.append(f"minSdk {sdk.group(1) if sdk else '?'} / targetSdk {tsdk.group(1) if tsdk else '?'}")
        if sdk and sdk.group(1) != "28": errors.append(f"minSdk is {sdk.group(1)}, expected 28 (Android 9, DynamicsProcessing)")
        label = re.search(r"application-label:'([^']+)'", out)
        if not label or label.group(1) != "AutoVybz": errors.append(f"app label is {label.group(1) if label else '?'}")
        locales = re.search(r"locales: (.*)", out)
        if locales: facts.append(f"resource locales: {locales.group(1).count(chr(39)) // 2}")
        perms = sorted(set(re.findall(r"uses-permission: name='([^']+)'", out)))
        facts.append(f"{len(perms)} permissions")
    print(f"- {'✅' if not errors else '❌'} **APK audit**: " + "; ".join(facts[:3]))
    for f in facts[3:]: print(f"  - {f}")
    for e in errors:
        print(f"  - ❌ {e}")
        if os.environ.get("GITHUB_ACTIONS") == "true": print(f"::error title=APK audit::{e}", file=sys.stderr)
    sys.exit(1 if errors else 0)

if __name__ == "__main__":
    main()
