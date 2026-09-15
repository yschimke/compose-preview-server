// Where the magnifier looks, and where it sits.
//
// The eyedropper answers "what is this pixel, on both sides". The loupe is the same question asked
// of a neighbourhood: a 3px shift, a half-covered state layer, a one-pixel border that became two
// are all things a single reading names one pixel of and a magnified patch shows whole. It is the
// same instrument — one point in the normalised space both frames share — drawn at a scale an eye
// can work at, on both sides at once.
//
// Only geometry lives here, for the same reason `spec/pick.ts` holds only index arithmetic: the
// two decisions worth pinning are which source rectangle a point magnifies to, and where a panel
// that follows the cursor is allowed to sit. Neither needs a canvas to be true.

export interface LoupeWindow {
    /** Top-left of the magnified square, in the normalised space. May be negative near an edge. */
    sx: number;
    sy: number;
    /** The square's side, in normalised pixels. */
    span: number;
}

export interface Placement {
    left: number;
    top: number;
}

/**
 * The square of normalised pixels a point magnifies to.
 *
 * Centred on the PIXEL the point is inside — floored, exactly as `sampleAt` does — so the reading
 * in the row and the cell under the crosshair are the same pixel. Not clamped to the frame: a point
 * near an edge magnifies a window that hangs off it, and `drawImage` draws the intersection, which
 * is the truth (there is nothing there) rather than a window silently slid inward to a
 * neighbourhood the cursor is not on.
 */
export function windowAt(x: number, y: number, span: number): LoupeWindow {
    const half = Math.floor(span / 2);
    return { sx: Math.floor(x) - half, sy: Math.floor(y) - half, span };
}

/**
 * Where inside a tile the sampled pixel's cell lands, in tile pixels.
 *
 * Always the same cell — the window is built around the point — so this is the crosshair's home and
 * it does not move as the pointer does. That steadiness is the point of a loupe: the picture slides
 * under a fixed sight rather than the sight wandering over the picture.
 */
export function crosshairAt(span: number, tile: number): Placement {
    const cell = tile / span;
    const half = Math.floor(span / 2);
    return { left: half * cell, top: half * cell };
}

/**
 * Where to put the panel, given the pointer and the viewport.
 *
 * Below-right of the cursor by `gap`, because that is where a magnifier is expected and it leaves
 * the thing being magnified uncovered above it. Flipped to the other side of the cursor rather than
 * merely clamped when it would leave the viewport: clamping slides the panel UNDER the cursor, so
 * near the bottom-right corner — which on a preview page is where the stage's own corner is — the
 * loupe would cover the pixels it was opened to show. Clamped as well as flipped, for a viewport
 * too small for either side.
 */
export function placeAt(
    pointer: { x: number; y: number },
    size: { width: number; height: number },
    viewport: { width: number; height: number },
    gap: number,
): Placement {
    const fits = (start: number, extent: number, limit: number) =>
        start >= 0 && start + extent <= limit;
    const rightOf = pointer.x + gap;
    const leftOf = pointer.x - gap - size.width;
    const below = pointer.y + gap;
    const above = pointer.y - gap - size.height;
    const left = fits(rightOf, size.width, viewport.width) ? rightOf : leftOf;
    const top = fits(below, size.height, viewport.height) ? below : above;
    return {
        left: Math.max(0, Math.min(left, viewport.width - size.width)),
        top: Math.max(0, Math.min(top, viewport.height - size.height)),
    };
}
