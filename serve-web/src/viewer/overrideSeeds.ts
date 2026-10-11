// Which URL override values may drive a control, and how they are read. `hydrateFromUrl` must reach
// the same answer the server rendered, or controls claim values the pixels never had. The rules
// mirror `ServeOverrides.knobControlValue`/`rcControlValue` and
// `ServeHttpServer.seedableOverrideParams`, as testable pure functions.

/** The `<kind>` tags a `knob.<key>=<kind>:<value>` may carry. */
const KNOB_KINDS = new Set(["string", "int", "float", "bool", "color"]);

/**
 * Axes this page won't let the URL drive (`knob.<key>`/`rc.<name>`), from
 * `data-unseeded-overrides`. Non-empty only where the image did not apply them: a pinned revision,
 * `?fallback=baked`, or replay-dropped axes.
 */
export function unseededOverrides(root: Element | null): Set<string> {
    const raw = root?.getAttribute("data-unseeded-overrides") || "";
    if (raw === "") return new Set();
    // A JSON array because knob keys may contain commas. Malformed input withholds nothing, like an
    // ordinary page.
    try {
        const parsed: unknown = JSON.parse(raw);
        if (!Array.isArray(parsed)) return new Set();
        return new Set(
            parsed.filter((s): s is string => typeof s === "string"),
        );
    } catch {
        return new Set();
    }
}

/**
 * In-browser lanes and the one control family each forwards: Wasm forwards `.cp-knob`, the RC
 * canvas and CMP-Wasm player forward `.cp-rc-knob`, and `syncServerControls()` disables the other
 * family. So the exemption from withholding is per family: `?mode=wasm&rc.x=…` must keep
 * withholding `rc.x`.
 */
const IN_BROWSER_LANE_FAMILY: Record<string, string> = {
    wasm: "knob.",
    rc: "rc.",
    "rc-wasm": "rc.",
};

/**
 * Withheld axes that still bind for the lane this restore heads to. Everything stays withheld on
 * server-rendered lanes and without `?mode=`. [laneEnterable] says whether that lane will actually
 * draw: a stale `?mode=wasm` on a page with no Wasm lane leaves the snapshot showing.
 */
export function effectiveUnseeded(
    all: Set<string>,
    mode: string | null,
    laneEnterable: boolean,
): Set<string> {
    if (all.size === 0) return all;
    const family = laneEnterable
        ? IN_BROWSER_LANE_FAMILY[mode || ""]
        : undefined;
    if (family === undefined) return all;
    return new Set([...all].filter((axis) => !axis.startsWith(family)));
}

/**
 * The value a declared knob's control opens on: `initial` (`data-knob-initial`) unless the URL
 * supplies a usable, non-withheld value (empty is skipped for non-string knobs). A `<kind>:` tag is
 * stripped only when it matches the declared kind.
 */
export function knobHydratedValue(opts: {
    wireKey: string;
    urlValue: string | null;
    initial: string;
    declaredKind: string;
    unseeded: Set<string>;
}): string {
    const { wireKey, urlValue, initial, declaredKind, unseeded } = opts;
    if (urlValue === null) return initial;
    if (unseeded.has("knob." + wireKey)) return initial;
    let value = urlValue;
    const sep = value.indexOf(":");
    if (sep > 0) {
        const prefix = value.substring(0, sep);
        if (KNOB_KINDS.has(prefix) && prefix === declaredKind)
            value = value.substring(sep + 1);
    }
    if (value === "" && declaredKind && declaredKind !== "string")
        return initial;
    return value;
}

/**
 * The same for a Remote Compose knob, stricter because `rc.` values type from their own tag: a seed
 * applies only when its parsed kind matches the declaration, and blanks are skipped.
 */
export function rcHydratedValue(opts: {
    name: string;
    urlValue: string | null;
    initial: string;
    declaredKind: string;
    unseeded: Set<string>;
}): string {
    const { name, urlValue, initial, declaredKind, unseeded } = opts;
    if (urlValue === null) return initial;
    if (unseeded.has("rc." + name)) return initial;
    if (urlValue.trim() === "") return initial;
    const sep = urlValue.indexOf(":");
    const tag = sep > 0 ? urlValue.substring(0, sep) : null;
    const wireKind = tag && RC_KINDS.has(tag) ? tag : null;
    const value = wireKind !== null ? urlValue.substring(sep + 1) : urlValue;
    return (wireKind ?? "string") === declaredKind ? value : initial;
}

/** The `<kind>` tags an `rc.<name>=<kind>:<value>` may carry — [KNOB_KINDS] plus `dp`. */
const RC_KINDS = new Set(["string", "int", "float", "dp", "bool", "color"]);

/** `true` for `1` or `true` in any case — the rule the server's parser reads a bool by. */
export function isChecked(value: string): boolean {
    return value === "1" || value.toLowerCase() === "true";
}
