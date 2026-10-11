// Grabbing a picture of what the visitor is actually looking at. The triptych, wipe, exploded
// stack, Remote Compose canvas and lane errors are composed in the browser and have no URL, so the
// server cannot produce them (see #4261). DOM-to-SVG rasterising reconstructs markup and drops
// canvases, cross-origin fonts and shaders; `getDisplayMedia` returns the painted pixels. The cost
// is a permission prompt; where the API is missing or refused the affordance stays hidden and the
// report asks for a pasted screenshot.

import {
    Rect,
    Scale,
    Size,
    fitWithin,
    frameScale,
    mapRect,
} from "./geometry.js";

/** A frame of the visitor's screen, and enough about it to crop safely. */
export interface Frame {
    canvas: HTMLCanvasElement;
    width: number;
    height: number;
    /**
     * What was shared: `browser` is a tab, `window`/`monitor` are not (unknown `""` counts as
     * not-a-tab). Crops assume the frame is the viewport, which only holds for a tab.
     */
    surface: string;
}

/** Whether this browser can do any of it. */
export function captureSupported(): boolean {
    return (
        typeof navigator !== "undefined" &&
        !!navigator.mediaDevices &&
        typeof navigator.mediaDevices.getDisplayMedia === "function" &&
        typeof HTMLCanvasElement !== "undefined"
    );
}

/** The longest side a stored capture may have — see `fitWithin`. */
const MAX_SIDE = 1600;

/**
 * One frame of the current tab. `preferCurrentTab` (Chromium) and `displaySurface: "browser"` are
 * hints only, hence [Frame.surface] is read back. The track is always stopped before returning so
 * the "sharing your screen" indicator does not linger.
 */
export async function grabFrame(): Promise<Frame> {
    const stream = await navigator.mediaDevices.getDisplayMedia({
        video: { displaySurface: "browser" },
        audio: false,
        // Not in lib.dom: a Chromium-specific hint, harmless where unknown.
        preferCurrentTab: true,
    } as DisplayMediaStreamOptions);
    const track = stream.getVideoTracks()[0];
    try {
        const video = document.createElement("video");
        video.srcObject = stream;
        video.muted = true;
        // Kept out of the layout entirely. It must not be `display: none` — a hidden video is
        // allowed to stop decoding, and then the first frame never arrives.
        video.style.cssText =
            "position:fixed;left:-10000px;top:0;width:1px;height:1px;opacity:0;pointer-events:none";
        document.body.appendChild(video);
        try {
            await video.play();
            await firstFrame(video);
            const width = video.videoWidth;
            const height = video.videoHeight;
            const canvas = document.createElement("canvas");
            canvas.width = Math.max(1, width);
            canvas.height = Math.max(1, height);
            canvas.getContext("2d")?.drawImage(video, 0, 0);
            return {
                canvas,
                width: canvas.width,
                height: canvas.height,
                surface: String(
                    (track?.getSettings() as { displaySurface?: string })
                        ?.displaySurface ?? "",
                ),
            };
        } finally {
            video.srcObject = null;
            video.remove();
        }
    } finally {
        stream.getTracks().forEach((t) => t.stop());
    }
}

/**
 * Wait for a decoded frame with real dimensions: after the share dialog `videoWidth` is often still
 * 0, yielding a 1×1 capture. Gives up after a bounded wait so a dead stream yields an empty
 * capture, not a hang.
 */
function firstFrame(video: HTMLVideoElement): Promise<void> {
    return new Promise((resolve) => {
        const deadline = Date.now() + 2000;
        const tick = () => {
            if (video.videoWidth > 0 && video.readyState >= 2) return resolve();
            if (Date.now() > deadline) return resolve();
            requestAnimationFrame(tick);
        };
        tick();
    });
}

/** The viewport the frame is a picture of, in CSS px. */
export function viewportSize(): Size {
    return {
        width: window.innerWidth || document.documentElement.clientWidth || 0,
        height:
            window.innerHeight || document.documentElement.clientHeight || 0,
    };
}

/** CSS px → frame px for this frame. */
export function scaleOf(frame: Frame): Scale {
    return frameScale(
        { width: frame.width, height: frame.height },
        viewportSize(),
    );
}

/**
 * Cut [rect] (CSS px, viewport-relative) out of [frame] and downscale it for the `sessionStorage`
 * budget. Cropping before resizing avoids compounded resampling on text-heavy captures.
 */
export function crop(frame: Frame, rect: Rect): HTMLCanvasElement {
    const source = mapRect(rect, scaleOf(frame));
    const fitted = fitWithin(
        { width: source.width, height: source.height },
        MAX_SIDE,
    );
    const out = document.createElement("canvas");
    out.width = fitted.width;
    out.height = fitted.height;
    const ctx = out.getContext("2d");
    if (ctx) {
        ctx.imageSmoothingQuality = "high";
        ctx.drawImage(
            frame.canvas,
            source.x,
            source.y,
            source.width,
            source.height,
            0,
            0,
            fitted.width,
            fitted.height,
        );
    }
    return out;
}

/**
 * The whole frame, downscaled the same way, in frame coordinates so it is right for any shared
 * surface.
 */
export function whole(frame: Frame): HTMLCanvasElement {
    const fitted = fitWithin(
        { width: frame.width, height: frame.height },
        MAX_SIDE,
    );
    const out = document.createElement("canvas");
    out.width = fitted.width;
    out.height = fitted.height;
    const ctx = out.getContext("2d");
    if (ctx) {
        ctx.imageSmoothingQuality = "high";
        ctx.drawImage(frame.canvas, 0, 0, fitted.width, fitted.height);
    }
    return out;
}

/**
 * The clipboard hand-off. The blob goes in as a promise and the `ClipboardItem` is built
 * synchronously, because Safari refuses a write outside the authorising click.
 */
export function copyPng(blob: Promise<Blob>): Promise<void> {
    if (typeof ClipboardItem === "undefined" || !navigator.clipboard?.write) {
        return Promise.reject(new Error("no clipboard"));
    }
    return navigator.clipboard.write([
        new ClipboardItem({ "image/png": blob }),
    ]);
}

/** A canvas as PNG bytes. */
export function toBlob(canvas: HTMLCanvasElement): Promise<Blob> {
    return new Promise((resolve, reject) => {
        canvas.toBlob((blob) => {
            if (blob) resolve(blob);
            else reject(new Error("encode failed"));
        }, "image/png");
    });
}

/** The same bytes as the data URL that survives the navigation to `/report-bug`. */
export function toDataUrl(canvas: HTMLCanvasElement): string {
    return canvas.toDataURL("image/png");
}

/** A stored data URL back to bytes, for a Copy pressed on the report page. */
export function blobFromDataUrl(dataUrl: string): Promise<Blob> {
    return fetch(dataUrl).then((r) => r.blob());
}
