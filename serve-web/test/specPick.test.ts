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

    it("reads the candidate at the offset, and only the candidate", () => {
        // Issue #830's case, in miniature: the ink is at row 0 on one side and row 1 on the other.
        // Read at the same coordinate the two disagree completely; read at the shift they are the
        // same ink, which is the answer the question was actually about.
        const reference = buffer([FOCUSED, CLEAR]);
        const candidate = buffer([CLEAR, FOCUSED]);
        const plain = readingAt(reference, candidate, 1, 2, 0, 0);
        assert.equal(plain.delta, 255);
        assert.equal(plain.offset, null);

        const aligned = readingAt(reference, candidate, 1, 2, 0, 0, {
            dx: 0,
            dy: 1,
        });
        assert.equal(aligned.delta, 0);
        assert.deepEqual(aligned.offset, { dx: 0, dy: 1 });
        // The point named is still the point the pointer is on. The offset says where the other
        // side was read, it does not move the reading.
        assert.equal(aligned.x, 0);
        assert.equal(aligned.y, 0);
    });

    it("answers null for a candidate the offset pushes off the frame", () => {
        const reference = buffer([RESTING, RESTING]);
        const candidate = buffer([RESTING, RESTING]);
        const reading = readingAt(reference, candidate, 1, 2, 0, 1, {
            dx: 0,
            dy: 4,
        });
        assert.notEqual(reading.reference, null);
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

    it("states an applied offset, zero included", () => {
        // A reading taken under alignment and one taken without it can name the same point and
        // disagree about the colour, so the line has to say which of the two it is — and "+0,+3"
        // is itself the answer to "how far did it move".
        const reference = buffer([FOCUSED, CLEAR]);
        const candidate = buffer([CLEAR, FOCUSED]);
        assert.equal(
            summarise(
                readingAt(reference, candidate, 1, 2, 0, 0, { dx: 0, dy: 1 }),
                "Figma",
                "Render",
            ),
            "0,0 · Figma #7661ad · Render #7661ad · aligned +0,+1 · identical",
        );
        assert.match(
            summarise(
                readingAt(reference, reference, 1, 2, 0, 0, { dx: -2, dy: 0 }),
                "Figma",
                "Render",
            ),
            /aligned -2,\+0/,
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
