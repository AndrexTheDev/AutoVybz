#!/usr/bin/env python3
"""Summarises Gradle JUnit XML results (app/build/test-results/testDebugUnitTest): totals per test
class plus the PERF lines printed by PerformanceBudgetTest. Usage: junit_summary.py <dir>"""
import glob, os, re, sys, xml.etree.ElementTree as ET

def main():
    files = glob.glob(os.path.join(sys.argv[1], "*.xml"))
    total = fail = skip = 0; t = 0.0; rows = []; perf = []
    for f in sorted(files):
        r = ET.parse(f).getroot()
        n, fl, er, sk = (int(r.get(k, 0)) for k in ("tests", "failures", "errors", "skipped"))
        total += n; fail += fl + er; skip += sk; t += float(r.get("time", 0))
        rows.append((r.get("name", "?").split(".")[-1], n, fl + er))
        out = r.findtext("system-out") or ""
        perf += re.findall(r"^PERF .*$", out, re.M)
    print(f"- {'✅' if fail == 0 and total else '❌'} **JVM unit tests**: {total} tests in {len(files)} classes, "
          f"{fail} failed, {skip} skipped ({t:.0f} s)")
    for name, n, f in rows:
        print(f"  - {'❌' if f else '✅'} {name}: {n}" + (f" ({f} failed)" if f else ""))
    for p in perf:
        print(f"  - ⏱️ {p[5:]}")
    sys.exit(1 if fail or not total else 0)

if __name__ == "__main__":
    main()
