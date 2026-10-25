#!/usr/bin/env python3
"""Scans every git-tracked file for committed secrets: private keys, keystores, API tokens, crypto
seed phrases / private keys, .env files, hard-coded passwords. Public donation addresses are fine.
Usage: secret_scan.py [repo-root]   (exit 1 on findings)"""
import os, re, subprocess, sys

PATTERNS = {
    "private key block": re.compile(r"-----BEGIN (?:RSA |EC |DSA |OPENSSH |PGP |ENCRYPTED )?PRIVATE KEY-----"),
    "GitHub token": re.compile(r"\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{36}\b|\bgithub_pat_[A-Za-z0-9_]{60,}\b"),
    "AWS access key": re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    "Google API key": re.compile(r"\bAIza[0-9A-Za-z_\-]{35}\b"),
    "Slack token": re.compile(r"\bxox[abprs]-[A-Za-z0-9-]{10,}\b"),
    "Stripe secret": re.compile(r"\bsk_live_[0-9a-zA-Z]{24,}\b"),
    "hex private key (64 hex)": re.compile(r"(?i)(?:priv(?:ate)?[_ ]?key|secret)\s*[:=]\s*['\"]?(?:0x)?[0-9a-f]{64}\b"),
    "WIF bitcoin private key": re.compile(r"\b[5KL][1-9A-HJ-NP-Za-km-z]{50,51}\b"),
    "password assignment": re.compile(r"(?i)\b(?:password|passwd|storePassword|keyPassword)\s*[:=]\s*['\"][^'\"\s$]{4,}['\"]"),
    "seed phrase": re.compile(r"(?i)\b(?:seed|mnemonic|recovery)\s*(?:phrase|words)?\s*[:=]\s*['\"]?(?:[a-z]{3,8}\s+){11,23}[a-z]{3,8}"),
}
BAD_FILES = re.compile(r"(?i)(^|/)(\.env(\..*)?|.*\.(jks|keystore|p12|pfx|pem|key)|id_rsa|id_ed25519|google-services\.json|local\.properties)$")
SKIP = re.compile(r"(?i)\.(png|jpg|jpeg|webp|gif|ico|svg|ttf|otf|woff2?|jar|so|a|zip|gz|wav|flac|mp3|pdf)$|^LICENSE$|^gradlew(\.bat)?$")
ALLOW = {"79KsqtJJdhKFJ9woxnYgtf3nq7HxQveafWBCtC3mxWi8"}  # public SOL donation address (base58, starts like WIF)


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    files = subprocess.run(["git", "-C", root, "ls-files"], capture_output=True, text=True, check=True).stdout.split("\n")
    findings = []
    for f in filter(None, files):
        if BAD_FILES.search(f) and not f.endswith(".example"):
            findings.append(f"{f}: secret-bearing file type is tracked")
        if SKIP.search(f):
            continue
        try:
            text = open(os.path.join(root, f), encoding="utf-8", errors="ignore").read()
        except (IsADirectoryError, FileNotFoundError):
            continue
        for name, rx in PATTERNS.items():
            for m in rx.finditer(text):
                if m.group(0) in ALLOW or any(a in m.group(0) for a in ALLOW):
                    continue
                line = text.count("\n", 0, m.start()) + 1
                findings.append(f"{f}:{line}: {name}")
    print(f"- {'✅' if not findings else '❌'} **Secret scan**: {len(files)} tracked files, {len(findings)} findings")
    for x in findings:
        print(f"  - ❌ {x}")
        if os.environ.get("GITHUB_ACTIONS") == "true":
            print(f"::error title=Secret scan::{x}", file=sys.stderr)
    sys.exit(1 if findings else 0)


if __name__ == "__main__":
    main()
