#!/usr/bin/env bash
# Guard the rollout gate in `preview-host-image.yml`, which decides whether a deploy happened.
#
# It is the only automated statement that the box is running the build that was just pushed, and it
# used to be able to say yes when it had not. Two reasons, both found by watching the 3.27.0 rollout
# rather than by reading:
#
#   1. It read the live version from `/version` ALONE. During the container swap two processes
#      answer this host, and `/version` returned 3.27.0 in the same second `/status.json` returned
#      3.26.0 with 9213 seconds of uptime. A gate reading one endpoint can call that converged and
#      report a successful deploy against the build it was replacing.
#   2. It required `loaded -ge 0` and `total -ge 0` of the catalogs, which is true of every number
#      those fields can hold, `loaded=0` included. An assertion that cannot fail is not a check --
#      the same shape as a substring `expect` that matched a prefix and a filter that read the
#      wrong discriminator, both of which shipped in this repository this week.
#
# The function is EXTRACTED from the workflow rather than copied here, so this cannot pass against a
# gate that no longer ships. Run by the `deployment` job, which globs `.github/scripts/test-*.sh`.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
workflow="${WORKFLOW_FILE:-${here}/../workflows/preview-host-image.yml}"
[[ -f "${workflow}" ]] || {
  echo "FAIL: no workflow at ${workflow}" >&2
  exit 1
}

tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT

# A YAML parse rather than a pattern: this lives in a `run:` block scalar, and finding its bounds by
# grep is how an extraction quietly starts testing half a function.
python3 - "${workflow}" "${tmp}/converged.sh" <<'PY'
import re, sys, yaml

doc = yaml.safe_load(open(sys.argv[1], encoding="utf-8"))
run = None
for job in doc["jobs"].values():
    for step in job.get("steps", []):
        if step.get("name") == "Notify preview.coo.ee and verify rollout":
            run = step["run"]
if run is None:
    sys.exit("the rollout step is gone, or was renamed")

match = re.search(r"^\s*converged\(\) \{.*?^\s*\}\s*$", run, re.S | re.M)
if not match:
    sys.exit("the rollout step no longer defines a `converged` function this test can extract")

body = "\n".join(line[10:] if line.startswith(" " * 10) else line for line in match.group(0).splitlines())
open(sys.argv[2], "w", encoding="utf-8").write(body + "\n")
PY

status=0
ok() { echo "ok   $1"; }
bad() {
  echo "FAIL $1: $2" >&2
  status=1
}

# shellcheck disable=SC1090
source "${tmp}/converged.sh"

want() { # 1=label 2..=converged args -- expected to PASS
  local label="$1"
  shift
  if converged "$@"; then ok "${label}"; else bad "${label}" "converged said no to a good rollout"; fi
}
deny() { # 1=label 2..=converged args -- expected to FAIL
  local label="$1"
  shift
  if converged "$@"; then bad "${label}" "converged said yes"; else ok "${label}"; fi
}

# Argument order: live status_version expected loaded total failed builder_ok renders_ok wear_authorable
echo "==> a real rollout"
want "the new build, every catalog loaded, none failed" 3.27.0 3.27.0 3.27.0 37 37 0 1 1 1

echo "==> the split this was written for"
deny "/version is new but /status.json is still the old process" 3.27.0 3.26.0 3.27.0 37 37 0 1 1 1
deny "/status.json is new but /version is still the old process" 3.26.0 3.27.0 3.27.0 37 37 0 1 1 1
deny "neither endpoint has moved yet" 3.26.0 3.26.0 3.27.0 37 37 0 1 1 1

echo "==> the catalogs actually have to be loaded"
# The case the old gate accepted: every count is >= 0, so it said yes to a server with nothing on it.
deny "no catalogs loaded at all" 3.27.0 3.27.0 3.27.0 0 37 0 1 1 1
deny "still warming up, most catalogs pending" 3.27.0 3.27.0 3.27.0 15 37 0 1 1 1
deny "a catalog failed to load" 3.27.0 3.27.0 3.27.0 36 37 1 1 1 1
deny "every catalog loaded but one failed" 3.27.0 3.27.0 3.27.0 37 37 2 1 1 1

echo "==> unreadable answers are not converged"
# `-1` is what the jq fallbacks emit when the endpoint gave nothing back, and an empty version is
# what the sed emits when /version did not answer. None of these may read as success.
deny "status.json did not answer" 3.27.0 "" 3.27.0 -1 -1 -1 1 1 1
deny "version did not answer" "" 3.27.0 3.27.0 37 37 0 1 1 1
deny "neither answered" "" "" 3.27.0 -1 -1 -1 1 1 1
deny "a total of zero is not a loaded fleet" 3.27.0 3.27.0 3.27.0 0 0 0 1 1 1

echo "==> the ui-builder still has to be served"
deny "the builder assets are not up" 3.27.0 3.27.0 3.27.0 37 37 0 0 1 1

echo "==> Wear has to be authorable, not merely published"
deny "the host kept the retired pre-Wear authoring allowlist" 3.27.0 3.27.0 3.27.0 37 37 0 1 1 0

echo "==> a loaded catalog is not a rendering one"
# The 3.38.0 rollout: every field above agreed, and `glimmer-catalog` answered its page with all 24
# of its images missing because the lane had no daemon yet. `loaded` counts discovery; only a render
# proves the catalog can serve. Without this case the gate is back to reporting that deploy green.
deny "every catalog loaded, but a design system cannot render yet" 3.27.0 3.27.0 3.27.0 37 37 0 1 0 1
deny "nothing renders and the builder is down either" 3.27.0 3.27.0 3.27.0 37 37 0 0 0 1

exit "${status}"
