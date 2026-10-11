// The rules behind `<cp-viewer-drawers>`, as pure functions over the viewport band, the stored
// preference and the server's markup, so they are unit-tested without a DOM. Three bands:
//
//   phone   (≤ 640px)  drawers are MODAL bottom sheets over the preview
//   middle  (641–1099) inline columns, nav hidden by default
//   wide    (≥ 1100px) inline columns, nav shown by default
//
// so `isWide` is not `!isMobile`.

/** Which viewport band the page is in. Both false is the middle band. */
export interface Viewport {
    /** `(max-width: 640px)` — drawers are bottom sheets here. */
    mobile: boolean;
    /** `(min-width: 1100px)` — the component list is shown by default here. */
    wide: boolean;
}

/** A stored fold preference: `"1"` open, `"0"` closed, `null` never expressed. */
export type FoldPref = string | null;

/**
 * The component nav's resting state: always closed on a phone (a sheet is never a resting state);
 * otherwise a stored choice, else the CSS default (shown wide, hidden middle). Resolved into an
 * explicit class because the markup carries none, and reading the class would mistake the wide
 * default for closed.
 */
export function resolveNavOpen(viewport: Viewport, pref: FoldPref): boolean {
    if (viewport.mobile) return false;
    if (pref !== null) return pref === "1";
    return viewport.wide;
}

/**
 * The overrides drawer's resting state: closed on a phone so the preview leads; otherwise a stored
 * choice, else `serverDefault` (the markup's `cp-controls-open`).
 */
export function resolveControlsOpen(
    viewport: Viewport,
    pref: FoldPref,
    serverDefault: boolean,
): boolean {
    if (viewport.mobile) return false;
    if (pref !== null) return pref === "1";
    return serverDefault;
}

/**
 * Whether a drawer toggle is remembered. Never on a phone: the sheets are modal and close each
 * other, so a stored state would cover the next page or record a choice never made. In-page folds
 * remember at every width.
 */
export function shouldPersistDrawer(viewport: Viewport): boolean {
    return !viewport.mobile;
}

/**
 * Opening one sheet closes the other, on a phone only, so they never stack over the preview.
 * Returns the class to close, or null when both may stay open (any width above the phone).
 */
export function drawerToClose(
    viewport: Viewport,
    opening: DrawerClass,
): DrawerClass | null {
    if (!viewport.mobile) return null;
    return opening === "cp-nav-open" ? "cp-controls-open" : "cp-nav-open";
}

/** The two drawer state classes, as they appear on `.cp-viewer`. */
export type DrawerClass = "cp-nav-open" | "cp-controls-open";

/** The toggle button that drives each drawer. */
export function toggleIdFor(drawer: DrawerClass): string {
    return drawer === "cp-nav-open" ? "cp-nav-toggle" : "cp-controls-toggle";
}

/**
 * Per-catalog storage key, like `cp-theme:<catalog>`: one origin serves many catalogs, so an
 * unscoped key would leak folds across catalogs.
 */
export function foldKey(scope: string, id: string): string {
    return `cp-fold:${scope}.${id}`;
}
