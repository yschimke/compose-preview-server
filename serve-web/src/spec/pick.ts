// Reading the colour under the cursor, on both sides of the comparison at once.
//
// The lane can already say that two pictures differ and by how much. It cannot say what either
// pixel IS, which is the question a state layer asks: Material's focus treatment is a 10% white
// overlay, so a focused container differs from its resting one by about 17/255 on one channel —
// real, and at the edge of what an eye reports reliably. "Is the overlay drawn, and does the
// reference agree" is a question about two specific pixels, and nothing on this page answered it.
//
// The alignment that question needs is already done. `normaliseImageUrls` returns the pair as two
// canvases of ONE size, cropped to their content boxes and drawn onto a shared origin, which is
// also what makes the delta map meaningful. So a point in that space names the same feature in
// both frames by construction — no per-side scale, no root-translate subtraction, no letterbox
// offset, none of the registration arithmetic a picker over two independently-placed images needs.
// Everything here is therefore index arithmetic over two RGBA buffers.

/** One pixel, straight (unmultiplied) as the canvas hands it over. */
export interface Sample {
    r: number;
    g: number;
    b: number;
    a: number;
}

export interface Reading {
    /** The sampled point, in the normalised space both buffers share. */
    x: number;
    y: number;
    /** Null when the point falls outside the buffer — a real answer, not an absent one. */
    reference: Sample | null;
    candidate: Sample | null;
    /** Largest channel difference, alpha included; null unless both sides answered. */
    delta: number | null;
}

/** The pixel at (x, y), or null when the point is off the buffer. */
export function sampleAt(
    data: ArrayLike<number>,
    width: number,
    height: number,
    x: number,
    y: number,
): Sample | null {
    const px = Math.floor(x);
    const py = Math.floor(y);
    if (!(px >= 0 && py >= 0 && px < width && py < height)) return null;
    const i = (py * width + px) * 4;
    return { r: data[i], g: data[i + 1], b: data[i + 2], a: data[i + 3] };
}

/**
 * Alpha included in the delta, matching `deltaMap`'s own rule: a mark appearing over transparency
 * is a difference, and a picker that ignored alpha would call an opaque pixel and a transparent one
 * of the same RGB identical — which is exactly the case the reference lane produces when the export
 * is missing a layer the render draws.
 */
export function deltaOf(reference: Sample, candidate: Sample): number {
    return Math.max(
        Math.abs(reference.r - candidate.r),
        Math.abs(reference.g - candidate.g),
        Math.abs(reference.b - candidate.b),
        Math.abs(reference.a - candidate.a),
    );
}

/** Both sides at one point in the shared space. */
export function readingAt(
    reference: ArrayLike<number>,
    candidate: ArrayLike<number>,
    width: number,
    height: number,
    x: number,
    y: number,
): Reading {
    const ref = sampleAt(reference, width, height, x, y);
    const cand = sampleAt(candidate, width, height, x, y);
    return {
        x: Math.floor(x),
        y: Math.floor(y),
        reference: ref,
        candidate: cand,
        delta: ref && cand ? deltaOf(ref, cand) : null,
    };
}

const HEX = (n: number) => n.toString(16).padStart(2, "0");

/** `#rrggbb`, and the alpha spelled separately rather than folded into an eight-digit hex. */
export function hexOf(sample: Sample): string {
    return "#" + HEX(sample.r) + HEX(sample.g) + HEX(sample.b);
}

/**
 * What one side reads as, for the panel and for a screen reader.
 *
 * A transparent pixel is reported as transparent rather than as its meaningless RGB: a canvas hands
 * back whatever happens to sit in an unpainted buffer, and printing that as a colour invents a fact
 * about the picture. Partial alpha keeps the hex and names the alpha, because there the RGB is real
 * ink and the alpha is how much of it there is.
 */
export function describe(sample: Sample | null): string {
    if (!sample) return "outside this frame";
    if (sample.a === 0) return "transparent";
    if (sample.a === 255) return hexOf(sample);
    return hexOf(sample) + " at " + (sample.a / 255).toFixed(2) + " alpha";
}

/** The whole reading as one line — the readout's text, and the announcement's. */
export function summarise(
    reading: Reading,
    referenceLabel: string,
    candidateLabel: string,
): string {
    const where = reading.x + "," + reading.y;
    const parts = [
        where,
        referenceLabel + " " + describe(reading.reference),
        candidateLabel + " " + describe(reading.candidate),
    ];
    if (reading.delta !== null)
        parts.push(reading.delta === 0 ? "identical" : "Δ " + reading.delta);
    return parts.join(" · ");
}
