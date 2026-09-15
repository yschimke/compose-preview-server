// Content-aware alignment: which matched layout box a point is in, and how far that box moved.
//
// The case it exists for is the one issue #830 states: a label three pixels lower in the render.
// Read at the reference's own coordinate, the two sides disagree everywhere the glyph is and the
// picker reports a difference of 200 for a shift of 3. Read inside the matched box, ink meets ink.

import assert from "node:assert/strict";
import { alignedBoxes, intoNormalised, offsetAt } from "../src/spec/align.js";

/** The identity crop: a frame already normalised at its own size. */
const same = (width: number, height: number) => ({
    reference: { x: 0, y: 0, width, height },
    candidate: { x: 0, y: 0, width, height },
});

const layout = (
    x: number,
    y: number,
    width: number,
    height: number,
    role?: string,
) => ({ kind: "layout", bounds: { x, y, width, height }, role });

describe("intoNormalised", () => {
    it("maps a source box through the crop the pair was normalised from", () => {
        // The same arithmetic the typography overlay places its markers with: subtract the crop's
        // origin, then scale by frame/crop per axis.
        const box = intoNormalised(
            { x: 12, y: 20, width: 10, height: 4 },
            { x: 2, y: 4, width: 20, height: 8 },
            40,
            16,
        );
        assert.deepEqual(box, { x: 20, y: 32, width: 20, height: 8 });
    });

    it("refuses a crop with no area rather than dividing by it", () => {
        assert.equal(
            intoNormalised(
                { x: 0, y: 0, width: 1, height: 1 },
                { x: 0, y: 0, width: 0, height: 8 },
                8,
                8,
            ),
            null,
        );
    });
});

describe("alignedBoxes", () => {
    it("pairs the two sides' layout boxes in the shared space", () => {
        const boxes = alignedBoxes(
            [layout(0, 0, 40, 20, "card"), layout(4, 4, 20, 6, "label")],
            [layout(0, 0, 40, 20, "card"), layout(4, 7, 20, 6, "label")],
            same(40, 20),
            40,
            20,
        );
        assert.equal(boxes.length, 2);
        const label = boxes.find((box) => box.reference.y === 4);
        assert.deepEqual(label?.candidate, {
            x: 4,
            y: 7,
            width: 20,
            height: 6,
        });
    });

    it("ignores everything that is not a layout box", () => {
        // Typography boxes are a sparse overlay of the text runs, not a tiling of the frame, so
        // "the smallest box containing this point" means nothing over them — and `matchAnnotationItems`
        // keeps BOTH sides' unmatched typography, which is what would break the index pairing.
        const boxes = alignedBoxes(
            [
                layout(0, 0, 8, 8),
                {
                    kind: "typography",
                    bounds: { x: 1, y: 1, width: 2, height: 2 },
                },
            ],
            [
                layout(0, 0, 8, 8),
                {
                    kind: "typography",
                    bounds: { x: 1, y: 3, width: 2, height: 2 },
                },
                {
                    kind: "typography",
                    bounds: { x: 5, y: 3, width: 2, height: 2 },
                },
            ],
            same(8, 8),
            8,
            8,
        );
        assert.equal(boxes.length, 1);
        assert.equal(boxes[0].reference.width, 8);
    });

    it("answers nothing when one side has no layout boxes at all", () => {
        assert.deepEqual(
            alignedBoxes([layout(0, 0, 8, 8)], [], same(8, 8), 8, 8),
            [],
        );
        assert.deepEqual(alignedBoxes(null, null, same(8, 8), 8, 8), []);
    });
});

describe("offsetAt", () => {
    const boxes = alignedBoxes(
        [layout(0, 0, 40, 20, "card"), layout(4, 4, 20, 6, "label")],
        [layout(0, 0, 40, 20, "card"), layout(4, 7, 20, 6, "label")],
        same(40, 20),
        40,
        20,
    );

    it("gives the innermost box's shift, not the frame's", () => {
        // Inside the label: the card did not move and the label did, and it is the label the
        // pointer is on. Aligning by the outer box is the same as not aligning at all, which is
        // exactly the failure the option exists to fix.
        assert.deepEqual(offsetAt(boxes, 10, 6), { dx: 0, dy: 3 });
    });

    it("gives the containing box's own shift outside the inner one", () => {
        assert.deepEqual(offsetAt(boxes, 35, 18), { dx: 0, dy: 0 });
    });

    it("answers null where no matched box covers the point", () => {
        // A real answer, and the readout says so: alignment that quietly fell back to zero would
        // be indistinguishable from alignment that found a box which had not moved.
        assert.equal(offsetAt(boxes, 100, 100), null);
        assert.equal(offsetAt([], 1, 1), null);
    });

    it("aligns by centres, so a box that also resized still names one shift", () => {
        // There is no single "shift" for a box that moved and grew; its centre is the point both
        // sides agree is the same point.
        const resized = alignedBoxes(
            [layout(0, 0, 40, 20), layout(10, 4, 10, 4)],
            [layout(0, 0, 40, 20), layout(9, 8, 12, 4)],
            same(40, 20),
            40,
            20,
        );
        assert.deepEqual(offsetAt(resized, 12, 5), { dx: 0, dy: 4 });
    });

    it("rounds to a whole pixel, because the offset indexes a buffer", () => {
        const half = alignedBoxes(
            [layout(0, 0, 40, 20), layout(10, 4, 10, 4)],
            [layout(0, 0, 40, 20), layout(10, 6.6, 10, 4)],
            same(40, 20),
            40,
            20,
        );
        assert.deepEqual(offsetAt(half, 12, 5), { dx: 0, dy: 3 });
    });
});
