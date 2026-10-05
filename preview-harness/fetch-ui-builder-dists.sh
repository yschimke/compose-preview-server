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

fetch() {
  local asset="$1" into="$2"
  if ! curl -fsSL --retry 4 -o "${download}/${asset}" "${base}/${asset}"; then
    echo "::error::could not download ${asset} from release v${version}. Releases before the" \
      "harness fixtures were attached (compose-ui-builder#483) do not carry it." >&2
    exit 1
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
