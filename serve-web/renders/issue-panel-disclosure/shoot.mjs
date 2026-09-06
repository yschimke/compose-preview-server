// Screenshot the filed-issue panel on the two pages that carry it beside something else — the
// viewer, where it sits between a preview's title and the preview, and the parity dashboard, where
// it repeats once per component.
//
//   node shoot.mjs viewer-before | viewer-after | viewer-open
//   node shoot.mjs parity-before | parity-after
//
// `*-before` is shot from `fixture/before-*.html` against `main`'s stylesheet, read out of git at
// shoot time (`BEFORE_REF`, default `origin/main`). Those pages ARE `main`'s `ServeWeb` output for
// the same four issues the after side shows: this branch adds two to the shared fixture list in
// `ServeWebFixtureTest`, and that one data change was applied on top of `main` to regenerate them.
// Nothing is drawn by hand — both sides are the production stylesheet over server-emitted markup.
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
const [, , which = "viewer-after"] = process.argv;
const [page_, mode] = which.split("-");
const before = mode === "before";
const open = mode === "open";

const beforeCss = () =>
    execFileSync(
        "git",
        [
            "show",
            `${BASE}:server/src/main/resources/ee/schimke/composeai/cli/serve/assets/serve.css`,
        ],
        { cwd: repo, maxBuffer: 32 * 1024 * 1024 },
    );

const file = before
    ? path.join(here, `fixture/before-${page_}.html`)
    : path.join(pages, `serve-${page_}.html`);
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
const tab = await browser.newPage({
    viewport: { width: 1024, height: 900 },
    colorScheme: "light",
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
    const f = path.join(assets, name);
    return fs.existsSync(f)
        ? route.fulfill({
              path: f,
              contentType: types[path.extname(name)] ?? "text/plain",
          })
        : route.fulfill({ status: 404, body: "" });
});
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
await tab.goto(`file://${file}`);
await tab.waitForTimeout(1000);
if (open) {
    await tab.click(".cp-parity-issues-sum");
    await tab.mouse.move(2, 2);
    await tab.waitForTimeout(200);
}
// The viewer's panel and the preview under it; on the dashboard, the bands and the heading above
// them. Measured rather than guessed, so both sides frame the same region whatever either does to
// the block's height.
const anchor =
    page_ === "viewer"
        ? ".cp-parity-issues"
        : ".cp-parity-issue-group";
const box = await tab.locator(anchor).first().boundingBox();
const height = page_ === "viewer" ? 520 : 440;
await tab.evaluate((y) => window.scrollTo(0, y), Math.max(0, box.y - 90));
await tab.waitForTimeout(150);
await tab.screenshot({
    path: path.join(here, `${which}.png`),
    fullPage: true,
    clip: { x: 0, y: Math.max(0, box.y - 90), width: 1024, height },
    animations: "disabled",
});
console.log(`${which}: block ${Math.round(box.height)}px tall`);
await browser.close();
