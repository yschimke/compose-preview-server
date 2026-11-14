// Which query parameters the viewer manages, and which it must leave alone.
//
// `window.cpUrlState.sync` DROPS any owned parameter the caller does not supply a value for. That
// makes this list load-bearing in both directions, and neither mistake announces itself:
//
//   - claim a parameter the viewer does not set, and every sync silently deletes it — a session
//     token, a catalog filter, someone else's deep link, gone on the next slider drag;
//   - fail to claim one the viewer DOES set, and a stale value survives after the control that put
//     it there has been turned off, so the address bar describes a page that is not on screen.
//
// The prefixed families are open-ended by design: author knobs and Remote Compose seeds are named
// by the preview, so they cannot be enumerated here.

/** Parameters the viewer writes by name. */
export const URL_STATE_PARAMS: readonly string[] = [
    "device",
    "localeTag",
    "orientation",
    "fontScale",
    "uiMode",
    "themeProvider",
    "focus",
    "gestures",
    "touchOverlay",
    "scroll",
    "mode",
    "svg",
    "zoom",
    "sizeMode",
    "rcPlayer",
    "specView",
    "specSource",
    "motion",
    "widthPx",
    "heightPx",
    "minWidthPx",
    "minHeightPx",
    "maxWidthPx",
    "maxHeightPx",
    "exploded",
    "explodeTilt",
    "explodeSpin",
    "explodeGap",
    "explodeDepth",
    "ambient",
    "ambientBurnIn",
    "ambientLowBit",
    "clockEpochMillis",
    "wfRenderer",
];

/** Watch-face and Wear ambient state parameters written by name rather than `wf.*` prefix. */
export const WATCH_FACE_STATE_PARAMS: readonly string[] = [
    "ambient",
    "ambientBurnIn",
    "ambientLowBit",
    "clockEpochMillis",
];

/** Prefixes whose members are named by the preview rather than by this list. */
export const URL_STATE_PREFIXES: readonly string[] = ["knob.", "rc.", "wf."];

export function ownsUrlParam(name: string): boolean {
    return (
        URL_STATE_PARAMS.includes(name) ||
        URL_STATE_PREFIXES.some((prefix) => name.startsWith(prefix))
    );
}

/** Whether [name] is a watch-face or Wear ambient state override parameter (`ambient*`, `clockEpochMillis`, `wf.*`). */
export function isWatchFaceStateParam(name: string): boolean {
    return WATCH_FACE_STATE_PARAMS.includes(name) || name.startsWith("wf.");
}

/** Normalises a tri-state boolean URL value (`""` / `"true"` / `"false"`) for `<select>` controls. */
export function triStateBoolParam(raw: string | null): string {
    if (raw === null) return "";
    const trimmed = raw.trim();
    if (trimmed === "") return "";
    if (trimmed === "1" || trimmed.toLowerCase() === "true") return "true";
    if (trimmed === "0" || trimmed.toLowerCase() === "false") return "false";
    return trimmed;
}

/**
 * Watch-face / ambient URL overrides that have no `.cp-wf-field` DOM control on this viewer page
 * (for example `ambientBurnIn` / `ambientLowBit` on a non-watch-face Wear preview, or `wf.is24Hour`
 * / `wf.themeColor.*` deep-linked without a dedicated input). Preserving them in viewer state keeps
 * `renderOverrides()`, `liveOverrides()`, and `cpUrlState.sync()` from silently stripping owned
 * parameters.
 */
export function uncontrolledWatchFaceParams(
    params: URLSearchParams,
    controlledKeys: ReadonlySet<string>,
    unseeded: ReadonlySet<string>,
): Record<string, string> {
    const out: Record<string, string> = {};
    params.forEach((value, key) => {
        if (value === "") return;
        if (!isWatchFaceStateParam(key)) return;
        if (controlledKeys.has(key) || unseeded.has(key)) return;
        if (!(key in out)) out[key] = value;
    });
    return out;
}
