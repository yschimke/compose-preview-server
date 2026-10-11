// Behavioural contract for the catalog landing's S / M / L thumbnail size.
//
// What can silently rot is the part no screenshot shows: that a pick is remembered across a reload,
// that M leaves the served page exactly as it was (no attribute, no stored entry), that a blocked
// store still lets the click land, and that the control only appears on a catalog's preview grid.

import "./setup.js";
import assert from "node:assert/strict";
import { flush, resetDom, stubStorage } from "./setup.js";
import {
    DENSITY_KEY,
    installGridDensity,
    readDensity,
    resetDensityForTest,
} from "../src/components/GridDensity.js";

/** A flat catalog landing: the filter and the toggle row in the toolbar, then the grid. */
const LANDING = `
  <div class="cp-catalog-tools">
    <div class="cp-searchbar"><input class="cp-search"></div>
    <div class="cp-head-toggles">
      <div class="cp-catalog-actions"><details class="cp-actions-menu"><summary>⋯</summary></details></div>
    </div>
  </div>
  <div class="cp-grid" id="cp-grid"><a class="cp-card" href="/p/a">A</a></div>`;

const root = () => document.documentElement;
const radios = () => [
    ...document.querySelectorAll<HTMLInputElement>(".cp-density input"),
];

function pick(value: string): void {
    const input = radios().find((r) => r.value === value)!;
    input.checked = true;
    input.dispatchEvent(new Event("change", { bubbles: true }));
}

describe("grid density", () => {
    let storage: ReturnType<typeof stubStorage>;

    beforeEach(() => {
        resetDom();
        resetDensityForTest();
        root().removeAttribute("data-cp-density");
        storage = stubStorage();
    });

    it("mounts an S / M / L radio group at the head of the toggle row, M checked", () => {
        document.body.innerHTML = LANDING;
        const group = installGridDensity();
        assert.ok(group);
        assert.equal(group.getAttribute("role"), "radiogroup");
        assert.equal(group.getAttribute("aria-label"), "Thumbnail size");
        assert.equal(
            document.querySelector(".cp-head-toggles")!.firstElementChild,
            group,
        );
        assert.deepEqual(
            radios().map((r) => [r.value, r.getAttribute("aria-label")]),
            [
                ["s", "Small"],
                ["m", "Medium"],
                ["l", "Large"],
            ],
        );
        // One shared name is what gives native radios their arrow-key model.
        assert.ok(
            radios().every(
                (r) => r.type === "radio" && r.name === "cp-density",
            ),
        );
        assert.equal(radios().find((r) => r.checked)?.value, "m");
        assert.equal(root().hasAttribute("data-cp-density"), false);
    });

    it("applies and remembers a pick, and M clears both", async () => {
        document.body.innerHTML = LANDING;
        installGridDensity();
        pick("s");
        await flush();
        assert.equal(root().getAttribute("data-cp-density"), "s");
        assert.equal(storage.get(DENSITY_KEY), "s");

        pick("m");
        assert.equal(root().hasAttribute("data-cp-density"), false);
        assert.equal(storage.get(DENSITY_KEY), null);
    });

    it("restores the remembered size on the next page load", () => {
        localStorage.setItem(DENSITY_KEY, "l");
        document.body.innerHTML = LANDING;
        installGridDensity();
        assert.equal(root().getAttribute("data-cp-density"), "l");
        assert.equal(radios().find((r) => r.checked)?.value, "l");
    });

    it("reads a malformed entry as the default", () => {
        localStorage.setItem(DENSITY_KEY, "xl");
        assert.equal(readDensity(), "m");
    });

    it("still applies a pick when storage refuses the write", () => {
        stubStorage(true);
        document.body.innerHTML = LANDING;
        installGridDensity();
        pick("l");
        assert.equal(root().getAttribute("data-cp-density"), "l");
        // Held for this page view, so a remount shows the pick rather than snapping back to M.
        document.body.innerHTML = LANDING;
        installGridDensity();
        assert.equal(radios().find((r) => r.checked)?.value, "l");
    });

    it("survives storage that throws on read", () => {
        Object.defineProperty(globalThis, "localStorage", {
            configurable: true,
            get() {
                throw new Error("SecurityError");
            },
        });
        try {
            assert.equal(readDensity(), "m");
        } finally {
            stubStorage();
        }
    });

    it("is idempotent", () => {
        document.body.innerHTML = LANDING;
        const first = installGridDensity();
        assert.equal(installGridDensity(), first);
        assert.equal(document.querySelectorAll(".cp-density").length, 1);
    });

    it("stays off pages without a catalog preview grid", () => {
        document.body.innerHTML = `<div class="cp-head-toggles"></div>`;
        assert.equal(installGridDensity(), null);
        // The front door's catalog list is a `.cp-grid` too, but not one of previews.
        document.body.innerHTML = `<div class="cp-head-toggles"></div><div id="cp-grid" class="cp-grid cp-syslist"></div>`;
        assert.equal(installGridDensity(), null);
        assert.equal(document.querySelector(".cp-density"), null);
    });
});
