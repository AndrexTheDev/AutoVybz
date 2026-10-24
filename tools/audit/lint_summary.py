#!/usr/bin/env python3
"""Summarises Android Lint (lint-results-debug.xml). AutoVybz code/resources must have 0 errors;
inherited Equalizer314 findings are reported for information only.
Usage: lint_summary.py <lint-results.xml>   (exit 1 if AutoVybz has lint errors)"""
import collections, os, sys, xml.etree.ElementTree as ET

def is_autovybz(path):
    p = path.replace("\\", "/")
    return "/com/andrexthedev/" in p or "/autovybz_" in p or "/locales_config" in p

def main():
    xml = sys.argv[1]
    if not os.path.exists(xml):
        print(f"- ⚠️ **Lint**: no report at {xml}"); sys.exit(0)
    issues = ET.parse(xml).getroot().findall("issue")
    own, upstream = collections.Counter(), collections.Counter()
    own_list = []
    for i in issues:
        loc = i.find("location")
        path = loc.get("file", "") if loc is not None else ""
        key = (i.get("severity"), i.get("id"))
        if is_autovybz(path):
            own[key] += 1
            own_list.append((i.get("severity"), i.get("id"), os.path.relpath(path) if path else "?", loc.get("line", "?") if loc is not None else "?", i.get("message", "")))
        else:
            upstream[key] += 1
    own_errors = [x for x in own_list if x[0] in ("Error", "Fatal")]
    print(f"- {'✅' if not own_errors else '❌'} **Android Lint**: AutoVybz {sum(own.values())} findings "
          f"({len(own_errors)} errors) · inherited Equalizer314 code {sum(upstream.values())} findings")
    for sev, iid, path, line, msg in sorted(own_list)[:60]:
        print(f"  - {'❌' if sev in ('Error','Fatal') else '⚠️'} {sev} `{iid}` {path}:{line} – {msg[:160]}")
        if os.environ.get("GITHUB_ACTIONS") == "true":
            level = "error" if sev in ("Error", "Fatal") else "warning"
            print(f"::{level} file={path},line={line},title=Lint {iid}::{msg[:300]}", file=sys.stderr)
    top = ", ".join(f"{iid} ×{n}" for (sev, iid), n in upstream.most_common(8))
    if top:
        print(f"  - ℹ️ most frequent inherited findings: {top}")
    sys.exit(1 if own_errors else 0)

if __name__ == "__main__":
    main()
