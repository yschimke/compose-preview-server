// Which of the wall's two picture columns comes first, and what its header calls itself.
//
// ONE ORDER, EVERY LANE: **baseline · diff · ours**.
//
// The wall used to keep the render on the left for the `svg` and `rc` lanes and the target on the
// left for `reference` and `parallel`, on the reasoning that an export is "on trial" against the
// render that produced it while an independently-authored design is not. That reasoning is sound
// and the result was still wrong to read: pressing a baseline button swapped both pictures' sides
// AND relabelled both headers, so the one control that changes the question also moved the answer
// (`docs/design/COMPARE_NAVIGATION.md`, F3). A comparison table may change what it is comparing; it
// may not change where to look.
//
// Baseline-first is the order every other surface in the product already reads in — the viewer's
// triptych (Spec / Diff / Render), the wipe seam, and the focused `/compare/<id>` page (Reference /
// Diff / Actual) — so the two lanes that dissented are the ones that move.
//
// DOM-free on purpose: `CompareWall` reads these two answers and moves the cells.

import type { Format } from "./pairing.js";

/**
 * Whether the baseline leads the pair. Always, on every lane — see the module note.
 *
 * Kept as a function rather than inlined at the call sites: `CompareWall.orderColumns` asserts the
 * order on every render, and one place to state the rule is what keeps a future lane from quietly
 * reintroducing a second one.
 */
export function specLeadsColumns(_format: Format): boolean {
    return true;
}

/**
 * What the baseline column's header calls itself.
 *
 * The head used to be the constant `SVG`, which was already wrong on two of the three lanes and
 * would be actively misleading now that the columns can swap: a header reading `SVG` over the
 * Figma column, beside one reading `Rendered PNG` over a picture that is not the render, tells the
 * reader the pair is the other way round. [referenceLabel] is the tool the catalog's references
 * actually came from ("Figma"), the same word the baseline button and the catalog action use.
 */
export function targetHeadLabel(
    format: Format,
    referenceLabel: string,
    parallelLabel = "Parallel implementation",
): string {
    if (format === "reference") return referenceLabel || "Design reference";
    if (format === "parallel") return parallelLabel;
    return format === "rc" ? "Remote Compose" : "SVG";
}
