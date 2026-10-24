#!/usr/bin/env python3
"""Set / insert AutoVybz strings in en, de and es at once (keeps the three files key-aligned).

Usage from Python:  from set_strings import put; put("avz_key", en, de, es, after="avz_other_key")
Values are raw XML text (escape ' as \\' and & as &amp;)."""
import os, re

RES = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "res")
FILES = {"en": "values", "de": "values-de", "es": "values-es"}


def _path(loc):
    return os.path.join(RES, FILES[loc], "autovybz_strings.xml")


def put(key, en, de, es, after=None, attrs=""):
    for loc, text in (("en", en), ("de", de), ("es", es)):
        p = _path(loc)
        s = open(p, encoding="utf-8").read()
        line = f'    <string name="{key}"{attrs}>{text}</string>'
        pat = re.compile(rf'^    <string name="{re.escape(key)}"[^>]*>.*?</string>$', re.M | re.S)
        if pat.search(s):
            s = pat.sub(lambda m: line, s, count=1)
        else:
            if after:
                a = re.compile(rf'^    <(string|plurals) name="{re.escape(after)}"[^>]*>.*?</\1>$', re.M | re.S).search(s)
                if not a:
                    raise SystemExit(f"anchor {after} not found in {loc}")
                s = s[: a.end()] + "\n" + line + s[a.end():]
            else:
                i = s.rindex("</resources>")
                s = s[:i] + line + "\n" + s[i:]
        open(p, "w", encoding="utf-8").write(s)


def remove(key):
    for loc in FILES:
        p = _path(loc)
        s = open(p, encoding="utf-8").read()
        s2 = re.sub(rf'^    <string name="{re.escape(key)}"[^>]*>.*?</string>\n', "", s, flags=re.M | re.S)
        open(p, "w", encoding="utf-8").write(s2)
