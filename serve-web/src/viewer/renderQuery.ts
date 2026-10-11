// What lands in a render URL. Every rule decides whether the viewer stays on the instant baked
// snapshot or routes to the daemon: a needless parameter turns a free page load into a render (for
// every copied link), and a missing one shows a picture that doesn't match the controls. Neither
// looks like a failure, hence a table here. DOM-free: `viewer.js` passes plain values.

/** A knob's declared type. Anything undeclared parses as `string` server-side. */
export type KnobKind = "string" | "int" | "float" | "bool" | "color";

/**
 * Whether an author-declared knob (`knob.<key>=`) belongs in the URL. An empty string is a real
 * value and is sent; an emptied number has nothing to send (and the map replaces the daemon's whole
 * bag). A knob at its declared default is omitted, since any `knob.*` routes a published catalog to
 * the daemon.
 */
export function knobEmitted(
    value: string,
    initial: string,
    kind: KnobKind | string,
): boolean {
    if (value === "" && kind !== "string") return false;
    return value !== initial;
}

/**
 * Whether a Remote Compose knob (`rc.<name>=<kind>:<value>`) belongs in the URL. An empty value is
 * never sent: no RC seed means "empty".
 */
export function rcKnobEmitted(value: string, initial: string): boolean {
    if (value === "") return false;
    return value !== initial;
}

/** An RC knob's wire value: the kind prefix is what types the seed server-side. */
export function rcKnobValue(kind: string, value: string): string {
    return `${kind || "string"}:${value}`;
}

/**
 * Whether `?exploded=` asks for the 3D view, accepting the same forms as `ServeExplodedSvg.enabled`
 * so the page agrees with the render endpoint.
 */
export function explodeParamOn(raw: string | null | undefined): boolean {
    if (raw === null || raw === undefined) return false;
    if (raw === "") return true; // a bare `?exploded`
    return ["1", "true", "on", "yes"].includes(String(raw).toLowerCase());
}

export interface ExplodeKnob {
    /** The URL parameter name, e.g. `explodeTilt`. */
    param: string;
    value: string;
    /** The authored default, from `data-cp-default`. */
    defaultValue: string;
}

/**
 * The exploded view's parameters: all knobs ride the URL (the angle is part of the copied link,
 * download and review screenshot), except those at their authored default, so the common URL is
 * `?exploded=1`.
 */
export function explodeParams(knobs: ExplodeKnob[]): string[] {
    const parts = ["exploded=1"];
    for (const knob of knobs) {
        if (knob.value === "" || knob.value === knob.defaultValue) continue;
        parts.push(`${knob.param}=${encodeURIComponent(knob.value)}`);
    }
    return parts;
}

/** Append `parts` to a query string that may be empty. */
export function appendQuery(qs: string, parts: string[]): string {
    if (!parts.length) return qs;
    const added = parts.join("&");
    return qs ? `${qs}&${added}` : added;
}

/**
 * "Full page (scroll)": the server routes SVG to `compose/figma-svg-long` and PNG to
 * `render/scroll/long`.
 */
export function withScroll(qs: string, scrollLong: boolean): string {
    return scrollLong ? appendQuery(qs, ["scroll=long"]) : qs;
}

/** The exploded view rides only `.svg`; on PNG it would silently do nothing. */
export function withExplode(
    ext: string,
    qs: string,
    on: boolean,
    knobs: ExplodeKnob[],
): string {
    if (ext !== ".svg" || !on) return qs;
    return appendQuery(qs, explodeParams(knobs));
}

export function withSnapshotFormat(
    ext: string,
    qs: string,
    options: { scrollLong: boolean; exploded: boolean; knobs: ExplodeKnob[] },
): string {
    return withExplode(
        ext,
        withScroll(qs, options.scrollLong),
        options.exploded,
        options.knobs,
    );
}

/**
 * A size field's device pixels, or `null` for blank or non-positive input (a zero-width render is a
 * failure, not a smaller picture).
 */
export function sizePx(value: string, density: number): string | null {
    const dp = parseFloat(value);
    if (!(dp > 0)) return null;
    return String(Math.max(1, Math.round(dp * density)));
}

export type SizeMode = "fixed" | "min" | "max" | "within" | "";

/** The size fields a mode reads, and the override key each becomes. */
export const SIZE_FIELDS: Record<
    Exclude<SizeMode, "">,
    Array<[field: string, key: string]>
> = {
    fixed: [
        ["fixedW", "widthPx"],
        ["fixedH", "heightPx"],
    ],
    min: [
        ["minW", "minWidthPx"],
        ["minH", "minHeightPx"],
    ],
    max: [
        ["maxW", "maxWidthPx"],
        ["maxH", "maxHeightPx"],
    ],
    // `within` is BOTH bounds, which is what makes it a mode of its own rather than a label: a
    // reader asking for "within 320–600" is asking for two constraints at once.
    within: [
        ["minW", "minWidthPx"],
        ["minH", "minHeightPx"],
        ["maxW", "maxWidthPx"],
        ["maxH", "maxHeightPx"],
    ],
};

/**
 * The size overrides a mode contributes. Each mode reads only its own fields, so switching can't
 * leave a stale `widthPx`; field values persist so switching back restores them.
 */
export function sizeOverrides(
    mode: SizeMode,
    read: (field: string) => string | null,
): Record<string, string> {
    const overrides: Record<string, string> = {};
    if (!mode || !(mode in SIZE_FIELDS)) return overrides;
    for (const [field, key] of SIZE_FIELDS[mode as Exclude<SizeMode, "">]) {
        const value = read(field);
        if (value !== null) overrides[key] = value;
    }
    return overrides;
}

/**
 * Whether the render URL carries the page's cache generation (`gen=<sha>`), so a viewer open across
 * a catalog refresh keeps fetching the frame its published metadata describes. Only when:
 * - there is a generation (a delivery branch);
 * - the page isn't pinned (`at=` already fixes the publish);
 * - nothing is overridden (an override render reflects no published bytes and is `no-store`).
 */
export function generationEmitted(
    generation: string,
    pinned: string,
    overridden: boolean,
): boolean {
    return !!generation && !pinned && !overridden;
}
