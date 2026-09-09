// The comparison page's alias table: every preview id a row can be narrowed by, read once.
//
// The server publishes one `<script type="application/json" id="cp-compare-aliases">` for the whole
// page rather than an id list per row. It had to: both walls fold a component's variants into one
// row, and writing each row's fold inline came to 19,188 mentions of 538 distinct ids on
// `remote-m3` — 967 KB of `data-preview-ids`, most of another 1.08 MB of `data-hay`, on a page that
// took two minutes to arrive (`docs/design/COMPARE_NAVIGATION.md`, F2).
//
// The table carries two facts and the CALLER picks which it needs, because the difference between
// them is a rule the server owns:
//
// - `cards` — each comparison card's full id list.
// - `rowed` — every id that has a row of its own.
//
// A design reference names one exact state/props mapping, so that variant is kept out of the fold
// and gets its own row; it must therefore not also alias onto its siblings' rows, or filtering by
// it would match all of them. The comparison wall subtracts `rowed`; the Remote Compose lane wall,
// whose rows are one per preview rather than one per mapping, does not.
//
// DOM-free apart from the one read: the parse is done once per page and handed to `keepRow` as
// plain strings.

/** What the page published, already split per card. */
export interface AliasTable {
    /** Card key → every preview id on that card. */
    cards: Map<string, string[]>;
    /** Every preview id that has a row of its own. */
    rowed: Set<string>;
}

const EMPTY: AliasTable = { cards: new Map(), rowed: new Set() };

const ids = (value: unknown): string[] =>
    typeof value === "string" ? value.split(/\s+/).filter(Boolean) : [];

/**
 * Read the table out of the document, or an empty one.
 *
 * Empty rather than throwing on anything unexpected — a missing element, malformed JSON, a shape
 * from a different version. The table is an OPTIMISATION of a filter: without it every row still
 * matches on its own ids, so a page that cannot read it narrows slightly less rather than failing
 * to draw. That also makes a cached page served before the table existed keep working.
 */
export function readAliasTable(root: ParentNode = document): AliasTable {
    const node = root.querySelector("#cp-compare-aliases");
    if (!node?.textContent) return EMPTY;
    let parsed: unknown;
    try {
        parsed = JSON.parse(node.textContent);
    } catch {
        return EMPTY;
    }
    if (!parsed || typeof parsed !== "object") return EMPTY;
    const { cards, rowed } = parsed as {
        cards?: Record<string, unknown>;
        rowed?: unknown;
    };
    const table: AliasTable = {
        cards: new Map(),
        rowed: new Set(ids(rowed)),
    };
    for (const [key, value] of Object.entries(cards ?? {})) {
        table.cards.set(key, ids(value));
    }
    return table;
}

/**
 * The ids that select a row, given the row's own ids and the card it claimed.
 *
 * `foldedOnly` is the comparison wall: an id with a row of its own selects that row and not this
 * one. The Remote Compose lane wall passes false, because its rows are one per preview.
 *
 * A row that claimed no card (its card's aliases belong to an earlier row) resolves to its own ids
 * alone, which is exactly what the old per-row attribute said by being short.
 */
export function aliasesFor(
    table: AliasTable,
    ownIds: string,
    card: string | null,
    foldedOnly: boolean,
): string[] {
    const own = ids(ownIds);
    if (!card) return own;
    const all = table.cards.get(card) ?? [];
    const folded = foldedOnly ? all.filter((id) => !table.rowed.has(id)) : all;
    // A Set because a card's list and the row's own ids overlap by construction — the row's
    // variants are on the card — and a duplicate would only cost the filter a second comparison.
    return [...new Set([...own, ...folded])];
}
