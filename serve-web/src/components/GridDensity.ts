// Thumbnail size — an S / M / L choice for a catalog landing's card grid.
//
// One card size never suits both reasons to open a catalog. Looking for something wants the whole
// catalog on one screen, so the eye can scan it; looking AT something wants a thumbnail big enough
// to read its text and spacing without opening the viewer. M is the grid the server has always
// drawn; S trades the detail for overview and L the overview for detail.
//
// Per-visitor taste, not shared state, so it lives in `localStorage` (the call `chrome/stars.ts`
// and `chrome/recent.ts` make) and never in the URL: a link someone shares must not resize the
// recipient's grid. One key for every catalog on the host — a visitor who likes small cards likes
// them everywhere.
//
// Entirely additive. The server emits none of this markup: without the bundle, or with storage
// blocked, the visitor gets the M grid the server rendered, and a write that fails keeps the pick
// for this page view and nothing worse. The choice is an attribute on `<html>` rather than on
// `#cp-grid`, because the Starred row (`chrome/stars.ts`) is a sibling of the grid that is rebuilt
// on every star and should resize with it; `serve.css` reads `html[data-cp-density]`.

export const DENSITY_KEY = "cp-density.v1";

export type Density = "s" | "m" | "l";

/** M is the server's own grid, so it is also what an absent or unreadable choice means. */
export const DEFAULT_DENSITY: Density = "m";

/** [label] is drawn, [name] is announced, [title] is the hover hint. */
const CHOICES: ReadonlyArray<{
    value: Density;
    label: string;
    name: string;
    title: string;
}> = [
    {
        value: "s",
        label: "S",
        name: "Small",
        title: "Small thumbnails — the whole catalog at a glance",
    },
    { value: "m", label: "M", name: "Medium", title: "Medium thumbnails" },
    {
        value: "l",
        label: "L",
        name: "Large",
        title: "Large thumbnails — enough to read the detail",
    },
];

const isDensity = (value: unknown): value is Density =>
    value === "s" || value === "m" || value === "l";

/**
 * A pick that could not be written — private mode, a full quota, storage blocked outright. Held for
 * the rest of this page view so a remount (the phone toolbar moves the row) still shows it.
 */
let unsaved: Density | null = null;

/** The remembered size — a missing, blocked or malformed entry is the default. */
export function readDensity(): Density {
    if (unsaved) return unsaved;
    try {
        const raw = localStorage.getItem(DENSITY_KEY);
        return isDensity(raw) ? raw : DEFAULT_DENSITY;
    } catch {
        return DEFAULT_DENSITY;
    }
}

/** Remember [density]; the default is stored as no entry, so the store only holds a real choice. */
export function writeDensity(density: Density): void {
    try {
        if (density === DEFAULT_DENSITY) localStorage.removeItem(DENSITY_KEY);
        else localStorage.setItem(DENSITY_KEY, density);
        unsaved = null;
    } catch {
        unsaved = density;
    }
}

/** Apply [density] to the page. M removes the attribute, leaving exactly the served stylesheet. */
export function applyDensity(density: Density): void {
    const root = document.documentElement;
    if (density === DEFAULT_DENSITY) root.removeAttribute("data-cp-density");
    else root.setAttribute("data-cp-density", density);
}

/**
 * Mount the control on a catalog landing, and apply the remembered size.
 *
 * Only where there is a preview grid (`#cp-grid` holding catalog cards, not the front door's
 * catalog list) and the row of toggles the server already puts beside the heading or the filter —
 * the control joins the Theme pill and `⋯` menu at the head of that row. (`serve.css` hides it on a
 * phone, whose toolbar row is already full and whose grid is its own size.) Idempotent: a second
 * call finds its own group and only re-applies.
 *
 * Native radios, so the group gets the platform's keyboard model for free — Tab enters on the
 * checked option, arrows move and select — and a screen reader announces "Small, radio button,
 * 1 of 3" inside the "Thumbnail size" group. The inputs are visually hidden; the labels draw the
 * segmented track.
 */
export function installGridDensity(): HTMLElement | null {
    const grid = document.getElementById("cp-grid");
    const toggles = document.querySelector(".cp-head-toggles");
    if (!grid || !toggles || grid.classList.contains("cp-syslist")) return null;
    const density = readDensity();
    applyDensity(density);
    const existing = toggles.querySelector<HTMLElement>(".cp-density");
    if (existing) return existing;

    const group = document.createElement("div");
    group.className = "cp-density";
    group.setAttribute("role", "radiogroup");
    group.setAttribute("aria-label", "Thumbnail size");
    for (const { value, label, name, title } of CHOICES) {
        const option = document.createElement("label");
        option.className = "cp-density-opt";
        option.title = title;
        const input = document.createElement("input");
        input.type = "radio";
        input.name = "cp-density";
        input.value = value;
        input.className = "cp-visually-hidden";
        input.checked = value === density;
        input.setAttribute("aria-label", name);
        const text = document.createElement("span");
        text.textContent = label;
        text.setAttribute("aria-hidden", "true");
        option.append(input, text);
        group.append(option);
    }
    group.addEventListener("change", (event) => {
        const value = (event.target as HTMLInputElement | null)?.value;
        if (!isDensity(value)) return;
        writeDensity(value);
        applyDensity(value);
    });
    toggles.prepend(group);
    return group;
}

/** Test seam: forget a pick held for this page view. */
export function resetDensityForTest(): void {
    unsaved = null;
}
