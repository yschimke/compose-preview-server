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
# It compares the CATALOG-LEVEL facts a published `ui-builder.policy.json` states against the same
# facts in the frozen golden — the JSON `SynthesisedCatalogGoldenTest` writes out of the generator
# that runs today. Where they agree, the catalog can describe itself; where they differ, either the
# policy is wrong or the difference is deliberate and belongs in the reviewed-difference list beside
# it.
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
#                                             [--strict]
#
#   --policy       a catalog repository's ui-builder.policy.json (fetched, or a local checkout)
#   --golden       docs/design/fixtures/ui-builder/<id>-capabilities-v1.json
#   --differences  a JSON array of {"field": …, "why": …, "policy": …} — differences somebody has
#                  read and accepted. `why` is printed, because an unexplained exemption is how a
#                  gate stops meaning anything; `policy` is the exact value that was reviewed,
#                  because an exemption that outlives the thing it exempted is the other way. A
#                  waiver naming only the field would approve every future value of it — an
#                  accidentally emptied menu passing under the entry that reviewed a deliberate
#                  reordering — so a changed value re-surfaces as a stale exemption.
#
# Three things fail under `--strict`: a difference nobody has accepted, a fact the frozen catalog
# states that the policy is silent about, and a stale exemption. The middle one is what stops the
# gate answering "ready" for a policy that describes nothing at all.
set -euo pipefail

policy=""
golden=""
differences=""
strict=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --policy) policy="$2"; shift 2 ;;
    --golden) golden="$2"; shift 2 ;;
    --differences) differences="$2"; shift 2 ;;
    --strict) strict=1; shift ;;
    -h|--help) sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ -z "${policy}" || -z "${golden}" ]]; then
  echo "usage: $0 --policy <ui-builder.policy.json> --golden <…-capabilities-v1.json> [--differences <json>] [--strict]" >&2
  exit 2
fi

if [[ ! -f "${golden}" ]]; then
  echo "ui-builder-equivalence: no golden at ${golden}." >&2
  echo "  Run scripts/regenerate-goldens.sh" >&2
  exit 2
fi

if [[ ! -f "${policy}" ]]; then
  # Not an error, and not a pass. Through phases 2 and 3 this is the expected answer for a catalog
  # that has not authored a policy yet, and saying so is the whole point of a readiness check.
  echo "ui-builder-equivalence: ${policy} does not exist — this catalog does not describe itself yet."
  exit 0
fi

node - "${policy}" "${golden}" "${differences}" "${strict}" <<'NODE'
const { readFileSync } = require("node:fs");
const [, , policyPath, goldenPath, differencesPath, strictFlag] = process.argv;
const strict = strictFlag === "1";

const read = (path) => JSON.parse(readFileSync(path, "utf8"));
const policy = read(policyPath);
const golden = read(goldenPath);
const accepted = new Map(
  (differencesPath ? read(differencesPath) : []).map((entry) => [entry.field, entry]),
);

const semantics = golden.statusSemantics ?? {};

// The catalog-level facts, and nothing else. Each is something the policy states outright and the
// golden already carries, so the comparison is between two claims about the same thing rather than
// between a claim and a derivation.
const fields = [
  ["platform", policy.platform, semantics.platform],
  ["platformLabel", policy.platformLabel, semantics.platformLabel],
  [
    "componentMenu.groupOrder",
    policy.menu?.groupOrder,
    semantics.componentMenu?.groupOrder,
  ],
  ["frame.adapter", policy.frame?.adapter, semantics.frame?.adapter],
  ["frame.geometry", policy.frame?.geometry, semantics.frame?.geometry],
  ["colorTokens.roles", policy.colorTokens?.roles, semantics.colorTokens?.roles],
  ["builtins", Object.keys(policy.builtins ?? {}).sort(), undefined],
];

// `$comment` keys are prose for a reader and are never part of a comparison — and object keys are
// sorted, because two generators emitting the same object in different insertion orders is not a
// difference and a gate that said it was would block the cutover on nothing.
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
for (const [field, stated, frozen] of fields) {
  if (stated === undefined) {
    if (frozen === undefined) continue;
    // The golden states this and the policy does not. Silently skipping it is how a gate goes green
    // for a policy that describes nothing at all — the failure that makes a readiness check worse
    // than no check, because it answers "ready" for a catalog nobody has written.
    gaps += 1;
    console.log(`  ? ${field}: the frozen catalog states ${show(frozen)}; the policy is silent`);
    continue;
  }
  if (frozen === undefined) {
    // The policy says something the frozen catalog has no opinion about. Not a difference: the
    // synthesised catalogs were never asked half these questions, which is a good part of why the
    // knowledge is moving.
    unstated += 1;
    console.log(`  ~ ${field}: stated as ${show(stated)}; the frozen catalog says nothing`);
    continue;
  }
  if (canonical(stated) === canonical(frozen)) {
    console.log(`  = ${field}`);
    continue;
  }
  const waiver = accepted.get(field);
  if (waiver === undefined) {
    differences += 1;
    console.log(`  x ${field}`);
    console.log(`      policy: ${show(stated)}`);
    console.log(`      frozen: ${show(frozen)}`);
    continue;
  }
  // A waiver names the value somebody reviewed, not the field. Waiving the field would approve
  // every future value of it — an accidentally emptied menu would pass under the same entry that
  // reviewed a deliberate reordering — and an exemption that survives the thing it exempted is how
  // a gate stops meaning anything.
  if (waiver.policy === undefined) {
    stale += 1;
    console.log(`  ! ${field}: the accepted difference names no reviewed value`);
    console.log(`      add "policy": ${show(stated)} to the entry if that is what was reviewed`);
    continue;
  }
  if (canonical(waiver.policy) !== canonical(stated)) {
    stale += 1;
    console.log(`  ! ${field}: the policy changed since this difference was accepted`);
    console.log(`      reviewed: ${show(waiver.policy)}`);
    console.log(`      now:      ${show(stated)}`);
    continue;
  }
  console.log(`  ! ${field}: accepted difference — ${waiver.why}`);
}

const id = policy.catalogId ?? "(defaulted from the cover sheet)";
const blocking = differences + gaps + stale;
console.log("");
console.log(
  `ui-builder-equivalence: ${id} — ${differences} difference(s), ${gaps} unstated fact(s) the ` +
    `frozen catalog has, ${stale} stale exemption(s), ${unstated} field(s) the frozen catalog has ` +
    `no opinion about.`,
);
if (blocking > 0 && strict) {
  console.log("A difference is either wrong, or deliberate and belongs in the list passed with");
  console.log("--differences — with a `why` AND the `policy` value that was reviewed, so the");
  console.log("exemption fails when the thing it exempted changes.");
  process.exit(1);
}
NODE
