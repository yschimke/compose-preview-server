// Reading the colour under the cursor on both sides of the comparison at once, e.g. to check
// whether a 10% state-layer overlay is drawn and matches. `normaliseImageUrls` already returns both
// frames on one same-sized, shared-origin canvas, so a point names the same feature in both and
// this is plain index arithmetic over two RGBA buffers.

/** One pixel, straight (unmultiplied) as the canvas hands it over. */
export interface Sample {
    r: number;
    g: number;
    b: number;
    a: number;
}

/**
 * How far the candidate is read from the reference's point, in normalised pixels. Null when
 * alignment is off or has nothing to say, which differs from `{0, 0}` (matched and unmoved).
 */
export interface Offset {
    dx: number;
    dy: number;
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
    /** The shift the candidate was read at, or null when both sides were read at the same point. */
    offset: Offset | null;
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

/** Alpha is included, matching `deltaMap`: a mark over transparency is a difference. */
export function deltaOf(reference: Sample, candidate: Sample): number {
    return Math.max(
        Math.abs(reference.r - candidate.r),
        Math.abs(reference.g - candidate.g),
        Math.abs(reference.b - candidate.b),
        Math.abs(reference.a - candidate.a),
    );
}

/**
 * Both sides at one point, or with an offset at one point and its match (see #830). Content-box
 * normalisation misregisters a label shifted a few pixels on one side; given the shift (from
 * `spec/align.ts`) it compares ink against ink.
 */
export function readingAt(
    reference: ArrayLike<number>,
    candidate: ArrayLike<number>,
    width: number,
    height: number,
    x: number,
    y: number,
    offset: Offset | null = null,
): Reading {
    const ref = sampleAt(reference, width, height, x, y);
    const cand = sampleAt(
        candidate,
        width,
        height,
        x + (offset?.dx ?? 0),
        y + (offset?.dy ?? 0),
    );
    return {
        x: Math.floor(x),
        y: Math.floor(y),
        reference: ref,
        candidate: cand,
        delta: ref && cand ? deltaOf(ref, cand) : null,
        offset,
    };
}

const HEX = (n: number) => n.toString(16).padStart(2, "0");

/** `#rrggbb`, and the alpha spelled separately rather than folded into an eight-digit hex. */
export function hexOf(sample: Sample): string {
    return "#" + HEX(sample.r) + HEX(sample.g) + HEX(sample.b);
}

/**
 * What one side reads as, for the panel and screen readers. Fully transparent pixels say so instead
 * of printing meaningless RGB; partial alpha keeps the hex and names the alpha.
 */
export function describe(sample: Sample | null): string {
    if (!sample) return "outside this frame";
    if (sample.a === 0) return "transparent";
    if (sample.a === 255) return hexOf(sample);
    return hexOf(sample) + " at " + (sample.a / 255).toFixed(2) + " alpha";
}

/** A shift, always signed, so `+0` reads as "measured, and it had not moved". */
function signed(n: number): string {
    return (n < 0 ? "" : "+") + n;
}

/**
 * The whole reading as one line (readout and announcement). An applied offset is always stated,
 * zero included, since aligned and unaligned readings can disagree.
 */
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
    if (reading.offset)
        parts.push(
            "aligned " +
                signed(reading.offset.dx) +
                "," +
                signed(reading.offset.dy),
        );
    if (reading.delta !== null)
        parts.push(reading.delta === 0 ? "identical" : "Δ " + reading.delta);
    return parts.join(" · ");
}
