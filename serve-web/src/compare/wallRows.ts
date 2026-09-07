// Which rows the wall shows, and in what order.

/** What the filter needs to know about one row, read off its server-written attributes. */
export interface RowFacts {
    /** Lower-cased haystack of everything searchable about the row. */
    hay: string;
    /**
     * The preview ids behind this row, for the `?preview=` narrow and the typed search.
     *
     * Resolved by the caller through the page's alias table (`compare/aliases.ts`) rather than read
     * off the row: the ids are published once for the whole page, and which of a card's ids belong
     * to THIS row is a rule the server states there.
     */
    previewIds: string;
    /** The component this row belongs to, for the `?component=` narrow. */
    componentId: string;
    /** Whether the current format can pair this row at all. */
    hasFormat: boolean;
}

/**
 * Whether a row survives the filter.
 *
 * Four independent narrows, all of which must pass. Two of them are SCOPES the reader arrived
 * with rather than typed, and both compose with the search box rather than being overridden by it:
 * someone who arrives scoped and then types is narrowing within that scope, not starting a new
 * search across the catalog.
 *
 * `?preview=` is one exact variant — the viewer's link for the frame on its stage. `?component=` is
 * every variant of a component, which is the scope a reader coming from a component page or from
 * the parity index actually wants: they are not asking about `appcard__ideal__icon__compact`, they
 * are asking about App Card. Without it the wall opened on the whole catalog and had to be filtered
 * by hand from a page that already knew the answer
 * (`docs/design/COMPARE_NAVIGATION.md`, F4).
 *
 * Matched case-insensitively and on the id the row carries, not on its label: a component's display
 * name is prose ("App Card") and its id is not, and a link built from one and matched against the
 * other silently selects nothing.
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
    // The typed query matches the row's TEXT or one of its preview ids. The ids used to be copied
    // into the haystack so this was one test; they are now written once in the page's alias table
    // and resolved onto the row, so the search has to look in both places to keep matching what it
    // always did — typing `appcard__ideal__icon__compact` still finds the row that stands for it.
    // See `docs/design/COMPARE_NAVIGATION.md`, F2.
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
 * Worst first.
 *
 * The wall exists to find what is wrong, so the rows that are wrong have to be the ones on screen
 * without scrolling. A row that could not be scored sorts to `-1` and leads — "we could not measure
 * this" outranks any measured score, because an unmeasured pair is the one nobody is looking at.
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
 * A PUBLISHED score off a row's `data-match-<variant>`, or null where the row carries none.
 *
 * Null rather than {@link scoreOf}'s `-1` because the two absences are opposites. `-1` means "this
 * browser tried to measure the pair and could not", which leads the wall. A missing published score
 * means only that the delivery branch had nothing to say — a catalog baked before the producer
 * existed, a run with no browser, a lane that publishes no score at all — and a row nobody has
 * measured yet is not a finding.
 */
export function bakedScoreOf(raw: string | null): number | null {
    const value = parseFloat(raw ?? "");
    return Number.isFinite(value) ? value : null;
}

/**
 * Worst first among the rows that HAVE a published score, with the rest left where they were.
 *
 * The seed order, used before this browser has measured anything: it puts the catalog's own worst
 * pairs on screen at first paint instead of after a raster decode per row. Unscored rows sort last
 * and keep their served order between them — see {@link bakedScoreOf} for why they are not `-1`.
 * Once every visible row has been measured the wall re-sorts with {@link byWorstFirst}, where an
 * unmeasurable row leads.
 */
export function byWorstKnownFirst(a: number | null, b: number | null): number {
    if (a === null) return b === null ? 0 : 1;
    if (b === null) return -1;
    return a - b;
}
