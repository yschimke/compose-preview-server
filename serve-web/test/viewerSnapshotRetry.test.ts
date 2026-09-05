// Which /render refusals the viewer asks again about, and what it says while it waits.
//
// The rule this file pins is the one the viewer got wrong: retryability is a property of the
// STATUS, not of whether the refusal happened to carry the dropped-overrides header. The public
// server's busiest refusal — `503 render busy; retry shortly` + `Retry-After: 2` — carries no such
// header, and was therefore reported as a permanent failure of a preview that renders fine.

import assert from "node:assert/strict";
import {
    isRetryableSnapshotFailure,
    snapshotFailureMessage,
    snapshotRetryWaitMs,
    willRetrySnapshot,
    type SnapshotFailure,
} from "../src/viewer/snapshotRetry.js";

const failure = (over: Partial<SnapshotFailure> = {}): SnapshotFailure => ({
    status: 503,
    dropped: "",
    retryAfterSeconds: 0,
    ...over,
});

describe("snapshot retry classification", () => {
    it("retries the load-shed refusals", () => {
        // `render busy` (daemon lock), `render queue saturated` (HTTP semaphore) and `warming`
        // all arrive as 503; the theme-render lease answers 429. Every one is "not yet".
        assert.equal(
            isRetryableSnapshotFailure(failure({ status: 503 })),
            true,
        );
        assert.equal(
            isRetryableSnapshotFailure(failure({ status: 429 })),
            true,
        );
    });

    it("does not retry the terminal refusal, header or no header", () => {
        // 409 is the server saying this preview can only ever be its published snapshot.
        assert.equal(
            isRetryableSnapshotFailure(failure({ status: 409 })),
            false,
        );
        assert.equal(
            isRetryableSnapshotFailure(
                failure({ status: 409, dropped: "fontScale" }),
            ),
            false,
        );
    });

    it("retries a busy refusal that carries no dropped-overrides header", () => {
        // The regression. This is exactly what a font-scale drag gets back from a saturated
        // daemon, and it used to fall straight through to "render failed for this preview".
        const busy = failure({ status: 503, retryAfterSeconds: 2 });
        assert.equal(willRetrySnapshot(busy, 0, 4), true);
        assert.equal(
            snapshotFailureMessage(busy, true, "PNG"),
            "PNG render is busy; retrying…",
        );
    });

    it("treats an unreachable server and an unexplained status as terminal", () => {
        assert.equal(isRetryableSnapshotFailure(failure({ status: 0 })), false);
        assert.equal(
            isRetryableSnapshotFailure(failure({ status: 500 })),
            false,
        );
        assert.equal(
            isRetryableSnapshotFailure(failure({ status: 404 })),
            false,
        );
    });

    it("stops at the limit", () => {
        const busy = failure({ status: 503 });
        assert.equal(willRetrySnapshot(busy, 3, 4), true);
        assert.equal(willRetrySnapshot(busy, 4, 4), false);
    });
});

describe("snapshot retry pacing", () => {
    it("paces off the server's Retry-After, backing away per attempt", () => {
        const busy = failure({ retryAfterSeconds: 3 });
        assert.equal(snapshotRetryWaitMs(busy, 1), 3000);
        assert.equal(snapshotRetryWaitMs(busy, 2), 6000);
    });

    it("falls back to two seconds when the refusal named none", () => {
        assert.equal(snapshotRetryWaitMs(failure(), 1), 2000);
    });
});

describe("snapshot failure message", () => {
    it("names the dropped params rather than calling the preview broken", () => {
        const dropped = failure({
            status: 409,
            dropped: "fontScale,localeTag",
        });
        assert.equal(
            snapshotFailureMessage(dropped, false, "PNG"),
            "Not rendered with fontScale, localeTag — this preview can only be served as its published snapshot.",
        );
    });

    it("distinguishes a dropped-override wait from a dropped-override dead end", () => {
        const dropped = failure({ status: 503, dropped: "fontScale" });
        assert.equal(
            snapshotFailureMessage(dropped, true, "PNG"),
            "Not rendered with fontScale — the live render is warming up; retrying…",
        );
        assert.equal(
            snapshotFailureMessage(dropped, false, "PNG"),
            "Not rendered with fontScale — the live render is unavailable right now; change a control to try again.",
        );
    });

    it("says the retries ran out rather than that the render failed", () => {
        // A saturated server is not a broken preview, and the difference is what the visitor does
        // next: wait and nudge a control, versus go looking for a bug in the composable.
        assert.equal(
            snapshotFailureMessage(failure({ status: 503 }), false, "PNG"),
            "PNG render is still busy — the server is saturated; change a control to try again.",
        );
    });

    it("keeps the plain failure wording for a terminal refusal, per format", () => {
        assert.equal(
            snapshotFailureMessage(failure({ status: 500 }), false, "SVG"),
            "SVG render failed for this preview.",
        );
    });
});
