import { chromium, expect } from "@playwright/test";
import { writeFile } from "node:fs/promises";
import assert from "node:assert/strict";
import { PNG } from "pngjs";

const [origin, designId, output] = process.argv.slice(2);
const token = process.env.UI_BUILDER_TEST_TOKEN;
assert(origin && designId && output && token);
const browser = await chromium.launch({
  headless: true,
  executablePath: process.env.CHROME_PATH || undefined,
  args: ["--enable-unsafe-swiftshader", "--use-gl=angle", "--force-renderer-accessibility"],
});
let page;
try {
  page = await browser.newPage({ viewport: { width: 1600, height: 1050 }, deviceScaleFactor: 1 });
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.goto(`${origin}/ui-builder/remote-m3/${designId}?token=${encodeURIComponent(token)}&node=choice`);
  async function click(locator) {
    await expect(locator).toBeVisible({ timeout: 60000 });
    const bounds = await locator.boundingBox();
    assert(bounds);
    await page.mouse.click(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
  }
  await click(page.getByRole("button", { name: /^Workspace panes/ }));
  const response = page.waitForResponse(r => r.url().includes("/native-preview") && r.request().method() === "POST", { timeout: 120000 });
  await click(page.getByRole("button", { name: /^Native/ }));
  const native = await response;
  assert.equal(native.status(), 200);
  const result = await native.json();
  assert(!result.compileError, result.compileError);
  const decoded = PNG.sync.read(Buffer.from(result.imageBase64.split(",").at(-1), "base64"));
  assert.equal(decoded.width, decoded.height);
  let observed;
  await expect.poll(async () => {
    const screenshot = PNG.sync.read(await page.screenshot());
    // Compose's image semantics can have no DOM bounds after its popup closes. Verify the
    // actual pixels: the editor and native pane must each contain a large, separate green square.
    const runs = new Map();
    for (let y = 0; y < screenshot.height; y++) {
      let start = -1;
      for (let x = 0; x <= screenshot.width; x++) {
        const i = 4 * (y * screenshot.width + x);
        const green = x < screenshot.width && [0, 133, 119].every((v, c) => Math.abs(screenshot.data[i + c] - v) < 8);
        if (green && start < 0) start = x;
        if (!green && start >= 0) {
          if (x - start > 200) {
            const key = `${start}:${x}`;
            const r = runs.get(key) || { x: start, y, width: x - start, height: 0 };
            r.height++;
            runs.set(key, r);
          }
          start = -1;
        }
      }
    }
    observed = [...runs.values()].filter(r => r.height > 200 && Math.abs(r.width - r.height) < 4).sort((a, b) => a.x - b.x);
    return observed.length === 2 && observed[1].x > observed[0].x + observed[0].width;
  }, { timeout: 20000 }).toBe(true);
  await page.screenshot({ path: `${output}/browser.png` });
  assert.deepEqual(errors, []);
  await writeFile(`${output}/browser-verification.json`, JSON.stringify({ existingWasmApp: true, revision: result.revision, nativeSize: [decoded.width, decoded.height], observed, errors }, null, 2) + "\n");
} catch (error) {
  if (page) {
    await page.screenshot({ path: `${output}/browser-failure.png` });
    await writeFile(`${output}/browser-failure.txt`, await page.locator("body").innerText());
  }
  throw error;
} finally {
  await browser.close();
}
