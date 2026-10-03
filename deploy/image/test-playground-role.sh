#!/usr/bin/env bash
# Guard: the split playground deployment's two halves get the flags they need, and only those.
#
# `SERVE_ROLE=playground` runs this image as the public playground's own container. It shares
# `preview`'s config volume, so every lane that would write state beside catalogs.json must stay
# off there, and the theme optimizer and background warming must not duplicate the main server's
# work. The main server, for its part, keeps those lanes and adds `--playground-external`.
#
# Runs the real entrypoint against a stub `compose-preview-server` that records its arguments, so a
# default added later that quietly re-enables a writer in the role fails here. No Docker, no network.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
entrypoint="${ENTRYPOINT_FILE:-${here}/entrypoint.sh}"
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

mkdir -p "${work}/bin" "${work}/project"
cat >"${work}/bin/compose-preview-server" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$@" >"${STUB_OUT}"
printf 'JAVA_TOOL_OPTIONS=%s\n' "${JAVA_TOOL_OPTIONS:-}" >>"${STUB_OUT}"
EOF
chmod +x "${work}/bin/compose-preview-server"
# The image runs from /project; point the copy at a scratch directory instead.
sed "s#^cd /project\$#cd '${work}/project'#" "${entrypoint}" >"${work}/entrypoint.sh"

fail=0
note() { echo "FAIL: $*" >&2; fail=1; }

run() {
  rm -f "${work}/out"
  env -i PATH="${work}/bin:/usr/bin:/bin" HOME="${work}" STUB_OUT="${work}/out" PORT=8080 \
    SERVE_CATALOGS_FILE=none SERVE_TRUST_STORE=none SERVE_PUBLIC=1 SERVE_LIVE_SEATS=2 \
    SERVE_GITHUB_AUTH_CLIENT_ID=id SERVE_GITHUB_AUTH_CLIENT_SECRET=secret \
    SERVE_GITHUB_AUTH_COOKIE_SECRET=cookie SERVE_IMAGE_UPLOAD_REPO=o/r \
    "$@" bash "${work}/entrypoint.sh" >"${work}/log" 2>&1
}
has() { grep -qx -- "$1" "${work}/out"; }

# --- the playground's own container -------------------------------------------------------------
run SERVE_ROLE=playground SERVE_ACCEPT_DOCS=1 || note "the playground role did not start: $(tail -1 "${work}/log")"
has --role || note "the playground role does not pass --role playground"
for writer in --engagement-file --agent-grants --accept-images --ui-builder-dir --compile-engine; do
  has "${writer}" && note "the playground role passes ${writer}, which writes or serves state it must not"
done
has --accept-docs || note "the playground role drops --accept-docs, so Remote Compose mode has nowhere to publish"
grep -q -- '-Dcomposeai.serve.themeOptimization=false' "${work}/out" ||
  note "the playground role leaves the theme optimizer on"
grep -q -- '-Dcomposeai.serve.warmInBackground=false' "${work}/out" ||
  note "the playground role leaves background warming on"

# An operator's SERVE_JAVA_OPTS still wins (appended after the role's defaults).
run SERVE_ROLE=playground SERVE_JAVA_OPTS=-Dcomposeai.serve.themeOptimization=true ||
  note "the playground role did not start with SERVE_JAVA_OPTS"
opts="$(grep '^JAVA_TOOL_OPTIONS=' "${work}/out")"
[[ "${opts}" == *"themeOptimization=false"*"themeOptimization=true"* ]] ||
  note "SERVE_JAVA_OPTS no longer comes after the role's defaults: ${opts}"

# --- the main server beside it ------------------------------------------------------------------
run SERVE_COMPILE_ENGINE=1 SERVE_PLAYGROUND_EXTERNAL=1 || note "the main server did not start: $(tail -1 "${work}/log")"
for flag in --compile-engine --playground-external --agent-grants --engagement-file; do
  has "${flag}" || note "the main server lost ${flag}"
done
has --role && note "the main server took a role it was never given"

# --- a typo fails loudly rather than starting the wrong server -----------------------------------
if run SERVE_ROLE=playgorund; then
  note "an unknown SERVE_ROLE started a server"
fi
[[ -f "${work}/out" ]] && note "an unknown SERVE_ROLE reached the server binary"

((fail)) && exit 1
echo "PASS: the playground role and the main server each get exactly their half of the split"
