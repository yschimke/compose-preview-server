#!/usr/bin/env bash
# Reconcile the image's SEED config onto a running preview server via its admin API.
#
# Why this exists: /config/catalogs.json and /config/producers.json are seeded on first boot and
# never overwritten after — deliberately, so an image roll can't stomp a runtime edit (see #2879 /
# #2897). The consequence is that adding a catalog or producer to a committed file changes nothing
# on an already-deployed box: it keeps the config it already has, and someone has to remember to
# POST the new entries by hand. This closes that gap as part of publishing.
#
# ADDITIVE BY DEFAULT. Without --prune this never deletes and never rewrites an existing entry: an
# id already present comes back 409 from the admin API, which is treated as success. So a producer
# or catalog an operator added directly on the box survives untouched.
#
# The flip side, and it was a real trade-off rather than an oversight: because the reconcile is
# blind to history, a catalog RETIRED on the box while still listed in catalogs.json is re-added by
# the next publish, and — the half that bit — one DROPPED from catalogs.json is never retired. The
# committed file could add but not remove, so it was the declared intent for what should exist and
# silent about what should not.
#
# --prune closes that half WITHOUT the tombstones #2962 said it would need. Tombstones were only
# necessary while "what exists on the box" was unknowable; it is not, because the box lists it
# (GET /admin/catalogs). So the file is what should exist, the listing is what does, and the
# difference is retired. What made that unsafe until now is that the difference is not all stale:
# a box nominating a registry (--catalog-registry) serves catalogs that are deliberately absent
# from this file, and a naive prune would delete them on every publish, every time. `/status.json`
# reports `config.catalogRegistries[].systems` since #63, so they can be told apart — and when that
# field is missing (an older box) --prune REFUSES rather than guessing, because on such a box a
# registry catalog and a stale one are indistinguishable and the wrong guess deletes something the
# box is correctly serving.
#
# --prune stays opt-in for the property in the first paragraph: an adopter's box may legitimately
# carry catalogs this repository has never heard of. preview.coo.ee's own publish passes it, because
# there the committed file IS meant to be the whole answer.
#
# Usage:
#   BASE_URL=https://preview.coo.ee ADMIN_TOKEN=… publish-config-to-box.sh [--dry-run] [--prune]
#
# --dry-run prints the requests it would make, one per line, and talks to nothing. That is the
# seam test-publish-config-to-box.sh drives, so the ordering and payload rules below are covered
# without a server.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# The DEPLOYMENT's config, not the image's generic seed. These are different things and conflating
# them is what gave preview.coo.ee favoured-nation status: its 17 catalogs and 9 trusted producers
# used to live in deploy/image/, so every adopter of the prebuilt image inherited them. The image
# seed is now compose-m3 plus the one producer that publishes it; a deployment's own set lives in
# its own directory and is applied from here.
#
# DEPLOY_CONFIG_DIR is what another adopter overrides — point it at your own directory with the
# same two filenames and this script works unchanged against your box.
DEPLOY_CONFIG_DIR="${DEPLOY_CONFIG_DIR:-${REPO_ROOT}/deploy/preview.coo.ee}"
CATALOGS_FILE="${CATALOGS_FILE:-${DEPLOY_CONFIG_DIR}/catalogs.json}"
TRUST_FILE="${TRUST_FILE:-${DEPLOY_CONFIG_DIR}/producers.json}"
SETTINGS_FILE="${SETTINGS_FILE:-${DEPLOY_CONFIG_DIR}/settings.json}"
ADMIN_TOKEN_HEADER="X-Compose-Preview-Admin-Token"

# How long the replacement-branch probe below may take. Injectable so the self-test can drive the
# stall path in a second instead of thirty; nothing else should set it. `timeout` is coreutils and
# present on every runner this executes on — on a host without it the probe runs unbounded, which
# is the behaviour that existed before this line.
LS_REMOTE_TIMEOUT_SECONDS="${LS_REMOTE_TIMEOUT_SECONDS:-30}"
if command -v timeout > /dev/null 2>&1; then
  LS_REMOTE=(timeout "${LS_REMOTE_TIMEOUT_SECONDS}" git ls-remote)
else
  LS_REMOTE=(git ls-remote)
fi

DRY_RUN=0
PRUNE=0
# Restart the box when a PUT below owes one (see "Applying config that waits for a restart" at the
# end). On by default: the committed file is meant to be what the box serves, and without this a
# config change sits written-but-unserved until the next image happens to roll. --no-restart (or
# NO_RESTART=1) writes the config and leaves the restart to whoever runs it.
RESTART=1
[[ "${NO_RESTART:-0}" == 1 ]] && RESTART=0
for arg in "$@"; do
  case "${arg}" in
    --dry-run) DRY_RUN=1 ;;
    --prune) PRUNE=1 ;;
    --no-restart) RESTART=0 ;;
    # Refused rather than ignored: a typo'd flag that silently did nothing would read as a
    # successful prune on a box that pruned nothing.
    *) echo "::error::unknown argument '${arg}' (expected --dry-run, --prune and/or --no-restart)" >&2; exit 2 ;;
  esac
done

# The deploy hook's restart route (deploy/image/deploy-hook.sh), gated by its own token rather than
# the admin token: the admin API writes config, the hook is what can roll the box.
DEPLOY_HOOK_TOKEN="${DEPLOY_HOOK_TOKEN:-}"
DEPLOY_HOOK_RESTART_URL="${DEPLOY_HOOK_RESTART_URL:-${BASE_URL:-}/__hooks/restart}"
# How long a restart may take to be proven: a forced rollout boots a fresh replica and waits for its
# /readyz (up to the rollout's 300s health timeout), then drains the old one. Injectable so the
# self-test runs in seconds.
RESTART_WAIT_SECONDS="${RESTART_WAIT_SECONDS:-900}"
RESTART_POLL_SECONDS="${RESTART_POLL_SECONDS:-20}"
RESTART_RETRIES="${RESTART_RETRIES:-10}"
RESTART_RETRY_SECONDS="${RESTART_RETRY_SECONDS:-30}"
# Reads in a row that must say nothing is owed. During the swap two replicas answer this host and
# the old one still owes the restart, so one clean answer can be the new replica alone.
RESTART_CONSECUTIVE="${RESTART_CONSECUTIVE:-3}"

# What a PUT below said is written but not yet serving. `restart_candidate` is the dry-run's
# stand-in: a block that WOULD be PUT, which may owe one.
restart_owed=()
restart_candidate=0

: "${BASE_URL:?BASE_URL required}"
if [[ "${DRY_RUN}" == 0 ]]; then
  : "${ADMIN_TOKEN:?ADMIN_TOKEN required}"
fi

# Entries the server refused. A rejected entry means the box is NOT serving something the seed
# says it should, which is the exact condition this script exists to prevent — so it ends in a
# non-zero exit (the workflow step is continue-on-error, so the publish still succeeds, but the
# step goes red and the log carries an ::error:: instead of a warning nobody reads).
rejected=0

# Sections skipped because the box lacks the route (an older image, e.g. one still mid-roll).
groups_skipped=0
catalogs_skipped=0
sites_skipped=0

# POST one JSON body to an admin path. 200 = applied, 409 = already there (both fine), 404 =
# that ROUTE doesn't exist on this box — returned as 2 so the caller can skip just its own section.
#
# A 404 is per-route, NOT "the admin API is off". This bit for real on the 0.19.8 publish: the box
# was still rolling and answered as 0.19.7, which has /admin/trust but not the newer /admin/groups.
# The groups 404 was treated as a global "admin not enabled" and aborted the run before the catalogs
# loop, so a newly-added catalog (horologist) was silently never published. A missing groups route
# only means catalogs land ungrouped — no reason to skip them.
post() {
  local path="$1" body="$2" label="$3"
  last_post=failed
  if [[ "${DRY_RUN}" == 1 ]]; then
    echo "POST ${path} ${body}"
    last_post=ok
    return 0
  fi
  local response code payload
  # Body AND status: a 400's body carries WHY, and the reason changes what the operator has to do.
  response=$(curl -sS -w $'\n%{http_code}' -m 30 \
    -X POST -H "${ADMIN_TOKEN_HEADER}: ${ADMIN_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "${body}" "${BASE_URL}${path}" 2>/dev/null || printf '\n000')
  code="${response##*$'\n'}"
  payload="${response%$'\n'*}"
  case "${code}" in
    200 | 201)
      echo "  ${label}: applied"
      last_post=ok
      ;;
    409)
      echo "  ${label}: already present"
      last_post=present
      ;;
    404)
      echo "::warning::${path} returned 404 — route not available on this box; skipping the rest of this section."
      return 2
      ;;
    400)
      rejected=$((rejected + 1))
      # `unknown group` should now be unreachable: the group loop above defines every section before
      # any catalog claims one. If it still happens, the group POST silently failed or the box
      # predates /admin/groups — worth saying rather than a generic "rejected".
      if [[ "${payload}" == *"unknown group"* ]]; then
        echo "::error::${label}: ${payload}. Groups are reconciled first, so this means the /admin/groups POST did not take — check the group lines above, or whether this box predates the route."
      else
        echo "::error::${label}: rejected (HTTP 400) — ${payload}"
      fi
      ;;
    *)
      rejected=$((rejected + 1))
      echo "::error::${label}: HTTP ${code} — ${payload}"
      ;;
  esac
  return 0
}

# DELETE one admin path. Sets `last_delete` to ok | absent | refused.
#
# A 409 is NOT uniformly "it was already gone". `ServeCatalogAdmin.unregister` answers 409 for two
# opposite situations: the catalog is not published here (benign — the POST below will create it),
# and the catalog is published as a TOP-LEVEL SITE, which refuses retirement outright so a hostname
# is never stranded. Reading the second as success is how a move on m3-catalog or wear-m3-catalog —
# the two catalogs that ARE sites — would come back green having changed nothing: the delete is
# refused, the re-post 409s on the repo mismatch, and `post` calls that "already present". So
# discriminate on the payload, and let the caller decide.
delete() {
  local path="$1" label="$2"
  last_delete=refused
  if [[ "${DRY_RUN}" == 1 ]]; then
    echo "DELETE ${path}"
    last_delete=ok
    return 0
  fi
  local response code payload
  response=$(curl -sS -w $'\n%{http_code}' -m 30 \
    -X DELETE -H "${ADMIN_TOKEN_HEADER}: ${ADMIN_TOKEN}" \
    "${BASE_URL}${path}" 2>/dev/null || printf '\n000')
  code="${response##*$'\n'}"
  payload="${response%$'\n'*}"
  case "${code}" in
    200 | 201)
      echo "  ${label}: retired the stale registration"
      last_delete=ok
      ;;
    404)
      echo "  ${label}: nothing to retire"
      last_delete=absent
      ;;
    409)
      # `: no change` is the trust store's own "not trusted" (ServeTrustAdmin.mutate), the same
      # nothing-to-do outcome for a producer that the other two phrases are for a catalog.
      if [[ "${payload}" == *"is not published here"* || "${payload}" == *"is not configured"* ||
        "${payload}" == *": no change"* ]]; then
        echo "  ${label}: nothing to retire"
        last_delete=absent
      else
        rejected=$((rejected + 1))
        echo "::error::${label}: cannot be retired — ${payload}"
        last_delete=refused
      fi
      ;;
    *)
      rejected=$((rejected + 1))
      echo "::error::${label}: retiring failed, HTTP ${code} — ${payload}"
      last_delete=refused
      ;;
  esac
  return 0
}

# Trust FIRST. A catalog is verified at fetch time, so publishing it before its producer is
# trusted would register it as `unverified` and leave it that way until its branch next moves.
echo "Reconciling trusted producers from ${TRUST_FILE#"${REPO_ROOT}/"}"
while IFS= read -r entry; do
  [[ -n "${entry}" ]] || continue
  repo=$(printf '%s' "${entry}" | jq -r '.repo')
  branch=$(printf '%s' "${entry}" | jq -r '.branch // "*"')
  post /admin/trust \
    "$(jq -cn --arg r "${repo}" --arg b "${branch}" \
      '{kind:"branch", repo:$r, branch:$b}')" \
    "branch ${repo}@${branch}" || {
    # /admin/trust is the oldest of the three routes, so a 404 HERE really does mean the admin API
    # is off (no --admin-token, or a wrong token — both answer 404 by design). Nothing downstream
    # can work, so stop rather than emit the same warning for every group and catalog.
    if [[ $? == 2 ]]; then
      echo "::warning::/admin/trust is unavailable — admin API not enabled on this box (or the token does not match); skipping the whole reconcile."
      exit 0
    fi
  }
done < <(jq -c '.branches // [] | .[]' "${TRUST_FILE}")

# Groups BEFORE catalogs, for the same reason trust comes before both: a catalog claiming a section
# the server hasn't been told about is rejected outright, and until /admin/groups existed that
# rejection was unfixable from here.
echo "Reconciling front-page groups from ${CATALOGS_FILE#"${REPO_ROOT}/"}"
while IFS= read -r group; do
  [[ -n "${group}" ]] || continue
  id=$(printf '%s' "${group}" | jq -r '.id')
  post /admin/groups "${group}" "group ${id}" || {
    # Route missing (a box predating /admin/groups, e.g. one still mid-roll on an older image).
    # Catalogs are still worth publishing — they just land under the owner-repo fallback heading
    # until a later run can group them. Skipping them here is what silently lost horologist.
    if [[ $? == 2 ]]; then
      groups_skipped=1
      break
    fi
  }
done < <(jq -c '.groups // [] | .[]' "${CATALOGS_FILE}")

# What the box serves RIGHT NOW, so a `repo` change in this file can actually be applied.
#
# The reconcile is additive: re-posting an entry the box already has comes back 409, which `post`
# treats as success. That converges `listed`, `group` and `loadPriority` in place, but NOT `repo` —
# the server refuses to re-point a catalog under an existing id, so the 409 means "unchanged", and
# the box would go on serving the old repository's branch while this file says otherwise and every
# log line reads green. That is not hypothetical: it is exactly what a catalog moving repositories
# does (remote-m3 → yschimke/wear-m3-catalog, #4588), and the failure is silent in the worst way —
# the publisher stops, the served bytes freeze, and nothing reports a problem.
#
# So: read the current registrations, and where the declared `repo` differs from the live one,
# retire that id immediately before the POST re-creates it. A GET failure (older box, no route,
# no token) leaves the map empty, which degrades to exactly the old additive behaviour.
box_catalogs=""
if [[ "${DRY_RUN}" != 1 ]]; then
  box_catalogs=$(curl -sS -m 30 -H "${ADMIN_TOKEN_HEADER}: ${ADMIN_TOKEN}" \
    "${BASE_URL}/admin/catalogs" 2>/dev/null || true)
fi

# One field of the box's current registration for a system, or empty if it serves no such catalog.
box_field_for() {
  [[ -n "${box_catalogs}" ]] || return 0
  printf '%s' "${box_catalogs}" |
    jq -r --arg s "$1" --arg f "$2" \
      '.catalogs // [] | map(select(.system == $s)) | .[0][$f] // ""' 2>/dev/null || true
}

# Is <repo> actually publishing <branch>? A repo move retires the live registration before the POST
# re-creates it, and `ServeCatalogAdmin.register` FETCHES before it persists — so a POST that cannot
# load removes the entry it just added and the catalog is left published nowhere, not rolled back to
# where it was. This is the cheap half of closing that window: refuse to retire anything until the
# replacement branch is known to exist. It does not prove the box can load it, which is why the
# caller still shouts if the re-post fails.
#
# BOUNDED, because `git ls-remote` has no timeout of its own and this is a synchronous call in the
# middle of the reconcile. A connection GitHub accepts and then stalls would hold the entire run —
# every remaining catalog, every site — until the job's own five-minute limit killed it
# (.github/workflows/publish-preview-config.yml), which is a far worse outcome than the error path
# this probe exists to take. Every HTTP call around it already carries `-m 30`; so does this.
# `timeout` exits 124, which is non-zero like any other failure, so a stall lands on the same
# `return 2` and is reported as "could not reach github.com" rather than "no such branch".
#
# GIT_TERMINAL_PROMPT=0 closes the second way this hangs: a repository that 404s to an
# unauthenticated fetch makes git ASK for a username, and a runner has no one to answer.
delivery_branch_exists() {
  local repo="$1" branch="$2" heads
  [[ -n "${repo}" && -n "${branch}" ]] || return 1
  heads=$(GIT_TERMINAL_PROMPT=0 "${LS_REMOTE[@]}" --heads \
    "https://github.com/${repo}.git" "refs/heads/${branch}" 2>/dev/null) ||
    return 2
  [[ -n "${heads}" ]]
}

# The box's registration for one system read FRESH, rather than from the startup snapshot — used
# only to tell a lost catalog from a raced one, where a stale answer is the whole problem. Prints
# the repo, or fails if the listing cannot be read.
live_repo_for() {
  local system="$1" latest
  latest=$(curl -sS -m 30 -H "${ADMIN_TOKEN_HEADER}: ${ADMIN_TOKEN}" \
    "${BASE_URL}/admin/catalogs" 2>/dev/null) || return 1
  [[ -n "${latest}" ]] || return 1
  printf '%s' "${latest}" |
    jq -er --arg s "${system}" \
      '.catalogs // [] | map(select(.system == $s)) | .[0].repo // ""' 2>/dev/null
}

# A catalog which is exposed as a top-level site cannot be retired while that site
# still names it: the server deliberately refuses the DELETE so an operator never
# strands a hostname.  That guard is correct, but a repository move needs the
# inverse ordering: detach the hostname, replace the catalog registration, then
# re-apply the declared site at the end of this script.  The public site is only
# absent for the small delete/re-register window, rather than leaving the catalog
# pointed at the old repository indefinitely.
#
# Do this only for an actual repository move reported by the live catalog listing.
# A normal re-run sees the declared repo already in place and leaves sites alone.
if [[ -n "${box_catalogs}" ]]; then
  echo "Detaching top-level sites whose catalogs move repositories"
  while IFS= read -r site; do
    [[ -n "${site}" ]] || continue
    host=$(printf '%s' "${site}" | jq -r '.host')
    system=$(printf '%s' "${site}" | jq -r '.system')
    current_repo=$(box_field_for "${system}" repo)
    declared_repo=$(jq -r --arg s "${system}" \
      '.catalogs // [] | map(select(.system == $s)) | .[0].repo // ""' \
      "${CATALOGS_FILE}")
    [[ -n "${current_repo}" && -n "${declared_repo}" && "${current_repo}" != "${declared_repo}" ]] || continue

    echo "  site ${host}: temporarily detaching ${system} for ${current_repo} -> ${declared_repo}"
    delete "/admin/sites/${host}" "site ${host}" || true
    case "${last_delete}" in
      ok | absent) ;;
      *)
        rejected=$((rejected + 1))
        echo "::error::site ${host}: could not detach ${system} before its repository move; leaving the catalog registration unchanged."
        ;;
    esac
  done < <(jq -c '.sites // [] | .[]' "${CATALOGS_FILE}")
fi

echo "Reconciling catalogs from ${CATALOGS_FILE#"${REPO_ROOT}/"}"
while IFS= read -r entry; do
  [[ -n "${entry}" ]] || continue
  system=$(printf '%s' "${entry}" | jq -r '.system')
  declared_repo=$(printf '%s' "${entry}" | jq -r '.repo // ""')
  current_repo=$(box_field_for "${system}" repo)
  moved=0
  if [[ -n "${current_repo}" && -z "${declared_repo}" ]]; then
    # No `repo` here means "the box's own --catalog-repo default", which this file cannot see. The
    # POST resolves it, finds a mismatch, 409s, and `post` logs that as "already present" — so a
    # catalog that should have moved to the default keeps serving ${current_repo} and the run ends
    # green. A warning was not enough: nobody reads a warning in a passing job, which is the whole
    # reason this script counts rejections at all.
    #
    # So this reconcile REQUIRES an explicit repo for any catalog the box already serves, which is
    # the cheaper half of Codex's two options — comparing against the server's resolved default
    # would mean reading a flag this file has no route to. Every entry in this repository's config
    # names its repo, so the requirement costs nothing and the failure is loud.
    rejected=$((rejected + 1))
    echo "::error::catalog ${system}: no repo declared, so a move away from ${current_repo} cannot be detected here — declare the repo explicitly in ${CATALOGS_FILE#"${REPO_ROOT}/"}."
  elif [[ -n "${current_repo}" && "${current_repo}" != "${declared_repo}" ]]; then
    echo "  catalog ${system}: repo moved ${current_repo} -> ${declared_repo}"
    # The branch name does not depend on the repo — it is `<branchPrefix><system>` either way — so
    # the live registration tells us what to look for on the new side.
    target_branch=$(box_field_for "${system}" branch)
    if delivery_branch_exists "${declared_repo}" "${target_branch}"; then
      moved=1
      delete "/admin/catalogs/${system}" "catalog ${system}"
      [[ "${last_delete}" == refused ]] && moved=0
    else
      case $? in
        2) echo "::error::catalog ${system}: could not reach github.com to check ${declared_repo}@${target_branch}; leaving it on ${current_repo}." ;;
        *) echo "::error::catalog ${system}: ${declared_repo} publishes no ${target_branch} yet; leaving it on ${current_repo} rather than retiring a catalog with nothing to replace it." ;;
      esac
      rejected=$((rejected + 1))
    fi
  fi
  # Preserve the declared shape — an unlisted catalog must stay off the front page, a group
  # claim has to survive or the card lands under the owner fallback instead of its section, and
  # loadPriority has to reach the box or the committed startup fetch order never takes effect
  # there (the box boots from its own /config/catalogs.json, which this is what rewrites).
  body=$(printf '%s' "${entry}" | jq -c '{system, repo, listed, group, importedFrom, attributionRepos, loadPriority}
    | with_entries(select(.value != null))')
  post /admin/catalogs "${body}" "catalog ${system}" || {
    if [[ $? == 2 ]]; then
      catalogs_skipped=1
      break
    fi
  }
  if [[ "${moved}" == 1 && "${last_post}" != ok ]]; then
    # The retire succeeded and the re-publish did not. Usually that means the catalog is published
    # NOWHERE — `register` drops the entry it added when the fetch fails — and that is the one
    # outcome an operator has to act on immediately, so it must never end in a green log.
    #
    # But a 409 says the opposite of "nowhere": the server has a registration under this id. It can
    # only have arrived between our DELETE and our POST — a concurrent reconcile, or an operator
    # registering it by hand — and if it came from the repository we were moving TO, the move
    # happened and there is nothing to report. Declaring an outage there is a false alarm that
    # fails the standalone publish workflow for a race that converged.
    #
    # So read the live registration back before saying anything. Only the `present` case can be
    # rescued; anything else genuinely lost the catalog.
    raced_repo=""
    if [[ "${last_post}" == present ]]; then
      raced_repo=$(live_repo_for "${system}") || raced_repo=""
    fi
    if [[ -n "${raced_repo}" && "${raced_repo}" == "${declared_repo}" ]]; then
      echo "  catalog ${system}: re-registered from ${declared_repo} by a concurrent publish — the move stands"
    elif [[ -n "${raced_repo}" ]]; then
      rejected=$((rejected + 1))
      echo "::error::catalog ${system} was retired from ${current_repo} and is now registered from ${raced_repo}, not the declared ${declared_repo} — something else re-registered it mid-publish. Re-run this workflow, and check who else is publishing to this box."
    elif [[ "${last_post}" == present ]]; then
      rejected=$((rejected + 1))
      echo "::error::catalog ${system} was retired from ${current_repo} and the re-publish came back 409, but the live registration could not be read back — it is registered as SOMETHING, and possibly not ${declared_repo}. Check /admin/catalogs on the box."
    else
      rejected=$((rejected + 1))
      echo "::error::catalog ${system} was retired from ${current_repo} but could not be re-published from ${declared_repo} — it is currently unpublished. Re-run this workflow once ${declared_repo} serves ${target_branch}, or re-post the old entry to restore it."
    fi
  fi
done < <(jq -c '.catalogs // [] | .[]' "${CATALOGS_FILE}")

# PRUNE (--prune only): retire what the box serves and this file no longer declares.
#
# Runs AFTER the catalogs loop so a catalog being moved between repositories — retired and
# re-posted in that loop — is present again by the time we diff, and is never seen as stale.
#
# Three sets, and the difference between them is the whole logic:
#   declared — .catalogs[].system in the committed file: what should exist.
#   on the box — GET /admin/catalogs: what does exist.
#   registry — /status.json .config.catalogRegistries[].systems: what exists ON PURPOSE while being
#              absent from this file, because a nominated registry contributes it.
#
# Retire (on the box) minus (declared) minus (registry). Without that last term this would delete
# every registry catalog on every publish and they would reappear on the next refresh — a delete
# loop against the box's own correct behaviour.
if [[ "${PRUNE}" == 1 ]]; then
  echo "Pruning catalogs the box serves and ${CATALOGS_FILE#"${REPO_ROOT}/"} no longer declares"

  # Injectable so the self-test can drive the diff without a server; nothing else should set these.
  prune_listing="${PRUNE_BOX_CATALOGS_JSON:-}"
  prune_status="${PRUNE_STATUS_JSON:-}"
  if [[ -z "${prune_listing}" && "${DRY_RUN}" != 1 ]]; then
    prune_listing="${box_catalogs}"
  fi
  if [[ -z "${prune_status}" && "${DRY_RUN}" != 1 ]]; then
    prune_status=$(curl -sS -m 30 "${BASE_URL}/status.json" 2>/dev/null || true)
  fi

  if [[ -z "${prune_listing}" ]]; then
    # Not fatal on its own — the additive half already ran and succeeded. But say it loudly: a
    # prune that silently pruned nothing is indistinguishable from one with nothing to do.
    echo "::warning::--prune could not read /admin/catalogs; nothing was retired."
  elif ! printf '%s' "${prune_status}" |
    jq -e '(.config // {}) | has("catalogRegistries")' >/dev/null 2>&1; then
    # The refusal that keeps this safe. See the header: without this field a registry-contributed
    # catalog and an abandoned one look identical, and deleting the wrong one takes down something
    # the box is serving correctly.
    echo "::error::--prune needs config.catalogRegistries on ${BASE_URL}/status.json (added in #63) to tell registry-contributed catalogs from stale ones. This box predates it; nothing was retired."
    rejected=$((rejected + 1))
  else
    prune_keep=$(
      {
        jq -r '.catalogs // [] | .[].system' "${CATALOGS_FILE}"
        printf '%s' "${prune_status}" |
          jq -r '.config.catalogRegistries // [] | .[].systems // [] | .[]'
      } | sort -u
    )
    while IFS= read -r system; do
      [[ -n "${system}" ]] || continue
      grep -qxF "${system}" <<<"${prune_keep}" && continue
      delete "/admin/catalogs/${system}" "catalog ${system}"
      case "${last_delete}" in
        ok | absent) ;;
        # A catalog that is a top-level SITE refuses retirement so a hostname is never stranded.
        # That is the admin API protecting the box, not a failure of this script — report it and
        # leave the entry alone rather than counting it as a rejected publish.
        *)
          echo "::warning::catalog ${system} is no longer declared but could not be retired (it is published as a top-level site). Remove its \`sites\` entry first."
          ;;
      esac
    done < <(printf '%s' "${prune_listing}" | jq -r '.catalogs // [] | .[].system')
  fi
fi

# PRUNE, trust half (--prune only): revoke branch trust the box holds and producers.json no longer
# declares. Without it a producer dropped from the file stayed trusted on the box indefinitely —
# and on a box running --allow-render-trusted, trusted means eligible for server-side execution, so
# a retired fork kept that standing after its catalogs were gone (thunderbird-android in #1490,
# compose-samples after it). Revoking through the admin API also retires the verdicts of anything
# already loaded under the old trust (ServeTrustAdmin.onRevoke), which is the point.
#
# Runs AFTER the catalogs pass, so a catalog it just retired is not briefly re-verified against
# trust that is about to go. Branch entries only: pinned keys and OIDC identities are not
# declared in producers.json's `branches`, so this file cannot speak for them and leaves them be.
# Registry catalogs need no special case here the way the catalog prune does — a registry's output
# repository is trusted by an ordinary entry in this file (compose-preview-imports-out), so it is
# declared like any other producer.
if [[ "${PRUNE}" == 1 ]]; then
  echo "Pruning branch trust the box holds and ${TRUST_FILE#"${REPO_ROOT}/"} no longer declares"

  # Injectable for the self-test, like the catalog listing above; nothing else should set it.
  trust_listing="${PRUNE_BOX_TRUST_JSON:-}"
  if [[ -z "${trust_listing}" && "${DRY_RUN}" != 1 ]]; then
    trust_listing=$(curl -sS -m 30 -H "${ADMIN_TOKEN_HEADER}: ${ADMIN_TOKEN}" \
      "${BASE_URL}/admin/trust" 2>/dev/null || true)
  fi
  trust_declared=$(jq -r '.branches // [] | .[] | "\(.repo)@\(.branch // "*")"' "${TRUST_FILE}" | sort -u)

  if [[ -z "${trust_declared}" ]]; then
    # An empty or misnamed `branches` would make every trusted producer look undeclared, and the
    # diff below would revoke the lot — catalogs included, through onRevoke. Never do that from a
    # file that says nothing; an operator who really wants no trust edits the box directly.
    echo "::error::--prune found no branches in ${TRUST_FILE#"${REPO_ROOT}/"}; refusing to revoke every trusted producer."
    rejected=$((rejected + 1))
  elif ! printf '%s' "${trust_listing}" | jq -e '.branches | type == "array"' >/dev/null 2>&1; then
    echo "::warning::--prune could not read /admin/trust; no trust was revoked."
  else
    while IFS=$'\t' read -r t_repo t_branch; do
      [[ -n "${t_repo}" ]] || continue
      grep -qxF "${t_repo}@${t_branch}" <<<"${trust_declared}" && continue
      delete "/admin/trust?kind=branch&repo=$(jq -rn --arg v "${t_repo}" '$v|@uri')&branch=$(jq -rn --arg v "${t_branch}" '$v|@uri')" \
        "branch ${t_repo}@${t_branch}"
    done < <(printf '%s' "${trust_listing}" | jq -r '.branches[] | [.repo, .branch] | @tsv')
  fi
fi

# Sites LAST: a site may only name a catalog the box already serves, so it has to follow the
# catalogs loop that publishes them — a hostname posted first would be rejected as naming an
# unserved system, and on a first rollout that is every hostname.
#
# This section is why the whole reconcile exists for sites at all. `sites` is read at STARTUP from
# /config/catalogs.json, which is seeded on first boot and never overwritten, so a hostname added to
# the committed file reached a running box through nothing: standing one up meant editing SERVE_SITES
# in the box's untracked .env and recreating the container. POST /admin/sites applies it live and
# writes it back to the same file.
#
# What this still does NOT do is make the name reachable: DNS must point at the box, and the edge
# must match the hostname and hold a certificate for it. The caddy container derives that from this
# same file (deploy/image/caddy-entrypoint.sh), so it needs a caddy restart and no hand-maintained
# env var — but a brand-new hostname is not live the instant this script prints "applied".
echo "Reconciling top-level sites from ${CATALOGS_FILE#"${REPO_ROOT}/"}"
while IFS= read -r site; do
  [[ -n "${site}" ]] || continue
  host=$(printf '%s' "${site}" | jq -r '.host')
  system=$(printf '%s' "${site}" | jq -r '.system')
  post /admin/sites \
    "$(jq -cn --arg h "${host}" --arg s "${system}" '{host:$h, system:$s}')" \
    "site ${host} -> ${system}" || {
    # A box predating /admin/sites. Everything else published fine; the hostnames stay on whatever
    # that box booted with, which is the behaviour this section replaced.
    if [[ $? == 2 ]]; then
      sites_skipped=1
      break
    fi
  }
done < <(jq -c '.sites // [] | .[]' "${CATALOGS_FILE}")

# The UI-builder editor pin (#1035). `editor` in catalogs.json names the compose-ui-builder release
# this box serves, by version and archive digest, so an editor fix ships as a config change instead
# of a server release. PUT /admin/editor fetches and verifies the archive BEFORE writing the pin —
# a wrong digest or an editor speaking a server API this box does not is a 400 here, not a silent
# fallback at the next boot — which is why its timeout is minutes, not the other routes' 30 s.
#
# The pin applies at the box's next START (the editor directory backs several caches that cannot be
# swapped under a running server), so an accepted pin that differs from what is serving is reported
# as owing a restart. Additive like everything else: a file with no `editor` leaves a box's pin
# alone, except under --prune, where the file is the whole answer and the pin is cleared.
editor_pin=$(jq -c '.editor // empty' "${CATALOGS_FILE}")
if [[ -n "${editor_pin}" ]]; then
  echo "Reconciling the UI-builder editor pin from ${CATALOGS_FILE#"${REPO_ROOT}/"}"
  editor_version=$(printf '%s' "${editor_pin}" | jq -r '.version')
  if [[ "${DRY_RUN}" == 1 ]]; then
    echo "PUT /admin/editor ${editor_pin}"
    restart_candidate=1
  else
    response=$(curl -sS -w $'\n%{http_code}' -m 900 \
      -X PUT -H "${ADMIN_TOKEN_HEADER}: ${ADMIN_TOKEN}" \
      -H 'Content-Type: application/json' \
      -d "${editor_pin}" "${BASE_URL}/admin/editor" 2>/dev/null || printf '\n000')
    code="${response##*$'\n'}"
    payload="${response%$'\n'*}"
    case "${code}" in
      200)
        if [[ "$(printf '%s' "${payload}" | jq -r '.restartRequired // false' 2>/dev/null)" == true ]]; then
          echo "::notice::editor ${editor_version} pinned — it serves from the box's next restart."
          restart_owed+=(editor)
        else
          echo "  editor ${editor_version}: applied"
        fi
        ;;
      409) echo "  editor ${editor_version}: already pinned" ;;
      404) echo "::warning::/admin/editor returned 404 — this box predates editor pins; it keeps its bundled editor." ;;
      *)
        rejected=$((rejected + 1))
        echo "::error::editor ${editor_version}: HTTP ${code} — ${payload}"
        ;;
    esac
  fi
elif [[ "${PRUNE}" == 1 ]]; then
  echo "Clearing any UI-builder editor pin (catalogs.json declares none)"
  delete /admin/editor "editor pin" || true
fi

# The UI builder's catalog settings: `uiBuilder` in catalogs.json, the replacement for the
# SERVE_UI_BUILDER_* catalog variables in the box's private .env (see
# ServeUiBuilderSettings.kt for the variable -> field mapping). The block holds OVERRIDES of that
# .env, so publishing it can only change the catalogs it names — a box keeps everything else its
# .env serves, which is what lets a box move over one catalog at a time.
#
# Like the editor pin it applies at the box's next START, and the reply says when it differs from
# what is serving. Additive like everything else: a file with no `uiBuilder` leaves the box's
# block alone, except under --prune, where the file is the whole answer and the block is cleared.
ui_builder=$(jq -c '.uiBuilder // empty' "${CATALOGS_FILE}")
if [[ -n "${ui_builder}" ]]; then
  echo "Reconciling the UI-builder catalog settings from ${CATALOGS_FILE#"${REPO_ROOT}/"}"
  if [[ "${DRY_RUN}" == 1 ]]; then
    echo "PUT /admin/ui-builder/config ${ui_builder}"
    restart_candidate=1
  else
    response=$(curl -sS -w $'\n%{http_code}' -m 30 \
      -X PUT -H "${ADMIN_TOKEN_HEADER}: ${ADMIN_TOKEN}" \
      -H 'Content-Type: application/json' \
      -d "${ui_builder}" "${BASE_URL}/admin/ui-builder/config" 2>/dev/null || printf '\n000')
    code="${response##*$'\n'}"
    payload="${response%$'\n'*}"
    case "${code}" in
      200)
        next=$(printf '%s' "${payload}" | jq -r '.next.catalogs | join(",")' 2>/dev/null)
        printf '%s' "${payload}" | jq -r '.problems[]? | "::warning::ui-builder settings: \(.)"' 2>/dev/null
        if [[ "$(printf '%s' "${payload}" | jq -r '.restartRequired // false' 2>/dev/null)" == true ]]; then
          echo "::notice::UI-builder catalogs ${next} written — they serve from the box's next restart."
          restart_owed+=(ui-builder/config)
        else
          echo "  UI-builder catalogs ${next}: already serving"
        fi
        ;;
      404) echo "::warning::/admin/ui-builder/config returned 404 — this box predates it; its builder catalogs stay on its .env." ;;
      *)
        rejected=$((rejected + 1))
        echo "::error::UI-builder settings: HTTP ${code} — ${payload}"
        ;;
    esac
  fi
elif [[ "${PRUNE}" == 1 ]]; then
  echo "Clearing any UI-builder catalog settings (catalogs.json declares none)"
  delete /admin/ui-builder/config "UI-builder settings" || true
fi

# The deployment's settings (deploy/image/SETTINGS.md, generated from ServeSettings.kt): every
# non-secret SERVE_* setting, reviewed here instead of edited in the box's private .env. The whole
# file is PUT, so the box holds exactly what is committed — a setting deleted here is deleted there.
#
# The reply says what took effect: `applied` settings were re-read live (the guidelines model and
# allow-list), `pending` ones apply at the box's next start, and `overridden` ones do not apply at
# all while the box's .env still sets the same variable — the environment stays on top so an
# emergency fix works, and this is how a stale line gets noticed. Additive like everything else: no
# settings.json leaves the box's alone, except under --prune, where the file is the whole answer.
if [[ -f "${SETTINGS_FILE}" ]]; then
  echo "Reconciling deployment settings from ${SETTINGS_FILE#"${REPO_ROOT}/"}"
  settings=$(jq -c . "${SETTINGS_FILE}")
  if [[ "${DRY_RUN}" == 1 ]]; then
    echo "PUT /admin/settings ${settings}"
    restart_candidate=1
  else
    response=$(curl -sS -w $'\n%{http_code}' -m 30 \
      -X PUT -H "${ADMIN_TOKEN_HEADER}: ${ADMIN_TOKEN}" \
      -H 'Content-Type: application/json' \
      -d "${settings}" "${BASE_URL}/admin/settings" 2>/dev/null || printf '\n000')
    code="${response##*$'\n'}"
    payload="${response%$'\n'*}"
    case "${code}" in
      200)
        printf '%s' "${payload}" | jq -r '
          (.applied[]? | "  settings: \(.) applied live"),
          (.pending[]? | "::notice::settings: \(.) written — it applies at the box'"'"'s next start."),
          (.overridden[]? | "::warning::settings: \(.) is overridden by the box'"'"'s .env; delete that line to let this value apply."),
          (.problems[]? | "::warning::settings: \(.)")' 2>/dev/null
        if [[ "$(printf '%s' "${payload}" | jq -r '(.pending // []) | length' 2>/dev/null)" != 0 ]]; then
          restart_owed+=(settings)
        fi
        ;;
      404) echo "::warning::/admin/settings returned 404 — this box predates settings.json; its settings stay on its .env." ;;
      *)
        rejected=$((rejected + 1))
        echo "::error::settings: HTTP ${code} — ${payload}"
        ;;
    esac
  fi
elif [[ "${PRUNE}" == 1 ]]; then
  echo "Clearing any deployment settings (${SETTINGS_FILE#"${REPO_ROOT}/"} does not exist)"
  delete /admin/settings "deployment settings" || true
fi

if [[ "${groups_skipped}" == 1 ]]; then
  echo "::warning::front-page groups were not reconciled — this box predates /admin/groups. Catalogs are published ungrouped; the next publish against a newer image will group them."
fi
if [[ "${catalogs_skipped}" == 1 ]]; then
  echo "::error::catalogs were not reconciled — /admin/catalogs is unavailable on this box."
  rejected=$((rejected + 1))
fi
if [[ "${sites_skipped}" == 1 ]]; then
  echo "::warning::top-level sites were not reconciled — this box predates /admin/sites. Its hostnames keep serving whatever it booted with; the next publish against a newer image applies them."
fi

# Applying config that waits for a restart.
#
# The editor pin, the `uiBuilder` block and some settings are read once at the server's START, so
# a PUT that answers `restartRequired` (or settings `pending`) has written config the box is not
# serving. Leaving it there is how a reviewed, merged config change used to need someone to SSH in
# and restart the container — or sit unapplied until the next image happened to roll.
#
# So the publish restarts the box itself, through the deploy hook's /__hooks/restart: a forced,
# zero-downtime rollout of the tag the box already runs (deploy/image/rollout.sh --force). Then it
# proves it, by reading the same three routes until none owes a restart — the step goes red if the
# config still is not serving, rather than reporting a restart that did not happen.
restart_needed=0
if [[ "${DRY_RUN}" == 1 && "${restart_candidate}" == 1 ]] || [[ "${#restart_owed[@]}" -gt 0 ]]; then
  restart_needed=1
fi

restart_owes() { # restart_owes → 0 when any route still answers restartRequired (or pending)
  local route answer
  for route in /admin/editor /admin/ui-builder/config /admin/settings; do
    answer=$(curl -sS -m 30 -H "${ADMIN_TOKEN_HEADER}: ${ADMIN_TOKEN}" \
      "${BASE_URL}${route}" 2>/dev/null || true)
    # 404 (a box without the route) and unreadable answers owe nothing that this can wait for.
    if [[ "$(printf '%s' "${answer}" | jq -r '.restartRequired // false' 2>/dev/null)" == true ]]; then
      return 0
    fi
  done
  return 1
}

if [[ "${restart_needed}" == 1 && "${RESTART}" == 0 ]]; then
  echo "::notice::config owes a restart (${restart_owed[*]:-dry run}); --no-restart given, so it applies at the box's next start."
elif [[ "${restart_needed}" == 1 && "${DRY_RUN}" == 1 ]]; then
  echo "POST ${DEPLOY_HOOK_RESTART_URL#"${BASE_URL}"} (only when a PUT above answers restartRequired)"
elif [[ "${restart_needed}" == 1 && -z "${DEPLOY_HOOK_TOKEN}" ]]; then
  echo "::warning::config owes a restart (${restart_owed[*]}) but DEPLOY_HOOK_TOKEN is unset — it applies at the box's next start (the next image roll)."
elif [[ "${restart_needed}" == 1 ]]; then
  echo "Restarting the box so ${restart_owed[*]} serves: POST ${DEPLOY_HOOK_RESTART_URL}"
  attempt=0
  code=000
  while :; do
    attempt=$((attempt + 1))
    code=$(curl -sS -o /dev/null -w '%{http_code}' -m 30 -X POST \
      -H "Authorization: Bearer ${DEPLOY_HOOK_TOKEN}" "${DEPLOY_HOOK_RESTART_URL}" 2>/dev/null || echo 000)
    # 409: a rollout is already running and may have booted on the old config, so it does not
    # count; wait for it and ask again.
    [[ "${code}" == 409 && "${attempt}" -lt "${RESTART_RETRIES}" ]] || break
    echo "  restart hook: a rollout is in progress — retrying in ${RESTART_RETRY_SECONDS}s"
    sleep "${RESTART_RETRY_SECONDS}"
  done
  case "${code}" in
    200 | 202)
      echo "  restart hook: accepted — waiting for the box to serve the new config"
      waited=0
      clean=0
      while [[ "${clean}" -lt "${RESTART_CONSECUTIVE}" ]]; do
        if [[ "${waited}" -ge "${RESTART_WAIT_SECONDS}" ]]; then
          rejected=$((rejected + 1))
          echo "::error::the box still owes a restart ${RESTART_WAIT_SECONDS}s after /__hooks/restart was accepted. A hook older than restarts answers ANY path with a plain rollout, which is a no-op without a new image — if the box's deploy/image predates deploy-hook.sh's restart route, update it once; otherwise check \`docker compose logs hook\` there."
          break
        fi
        sleep "${RESTART_POLL_SECONDS}"
        waited=$((waited + RESTART_POLL_SECONDS))
        if restart_owes; then clean=0; else clean=$((clean + 1)); fi
      done
      [[ "${clean}" -ge "${RESTART_CONSECUTIVE}" ]] && echo "  restart: the box serves the published config (${waited}s)"
      ;;
    404)
      echo "::warning::/__hooks/restart returned 404 — the box's hook predates restarts (update deploy/image on the box once); the config applies at its next start."
      ;;
    *)
      rejected=$((rejected + 1))
      echo "::error::restart hook: HTTP ${code} — the config is written but not serving."
      ;;
  esac
fi

if [[ "${rejected}" -gt 0 ]]; then
  echo "::error::${rejected} seed entr(y|ies) were rejected — the box is not serving everything the committed config declares." >&2
  exit 1
fi
echo "Config reconcile complete."
