#!/usr/bin/env bash
#
# Is a catalog repository ready to describe itself?
#
# Two of the three catalogs the UI builder serves are written in Kotlin in THIS repository —
# synthesised at startup from the packaged Material 3 one — and the plan in
# docs/design/UI_BUILDER_CATALOG_CONTRACT.md moves that description into the repositories that own
# the components. The phases are ordered so those repositories publish first and this one cuts over
# afterwards, which turns "is wear-m3-catalog ready?" from a judgement into a question somebody can
# answer. This script is how it gets answered.
#
# It compares the CATALOG-LEVEL facts a catalog states against the same facts in the frozen golden —
# the JSON `SynthesisedCatalogGoldenTest` writes out of the generator that runs today. Where they
# agree, the catalog can describe itself; where they differ, either the catalog is wrong or the
# difference is deliberate and belongs in the reviewed-difference list beside it.
#
# TWO SHAPES, one comparison. Through phases 2 and 3 the only thing a catalog has is its authored
# `ui-builder.policy.json`, in a checkout — no catalog can publish until the pipeline release lands.
# From phase 1 onwards the delivery branch also carries the GENERATED `ui-builder.json`, where the
# same facts ride under `statusSemantics` and `menu` is called `componentMenu`. `--policy` accepts
# either and says which it read: a gate that only understood the source would be useless at the
# cutover, and one that only understood the published file would be useless until then.
#
# What it deliberately does NOT do
#
#   Compare components. The published builder catalog is POLICY; the component record beside it is
#   the inventory, and composing the two into a capability catalog is the loader this repository has
#   not written yet (phase 4). Pretending to compare them here would mean a second implementation of
#   that composition, in bash, whose disagreements with the real one nobody would ever see.
#
#   Fail a build. Through phases 2 and 3 a catalog that publishes nothing reports "not yet", and one
#   that differs reports what differs. `--strict` turns that into exit 1, which is what the cutover
#   PR turns on once a catalog is meant to be equivalent.
#
# Usage:
#   .github/scripts/ui-builder-equivalence.sh --policy <path> --golden <path> [--differences <path>]
#                                             [--catalog-id <id>] [--strict]
#
#   --policy       a catalog's authored ui-builder.policy.json, or its generated ui-builder.json
#                  (a local checkout, or fetched from the delivery branch)
#   --golden       docs/design/fixtures/ui-builder/<id>-capabilities-v1.json
#   --catalog-id   the id the caller believes it fetched, checked against the golden's own. Only
#                  needed when the policy defaults its id from the cover sheet rather than declaring
#                  one (:remote-catalog does). Semantics never identify a catalog — a second `wear`
#                  catalog can agree on every compared field — so without an id `--strict` has no
#                  way to tell "ready" from "you read the wrong file".
#   --differences  a JSON array of {"field": …, "why": …, "policy": …} — differences somebody has
#                  read and accepted. `why` is printed, because an unexplained exemption is how a
#                  gate stops meaning anything; `policy` is the exact value that was reviewed,
#                  because an exemption that outlives the thing it exempted is the other way. A
#                  waiver naming only the field would approve every future value of it — an
#                  accidentally emptied menu passing under the entry that reviewed a deliberate
#                  reordering — so a changed value re-surfaces as a stale exemption.
#
# Five things fail under `--strict`: a difference nobody has accepted, a fact the frozen catalog
# states that the catalog is silent about, a stale or unexplained exemption, a MISSING policy file,
# and a policy that is not this catalog's. The last three matter most: `--strict` is the cutover
# asserting readiness, so "there is no catalog here", "this describes nothing at all" and "somebody
# fetched a real file belonging to a different catalog" must none of them read as success. An
# exemption counts as stale once the field it waives AGREES again — it has outlived its
# disagreement, and leaving it would silently re-authorise a return to the waived value.
#
# What `builtins` can and cannot tell you. A declared builtin the frozen catalog carries no
# component for is a real difference and is reported. The reverse — a builtin the catalog OUGHT to
# declare and does not — is not checkable here, because the frozen catalog does not distinguish a
# builtin from a record component; that one is caught by the generator's `policy.builtin.*`
# diagnostics and by the phase-4 loader. Said plainly rather than left as a field that silently
# never differs.
set -euo pipefail

policy=""
golden=""
differences=""
catalog_id=""
strict=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --policy) policy="$2"; shift 2 ;;
    --golden) golden="$2"; shift 2 ;;
    --differences) differences="$2"; shift 2 ;;
    --catalog-id) catalog_id="$2"; shift 2 ;;
    --strict) strict=1; shift ;;
    -h|--help) sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ -z "${policy}" || -z "${golden}" ]]; then
  echo "usage: $0 --policy <ui-builder.policy.json> --golden <…-capabilities-v1.json> [--differences <json>] [--catalog-id <id>] [--strict]" >&2
  exit 2
fi

if [[ ! -f "${golden}" ]]; then
  echo "ui-builder-equivalence: no golden at ${golden}." >&2
  echo "  Run scripts/regenerate-goldens.sh" >&2
  exit 2
fi

if [[ ! -f "${policy}" ]]; then
  # Through phases 2 and 3 this is the expected answer for a catalog that has not authored a policy
  # yet, and saying so is the point of a readiness check. Under --strict it is not: --strict is the
  # cutover asserting this catalog IS ready, and "there is no file" and "somebody fetched the wrong
  # path" must not read as success to the automation about to switch a reader over.
  echo "ui-builder-equivalence: ${policy} does not exist — this catalog does not describe itself yet."
  if [[ "${strict}" == "1" ]]; then
    echo "  --strict asserts readiness, and a catalog with no policy is not ready." >&2
    exit 1
  fi
  exit 0
fi

node - "${policy}" "${golden}" "${differences}" "${strict}" "${catalog_id}" <<'NODE'
const { readFileSync } = require("node:fs");
const [, , policyPath, goldenPath, differencesPath, strictFlag, expectedId] = process.argv;
const strict = strictFlag === "1";

const read = (path) => JSON.parse(readFileSync(path, "utf8"));
const source = read(policyPath);
const golden = read(goldenPath);
const accepted = new Map(
  (differencesPath ? read(differencesPath) : []).map((entry) => [entry.field, entry]),
);

const semantics = golden.statusSemantics ?? {};

// Either shape. A generated `ui-builder.json` carries the facts under `statusSemantics` and calls
// the shelf order `componentMenu`; the authored `ui-builder.policy.json` carries them at the top
// level and calls it `menu`. Reading only one would make the gate useless either before the
// pipeline release or after the cutover, and it is the same facts either way.
const published = source.statusSemantics !== undefined;
const facts = published ? source.statusSemantics : source;
const shape = published ? "generated ui-builder.json" : "authored ui-builder.policy.json";
const menu = published ? facts.componentMenu : facts.menu;
const declaredBuiltins = Object.keys(facts.builtins ?? {}).sort();

const fields = [
  ["platform", facts.platform, semantics.platform],
  ["platformLabel", facts.platformLabel, semantics.platformLabel],
  // Compared, not merely carried. A surface entry says which backend renders a catalog
  // authoritatively and how honest the other one is, and phase 4 reads it to CHOOSE the native
  // backend — so a policy claiming `native.backend: "desktop"` for a catalog the frozen one renders
  // under Robolectric would change what gets drawn while every other field still matched. Left out
  // of this list it could not differ, and a field that cannot differ is the omission this gate
  // already had to be fixed for once.
  ["previewSurfaces", facts.previewSurfaces, semantics.previewSurfaces],
  ["componentMenu.groupOrder", menu?.groupOrder, semantics.componentMenu?.groupOrder],
  ["frame.adapter", facts.frame?.adapter, semantics.frame?.adapter],
  ["frame.geometry", facts.frame?.geometry, semantics.frame?.geometry],
  ["colorTokens.roles", facts.colorTokens?.roles, semantics.colorTokens?.roles],
];

// `$comment` keys are prose for a reader and are never part of a comparison — and object keys are
// sorted, because two generators emitting the same object in different insertion orders is not a
// difference and a gate that said it was would block the cutover on nothing. Arrays are left alone:
// shelf order and allowed values are information.
const strip = (value) => {
  if (Array.isArray(value)) return value.map(strip);
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value)
        .filter(([key]) => !key.startsWith("$comment"))
        .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
        .map(([key, child]) => [key, strip(child)]),
    );
  }
  return value;
};

const canonical = (value) => JSON.stringify(strip(value));
const show = (value) => (value === undefined ? "(absent)" : canonical(value));

let differences = 0;
let gaps = 0;
let stale = 0;
let unstated = 0;

// A waiver names the values somebody reviewed — BOTH of them — not the field. Waiving the field
// approves every future value of it; pinning only one side leaves the other free to move under a
// waiver that no longer describes the discrepancy being waived.
const waiverVerdict = (waiver, stated, frozen) => {
  if (waiver.why === undefined || String(waiver.why).trim() === "") {
    return "the accepted difference carries no `why`";
  }
  if (waiver.policy === undefined) return "the accepted difference names no reviewed policy value";
  if (waiver.frozen === undefined) return "the accepted difference names no reviewed frozen value";
  if (canonical(waiver.policy) !== canonical(stated)) {
    return `the catalog changed since this was accepted\n      reviewed: ${show(waiver.policy)}\n      now:      ${show(stated)}`;
  }
  if (canonical(waiver.frozen) !== canonical(frozen)) {
    return `the frozen catalog changed since this was accepted\n      reviewed: ${show(waiver.frozen)}\n      now:      ${show(frozen)}`;
  }
  return null;
};

console.log(`  read ${policyPath} as a ${shape}`);
for (const [field, stated, frozen] of fields) {
  if (stated === undefined) {
    if (frozen === undefined) continue;
    // The golden states this and the catalog does not. Silently skipping it is how a gate goes
    // green for a catalog that has described nothing at all — the failure that makes a readiness
    // check worse than no check, because it answers the question wrongly.
    gaps += 1;
    console.log(`  ? ${field}: the frozen catalog states ${show(frozen)}; the catalog is silent`);
    continue;
  }
  if (frozen === undefined) {
    unstated += 1;
    console.log(`  ~ ${field}: stated as ${show(stated)}; the frozen catalog says nothing`);
    continue;
  }
  if (canonical(stated) === canonical(frozen)) {
    // A waiver on a field that now AGREES has outlived the discrepancy it was written for. Skipping
    // the check here left it valid indefinitely, so if the catalog ever returned to the exact value
    // that was waived, the old entry would authorise the regression with nobody re-reading it —
    // which is the failure the reviewed-value pinning exists to prevent, reintroduced by the happy
    // path. An exemption is only ever as good as the disagreement it describes.
    if (accepted.has(field)) {
      stale += 1;
      console.log(`  ! ${field}: agrees with the frozen catalog, so the accepted difference for it`);
      console.log(`      is obsolete and should be deleted — it would silently re-authorise a`);
      console.log(`      return to ${show(accepted.get(field).policy)}`);
      continue;
    }
    console.log(`  = ${field}`);
    continue;
  }
  const waiver = accepted.get(field);
  if (waiver === undefined) {
    differences += 1;
    console.log(`  x ${field}`);
    console.log(`      catalog: ${show(stated)}`);
    console.log(`      frozen:  ${show(frozen)}`);
    continue;
  }
  const problem = waiverVerdict(waiver, stated, frozen);
  if (problem !== null) {
    stale += 1;
    console.log(`  ! ${field}: ${problem}`);
    continue;
  }
  console.log(`  ! ${field}: accepted difference — ${waiver.why}`);
}

// The one thing `builtins` can be checked against: the frozen catalog's own component ids. A
// builtin it carries no component for is a component this build has never had under that id.
const frozenIds = new Set((golden.components ?? []).map((component) => component.componentId));
const unknownBuiltins = declaredBuiltins.filter((id) => !frozenIds.has(id));
if (declaredBuiltins.length > 0) {
  if (unknownBuiltins.length === 0) {
    console.log(`  = builtins (${declaredBuiltins.length}, all present in the frozen catalog)`);
  } else {
    differences += unknownBuiltins.length;
    console.log(`  x builtins the frozen catalog has no component for: ${unknownBuiltins.join(", ")}`);
  }
}

// WHICH CATALOG IS THIS? Asked before anything above is believed.
//
// Every comparison so far has been of catalog-level SEMANTICS, and semantics do not identify a
// catalog: a second `wear` catalog with the same shelves, roles and frame agrees with this golden
// on every enumerated field while being a different catalog entirely. The id was printed and never
// checked, so `--strict` — whose whole job is to assert "this catalog is ready to replace that
// frozen one" — could answer yes about the wrong file. That is the same failure as the missing
// policy the header already argues about, arriving through a path that exists rather than one that
// does not.
//
// The authored shape may legitimately be silent: :remote-catalog defaults its id from its cover
// sheet's `system` on purpose. So `--catalog-id` lets the caller supply the id it believes it
// fetched. Silence with no fallback is not resolvable, and under --strict that is a refusal rather
// than a shrug — an unidentified catalog cannot be asserted ready.
const declaredId = source.catalogId ?? source.catalog?.id ?? null;
const statedId = declaredId ?? (expectedId ? expectedId : null);
const goldenId = golden.benchmark?.catalogSystemId ?? null;
let misidentified = 0;
if (goldenId === null) {
  // The golden names no catalog, so there is nothing to be wrong about. Silent on purpose: this is
  // the shape of a hand-written fixture, not of a real frozen catalog.
} else if (statedId === null) {
  misidentified += 1;
  console.log("");
  console.log(
    `  ? catalog id: the policy declares none and no --catalog-id was given, so there is nothing ` +
      `to check against the frozen catalog's ${JSON.stringify(goldenId)}`,
  );
} else if (statedId !== goldenId) {
  misidentified += 1;
  console.log("");
  console.log(`  x catalog id: this is ${JSON.stringify(statedId)}, the golden is ${JSON.stringify(goldenId)}`);
  console.log(`      Not a difference to waive — a policy for another catalog was read.`);
} else {
  const how = declaredId !== null ? "declared" : "supplied with --catalog-id";
  console.log(`  = catalog id (${how}: ${JSON.stringify(statedId)})`);
}

const id = statedId ?? "(unidentified)";
const blocking = differences + gaps + stale + misidentified;
console.log("");
console.log(
  `ui-builder-equivalence: ${id} — ${differences} difference(s), ${gaps} unstated fact(s) the ` +
    `frozen catalog has, ${stale} unusable exemption(s), ${unstated} field(s) the frozen catalog ` +
    `has no opinion about.`,
);
if (misidentified > 0 && strict) {
  console.log("`--strict` asserts THIS catalog is ready to replace THAT frozen one, which cannot be");
  console.log("asserted about a catalog nobody has identified. Pass --catalog-id when the policy");
  console.log("defaults its id from the cover sheet.");
  process.exit(1);
}
if (blocking > 0 && strict) {
  console.log("A difference is either wrong, or deliberate and belongs in the list passed with");
  console.log("--differences — with a `why`, the `policy` value reviewed AND the `frozen` value it");
  console.log("was reviewed against, so the exemption fails when either side moves.");
  process.exit(1);
}
NODE
