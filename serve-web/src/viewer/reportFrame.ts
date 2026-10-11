// Whether the prefilled report may be rewritten from the controls yet. Controls and copyable links
// move with a knob immediately, but the image only after the new frame decodes (or never, if it
// fails). A report composed in that gap would carry a `compose-parity-locator/v1` and render URL
// for a frame the reporter never saw (the D4 defect in `COMPONENT_PARITY_WORKFLOW.md`). So the
// report is recomposed only while the landed frame and the controls agree; otherwise it keeps
// describing the frame still on screen.

/**
 * Whether [landedRenderUrl] (the `/render` URL decoded on stage) is the one the controls currently
 * ask for. `null` means nothing has landed and the server's body already describes the page, so
 * false. Compared on path and query only, since one side is relative and the other absolute. Strict
 * otherwise: a false negative skips one recomposition, a false positive is the defect.
 */
export function reportFollowsDisplayedFrame(
    requestedRenderUrl: string,
    landedRenderUrl: string | null,
): boolean {
    if (!landedRenderUrl) return false;
    const frame = (url: string) => {
        const parsed = new URL(url, "http://viewer.invalid");
        return parsed.pathname + parsed.search;
    };
    try {
        return frame(landedRenderUrl) === frame(requestedRenderUrl);
    } catch {
        return false;
    }
}

/**
 * Stages whose `<img>` (and `data-cp-src`) describes what is on screen. An allowlist on purpose:
 * live, Wasm and RC players paint into a canvas or iframe and apply overrides in place, so the
 * frame gate would pass for pixels it knows nothing about, and a denylist would admit the next
 * interactive lane by default. `motion` and `source` are out; `spec` (comparing the snapshot
 * underneath) and `svg` (server-rendered from the same query) are in.
 */
const FRAME_STAGES = new Set(["snapshot", "svg", "spec"]);

/**
 * Whether a report from [stage] may carry a `compose-parity-locator/v1` block. Withheld in
 * interactive lanes, so the issue stays an ordinary one that `buildIssueIndex` skips rather than a
 * row keyed to unseen pixels.
 */
export function reportMayCarryLocator(stage: string): boolean {
    return FRAME_STAGES.has(stage);
}
