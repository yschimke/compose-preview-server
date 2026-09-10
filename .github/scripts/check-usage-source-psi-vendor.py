#!/usr/bin/env python3
"""Verify or refresh the compose-ai-tools-owned usage-source-psi source vendor."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / "usage-source-psi"
SOURCE = MODULE / "src/main"
MANIFEST = MODULE / "upstream.json"
SHA = re.compile(r"^[0-9a-f]{40}$")


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check() -> int:
    manifest = json.loads(MANIFEST.read_text())
    revision = manifest["revision"]
    if not SHA.fullmatch(revision):
        print(f"error: vendor revision is not an immutable commit SHA: {revision!r}")
        return 1

    expected = manifest["files"]
    actual = {
        path.relative_to(SOURCE).as_posix(): digest(path)
        for path in SOURCE.rglob("*")
        if path.is_file()
    }
    failures = []
    for name in sorted(set(expected) | set(actual)):
        if name not in expected:
            failures.append(f"unmanifested vendored source: {name}")
        elif name not in actual:
            failures.append(f"missing vendored source: {name}")
        elif expected[name] != actual[name]:
            failures.append(
                f"vendored source drift: {name} (expected {expected[name]}, got {actual[name]})"
            )

    if failures:
        print("\n".join(f"error: {failure}" for failure in failures))
        print(
            "refresh from a reviewed upstream commit with "
            "python3 .github/scripts/check-usage-source-psi-vendor.py --refresh <40-char-sha>"
        )
        return 1

    print(
        f"usage-source-psi matches {manifest['repository']}@{revision[:12]} "
        f"({len(actual)} files)"
    )
    return 0


def fetch_json(url: str) -> object:
    request = urllib.request.Request(url, headers={"Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def fetch_bytes(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=30) as response:
        return response.read()


def refresh(revision: str) -> int:
    if not SHA.fullmatch(revision):
        print("error: --refresh requires a full 40-character commit SHA")
        return 1

    old = json.loads(MANIFEST.read_text())
    repository = old["repository"]
    prefix = old["prefix"]
    tree = fetch_json(
        f"https://api.github.com/repos/{repository}/git/trees/{revision}?recursive=1"
    )
    entries = {
        item["path"][len(prefix) :]: item["path"]
        for item in tree["tree"]
        if item["type"] == "blob" and item["path"].startswith(prefix)
    }
    if not entries:
        print(f"error: no sources found under {repository}@{revision}:{prefix}")
        return 1

    staging = MODULE / ".upstream-refresh"
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir()
    hashes = {}
    for relative, upstream_path in sorted(entries.items()):
        body = fetch_bytes(
            f"https://raw.githubusercontent.com/{repository}/{revision}/{upstream_path}"
        )
        target = staging / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(body)
        hashes[relative] = hashlib.sha256(body).hexdigest()

    shutil.rmtree(SOURCE)
    staging.rename(SOURCE)
    MANIFEST.write_text(
        json.dumps(
            {
                "repository": repository,
                "revision": revision,
                "prefix": prefix,
                "files": hashes,
            },
            indent=2,
        )
        + "\n"
    )
    return check()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--refresh", metavar="SHA")
    args = parser.parse_args()
    return refresh(args.refresh) if args.refresh else check()


if __name__ == "__main__":
    raise SystemExit(main())
