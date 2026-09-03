// The D4 gate: the report describes the frame on screen, not the controls that asked for one.

import assert from "node:assert/strict";
import {
    reportFollowsDisplayedFrame,
    reportMayCarryLocator,
} from "../src/viewer/reportFrame.js";

const REQUESTED =
    "http://host/compose-m3/render/button-filled.png?uiMode=dark&knob.label=Send";

describe("report follows the displayed frame", () => {
    it("holds off until a frame has landed", () => {
        // Nothing fetched yet: the server-rendered image is on screen and the server's own body
        // already describes it. Rewriting from the controls here would be the same defect early.
        assert.equal(reportFollowsDisplayedFrame(REQUESTED, null), false);
        assert.equal(reportFollowsDisplayedFrame(REQUESTED, ""), false);
    });

    it("recomposes once the landed frame is the requested one", () => {
        assert.equal(reportFollowsDisplayedFrame(REQUESTED, REQUESTED), true);
    });

    it("matches a relative landed URL against the absolute one the links carry", () => {
        // `data-cp-src` is written from the fetch URL, which is path-relative; `#cp-url-png` is
        // absolute. Comparing the raw strings would never match and the report would freeze.
        assert.equal(
            reportFollowsDisplayedFrame(
                REQUESTED,
                "/compose-m3/render/button-filled.png?uiMode=dark&knob.label=Send",
            ),
            true,
        );
    });

    it("declines while a requested frame is still in flight", () => {
        // The reporter moved a control; the previous frame is still on the stage. The field keeps
        // the body that describes it rather than gaining one for pixels nobody has seen.
        assert.equal(
            reportFollowsDisplayedFrame(
                REQUESTED,
                "http://host/compose-m3/render/button-filled.png?uiMode=light",
            ),
            false,
        );
    });

    it("declines after a failed render, which leaves the previous frame up", () => {
        // Same state as in-flight from this predicate's point of view, and deliberately so: what
        // decides is which pixels are on the stage, not why the newer ones are missing.
        assert.equal(
            reportFollowsDisplayedFrame(
                REQUESTED,
                "/compose-m3/render/button-filled.png",
            ),
            false,
        );
    });

    it("tells one lane's frame from another's", () => {
        assert.equal(
            reportFollowsDisplayedFrame(
                "http://host/compose-m3/render/button-filled.svg?uiMode=dark",
                "/compose-m3/render/button-filled.png?uiMode=dark",
            ),
            false,
        );
    });

    it("answers false rather than throwing on an unparseable landed URL", () => {
        assert.equal(reportFollowsDisplayedFrame(REQUESTED, "http://"), false);
    });
});

describe("report may carry a locator", () => {
    it("names a comparison from the lanes whose pixels the stage image is", () => {
        for (const stage of ["snapshot", "svg", "spec"]) {
            assert.equal(reportMayCarryLocator(stage), true, stage);
        }
    });

    it("withholds it from every lane that paints its own pixels", () => {
        // Live, Wasm and the Remote Compose players draw into a canvas or an iframe and apply
        // overrides in place, so `data-cp-src` names a frame the reporter stopped looking at
        // several interactions ago. `motion` plays an animation the still does not describe.
        for (const stage of [
            "live",
            "wasm",
            "rc",
            "rc-wasm",
            "motion",
            "source",
        ]) {
            assert.equal(reportMayCarryLocator(stage), false, stage);
        }
    });

    it("withholds it from a lane it has never heard of", () => {
        // An allowlist: the next interactive lane must not be indexable by default. That failure
        // would be silent and visible only in the filed issue.
        assert.equal(reportMayCarryLocator("holographic"), false);
        assert.equal(reportMayCarryLocator(""), false);
    });
});
