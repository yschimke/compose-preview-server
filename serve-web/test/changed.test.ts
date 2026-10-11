import "./setup.js";
import assert from "node:assert/strict";
import { resetDom, stubStorage } from "./setup.js";
import {
    CHANGED_KEY,
    CHANGED_LABEL,
    CHANGED_LANDING_LIMIT,
    cardHashes,
    diffAndMerge,
    heroHash,
    installChanged,
    readSeen,
    thumbHash,
} from "../src/chrome/changed.js";

function at(path: string): void {
    history.replaceState(null, "", path);
}

// The front door as the server builds it: a prebaked hero on its content-hashed URL, or (for a
// catalog whose hero could not be baked) the plain render lane, which has no hash to compare.
const homeCard = (system: string, hero: string | null) => `
  <div class="cp-card cp-sys" data-cp-system="${system}">
    <div class="cp-imgwrap">${
        hero
            ? `<img alt="" src="/hero/${system}/${hero}.png?token=t">`
            : `<img alt="" src="/${system}/render/x.png?token=t">`
    }</div>
    <div class="cp-meta"><div class="cp-sys-title"><a class="cp-sys-open" href="/${system}/">${system}</a></div></div>
  </div>`;

const home = (heroes: Record<string, string | null>) => `
  <div class="cp-section-title"><h1 class="cp-head">Material</h1></div>
  <div class="cp-grid cp-syslist" id="cp-grid">
    ${Object.entries(heroes)
        .map(([s, h]) => homeCard(s, h))
        .join("")}
  </div>`;

const src = (id: string, hash: string | null) =>
    `/compose-m3/render/${id}.png?token=t${hash ? `&amp;thumb=${hash}` : ""}`;

const single = (id: string, hash: string | null) => `
  <a class="cp-card" href="/compose-m3/p/${id}?token=t" aria-label="${id}">
    <div class="cp-imgwrap"><img alt="${id}" src="${src(id, hash)}"></div>
  </a>`;

const pair = (l: string, lh: string, d: string, dh: string) => `
  <a class="cp-card" aria-label="${l}" data-swap="1" data-l-id="${l}" data-l-src="${src(l, lh)}"
     data-d-id="${d}" data-d-src="${src(d, dh)}" href="/compose-m3/p/${l}">
    <div class="cp-imgwrap"><img alt="${l}" src="${src(l, lh)}"></div>
  </a>`;

const landing = (cards: string) => `
  <div class="cp-catalog-title"><h1 class="cp-head cp-catalog-head">Compose Material 3</h1></div>
  <div class="cp-grid" id="cp-grid">${cards}</div>`;

function visit(path: string, body: string): void {
    resetDom();
    at(path);
    document.body.innerHTML = body;
    installChanged();
}

const marked = (): string[] =>
    Array.from(document.querySelectorAll("[data-cp-changed]")).map(
        (c) =>
            c.getAttribute("data-cp-system") ?? c.getAttribute("aria-label")!,
    );

describe("changed since your last visit", () => {
    let storage: ReturnType<typeof stubStorage>;
    beforeEach(() => {
        storage = stubStorage();
    });
    afterEach(() => resetDom());

    it("reads the content hashes the server already emits", () => {
        assert.equal(
            heroHash("/hero/compose-m3/1f0c9a4b.png?token=t"),
            "1f0c9a4b",
        );
        assert.equal(heroHash("/compose-m3/render/x.png"), null);
        assert.equal(thumbHash("/m3/render/b.png?token=t&thumb=ab12"), "ab12");
        // A theme switch appends to the URL; the hash survives it.
        assert.equal(
            thumbHash("/m3/render/b.png?thumb=ab12&themeProvider=x.Dark"),
            "ab12",
        );
        assert.equal(thumbHash("/m3/render/b.png?token=t"), null);
    });

    it("compares only what has a hash on both sides, and keeps what this page lacks", () => {
        const { changed, merged } = diffAndMerge(
            { a: "1", b: "2", gone: "9" },
            { a: "1", b: "3", fresh: "4" },
        );
        assert.deepEqual([...changed], ["b"]);
        // A card new since last time is not "changed" — there was nothing to compare against.
        assert.deepEqual(merged, { a: "1", b: "3", fresh: "4", gone: "9" });
        assert.equal(diffAndMerge(null, { a: "1" }).changed.size, 0);
    });

    it("hashes both renders of a light/dark card", () => {
        resetDom();
        document.body.innerHTML = pair("b_light", "l1", "b_dark", "d1");
        assert.deepEqual(cardHashes(document.querySelector(".cp-card")!), {
            b_light: "l1",
            b_dark: "d1",
        });
    });

    it("marks nothing on the first visit to the front door, then the catalogs whose hero moved", () => {
        visit("/", home({ m3: "aaa", wear: "bbb", remote: null }));
        assert.deepEqual(marked(), []);
        assert.deepEqual(readSeen().home, { m3: "aaa", wear: "bbb" });

        visit(
            "/",
            home({ m3: "aaa", wear: "ccc", remote: null, fresh: "ddd" }),
        );
        assert.deepEqual(marked(), ["wear"]);
        const dot = document.querySelector(
            '[data-cp-system="wear"] > .cp-changed',
        )!;
        assert.equal(dot.getAttribute("role"), "img");
        assert.equal(dot.getAttribute("aria-label"), CHANGED_LABEL);

        // Seen now: the visit after shows no dots.
        visit(
            "/",
            home({ m3: "aaa", wear: "ccc", remote: null, fresh: "ddd" }),
        );
        assert.deepEqual(marked(), []);
    });

    it("marks the landing cards whose thumbnail hash moved, per catalog", () => {
        const page = (b: string, card: string | null) =>
            landing(
                single("button", b) +
                    single("card", card) +
                    pair("chip_light", "l1", "chip_dark", "d1"),
            );
        visit("/compose-m3/", page("b1", "c1"));
        assert.deepEqual(marked(), []);

        // A different catalog with the same ids is its own first visit.
        visit("/wear-m3/", page("zz", "zz"));
        assert.deepEqual(marked(), []);

        // `card` lost its baked thumbnail on this build: no dot, and its old hash is kept.
        visit(
            "/compose-m3/",
            landing(
                single("button", "b2") +
                    single("card", null) +
                    pair("chip_light", "l1", "chip_dark", "d2"),
            ),
        );
        assert.deepEqual(marked(), ["button", "chip_light"]);
        const button = document.querySelector('[aria-label="button"]')!;
        assert.equal(
            button.getAttribute("aria-describedby"),
            "cp-changed-desc",
        );
        assert.equal(
            document.getElementById("cp-changed-desc")?.textContent,
            CHANGED_LABEL,
        );

        visit(
            "/compose-m3/",
            landing(
                single("button", "b2") +
                    single("card", "c2") +
                    pair("chip_light", "l1", "chip_dark", "d2"),
            ),
        );
        assert.deepEqual(marked(), ["card"]);
    });

    it("marks a starred copy alongside its original", () => {
        visit("/compose-m3/", landing(single("button", "b1")));
        resetDom();
        at("/compose-m3/");
        document.body.innerHTML =
            `<section id="cp-starred">${single("button", "b2")}</section>` +
            landing(single("button", "b2"));
        installChanged();
        assert.equal(document.querySelectorAll("[data-cp-changed]").length, 2);
    });

    it("stays bounded and survives blocked or malformed storage", () => {
        for (let i = 0; i < CHANGED_LANDING_LIMIT + 5; i++)
            visit(`/c${i}/`, landing(single("button", "b")));
        const seen = readSeen();
        assert.equal(seen.landings.length, CHANGED_LANDING_LIMIT);
        assert.equal(seen.landings[0].system, `c${CHANGED_LANDING_LIMIT + 4}`);

        localStorage.setItem(CHANGED_KEY, "{not json");
        visit("/", home({ m3: "aaa" }));
        assert.deepEqual(marked(), []);
        assert.ok(storage.get(CHANGED_KEY)!.includes("aaa"));

        stubStorage(true);
        visit("/", home({ m3: "zzz" }));
        assert.deepEqual(marked(), []);
    });
});
