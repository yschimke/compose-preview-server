// Screenshot the report launcher on a phone, before and after issue #801.
//
// Same fixture as `spec-lane-eyedropper`: the real `/remote-m3/p/appcard__ideal__default__compact`
// page as preview.coo.ee served it, at the reporter's own 411x785 at device pixel ratio 2.625. The
// launcher is the server's own markup and the working tree's own `serve.css`; nothing here places
// it. That is the point — where the button lands is the browser's answer, not this script's.
//
//   node shoot.mjs after.png            # the launcher as this branch serves it
//   node shoot.mjs before.png --before  # the same page with the fix backed out
//
// `--before` restores the one declaration the fix added, by putting `.cp-spec-pick-live` back at
// its static position (`left: auto; top: auto`). That IS the old CSS: the span was clipped but not
// anchored, so it sat at the far end of the horizontally scrolling controls row, outside that row's
// clip, and stretched the document — and with it the phone's layout viewport — to 764px. Every
// `position: fixed; right:` then resolved against 764 instead of 411.
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
await new Promise((resolve) => server.listen(8796, resolve));

const chrome = [
    "/opt/pw-browsers/chromium/chrome-linux/chrome",
    "/opt/pw-browsers/chromium-1194/chrome-linux/chrome",
    "/opt/pw-browsers/chromium",
].find((candidate) => fs.existsSync(candidate));
const browser = await chromium.launch(
    chrome ? { executablePath: chrome } : {},
);
const context = await browser.newContext({
    // The reporter's own device, out of the issue's Browser table.
    viewport: { width: 411, height: 785 },
    deviceScaleFactor: 2.625,
    isMobile: true,
    hasTouch: true,
    colorScheme: "light",
    userAgent:
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36",
});
const page = await context.newPage();
await page.goto(`http://127.0.0.1:8796${PAGE}`, { waitUntil: "networkidle" });
if (before) {
    await page.addStyleTag({
        content: ".cp-spec-pick-live { left: auto; top: auto; }",
    });
}
await page.waitForTimeout(1200);

// What the shot is of, stated as numbers as well: the layout viewport the page ended up with, and
// where the launcher landed inside the 411px the visitor can actually see.
const measured = await page.evaluate(() => {
    const fab = document.querySelector(".cp-fab");
    const rect = fab?.getBoundingClientRect();
    const screen = document.documentElement.clientWidth;
    return {
        layoutViewport: window.innerWidth,
        screen,
        fab: rect ? { left: Math.round(rect.left), right: Math.round(rect.right) } : null,
        onScreen: !!rect && rect.left >= 0 && rect.right <= screen,
    };
});
await page.screenshot({ path: path.join(here, out) });
console.log(out, before ? "(before)" : "(after)", measured);
await browser.close();
server.close();
