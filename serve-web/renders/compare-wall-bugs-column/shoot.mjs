// Screenshot the comparison wall's **Bugs** column, collapsed and open, before and after.
//
// The subject is one table cell, so these are crops rather than pages: the cell's own box plus the
// score beside it, measured in the browser rather than guessed, so the two shots frame the same
// region of the same row whatever either version does to the column's width.
//
//   node shoot.mjs after            # the cell as this branch serves it, collapsed
//   node shoot.mjs after-open       # …with the disclosure open, light and dark
//   node shoot.mjs before           # the same row and the same four issues, as `main` served them
//
// `before` is shot from `fixture/before-page.html` against `main`'s stylesheet, which is read out
// of git at shoot time rather than committed here. The fixture page IS `main`'s `ServeWeb` output:
// this branch adds two issues to the shared fixture list in `ServeWebFixtureTest` so the row shows
// the run of reports the collapsed line exists for, and that one data change was applied on top of
// `main` to regenerate this page. Nothing in either shot is drawn or edited by hand — both are the
// production stylesheet over server-emitted markup, which is why this is shot rather than described.
import { chromium } from "playwright";
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, "../../..");
const assets = path.join(
    repo,
    "server/src/main/resources/ee/schimke/composeai/cli/serve/assets",
);
const pages = path.join(repo, "preview-harness/fixtures/pages");
const BASE = process.env.BEFORE_REF ?? "origin/main";
const [, , which = "after"] = process.argv;
const before = which === "before";
const open = which.endsWith("-open");

const beforeCss = () =>
    execFileSync(
        "git",
        [
            "show",
            `${BASE}:server/src/main/resources/ee/schimke/composeai/cli/serve/assets/serve.css`,
        ],
        { cwd: repo, maxBuffer: 32 * 1024 * 1024 },
    );

const page_ = before
    ? path.join(here, "fixture/before-page.html")
    : path.join(pages, "serve-format-compare.html");
const types = {
    ".css": "text/css",
    ".js": "text/javascript",
    ".png": "image/png",
    ".svg": "image/svg+xml",
};

const browser = await chromium.launch({
    ...(process.env.HARNESS_CHROMIUM
        ? { executablePath: process.env.HARNESS_CHROMIUM }
        : {}),
    args: [
        "--enable-unsafe-swiftshader",
        "--use-gl=angle",
        "--disable-partial-raster",
        "--disable-skia-runtime-opts",
    ],
});
// 1280, the width `serve-format-compare-picked` is captured at: the reference lane's three panels
// plus the label and the score do not fit 1024, and this column's whole claim is about what it
// leaves for them.
for (const theme of open ? ["light", "dark"] : ["light"]) {
    const tab = await browser.newPage({
        viewport: { width: 1280, height: 900 },
        colorScheme: theme,
    });
    await tab.route("**/assets/serve/**/serve.css", (route) =>
        before
            ? route.fulfill({ body: beforeCss(), contentType: "text/css" })
            : route.fulfill({
                  path: path.join(assets, "serve.css"),
                  contentType: "text/css",
              }),
    );
    await tab.route("**/assets/serve/**/*", (route) => {
        const name = path.basename(new URL(route.request().url()).pathname);
        const file = path.join(assets, name);
        return fs.existsSync(file)
            ? route.fulfill({
                  path: file,
                  contentType: types[path.extname(name)] ?? "text/plain",
              })
            : route.fulfill({ status: 404, body: "" });
    });
    // The wall's two image lanes have no backend here; the harness's committed placeholders stand
    // in, exactly as `pages-snapshot.spec.mjs` stands them in.
    await tab.route("**/render/**", (route) =>
        route.fulfill({
            path: path.join(pages, "_render-placeholder.png"),
            contentType: "image/png",
        }),
    );
    await tab.route("**/reference/**", (route) =>
        route.fulfill({
            path: path.join(pages, "_design-render-placeholder.png"),
            contentType: "image/png",
        }),
    );
    await tab.goto(`file://${page_}`);
    await tab.waitForTimeout(1200);
    if (open) {
        await tab
            .locator(".cp-compare-row:not([hidden]) .cp-compare-bug-summary")
            .first()
            .click();
        // Park the pointer. `click()` leaves the mouse on the summary, and the chips underline on
        // hover — a difference between two runs of the same code, not between two designs.
        await tab.mouse.move(2, 2);
        await tab.waitForTimeout(300);
    }
    const row = tab.locator(".cp-compare-row:not([hidden])").first();
    const cell = row.locator(".cp-compare-bugs");
    const rb = await row.boundingBox();
    const cb = await cell.boundingBox();
    const panel = open
        ? await tab.locator(".cp-compare-bug-panel").first().boundingBox()
        : null;
    const pad = 12;
    const bottom = Math.max(
        rb.y + rb.height,
        panel ? panel.y + panel.height : 0,
    );
    await tab.screenshot({
        path: path.join(here, `${which}${open ? `.${theme}` : ""}.png`),
        clip: {
            x: cb.x - 150,
            y: rb.y - pad,
            width: cb.width + 162,
            height: bottom - rb.y + pad * 3,
        },
        animations: "disabled",
    });
    console.log(`${which} ${theme}: cell ${Math.round(cb.width)}px wide`);
    await tab.close();
}
await browser.close();
