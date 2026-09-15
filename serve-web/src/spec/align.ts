// Reading the same CONTENT on both sides, rather than the same coordinate.
//
// `normaliseImageUrls` registers the pair by content box: both frames are cropped to their ink and
// drawn onto one origin. That is the right registration for a delta map, and it is a whole-frame
// one — it says nothing about where any individual element ended up. So a card whose label sits
// three pixels lower in the render lines up at the card's edges and disagrees everywhere the label
// is, and the eyedropper, asked what the two sides are at a point on that label, answers with the
// glyph on one side and the background on the other: `Δ 200`, for a three-pixel shift. The number
// is true about those two pixels and useless as an answer about the label.
//
// The annotations already say where each element is on each side, and `annotate/match.ts` already
// pairs them — that is what the typography overlay draws. This takes the same matched pairs,
// mapped into the pair's normalised space, and turns them into one question: for a point, which
// matched box is it in, and how far did that box move? The answer is the offset the candidate is
// read at, and stating it is half the value — "aligned +0,+3" is the shift itself, measured.
//
// Layout boxes only, deliberately. They are the ones that tile the frame, so "the smallest box
// containing this point" is a meaningful localisation; typography boxes are a sparse overlay of the
// text runs, and matching a point to one of those would align the glyph while leaving every pixel
// around it read at a shift that belongs to something else.

import {
    matchAnnotationItems,
    type AnnotationItem,
    type Bounds,
} from "../annotate/match.js";
import type { Offset } from "./pick.js";

/** The crop `normaliseImageUrls` redrew one side from, in that side's own source pixels. */
export interface Crop {
    x: number;
    y: number;
    width: number;
    height: number;
}

/** One element, on both sides, in the pair's normalised space. */
export interface AlignedBox {
    reference: Bounds;
    candidate: Bounds;
}

/**
 * A source-space box in the normalised space, which is the mapping the typography overlay places
 * its markers with — `(bounds - crop) * frame / crop`, per axis.
 */
export function intoNormalised(
    bounds: Bounds,
    crop: Crop,
    width: number,
    height: number,
): Bounds | null {
    if (!(crop.width > 0 && crop.height > 0)) return null;
    const sx = width / crop.width;
    const sy = height / crop.height;
    return {
        x: (bounds.x - crop.x) * sx,
        y: (bounds.y - crop.y) * sy,
        width: bounds.width * sx,
        height: bounds.height * sy,
    };
}

/** Whether a box is one of the ones this aligns by. */
function isLayout(item: AnnotationItem): boolean {
    return (item.kind ?? "") === "layout";
}

/**
 * The matched layout boxes of one pair, both sides in its normalised space.
 *
 * Each side is filtered to layout boxes BEFORE matching, which is what makes the two returned lists
 * index-aligned: `matchAnnotationItems` emits the pairs first and in one order, and keeps overflow
 * only for typography — with a single kind and no overflow, entry *i* of one list is entry *i* of
 * the other by construction. Zipping its mixed-kind output instead would silently pair a layout box
 * on one side with a typography box on the other the moment the counts diverged.
 */
export function alignedBoxes(
    referenceIn: unknown,
    actualIn: unknown,
    crops: { reference: Crop; candidate: Crop },
    width: number,
    height: number,
): AlignedBox[] {
    const layoutOf = (items: unknown) =>
        (Array.isArray(items) ? (items as AnnotationItem[]) : []).filter(
            isLayout,
        );
    const matched = matchAnnotationItems(
        layoutOf(referenceIn),
        layoutOf(actualIn),
    );
    const boxes: AlignedBox[] = [];
    const count = Math.min(matched.reference.length, matched.actual.length);
    for (let i = 0; i < count; i++) {
        const reference = matched.reference[i].bounds;
        const candidate = matched.actual[i].bounds;
        if (!reference || !candidate) continue;
        const r = intoNormalised(reference, crops.reference, width, height);
        const c = intoNormalised(candidate, crops.candidate, width, height);
        if (!r || !c) continue;
        if (!(r.width > 0 && r.height > 0)) continue;
        boxes.push({ reference: r, candidate: c });
    }
    return boxes;
}

function contains(box: Bounds, x: number, y: number): boolean {
    return (
        x >= box.x &&
        y >= box.y &&
        x < box.x + box.width &&
        y < box.y + box.height
    );
}

/**
 * The shift to read the candidate at, for a point in the reference's normalised space.
 *
 * The SMALLEST containing box wins. Layout boxes nest — a card holds a column holds a label — and
 * the innermost one is the element the pointer is actually on, so it is the one whose movement
 * explains what is under the cursor. The outer ones have usually not moved at all (normalisation
 * aligned the frame by its content box), which is precisely why aligning by them would leave the
 * label reading as a difference.
 *
 * Centres rather than origins: an element that both moved and changed size has no single "shift",
 * and its centre is the point of it that both sides agree is the same point. Rounded, because the
 * result indexes a pixel buffer, and a half-pixel shift is not a thing either frame contains.
 */
export function offsetAt(
    boxes: AlignedBox[],
    x: number,
    y: number,
): Offset | null {
    let best: AlignedBox | null = null;
    let bestArea = Infinity;
    for (const box of boxes) {
        if (!contains(box.reference, x, y)) continue;
        const area = box.reference.width * box.reference.height;
        if (area < bestArea) {
            bestArea = area;
            best = box;
        }
    }
    if (!best) return null;
    return {
        dx: Math.round(
            best.candidate.x +
                best.candidate.width / 2 -
                (best.reference.x + best.reference.width / 2),
        ),
        dy: Math.round(
            best.candidate.y +
                best.candidate.height / 2 -
                (best.reference.y + best.reference.height / 2),
        ),
    };
}
