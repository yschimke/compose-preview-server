// Screenshot the design-page CANVAS of of a running `compose-preview-server serve`, framed on the
// drawing itself (`#cp-design-page`) because the scene -- the plates, and how the export
// composites over them -- is the whole subject.
//
// The two bundles differ only in their manifest: `plates/` carries `assets` and a page
// `background`, `flat/` carries neither. So the pair is a before/after of the same sheet as the
// code sees it, not two different sheets.
import { chromium } from "playwright";

const [, , url, out] = process.argv;

const browser = await chromium.launch({ executablePath: "/opt/pw-browsers/chromium" });
const page = await browser.newPage({ viewport: { width: 1180, height: 760 }, deviceScaleFactor: 2 });
await page.goto(url, { waitUntil: "networkidle" });
// The plates are <img>; a screenshot taken before they decode captures the very hole this
// change exists to fill.
await page.waitForFunction(
  () => Array.from(document.images).every((i) => i.complete && i.naturalWidth > 0),
  null,
  { timeout: 15000 },
);
await page.waitForTimeout(500);
const shot = page.locator(".cp-page-canvas");
await ((await shot.count()) ? shot.first() : page).screenshot({ path: out });
await browser.close();
console.log("wrote", out);
