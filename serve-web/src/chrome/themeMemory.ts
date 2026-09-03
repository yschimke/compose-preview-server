// Where a catalog's theme choice is remembered, and for how long.
//
// `sessionStorage`, not `localStorage`, and that is the whole point of this file existing rather
// than each caller reaching for storage itself.
//
// The choice is TAB state. Somebody comparing an Expressive Dark render in one tab against the
// baked light one in another is doing an ordinary thing, and under a per-origin `localStorage` it
// was impossible: the second tab's pick reached back into the first the moment it navigated, and
// the first tab's next page load silently adopted a theme chosen in a window the reader was no
// longer looking at. `sessionStorage` is scoped to the tab, so a pick follows every navigation,
// reload and Back/Forward WITHIN the tab that made it, reaches no other tab, and is gone when the
// tab closes — which is also what makes a shared or bookmarked link reproducible again, because a
// freshly opened tab starts with no memory at all.
//
// The Page theme SETTING (`cp-page-theme`, whether the chrome follows the previews or the OS) is a
// standing preference rather than page state and stays in `localStorage`; only the theme choice
// itself moved. See `pageTheme.ts`.

/**
 * The remembered choice for [key] (a catalog-scoped `cp-theme:<catalog>`), or `""`.
 *
 * Storage can throw outright — a private window, a browser set to block site data — and never
 * having a remembered theme is survivable in every caller, so the failure is swallowed here rather
 * than at each call site.
 */
export function readThemeMemory(key: string): string {
    if (!key) return "";
    try {
        return sessionStorage.getItem(key) || "";
    } catch {
        return "";
    }
}

/** Remember [value] under [key] for this tab. Silently does nothing when storage is unavailable. */
export function writeThemeMemory(key: string, value: string): void {
    if (!key) return;
    try {
        sessionStorage.setItem(key, value);
    } catch {
        // Storage blocked: the pick applies to the page that made it and is not carried forward.
    }
}
