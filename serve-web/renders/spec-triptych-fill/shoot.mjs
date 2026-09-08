// Screenshot the spec lane's triptych before and after its frames filled their columns.
//
// The triptych stretches its three columns (`flex: 1 1 0`) but the base panel rule sizes a frame
// with `max-*` against auto dimensions, which only ever SHRINKS a replaced element. So a small
// raster sat at its intrinsic size in a column nearly four times its width — and the surrounding
// column was inert: nothing to look at, and no pixel for the eyedropper to read.
//
//   node shoot.mjs after.png
//   node shoot.mjs before.png --before   # `serve.css` and the viewer bundle from the base commit
//   node shoot.mjs letterbox.png --short # the height-budget case, where `contain` earns its keep
//
// The fixture is the one `spec-picker-reflow` recorded — the page issue #464 was reported from,
// `/wear-m3-catalog/p/button-compact__ideal__filled-variant-icon-only`, whose frame is 104x66 in a
// 387px column. Replaying it offline keeps these shots deterministic.
import { chromium } from "playwright";
import { execFileSync } from "node:child_process";
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const fixture = path.join(here, "../spec-picker-reflow/fixture");
const assets = path.resolve(
    here,
    "../../../server/src/main/resources/ee/schimke/composeai/cli/serve/assets",
);
const PAGE =
    "/wear-m3-catalog/p/button-compact__ideal__filled-variant-icon-only";
const BASE = process.env.CP_BEFORE_REF ?? "HEAD";
const argv = process.argv.slice(2);
const before = argv.includes("--before");
const short = argv.includes("--short");
const out = argv.find((a) => a.endsWith(".png")) ?? "after.png";

// Both halves of the change come from the base commit for a `--before` shot: the sheet that sizes
// the frame, and the bundle that reads it. Through `git show`, so the working tree stays put.
const baseAsset = (name) =>
    execFileSync(
        "git",
        [
            "show",
            `${BASE}:server/src/main/resources/ee/schimke/composeai/cli/serve/assets/${name}`,
        ],
        { cwd: here, maxBuffer: 64 * 1024 * 1024 },
    );
const baseAssets = before
    ? {
          "serve.css": baseAsset("serve.css"),
          "viewer-components.js": baseAsset("viewer-components.js"),
      }
    : {};

const index = JSON.parse(
    fs.readFileSync(path.join(fixture, "index.json"), "utf8"),
);
const types = { ".css": "text/css", ".js": "text/javascript" };
const server = http.createServer((request, response) => {
    const url = request.url ?? "/";
    const name = path.basename(url.split("?")[0]);
    if (baseAssets[name]) {
        response.writeHead(200, { "content-type": types[path.extname(name)] });
        return void response.end(baseAssets[name]);
    }
    if (url.startsWith("/assets/")) {
        const file = path.join(assets, name);
        if (file.startsWith(assets) && fs.existsSync(file)) {
            response.writeHead(200, {
                "content-type":
                    types[path.extname(file)] ?? "application/octet-stream",
            });
            return void fs.createReadStream(file).pipe(response);
        }
    }
    const entry = index[url];
    if (!entry) return void response.writeHead(404).end("not recorded");
    response.writeHead(200, { "content-type": entry.type });
    response.end(fs.readFileSync(path.join(fixture, entry.file)));
});
await new Promise((resolve) => server.listen(8816, resolve));

const browser = await chromium.launch({
    executablePath: "/opt/pw-browsers/chromium",
});
// 683 tall is the reporter's own viewport, where the column decides the size. 400 forces the other
// case: `max-height: 52vh` binds first, and `object-fit: contain` letterboxes rather than squashing.
const context = await browser.newContext({
    viewport: { width: 1280, height: short ? 400 : 683 },
    deviceScaleFactor: 2,
    colorScheme: "light",
});
const page = await context.newPage();
await page.goto(`http://127.0.0.1:8816${PAGE}?mode=spec`, {
    waitUntil: "networkidle",
    timeout: 90000,
});
await page.evaluate(() => {
    for (const node of document.querySelectorAll(".cp-nav, .cp-drawer"))
        node.remove();
});
await page.waitForTimeout(2500);
await page.evaluate(() =>
    document
        .getElementById("cp-spec-compare")
        .scrollIntoView({ block: "center" }),
);
await page.waitForTimeout(300);

const facts = await page.evaluate(() => {
    const canvas = document.getElementById("cp-spec-actual");
    const rect = canvas.getBoundingClientRect();
    const scale = Math.min(
        rect.width / canvas.width,
        rect.height / canvas.height,
    );
    const stage = document
        .getElementById("cp-spec-compare")
        .getBoundingClientRect();
    return {
        raster: [canvas.width, canvas.height],
        box: [+rect.width.toFixed(1), +rect.height.toFixed(1)],
        drawn: [
            +(canvas.width * scale).toFixed(1),
            +(canvas.height * scale).toFixed(1),
        ],
        upscaled: canvas.classList.contains("cp-spec-canvas--upscaled"),
        readable: +(
            ((rect.width * rect.height * 3) / (stage.width * stage.height)) *
            100
        ).toFixed(0),
        clip: {
            x: Math.max(0, stage.x - 8),
            y: Math.max(0, stage.y - 8),
            width: stage.width + 16,
            height: stage.height + 16,
        },
    };
});
await page.screenshot({ path: path.join(here, out), clip: facts.clip });

// What fraction of a pointer sweep across the stage can be read at all — the number the whole
// change is about, taken from the page rather than asserted.
const readingAt = async (x, y) => {
    await page.mouse.move(x, y);
    await page.waitForTimeout(12);
    return page.evaluate(
        () => document.getElementById("cp-spec-pick")?.textContent ?? "",
    );
};
let blank = 0;
const N = 160;
const midY = facts.clip.y + 8 + facts.box[1] / 2;
for (let i = 0; i <= N; i++) {
    const x = facts.clip.x + 8 + (facts.clip.width - 16) * (i / N);
    if ((await readingAt(x, midY)) === "") blank++;
}
console.log(
    out,
    before
        ? `(before, ${BASE})`
        : short
          ? "(after, short viewport)"
          : "(after)",
    `\n  raster ${facts.raster.join("x")}  box ${facts.box.join("x")}  drawn ${facts.drawn.join("x")}`,
    `\n  aspect drawn ${(facts.drawn[0] / facts.drawn[1]).toFixed(3)} vs raster ${(facts.raster[0] / facts.raster[1]).toFixed(3)}`,
    `\n  nearest-neighbour: ${facts.upscaled}`,
    `\n  sweep across the stage reads nothing for ${((blank / (N + 1)) * 100).toFixed(0)}% of its travel`,
);
await browser.close();
server.close();
