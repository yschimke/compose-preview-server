// What a reporter can point at, and in which plane it is recorded. A tag (`testTag → {count,
// bounds, space}`) is an identity that survives re-layout, unlike a `SemanticsRefs` path that
// indexes siblings. A region is just a rectangle: geometric only, but needs nothing from the
// server. Selection only says what the report is about; it accepts nothing.

import type { Bounds } from "./locator.js";

/** One entry of the published index, as `ServeAnnotationsPayload.encodeTags` writes it. */
interface WireTagEntry {
    count?: number;
    bounds?: { x: number; y: number; width: number; height: number } | null;
    space?: string;
}

/** A tag offered in the picker. */
export interface TagTarget {
    /** The tag VERBATIM — no trimming anywhere in the chain. */
    tag: string;
    count: number;
    /** Absent for a tag whose every carrying node had a zero-area box. */
    bounds?: Bounds;
    /**
     * True when more than one node carries the tag; such a tag may be listed but never chosen as an
     * element selector. `count` includes nodes with unusable bounds so a zero-area duplicate can't
     * make an ambiguous tag look unique.
     */
    ambiguous: boolean;
}

/** The only plane the index is allowed to publish; see D1. */
const RENDER_PIXELS = "render-pixels";

/**
 * The index payload as picker targets, sorted by tag for scanning. Entries with a missing or
 * unknown space are dropped, not defaulted (that is what the discriminator prevents), as are
 * entries counting fewer than one node (a producer bug).
 */
export function tagTargets(payload: unknown): TagTarget[] {
    const tags = (payload as { tags?: Record<string, WireTagEntry> } | null)
        ?.tags;
    if (!tags || typeof tags !== "object") return [];
    const out: TagTarget[] = [];
    for (const tag of Object.keys(tags)) {
        if (!tag) continue;
        const entry = tags[tag];
        const count = Number(entry?.count ?? 0);
        if (!Number.isInteger(count) || count < 1) continue;
        if (entry?.space !== RENDER_PIXELS) continue;
        const box = entry?.bounds;
        const bounds =
            box && box.width > 0 && box.height > 0
                ? {
                      x: Math.trunc(box.x),
                      y: Math.trunc(box.y),
                      width: Math.trunc(box.width),
                      height: Math.trunc(box.height),
                  }
                : undefined;
        out.push({ tag, count, bounds, ambiguous: count > 1 });
    }
    // Code-point order, matching how the locator's own maps are ordered, so two pages built from
    // the same index offer the same list in the same sequence.
    return out.sort((a, b) => (a.tag < b.tag ? -1 : a.tag > b.tag ? 1 : 0));
}

/** A rectangle in the DISPLAY plane — CSS pixels, relative to the frame's rendered box. */
export interface DisplayRect {
    x: number;
    y: number;
    width: number;
    height: number;
}

/**
 * One display point as unrounded render-plane coordinates (rounding happens outward on the
 * rectangle). Null before the frame decodes, since there is no scale.
 */
export function toRenderPoint(
    point: { x: number; y: number },
    frame: { naturalWidth: number; clientWidth: number },
): { x: number; y: number } | null {
    if (!frame.naturalWidth || !frame.clientWidth) return null;
    const scale = frame.naturalWidth / frame.clientWidth;
    if (!Number.isFinite(scale) || scale <= 0) return null;
    return { x: point.x * scale, y: point.y * scale };
}

/**
 * The rectangle two render-plane points bound, rounded **outward**: `floor` the origin, `ceil` the
 * far edge. A selection that grew by half a pixel still contains what the reporter dragged around,
 * where one that shrank may have clipped the very edge they were pointing at.
 *
 * Taking two already-converted points rather than a display rectangle is what makes a drag survive
 * a reflow. Converting at the END would measure an origin captured against the old frame box with a
 * scale taken from the new one — two coordinate systems in one rectangle, silently naming a region
 * nobody selected. A point converted at the moment it was touched stays valid however the frame is
 * subsequently resized, because the render plane is a property of the render, not of the display.
 *
 * Null when the result has no area — a click with no drag is not a region.
 */
export function renderRectBetween(
    a: { x: number; y: number },
    b: { x: number; y: number },
): Bounds | null {
    const x = Math.floor(Math.min(a.x, b.x));
    const y = Math.floor(Math.min(a.y, b.y));
    const width = Math.ceil(Math.max(a.x, b.x)) - x;
    const height = Math.ceil(Math.max(a.y, b.y)) - y;
    if (width < 1 || height < 1) return null;
    return { x, y, width, height };
}

/**
 * A dragged rectangle from display pixels to render pixels in one step, for a gesture whose frame
 * cannot have moved. `v1` accepts only `render-pixels`; a display-plane rectangle would report a
 * still element as moved at a different width.
 */
export function toRenderPixels(
    rect: DisplayRect,
    frame: { naturalWidth: number; clientWidth: number },
): Bounds | null {
    const origin = toRenderPoint({ x: rect.x, y: rect.y }, frame);
    const far = toRenderPoint(
        { x: rect.x + rect.width, y: rect.y + rect.height },
        frame,
    );
    if (!origin || !far) return null;
    return renderRectBetween(origin, far);
}
