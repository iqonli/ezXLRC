#!/usr/bin/env python3
"""Syntax/dependency audit for the live ESLyric searcher list used by ezXLRC."""

from __future__ import annotations

import json
import re
import subprocess
import urllib.request

BASE = "https://raw.githubusercontent.com/ESLyric/scripts/main"
ALLOWED_IMPORTS = {
    "crypto-es/lib/index.js",
    "himalaya/src/index.js",
}
SAFE_URI = re.compile(r"^[A-Za-z0-9_.-]+\.js$")
IMPORT = re.compile(r"^\s*import\s+(?:[\s\S]*?)\s+from\s+['\"]([^'\"]+)['\"]\s*;?\s*$", re.MULTILINE)


def fetch(url: str) -> str:
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "ezXLRC-upstream-audit/1.0", "Accept": "application/json, text/javascript"},
    )
    with urllib.request.urlopen(request, timeout=25) as response:
        if response.status != 200:
            raise RuntimeError(f"HTTP {response.status}: {url}")
        return response.read().decode("utf-8-sig")


def main() -> None:
    manifest = json.loads(fetch(f"{BASE}/archive/version.json"))
    sources = manifest.get("sources")
    if not isinstance(sources, list) or not sources:
        raise SystemExit("ESLyric manifest has no sources list")

    checked = 0
    for item in sources:
        uri = str(item.get("uri", ""))
        if not SAFE_URI.fullmatch(uri):
            raise SystemExit(f"Unsafe searcher path in upstream manifest: {uri!r}")
        code = fetch(f"{BASE}/searcher/{uri}")
        if not re.search(r"\bfunction\s+getLyrics\s*\(", code):
            raise SystemExit(f"Searcher has no getLyrics function: {uri}")

        imports = set(IMPORT.findall(code))
        unsupported = imports - ALLOWED_IMPORTS
        if unsupported:
            raise SystemExit(f"Unsupported imports in {uri}: {', '.join(sorted(unsupported))}")

        result = subprocess.run(
            ["node", "--input-type=module", "--check"],
            input=code,
            text=True,
            capture_output=True,
            check=False,
        )
        if result.returncode:
            raise SystemExit(f"JavaScript syntax error in {uri}:\n{result.stderr}")
        checked += 1

    print(f"Validated {checked} ESLyric searcher scripts; imports are covered by ezXLRC's runtime adapters.")


if __name__ == "__main__":
    main()
