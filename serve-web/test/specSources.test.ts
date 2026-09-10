// Which source the spec lane compares against, as a table.

import assert from "node:assert/strict";

import {
    activeSource,
    changesSource,
    closesSource,
    isSpecSource,
    offersChoice,
    sourceForParam,
    sourceNote,
    sourceParam,
    type SpecSource,
} from "../src/spec/sources.js";

const kit: SpecSource = {
    id: "kit",
    label: "Figma",
    src: "/m3/reference/a.png",
};
const parallel: SpecSource = {
    id: "parallel",
    label: "wear-m3-catalog",
    src: "/wear-m3-catalog/render/b.png",
    provenance:
        "wear-m3-catalog's own render, under that catalog's theme and knobs.",
};

describe("spec lane sources", () => {
    it("takes the pressed source", () => {
        assert.equal(activeSource([kit, parallel], "parallel")?.id, "parallel");
    });

    it("falls back to the first when nothing is pressed", () => {
        // Markup alone describes the initial state; a picker that arrives with nothing pressed still
        // compares against something rather than going blank.
        assert.equal(activeSource([kit, parallel], null)?.id, "kit");
    });

    it("falls back to the first when the pressed id is not one of the sources", () => {
        assert.equal(activeSource([kit, parallel], "gone")?.id, "kit");
    });

    it("has no active source when the lane has none", () => {
        assert.equal(activeSource([], "kit"), null);
    });

    it("knows a specification from another catalog's render", () => {
        // Which one it is decides whether the published spec verdict, the kit's annotations and the
        // word "Spec" on the panel still describe what is on the stage.
        assert.equal(isSpecSource(kit), true);
        assert.equal(isSpecSource(parallel), false);
        assert.equal(
            isSpecSource(null),
            true,
            "a lane with no picker has only ever shown the kit reference",
        );
    });

    it("offers no choice for the single-source lane every unpaired catalog has", () => {
        assert.equal(offersChoice([kit]), false);
        assert.equal(offersChoice([]), false);
        assert.equal(offersChoice([kit, parallel]), true);
    });

    it("treats re-picking the showing source as a no-op", () => {
        // Re-entering costs a raster request and a fresh normalisation pass.
        assert.equal(changesSource([kit, parallel], "kit", "kit"), false);
    });

    it("switches between two real sources", () => {
        assert.equal(changesSource([kit, parallel], "kit", "parallel"), true);
        assert.equal(changesSource([kit, parallel], "parallel", "kit"), true);
    });

    it("refuses a source the lane does not offer", () => {
        assert.equal(changesSource([kit, parallel], "kit", "invented"), false);
    });

    it("closes the comparison when its pressed source chip is pressed again", () => {
        assert.equal(closesSource(true, "kit", "kit"), true);
        assert.equal(closesSource(true, "parallel", "parallel"), true);
        assert.equal(closesSource(true, "kit", "parallel"), false);
        assert.equal(closesSource(false, "parallel", "parallel"), false);
    });

    it("says nothing when the pressed default is implicit", () => {
        assert.equal(changesSource([kit, parallel], null, "kit"), false);
        assert.equal(changesSource([kit, parallel], null, "parallel"), true);
    });

    it("states the caveat for another catalog's render", () => {
        assert.match(sourceNote(parallel), /own render/);
    });

    it("gives the imported spec no caveat to state", () => {
        // A specification needs none: comparing this publish's render against this publish's spec is
        // the comparison the lane exists for.
        assert.equal(sourceNote(kit), "");
        assert.equal(sourceNote(null), "");
    });

    it("names only a source that departs from the lane's default", () => {
        // The first source is the one the server presses, so the page already opens on it. Pinning
        // it into the address bar would put a redundant parameter on every copied link.
        assert.equal(sourceParam([kit, parallel], "kit"), "");
        assert.equal(sourceParam([kit, parallel], null), "");
        assert.equal(sourceParam([kit, parallel], "parallel"), "parallel");
    });

    it("names nothing for a lane that offers no choice", () => {
        // No picker, no state: a catalog with no pairing writes the URL it always wrote.
        assert.equal(sourceParam([kit], "kit"), "");
        assert.equal(sourceParam([], null), "");
    });

    it("restores the source a URL names", () => {
        assert.equal(sourceForParam([kit, parallel], "parallel"), "parallel");
        assert.equal(sourceForParam([kit, parallel], "kit"), "kit");
    });

    it("falls back to the default for a source the lane cannot offer", () => {
        // A stale or mistyped `?specSource=` opens the pair the page always opened on rather than
        // a blank stage; the next sync drops the parameter it could not honour.
        assert.equal(sourceForParam([kit, parallel], "invented"), "kit");
        assert.equal(sourceForParam([kit, parallel], ""), "kit");
        assert.equal(sourceForParam([], "parallel"), "");
    });

    it("round-trips a picked source through the URL", () => {
        // The property the address bar needs: what a press writes is what a reload presses back.
        for (const picked of ["kit", "parallel"]) {
            const written = sourceParam([kit, parallel], picked);
            assert.equal(sourceForParam([kit, parallel], written), picked);
        }
    });
});
