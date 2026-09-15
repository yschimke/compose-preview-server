// The magnifier's geometry: what a point magnifies to, and where the patch is allowed to sit.

import assert from "node:assert/strict";
import { crosshairAt, placeAt, windowAt } from "../src/spec/loupe.js";

describe("windowAt", () => {
    it("centres the window on the pixel the point is inside", () => {
        // Floored, exactly as `sampleAt` floors it, so the cell under the crosshair is the pixel
        // the readout names. Rounding here instead would put the two one pixel apart on half the
        // hovers, and the patch would be quietly describing a neighbour.
        assert.deepEqual(windowAt(20.9, 30.1, 15), {
            sx: 13,
            sy: 23,
            span: 15,
        });
    });

    it("lets the window hang off the frame rather than sliding it inward", () => {
        // A point near an edge has fewer neighbours, and that is the truth about it. Clamping the
        // window would show a neighbourhood the cursor is not on, centred on a pixel it is not
        // over — a patch that looks right and is not.
        assert.deepEqual(windowAt(1, 0, 15), { sx: -6, sy: -7, span: 15 });
    });
});

describe("crosshairAt", () => {
    it("puts the sight on the centre cell, which never moves", () => {
        assert.deepEqual(crosshairAt(15, 120), { left: 56, top: 56 });
    });
});

describe("placeAt", () => {
    const size = { width: 260, height: 160 };
    const viewport = { width: 1000, height: 800 };

    it("hangs below and right of the pointer", () => {
        assert.deepEqual(placeAt({ x: 100, y: 100 }, size, viewport, 20), {
            left: 120,
            top: 120,
        });
    });

    it("flips to the other side rather than sliding under the cursor", () => {
        // Clamping alone would push the patch back over the pointer near the bottom-right — which
        // on a preview page is where the stage's own corner is, so the loupe would cover the
        // pixels it was opened to show.
        assert.deepEqual(placeAt({ x: 980, y: 780 }, size, viewport, 20), {
            left: 700,
            top: 600,
        });
    });

    it("flips on one axis at a time", () => {
        assert.deepEqual(placeAt({ x: 980, y: 100 }, size, viewport, 20), {
            left: 700,
            top: 120,
        });
    });

    it("clamps when neither side fits", () => {
        assert.deepEqual(
            placeAt({ x: 100, y: 100 }, size, { width: 200, height: 120 }, 20),
            { left: 0, top: 0 },
        );
    });
});
