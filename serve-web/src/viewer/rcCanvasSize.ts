// The camaelon-js Remote Compose lane derives the document viewport from its canvas's size at load
// time, so the canvas must carry the preview's real pixel dimensions before the document loads. The
// baked snapshot is the source of those dimensions, but on a deep link (`?mode=rc`) the lane opens
// while the snapshot is still being fetched: its `naturalWidth` is 0 then, and a canvas left at the
// HTML default of 300×150 lays the document out into a sliver and paints black. These helpers wait
// for the snapshot to decode and, failing that, pick a size that is never the default.

export interface PixelSize {
    width: number;
    height: number;
}

/** The size, in dp, assumed when neither a decoded snapshot nor a measurable stage exists. */
export const RC_FALLBACK_DP: PixelSize = { width: 360, height: 640 };

/** How long the lane waits for the snapshot to decode before falling back. */
export const RC_SNAPSHOT_WAIT_MS = 15000;

function positive(n: number): boolean {
    return typeof n === "number" && isFinite(n) && n > 0;
}

/**
 * The canvas size for the RC lane: the decoded snapshot's natural size when there is one, else the
 * stage's CSS box scaled by the render density, else [RC_FALLBACK_DP] at that density. Always whole
 * positive pixels.
 */
export function rcCanvasSize(
    natural: PixelSize | null,
    stageBox: PixelSize | null,
    density: number,
): PixelSize {
    if (natural && positive(natural.width) && positive(natural.height)) {
        return {
            width: Math.round(natural.width),
            height: Math.round(natural.height),
        };
    }
    var d = positive(density) ? density : 1;
    var box =
        stageBox && positive(stageBox.width) && positive(stageBox.height)
            ? stageBox
            : RC_FALLBACK_DP;
    return {
        width: Math.max(1, Math.round(box.width * d)),
        height: Math.max(1, Math.round(box.height * d)),
    };
}

/** The image's natural size when it has decoded, else null. */
export function decodedSize(img: HTMLImageElement): PixelSize | null {
    return img.naturalWidth > 0 && img.naturalHeight > 0
        ? { width: img.naturalWidth, height: img.naturalHeight }
        : null;
}

/**
 * Resolves with the image's natural size once it has decoded — immediately when it already has —
 * or with null after [timeoutMs]. A failed load does not settle it: the viewer retries snapshots
 * into the same element, so a later `load` still counts.
 */
export function whenImageSized(
    img: HTMLImageElement,
    timeoutMs: number,
): Promise<PixelSize | null> {
    var now = decodedSize(img);
    if (now) return Promise.resolve(now);
    return new Promise(function (resolve) {
        var timer: ReturnType<typeof setTimeout> | null = null;
        function finish(size: PixelSize | null) {
            img.removeEventListener("load", onLoad);
            if (timer !== null) clearTimeout(timer);
            timer = null;
            resolve(size);
        }
        function onLoad() {
            var size = decodedSize(img);
            if (size) finish(size);
        }
        img.addEventListener("load", onLoad);
        timer = setTimeout(function () {
            finish(decodedSize(img));
        }, timeoutMs);
    });
}
