// Screenshot the spec lane's readout showing and hiding, before and after issue #464.
//
// The fault is a LAYOUT one: hovering the comparison put a reading in the lane's reserved row, the
// row's text widened the lane, the lane re-wrapped `.cp-preview-primary`, and the stage moved down
// 6px — under the cursor, on every hover, and back again on every leave. So each shot is TWO
// frames of the same page rectangle, pointer away and pointer on the render panel, stacked with a
// rule drawn at one fixed page y across both. In `before.png` the panels sit at different heights
// either side of that rule; in `after.png` they do not move.
//
//   node shoot.mjs after.png
//   node shoot.mjs before.png --before   # `serve.css` as `git show` hands it back from the base
//   node shoot.mjs --record              # refresh fixture/ from the live server
//
// `fixture/` is `/wear-m3-catalog/p/button-compact__ideal__filled-variant-icon-only` — the page the
// issue was reported from — recorded off preview.coo.ee with every response it made, so ordinary
// runs are offline and deterministic. Only `serve.css` and the script bundles come from the working
// tree, by basename, so the shots follow the sheet being edited. The viewport is the reporter's own
// 1280x683 at devicePixelRatio 2, and it is load-bearing: the lane has to be narrow enough to leave
// the SVG and 3D toggles beside it, which is what the widened lane pushes onto a new line.
import { chromium } from "playwright";
import { execFile, execFileSync } from "node:child_process";
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
const ORIGIN = "https://preview.coo.ee";
const PAGE =
    "/wear-m3-catalog/p/button-compact__ideal__filled-variant-icon-only";
const BASE = process.env.CP_BEFORE_REF ?? "HEAD";
const argv = process.argv.slice(2);
const record = argv.includes("--record");
const before = argv.includes("--before");
const out = argv.find((a) => a.endsWith(".png")) ?? "after.png";

// The recording is a readable tree plus one manifest: bodies land at the path that served them,
// and `index.json` maps the full URL — query string included — to the file and its content type. A
// second URL onto the same path (a different `?thumb=`) gets a numbered neighbour rather than
// silently overwriting the first.
const indexPath = path.join(fixture, "index.json");
// Held in memory and written once, at the end of a recording: the page fires its requests
// concurrently, and a read-modify-write per response loses all but the last few.
const index = fs.existsSync(indexPath)
    ? JSON.parse(fs.readFileSync(indexPath, "utf8"))
    : {};
const slotFor = (url) => {
    const base = path.join(fixture, decodeURIComponent(url.split("?")[0]));
    let file = base === fixture ? path.join(fixture, "index.html") : base;
    const taken = new Set(Object.values(index).map((e) => e.file));
    for (let n = 2; taken.has(path.relative(fixture, file)); n++)
        file = `${base}.${n}`;
    return file;
};

const fetchUp = (url) =>
    new Promise((resolve) => {
        execFile(
            "curl",
            ["-sS", "--max-time", "60", "-D", "-", ORIGIN + url],
            { encoding: "buffer", maxBuffer: 64 * 1024 * 1024 },
            (error, stdout) => {
                if (error) return resolve(null);
                // `curl -D -` writes one header block per response in the chain; the body follows
                // the last of them.
                const text = stdout.toString("latin1");
                const cut = text.lastIndexOf("\r\n\r\n");
                const kinds = [
                    ...text
                        .slice(0, cut)
                        .matchAll(/content-type:\s*([^\r\n]+)/gi),
                ];
                resolve({
                    body: stdout.subarray(
                        Buffer.byteLength(text.slice(0, cut + 4), "latin1"),
                    ),
                    type: kinds.length
                        ? kinds[kinds.length - 1][1]
                        : "application/octet-stream",
                });
            },
        );
    });

// The one sheet the fix touches, as the base commit has it. Read through `git show` rather than a
// checkout, so the working tree stays where it is.
const baseCss = before
    ? execFileSync(
          "git",
          [
              "show",
              `${BASE}:server/src/main/resources/ee/schimke/composeai/cli/serve/assets/serve.css`,
          ],
          { cwd: here, maxBuffer: 32 * 1024 * 1024 },
      )
    : null;

const types = { ".css": "text/css", ".js": "text/javascript" };
const server = http.createServer(async (request, response) => {
    const url = request.url ?? "/";
    const clean = decodeURIComponent(url.split("?")[0]);
    const name = path.basename(clean);
    if (baseCss && name === "serve.css") {
        response.writeHead(200, { "content-type": "text/css" });
        return void response.end(baseCss);
    }
    if (clean.startsWith("/assets/")) {
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
    if (!record && entry) {
        response.writeHead(200, { "content-type": entry.type });
        return void response.end(
            fs.readFileSync(path.join(fixture, entry.file)),
        );
    }
    if (!record) return void response.writeHead(404).end("not recorded");
    const up = await fetchUp(url);
    if (!up) return void response.writeHead(502).end("upstream");
    const file = slotFor(url);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, up.body);
    index[url] = { file: path.relative(fixture, file), type: up.type };
    response.writeHead(200, { "content-type": up.type });
    response.end(up.body);
});
await new Promise((resolve) => server.listen(8803, resolve));

const browser = await chromium.launch({
    executablePath: "/opt/pw-browsers/chromium",
});
const context = await browser.newContext({
    viewport: { width: 1280, height: 683 },
    deviceScaleFactor: 2,
    colorScheme: "light",
});
const page = await context.newPage();
await page.goto(`http://127.0.0.1:8803${PAGE}?mode=spec`, {
    waitUntil: "networkidle",
    timeout: 90000,
});
// The component nav opens over the stage at this width and is not what these shots are about.
await page.evaluate(() => {
    for (const node of document.querySelectorAll(".cp-nav, .cp-drawer"))
        node.remove();
});
await page.waitForTimeout(2500);

if (record) {
    fs.writeFileSync(
        indexPath,
        JSON.stringify(
            Object.fromEntries(Object.entries(index).sort()),
            null,
            2,
        ) + "\n",
    );
    console.log("recorded", Object.keys(index).length, "responses");
    await browser.close();
    server.close();
    process.exit(0);
}

// The rectangle both frames are cropped to: the controls the lane sits in, down through the stage.
const clip = await page.evaluate(() => {
    const primary = document.querySelector(".cp-preview-primary");
    const stage = document.getElementById("cp-spec-compare");
    const a = primary.getBoundingClientRect();
    const b = stage.getBoundingClientRect();
    return {
        x: Math.max(0, a.left - 10),
        y: Math.max(0, a.top - 10),
        width: Math.max(a.width, b.width) + 20,
        height: b.bottom - a.top + 24,
    };
});
const stageY = () =>
    page.evaluate(
        () =>
            +document
                .getElementById("cp-spec-compare")
                .getBoundingClientRect()
                .y.toFixed(1),
    );

const idleY = await stageY();
// The rule is drawn INTO the page, `position: fixed` at one viewport y, before either frame is
// taken — so both shots carry it at the same page coordinate and nothing in the composite depends
// on this script's own arithmetic. Where the stage sits against it is then the page's answer.
await page.evaluate((y) => {
    const rule = document.createElement("div");
    rule.style.cssText = `position:fixed;left:0;right:0;top:${y}px;height:0;z-index:9;
        border-top:1.5px dashed #d32f2f;pointer-events:none`;
    document.body.append(rule);
}, idleY);
const away = await page.screenshot({ clip });
const panel = await page.locator("#cp-spec-actual").boundingBox();
await page.mouse.move(
    panel.x + panel.width * 0.5,
    panel.y + panel.height * 0.5,
);
await page.waitForTimeout(400);
const over = await page.screenshot({ clip });
const hoverY = await stageY();
const reading = await page.evaluate(
    () => document.getElementById("cp-spec-pick")?.textContent ?? null,
);

// Stack the two frames with a rule at one fixed y across both, so a stage that moved reads as the
// panels sitting differently against it. Composed in the browser because the shots are already
// there; nothing is redrawn, only placed.
const composite = await context.newPage();
await composite.setViewportSize({
    width: Math.ceil(clip.width),
    height: Math.ceil(clip.height * 2) + 66,
});
await composite.setContent(`
  <style>
    body { margin: 0; background: #fff; font: 12px system-ui, sans-serif; color: #333; }
    figure { margin: 0; }
    figcaption { padding: 4px 10px; font-weight: 600; }
    img { display: block; width: ${clip.width}px; }
  </style>
  <figure><figcaption>pointer away — no reading</figcaption>
    <img src="data:image/png;base64,${away.toString("base64")}"></figure>
  <figure><figcaption>pointer on the render panel — a reading in the row</figcaption>
    <img src="data:image/png;base64,${over.toString("base64")}"></figure>
`);
await composite.waitForTimeout(200);
await composite.screenshot({ path: path.join(here, out), fullPage: true });

console.log(
    out,
    before ? `(before, ${BASE})` : "(after)",
    `\n  stage y: ${idleY} away -> ${hoverY} hovering`,
    idleY === hoverY
        ? "(still)"
        : `*** moved ${(hoverY - idleY).toFixed(1)}px ***`,
    `\n  reading: ${JSON.stringify(reading)}`,
);
await browser.close();
server.close();
