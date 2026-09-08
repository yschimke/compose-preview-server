// WHAT THE SHEET SHOWS, AND WHAT IT IS BEING JUDGED AGAINST — two questions, two controls.
//
// The surface used to ask one: a three-way lane of `code` / `design` / `diff`. That worked while
// there were only ever two pictures of a node, because "diff" could mean "ours, scored against the
// design" without ever saying so. It stops working the moment a catalog has a `compareWith`
// sibling: a reader on `remote-m3` wants to see `wear-m3`'s rendition of the same kit node, and to
// score against that rather than against Figma, and neither fits on an axis whose third value is a
// verb.
//
// So the lane is factored into the two orthogonal questions it was conflating:
//
//   SHOW         — whose picture stands in each of the design's slots: ours, the sibling's, or the
//                  design's own drawing.
//   DIFF AGAINST — which of the other two that picture is scored against, or nothing.
//
// Both axes range over the SAME three sources, which is what makes a pairing describable in one
// sentence ("showing ours, diffed against wear-m3") and what keeps the readout honest: a diff is a
// pair of pictures, and both halves are named on screen rather than one being implied by a verb.
//
// The two "show" lanes are still deliberately not a composite — no opacity slider, no `difference`
// blend over the whole sheet. Those answered "how close are these two pictures" by making the
// reader squint; the diff axis answers it with a number and a map, and the eye compares two clean
// frames better than one muddy one.

/** One picture of a node: this catalog's render, the sibling catalog's, or the design's drawing. */
export type Source = "code" | "parallel" | "design";

/** Which source stands in the design's slots. */
export type Lane = Source;

/** What the shown source is scored against — `off` for no scoring at all. */
export type Baseline = "off" | Source;

/** Every source, in the order the controls offer them. */
export const SOURCES: readonly Source[] = ["code", "parallel", "design"];

/** The classes the stage carries for a (lane, baseline) pair. */
export interface StageState {
    "cp-page-swap-on": boolean;
    "cp-page-hide-design": boolean;
    "cp-page-parallel-on": boolean;
    "cp-page-diff-on": boolean;
}

/**
 * What the stage looks like for one pairing.
 *
 * The first three are keyed on the LANE alone — what is being scored changes no pixel of what is
 * drawn, which is exactly the separation this split exists to make. `cp-page-swap-on` still means
 * "a render stands in the design's slots"; WHICH render is `cp-page-parallel-on`'s business.
 */
export function stageState(lane: Lane, baseline: Baseline): StageState {
    const ours = lane !== "design";
    return {
        "cp-page-swap-on": ours,
        "cp-page-hide-design": ours,
        "cp-page-parallel-on": lane === "parallel",
        "cp-page-diff-on": baseline !== "off",
    };
}

/** Whether this pairing needs THIS catalog's renders adopted out of their inert `<template>`. */
export function needsRenders(lane: Lane, baseline: Baseline): boolean {
    return lane === "code" || baseline === "code";
}

/**
 * Whether this pairing needs the sibling catalog's renders adopted.
 *
 * Asked separately from {@link needsRenders} because the sibling's images come off another
 * catalog's daemon: a reader who never names it must never cost it a request.
 */
export function needsParallel(lane: Lane, baseline: Baseline): boolean {
    return lane === "parallel" || baseline === "parallel";
}

export function laneOf(value: string | null | undefined): Lane {
    return value === "design" || value === "parallel" ? value : "code";
}

export function baselineOf(value: string | null | undefined): Baseline {
    return value === "design" || value === "parallel" || value === "code"
        ? value
        : "off";
}

/**
 * Whether [value] is a baseline the sheet can actually be scored against right now.
 *
 * Two rules, both about the reader rather than about the scorer. A source cannot be its own
 * baseline — the answer is zero by construction, and offering it would be a control whose only
 * outcome is "0.0%" in every slot. And a sibling this page carries no renders for is not a
 * comparison this server can make, so the option is not offered at all rather than offered and
 * dashed.
 */
export function allowsBaseline(
    lane: Lane,
    value: Baseline,
    hasParallel: boolean,
): boolean {
    if (value === "off") return true;
    if (value === lane) return false;
    return value !== "parallel" || hasParallel;
}

/**
 * The baseline to hold after the reader changes what the sheet SHOWS.
 *
 * Flipping onto the source you were scoring against reads as a SWAP — "now show me that one" — so
 * the baseline takes the lane just vacated rather than falling to `off`. It is the same pair seen
 * from the other side and the number does not move; dropping to `off` instead would blank every
 * badge on the one gesture most likely to mean "and how far is it, from here?".
 *
 * Nothing else moves: a baseline that is still legal is left exactly where the reader put it.
 */
export function baselineAfterLane(
    previous: Lane,
    next: Lane,
    baseline: Baseline,
    hasParallel: boolean,
): Baseline {
    if (allowsBaseline(next, baseline, hasParallel)) return baseline;
    if (baseline === "off") return "off";
    return allowsBaseline(next, previous, hasParallel) ? previous : "off";
}

/**
 * What the coverage filter does when it is switched on.
 *
 * A coverage filter with nothing to draw on is a no-op the reader cannot see, so asking for it turns
 * the resting marks on. Unchecking leaves them on: it was an explicit state to arrive at, and
 * silently repainting the sheet plain would read as the filter having broken something.
 */
export function outlinesAfterUnlinked(
    unlinkedOn: boolean,
    outlinesOn: boolean,
): boolean {
    return unlinkedOn ? true : outlinesOn;
}

/**
 * Whether an overlay is taken out of the tab order and the accessibility tree.
 *
 * CSS alone cannot do this: `opacity: 0` + `pointer-events: none` still leaves a control focusable,
 * so a keyboard user could tab onto an invisible rectangle — no focus ring, no indication of where
 * they are.
 *
 * Keyed on the GAP, not on "unlinked": the filter shows components with no code behind them, and the
 * sheet's private furniture and variant-set containers are neither.
 */
export function isInert(unlinkedOnly: boolean, hasGap: boolean): boolean {
    return unlinkedOnly && !hasGap;
}

/**
 * The class that puts EVERY diff badge on the sheet at once.
 *
 * The diff axis's resting state is one badge — wherever the reader is pointing or focused — because
 * forty red pills over a specimen sheet hide the drawing they are judging
 * (`docs/design/COMPARE_NAVIGATION.md`, F5). This is the deliberate look at all of them, and it is
 * held rather than latched: it is the gesture for "which one is worst?", which is a question you
 * ask for a second and then go back to reading.
 *
 * `serve.css` owns what it does; `<cp-design-page>` owns when it is on.
 */
export const DIFF_ALL_CLASS = "cp-page-diff-all";

/**
 * Whether the sheet is showing every badge.
 *
 * Gated on the baseline as well as on the gesture: the control is only held-able while something is
 * actually being scored, and a stuck `held` with the diff axis off would otherwise arm a class that
 * paints badges the moment a baseline is picked — a sheet that lights up covered in pills for no
 * reason the reader can connect to anything they did.
 */
export function showsEveryBadge(baseline: Baseline, held: boolean): boolean {
    return baseline !== "off" && held;
}

/**
 * The key a settled score is remembered under.
 *
 * A number is about a PAIR, not about a node, so the pair is in the key: flipping the baseline from
 * Figma to the sibling asks a different question of the same slot, and a cache keyed on the node
 * alone would answer the new question with the old number. Re-entering a pairing already scored
 * stays free, which is what the cache is for.
 */
export function scoreKey(
    lane: Lane,
    baseline: Baseline,
    nodeId: string,
): string {
    return `${lane} ${baseline} ${nodeId}`;
}

/**
 * What a sheet's URL should carry for its four controls, as a plain map.
 *
 * Only departures from the page's defaults are named. A sheet opens on the code lane with no
 * baseline and both filters off, so writing those out would put four redundant parameters on every
 * link someone copies — and the empty map is what `cpUrlState` turns into the clean URL a visitor
 * arrived with. What IS written is the state a refresh used to lose: which drawing the sheet is
 * showing, what it is being scored against, and whether the outlines and the unlinked-only filter
 * are on.
 */
export function pageParams(state: {
    lane: Lane;
    baseline: Baseline;
    outlines: boolean;
    unlinked: boolean;
}): Record<string, string | null> {
    return {
        lane: state.lane === "code" ? null : state.lane,
        baseline: state.baseline === "off" ? null : state.baseline,
        outlines: state.outlines ? "1" : null,
        unlinked: state.unlinked ? "1" : null,
    };
}

/**
 * The state a URL asks for, resolved against what this sheet can actually offer.
 *
 * A baseline the lane forbids (its own source) or that this page carries no renders for is dropped
 * rather than honoured, on `allowsBaseline`'s reasoning: a stale link must not put the sheet into a
 * pairing that does not exist. The unlinked filter implies the outlines it filters, exactly as
 * pressing it does (`outlinesAfterUnlinked`).
 */
export function pageStateFrom(
    params: {
        lane: string | null;
        baseline: string | null;
        outlines: string | null;
        unlinked: string | null;
    },
    hasParallel: boolean,
): { lane: Lane; baseline: Baseline; outlines: boolean; unlinked: boolean } {
    const lane = laneOf(params.lane);
    const asked = baselineOf(params.baseline);
    const baseline = allowsBaseline(lane, asked, hasParallel) ? asked : "off";
    const unlinked = params.unlinked === "1";
    return {
        lane,
        baseline,
        outlines: outlinesAfterUnlinked(unlinked, params.outlines === "1"),
        unlinked,
    };
}
