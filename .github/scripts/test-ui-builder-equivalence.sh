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
{ "benchmark": { "catalogSystemId": "wear-m3" },
  "statusSemantics": { "platform": "wear", "componentMenu": { "groupOrder": ["A", "B"] } } }
JSON

# Same semantics, different catalog. Every compared field agrees; only the id says otherwise.
cat >"${work}/impostor.json" <<'JSON'
{ "schema": "compose-ui-builder-policy/v1", "catalogId": "wear-m3-tv", "platform": "wear",
  "menu": { "groupOrder": ["A", "B"] } }
JSON

# Declares no id — legitimate for a module defaulting it from its cover sheet.
cat >"${work}/unnamed.json" <<'JSON'
{ "schema": "compose-ui-builder-policy/v1", "platform": "wear",
  "menu": { "groupOrder": ["A", "B"] } }
JSON

# A waiver for a field that now AGREES with the frozen catalog.
cat >"${work}/converged.json" <<'JSON'
[ { "field": "componentMenu.groupOrder", "why": "reviewed back when these disagreed",
    "policy": ["B", "A"], "frozen": ["A", "B"] } ]
JSON

# previewSurfaces: the golden claims an android backend, this policy claims desktop.
cat >"${work}/surfaces-golden.json" <<'JSON'
{ "benchmark": { "catalogSystemId": "wear-m3" },
  "statusSemantics": { "platform": "wear", "componentMenu": { "groupOrder": ["A"] },
    "previewSurfaces": { "native": { "fidelity": "authoritative", "backend": "android" } } } }
JSON

cat >"${work}/surfaces-policy.json" <<'JSON'
{ "schema": "compose-ui-builder-policy/v1", "catalogId": "wear-m3", "platform": "wear",
  "menu": { "groupOrder": ["A"] },
  "previewSurfaces": { "native": { "fidelity": "authoritative", "backend": "desktop" } } }
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
    "policy": ["B", "A"], "frozen": ["A", "B"] } ]
JSON

cat >"${work}/unpinned.json" <<'JSON'
[ { "field": "componentMenu.groupOrder", "why": "no reviewed value recorded" } ]
JSON

cat >"${work}/stale.json" <<'JSON'
[ { "field": "componentMenu.groupOrder", "why": "reviewed something else",
    "policy": ["C", "D"], "frozen": ["A", "B"] } ]
JSON

# States nothing the golden states. The gate must not call this ready.
cat >"${work}/silent.json" <<'JSON'
{ "schema": "compose-ui-builder-policy/v1", "catalogId": "wear-m3", "platform": "wear" }
JSON

cat >"${work}/unexplained.json" <<'JSON'
[ { "field": "componentMenu.groupOrder", "policy": ["B", "A"], "frozen": ["A", "B"] } ]
JSON

cat >"${work}/frozen-moved.json" <<'JSON'
[ { "field": "componentMenu.groupOrder", "why": "reviewed against a different frozen value",
    "policy": ["B", "A"], "frozen": ["X", "Y"] } ]
JSON

# The published shape: the same facts under statusSemantics, with `menu` called `componentMenu`.
cat >"${work}/published.json" <<'JSON'
{ "schema": "compose-ui-builder-catalog/v1", "catalog": { "id": "wear-m3" },
  "statusSemantics": { "platform": "wear", "componentMenu": { "groupOrder": ["A", "B"] } } }
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
grep -q "the catalog is silent" "${work}/out" ||
  { echo "FAIL silence not reported"; failures=$((failures + 1)); }

# An exemption that outlives the thing it exempted approves every future value of the field —
# an accidentally emptied menu passing under the entry that reviewed a deliberate reordering.
"${gate}" --policy "${work}/differs.json" --golden "${work}/golden.json" \
  --differences "${work}/unpinned.json" --strict >"${work}/out" 2>&1
check "an exemption naming no reviewed value fails --strict" 1 $?
grep -q "names no reviewed policy value" "${work}/out" ||
  { echo "FAIL unpinned exemption not reported"; failures=$((failures + 1)); }

"${gate}" --policy "${work}/differs.json" --golden "${work}/golden.json" \
  --differences "${work}/stale.json" --strict >"${work}/out" 2>&1
check "an exemption whose reviewed value has changed fails --strict" 1 $?
grep -q "catalog changed since this was accepted" "${work}/out" ||
  { echo "FAIL stale exemption not reported"; failures=$((failures + 1)); }

# Two generators emitting one object in different insertion orders is not a difference, and a
# gate that said it was would block the cutover on nothing.
"${gate}" --policy "${work}/reordered-policy.json" --golden "${work}/reordered-golden.json" \
  --strict >"${work}/out" 2>&1
check "object key order is not a difference" 0 $?

# An exemption with the values but no reason is the failure the `why` field exists to prevent.
"${gate}" --policy "${work}/differs.json" --golden "${work}/golden.json" \
  --differences "${work}/unexplained.json" --strict >"${work}/out" 2>&1
check "an exemption with no why fails --strict" 1 $?
grep -q "carries no \`why\`" "${work}/out" ||
  { echo "FAIL unexplained exemption not reported"; failures=$((failures + 1)); }

# Pinning only the catalog side leaves the frozen side free to move under a waiver that no longer
# describes the discrepancy it waived.
"${gate}" --policy "${work}/differs.json" --golden "${work}/golden.json" \
  --differences "${work}/frozen-moved.json" --strict >"${work}/out" 2>&1
check "an exemption whose reviewed frozen value has changed fails --strict" 1 $?
grep -q "frozen catalog changed" "${work}/out" ||
  { echo "FAIL moved frozen side not reported"; failures=$((failures + 1)); }

# The generated artifact is the shape the cutover fetches; the authored policy is all a catalog has
# before the pipeline release. Both have to work, or the gate is useless at one end or the other.
"${gate}" --policy "${work}/published.json" --golden "${work}/golden.json" --strict >"${work}/out" 2>&1
check "the generated ui-builder.json shape is read too" 0 $?
grep -q "as a generated ui-builder.json" "${work}/out" ||
  { echo "FAIL published shape not detected"; failures=$((failures + 1)); }

# --strict is the cutover asserting readiness; "there is no catalog" must not read as success.
"${gate}" --policy "${work}/absent.json" --golden "${work}/golden.json" --strict >/dev/null 2>&1
check "a missing policy fails --strict" 1 $?

# Semantics do not identify a catalog. A second `wear` catalog agreeing on every compared field is
# still the wrong file, and "you fetched a real path belonging to somebody else" has to be as loud
# as "you fetched nothing".
"${gate}" --policy "${work}/impostor.json" --golden "${work}/golden.json" --strict >"${work}/out" 2>&1
check "a policy for another catalog fails --strict despite agreeing on everything" 1 $?
grep -q "a policy for another catalog was read" "${work}/out" ||
  { echo "FAIL wrong catalog not reported"; failures=$((failures + 1)); }

# Defaulting the id from the cover sheet is legitimate, so the caller can supply it — but only the
# caller can, and without it the gate must decline to assert readiness rather than assume.
"${gate}" --policy "${work}/unnamed.json" --golden "${work}/golden.json" --strict >"${work}/out" 2>&1
check "an unidentifiable catalog fails --strict" 1 $?
grep -q "there is nothing to check against" "${work}/out" ||
  { echo "FAIL unidentified catalog not reported"; failures=$((failures + 1)); }

"${gate}" --policy "${work}/unnamed.json" --golden "${work}/golden.json"   --catalog-id wear-m3 --strict >"${work}/out" 2>&1
check "--catalog-id supplies the id a policy defaults" 0 $?

"${gate}" --policy "${work}/unnamed.json" --golden "${work}/golden.json"   --catalog-id wear-m3-tv --strict >/dev/null 2>&1
check "--catalog-id naming the wrong catalog still fails --strict" 1 $?

# `--catalog-id` is an assertion, not a fallback. Treating it as a fallback meant the caller's
# explicit target vanished the moment the file declared anything — so asking for one catalog while
# holding another's policy AND its matching golden passed, because those two agree with each other.
"${gate}" --policy "${work}/agrees.json" --golden "${work}/golden.json" \
  --catalog-id remote-m3 --strict >"${work}/out" 2>&1
check "--catalog-id disagreeing with a self-consistent pair fails --strict" 1 $?
grep -q "the wrong pair of files was fetched" "${work}/out" ||
  { echo "FAIL mis-fetched pair not reported"; failures=$((failures + 1)); }

# A capability document names its catalog at `benchmark.catalogSystemId`. It plainly identifies
# itself, so it must not read as unidentified.
cat >"${work}/capability-shaped.json" <<'JSON'
{ "benchmark": { "catalogSystemId": "wear-m3" },
  "statusSemantics": { "platform": "wear", "componentMenu": { "groupOrder": ["A", "B"] } } }
JSON
"${gate}" --policy "${work}/capability-shaped.json" --golden "${work}/golden.json" \
  --strict >"${work}/out" 2>&1
check "a document naming its catalog at benchmark.catalogSystemId is identified" 0 $?

# The other way a waiver outlives its disagreement: both sides go SILENT. The entry then survives
# to re-authorise the exact old discrepancy the day it returns.
cat >"${work}/absent-both.json" <<'JSON'
{ "schema": "compose-ui-builder-policy/v1", "catalogId": "wear-m3", "platform": "wear",
  "menu": { "groupOrder": ["A", "B"] } }
JSON
cat >"${work}/waiver-for-absent.json" <<'JSON'
[ { "field": "colorTokens.roles", "why": "reviewed back when both sides had one",
    "policy": ["primary"], "frozen": ["secondary"] } ]
JSON
"${gate}" --policy "${work}/absent-both.json" --golden "${work}/golden.json" \
  --differences "${work}/waiver-for-absent.json" --strict >"${work}/out" 2>&1
check "a waiver for a field neither side states fails --strict" 1 $?
grep -q "is obsolete and should be deleted" "${work}/out" ||
  { echo "FAIL both-absent waiver not reported"; failures=$((failures + 1)); }

# A future MAJOR is refused rather than compared. Comparing the fields it happens to recognise
# would report readiness for semantics this gate does not know.
cat >"${work}/future-major.json" <<'JSON'
{ "schema": "compose-ui-builder-policy/v2", "catalogId": "wear-m3", "platform": "wear",
  "menu": { "groupOrder": ["A", "B"] } }
JSON
"${gate}" --policy "${work}/future-major.json" --golden "${work}/golden.json" \
  --strict >"${work}/out" 2>&1
check "a future schema major is refused under --strict" 1 $?
grep -q "future major" "${work}/out" ||
  { echo "FAIL future major not reported"; failures=$((failures + 1)); }

# The current major still passes, so the refusal is a version check and not a schema allowlist that
# breaks on the next minor.
"${gate}" --policy "${work}/agrees.json" --golden "${work}/golden.json" --strict >/dev/null 2>&1
check "the current schema major still passes" 0 $?

# A waiver outlives its disagreement. Left valid, it would silently re-authorise a return to the
# exact value it once waived, with nobody re-reading it.
"${gate}" --policy "${work}/agrees.json" --golden "${work}/golden.json"   --differences "${work}/converged.json" --strict >"${work}/out" 2>&1
check "a waiver for a field that now agrees fails --strict" 1 $?
grep -q "is obsolete and should be deleted" "${work}/out" ||
  { echo "FAIL converged waiver not reported"; failures=$((failures + 1)); }

# previewSurfaces chooses the native backend at phase 4. A field left out of the comparison cannot
# differ, and a field that cannot differ is not being checked.
"${gate}" --policy "${work}/surfaces-policy.json" --golden "${work}/surfaces-golden.json" \
  --strict >"${work}/out" 2>&1
check "a preview surface claiming the wrong backend fails --strict" 1 $?
grep -q "previewSurfaces" "${work}/out" ||
  { echo "FAIL previewSurfaces not compared"; failures=$((failures + 1)); }
set -e

if [[ ${failures} -gt 0 ]]; then
  echo "${failures} failure(s)"
  exit 1
fi
echo "all ui-builder-equivalence tests passed"
