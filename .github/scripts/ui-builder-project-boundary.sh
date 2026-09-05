#!/usr/bin/env bash
#
# The UI builder is a second project inside this repository.
#
# It shares the release line, the CI and the git history with the server, and it does not share a
# dependency graph: the builder never reaches into the server, and the server reaches the builder
# only through the four modules it publishes. This script is what makes that a fact rather than an
# intention. See docs/design/UI_BUILDER_PROJECT_BOUNDARY.md for why the boundary is drawn here.
#
# Why a text scan and not a Gradle task. A resolved-classpath check sees compile and runtime edges
# and nothing else, and the edge this boundary lost most recently was neither: `:ui-builder-runtime`
# reached `project(":ui-builder").tasks` to copy a build output, which no classpath would ever
# report (#346, #350). A declaration is what the rule is actually about, so a declaration is what
# gets read.
#
# Usage: .github/scripts/ui-builder-project-boundary.sh [--list]
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)
cd "${repo_root}"

# The UI-builder project. Membership is frontend *and* runtime: the runtime is the builder's own
# service, and the fact that the server links it does not make it the server's.
ui_builder_modules=(
  ui-builder
  ui-builder-artwork
  ui-builder-export
  ui-builder-generated-jetcaster
  ui-builder-reference-jetcaster
  ui-builder-render-bundle
  ui-builder-renderer
  ui-builder-runtime
  ui-builder-web
)

# The server project: everything else this build contains.
server_modules=(
  mcp
  native-catalog-m3
  server
  slot-preview-runtime
  usage-source-psi
  wasm-ui
)

# What the server side may name. Every one of these is a published artifact with a POM a consumer
# outside this repository could resolve, which is the test for a seam: if the server could not
# depend on it across a repository boundary, it must not depend on it across this one.
#
# `:ui-builder` itself is deliberately absent. The editor is 39k lines of Compose reached as a Wasm
# distribution, never as a classpath.
seam_modules=(
  ui-builder-export
  ui-builder-render-bundle
  ui-builder-runtime
  ui-builder-web
)

contains() {
  local needle=$1
  shift
  local item
  for item in "$@"; do
    [[ "${item}" == "${needle}" ]] && return 0
  done
  return 1
}

if [[ "${1:-}" == "--list" ]]; then
  printf 'ui-builder project:\n'
  printf '  :%s\n' "${ui_builder_modules[@]}"
  printf 'server project:\n'
  printf '  :%s\n' "${server_modules[@]}"
  printf 'seams the server project may name:\n'
  printf '  :%s\n' "${seam_modules[@]}"
  exit 0
fi

failures=0
fail() {
  printf '%s\n' "$1" >&2
  failures=$((failures + 1))
}

# Every module in the build belongs to exactly one project. A new module that joins neither is the
# common way a boundary rots: nobody decided it was outside, nobody decided it was inside, and the
# check silently stopped covering part of the build.
declared_modules=$(
  grep -oE '^include\(":[a-z0-9-]+"\)' settings.gradle.kts | sed -E 's/^include\(":([a-z0-9-]+)"\)/\1/'
)
for module in ${declared_modules}; do
  if ! contains "${module}" "${ui_builder_modules[@]}" &&
    ! contains "${module}" "${server_modules[@]}"; then
    fail "unclassified module :${module} — add it to this script's ui-builder or server list"
  fi
done
for module in "${ui_builder_modules[@]}" "${server_modules[@]}"; do
  if ! grep -qF "include(\":${module}\")" settings.gradle.kts; then
    fail "this script lists :${module}, which settings.gradle.kts does not include"
  fi
done

# Direction 1: the builder never names the server. This is the load-bearing one — a second project
# that reaches back into its host is not a second project.
for module in "${ui_builder_modules[@]}"; do
  build_file="${module}/build.gradle.kts"
  [[ -f "${build_file}" ]] || continue
  for referenced in $(grep -oE 'project\(":[a-z0-9-]+"\)' "${build_file}" |
    sed -E 's/project\(":([a-z0-9-]+)"\)/\1/' | sort -u); do
    if contains "${referenced}" "${server_modules[@]}"; then
      fail ":${module} names :${referenced}, which is the server project — the UI builder must not depend on its host"
    fi
  done
done

# Direction 2: the server names only the seams. It may depend on the builder; it may not reach past
# the published surface into the editor, its artwork or its fixtures.
for module in "${server_modules[@]}"; do
  build_file="${module}/build.gradle.kts"
  [[ -f "${build_file}" ]] || continue
  for referenced in $(grep -oE 'project\(":[a-z0-9-]+"\)' "${build_file}" |
    sed -E 's/project\(":([a-z0-9-]+)"\)/\1/' | sort -u); do
    if contains "${referenced}" "${ui_builder_modules[@]}" &&
      ! contains "${referenced}" "${seam_modules[@]}"; then
      fail ":${module} names :${referenced}, which is inside the UI-builder project and is not one of its seams (${seam_modules[*]})"
    fi
  done
done

if ((failures > 0)); then
  printf '\n%s\n' "UI-builder project boundary: ${failures} violation(s)." >&2
  printf '%s\n' "The rule and how to change it: docs/design/UI_BUILDER_PROJECT_BOUNDARY.md" >&2
  exit 1
fi

printf '%s\n' "UI-builder project boundary holds."
