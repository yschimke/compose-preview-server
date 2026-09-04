// Where the comparison metric runs: a worker when the page has said where one lives, this thread
// otherwise.
//
// `scorePlanes` is the expensive half of every comparison on this site — a two-directional
// neighbourhood search over both luminance planes — and it ran on the main thread, interleaved with
// a `setTimeout(0)` every eighth row so the page could still paint. That kept the tab alive at the
// cost of making each comparison much slower than the arithmetic in it, and it still spent the
// browser's one thread on pixels while the reader was trying to scroll.
//
// **Opt-in by URL, and that is deliberate.** The offload only happens when the page carries a
// `<script data-cp-scorer-worker="…">` naming the built worker; with no such attribute this module
// calls `scorePlanes` directly, exactly as before. Two consumers depend on that being the default:
// `scripts/design-artifacts/design-reference-score.mjs` and `scripts/compare-audit.mjs` inject
// `format-compare.js` into a bare page and drive `window.ComposePreviewCompare` there, so they have
// no asset URL to name and must keep getting the same numbers on the calling thread. A page that
// scores nothing pays for nothing either — the worker is constructed on the first comparison, not
// on load.
//
// Every failure falls back rather than surfacing: a browser with no `Worker`, a construction that
// throws (a CSP that forbids the URL), a worker that errors, a reply that never comes. The metric
// is identical either way — same function, same tuning constants — so falling back costs latency
// and nothing else, and a comparison that cannot be offloaded must never become a comparison that
// does not happen.

import { scorePlanes, type Plane } from "./planes.js";
import type { ScoreReply, ScoreRequest } from "./scorerWorker.js";

/** How long a reply may take before this comparison gives up and re-scores locally. */
const REPLY_TIMEOUT_MS = 30_000;

/** The attribute the serving page puts the built worker's URL in. See `ServeWeb.compareScorer`. */
const URL_ATTRIBUTE = "data-cp-scorer-worker";

type Pending = {
    resolve: (percent: number) => void;
    reject: (cause: Error) => void;
    timer: ReturnType<typeof setTimeout>;
};

let worker: Worker | null = null;
/** True once a worker has been ruled out for this page — no URL, no `Worker`, or one that failed. */
let refused = false;
let nextId = 1;
const pending = new Map<number, Pending>();

/** The URL the page named, or null. Read per attempt: the tag is written before any comparison. */
function workerUrl(): string | null {
    if (typeof document === "undefined") return null;
    const tag = document.querySelector<HTMLElement>(`[${URL_ATTRIBUTE}]`);
    const url = tag?.getAttribute(URL_ATTRIBUTE)?.trim();
    return url ? url : null;
}

/**
 * Give up on the worker for the rest of this page, failing anything still in flight.
 *
 * The rejections matter: {@link scorePlanesOffloaded} catches them and re-scores locally, so a
 * worker that dies mid-wall costs those comparisons a repeat rather than a row of "unavailable".
 */
function refuse(reason: string): void {
    refused = true;
    if (worker) {
        worker.terminate();
        worker = null;
    }
    for (const [, entry] of pending) {
        clearTimeout(entry.timer);
        entry.reject(new Error(reason));
    }
    pending.clear();
}

function ensureWorker(): Worker | null {
    if (refused) return null;
    if (worker) return worker;
    const url = workerUrl();
    if (!url || typeof Worker === "undefined") {
        refused = true;
        return null;
    }
    try {
        worker = new Worker(url);
    } catch {
        // A CSP that forbids the URL, or an origin that will not serve it. Neither is retryable.
        refused = true;
        return null;
    }
    worker.addEventListener("message", (event: MessageEvent<ScoreReply>) => {
        const reply = event.data;
        const entry = pending.get(reply.id);
        if (!entry) return;
        pending.delete(reply.id);
        clearTimeout(entry.timer);
        if ("error" in reply) entry.reject(new Error(reply.error));
        else entry.resolve(reply.percent);
    });
    // A worker that fails to load reports here rather than throwing from the constructor.
    worker.addEventListener("error", () => refuse("scorer worker failed"));
    return worker;
}

/** The planes as the transferable shape the worker is typed against. */
function asFloat32(plane: Plane): Float32Array {
    return plane instanceof Float32Array ? plane : Float32Array.from(plane);
}

/**
 * {@link scorePlanes}, run wherever it is cheapest — and always answering.
 *
 * The planes are **copied** into the worker rather than transferred: a caller scores the same pair
 * against more than one comparison ground (`scoreOnEveryGround`), so detaching the buffers here
 * would empty the planes the next ground is about. The copy is one memcpy against a scan that
 * visits every pixel's neighbourhood twice.
 */
export async function scorePlanesOffloaded(
    reference: Plane,
    candidate: Plane,
    width: number,
    height: number,
): Promise<number> {
    const active = ensureWorker();
    if (!active) return scorePlanes(reference, candidate, width, height);
    const id = nextId++;
    const request: ScoreRequest = {
        id,
        reference: asFloat32(reference),
        candidate: asFloat32(candidate),
        width,
        height,
    };
    try {
        return await new Promise<number>((resolve, reject) => {
            const timer = setTimeout(
                () => refuse("scorer worker timed out"),
                REPLY_TIMEOUT_MS,
            );
            pending.set(id, { resolve, reject, timer });
            active.postMessage(request);
        });
    } catch {
        // Whatever went wrong, the answer is the same one this module exists to protect: score it
        // here. `refuse` has already taken the worker out for the rest of the page.
        pending.delete(id);
        return scorePlanes(reference, candidate, width, height);
    }
}

/** Test seam: forget the worker and the refusal, so a spec can set a different page up. */
export function resetScorerOffloadForTests(): void {
    if (worker) worker.terminate();
    worker = null;
    refused = false;
    pending.clear();
}
