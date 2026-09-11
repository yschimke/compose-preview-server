import { chromium, expect } from "@playwright/test";
import { readFile, writeFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import assert from "node:assert/strict";
import { PNG } from "pngjs";

const [origin, output] = process.argv.slice(2);
const token = process.env.UI_BUILDER_TEST_TOKEN;
assert(origin && output && token, "Pass origin, output directory and UI_BUILDER_TEST_TOKEN");
const sample = JSON.parse(await readFile(`${output}/document.json`, "utf8"));
sample.id = `local-png-${Date.now()}`;
const headers = { "Content-Type": "application/json", "X-Compose-Preview-Token": token };
async function mcp(name, args = {}) {
  const response = await fetch(`${origin}/mcp`, { method: "POST", headers,
    body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name, arguments: args } }) });
  const body = await response.json();
  assert(response.ok && !body.result.isError, JSON.stringify(body));
  return JSON.parse(body.result.content[0].text).response;
}
const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH || undefined,
  args: ["--enable-unsafe-swiftshader", "--use-gl=angle", "--force-renderer-accessibility"] });
try {
  const page = await browser.newPage({ viewport: { width: 1600, height: 1050 }, deviceScaleFactor: 1 });
  const errors = [], exports = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.addInitScript(doc => {
    const key = "ui-builder.local.design." + doc.id;
    if (!localStorage.getItem(key)) localStorage.setItem(key, JSON.stringify({
      schema: "compose-ui-builder-local-design/v1", designId: doc.id, catalogSystemId: "remote-m3",
      seed: doc, seedSequence: 0, log: [], updatedAtEpochMillis: Date.now(),
    }));
  }, sample);
  async function click(locator) {
    const bounds = await locator.boundingBox();
    assert(bounds, "control has no bounds");
    await page.mouse.click(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
    await page.mouse.move(60, 100);
  }
  await page.goto(`${origin}/ui-builder/remote-m3/${sample.id}?storage=local&token=${encodeURIComponent(token)}&node=choice`);
  await page.waitForFunction(() => document.documentElement.dataset.uiBuilderReady === "true", null, { timeout: 60000 });
  async function exportPng(name, state, color) {
    await click(page.getByRole("button", { name: /^Export(?:$| \()/ }));
    const row = page.getByLabel("Download PNG", { exact: true });
    await row.waitFor();
    await page.screenshot({ path: `${output}/${name}-menu.png` });
    const [download, response] = await Promise.all([
      page.waitForEvent("download"),
      page.waitForResponse(r => r.request().method() === "POST" && new URL(r.url()).pathname === "/api/ui-builder/v1/documents/export.png"),
      click(row),
    ]);
    assert.equal(response.status(), 200);
    const posted = response.request().postDataJSON();
    assert.equal(posted.id, sample.id);
    assert.equal(posted.stateVariables.page.initialValue, state);
    await writeFile(`${output}/${name}.document.json`, JSON.stringify(posted, null, 2) + "\n");
    const bytes = await readFile(await download.path());
    const artifact = (await mcp("ui_builder_export_document", { document: posted, format: "png" })).artifact;
    assert(!artifact.diagnostics.some(d => d.severity === "error"), JSON.stringify(artifact));
    assert(bytes.equals(Buffer.from(artifact.content, "base64")));
    const sha256 = createHash("sha256").update(bytes).digest("hex");
    // Chrome may omit a downloaded image body from DevTools. Verify the real downloaded file
    // against the response digest and the independently rendered MCP artifact instead.
    assert.equal(response.headers().etag, `"${sha256}"`);
    assert.equal(sha256, artifact.contentDigest);
    const png = PNG.sync.read(bytes);
    assert.equal(png.width, 360); assert.equal(png.height, 360);
    const rgb = (x, y) => [...png.data.subarray((y * png.width + x) * 4, (y * png.width + x) * 4 + 3)];
    assert.deepEqual(rgb(180, 180), color);
    assert.deepEqual(rgb(24, 180), color);
    assert.notDeepEqual(rgb(23, 180), color);
    assert.deepEqual(rgb(180, 335), color);
    assert.notDeepEqual(rgb(180, 336), color);
    await writeFile(`${output}/${name}.png`, bytes);
    exports.push({ name, state, revision: posted.revision, sha256, browserMatchesMcp: true });
  }
  await exportPng("local-initial", 20, [0, 133, 119]);
  // Reopen the local design to also verify that export did not replace its persisted source.
  await page.reload();
  await page.waitForFunction(() => document.documentElement.dataset.uiBuilderReady === "true", null, { timeout: 60000 });
  await writeFile(`${output}/editor-accessibility.txt`, await page.locator("body").ariaSnapshot());
  await click(page.getByLabel("Open screen panel"));
  await click(page.getByRole("button", { name: /^State ·/ }));
  await click(page.getByLabel("Edit state page"));
  const initialValue = page.getByRole("textbox").nth(1);
  await click(initialValue);
  await page.keyboard.press("Meta+A");
  await page.keyboard.type("30");
  await expect.poll(() => initialValue.textContent()).toBe("30");
  await click(page.getByLabel("Save state variable"));
  await page.waitForFunction(() => globalThis.__uiBuilderEditor?.revision === 1);
  await exportPng("local-edited", 30, [57, 73, 171]);
  assert.notEqual(exports[0].sha256, exports[1].sha256);
  const listed = (await mcp("ui_builder_list_designs")).designs;
  assert(!listed.some(d => d.designId === sample.id || d.id === sample.id));
  assert.deepEqual(errors, []);
  await writeFile(`${output}/browser-verification.json`, JSON.stringify({ exports, savedOnServer: false, errors }, null, 2) + "\n");
  console.log(JSON.stringify({ exports, savedOnServer: false, errors }));
} finally { await browser.close(); }
