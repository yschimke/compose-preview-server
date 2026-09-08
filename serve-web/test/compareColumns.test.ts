// Which picture leads the wall's pair, and what the column that is not the render calls itself.
//
// The failure this guards is silent by construction: both columns keep showing real pictures and
// the score keeps being right, so a reversed pair reads as a working page that has quietly swapped
// which artifact is the design and which is the render.

import assert from "node:assert/strict";
import { specLeadsColumns, targetHeadLabel } from "../src/compare/columns.js";

describe("compare wall columns", () => {
    it("puts the baseline first on every lane", () => {
        // The house rule everywhere two pictures are shown together: baseline left, diff, ours
        // right. `svg` and `rc` used to read the other way round, which meant pressing a baseline
        // button swapped both pictures' sides as well as relabelling both headers.
        assert.equal(specLeadsColumns("reference"), true);
        assert.equal(specLeadsColumns("parallel"), true);
        assert.equal(specLeadsColumns("svg"), true);
        assert.equal(specLeadsColumns("rc"), true);
    });

    it("names the column after the lane it is actually showing", () => {
        assert.equal(targetHeadLabel("reference", "Figma"), "Figma");
        assert.equal(
            targetHeadLabel("parallel", "Figma", "Wear M3"),
            "Wear M3",
        );
        assert.equal(targetHeadLabel("rc", "Figma"), "Remote Compose");
        assert.equal(targetHeadLabel("svg", "Figma"), "SVG");
    });

    it("falls back to the neutral name when the catalog names no design tool", () => {
        // A catalog whose references are plain PNGs/mocks has no tool to be named after, and an
        // empty header over the design column is worse than a generic one.
        assert.equal(targetHeadLabel("reference", ""), "Design reference");
    });
});
