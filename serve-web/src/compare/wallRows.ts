// Which rows the wall shows, and in what order.

/** What the filter needs to know about one row, read off its server-written attributes. */
export interface RowFacts {
    /** Lower-cased haystack of everything searchable about the row. */
    hay: string;
    /**
     * The preview ids behind this row, resolved by the caller through the page's alias table
     * (`compare/aliases.ts`).
     */
    previewIds: string;
    /** The component this row belongs to, for the `?component=` narrow. */
    componentId: string;
    /** Whether the current format can pair this row at all. */
    hasFormat: boolean;
}

/**
 * Whether a row survives the filter: four narrows, all must pass. `?preview=` (one exact variant)
 * and `?component=` (every variant of a component) are arrival scopes that compose with the search
 * box (`docs/design/COMPARE_NAVIGATION.md`, F4). Matched case-insensitively on ids, not display
 * labels.
 */
export function keepRow(
    row: RowFacts,
    query: string,
    preview: string,
    component = "",
): boolean {
    if (!row.hasFormat) return false;
    const previewIds = row.previewIds
        .toLowerCase()
        .split(/\s+/)
        .filter(Boolean);
    const needle = query.trim().toLowerCase();
    // The typed query matches the row's text or one of its preview ids (now resolved from the alias
    // table, see `docs/design/COMPARE_NAVIGATION.md`, F2).
    if (
        needle &&
        !row.hay.includes(needle) &&
        !previewIds.some((id) => id.includes(needle))
    )
        return false;
    const wanted = preview.trim().toLowerCase();
    if (wanted && !previewIds.includes(wanted)) return false;
    const scope = component.trim().toLowerCase();
    if (scope && row.componentId.trim().toLowerCase() !== scope) return false;
    return true;
}

/** The count under the search box, pluralised. */
export function countLabel(visible: number): string {
    return `${visible} ${visible === 1 ? "comparison" : "comparisons"}`;
}

/**
 * Worst first. An unscorable row (`-1`) leads, since an unmeasured pair is the one nobody is
 * checking.
 */
export function byWorstFirst(a: number, b: number): number {
    return a - b;
}

/** The score attribute's value as a number, with the unscored/failed sentinel. */
export function scoreOf(raw: string | null): number {
    const value = parseFloat(raw ?? "");
    return Number.isNaN(value) ? -1 : value;
}

/**
 * A published score from `data-match-<variant>`, or null. Null (not `-1`) because a missing
 * published score is not a finding, while `-1` means this browser failed to measure.
 */
export function bakedScoreOf(raw: string | null): number | null {
    const value = parseFloat(raw ?? "");
    return Number.isFinite(value) ? value : null;
}

/**
 * Worst first among rows with a published score; the rest sort last in served order. The seed order
 * before this browser measures anything; afterwards the wall re-sorts with {@link byWorstFirst}.
 */
export function byWorstKnownFirst(a: number | null, b: number | null): number {
    if (a === null) return b === null ? 0 : 1;
    if (b === null) return -1;
    return a - b;
}
