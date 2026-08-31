// Screenshot the spec lane's source picker, before and after issue #4895.
//
// The fixture is the real page: `/remote-m3/p/appcard__ideal__default__compact` exactly as
// preview.coo.ee served it when the bug was filed, beside the three PNGs it points at — the
// remote-m3 render, its imported Figma reference, and wear-m3-catalog's own render of the paired
// component. Nothing here draws a panel: every canvas in both shots is painted by the COMMITTED
// `viewer.js` / `viewer-components.js` from that committed artwork, which is why this is shot
// rather than described.
//
//   node shoot.mjs after.png            # the lane as this branch serves it
//   node shoot.mjs before.png --before  # the same lane with the fix taken back out
//
// `--before` re-latches the reference instead of checking out the old assets. The change is exactly
// that `viewer.js` now names the picked source on `open()` and `<cp-spec-compare>` acts on it, so
// dropping that argument on the way through reproduces the previous behaviour precisely: the old
// `open(url)` took no source, read `data-reference` once at install, and every surface downstream —
// the reference canvas, the caption, the chip, the annotations — followed from that one raster.
import { chromium } from "playwright";
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const fixture = path.join(here, "fixture");
const assets = path.resolve(
    here,
    "../../../server/src/main/resources/ee/schimke/composeai/cli/serve/assets",
);
const PAGE = "/remote-m3/p/appcard__ideal__default__compact";
const [, , out = "after.png", flag] = process.argv;
const before = flag === "--before";

const types = {
    ".html": "text/html",
    ".css": "text/css",
    ".js": "text/javascript",
    ".png": "image/png",
    ".svg": "image/svg+xml",
};
// The served page fingerprints its bundles (`/assets/serve/<len>-<hash>/viewer.js`) so they can be
// cached forever. The fingerprint is not evidence of anything here, and pinning it would make this
// script stop resolving the moment a bundle changes — so assets resolve by BASENAME, against the
// working tree's committed bundles, which is the whole point of shooting them.
const server = http.createServer((request, response) => {
    const url = decodeURIComponent((request.url ?? "/").split("?")[0]);
    const asset = url.startsWith("/assets/");
    // Served at its OWN path, not as `/page.html`: `viewer.js` builds every render URL relative to
    // the page it is on, so the route is part of the fixture.
    const file = asset
        ? path.join(assets, path.basename(url))
        : path.join(fixture, url === PAGE ? "page.html" : url);
    const root = asset ? assets : fixture;
    if (
        !file.startsWith(root) ||
        !fs.existsSync(file) ||
        fs.statSync(file).isDirectory()
    ) {
        response.writeHead(404).end("not here");
        return;
    }
    response.writeHead(200, {
        "content-type": types[path.extname(file)] ?? "application/octet-stream",
    });
    fs.createReadStream(file).pipe(response);
});
await new Promise((resolve) => server.listen(8793, resolve));

const browser = await chromium.launch({
    executablePath: "/opt/pw-browsers/chromium",
});
const context = await browser.newContext({
    viewport: { width: 1100, height: 900 },
    deviceScaleFactor: 2,
    colorScheme: "light",
});
const page = await context.newPage();
if (before) {
    await page.addInitScript(() => {
        // Re-latch the reference: swallow the source `viewer.js` now names, so the element falls
        // back to `data-reference` for the life of the page, as it did before this change.
        let latched = null;
        Object.defineProperty(window, "cpSpecCompare", {
            configurable: true,
            get: () => latched,
            set: (api) => {
                latched = api && {
                    ...api,
                    open: (url) => api.open(url),
                };
            },
        });
    });
}
await page.goto(`http://127.0.0.1:8793${PAGE}?mode=spec`, {
    waitUntil: "networkidle",
});
// The component nav opens over the stage at this width and is not what either shot is about.
await page.evaluate(() => {
    for (const node of document.querySelectorAll(".cp-nav, .cp-drawer"))
        node.remove();
});
// The lane, then the sibling. Both are ordinary clicks on the server-rendered controls — the shot
// is of the picker being used, not of a state poked into the page.
await page
    .locator("#cp-spec-sources button", { hasText: "M3 Wear OS" })
    .click();
await page.waitForTimeout(1500);
if (before) {
    // The other half of the change, restored the same way: the stage hint used to be decided by
    // `specActive()` alone, so it read "imported design spec" whichever source was pressed.
    await page.evaluate(() => {
        document.getElementById("cp-mode-hint").textContent =
            "imported design spec — not a render";
    });
}
// The picker and the stage in one frame: which button is pressed is half of what these shots are
// evidence about, and cropping to the stage alone would leave the reader taking that on trust.
const clip = await page.evaluate(() => {
    const lane = document
        .getElementById("cp-spec-sources")
        .getBoundingClientRect();
    const stage = document.querySelector(".cp-stage").getBoundingClientRect();
    const top = Math.min(lane.top, stage.top) - 12;
    const left = Math.min(lane.left, stage.left) - 12;
    return {
        x: left,
        y: top,
        width: Math.max(lane.right, stage.right) - left + 12,
        height: Math.max(lane.bottom, stage.bottom) - top + 12,
    };
});
await page.screenshot({ path: path.join(here, out), clip });
await browser.close();
server.close();
