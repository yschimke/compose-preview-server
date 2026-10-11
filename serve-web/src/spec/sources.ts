// Which source the spec lane compares the render against. The lane's four views work over any pair
// of images, so another comparison is another source, not another mode. These decisions are pure
// and testable without a browser.

/**
 * The imported kit's reference — the only source that is a specification; all others are another
 * catalog's render. Mirrors `ServeHttpServer.parallelSpecSource`'s `id = "parallel"`.
 */
export const KIT_SOURCE = "kit";

/** One thing the lane can put on the stage beside the render, as the server described it. */
export interface SpecSource {
    /** `kit` / `parallel` — the picker's value, and the token URL state would carry. */
    id: string;
    /** What the button reads, e.g. `Figma` or `wear-m3-catalog`. */
    label: string;
    /** Same-origin URL of the image to compare against. */
    src: string;
    /**
     * Caveat about where these pixels came from, shown while selected. Empty when none is needed.
     */
    provenance?: string;
}

/**
 * The server omits the picker for a one-source lane; this still yields that source as a descriptor
 * without adding a one-item control. A picker, when present, is authoritative.
 */
export function sourcesOrFallback(
    sources: readonly SpecSource[],
    fallback: SpecSource | null,
): SpecSource[] {
    if (sources.length > 0) return Array.from(sources);
    return fallback && fallback.src ? [fallback] : [];
}

/**
 * The source marked pressed, else the first — so a picker with nothing pressed still compares
 * against something.
 */
export function activeSource(
    sources: readonly SpecSource[],
    pressedId: string | null,
): SpecSource | null {
    if (sources.length === 0) return null;
    if (pressedId) {
        for (const source of sources)
            if (source.id === pressedId) return source;
    }
    return sources[0];
}

/**
 * Whether [source] is the imported spec. A missing source means the single-source lane, which only
 * shows the kit reference.
 */
export function isSpecSource(source: SpecSource | null): boolean {
    return !source || source.id === KIT_SOURCE;
}

/** Whether the lane offers a genuine choice; one source means no picker. */
export function offersChoice(sources: readonly SpecSource[]): boolean {
    return sources.length > 1;
}

/**
 * Whether switching to [nextId] is worth a raster request and re-normalisation; re-picking the
 * current source is a no-op.
 */
export function changesSource(
    sources: readonly SpecSource[],
    pressedId: string | null,
    nextId: string,
): boolean {
    const active = activeSource(sources, pressedId);
    if (!active) return false;
    if (active.id === nextId) return false;
    return sources.some((source) => source.id === nextId);
}

/**
 * Whether a resting-bar source chip should close the comparison lane. Every chip toggles: pressing
 * a pressed one returns to the render, an unpressed one selects its source.
 */
export function closesSource(
    onComparisonLane: boolean,
    pressedId: string | null,
    sourceId: string,
): boolean {
    return onComparisonLane && pressedId === sourceId;
}

/**
 * What the lane says about the panel it shows. A sibling's panel is another catalog's render under
 * its own theme and overrides, not a specification, so a source carrying a caveat states it.
 */
export function sourceNote(source: SpecSource | null): string {
    if (!source) return "";
    return source.provenance ? source.provenance.trim() : "";
}

/**
 * The address-bar value for the picked source: its id, or nothing for a lane with no choice or for
 * the default first source.
 */
export function sourceParam(
    sources: readonly SpecSource[],
    pressedId: string | null,
): string {
    if (!offersChoice(sources)) return "";
    const active = activeSource(sources, pressedId);
    if (!active || active.id === sources[0].id) return "";
    return active.id;
}

/**
 * The source a URL asks for, falling back to the default so a stale or mistyped `?specSource=` is
 * harmless.
 */
export function sourceForParam(
    sources: readonly SpecSource[],
    param: string,
): string {
    if (sources.length === 0) return "";
    if (param) {
        for (const source of sources) if (source.id === param) return source.id;
    }
    return sources[0].id;
}
