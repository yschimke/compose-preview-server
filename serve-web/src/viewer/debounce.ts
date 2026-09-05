// Coalescing a drag into one request.
//
// A range input fires `input` once per step of travel, and a text or number input once per
// keystroke. Every one of those events reaches `onControlsChanged`, and on a session that can
// re-render — a live daemon, or a published catalog carrying one — every one of them is a fresh
// `/render`. Dragging the font-scale slider across its sixteen stops therefore issued fifteen
// renders of the same preview, of which the visitor wanted only the last.
//
// That is not merely wasteful. Renders serialise on a per-daemon lock, and a request that cannot
// take it inside `ServeRenderHost.DAEMON_BUSY_WAIT_MS` is refused with a retryable 503 — so the
// burst a single drag produces is largely a burst the server rejects, and the reject that lands
// last is the one the stage keeps. The controls looked broken precisely because they were being
// used normally.
//
// The exploded-view knobs already coalesced their drags, with their own timer and their own
// comment saying why (`scheduleExplodeRender`). Their renders are the cheap kind — a rewrite of
// cached bytes, no daemon involved — so the case that most needed this had it least. This is that
// same idea, factored so a control's wiring can ask for it in one call.

/**
 * Wrap [fn] so that a burst of calls runs it once, [waitMs] after the burst stops.
 *
 * Trailing rather than leading: the value a drag is worth rendering is the one it ends on, and a
 * leading edge would render the first step of the travel — the value the visitor was moving away
 * from — and then debounce away the one they chose.
 *
 * Each wrapper owns its timer, so two controls edited inside one window still produce two
 * renders. Sharing one would be defensible (a render reads every control at fire time, so the
 * second would subsume the first) but it would also mean a slider drag could swallow a knob edit
 * that happened to land in the same 200ms, which is a bug that would be very hard to see.
 */
export function debounced(
    fn: () => void,
    waitMs: number,
): { (): void; cancel: () => void } {
    let timer: ReturnType<typeof setTimeout> | null = null;
    const run = function () {
        if (timer !== null) clearTimeout(timer);
        timer = setTimeout(function () {
            timer = null;
            fn();
        }, waitMs);
    };
    run.cancel = function () {
        if (timer === null) return;
        clearTimeout(timer);
        timer = null;
    };
    return run;
}

/**
 * The window a continuous control's edits are coalesced over.
 *
 * Long enough to swallow a drag — a slider moved by hand emits steps tens of milliseconds apart,
 * and a typed number arrives faster than that — and short enough that releasing the control feels
 * like it rendered immediately. A warm render on the public host is ~0.25–1.1s, so this is well
 * inside the noise of the request it is replacing fifteen of.
 */
export const CONTINUOUS_EDIT_DEBOUNCE_MS = 200;
