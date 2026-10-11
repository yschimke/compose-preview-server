// Stars — a visitor's own shortlist of catalogs and previews, pinned to the top of the page.
//
// Someone who comes back to preview.coo.ee comes back for the same two or three catalogs and the
// same handful of components inside them, and every visit started by scrolling past the rest to
// find them. A star is a bookmark that lives where the thing is: on the front door's catalog cards,
// beside a catalog's heading, and beside a preview's title — and whatever is starred is repeated in
// a "Starred" row at the top of the page that lists it.
//
// Per-visitor taste, not shared state, so it lives in `localStorage` (the `cp-grp.` drawer memory
// is the same call) and never in the URL: a link someone shares must not carry their shortlist.
// The one exception is asked for by name — the Starred row's Share action (`starsLink.ts`) copies a
// link whose fragment carries the row, and opening one offers to ADD those stars, never to replace.
// One host serves many catalogs from one origin, so previews are keyed by the catalog they belong
// to — the path segment the server mounts it under, which is the same on the landing and the
// viewer.
//
// Entirely additive. The server emits none of this markup, so a visitor without JavaScript, or with
// storage blocked, sees exactly the page the server rendered; a write that fails leaves the star
// toggled for this page view and nothing worse. The Starred row holds COPIES of the cards, so the
// grids below — their sections, counts, anchors and filters — are untouched by it.

import { copyText } from "../viewer/copyId.js";
import {
    type SharedStars,
    clearSharedStarsFragment,
    decodeSharedStars,
    sharedStarsUrl,
    sharedStarsValue,
    showSharedStarsOffer,
} from "./starsLink.js";

export const STARS_KEY = "cp-stars.v1";

/** What is starred: catalog ids, and preview ids per catalog id. Most recently starred first. */
export interface Stars {
    catalogs: string[];
    previews: Record<string, string[]>;
}

const empty = (): Stars => ({ catalogs: [], previews: {} });

const strings = (value: unknown): string[] =>
    Array.isArray(value)
        ? value.filter((v): v is string => typeof v === "string")
        : [];

/** The stored stars, or none — a missing, blocked or malformed entry is the same empty shortlist. */
export function readStars(): Stars {
    if (unsaved) return unsaved;
    let raw: string | null = null;
    try {
        raw = localStorage.getItem(STARS_KEY);
    } catch {
        return empty();
    }
    if (!raw) return empty();
    try {
        const parsed = JSON.parse(raw) as {
            catalogs?: unknown;
            previews?: unknown;
        };
        const previews: Record<string, string[]> = {};
        if (parsed.previews && typeof parsed.previews === "object")
            for (const [system, ids] of Object.entries(parsed.previews))
                if (strings(ids).length) previews[system] = strings(ids);
        return { catalogs: strings(parsed.catalogs), previews };
    } catch {
        return empty();
    }
}

/**
 * Stars that could not be written — private mode, a full quota, storage blocked outright. Held for
 * the rest of this page view, so a click still toggles: without it every repaint would read the
 * unchanged store back and undo the click the moment it landed.
 */
let unsaved: Stars | null = null;

function writeStars(stars: Stars): void {
    try {
        localStorage.setItem(STARS_KEY, JSON.stringify(stars));
        unsaved = null;
    } catch {
        unsaved = stars;
    }
}

/** Star or unstar [id] in [list], newest first. Returns the new list. */
function toggled(list: string[], id: string, on: boolean): string[] {
    const rest = list.filter((x) => x !== id);
    return on ? [id, ...rest] : rest;
}

export function setCatalogStarred(system: string, on: boolean): void {
    const stars = readStars();
    stars.catalogs = toggled(stars.catalogs, system, on);
    writeStars(stars);
}

/** Star or unstar every id in [ids] — a card can stand for a light and a dark render of one thing. */
export function setPreviewStarred(
    system: string,
    ids: string[],
    on: boolean,
): void {
    const stars = readStars();
    let list = stars.previews[system] ?? [];
    for (const id of on ? ids.slice().reverse() : ids)
        list = toggled(list, id, on);
    if (list.length) stars.previews[system] = list;
    else delete stars.previews[system];
    writeStars(stars);
}

/**
 * Add [shared] to the visitor's stars: catalogs globally, previews under [system]. Merge, never
 * replace — what the visitor had stays, in their order, and only ids they did not already have are
 * added, newest-first like any other star (in the link's own order). Returns how many were new.
 */
export function mergeStars(system: string, shared: SharedStars): number {
    const stars = readStars();
    const newCatalogs = shared.catalogs.filter(
        (id) => !stars.catalogs.includes(id),
    );
    const had = stars.previews[system] ?? [];
    const newPreviews = shared.previews.filter((id) => !had.includes(id));
    const added = newCatalogs.length + newPreviews.length;
    if (!added) return 0;
    stars.catalogs = [...newCatalogs, ...stars.catalogs];
    if (newPreviews.length) stars.previews[system] = [...newPreviews, ...had];
    writeStars(stars);
    return added;
}

/**
 * The catalog this page belongs to: the path ahead of the viewer's `/p/` segment, or the whole path
 * on a landing. A root-mounted legacy catalog has no such path and is told apart by `?session=`
 * instead — the same order `ServeHttpServer.selectedSessionId` resolves them in — so two of those
 * keep separate stars. A top-level site serves its one catalog at `/`, which keys as `""`, and is
 * already kept apart from every other by its origin.
 */
export function systemFromPath(pathname: string, search = ""): string {
    const parts = pathname.split("/").filter((p) => p.length > 0);
    const at = parts.indexOf("p");
    const own = at >= 0 ? parts.slice(0, at) : parts;
    let path: string;
    try {
        path = own.map(decodeURIComponent).join("/");
    } catch {
        path = own.join("/");
    }
    if (path) return path;
    return new URLSearchParams(search).get("session") ?? "";
}

/** The preview ids a catalog-grid card shows: both renders of a light/dark pair, else its link. */
export function cardPreviewIds(card: Element): string[] {
    const pair = [
        card.getAttribute("data-l-id"),
        card.getAttribute("data-d-id"),
    ].filter((id): id is string => !!id);
    if (pair.length) return pair;
    const href = card.getAttribute("href") ?? "";
    const match = /\/p\/([^/?#]+)/.exec(href);
    if (!match) return [];
    try {
        return [decodeURIComponent(match[1])];
    } catch {
        return [match[1]];
    }
}

/** The id a card's star adds: the render the card links to right now. */
function shownPreviewId(card: Element): string | null {
    const href = card.getAttribute("href") ?? "";
    const match = /\/p\/([^/?#]+)/.exec(href);
    if (match) {
        try {
            return decodeURIComponent(match[1]);
        } catch {
            return match[1];
        }
    }
    return cardPreviewIds(card)[0] ?? null;
}

const SYNC = "cp-star-sync";

function paint(button: HTMLButtonElement, on: boolean, name: string): void {
    button.setAttribute("aria-pressed", on ? "true" : "false");
    const label = `${on ? "Unstar" : "Star"} ${name}`;
    button.setAttribute("aria-label", label);
    button.title = label;
    button.firstElementChild!.textContent = on ? "★" : "☆";
}

/**
 * A star toggle. Inside a card it is the card's one other click target, so it keeps the click to
 * itself — the same footing as the broken-image Retry button (`previewImages.ts`).
 */
function starButton(
    name: string,
    isOn: () => boolean,
    set: (on: boolean) => void,
    extraClass = "",
): HTMLButtonElement {
    const button = document.createElement("button");
    button.type = "button";
    button.className = `cp-star${extraClass ? ` ${extraClass}` : ""}`;
    const glyph = document.createElement("span");
    glyph.setAttribute("aria-hidden", "true");
    button.append(glyph);
    paint(button, isOn(), name);
    button.addEventListener("click", (event) => {
        event.preventDefault();
        event.stopPropagation();
        set(!isOn());
        refresh();
    });
    // Every star on the page repaints from storage when any one of them changes — the card and its
    // copy in the Starred row are two buttons for one thing.
    button.addEventListener(SYNC, () => paint(button, isOn(), name));
    return button;
}

function cardName(card: Element): string {
    return (
        card.getAttribute("aria-label") ||
        card.querySelector(".cp-sys-title, .cp-label")?.textContent?.trim() ||
        "this"
    );
}

/** A copy of [card] for the Starred row: no ids (the anchors stay unique), no old star. */
function copyOf(card: Element): Element {
    const copy = card.cloneNode(true) as Element;
    copy.removeAttribute("id");
    copy.querySelectorAll("[id]").forEach((el) => el.removeAttribute("id"));
    // A broken-image Retry panel is wired to the original's image, not this one.
    copy.querySelectorAll(".cp-star, .cp-image-error").forEach((el) =>
        el.remove(),
    );
    // Nor does a copy inherit the live-preview affordances `<cp-catalog-live>` gave the original:
    // its press-and-hold listeners are on the original, so a copy that kept the "hold for live"
    // hint would advertise an interaction that just follows the link.
    copy.classList.remove(
        "cp-card-livable",
        "cp-card-live",
        "cp-card-pressing",
    );
    copy.querySelectorAll(
        ".cp-live-hint, .cp-live-chip, .cp-live-error, .cp-card-canvas",
    ).forEach((el) => el.remove());
    copy.querySelectorAll<HTMLElement>("img").forEach((img) =>
        img.style.removeProperty("visibility"),
    );
    copy.setAttribute("data-cp-starred-copy", "1");
    return copy;
}

const COPIED_MS = 1400;

/**
 * The Starred row's Share action: copies a link to this page carrying [shared]'s stars in its
 * fragment. Confirms the way the viewer's id chip does — the label reads "Link copied ✓" for a
 * moment, and a polite live region says so for a screen reader.
 */
function shareButton(what: string, shared: () => SharedStars): HTMLElement {
    const wrap = document.createElement("span");
    const button = document.createElement("button");
    button.type = "button";
    button.className = "cp-starred-share";
    button.textContent = "Share";
    button.setAttribute("aria-label", `Copy a link to these starred ${what}`);
    button.title = `Copy a link that offers these starred ${what} to whoever opens it`;
    const live = document.createElement("span");
    live.className = "cp-visually-hidden";
    live.setAttribute("role", "status");
    live.setAttribute("aria-live", "polite");
    let timer: ReturnType<typeof setTimeout> | undefined;
    button.addEventListener("click", () => {
        void copyText(sharedStarsUrl(shared())).then((ok) => {
            // Pin the width so the shorter/longer confirmation does not shove the heading about.
            if (!timer) button.style.minWidth = `${button.offsetWidth}px`;
            button.textContent = ok ? "Link copied ✓" : "Copy failed";
            live.textContent = ok ? "Link copied" : "Copy failed";
            clearTimeout(timer);
            timer = setTimeout(() => {
                button.textContent = "Share";
                button.style.minWidth = "";
                live.textContent = "";
                timer = undefined;
            }, COPIED_MS);
        });
    });
    wrap.append(button, live);
    return wrap;
}

// ---------------------------------------------------------------- front door

function homeCards(): HTMLElement[] {
    return Array.from(
        document.querySelectorAll<HTMLElement>(
            ".cp-card.cp-sys[data-cp-system]:not(.cp-sys-loading):not([data-cp-starred-copy])",
        ),
    );
}

function wireHomeCard(card: HTMLElement): void {
    const system = card.getAttribute("data-cp-system")!;
    const button = starButton(
        cardName(card),
        () => readStars().catalogs.includes(system),
        (on) => setCatalogStarred(system, on),
    );
    card.append(button);
}

function renderHomeStarred(): void {
    document.getElementById("cp-starred")?.remove();
    const starred = readStars().catalogs;
    const bySystem = new Map(
        homeCards().map((c) => [c.getAttribute("data-cp-system")!, c]),
    );
    const cards = starred
        .map((s) => bySystem.get(s))
        .filter((c): c is HTMLElement => !!c);
    if (!cards.length) return;
    const anchor = document.querySelector(
        ".cp-section-band, .cp-section-title",
    );
    if (!anchor?.parentNode) return;
    const section = document.createElement("section");
    section.id = "cp-starred";
    section.className = "cp-starred";
    section.setAttribute("aria-label", "Starred catalogs");
    // The same title + grid pair the server's own sections are, so the front door's search hides
    // this heading when every card under it is filtered out, exactly as it does theirs.
    const title = document.createElement("div");
    title.className = "cp-section-title";
    const head = document.createElement("h2");
    head.className = "cp-head";
    head.textContent = "Starred";
    // Shares the catalogs the row shows, in its order — not stale ids for catalogs this host no
    // longer serves, which the row already leaves out.
    title.append(
        head,
        shareButton("catalogs", () => ({
            catalogs: cards.map((c) => c.getAttribute("data-cp-system")!),
            previews: [],
        })),
    );
    const grid = document.createElement("div");
    grid.className = "cp-grid cp-syslist";
    for (const card of cards) {
        const copy = copyOf(card) as HTMLElement;
        copy.hidden = false;
        wireHomeCard(copy);
        grid.append(copy);
    }
    section.append(title, grid);
    anchor.parentNode.insertBefore(section, anchor);
    // The front door's search re-reads every `.cp-sys` on each pass, copies included — but a row
    // built while a query is already typed has not had a pass yet, so ask for one.
    const search = document.getElementById(
        "cp-browser-catalog-search",
    ) as HTMLInputElement | null;
    if (search?.value.trim()) search.dispatchEvent(new Event("input"));
}

// ---------------------------------------------------------------- catalog landing

function landingCards(): HTMLElement[] {
    return Array.from(
        document.querySelectorAll<HTMLElement>("#cp-grid a.cp-card[href]"),
    ).filter((card) => cardPreviewIds(card).length > 0);
}

function wireLandingCard(card: HTMLElement, system: string): void {
    const button = starButton(
        cardName(card),
        () => {
            const starred = readStars().previews[system] ?? [];
            return cardPreviewIds(card).some((id) => starred.includes(id));
        },
        (on) => {
            if (on) {
                const id = shownPreviewId(card);
                if (id) setPreviewStarred(system, [id], true);
            } else setPreviewStarred(system, cardPreviewIds(card), false);
        },
        "cp-star--card",
    );
    card.append(button);
}

function renderLandingStarred(system: string): void {
    document.getElementById("cp-starred")?.remove();
    const starred = readStars().previews[system] ?? [];
    if (!starred.length) return;
    const cards = landingCards();
    const picked: HTMLElement[] = [];
    for (const id of starred) {
        const card = cards.find((c) => cardPreviewIds(c).includes(id));
        if (card && !picked.includes(card)) picked.push(card);
    }
    if (!picked.length) return;
    const grid = document.getElementById("cp-grid");
    const anchor = document.querySelector(".cp-catalog-body") ?? grid;
    if (!anchor?.parentNode) return;
    const section = document.createElement("section");
    section.id = "cp-starred";
    section.className = "cp-starred cp-subgroup";
    section.setAttribute("aria-label", "Starred previews");
    section.style.setProperty("--cp-n", String(picked.length));
    const headRow = document.createElement("div");
    headRow.className = "cp-starred-head";
    const head = document.createElement("h2");
    head.className = "cp-group-head";
    head.textContent = "Starred";
    headRow.append(
        head,
        shareButton("previews", () => ({
            catalogs: [],
            previews: starred.filter((id) =>
                picked.some((card) => cardPreviewIds(card).includes(id)),
            ),
        })),
    );
    const row = document.createElement("div");
    row.className = "cp-cards";
    const pairs: Array<[HTMLElement, HTMLElement]> = [];
    for (const card of picked) {
        const copy = copyOf(card) as HTMLElement;
        wireLandingCard(copy, system);
        row.append(copy);
        pairs.push([card, copy]);
    }
    section.append(headRow, row);
    anchor.parentNode.insertBefore(section, anchor);
    followFilter(section, pairs);
}

/**
 * Keeps the Starred row honest about the landing's filter.
 *
 * The server's filter script captured its card list before this row existed, so it never visits a
 * copy. It does mark each ORIGINAL `hidden`, though, and while a query is typed that mark means
 * exactly "does not match" (every tab is searched then). With no query the mark only means "in
 * another tab", which must not empty a row that sits above every tab — so the copy follows its
 * original only while the field holds a query, and the row folds away when nothing in it matches.
 */
let filterObserver: MutationObserver | null = null;

function followFilter(
    section: HTMLElement,
    pairs: Array<[HTMLElement, HTMLElement]>,
): void {
    filterObserver?.disconnect();
    const input = document.getElementById(
        "cp-search",
    ) as HTMLInputElement | null;
    const sync = (): void => {
        const searching = !!input?.value.trim();
        let shown = 0;
        for (const [card, copy] of pairs) {
            copy.hidden = searching && card.hidden;
            if (!copy.hidden) shown++;
        }
        section.hidden = shown === 0;
    };
    sync();
    if (typeof MutationObserver === "undefined") return;
    filterObserver = new MutationObserver(sync);
    for (const [card] of pairs)
        filterObserver.observe(card, {
            attributes: true,
            attributeFilter: ["hidden"],
        });
}

// ---------------------------------------------------------------- page wiring

/** Which page this is, decided once at install: what gets a star, and where the Starred row goes. */
let page:
    | { kind: "home" }
    | { kind: "landing"; system: string }
    | { kind: "viewer" }
    | null = null;

/** Repaint every star on the page, and rebuild the Starred row, from storage. */
function refresh(): void {
    if (page?.kind === "home") renderHomeStarred();
    if (page?.kind === "landing") renderLandingStarred(page.system);
    document
        .querySelectorAll<HTMLButtonElement>("button.cp-star")
        .forEach((button) => button.dispatchEvent(new Event(SYNC)));
}

function install(): void {
    const isHome =
        homeCards().length > 0 && !!document.querySelector(".cp-syslist");
    const landingHead = document.querySelector<HTMLElement>(
        ".cp-catalog-title .cp-catalog-head",
    );
    const viewer = document.querySelector<HTMLElement>(
        ".cp-viewer[data-preview-id]",
    );
    const system = systemFromPath(location.pathname, location.search);

    if (isHome) {
        page = { kind: "home" };
        for (const card of homeCards()) wireHomeCard(card);
    } else if (landingHead && document.getElementById("cp-grid")) {
        page = { kind: "landing", system };
        const name = landingHead.textContent?.trim() || "this catalog";
        const button = starButton(
            name,
            () => readStars().catalogs.includes(system),
            (on) => setCatalogStarred(system, on),
            "cp-star--head",
        );
        landingHead.after(button);
        for (const card of landingCards()) wireLandingCard(card, system);
    } else if (viewer) {
        page = { kind: "viewer" };
        const id = viewer.getAttribute("data-preview-id")!;
        const title = document.querySelector<HTMLElement>(".cp-preview-title");
        if (!title) return;
        const name = title.textContent?.trim() || "this preview";
        const button = starButton(
            name,
            () => (readStars().previews[system] ?? []).includes(id),
            (on) => setPreviewStarred(system, [id], on),
            "cp-star--head",
        );
        title.after(button);
    } else return;

    refresh();
    offerSharedStars();
    // A star added in another tab shows up here too.
    window.addEventListener("storage", (event) => {
        if (event.key !== STARS_KEY) return;
        unsaved = null;
        refresh();
    });
}

/**
 * When this page was opened from a Share link, offer its stars. Only what this page can show is
 * offered — catalogs on the front door, this catalog's previews on its landing — and only what the
 * visitor does not already have, so the count in the prompt is what Add will visibly put in the
 * row. A link with nothing left to offer, or one that does not parse, just has its fragment cleared.
 */
function offerSharedStars(): void {
    const value = sharedStarsValue(location.hash);
    if (value === null) return;
    const shared = decodeSharedStars(value);
    const stars = readStars();
    const offer: SharedStars = { catalogs: [], previews: [] };
    let system = "";
    if (shared && page?.kind === "home") {
        const present = new Set(
            Array.from(
                document.querySelectorAll(".cp-sys[data-cp-system]"),
                (c) => c.getAttribute("data-cp-system"),
            ),
        );
        offer.catalogs = shared.catalogs.filter(
            (id) => present.has(id) && !stars.catalogs.includes(id),
        );
    } else if (shared && page?.kind === "landing") {
        system = page.system;
        const had = stars.previews[system] ?? [];
        const cards = landingCards();
        offer.previews = shared.previews.filter(
            (id) =>
                !had.includes(id) &&
                cards.some((c) => cardPreviewIds(c).includes(id)),
        );
    }
    if (!offer.catalogs.length && !offer.previews.length) {
        clearSharedStarsFragment();
        return;
    }
    showSharedStarsOffer(offer, () => {
        mergeStars(system, offer);
        refresh();
    });
}

/** Wire stars into whichever of the front door, a catalog landing or a viewer this page is. */
export function installStars(): void {
    if (document.readyState === "loading")
        document.addEventListener("DOMContentLoaded", install, { once: true });
    else install();
}

/** Test hook: forget which page was wired, so a suite can install against a fresh document. */
export function resetStarsForTest(): void {
    page = null;
    unsaved = null;
    document.getElementById("cp-stars-offer")?.remove();
    filterObserver?.disconnect();
    filterObserver = null;
}
