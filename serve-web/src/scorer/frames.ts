// Getting browser pixels into plain arrays. Everything needing `document`, `Image` or a canvas
// lives here so `planes.ts`, `contentBox.ts` and `deltaMap.ts` stay testable without one.

import {
    boxFromSamples,
    normalisedBoxes as decideBoxes,
    wholeImage,
    type Box,
    type NormalisedBoxes,
    type Size,
} from "./contentBox.js";
import { BOX_SAMPLE_SIDE } from "./tuning.js";
import {
    cropTo,
    resampleArea,
} from "@design-parity/known-differences/known-difference-resample";

/** Anything decoded that a canvas can draw and that reports its own size. */
export type Frame = CanvasImageSource & {
    width?: number;
    height?: number;
    naturalWidth?: number;
    naturalHeight?: number;
};

export function loadImage(src: string): Promise<HTMLImageElement> {
    return new Promise((resolve, reject) => {
        const img = new Image();
        img.decoding = "async";
        img.onload = () => resolve(img);
        img.onerror = () => reject(new Error("image load failed"));
        img.src = src;
    });
}

/**
 * SVG text decoded into an image via a blob URL (large SVGs overflow data URIs); the URL is revoked
 * after decode either way.
 */
export function svgImage(text: string): Promise<HTMLImageElement> {
    const url = URL.createObjectURL(
        new Blob([text], { type: "image/svg+xml" }),
    );
    return loadImage(url).then(
        (img) => {
            setTimeout(() => URL.revokeObjectURL(url), 0);
            return img;
        },
        (error) => {
            URL.revokeObjectURL(url);
            throw error;
        },
    );
}

export function imageDimensions(image: Frame): Size {
    return {
        width: image.naturalWidth || image.width || 0,
        height: image.naturalHeight || image.height || 0,
    };
}

/** A 2D context that will be read back — see `boxCanvas` on why the flag has to be set here. */
function readableContext(canvas: HTMLCanvasElement): CanvasRenderingContext2D {
    return canvas.getContext("2d", {
        willReadFrequently: true,
    }) as CanvasRenderingContext2D;
}

/** A decoded raster in the shape the portable kernel works on. */
export interface Raster {
    width: number;
    height: number;
    pixels: Uint8Array;
}

/**
 * A frame's own pixels at its own size — the entry to the portable path. The draw is one-to-one so
 * no host-dependent filter runs; downscaling is {@link resampleArea}'s arithmetic (see
 * [D3](../../../../docs/design/parity-batches/00-decisions.md)). `null` when a cross-origin
 * artifact taints the canvas.
 */
export function rasterOf(image: Frame): Raster | null {
    const { width, height } = imageDimensions(image);
    if (width <= 0 || height <= 0) return null;
    const canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    const context = readableContext(canvas);
    context.drawImage(image, 0, 0);
    try {
        const data = context.getImageData(0, 0, width, height).data;
        return {
            width,
            height,
            pixels: new Uint8Array(
                data.buffer,
                data.byteOffset,
                data.byteLength,
            ),
        };
    } catch {
        return null;
    }
}

/**
 * One raster composited onto `ground` as a luminance plane: {@link grayFromDraw}'s `source-over`
 * arithmetic without a canvas round-trip, avoiding engine-specific premultiplication rounding.
 */
export function grayFromRaster(
    raster: Raster,
    ground: readonly [number, number, number],
): Float32Array {
    const { width, height, pixels } = raster;
    const gray = new Float32Array(width * height);
    for (let i = 0; i < gray.length; i++) {
        const alpha = pixels[i * 4 + 3] / 255;
        const rest = 1 - alpha;
        const r = pixels[i * 4] * alpha + ground[0] * rest;
        const g = pixels[i * 4 + 1] * alpha + ground[1] * rest;
        const b = pixels[i * 4 + 2] * alpha + ground[2] * rest;
        gray[i] = 0.299 * r + 0.587 * g + 0.114 * b;
    }
    return gray;
}

/**
 * {@link grayFromRaster} for a **premultiplied** raster (the score plane): adds the ground's share
 * without weighting colour by alpha again. Averaging straight colour then compositing does not
 * commute, so `resampleAreaPremultiplied` averages premultiplied and this reads it, giving
 * `mean(a·c) + g·(1 − mean(a))` — what a canvas downscale produces.
 */
export function grayFromPremultipliedRaster(
    raster: Raster,
    ground: readonly [number, number, number],
): Float32Array {
    const { width, height, pixels } = raster;
    const gray = new Float32Array(width * height);
    for (let i = 0; i < gray.length; i++) {
        const rest = 1 - pixels[i * 4 + 3] / 255;
        const r = pixels[i * 4] + ground[0] * rest;
        const g = pixels[i * 4 + 1] + ground[1] * rest;
        const b = pixels[i * 4 + 2] + ground[2] * rest;
        gray[i] = 0.299 * r + 0.587 * g + 0.114 * b;
    }
    return gray;
}

/**
 * Whatever `draw` paints, on `ground`, as a luminance plane. Both sides of a comparison must use
 * the same ground (see {@link COMPARISON_GROUNDS}).
 */
export function grayFromDraw(
    draw: (context: CanvasRenderingContext2D) => void,
    width: number,
    height: number,
    ground: string,
): Float32Array {
    const canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    const context = readableContext(canvas);
    context.imageSmoothingEnabled = true;
    context.imageSmoothingQuality = "high";
    context.fillStyle = ground;
    context.fillRect(0, 0, width, height);
    draw(context);
    const rgba = context.getImageData(0, 0, width, height).data;
    const gray = new Float32Array(width * height);
    for (let i = 0; i < gray.length; i++) {
        gray[i] =
            0.299 * rgba[i * 4] +
            0.587 * rgba[i * 4 + 1] +
            0.114 * rgba[i * 4 + 2];
    }
    return gray;
}

/**
 * The rectangle an image actually draws in, in source pixels, sampled on a downscale since the crop
 * only needs to be roughly right.
 */
export function contentBox(image: Frame): Box {
    const raster = rasterOf(image);
    // A tainted canvas (cross-origin artifact) cannot be sampled. Fall back to the whole image.
    if (!raster) return wholeImage(imageDimensions(image));
    return contentBoxOf(raster);
}

/**
 * {@link contentBox} over a raster the caller already holds, so one decode serves both the content
 * box and the score plane.
 */
export function contentBoxOf(raster: Raster): Box {
    const size = { width: raster.width, height: raster.height };
    const scale = Math.min(
        1,
        BOX_SAMPLE_SIDE / Math.max(size.width, size.height),
    );
    const width = Math.max(1, Math.round(size.width * scale));
    const height = Math.max(1, Math.round(size.height * scale));
    // At `scale === 1` the resample is the identity, so a preview-sized capture is sampled at full
    // resolution and the kernel cannot matter at all.
    const sampled = scale === 1 ? raster : resampleArea(raster, width, height);
    return boxFromSamples(sampled.pixels, width, height, size, scale);
}

/** Both sides measured, and the decision about whether cropping to those measurements is safe. */
export function normalisedBoxes(
    referenceImage: Frame,
    candidateImage: Frame,
): NormalisedBoxes {
    return decideBoxes(
        imageDimensions(referenceImage),
        imageDimensions(candidateImage),
        contentBox(referenceImage),
        contentBox(candidateImage),
    );
}

/** {@link normalisedBoxes} over two rasters the caller already holds. */
export function normalisedBoxesOf(
    reference: Raster,
    candidate: Raster,
): NormalisedBoxes {
    return decideBoxes(
        { width: reference.width, height: reference.height },
        { width: candidate.width, height: candidate.height },
        contentBoxOf(reference),
        contentBoxOf(candidate),
    );
}

/**
 * One image's content box redrawn into a canvas of the shared comparison size. `willReadFrequently`
 * must be set on the first `getContext`, since `getImageData` follows immediately.
 */
export function boxCanvas(
    image: Frame,
    box: Box,
    width: number,
    height: number,
    raster: Raster | null = rasterOf(image),
): HTMLCanvasElement {
    const canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    const context = readableContext(canvas);
    if (!raster) {
        // Unreadable pixels: the browser can still *draw* what it will not let anyone read back, so
        // the visible panel stays right even though nothing downstream can measure it.
        context.drawImage(
            image,
            box.x,
            box.y,
            box.width,
            box.height,
            0,
            0,
            width,
            height,
        );
        return canvas;
    }
    // The same crop-and-resample as the score plane, so the magenta map marks the pixels the number
    // was computed over.
    const scaled = cropTo(raster, box, width, height);
    const painted = context.createImageData(width, height);
    painted.data.set(scaled.pixels);
    context.putImageData(painted, 0, 0);
    return canvas;
}

/** The RGBA of an already-painted canvas, for the delta map. */
export function pixelsOf(
    canvas: HTMLCanvasElement,
    width: number,
    height: number,
): Uint8ClampedArray {
    return readableContext(canvas).getImageData(0, 0, width, height).data;
}

/** A fresh, fully transparent RGBA buffer sized to `target`, plus the context that will show it. */
export function blankMap(
    target: HTMLCanvasElement,
    width: number,
    height: number,
): { context: CanvasRenderingContext2D; image: ImageData } {
    target.width = width;
    target.height = height;
    const context = readableContext(target);
    return { context, image: context.createImageData(width, height) };
}
