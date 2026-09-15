// Screenshot the spec lane's loupe, on and off.
//
// Same fixture as `spec-lane-eyedropper` and `spec-lane-source-switch`: the real
// `/remote-m3/p/appcard__ideal__default__compact` page as preview.coo.ee served it, beside the PNGs
// it points at. Nothing here draws a panel or a patch — every canvas in both shots is painted by
// the COMMITTED `viewer.js` / `viewer-components.js` from that committed artwork, and the magnified
// pixels are read out of the very canvases the panels were painted from. That is why this is shot
// rather than described: the ink in the loupe is the picture's.
//
//   node shoot.mjs after.png            # the same hover, asking for the patch
//   node shoot.mjs before.png --before  # the lane as it was, and as it still rests
//
// The loupe is OFF at rest, so `before.png` needs nothing removed or backed out: it is the lane on
// an ordinary hover, which is what the lane did before this change and what it still does until
// somebody asks. `after.png` asks, by holding Shift over the panel — the one-look gesture, rather
// than the toggle, because that is how this will mostly be reached. The toggles are visible in both
// shots on purpose: they are part of what changed, and hiding them in one would make the pair
// differ in more than the one thing it is about.
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

// Hover the render panel where the card's own type is — an ordinary pointer move over the
// server-rendered canvas, not a state poked into the page. For the `after` shot, with Shift down:
// a real modifier on a real pointer move, which is the gesture, not a flag set on the element.
const actual = page.locator("#cp-spec-actual");
const box = await actual.boundingBox();
if (!before) await page.keyboard.down("Shift");
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
    "patch hidden:",
    await page.evaluate(
        () => document.getElementById("cp-spec-loupe")?.hidden ?? null,
    ),
);
if (!before) await page.keyboard.up("Shift");
await browser.close();
server.close();
