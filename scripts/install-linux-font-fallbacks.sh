#!/usr/bin/env bash

# Install the Linux dependencies passed as arguments plus a deterministic Noto fallback set for
# Skiko text rendering. Preserve the generic families that were selected before Noto was installed:
# apps keep their declared typography, while missing Arabic, Indic, Thai, CJK and emoji glyphs fall
# through to Noto instead of tofu.

set -euo pipefail

if [[ "${EUID}" -eq 0 ]]; then
  root=()
else
  root=(sudo)
fi

generic_family() {
  local generic="$1"
  local fallback="$2"
  local family=""

  if command -v fc-match >/dev/null 2>&1; then
    family="$(fc-match -f '%{family}\n' "${generic}" 2>/dev/null | head -n1 | cut -d, -f1)"
  fi
  printf '%s' "${family:-${fallback}}"
}

xml_escape() {
  sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' -e 's/"/\&quot;/g' -e "s/'/\&apos;/g"
}

prev_sans="$(generic_family sans-serif 'DejaVu Sans')"
prev_serif="$(generic_family serif 'DejaVu Serif')"
prev_mono="$(generic_family monospace 'DejaVu Sans Mono')"

# A mirror can briefly serve a Packages index that does not match its signed Release metadata.
# Retry the whole update so apt fetches fresh metadata, while still enforcing its hash checks.
# Error-Mode=any also rejects partial updates that apt would otherwise accept with a warning.
for attempt in 1 2 3 4 5; do
  if "${root[@]}" apt-get update -qq -o APT::Update::Error-Mode=any; then
    break
  else
    status=$?
  fi
  if [[ "${attempt}" -eq 5 ]]; then
    echo "apt-get update failed after ${attempt} attempts" >&2
    exit "${status}"
  fi
  delay=$((attempt * 15))
  echo "apt-get update failed (attempt ${attempt}/5); retrying in ${delay}s" >&2
  sleep "${delay}"
done
"${root[@]}" apt-get install -y -qq --no-install-recommends \
  fontconfig fonts-dejavu-core fonts-noto-cjk fonts-noto-core fonts-noto-color-emoji "$@"

conf_dir="${FONTCONFIG_CONF_DIR:-/etc/fonts/conf.d}"
conf_file="${conf_dir}/99-composeai-preserve-generic-fonts.conf"
tmp_file="$(mktemp)"
trap 'rm -f "${tmp_file}"' EXIT

{
  echo '<?xml version="1.0"?>'
  echo '<!DOCTYPE fontconfig SYSTEM "fonts.dtd">'
  echo '<fontconfig>'
  for pair in "sans-serif:${prev_sans}" "serif:${prev_serif}" "monospace:${prev_mono}"; do
    generic="${pair%%:*}"
    family="${pair#*:}"
    escaped_family="$(printf '%s' "${family}" | xml_escape)"
    printf '  <match target="pattern"><test qual="any" name="family"><string>%s</string></test><edit name="family" mode="prepend" binding="strong"><string>%s</string></edit></match>\n' \
      "${generic}" "${escaped_family}"
  done
  echo '</fontconfig>'
} >"${tmp_file}"

"${root[@]}" mkdir -p "${conf_dir}"
"${root[@]}" install -m 0644 "${tmp_file}" "${conf_file}"
fc-cache -f >/dev/null

echo "generic sans-serif -> $(fc-match -f '%{family}\n' sans-serif) (was: ${prev_sans})"
echo "generic serif      -> $(fc-match -f '%{family}\n' serif) (was: ${prev_serif})"
echo "generic monospace  -> $(fc-match -f '%{family}\n' monospace) (was: ${prev_mono})"

if [[ "${COMPOSEAI_CLEAN_APT:-0}" == "1" ]]; then
  "${root[@]}" rm -rf /var/lib/apt/lists/*
fi
