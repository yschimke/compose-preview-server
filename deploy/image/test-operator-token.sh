#!/usr/bin/env bash
# Guard: the entrypoint's auth posture, and the public box's optional operator token.
#
# A `--public` box started without `--token` has no stable operator: the server makes up a random
# token each start, so a co-hosted service holding a configured token (the Home Assistant card
# design server, creating Card Studio designs) is refused as `operator` on every request, and the
# refusal reads as a missing catalog. SERVE_OPERATOR_TOKEN gives a public box that token, and must
# give it nothing else: unset, a public box is exactly as it was, and a token-gated box still takes
# SERVE_TOKEN and nothing but.
#
# Runs the real block out of entrypoint.sh rather than a copy, so the two cannot drift.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
entrypoint="${ENTRYPOINT_FILE:-${here}/entrypoint.sh}"

posture="$(awk '/^# Auth posture/,/^fi$/' "${entrypoint}")"
[[ -n "${posture}" ]] || {
  echo "FAIL: could not find the auth posture block in ${entrypoint}" >&2
  echo "      (did its leading comment change? this test locates it by that line)" >&2
  exit 1
}

# $1 = expected args, $2 = description, $3… = environment assignments
expect() {
  local want="$1" desc="$2"
  shift 2
  local got
  # `env -i`: an empty environment, so a shell that exports SERVE_* can't make a case pass.
  got="$(env -i PATH="${PATH}" "$@" bash -c "set -euo pipefail; args=(); ${posture}
    echo \"\${args[*]}\"" 2>/dev/null)" || got="<exit $?>"
  if [[ "${got}" != "${want}" ]]; then
    echo "FAIL: ${desc}: expected '${want}', got '${got}'" >&2
    exit 1
  fi
  echo "  ok: ${desc} -> ${got}"
}

echo "==> auth posture"
expect "--public" "public, no operator token => no --token" SERVE_PUBLIC=1
expect "--public" "public ignores SERVE_TOKEN, as before" SERVE_PUBLIC=1 SERVE_TOKEN=share
expect "--public --token op" "public + SERVE_OPERATOR_TOKEN => stable operator" \
  SERVE_PUBLIC=1 SERVE_OPERATOR_TOKEN=op
expect "--public --token op" "public takes the operator token, not SERVE_TOKEN" \
  SERVE_PUBLIC=true SERVE_TOKEN=share SERVE_OPERATOR_TOKEN=op
expect "--token share" "token-gated takes SERVE_TOKEN" SERVE_TOKEN=share
expect "--token share" "token-gated ignores SERVE_OPERATOR_TOKEN" \
  SERVE_TOKEN=share SERVE_OPERATOR_TOKEN=op
expect "<exit 64>" "token-gated without SERVE_TOKEN still refuses to start" SERVE_OPERATOR_TOKEN=op

echo "PASS: auth posture"
