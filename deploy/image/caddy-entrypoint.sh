#!/bin/sh
# Entrypoint for the preview host's caddy container: resolve SITE_DOMAINS, then run Caddy.
#
# `{$SITE_DOMAINS}` lands on the Caddyfile's site-address line beside `{$DOMAIN}`, which is what
# makes Caddy match a top-level site's hostname AND provision its certificate. It used to come from
# the box's untracked `.env`, kept in step with `catalogs.json`'s `sites` by hand — so a hostname
# committed on `main` was live in the app and unreachable at the edge until someone remembered the
# other half, and nothing anywhere reported the mismatch.
#
# Now the same file decides both: whatever the preview server booted from (mounted read-only at
# CATALOGS_FILE) names the hostnames, and they are unioned with any explicit SITE_DOMAINS so an
# operator can still add a name the file doesn't carry. Order is preserved, duplicates dropped.
#
# Still a caddy RESTART, not a reload: Caddy reads its config once at start, so a site published on
# a running box (POST /admin/sites) is served by the app immediately and reachable at the edge after
# the next `docker compose up -d`. What has gone is the hand-edit and the drift, not the restart.
set -eu

CATALOGS_FILE="${CATALOGS_FILE:-/srv/preview-config/catalogs.json}"
# Beside this script, wherever it is: /usr/local/bin in the image, the repo directory when the
# self-test drives it. A hardcoded container path would make the pair untestable outside a build.
SCAN="$(dirname "$0")/site-domains.sh"

derived="$(sh "${SCAN}" "${CATALOGS_FILE}" || true)"

# Union, first spelling wins. SITE_DOMAINS is space- or comma-separated by documented convention;
# the derived list is newline-separated. Include the optional builder hostname too.
# Normalise the lists to whitespace and dedupe.
resolved="$(
  printf '%s %s %s' "$(printf '%s' "${SITE_DOMAINS:-}" | tr ',' ' ')" "$(printf '%s' "${derived}" | tr '\n' ' ')" "${SERVE_UI_BUILDER_HOST:-}" |
    tr -s ' \t' '\n' |
    awk 'NF && !seen[$0]++' |
    tr '\n' ' '
)"
# Trim the trailing separator: an empty value must stay empty, because `{$SITE_DOMAINS:}` expanding
# to whitespace is the difference between "one domain on this block" and a Caddyfile parse error.
resolved="$(printf '%s' "${resolved}" | sed 's/[[:space:]]*$//')"

# The Home Assistant project's hostname (HA_DOMAIN) is served by its own block, ha.caddy, which the
# Caddyfile imports from OPTIONAL_SITES_DIR. Install it only when the name is set — the import's glob
# then matches nothing and the config is exactly the main block — and take the name out of
# SITE_DOMAINS, because Caddy refuses a hostname claimed by two blocks. The .env that served
# ha.coo.ee as a plain site before HA_DOMAIN existed keeps working unchanged after it is set.
OPTIONAL_SITES_DIR="${OPTIONAL_SITES_DIR:-/run/caddy-sites}"
HA_SITE_FILE="${HA_SITE_FILE:-/etc/caddy/ha.caddy}"
mkdir -p "${OPTIONAL_SITES_DIR}"
rm -f "${OPTIONAL_SITES_DIR}/ha.caddy"
if [ -n "${HA_DOMAIN:-}" ]; then
  cp "${HA_SITE_FILE}" "${OPTIONAL_SITES_DIR}/ha.caddy"
  resolved="$(printf '%s' "${resolved}" | tr ' ' '\n' | awk -v ha="${HA_DOMAIN}" 'NF && $0 != ha' | tr '\n' ' ' | sed 's/[[:space:]]*$//')"
  echo "caddy: ${HA_DOMAIN} served by ha.caddy (design server + preview server, split by path)" >&2
fi

export SITE_DOMAINS="${resolved}"
if [ -n "${SITE_DOMAINS}" ]; then
  echo "caddy: top-level site hostnames: ${SITE_DOMAINS}" >&2
else
  echo "caddy: no top-level site hostnames (none in ${CATALOGS_FILE}, none in SITE_DOMAINS)" >&2
fi

# Belt and braces for the failure that took the box down: if the arguments are missing — a
# Dockerfile that declares ENTRYPOINT without restating CMD, a compose file that sets `entrypoint:`
# and forgets `command:` — `exec caddy` with none prints usage and exits, and a caddy container that
# exits takes ports 80/443 with it. Every hostname on the box goes dark, not just the sites. Default
# to what caddy:2 would have run rather than letting a config slip become an outage.
if [ "$#" -eq 0 ]; then
  echo "caddy: no arguments given (dropped CMD?) — defaulting to the standard config" >&2
  set -- run --config /etc/caddy/Caddyfile --adapter caddyfile
fi

exec caddy "$@"
