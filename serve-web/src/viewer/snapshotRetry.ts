// What a failed `/render` response means, and whether the viewer should ask again.
//
// The snapshot lane has always had retry machinery — a bounded, server-paced re-request for a
// refusal that says "not yet" rather than "never". It was reachable from exactly one refusal: a
// 503 carrying `X-Compose-Preview-Dropped-Overrides`. Every other retryable refusal fell past it
// into the terminal branch and printed "render failed for this preview", including the one the
// public server actually returns under load.
//
// That one is `503 render busy; retry shortly` + `Retry-After: 2`. The daemon serialises renders
// behind a bounded lock (`ServeRenderHost.DAEMON_BUSY_WAIT_MS`, 2s); a request that cannot take
// the lock in that window backs off rather than pinning an HTTP render slot, and — because an
// override-bearing request must never be answered with baked pixels that ignore it (#3449) — the
// server has nothing honest to return but a retryable refusal. It says so, in the status line and
// in a header, and the page ignored both.
//
// So the decision moves here, keyed on the STATUS rather than on which header happened to be
// present. The dropped-overrides header still shapes the message the visitor reads — "not
// rendered with fontScale" is a different sentence from "the render is busy" — but it no longer
// decides whether asking again is worth doing.
//
// DOM-free: `viewer.js` reads the response and passes plain values.

/** A `/render` response the viewer could not paint, reduced to the three things that matter. */
export interface SnapshotFailure {
    /**
     * The HTTP status, or `0` when no response arrived at all (a dropped connection, a decode
     * failure downstream of a 200).
     */
    status: number;
    /**
     * The `X-Compose-Preview-Dropped-Overrides` value, or `""` when the response carried none.
     *
     * Present only on the correctness refusal: the server would have had to answer an override
     * with pixels that ignore it, so it refused instead and named the parameters it could not
     * apply. It changes the wording, never the verdict.
     */
    dropped: string;
    /** The server's `Retry-After` in seconds, or `0` when it named none. */
    retryAfterSeconds: number;
}

/** The wait used when a retryable refusal names no `Retry-After` of its own. */
const DEFAULT_RETRY_AFTER_SECONDS = 2;

/**
 * Whether asking again could ever produce a different answer.
 *
 * Retryable is the narrow set, spelled out rather than inferred, because the cost of getting it
 * wrong runs in both directions: treat a permanent refusal as retryable and the page hammers a
 * server that has already given its final answer; treat a transient one as permanent and the
 * visitor is left looking at "render failed" on a preview that renders perfectly two seconds
 * later.
 *
 *   * **503** — every load-shed refusal on the render lane: `render busy` (the daemon lock backed
 *     off), `render queue saturated` (the HTTP semaphore did), `warming` (no render has landed on
 *     this host yet). All of them are "not yet".
 *   * **429** — the theme-render lease is saturated. Same claim, different budget.
 *   * **409** — the explicit terminal refusal: this preview can only ever be served as its
 *     published snapshot, so no amount of waiting makes the override applicable.
 *
 * Everything else is terminal by default, including `0`. A request that never got a response has
 * not been told anything about whether the work is possible, and a page that retries on its own
 * guess is how a broken deployment turns into a request storm — a control change starts a fresh
 * attempt sequence anyway, so the visitor is never stuck without a way to ask again.
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
 * How long to wait before attempt number [attempt] (1-based).
 *
 * Paced off the server's own `Retry-After` when it sent one, so a box that knows how busy it is
 * sets the interval rather than the page guessing at it, and multiplied by the attempt so a lane
 * that is genuinely down is backed away from instead of polled at a fixed rate.
 */
export function snapshotRetryWaitMs(
    failure: SnapshotFailure,
    attempt: number,
): number {
    const seconds = failure.retryAfterSeconds || DEFAULT_RETRY_AFTER_SECONDS;
    return seconds * 1000 * Math.max(1, attempt);
}

/**
 * What the visitor reads on the stage.
 *
 * [format] is the snapshot format that was asked for (`"PNG"` / `"SVG"`), which matters because a
 * preview can export one and not the other.
 *
 * The dropped-overrides wording is deliberately not "render failed": the preview is fine and its
 * published pixels are right there — what is unavailable is the live lane that would have applied
 * `fontScale`. Saying "failed" there sent people looking for a broken preview.
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
