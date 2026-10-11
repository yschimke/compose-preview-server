// The comparison metric, as a table: pure arithmetic over luminance planes. Assertions are mostly
// relational because exact percentages follow the tuning constants; what must not change is the
// ordering (a raster shift stays cheaper than a missing mark).

import assert from "node:assert/strict";
import {
    contentMask,
    directedCosts,
    edgeMask,
    meanCost,
    pixelCost,
    scorePlanes,
} from "../src/scorer/planes.js";
import {
    EDGE_GRADIENT_THRESHOLD,
    FULL_DIFFERENCE_DELTA,
    LUMA_TOLERANCE,
} from "../src/scorer/tuning.js";

/** A plane from an ASCII grid: `#` is ink (0), `.` is paper (255). */
function plane(rows: string[]) {
    const width = rows[0].length;
    const height = rows.length;
    const values = rows
        .join("")
        .split("")
        .map((c) => (c === "#" ? 0 : 255));
    return { values, width, height };
}

/** The same grid centred on a larger sheet of paper — how much blank canvas surrounds a mark. */
function pad(rows: string[], width: number, height: number): string[] {
    const insetX = Math.floor((width - rows[0].length) / 2);
    const insetY = Math.floor((height - rows.length) / 2);
    const out: string[] = [];
    for (let y = 0; y < height; y++) {
        const row = rows[y - insetY];
        out.push(
            row === undefined
                ? ".".repeat(width)
                : ".".repeat(insetX) +
                      row +
                      ".".repeat(width - insetX - row.length),
        );
    }
    return out;
}

const noYield = async () => {};

const score = (a: string[], b: string[]) => {
    const left = plane(a);
    const right = plane(b);
    return scorePlanes(
        left.values,
        right.values,
        left.width,
        left.height,
        noYield,
    );
};

describe("edgeMask", () => {
    it("marks the pixels either side of a step, and nothing in a flat field", () => {
        const flat = plane(["....", "....", "....", "...."]);
        assert.equal(
            edgeMask(flat.values, flat.width, flat.height).reduce(
                (a, b) => a + b,
                0,
            ),
            0,
        );

        const line = plane(["..#..", "..#..", "..#..", "..#.."]);
        const mask = edgeMask(line.values, line.width, line.height);
        // The ink column and BOTH paper columns beside it: an edge is a step, and the step is
        // visible from either side of it.
        assert.deepEqual(Array.from(mask.slice(0, 5)), [0, 1, 1, 1, 0]);
    });

    it("clamps at the border rather than wrapping", () => {
        // A column of ink at x=0 must not read the last column as its left neighbour; if it did, a
        // mark against the left edge would answer an edge state that depends on the right edge.
        const edge = plane(["#..", "#..", "#.."]);
        const mask = edgeMask(edge.values, edge.width, edge.height);
        assert.deepEqual(Array.from(mask.slice(0, 3)), [1, 1, 0]);
    });

    it("ignores a step smaller than the gradient threshold", () => {
        const width = 3;
        const subtle = [0, EDGE_GRADIENT_THRESHOLD - 1, 0];
        assert.equal(
            edgeMask(subtle, width, 1).reduce((a, b) => a + b, 0),
            0,
        );
    });
});

describe("pixelCost", () => {
    it("is free inside the tolerance and full price from a clearly different tone", () => {
        assert.equal(pixelCost(0), 0);
        assert.equal(pixelCost(LUMA_TOLERANCE), 0);
        assert.equal(pixelCost(FULL_DIFFERENCE_DELTA), 1);
        assert.equal(pixelCost(255), 1);
        // Ramping between them, so a fill that drifted a shade still reads as mostly right.
        const half = (FULL_DIFFERENCE_DELTA + LUMA_TOLERANCE) / 2;
        assert.ok(Math.abs(pixelCost(half) - 0.5) < 1e-6, `${pixelCost(half)}`);
    });

    it("charges a mid-tone mark on paper as heavily as a black one", () => {
        // The old cost divided the gap by 255, so a mark whose own tone was mid-grey cost half of
        // what an identical black mark cost — the metric graded the ink rather than the absence.
        assert.equal(
            pixelCost(255 - FULL_DIFFERENCE_DELTA + 1),
            pixelCost(255),
        );
    });
});

describe("contentMask", () => {
    it("takes both frames' detail, widened by one pixel, and nothing else", () => {
        const left = plane(["....", ".##.", ".##.", "...."]);
        const right = plane(["....", "....", "....", "...."]);
        const mask = contentMask(
            edgeMask(left.values, left.width, left.height),
            edgeMask(right.values, right.width, right.height),
            left.width,
            left.height,
        );
        // The mark, the ring of paper its step is visible from, and the widening (every pixel on a
        // 4x4). A blank partner contributes nothing.
        assert.equal(
            mask.reduce((a: number, b: number) => a + b, 0),
            16,
        );

        const blank = plane(["....", "....", "....", "...."]);
        const blankEdges = edgeMask(blank.values, blank.width, blank.height);
        const empty = contentMask(
            blankEdges,
            blankEdges,
            blank.width,
            blank.height,
        );
        assert.equal(
            empty.reduce((a: number, b: number) => a + b, 0),
            0,
        );
    });
});

describe("scorePlanes", () => {
    const MARK = [
        ".........",
        ".........",
        "...###...",
        "...###...",
        "...###...",
        ".........",
        ".........",
    ];
    const SHIFTED = [
        ".........",
        ".........",
        "....###..",
        "....###..",
        "....###..",
        ".........",
        ".........",
    ];
    const ABSENT = [
        ".........",
        ".........",
        ".........",
        ".........",
        ".........",
        ".........",
        ".........",
    ];
    const EXTRA = [
        ".........",
        ".........",
        "...###...",
        "...###...",
        "...###...",
        ".......#.",
        ".........",
    ];

    it("answers exactly 100 for two identical planes", async () => {
        assert.equal(await score(MARK, MARK), 100);
    });

    it("charges a one-pixel raster shift far less than a missing mark", async () => {
        // Figma's SVG rasteriser and Skia cover the same edge with different sub-pixels, so a
        // one-pixel shift must stay nearly free.
        const shifted = await score(MARK, SHIFTED);
        const absent = await score(MARK, ABSENT);
        assert.ok(shifted > absent, `${shifted} should beat ${absent}`);
        assert.ok(shifted > 95, `a one-pixel shift scored ${shifted}`);
        assert.ok(absent < 90, `a missing mark scored ${absent}`);
    });

    it("averages BOTH directions, so the answer cannot depend on which side is the reference", async () => {
        // The two directions genuinely disagree, and the reason is worth knowing: only an EDGE
        // pixel of the SOURCE may search. A mark added into what was a flat region has no edge in
        // the reference to go looking for it, so reference→candidate pays the full price; from the
        // other side that same mark IS an edge and finds a partner two pixels away for almost
        // nothing. One direction alone would therefore score the same pair differently depending on
        // which frame the caller happened to pass first — and the parity page, the compare wall and
        // the spec lane do not all pass them in the same order.
        const left = plane(MARK);
        const right = plane(EXTRA);
        const leftEdges = edgeMask(left.values, left.width, left.height);
        const rightEdges = edgeMask(right.values, right.width, right.height);
        const forwards = meanCost(
            await directedCosts(
                left.values,
                right.values,
                leftEdges,
                rightEdges,
                left.width,
                left.height,
                noYield,
            ),
        );
        const backwards = meanCost(
            await directedCosts(
                right.values,
                left.values,
                rightEdges,
                leftEdges,
                right.width,
                right.height,
                noYield,
            ),
        );
        assert.ok(
            forwards > backwards * 2,
            `the added mark costs far more from the reference's side (${forwards} vs ${backwards})`,
        );
        assert.ok(
            (await score(MARK, EXTRA)) < 100,
            "an added mark is a difference",
        );
    });

    it("does not let blank canvas dilute a missing mark — issue #4290", async () => {
        // The score is over content, not canvas area, so the same missing mark scores the same
        // regardless of frame size.
        const sizes: Array<[number, number]> = [
            [9, 7],
            [21, 15],
            [41, 31],
        ];
        const scores = [];
        for (const [width, height] of sizes) {
            scores.push(
                await score(
                    pad(MARK, width, height),
                    pad(ABSENT, width, height),
                ),
            );
        }
        for (const scored of scores) {
            assert.equal(scored, scores[0], `${scores.join(" / ")}`);
            assert.ok(scored < 90, `a missing mark scored ${scored}`);
        }
    });

    it("still answers 100 for two frames that are blank together", async () => {
        // No content and no disagreement is a match by definition — and, less philosophically, the
        // only answer that is not a division by zero.
        assert.equal(await score(ABSENT, ABSENT), 100);
    });

    it("answers 100 for two DIFFERENT frames that a ground flattened to blank", async () => {
        // A plane cannot distinguish "nothing drawn" from ink matching the ground, so white ink on
        // white scores as a perfect match (e.g. `TextMaxLinesTruncated` in
        // `design-catalog-wear-m3`). The fix lives in `scoreOnEveryGround`, which also scores on
        // black and keeps the worse number.
        const whiteInkOnWhite = new Array(6 * 6).fill(255);
        const differentWhiteInkOnWhite = new Array(6 * 6).fill(255);
        assert.equal(
            await scorePlanes(
                whiteInkOnWhite,
                differentWhiteInkOnWhite,
                6,
                6,
                noYield,
            ),
            100,
            "two flattened planes are indistinguishable from two empty ones",
        );
    });

    it("is symmetric — swapping the two sides answers the same number", async () => {
        "";
        assert.equal(await score(MARK, SHIFTED), await score(SHIFTED, MARK));
    });

    it("lets a difference within the luma tolerance through for free", async () => {
        // The two rasterisers disagree slightly about a shared edge on every pixel. Accumulating
        // that would turn "these match" into a percentage that slowly drifts with image size.
        const width = 4;
        const height = 4;
        const flat = new Array(width * height).fill(200);
        const nudged = flat.map((v) => v + LUMA_TOLERANCE);
        assert.equal(
            await scorePlanes(flat, nudged, width, height, noYield),
            100,
        );
        const beyond = flat.map((v) => v + LUMA_TOLERANCE + 40);
        assert.ok(
            (await scorePlanes(flat, beyond, width, height, noYield)) < 100,
        );
    });

    it("charges a flat-region change in full — only EDGE pixels may search", async () => {
        // A large uniform region could otherwise absorb any change inside it by pointing every
        // pixel at some neighbour of the same value.
        const width = 11;
        const height = 11;
        const flat = new Array(width * height).fill(255);
        const blot = flat.slice();
        for (let y = 3; y < 8; y++)
            for (let x = 3; x < 8; x++) blot[y * width + x] = 0;
        const scored = await scorePlanes(flat, blot, width, height, noYield);
        assert.ok(scored < 85, `a blot on a blank field scored ${scored}`);
    });

    it("yields between chunks of rows, so the page keeps painting", async () => {
        // Not a detail: a full catalog performs dozens of comparisons, and without the yield the
        // tab stops accepting input for the duration of each one.
        let yields = 0;
        const size = 24;
        const flat = new Array(size * size).fill(255);
        await scorePlanes(flat, flat, size, size, async () => {
            yields += 1;
        });
        // Every eighth row, in each of the two directions.
        assert.equal(yields, Math.floor(size / 8) * 2);
    });

    it("never answers outside 0–100", async () => {
        const width = 6;
        const height = 6;
        const black = new Array(width * height).fill(0);
        const white = new Array(width * height).fill(255);
        const worst = await scorePlanes(black, white, width, height, noYield);
        assert.ok(worst >= 0 && worst <= 100, `${worst}`);
    });
});
