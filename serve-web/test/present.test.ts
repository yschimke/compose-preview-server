// The viewer's Present mode: a corner button that takes the stage full screen and back, offered only
// where the Fullscreen API exists, and announcing a resize so the live overlays re-pin.

import "./setup.js";
import assert from "node:assert/strict";
import { resetDom } from "./setup.js";
import { installPresent, presentOffered } from "../src/viewer/present.js";

const tick = () => new Promise((r) => setTimeout(r, 0));

/** A Fullscreen API the test drives: `enabled` is the browser's answer, `element` its top layer. */
function fakeFullscreen(enabled: boolean) {
    const state = { element: null as Element | null, exits: 0 };
    const define = (name: string, get: () => unknown) =>
        Object.defineProperty(document, name, { configurable: true, get });
    define("fullscreenEnabled", () => enabled);
    define("fullscreenElement", () => state.element);
    const change = (element: Element | null) => {
        state.element = element;
        document.dispatchEvent(new Event("fullscreenchange"));
    };
    Object.defineProperty(document, "exitFullscreen", {
        configurable: true,
        value: () => {
            state.exits++;
            change(null);
            return Promise.resolve();
        },
    });
    return { state, change };
}

function stageWithFullscreen(onRequest: (stage: HTMLElement) => void) {
    document.body.innerHTML = `<div class="cp-viewer"><div class="cp-stage"><img id="cp-img"></div></div>`;
    const stage = document.querySelector<HTMLElement>(".cp-stage")!;
    stage.requestFullscreen = () => {
        onRequest(stage);
        return Promise.resolve();
    };
    return stage;
}

describe("the viewer's Present mode", () => {
    beforeEach(resetDom);

    it("is offered only where an element can go full screen", () => {
        const can = { requestFullscreen: () => Promise.resolve() };
        const cannot = {} as Pick<HTMLElement, "requestFullscreen">;
        assert.equal(presentOffered({ fullscreenEnabled: true }, can), true);
        assert.equal(
            presentOffered({ fullscreenEnabled: false }, can),
            false,
            "a policy or iframe that forbids it",
        );
        assert.equal(
            presentOffered({ fullscreenEnabled: true }, cannot),
            false,
            "iPhone Safari: no element fullscreen at all",
        );
    });

    it("adds no button where fullscreen is unavailable", () => {
        fakeFullscreen(false);
        installPresent(stageWithFullscreen(() => {}));
        assert.equal(document.querySelector(".cp-present"), null);
    });

    it("takes the stage full screen and the same button brings it back", async () => {
        const fs = fakeFullscreen(true);
        const stage = stageWithFullscreen((s) => fs.change(s));
        let resizes = 0;
        window.addEventListener("resize", () => resizes++);
        installPresent(stage);

        const button = stage.querySelector<HTMLButtonElement>(".cp-present")!;
        assert.ok(button, "the button sits inside the stage");
        assert.equal(button.getAttribute("aria-pressed"), "false");
        assert.equal(button.getAttribute("aria-label"), "Present full screen");

        button.click();
        await tick();
        assert.equal(fs.state.element, stage, "the stage itself, not the page");
        assert.equal(button.getAttribute("aria-pressed"), "true");
        assert.equal(button.getAttribute("aria-label"), "Exit present mode");
        assert.equal(resizes, 1, "the overlays are told to re-pin");

        button.click();
        await tick();
        assert.equal(fs.state.exits, 1);
        assert.equal(button.getAttribute("aria-pressed"), "false");
        assert.equal(resizes, 2);

        // Esc is the browser's: it leaves fullscreen without the button, and the button follows.
        button.click();
        await tick();
        fs.change(null);
        assert.equal(button.getAttribute("aria-pressed"), "false");
    });
});
