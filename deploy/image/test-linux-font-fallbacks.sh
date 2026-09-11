#!/usr/bin/env bash
# Offline regression tests for the release image's apt mirror-sync recovery. Run by ci.yml.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="${here}/../../scripts/install-linux-font-fallbacks.sh"
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT
mkdir -p "${work}/bin"

cat > "${work}/bin/apt-get" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
echo "$*" >> "${APT_TEST_DIR}/calls"
case "$1" in
  update)
    # Require strict updates: partial metadata must never reach package installation.
    [[ " $* " == *' -o APT::Update::Error-Mode=any '* ]] || exit 99
    count=$(cat "${APT_TEST_DIR}/updates")
    count=$((count + 1))
    echo "${count}" > "${APT_TEST_DIR}/updates"
    if ((count <= APT_TEST_FAILURES)); then
      echo 'E: File has unexpected size. Mirror sync in progress?' >&2
      exit 100
    fi
    ;;
  install) exit "${APT_TEST_INSTALL_STATUS}" ;;
  *) exit 99 ;;
esac
STUB
cat > "${work}/bin/sudo" <<'STUB'
#!/usr/bin/env bash
exec "$@"
STUB
cat > "${work}/bin/sleep" <<'STUB'
#!/usr/bin/env bash
echo "$*" >> "${APT_TEST_DIR}/sleeps"
STUB
cat > "${work}/bin/fc-match" <<'STUB'
#!/usr/bin/env bash
echo 'Original Font'
STUB
cat > "${work}/bin/fc-cache" <<'STUB'
#!/usr/bin/env bash
touch "${APT_TEST_DIR}/cached"
STUB
chmod +x "${work}/bin/"*
export PATH="${work}/bin:${PATH}"
# Never touch the host's font configuration or apt lists, including when run as root in CI.
export COMPOSEAI_CLEAN_APT=0

check() {
  if [[ "$2" != "$3" ]]; then
    echo "FAIL: $1 (expected '$2', got '$3')" >&2
    exit 1
  fi
}

run_case() {
  export APT_TEST_DIR="${work}/$1"
  export APT_TEST_FAILURES="$2" APT_TEST_INSTALL_STATUS="$3"
  export FONTCONFIG_CONF_DIR="${APT_TEST_DIR}/fonts"
  mkdir -p "${APT_TEST_DIR}"
  echo 0 > "${APT_TEST_DIR}/updates"
  : > "${APT_TEST_DIR}/sleeps"
  result=0
  bash "${script}" libgl1 bubblewrap > "${APT_TEST_DIR}/output" 2>&1 || result=$?
  check "$1 exit status" "$4" "${result}"
  check "$1 update attempts" "$5" "$(cat "${APT_TEST_DIR}/updates")"
}

run_case healthy 0 0 0 1
check 'healthy update does not sleep' '' "$(cat "${APT_TEST_DIR}/sleeps")"
test -f "${APT_TEST_DIR}/cached"

run_case mirror_recovers 2 0 0 3
check 'backoff before recovery' $'15\n30' "$(cat "${APT_TEST_DIR}/sleeps")"
check 'install once after recovery' 1 "$(grep -c '^install ' "${APT_TEST_DIR}/calls")"
grep -q 'fonts-noto-color-emoji libgl1 bubblewrap$' "${APT_TEST_DIR}/calls"
grep -q '<string>Original Font</string>' "${FONTCONFIG_CONF_DIR}/99-composeai-preserve-generic-fonts.conf"
test -f "${APT_TEST_DIR}/cached"

run_case last_attempt_recovers 4 0 0 5
test -f "${APT_TEST_DIR}/cached"

run_case mirror_stays_broken 5 0 100 5
check 'bounded backoff without a final sleep' $'15\n30\n45\n60' "$(cat "${APT_TEST_DIR}/sleeps")"
check 'no install after failed updates' 0 "$(grep -c '^install ' "${APT_TEST_DIR}/calls" || true)"
test ! -e "${FONTCONFIG_CONF_DIR}"
test ! -f "${APT_TEST_DIR}/cached"
grep -q 'apt-get update failed after 5 attempts' "${APT_TEST_DIR}/output"

run_case install_fails 0 42 42 1
check 'package install failure is not retried' 1 "$(grep -c '^install ' "${APT_TEST_DIR}/calls")"
check 'package install failure does not sleep' '' "$(cat "${APT_TEST_DIR}/sleeps")"
test ! -e "${FONTCONFIG_CONF_DIR}"
test ! -f "${APT_TEST_DIR}/cached"

echo 'PASS: Linux font installer apt recovery (5 scenarios)'
