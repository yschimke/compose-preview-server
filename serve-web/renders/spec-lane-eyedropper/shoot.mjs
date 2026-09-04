// Screenshot the spec lane's eyedropper, before and after it existed.
//
// Same fixture as `spec-lane-source-switch`: the real `/remote-m3/p/appcard__ideal__default__compact`
// page as preview.coo.ee served it, beside the PNGs it points at. Nothing here draws a panel — every
// canvas in both shots is painted by the COMMITTED `viewer.js` / `viewer-components.js` from that
// committed artwork, and the reading in `after.png` is read back out of those same pixels. That is
// why this is shot rather than described: the hex in the readout is the picture's, not the shot's.
//
//   node shoot.mjs after.png            # the lane as this branch serves it
//   node shoot.mjs before.png --before  # the same hover with the readout taken back out
//
// `--before` removes the two readout elements at init rather than checking out the old assets. The
// change is exactly that the lane now carries `#cp-spec-pick` and reads the pair's pixels into it;
// with the elements gone `setPick` finds nothing and writes nowhere, which is precisely how the
// lane behaved when neither the elements nor the handlers existed.
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
// Assets resolve by BASENAME against the working tree's committed bundles — the served page
// fingerprints them for caching, and pinning that fingerprint would make this script stop resolving
// the moment a bundle changes, which is the one thing it exists to shoot.
const server = http.createServer((request, response) => {
    const url = decodeURIComponent((request.url ?? "/").split("?")[0]);
    const asset = url.startsWith("/assets/");
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
await new Promise((resolve) => server.listen(8794, resolve));

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
        const strip = () => {
            for (const id of ["cp-spec-pick", "cp-spec-pick-live"])
                document.getElementById(id)?.remove();
        };
        document.addEventListener("DOMContentLoaded", strip);
        strip();
    });
}
await page.goto(`http://127.0.0.1:8794${PAGE}?mode=spec`, {
    waitUntil: "networkidle",
});
// The component nav opens over the stage at this width and is not what either shot is about.
await page.evaluate(() => {
    for (const node of document.querySelectorAll(".cp-nav, .cp-drawer"))
        node.remove();
});
// Let the lane normalise the pair and paint the three panels before reading pixels out of them.
await page.waitForTimeout(1500);

// Hover the render panel a little in from its top-left, where the card's own artwork is — an
// ordinary pointer move over the server-rendered canvas, not a state poked into the page.
const actual = page.locator("#cp-spec-actual");
const box = await actual.boundingBox();
await page.mouse.move(box.x + box.width * 0.42, box.y + box.height * 0.34);
await page.waitForTimeout(400);

// The panels and the lane header in one frame: the reading is only evidence beside the pixels it
// claims to describe, so cropping to the readout alone would leave the reader taking it on trust.
const clip = await page.evaluate(() => {
    const lane = document.getElementById("cp-spec-lane");
    const compare = document.getElementById("cp-spec-compare");
    if (!lane || !compare) return null;
    const a = lane.getBoundingClientRect();
    const b = compare.getBoundingClientRect();
    const top = Math.min(a.top, b.top) - 12;
    const left = Math.min(a.left, b.left) - 12;
    return {
        x: Math.max(0, left),
        y: Math.max(0, top),
        width: Math.max(a.right, b.right) - Math.max(0, left) + 12,
        height: Math.max(a.bottom, b.bottom) - Math.max(0, top) + 12,
    };
});
await page.screenshot({ path: path.join(here, out), clip: clip ?? undefined });
console.log(
    out,
    before ? "(before)" : "(after)",
    "readout:",
    JSON.stringify(
        await page.evaluate(
            () => document.getElementById("cp-spec-pick")?.textContent ?? null,
        ),
    ),
);
await browser.close();
server.close();
