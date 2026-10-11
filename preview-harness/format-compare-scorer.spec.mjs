// Behavioural tests for the `/compare` page's design-reference scorer, driving
// `window.ComposePreviewCompare.scoreImageUrls` over synthetic in-page images (data URIs don't
// taint the canvas). Pins content-box normalisation: a preview carries its `@Preview` scaffold
// (background, padding, unfilled height) while a reference is cropped to the artboard, and the
// score should answer "does this look like its design?". Arithmetic-only annotation tests live in
// `cli/serve-web/test/annotateMatch.test.ts` and `annotateTypography.test.ts`.

import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const SCORER = readFileSync(
    resolve(
        here,
        "../server/src/main/resources/ee/schimke/composeai/cli/serve/assets/format-compare.js",
    ),
    "utf8",
);

/** Load the real asset into a page with an origin, then score the four synthetic pairs. */
async function scorePairs(page) {
    await page.goto("/preview-harness/index.html");
    await page.addScriptTag({ content: SCORER });
    return page.evaluate(async () => {
        function make(width, height, draw) {
            const canvas = document.createElement("canvas");
            canvas.width = width;
            canvas.height = height;
            draw(canvas.getContext("2d"), width, height);
            return canvas.toDataURL("image/png");
        }
        // A stand-in component: a rounded card with a title bar and two body rows.
        function card(ctx, x, y, w, h) {
            ctx.fillStyle = "#e8f1ee";
            ctx.beginPath();
            ctx.roundRect(x, y, w, h, h * 0.12);
            ctx.fill();
            ctx.fillStyle = "#123";
            ctx.fillRect(x + w * 0.08, y + h * 0.15, w * 0.5, h * 0.14);
            ctx.fillStyle = "#567";
            ctx.fillRect(x + w * 0.08, y + h * 0.45, w * 0.7, h * 0.09);
            ctx.fillRect(x + w * 0.08, y + h * 0.65, w * 0.6, h * 0.09);
        }

        // Reference bleeds to the artboard; the preview pads the same component onto an opaque
        // sheet and lands on a different canvas size entirely.
        const bleedReference = make(400, 240, (c, w, h) => card(c, 0, 0, w, h));
        const scaffoldedPreview = make(480, 300, (c, w, h) => {
            c.fillStyle = "#fff";
            c.fillRect(0, 0, w, h);
            card(c, 40, 30, w - 80, h - 60);
        });

        // Both near-empty: the same small mark in both, plus a faint full-bleed hairline on the
        // reference that makes its content box the whole canvas. Cropping the preview to its few
        // percent and stretching it would report a total mismatch; the whole-canvas fallback
        // prevents that.
        const emptyReference = make(400, 320, (c, w, h) => {
            c.fillStyle = "#fff";
            c.fillRect(0, 0, w, h);
            c.strokeStyle = "#eee";
            c.strokeRect(0.5, 0.5, w - 1, h - 1);
            c.fillStyle = "#111";
            c.fillRect(20, 22, 90, 16);
        });
        const emptyPreview = make(400, 320, (c, w, h) => {
            c.fillStyle = "#fff";
            c.fillRect(0, 0, w, h);
            c.fillStyle = "#111";
            c.fillRect(20, 22, 90, 16);
        });

        // Different content at comparable framing — the control for "normalisation inflates
        // everything".
        const unrelatedPreview = make(400, 240, (c, w, h) => {
            c.fillStyle = "#3b1d1d";
            c.fillRect(0, 0, w, h);
            c.fillStyle = "#ffd";
            c.beginPath();
            c.arc(w / 2, h / 2, h * 0.35, 0, Math.PI * 2);
            c.fill();
        });

        // A missing repeated mark must not match its neighbour in the luminance search; a one-pixel
        // edge shift remains tolerated.
        const repeatedReference = make(100, 60, (c) => {
            c.fillStyle = "#fff";
            c.fillRect(0, 0, 100, 60);
            c.fillStyle = "#000";
            c.fillRect(30, 10, 2, 40);
            c.fillRect(36, 10, 2, 40);
        });
        const missingRepeatedMark = make(100, 60, (c) => {
            c.fillStyle = "#fff";
            c.fillRect(0, 0, 100, 60);
            c.fillStyle = "#000";
            c.fillRect(30, 10, 2, 40);
        });
        const shiftedEdges = make(100, 60, (c) => {
            c.fillStyle = "#fff";
            c.fillRect(0, 0, 100, 60);
            c.fillStyle = "#000";
            c.fillRect(31, 10, 2, 40);
            c.fillRect(37, 10, 2, 40);
        });

        // An opaque reference cropped to a card, so its fill reaches (0, 0) as typical design-tool
        // exports do; the corner is artwork, not backdrop.
        const opaqueBledCard = make(400, 240, (c, w, h) => {
            c.fillStyle = "#e8f1ee";
            c.fillRect(0, 0, w, h);
            card(c, 0, 0, w, h);
        });

        const score = window.ComposePreviewCompare.scoreImageUrls;
        return {
            scaffolded: await score(bleedReference, scaffoldedPreview),
            identical: await score(bleedReference, bleedReference),
            nearEmpty: await score(emptyReference, emptyPreview),
            unrelated: await score(bleedReference, unrelatedPreview),
            opaqueBled: await score(opaqueBledCard, scaffoldedPreview),
            missingRepeated: await score(repeatedReference, missingRepeatedMark),
            shiftedEdges: await score(repeatedReference, shiftedEdges),
        };
    });
}

test.describe("format-compare · design reference scorer", () => {
    test("a scaffolded preview still matches its bled reference", async ({ page }) => {
        const { scaffolded, identical } = await scorePairs(page);
        // Differing canvas sizes and differently framed pairs must still compare.
        expect(scaffolded.percent).toBeGreaterThan(90);
        expect(scaffolded.geometry).toBeLessThan(2);
        expect(identical.percent).toBeCloseTo(100, 5);
    });

    test("a near-empty pair falls back to whole-canvas instead of magnifying one mark", async ({
        page,
    }) => {
        const { nearEmpty } = await scorePairs(page);
        // Cropping would stretch a 90x16 mark across the comparison; the whole-canvas fallback is
        // what lets this pair score.
        expect(nearEmpty.percent).toBeGreaterThan(90);
        // The framing difference is real and still reported, just kept out of the match score.
        expect(nearEmpty.geometry).toBeGreaterThan(50);
    });

    test("an opaque reference whose artwork reaches the corner is not stripped", async ({
        page,
    }) => {
        const { opaqueBled } = await scorePairs(page);
        // "A uniform border around an interior region" is the same picture whether the border is a
        // scaffold sheet or a card bleeding to the artboard edge, so an opaque corner is only taken
        // as a backdrop when it is a sheet `showBackground` actually paints. Treating this card's
        // own fill as the backdrop boxed only its text and scored the pair at 8%.
        expect(opaqueBled.percent).toBeGreaterThan(85);
    });

    test("unrelated content still reads as a mismatch", async ({ page }) => {
        const { unrelated } = await scorePairs(page);
        expect(unrelated.percent).toBeLessThan(75);
    });

    test("edge tolerance preserves position and still tolerates raster shifts", async ({ page }) => {
        const { missingRepeated, shiftedEdges } = await scorePairs(page);
        expect(missingRepeated.percent).toBeLessThan(99.5);
        expect(shiftedEdges.percent).toBeGreaterThan(missingRepeated.percent);
        expect(shiftedEdges.percent).toBeGreaterThan(95);
    });
});

// SVG lane registration: fixture SVGs all sit at `translate(0, 0)`, so fractional offsets (which
// Figma writes routinely) are tested here.
test.describe("format-compare · SVG lane registration", () => {
    /** The same card as a PNG at the origin, and as an SVG placed at (tx, ty) on its board. */
    async function scorePlacement(page, tx, ty) {
        await page.goto("/preview-harness/index.html");
        await page.addScriptTag({ content: SCORER });
        return page.evaluate(
            async ([x, y]) => {
                const canvas = document.createElement("canvas");
                canvas.width = 200;
                canvas.height = 120;
                const ctx = canvas.getContext("2d");
                ctx.fillStyle = "#fff";
                ctx.fillRect(0, 0, 200, 120);
                ctx.fillStyle = "#2c5f4f";
                ctx.fillRect(20, 20, 160, 80);
                ctx.fillStyle = "#fff";
                ctx.fillRect(40, 40, 80, 16);
                const png = canvas.toDataURL("image/png");

                const svg =
                    '<svg xmlns="http://www.w3.org/2000/svg" width="200" height="120">' +
                    '<rect width="200" height="120" fill="#fff"/>' +
                    `<g transform="translate(${x}, ${y})">` +
                    '<rect x="20" y="20" width="160" height="80" fill="#2c5f4f"/>' +
                    '<rect x="40" y="40" width="80" height="16" fill="#fff"/>' +
                    "</g></svg>";

                const realFetch = window.fetch;
                window.fetch = async () => ({ ok: true, text: async () => svg });
                try {
                    return await window.ComposePreviewCompare.scoreSvgUrls(png, "/svg");
                } finally {
                    window.fetch = realFetch;
                }
            },
            [tx, ty],
        );
    }

    test("a fractionally-placed export registers as well as an integer one", async ({
        page,
    }) => {
        // Integer and fractional placements must score within a point of each other.
        const integer = await scorePlacement(page, 60, 30);
        const fractional = await scorePlacement(page, 60.5, 30.25);
        expect(fractional).toBeGreaterThan(integer - 1);
    });
});
