#!/usr/bin/env python3
"""Local preview that mimics GitHub Pages: serves site/_site under /AutoVybz/ (+ 404.html).

    python3 site/build.py && python3 site/tools/serve.py [port]
"""
import http.server
import os
import sys
from pathlib import Path

SITE = Path(__file__).resolve().parents[1] / "_site"
PREFIX = "/AutoVybz/"


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=str(SITE), **kw)

    def send_head(self):
        path = self.path.split("?", 1)[0].split("#", 1)[0]
        if path in ("/", ""):
            self.send_response(302); self.send_header("Location", PREFIX); self.end_headers(); return None
        if path == PREFIX.rstrip("/"):
            self.send_response(301); self.send_header("Location", PREFIX); self.end_headers(); return None
        if not path.startswith(PREFIX):
            return self.not_found()
        rel = path[len(PREFIX):]
        fs = SITE / rel
        if fs.is_dir() and not path.endswith("/"):
            self.send_response(301); self.send_header("Location", path + "/"); self.end_headers(); return None
        if fs.is_dir():
            fs = fs / "index.html"
        if not fs.exists():
            return self.not_found()
        self.path = "/" + str(fs.relative_to(SITE))
        return super().send_head()

    def not_found(self):
        body = (SITE / "404.html").read_bytes()
        self.send_response(404); self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body))); self.end_headers()
        self.wfile.write(body)
        return None

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    print(f"serving {SITE} at http://0.0.0.0:{port}{PREFIX}")
    http.server.ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
