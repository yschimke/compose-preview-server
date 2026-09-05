// Coalescing a drag into one call.
//
// The font-scale slider fires `input` once per stop; every one of those used to be a `/render`
// the daemon had to serialise, and most of the burst came back as a retryable 503. This is the
// trailing-edge coalescing that turns a drag into the single render the visitor meant.

import assert from "node:assert/strict";
import {
    CONTINUOUS_EDIT_DEBOUNCE_MS,
    debounced,
} from "../src/viewer/debounce.js";

const tick = (ms: number) => new Promise((r) => setTimeout(r, ms));

describe("debounced", () => {
    it("runs a burst once, after it stops", async () => {
        let calls = 0;
        const run = debounced(() => calls++, 10);
        // Fifteen stops of a slider drag, the shape the bug had.
        for (let i = 0; i < 15; i++) run();
        assert.equal(
            calls,
            0,
            "nothing fires while the control is still moving",
        );
        await tick(30);
        assert.equal(calls, 1);
    });

    it("is trailing, not leading — the value a drag ENDS on is the one worth rendering", async () => {
        const seen: number[] = [];
        let value = 0;
        const run = debounced(() => seen.push(value), 10);
        value = 1;
        run();
        value = 2;
        run();
        await tick(30);
        assert.deepEqual(seen, [2]);
    });

    it("runs again for a later burst", async () => {
        let calls = 0;
        const run = debounced(() => calls++, 10);
        run();
        await tick(30);
        run();
        await tick(30);
        assert.equal(calls, 2);
    });

    it("gives each wrapper its own timer, so one control cannot swallow another's edit", async () => {
        let a = 0;
        let b = 0;
        const runA = debounced(() => a++, 10);
        const runB = debounced(() => b++, 10);
        runA();
        runB();
        await tick(30);
        assert.equal(a, 1);
        assert.equal(b, 1);
    });

    it("cancels a pending run", async () => {
        let calls = 0;
        const run = debounced(() => calls++, 10);
        run();
        run.cancel();
        await tick(30);
        assert.equal(calls, 0);
        // Cancelling an idle wrapper is a no-op, not a throw.
        run.cancel();
    });

    it("waits long enough to swallow a hand-moved slider", () => {
        // A drag emits steps tens of milliseconds apart; anything under that renders mid-travel.
        assert.ok(CONTINUOUS_EDIT_DEBOUNCE_MS >= 100);
        // And short enough that letting go still feels immediate next to a ~0.25-1.1s render.
        assert.ok(CONTINUOUS_EDIT_DEBOUNCE_MS <= 300);
    });
});
