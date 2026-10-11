// Which live frame reaches the canvas, and in what order. Async decodes finish out of order, so a
// heavier frame N could paint over N+1 (one cause of #4159's ripple artifacts). The serve wire's
// frame envelope (`{type:"frame", seq, codec, dataBase64}`) carries a monotonic `seq`, and this
// applies the same ordering rules as the VS Code client
// ([`src/daemon/streamClient.ts`](https://github.com/yschimke/compose-preview-vscode/blob/main/src/daemon/streamClient.ts),
// `docs/daemon/STREAMING.md` § "Client model"). Pure, so it is unit-tested and shared by both
// lanes.

/** One `type: "frame"` message off the serve socket. */
export interface ServeFrame {
    seq: number;
    codec?: string;
    widthPx?: number;
    heightPx?: number;
    dataBase64?: string;
}

/**
 * Newest-wins queue for one socket, holding at most one frame. A newer frame replaces a queued one;
 * frames at or below the last dispatched `seq` are dropped so a replayed wire cannot go backwards.
 */
export class FrameQueue {
    private pending: ServeFrame | null = null;
    private lastDispatchedSeq = -1;

    /** Queue a frame, unless it is stale or an older one is already newer. */
    submit(frame: ServeFrame): void {
        if (!isFiniteSeq(frame.seq)) return;
        if (frame.seq <= this.lastDispatchedSeq) return;
        if (this.pending && this.pending.seq >= frame.seq) return;
        this.pending = frame;
    }

    /** Take the queued frame, if any, and record its `seq` as the new floor. */
    dispatch(): ServeFrame | null {
        const out = this.pending;
        this.pending = null;
        if (out !== null) this.lastDispatchedSeq = out.seq;
        return out;
    }

    /** Test / debug accessor. Painter code should call [dispatch]. */
    peek(): ServeFrame | null {
        return this.pending;
    }
}

/**
 * The ordering guard after decode, since decodes resolve out of order: true when [decodedSeq] is
 * newer than [paintedSeq]; callers then bump their watermark to [decodedSeq].
 */
export function shouldPaintDecodedFrame(
    paintedSeq: number,
    decodedSeq: number,
): boolean {
    return decodedSeq > paintedSeq;
}

/**
 * Drain one frame per animation frame while [alive] holds. Rescheduling sits in a `finally`, so a
 * throwing tick loses only its own frame instead of stopping the loop for good (see #4313).
 * [schedule] defaults to `requestAnimationFrame` and is injectable for tests; [onError] is for
 * diagnostics only.
 */
export function pumpFrames(opts: {
    alive: () => boolean;
    next: () => ServeFrame | null;
    paint: (frame: ServeFrame) => void;
    schedule?: (cb: () => void) => void;
    onError?: (error: unknown) => void;
}): void {
    const schedule =
        opts.schedule ??
        function (cb: () => void) {
            requestAnimationFrame(cb);
        };
    const tick = function () {
        if (!opts.alive()) return;
        try {
            const frame = opts.next();
            if (frame) opts.paint(frame);
        } catch (error) {
            opts.onError?.(error);
        } finally {
            schedule(tick);
        }
    };
    schedule(tick);
}

/**
 * A frame's bytes as a `Blob` for `createImageBitmap`, which decodes off the main thread so the
 * visible canvas is never torn down mid-decode. Null for an `unchanged` heartbeat (no payload) or a
 * payload `atob` rejects — one dropped frame rather than a throw.
 */
export function frameBlob(frame: ServeFrame): Blob | null {
    const b64 = frame.dataBase64;
    if (!b64) return null;
    let binary: string;
    try {
        binary = atob(b64);
    } catch {
        return null;
    }
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return new Blob([bytes], { type: `image/${frame.codec || "png"}` });
}

function isFiniteSeq(seq: unknown): seq is number {
    return typeof seq === "number" && Number.isFinite(seq);
}
