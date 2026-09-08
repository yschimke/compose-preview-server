#!/usr/bin/env bash
#
# Tests for ui-builder-equivalence.sh. A readiness gate that silently passes is worth nothing, so
# the three answers it has to be able to give each get a case: "not yet", "these differ", and
# "somebody read that difference and accepted it".
#
# Usage: .github/scripts/test-ui-builder-equivalence.sh
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)
gate="${repo_root}/.github/scripts/ui-builder-equivalence.sh"
work=$(mktemp -d)
trap 'rm -rf "${work}"' EXIT

failures=0
check() {
  local name="$1" expected="$2" actual="$3"
  if [[ "${expected}" == "${actual}" ]]; then
    echo "ok   ${name}"
  else
    echo "FAIL ${name}: expected ${expected}, got ${actual}"
    failures=$((failures + 1))
  fi
}

cat >"${work}/golden.json" <<'JSON'
{ "statusSemantics": { "platform": "wear", "componentMenu": { "groupOrder": ["A", "B"] } } }
JSON

cat >"${work}/agrees.json" <<'JSON'
{ "schema": "compose-ui-builder-policy/v1", "catalogId": "wear-m3", "platform": "wear",
  "menu": { "groupOrder": ["A", "B"] } }
JSON

cat >"${work}/differs.json" <<'JSON'
{ "schema": "compose-ui-builder-policy/v1", "catalogId": "wear-m3", "platform": "wear",
  "menu": { "$comment": "prose is never compared", "groupOrder": ["B", "A"] } }
JSON

cat >"${work}/accepted.json" <<'JSON'
[ { "field": "componentMenu.groupOrder", "why": "the catalog's own sections, deliberately",
    "policy": ["B", "A"] } ]
JSON

cat >"${work}/unpinned.json" <<'JSON'
[ { "field": "componentMenu.groupOrder", "why": "no reviewed value recorded" } ]
JSON

cat >"${work}/stale.json" <<'JSON'
[ { "field": "componentMenu.groupOrder", "why": "reviewed something else",
    "policy": ["C", "D"] } ]
JSON

# States nothing the golden states. The gate must not call this ready.
cat >"${work}/silent.json" <<'JSON'
{ "schema": "compose-ui-builder-policy/v1", "catalogId": "wear-m3", "platform": "wear" }
JSON

# The same facts as the golden, with object members in the other order.
cat >"${work}/reordered-golden.json" <<'JSON'
{ "statusSemantics": { "platform": "wear",
  "frame": { "geometry": { "contentPadding": [ { "screenDp": 192, "horizontalDp": 10 } ] } } } }
JSON

cat >"${work}/reordered-policy.json" <<'JSON'
{ "schema": "compose-ui-builder-policy/v1", "platform": "wear",
  "frame": { "adapter": "frame/round-screen",
    "geometry": { "$comment": "prose", "contentPadding": [ { "horizontalDp": 10, "screenDp": 192 } ] } } }
JSON

set +e
"${gate}" --policy "${work}/absent.json" --golden "${work}/golden.json" >"${work}/out" 2>&1
check "a catalog with no policy reports not-yet rather than failing" 0 $?
grep -q "does not describe itself yet" "${work}/out" ||
  { echo "FAIL missing-policy message"; failures=$((failures + 1)); }

"${gate}" --policy "${work}/agrees.json" --golden "${work}/golden.json" --strict >/dev/null 2>&1
check "agreement passes under --strict" 0 $?

"${gate}" --policy "${work}/differs.json" --golden "${work}/golden.json" >"${work}/out" 2>&1
check "a difference is reported without --strict, and does not fail" 0 $?
grep -q "1 difference(s)" "${work}/out" ||
  { echo "FAIL difference not counted"; failures=$((failures + 1)); }

"${gate}" --policy "${work}/differs.json" --golden "${work}/golden.json" --strict >/dev/null 2>&1
check "a difference fails under --strict" 1 $?

"${gate}" --policy "${work}/differs.json" --golden "${work}/golden.json" \
  --differences "${work}/accepted.json" --strict >"${work}/out" 2>&1
check "an accepted difference passes under --strict" 0 $?
grep -q "the catalog's own sections, deliberately" "${work}/out" ||
  { echo "FAIL the accepted reason is not printed"; failures=$((failures + 1)); }

"${gate}" --policy "${work}/agrees.json" --golden "${work}/missing-golden.json" >/dev/null 2>&1
check "a missing golden is a usage error, not a pass" 2 $?

# A gate that answers "ready" for a policy stating nothing is worse than no gate: it answers the
# question wrongly rather than declining to answer it.
"${gate}" --policy "${work}/silent.json" --golden "${work}/golden.json" --strict >"${work}/out" 2>&1
check "a policy silent about a fact the golden states fails --strict" 1 $?
grep -q "the policy is silent" "${work}/out" ||
  { echo "FAIL silence not reported"; failures=$((failures + 1)); }

# An exemption that outlives the thing it exempted approves every future value of the field —
# an accidentally emptied menu passing under the entry that reviewed a deliberate reordering.
"${gate}" --policy "${work}/differs.json" --golden "${work}/golden.json" \
  --differences "${work}/unpinned.json" --strict >"${work}/out" 2>&1
check "an exemption naming no reviewed value fails --strict" 1 $?
grep -q "names no reviewed value" "${work}/out" ||
  { echo "FAIL unpinned exemption not reported"; failures=$((failures + 1)); }

"${gate}" --policy "${work}/differs.json" --golden "${work}/golden.json" \
  --differences "${work}/stale.json" --strict >"${work}/out" 2>&1
check "an exemption whose reviewed value has changed fails --strict" 1 $?
grep -q "changed since this difference was accepted" "${work}/out" ||
  { echo "FAIL stale exemption not reported"; failures=$((failures + 1)); }

# Two generators emitting one object in different insertion orders is not a difference, and a
# gate that said it was would block the cutover on nothing.
"${gate}" --policy "${work}/reordered-policy.json" --golden "${work}/reordered-golden.json" \
  --strict >"${work}/out" 2>&1
check "object key order is not a difference" 0 $?
set -e

if [[ ${failures} -gt 0 ]]; then
  echo "${failures} failure(s)"
  exit 1
fi
echo "all ui-builder-equivalence tests passed"
