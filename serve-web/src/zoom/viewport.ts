// Pure geometry behind `<cp-page-zoom>`: zoom-to-frame, pan limits and "one level in" are decided
// here so they can be unit-tested without a DOM, including against wide specimen sheets.

/** How far the sheet is zoomed, and where it has been dragged to (CSS pixels). */
export interface View {
    scale: number;
    x: number;
    y: number;
}

/** The rectangle subset this module needs — a `DOMRect` satisfies it. */
export interface Box {
    left: number;
    top: number;
    width: number;
    height: number;
}

/** 1:1 is the floor: the sheet is served at exactly the width of its stage. */
export const MIN_SCALE = 1;

/** Enough to read 8 px design type on a heavily squeezed sheet, short of pure PNG blur. */
export const MAX_SCALE = 24;

/** One notch of the corner buttons, and of a double-click with nowhere to go. */
export const STEP = 1.45;

/** Air around a framed section, so it reads as an object and not as a crop. */
export const FRAME_PAD = 0.94;

/** The identity view: the whole sheet, unpanned. */
export function rest(): View {
    return { scale: MIN_SCALE, x: 0, y: 0 };
}

export function zoomed(view: View): boolean {
    return view.scale > 1.001;
}

/** Keep the sheet inside its stage so it cannot be dragged off; at 1:1 the view is pinned. */
export function clamp(view: View, box: Box): View {
    if (!(view.scale > MIN_SCALE)) return rest();
    const scale = Math.min(view.scale, MAX_SCALE);
    return {
        scale,
        x: Math.min(0, Math.max(box.width - box.width * scale, view.x)),
        y: Math.min(0, Math.max(box.height - box.height * scale, view.y)),
    };
}

/**
 * Carry a pan across a stage resize, keeping the same part of the sheet in view. Offsets are in
 * stage pixels, so they are scaled by the size ratio rather than just re-clamped (which shifts the
 * centre).
 */
export function rescale(view: View, from: Box, to: Box): View {
    if (!(from.width > 0 && from.height > 0)) return view;
    if (!(to.width > 0 && to.height > 0)) return view;
    return clamp(
        {
            scale: view.scale,
            x: view.x * (to.width / from.width),
            y: view.y * (to.height / from.height),
        },
        to,
    );
}

/** Zoom about a point, keeping what is under it fixed. */
export function zoomAbout(
    view: View,
    box: Box,
    clientX: number,
    clientY: number,
    factor: number,
): View {
    const px = clientX - box.left;
    const py = clientY - box.top;
    const scale = Math.min(MAX_SCALE, Math.max(MIN_SCALE, view.scale * factor));
    const cx = (px - view.x) / view.scale;
    const cy = (py - view.y) / view.scale;
    return clamp({ scale, x: px - cx * scale, y: py - cy * scale }, box);
}

/**
 * How much bigger a rect gets when framed in the stage. Fitting both axes is not enough: the stage
 * has the sheet's aspect (possibly 3.4:1), so a full-height section would fit at ~1.0 and the
 * gesture would do nothing. A rect much taller than the viewport is fitted to its width (caller
 * top-anchors it), with the crop capped at 3x the fitting height; shapes within 1.5x of the stage
 * just fit.
 */
export function fitFactor(rect: Box, box: Box): number {
    if (!(rect.width > 0 && rect.height > 0)) return 1;
    const sw = box.width / rect.width;
    const sh = box.height / rect.height;
    return (
        (sw > sh * 1.5 ? Math.min(sw, sh * 3) : Math.min(sw, sh)) * FRAME_PAD
    );
}

/** Frame a measured rect in the stage: centred when it fits, top-anchored when not. */
export function frameRect(view: View, box: Box, rect: Box): View {
    if (!(rect.width > 0 && rect.height > 0)) return view;
    if (!(box.width > 0 && box.height > 0)) return view;
    const scale = Math.min(
        MAX_SCALE,
        Math.max(MIN_SCALE, view.scale * fitFactor(rect, box)),
    );
    const grew = scale / view.scale;
    const cx = (rect.left + rect.width / 2 - box.left - view.x) / view.scale;
    const top = (rect.top - box.top - view.y) / view.scale;
    const height = rect.height * grew;
    return clamp(
        {
            scale,
            x: box.width / 2 - cx * scale,
            // The top of a column is where reading starts, so a section three
            // viewports tall opens at its top rather than in its middle.
            y:
                height <= box.height
                    ? (box.height - height) / 2 - top * scale
                    : box.height * 0.03 - top * scale,
        },
        box,
    );
}

/**
 * The smallest pan that brings a rect into the stage, or null if already visible — so keyboard
 * focus on an off-screen node is revealed.
 */
export function revealDelta(
    rect: Box,
    box: Box,
    pad = 12,
): { x: number; y: number } | null {
    let x = 0;
    let y = 0;
    const right = box.left + box.width;
    const bottom = box.top + box.height;
    if (rect.left < box.left + pad) x = box.left + pad - rect.left;
    else if (rect.left + rect.width > right - pad) {
        x = right - pad - (rect.left + rect.width);
    }
    if (rect.top < box.top + pad) y = box.top + pad - rect.top;
    else if (rect.top + rect.height > bottom - pad) {
        y = bottom - pad - (rect.top + rect.height);
    }
    return x || y ? { x, y } : null;
}

/** One addressable box of the export, as the drill sees it. */
export interface Level<T> {
    node: T;
    box: Box;
}

/**
 * Pick the next level in from the chain of boxes under the pointer (outermost first); null means
 * "nothing deeper", read by the caller as a step out. `start` is the currently framed index (-1 for
 * the whole sheet); depth is remembered rather than inferred from the view, because a framed
 * section may fill only one axis.
 */
export function pickLevel<T>(
    chain: Array<Level<T>>,
    start: number,
    box: Box,
    outer: Box,
    scale = 1,
): Level<T> | null {
    const current = start >= 0 ? chain[start].box : outer;
    for (let i = start + 1; i < chain.length; i++) {
        const level = chain[i];
        // A wrapper the same size as the current level (clip group, frame around a frame) re-frames
        // the same picture, so it is not a level.
        if (
            level.box.width >= current.width * 0.92 &&
            level.box.height >= current.height * 0.92
        ) {
            continue;
        }
        // Skip hairlines and single glyph strokes. Measured in sheet pixels, since these rects
        // already carry the zoom.
        if (level.box.width / scale < 6 || level.box.height / scale < 6)
            continue;
        // A level that cannot be magnified is not a level; keep descending.
        if (fitFactor(level.box, box) < 1.15) continue;
        return level;
    }
    return null;
}
