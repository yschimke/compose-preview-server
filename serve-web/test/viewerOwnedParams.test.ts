// Which query parameters the viewer manages.
//
// `cpUrlState.sync` DROPS any owned parameter the caller does not supply, so this list is
// load-bearing in both directions and neither mistake announces itself: over-claim and the viewer
// deletes someone else's parameter on the next slider drag; under-claim and a stale value outlives
// the control that set it, so the address bar describes a page that is not on screen.

import assert from "node:assert/strict";
import {
    URL_STATE_PARAMS,
    isWatchFaceStateParam,
    ownsUrlParam,
    triStateBoolParam,
    uncontrolledWatchFaceParams,
} from "../src/viewer/ownedParams.js";

describe("ownsUrlParam", () => {
    it("claims every parameter the viewer writes by name", () => {
        // Circular on its own — it only says the list agrees with itself. The cases below are what
        // pin the MEMBERSHIP, which is the part that can actually be wrong.
        for (const name of URL_STATE_PARAMS) {
            assert.equal(ownsUrlParam(name), true, name);
        }
    });

    it("claims the overlay toggles, which are URL state and not socket-only", () => {
        // These are collected by `overrides()` — the map `query()` serialises — so a ticked box
        // rides the page URL, the export links and the stream's connect query. Collected only into
        // the live-socket map they would reach the daemon and nowhere else: unshareable, not
        // restorable by Back, and applied a frame late by the onopen replay rather than arriving
        // with `stream/start`.
        assert.equal(ownsUrlParam("touchOverlay"), true);
        assert.equal(ownsUrlParam("gestures"), true);
        assert.equal(ownsUrlParam("focus"), true);
    });

    it("claims the size fields, so switching size mode cannot strand one", () => {
        // Each mode writes only its own fields, so the ones it does not write must be dropped —
        // which only happens if they are owned.
        for (const name of [
            "sizeMode",
            "widthPx",
            "heightPx",
            "minWidthPx",
            "minHeightPx",
            "maxWidthPx",
            "maxHeightPx",
        ]) {
            assert.equal(ownsUrlParam(name), true, name);
        }
    });

    it("claims the lanes and framings that have no control of their own", () => {
        // `svg` and `zoom` are written by a pressed button rather than by a form field, and both
        // were memory-only: a page read as SVG at full width reopened as a fitted PNG. Owned, so
        // the press that turns either off also removes its parameter.
        assert.equal(ownsUrlParam("svg"), true);
        assert.equal(ownsUrlParam("zoom"), true);
    });

    it("claims the spec lane's source, which the picker supplies on every sync", () => {
        // Over-claiming deletes a parameter the viewer does not write; this one is safe because
        // `syncUrl` emits it from the picker in every mode, not only while the lane is up.
        assert.equal(ownsUrlParam("specSource"), true);
    });

    it("claims the exploded view and every one of its knobs", () => {
        // The angle someone tuned is part of the link they copy; a knob left unowned would survive
        // in the URL after the view it belongs to was turned off.
        for (const name of [
            "exploded",
            "explodeTilt",
            "explodeSpin",
            "explodeGap",
            "explodeDepth",
        ]) {
            assert.equal(ownsUrlParam(name), true, name);
        }
    });

    it("claims the open-ended families, which the preview names", () => {
        // Author knobs, Remote Compose seeds and watch-face slot/config keys cannot be enumerated.
        assert.equal(ownsUrlParam("knob.label"), true);
        assert.equal(ownsUrlParam("knob.count"), true);
        assert.equal(ownsUrlParam("rc.tint"), true);
        assert.equal(ownsUrlParam("wf.slot.1.type"), true);
        assert.equal(ownsUrlParam("wf.config.color_style_setting"), true);
        assert.equal(ownsUrlParam("wf.heartRate"), true);
        assert.equal(ownsUrlParam("ambient"), true);
        assert.equal(ownsUrlParam("ambientBurnIn"), true);
        assert.equal(ownsUrlParam("ambientLowBit"), true);
        assert.equal(ownsUrlParam("clockEpochMillis"), true);
        assert.equal(ownsUrlParam("wfRenderer"), true);
    });

    it("leaves ALONE the parameters that are not the viewer's", () => {
        // The consequential half. `token` is the one that matters most: claiming it would strip a
        // token-gated visitor's credential from the address bar on the first control edit.
        for (const name of [
            "token",
            "session",
            "at",
            "reference",
            "q",
            "preview",
            "format",
        ]) {
            assert.equal(ownsUrlParam(name), false, name);
        }
    });

    it("matches a prefix only at the START of the name", () => {
        // `myknob.x` is not an author knob, and treating it as one would delete it.
        assert.equal(ownsUrlParam("myknob.label"), false);
        assert.equal(ownsUrlParam("src.rc.tint"), false);
        assert.equal(ownsUrlParam("dwf.slot.1.type"), false);
    });

    it("is exact about names, not a substring test", () => {
        // `mode` is owned; `uiMode` is owned separately by name. Something merely CONTAINING an
        // owned name is not owned — a substring rule would claim `nodeMode` or `demode`.
        assert.equal(ownsUrlParam("mode"), true);
        assert.equal(ownsUrlParam("nodeMode"), false);
        assert.equal(ownsUrlParam("modes"), false);
        assert.equal(ownsUrlParam("scrollTo"), false);
    });

    it("lists no duplicates, so the set is what it appears to be", () => {
        assert.equal(new Set(URL_STATE_PARAMS).size, URL_STATE_PARAMS.length);
    });
});

describe("uncontrolledWatchFaceParams", () => {
    it("preserves ambientBurnIn, ambientLowBit and uncontrolled wf.* params when no control owns them", () => {
        const params = new URLSearchParams(
            "ambient=true&ambientBurnIn=true&ambientLowBit=false&wf.is24Hour=true&wf.slot.1.type=SHORT_TEXT&token=secret",
        );
        const controlled = new Set(["ambient", "wf.slot.1.type"]);
        const unseeded = new Set<string>();
        assert.deepEqual(
            uncontrolledWatchFaceParams(params, controlled, unseeded),
            {
                ambientBurnIn: "true",
                ambientLowBit: "false",
                "wf.is24Hour": "true",
            },
        );
    });

    it("preserves ambient and clockEpochMillis on non-watch-face pages without .cp-wf-field controls", () => {
        const params = new URLSearchParams(
            "ambient=true&ambientBurnIn=true&clockEpochMillis=1700000000000&wfRenderer=dwf-rc",
        );
        assert.deepEqual(
            uncontrolledWatchFaceParams(params, new Set(), new Set()),
            {
                ambient: "true",
                ambientBurnIn: "true",
                clockEpochMillis: "1700000000000",
            },
        );
        assert.equal(isWatchFaceStateParam("wfRenderer"), false);
    });

    it("withholds unseeded parameters and ignores empty values", () => {
        const params = new URLSearchParams(
            "ambientBurnIn=true&ambientLowBit=&wf.is24Hour=true",
        );
        assert.deepEqual(
            uncontrolledWatchFaceParams(
                params,
                new Set(),
                new Set(["ambientBurnIn"]),
            ),
            {
                "wf.is24Hour": "true",
            },
        );
    });

    it("normalises tri-state boolean URL values cleanly", () => {
        assert.equal(triStateBoolParam(null), "");
        assert.equal(triStateBoolParam(""), "");
        assert.equal(triStateBoolParam("1"), "true");
        assert.equal(triStateBoolParam("TRUE"), "true");
        assert.equal(triStateBoolParam("0"), "false");
        assert.equal(triStateBoolParam("FALSE"), "false");
    });
});
