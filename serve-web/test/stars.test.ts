import "./setup.js";
import assert from "node:assert/strict";
import { resetDom, stubStorage } from "./setup.js";
import {
    STARS_KEY,
    cardPreviewIds,
    installStars,
    mergeStars,
    readStars,
    resetStarsForTest,
    systemFromPath,
} from "../src/chrome/stars.js";
import {
    MAX_FRAGMENT_LENGTH,
    MAX_SHARED_IDS,
    decodeSharedStars,
    encodeSharedStars,
    fitSharedStars,
    sharedStarsUrl,
    sharedStarsValue,
} from "../src/chrome/starsLink.js";

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
        // A root-mounted legacy catalog is told apart by its session.
        assert.equal(
            systemFromPath("/p/button", "?session=wear-m3"),
            "wear-m3",
        );
        assert.equal(
            systemFromPath("/", "?token=t&session=wear-m3"),
            "wear-m3",
        );
        assert.equal(
            systemFromPath("/compose-m3/", "?session=other"),
            "compose-m3",
        );
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

describe("shared stars", () => {
    const clipboard = Object.getOwnPropertyDescriptor(navigator, "clipboard");
    beforeEach(() => {
        stubStorage();
        resetStarsForTest();
    });
    afterEach(() => {
        resetDom();
        if (clipboard) Object.defineProperty(navigator, "clipboard", clipboard);
        else delete (navigator as { clipboard?: unknown }).clipboard;
    });

    const flush = () => new Promise((r) => setTimeout(r, 0));

    it("round-trips a shortlist through the fragment, ids with separators included", () => {
        const stars = {
            catalogs: ["compose-m3", "team/apps"],
            previews: ["chip__light", "odd,id:with;bits", "ünï"],
        };
        const value = encodeSharedStars(stars);
        assert.equal(
            value.startsWith("c:compose-m3,team%2Fapps;p:chip__light,"),
            true,
        );
        assert.deepEqual(decodeSharedStars(value), stars);
        assert.equal(encodeSharedStars({ catalogs: [], previews: [] }), "");
    });

    it("builds a link to this page that keeps its query and replaces its fragment", () => {
        at("/compose-m3/?tab=all#c-chip");
        const url = sharedStarsUrl({ catalogs: [], previews: ["chip"] });
        assert.equal(
            url,
            `${location.origin}/compose-m3/?tab=all#stars=p:chip`,
        );
        assert.equal(sharedStarsValue(new URL(url).hash), "p:chip");
        assert.equal(sharedStarsValue("#c-chip"), null);
        // The visitor's own browse token never rides a link made to hand on.
        at("/?token=secret&session=wear-m3");
        assert.equal(
            sharedStarsUrl({ catalogs: ["a"], previews: [] }),
            `${location.origin}/?session=wear-m3#stars=c:a`,
        );
    });

    it("ignores malformed input and caps what a link can carry", () => {
        for (const bad of [
            "",
            "garbage",
            ":x",
            "c:",
            "c:a,,b",
            "c:%E0%A4%A",
            "c:" + "a".repeat(9000),
        ])
            assert.equal(decodeSharedStars(bad), null, bad);
        // A control character or an over-long id is dropped, not the whole link.
        assert.deepEqual(decodeSharedStars("c:ok,%00bad," + "x".repeat(201)), {
            catalogs: ["ok"],
            previews: [],
        });
        // A group a later format might add is skipped; duplicates collapse.
        assert.deepEqual(decodeSharedStars("z:whatever;p:a,a,b"), {
            catalogs: [],
            previews: ["a", "b"],
        });
        const many = Array.from({ length: 80 }, (_, i) => `id${i}`);
        assert.equal(
            decodeSharedStars(`p:${many.join(",")}`)!.previews.length,
            MAX_SHARED_IDS,
        );
    });

    it("trims a too-long shortlist to the newest stars, so every link it writes decodes", () => {
        // 50 ids at the 200-char cap, each of them non-ASCII: far past the fragment limit encoded.
        const long = (i: number) => `${i}`.padStart(4, "0") + "é".repeat(196);
        const stars = {
            catalogs: Array.from({ length: MAX_SHARED_IDS }, (_, i) => long(i)),
            previews: Array.from({ length: MAX_SHARED_IDS }, (_, i) =>
                long(100 + i),
            ),
        };
        const value = encodeSharedStars(stars);
        assert.ok(value.length <= MAX_FRAGMENT_LENGTH, `${value.length}`);
        const fit = fitSharedStars(stars);
        assert.ok(fit.catalogs.length > 0);
        assert.ok(fit.catalogs.length < MAX_SHARED_IDS);
        // Newest first, so the trim keeps a prefix.
        assert.deepEqual(
            fit.catalogs,
            stars.catalogs.slice(0, fit.catalogs.length),
        );
        assert.deepEqual(decodeSharedStars(value), fit);

        // At the plain-ASCII maximum everything fits and round-trips untouched.
        const ascii = {
            catalogs: Array.from({ length: MAX_SHARED_IDS }, (_, i) =>
                `c${i}`.padEnd(150, "x"),
            ),
            previews: [],
        };
        assert.deepEqual(decodeSharedStars(encodeSharedStars(ascii)), ascii);
    });

    it("merges into the visitor's stars instead of replacing them", () => {
        localStorage.setItem(
            STARS_KEY,
            JSON.stringify({
                catalogs: ["wear-m3"],
                previews: { "compose-m3": ["button"] },
            }),
        );
        assert.equal(
            mergeStars("compose-m3", {
                catalogs: ["jetcaster", "wear-m3"],
                previews: ["chip__light", "button"],
            }),
            2,
        );
        assert.deepEqual(readStars(), {
            catalogs: ["jetcaster", "wear-m3"],
            previews: { "compose-m3": ["chip__light", "button"] },
        });
    });

    it("copies a link carrying the Starred row from its Share action", async () => {
        const copied: string[] = [];
        Object.defineProperty(navigator, "clipboard", {
            configurable: true,
            value: {
                writeText: (text: string) => {
                    copied.push(text);
                    return Promise.resolve();
                },
            },
        });
        at("/");
        document.body.innerHTML = HOME;
        installStars();
        star('#cp-grid [data-cp-system="wear-m3"] > .cp-star').click();
        star('#cp-grid-1 [data-cp-system="jetcaster"] > .cp-star').click();
        const share = document.querySelector<HTMLButtonElement>(
            "#cp-starred .cp-section-title .cp-starred-share",
        )!;
        assert.ok(share, "the Starred heading carries a Share action");
        share.click();
        await flush();
        assert.deepEqual(copied, [
            `${location.origin}/#stars=c:jetcaster,wear-m3`,
        ]);
        assert.equal(share.textContent, "Link copied ✓");
    });

    it("offers a link's previews on the landing, and Add merges then clears the fragment", () => {
        localStorage.setItem(
            STARS_KEY,
            JSON.stringify({
                catalogs: [],
                previews: { "compose-m3": ["button"] },
            }),
        );
        // `button` is already starred and `gone` is not on this page: only the chip is offered.
        at("/compose-m3/?tab=all#stars=p:chip__dark,button,gone");
        document.body.innerHTML = LANDING;
        installStars();
        const offer = document.getElementById("cp-stars-offer")!;
        assert.ok(offer, "the link raises an offer");
        assert.equal(
            offer.querySelector(".cp-stars-offer-text")!.textContent,
            "Add 1 starred preview from this link?",
        );
        // Nothing is added until the visitor says so.
        assert.deepEqual(readStars().previews, { "compose-m3": ["button"] });

        offer.querySelector<HTMLButtonElement>(".cp-stars-offer-add")!.click();
        assert.deepEqual(readStars().previews, {
            "compose-m3": ["chip__dark", "button"],
        });
        assert.equal(document.getElementById("cp-stars-offer"), null);
        assert.equal(location.hash, "");
        assert.equal(location.search, "?tab=all");
        assert.equal(
            document.querySelectorAll("#cp-starred .cp-card").length,
            2,
        );
    });

    it("dismisses without adding, and clears a link with nothing to offer", () => {
        at("/#stars=c:jetcaster");
        document.body.innerHTML = HOME;
        installStars();
        document
            .querySelector<HTMLButtonElement>(".cp-stars-offer-dismiss")!
            .click();
        assert.deepEqual(readStars().catalogs, []);
        assert.equal(document.getElementById("cp-stars-offer"), null);
        assert.equal(location.hash, "");

        resetDom();
        resetStarsForTest();
        at("/#stars=c:%E0%A4%A");
        document.body.innerHTML = HOME;
        installStars();
        assert.equal(document.getElementById("cp-stars-offer"), null);
        assert.equal(location.hash, "");
    });
});
