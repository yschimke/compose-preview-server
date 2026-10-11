// The comparison metric's tuning numbers. Each is a judgement about what counts as a difference;
// shared thresholds live in `compare/thresholds.ts`.

/** Longest side of the downscale a score is computed over. */
export const MAX_SIDE = 192;

// Figma's browser SVG rasteriser and Skia's Compose rasteriser cover the same vector edge with
// different sub-pixels. Search a small neighbourhood only for actual edge pixels, and charge a
// positional cost for displacement so repeated luminances cannot hide a missing or added mark.
export const EDGE_SEARCH_RADIUS = 5;
export const EDGE_POSITION_COST = 10;
export const EDGE_GRADIENT_THRESHOLD = 12;
export const LUMA_TOLERANCE = 16;

/**
 * The luminance gap at which a pixel counts as completely wrong. Below it the cost ramps (a shade
 * of drift reads as mostly right); at or above it the pixel is charged in full, so lost fills score
 * as a mismatch.
 */
export const FULL_DIFFERENCE_DELTA = 128;

/**
 * How far the content mask reaches beyond the pixels that carry detail (see `contentMask`).
 *
 * One pixel, because a mark is wider than its own gradient: the anti-aliased skirt of a hairline
 * stroke is part of the stroke, and leaving it outside the measured region would put a stroke's
 * disagreement in the numerator while its own pixels sat outside the denominator.
 */
export const CONTENT_DILATION = 1;

// Longest side of the downscale that content-box detection samples, and how far a pixel may sit
// from the backdrop colour before it counts as drawn.
export const BOX_SAMPLE_SIDE = 256;
export const BOX_COLOUR_TOLERANCE = 12;

/**
 * Smallest share of its canvas a content box may cover before cropping to it stops being
 * trustworthy — see `normalisedBoxes`.
 */
export const MIN_BOX_COVERAGE = 0.05;

/**
 * The backing colours `@Preview(showBackground = true)` resolves to (white for day, M3 dark surface
 * #1C1B1F for night), mirroring the server's `PreviewBackground`. A corner of one of these colours
 * means a scaffold sheet; see `contentBox`.
 */
export const SCAFFOLD_SHEETS: ReadonlyArray<readonly [number, number, number]> =
    [
        [255, 255, 255],
        [28, 27, 31],
    ];

/** Slack for PNG round-tripping and the detection downscale's resampling of an edge pixel. */
export const SHEET_TOLERANCE = 6;

/**
 * The grounds a comparison is scored on — both, every time, keeping the worse score. Fixed rather
 * than themed so site appearance never moves a score. One opaque ground would annihilate ink that
 * matches it (e.g. white glyphs on transparency become a blank plane that `scorePlanes` scores
 * 100); with white AND black a pixel can only vanish when it is absent from both images.
 */
export const COMPARISON_GROUNDS: ReadonlyArray<string> = ["#ffffff", "#000000"];

/**
 * The same two grounds as RGB triples, for the arithmetic compositing path (`grayFromRaster`). Kept
 * adjacent to `COMPARISON_GROUNDS` so the two cannot drift.
 */
export const COMPARISON_GROUND_RGB: ReadonlyArray<
    readonly [number, number, number]
> = [
    [255, 255, 255],
    [0, 0, 0],
];

/**
 * Which pixel path produced a score, so a published number can be told from a rebaselined one. `1`:
 * browser `drawImage` downscale (not reproducible offline). `2`: portable area average. `3`:
 * premultiplied area average, so visually identical exports at different resolutions score alike.
 * Readers drop a baked `match` whose version differs from the one they compute with, and score live
 * instead. **Bump it in the same change that moves the number, never in one that also changes
 * acceptance semantics.**
 */
export const SCORE_VERSION = 3;

/** Below this per-channel delta a pixel has not moved — PNG round-tripping and resampling noise. */
export const DIFF_CHANNEL_TOLERANCE = 3;
