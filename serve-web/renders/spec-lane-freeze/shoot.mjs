// Screenshot what the eyedropper freezes when you click it, before and after issue #464.
//
// Same fixture as `spec-lane-eyedropper` and `spec-lane-source-switch`: the real
// `/remote-m3/p/appcard__ideal__default__compact` page as preview.coo.ee served it, beside the PNGs
// it points at. Nothing here draws a panel or writes a readout — every canvas is painted by the
// committed `viewer.js` / `viewer-components.js`, and both readings are read back out of those very
// pixels by the lane itself.
//
//   node shoot.mjs after.png            # the lane as this branch serves it
//   node shoot.mjs before.png --before  # the identical gesture on the bundle committed at HEAD~
//
// `--before` serves `viewer-components.js` as `git show` hands it back from the base commit, so the
// left-hand shot is the shipped behaviour rather than a re-enactment of it. Everything else — page,
// artwork, viewport, pointer path — is byte-identical between the two runs.
//
// The gesture is one pointer move onto the render panel followed by one click at the SAME screen
// point. The two shots differ only in what the click leaves in the row.
import { chromium } from "playwright";
import { execFileSync } from "node:child_process";
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const fixture = path.join(here, "../spec-lane-eyedropper/fixture");
const assets = path.resolve(
    here,
    "../../../server/src/main/resources/ee/schimke/composeai/cli/serve/assets",
);
const PAGE = "/remote-m3/p/appcard__ideal__default__compact";
const BASE = process.env.CP_BEFORE_REF ?? "HEAD";
const [, , out = "after.png", flag] = process.argv;
const before = flag === "--before";

// The one asset the fix touches, as the base commit has it. Read through `git show` rather than a
// checkout so the working tree — and the shot's own fixture — stay exactly where they are.
const baseBundle = before
    ? execFileSync(
          "git",
          [
              "show",
              `${BASE}:server/src/main/resources/ee/schimke/composeai/cli/serve/assets/viewer-components.js`,
          ],
          { cwd: here, maxBuffer: 64 * 1024 * 1024 },
      )
    : null;

const types = {
    ".html": "text/html",
    ".css": "text/css",
    ".js": "text/javascript",
    ".png": "image/png",
    ".svg": "image/svg+xml",
};
const server = http.createServer((request, response) => {
    const url = decodeURIComponent((request.url ?? "/").split("?")[0]);
    const asset = url.startsWith("/assets/");
    const name = path.basename(url);
    if (baseBundle && name === "viewer-components.js") {
        response.writeHead(200, { "content-type": "text/javascript" });
        response.end(baseBundle);
        return;
    }
    const file = asset
        ? path.join(assets, name)
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
await new Promise((resolve) => server.listen(8798, resolve));

const browser = await chromium.launch({
    executablePath: "/opt/pw-browsers/chromium",
});
const context = await browser.newContext({
    viewport: { width: 1100, height: 900 },
    deviceScaleFactor: 2,
    colorScheme: "light",
});
const page = await context.newPage();
await page.goto(`http://127.0.0.1:8798${PAGE}?mode=spec`, {
    waitUntil: "networkidle",
});
// The component nav opens over the stage at this width and is not what either shot is about.
await page.evaluate(() => {
    for (const node of document.querySelectorAll(".cp-nav, .cp-drawer"))
        node.remove();
});
await page.waitForTimeout(1500);

// One hover, then one click at the same screen point. A real pointer move carries sub-pixel
// coordinates; the click that follows carries them rounded to whole CSS pixels, which is the whole
// bug — the panel is the raster fitted into its box, so one CSS pixel is more than one pixel of the
// normalised space the reading names.
const actual = page.locator("#cp-spec-actual");
const box = await actual.boundingBox();
const point = { x: box.x + box.width * 0.42, y: box.y + box.height * 0.34 };
await page.mouse.move(point.x, point.y);
await page.waitForTimeout(400);
const hovered = await page.evaluate(
    () => document.getElementById("cp-spec-pick")?.textContent ?? null,
);
await page.mouse.down();
await page.mouse.up();
await page.waitForTimeout(400);
const frozen = await page.evaluate(
    () => document.getElementById("cp-spec-pick")?.textContent ?? null,
);

// Screenshots do not carry the cursor, and where it was is half of what these shots claim. The
// crosshair is drawn by this script, over the page, at the exact coordinates the events carried.
await page.evaluate((at) => {
    const mark = document.createElement("div");
    mark.style.cssText = `position:fixed;left:${at.x}px;top:${at.y}px;width:15px;height:15px;
        margin:-8px 0 0 -8px;border:1.5px solid #d32f2f;border-radius:50%;
        box-shadow:0 0 0 1.5px #fff, inset 0 0 0 1.5px #fff;pointer-events:none;z-index:9`;
    document.body.append(mark);
}, point);

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
    before ? `(before, ${BASE})` : "(after)",
    "\n  hovered:",
    JSON.stringify(hovered),
    "\n  frozen :",
    JSON.stringify(frozen),
    hovered === frozen ? "(same)" : "*** the click changed the reading ***",
);
await browser.close();
server.close();
