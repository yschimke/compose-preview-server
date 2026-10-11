// Behavioural contract for `<cp-spec-compare>`. The rules are pinned in `specViews.test.ts`,
// `specVerdict.test.ts` and `sameOrigin.test.ts`; this covers the lane's lifecycle: `viewer.js`
// finds the global, entering and leaving restores the chip as served, a view switch doesn't re-run
// the comparison, and a comparison in flight at close can't paint over the published verdict.

import "./setup.js";
import assert from "node:assert/strict";
import { flush, resetDom } from "./setup.js";
import "../src/components/SpecCompare.js";

/** Frames whose canvases are never actually drawn — happy-dom has no 2d context worth painting. */
const framesFor = (width = 8, height = 8) => ({
    reference: { width, height } as never,
    candidate: { width, height } as never,
    images: [{}, {}] as [unknown, unknown],
    width,
    height,
    boxes: {
        reference: { x: 0, y: 0, width, height },
        candidate: { x: 0, y: 0, width, height },
    },
});

interface Stub {
    normalise: string[];
    scores: number[];
    settle(): void;
}

/** A `ComposePreviewCompare` whose normalisation resolves only when a test says so. */
function stubCompare(
    result: { percent: number; geometry: number } = {
        percent: 98.4,
        geometry: 0,
    },
    options: { hold?: boolean } = {},
): Stub {
    const held: Array<() => void> = [];
    const stub: Stub = {
        normalise: [],
        scores: [],
        settle: () => {
            for (const release of held.splice(0)) release();
        },
    };
    window.ComposePreviewCompare = {
        scoreImageUrls: async () => result,
        normaliseImageUrls: async (reference: string, actual: string) => {
            stub.normalise.push(`${reference}|${actual}`);
            if (options.hold) await new Promise<void>((r) => held.push(r));
            return framesFor();
        },
        diffCanvases: () => 12,
        scoreImages: async () => {
            stub.scores.push(1);
            return result;
        },
    };
    return stub;
}

async function mount(options: { baseline?: boolean } = {}): Promise<void> {
    const baseline =
        options.baseline === false ? ' data-spec-baseline="0"' : "";
    document.body.innerHTML = `
      <cp-spec-compare></cp-spec-compare>
      <span id="cp-spec-chip" data-spec-match="close"
            data-spec-chip-name="Button" data-spec-chip-label="Button 97.1%"
            data-spec-chip-tip="97.1% match — click to see where"
            data-spec-chip-stale-tip="measured against the default render"
            title="97.1% match — click to see where">Button 97.1%</span>
      <div class="cp-viewer"${baseline}>
        <img id="cp-img" data-cp-src="/render/Button.png?theme=dark">
        <span id="cp-spec-views" hidden>
          <button type="button" data-cp-spec-view="spec" aria-pressed="true">Spec</button>
          <button type="button" data-cp-spec-view="diff" aria-pressed="false">Diff</button>
          <button type="button" data-cp-spec-view="triptych" aria-pressed="false">Triptych</button>
          <button type="button" data-cp-spec-view="slider" aria-pressed="false">Slider</button>
        </span>
        <span id="cp-spec-score" hidden></span>
        <span class="cp-spec-pick" id="cp-spec-pick" hidden></span>
        <span class="cp-spec-pick-live" id="cp-spec-pick-live" aria-live="polite"></span>
        <label><input class="cp-inspect" data-cp-inspect="a11y" type="checkbox">Accessibility</label>
        <label><input class="cp-inspect" data-cp-inspect="typography" type="checkbox">Typography</label>
        <div class="cp-spec-compare" id="cp-spec-compare" hidden data-view="spec"
             data-reference="/reference/Button.png">
          <figure data-cp-spec-panel="reference"><canvas id="cp-spec-reference"
            aria-label="Imported design spec"></canvas><figcaption>Spec</figcaption></figure>
          <figure data-cp-spec-panel="diff"><canvas id="cp-spec-diff"></canvas><figcaption>Diff</figcaption></figure>
          <figure data-cp-spec-panel="actual"><canvas id="cp-spec-actual"></canvas><figcaption>Render</figcaption></figure>
          <div class="cp-spec-wipe">
            <canvas id="cp-spec-wipe-canvas"></canvas>
            <input id="cp-spec-wipe-range" type="range" min="0" max="100" value="50">
          </div>
        </div>
        <script type="application/json" id="cp-spec-annotations">${JSON.stringify(
            {
                reference: [
                    {
                        kind: "typography",
                        bounds: { x: 1, y: 1, width: 4, height: 2 },
                        role: "Label",
                        label: "labelLarge",
                        detail: {
                            token: "m3/label/large",
                            fontFamily: "Roboto",
                            fontWeight: "500",
                            fontSize: "14sp",
                            lineHeight: "20sp",
                        },
                    },
                ],
            },
        )}</script>
        <aside id="cp-controls"></aside>
      </div>`;
    await flush();
}

const lane = () => window.cpSpecCompare!;
const chip = () => document.getElementById("cp-spec-chip") as HTMLElement;
const score = () => document.getElementById("cp-spec-score") as HTMLElement;
const pick = () => document.getElementById("cp-spec-pick") as HTMLElement;
const pickLive = () =>
    document.getElementById("cp-spec-pick-live") as HTMLElement;
const panel = () => document.getElementById("cp-spec-compare") as HTMLElement;
const viewer = () => document.querySelector(".cp-viewer") as HTMLElement;
const press = (view: string) =>
    document
        .querySelector<HTMLElement>(`[data-cp-spec-view="${view}"]`)
        ?.click();

describe("<cp-spec-compare>", () => {
    afterEach(() => {
        delete window.ComposePreviewCompare;
        delete window.cpSpecCompare;
        resetDom();
    });

    it("claims the eyedropper's row as the lane opens", async () => {
        // The readout row is claimed while nothing is read, so the first reading doesn't reflow the
        // header under the cursor.
        stubCompare();
        await mount();
        assert.equal(pick().hidden, true, "no row before the lane is entered");
        lane().open("/render/Button.png");
        assert.equal(pick().hidden, false);
        assert.equal(pick().textContent, "", "reserved, not filled");
    });

    // Freezing a reading must hold what is on screen: a click's coordinates are rounded to whole
    // CSS pixels (unlike `pointermove`), so re-reading at the click could freeze a neighbouring
    // pixel with a different verdict.

    /**
     * A pair whose sides return real pixels. The reference alternates by row against a uniform
     * candidate, so a one-pixel-off reading produces a visibly different line.
     */
    const readablePair = (size = 8) => {
        const buffer = (
            paint: (row: number) => [number, number, number, number],
        ) => {
            const data = new Uint8ClampedArray(size * size * 4);
            for (let y = 0; y < size; y++)
                for (let x = 0; x < size; x++)
                    data.set(paint(y), (y * size + x) * 4);
            return {
                width: size,
                height: size,
                getContext: () => ({ getImageData: () => ({ data }) }),
            } as never;
        };
        return {
            reference: buffer((row) =>
                row === 2 ? [255, 255, 255, 255] : [0, 0, 0, 255],
            ),
            candidate: buffer(() => [0, 0, 0, 255]),
            images: [{}, {}] as [unknown, unknown],
            width: size,
            height: size,
            boxes: {
                reference: { x: 0, y: 0, width: size, height: size },
                candidate: { x: 0, y: 0, width: size, height: size },
            },
        };
    };

    /**
     * The lane open on a readable pair, with the render panel at half the pair's height, so the
     * click's rounding lands on a different row (the ordinary, scaled-to-fit case).
     */
    async function openReadableLane(): Promise<HTMLCanvasElement> {
        const pair = readablePair();
        window.ComposePreviewCompare = {
            scoreImageUrls: async () => ({ percent: 98.4, geometry: 0 }),
            normaliseImageUrls: async () => pair as never,
            diffCanvases: () => 12,
            scoreImages: async () => ({ percent: 98.4, geometry: 0 }),
        };
        await mount();
        lane().open("/render/Button.png");
        for (let i = 0; i < 5; i++) await flush();
        const actual = document.getElementById(
            "cp-spec-actual",
        ) as HTMLCanvasElement;
        actual.getBoundingClientRect = () =>
            ({
                left: 0,
                top: 0,
                right: 8,
                bottom: 4,
                width: 8,
                height: 4,
            }) as DOMRect;
        return actual;
    }

    /** A pointer move over a panel, at the sub-pixel coordinates a real one carries. */
    const movePointer = (
        panel: HTMLElement,
        x: number,
        y: number,
        options: { shift?: boolean } = {},
    ) =>
        panel.dispatchEvent(
            new MouseEvent("pointermove", {
                clientX: x,
                clientY: y,
                shiftKey: options.shift ?? false,
                bubbles: true,
            }),
        );

    /** A click, with the whole-CSS-pixel coordinates the browser rounds it to. */
    const clickPanel = (panel: HTMLElement, x: number, y: number) =>
        panel.dispatchEvent(
            new MouseEvent("click", {
                clientX: Math.round(x),
                clientY: Math.round(y),
                bubbles: true,
            }),
        );

    it("reads through the drawn frame, not the panel's box", async () => {
        // A panel letterboxed by `object-fit: contain`: an 8x8 pair centred in a 16x8 box with 4px
        // bars. Client x 5.5 is frame x 1; mapping through the element box would read x 2.
        const actual = await openReadableLane();
        actual.getBoundingClientRect = () =>
            ({
                left: 0,
                top: 0,
                right: 16,
                bottom: 8,
                width: 16,
                height: 8,
            }) as DOMRect;

        movePointer(actual, 5.5, 4.5);
        assert.match(
            pick().textContent ?? "",
            /^1,4 /,
            "the point is read where the frame actually draws it",
        );
    });

    it("reads nothing over the letterbox beside a frame", async () => {
        // The letterbox bars aren't the picture, so the row stays empty there.
        const actual = await openReadableLane();
        actual.getBoundingClientRect = () =>
            ({
                left: 0,
                top: 0,
                right: 16,
                bottom: 8,
                width: 16,
                height: 8,
            }) as DOMRect;

        movePointer(actual, 5.5, 4.5);
        assert.notEqual(pick().textContent, "", "on the frame, a reading");
        movePointer(actual, 1.5, 4.5);
        assert.equal(pick().textContent, "", "on the bar beside it, nothing");
    });

    it("has nothing to freeze on the letterbox either", async () => {
        const actual = await openReadableLane();
        actual.getBoundingClientRect = () =>
            ({
                left: 0,
                top: 0,
                right: 16,
                bottom: 8,
                width: 16,
                height: 8,
            }) as DOMRect;
        movePointer(actual, 1.5, 4.5);
        clickPanel(actual, 1.5, 4.5);
        assert.equal(pick().classList.contains("cp-spec-pick--frozen"), false);
    });

    it("freezes the reading on screen, not the click's own pixel", async () => {
        const actual = await openReadableLane();
        // y 1.9 at half scale is row 3 (the matching row); the click carries y 2, which the latched
        // reading must not use.
        movePointer(actual, 4.4, 1.9);
        const onScreen = pick().textContent;
        assert.match(onScreen ?? "", /^4,3 /, "the pointer is on row 3");

        clickPanel(actual, 4.4, 1.9);
        assert.equal(pick().classList.contains("cp-spec-pick--frozen"), true);
        assert.equal(
            pick().textContent,
            onScreen,
            "the frozen line is the line that was being read",
        );
        assert.equal(pickLive().textContent, "Frozen reading. " + onScreen);
    });

    it("ignores the pointer while frozen", async () => {
        const actual = await openReadableLane();
        movePointer(actual, 4.4, 1.9);
        const held = pick().textContent;
        clickPanel(actual, 4.4, 1.9);

        movePointer(actual, 4.4, 1.1);
        assert.equal(pick().textContent, held, "the latch holds the row still");
    });

    it("hands the row back to the pointer when a second click releases it", async () => {
        // Releasing by click must behave like Escape, not leave the latched line up unstyled.
        const actual = await openReadableLane();
        movePointer(actual, 4.4, 1.9);
        const held = pick().textContent;
        clickPanel(actual, 4.4, 1.9);
        movePointer(actual, 4.4, 1.1);

        clickPanel(actual, 4.4, 1.1);
        assert.equal(pick().classList.contains("cp-spec-pick--frozen"), false);
        assert.equal(pickLive().textContent, "");
        assert.notEqual(
            pick().textContent,
            held,
            "not the line the latch held",
        );
        assert.match(
            pick().textContent ?? "",
            /^4,2 /,
            "the pixel the pointer is actually on",
        );
    });

    it("hands the row back to the pointer when Escape releases it", async () => {
        const actual = await openReadableLane();
        movePointer(actual, 4.4, 1.9);
        clickPanel(actual, 4.4, 1.9);
        movePointer(actual, 4.4, 1.1);

        document.dispatchEvent(
            new KeyboardEvent("keydown", { key: "Escape", bubbles: true }),
        );
        assert.equal(pick().classList.contains("cp-spec-pick--frozen"), false);
        assert.match(pick().textContent ?? "", /^4,2 /);
    });

    it("empties the row when a release finds the pointer gone", async () => {
        // The latch survives the pointer leaving the comparison — that is what makes a reading
        // readable and copyable. Releasing then has nothing under the cursor to describe.
        const actual = await openReadableLane();
        movePointer(actual, 4.4, 1.9);
        clickPanel(actual, 4.4, 1.9);
        panel().dispatchEvent(
            new MouseEvent("pointerleave", { bubbles: false }),
        );
        assert.notEqual(
            pick().textContent,
            "",
            "the latch holds through a leave",
        );

        document.dispatchEvent(
            new KeyboardEvent("keydown", { key: "Escape", bubbles: true }),
        );
        assert.equal(pick().textContent, "");
        assert.equal(pick().hidden, false, "the row itself is still reserved");
    });

    it("has nothing to freeze in the gutter between two panels", async () => {
        const actual = await openReadableLane();
        movePointer(actual, 4.4, 1.9);
        panel().dispatchEvent(
            new MouseEvent("pointermove", {
                clientX: 4.4,
                clientY: 1.9,
                bubbles: true,
            }),
        );
        assert.equal(pick().textContent, "", "no panel under the pointer");

        clickPanel(panel(), 4.4, 1.9);
        assert.equal(
            pick().classList.contains("cp-spec-pick--frozen"),
            false,
            "an empty row does not latch",
        );
    });

    it("lets a frozen reading go when the view changes", async () => {
        // A reading must not survive a view switch (it may describe a hidden surface, and in Slider
        // only Escape could release it).
        stubCompare();
        await mount();
        lane().open("/render/Button.png");
        for (let i = 0; i < 5; i++) await flush();
        pick().textContent =
            "145,121 · Figma #332e3c · Render #332e3c · identical";
        pick().hidden = false;
        pick().classList.add("cp-spec-pick--frozen");
        pickLive().textContent = "Frozen reading. 145,121 · …";

        press("slider");
        await flush();
        assert.equal(pick().textContent, "");
        assert.equal(pick().classList.contains("cp-spec-pick--frozen"), false);
        assert.equal(pickLive().textContent, "");
    });

    it("lets a frozen reading go when Back changes the view", async () => {
        // Back and Forward reach the view the buttons reach, by another door: `hydrate`. A reading
        // restored alongside a view it was not taken in describes a panel that may not be there.
        stubCompare();
        await mount();
        lane().open("/render/Button.png");
        for (let i = 0; i < 5; i++) await flush();
        pick().textContent =
            "145,121 · Figma #332e3c · Render #332e3c · identical";
        pick().hidden = false;
        pick().classList.add("cp-spec-pick--frozen");
        pickLive().textContent = "Frozen reading. 145,121 · …";

        lane().hydrate("slider");
        await flush();
        assert.equal(pick().textContent, "");
        assert.equal(pick().classList.contains("cp-spec-pick--frozen"), false);
        assert.equal(pickLive().textContent, "");
    });

    it("takes a frozen reading away when the lane closes", async () => {
        // `cp-spec-lane` persists off the lane, so leaving must clear the reading and the latch.
        stubCompare();
        await mount();
        lane().open("/render/Button.png");
        pick().textContent = "146,122 · Figma #494451 · Render #332e3c · Δ 22";
        pick().hidden = false;
        pick().classList.add("cp-spec-pick--frozen");
        pickLive().textContent = "Frozen reading. 146,122 · Δ 22";

        lane().close();
        assert.equal(pick().textContent, "");
        assert.equal(pick().hidden, true);
        assert.equal(pick().classList.contains("cp-spec-pick--frozen"), false);
        assert.equal(pickLive().textContent, "");
    });

    it("hands viewer.js the global it calls", async () => {
        await mount();
        assert.equal(typeof lane().open, "function");
        assert.equal(lane().view(), "triptych");
    });

    it("opens comparing, without waiting to be asked", async () => {
        // The lane opens on the triptych (spec, diff and render side by side).
        const stub = stubCompare({ percent: 98.44, geometry: 0 });
        await mount();
        lane().open("/render/Button.png");
        for (let i = 0; i < 5; i++) await flush();
        assert.equal(viewer().getAttribute("data-spec-view"), "triptych");
        assert.equal(panel().hidden, false, "the comparison panel is up");
        assert.equal(score().hidden, false);
        assert.equal(score().textContent, "98.4% match · 18.75% pixels differ");
        assert.equal(stub.normalise.length, 1);
    });

    it("leaves the stage alone on the plain Spec view", async () => {
        // Spec paints nothing of its own: the stage is just viewer.js's raster `<img>`.
        stubCompare();
        await mount();
        lane().open("/render/Button.png");
        press("spec");
        await flush();
        assert.equal(viewer().getAttribute("data-spec-view"), "spec");
        assert.equal(panel().hidden, true, "the comparison panel stays away");
        assert.equal(score().hidden, true);
    });

    it("takes the stage for a comparison view and scores the pair", async () => {
        const stub = stubCompare({ percent: 98.44, geometry: 0 });
        await mount();
        lane().open("/render/Button.png");
        press("triptych");
        for (let i = 0; i < 5; i++) await flush();
        assert.equal(viewer().getAttribute("data-spec-view"), "triptych");
        assert.equal(panel().hidden, false);
        assert.equal(score().textContent, "98.4% match · 18.75% pixels differ");
        assert.deepEqual(stub.normalise, [
            "https://preview.example/reference/Button.png|https://preview.example/render/Button.png",
        ]);
    });

    it("shows only changed typography beside Diff and highlights it", async () => {
        stubCompare();
        const urls: string[] = [];
        const previousFetch = globalThis.fetch;
        globalThis.fetch = (async (url: string) => {
            urls.push(String(url));
            return {
                ok: true,
                json: async () => ({
                    annotations: [
                        {
                            kind: "typography",
                            bounds: { x: 1, y: 1, width: 4, height: 2 },
                            role: "Label",
                            label: "labelLarge",
                            detail: {
                                token: "m3/label/large",
                                fontFamily: "Roboto",
                                fontWeight: "600",
                                fontSize: "14sp",
                                lineHeight: "20sp",
                            },
                        },
                    ],
                }),
            };
        }) as unknown as typeof fetch;
        await mount();
        document.querySelector<HTMLInputElement>(
            '[data-cp-inspect="typography"]',
        )!.checked = true;
        lane().open("blob:https://preview.example/current-snapshot");
        press("diff");
        for (let i = 0; i < 8; i++) await flush();
        const legend = document.getElementById("cp-spec-typography-legend")!;
        assert.equal(legend.hidden, false);
        assert.deepEqual(
            Array.from(legend.querySelectorAll(".cp-spec-type-field")).map(
                (node) => node.textContent,
            ),
            ["Weight: 500 → 600"],
            "matching token, family and size stay out of the legend",
        );
        assert.equal(
            legend.querySelectorAll(".cp-typography-changed").length,
            1,
        );
        assert.equal(
            document.querySelectorAll(
                '[data-cp-spec-panel="diff"] .cp-spec-type-box',
            ).length,
            1,
            "the changed Compose usage is marked over the diff",
        );
        assert.deepEqual(urls, ["/render/Button.annotations?theme=dark"]);

        document
            .getElementById("cp-img")!
            .setAttribute("data-cp-src", "/render/Button.png?theme=light");
        window.dispatchEvent(new CustomEvent("cp-inspect-change"));
        for (let i = 0; i < 5; i++) await flush();
        assert.deepEqual(
            urls,
            ["/render/Button.annotations?theme=dark"],
            "the legend remains tied to the render already copied into the canvases",
        );
        globalThis.fetch = previousFetch;
    });

    it("compares accessibility stops from both parallel renders", async () => {
        stubCompare();
        const previousFetch = globalThis.fetch;
        globalThis.fetch = (async (url: string) => ({
            ok: true,
            json: async () => ({
                nodes: [
                    {
                        label: String(url).includes("reference")
                            ? "Save"
                            : "Save changes",
                        role: "Button",
                        boundsInScreen: "1,1,5,3",
                        states: ["clickable"],
                    },
                ],
                findings: [],
                touchTargets: [],
            }),
        })) as unknown as typeof fetch;
        try {
            await mount();
            lane().open("/render/Button.png");
            press("diff");
            document.querySelector<HTMLInputElement>(
                '[data-cp-inspect="a11y"]',
            )!.checked = true;
            window.dispatchEvent(new CustomEvent("cp-inspect-change"));
            for (let i = 0; i < 8; i++) await flush();

            const legend = document.getElementById("cp-spec-a11y-legend")!;
            assert.equal(legend.hidden, false);
            assert.match(legend.textContent ?? "", /Accessibility differences/);
            assert.match(legend.textContent ?? "", /Save → Save changes/);
        } finally {
            globalThis.fetch = previousFetch;
        }
    });

    it("puts the live verdict on the chip, and the published one back on the way out", async () => {
        // The chip's published score must be restored on leaving, not left showing a knob-bent live
        // number.
        stubCompare({ percent: 99.9, geometry: 0 });
        await mount();
        lane().open("/render/Button.png?knob=1");
        press("diff");
        for (let i = 0; i < 5; i++) await flush();
        assert.equal(chip().textContent, "Button 99.9%");
        assert.equal(chip().getAttribute("data-spec-match"), "match");

        lane().close();
        assert.equal(
            chip().textContent,
            "Button 97.1%",
            "the published label returns",
        );
        assert.equal(
            chip().getAttribute("data-spec-match"),
            "close",
            "and its published band",
        );
    });

    it("drops the published verdict once the render leaves the baseline", async () => {
        // Off the baseline (a theme picked), only the render moves since the spec isn't re-exported
        // per theme, so the published number no longer describes the stage.
        await mount();
        lane().baseline(false);
        assert.equal(chip().textContent, "Button", "just the provider label");
        assert.equal(
            chip().getAttribute("data-spec-match"),
            null,
            "and no band — a colour is a verdict too",
        );
        assert.equal(chip().title, "measured against the default render");

        lane().baseline(true);
        assert.equal(chip().textContent, "Button 97.1%");
        assert.equal(chip().getAttribute("data-spec-match"), "close");
        assert.equal(chip().title, "97.1% match — click to see where");
    });

    it("never paints a published verdict the served page already knows is stale", async () => {
        // viewer.js has no guaranteed ordering against this element, so the baseline state is read
        // off the stage at install.
        await mount({ baseline: false });
        assert.equal(chip().textContent, "Button");
        assert.equal(chip().getAttribute("data-spec-match"), null);
    });

    it("publishes no match score at all off the baseline", async () => {
        // Off the baseline, neither the published nor a live number is shown: no spec exists for
        // the themed frame, so measuring grades the theme (e.g. a token-colour change scoring
        // "90.5% match" on pixel-correct geometry).
        const stub = stubCompare({ percent: 88.9, geometry: 0 });
        await mount({ baseline: false });
        lane().open("/render/Button.png?themeProvider=HighContrast");
        press("diff");
        for (let i = 0; i < 5; i++) await flush();

        assert.deepEqual(stub.scores, [], "the pair is never scored");
        assert.equal(chip().textContent, "Button", "just the provider label");
        assert.equal(chip().getAttribute("data-spec-match"), null);
        assert.equal(chip().title, "measured against the default render");
        // The changed-pixel count stays (it is true and is the panels' caption), but not as a
        // verdict.
        assert.equal(
            score().textContent,
            "18.75% pixels differ · the imported spec is baseline-only, " +
                "so this is not a match score — clear the overrides to compare",
        );
    });

    it("re-decides the readout when the render returns to the baseline", async () => {
        // The baseline flag can flip while the lane is open, and the cached-frames path skips the
        // readout, so it must be re-decided.
        const stub = stubCompare({ percent: 98.44, geometry: 0 });
        await mount({ baseline: false });
        lane().open("/render/Button.png");
        press("triptych");
        for (let i = 0; i < 5; i++) await flush();
        assert.deepEqual(stub.scores, []);

        lane().baseline(true);
        for (let i = 0; i < 5; i++) await flush();
        assert.equal(score().textContent, "98.4% match · 18.75% pixels differ");
        assert.equal(chip().textContent, "Button 98.4%");
    });

    it("does not re-compare when only the view changes", async () => {
        // One normalisation feeds every view, so switching is free; re-running would re-request a
        // no-store render that could differ.
        const stub = stubCompare();
        await mount();
        lane().open("/render/Button.png");
        for (let i = 0; i < 5; i++) await flush();
        press("diff");
        press("triptych");
        press("slider");
        for (let i = 0; i < 5; i++) await flush();
        assert.equal(stub.normalise.length, 1);
    });

    it("re-compares when the render changed underneath", async () => {
        const stub = stubCompare();
        await mount();
        lane().open("/render/Button.png");
        for (let i = 0; i < 5; i++) await flush();
        lane().close();
        lane().open("/render/Button.png?theme=dark");
        for (let i = 0; i < 5; i++) await flush();
        assert.equal(
            stub.normalise.length,
            2,
            "a new pair is a new comparison",
        );
    });

    it("abandons a comparison the lane no longer wants", async () => {
        // `close()` restores the published verdict; a late score must not paint over it.
        const stub = stubCompare({ percent: 42, geometry: 0 }, { hold: true });
        await mount();
        lane().open("/render/Button.png?knob=1");
        press("diff");
        await flush();
        lane().close();
        stub.settle();
        for (let i = 0; i < 5; i++) await flush();
        assert.equal(
            chip().textContent,
            "Button 97.1%",
            "the published label survives",
        );
        assert.equal(chip().getAttribute("data-spec-match"), "close");
    });

    it("says so rather than showing nothing when the pair cannot be compared", async () => {
        // No `format-compare.js` on this page: the lane has views but no instruments.
        await mount();
        lane().open("/render/Button.png");
        press("diff");
        for (let i = 0; i < 3; i++) await flush();
        assert.equal(score().textContent, "Comparison unavailable");
    });

    it("refuses a reference that is not ours", async () => {
        const stub = stubCompare();
        await mount();
        panel().setAttribute("data-reference", "https://evil.example/x.png");
        // Re-mount so the element re-reads the attribute the way a served page would.
        document.querySelector("cp-spec-compare")!.remove();
        document.body.insertBefore(
            document.createElement("cp-spec-compare"),
            document.body.firstChild,
        );
        await flush();
        lane().open("/render/Button.png");
        press("diff");
        for (let i = 0; i < 3; i++) await flush();
        assert.equal(
            stub.normalise.length,
            0,
            "nothing cross-origin reaches a canvas",
        );
        assert.equal(score().textContent, "Comparison unavailable");
    });

    it("marks the pressed view, and only that one", async () => {
        stubCompare();
        await mount();
        lane().open("/render/Button.png");
        press("slider");
        await flush();
        const pressed = Array.from(
            document.querySelectorAll("[data-cp-spec-view]"),
        ).map((b) => b.getAttribute("aria-pressed"));
        assert.deepEqual(pressed, ["false", "false", "false", "true"]);
    });

    it("honours a view the URL arrived with over one the chip asks for", async () => {
        // The bug this prevents: a shared `?specView=triptych` link, or a Back into one, being
        // overwritten by the chip that happens to sit on the same page.
        stubCompare();
        await mount();
        lane().hydrate("triptych");
        lane().prefer("slider");
        lane().open("/render/Button.png");
        await flush();
        assert.equal(lane().view(), "triptych");
    });

    it("opens on the chip's view when nobody else has spoken", async () => {
        stubCompare();
        await mount();
        lane().prefer("slider");
        lane().open("/render/Button.png");
        await flush();
        assert.equal(lane().view(), "slider");
        assert.equal(viewer().getAttribute("data-spec-view"), "slider");
    });

    // The source picker belongs to `viewer.js`, which names the winner on `open()`; this element
    // must actually move the pair to that source.

    const sibling = {
        reference: "https://preview.example/wear-m3/render/AppCard.png",
        label: "wear-m3-catalog",
        spec: false,
    };
    const kit = {
        reference: "https://preview.example/reference/Button.png",
        label: "Figma",
        spec: true,
    };
    const caption = () =>
        document.querySelector('[data-cp-spec-panel="reference"] figcaption')
            ?.textContent;

    it("compares against the source it was opened with, not the served reference", async () => {
        const stub = stubCompare({ percent: 62.5, geometry: 0 });
        await mount();
        lane().open("/render/AppCard.png", sibling);
        for (let i = 0; i < 5; i++) await flush();
        assert.deepEqual(stub.normalise, [
            `${sibling.reference}|https://preview.example/render/AppCard.png`,
        ]);
    });

    it("re-normalises the pair when the picker switches source", async () => {
        // The bug: the reference was latched at install off `data-reference`, so a switch
        // re-pointed the hidden raster and left every canvas painting the first pair.
        const stub = stubCompare({ percent: 62.5, geometry: 0 });
        await mount();
        lane().open("/render/AppCard.png", kit);
        for (let i = 0; i < 5; i++) await flush();
        lane().open("/render/AppCard.png", sibling);
        for (let i = 0; i < 5; i++) await flush();
        assert.deepEqual(stub.normalise, [
            `${kit.reference}|https://preview.example/render/AppCard.png`,
            `${sibling.reference}|https://preview.example/render/AppCard.png`,
        ]);
    });

    it("names the sibling on the panel it is showing, and gives the caption back", async () => {
        stubCompare();
        await mount();
        lane().open("/render/AppCard.png", sibling);
        for (let i = 0; i < 5; i++) await flush();
        assert.equal(caption(), "wear-m3-catalog");
        assert.equal(
            document
                .getElementById("cp-spec-reference")!
                .getAttribute("aria-label"),
            "wear-m3-catalog's own render of this component",
        );

        lane().open("/render/AppCard.png", kit);
        for (let i = 0; i < 5; i++) await flush();
        assert.equal(caption(), "Spec", "the served caption returns");
        assert.equal(
            document
                .getElementById("cp-spec-reference")!
                .getAttribute("aria-label"),
            "Imported design spec",
        );
    });

    it("keeps the design-spec chip out of a sibling comparison", async () => {
        // A sibling pair is scored (a real pixel comparison), but not shown on the chip named for
        // the kit's provider.
        const stub = stubCompare({ percent: 62.5, geometry: 0 });
        await mount();
        lane().open("/render/AppCard.png", sibling);
        press("diff");
        for (let i = 0; i < 5; i++) await flush();
        assert.equal(score().textContent, "62.5% match · 18.75% pixels differ");
        assert.equal(stub.scores.length, 1, "the pair is still measured");
        assert.equal(chip().textContent, "Button", "just the provider label");
        assert.equal(chip().getAttribute("data-spec-match"), null);

        lane().close();
        assert.equal(chip().textContent, "Button 97.1%");
        assert.equal(caption(), "Spec");
    });

    it("uses the sibling's typography rather than the imported kit's", async () => {
        // The peer source has its own annotations endpoint; the same fixture for both proves the
        // sibling pair is considered.
        stubCompare();
        globalThis.fetch = (async () => ({
            ok: true,
            json: async () => ({
                annotations: [
                    {
                        kind: "typography",
                        bounds: { x: 1, y: 1, width: 4, height: 2 },
                        role: "Label",
                        label: "labelLarge",
                        detail: { token: "m3/label/large", fontWeight: "600" },
                    },
                ],
            }),
        })) as unknown as typeof fetch;
        await mount();
        document.querySelector<HTMLInputElement>(
            '[data-cp-inspect="typography"]',
        )!.checked = true;
        lane().open("/render/AppCard.png", sibling);
        press("diff");
        for (let i = 0; i < 8; i++) await flush();
        assert.equal(
            document.querySelectorAll(
                '[data-cp-spec-panel="diff"] .cp-spec-type-box',
            ).length,
            1,
        );
    });

    it("says which side is baseline-only when a sibling is off the baseline", async () => {
        const stub = stubCompare({ percent: 88.9, geometry: 0 });
        await mount({ baseline: false });
        lane().open("/render/AppCard.png?themeProvider=HighContrast", sibling);
        press("diff");
        for (let i = 0; i < 5; i++) await flush();
        assert.deepEqual(stub.scores, []);
        assert.equal(
            score().textContent,
            "18.75% pixels differ · wear-m3-catalog's render is baseline-only, " +
                "so this is not a match score — clear the overrides to compare",
        );
    });

    // The loupe and content-aware alignment: magnify the neighbourhood, and with alignment on,
    // compare the element rather than the coordinate.

    const loupeControls = () =>
        document.getElementById("cp-spec-loupe-controls") as HTMLElement;
    const loupe = () =>
        document.getElementById("cp-spec-loupe") as HTMLElement | null;
    const pressLoupe = (name: string) =>
        document
            .querySelector<HTMLElement>(`[data-cp-spec-loupe="${name}"]`)
            ?.click();

    it("puts the loupe's toggles beside the views, and hides them with them", async () => {
        stubCompare();
        await mount();
        assert.equal(
            loupeControls().hidden,
            true,
            "no toggles before the lane is entered",
        );
        lane().open("/render/Button.png");
        for (let i = 0; i < 5; i++) await flush();
        assert.equal(loupeControls().hidden, false);
        assert.equal(
            loupeControls().previousElementSibling?.id,
            "cp-spec-views",
            "beside the views, because it is the same kind of choice one question over",
        );

        // The plain Spec view has no panels, so there is nothing to magnify and nothing to align.
        press("spec");
        await flush();
        assert.equal(loupeControls().hidden, true);

        press("triptych");
        await flush();
        lane().close();
        assert.equal(loupeControls().hidden, true);
    });

    it("draws no patch until one is asked for", async () => {
        // A patch that follows the cursor covers what it magnifies, so hovering alone must not
        // raise one. The READING is the picker's and not the loupe's, so it is there as ever.
        const actual = await openReadableLane();
        assert.equal(
            document
                .querySelector('[data-cp-spec-loupe="loupe"]')
                ?.getAttribute("aria-pressed"),
            "false",
            "the toggle rests unpressed",
        );
        movePointer(actual, 4.4, 1.9);
        assert.equal(loupe()?.hidden ?? true, true);
        assert.notEqual(pick().textContent, "");
    });

    it("magnifies while the loupe is latched on, and drops the patch when the pointer leaves", async () => {
        const actual = await openReadableLane();
        pressLoupe("loupe");
        movePointer(actual, 4.4, 1.9);
        assert.equal(loupe()?.hidden, false, "a patch, for a reading");

        panel().dispatchEvent(
            new MouseEvent("pointerleave", { bubbles: false }),
        );
        assert.equal(
            loupe()?.hidden,
            true,
            "nothing under the pointer, nothing to magnify",
        );
    });

    it("raises the patch for as long as Shift is held, without spending the toggle", async () => {
        // The common case is a glance. Held, the patch lasts exactly as long as the question, and
        // the toggle is still resting unpressed afterwards.
        const actual = await openReadableLane();
        movePointer(actual, 4.4, 1.9, { shift: true });
        assert.equal(loupe()?.hidden, false);

        document.dispatchEvent(
            new KeyboardEvent("keyup", { key: "Shift", bubbles: true }),
        );
        assert.equal(loupe()?.hidden, true, "let go, and it is gone");
        assert.equal(
            document
                .querySelector('[data-cp-spec-loupe="loupe"]')
                ?.getAttribute("aria-pressed"),
            "false",
            "holding it never pressed the toggle",
        );
    });

    it("raises the patch on a reading already on screen when Shift goes down", async () => {
        // A modifier is not delivered to whatever the cursor is over, and the pointer need not
        // move to ask the question — so Shift alone, on the document, has to be enough.
        const actual = await openReadableLane();
        movePointer(actual, 4.4, 1.9);
        assert.equal(loupe()?.hidden ?? true, true);

        document.dispatchEvent(
            new KeyboardEvent("keydown", { key: "Shift", bubbles: true }),
        );
        assert.equal(loupe()?.hidden, false);
    });

    it("lets the modifier go when the page loses focus", async () => {
        // Shift released while unfocused never arrives, so blur must drop the held modifier.
        const actual = await openReadableLane();
        movePointer(actual, 4.4, 1.9, { shift: true });
        assert.equal(loupe()?.hidden, false);

        window.dispatchEvent(new Event("blur"));
        movePointer(actual, 4.4, 1.9);
        assert.equal(loupe()?.hidden, true);
    });

    it("takes the patch away with the pair it was a picture of", async () => {
        const actual = await openReadableLane();
        pressLoupe("loupe");
        movePointer(actual, 4.4, 1.9);
        assert.equal(loupe()?.hidden, false);
        lane().close();
        assert.equal(loupe()?.hidden, true);
    });

    it("keeps a frozen reading when a toggle is pressed from outside the comparison", async () => {
        // Pressing a toggle sends `pointerleave` first; a frozen reading must re-read at its
        // latched point, not the emptied live one.
        const actual = await openReadableLane();
        movePointer(actual, 4.4, 1.9);
        clickPanel(actual, 4.4, 1.9);
        const frozen = pick().textContent;
        panel().dispatchEvent(
            new MouseEvent("pointerleave", { bubbles: false }),
        );

        pressLoupe("align");
        assert.equal(pick().textContent, frozen, "the latch still holds it");
        assert.equal(pick().classList.contains("cp-spec-pick--frozen"), true);
        assert.equal(pickLive().textContent, "Frozen reading. " + frozen);

        // The other half of the same rule: while the latch is shut, a re-read is of the LATCHED
        // point, not of wherever the pointer has since moved to on the panel.
        movePointer(actual, 4.4, 1.1);
        pressLoupe("align");
        assert.equal(
            pick().textContent,
            frozen,
            "not the pixel the pointer has wandered onto",
        );

        // …and releasing still hands the row back to the LIVE pointer, wherever it now is.
        document.dispatchEvent(
            new KeyboardEvent("keydown", { key: "Escape", bubbles: true }),
        );
        assert.equal(pick().classList.contains("cp-spec-pick--frozen"), false);
        assert.notEqual(pick().textContent, frozen);
        assert.match(pick().textContent ?? "", /^4,2 /);
    });

    /**
     * The lane open on a pair whose "label" is one row lower in the render, with layout annotations
     * on both sides saying so: at the same coordinate the row is white on one side and black on the
     * other.
     */
    async function openShiftedLane(): Promise<HTMLCanvasElement> {
        const size = 8;
        const row = (data: Uint8ClampedArray, y: number) => {
            for (let x = 0; x < size; x++)
                data.set([255, 255, 255, 255], (y * size + x) * 4);
        };
        const buffer = (ink: number) => {
            const data = new Uint8ClampedArray(size * size * 4);
            for (let i = 0; i < size * size; i++)
                data.set([0, 0, 0, 255], i * 4);
            row(data, ink);
            return {
                width: size,
                height: size,
                getContext: () => ({ getImageData: () => ({ data }) }),
            } as never;
        };
        const pair = {
            reference: buffer(2),
            candidate: buffer(5),
            images: [{}, {}] as [unknown, unknown],
            width: size,
            height: size,
            boxes: {
                reference: { x: 0, y: 0, width: size, height: size },
                candidate: { x: 0, y: 0, width: size, height: size },
            },
        };
        window.ComposePreviewCompare = {
            scoreImageUrls: async () => ({ percent: 60, geometry: 0 }),
            normaliseImageUrls: async () => pair as never,
            diffCanvases: () => 8,
            scoreImages: async () => ({ percent: 60, geometry: 0 }),
        };
        await mount();
        const annotations = document.getElementById("cp-spec-annotations")!;
        annotations.textContent = JSON.stringify({
            reference: [
                {
                    kind: "layout",
                    bounds: { x: 0, y: 0, width: 8, height: 8 },
                    role: "card",
                },
                {
                    kind: "layout",
                    bounds: { x: 0, y: 2, width: 8, height: 1 },
                    role: "label",
                },
            ],
        });
        lane().open("/render/Button.png");
        for (let i = 0; i < 5; i++) await flush();
        const actual = document.getElementById(
            "cp-spec-actual",
        ) as HTMLCanvasElement;
        // 1:1, so a client coordinate is a normalised one and the arithmetic stays readable.
        actual.getBoundingClientRect = () =>
            ({
                left: 0,
                top: 0,
                right: 8,
                bottom: 8,
                width: 8,
                height: 8,
            }) as DOMRect;
        return actual;
    }

    /** The render's annotations, as the annotations endpoint would answer them. */
    function stubAnnotations(labelY: number): () => void {
        const original = globalThis.fetch;
        globalThis.fetch = (async () => ({
            ok: true,
            json: async () => ({
                annotations: [
                    {
                        kind: "layout",
                        bounds: { x: 0, y: 0, width: 8, height: 8 },
                        role: "card",
                    },
                    {
                        kind: "layout",
                        bounds: { x: 0, y: labelY, width: 8, height: 1 },
                        role: "label",
                    },
                ],
            }),
        })) as unknown as typeof fetch;
        return () => {
            globalThis.fetch = original;
        };
    }

    it("reads the render inside its own matched box once alignment is on", async () => {
        const restore = stubAnnotations(5);
        try {
            const actual = await openShiftedLane();
            movePointer(actual, 4.5, 2.5);
            assert.match(
                pick().textContent ?? "",
                /Δ 255$/,
                "at the same coordinate the shifted label is ink against background",
            );

            pressLoupe("align");
            for (let i = 0; i < 6; i++) await flush();
            assert.equal(
                pick().textContent,
                "4,2 · Spec #ffffff · Render #ffffff · aligned +0,+3 · identical",
                "inside the matched box it is the same ink, and the line says how far it moved",
            );
        } finally {
            restore();
        }
    });

    it("says so rather than guessing where no matched box covers the point", async () => {
        // A silent fall back to a zero offset would be indistinguishable from a matched box that
        // had not moved — the one case the option exists to tell apart.
        const restore = stubAnnotations(5);
        try {
            const actual = await openShiftedLane();
            const annotations = document.getElementById("cp-spec-annotations")!;
            annotations.textContent = JSON.stringify({
                reference: [
                    {
                        kind: "layout",
                        bounds: { x: 0, y: 6, width: 2, height: 2 },
                        role: "chip",
                    },
                ],
            });
            pressLoupe("align");
            pressLoupe("loupe");
            for (let i = 0; i < 6; i++) await flush();
            movePointer(actual, 4.5, 2.5);
            assert.match(pick().textContent ?? "", /Δ 255$/);
            assert.doesNotMatch(pick().textContent ?? "", /aligned/);
            assert.equal(
                loupe()?.querySelector("[data-cp-loupe-note]")?.textContent,
                "no matched box here",
            );
        } finally {
            restore();
        }
    });

    it("drops the matched boxes with the pair they describe", async () => {
        // Boxes must not carry into the next pair.
        const restore = stubAnnotations(5);
        try {
            const actual = await openShiftedLane();
            pressLoupe("align");
            for (let i = 0; i < 6; i++) await flush();
            movePointer(actual, 4.5, 2.5);
            assert.match(pick().textContent ?? "", /aligned \+0,\+3/);

            lane().close();
            lane().open("/render/Button.png?theme=dark");
            movePointer(actual, 4.5, 2.5);
            assert.doesNotMatch(
                pick().textContent ?? "",
                /aligned/,
                "nothing is aligned until the new pair's own boxes are matched",
            );
        } finally {
            restore();
        }
    });

    it("stays silent on a preview with no published reference", async () => {
        document.body.innerHTML = `<cp-spec-compare></cp-spec-compare><div class="cp-viewer"></div>`;
        await flush();
        assert.equal(window.cpSpecCompare, undefined);
    });
});
