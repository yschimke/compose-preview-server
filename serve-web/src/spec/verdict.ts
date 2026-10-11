// What the spec lane says about the pair on screen. The readout and the identity row's design-spec
// chip are decided together so they can't disagree: the chip's score is baked at publish, so the
// lane overwrites it with a live measurement while active and restores the published one on exit.

// Shared rather than restated: the parity page decides a pair is worth opening on the same number
// this lane then judges it by, and the two disagreeing reads as a broken page.
import { GEOMETRY_REPORT_THRESHOLD } from "../compare/thresholds.js";

export { GEOMETRY_REPORT_THRESHOLD };

/** How close counts as which. Mirrors `ServeWeb.specMatchBand` and the export driver's `matchBand`. */
export type MatchBand = "match" | "close" | "off";

/**
 * Bands calibrated to the content-measured score (`scorer/planes.ts`): on wear-m3-catalog's
 * published pairs scores span 4–100% with a median of 91, and pairs below 85 are visible
 * divergences.
 */
export function matchBand(percent: number): MatchBand {
    if (percent >= 95) return "match";
    if (percent >= 85) return "close";
    return "off";
}

/** The chip's label while the lane is live: the component's name, plus what it currently scores. */
export function chipText(name: string, percent: number): string {
    return `${name} ${percent.toFixed(1)}%`;
}

/**
 * The readout under the view buttons: match % is structural ("how alike"), changed-pixel % literal
 * ("how much moved"); together they distinguish a uniform shift from a misplaced element.
 */
export function readout(
    percent: number,
    changedPercent: number,
    geometry: number,
): string {
    const drift =
        geometry >= GEOMETRY_REPORT_THRESHOLD
            ? ` · ${geometry.toFixed(1)}% proportion difference`
            : "";
    return `${percent.toFixed(1)}% match · ${changedPercent.toFixed(2)}% pixels differ${drift}`;
}

/** Changed pixels as a percentage of the frame, guarding the empty frame. */
export function changedPercentOf(
    changed: number,
    width: number,
    height: number,
): number {
    const pixels = width * height;
    return pixels ? (changed * 100) / pixels : 0;
}

/**
 * Said instead of a match score when the stage's render is not the one the spec describes. A spec
 * is imported once at the catalog baseline (default theme, knob defaults, no overrides), so a score
 * across an overridden render measures the override, not the component. The changed-pixel count is
 * still reported, explicitly not as a verdict.
 */
export function offBaselineReadout(
    changedPercent: number,
    counterpart = "the imported spec",
): string {
    return (
        `${changedPercent.toFixed(2)}% pixels differ · ${counterpart} is baseline-only, ` +
        `so this is not a match score — clear the overrides to compare`
    );
}

/**
 * The noun for the other side in [offBaselineReadout]: a sibling catalog's render is equally
 * incomparable once overridden, so only the wording changes.
 */
export function counterpartName(label: string): string {
    const name = label.trim();
    return name ? `${name}'s render` : "the imported spec";
}

/** Said in place of a number when the pair cannot be compared at all. */
export const UNAVAILABLE = "Comparison unavailable";

/** Said while a comparison is in flight. */
export const COMPARING = "comparing…";
