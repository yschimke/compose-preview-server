#!/usr/bin/env bash
# The UI-builder comment webhook posts a thread permalink into somebody's chat channel, and a
# permalink is the whole point of the notification. That link is built from the public origin the
# server was told about, which on this image is DOMAIN.
#
# The regression this guards: --github-auth-callback-base-url used to be forwarded only from inside
# the GitHub OAuth credential block, so a box with a DOMAIN and no OAuth configured fell back to its
# bind address and posted http://127.0.0.1:8080/... links that nobody receiving them could open.
# The origin is a property of the deployment, not of whether sign-in happens to be switched on.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
entrypoint="${ENTRYPOINT_FILE:-${here}/entrypoint.sh}"

# The OAuth credential block, from its `if` to the `fi` that closes it.
oauth_block="$(awk '/^if \[\[ -n "\$\{SERVE_GITHUB_AUTH_CLIENT_ID:-\}" \|\|$/,/^fi$/' "${entrypoint}")"

[[ -n "${oauth_block}" ]] || {
  echo "FAIL: could not find the GitHub OAuth credential block in the entrypoint" >&2
  exit 1
}

grep -Fq -- '--github-auth-callback-base-url' <<<"${oauth_block}" && {
  echo "FAIL: the public origin is forwarded from inside the OAuth block, so a deployment" >&2
  echo "      without OAuth posts loopback permalinks to its comment webhook" >&2
  exit 1
}

# Forwarded at top level: the append is unindented, which is the only place it runs unconditionally.
grep -Eq '^\[\[ -n "\$\{github_auth_callback_base_url\}" \]\] &&$' "${entrypoint}" || {
  echo "FAIL: entrypoint does not forward the public origin outside the OAuth block" >&2
  exit 1
}

grep -Eq '^  args\+=\(--github-auth-callback-base-url "\$\{github_auth_callback_base_url\}"\)$' \
  "${entrypoint}" || {
  echo "FAIL: entrypoint does not pass --github-auth-callback-base-url unconditionally" >&2
  exit 1
}

# Still derived from DOMAIN when the operator did not pin it explicitly.
grep -Eq '^  github_auth_callback_base_url="https://\$\{DOMAIN\}"$' "${entrypoint}" || {
  echo "FAIL: entrypoint no longer derives the public origin from DOMAIN" >&2
  exit 1
}

# And the webhook itself reaches the server, so the origin has something to build a link for.
grep -Fq 'args+=(--ui-builder-comment-webhook "${SERVE_UI_BUILDER_COMMENT_WEBHOOK}")' \
  "${entrypoint}" || {
  echo "FAIL: entrypoint does not forward SERVE_UI_BUILDER_COMMENT_WEBHOOK" >&2
  exit 1
}

echo "PASS: the public origin is forwarded independently of GitHub OAuth"
