// Writing a selection into the `compose-parity-locator/v1` block. The Kotlin writer
// (`ServeIssueReport`) emits the block; this fills the part known only after a click. Both must
// agree byte for byte (the block is canonical bytes, and the indexing producer refuses
// non-canonical fields); `test/reportLocator.test.ts` checks this module against the shared fixture
// the Kotlin writer and JS parser use. Invariants are enforced where the rectangle is made, like
// `ServeIssueReport.Bounds`, since a refused rectangle silently drops the issue from the index.

/** The only plane `v1` accepts. See D1: both tag-index producers publish render pixels. */
export const RENDER_PIXELS = "render-pixels";

/** A selected region, in the render's own pixel space. */
export interface Bounds {
    x: number;
    y: number;
    width: number;
    height: number;
}

/** What the page has chosen: a tagged element, a dragged region, or both. */
export interface Selection {
    /** The `testTag` verbatim — no trimming anywhere in the chain. */
    element?: string;
    bounds?: Bounds;
}

/**
 * `element` as a JSON string, so a tag can't become syntax: the block is line-oriented and a
 * newline or fence delimiter in a `testTag` could inject fields or end the block. Quoting also
 * preserves leading/trailing whitespace.
 */
export function canonicalElement(element: string): string {
    return JSON.stringify(element);
}

/**
 * Canonical bounds JSON in code-point key order (height < space < width < x < y), written out since
 * the keys are fixed.
 */
export function canonicalBounds(bounds: Bounds): string {
    return (
        `{"height":${bounds.height},"space":"${RENDER_PIXELS}",` +
        `"width":${bounds.width},"x":${bounds.x},"y":${bounds.y}}`
    );
}

/**
 * Whether a rectangle is acceptable to `v1`. The origin may be negative (a tagged node can extend
 * beyond the render root, and the tag index emits signed coordinates). The extent must be positive
 * and every coordinate an integer; drag selections are converted from display pixels before this.
 */
export function usableBounds(bounds: Bounds | undefined): bounds is Bounds {
    if (!bounds) return false;
    const all = [bounds.x, bounds.y, bounds.width, bounds.height];
    if (!all.every((n) => Number.isInteger(n))) return false;
    return bounds.width >= 1 && bounds.height >= 1;
}

/**
 * The `element:` / `bounds:` lines a selection contributes, each newline-terminated.
 *
 * Empty for no selection, which is what makes the substitution below reproduce the block the server
 * would have written on its own. An unusable rectangle contributes nothing rather than an invalid
 * line — a report that names its element and no region is a real, useful report; one carrying a
 * rectangle the producer refuses is not a report at all.
 */
export function selectionLines(selection: Selection): string {
    let out = "";
    // Only an EMPTY tag is dropped. `"item"` and `" item "` are different identities to a tag index
    // and normalising here would point the acceptance at the wrong one — or at none.
    if (selection.element)
        out += `element: ${canonicalElement(selection.element)}\n`;
    if (usableBounds(selection.bounds))
        out += `bounds: ${canonicalBounds(selection.bounds)}\n`;
    return out;
}

/** The placeholder the server leaves in the template's locator block for the lines above. */
export const SELECTION_PLACEHOLDER = "{{selection}}";

/**
 * [template] with the selection placeholder line replaced by [selectionLines]. Matched as a whole
 * line, since catalog-authored values earlier in the block could end with the placeholder text. The
 * line is consumed with its newline, so an empty selection yields exactly the block the server
 * writes without one.
 */
export function fillSelection(template: string, selection: Selection): string {
    const lines = template.split("\n");
    const at = lines.indexOf(SELECTION_PLACEHOLDER);
    if (at < 0) return template;
    const filled = selectionLines(selection);
    // `selectionLines` is newline-TERMINATED, so splice in its lines and drop the trailing empty
    // piece; an empty selection splices nothing and removes the placeholder line entirely.
    lines.splice(at, 1, ...(filled ? filled.split("\n").slice(0, -1) : []));
    return lines.join("\n");
}

/**
 * The placeholder for the locator's `overrides:` value (`ServeIssueReport.OVERRIDES_PLACEHOLDER`).
 * Filled from live control state in the same pass as `{{render}}`, so identity and pixels agree.
 */
export const OVERRIDES_PLACEHOLDER = "{{overrides}}";
const OVERRIDES_PLACEHOLDER_LINE = `overrides: ${OVERRIDES_PLACEHOLDER}`;

/**
 * [template] with its `overrides: {{overrides}}` line rewritten from [overrides], matched as a
 * whole line anchored on the `overrides: ` key (other values are catalog-authored). Templates
 * without the line (where the server knew the overrides) are returned unchanged.
 */
export function fillOverrides(
    template: string,
    overrides: Record<string, string>,
): string {
    const lines = template.split("\n");
    const at = lines.indexOf(OVERRIDES_PLACEHOLDER_LINE);
    if (at < 0) return template;
    lines[at] = `overrides: ${canonicalOverrides(overrides)}`;
    return lines.join("\n");
}

// Writing a whole block from nothing, for the comparison wall's multi-row picker, where rows are
// ticked after serving. A third engine on `compose-parity-locator/v1`, pinned to the same shared
// fixture (`test/reportLocator.test.ts`).

/** The fence both delimiters carry; kept in step with `ServeIssueReport.LOCATOR_FENCE`. */
export const LOCATOR_FENCE = "compose-parity-locator/v1";

/**
 * The placeholder line a pickable page's template carries
 * (`ServeIssueReport.LOCATORS_PLACEHOLDER`).
 */
export const LOCATORS_PLACEHOLDER = "{{locators}}";

/** One comparison, as the block names it. */
export interface Locator {
    repository: string;
    system: string;
    componentId: string;
    previewId: string;
    referenceId: string;
    variant: string;
    overrides?: Record<string, string>;
    revision?: string | null;
    element?: string;
    bounds?: Bounds;
}

/**
 * Compare by code point, unlike `Array.prototype.sort` (UTF-16 code units), which orders astral
 * keys before high-BMP ones, unlike the Kotlin writer. Covered by the shared fixture's
 * `astral-override-keys` case.
 */
function compareCodePoints(a: string, b: string): number {
    const left = [...a];
    const right = [...b];
    const shared = Math.min(left.length, right.length);
    for (let i = 0; i < shared; i++) {
        const one = left[i].codePointAt(0) ?? 0;
        const two = right[i].codePointAt(0) ?? 0;
        if (one !== two) return one - two;
    }
    return left.length - right.length;
}

/** Overrides as canonical JSON: code-point key order, and `{}` when there are none. */
export function canonicalOverrides(
    overrides: Record<string, string> = {},
): string {
    const keys = Object.keys(overrides).sort(compareCodePoints);
    return `{${keys
        .map(
            (key) => `${JSON.stringify(key)}:${JSON.stringify(overrides[key])}`,
        )
        .join(",")}}`;
}

/**
 * The block for one comparison, newline-terminated, byte-identical to
 * `ServeIssueReport.locatorBlock`. Field order is part of the format: blocks are compared unparsed.
 */
export function locatorBlock(locator: Locator): string {
    let out = "```" + LOCATOR_FENCE + "\n";
    out += `repository: ${locator.repository}\n`;
    out += `system: ${locator.system}\n`;
    out += `component: ${locator.componentId}\n`;
    out += `preview: ${locator.previewId}\n`;
    out += `reference: ${locator.referenceId}\n`;
    out += `variant: ${locator.variant}\n`;
    out += `overrides: ${canonicalOverrides(locator.overrides)}\n`;
    out += selectionLines({
        element: locator.element,
        bounds: locator.bounds,
    });
    if (locator.revision) out += `revision: ${locator.revision}\n`;
    out += "```\n";
    return out;
}

/**
 * The `variant:` line: the preview id's tail with its separator swapped, ported from
 * `ServeIssueReport.variantFor`.
 */
export function variantOf(previewId: string): string {
    const at = previewId.indexOf("__");
    return at < 0
        ? ""
        : previewId
              .slice(at + 2)
              .split("__")
              .join("/");
}

/**
 * [template] with its `{{locators}}` line replaced by [blocks], or removed when there are none.
 * Whole-line matching as in [fillSelection]; removal reproduces the server's body with nothing to
 * fill.
 */
export function fillLocators(template: string, blocks: string[]): string {
    const lines = template.split("\n");
    const at = lines.indexOf(LOCATORS_PLACEHOLDER);
    if (at < 0) return template;
    // The blank line before the first fence is written here, so an empty selection matches the
    // server's body exactly.
    const filled = blocks.length ? "\n" + blocks.join("") : "";
    lines.splice(at, 1, ...(filled ? filled.split("\n").slice(0, -1) : []));
    return lines.join("\n");
}

/**
 * [body] with every `compose-parity-locator/v1` block and its separating blank line removed, for a
 * page that must not file a locator now (an interactive lane, whose pixels `data-cp-src` doesn't
 * describe). The result is byte-identical to a body written without a locator.
 */
export function withoutLocators(body: string): string {
    const lines = body.split("\n");
    const out: string[] = [];
    let inside = false;
    for (const line of lines) {
        if (!inside && line === "```" + LOCATOR_FENCE) {
            inside = true;
            // Drop the blank line the block was separated from the prose by, if this writer put
            // one there. Only one, and only when it is the last thing kept.
            if (out.length && out[out.length - 1] === "") out.pop();
            continue;
        }
        if (inside) {
            if (line === "```") inside = false;
            continue;
        }
        out.push(line);
    }
    return out.join("\n");
}
