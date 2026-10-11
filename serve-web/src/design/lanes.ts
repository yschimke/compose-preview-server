// What the sheet shows, and what it is judged against: two orthogonal controls.
//
//   SHOW         - whose picture stands in the design's slots: ours, the sibling's, or the design's
//                  own drawing.
//   DIFF AGAINST - which of the other two it is scored against, or nothing.
//
// Both range over the same three sources, so a pairing reads as one sentence ("showing ours, diffed
// against wear-m3") with both halves named on screen. The show lanes are not composited (no opacity
// slider or blend); the diff axis answers closeness with a number and a map.

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
 * What the stage looks like for one pairing. The first three are keyed on the lane alone: what is
 * scored changes no drawn pixel. `cp-page-swap-on` means a render stands in the slots; which render
 * is `cp-page-parallel-on`'s business.
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
 * Whether this pairing needs the sibling's renders adopted. Separate from {@link needsRenders}
 * because those images come from another catalog's daemon and must cost nothing unless named.
 */
export function needsParallel(lane: Lane, baseline: Baseline): boolean {
    return lane === "parallel" || baseline === "parallel";
}

export function laneOf(value: string | null | undefined): Lane {
    return value === "design" || value === "parallel" ? value : "code";
}

/**
 * The lane this sheet can actually show: like `allowsBaseline`, a `parallel` lane is refused
 * without sibling renders.
 */
export function laneWithin(lane: Lane, hasParallel: boolean): Lane {
    return lane === "parallel" && !hasParallel ? "code" : lane;
}

export function baselineOf(value: string | null | undefined): Baseline {
    return value === "design" || value === "parallel" || value === "code"
        ? value
        : "off";
}

/**
 * Whether [value] is a usable baseline now: not the lane's own source (always 0.0%), and not a
 * sibling this page carries no renders for.
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
 * The baseline after the shown lane changes. Switching onto the current baseline is a swap: the
 * baseline takes the vacated lane, keeping the same pair and number. A still-legal baseline is left
 * alone.
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
 * Turning the coverage filter on also turns outlines on (otherwise it shows nothing); turning it
 * off leaves them on.
 */
export function outlinesAfterUnlinked(
    unlinkedOn: boolean,
    outlinesOn: boolean,
): boolean {
    return unlinkedOn ? true : outlinesOn;
}

/**
 * Whether an overlay is removed from the tab order and accessibility tree (CSS alone leaves it
 * focusable). Keyed on the gap, not "unlinked": private furniture and variant-set containers are
 * neither.
 */
export function isInert(unlinkedOnly: boolean, hasGap: boolean): boolean {
    return unlinkedOnly && !hasGap;
}

/**
 * The class that shows every diff badge at once. The resting state is one badge (where the reader
 * points), since many pills hide the drawing (`docs/design/COMPARE_NAVIGATION.md` F5); this is
 * held, not latched. `serve.css` owns its effect; `<cp-design-page>` owns when it is on.
 */
export const DIFF_ALL_CLASS = "cp-page-diff-all";

/**
 * Whether every badge shows: only while something is scored, so a stuck `held` can't light up the
 * sheet when a baseline is later picked.
 */
export function showsEveryBadge(baseline: Baseline, held: boolean): boolean {
    return baseline !== "off" && held;
}

/**
 * The key a settled score is cached under: the pair, not just the node, so changing the baseline
 * asks a new question.
 */
export function scoreKey(
    lane: Lane,
    baseline: Baseline,
    nodeId: string,
): string {
    return `${lane} ${baseline} ${nodeId}`;
}

/**
 * The URL parameters for the four controls: only departures from the defaults (code lane, no
 * baseline, filters off), so an untouched sheet keeps a clean URL.
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
 * The state a URL asks for, resolved against what this sheet offers. A forbidden or unavailable
 * baseline is dropped; the unlinked filter implies outlines.
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
    // Resolve the lane first, then validate the baseline against it: a stale `?lane=parallel` on an
    // unpaired sheet would otherwise let `code` pass as a baseline opposite a missing lane, scoring
    // every render against itself. Fall back to `code`, where the sheet lands anyway.
    const lane = laneWithin(laneOf(params.lane), hasParallel);
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
