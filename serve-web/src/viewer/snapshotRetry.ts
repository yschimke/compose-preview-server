// What a failed `/render` response means and whether the viewer should ask again. Keyed on status,
// not on which header is present: under load the server answers `503 render busy` + `Retry-After`
// (the daemon lock backed off, and override-bearing requests must never get baked pixels), which
// used to fall into the terminal branch. The dropped-overrides header only shapes the message.
// DOM-free: `viewer.js` passes plain values.

/** A `/render` response the viewer could not paint, reduced to the three things that matter. */
export interface SnapshotFailure {
    /**
     * The HTTP status, or `0` when no response arrived (dropped connection, decode failure after a
     * 200).
     */
    status: number;
    /**
     * The `X-Compose-Preview-Dropped-Overrides` value, or `""`. Present only on the correctness
     * refusal; it changes the wording, never the verdict.
     */
    dropped: string;
    /** The server's `Retry-After` in seconds, or `0` when it named none. */
    retryAfterSeconds: number;
}

/** The wait used when a retryable refusal names no `Retry-After` of its own. */
const DEFAULT_RETRY_AFTER_SECONDS = 2;

/**
 * Whether asking again could produce a different answer, spelled out because both mistakes hurt
 * (hammering a final answer vs. "render failed" on a transient):
 * - **503** — load-shed refusals (`render busy`, `render queue saturated`, `warming`): "not yet".
 * - **429** — the theme-render lease is saturated.
 * - **409** — explicit terminal refusal (snapshot only), so not retryable.
 *
 * Everything else is terminal, including `0`: retrying on a guess turns a broken deployment into a
 * request storm, and a control change starts a fresh attempt anyway.
 */
export function isRetryableSnapshotFailure(failure: SnapshotFailure): boolean {
    return failure.status === 503 || failure.status === 429;
}

/** Whether a bounded, server-paced retry is still owed for [failure]. */
export function willRetrySnapshot(
    failure: SnapshotFailure,
    retriesSoFar: number,
    limit: number,
): boolean {
    return isRetryableSnapshotFailure(failure) && retriesSoFar < limit;
}

/**
 * Wait before attempt [attempt] (1-based): the server's `Retry-After` when sent, multiplied by the
 * attempt so a down lane is backed away from.
 */
export function snapshotRetryWaitMs(
    failure: SnapshotFailure,
    attempt: number,
): number {
    const seconds = failure.retryAfterSeconds || DEFAULT_RETRY_AFTER_SECONDS;
    return seconds * 1000 * Math.max(1, attempt);
}

/**
 * What the visitor reads on the stage. [format] is the requested snapshot format (`"PNG"`/`"SVG"`).
 * The dropped-overrides wording avoids "render failed": the preview is fine, only the live lane
 * that would apply the override is unavailable.
 */
export function snapshotFailureMessage(
    failure: SnapshotFailure,
    willRetry: boolean,
    format: string,
): string {
    if (failure.dropped) {
        const params = failure.dropped.split(",").join(", ");
        return (
            "Not rendered with " +
            params +
            " — " +
            (willRetry
                ? "the live render is warming up; retrying…"
                : isRetryableSnapshotFailure(failure)
                  ? "the live render is unavailable right now; change a control to try again."
                  : "this preview can only be served as its published snapshot.")
        );
    }
    if (willRetry) return format + " render is busy; retrying…";
    if (isRetryableSnapshotFailure(failure))
        return (
            format +
            " render is still busy — the server is saturated; change a control to try again."
        );
    return format + " render failed for this preview.";
}
