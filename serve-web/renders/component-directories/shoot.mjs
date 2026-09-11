// Screenshot the viewer's component drawer, with and without the subtree DIRECTORIES — the named
// groups that hold a component's recordings and the catalogs that are about it.
//
//   node shoot.mjs before | after
//
// Both sides are `ServeWeb.viewerPage` output over the production stylesheet; nothing is drawn by
// hand. `before` reads BOTH the page and the stylesheet out of git (`BEFORE_REF`, default
// `origin/main`), so it is literally what this fixture rendered before the change — the same
// component, the same two published motion captures, the same catalog. `after` reads the working
// tree. The drawer is opened on both, because it is closed at rest and this is a picture of what is
// inside it.
import { chromium } from "playwright";
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
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

const fromGit = (repoPath) =>
    execFileSync("git", ["show", `${BASE}:${repoPath}`], {
        cwd: repo,
        maxBuffer: 32 * 1024 * 1024,
    });

// The before page has to exist as a FILE for `file://`, and it is a build artifact of this script
// rather than something to commit twice — the golden it came from is already in the tree.
const file = before
    ? (() => {
          const dir = fs.mkdtempSync(path.join(os.tmpdir(), "cp-dirs-"));
          const out = path.join(dir, "serve-viewer.html");
          fs.writeFileSync(out, fromGit("preview-harness/fixtures/pages/serve-viewer.html"));
          return out;
      })()
    : path.join(pages, "serve-viewer.html");

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
        ? route.fulfill({
              body: fromGit(
                  "server/src/main/resources/ee/schimke/composeai/cli/serve/assets/serve.css",
              ),
              contentType: "text/css",
          })
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
await tab.goto(`file://${file}`);
await tab.waitForTimeout(800);
await tab.click("#cp-nav-toggle");
await tab.waitForTimeout(400);
await tab.mouse.move(2, 2);
await tab.waitForTimeout(150);

const box = await tab.locator("#cp-nav").boundingBox();
await tab.screenshot({
    path: path.join(here, `drawer-${which}.png`),
    clip: {
        x: Math.max(0, box.x - 8),
        y: Math.max(0, box.y - 8),
        width: Math.min(box.width + 16, 1024),
        height: Math.min(box.height + 16, 900),
    },
    animations: "disabled",
});
console.log(`drawer-${which}: ${Math.round(box.width)}×${Math.round(box.height)}`);
await browser.close();
