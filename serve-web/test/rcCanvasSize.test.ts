// The camaelon-js RC lane must never leave its canvas at the 300×150 default: it waits for the
// snapshot to decode, and falls back to the stage box when it never does.

import assert from "node:assert/strict";
import {
    RC_FALLBACK_DP,
    rcCanvasSize,
    whenImageSized,
} from "../src/viewer/rcCanvasSize.js";

function fakeImage(width: number, height: number): HTMLImageElement {
    const img = document.createElement("img");
    Object.defineProperty(img, "naturalWidth", {
        configurable: true,
        get: () => width,
    });
    Object.defineProperty(img, "naturalHeight", {
        configurable: true,
        get: () => height,
    });
    return img;
}

function decode(img: HTMLImageElement, width: number, height: number) {
    Object.defineProperty(img, "naturalWidth", {
        configurable: true,
        get: () => width,
    });
    Object.defineProperty(img, "naturalHeight", {
        configurable: true,
        get: () => height,
    });
    img.dispatchEvent(new Event("load"));
}

describe("rcCanvasSize", () => {
    it("uses the decoded snapshot's natural size", () => {
        assert.deepEqual(
            rcCanvasSize(
                { width: 1080, height: 2400 },
                { width: 400, height: 300 },
                2.625,
            ),
            { width: 1080, height: 2400 },
        );
    });

    it("scales the stage box by the render density without a snapshot", () => {
        assert.deepEqual(rcCanvasSize(null, { width: 411.5, height: 891 }, 2), {
            width: 823,
            height: 1782,
        });
        assert.deepEqual(
            rcCanvasSize(
                { width: 0, height: 0 },
                { width: 100, height: 50 },
                3,
            ),
            { width: 300, height: 150 },
        );
    });

    it("falls back to a phone-sized dp box when nothing is measurable", () => {
        assert.deepEqual(rcCanvasSize(null, { width: 0, height: 0 }, 2), {
            width: RC_FALLBACK_DP.width * 2,
            height: RC_FALLBACK_DP.height * 2,
        });
        assert.deepEqual(rcCanvasSize(null, null, NaN), RC_FALLBACK_DP);
    });
});

describe("whenImageSized", () => {
    it("resolves immediately for an already decoded image", async () => {
        assert.deepEqual(await whenImageSized(fakeImage(720, 1280), 1000), {
            width: 720,
            height: 1280,
        });
    });

    it("waits for a later load, ignoring loads that did not decode", async () => {
        const img = fakeImage(0, 0);
        const pending = whenImageSized(img, 5000);
        img.dispatchEvent(new Event("load"));
        decode(img, 1080, 2340);
        assert.deepEqual(await pending, { width: 1080, height: 2340 });
    });

    it("resolves null when the snapshot never decodes", async () => {
        assert.equal(await whenImageSized(fakeImage(0, 0), 10), null);
    });
});
