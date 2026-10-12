// The camaelon-js RC lane must never leave its canvas at the 300×150 default: it waits for the
// snapshot to decode, and falls back to the stage box when it never does.

import assert from "node:assert/strict";
import {
    RC_FALLBACK_DP,
    rcCanvasSize,
    formatRcDensity,
    parseRcDensityAttr,
    rcDocumentHeader,
    rcDocumentSize,
    rcLaneDensity,
    rcWasmDensity,
    snapshotKeepsCapturedLayout,
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

function header(parts: Array<[number, number]>): ArrayBuffer {
    // parts: [byteLength (1|2|4), value]
    const size = parts.reduce((n, [len]) => n + len, 0);
    const v = new DataView(new ArrayBuffer(size));
    let o = 0;
    for (const [len, value] of parts) {
        if (len === 1) v.setUint8(o, value);
        else if (len === 2) v.setUint16(o, value);
        else v.setInt32(o, value);
        o += len;
    }
    return v.buffer;
}

describe("rcDocumentSize", () => {
    it("reads the tagged header's width and height", () => {
        const buf = header([
            [1, 0],
            [4, (0x048c << 16) | 1],
            [4, 1],
            [4, 0],
            [4, 3],
            [2, (1 << 10) | 7], // float density, skipped
            [2, 4],
            [4, 0x40000000],
            [2, 5],
            [2, 4],
            [4, 454],
            [2, 6],
            [2, 4],
            [4, 300],
        ]);
        assert.deepEqual(rcDocumentSize(buf), { width: 454, height: 300 });
    });

    it("reads the untagged header's width and height", () => {
        const buf = header([
            [1, 0],
            [4, 1],
            [4, 0],
            [4, 0],
            [4, 558],
            [4, 280],
        ]);
        assert.deepEqual(rcDocumentSize(buf), { width: 558, height: 280 });
    });

    it("is null when the header declares no size, is truncated, or is not RC", () => {
        assert.equal(
            rcDocumentSize(
                header([
                    [1, 0],
                    [4, (0x048c << 16) | 1],
                    [4, 1],
                    [4, 0],
                    [4, 0],
                ]),
            ),
            null,
        );
        assert.equal(
            rcDocumentSize(
                header([
                    [1, 0],
                    [4, (0x048c << 16) | 1],
                    [4, 1],
                    [4, 0],
                    [4, 2],
                    [2, 5],
                ]),
            ),
            null,
        );
        assert.equal(rcDocumentSize(header([[4, 0x89504e47]])), null);
    });
});

describe("rcDocumentHeader", () => {
    const tagged = (density: number | null) =>
        header([
            [1, 0],
            [4, (0x048c << 16) | 1],
            [4, 1],
            [4, 0],
            [4, density === null ? 2 : 3],
            ...(density === null
                ? []
                : ([
                      [2, (1 << 10) | 7],
                      [2, 4],
                      [4, floatBits(density)],
                  ] as Array<[number, number]>)),
            [2, 5],
            [2, 4],
            [4, 454],
            [2, 6],
            [2, 4],
            [4, 300],
        ]);

    it("reads DOC_DENSITY_AT_GENERATION from the tagged form", () => {
        assert.deepEqual(rcDocumentHeader(tagged(2.75)), {
            size: { width: 454, height: 300 },
            density: 2.75,
        });
    });

    it("reports no density when the tagged form declares none", () => {
        assert.deepEqual(rcDocumentHeader(tagged(null)), {
            size: { width: 454, height: 300 },
            density: null,
        });
    });

    it("ignores a non-positive declared density", () => {
        assert.equal(rcDocumentHeader(tagged(0))!.density, null);
    });

    it("never declares a density in the untagged form", () => {
        const buf = header([
            [1, 0],
            [4, 1],
            [4, 0],
            [4, 0],
            [4, 558],
            [4, 280],
        ]);
        assert.deepEqual(rcDocumentHeader(buf), {
            size: { width: 558, height: 280 },
            density: null,
        });
    });

    it("does not take an int property with key 7 for the density", () => {
        const buf = header([
            [1, 0],
            [4, (0x048c << 16) | 1],
            [4, 1],
            [4, 0],
            [4, 1],
            [2, 7],
            [2, 4],
            [4, 3],
        ]);
        assert.equal(rcDocumentHeader(buf)!.density, null);
    });

    it("is null for a buffer that is not Remote Compose", () => {
        assert.equal(rcDocumentHeader(header([[4, 0x89504e47]])), null);
    });
});

describe("rcLaneDensity", () => {
    const declared = { size: null, density: 2.625 };
    const undeclared = { size: { width: 10, height: 10 }, density: null };

    it("leaves a document that declares its density to the player", () => {
        assert.equal(rcLaneDensity(declared, 2.75), null);
    });

    it("imposes the page density on an undeclared document", () => {
        assert.equal(rcLaneDensity(undeclared, 2.75), 2.75);
        assert.equal(rcLaneDensity(undeclared, 1), 1);
        assert.equal(rcLaneDensity(null, 1), 1);
    });

    it("imposes nothing when the page knows no density", () => {
        assert.equal(rcLaneDensity(undeclared, null), null);
        assert.equal(rcLaneDensity(undeclared, 0), null);
    });
});

describe("parseRcDensityAttr", () => {
    it("reads a positive density and refuses anything else", () => {
        assert.equal(parseRcDensityAttr("2.75"), 2.75);
        assert.equal(parseRcDensityAttr("1"), 1);
        assert.equal(parseRcDensityAttr(null), null);
        assert.equal(parseRcDensityAttr(""), null);
        assert.equal(parseRcDensityAttr("0"), null);
        assert.equal(parseRcDensityAttr("-2"), null);
        assert.equal(parseRcDensityAttr("abc"), null);
    });
});

function floatBits(f: number): number {
    const v = new DataView(new ArrayBuffer(4));
    v.setFloat32(0, f);
    return v.getInt32(0);
}

describe("rcWasmDensity", () => {
    const declared = { size: { width: 454, height: 400 }, density: 2 };
    const undeclared = { size: { width: 558, height: 280 }, density: null };

    it("is the lane density at natural size", () => {
        assert.equal(rcWasmDensity(undeclared, 2.75, 558, 558), 2.75);
        assert.equal(rcWasmDensity(declared, 2.75, 454, 454), 2);
    });

    it("prefers the declared density over the page's", () => {
        assert.equal(rcWasmDensity(declared, 3, 454, 454), 2);
    });

    it("scales by the snapshot's displayed size", () => {
        assert.equal(rcWasmDensity(undeclared, 2.75, 279, 558), 1.375);
        assert.equal(rcWasmDensity(declared, null, 908, 454), 4);
    });

    it("takes the box at natural size when either width is unknown", () => {
        assert.equal(rcWasmDensity(undeclared, 1, 0, 400), 1);
        assert.equal(rcWasmDensity(undeclared, 1, 300, 0), 1);
    });

    it("is null when neither the document nor the page knows a density", () => {
        assert.equal(rcWasmDensity(undeclared, null, 558, 558), null);
        assert.equal(rcWasmDensity(null, null, 558, 558), null);
    });
});

describe("formatRcDensity", () => {
    it("writes a plain decimal", () => {
        assert.equal(formatRcDensity(2.75), "2.75");
        assert.equal(formatRcDensity(1), "1");
        assert.equal(formatRcDensity(2.75 * (279 / 558)), "1.375");
        assert.equal(formatRcDensity(1 / 3), "0.3333");
    });
});

describe("snapshotKeepsCapturedLayout", () => {
    it("trusts the baked snapshot and renders without layout overrides", () => {
        assert.equal(snapshotKeepsCapturedLayout(null), true);
        assert.equal(snapshotKeepsCapturedLayout("/render/x.png"), true);
        assert.equal(
            snapshotKeepsCapturedLayout(
                "/render/x.png?rcPlayer=cmp-android&uiMode=dark&fontScale=1.3",
            ),
            true,
        );
    });

    it("refuses renders under a size, device or orientation override", () => {
        for (const q of [
            "widthPx=300&heightPx=150",
            "maxWidthPx=400",
            "device=id%3Apixel_5",
            "orientation=landscape",
        ]) {
            assert.equal(
                snapshotKeepsCapturedLayout("/render/x.png?" + q),
                false,
                q,
            );
        }
    });
});
