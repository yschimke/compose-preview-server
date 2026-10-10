// Recently viewed — the last few previews a visitor opened, offered back on the pages they start from.
//
// The usual return trip is "the thing I was looking at a minute ago": open a preview, go back to
// the catalog or the front door, and then hunt for it again in a grid of forty. The viewer records
// each preview it shows, and the front door and a catalog landing repeat the newest few as a
// compact row of small thumbnails above everything else — on a landing only that catalog's, on the
// front door all of them, each labelled with the catalog it came from.
//
// Per-visitor history, not shared state, so it lives in `localStorage` and never in the URL (the
// same call `chrome/stars.ts` makes). It stores what the row needs to draw itself — label, link,
// thumbnail — rather than re-deriving them from the page, because the front door has no preview
// cards to read them from. Entirely additive: the server emits none of this markup, an empty or
// unreadable history renders nothing, and a write that fails costs only the next visit's row.

export const RECENT_KEY = "cp-recent.v1";

/** How many previews are remembered, across every catalog. A row of small thumbnails, not a log. */
export const RECENT_LIMIT = 8;

/** One remembered preview. [system] is the catalog's mount path, `""` for a top-level site. */
export interface RecentEntry {
    system: string;
    id: string;
    label: string;
    href: string;
    thumb: string;
}

const isEntry = (value: unknown): value is RecentEntry => {
    if (!value || typeof value !== "object") return false;
    const v = value as Record<string, unknown>;
    return (
        typeof v.system === "string" &&
        typeof v.id === "string" &&
        typeof v.label === "string" &&
        typeof v.href === "string" &&
        typeof v.thumb === "string" &&
        v.id.length > 0
    );
};

/** The remembered previews, newest first — a missing, blocked or malformed entry reads as none. */
export function readRecent(): RecentEntry[] {
    try {
        const raw = localStorage.getItem(RECENT_KEY);
        if (!raw) return [];
        const parsed: unknown = JSON.parse(raw);
        return Array.isArray(parsed)
            ? parsed.filter(isEntry).slice(0, RECENT_LIMIT)
            : [];
    } catch {
        return [];
    }
}

function writeRecent(entries: RecentEntry[]): void {
    try {
        if (entries.length)
            localStorage.setItem(RECENT_KEY, JSON.stringify(entries));
        else localStorage.removeItem(RECENT_KEY);
    } catch {
        // Private mode or a full quota: this visit simply is not remembered.
    }
}

/** Put [entry] first, dropping any older visit to the same preview of the same catalog. */
export function recordRecent(entry: RecentEntry): void {
    const rest = readRecent().filter(
        (e) => !(e.system === entry.system && e.id === entry.id),
    );
    writeRecent([entry, ...rest].slice(0, RECENT_LIMIT));
}

/** Forget [system]'s previews, or every catalog's when [system] is omitted. */
export function clearRecent(system?: string): void {
    writeRecent(
        system === undefined
            ? []
            : readRecent().filter((e) => e.system !== system),
    );
}

/**
 * The catalog a page belongs to: the path ahead of the viewer's `/p/` segment, or the whole path on
 * a landing. The server mounts catalogs two ways, and this keys both to the same session id it
 * routes by (`ServeHttpServer.selectedSessionId`): the canonical `/<system>/…` form names it in the
 * path, and the older root-mounted form carries it as `?session=<id>` — without reading that, every
 * legacy session would key as `""` and their histories would mix. A root page with no `?session=`
 * is the default session (or a top-level site, already isolated by its own origin) and keys as `""`.
 */
export function catalogKey(pathname: string, search: string): string {
    const parts = pathname.split("/").filter((p) => p.length > 0);
    const at = parts.indexOf("p");
    const own = at >= 0 ? parts.slice(0, at) : parts;
    let system: string;
    try {
        system = own.map(decodeURIComponent).join("/");
    } catch {
        system = own.join("/");
    }
    return system || new URLSearchParams(search).get("session") || "";
}

/**
 * The routing part of this page's query, which every same-catalog link the server builds carries
 * (`ServeWeb.linkQuery`): `token` on a private host, and `session` on a root-mounted catalog. Only
 * those two — the viewer's own state (`mode=`, theme, overrides) would turn a link back to a
 * preview into a link back to one particular configuration of it.
 */
export function routingQuery(search: string): string {
    const from = new URLSearchParams(search);
    const kept = new URLSearchParams();
    for (const key of ["token", "session"]) {
        const value = from.get(key);
        if (value !== null) kept.set(key, value);
    }
    const query = kept.toString();
    return query ? `?${query}` : "";
}

// ---------------------------------------------------------------- viewer: record

/** The title's own words — a trust badge or other chip inside the heading is not part of the name. */
function titleText(title: Element): string {
    const own = Array.from(title.childNodes)
        .filter((n) => n.nodeType === Node.TEXT_NODE)
        .map((n) => n.textContent ?? "")
        .join("")
        .replace(/\s+/g, " ")
        .trim();
    return own || title.textContent?.trim() || "";
}

/**
 * What the viewer at [viewer] should remember. Link and thumbnail are built from the preview id
 * itself rather than read off the nav drawer: the drawer folds a non-default state, props or size
 * variant into its component's representative and marks THAT row current, and a single-preview
 * session has no drawer at all. Both carry the page's routing query, so a private host's token and
 * a root-mounted catalog's `?session=` survive the trip back.
 */
export function viewerEntry(viewer: Element, loc: Location): RecentEntry {
    const id = viewer.getAttribute("data-preview-id")!;
    const title = document.querySelector(".cp-preview-title");
    const base = loc.pathname.slice(0, loc.pathname.lastIndexOf("/p/") + 1);
    const segment = encodeURIComponent(id);
    const query = routingQuery(loc.search);
    return {
        system: catalogKey(loc.pathname, loc.search),
        id,
        label: (title && titleText(title)) || id,
        href: `${base}p/${segment}${query}`,
        thumb: `${base}render/${segment}.png${query}`,
    };
}

// ---------------------------------------------------------------- front door + landing: show

/** The front door's display name for [system], from its catalog card; else the id itself. */
function catalogName(system: string): string {
    for (const card of document.querySelectorAll(".cp-sys[data-cp-system]"))
        if (card.getAttribute("data-cp-system") === system)
            return (
                card.querySelector(".cp-sys-title")?.textContent?.trim() ||
                system
            );
    return system || "Home";
}

function tile(entry: RecentEntry, withCatalog: boolean): HTMLAnchorElement {
    const link = document.createElement("a");
    link.className = "cp-recent-item";
    link.href = entry.href;
    const from = withCatalog ? catalogName(entry.system) : "";
    link.title = from ? `${entry.label} — ${from}` : entry.label;
    const img = document.createElement("img");
    img.className = "cp-recent-thumb";
    img.src = entry.thumb;
    img.alt = "";
    img.loading = "lazy";
    img.decoding = "async";
    // A preview that no longer renders keeps its label; a broken-image glyph would only be noise.
    img.addEventListener("error", () => (img.style.visibility = "hidden"), {
        once: true,
    });
    const text = document.createElement("span");
    text.className = "cp-recent-text";
    const name = document.createElement("span");
    name.className = "cp-recent-label";
    name.textContent = entry.label;
    text.append(name);
    if (from) {
        const catalog = document.createElement("span");
        catalog.className = "cp-recent-catalog";
        catalog.textContent = from;
        text.append(catalog);
    }
    link.append(img, text);
    return link;
}

/**
 * Draw the row for [entries] before [anchor], or remove it when there is nothing to show. When the
 * Starred row (`chrome/stars.ts`) is already on the page this goes above it, and because Stars
 * always re-inserts its row directly before the server's first section, a re-render on either side
 * keeps the same order: this compact strip first, the bigger starred cards under it.
 */
function render(
    entries: RecentEntry[],
    anchor: Element,
    clear: () => void,
    withCatalog: boolean,
): void {
    document.getElementById("cp-recent")?.remove();
    if (!entries.length || !anchor.parentNode) return;
    const section = document.createElement("section");
    section.id = "cp-recent";
    section.className = "cp-recent";
    section.setAttribute("aria-labelledby", "cp-recent-head");
    const head = document.createElement("div");
    head.className = "cp-recent-head-row";
    const title = document.createElement("h2");
    title.id = "cp-recent-head";
    title.className = "cp-recent-head";
    title.textContent = "Recently viewed";
    const button = document.createElement("button");
    button.type = "button";
    button.className = "cp-recent-clear";
    button.textContent = "Clear";
    button.setAttribute("aria-label", "Clear recently viewed");
    button.addEventListener("click", () => {
        clear();
        section.remove();
    });
    head.append(title, button);
    const list = document.createElement("div");
    list.className = "cp-recent-list";
    for (const entry of entries) list.append(tile(entry, withCatalog));
    section.append(head, list);
    const starred = document.getElementById("cp-starred");
    const before = starred?.parentNode === anchor.parentNode ? starred : anchor;
    anchor.parentNode.insertBefore(section, before);
}

// ---------------------------------------------------------------- page wiring

function install(): void {
    const system = catalogKey(location.pathname, location.search);
    const viewer = document.querySelector(".cp-viewer[data-preview-id]");
    if (viewer) {
        recordRecent(viewerEntry(viewer, location));
        return;
    }
    const isHome =
        !!document.querySelector(".cp-card.cp-sys[data-cp-system]") &&
        !!document.querySelector(".cp-syslist");
    if (isHome) {
        const anchor = document.querySelector(
            ".cp-section-band, .cp-section-title",
        );
        if (anchor) render(readRecent(), anchor, () => clearRecent(), true);
        return;
    }
    const isLanding =
        !!document.querySelector(".cp-catalog-title .cp-catalog-head") &&
        !!document.getElementById("cp-grid");
    if (isLanding) {
        const anchor =
            document.querySelector(".cp-catalog-body") ??
            document.getElementById("cp-grid")!;
        render(
            readRecent().filter((e) => e.system === system),
            anchor,
            () => clearRecent(system),
            false,
        );
    }
}

/** Record the viewer's preview, or show the Recently viewed row on the front door or a landing. */
export function installRecent(): void {
    if (document.readyState === "loading")
        document.addEventListener("DOMContentLoaded", install, { once: true });
    else install();
}
