// Changed since your last visit — a small accent dot on the cards whose pictures moved.
//
// Someone who comes back to a catalog after a release wants to know what is different, and a grid
// of forty cards that all look roughly familiar does not say. The browser remembers a fingerprint
// of every card it last showed, and on the next visit marks the ones whose fingerprint differs:
// catalog cards on the front door, preview cards on a catalog landing. Visiting a page is what
// "seeing" means, so every visit records what is on it now and the dots clear on the visit after.
//
// The fingerprint is a content hash the server already puts in the markup, never invented here:
//
// - A front-door catalog card's prebaked hero is served from `/hero/<system>/<hash>.png`, the hash
//   of the baked bytes (`ServeHeroImages.bake`). It moves when that catalog's showcase render does.
// - A landing card's prebaked thumbnail carries `?thumb=<hash>` on every URL for its pixels — the
//   `src`, and a light/dark card's `data-l-src` / `data-d-src` (`ServeWeb.renderSrc`,
//   `ServeHeroImages.bakeGridThumb`). It moves when that preview's render does.
//
// A card without one (a hero that could not be baked, a thumbnail not baked yet on this page build)
// has nothing reliable to compare, so it is never marked and its last known hash is kept for a
// later visit. The first visit to a page marks nothing; it only records.
//
// Per-visitor memory, so it lives in `localStorage` like `chrome/stars.ts` and `chrome/recent.ts`,
// bounded so a visitor who browses every catalog cannot grow it without limit. Entirely additive:
// with storage blocked nothing is marked and the page is the one the server rendered.

import { catalogKey } from "./recent.js";
import { cardPreviewIds } from "./stars.js";

export const CHANGED_KEY = "cp-changed.v1";

/** How many catalog landings are remembered, most recently visited first. */
export const CHANGED_LANDING_LIMIT = 12;

/** How many previews of one landing are remembered — far more than any catalog shows. */
export const CHANGED_PREVIEW_LIMIT = 1000;

/** How many front-door catalogs are remembered. */
export const CHANGED_CATALOG_LIMIT = 200;

/** A ceiling on the serialized size, well inside every browser's per-origin quota. */
const MAX_CHARS = 200_000;

export const CHANGED_LABEL = "Changed since your last visit";

/** What was last seen: hero hash per catalog, and thumbnail hash per preview per catalog. */
export interface Seen {
    /** `null` until the front door has been visited once — the "first visit" marker. */
    home: Record<string, string> | null;
    /** Most recently visited landing first. */
    landings: Array<{ system: string; previews: Record<string, string> }>;
}

const empty = (): Seen => ({ home: null, landings: [] });

function hashes(value: unknown): Record<string, string> | null {
    if (!value || typeof value !== "object" || Array.isArray(value))
        return null;
    const out: Record<string, string> = {};
    for (const [k, v] of Object.entries(value))
        if (typeof v === "string" && v) out[k] = v;
    return out;
}

/** What was last seen — a missing, blocked or malformed entry is the same as never having visited. */
export function readSeen(): Seen {
    try {
        const raw = localStorage.getItem(CHANGED_KEY);
        if (!raw) return empty();
        const parsed = JSON.parse(raw) as {
            home?: unknown;
            landings?: unknown;
        };
        const landings: Seen["landings"] = [];
        if (Array.isArray(parsed.landings))
            for (const entry of parsed.landings) {
                const e = entry as { system?: unknown; previews?: unknown };
                const previews = hashes(e?.previews);
                if (typeof e?.system === "string" && previews)
                    landings.push({ system: e.system, previews });
            }
        return { home: hashes(parsed.home), landings };
    } catch {
        return empty();
    }
}

/** Keep at most [limit] entries of [map], in its own order. */
const capped = (map: Record<string, string>, limit: number) =>
    Object.fromEntries(Object.entries(map).slice(0, limit));

function writeSeen(seen: Seen): void {
    const bounded: Seen = {
        home: seen.home && capped(seen.home, CHANGED_CATALOG_LIMIT),
        landings: seen.landings.slice(0, CHANGED_LANDING_LIMIT).map((l) => ({
            system: l.system,
            previews: capped(l.previews, CHANGED_PREVIEW_LIMIT),
        })),
    };
    let json = JSON.stringify(bounded);
    // An unusually large catalog set: forget the landings visited longest ago until it fits.
    while (json.length > MAX_CHARS && bounded.landings.length > 1) {
        bounded.landings.pop();
        json = JSON.stringify(bounded);
    }
    try {
        localStorage.setItem(CHANGED_KEY, json);
    } catch {
        // Private mode or a full quota: this visit is simply not remembered.
    }
}

/**
 * Which of [now] differ from [before], recording [now] over it. Only a key with a hash on both sides
 * can differ; a key missing from [now] keeps its old hash (the card may only lack a baked thumbnail
 * on this page build), and the current keys go first so a later trim drops the stale ones.
 */
export function diffAndMerge(
    before: Record<string, string> | null,
    now: Record<string, string>,
): { changed: Set<string>; merged: Record<string, string> } {
    const changed = new Set<string>();
    if (before)
        for (const [key, hash] of Object.entries(now))
            if (before[key] !== undefined && before[key] !== hash)
                changed.add(key);
    const merged: Record<string, string> = { ...now };
    for (const [key, hash] of Object.entries(before ?? {}))
        if (!(key in merged)) merged[key] = hash;
    return { changed, merged };
}

// ---------------------------------------------------------------- fingerprints from the markup

/** The `?thumb=` content hash on a render URL, or null when this card has no prebaked thumbnail. */
export function thumbHash(src: string | null): string | null {
    if (!src) return null;
    try {
        return new URL(src, "http://x/").searchParams.get("thumb") || null;
    } catch {
        return null;
    }
}

/** The content hash in a front-door hero's `/hero/<system>/<hash>.png`, or null for any other image. */
export function heroHash(src: string | null): string | null {
    const match = /\/hero\/[^/?#]+\/([^/?#]+)\.png(?:[?#]|$)/.exec(src ?? "");
    return match ? match[1] : null;
}

/**
 * Each preview id a landing card shows, with its thumbnail hash. A light/dark card carries both
 * renders' URLs as data, so the render not on screen right now is still compared; a plain card has
 * its one `<img>`. Theme switches rewrite `src` by appending to it, so `?thumb=` survives them.
 */
export function cardHashes(card: Element): Record<string, string> {
    const out: Record<string, string> = {};
    const l = card.getAttribute("data-l-id");
    const d = card.getAttribute("data-d-id");
    if (l || d) {
        const lh = thumbHash(card.getAttribute("data-l-src"));
        const dh = thumbHash(card.getAttribute("data-d-src"));
        if (l && lh) out[l] = lh;
        if (d && dh) out[d] = dh;
        return out;
    }
    const [id] = cardPreviewIds(card);
    const hash = thumbHash(
        card.querySelector(".cp-imgwrap img")?.getAttribute("src") ?? null,
    );
    if (id && hash) out[id] = hash;
    return out;
}

// ---------------------------------------------------------------- the dot

/** The marker. `role="img"` so it reads where it stands; a link card also points at it (below). */
function mark(card: HTMLElement): void {
    if (card.querySelector(":scope > .cp-changed")) return;
    const dot = document.createElement("span");
    dot.className = "cp-changed";
    dot.setAttribute("role", "img");
    dot.setAttribute("aria-label", CHANGED_LABEL);
    dot.title = CHANGED_LABEL;
    card.append(dot);
    card.setAttribute("data-cp-changed", "1");
    // A landing card is a link named by its own `aria-label`, which hides everything inside it from
    // assistive tech — so the change is said as the link's description instead, through one shared
    // element (ids are stripped from the Starred row's copies; this one is outside every card).
    if (card.hasAttribute("aria-label")) {
        describer();
        const ids = (card.getAttribute("aria-describedby") ?? "")
            .split(/\s+/)
            .filter(Boolean);
        if (!ids.includes(DESC_ID))
            card.setAttribute("aria-describedby", [...ids, DESC_ID].join(" "));
    }
}

const DESC_ID = "cp-changed-desc";

function describer(): void {
    if (document.getElementById(DESC_ID)) return;
    const desc = document.createElement("span");
    desc.id = DESC_ID;
    desc.hidden = true;
    desc.textContent = CHANGED_LABEL;
    document.body.append(desc);
}

// ---------------------------------------------------------------- page wiring

/**
 * Every card on the page, the Starred row's copies (`chrome/stars.ts`) included: a copy stands for
 * the same thing and should say the same. Recording reads them too; a copy's hashes are its
 * original's, so it changes nothing.
 */
function homeCards(): HTMLElement[] {
    return Array.from(
        document.querySelectorAll<HTMLElement>(
            ".cp-card.cp-sys[data-cp-system]",
        ),
    );
}

function landingCards(): HTMLElement[] {
    return Array.from(
        document.querySelectorAll<HTMLElement>(
            "#cp-grid a.cp-card[href], #cp-starred a.cp-card[href]",
        ),
    );
}

function installHome(): void {
    const now: Record<string, string> = {};
    const cards = homeCards();
    for (const card of cards) {
        const hash = heroHash(
            card.querySelector(".cp-imgwrap img")?.getAttribute("src") ?? null,
        );
        if (hash) now[card.getAttribute("data-cp-system")!] = hash;
    }
    const seen = readSeen();
    const { changed, merged } = diffAndMerge(seen.home, now);
    for (const card of cards)
        if (changed.has(card.getAttribute("data-cp-system")!)) mark(card);
    writeSeen({ ...seen, home: merged });
}

function installLanding(system: string): void {
    const cards = landingCards();
    const now: Record<string, string> = {};
    for (const card of cards) Object.assign(now, cardHashes(card));
    const seen = readSeen();
    const at = seen.landings.findIndex((l) => l.system === system);
    const before = at >= 0 ? seen.landings[at].previews : null;
    const { changed, merged } = diffAndMerge(before, now);
    for (const card of cards)
        if (Object.keys(cardHashes(card)).some((id) => changed.has(id)))
            mark(card);
    const rest = seen.landings.filter((l) => l.system !== system);
    writeSeen({ ...seen, landings: [{ system, previews: merged }, ...rest] });
}

function install(): void {
    const isHome =
        !!document.querySelector(".cp-card.cp-sys[data-cp-system]") &&
        !!document.querySelector(".cp-syslist");
    if (isHome) return installHome();
    const isLanding =
        !!document.querySelector(".cp-catalog-title .cp-catalog-head") &&
        !!document.getElementById("cp-grid");
    if (isLanding)
        installLanding(catalogKey(location.pathname, location.search));
}

/** Mark what changed since the last visit on the front door or a catalog landing, and record this one. */
export function installChanged(): void {
    if (document.readyState === "loading")
        document.addEventListener("DOMContentLoaded", install, { once: true });
    else install();
}
