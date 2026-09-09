// What a Back/Forward restore still has to do after hydration.
//
// The viewer's pop handler falls through to `onControlsChanged()`, whose last resort on a fully
// static published catalog is `setMode("wasm")`. So dispatching it for an entry hydration already
// finished is not a wasted call, it is a navigation: Back out of Fit width left the lane, mounted
// the Wasm app and pushed an entry of its own.

import assert from "node:assert/strict";
import {
    RESTORED_IN_PLACE,
    restoredInPlace,
} from "../src/viewer/historyRestore.js";
import { ownsUrlParam } from "../src/viewer/ownedParams.js";

describe("restoredInPlace", () => {
    it("finishes a zoom-only restore without the render controls", () => {
        // The reported case. `zoom` frames pixels that already exist; nothing about the render it
        // frames has moved, so there is nothing to ask a transport for.
        assert.equal(restoredInPlace("?zoom=width", ""), true);
        assert.equal(restoredInPlace("", "?zoom=width"), true);
    });

    it("finishes a spec-source restore, which hydration re-enters the lane for", () => {
        // `pickSpecSource` owns that request and `hydrateFromUrl` has already made it.
        assert.equal(
            restoredInPlace("?mode=spec&specSource=parallel", "?mode=spec"),
            true,
        );
        assert.equal(
            restoredInPlace(
                "?mode=spec&specView=slider",
                "?mode=spec&specView=diff",
            ),
            true,
        );
    });

    it("dispatches the controls for an axis that changes the render", () => {
        // The reason the fallback exists at all: these are overrides a transport has to be told
        // about, and the entry is not restored until it has been.
        assert.equal(restoredInPlace("?device=pixel_7", ""), false);
        assert.equal(
            restoredInPlace("?zoom=width", "?zoom=width&focus"),
            false,
        );
        assert.equal(
            restoredInPlace("?knob.title=a", "?knob.title=b"),
            false,
            "author knobs are owned by prefix, not by name",
        );
    });

    it("dispatches when a render axis moves ALONGSIDE an in-place one", () => {
        // The whole set has to be in-place, not just one member of it — otherwise the one entry
        // that carries both a zoom and a locale silently loses the locale.
        assert.equal(
            restoredInPlace("?zoom=width&localeTag=en", "?localeTag=fr"),
            false,
        );
    });

    it("ignores parameters the viewer does not own", () => {
        // A credential, an inspect layer or an annotation selection says nothing about what the
        // stage should draw — and `onControlsChanged` reads none of them, so dispatching for one
        // reaches the dead wasm fallback for a non-reason.
        assert.equal(
            restoredInPlace("?token=abc&inspect=a11y", "?token=abc"),
            true,
        );
        assert.equal(restoredInPlace("?annotate=typography", ""), true);
    });

    it("treats an unchanged entry as nothing left to apply", () => {
        assert.equal(
            restoredInPlace("?device=pixel_7", "?device=pixel_7"),
            true,
        );
        assert.equal(restoredInPlace("", ""), true);
    });

    it("compares every value of a repeated parameter", () => {
        // `get` would read the first and call a real change no change.
        assert.equal(
            restoredInPlace("?knob.tag=a&knob.tag=b", "?knob.tag=a&knob.tag=c"),
            false,
        );
    });

    it("names only parameters the viewer owns as restorable in place", () => {
        // A member the viewer does not own could never be filtered INTO the check, so the list
        // would be quietly describing nothing.
        for (const name of RESTORED_IN_PLACE) {
            assert.equal(ownsUrlParam(name), true, name);
        }
    });
});
