// Behavioural contract for `<cp-compare-wall>`. Decisions are pinned in `compareWall.test.ts`; this
// covers the wiring: the scorer handle is read late enough, a lane switch can't be overwritten by
// the previous lane's slower rows, the player wall replaces the client table, and the worst rows
// end up first.

import "./setup.js";
import assert from "node:assert/strict";
import { flush, resetDom, stubStorage } from "./setup.js";
import "../src/components/CompareWall.js";

interface Scorer {
    calls: string[];
    /** The canvases `diffCanvases` was asked to paint, in the order the wall asked. */
    painted: HTMLCanvasElement[];
    /** The `maxSide` each normalisation was asked for — `undefined` means "the frame's own size". */
    bounds: Array<number | undefined>;
    settle(): void;
}

/** `window.ComposePreviewCompare`, published late by default as on the real page. */
function stubScorer(
    scores: Record<string, number>,
    options: {
        hold?: boolean;
        holdReference?: boolean;
        publish?: boolean;
        /** Dimensions the stubbed delta map is painted at, before the wall bounds it. */
        map?: { width: number; height: number };
    } = {},
): Scorer {
    const held: Array<() => void> = [];
    const state: Scorer = {
        calls: [],
        painted: [],
        bounds: [],
        settle: () => {
            for (const release of held.splice(0)) release();
        },
    };
    const api = {
        loadImage: async () => ({ naturalWidth: 10, naturalHeight: 10 }),
        scoreSvgUrls: async (png: string, svg: string) => {
            state.calls.push(svg);
            if (options.hold) await new Promise<void>((r) => held.push(r));
            const score = scores[svg];
            if (score === undefined) throw new Error("unavailable");
            return score;
        },
        scoreCanvas: async () => 50,
        scoreImageUrls: async (reference: string) => {
            state.calls.push(reference);
            return { percent: scores[reference] ?? 0, geometry: 3.5 };
        },
        // The reference lane normalises, diffs and scores one pair, so map and percentage describe
        // the same frames. The stub keys the score off the reference URL like `scoreImageUrls`.
        normaliseImageUrls: async (
            reference: string,
            candidate: string,
            maxSide?: number,
        ) => {
            state.calls.push(reference);
            state.bounds.push(maxSide);
            if (options.holdReference)
                await new Promise<void>((r) => held.push(r));
            return {
                reference: {},
                candidate: {},
                images: [{ src: reference }, { src: candidate }],
                width: 8,
                height: 8,
                geometry: 3.5,
            };
        },
        diffCanvases: (
            _reference: unknown,
            _candidate: unknown,
            into: HTMLCanvasElement,
        ) => {
            state.painted.push(into);
            into.width = options.map?.width ?? 8;
            into.height = options.map?.height ?? 8;
            return 12;
        },
        scoreImages: async (reference: { src?: string }) => ({
            percent: scores[reference.src ?? ""] ?? 0,
            geometry: 3.5,
        }),
    };
    if (options.publish !== false)
        (window as Record<string, unknown>).ComposePreviewCompare = api;
    return state;
}

/**
 * One row with the server's `<kind>-<variant>` artifacts. [attrs] is written verbatim (e.g. the
 * published `data-match-<variant>` score).
 */
const rowHtml = (name: string, have: string[], attrs = "") => `
  <tr class="cp-compare-row" data-label="${name}" data-hay="${name.toLowerCase()}"
      data-preview-ids="com.example.${name}Preview" data-preview-light="${name}-light"
      data-preview-dark="${name}-dark"
      ${have.map((h) => `data-${h}="/a/${name}-${h}"`).join(" ")} ${attrs}>
    <td><img class="cp-compare-png" alt=""></td>
    <td class="cp-compare-diff-cell"><canvas class="cp-compare-diff"></canvas></td>
    <td><img class="cp-compare-vector" alt=""><canvas class="cp-compare-rc" hidden></canvas></td>
    <td><span class="cp-compare-score"></span></td>
    <td class="cp-compare-bugs"><a class="cp-compare-bug-new" href="/p/${name}#cp-report"
        data-bug-fallback="/p/${name}#cp-report">+ file</a></td>
  </tr>`;

async function mount(
    options: {
        rows?: Array<{ name: string; have: string[]; attrs?: string }>;
        available?: string;
        search?: string;
        lanes?: boolean;
    } = {},
): Promise<void> {
    const rows = options.rows ?? [
        { name: "Button", have: ["png-light", "svg-light"] },
        { name: "Card", have: ["png-light", "svg-light"] },
    ];
    document.body.innerHTML = `
      <cp-compare-wall></cp-compare-wall>
      <div id="cp-compare" data-default-format="svg" data-default-theme="light"
           data-theme-key="cp-compare-theme" ${options.available ?? 'data-has-svg="1"'}>
        <button data-compare-format="svg">SVG</button>
        <button data-compare-format="rc">RC</button>
        <button data-compare-format="reference">Reference</button>
        <button data-compare-format="parallel">Parallel</button>
        <button data-compare-theme="light">Light</button>
        <button data-compare-theme="dark">Dark</button>
        <input id="cp-compare-search" value="${options.search ?? ""}">
        <span id="cp-compare-count"></span>
        <div id="cp-compare-formats"><table><tbody>${rows.map((r) => rowHtml(r.name, r.have, r.attrs)).join("")}</tbody></table></div>
        <p id="cp-compare-empty" hidden></p>
        ${options.lanes ? '<div id="cp-rc-lanes"></div>' : ""}
      </div>`;
    await flush();
}

const settle = async () => {
    for (let i = 0; i < 12; i++) await flush();
};
const rowsOf = () =>
    Array.from(document.querySelectorAll<HTMLElement>(".cp-compare-row"));
const scoreTextOf = (name: string) =>
    document.querySelector(`[data-label="${name}"] .cp-compare-score`)
        ?.textContent;
const count = () => document.getElementById("cp-compare-count")?.textContent;

describe("<cp-compare-wall>", () => {
    beforeEach(() => {
        stubStorage();
        window.history.replaceState(null, "", "/compare");
    });
    afterEach(() => {
        resetDom();
        delete (window as Record<string, unknown>).ComposePreviewCompare;
        window.history.replaceState(null, "", "/");
    });

    it("scores each row and says so on the row", async () => {
        stubScorer({ "/a/Button-svg-light": 93.4, "/a/Card-svg-light": 61 });
        await mount();
        await settle();
        assert.equal(scoreTextOf("Button"), "93.4%");
        assert.equal(scoreTextOf("Card"), "61.0%");
        assert.equal(count(), "2 comparisons");
    });

    it("bands the score so a wall can be triaged by colour", async () => {
        stubScorer({ "/a/Button-svg-light": 93.4, "/a/Card-svg-light": 61 });
        await mount();
        await settle();
        const classOf = (name: string) =>
            document.querySelector(`[data-label="${name}"] .cp-compare-score`)
                ?.className;
        assert.ok(classOf("Button")?.includes("cp-compare-score--good"));
        assert.ok(classOf("Card")?.includes("cp-compare-score--bad"));
    });

    it("puts the WORST rows where the wall is read", async () => {
        // The page exists to find what is wrong; the rows that are wrong have to be on screen
        // without scrolling.
        stubScorer({ "/a/Button-svg-light": 93.4, "/a/Card-svg-light": 61 });
        await mount();
        await settle();
        assert.deepEqual(
            rowsOf().map((r) => r.getAttribute("data-label")),
            ["Card", "Button"],
        );
    });

    it("leads with a row it could not score at all", async () => {
        // An unmeasurable pair outranks every measured one.
        stubScorer({ "/a/Button-svg-light": 20 });
        await mount();
        await settle();
        assert.equal(scoreTextOf("Card"), "unavailable");
        assert.deepEqual(
            rowsOf().map((r) => r.getAttribute("data-label")),
            ["Card", "Button"],
        );
    });

    it("reads the scorer LATE, so the page's script order cannot silence it", async () => {
        // The components bundle loads before `format-compare.js`, so a handle cached at upgrade
        // would be null.
        await mount();
        await flush();
        stubScorer({ "/a/Button-svg-light": 88, "/a/Card-svg-light": 88 });
        document
            .querySelector<HTMLElement>('[data-compare-format="svg"]')!
            .click();
        await settle();
        assert.equal(scoreTextOf("Button"), "88.0%");
    });

    it("hides a row this format cannot pair, rather than scoring the wrong two pictures", async () => {
        stubScorer({ "/a/Button-svg-light": 90 });
        await mount({
            rows: [
                { name: "Button", have: ["png-light", "svg-light"] },
                { name: "Card", have: ["png-light"] },
            ],
        });
        await settle();
        assert.equal(
            document.querySelector<HTMLElement>('[data-label="Card"]')!.hidden,
            true,
        );
        assert.equal(count(), "1 comparison");
    });

    it("narrows on the search box, and says how many are left", async () => {
        stubScorer({ "/a/Button-svg-light": 90, "/a/Card-svg-light": 90 });
        await mount();
        await settle();
        const search =
            document.querySelector<HTMLInputElement>("#cp-compare-search")!;
        search.value = "card";
        search.dispatchEvent(new Event("input"));
        await flush();
        assert.equal(count(), "1 comparison");
        assert.equal(
            document.querySelector<HTMLElement>('[data-label="Button"]')!
                .hidden,
            true,
        );
    });

    /** Rows carrying both themes, so a theme switch is a real re-run rather than an empty wall. */
    const bothThemes = [
        {
            name: "Button",
            have: ["png-light", "svg-light", "png-dark", "svg-dark"],
        },
        {
            name: "Card",
            have: ["png-light", "svg-light", "png-dark", "svg-dark"],
        },
    ];

    it("switches exact issue pills with the preview theme", async () => {
        stubScorer({
            "/a/Button-svg-light": 90,
            "/a/Button-svg-dark": 90,
        });
        await mount({ rows: [bothThemes[0]] });
        const cell = document.querySelector<HTMLElement>(
            '[data-label="Button"] .cp-compare-bugs',
        )!;
        cell.insertAdjacentHTML(
            "afterbegin",
            '<a id="light-issue" data-bug-scope="variant" data-bug-preview-ids="Button-light">light</a>' +
                '<a id="dark-issue" data-bug-scope="variant" data-bug-preview-ids="Button-dark" hidden>dark</a>' +
                '<a id="component-issue" data-bug-scope="component">component</a>',
        );

        document
            .querySelector<HTMLElement>('[data-compare-theme="dark"]')!
            .click();
        await settle();

        assert.equal(
            document.querySelector<HTMLElement>("#light-issue")!.hidden,
            true,
        );
        assert.equal(
            document.querySelector<HTMLElement>("#dark-issue")!.hidden,
            false,
        );
        assert.equal(
            document.querySelector<HTMLElement>("#component-issue")!.hidden,
            false,
        );
    });

    it("does not load the pictures of a row the filter hides", async () => {
        // Dressing assigns image sources, so it must follow filtering, or a single-row link decodes
        // the whole wall.
        stubScorer({
            "/a/Button-svg-light": 90,
            "/a/Card-svg-light": 90,
            "/a/Button-svg-dark": 90,
            "/a/Card-svg-dark": 90,
        });
        // Both themes on both rows, so the dark switch below has a pair to draw.
        await mount({ rows: bothThemes });
        await settle();

        const pngOf = (label: string) =>
            document
                .querySelector<HTMLElement>(`[data-label="${label}"]`)!
                .querySelector<HTMLImageElement>(".cp-compare-png")!;

        // Narrow first, then blank what the previous pass drew, so what appears below is what THIS
        // pass assigned rather than what was left over.
        const search =
            document.querySelector<HTMLInputElement>("#cp-compare-search")!;
        search.value = "card";
        search.dispatchEvent(new Event("input"));
        await flush();
        for (const label of ["Button", "Card"])
            pngOf(label).removeAttribute("src");

        // A theme switch is a full re-run: every row is redressed for the new pair.
        document
            .querySelector<HTMLElement>('[data-compare-theme="dark"]')!
            .click();
        await settle();

        assert.equal(count(), "1 comparison");
        assert.ok(
            pngOf("Card").getAttribute("src"),
            "the visible row is dressed",
        );
        assert.equal(
            pngOf("Button").getAttribute("src"),
            null,
            "the filtered-out row loads nothing",
        );
    });

    it("dresses a row a widened filter reveals, rather than showing it empty", async () => {
        // The other half of dressing lazily: a row the run left hidden was never dressed, so
        // clearing the search has to dress it on the way in or it appears with no pictures.
        stubScorer({
            "/a/Button-svg-light": 90,
            "/a/Card-svg-light": 90,
            "/a/Button-svg-dark": 90,
            "/a/Card-svg-dark": 90,
        });
        await mount({ rows: bothThemes });
        await settle();
        const pngOf = (label: string) =>
            document
                .querySelector<HTMLElement>(`[data-label="${label}"]`)!
                .querySelector<HTMLImageElement>(".cp-compare-png")!;
        const search =
            document.querySelector<HTMLInputElement>("#cp-compare-search")!;
        search.value = "card";
        search.dispatchEvent(new Event("input"));
        await flush();
        for (const label of ["Button", "Card"])
            pngOf(label).removeAttribute("src");
        document
            .querySelector<HTMLElement>('[data-compare-theme="dark"]')!
            .click();
        await settle();
        assert.equal(pngOf("Button").getAttribute("src"), null);

        search.value = "";
        search.dispatchEvent(new Event("input"));
        await flush();

        const button = document.querySelector<HTMLElement>(
            '[data-label="Button"]',
        )!;
        assert.equal(button.hidden, false);
        assert.ok(
            pngOf("Button").getAttribute("src"),
            "a revealed row carries its picture",
        );
        assert.equal(count(), "2 comparisons");
    });

    it("says so when the filter leaves nothing", async () => {
        stubScorer({ "/a/Button-svg-light": 90, "/a/Card-svg-light": 90 });
        await mount();
        await settle();
        const search =
            document.querySelector<HTMLInputElement>("#cp-compare-search")!;
        search.value = "nothing-matches-this";
        search.dispatchEvent(new Event("input"));
        await flush();
        assert.equal(count(), "0 comparisons");
        assert.equal(
            document.getElementById("cp-compare-empty")!.hidden,
            false,
        );
    });

    it("hands the filter to the published wall and stops, rather than running behind it", async () => {
        // The lane wall's numbers are offline; the client table must not keep decoding behind it.
        const filtered: string[] = [];
        (window as Record<string, unknown>).cpRcLanes = {
            filter: (q: string) => filtered.push(q),
        };
        const scorer = stubScorer({ "/a/Button-svg-light": 90 });
        await mount({
            available: 'data-has-svg="1" data-has-rc="1"',
            lanes: true,
        });
        await settle();
        const before = scorer.calls.length;
        document
            .querySelector<HTMLElement>('[data-compare-format="rc"]')!
            .click();
        await settle();
        assert.equal(
            document.getElementById("cp-compare-formats")!.hidden,
            true,
        );
        assert.equal(document.getElementById("cp-rc-lanes")!.hidden, false);
        assert.equal(scorer.calls.length, before, "no rows scored behind it");
        assert.equal(filtered.length > 0, true);
        delete (window as Record<string, unknown>).cpRcLanes;
    });

    it("does not let a slow lane write its scores over a newer one's", async () => {
        // A lane switch mid-run leaves the old lane's promises in flight; the generation counter
        // keeps them off the new lane's rows.
        const scorer = stubScorer(
            { "/a/Button-svg-light": 11, "/a/Card-svg-light": 11 },
            { hold: true },
        );
        await mount({ available: 'data-has-svg="1" data-has-reference="1"' });
        await flush();
        document
            .querySelector<HTMLElement>('[data-compare-format="reference"]')!
            .click();
        await flush();
        scorer.settle();
        await settle();
        assert.notEqual(
            scoreTextOf("Button"),
            "11.0%",
            "the abandoned SVG run must not land",
        );
    });

    it("paints the middle diff map, and only in the reference lane", async () => {
        // Only the reference lane compares independent artwork, so only it has a "which pixels
        // moved" map.
        const scorer = stubScorer({ "/a/Button-reference-light": 80 });
        await mount({
            available: 'data-has-svg="1" data-has-reference="1"',
            rows: [
                {
                    name: "Button",
                    have: ["png-light", "reference-light", "svg-light"],
                },
            ],
        });
        await settle();
        assert.deepEqual(scorer.painted, [], "the SVG lane has nothing to map");

        document
            .querySelector<HTMLElement>('[data-compare-format="reference"]')!
            .click();
        await settle();
        const diff = document.querySelector<HTMLCanvasElement>(
            '[data-label="Button"] .cp-compare-diff',
        )!;
        assert.equal(scorer.painted.length, 1);
        assert.notEqual(
            scorer.painted[0],
            diff,
            "the scorer must paint its own canvas, never the row's",
        );
        assert.equal(diff.width, 8);
        assert.equal(scoreTextOf("Button"), "80.0%");
    });

    it("scores a parallel implementation as a raster pair and paints its diff", async () => {
        const scorer = stubScorer({ "/a/Button-parallel-light": 72.5 });
        await mount({
            available: 'data-has-svg="1" data-has-parallel="1"',
            rows: [
                {
                    name: "Button",
                    have: ["png-light", "parallel-light", "svg-light"],
                },
            ],
        });
        document
            .querySelector<HTMLElement>('[data-compare-format="parallel"]')!
            .click();
        await settle();

        const diff = document.querySelector<HTMLCanvasElement>(
            '[data-label="Button"] .cp-compare-diff',
        )!;
        assert.equal(scoreTextOf("Button"), "72.5%");
        assert.equal(diff.width, 8);
        assert.equal(scorer.calls.at(-1), "/a/Button-parallel-light");
    });

    it("clears the map rather than leaving the last lane's magenta standing", async () => {
        // A stale delta map beside a freshly-scored row reads as a finding. It has to go the moment
        // the row is re-run, not once something else happens to repaint it.
        stubScorer({
            "/a/Button-reference-light": 80,
            "/a/Button-svg-light": 93.4,
        });
        await mount({
            available: 'data-has-svg="1" data-has-reference="1"',
            rows: [
                {
                    name: "Button",
                    have: ["png-light", "reference-light", "svg-light"],
                },
            ],
        });
        document
            .querySelector<HTMLElement>('[data-compare-format="reference"]')!
            .click();
        await settle();
        const diff = document.querySelector<HTMLCanvasElement>(
            '[data-label="Button"] .cp-compare-diff',
        )!;
        assert.equal(diff.width, 8);

        document
            .querySelector<HTMLElement>('[data-compare-format="svg"]')!
            .click();
        await settle();
        assert.equal(diff.width, 0);
    });

    it("does not let an abandoned lane's map land on the row", async () => {
        // The map must be generation-guarded too, or an abandoned run repaints a canvas the new run
        // finished.
        window.history.replaceState(null, "", "/compare?format=reference");
        const scorer = stubScorer(
            {
                "/a/Button-reference-light": 80,
                "/a/Button-svg-light": 93.4,
            },
            { holdReference: true },
        );
        await mount({
            available: 'data-has-svg="1" data-has-reference="1"',
            rows: [
                {
                    name: "Button",
                    have: ["png-light", "reference-light", "svg-light"],
                },
            ],
        });
        await flush();
        const diff = document.querySelector<HTMLCanvasElement>(
            '[data-label="Button"] .cp-compare-diff',
        )!;

        document
            .querySelector<HTMLElement>('[data-compare-format="svg"]')!
            .click();
        await settle();
        assert.equal(scoreTextOf("Button"), "93.4%");

        // The reference run now finishes, into a lane nobody is looking at.
        scorer.settle();
        await settle();
        assert.equal(
            diff.width,
            0,
            "the abandoned run must not repaint the row",
        );
        assert.equal(scoreTextOf("Button"), "93.4%");
    });

    it("stops an abandoned chain before it resets the rows behind it", async () => {
        // A stale chain keeps walking its rows even after its results are dropped; guarding only
        // results let it wipe rows (src, "comparing…", blank map) and never refill them.
        window.history.replaceState(null, "", "/compare?format=reference");
        const scorer = stubScorer(
            {
                "/a/Button-reference-light": 80,
                "/a/Card-reference-light": 60,
                "/a/Button-svg-light": 93.4,
                "/a/Card-svg-light": 88.1,
            },
            { holdReference: true },
        );
        await mount({
            available: 'data-has-svg="1" data-has-reference="1"',
            rows: [
                {
                    name: "Button",
                    have: ["png-light", "reference-light", "svg-light"],
                },
                {
                    name: "Card",
                    have: ["png-light", "reference-light", "svg-light"],
                },
            ],
        });
        await flush();

        // The reference chain is parked on its FIRST row, so it has not reached Card yet.
        document
            .querySelector<HTMLElement>('[data-compare-format="svg"]')!
            .click();
        await settle();
        assert.equal(scoreTextOf("Button"), "93.4%");
        assert.equal(scoreTextOf("Card"), "88.1%");

        // Released, the abandoned chain now walks on to Card — a row the visitor is reading.
        scorer.settle();
        await settle();
        assert.equal(scoreTextOf("Card"), "88.1%");
        assert.equal(
            document.querySelector<HTMLCanvasElement>(
                '[data-label="Card"] .cp-compare-diff',
            )!.width,
            0,
        );
    });

    it("bounds the map it keeps to what the column can draw", async () => {
        // Full-resolution references normalise to frames far larger than the 200px column, and the
        // wall keeps one per row, so maps are bounded (and oversized frames would fail).
        const scorer = stubScorer(
            { "/a/Button-reference-light": 80 },
            { map: { width: 1600, height: 2400 } },
        );
        window.history.replaceState(null, "", "/compare?format=reference");
        await mount({
            available: 'data-has-svg="1" data-has-reference="1"',
            rows: [
                {
                    name: "Button",
                    have: ["png-light", "reference-light", "svg-light"],
                },
            ],
        });
        await settle();
        const diff = document.querySelector<HTMLCanvasElement>(
            '[data-label="Button"] .cp-compare-diff',
        )!;
        assert.equal(diff.height, 440);
        // Bounded, not squashed: a map drawn at the wrong proportion would misreport where the two
        // drawings disagree, which is the one thing this column exists to say.
        assert.equal(diff.width, Math.round((1600 / 2400) * 440));
        // The bound is requested up front, not applied after normalising at full size.
        assert.deepEqual(scorer.bounds, [440]);
    });

    it("opens the Reference / Diff / Actual page from the map as well as the render", async () => {
        // Two panels of the same triptych; clicking the one that shows the problem should not be
        // the click that does nothing.
        stubScorer({ "/a/Button-reference-light": 80 });
        window.history.replaceState(null, "", "/compare?format=reference");
        await mount({
            available: 'data-has-svg="1" data-has-reference="1"',
            rows: [
                {
                    name: "Button",
                    have: [
                        "png-light",
                        "reference-light",
                        "reference-detail-light",
                        "svg-light",
                    ],
                },
            ],
        });
        await settle();
        const diff = document.querySelector<HTMLCanvasElement>(
            '[data-label="Button"] .cp-compare-diff',
        )!;
        assert.equal(diff.title, "Open Reference / Diff / Actual");
        assert.equal(typeof diff.onclick, "function");
    });

    it("opens on the format a deep link names", async () => {
        window.history.replaceState(null, "", "/compare?format=reference");
        stubScorer({ "/a/Button-reference-light": 80 });
        await mount({
            available: 'data-has-svg="1" data-has-reference="1"',
            rows: [
                {
                    name: "Button",
                    have: ["png-light", "reference-light", "svg-light"],
                },
            ],
        });
        await settle();
        assert.equal(
            document
                .querySelector('[data-compare-format="reference"]')
                ?.getAttribute("aria-pressed"),
            "true",
        );
    });

    it("ignores a format this catalog publishes nothing for", async () => {
        // Not an empty table: that reads as "nothing matches your filter", which is a different and
        // wrong answer to a link naming a lane this catalog simply does not have.
        window.history.replaceState(null, "", "/compare?format=rc");
        stubScorer({ "/a/Button-svg-light": 90, "/a/Card-svg-light": 90 });
        await mount();
        await settle();
        assert.equal(
            document
                .querySelector('[data-compare-format="svg"]')
                ?.getAttribute("aria-pressed"),
            "true",
        );
        assert.equal(count(), "2 comparisons");
    });

    it("gives a format pick its own history entry, and typing none", async () => {
        // A discrete pick is a Back target, a keystroke isn't. Both go through `cpUrlState`, which
        // preserves session keys.
        const pushed: Array<Record<string, string>> = [];
        const replaced: Array<Record<string, string>> = [];
        (window as Record<string, unknown>).cpUrlState = {
            push: (v: Record<string, string>) => pushed.push(v),
            replace: (v: Record<string, string>) => replaced.push(v),
            onPop: () => () => {},
        };
        stubScorer({ "/a/Button-svg-light": 90, "/a/Card-svg-light": 90 });
        await mount({ available: 'data-has-svg="1" data-has-reference="1"' });
        await settle();

        document
            .querySelector<HTMLElement>('[data-compare-format="reference"]')!
            .click();
        assert.deepEqual(pushed, [{ format: "reference" }]);

        document
            .querySelector<HTMLElement>('[data-compare-theme="dark"]')!
            .click();
        assert.deepEqual(pushed[1], { theme: "dark" });

        const search =
            document.querySelector<HTMLInputElement>("#cp-compare-search")!;
        search.value = "  card  ";
        search.dispatchEvent(new Event("input"));
        assert.deepEqual(replaced, [{ q: "card" }]);
        assert.equal(pushed.length, 2, "typing pushed nothing");
        delete (window as Record<string, unknown>).cpUrlState;
    });

    it("remembers the theme it was last compared in", async () => {
        const storage = stubStorage();
        stubScorer({ "/a/Button-svg-light": 90, "/a/Card-svg-light": 90 });
        await mount();
        await settle();
        document
            .querySelector<HTMLElement>('[data-compare-theme="dark"]')!
            .click();
        assert.equal(storage.get("cp-compare-theme"), "dark");
    });

    it("repaints the page's chrome for the theme being compared, on a pick AND on Back", async () => {
        // Every pop path restores the theme by assigning the control's value (no `change` event),
        // so each must hand the restored choice on itself.
        const followed: string[] = [];
        let popHandler = () => {};
        (window as Record<string, unknown>).cpPageTheme = {
            follow: (t: string) => followed.push(t),
        };
        (window as Record<string, unknown>).cpUrlState = {
            push: () => {},
            replace: () => {},
            onPop: (fn: () => void) => {
                popHandler = fn;
                return () => {};
            },
        };
        stubScorer({ "/a/Button-svg-light": 90, "/a/Card-svg-light": 90 });
        await mount();
        await settle();

        document
            .querySelector<HTMLElement>('[data-compare-theme="dark"]')!
            .click();
        assert.deepEqual(followed, ["dark"], "a pick repaints the chrome");

        window.history.replaceState(null, "", "/compare?theme=light");
        popHandler();
        assert.deepEqual(
            followed,
            ["dark", "light"],
            "and so does the entry Back restores",
        );
        delete (window as Record<string, unknown>).cpPageTheme;
        delete (window as Record<string, unknown>).cpUrlState;
    });

    it("fixes the RC player's theme and fonts BEFORE it measures", async () => {
        // An ordering invariant: the explicit artifact theme first (not the OS preference), then
        // the first paint (which discovers named fonts), then `fontsReady`, then a repaint, then
        // measure.
        const order: string[] = [];
        (window as Record<string, unknown>).RC = {
            RcdPlayer: class {
                setTheme(t: string) {
                    order.push(`setTheme:${t}`);
                }
                async loadFromArrayBuffer() {
                    order.push("load");
                }
                repaint() {
                    order.push("repaint");
                }
                async fontsReady() {
                    order.push("fontsReady");
                }
            },
        };
        // Saved and restored, not deleted: mocha shares one process, and `rcFonts.test.ts` later
        // asserts that the real module registered itself on this very property.
        const priorFonts = (window as Record<string, unknown>).cpRcFonts;
        (window as Record<string, unknown>).cpRcFonts = {
            ready: async () => order.push("pageFonts"),
        };
        globalThis.fetch = (async () => ({
            ok: true,
            arrayBuffer: async () => new ArrayBuffer(8),
        })) as unknown as typeof fetch;
        globalThis.requestAnimationFrame = ((fn: () => void) => {
            setTimeout(fn, 0);
            return 0;
        }) as unknown as typeof requestAnimationFrame;

        const scorer = stubScorer({});
        await mount({
            available: 'data-has-rc="1"',
            rows: [{ name: "Button", have: ["png-light", "rc-light"] }],
        });
        await settle();
        void scorer;

        assert.deepEqual(order.slice(order.indexOf("setTheme:light")), [
            "setTheme:light",
            "load",
            "repaint",
            "fontsReady",
            "repaint",
        ]);
        delete (window as Record<string, unknown>).RC;
        (window as Record<string, unknown>).cpRcFonts = priorFonts;
    });

    it("keeps the baseline on the left on every lane, and only renames the header", async () => {
        // One order on every lane: baseline · diff · ours. A document in the old order is
        // normalised on arrival (`COMPARE_NAVIGATION.md` F3).
        stubScorer({ "/a/Button-reference-light": 80 });
        document.body.innerHTML = `
          <cp-compare-wall></cp-compare-wall>
          <div id="cp-compare" data-default-format="svg" data-default-theme="light"
               data-theme-key="cp-compare-theme" data-has-svg="1" data-has-reference="1"
               data-reference-label="Figma">
            <button data-compare-format="svg">SVG</button>
            <button data-compare-format="reference">Figma</button>
            <div id="cp-compare-formats"><table>
              <thead><tr><th>Preview</th>
                <th class="cp-compare-render-head">Rendered PNG</th>
                <th class="cp-compare-target-head">SVG</th>
                <th>Match</th></tr></thead>
              <tbody>
                <tr class="cp-compare-row" data-label="Button" data-hay="button"
                    data-preview-ids="com.example.ButtonPreview"
                    data-png-light="/a/Button-png-light" data-svg-light="/a/Button-svg-light"
                    data-reference-light="/a/Button-reference-light">
                  <th scope="row">Button</th>
                  <td class="cp-compare-render-cell"><img class="cp-compare-png" alt=""></td>
                  <td class="cp-compare-target-cell"><img class="cp-compare-vector" alt=""><canvas></canvas></td>
                  <td class="cp-compare-score"></td>
                </tr>
              </tbody>
            </table></div>
          </div>`;
        await flush();
        await settle();

        const headOrder = () =>
            Array.from(document.querySelectorAll("thead th"))
                .map((th) => th.className)
                .filter(Boolean);
        const cellOrder = () =>
            Array.from(
                document.querySelectorAll(
                    ".cp-compare-row .cp-compare-render-cell, .cp-compare-row .cp-compare-target-cell",
                ),
            ).map((td) => td.className);
        const headText = () =>
            document.querySelector(".cp-compare-target-head")?.textContent;

        const baselineFirstHeads = [
            "cp-compare-target-head",
            "cp-compare-render-head",
        ];
        const baselineFirstCells = [
            "cp-compare-target-cell",
            "cp-compare-render-cell",
        ];

        assert.deepEqual(headOrder(), baselineFirstHeads);
        assert.deepEqual(cellOrder(), baselineFirstCells);
        assert.equal(headText(), "SVG");

        document
            .querySelector<HTMLElement>('[data-compare-format="reference"]')!
            .click();
        await settle();
        assert.deepEqual(headOrder(), baselineFirstHeads);
        assert.deepEqual(cellOrder(), baselineFirstCells);
        // Named for the lane it is showing — a header still reading "SVG" over the Figma column
        // would say the pair is the other way round.
        assert.equal(headText(), "Figma");

        document
            .querySelector<HTMLElement>('[data-compare-format="svg"]')!
            .click();
        await settle();
        assert.deepEqual(headOrder(), baselineFirstHeads);
        assert.deepEqual(cellOrder(), baselineFirstCells);
        assert.equal(headText(), "SVG");
    });

    it("keeps the delta map between the pair when the columns swap", async () => {
        // The map must stay between the two pictures when the pair is reordered.
        stubScorer({ "/a/Button-reference-light": 80 });
        document.body.innerHTML = `
          <cp-compare-wall></cp-compare-wall>
          <div id="cp-compare" data-default-format="svg" data-default-theme="light"
               data-theme-key="cp-compare-theme" data-has-svg="1" data-has-reference="1"
               data-reference-label="Figma">
            <button data-compare-format="svg">SVG</button>
            <button data-compare-format="reference">Figma</button>
            <div id="cp-compare-formats"><table>
              <thead><tr><th>Preview</th>
                <th class="cp-compare-render-head">Rendered PNG</th>
                <th class="cp-compare-diff-head">Diff</th>
                <th class="cp-compare-target-head">SVG</th>
                <th>Match</th></tr></thead>
              <tbody>
                <tr class="cp-compare-row" data-label="Button" data-hay="button"
                    data-preview-ids="com.example.ButtonPreview"
                    data-png-light="/a/Button-png-light" data-svg-light="/a/Button-svg-light"
                    data-reference-light="/a/Button-reference-light">
                  <th scope="row">Button</th>
                  <td class="cp-compare-render-cell"><img class="cp-compare-png" alt=""></td>
                  <td class="cp-compare-diff-cell"><canvas class="cp-compare-diff"></canvas></td>
                  <td class="cp-compare-target-cell"><img class="cp-compare-vector" alt=""><canvas class="cp-compare-rc" hidden></canvas></td>
                  <td class="cp-compare-score"></td>
                </tr>
              </tbody>
            </table></div>
          </div>`;
        await flush();
        await settle();

        const pictures = (selector: string) =>
            Array.from(document.querySelectorAll(selector))
                .map((node) => node.className)
                .filter((name) => /render|diff|target/.test(name));
        const heads = () => pictures("thead th");
        const cells = () => pictures(".cp-compare-row td");

        const orderedHeads = [
            "cp-compare-target-head",
            "cp-compare-diff-head",
            "cp-compare-render-head",
        ];
        const orderedCells = [
            "cp-compare-target-cell",
            "cp-compare-diff-cell",
            "cp-compare-render-cell",
        ];

        assert.deepEqual(heads(), orderedHeads);
        assert.deepEqual(cells(), orderedCells);

        document
            .querySelector<HTMLElement>('[data-compare-format="reference"]')!
            .click();
        await settle();
        assert.deepEqual(heads(), orderedHeads);
        assert.deepEqual(cells(), orderedCells);

        document
            .querySelector<HTMLElement>('[data-compare-format="svg"]')!
            .click();
        await settle();
        assert.deepEqual(heads(), orderedHeads);
        assert.deepEqual(cells(), orderedCells);
    });

    it("paints every row's pictures before it has scored any of them", async () => {
        // Picture sources are set before scoring, so the wall draws immediately rather than one row
        // per completed comparison.
        stubScorer(
            { "/a/Button-svg-light": 90, "/a/Card-svg-light": 90 },
            { hold: true },
        );
        await mount();
        await flush();
        const painted = rowsOf().map(
            (row) =>
                row.querySelector<HTMLImageElement>(".cp-compare-png")?.src ??
                "",
        );
        assert.equal(painted.length, 2);
        assert.ok(
            painted.every((src) => src.includes("png-light")),
            `every row points at its render while the first is still scoring: ${painted}`,
        );
    });

    it("shows the published score before it has measured anything", async () => {
        // Published scores seed the wall before any measurement.
        window.history.replaceState(null, "", "/compare?format=reference");
        const scorer = stubScorer(
            { "/a/Button-reference-light": 62 },
            { holdReference: true },
        );
        await mount({
            available: 'data-has-reference="1"',
            rows: [
                {
                    name: "Button",
                    have: ["png-light", "reference-light"],
                    attrs: 'data-match-light="61.80"',
                },
            ],
        });
        await flush();
        assert.equal(scoreTextOf("Button"), "61.8%");
        const score = document.querySelector(
            '[data-label="Button"] .cp-compare-score',
        )!;
        assert.equal(score.getAttribute("data-score-source"), "published");
        assert.ok(score.className.includes("cp-compare-score--bad"));

        // …and the browser's own measurement replaces it, which is the whole point of still
        // taking one.
        scorer.settle();
        await settle();
        assert.equal(scoreTextOf("Button"), "62.0%");
        assert.equal(score.getAttribute("data-score-source"), null);
    });

    it("orders the wall on the published scores before a single row is measured", async () => {
        window.history.replaceState(null, "", "/compare?format=reference");
        stubScorer(
            {
                "/a/Button-reference-light": 95,
                "/a/Card-reference-light": 40,
            },
            { holdReference: true },
        );
        await mount({
            available: 'data-has-reference="1"',
            rows: [
                {
                    name: "Button",
                    have: ["png-light", "reference-light"],
                    attrs: 'data-match-light="95"',
                },
                {
                    name: "Card",
                    have: ["png-light", "reference-light"],
                    attrs: 'data-match-light="40"',
                },
            ],
        });
        await flush();
        assert.deepEqual(
            rowsOf().map((r) => r.getAttribute("data-label")),
            ["Card", "Button"],
        );
    });

    it("leaves a row with no published score where the server put it", async () => {
        // Unscored rows must not lead the wall the way unmeasurable rows do.
        window.history.replaceState(null, "", "/compare?format=reference");
        stubScorer({}, { holdReference: true });
        await mount({
            available: 'data-has-reference="1"',
            rows: [
                {
                    name: "Button",
                    have: ["png-light", "reference-light"],
                    attrs: 'data-match-light="70"',
                },
                { name: "Card", have: ["png-light", "reference-light"] },
            ],
        });
        await flush();
        assert.deepEqual(
            rowsOf().map((r) => r.getAttribute("data-label")),
            ["Button", "Card"],
        );
        assert.equal(
            document
                .querySelector<HTMLElement>('[data-label="Card"]')!
                .hasAttribute("data-score"),
            false,
            "nothing to seed it with",
        );
    });

    it("does not seed the vector lanes from a score about another comparison", async () => {
        // `data-match-light` describes render-vs-design, so it must not seed the SVG lane.
        stubScorer({ "/a/Button-svg-light": 88 }, { hold: true });
        await mount({
            rows: [
                {
                    name: "Button",
                    have: ["png-light", "svg-light", "reference-light"],
                    attrs: 'data-match-light="12"',
                },
            ],
        });
        await flush();
        assert.equal(scoreTextOf("Button"), "comparing…");
    });

    it("keeps a published score when this browser cannot measure the pair", async () => {
        // A browser-side failure doesn't erase a published score.
        window.history.replaceState(null, "", "/compare?format=reference");
        (window as Record<string, unknown>).ComposePreviewCompare = undefined;
        await mount({
            available: 'data-has-reference="1"',
            rows: [
                {
                    name: "Button",
                    have: ["png-light", "reference-light"],
                    attrs: 'data-match-light="88.5"',
                },
            ],
        });
        await settle();
        assert.equal(scoreTextOf("Button"), "88.5%");
    });

    it("points '+ file' at the pair the row is actually showing", async () => {
        stubScorer({ "/a/Button-reference-light": 80 });
        await mount({
            available: 'data-has-svg="1" data-has-reference="1"',
            rows: [
                {
                    name: "Button",
                    have: [
                        "png-light",
                        "svg-light",
                        "reference-light",
                        "reference-detail-light",
                    ],
                },
            ],
        });
        await settle();
        const link = () =>
            document.querySelector<HTMLAnchorElement>(
                '[data-label="Button"] .cp-compare-bug-new',
            )!;
        // The SVG lane focuses no pair, so the report is the viewer's own.
        assert.ok(link().getAttribute("href")?.endsWith("/p/Button#cp-report"));

        document
            .querySelector<HTMLElement>('[data-compare-format="reference"]')!
            .click();
        await settle();
        assert.ok(
            link()
                .getAttribute("href")
                ?.endsWith("/a/Button-reference-detail-light"),
        );
    });

    it("stays inert on a page that is not the compare wall", async () => {
        document.body.innerHTML = `<cp-compare-wall></cp-compare-wall><div class="cp-grid"></div>`;
        await flush();
        assert.equal(document.querySelector(".cp-compare-row"), null);
    });

    // happy-dom has no `IntersectionObserver`, so the tests above exercise the measure-everything
    // fallback; these cover a browser that can report visibility.
    describe("with a viewport to measure against", () => {
        let realObserver: typeof IntersectionObserver;
        let observed: Set<Element>;
        let notify: ((targets: Element[]) => void) | null;

        class StubObserver {
            constructor(callback: IntersectionObserverCallback) {
                notify = (targets) =>
                    callback(
                        targets.map((target) => ({
                            target,
                            isIntersecting: true,
                        })) as never,
                        this as never,
                    );
            }
            observe(target: Element): void {
                observed.add(target);
            }
            unobserve(target: Element): void {
                observed.delete(target);
            }
            disconnect(): void {
                observed.clear();
            }
        }

        const scroll = async (...names: string[]) => {
            notify?.(
                names.map((name) =>
                    document.querySelector(`[data-label="${name}"]`)!,
                ),
            );
            await settle();
        };

        const three = [
            { name: "Button", have: ["png-light", "svg-light"] },
            { name: "Card", have: ["png-light", "svg-light"] },
            { name: "Chip", have: ["png-light", "svg-light"] },
        ];

        beforeEach(() => {
            observed = new Set();
            notify = null;
            realObserver = globalThis.IntersectionObserver;
            globalThis.IntersectionObserver = StubObserver as never;
        });

        afterEach(() => {
            globalThis.IntersectionObserver = realObserver;
        });

        it("measures nothing until a row comes near the viewport", async () => {
            const scorer = stubScorer({ "/a/Button-svg-light": 93.4 });
            await mount({ rows: three });
            await settle();
            assert.deepEqual(
                scorer.calls,
                [],
                "nothing on screen, nothing scored",
            );
            assert.equal(observed.size, 3, "every visible row is watched for");
        });

        it("measures a row when it arrives, and only that row", async () => {
            const scorer = stubScorer({
                "/a/Button-svg-light": 93.4,
                "/a/Card-svg-light": 61,
                "/a/Chip-svg-light": 12,
            });
            await mount({ rows: three });
            await scroll("Card");
            assert.deepEqual(scorer.calls, ["/a/Card-svg-light"]);
            assert.equal(scoreTextOf("Card"), "61.0%");
            // Left exactly as the server wrote it — no measurement, and none started.
            assert.equal(
                document
                    .querySelector('[data-label="Button"]')
                    ?.hasAttribute("data-score"),
                false,
            );
        });

        it("measures a row once however often it is reported on screen", async () => {
            const scorer = stubScorer({ "/a/Card-svg-light": 61 });
            await mount({ rows: three });
            await scroll("Card");
            await scroll("Card");
            assert.deepEqual(
                scorer.calls,
                ["/a/Card-svg-light"],
                "a second sighting is not a second comparison",
            );
        });

        it("holds the served order until every row has been measured", async () => {
            stubScorer({
                "/a/Button-svg-light": 93.4,
                "/a/Card-svg-light": 61,
                "/a/Chip-svg-light": 12,
            });
            await mount({ rows: three });
            await scroll("Button", "Card");
            assert.deepEqual(
                rowsOf().map((row) => row.getAttribute("data-label")),
                ["Button", "Card", "Chip"],
                "a partial pass must not move rows under the reader",
            );
            await scroll("Chip");
            assert.deepEqual(
                rowsOf().map((row) => row.getAttribute("data-label")),
                ["Chip", "Card", "Button"],
                "worst first, once the whole wall has a measured number",
            );
        });

        it("stops watching the previous lane's rows when the lane changes", async () => {
            stubScorer({ "/a/Button-svg-light": 93.4 });
            await mount({ rows: three });
            const first = notify;
            document
                .querySelector<HTMLElement>('[data-compare-format="svg"]')!
                .click();
            await settle();
            assert.notEqual(notify, first, "a run installs its own watch");
            assert.equal(
                observed.size,
                3,
                "and the superseded one has let go of its rows",
            );
        });
    });
});
