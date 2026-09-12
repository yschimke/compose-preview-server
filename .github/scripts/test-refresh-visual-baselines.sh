#!/usr/bin/env bash
# Guard the two decisions `refresh-visual-baselines.yml` makes, because both were wrong once.
#
#   WHO may ask      -- the `resolve` job's trigger gating.
#   WHERE it lands   -- the `Commit and push` step's target, chosen AFTER the ~18 minute render.
#
# The second decision is the one with history. It first pushed to the triggering pull request's
# branch unconditionally, which stranded a refresh when #808 merged mid-render. The fix then
# treated a pull request CLOSED without merging the same as MERGED, which would have opened a
# rescue pull request re-proposing rejected work. Neither mistake is visible by reading the YAML
# quickly, and neither would have been caught by anything: the workflow only runs on a comment or
# a dispatch, so no pull request exercises it.
#
# Both scripts are EXTRACTED from the workflow rather than copied here, so this cannot pass against
# a version of the logic that no longer ships. It is run by the `deployment` job, which globs
# `.github/scripts/test-*.sh`.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
workflow="${WORKFLOW_FILE:-${here}/../workflows/refresh-visual-baselines.yml}"
[[ -f "${workflow}" ]] || {
  echo "FAIL: no workflow at ${workflow}" >&2
  exit 1
}

tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT

# `python3 -c` with a YAML parser rather than awk: these are `run:` block scalars, and finding
# their boundaries by pattern is how an extraction quietly starts testing half a script.
extract() { # $1 = step name, $2 = output file
  python3 - "$1" "$2" "${workflow}" <<'PY'
import sys, yaml
name, out, path = sys.argv[1], sys.argv[2], sys.argv[3]
doc = yaml.safe_load(open(path, encoding="utf-8"))
for job in doc["jobs"].values():
    for step in job.get("steps", []):
        if step.get("name") == name:
            open(out, "w", encoding="utf-8").write(step["run"])
            sys.exit(0)
print(f"step not found: {name}", file=sys.stderr)
sys.exit(1)
PY
}

status=0
ok() { echo "ok   $1"; }
bad() {
  echo "FAIL $1: $2" >&2
  status=1
}

# ---------------------------------------------------------------------------------------------
# WHO may ask
# ---------------------------------------------------------------------------------------------
extract Decide "${tmp}/decide.sh"
# Stop before the `gh pr view` half: this is the gating test, and reaching the API would make it a
# network test of somebody else's state.
python3 - "${tmp}/decide.sh" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
marker = "# Resolve the head branch"
assert marker in s, "the Decide step no longer has the marker this test cuts at"
open(p, "w", encoding="utf-8").write(s[: s.index(marker)] + '\nprintf "run=%s standalone=%s pr=%s harness=%s\\n" "${run:-}" "${standalone:-}" "${pr}" "${harness}"\n')
PY

# Read GITHUB_OUTPUT, not stdout: the refusal and standalone paths both write there and `exit 0`
# before reaching the end of the script, so a stdout-only assertion would report "nothing to
# refresh" for every one of them and pass or fail for the wrong reason.
decide() { # 1=event 2=comment 3=association 4=is_pr_comment 5=dispatch_pr 6=dispatch_harness
  local out="${tmp}/decide-out"
  : > "${out}"
  env -i PATH="${PATH}" \
    EVENT="$1" COMMENT="$2" ASSOCIATION="$3" IS_PR_COMMENT="$4" COMMENT_PR=42 \
    DISPATCH_PR="$5" DISPATCH_HARNESS="$6" DEFAULT_BRANCH=main \
    GITHUB_OUTPUT="${out}" \
    bash "${tmp}/decide.sh" >"${tmp}/decide-stdout" 2>/dev/null || true
  # The trailing printf only runs on the path that falls through to the pull-request lookup.
  { tr '\n' ' ' < "${out}"; tail -1 "${tmp}/decide-stdout"; } | tr '\n' ' '
}

expect_decide() { # 1=label 2=expected-substring 3..=decide args
  local label="$1" want="$2"
  shift 2
  local got
  got="$(decide "$@")"
  if [[ "${got}" == *"${want}"* ]]; then ok "${label}"; else bad "${label}" "wanted '${want}', got '${got}'"; fi
}

echo "==> who may ask"
expect_decide "an owner's command is accepted" "pr=42" \
  issue_comment '/refresh-visual-baselines' OWNER true '' ''
expect_decide "a collaborator's command is accepted" "pr=42" \
  issue_comment '/refresh-visual-baselines' COLLABORATOR true '' ''
expect_decide "an outsider is refused" "run=false" \
  issue_comment '/refresh-visual-baselines' NONE true '' ''
expect_decide "a non-pull-request comment is refused" "run=false" \
  issue_comment '/refresh-visual-baselines' OWNER false '' ''
expect_decide "prose mentioning the command is refused" "run=false" \
  issue_comment 'we should /refresh-visual-baselines later' OWNER true '' ''
expect_decide "a shell metacharacter in the body is refused" "run=false" \
  issue_comment '/refresh-visual-baselines; rm -rf /' OWNER true '' ''
expect_decide "an unknown harness word is refused" "run=false" \
  issue_comment '/refresh-visual-baselines nope' OWNER true '' ''
expect_decide "a named harness is carried" "harness=ui-builder-editor" \
  issue_comment '/refresh-visual-baselines ui-builder-editor' OWNER true '' ''
expect_decide "a dispatch with no number is standalone on the default branch" "standalone=true" \
  workflow_dispatch '' '' '' '' ''
expect_decide "a standalone dispatch carries its harness" "harness=ui-builder" \
  workflow_dispatch '' '' '' '' 'ui-builder'

# ---------------------------------------------------------------------------------------------
# WHERE it lands
# ---------------------------------------------------------------------------------------------
extract 'Commit and push' "${tmp}/push.sh"
python3 - "${tmp}/push.sh" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
marker = "# WHERE the commit goes is decided HERE"
assert marker in s, "the Commit and push step no longer has the marker this test cuts at"
body = s[s.index(marker):]
end = 'echo "state=${state}"'
assert end in body, "the Commit and push step no longer ends its decision where this test expects"
open(p, "w", encoding="utf-8").write(body[: body.index(end)] + 'echo "state=${state}"\n}\n')
PY

land() { # 1=simulated `gh pr view` state, 2=standalone
  local out="${tmp}/land-out"
  : > "${out}"
  env -i PATH="${PATH}" \
    STATE_SIM="$1" STANDALONE="$2" PR=808 BRANCH=agent/some-branch \
    HARNESS=ui-builder-jetcaster GITHUB_RUN_ID=99 GITHUB_RUN_ATTEMPT=2 \
    DEFAULT_BRANCH=main GITHUB_OUTPUT="${out}" files=1 \
    bash -c '
      set -euo pipefail
      gh() { [ "${STATE_SIM}" = "LOOKUP_FAILS" ] && return 1; echo "${STATE_SIM}"; }
      git() { case "$1" in rev-parse) echo deadbeef ;; diff) return "${DIFF_RC:-1}" ;; *) : ;; esac; }
      '"$(cat "${tmp}/push.sh")"'
    ' >/dev/null 2>&1
  tr '\n' ' ' < "${out}"
}

expect_land() { # 1=label 2=expected-substring 3=state 4=standalone
  local got
  got="$(land "$3" "$4")"
  if [[ "${got}" == *"$2"* ]]; then ok "$1"; else bad "$1" "wanted '$2', got '${got}'"; fi
}

echo "==> where it lands"
expect_land "an open pull request gets the commit on its branch" \
  "landed=branch target=agent/some-branch" OPEN false
expect_land "a merged pull request gets a rescue pull request" \
  "landed=pr" MERGED false
expect_land "the rescue branch names the run ATTEMPT, not just the run" \
  "target=agent/refresh-ui-builder-jetcaster-baselines-99-2" MERGED false
expect_land "a standalone run gets a pull request of its own" \
  "landed=pr" STANDALONE true
# The regression this file was written for. A pull request closed WITHOUT merging is rejected
# work; the pixels were rendered from it and must not be re-proposed.
expect_land "a pull request closed unmerged publishes NOTHING" \
  "landed=none" CLOSED false
expect_land "a failed state lookup publishes NOTHING" \
  "landed=none" LOOKUP_FAILS false

exit "${status}"
