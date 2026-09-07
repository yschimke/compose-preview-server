// The diff lane's badges: one at a time at rest, all of them while the control is held.
//
// Guards a failure that is only visible as a *feeling* — a sheet covered in red pills reads as a
// page that has broken rather than as a lane doing its job — so the rule is stated here rather than
// left to whoever next reads the CSS.

import assert from "node:assert/strict";
import { DIFF_ALL_CLASS, showsEveryBadge } from "../src/design/lanes.js";

describe("the diff lane's badges", () => {
    it("shows every badge only while the control is held, and only in the lane", () => {
        // The lane's resting state is the SHEET. Forty red pills over the drawing being judged is
        // the annotated export this page was rescued from, so all of them at once is a gesture you
        // hold rather than a state you land in. See `docs/design/COMPARE_NAVIGATION.md`, F5.
        assert.equal(showsEveryBadge("diff", true), true);
        assert.equal(showsEveryBadge("diff", false), false);
        // And a `held` that outlived the lane cannot arm the class: a sheet that opened covered in
        // badges because of a pointerup the reader never connected to this control would read as
        // the lane being broken.
        assert.equal(showsEveryBadge("code", true), false);
        assert.equal(showsEveryBadge("design", true), false);
    });

    it("names the class serve.css keys the reveal off", () => {
        // Pinned because the rule and the element that arms it live in different languages, and a
        // rename on one side is invisible from the other.
        assert.equal(DIFF_ALL_CLASS, "cp-page-diff-all");
    });
});
