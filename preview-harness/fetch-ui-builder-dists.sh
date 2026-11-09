#!/usr/bin/env bash
# Lay out the UI builder's four Wasm distributions, from the release this repository pins, the way
# the harness reads them from a checkout of yschimke/compose-ui-builder:
#
#   <dest>/ui-builder/build/wasmDist                       compose-preview-ui-builder-web-<v>.zip
#   <dest>/ui-builder-renderer/build/wasmRendererDist      compose-preview-ui-builder-renderer-<v>.zip
#   <dest>/ui-builder-reference-jetcaster/build/wasmDist   compose-preview-ui-builder-reference-jetcaster-<v>.zip
#   <dest>/ui-builder-generated-jetcaster/build/wasmDist   compose-preview-ui-builder-generated-jetcaster-<v>.zip
#
# Then point the harness at it with `COMPOSE_UI_BUILDER_DIR=<dest>`. The version is
# `composeai-ui-builder` in gradle/libs.versions.toml -- the same release `:server:installDist`
# takes the editor from -- so the harness tests the builder this server ships, not whatever that
# repository's `main` holds today. To test against a builder checkout instead, build those four
# tasks in it and point `COMPOSE_UI_BUILDER_DIR` there; this script is not involved.
#
# Usage: preview-harness/fetch-ui-builder-dists.sh <dest> [version]
set -euo pipefail

dest="${1:?usage: $0 <dest> [version]}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
version="${2:-$(sed -n 's/^composeai-ui-builder = "\(.*\)"$/\1/p' "${root}/gradle/libs.versions.toml")}"
if [ -z "${version}" ]; then
  echo "::error::no composeai-ui-builder version in gradle/libs.versions.toml" >&2
  exit 1
fi
base="https://github.com/yschimke/compose-ui-builder/releases/download/v${version}"

download=$(mktemp -d)
trap 'rm -rf "${download}"' EXIT

# GitHub's browser release URL can return persistent 504s while the public asset API serves the
# same release. Keep the normal retries first; the fallback never changes the pin or accepts bytes
# without the exact release asset's reported size and SHA-256. No GitHub token is needed.
fetch_api() {
  local asset="$1"
  local api="https://api.github.com/repos/yschimke/compose-ui-builder"
  curl -fsSL --retry 4 -o "${download}/release.json" "${api}/releases/tags/v${version}" || return 1
  python3 - "${download}/release.json" "${version}" "${asset}" "${api}" >"${download}/asset.txt" <<'PY' || return 1
import json
import re
import sys

try:
    release = json.load(open(sys.argv[1], encoding="utf-8"))
    if release.get("tag_name") != "v" + sys.argv[2]:
        raise ValueError("release tag mismatch")
    matches = [asset for asset in release.get("assets", []) if asset.get("name") == sys.argv[3]]
    if len(matches) != 1:
        raise ValueError("exact asset name missing or ambiguous")
    asset = matches[0]
    if type(asset.get("id")) is not int or asset["id"] <= 0:
        raise ValueError("invalid asset id")
    url = sys.argv[4] + "/releases/assets/" + str(asset["id"])
    if asset.get("url") != url or asset.get("state") != "uploaded":
        raise ValueError("unexpected asset URL or upload state")
    if type(asset.get("size")) is not int or asset["size"] <= 0:
        raise ValueError("invalid asset size")
    digest = asset.get("digest", "")
    if not isinstance(digest, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
        raise ValueError("asset has no SHA-256 digest")
    print(url)
    print(asset["size"])
    print(digest[7:])
except (ValueError, TypeError, AttributeError, OSError) as error:
    sys.exit("release API metadata refused: " + str(error))
PY
  local api_url size digest
  {
    IFS= read -r api_url && IFS= read -r size && IFS= read -r digest
  } <"${download}/asset.txt" || return 1
  curl -fsSL --retry 4 -H 'Accept: application/octet-stream' \
    -o "${download}/${asset}" "${api_url}" || return 1
  python3 - "${download}/${asset}" "${size}" "${digest}" <<'PY'
import hashlib
import os
import sys

if os.path.getsize(sys.argv[1]) != int(sys.argv[2]):
    sys.exit("release API download size mismatch")
digest = hashlib.sha256()
with open(sys.argv[1], "rb") as stream:
    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
        digest.update(chunk)
if digest.hexdigest() != sys.argv[3]:
    sys.exit("release API download SHA-256 mismatch")
PY
}

fetch() {
  local asset="$1" into="$2"
  if ! curl -fsSL --retry 4 -o "${download}/${asset}" "${base}/${asset}"; then
    echo "Release URL failed for ${asset}; trying the same pinned asset through GitHub's public API." >&2
    if ! fetch_api "${asset}"; then
      echo "::error::could not download and verify ${asset} from release v${version}." >&2
      exit 1
    fi
  fi
  rm -rf "${dest:?}/${into}"
  mkdir -p "${dest}/${into}"
  unzip -q "${download}/${asset}" -d "${dest}/${into}"
}

fetch "compose-preview-ui-builder-web-${version}.zip" ui-builder/build/wasmDist
fetch "compose-preview-ui-builder-renderer-${version}.zip" ui-builder-renderer/build/wasmRendererDist
fetch "compose-preview-ui-builder-reference-jetcaster-${version}.zip" ui-builder-reference-jetcaster/build/wasmDist
fetch "compose-preview-ui-builder-generated-jetcaster-${version}.zip" ui-builder-generated-jetcaster/build/wasmDist
echo "UI builder v${version} distributions in ${dest}"
