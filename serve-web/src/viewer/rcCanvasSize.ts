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

/**
 * How long the lane waits for the snapshot when the document declares its own size to fall back
 * on: short, since the header size is usable at once and a late snapshot re-fits the canvas.
 */
export const RC_SNAPSHOT_HEADER_WAIT_MS = 3000;

/** How long a lane that fell back keeps listening for the snapshot so it can re-fit. */
export const RC_SNAPSHOT_REFIT_MS = 120000;

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

const RC_MAGIC = 0x048c;
const DOC_WIDTH = 5;
const DOC_HEIGHT = 6;
const DOC_DENSITY_AT_GENERATION = 7;
const DATA_TYPE_INT = 0;
const DATA_TYPE_FLOAT = 1;
const MAX_HEADER_PROPERTIES = 1000;

/** What a Remote Compose document's `Header` operation declares about its own geometry. */
export interface RcDocumentHeader {
    /** The pixel size the document was captured at, or null when it declares none. */
    size: PixelSize | null;
    /**
     * `DOC_DENSITY_AT_GENERATION` (a float header property; the document's dp→px factor), or null
     * when it declares none — always null for the untagged form, which has no property table.
     */
    density: number | null;
}

/**
 * Read a Remote Compose document's `Header` operation, in either the untagged (version, width,
 * height) or the tagged property-table form; null when the buffer is not a Remote Compose
 * document. Mirrors the server's `ServeDocFormats` reader. A truncated table yields what was read.
 */
export function rcDocumentHeader(buf: ArrayBuffer): RcDocumentHeader | null {
    const v = new DataView(buf);
    if (v.byteLength < 13 || v.getUint8(0) !== 0) return null;
    const major = v.getInt32(1);
    let o = 13;
    if (major >>> 16 === RC_MAGIC) {
        let width = 0,
            height = 0,
            density = 0;
        try {
            const count = Math.min(
                Math.max(v.getInt32(o), 0),
                MAX_HEADER_PROPERTIES,
            );
            o += 4;
            for (let i = 0; i < count; i++) {
                const tag = v.getUint16(o);
                const length = v.getUint16(o + 2);
                o += 4;
                const dataType = tag >> 10,
                    key = tag & 0x3f;
                if (dataType === DATA_TYPE_INT && length === 4) {
                    if (key === DOC_WIDTH) width = v.getInt32(o);
                    if (key === DOC_HEIGHT) height = v.getInt32(o);
                } else if (
                    dataType === DATA_TYPE_FLOAT &&
                    length === 4 &&
                    key === DOC_DENSITY_AT_GENERATION
                ) {
                    density = v.getFloat32(o);
                }
                o += length;
            }
        } catch (e) {
            /* truncated table: keep what was read */
        }
        return {
            size: width > 0 && height > 0 ? { width, height } : null,
            density: positive(density) ? density : null,
        };
    }
    if (major >>> 16 !== 0 || major < 1 || v.byteLength < 21) return null;
    const width = v.getInt32(o),
        height = v.getInt32(o + 4);
    if (!(width > 0 && height > 0)) return null;
    return { size: { width, height }, density: null };
}

/**
 * The pixel size a Remote Compose document declares in its `Header` operation, or null when it
 * declares none or is not a Remote Compose document. It is the size the document was captured at,
 * so the baked snapshot shares it.
 */
export function rcDocumentSize(buf: ArrayBuffer): PixelSize | null {
    const header = rcDocumentHeader(buf);
    return header ? header.size : null;
}

/**
 * The density a browser RC lane should impose on a document, or null to leave the player to the
 * document: a document that declares `DOC_DENSITY_AT_GENERATION` is drawn at it (both players
 * already honour it), and only an undeclared one takes the page's `data-rc-density` — the
 * preview's render density, or 1 for a document-only catalog. Null too when the page carries none,
 * which leaves the player's own default (`devicePixelRatio` for camaelon-js).
 */
export function rcLaneDensity(
    header: RcDocumentHeader | null,
    pageDensity: number | null,
): number | null {
    if (header && header.density !== null) return null;
    return pageDensity !== null && positive(pageDensity) ? pageDensity : null;
}

/** `data-rc-density` read off the viewer root; null when absent or not a positive number. */
export function parseRcDensityAttr(value: string | null): number | null {
    if (value === null) return null;
    const d = parseFloat(value);
    return positive(d) ? d : null;
}

/**
 * The cmp-wasm player's `?density=`, in CSS px per dp (the player multiplies it by
 * `devicePixelRatio`). The density d is the document's declared `DOC_DENSITY_AT_GENERATION`, else
 * the page's `data-rc-density`, else none (null: the parameter is omitted). The iframe is laid over
 * the snapshot's displayed box, which may be scaled from its natural pixel size (`max-width`,
 * zoom), so d is multiplied by `displayedWidth / naturalWidth`; with either unknown, the box is
 * taken at natural size. Unlike camaelon-js — whose canvas is the document's own pixel size, so
 * `setDensity` takes d itself — the wasm player always receives the density, declared or not.
 */
export function rcWasmDensity(
    header: RcDocumentHeader | null,
    pageDensity: number | null,
    displayedWidth: number,
    naturalWidth: number,
): number | null {
    const declared = header ? header.density : null;
    const d =
        declared !== null
            ? declared
            : pageDensity !== null && positive(pageDensity)
              ? pageDensity
              : null;
    if (d === null) return null;
    const scale =
        positive(displayedWidth) && positive(naturalWidth)
            ? displayedWidth / naturalWidth
            : 1;
    return d * scale;
}

/** A density as a plain decimal (no exponent), to four places, without trailing zeros. */
export function formatRcDensity(density: number): string {
    return String(Number(density.toFixed(4)));
}

/**
 * Whether the image on stage still shows the preview at its captured layout, so its size can stand
 * for the document's viewport. [renderedUrl] is the `/render` URL its bytes came from
 * (`data-cp-src`), absent for the page's own baked snapshot. A render under a layout-changing
 * override — a device, an orientation, or any `…Px` size bound — is a different viewport from the
 * base `.rc` the canvas lane plays, so it is not.
 */
export function snapshotKeepsCapturedLayout(
    renderedUrl: string | null,
): boolean {
    if (!renderedUrl) return true;
    var q = renderedUrl.indexOf("?");
    if (q < 0) return true;
    var keys = renderedUrl
        .slice(q + 1)
        .split("&")
        .map(function (pair) {
            return decodeURIComponent(pair.split("=")[0] || "");
        });
    return !keys.some(function (key) {
        return key === "device" || key === "orientation" || /Px$/.test(key);
    });
}
