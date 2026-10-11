// The six entry points every comparison surface calls: the whole of `window.ComposePreviewCompare`,
// used by the parity page, compare wall, spec lane and reference page, and by external consumers
// loading the built asset (`design-artifacts/design-reference-score.mjs`, the compare audit). Its
// shape is a contract; `src/formatCompare.ts` publishes it.

import { deltaMap } from "./deltaMap.js";
import {
    blankMap,
    boxCanvas,
    grayFromDraw,
    grayFromPremultipliedRaster,
    imageDimensions,
    loadImage,
    normalisedBoxes,
    normalisedBoxesOf,
    pixelsOf,
    rasterOf,
    svgImage,
    type Frame,
    type Raster,
} from "./frames.js";
import { scorePlanesOffloaded } from "./offload.js";
import { translateOf } from "./svgTranslate.js";
import {
    COMPARISON_GROUNDS,
    COMPARISON_GROUND_RGB,
    MAX_SIDE,
} from "./tuning.js";
import { cropToPremultiplied } from "@design-parity/known-differences/known-difference-resample";

export interface Measurement {
    /** Structural match, 0–100. */
    percent: number;
    /** Proportion drift between the two content boxes, in percent. */
    geometry: number;
}

export interface NormalisedPair {
    reference: HTMLCanvasElement;
    candidate: HTMLCanvasElement;
    /** The decoded originals, so a caller can score without re-requesting the two frames. */
    images: [HTMLImageElement, HTMLImageElement];
    width: number;
    height: number;
    geometry: number;
    /** Source rectangles redrawn into the shared canvas; annotation bounds use this space. */
    boxes: {
        reference: { x: number; y: number; width: number; height: number };
        candidate: { x: number; y: number; width: number; height: number };
    };
}

/** The downscale a score is computed at, from whichever box drives the shared size. */
function comparisonSize(box: { width: number; height: number }) {
    const scale = Math.min(1, MAX_SIDE / Math.max(box.width, box.height));
    return {
        scale,
        width: Math.max(1, Math.round(box.width * scale)),
        height: Math.max(1, Math.round(box.height * scale)),
    };
}

/** Paints one side of a comparison onto whichever ground it is handed. */
type Draw = (context: CanvasRenderingContext2D) => void;

/**
 * The structural match of two drawings, scored once per {@link COMPARISON_GROUNDS} and reported as
 * the worst. Used by the canvas-bound scorers (SVG and Remote Compose lanes): a single opaque
 * ground deletes matching ink and `scorePlanes` scores the resulting blanks 100, while content lost
 * on one ground survives on the other. The pessimism on an honest pair is small.
 */
async function scoreOnEveryGround(
    drawReference: Draw,
    drawCandidate: Draw,
    width: number,
    height: number,
): Promise<number> {
    // Rasterise every plane before the first await: the RC player's live canvas repaints on its own
    // frames, so per-ground passes could measure different frames.
    const planes = COMPARISON_GROUNDS.map((ground) => ({
        reference: grayFromDraw(drawReference, width, height, ground),
        candidate: grayFromDraw(drawCandidate, width, height, ground),
    }));

    let worst = 100;
    for (const { reference, candidate } of groundsWorthScoring(planes)) {
        worst = Math.min(
            worst,
            await scorePlanesOffloaded(reference, candidate, width, height),
        );
    }
    return worst;
}

/** One comparison, composited onto one ground. */
export interface GroundPlanes {
    reference: Float32Array;
    candidate: Float32Array;
}

/**
 * Which grounds deserve a score. Extra grounds matter only where alpha shows through; equal planes
 * reveal an opaque image for free. A mixed pair (opaque reference vs transparent render surround,
 * e.g. a design-page crop) would otherwise report ground differences as artwork differences.
 */
export function groundsWorthScoring(
    planes: ReadonlyArray<GroundPlanes>,
): ReadonlyArray<GroundPlanes> {
    const varies = (side: keyof GroundPlanes) =>
        planes.some((plane) => !samePlane(plane[side], planes[0][side]));
    return varies("reference") && varies("candidate") ? planes : [planes[0]];
}

/** Whether two luminance planes are the same picture, with tolerance for nearly-opaque pixels. */
function samePlane(a: Float32Array, b: Float32Array): boolean {
    if (a.length !== b.length) return false;
    for (let i = 0; i < a.length; i++) {
        if (Math.abs(a[i] - b[i]) > 1) return false;
    }
    return true;
}

/**
 * Score a baked PNG against an SVG of the same render, as a bare percentage (shared geometry). The
 * SVG's root translate is subtracted (`svgTranslate.ts`) so the offset isn't scored.
 */
export async function scoreSvgUrls(
    pngUrl: string,
    svgUrl: string,
): Promise<number> {
    const [png, text] = await Promise.all([
        loadImage(pngUrl),
        fetch(svgUrl).then((response) => {
            if (!response.ok) throw new Error(`SVG ${response.status}`);
            return response.text();
        }),
    ]);
    const svg = await svgImage(text);
    const render = imageDimensions(png);
    const { scale, width, height } = comparisonSize(render);
    const translate = translateOf(text);
    const svgSize = imageDimensions(svg);
    return scoreOnEveryGround(
        (context) =>
            context.drawImage(
                png,
                0,
                0,
                render.width * scale,
                render.height * scale,
            ),
        (context) =>
            context.drawImage(
                svg,
                -translate.x * scale,
                -translate.y * scale,
                svgSize.width * scale,
                svgSize.height * scale,
            ),
        width,
        height,
    );
}

/** Score a baked PNG against a canvas something else has already drawn — the RC lane's shape. */
export async function scoreCanvas(
    pngUrl: string,
    sourceCanvas: CanvasImageSource,
): Promise<number> {
    const png = await loadImage(pngUrl);
    const render = imageDimensions(png);
    const { scale, width, height } = comparisonSize(render);
    const draw =
        (source: CanvasImageSource) => (context: CanvasRenderingContext2D) =>
            context.drawImage(
                source,
                0,
                0,
                render.width * scale,
                render.height * scale,
            );
    return scoreOnEveryGround(draw(png), draw(sourceCanvas), width, height);
}

/**
 * Score a design reference against a rendered preview: both are cropped to their content box and
 * drawn into one target box, so the score is about appearance, not export size.
 */
export async function scoreImageUrls(
    referenceUrl: string,
    candidateUrl: string,
): Promise<Measurement> {
    const [reference, candidate] = await Promise.all([
        loadImage(referenceUrl),
        loadImage(candidateUrl),
    ]);
    return scoreImages(reference, candidate);
}

/**
 * {@link scoreImageUrls} over already-decoded frames, so the spec lane can score the frames it drew
 * (a re-request of a no-store render could differ). Downscaling starts from the original images.
 *
 * The kernel is the portable area average, not `drawImage`: rasterised, cropped and resampled by
 * `cropTo`, with grounds composited arithmetically. That makes the number reproducible offline and
 * equal to the acceptance band's `raw`. `SCORE_VERSION` records the path; see `tuning.ts`.
 */
export async function scoreImages(
    referenceImage: Frame,
    candidateImage: Frame,
): Promise<Measurement> {
    // Rasterise each side once and reuse it for content box and score plane.
    const reference = rasterOf(referenceImage);
    const candidate = rasterOf(candidateImage);
    if (!reference || !candidate) {
        // Unreadable pixels — a cross-origin frame. The old path could not measure one either:
        // every plane it scored came back through `getImageData`.
        throw new Error("frame pixels are unreadable");
    }
    const boxes = normalisedBoxesOf(reference, candidate);
    const { width, height } = comparisonSize(boxes.candidate);
    // One premultiplied area-average resample to the score plane at the candidate box's size (see
    // `resampleAreaPremultiplied`). `boxCanvas` still crops via straight `cropTo`, since
    // `putImageData` needs displayable bytes.
    const scaled: [Raster, Raster] = [
        cropToPremultiplied(reference, boxes.reference, width, height),
        cropToPremultiplied(candidate, boxes.candidate, width, height),
    ];
    const grounds = COMPARISON_GROUND_RGB.map((ground) => ({
        reference: grayFromPremultipliedRaster(scaled[0], ground),
        candidate: grayFromPremultipliedRaster(scaled[1], ground),
    }));
    let percent = 100;
    for (const plane of groundsWorthScoring(grounds)) {
        percent = Math.min(
            percent,
            await scorePlanesOffloaded(
                plane.reference,
                plane.candidate,
                width,
                height,
            ),
        );
    }
    return { percent, geometry: boxes.geometry };
}

/**
 * Both frames redrawn at one shared size (each side's content box scaled onto the candidate's), the
 * prerequisite for every pixel-for-pixel surface (diff map, triptych, wipe), since references
 * routinely differ in scale or padding.
 */
export async function normaliseImageUrls(
    referenceUrl: string,
    candidateUrl: string,
    maxSide?: number,
): Promise<NormalisedPair> {
    const images = (await Promise.all([
        loadImage(referenceUrl),
        loadImage(candidateUrl),
    ])) as [HTMLImageElement, HTMLImageElement];
    const rasters = [rasterOf(images[0]), rasterOf(images[1])] as const;
    const boxes =
        rasters[0] && rasters[1]
            ? normalisedBoxesOf(rasters[0], rasters[1])
            : normalisedBoxes(images[0], images[1]);
    // `maxSide` bounds the normalised pixel space for callers that never draw larger (the compare
    // wall). It doesn't affect the percentage (`scoreImages` measures the originals), only peak
    // memory and canvas limits.
    const bound = maxSide
        ? Math.min(
              1,
              maxSide /
                  Math.max(boxes.candidate.width, boxes.candidate.height, 1),
          )
        : 1;
    const width = Math.max(1, Math.round(boxes.candidate.width * bound));
    const height = Math.max(1, Math.round(boxes.candidate.height * bound));
    return {
        width,
        height,
        geometry: boxes.geometry,
        boxes: {
            reference: boxes.reference,
            candidate: boxes.candidate,
        },
        reference: boxCanvas(
            images[0],
            boxes.reference,
            width,
            height,
            rasters[0],
        ),
        candidate: boxCanvas(
            images[1],
            boxes.candidate,
            width,
            height,
            rasters[1],
        ),
        images,
    };
}

/**
 * Paint the magenta delta map of two same-sized normalised canvases into `target`, returning how
 * many pixels moved.
 */
export function diffCanvases(
    reference: HTMLCanvasElement,
    candidate: HTMLCanvasElement,
    target: HTMLCanvasElement,
): number {
    const width = reference.width;
    const height = reference.height;
    const referenceData = pixelsOf(reference, width, height);
    const candidateData = pixelsOf(candidate, width, height);
    const { context, image } = blankMap(target, width, height);
    const { changed } = deltaMap(referenceData, candidateData, image.data);
    context.clearRect(0, 0, width, height);
    context.putImageData(image, 0, 0);
    return changed;
}

export { loadImage };
