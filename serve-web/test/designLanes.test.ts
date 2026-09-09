// The two axes the sheet's controls ask about, and the badges the diff axis paints.
//
// The interesting half of this file guards failures that are only visible as a *feeling* — a sheet
// covered in red pills reads as a page that has broken rather than as a control doing its job, and a
// segmented group with a permanently dead button reads as something being wrong with the catalog. So
// the rules are stated here rather than left to whoever next reads the CSS.

import assert from "node:assert/strict";
import {
    DIFF_ALL_CLASS,
    allowsBaseline,
    baselineAfterLane,
    baselineOf,
    laneOf,
    needsParallel,
    needsRenders,
    scoreKey,
    showsEveryBadge,
    stageState,
} from "../src/design/lanes.js";

describe("what the sheet shows", () => {
    it("draws a render in the slots for either catalog, and neither for the design", () => {
        // `cp-page-swap-on` means "a render stands in the design's slots"; WHICH render is the
        // parallel class's business, so a stylesheet rule keyed on the first keeps working when a
        // second catalog arrives.
        assert.deepEqual(stageState("code", "off"), {
            "cp-page-swap-on": true,
            "cp-page-hide-design": true,
            "cp-page-parallel-on": false,
            "cp-page-diff-on": false,
        });
        assert.deepEqual(stageState("parallel", "off"), {
            "cp-page-swap-on": true,
            "cp-page-hide-design": true,
            "cp-page-parallel-on": true,
            "cp-page-diff-on": false,
        });
        assert.deepEqual(stageState("design", "off"), {
            "cp-page-swap-on": false,
            "cp-page-hide-design": false,
            "cp-page-parallel-on": false,
            "cp-page-diff-on": false,
        });
    });

    it("scores independently of what it shows", () => {
        // The whole point of the split: what is being measured changes no pixel of what is drawn.
        assert.equal(stageState("design", "code")["cp-page-diff-on"], true);
        assert.equal(stageState("design", "code")["cp-page-swap-on"], false);
    });

    it("reads an unknown value as this catalog's own renders", () => {
        assert.equal(laneOf("parallel"), "parallel");
        assert.equal(laneOf("diff"), "code", "the axis that no longer exists");
        assert.equal(laneOf(null), "code");
        assert.equal(baselineOf("diff"), "off");
        assert.equal(baselineOf("parallel"), "parallel");
    });

    it("fetches a catalog's renders only when a pairing names it", () => {
        // The sibling's images come off ANOTHER catalog's daemon, so a reader who never asks for it
        // must never cost it a request — including when it is only the baseline and never shown.
        assert.equal(needsParallel("code", "off"), false);
        assert.equal(needsParallel("code", "parallel"), true);
        assert.equal(needsParallel("parallel", "off"), true);
        assert.equal(needsRenders("design", "off"), false);
        assert.equal(needsRenders("design", "code"), true);
    });
});

describe("what it is diffed against", () => {
    it("never offers a source as its own baseline", () => {
        // The answer is 0.0% in every slot by construction, so the control would be one whose only
        // possible outcome is "no difference, obviously".
        assert.equal(allowsBaseline("code", "code", true), false);
        assert.equal(allowsBaseline("code", "design", true), true);
        assert.equal(allowsBaseline("design", "code", true), true);
        // …and "off" is always available, from anywhere.
        assert.equal(allowsBaseline("code", "off", false), true);
    });

    it("offers the sibling only on a page that carries its renders", () => {
        assert.equal(allowsBaseline("code", "parallel", false), false);
        assert.equal(allowsBaseline("code", "parallel", true), true);
    });

    it("treats a flip onto the baseline as a swap, not a contradiction", () => {
        // Showing ours against Figma, then asking to see Figma: the reader means "and now from the
        // other side", so the pair survives and the numbers do not move. Falling to `off` instead
        // would blank every badge on the gesture most likely to mean "how far is it, from here?".
        assert.equal(
            baselineAfterLane("code", "design", "design", false),
            "code",
        );
        assert.equal(
            baselineAfterLane("code", "parallel", "parallel", true),
            "code",
        );
        // A baseline that is still legal is left exactly where the reader put it.
        assert.equal(
            baselineAfterLane("code", "parallel", "design", true),
            "design",
        );
        // And a reader who was scoring nothing keeps scoring nothing — arriving in a lane with a
        // number you never asked for is the surprise this avoids.
        assert.equal(baselineAfterLane("code", "design", "off", true), "off");
    });

    it("remembers a score against the PAIR, not against the node", () => {
        // Flipping the baseline asks a different question of the same slot. A cache keyed on the
        // node alone would answer the new question with the old number.
        assert.notEqual(
            scoreKey("code", "design", "1:10"),
            scoreKey("code", "parallel", "1:10"),
        );
        assert.equal(
            scoreKey("code", "design", "1:10"),
            scoreKey("code", "design", "1:10"),
        );
    });
});

describe("the diff axis's badges", () => {
    it("shows every badge only while a control is held, and only while scoring", () => {
        // The resting state is the SHEET. Forty red pills over the drawing being judged is the
        // annotated export this page was rescued from, so all of them at once is a gesture you hold
        // rather than a state you land in. See `docs/design/COMPARE_NAVIGATION.md`, F5.
        assert.equal(showsEveryBadge("design", true), true);
        assert.equal(showsEveryBadge("parallel", true), true);
        assert.equal(showsEveryBadge("design", false), false);
        // And a `held` that outlived the scoring cannot arm the class: a sheet that lit up covered
        // in badges because of a pointerup the reader never connected to this control would read as
        // the page being broken.
        assert.equal(showsEveryBadge("off", true), false);
    });

    it("names the class serve.css keys the reveal off", () => {
        // Pinned because the rule and the element that arms it live in different languages, and a
        // rename on one side is invisible from the other.
        assert.equal(DIFF_ALL_CLASS, "cp-page-diff-all");
    });
});
