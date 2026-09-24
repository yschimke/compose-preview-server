#!/usr/bin/env bash
# Guard: the renderer sidecar the image points at must carry `window-core-desktop`.
#
# `/opt/lib-renderer` comes from compose-preview-daemon's desktop tarball, and that tarball does not
# carry `org.jetbrains.androidx.window:window-core`. The server distribution's own `lib-renderer/`
# does (`server/build.gradle.kts`, #812), but `-Dcomposeai.cli.libRendererDir=/opt/lib-renderer`
# makes the lookup skip it. Without the jar, the first UI-builder PNG export of a constrained frame
# dies on `NoClassDefFoundError: androidx/window/core/layout/WindowSizeClass`, and the render
# breaker then disables the lane for every design on the box.
#
# So: assert the runtime stage copies the jar from the server stage into /opt/lib-renderer, AFTER
# the directory itself is copied (a later `COPY … /opt/lib-renderer` would replace it).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
dockerfile="${DOCKERFILE:-${here}/Dockerfile}"

check() {
  local file="$1"
  local dir_line jar_line
  dir_line="$(grep -nE '^COPY --from=[a-z0-9-]+ /opt/lib-renderer /opt/lib-renderer$' "${file}" | tail -n 1 | cut -d: -f1)"
  jar_line="$(grep -nE '^COPY --from=server "?/opt/compose-preview-server-\$\{SERVER_VERSION\}/lib-renderer/window-core-desktop-\*\.jar"? /opt/lib-renderer/$' "${file}" | tail -n 1 | cut -d: -f1)"
  [[ -n "${dir_line}" && -n "${jar_line}" && "${jar_line}" -gt "${dir_line}" ]]
}

check "${dockerfile}" || {
  echo "FAIL: the runtime stage must COPY the server's lib-renderer/window-core-desktop-*.jar into" >&2
  echo "      /opt/lib-renderer after copying that directory, or UI-builder PNG export dies on" >&2
  echo "      NoClassDefFoundError: androidx/window/core/layout/WindowSizeClass." >&2
  exit 1
}
echo "PASS: /opt/lib-renderer carries window-core-desktop from the server distribution"

# Self-test: the jar copied BEFORE the directory is overwritten by it, and must be caught.
tmp="$(mktemp)"
trap 'rm -f "${tmp}"' EXIT
cat >"${tmp}" <<'BAD'
FROM base AS runtime
COPY --from=server "/opt/compose-preview-server-${SERVER_VERSION}/lib-renderer/window-core-desktop-*.jar" /opt/lib-renderer/
COPY --from=desktop-daemon /opt/lib-renderer /opt/lib-renderer
BAD
if check "${tmp}"; then
  echo "FAIL: self-test — a jar copied before its directory was not caught." >&2
  exit 1
fi
echo "PASS: self-test — a jar copied before the directory is caught"
