// Where a recorded interaction is up to, and what each transport control does. The browser loops an
// APNG forever (`loopCount = 0`) with no pausing or slowing, so on → off → on is indistinguishable
// from its reverse; the viewer drives playback itself. This is the DOM-free part (position in ms,
// rate, running); `viewer.ts` owns the decoder, canvas and clock.
//
// [tick] stops on the last frame instead of wrapping, so a transition can be read; play from the
// end restarts from the top ([toggle]).

/** The frames a capture published, and how long each is held. */
export interface MotionTimeline {
    /** Frames in the capture. Always ≥ 1 — a capture with none is not a capture. */
    frameCount: number;
    /**
     * How long one frame is held, in ms. Uniform because every capture here is (the recorder
     * advances a fixed `frameIntervalMs` and both encoders write that delay), and a per-frame table
     * would depend on undecoded frames.
     */
    frameDurationMs: number;
}

/** Where the playhead is, and what it is doing. */
export interface PlaybackState {
    /** Milliseconds from the start of the capture. Never past [spanMs]. */
    positionMs: number;
    /** Whether the clock is advancing. False at the end of a pass, and while scrubbing. */
    playing: boolean;
    /** Clock multiplier — 1 is the rate the capture was recorded at. */
    rate: number;
}

/**
 * Offered rates, slowest first. Captures are 60fps and often document a ~300ms spring, so 0.25×
 * makes overshoot visible; 2× suits long scripted interactions where gaps dominate.
 */
export const PLAYBACK_RATES = [0.25, 0.5, 1, 2] as const;

/** The rate a lane opens at. */
export const DEFAULT_RATE = 1;

/**
 * How far the playhead can travel: the last frame's timestamp at 1×, one frame short of the run
 * time, since the playhead addresses frames (otherwise the bar reads (N-1)/N full at the last
 * frame).
 */
export function spanMs(timeline: MotionTimeline): number {
    return Math.max(0, timeline.frameCount - 1) * timeline.frameDurationMs;
}

/** Which frame is on screen at [positionMs], clamped at both ends rather than wrapped. */
export function frameAt(timeline: MotionTimeline, positionMs: number): number {
    if (timeline.frameDurationMs <= 0) return 0;
    const raw = Math.floor(positionMs / timeline.frameDurationMs);
    return Math.min(Math.max(raw, 0), Math.max(0, timeline.frameCount - 1));
}

/** The position that puts [frame] on screen — its first instant, not its midpoint. */
export function positionOfFrame(
    timeline: MotionTimeline,
    frame: number,
): number {
    const clamped = Math.min(
        Math.max(frame, 0),
        Math.max(0, timeline.frameCount - 1),
    );
    return clamped * timeline.frameDurationMs;
}

/** How far through the capture the playhead is, 0…1 — what the timeline bar fills to. */
export function progress(timeline: MotionTimeline, positionMs: number): number {
    const span = spanMs(timeline);
    // A single-frame capture has nowhere to travel, and there is nothing left to play: full, not
    // empty, which is also what its thumb (min 0, max 0) shows.
    if (span <= 0) return 1;
    return Math.min(1, Math.max(0, positionMs / span));
}

/** True once the playhead is sitting on the last frame with nothing left to play. */
export function atEnd(timeline: MotionTimeline, positionMs: number): boolean {
    return frameAt(timeline, positionMs) >= timeline.frameCount - 1;
}

/**
 * Advance by [elapsedMs] × rate, so slower playback holds frames longer without skipping any.
 * Paused state is returned untouched. Stops on the last frame, the interaction's resting state.
 */
export function tick(
    state: PlaybackState,
    timeline: MotionTimeline,
    elapsedMs: number,
): PlaybackState {
    if (!state.playing) return state;
    const advanced = state.positionMs + Math.max(0, elapsedMs) * state.rate;
    const last = positionOfFrame(timeline, timeline.frameCount - 1);
    if (advanced >= last) return { ...state, positionMs: last, playing: false };
    return { ...state, positionMs: advanced };
}

/** Play/pause, and from a finished pass, play again from the top. */
export function toggle(
    state: PlaybackState,
    timeline: MotionTimeline,
): PlaybackState {
    if (state.playing) return { ...state, playing: false };
    if (atEnd(timeline, state.positionMs))
        return { ...state, positionMs: 0, playing: true };
    return { ...state, playing: true };
}

/** Back to the first frame and running — the ↻ button, and what entering the lane does. */
export function replay(state: PlaybackState): PlaybackState {
    return { ...state, positionMs: 0, playing: true };
}

/** Scrub to a frame; always pauses, since scrubbing is inspection. */
export function seek(
    state: PlaybackState,
    timeline: MotionTimeline,
    frame: number,
): PlaybackState {
    return {
        ...state,
        positionMs: positionOfFrame(timeline, frame),
        playing: false,
    };
}

/** Step one frame either way, from wherever the playhead is. Pauses, for the same reason [seek] does. */
export function step(
    state: PlaybackState,
    timeline: MotionTimeline,
    delta: number,
): PlaybackState {
    return seek(state, timeline, frameAt(timeline, state.positionMs) + delta);
}

/** Pick the offered rate nearest a raw value, so a hand-edited URL or a stale control cannot set 37×. */
export function normaliseRate(raw: number | string | null | undefined): number {
    const value = typeof raw === "string" ? parseFloat(raw) : raw;
    if (!value || !isFinite(value) || value <= 0) return DEFAULT_RATE;
    return PLAYBACK_RATES.reduce(
        (best, rate) =>
            Math.abs(rate - value) < Math.abs(best - value) ? rate : best,
        PLAYBACK_RATES[0] as number,
    );
}

/**
 * The readout: elapsed of total in seconds (one decimal), then the frame number, the unit the ← / →
 * keys step in.
 */
export function readout(timeline: MotionTimeline, positionMs: number): string {
    const seconds = (ms: number) => `${(ms / 1000).toFixed(1)}s`;
    const frame = frameAt(timeline, positionMs) + 1;
    return `${seconds(positionMs)} / ${seconds(spanMs(timeline))} · frame ${frame}/${timeline.frameCount}`;
}
