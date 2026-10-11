// What the reference page says about the pair, and what it hands the report form.

import type { ComparisonResult } from "../compare/detail.js";
import { GEOMETRY_REPORT_THRESHOLD } from "../compare/thresholds.js";

/** Changed pixels as a percentage, guarding the frame that never decoded. */
export function changedPercentOf(result: ComparisonResult): number {
    return result.pixels ? (result.changed * 100) / result.pixels : 0;
}

/**
 * The line under the two panels. Structural match ("how alike") and changed pixels ("how much
 * moved") answer different questions; geometry joins only once above rasteriser noise.
 */
export function resultLine(result: ComparisonResult): string {
    const geometry =
        result.geometry >= GEOMETRY_REPORT_THRESHOLD
            ? ` · ${result.geometry.toFixed(1)}% proportion difference`
            : "";
    return (
        `${result.score.toFixed(1)}% structural match · ` +
        `${changedPercentOf(result).toFixed(2)}% pixels changed${geometry}`
    );
}

/** The same measurements as one sentence, for the report body. */
export function rawScores(result: ComparisonResult): string {
    let text =
        `${result.score.toFixed(1)}% structural match; ` +
        `${changedPercentOf(result).toFixed(2)}% pixels changed`;
    if (result.geometry >= GEOMETRY_REPORT_THRESHOLD) {
        text += `; ${result.geometry.toFixed(1)}% proportion difference`;
    }
    return text;
}

/**
 * The render URL a report should quote, without the session token (which would grant readers the
 * reporter's access). Overrides stay so the URL reproduces the frame.
 */
export function reportRenderUrl(actualUrl: string, base: string): string {
    const url = new URL(actualUrl, base);
    url.searchParams.delete("token");
    return url.toString();
}

/**
 * The server's raw-scores row, matched exactly: any other line carrying the placeholder is
 * catalog-authored data. Kept in step with `ServeIssueReport.body` (`reportBodyRows.test.ts` and
 * `ServeWebFixtureTest` fail on drift).
 */
const RAW_SCORES_ROW = "| Raw comparison | `{{rawScores}}` |";

/**
 * The render placeholder as a markdown link destination — the only form `ServeIssueReport.body`
 * emits (`![alt]({{render}})` or `[PNG at these settings]({{render}})`) — so a bare occurrence in
 * catalog text is never rewritten.
 */
const RENDER_DESTINATION = "]({{render}})";

/**
 * Whether [template] has a render link to fill. The comparison wall's template names none, so it is
 * composable without a render URL.
 */
export function needsRender(template: string): boolean {
    return template.includes(RENDER_DESTINATION);
}

/**
 * The report body's render and score placeholders, filled. Page-derived values only reach the
 * form's hidden input, never a navigation sink. A null [scores] drops the score row (matching what
 * the server writes without measurements); the row is removed by exact identity, not by any line
 * containing the placeholder, so catalog-authored text cannot take other rows with it.
 */
/**
 * A render URL with `?bg=auto` (the server-composited stage, `ServeRenderMatte`), mirroring
 * `ServeIssueReport.withStage` so both paths agree. Appends unless a `bg` exists; leaves non-render
 * URLs alone. Needed because renders are transparent and GitHub has no ground for them.
 */
export function withStage(url: string): string {
    if (!url.includes("/render/")) return url;
    const query = url.split("?")[1] ?? "";
    if (query.split("&").some((p) => p.split("=")[0] === "bg")) return url;
    return url + (query ? "&" : "?") + "bg=auto";
}

export function fillReport(
    template: string,
    renderUrl: string,
    scores: string | null,
): string {
    const filled = template.replace(
        RENDER_DESTINATION,
        `](${withStage(renderUrl)})`,
    );
    const lines = filled.split("\n");
    const at = lines.indexOf(RAW_SCORES_ROW);
    // No row means nothing to fill; editing some other line carrying the text would be worse.
    if (at < 0) return filled;
    if (scores === null) lines.splice(at, 1);
    else lines[at] = RAW_SCORES_ROW.replace("{{rawScores}}", scores);
    return lines.join("\n");
}
