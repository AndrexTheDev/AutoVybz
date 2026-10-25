#!/usr/bin/env python3
"""Parses `am instrument -r` output into a Markdown summary (stdout), JSON and GitHub annotations.

Status codes: 1 start, 0 ok, -1 error, -2 failure, -3 ignored, -4 assumption failure.
Exit code 1 if any test failed/errored or the run crashed.
"""
import argparse, json, os, re, sys

def parse(text):
    """Each status block's fields precede its INSTRUMENTATION_STATUS_CODE line."""
    tests, crash = [], None
    pieces = re.split(r"\nINSTRUMENTATION_STATUS_CODE: ", "\n" + text)
    for i in range(len(pieces) - 1):
        fields = dict(re.findall(r"INSTRUMENTATION_STATUS: (\w+)=(.*?)(?=\nINSTRUMENTATION_STATUS: |\nINSTRUMENTATION_STATUS_CODE: |\Z)", pieces[i], re.S))
        m = re.match(r"(-?\d+)", pieces[i + 1])
        if not m or "test" not in fields:
            continue
        code = int(m.group(1))
        if code != 1:
            tests.append({"class": fields.get("class", "?").strip(), "test": fields["test"].strip(),
                          "code": code, "stack": fields.get("stack", "").strip()})
    m = re.search(r"INSTRUMENTATION_RESULT: shortMsg=(.*)", text)
    if m:
        crash = m.group(1).strip()
    if "Process crashed" in text and not crash:
        crash = "Process crashed"
    return tests, crash

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("file"); ap.add_argument("--suite", default="instrumented"); ap.add_argument("--api", default="?")
    ap.add_argument("--json")
    a = ap.parse_args()
    text = open(a.file, encoding="utf-8", errors="replace").read()
    tests, crash = parse(text)
    names = {0: "passed", -1: "error", -2: "failed", -3: "ignored", -4: "skipped"}
    count = {v: 0 for v in names.values()}
    for t in tests:
        count[names.get(t["code"], "error")] += 1
    bad = [t for t in tests if t["code"] in (-1, -2)]
    gha = os.environ.get("GITHUB_ACTIONS") == "true"
    icon = "✅" if not bad and not crash and tests else "❌"
    print(f"- {icon} **{a.suite}** (API {a.api}): {count['passed']} passed, {len(bad)} failed, "
          f"{count['skipped']} skipped, {count['ignored']} ignored" + (f" — run aborted: {crash}" if crash else ""))
    for t in bad:
        first = next((l for l in t["stack"].splitlines() if l.strip()), "?")
        print(f"  - `{t['class'].split('.')[-1]}.{t['test']}`: {first[:300]}")
        if gha:
            msg = t["stack"][:1500].replace("%", "%25").replace("\r", "").replace("\n", "%0A")
            print(f"::error title=E2E API {a.api} {t['class'].split('.')[-1]}.{t['test']}::{msg}", file=sys.stderr)
    if crash and gha:
        print(f"::error title=E2E API {a.api} {a.suite}::instrumentation aborted: {crash}", file=sys.stderr)
    if not tests and not crash:
        print(f"  - no tests ran", file=sys.stdout)
    if a.json:
        json.dump({"suite": a.suite, "api": a.api, "crash": crash, "failed": len(bad), **count,
                   "tests": tests}, open(a.json, "w"), indent=1)
    sys.exit(1 if bad or crash or not tests else 0)

if __name__ == "__main__":
    main()
