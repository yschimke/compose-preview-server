// The viewer's 8dp grid: one cell is 8dp of the render, scaled with the image as shown; the box
// follows the image's pixels (letterboxed when presenting); the choice is remembered per browser,
// and presenting starts without it.

import "./setup.js";
import assert from "node:assert/strict";
import { resetDom } from "./setup.js";
import {
    contentBox,
    densityFromDeviceLabel,
    installGridOverlay,
} from "../src/viewer/gridOverlay.js";

/** A stage holding an image of [natural] px drawn into a [shown] CSS-px box at (20, 30). */
function stageWithImage(
    natural: [number, number],
    shown: [number, number],
    objectFit = "",
) {
    document.body.innerHTML = `<div class="cp-viewer"><div class="cp-stage"><img id="cp-img"></div></div>`;
    const stage = document.querySelector<HTMLElement>(".cp-stage")!;
    const img = document.querySelector<HTMLImageElement>("#cp-img")!;
    Object.defineProperty(img, "naturalWidth", { get: () => natural[0] });
    Object.defineProperty(img, "naturalHeight", { get: () => natural[1] });
    const rect = (left: number, top: number, width: number, height: number) =>
        ({ left, top, width, height }) as DOMRect;
    stage.getBoundingClientRect = () => rect(0, 0, 800, 600);
    img.getBoundingClientRect = () => rect(20, 30, shown[0], shown[1]);
    img.style.objectFit = objectFit;
    return { stage, img };
}

const toggle = () =>
    document.querySelector<HTMLButtonElement>(".cp-grid-toggle")!;
const grid = () => document.querySelector<HTMLElement>(".cp-grid-overlay")!;

function presentAs(element: Element | null) {
    Object.defineProperty(document, "fullscreenElement", {
        configurable: true,
        get: () => element,
    });
    document.dispatchEvent(new Event("fullscreenchange"));
}

describe("the viewer's 8dp grid", () => {
    beforeEach(() => {
        resetDom();
        localStorage.clear();
    });
    afterEach(() => {
        delete (document as { fullscreenElement?: unknown }).fullscreenElement;
    });

    it("letterboxes the content box only for object-fit: contain", () => {
        assert.deepEqual(contentBox(400, 300, 200, 300, ""), {
            left: 0,
            top: 0,
            width: 400,
            height: 300,
        });
        // A tall render contain-fitted into a wide screen: pillarboxed, centred.
        assert.deepEqual(contentBox(1000, 600, 400, 800, "contain"), {
            left: 350,
            top: 0,
            width: 300,
            height: 600,
        });
    });

    it("reads a picked device's density off its dp size and the PNG", () => {
        const pixel5 = {
            textContent: "Pixel 5 · compact phone (393 × 851 dp)",
        };
        const png = (naturalWidth: number, naturalHeight: number) => ({
            naturalWidth,
            naturalHeight,
        });
        assert.equal(densityFromDeviceLabel(pixel5, png(1179, 2553)), 3);
        assert.equal(
            densityFromDeviceLabel(pixel5, png(2553, 1179)),
            3,
            "landscape is the same device turned round",
        );
        assert.equal(
            densityFromDeviceLabel(
                { textContent: "(preview default)" },
                png(10, 10),
            ),
            null,
        );
        assert.equal(densityFromDeviceLabel(undefined, png(10, 10)), null);
    });

    it("draws one cell per 8dp of the render as shown, over the image's box", () => {
        // 360dp at density 2.625 is 945px, shown at half size: 8dp is 10.5 CSS px.
        const { stage, img } = stageWithImage([945, 1890], [472.5, 945]);
        installGridOverlay(stage, img, () => 2.625);
        assert.equal(grid().hidden, true, "off until asked for");
        assert.equal(toggle().getAttribute("aria-pressed"), "false");
        assert.match(toggle().title, /2\.625 px per dp/);

        toggle().click();
        assert.equal(grid().hidden, false);
        assert.equal(toggle().getAttribute("aria-pressed"), "true");
        assert.equal(grid().style.left, "20px");
        assert.equal(grid().style.top, "30px");
        assert.equal(grid().style.width, "472.5px");
        assert.equal(grid().style.getPropertyValue("--cp-grid-x"), "10.5px");
        assert.equal(grid().style.getPropertyValue("--cp-grid-y"), "10.5px");
        assert.equal(localStorage.getItem("cp-grid-overlay"), "1");
    });

    it("stays hidden with no decoded render to lay it over", () => {
        const { stage, img } = stageWithImage([0, 0], [104, 20]);
        localStorage.setItem("cp-grid-overlay", "1");
        installGridOverlay(stage, img, () => 2);
        assert.equal(toggle().getAttribute("aria-pressed"), "true");
        assert.equal(grid().hidden, true, "an alt-text box is not a render");
    });

    it("is remembered per browser", () => {
        localStorage.setItem("cp-grid-overlay", "1");
        const { stage, img } = stageWithImage([200, 200], [200, 200]);
        installGridOverlay(stage, img, () => 1);
        assert.equal(grid().hidden, false, "on from the last visit");
        toggle().click();
        assert.equal(localStorage.getItem("cp-grid-overlay"), "0");
    });

    it("starts hidden when presenting, and a toggle there is for that screen only", () => {
        localStorage.setItem("cp-grid-overlay", "1");
        const { stage, img } = stageWithImage(
            [400, 800],
            [1000, 600],
            "contain",
        );
        installGridOverlay(stage, img, () => 2);
        presentAs(stage);
        assert.equal(grid().hidden, true, "presenting is for showing");
        assert.equal(toggle().getAttribute("aria-pressed"), "false");

        toggle().click();
        assert.equal(grid().hidden, false);
        // Pinned to the letterboxed pixels: 300 wide at x 350, so 8dp (16px) is 12 CSS px.
        assert.equal(grid().style.left, "370px");
        assert.equal(grid().style.width, "300px");
        assert.equal(grid().style.getPropertyValue("--cp-grid-x"), "12px");
        assert.equal(
            localStorage.getItem("cp-grid-overlay"),
            "1",
            "the page's own choice is untouched",
        );

        toggle().click();
        presentAs(null);
        assert.equal(
            grid().hidden,
            false,
            "back on the page, the page's choice",
        );
    });
});
