#!/usr/bin/env python3
"""Known-vulnerability check of the shipped dependencies against OSV.dev (Maven ecosystem).
Input: output of `./gradlew :app:dependencies --configuration debugRuntimeClasspath`.
Usage: osv_check.py <deps.txt>   (exit 1 on HIGH/CRITICAL vulnerabilities)"""
import json, os, re, sys, urllib.request

def coords(text):
    out = set()
    for line in text.splitlines():
        m = re.search(r"[+\\]--- ([\w.\-]+):([\w.\-]+):([\w.\-]+)(?: -> ([\w.\-]+))?", line)
        if m and "{strictly" not in line:
            out.add((m.group(1), m.group(2), m.group(4) or m.group(3)))
    return sorted(out)

def main():
    deps = coords(open(sys.argv[1]).read())
    queries = [{"package": {"ecosystem": "Maven", "name": f"{g}:{a}"}, "version": v} for g, a, v in deps]
    vulns = []
    for i in range(0, len(queries), 500):
        req = urllib.request.Request("https://api.osv.dev/v1/querybatch", data=json.dumps({"queries": queries[i:i + 500]}).encode(),
                                     headers={"Content-Type": "application/json"})
        res = json.load(urllib.request.urlopen(req, timeout=60))
        for q, r in zip(queries[i:i + 500], res.get("results", [])):
            for v in r.get("vulns", []) or []:
                vulns.append((q["package"]["name"], q["version"], v["id"]))
    severe = []
    details = []
    for name, ver, vid in vulns:
        try:
            d = json.load(urllib.request.urlopen(f"https://api.osv.dev/v1/vulns/{vid}", timeout=30))
            sev = (d.get("database_specific") or {}).get("severity", "UNKNOWN")
            summary = d.get("summary", "")[:140]
        except Exception:
            sev, summary = "UNKNOWN", ""
        details.append((name, ver, vid, sev, summary))
        if sev in ("HIGH", "CRITICAL"):
            severe.append(vid)
    print(f"- {'✅' if not severe else '❌'} **Dependency vulnerabilities (OSV.dev)**: {len(deps)} resolved artifacts, "
          f"{len(vulns)} advisories ({len(severe)} high/critical)")
    for name, ver, vid, sev, summary in details:
        print(f"  - {'❌' if sev in ('HIGH','CRITICAL') else '⚠️'} {name}:{ver} {vid} [{sev}] {summary}")
        if os.environ.get("GITHUB_ACTIONS") == "true":
            print(f"::{'error' if sev in ('HIGH','CRITICAL') else 'warning'} title=OSV {vid}::{name}:{ver} {summary}", file=sys.stderr)
    sys.exit(1 if severe else 0)

if __name__ == "__main__":
    main()
