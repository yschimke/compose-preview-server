// The component-nav filter's rule, as a pure function over plain rows.
//
// Small, but it has three exceptions in it — the current preview is pinned, the pinned "current"
// block counts toward whether anything is showing, and an empty query means everything — and each
// one is a line of `if` that a screenshot of a filtered list cannot tell you is right. Held here
// they are a table.

/** One filterable row: its searchable text, and whether it is the preview being viewed. */
export interface NavRow {
    /** `data-search` on the row, matched case-insensitively. */
    haystack: string;
    /** Rows carrying `aria-current` are the preview on screen. */
    current: boolean;
}

export interface NavFilterResult {
    /** Per row, in the order given: whether it stays visible. */
    keep: boolean[];
    /** How many rows are showing, counting the pinned current-component block. */
    shown: number;
    /** Whether the "nothing matched" message should be shown. */
    empty: boolean;
}

/**
 * @param rows the filterable sibling list
 * @param query raw input text; trimmed and lowercased here
 * @param hasCurrentBlock whether `.cp-nav-current` pins the active component above the list, which
 *   counts as something showing — otherwise filtering to only the pinned block would claim nothing
 *   matched while the reader is looking at a match.
 */
export function filterNav(
    rows: NavRow[],
    query: string,
    hasCurrentBlock: boolean,
): NavFilterResult {
    const needle = query.trim().toLowerCase();
    let shown = hasCurrentBlock ? 1 : 0;
    const keep = rows.map((row) => {
        // The preview you are looking at never filters itself away — losing it would make the
        // filter feel like a navigation.
        const kept =
            needle === "" ||
            row.current ||
            row.haystack.toLowerCase().includes(needle);
        if (kept) shown++;
        return kept;
    });
    return { keep, shown, empty: shown === 0 };
}

/**
 * One row of the drawer list in DOM order — a heading, or an item and whether [filterNav] kept it.
 *
 * The drawer lists a sectioned catalog under the same section and group headings its landing tree
 * publishes (#252), as FLAT siblings of the rows rather than as nested lists, so the filter still
 * decides one row at a time. What flat siblings cost is this: a heading has to be told when the
 * rows it heads have all gone, or a filter that matches nothing under "Buttons" leaves the word
 * "Buttons" standing over the next group's rows.
 */
export type NavListRow =
    { kind: "section" | "group" } | { kind: "item"; kept: boolean };

/**
 * Per row, in the order given: whether it stays visible. Items as the filter decided; a heading
 * only while a kept item still sits under it — up to the next heading of its own level, which is
 * where its span ends.
 */
export function keepNavRows(rows: NavListRow[]): boolean[] {
    const keep = rows.map((row) => row.kind === "item" && row.kept);
    // Backwards, because a heading's span is what FOLLOWS it: one pass carries "something is still
    // showing" up to the heading that owns it, and resets there.
    let underGroup = false;
    let underSection = false;
    for (let i = rows.length - 1; i >= 0; i--) {
        const row = rows[i];
        if (row.kind === "item") {
            if (row.kept) {
                underGroup = true;
                underSection = true;
            }
        } else if (row.kind === "group") {
            keep[i] = underGroup;
            underGroup = false;
        } else {
            keep[i] = underSection;
            underSection = false;
            // A section heading ends the group above it too — its groups are all behind us.
            underGroup = false;
        }
    }
    return keep;
}
