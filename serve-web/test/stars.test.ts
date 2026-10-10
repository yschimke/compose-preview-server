import "./setup.js";
import assert from "node:assert/strict";
import { resetDom, stubStorage } from "./setup.js";
import {
    STARS_KEY,
    cardPreviewIds,
    installStars,
    readStars,
    resetStarsForTest,
    systemFromPath,
} from "../src/chrome/stars.js";

function at(path: string): void {
    history.replaceState(null, "", path);
}

function star(selector: string): HTMLButtonElement {
    const button = document.querySelector<HTMLButtonElement>(selector);
    assert.ok(button, `no star at ${selector}`);
    return button;
}

const homeCard = (system: string, title: string) => `
  <div class="cp-card cp-sys" data-cp-system="${system}" data-browser-search="${title.toLowerCase()}">
    <div class="cp-imgwrap"><img alt="${title} preview" src="/hero/${system}.png"></div>
    <div class="cp-meta"><div class="cp-sys-title"><a class="cp-sys-open" href="/${system}/">${title}</a></div></div>
  </div>`;

const HOME = `
  <div class="cp-section-title"><h1 class="cp-head">Material</h1></div>
  <div class="cp-grid cp-syslist" id="cp-grid">
    ${homeCard("compose-m3", "Compose Material 3")}
    ${homeCard("wear-m3", "Wear Material 3")}
  </div>
  <div class="cp-section-title"><h1 class="cp-head">Apps</h1></div>
  <div class="cp-grid cp-syslist" id="cp-grid-1">${homeCard("jetcaster", "Jetcaster")}</div>`;

const LANDING = `
  <div class="cp-catalog-head-row"><div class="cp-catalog-title">
    <h1 class="cp-head cp-catalog-head">Compose Material 3</h1></div></div>
  <div class="cp-grid" id="cp-grid">
    <a class="cp-card" id="c-button" href="/compose-m3/p/button" aria-label="Button">
      <div class="cp-imgwrap"><img alt="Button" src="/r/button.png"></div></a>
    <a class="cp-card" id="c-chip" data-swap="1" data-l-id="chip__light" data-d-id="chip__dark"
       href="/compose-m3/p/chip__light" aria-label="Chip">
      <div class="cp-imgwrap"><img alt="Chip" src="/r/chip.png"></div></a>
  </div>`;

describe("stars", () => {
    let storage: ReturnType<typeof stubStorage>;
    beforeEach(() => {
        storage = stubStorage();
        resetStarsForTest();
    });
    afterEach(() => resetDom());

    it("keys a landing and its viewer to the same catalog", () => {
        assert.equal(systemFromPath("/compose-m3/"), "compose-m3");
        assert.equal(
            systemFromPath("/compose-m3/p/button__light"),
            "compose-m3",
        );
        // A top-level site serves its one catalog at `/`.
        assert.equal(systemFromPath("/"), "");
        assert.equal(systemFromPath("/p/button"), "");
    });

    it("reads a light/dark card as both of its renders", () => {
        document.body.innerHTML = LANDING;
        const [single, pair] = document.querySelectorAll(".cp-card");
        assert.deepEqual(cardPreviewIds(single), ["button"]);
        assert.deepEqual(cardPreviewIds(pair), ["chip__light", "chip__dark"]);
    });

    it("pins a starred catalog to the top of the front door", () => {
        at("/");
        document.body.innerHTML = HOME;
        installStars();
        assert.equal(document.getElementById("cp-starred"), null);

        star('[data-cp-system="jetcaster"] > .cp-star').click();

        const row = document.getElementById("cp-starred")!;
        assert.ok(row, "a Starred row appears");
        // Above every server-rendered section, and a copy rather than a move.
        assert.equal(document.body.firstElementChild, row);
        assert.deepEqual(
            Array.from(row.querySelectorAll(".cp-sys")).map((c) =>
                c.getAttribute("data-cp-system"),
            ),
            ["jetcaster"],
        );
        assert.equal(
            document.querySelectorAll('[data-cp-system="jetcaster"]').length,
            2,
        );
        assert.deepEqual(readStars().catalogs, ["jetcaster"]);
        assert.equal(
            star(
                '#cp-grid-1 [data-cp-system="jetcaster"] > .cp-star',
            ).getAttribute("aria-pressed"),
            "true",
        );
    });

    it("lists the newest star first and unstars from the copy", () => {
        at("/");
        document.body.innerHTML = HOME;
        installStars();
        star('#cp-grid [data-cp-system="wear-m3"] > .cp-star').click();
        star('#cp-grid [data-cp-system="compose-m3"] > .cp-star').click();
        const order = () =>
            Array.from(document.querySelectorAll("#cp-starred .cp-sys")).map(
                (c) => c.getAttribute("data-cp-system"),
            );
        assert.deepEqual(order(), ["compose-m3", "wear-m3"]);

        star('#cp-starred [data-cp-system="compose-m3"] > .cp-star').click();
        assert.deepEqual(order(), ["wear-m3"]);
        assert.equal(
            star(
                '#cp-grid [data-cp-system="compose-m3"] > .cp-star',
            ).getAttribute("aria-pressed"),
            "false",
        );
    });

    it("stars previews on a landing and repeats them above the grid", () => {
        at("/compose-m3/");
        document.body.innerHTML = LANDING;
        installStars();

        star("#c-chip > .cp-star").click();
        const row = document.getElementById("cp-starred")!;
        assert.ok(row);
        assert.equal(row.nextElementSibling?.id, "cp-grid");
        const copy = row.querySelector(".cp-card")!;
        assert.equal(copy.getAttribute("href"), "/compose-m3/p/chip__light");
        // The copy keeps no anchor id, so the tree's jumps still land on the real card.
        assert.equal(copy.hasAttribute("id"), false);
        assert.deepEqual(readStars().previews, {
            "compose-m3": ["chip__light"],
        });
    });

    it("stars the catalog from its own heading", () => {
        at("/compose-m3/");
        document.body.innerHTML = LANDING;
        installStars();
        const head = star(".cp-catalog-title > .cp-star");
        assert.equal(
            head.getAttribute("aria-label"),
            "Star Compose Material 3",
        );
        head.click();
        assert.deepEqual(readStars().catalogs, ["compose-m3"]);
        assert.equal(
            head.getAttribute("aria-label"),
            "Unstar Compose Material 3",
        );
    });

    it("stars a preview from the viewer, and the landing shows it", () => {
        at("/compose-m3/p/chip__dark");
        document.body.innerHTML = `
          <div class="cp-preview-head"><h1 class="cp-head cp-preview-title">Chip</h1></div>
          <div class="cp-viewer" data-preview-id="chip__dark"></div>`;
        installStars();
        star(".cp-preview-head > .cp-star").click();
        assert.deepEqual(readStars().previews, {
            "compose-m3": ["chip__dark"],
        });

        resetDom();
        resetStarsForTest();
        at("/compose-m3/");
        document.body.innerHTML = LANDING;
        installStars();
        // The dark render of a light/dark card stars the card.
        assert.equal(
            star("#c-chip > .cp-star").getAttribute("aria-pressed"),
            "true",
        );
        assert.equal(
            document.querySelectorAll("#cp-starred .cp-card").length,
            1,
        );
    });

    it("survives a malformed entry and a blocked store", () => {
        localStorage.setItem(STARS_KEY, "{not json");
        assert.deepEqual(readStars(), { catalogs: [], previews: {} });

        stubStorage(true);
        resetStarsForTest();
        at("/");
        document.body.innerHTML = HOME;
        installStars();
        const button = star('#cp-grid [data-cp-system="wear-m3"] > .cp-star');
        assert.doesNotThrow(() => button.click());
        // The write failed, but the click still holds for this page view.
        assert.equal(button.getAttribute("aria-pressed"), "true");
        assert.ok(document.getElementById("cp-starred"));
        void storage;
    });

    it("hides a starred copy the landing's filter rules out, but not another tab's", async () => {
        at("/compose-m3/");
        document.body.innerHTML = '<input id="cp-search" value="">' + LANDING;
        installStars();
        star("#c-chip > .cp-star").click();
        const copy = () =>
            document.querySelector<HTMLElement>("#cp-starred .cp-card")!;
        const original = document.getElementById("c-chip")!;

        // No query: the original is hidden only because it is in another tab.
        original.hidden = true;
        await new Promise((r) => setTimeout(r, 0));
        assert.equal(copy().hidden, false);

        // A query the original does not match hides the copy, and the empty row with it.
        (document.getElementById("cp-search") as HTMLInputElement).value =
            "zzz";
        original.hidden = false;
        original.hidden = true;
        await new Promise((r) => setTimeout(r, 0));
        assert.equal(copy().hidden, true);
        assert.equal(document.getElementById("cp-starred")!.hidden, true);
    });

    it("does not copy the live-preview affordances onto a starred copy", () => {
        at("/compose-m3/");
        document.body.innerHTML = LANDING;
        const original = document.getElementById("c-button")!;
        original.classList.add("cp-card-livable");
        original
            .querySelector(".cp-imgwrap")!
            .insertAdjacentHTML(
                "beforeend",
                '<span class="cp-live-hint">hold for live</span>',
            );
        installStars();
        star("#c-button > .cp-star").click();
        const copy = document.querySelector("#cp-starred .cp-card")!;
        assert.equal(copy.classList.contains("cp-card-livable"), false);
        assert.equal(copy.querySelector(".cp-live-hint"), null);
    });

    it("leaves a page with nothing to star alone", () => {
        at("/status");
        document.body.innerHTML = "<main><p>Status</p></main>";
        installStars();
        assert.equal(document.querySelector(".cp-star"), null);
    });
});
