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
#   The catalog's own MENU is a different thing and is compared per component: which shelf each one
#   lands on and which parameter its variant control writes to are catalog-level policy, stated in
#   the published file, and phase 4 reads them to build the insert panel. Only the entries the
#   catalog states, though — the frozen catalog's menu also carries the BUILDER's components
#   (`asset/image`, `layout/box`, `remote-compose/*`), which are not the catalog's to state.
#
#   Fail a build. Through phases 2 and 3 a catalog that publishes nothing reports "not yet", and one
#   that differs reports what differs. `--strict` turns that into exit 1, which is what the cutover
#   PR turns on once a catalog is meant to be equivalent.
#
#   VALIDATE a policy. Every catalog-level fact phase 4 consumes is COMPARED here — `code`,
#   `templates` and `frame.seedDevice` alongside the platform, the surfaces and the frame — but
#   whether `code.strategy` names a strategy that exists, or a `templates` path resolves to a
#   document, is the authoring pipeline's question. compose-ai-tools answers it in
#   `validate-ui-builder-policy.mjs`, and a second implementation of it here would disagree with
#   the real one exactly where that matters. What this gate says is that the two documents agree,
#   or that a fact only one of them states has been read.
#
# Usage:
#   .github/scripts/ui-builder-equivalence.sh --policy <path> --golden <path> [--differences <path>]
#                                             [--catalog-id <id>] [--strict]
#
#   --policy       a catalog's authored ui-builder.policy.json, or its generated ui-builder.json
#                  (a local checkout, or fetched from the delivery branch)
#   --golden       docs/design/fixtures/ui-builder/<id>-capabilities-v1.json
#   --catalog-id   the catalog the caller MEANT to check. An assertion in its own right, not a
#                  fallback for a silent policy: it is checked against the golden's id AND against
#                  the policy's, so asking for one catalog while holding another's policy and its
#                  matching golden fails — those two agree with each other, and only the caller
#                  knows which catalog was intended. Required for a policy that defaults its id from
#                  the cover sheet (:remote-catalog and m3-catalog both do). Semantics never
#                  identify a catalog — a second `wear` catalog can agree on every compared field —
#                  so without an id `--strict` cannot tell "ready" from "you read the wrong file".
#   --differences  a JSON array of {"field": …, "why": …, "policy": …} — differences somebody has,
#                  each field named at most ONCE: a second entry for a field would silently replace
#                  the first, leaving a review decision nothing ever judged.
#                  read and accepted. `why` is printed, because an unexplained exemption is how a
#                  gate stops meaning anything; `policy` is the exact value that was reviewed,
#                  because an exemption that outlives the thing it exempted is the other way. A
#                  waiver naming only the field would approve every future value of it — an
#                  accidentally emptied menu passing under the entry that reviewed a deliberate
#                  reordering — so a changed value re-surfaces as a stale exemption.
#
# Seven things fail under `--strict`: a difference nobody has accepted, a fact the frozen catalog
# states that the catalog is silent about, a fact the CATALOG states that the frozen one cannot
# check (accepted with `"frozen": null` once somebody has read it — the mirror of the silence rule,
# because one catches a catalog describing too little and the other a catalog describing something
# WRONG), a stale or unexplained exemption, a MISSING policy file, a policy that is not this
# catalog's — a different catalog from the golden's or from `--catalog-id`, or a document naming TWO
# catalogs because a field that identifies one shape rides as ordinary payload in another — and a
# SCHEMA this gate cannot vouch for on EITHER document — a future major, an unrecognised family, a
# family that does not match the shape the document actually has, an unreadable string, or none at
# all where only a capability document may
# omit one. Refused
# outright rather than compared, because the fields this gate happens to recognise in a `…/v2` say
# nothing about the semantics it does not. The last three matter most: `--strict` is the cutover
# asserting readiness, so "there is no catalog here", "this describes nothing at all" and "somebody
# fetched a real file belonging to a different catalog" must none of them read as success. An
# exemption counts as stale once the disagreement it describes stops existing — whether the two
# sides AGREE again, both go SILENT, or either one stops stating the field. Any of those and it sits
# there re-authorising a return to the waived value with nobody re-reading it.
#
# What `builtins` can and cannot tell you. A declared builtin the frozen catalog carries no
# component for is a real difference and is reported — one field per id, `builtins.<id>`, so a
# deliberate addition can be reviewed and pinned like any other fact the frozen catalog cannot
# check, and so a waiver approves the builtin somebody read rather than the whole set. The reverse — a builtin the catalog OUGHT to
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
    # The whole leading comment block, not a hardcoded line range: the range was `2,40p` and every
    # paragraph added above `Usage:` pushed the flags further out of it, so `--help` had quietly
    # stopped printing the flags it exists to document.
    -h|--help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}"; exit 0 ;;
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
const acceptedEntries = differencesPath ? read(differencesPath) : [];
const accepted = new Map(acceptedEntries.map((entry) => [entry.field, entry]));
// A `Map` keeps the LAST of two entries naming one field and drops the first without a word — so
// "every waiver is judged", the rule this whole file is built around, quietly stopped being true
// the moment somebody pasted an entry twice. The dropped one could be the unexplained or stale
// half, and `--strict` would report zero unusable exemptions and pass. Counted here rather than
// deduplicated, because two review decisions about one field are two people disagreeing, or one
// person editing the wrong copy, and neither is for this gate to resolve by picking one.
const duplicated = [
  ...new Set(
    acceptedEntries
      .map((entry) => entry.field)
      .filter((field, index, all) => all.indexOf(field) !== index),
  ),
].sort();

const semantics = golden.statusSemantics ?? {};

// Either shape. A generated `ui-builder.json` carries the facts under `statusSemantics` and calls
// the shelf order `componentMenu`; the authored `ui-builder.policy.json` carries them at the top
// level and calls it `menu`. Reading only one would make the gate useless either before the
// pipeline release or after the cutover, and it is the same facts either way.
const published = source.statusSemantics !== undefined;
const facts = published ? source.statusSemantics : source;
// A capability document is a third shape: it also carries `statusSemantics`, but its builtins have
// been materialised into top-level `components` alongside the record ones, so nothing in it says
// which were builtins. Named rather than lumped in with the generated artifact, because the two
// differ in exactly the way the builtin check depends on.
const capabilities = published && source.benchmark !== undefined;
const shape = !published
  ? "authored ui-builder.policy.json"
  : capabilities
    ? "capability document"
    : "generated ui-builder.json";
const menu = published ? facts.componentMenu : facts.menu;
const declaredBuiltins = Object.keys(facts.builtins ?? {}).sort();

// A future MAJOR is refused rather than compared. The contract's compatibility rule is that a
// reader ignores fields it does not understand and refuses a future major — and a gate that
// compared `…/v2` field by field would be reporting readiness for a document whose semantics it
// does not know, which is the readiness question answered by assuming the answer.
const SCHEMA_FAMILIES = {
  "compose-ui-builder-policy": 1,
  "compose-ui-builder-catalog": 1,
  "compose-ui-builder-capabilities": 1,
};
// And which family each SHAPE must declare. Knowing the family is not the same as the family being
// the right one: this gate detects the shape STRUCTURALLY — does the document carry
// `statusSemantics`, does it carry `benchmark` — so an authored policy labelled
// `compose-ui-builder-catalog/v1` was read as a policy, compared field by field, and passed. The
// argument is the one already made for an unrecognised family, one step in: the fields this gate
// happens to recognise say nothing about what the document MEANS, and a document whose label and
// whose contents disagree has one of the two wrong. Which one is not for this gate to guess.
const SHAPE_FAMILIES = {
  "authored ui-builder.policy.json": "compose-ui-builder-policy",
  "generated ui-builder.json": "compose-ui-builder-catalog",
  "capability document": "compose-ui-builder-capabilities",
};
// BOTH documents, not just the policy. The frozen catalog is regenerated from this repository's own
// types, so `CatalogCapabilityV1` moving to a v2 is the likelier of the two — and a v1 policy
// against a v2 golden is exactly as unreadable as the reverse, for the same reason: the fields that
// still line up say nothing about the ones whose meaning changed.
// Which shape each of the two documents is. The golden is a capability document by construction;
// the source is whichever shape was detected above. Defined before the function that reads it,
// because a `const` used ahead of its own initialiser is a temporal-dead-zone crash waiting for the
// first caller who reorders anything.
const shapeOf = (label) => (label === "catalog" ? shape : "capability document");

const refuseFutureMajor = (label, doc, mustDeclare, expectedFamily) => {
  const schema = doc?.schema;
  // A schema that is PRESENT and unreadable is refused, and a shape that must declare one and does
  // not is refused too. Only `family/version` says anything; `compose-ui-builder-policy-v999`, a
  // number, or an object all skipped every check below and let matching fields report readiness.
  // The exemption stays exactly where it was argued for: a CAPABILITY document (one carrying
  // `benchmark`) legitimately has no schema, and refusing those would make this an allowlist.
  if (schema === undefined || schema === null) {
    if (!mustDeclare) return false;
    console.log(`  x schema: the ${label} declares none, and only a capability document may.`);
    console.log(`      Without one this gate cannot say whether it understands the document.`);
    console.log("");
    console.log(`ui-builder-equivalence: refused — the ${label} declares no schema.`);
    return true;
  }
  if (typeof schema !== "string" || !schema.includes("/")) {
    console.log(`  x schema: the ${label} is ${JSON.stringify(schema)}, which is not`);
    console.log(`      'family/version', so this gate cannot tell what it is looking at.`);
    console.log("");
    console.log(`ui-builder-equivalence: refused — unreadable schema ${JSON.stringify(schema)}.`);
    return true;
  }
  const family = schema.slice(0, schema.lastIndexOf("/"));
  const major = Number.parseInt(String(schema.slice(schema.lastIndexOf("/") + 1)).replace(/^v/, ""), 10);
  const known = SCHEMA_FAMILIES[family];
  // An UNRECOGNISED family is refused for the same reason a future major is, and the reason is the
  // one this gate keeps coming back to: `compose-ui-builder-polciy/v999` still spells `platform` and
  // `componentMenu` the way this gate reads them, so every comparison passes and `--strict` reports
  // readiness for a document it has never heard of. "Not a family I know" and "a version I know is
  // too new" are the same answer — I do not know what this means — and only one of them was being
  // given.
  if (known === undefined) {
    console.log(`  x schema: the ${label} is ${JSON.stringify(schema)}, which is not a family this`);
    console.log(`      gate knows (${Object.keys(SCHEMA_FAMILIES).join(", ")}). The fields it`);
    console.log(`      happens to recognise say nothing about what the document means.`);
    console.log("");
    console.log(`ui-builder-equivalence: refused — unrecognised schema ${schema}.`);
    return true;
  }
  if (family !== expectedFamily) {
    console.log(`  x schema: the ${label} is ${JSON.stringify(schema)}, but its shape is`);
    console.log(`      ${shapeOf(label)}, which declares '${expectedFamily}'. A document whose`);
    console.log(`      label and whose contents disagree is not one this gate can vouch for.`);
    console.log("");
    console.log(`ui-builder-equivalence: refused — ${schema} on ${shapeOf(label)}.`);
    return true;
  }
  if (!Number.isFinite(major)) {
    console.log(`  x schema: the ${label} is ${JSON.stringify(schema)}, whose version is not a`);
    console.log(`      number, so this gate cannot tell whether it understands it.`);
    console.log("");
    console.log(`ui-builder-equivalence: refused — unreadable schema ${schema}.`);
    return true;
  }
  if (major <= known) return false;
  console.log(`  x schema: the ${label} is ${JSON.stringify(schema)}, a future major of '${family}'.`);
  console.log(`      This gate understands v${known}. Comparing the fields it happens to`);
  console.log(`      recognise would report readiness for semantics it does not know.`);
  console.log("");
  console.log(`ui-builder-equivalence: refused — unsupported schema ${schema}.`);
  return true;
};
// A capability document is the one shape allowed to be schema-less; everything else must say what
// it is. `capabilities` is computed above from the source's own `benchmark`; the golden is a
// capability document by construction.
if (
  refuseFutureMajor("catalog", source, !capabilities, SHAPE_FAMILIES[shape]) ||
  refuseFutureMajor("frozen catalog", golden, false, "compose-ui-builder-capabilities")
) {
  process.exit(strict ? 1 : 2);
}

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
  // The rest of what phase 4 CONSUMES, for the reason `previewSurfaces` is here: a field this list
  // leaves out cannot differ, and a field that cannot differ is checked by nothing. `seedDevice`
  // decides which device a new design opens on, `code` is what the emitter is routed through
  // (`code.strategy`, plus the imports and structural templates a generated screen is built from),
  // and `templates` names the documents the New-design chooser offers. Each of them would have
  // reached the cutover unread while `platform` and the shelf order agreed.
  //
  // Compared, not validated. Whether `code.strategy` is a strategy that exists, or a `templates`
  // path resolves to a document, is the authoring pipeline's question and is answered by
  // compose-ai-tools' `validate-ui-builder-policy.mjs` — a second implementation of it here, in a
  // gate that only holds the frozen catalog, would disagree with the real one where it matters.
  // What this gate can say is that the catalog and the frozen catalog do or do not agree, and that
  // a fact only the catalog states has been read by somebody.
  ["frame.seedDevice", facts.frame?.seedDevice, semantics.frame?.seedDevice],
  ["frame.geometry", facts.frame?.geometry, semantics.frame?.geometry],
  ["colorTokens.roles", facts.colorTokens?.roles, semantics.colorTokens?.roles],
  ["code", facts.code, semantics.code],
  ["templates", facts.templates, semantics.templates],
];

// EVERY component's shelf, not just the order of the shelves.
//
// `componentMenu.groupOrder` names the sections; `componentMenu.components` says which section each
// component lands in and which of its parameters the variant control writes to. Only the first was
// compared, so a generated catalog could move every component to a different shelf, or drop every
// `variantProperty`, and `--strict` would still report readiness — the insert panel at cutover
// bearing no resemblance to the frozen one while the section headings matched.
//
// A SUBSET, deliberately. The frozen catalog is a capability document: its menu carries the
// builder's own components (`asset/image`, `layout/box`, `remote-compose/*`) alongside the
// catalog's, and a generated `ui-builder.json` carries only the catalog's. Comparing the two maps
// whole would report a permanent difference nobody can fix, and waiving a thirty-entry map is a
// rubber stamp rather than a review. So each entry the CATALOG states is checked against the frozen
// entry for the same id; ids only the frozen one has are the builder's, not this catalog's to
// state, and the `builtins` check below is what covers that direction.
//
// Fed into the same `fields` list rather than compared separately, so waiver pinning, obsolescence
// and the unreviewed-field rule all apply to a shelf assignment exactly as they do to the frame —
// a second implementation of those rules here is how they would start to disagree. Only entries
// that DIFFER or already carry a waiver are added: adding the agreeing ones would print thirty `=`
// lines, and a waiver for an entry that has come back into agreement still has to be judged stale.
// A builtin the frozen catalog carries no component for, as one field per id.
//
// This was counted straight into `differences`, outside the model every other discrepancy goes
// through — so it could not be waived at all. Worse than that: the failure told the reader to put
// it in `--differences`, and an entry naming `builtins` was then reported a SECOND time by the
// sweep for waivers naming no compared field. The gate asked for a review decision and then
// refused the only place to record one, which is a check that cannot be satisfied rather than a
// check that can be answered.
//
// Per id, because a catalog that deliberately adds one builtin has reviewed THAT builtin, and a
// waiver naming the whole set would approve the next one nobody looked at. Each lands on the
// policy-only path — the catalog states it and the frozen catalog has no component to check it
// against — so it is accepted with `"frozen": null`, exactly like `frame.adapter`.
//
// The frozen catalog's own component ids are the one thing this can be checked against; a
// capability document does not distinguish a builtin from a record component, so it states nothing
// here and the note below says so rather than printing a silent pass.
const frozenIds = new Set((golden.components ?? []).map((component) => component.componentId));
const unknownBuiltins = capabilities
  ? []
  : declaredBuiltins.filter((id) => !frozenIds.has(id));
for (const id of unknownBuiltins) {
  fields.push([`builtins.${id}`, facts.builtins?.[id] ?? null, undefined]);
}

const statedEntries = menu?.components;
const frozenEntries = semantics.componentMenu?.components;
let agreeingEntries = 0;
if (statedEntries && typeof statedEntries === "object" && !Array.isArray(statedEntries)) {
  for (const id of Object.keys(statedEntries).sort()) {
    const field = `componentMenu.components.${id}`;
    const frozenEntry = frozenEntries?.[id];
    if (frozenEntry !== undefined && canonical(statedEntries[id]) === canonical(frozenEntry)) {
      agreeingEntries += 1;
      if (!accepted.has(field)) continue;
    }
    fields.push([field, statedEntries[id], frozenEntry]);
  }
}

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
  // `null` is how a waiver says "and the other side states NOTHING", which is a reviewed fact like
  // any other — the field is absent, and somebody looked at that. `undefined` still means the entry
  // forgot to say, which is the thing the presence checks above are for. Normalising them here
  // keeps those two apart while letting an absent side be pinned.
  const same = (a, b) => canonical(a ?? null) === canonical(b ?? null);
  if (!same(waiver.policy, stated)) {
    return `the catalog changed since this was accepted\n      reviewed: ${show(waiver.policy)}\n      now:      ${show(stated)}`;
  }
  if (!same(waiver.frozen, frozen)) {
    return `the frozen catalog changed since this was accepted\n      reviewed: ${show(waiver.frozen)}\n      now:      ${show(frozen)}`;
  }
  return null;
};

console.log(`  read ${policyPath} as a ${shape}`);

// An exemption is only ever as good as the disagreement it describes — the third and last way that
// can stop being true. Convergence was one; both sides going SILENT is the other, and this branch
// returned before the waiver was consulted, so the entry survived to re-authorise the exact old
// discrepancy the day it came back.
const waiverIsObsolete = (field, why) => {
  if (!accepted.has(field)) return false;
  stale += 1;
  console.log(`  ! ${field}: ${why}.`);
  console.log(`      The accepted difference for it is obsolete and should be deleted; left in`);
  console.log(`      place it would silently re-authorise a return to ${show(accepted.get(field).policy)}`);
  return true;
};

// EVERY waiver is judged here, once, before any branch decides what to print.
//
// I added this check to the branches one at a time — the field agreeing, then both sides silent —
// and each time a branch I had not thought about still slipped past it. There is one question worth
// asking and it does not depend on which branch a field lands in: DOES THE DISAGREEMENT THIS WAIVER
// DESCRIBES STILL EXIST? It does only when both sides state the field and the two values differ.
// Asked here, a field shape nobody has thought of yet cannot acquire a fourth exemption from the
// rule, because the branches no longer carry it.
const comparedFields = new Set(fields.map(([field]) => field));
for (const [field, stated, frozen] of fields) {
  // What a waiver can legitimately be reviewing, now that a policy-only fact needs one too:
  //
  //   both sides state it and the values differ  — a difference somebody accepted
  //   the catalog states it and the frozen one does not — a fact nothing here can check
  //
  // Anything else and the thing the waiver describes has stopped existing: the two agree again,
  // both went silent, or the catalog dropped the field while the frozen one kept it (which is a
  // gap, and blocking on its own — a waiver cannot make a missing fact present).
  const reviewable =
    stated !== undefined && (frozen === undefined || canonical(stated) !== canonical(frozen));
  if (reviewable) continue;
  waiverIsObsolete(
    field,
    stated === undefined && frozen === undefined
      ? "neither the catalog nor the frozen catalog states this"
      : stated === undefined
        ? "the catalog does not state this at all"
        : "this agrees with the frozen catalog",
  );
}
// And a waiver naming a field NOTHING compares. Iterating `fields` judged every waiver that could
// be reached from a compared field and silently skipped the rest — so a mistyped `colorTokens.rolez`
// sat in the differences file being counted as nothing at all, which is the same "an unchecked
// exemption is worse than none" this whole rule exists for. "Every waiver is judged" has to mean
// every waiver, not every waiver whose field happens to be on the list.
for (const field of accepted.keys()) {
  if (comparedFields.has(field)) continue;
  stale += 1;
  console.log(`  ! ${field}: no such compared field, so this exemption waives nothing.`);
  console.log(`      Compared fields are: ${[...comparedFields].join(", ")}.`);
}
for (const field of duplicated) {
  stale += 1;
  console.log(`  ! ${field}: named by more than one accepted difference, so only the last was read`);
  console.log(`      and the others were judged by nothing. Keep the one somebody means.`);
}

for (const [field, stated, frozen] of fields) {
  if (stated === undefined) {
    if (frozen === undefined) {
      continue;
    }
    // The golden states this and the catalog does not. Silently skipping it is how a gate goes
    // green for a catalog that has described nothing at all — the failure that makes a readiness
    // check worse than no check, because it answers the question wrongly.
    gaps += 1;
    console.log(`  ? ${field}: the frozen catalog states ${show(frozen)}; the catalog is silent`);
    continue;
  }
  if (frozen === undefined) {
    // The catalog states a fact the frozen one has no opinion about — and phase 4 CONSUMES these:
    // `platformLabel` names the platform in the chooser, `frame.adapter` picks what draws the
    // canvas, `frame.geometry` is the measured padding a design is laid out against. Nothing has
    // compared them to anything, so an arbitrary label or a wrong padding table would have reached
    // the cutover with the gate reporting ready.
    //
    // This was informational while its mirror image — the frozen catalog states it and the policy
    // is silent — was blocking, and that asymmetry is indefensible: both are a fact nobody
    // compared. One risks a catalog that describes too little, the other a catalog that describes
    // something WRONG, and only the first was being caught. Accepted with a reviewed entry pinning
    // `"frozen": null`, which is somebody saying they looked at a value the golden cannot check.
    const waiver = accepted.get(field);
    if (waiver === undefined) {
      unstated += 1;
      console.log(`  x ${field}: stated as ${show(stated)}; the frozen catalog says nothing, so`);
      console.log(`      nothing here has checked it. Accept it with "frozen": null once read.`);
      continue;
    }
    const problem = waiverVerdict(waiver, stated, frozen);
    if (problem !== null) {
      stale += 1;
      console.log(`  ! ${field}: ${problem}`);
      continue;
    }
    console.log(`  ! ${field}: unchecked by the frozen catalog, accepted — ${waiver.why}`);
    continue;
  }
  if (canonical(stated) === canonical(frozen)) {
    // A waiver on a field that now AGREES has outlived the discrepancy it was written for. Skipping
    // the check here left it valid indefinitely, so if the catalog ever returned to the exact value
    // that was waived, the old entry would authorise the regression with nobody re-reading it —
    // which is the failure the reviewed-value pinning exists to prevent, reintroduced by the happy
    // path. An exemption is only ever as good as the disagreement it describes.
    // Its waiver, if any, was already judged obsolete by the sweep above.
    if (accepted.has(field)) continue;
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

// Said once rather than thirty times: the entries that agree are the reason this comparison is worth
// having, and printing each of them would bury the ones that do not.
if (agreeingEntries > 0) {
  console.log(`  = componentMenu.components (${agreeingEntries} shelf assignment(s) agree)`);
}

if (capabilities) {
  // Not "no builtins" — "this shape cannot tell". Reporting nothing here would look identical to a
  // catalog that declares none, which is the difference between a check and its absence.
  console.log(`  ~ builtins: a capability document does not distinguish them from record`);
  console.log(`      components, so there is nothing here to check.`);
} else if (declaredBuiltins.length > 0) {
  if (unknownBuiltins.length === 0) {
    console.log(`  = builtins (${declaredBuiltins.length}, all present in the frozen catalog)`);
  }
  // The unknown ones were reported above, by the field loop, one per id.
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
// Wherever the file names itself: the authored policy's `catalogId`, the generated
// ui-builder.json's `catalog.id`, or a capability document's `benchmark.catalogSystemId`.
//
// WHICH of them, though, is decided by the shape — not by whichever happens to come first.
// A chain of `??` reads the fields in the author's order rather than the document's, and a
// capability document carries `catalog.id` as ordinary payload while being identified by
// `benchmark.catalogSystemId`. So a document whose benchmark says `remote-m3` and whose
// `catalog.id` says `wear-m3` was read as `wear-m3`, matched the Wear golden and the Wear
// `--catalog-id`, and passed `--strict` — the gate approving the wrong catalog through the one
// check that exists to stop exactly that. The fallback is kept for a shape that leaves its own
// field empty while plainly naming itself elsewhere, but it is a fallback now and not a race.
const IDENTIFIERS = {
  "authored ui-builder.policy.json": ["catalogId", source.catalogId],
  "generated ui-builder.json": ["catalog.id", source.catalog?.id],
  "capability document": ["benchmark.catalogSystemId", source.benchmark?.catalogSystemId],
};
const namedIds = Object.values(IDENTIFIERS).filter(([, value]) => value !== undefined);
const [shapeField, shapeId] = IDENTIFIERS[shape];
// Which field ACTUALLY supplied the id, not which one should have: a shape that leaves its own
// field empty falls back, and a message naming the empty field would be describing a value it did
// not carry — the sort of nearly-right report that costs somebody an hour.
const [identifyingField, declaredId] =
  shapeId !== undefined ? [shapeField, shapeId] : (namedIds[0] ?? [shapeField, null]);
const goldenId = golden.benchmark?.catalogSystemId ?? null;
let misidentified = 0;

// A document that names itself TWICE must agree with itself, whichever name won above. Two
// identifiers disagreeing is not a difference to waive and not a question of precedence: one of
// them is wrong, this gate cannot know which, and picking a winner silently is how the wrong
// catalog gets approved. Reported before the golden is consulted, because it is true regardless of
// what the golden says.
const conflicting = namedIds.filter(([, value]) => value !== declaredId);
for (const [field, value] of conflicting) {
  misidentified += 1;
  console.log("");
  console.log(
    `  x catalog id: this ${shape} is identified by \`${identifyingField}\` as ` +
      `${JSON.stringify(declaredId)}, but it also declares \`${field}\`: ${JSON.stringify(value)}`,
  );
  console.log(`      A document that names two different catalogs has one of them wrong.`);
}

// `--catalog-id` is an INDEPENDENT assertion, not a fallback for a silent policy.
//
// Treating it as a fallback meant the caller's explicit target vanished the moment the file
// declared anything — so automation asking for `remote-m3` and handed the Wear policy against the
// Wear golden passed, because those two agree with each other and nobody ever compared them to what
// was asked for. The whole point of the flag is that the caller knows which catalog it MEANT, which
// is exactly the knowledge a mis-fetch destroys.
if (goldenId === null) {
  // The golden names no catalog, so there is nothing to be wrong about. Silent on purpose: this is
  // the shape of a hand-written fixture, not of a real frozen catalog.
  if (expectedId) console.log(`  ~ catalog id: --catalog-id ${JSON.stringify(expectedId)}; the frozen catalog names none`);
} else {
  if (expectedId && expectedId !== goldenId) {
    misidentified += 1;
    console.log("");
    console.log(
      `  x catalog id: --catalog-id asked for ${JSON.stringify(expectedId)}, but this golden is ` +
        `${JSON.stringify(goldenId)} — the wrong pair of files was fetched.`,
    );
  }
  if (declaredId !== null && declaredId !== goldenId) {
    misidentified += 1;
    console.log("");
    console.log(`  x catalog id: this is ${JSON.stringify(declaredId)}, the golden is ${JSON.stringify(goldenId)}`);
    console.log(`      Not a difference to waive — a policy for another catalog was read.`);
  }
  if (declaredId !== null && expectedId && declaredId !== expectedId) {
    misidentified += 1;
    console.log(
      `  x catalog id: --catalog-id asked for ${JSON.stringify(expectedId)}, the policy declares ` +
        `${JSON.stringify(declaredId)}`,
    );
  }
  if (declaredId === null && !expectedId) {
    misidentified += 1;
    console.log("");
    console.log(
      `  ? catalog id: the policy declares none and no --catalog-id was given, so there is nothing ` +
        `to check against the frozen catalog's ${JSON.stringify(goldenId)}`,
    );
  }
  if (misidentified === 0) {
    const how = declaredId !== null ? "declared" : "supplied with --catalog-id";
    console.log(`  = catalog id (${how}: ${JSON.stringify(declaredId ?? expectedId)})`);
  }
}

const id = declaredId ?? expectedId ?? "(unidentified)";
const blocking = differences + gaps + stale + misidentified + unstated;
console.log("");
console.log(
  `ui-builder-equivalence: ${id} — ${differences} difference(s), ${gaps} unstated fact(s) the ` +
    `frozen catalog has, ${stale} unusable exemption(s), ${unstated} unreviewed field(s) the frozen catalog ` +
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
