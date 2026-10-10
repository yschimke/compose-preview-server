#!/usr/bin/env bash
# Self-test for the deploy webhook's two routes and rollout.sh's --force, run OFFLINE: the hook's
# handler is fed raw HTTP on stdin, and rollout.sh runs against a stub `docker` on PATH.
#
# What it pins is the reason /__hooks/restart exists: a CONFIG change is applied by restarting the
# box on the image it already runs, which the digest-gated rollout never does on its own. So:
#
#  * /__hooks/rollout keeps its meaning (a plain rollout), /__hooks/restart forces one, and any
#    other path is a 404 rather than an unintended rollout;
#  * both are behind the token, and share the single-flight lock — but a restart that finds a
#    rollout running is a 409, not folded into a rollout that may have booted on the old config;
#  * `rollout.sh` skips an unchanged image unless forced, and forced it rolls anyway.
#
# Run by ci.yml.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

pass=0
fail=0
check() { # check <description> <expected substring> <actual>
  if [[ "$3" == *"$2"* ]]; then
    pass=$((pass + 1))
  else
    fail=$((fail + 1))
    echo "FAIL: $1" >&2
    echo "  expected to contain: $2" >&2
    echo "  actual: $3" >&2
  fi
}

# --- the hook's handler -----------------------------------------------------------------------
# The detached rollout is replaced by a stub script that records what it was asked to do.
cat > "${work}/rollout-stub.sh" <<'STUB'
#!/bin/sh
echo "force=${ROLLOUT_FORCE:-unset}" >> "$ROLL_RECORD"
STUB
export ROLL_RECORD="${work}/rolls"
export DEPLOY_HOOK_TOKEN=s3cret
export DEPLOY_HOOK_LOCK="${work}/lock"
export DEPLOY_HOOK_ROLL_LOG="${work}/roll.log"
export DEPLOY_HOOK_ROLLOUT_SCRIPT="${work}/rollout-stub.sh"

request() { # request <method> <path> [token]
  local auth=""
  [[ -n "${3:-}" ]] && auth="Authorization: Bearer $3"$'\r\n'
  printf '%s %s HTTP/1.1\r\nHost: x\r\n%s\r\n' "$1" "$2" "${auth}" |
    sh "${here}/deploy-hook.sh" --handle 2>/dev/null | head -n1 | tr -d '\r'
}
wait_for_roll() { # the rollout is detached; give it a moment to record and free the lock
  for _ in $(seq 1 50); do
    [[ -d "${DEPLOY_HOOK_LOCK}" ]] || return 0
    sleep 0.1
  done
}

check "an unknown path is a 404, not a rollout" "404" "$(request POST /__hooks/nope s3cret)"
check "a GET is refused" "405" "$(request GET /__hooks/restart s3cret)"
check "restart needs the token" "401" "$(request POST /__hooks/restart wrong)"
check "and so does rollout" "401" "$(request POST /__hooks/rollout)"

check "rollout is accepted" "202" "$(request POST /__hooks/rollout s3cret)"
wait_for_roll
check "rollout runs rollout.sh unforced" "force=0" "$(cat "${ROLL_RECORD}")"
: > "${ROLL_RECORD}"

check "restart is accepted" "202" "$(request POST '/__hooks/restart?from=ci' s3cret)"
wait_for_roll
check "restart runs rollout.sh forced" "force=1" "$(cat "${ROLL_RECORD}")"

# Single flight: with the lock held, a second rollout folds in, a restart is refused.
mkdir "${DEPLOY_HOOK_LOCK}"
check "a rollout during a rollout folds into it" "200" "$(request POST /__hooks/rollout s3cret)"
check "a restart during a rollout is a 409 to retry" "409" "$(request POST /__hooks/restart s3cret)"
rmdir "${DEPLOY_HOOK_LOCK}"

# Fail-closed: no token configured, nothing runs.
check "an unconfigured hook refuses everything" "401" \
  "$(DEPLOY_HOOK_TOKEN='' request POST /__hooks/restart '')"

# --- rollout.sh --force -----------------------------------------------------------------------
# The running container and the pulled tag are the SAME image: an unforced run must skip, a forced
# one must still call `docker rollout`. Every docker call is logged.
mkdir -p "${work}/bin"
cat > "${work}/bin/docker" <<'STUB'
#!/usr/bin/env bash
echo "docker $*" >> "$DOCKER_LOG"
case "$1 $2" in
  "compose version") exit 0 ;;
  "compose ps") echo cid ;;
  "compose config") echo ghcr.io/x/compose-preview-host:latest ;;
  "image inspect") echo sha256:same ;;
  "inspect --format")
    # The lock marker answers "running" when LOCK_BUSY is set, otherwise the preview container's image.
    if [[ "${!#}" == compose-preview-rollout-lock ]]; then echo running; else echo sha256:same; fi ;;
  "run -d")
    if [[ -n "${LOCK_BUSY:-}" ]]; then
      busy=$(( $(cat "$LOCK_BUSY") - 1 )); echo "$busy" > "$LOCK_BUSY"
      [[ "$busy" -lt 0 ]] || exit 1
    fi ;;
esac
exit 0
STUB
chmod +x "${work}/bin/docker"
export DOCKER_LOG="${work}/docker.log"

roll() { PATH="${work}/bin:${PATH}" sh "${here}/rollout.sh" "$@" 2>&1; }

: > "${DOCKER_LOG}"
out="$(roll)"
check "an unchanged image is skipped" "already up to date" "${out}"
[[ "$(cat "${DOCKER_LOG}")" != *"docker rollout"* ]] && skipped=yes || skipped=no
check "and docker rollout is not called" "yes" "${skipped}"

: > "${DOCKER_LOG}"
out="$(roll --force)"
check "--force restarts the current image" "forced restart" "${out}"
check "by calling docker rollout" "docker rollout --timeout" "$(cat "${DOCKER_LOG}")"

: > "${DOCKER_LOG}"
out="$(ROLLOUT_FORCE=1 roll)"
check "ROLLOUT_FORCE=1 does the same" "docker rollout --timeout" "$(cat "${DOCKER_LOG}")"

# A held lock: an unforced run skips, a forced run waits its turn instead of skipping.
: > "${DOCKER_LOG}"
echo 1 > "${work}/busy"
out="$(LOCK_BUSY="${work}/busy" roll)"
check "an unforced run skips a held lock" "skipping" "${out}"
echo 2 > "${work}/busy"
: > "${DOCKER_LOG}"
out="$(LOCK_BUSY="${work}/busy" ROLLOUT_LOCK_POLL=0 roll --force)"
check "a forced run waits for a held lock" "waiting for it before the forced restart" "${out}"
check "and then rolls" "docker rollout --timeout" "$(cat "${DOCKER_LOG}")"
echo 1000 > "${work}/busy"
out="$(LOCK_BUSY="${work}/busy" ROLLOUT_LOCK_POLL=1 ROLLOUT_LOCK_WAIT=2 roll --force; echo "exit=$?")"
check "but gives up on a lock that never frees" "giving up" "${out}"

echo "deploy hook + rollout --force: ${pass} passed, ${fail} failed"
[[ "${fail}" -eq 0 ]]
