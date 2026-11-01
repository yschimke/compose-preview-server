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

# Extra sites: a deployment that serves more hostnames than this server's own (a sibling app on the
# same host, say) mounts one Caddyfile fragment per site into EXTRA_SITES_DIR, and the Caddyfile
# imports whatever is installed in OPTIONAL_SITES_DIR. EXTRA_SITE_DOMAINS names the hostnames those
# fragments claim, and they are taken out of SITE_DOMAINS, because Caddy refuses a hostname claimed
# by two blocks. With nothing mounted the import's glob matches nothing and the config is exactly
# the main block. Installed afresh on every start, so removing a mount removes the site.
#
# Only files this script installed (listed in .installed) are ever removed. A deployment may also
# bind-mount a fragment straight into OPTIONAL_SITES_DIR; deleting a bind-mounted file fails, and
# under `set -e` that would stop Caddy and take every hostname on the box down. Such a file is left
# alone, and a fragment of the same name is not copied over it.
OPTIONAL_SITES_DIR="${OPTIONAL_SITES_DIR:-/run/caddy-sites}"
EXTRA_SITES_DIR="${EXTRA_SITES_DIR:-/etc/caddy/sites.d}"
installed="${OPTIONAL_SITES_DIR}/.installed"
mkdir -p "${OPTIONAL_SITES_DIR}"
if [ -f "${installed}" ]; then
  while IFS= read -r name; do
    [ -n "${name}" ] && { rm -f "${OPTIONAL_SITES_DIR}/${name}" 2>/dev/null || true; }
  done < "${installed}"
fi
: > "${installed}"
install_site() { # install_site <source> <name>
  if [ -e "${OPTIONAL_SITES_DIR}/$2" ]; then
    echo "caddy: ${OPTIONAL_SITES_DIR}/$2 is already there (mounted?), not replacing it" >&2
    return 0
  fi
  cp "$1" "${OPTIONAL_SITES_DIR}/$2"
  echo "$2" >> "${installed}"
}
for site in "${EXTRA_SITES_DIR}"/*.caddy; do
  [ -f "${site}" ] || continue
  install_site "${site}" "$(basename "${site}")"
  echo "caddy: extra site $(basename "${site}") from ${EXTRA_SITES_DIR}" >&2
done
for claimed in $(printf '%s' "${EXTRA_SITE_DOMAINS:-}" | tr ',' ' '); do
  resolved="$(printf '%s' "${resolved}" | tr ' ' '\n' | awk -v d="${claimed}" 'NF && $0 != d' | tr '\n' ' ' | sed 's/[[:space:]]*$//')"
done

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
