// Screenshot the spec lane's loupe, on and off.
//
// Same fixture as `spec-lane-eyedropper` and `spec-lane-source-switch`: the real
// `/remote-m3/p/appcard__ideal__default__compact` page as preview.coo.ee served it, beside the PNGs
// it points at. Nothing here draws a panel or a patch — every canvas in both shots is painted by
// the COMMITTED `viewer.js` / `viewer-components.js` from that committed artwork, and the magnified
// pixels are read out of the very canvases the panels were painted from. That is why this is shot
// rather than described: the ink in the loupe is the picture's.
//
//   node shoot.mjs after.png            # the lane as this branch serves it
//   node shoot.mjs before.png --before  # the same hover with the loupe switched off
//
// `--before` presses the new Loupe toggle off rather than checking out the old assets, and that is
// exactly the old lane: before this change the lane had a reading and no patch, which is what the
// toggle off produces. The toggle itself is visible in `before.png` for the same reason — it is
// part of what changed, and hiding it would make the two shots differ in more than one thing.
import { chromium } from "playwright";
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const fixture = path.resolve(here, "../spec-lane-eyedropper/fixture");
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
await new Promise((resolve) => server.listen(8795, resolve));

/** Whichever spelling the container's pre-installed Chromium is under. */
const chrome = [
    "/opt/pw-browsers/chromium/chrome-linux/chrome",
    "/opt/pw-browsers/chromium-1194/chrome-linux/chrome",
    "/opt/pw-browsers/chromium",
].find((candidate) => fs.existsSync(candidate));
const browser = await chromium.launch(
    chrome ? { executablePath: chrome } : {},
);
const context = await browser.newContext({
    viewport: { width: 1100, height: 900 },
    deviceScaleFactor: 2,
    colorScheme: "light",
});
const page = await context.newPage();
await page.goto(`http://127.0.0.1:8795${PAGE}?mode=spec`, {
    waitUntil: "networkidle",
});
// The component nav opens over the stage at this width and is not what either shot is about.
await page.evaluate(() => {
    for (const node of document.querySelectorAll(".cp-nav, .cp-drawer"))
        node.remove();
});
// Let the lane normalise the pair and paint the three panels before reading pixels out of them.
await page.waitForTimeout(1500);
if (before) await page.click('[data-cp-spec-loupe="loupe"]');

// Hover the render panel where the card's own type is — an ordinary pointer move over the
// server-rendered canvas, not a state poked into the page.
const actual = page.locator("#cp-spec-actual");
const box = await actual.boundingBox();
await page.mouse.move(box.x + box.width * 0.42, box.y + box.height * 0.34);
await page.waitForTimeout(400);

// The lane, the panels and the patch in one frame: a magnified patch is only evidence beside the
// picture it claims to magnify, so cropping to the loupe alone would leave the reader taking it on
// trust. The loupe is `position: fixed`, so it is in the union rather than inside either box.
const clip = await page.evaluate(() => {
    const boxes = ["cp-spec-lane", "cp-spec-compare", "cp-spec-loupe"]
        .map((id) => document.getElementById(id))
        .filter((node) => node && !node.hidden)
        .map((node) => node.getBoundingClientRect());
    if (!boxes.length) return null;
    const left = Math.max(0, Math.min(...boxes.map((b) => b.left)) - 12);
    const top = Math.max(0, Math.min(...boxes.map((b) => b.top)) - 12);
    return {
        x: left,
        y: top,
        width: Math.max(...boxes.map((b) => b.right)) - left + 12,
        height: Math.max(...boxes.map((b) => b.bottom)) - top + 12,
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
    "patch:",
    await page.evaluate(
        () => document.getElementById("cp-spec-loupe")?.hidden ?? null,
    ),
);
await browser.close();
server.close();
