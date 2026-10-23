// Behavioural contract for the opt-in "Wear panes" view: off draws nothing, on lays every listed
// preview into four captioned watch panes that follow its image, and the choice survives a reload.

import "./setup.js";
import assert from "node:assert/strict";
import { flush, resetDom, stubStorage } from "./setup.js";
import {
    WEAR_PANES_KEY,
    installWearPanes,
    readWearPanes,
    resetWearPanesForTest,
} from "../src/components/WearPanes.js";

const CAPTIONS = [
    "Round · Small",
    "Round · Large",
    "Square · Small",
    "Square · Large",
];

/** A catalog landing: Transparent in the `⋯` panel, then a grid of two cards (one cropped). */
const LANDING = `
  <div class="cp-catalog-tools">
    <div class="cp-head-toggles">
      <div class="cp-catalog-actions">
        <details class="cp-actions-menu"><summary>⋯</summary></details>
        <div class="cp-actions-panel"><cp-bg-toggle></cp-bg-toggle></div>
      </div>
    </div>
  </div>
  <div class="cp-grid" id="cp-grid">
    <a class="cp-card" href="/p/a" aria-label="Chip">
      <div class="cp-imgwrap"><img alt="Chip" src="/render/a.png"></div>
      <div class="cp-meta">Chip</div>
    </a>
    <a class="cp-card" href="/p/b" aria-label="Tile">
      <div class="cp-imgwrap"><span class="cp-crop"><img alt="Tile" src="/render/b.png" style="width:120%"></span></div>
      <div class="cp-meta">Tile</div>
    </a>
  </div>`;

/** The viewer: the stage's snapshot and the View group's Transparent toggle. */
const VIEWER = `
  <div class="cp-viewer">
    <div class="cp-stage"><img id="cp-img" alt="Profile" src="/render/p.png"><div class="cp-error" hidden></div></div>
    <div class="cp-controls"><div class="cp-stage-view-row"><cp-bg-toggle></cp-bg-toggle><button class="cp-zoom-toggle">Fit width</button></div></div>
  </div>`;

const button = () =>
    document.querySelector<HTMLButtonElement>(".cp-wear-panes-btn");
const panes = () => [
    ...document.querySelectorAll<HTMLElement>(".cp-wear-panes"),
];
const captions = (group: Element) =>
    [...group.querySelectorAll("figcaption")].map((c) => c.textContent);

describe("wear panes", () => {
    let storage: ReturnType<typeof stubStorage>;

    beforeEach(() => {
        resetDom();
        resetWearPanesForTest();
        document.documentElement.removeAttribute("data-cp-wear-panes");
        storage = stubStorage();
    });

    afterEach(() => resetWearPanesForTest());

    it("is off by default: a labelled, unpressed toggle beside Transparent and no panes", () => {
        document.body.innerHTML = LANDING;
        const toggle = installWearPanes();
        assert.ok(toggle);
        assert.equal(toggle.textContent, "Wear panes");
        assert.match(toggle.getAttribute("aria-label")!, /Wear watch panes/);
        assert.equal(toggle.getAttribute("aria-pressed"), "false");
        assert.equal(
            document.querySelector(".cp-actions-panel cp-bg-toggle")!
                .nextElementSibling,
            toggle,
        );
        assert.deepEqual(panes(), []);
        assert.equal(storage.get(WEAR_PANES_KEY), null);
    });

    it("on, gives every card four captioned panes of its own image", () => {
        document.body.innerHTML = LANDING;
        installWearPanes()!.click();
        assert.equal(button()!.getAttribute("aria-pressed"), "true");
        const groups = panes();
        assert.equal(groups.length, 2);
        for (const [group, name, src] of [
            [groups[0], "Chip", "/render/a.png"],
            [groups[1], "Tile", "/render/b.png"],
        ] as const) {
            assert.equal(group.getAttribute("role"), "group");
            assert.equal(
                group.getAttribute("aria-label"),
                `${name} in approximate Wear panes`,
            );
            // Inside the card, right after its thumbnail.
            assert.ok(
                group.previousElementSibling!.classList.contains("cp-imgwrap"),
            );
            assert.deepEqual(captions(group), CAPTIONS);
            const figures = [...group.querySelectorAll<HTMLElement>("figure")];
            assert.deepEqual(
                figures.map((f) => [f.dataset.shape, f.dataset.size]),
                [
                    ["round", "small"],
                    ["round", "large"],
                    ["square", "small"],
                    ["square", "large"],
                ],
            );
            const imgs = [
                ...group.querySelectorAll<HTMLImageElement>(
                    ".cp-wear-screen > .cp-wear-container > img",
                ),
            ];
            assert.equal(imgs.length, 4);
            assert.ok(imgs.every((img) => img.getAttribute("src") === src));
            assert.deepEqual(
                imgs.map((img) => img.alt),
                CAPTIONS.map((c) => `${name} — ${c}`),
            );
            // The crop's inline geometry is the thumbnail's, not the pane's.
            assert.ok(imgs.every((img) => !img.getAttribute("style")));
        }
        assert.equal(
            document.documentElement.getAttribute("data-cp-wear-panes"),
            "on",
        );
    });

    it("remembers the choice across a reload, and off forgets it", () => {
        document.body.innerHTML = LANDING;
        installWearPanes()!.click();
        assert.equal(storage.get(WEAR_PANES_KEY), "1");

        // The next page load.
        resetWearPanesForTest();
        document.body.innerHTML = LANDING;
        const toggle = installWearPanes()!;
        assert.equal(toggle.getAttribute("aria-pressed"), "true");
        assert.equal(panes().length, 2);

        toggle.click();
        assert.equal(storage.get(WEAR_PANES_KEY), null);
        assert.deepEqual(panes(), []);
        assert.equal(
            document.documentElement.hasAttribute("data-cp-wear-panes"),
            false,
        );
        assert.equal(readWearPanes(), false);
    });

    it("follows a re-pointed image and a card added later", async () => {
        localStorage.setItem(WEAR_PANES_KEY, "1");
        document.body.innerHTML = LANDING;
        installWearPanes();
        document
            .querySelector<HTMLImageElement>('img[alt="Chip"]')!
            .setAttribute("src", "/render/a.png?theme=dark");
        const card = document.createElement("a");
        card.className = "cp-card";
        card.innerHTML = `<div class="cp-imgwrap"><img alt="New" src="/render/c.png"></div>`;
        document.getElementById("cp-grid")!.append(card);
        await flush();
        const groups = panes();
        assert.equal(groups.length, 3);
        assert.ok(
            [...groups[0].querySelectorAll("img")].every(
                (img) => img.getAttribute("src") === "/render/a.png?theme=dark",
            ),
        );
        assert.deepEqual(captions(groups[2]), CAPTIONS);
    });

    it("lays the viewer's render into four panes at the end of the stage", () => {
        document.body.innerHTML = VIEWER;
        const toggle = installWearPanes()!;
        assert.equal(
            document.querySelector(".cp-stage-view-row cp-bg-toggle")!
                .nextElementSibling,
            toggle,
        );
        assert.deepEqual(panes(), []);
        toggle.click();
        const [group] = panes();
        assert.equal(group.parentElement, document.querySelector(".cp-stage"));
        assert.deepEqual(captions(group), CAPTIONS);
        assert.ok(
            [...group.querySelectorAll("img")].every(
                (img) => img.getAttribute("src") === "/render/p.png",
            ),
        );
    });

    it("hides a viewer's panes until its render has a source", async () => {
        localStorage.setItem(WEAR_PANES_KEY, "1");
        document.body.innerHTML = VIEWER.replace(' src="/render/p.png"', "");
        installWearPanes();
        assert.equal(panes()[0].hidden, true);
        document.getElementById("cp-img")!.setAttribute("src", "/render/q.png");
        await flush();
        assert.equal(panes()[0].hidden, false);
        assert.equal(
            panes()[0].querySelector("img")!.getAttribute("src"),
            "/render/q.png",
        );
    });

    it("still applies a pick when storage refuses the write", () => {
        stubStorage(true);
        document.body.innerHTML = LANDING;
        installWearPanes()!.click();
        assert.equal(panes().length, 2);
        // Held for this page view, so a remount keeps it.
        document.body.innerHTML = LANDING;
        assert.equal(installWearPanes()!.getAttribute("aria-pressed"), "true");
    });

    it("joins the toggle row when there is no Transparent control", () => {
        document.body.innerHTML = `
          <div class="cp-catalog-head-row"><div class="cp-head-toggles"><div class="cp-toolbar"></div></div></div>
          <div class="cp-sections" id="cp-grid"></div>`;
        const toggle = installWearPanes()!;
        assert.equal(
            document.querySelector(".cp-head-toggles")!.lastElementChild,
            toggle,
        );
        assert.equal(installWearPanes(), toggle);
        assert.equal(document.querySelectorAll(".cp-wear-panes-btn").length, 1);
    });

    it("stays off pages that list no previews", () => {
        document.body.innerHTML = `<div class="cp-head-toggles"></div><div id="cp-grid" class="cp-grid cp-syslist"></div>`;
        assert.equal(installWearPanes(), null);
        assert.equal(button(), null);
    });
});
