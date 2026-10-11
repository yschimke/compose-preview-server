// What the `/compare` wall shows, which artifacts it pairs, and where each choice came from.
// Failures here are silent: a wrong pairing yields a confident number about the wrong pictures.

import assert from "node:assert/strict";
import { grade } from "../src/compare/grade.js";
import {
    rowTheme,
    supportsFormat,
    variantFor,
    type Available,
} from "../src/compare/pairing.js";
import { initialState, poppedState, themeOf } from "../src/compare/state.js";
import {
    bakedScoreOf,
    byWorstFirst,
    byWorstKnownFirst,
    countLabel,
    keepRow,
    scoreOf,
} from "../src/compare/wallRows.js";

const ALL: Available = { svg: true, rc: true, reference: true, parallel: true };
const SVG_ONLY: Available = {
    svg: true,
    rc: false,
    reference: false,
    parallel: false,
};

/** A row that carries exactly the listed `<kind>-<variant>` artifacts. */
const row = (have: string[]) => (kind: string, variant: string) =>
    have.includes(`${kind}-${variant}`) ? `/a/${kind}-${variant}.png` : "";

describe("grade", () => {
    it("bands on the wall's own thresholds", () => {
        assert.equal(grade(100), "good");
        assert.equal(grade(90), "good");
        assert.equal(grade(89.99), "warn");
        assert.equal(grade(75), "warn");
        assert.equal(grade(74.99), "bad");
        assert.equal(grade(0), "bad");
    });

    it("is looser than the spec lane's, deliberately", () => {
        // Three readings of one metric that must not be unified: the wall triages many rows, the
        // spec lane judges one chosen pair.
        assert.equal(
            grade(98),
            "good",
            "the spec lane would call this 'close'",
        );
    });
});

describe("supportsFormat", () => {
    it("takes only a format this page has artifacts for", () => {
        assert.equal(supportsFormat("svg", ALL), true);
        assert.equal(supportsFormat("rc", SVG_ONLY), false);
        assert.equal(supportsFormat("reference", SVG_ONLY), false);
        assert.equal(supportsFormat("parallel", ALL), true);
    });

    it("refuses anything that is not a format at all", () => {
        // `?format=` is visitor-controlled.
        assert.equal(supportsFormat("nonsense", ALL), false);
        assert.equal(supportsFormat(null, ALL), false);
        assert.equal(supportsFormat("", ALL), false);
    });
});

describe("variantFor", () => {
    it("pairs in the theme being compared when the row has it", () => {
        assert.equal(
            variantFor(
                row(["png-dark", "svg-dark", "png-light", "svg-light"]),
                "svg",
                "dark",
            ),
            "dark",
        );
    });

    it("NEVER substitutes the opposite baked theme", () => {
        // A dark PNG beside a light vector scores a meaningless number, reporting a pairing mistake
        // as a fidelity problem.
        assert.equal(
            variantFor(row(["png-light", "svg-light"]), "svg", "dark"),
            "",
        );
        assert.equal(
            variantFor(row(["png-dark", "svg-dark"]), "svg", "light"),
            "",
        );
    });

    it("falls back to a theme-neutral pairing under either theme", () => {
        // For a neutral component there is only one artifact and it is the right one.
        const neutral = row(["png-neutral", "svg-neutral"]);
        assert.equal(variantFor(neutral, "svg", "dark"), "neutral");
        assert.equal(variantFor(neutral, "svg", "light"), "neutral");
    });

    it("needs BOTH halves before it will pair anything", () => {
        // Half a pair is not a comparison.
        assert.equal(variantFor(row(["png-light"]), "svg", "light"), "");
        assert.equal(variantFor(row(["svg-light"]), "svg", "light"), "");
    });

    it("is asked per format, so a row can pair in one lane and not another", () => {
        const svgOnly = row(["png-light", "svg-light"]);
        assert.equal(variantFor(svgOnly, "svg", "light"), "light");
        assert.equal(variantFor(svgOnly, "reference", "light"), "");
    });
});

describe("rowTheme", () => {
    it("takes a genuinely themed pairing at its word, whatever the stage", () => {
        assert.equal(rowTheme("dark"), "dark");
        assert.equal(rowTheme("light"), "light");
        assert.equal(rowTheme("dark", "light"), "dark");
        assert.equal(rowTheme("light", "dark"), "light");
    });

    it("defaults an unthemed pairing to light, as before", () => {
        assert.equal(rowTheme("neutral"), "light");
        assert.equal(rowTheme(""), "light");
        assert.equal(rowTheme("neutral", "light"), "light");
    });

    it("puts an unthemed pairing on a dark-first catalog's own stage", () => {
        // wear-m3-catalog#56: a theme-neutral component in a dark-first system is still drawn for a
        // black watch face, so answering "light" here left its sticker nearly blank in the table.
        assert.equal(rowTheme("neutral", "dark"), "dark");
        assert.equal(rowTheme("", "dark"), "dark");
    });

    it("lets a preview's own declared ground outrank both", () => {
        // A preview's own declared ground overrides the catalog default.
        assert.equal(rowTheme("neutral", "dark", "light"), "light");
        assert.equal(rowTheme("neutral", "light", "dark"), "dark");
        assert.equal(rowTheme("dark", "dark", "light"), "light");
    });

    it("ignores an absent or unusable declaration", () => {
        assert.equal(rowTheme("neutral", "dark", null), "dark");
        assert.equal(rowTheme("neutral", "dark", undefined), "dark");
        assert.equal(rowTheme("neutral", "dark", ""), "dark");
    });
});

describe("initialState", () => {
    const defaults = { format: "svg", theme: "light" };

    it("opens on the page's defaults when nothing else asks", () => {
        const state = initialState({
            defaults,
            remembered: null,
            params: new URLSearchParams(),
            available: ALL,
        });
        assert.deepEqual(state, { format: "svg", theme: "light", query: "" });
    });

    it("lets the URL pick a format the page can actually show", () => {
        const state = initialState({
            defaults,
            remembered: null,
            params: new URLSearchParams("format=reference"),
            available: ALL,
        });
        assert.equal(state.format, "reference");
    });

    it("ignores a format this catalog has nothing for, rather than emptying the wall", () => {
        // An empty table reads as "nothing matches your filter" — a different answer, and a wrong
        // one, for a link that simply names a lane this catalog does not publish.
        const state = initialState({
            defaults,
            remembered: null,
            params: new URLSearchParams("format=rc"),
            available: SVG_ONLY,
        });
        assert.equal(state.format, "svg");
    });

    it("remembers the theme this visitor last compared in", () => {
        const state = initialState({
            defaults,
            remembered: "dark",
            params: new URLSearchParams(),
            available: ALL,
        });
        assert.equal(state.theme, "dark");
    });

    it("lets an explicit ?theme= OUTRANK what was remembered", () => {
        // A `?theme=` in the address bar beats the remembered preference, so shared links show what
        // was sent.
        const state = initialState({
            defaults,
            remembered: "dark",
            params: new URLSearchParams("theme=light"),
            available: ALL,
        });
        assert.equal(state.theme, "light");
    });

    it("ignores a remembered value that is not a theme", () => {
        // `localStorage` is visitor-writable and survives across releases.
        const state = initialState({
            defaults,
            remembered: "chartreuse",
            params: new URLSearchParams(),
            available: ALL,
        });
        assert.equal(state.theme, "light");
    });

    it("carries the search box's text out of the URL", () => {
        const state = initialState({
            defaults,
            remembered: null,
            params: new URLSearchParams("q=button"),
            available: ALL,
        });
        assert.equal(state.query, "button");
    });
});

describe("poppedState", () => {
    const initial = {
        format: "reference" as const,
        theme: "dark" as const,
        query: "",
    };

    it("restores what a history entry names", () => {
        const state = poppedState({
            initial,
            params: new URLSearchParams("format=svg&theme=light&q=card"),
            available: ALL,
        });
        assert.deepEqual(state, {
            format: "svg",
            theme: "light",
            query: "card",
        });
    });

    it("falls back to what THIS LOAD resolved to, not the page's bare default", () => {
        // The parameterless entry predates any pick, so Back from a shared `?theme=dark` restores
        // that, not the default.
        const state = poppedState({
            initial,
            params: new URLSearchParams(),
            available: ALL,
        });
        assert.equal(state.format, "reference");
        assert.equal(state.theme, "dark");
    });

    it("clears the search box for an entry that names no query", () => {
        // Not "leaves it alone": the box is part of the state the entry describes, and a stale
        // filter after Back hides rows the entry says are showing.
        const state = poppedState({
            initial,
            params: new URLSearchParams("format=svg"),
            available: ALL,
        });
        assert.equal(state.query, "");
    });
});

describe("themeOf", () => {
    it("takes the two themes and nothing else", () => {
        assert.equal(themeOf("light"), "light");
        assert.equal(themeOf("dark"), "dark");
        assert.equal(themeOf("Dark"), null);
        assert.equal(themeOf(null), null);
    });
});

describe("keepRow", () => {
    const facts = {
        hay: "filled button · buttons",
        previewIds: "com.example.FilledButtonPreview",
        componentId: "Button",
        hasFormat: true,
    };

    it("shows a row this format can pair", () => {
        assert.equal(keepRow(facts, "", ""), true);
    });

    it("hides a row this format has nothing for", () => {
        assert.equal(keepRow({ ...facts, hasFormat: false }, "", ""), false);
    });

    it("matches the search box case-insensitively, on trimmed text", () => {
        assert.equal(keepRow(facts, "BUTTON", ""), true);
        assert.equal(keepRow(facts, "  button  ", ""), true);
        assert.equal(keepRow(facts, "slider", ""), false);
    });

    it("COMPOSES the ?preview= narrow with the search box", () => {
        // Arriving from the viewer for one preview and then typing narrows within that preview.
        assert.equal(
            keepRow(facts, "button", "com.example.FilledButtonPreview"),
            true,
        );
        assert.equal(
            keepRow(facts, "slider", "com.example.FilledButtonPreview"),
            false,
        );
        assert.equal(keepRow(facts, "button", "Slider"), false);
    });

    it("matches ?preview= as a whole id, not a substring", () => {
        const grouped = {
            ...facts,
            previewIds:
                "com.example.FilledButtonPreview com.example.IconPreview",
        };
        assert.equal(keepRow(grouped, "", "com.example.IconPreview"), true);
        assert.equal(keepRow(grouped, "", "example.IconPreview"), false);
        assert.equal(keepRow(grouped, "", "com.example.Icon"), false);
    });

    it("narrows to one component, and composes with the search box", () => {
        // `?component=` is an arrival scope covering every variant, and survives typing
        // (`docs/design/COMPARE_NAVIGATION.md`, F4).
        assert.equal(keepRow(facts, "", "", "Button"), true);
        assert.equal(keepRow(facts, "filled", "", "Button"), true);
        assert.equal(keepRow(facts, "slider", "", "Button"), false);
        assert.equal(keepRow(facts, "", "", "Slider"), false);
    });

    it("matches a component id case-insensitively, and only whole", () => {
        // The id travels through a URL, where case is not guaranteed to survive a hand-typed link.
        assert.equal(keepRow(facts, "", "", "  button  "), true);
        // …but a prefix is a different component. `Butt` selecting `Button` would make a scoped
        // wall quietly show rows the link did not ask for.
        assert.equal(keepRow(facts, "", "", "Butt"), false);
    });

    it("leaves a row with no component id out of every component scope", () => {
        // Rather than in all of them: an unscopeable row under a chip claiming a component is a
        // wrong answer, where an absent row is a missing one.
        const orphan = { ...facts, componentId: "" };
        assert.equal(keepRow(orphan, "", "", "Button"), false);
        assert.equal(keepRow(orphan, "", "", ""), true);
    });

    it("finds a row by a preview id that is no longer in its haystack", () => {
        // The search also matches ids resolved from the page's alias table
        // (`docs/design/COMPARE_NAVIGATION.md`, F2).
        const row = { ...facts, hay: "filled button · buttons" };
        assert.equal(keepRow(row, "FilledButtonPreview", ""), true);
        assert.equal(keepRow(row, "com.example.FilledButton", ""), true);
        assert.equal(keepRow(row, "SliderPreview", ""), false);
    });
});

describe("countLabel", () => {
    it("counts, and says it in the singular exactly once", () => {
        assert.equal(countLabel(0), "0 comparisons");
        assert.equal(countLabel(1), "1 comparison");
        assert.equal(countLabel(2), "2 comparisons");
    });
});

describe("scoreOf / byWorstFirst", () => {
    it("sorts the worst rows to the top, where the wall is read", () => {
        const sorted = [90, 12.5, 100, 74].sort(byWorstFirst);
        assert.deepEqual(sorted, [12.5, 74, 90, 100]);
    });

    it("leads with a row that could not be scored at all", () => {
        // An unmeasured pair outranks any measured one: it is the row nobody is looking at.
        assert.equal(scoreOf(null), -1);
        assert.equal(scoreOf(""), -1);
        assert.equal(scoreOf("-1"), -1);
        assert.deepEqual(
            [80, scoreOf(null), 20].sort(byWorstFirst),
            [-1, 20, 80],
        );
    });

    it("reads a real score back off the attribute", () => {
        assert.equal(scoreOf("93.4"), 93.4);
    });
});

describe("initialState with an unusable default", () => {
    it("opens on a format the catalog HAS, not a hardcoded svg", () => {
        // A Remote-Compose-only catalog must not open on an empty "svg" lane, which would read as
        // "nothing matches your filter".
        const state = initialState({
            defaults: { format: "svg", theme: "light" },
            remembered: null,
            params: new URLSearchParams(),
            available: {
                svg: false,
                rc: true,
                reference: false,
                parallel: false,
            },
        });
        assert.equal(state.format, "rc");
    });

    it("prefers the declared order when several are available", () => {
        const state = initialState({
            defaults: { format: "nonsense", theme: "light" },
            remembered: null,
            params: new URLSearchParams(),
            available: {
                svg: false,
                rc: true,
                reference: true,
                parallel: false,
            },
        });
        assert.equal(state.format, "rc");
    });

    it("opens a parallel-only catalog on its one real comparison", () => {
        const state = initialState({
            defaults: { format: "svg", theme: "light" },
            remembered: null,
            params: new URLSearchParams("format=parallel"),
            available: {
                svg: false,
                rc: false,
                reference: false,
                parallel: true,
            },
        });
        assert.equal(state.format, "parallel");
    });
});

describe("bakedScoreOf", () => {
    it("reads a published score off the row", () => {
        assert.equal(bakedScoreOf("61.80"), 61.8);
        assert.equal(bakedScoreOf("0"), 0);
    });

    it("answers null — not the unmeasurable sentinel — where there is none", () => {
        // `-1` means this browser failed to measure (leads the wall); a missing published score is
        // not a finding.
        assert.equal(bakedScoreOf(null), null);
        assert.equal(bakedScoreOf(""), null);
        assert.equal(bakedScoreOf("Infinity"), null);
        assert.equal(scoreOf(null), -1);
    });
});

describe("byWorstKnownFirst", () => {
    it("leads with the worst published score", () => {
        assert.equal(byWorstKnownFirst(40, 95) < 0, true);
        assert.equal(byWorstKnownFirst(95, 40) > 0, true);
    });

    it("leaves the unscored rows behind the scored ones, in served order", () => {
        assert.equal(byWorstKnownFirst(null, 95) > 0, true);
        assert.equal(byWorstKnownFirst(95, null) < 0, true);
        assert.equal(byWorstKnownFirst(null, null), 0);
    });

    it("differs from the measured order exactly where it must", () => {
        // Once measured, an unmeasurable row leads; before measuring, an unpublished one trails.
        assert.equal(byWorstFirst(-1, 40) < 0, true);
        assert.equal(byWorstKnownFirst(null, 40) > 0, true);
    });
});
