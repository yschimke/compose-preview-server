import "./setup.js";
import assert from "node:assert/strict";
import { resetDom, stubStorage } from "./setup.js";
import {
    RECENT_KEY,
    RECENT_LIMIT,
    type RecentEntry,
    installRecent,
    readRecent,
    recordRecent,
    catalogKey,
    routingQuery,
} from "../src/chrome/recent.js";

function at(path: string): void {
    history.replaceState(null, "", path);
}

// The nav drawer as the server folds it: a non-default variant has no row of its own, so the row
// marked current is its component's representative — which is NOT what should be remembered.
const VIEWER = (id: string, title: string) => `
  <nav class="cp-nav">
    <a class="cp-nav-item" href="/compose-m3/p/other?token=t" title="other">
      <img class="cp-nav-thumb" alt="" src="/compose-m3/render/other.png?token=t"></a>
    <a class="cp-nav-item" href="/compose-m3/p/representative?token=t" aria-current="page">
      <img class="cp-nav-thumb" alt="" src="/compose-m3/render/representative.png?thumb=h&amp;token=t"></a>
  </nav>
  <h1 class="cp-head cp-preview-title">${title} <span class="cp-badge">⚠ untrusted</span></h1>
  <div class="cp-viewer" data-preview-id="${id}"><img id="cp-img" alt="${title}"></div>`;

const homeCard = (system: string, title: string) => `
  <div class="cp-card cp-sys" data-cp-system="${system}">
    <div class="cp-meta"><div class="cp-sys-title"><a class="cp-sys-open" href="/${system}/">${title}</a></div></div>
  </div>`;

const HOME = `
  <div id="cp-home-components" hidden></div>
  <div class="cp-section-title"><h1 class="cp-head">Material</h1></div>
  <div class="cp-grid cp-syslist" id="cp-grid">
    ${homeCard("compose-m3", "Compose Material 3")}
    ${homeCard("wear-m3", "Wear Material 3")}
  </div>`;

const LANDING = `
  <div class="cp-catalog-head-row"><div class="cp-catalog-title">
    <h1 class="cp-head cp-catalog-head">Compose Material 3</h1></div></div>
  <div class="cp-grid" id="cp-grid">
    <a class="cp-card" href="/compose-m3/p/button" aria-label="Button"></a>
  </div>`;

const entry = (system: string, id: string): RecentEntry => ({
    system,
    id,
    label: id,
    href: `/${system}/p/${id}`,
    thumb: `/${system}/render/${id}.png`,
});

/** Open the viewer for [id] in [system], the way a full page load would. */
function visit(system: string, id: string, title = id): void {
    resetDom();
    at(`/${system}/p/${id}`);
    document.body.innerHTML = VIEWER(id, title);
    installRecent();
}

const labels = () =>
    Array.from(document.querySelectorAll("#cp-recent .cp-recent-label")).map(
        (el) => el.textContent,
    );

describe("recently viewed", () => {
    let storage: ReturnType<typeof stubStorage>;
    beforeEach(() => {
        storage = stubStorage();
    });
    afterEach(() => resetDom());

    it("keys a landing and its viewer to the same catalog", () => {
        assert.equal(catalogKey("/compose-m3/", ""), "compose-m3");
        assert.equal(
            catalogKey("/compose-m3/p/button", "?token=t"),
            "compose-m3",
        );
        assert.equal(catalogKey("/p/button", ""), "");
        // A root-mounted legacy catalog is the session it names, the same id its path form has.
        assert.equal(catalogKey("/p/button", "?token=t&session=abc"), "abc");
        assert.equal(catalogKey("/", "?session=abc"), "abc");
        // The path outranks a stray query, as it does on the server.
        assert.equal(
            catalogKey("/compose-m3/p/b", "?session=abc"),
            "compose-m3",
        );
    });

    it("keeps only the routing part of the query", () => {
        assert.equal(
            routingQuery("?mode=live&session=a%20b&theme=dark&token=t"),
            "?token=t&session=a+b",
        );
        assert.equal(routingQuery("?mode=live"), "");
    });

    it("records the variant being viewed, not the nav row it folds into", () => {
        resetDom();
        at("/compose-m3/p/card__pressed?token=t&mode=live");
        document.body.innerHTML = VIEWER("card__pressed", "Card");
        installRecent();
        assert.deepEqual(readRecent(), [
            {
                system: "compose-m3",
                id: "card__pressed",
                // The trust badge inside the heading is not part of the name.
                label: "Card",
                href: "/compose-m3/p/card__pressed?token=t",
                thumb: "/compose-m3/render/card__pressed.png?token=t",
            },
        ]);
        // The viewer only records; the row belongs to the pages you start from.
        assert.equal(document.getElementById("cp-recent"), null);
    });

    it("builds the link and thumbnail from the id, encoded", () => {
        at("/wear-m3/p/edge%20button");
        document.body.innerHTML = `
          <h1 class="cp-preview-title">Edge button</h1>
          <div class="cp-viewer" data-preview-id="edge button"></div>`;
        installRecent();
        const [only] = readRecent();
        assert.equal(only.href, "/wear-m3/p/edge%20button");
        assert.equal(only.thumb, "/wear-m3/render/edge%20button.png");
    });

    it("keeps a root-mounted session apart, and its ?session= on the way back", () => {
        // A single-preview legacy session: no nav drawer at all.
        for (const session of ["abc", "xyz"]) {
            resetDom();
            at(`/p/card?session=${session}`);
            document.body.innerHTML = `
              <h1 class="cp-preview-title">Card</h1>
              <div class="cp-viewer" data-preview-id="card"></div>`;
            installRecent();
        }
        assert.deepEqual(
            readRecent().map((e) => [e.system, e.href, e.thumb]),
            [
                ["xyz", "/p/card?session=xyz", "/render/card.png?session=xyz"],
                ["abc", "/p/card?session=abc", "/render/card.png?session=abc"],
            ],
        );

        resetDom();
        at("/?session=abc");
        document.body.innerHTML = LANDING;
        installRecent();
        assert.deepEqual(
            Array.from(document.querySelectorAll("#cp-recent a")).map((a) =>
                a.getAttribute("href"),
            ),
            ["/p/card?session=abc"],
        );
    });

    it("keeps the newest first, once each, and only the last few", () => {
        visit("compose-m3", "a");
        visit("compose-m3", "b");
        visit("compose-m3", "a");
        assert.deepEqual(
            readRecent().map((e) => e.id),
            ["a", "b"],
        );
        // The same id in another catalog is a different preview.
        visit("wear-m3", "a");
        assert.equal(readRecent().length, 3);

        for (let i = 0; i < RECENT_LIMIT + 3; i++)
            recordRecent(entry("compose-m3", `p${i}`));
        const ids = readRecent().map((e) => e.id);
        assert.equal(ids.length, RECENT_LIMIT);
        assert.equal(ids[0], `p${RECENT_LIMIT + 2}`);
    });

    it("shows every catalog's on the front door, labelled with its catalog", () => {
        recordRecent(entry("wear-m3", "edge"));
        recordRecent(entry("compose-m3", "card"));
        at("/");
        document.body.innerHTML = HOME;
        installRecent();

        const row = document.getElementById("cp-recent")!;
        assert.ok(row, "a Recently viewed row appears");
        // Above the first server section.
        assert.equal(
            row.nextElementSibling,
            document.querySelector(".cp-section-title"),
        );
        assert.deepEqual(labels(), ["card", "edge"]);
        assert.deepEqual(
            Array.from(row.querySelectorAll(".cp-recent-catalog")).map(
                (el) => el.textContent,
            ),
            ["Compose Material 3", "Wear Material 3"],
        );
        const link = row.querySelector<HTMLAnchorElement>("a.cp-recent-item")!;
        assert.equal(link.getAttribute("href"), "/compose-m3/p/card");
        assert.equal(
            link.querySelector("img")!.getAttribute("src"),
            "/compose-m3/render/card.png",
        );
    });

    it("goes above an existing Starred row", () => {
        recordRecent(entry("compose-m3", "card"));
        at("/");
        document.body.innerHTML = HOME;
        const starred = document.createElement("section");
        starred.id = "cp-starred";
        const first = document.querySelector(".cp-section-title")!;
        first.before(starred);
        installRecent();
        assert.equal(
            document.getElementById("cp-recent")!.nextElementSibling,
            starred,
        );
    });

    it("shows only this catalog's on a landing, and clears only those", () => {
        recordRecent(entry("wear-m3", "edge"));
        recordRecent(entry("compose-m3", "button"));
        at("/compose-m3/");
        document.body.innerHTML = LANDING;
        installRecent();

        const row = document.getElementById("cp-recent")!;
        assert.deepEqual(labels(), ["button"]);
        assert.equal(
            row.nextElementSibling,
            document.getElementById("cp-grid"),
        );
        assert.equal(row.querySelector(".cp-recent-catalog"), null);

        row.querySelector<HTMLButtonElement>(".cp-recent-clear")!.click();
        assert.equal(document.getElementById("cp-recent"), null);
        assert.deepEqual(
            readRecent().map((e) => e.id),
            ["edge"],
        );
    });

    it("renders nothing when empty, and Clear on the front door forgets all", () => {
        at("/");
        document.body.innerHTML = HOME;
        installRecent();
        assert.equal(document.getElementById("cp-recent"), null);

        resetDom();
        recordRecent(entry("compose-m3", "card"));
        recordRecent(entry("wear-m3", "edge"));
        document.body.innerHTML = HOME;
        installRecent();
        document.querySelector<HTMLButtonElement>(".cp-recent-clear")!.click();
        assert.equal(document.getElementById("cp-recent"), null);
        assert.equal(storage.get(RECENT_KEY), null);
    });

    it("treats malformed or blocked storage as an empty history", () => {
        localStorage.setItem(RECENT_KEY, "{not json");
        assert.deepEqual(readRecent(), []);
        localStorage.setItem(
            RECENT_KEY,
            JSON.stringify([{ id: 3 }, entry("compose-m3", "ok"), "junk"]),
        );
        assert.deepEqual(
            readRecent().map((e) => e.id),
            ["ok"],
        );

        stubStorage(true);
        assert.doesNotThrow(() => visit("compose-m3", "card"));
        Object.defineProperty(globalThis, "localStorage", {
            configurable: true,
            get() {
                throw new Error("SecurityError");
            },
        });
        try {
            assert.deepEqual(readRecent(), []);
            at("/");
            document.body.innerHTML = HOME;
            assert.doesNotThrow(() => installRecent());
            assert.equal(document.getElementById("cp-recent"), null);
        } finally {
            stubStorage();
        }
    });
});
