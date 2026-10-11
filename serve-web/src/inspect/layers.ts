// The inspection layers, what each is fetched from, and how they are addressed.

/** A layer: its checkbox value, its legend heading, and the endpoint suffix it reads. */
export interface LayerSpec {
    kind: string;
    label: string;
    /** Endpoint suffix; typography/theme/layout intentionally share `annotations`. */
    source: string;
}

export const LAYERS: LayerSpec[] = [
    { kind: "slots", label: "Slots", source: "slots" },
    { kind: "a11y", label: "Accessibility", source: "a11y" },
    { kind: "typography", label: "Typography", source: "annotations" },
    { kind: "theme", label: "Theme", source: "annotations" },
    { kind: "layout", label: "Layout", source: "annotations" },
];

/**
 * The endpoints a set of layers needs, deduplicated: Typography, Theme and Layout share one
 * payload, and on an override-bearing frame each extra fetch is another daemon render.
 */
export function sourcesFor(kinds: string[]): string[] {
    const out: string[] = [];
    for (const spec of LAYERS) {
        if (kinds.includes(spec.kind) && !out.includes(spec.source))
            out.push(spec.source);
    }
    return out;
}

/** Layers in their declared order — the legend's sections read the same way every time. */
export function activeLayers(kinds: string[]): LayerSpec[] {
    return LAYERS.filter((spec) => kinds.includes(spec.kind));
}

/**
 * The data URL for one endpoint, derived from the displayed frame's URL so the overlay describes
 * those exact pixels without duplicating the viewer's query rules. Only the suffix changes; a
 * `scroll=long` frame falls back to the viewport-sized product.
 */
export function dataUrlFor(frameUrl: string, suffix: string): string | null {
    if (!frameUrl) return null;
    const cut = frameUrl.indexOf("?");
    const path = (cut < 0 ? frameUrl : frameUrl.slice(0, cut)).replace(
        /\.(png|svg)$/,
        "",
    );
    const query = cut < 0 ? "" : frameUrl.slice(cut);
    return `${path}.${suffix}${query}`;
}

/**
 * The `layers=` value for one endpoint: the ticked layers it serves, in declared order (empty for
 * single-product endpoints like `slots`, `a11y`). Lets the server replay typography-only requests
 * from the published bundle instead of starting a daemon.
 */
export function layersParamFor(source: string, kinds: string[]): string {
    const asked = LAYERS.filter(
        (spec) => spec.source === source && kinds.includes(spec.kind),
    ).map((spec) => spec.kind);
    // Narrowed only for typography alone; everything else asks unscoped. Keeping two addresses per
    // frame means the wide payload is shared by every combination (one cache entry, one render).
    return asked.length === 1 && asked[0] === "typography" ? "typography" : "";
}

/**
 * Whether an annotations payload carries more layers than [kinds] asked for (the server may answer
 * a narrowed request with the full capture). Read off the payload since the server picks the lane.
 */
export function carriesBeyond(
    payload: unknown,
    source: string,
    kinds: string[],
): boolean {
    if (source !== "annotations" || !payload || typeof payload !== "object")
        return false;
    const asked = LAYERS.filter(
        (spec) => spec.source === source && kinds.includes(spec.kind),
    ).map((spec) => spec.kind);
    const list = (payload as { annotations?: unknown }).annotations;
    if (!Array.isArray(list)) return false;
    return list.some((item) => {
        const kind = (item as { kind?: unknown } | null)?.kind;
        return typeof kind === "string" && !asked.includes(kind);
    });
}

/** Append a non-empty `layers=` to a data URL, preserving whatever query it already carries. */
export function withLayers(url: string, layers: string): string {
    if (!layers) return url;
    return `${url}${url.includes("?") ? "&" : "?"}layers=${encodeURIComponent(layers)}`;
}

/** The address to use before any frame has decoded: the preview's own, carrying the session keys. */
export function fallbackUrl(
    base: string,
    previewId: string,
    suffix: string,
    keys: { token?: string; session?: string },
): string {
    const parts: string[] = [];
    if (keys.token) parts.push(`token=${encodeURIComponent(keys.token)}`);
    if (keys.session) parts.push(`session=${encodeURIComponent(keys.session)}`);
    const query = parts.length ? `?${parts.join("&")}` : "";
    return `${base}/render/${encodeURIComponent(previewId)}.${suffix}${query}`;
}

/**
 * `/compose-m3/p/plain.Button` → `/compose-m3`, the render URL prefix for both the viewer
 * (`/p/<id>`) and the focused comparison (`/compare/<id>`). Only a fallback until the frame on
 * screen decodes.
 */
export function baseFrom(pathname: string): string {
    return pathname.replace(/\/(?:p|compare)\/[^/]*\/?$/, "");
}

/** The layers a `?inspect=` value names, in the order the page declares them. */
export function kindsFromParam(value: string | null): string[] {
    const wanted = (value ?? "").split(",").filter(Boolean);
    return LAYERS.filter((spec) => wanted.includes(spec.kind)).map(
        (spec) => spec.kind,
    );
}

/** What `?inspect=` should carry, or null to drop the parameter entirely. */
export function inspectParam(kinds: string[]): string | null {
    return kinds.length ? kinds.join(",") : null;
}
