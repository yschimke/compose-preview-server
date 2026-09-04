// The spec lane's eyedropper, as arithmetic over two normalised RGBA buffers.

import assert from "node:assert/strict";
import {
    deltaOf,
    describe as describeSample,
    hexOf,
    readingAt,
    sampleAt,
    summarise,
} from "../src/spec/pick.js";

/** A 2×2 buffer, row-major RGBA. */
function buffer(pixels: Array<[number, number, number, number]>) {
    const data = new Uint8ClampedArray(pixels.length * 4);
    pixels.forEach((p, i) => data.set(p, i * 4));
    return data;
}

// The case the picker exists for: M3's focus treatment is a 10% white overlay, so the focused
// container is #7661AD where the resting one is #6750A4 — a delta of 17 on one channel.
const RESTING: [number, number, number, number] = [103, 80, 164, 255];
const FOCUSED: [number, number, number, number] = [118, 97, 173, 255];
const CLEAR: [number, number, number, number] = [0, 0, 0, 0];

describe("sampleAt", () => {
    it("reads the pixel at a point", () => {
        const data = buffer([RESTING, FOCUSED, CLEAR, RESTING]);
        assert.deepEqual(sampleAt(data, 2, 2, 1, 0), {
            r: 118,
            g: 97,
            b: 173,
            a: 255,
        });
    });

    it("floors a fractional point rather than rounding it", () => {
        // A pointer lands between pixels; the pixel it is over is the one it is inside.
        const data = buffer([RESTING, FOCUSED, CLEAR, RESTING]);
        assert.equal(sampleAt(data, 2, 2, 1.9, 0.4)?.r, 118);
    });

    it("answers null outside the buffer", () => {
        // Null is a real answer: the frames are cropped to their content boxes, so a point can be
        // inside the stage and outside the picture. Clamping would invent a pixel.
        const data = buffer([RESTING, FOCUSED, CLEAR, RESTING]);
        assert.equal(sampleAt(data, 2, 2, 2, 0), null);
        assert.equal(sampleAt(data, 2, 2, -1, 0), null);
        assert.equal(sampleAt(data, 2, 2, 0, 2), null);
    });
});

describe("deltaOf", () => {
    it("reports the largest channel difference", () => {
        assert.equal(
            deltaOf(
                { r: 103, g: 80, b: 164, a: 255 },
                { r: 118, g: 97, b: 173, a: 255 },
            ),
            17,
        );
    });

    it("counts alpha, so ink over transparency is a difference", () => {
        // Same rule as the delta map's. Ignoring alpha would call an opaque pixel and a transparent
        // one of the same RGB identical — which is exactly what a reference missing a layer looks
        // like, and the one case this must not call a match.
        assert.equal(
            deltaOf({ r: 0, g: 0, b: 0, a: 0 }, { r: 0, g: 0, b: 0, a: 255 }),
            255,
        );
    });
});

describe("readingAt", () => {
    it("names the same point in both frames", () => {
        const reference = buffer([RESTING, RESTING, RESTING, RESTING]);
        const candidate = buffer([RESTING, FOCUSED, RESTING, RESTING]);
        const reading = readingAt(reference, candidate, 2, 2, 1, 0);
        assert.equal(reading.reference?.r, 103);
        assert.equal(reading.candidate?.r, 118);
        assert.equal(reading.delta, 17);
    });

    it("reports no delta when one side has no pixel there", () => {
        const reference = buffer([RESTING]);
        const candidate = buffer([FOCUSED]);
        const reading = readingAt(reference, candidate, 1, 1, 5, 5);
        assert.equal(reading.reference, null);
        assert.equal(reading.candidate, null);
        assert.equal(reading.delta, null);
    });
});

describe("describe", () => {
    it("spells an opaque pixel as its hex", () => {
        assert.equal(
            describeSample({ r: 118, g: 97, b: 173, a: 255 }),
            "#7661ad",
        );
    });

    it("calls a transparent pixel transparent rather than printing its RGB", () => {
        // An unpainted buffer hands back whatever happens to sit there; printing it as a colour
        // states a fact about the picture that is not true.
        assert.equal(
            describeSample({ r: 12, g: 34, b: 56, a: 0 }),
            "transparent",
        );
    });

    it("keeps the hex and names the alpha for partial ink", () => {
        assert.equal(
            describeSample({ r: 255, g: 255, b: 255, a: 26 }),
            "#ffffff at 0.10 alpha",
        );
    });

    it("says outside rather than absent when there is no pixel", () => {
        assert.equal(describeSample(null), "outside this frame");
    });
});

describe("summarise", () => {
    it("reads as one line, both sides and the verdict", () => {
        const reference = buffer([RESTING]);
        const candidate = buffer([FOCUSED]);
        assert.equal(
            summarise(
                readingAt(reference, candidate, 1, 1, 0, 0),
                "Spec",
                "Render",
            ),
            "0,0 · Spec #6750a4 · Render #7661ad · Δ 17",
        );
    });

    it("says identical when the two agree", () => {
        const same = buffer([FOCUSED]);
        assert.match(
            summarise(readingAt(same, same, 1, 1, 0, 0), "Spec", "Render"),
            /identical$/,
        );
    });

    it("omits the verdict when only one side has a pixel", () => {
        const reference = buffer([RESTING]);
        const candidate = buffer([FOCUSED]);
        const line = summarise(
            readingAt(reference, candidate, 1, 1, 9, 9),
            "Spec",
            "Render",
        );
        assert.match(line, /outside this frame/);
        assert.doesNotMatch(line, /Δ|identical/);
    });
});

describe("hexOf", () => {
    it("pads single-digit channels", () => {
        assert.equal(hexOf({ r: 1, g: 2, b: 3, a: 255 }), "#010203");
    });
});
