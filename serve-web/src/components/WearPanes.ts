// "Wear panes" — each preview also shown in approximate Wear watch screens.
//
// A library design (a card, a tile, a widget body) is drawn on its own canvas, which says nothing
// about how it reads on a wrist. This lays the SAME PNG into the two Wear widget containers a host
// offers — Small (200 × 60 dp) and Large (200 × 112 dp) — on a round and a square watch screen, so
// a reader can judge it at the size it will actually be shown. It is drawn the way a widget host
// draws a container: 26 dp corners, filled `rgb(39, 36, 48)`, 8 dp of padding, on a black screen,
// with the render fitted (`object-fit: contain`) into the content box.
//
// An approximation, and deliberately only HTML and CSS around the existing PNGs: no new render, no
// device frame, no clip of the widget to the screen's curve beyond what `overflow: hidden` does.
// The dp sizes are fractions of a nominal 225 dp screen (`serve.css`, `.cp-wear-screen`), so the
// whole set scales with the space it has, down to a phone's card.
//
// Opt-in and off by default. Per-visitor taste, so `localStorage` (one key for every catalog on the
// host, like `GridDensity.ts`), never the URL. With the bundle missing, or storage blocked, the
// page is exactly what the server drew; a write that fails keeps the choice for this page view.
// It applies to every preview the page lists — the catalog grid's cards and the viewer's stage —
// with no notion of which ones are "Wear": the reader turns it on to look, and off again.

export const WEAR_PANES_KEY = "cp-wear-panes.v1";

/** The four panes, in reading order: round before square, Small before Large. */
export const WEAR_PANES: ReadonlyArray<{
    shape: "round" | "square";
    size: "small" | "large";
    caption: string;
}> = [
    { shape: "round", size: "small", caption: "Round · Small" },
    { shape: "round", size: "large", caption: "Round · Large" },
    { shape: "square", size: "small", caption: "Square · Small" },
    { shape: "square", size: "large", caption: "Square · Large" },
];

const LABEL = "Show each preview in approximate Wear watch panes";
const PANES = "cp-wear-panes";

/** A choice that could not be written, held for this page view. */
let unsaved: boolean | null = null;

export function readWearPanes(): boolean {
    if (unsaved !== null) return unsaved;
    try {
        return localStorage.getItem(WEAR_PANES_KEY) === "1";
    } catch {
        return false;
    }
}

/** Off is stored as no entry, so the store only ever holds a real choice. */
export function writeWearPanes(on: boolean): void {
    try {
        if (on) localStorage.setItem(WEAR_PANES_KEY, "1");
        else localStorage.removeItem(WEAR_PANES_KEY);
        unsaved = null;
    } catch {
        unsaved = on;
    }
}

/**
 * The previews on this page, each with where its panes go: a catalog card's thumbnail (panes
 * after its `.cp-imgwrap`, inside the card), and the viewer's snapshot (panes at the end of the
 * stage, which `serve.css` wraps onto their own row). Nav thumbnails and the panes' own images are
 * not previews.
 */
interface Target {
    img: HTMLImageElement;
    /** The element whose direct child the panes are. */
    host: Element;
    /** Put [panes] where they belong in [host]. */
    place(panes: HTMLElement): void;
}

function targets(): Target[] {
    const found: Target[] = [];
    for (const img of document.querySelectorAll<HTMLImageElement>(
        ".cp-card:not(.cp-sys) .cp-imgwrap > img, .cp-card:not(.cp-sys) .cp-imgwrap > .cp-crop > img",
    )) {
        // A cropped thumbnail sits in a `.cp-crop` window; its pane shows the whole PNG.
        const wrap = img.closest(".cp-imgwrap")!;
        found.push({
            img,
            host: wrap.parentElement!,
            place: (panes) => {
                if (wrap.nextElementSibling !== panes) wrap.after(panes);
            },
        });
    }
    const stage = document.querySelector<HTMLImageElement>(
        ".cp-stage > img#cp-img",
    );
    // The stage gains children after the panes (the grid overlay and its toggle are absolutely
    // positioned), so anywhere inside it after the image will do.
    if (stage)
        found.push({
            img: stage,
            host: stage.parentElement!,
            place: (panes) => {
                if (panes.parentElement !== stage.parentElement)
                    stage.parentElement!.append(panes);
            },
        });
    return found;
}

function pane(shape: string, size: string, caption: string): HTMLElement {
    const figure = document.createElement("figure");
    figure.className = "cp-wear-pane";
    figure.dataset.shape = shape;
    figure.dataset.size = size;
    const screen = document.createElement("div");
    screen.className = "cp-wear-screen";
    const container = document.createElement("div");
    container.className = "cp-wear-container";
    const img = document.createElement("img");
    img.loading = "lazy";
    img.decoding = "async";
    container.append(img);
    screen.append(container);
    const label = document.createElement("figcaption");
    label.textContent = caption;
    figure.append(screen, label);
    return figure;
}

/** Keep one preview's panes present and pointing at its current pixels. */
function syncOne({ img, host, place }: Target): void {
    let panes = host.querySelector<HTMLElement>(`:scope > .${PANES}`);
    if (!panes) {
        panes = document.createElement("div");
        panes.className = PANES;
        panes.setAttribute("role", "group");
        for (const { shape, size, caption } of WEAR_PANES)
            panes.append(pane(shape, size, caption));
    }
    place(panes);
    const name = img.getAttribute("alt") || "Preview";
    const group = `${name} in approximate Wear panes`;
    if (panes.getAttribute("aria-label") !== group)
        panes.setAttribute("aria-label", group);
    // `src` is what the grid re-points on a theme change and the viewer on every render; mirror
    // the attribute rather than `currentSrc` so a pane follows before the new image has loaded.
    const src = img.getAttribute("src");
    panes.hidden = !src;
    panes
        .querySelectorAll<HTMLElement>(".cp-wear-pane")
        .forEach((figure, i) => {
            const shown = figure.querySelector("img")!;
            const alt = `${name} — ${WEAR_PANES[i].caption}`;
            if (shown.alt !== alt) shown.alt = alt;
            if (src && shown.getAttribute("src") !== src)
                shown.setAttribute("src", src);
        });
}

/** Show or remove every preview's panes. Idempotent, so an observer may call it freely. */
export function applyWearPanes(on: boolean): void {
    const root = document.documentElement;
    if (on) root.setAttribute("data-cp-wear-panes", "on");
    else root.removeAttribute("data-cp-wear-panes");
    if (!on) {
        document.querySelectorAll(`.${PANES}`).forEach((el) => el.remove());
        return;
    }
    for (const target of targets()) syncOne(target);
}

let observer: MutationObserver | null = null;

/**
 * While on, follow the page: a card added (the Starred row is rebuilt on every star), an image
 * re-pointed (a theme pick, a new render). Mutations inside the panes themselves are ignored, so
 * the sync's own writes do not wake it again.
 */
function follow(on: boolean): void {
    observer?.disconnect();
    observer = null;
    if (!on || typeof MutationObserver !== "function") return;
    let queued = false;
    observer = new MutationObserver((records) => {
        if (queued) return;
        if (records.every((r) => (r.target as Element).closest?.(`.${PANES}`)))
            return;
        queued = true;
        queueMicrotask(() => {
            queued = false;
            applyWearPanes(readWearPanes());
        });
    });
    observer.observe(document.body, {
        subtree: true,
        childList: true,
        attributes: true,
        attributeFilter: ["src", "alt"],
    });
}

/**
 * Mount the toggle and apply the remembered choice, on a page that lists previews.
 *
 * The toggle is a `.cp-bg-btn` `aria-pressed` chip beside Transparent, the other "how is each
 * preview drawn" choice: in the catalog's `⋯` panel, or the viewer's View group. A page without a
 * Transparent control (a component-browser landing) gets it at the end of its toggle row.
 * Idempotent: a second call finds its own button and only re-applies.
 */
export function installWearPanes(): HTMLButtonElement | null {
    const grid = document.getElementById("cp-grid");
    const isCatalog = !!grid && !grid.classList.contains("cp-syslist");
    if (!isCatalog && !document.querySelector(".cp-stage > img#cp-img"))
        return null;
    const on = readWearPanes();
    applyWearPanes(on);
    follow(on);
    const existing =
        document.querySelector<HTMLButtonElement>(".cp-wear-panes-btn");
    if (existing) return existing;

    const anchor = document.querySelector(
        ".cp-actions-panel > cp-bg-toggle, .cp-stage-view-row > cp-bg-toggle",
    );
    const row = anchor
        ? null
        : (document.querySelector(".cp-stage-view-row") ??
          document.querySelector(".cp-head-toggles"));
    if (!anchor && !row) return null;

    const button = document.createElement("button");
    button.type = "button";
    button.className = "cp-bg-btn cp-wear-panes-btn";
    button.textContent = "Wear panes";
    button.title = LABEL;
    button.setAttribute("aria-label", LABEL);
    button.setAttribute("aria-pressed", String(on));
    button.addEventListener("click", () => {
        const next = button.getAttribute("aria-pressed") !== "true";
        writeWearPanes(next);
        button.setAttribute("aria-pressed", String(next));
        applyWearPanes(next);
        follow(next);
    });
    if (anchor) anchor.after(button);
    else row!.append(button);
    return button;
}

/** Test seam: forget a choice held for this page view, and stop following the page. */
export function resetWearPanesForTest(): void {
    unsaved = null;
    observer?.disconnect();
    observer = null;
}
