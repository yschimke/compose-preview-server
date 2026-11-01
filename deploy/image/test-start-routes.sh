#!/usr/bin/env bash
# Self-test for the `/start` routes caddy-entrypoint.sh writes from START_SITE_URL and
# UI_BUILDER_START_SITE_URL.
#
# The failure worth pinning is the silent one: with neither variable set the entrypoint must write
# nothing, so a box that never opted in keeps reaching the app's own `/start` exactly as before. And
# a value the edge shouldn't splice into its config (not https, odd characters) must be refused, not
# written: a Caddyfile that fails to parse takes every hostname on the box down. Run by ci.yml.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
entrypoint="${here}/caddy-entrypoint.sh"
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT
mkdir -p "${work}/bin"
printf '#!/bin/sh\nexit 0\n' > "${work}/bin/caddy"
chmod +x "${work}/bin/caddy"

pass=0
fail=0
check() { # check <description> <expected> <actual>
  if [ "$2" = "$3" ]; then
    pass=$((pass + 1))
  else
    fail=$((fail + 1))
    echo "FAIL: $1" >&2
    echo "  expected: $2" >&2
    echo "  actual:   $3" >&2
  fi
}

routes() { # routes [VAR=value]... -> the generated start.caddy, or NONE
  rm -rf "${work}/routes" "${work}/sites"
  env -i PATH="${work}/bin:${PATH}" CATALOGS_FILE=/nonexistent OPTIONAL_SITES_DIR="${work}/sites" \
    ROUTES_DIR="${work}/routes" "$@" sh "${entrypoint}" run 2>/dev/null
  if [ -f "${work}/routes/start.caddy" ]; then cat "${work}/routes/start.caddy"; else echo NONE; fi
}

check "nothing set ⇒ no routes" "NONE" "$(routes)"

out="$(routes START_SITE_URL=https://example.github.io/docs/)"
check "default route proxies to the site's host" "1" \
  "$(grep -c 'reverse_proxy https://example.github.io {' <<<"${out}")"
check "default route rewrites under the site's path" "1" \
  "$(grep -c 'rewrite \* /docs{uri}' <<<"${out}")"
check "default route keeps redirects on /start" "1" \
  "$(grep -cF 'header_down Location "^https?://example\.github\.io/docs(/.*)?$" "/start$1"' <<<"${out}")"
check "default route sends the site's own Host" "1" \
  "$(grep -c 'header_up Host example.github.io' <<<"${out}")"
check "no builder host ⇒ default route matches every host" "0" "$(grep -c 'host ' <<<"${out}" || true)"

out="$(routes START_SITE_URL=https://example.github.io SERVE_UI_BUILDER_HOST=ui.example.com)"
check "a site at the root rewrites to the root" "1" "$(grep -c 'rewrite \* {uri}' <<<"${out}")"
check "builder host without its own guide ⇒ default covers it too" "0" \
  "$(grep -c 'host ui.example.com' <<<"${out}" || true)"

out="$(routes START_SITE_URL=https://example.github.io/docs UI_BUILDER_START_SITE_URL=https://example.github.io/builder SERVE_UI_BUILDER_HOST=ui.example.com)"
check "builder guide on the builder host" "1" "$(grep -cx $'\thost ui.example.com' <<<"${out}")"
check "default guide everywhere else" "1" "$(grep -cx $'\tnot host ui.example.com' <<<"${out}")"
check "both routes written" "2" "$(grep -c '^handle @start_' <<<"${out}")"

check "builder guide without a builder host ⇒ ignored" "NONE" \
  "$(routes UI_BUILDER_START_SITE_URL=https://example.github.io/builder)"
check "http:// refused" "NONE" "$(routes START_SITE_URL=http://example.github.io/docs)"
check "Caddyfile syntax in the URL refused" "NONE" \
  "$(routes 'START_SITE_URL=https://example.github.io/docs}{')"

echo "test-start-routes: ${pass} passed, ${fail} failed"
[ "${fail}" -eq 0 ]
