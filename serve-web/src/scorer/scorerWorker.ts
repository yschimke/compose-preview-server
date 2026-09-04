// Entry point for `assets/compare-scorer.js` — the comparison metric, off the main thread.
//
// Nothing but a message pump around {@link scorePlanes}. That function is already the whole of the
// hot path and already says why it can live here: "No DOM here. Everything below is Float32Array
// in, number out." The planes are produced on the main thread, where the canvases are; the scan
// over them is what this file exists to move.
//
// The **yield is dropped**, and that is half the point. `scorePlanes` awaits `yieldScorer` — a
// `setTimeout(0)` — every eighth row so the page can paint mid-scan. A worker has no page to paint,
// so the yield buys nothing there and costs a great deal: the browser clamps nested timeouts to
// ~4ms, and a 440-row frame takes 55 of them per direction, twice, which is a third of a second of
// waiting per comparison spent purely on being interruptible. On this thread the scan simply runs.
//
// Loaded only when a page hands `scorer/offload.ts` a URL for it. Anything that does not — the
// publish-time score driver and the compare audit, which inject `format-compare.js` into a bare
// page — keeps scoring on the calling thread, unchanged.

import { scorePlanes } from "./planes.js";

/** What {@link postScore} is asked to measure. One comparison, one message. */
export interface ScoreRequest {
    id: number;
    reference: Float32Array;
    candidate: Float32Array;
    width: number;
    height: number;
}

/** What comes back: the percentage, or the reason there isn't one. */
export type ScoreReply =
    { id: number; percent: number } | { id: number; error: string };

/** No yielding: see the header. */
const immediately = (): Promise<void> => Promise.resolve();

self.addEventListener("message", (event: MessageEvent<ScoreRequest>) => {
    const { id, reference, candidate, width, height } = event.data;
    scorePlanes(reference, candidate, width, height, immediately).then(
        (percent) => {
            const reply: ScoreReply = { id, percent };
            (self as unknown as Worker).postMessage(reply);
        },
        (cause: unknown) => {
            const reply: ScoreReply = {
                id,
                error: cause instanceof Error ? cause.message : String(cause),
            };
            (self as unknown as Worker).postMessage(reply);
        },
    );
});
