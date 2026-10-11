// "Present": the render stage alone, full screen.
//
// Showing a preview to a room, or on a phone held up to someone, wants the picture and nothing
// else — no bar, no drawer, no export line. The browser already has that gesture: the Fullscreen
// API puts ONE element in the top layer and the rest of the page out of sight. So this is a corner
// button on `.cp-stage` that requests fullscreen for the stage itself, and the stylesheet's
// `.cp-stage:fullscreen` rules do the framing: the stage's own backdrop fills the screen and the
// snapshot is contain-fitted to it.
//
// The button lives INSIDE the stage on purpose. It is then the one control still on screen while
// presenting, so it is also the way out — which a touchscreen needs, having no Esc key.
//
// Every lane keeps working because none of them is touched here. The live canvas and the Wasm
// frame are overlays pinned to the snapshot's box on `resize`; entering fullscreen changes that
// box, and the `resize` re-announced below is what re-pins them, so the stream follows the picture
// up to screen size the same way it follows a window being dragged wider.

/** Whether this browser can put an element full screen. iPhone Safari cannot, and gets no button. */
export function presentOffered(
    doc: Pick<Document, "fullscreenEnabled">,
    stage: Pick<HTMLElement, "requestFullscreen">,
): boolean {
    return (
        !!doc.fullscreenEnabled && typeof stage.requestFullscreen === "function"
    );
}

/** Add the Present button to [stage], where the Fullscreen API exists. */
export function installPresent(stage: HTMLElement | null): void {
    if (!stage || !presentOffered(document, stage)) return;
    const button = document.createElement("button");
    button.type = "button";
    button.className = "cp-present";
    button.textContent = "⛶";
    const sync = () => {
        const on = document.fullscreenElement === stage;
        const label = on ? "Exit present mode" : "Present full screen";
        button.setAttribute("aria-pressed", String(on));
        button.setAttribute("aria-label", label);
        button.title = label;
    };
    sync();
    button.addEventListener("click", () => {
        // A rejected request (a sandboxed frame, a policy, no user activation) leaves the page as
        // it was; there is nothing on screen to undo.
        if (document.fullscreenElement) document.exitFullscreen().catch(sync);
        else stage.requestFullscreen().catch(sync);
    });
    document.addEventListener("fullscreenchange", () => {
        sync();
        // Not every browser fires `resize` for an element going full screen in an already
        // full-screen window; the overlays and the fit cap re-measure on it, so say it once more.
        window.dispatchEvent(new Event("resize"));
    });
    stage.appendChild(button);
}
