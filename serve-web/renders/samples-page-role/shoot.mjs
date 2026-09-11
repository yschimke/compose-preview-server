// Screenshot a component page under the two page ROLES — the ordinary catalog page, and the
// samples page a catalog of call sites declares itself into.
//
//   node shoot.mjs catalog | samples
//
// Both sides are `ServeWeb.viewerPage` output over the production stylesheet, from the two goldens
// `ServeWebFixtureTest` builds out of ONE set of inputs: same preview, same usage source, same
// design reference, differing only in `pageRole`. So the difference in these pictures is the role
// and nothing else.
//
// `/usage/<id>` is stubbed, because the panel is filled by a fetch the harness has no server for —
// and an empty panel would show the layout without the thing it is a layout for. The snippet is a
// plausible sample, not a real one; nothing here claims otherwise.
import { chromium } from "playwright";
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
const [, , which = "samples"] = process.argv;
const file = path.join(
    pages,
    which === "samples"
        ? "serve-viewer-samples.html"
        : "serve-viewer-samples-as-catalog.html",
);

const SNIPPET = [
    "@Sampled",
    "@Composable",
    "fun ButtonSample() {",
    "    Button(onClick = { /* handle the click */ }) {",
    "        Icon(Icons.Filled.Favorite, contentDescription = null)",
    "        Spacer(Modifier.size(ButtonDefaults.IconSpacing))",
    "        Text(\"Like\")",
    "    }",
    "}",
].join("\n");

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
    viewport: { width: 1280, height: 900 },
    colorScheme: "light",
});
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
await tab.route("**/usage/**", (route) =>
    route.fulfill({
        contentType: "application/json",
        // `UsageSnippetResponse`'s own shape — `text`, plus the entry the panel names above it.
        body: JSON.stringify({
            text: SNIPPET,
            entryFunction: "ButtonSample",
            scaffoldsDeclared: true,
        }),
    }),
);
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
await tab.waitForTimeout(1200);
await tab.mouse.move(2, 2);
await tab.waitForTimeout(200);

// From the title down through the stage: the CONTROLS row has to be in frame, because half of
// what the role does is to the chips on it — the Compare group is there on one side and gone on
// the other, and a crop of the stage alone would show only the other half.
const head = await tab.locator(".cp-preview-head").boundingBox();
const stage = await tab.locator(".cp-viewer").boundingBox();
const top = Math.max(0, head.y - 10);
await tab.screenshot({
    path: path.join(here, `page-${which}.png`),
    clip: {
        x: Math.max(0, stage.x - 8),
        y: top,
        width: Math.min(stage.width + 16, 1280),
        height: Math.min(stage.y + stage.height + 10 - top, 900),
    },
    animations: "disabled",
});
console.log(`page-${which}: ${Math.round(stage.width)} wide, ${Math.round(stage.y + stage.height - top)} tall`);
await browser.close();
