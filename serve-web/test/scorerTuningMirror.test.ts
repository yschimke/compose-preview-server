// The browser scorer's constants against the offline engine's.
//
// `src/scorer/tuning.ts` is what the live scorer imports and where each number's rationale is
// written down; `@design-parity/known-differences`' tuning is what the offline engines read. Every
// one of them is load-bearing to the number that comes out, so a value changed on one side and not
// the other is a silent divergence between the browser and the offline run — and one no fixture can
// catch, since both engines would be measured against expectations generated with their own
// constants.
//
// This used to be a cross-repository test in compose-ai-tools' copy of the engine, which skipped
// without a checkout of this repository. Now that serve-web depends on the published engine it can
// compare the two directly, on every run.

import assert from "node:assert/strict";

import { PLANE_TUNING } from "@design-parity/known-differences/known-difference-plane";
import { SCORE_TUNING } from "@design-parity/known-differences/known-difference-tuning";

import * as tuning from "../src/scorer/tuning.js";

describe("scorer tuning mirrors @design-parity/known-differences", () => {
    for (const name of [
        "SCORE_VERSION",
        "MAX_SIDE",
        "EDGE_SEARCH_RADIUS",
        "EDGE_POSITION_COST",
        "EDGE_GRADIENT_THRESHOLD",
        "LUMA_TOLERANCE",
        "FULL_DIFFERENCE_DELTA",
        "CONTENT_DILATION",
    ] as const) {
        it(`${name} matches the score tuning`, () => {
            assert.equal(tuning[name], SCORE_TUNING[name]);
        });
    }

    for (const name of [
        "BOX_SAMPLE_SIDE",
        "BOX_COLOUR_TOLERANCE",
        "MIN_BOX_COVERAGE",
        "SHEET_TOLERANCE",
    ] as const) {
        it(`${name} matches the plane tuning`, () => {
            assert.equal(tuning[name], PLANE_TUNING[name]);
        });
    }

    // `SCAFFOLD_SHEETS` decides whether an opaque capture is cropped at all, so a sheet added on one
    // side alone is a content box measured two ways.
    it("SCAFFOLD_SHEETS matches the plane tuning", () => {
        assert.deepEqual(
            tuning.SCAFFOLD_SHEETS.map((sheet) => [...sheet]),
            PLANE_TUNING.SCAFFOLD_SHEETS,
        );
    });

    // The grounds are CSS strings here and RGB triples there, so they are compared by colour rather
    // than by spelling.
    it("COMPARISON_GROUNDS name the engine's grounds", () => {
        const triples = tuning.COMPARISON_GROUNDS.map((hex) => [
            Number.parseInt(hex.slice(1, 3), 16),
            Number.parseInt(hex.slice(3, 5), 16),
            Number.parseInt(hex.slice(5, 7), 16),
        ]);
        assert.deepEqual(triples, SCORE_TUNING.COMPARISON_GROUNDS);
    });

    // …and the browser carries the same grounds a second time, as triples, for the path that
    // composites in arithmetic. A ground added to one spelling and not the other would score the
    // design-reference lane and the SVG lane on different ground sets.
    it("COMPARISON_GROUND_RGB names the engine's grounds", () => {
        assert.deepEqual(
            tuning.COMPARISON_GROUND_RGB.map((ground) => [...ground]),
            SCORE_TUNING.COMPARISON_GROUNDS,
        );
    });
});
