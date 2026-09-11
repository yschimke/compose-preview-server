import { chromium, expect } from "@playwright/test";
import { readFile, writeFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import assert from "node:assert/strict";
import { PNG } from "pngjs";

const [origin, output] = process.argv.slice(2);
const token = process.env.UI_BUILDER_TEST_TOKEN;
const decimalSelection = process.env.VERIFY_REMOTE_FLOAT_BROWSER === "true";
const initialState = decimalSelection ? 2.5 : 20;
const editedState = decimalSelection ? 3.75 : 30;
const editModifiers = process.env.VERIFY_REMOTE_MODIFIER_BROWSER === "true";
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
  const errors = [], exports = [], playback = [];
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
  if (decimalSelection) {
    const pending = page.waitForResponse(r => r.request().method() === "POST" && new URL(r.url()).pathname === "/api/ui-builder/v1/documents/export.rc");
    await click(page.getByRole("button", { name: /^Preview \(/ }));
    const response = await pending;
    assert.equal(response.status(), 200);
    const target = page.getByLabel("Interactive document preview");
    await target.waitFor({ timeout: 30000 });
    for (const [name, expected] of [["second", [0, 133, 119]], ["fallback", [57, 73, 171]], ["first", [103, 80, 164]], ["second-again", [0, 133, 119]]]) {
      if (playback.length) await click(target);
      let actual;
      await expect.poll(async () => {
        const bounds = await target.boundingBox();
        if (!bounds) return false;
        const frame = PNG.sync.read(await page.screenshot());
        const offset = (Math.floor(bounds.y + bounds.height / 2) * frame.width + Math.floor(bounds.x + bounds.width / 2)) * 4;
        actual = [...frame.data.subarray(offset, offset + 3)];
        return actual.every((value, i) => Math.abs(value - expected[i]) < 16);
      }, { timeout: 15000 }).toBe(true);
      playback.push({ name, expected, actual });
      await page.screenshot({ path: `${output}/decimal-preview-${name}.png` });
    }
    await click(page.getByRole("button", { name: /^Design mode \(/ }));
  }
  async function exportPng(name, state, color, topPadding = 24) {
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
    assert.equal(posted.nodes.choice.modifiers.find(m => m.type === "padding").topDp, topPadding);
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
    assert.deepEqual(rgb(180, topPadding), color);
    assert.notDeepEqual(rgb(180, topPadding - 1), color);
    assert.deepEqual(rgb(180, 335), color);
    assert.notDeepEqual(rgb(180, 336), color);
    await writeFile(`${output}/${name}.png`, bytes);
    const formats = {};
    if (decimalSelection) {
      for (const [format, label] of [["json", "Download JSON"], ["rc", "Download Remote document (.rc)"]]) {
        // A fresh load restores the Compose accessibility tree after Chrome handles a download,
        // and verifies that all artifacts still use the persisted local edit.
        await page.reload();
        await page.waitForFunction(() => document.documentElement.dataset.uiBuilderReady === "true", null, { timeout: 60000 });
        await click(page.getByRole("button", { name: /^Export(?:$| \()/ }));
        const item = page.getByLabel(label, { exact: true });
        await item.waitFor();
        const [file, reply] = await Promise.all([
          page.waitForEvent("download"),
          page.waitForResponse(r => r.request().method() === "POST" && new URL(r.url()).pathname === `/api/ui-builder/v1/documents/export.${format}`),
          click(item),
        ]);
        assert.equal(reply.status(), 200);
        assert.deepEqual(reply.request().postDataJSON(), posted);
        const content = await readFile(await file.path());
        const other = (await mcp("ui_builder_export_document", { document: posted, format })).artifact;
        assert(!other.diagnostics.some(d => d.severity === "error"));
        assert(content.equals(Buffer.from(other.content, format === "rc" ? "base64" : "utf8")));
        const digest = createHash("sha256").update(content).digest("hex");
        assert.equal(digest, other.contentDigest);
        if (format === "json") assert.equal(JSON.parse(content).compilerProfile, "compose-preview-state-v1");
        formats[format] = { sha256: digest, browserMatchesMcp: true };
        await writeFile(`${output}/${name}.${format}`, content);
      }
    }
    exports.push({ name, state, topPadding, revision: posted.revision, sha256, browserMatchesMcp: true, ...(decimalSelection ? { formats } : {}) });
  }
  await exportPng("local-initial", initialState, [0, 133, 119]);
  // Reopen the local design to also verify that export did not replace its persisted source.
  await page.reload();
  await page.waitForFunction(() => document.documentElement.dataset.uiBuilderReady === "true", null, { timeout: 60000 });
  await writeFile(`${output}/editor-accessibility.txt`, await page.locator("body").ariaSnapshot());
  if (editModifiers) {
    await page.mouse.move(1460, 820);
    await page.mouse.wheel(0, 570);
    const top = page.getByLabel("Top value", { exact: true });
    await top.waitFor();
    await page.screenshot({ path: `${output}/modifier-before.png` });
    await click(top); await page.keyboard.press("Meta+A"); await page.keyboard.type("40");
    await page.keyboard.press("Enter");
    await page.waitForFunction(() => globalThis.__uiBuilderEditor?.revision === 1);
    await page.screenshot({ path: `${output}/modifier-after.png` });
    await exportPng("local-edited", initialState, [0, 133, 119], 40);
  } else {
    await click(page.getByLabel("Open screen panel"));
    await click(page.getByRole("button", { name: /^State ·/ }));
    await click(page.getByLabel("Edit state page"));
    const initialValue = page.getByRole("textbox").nth(1);
    await click(initialValue);
    await page.keyboard.press("Meta+A");
    await page.keyboard.type(String(editedState));
    await expect.poll(() => initialValue.textContent()).toBe(String(editedState));
    await click(page.getByLabel("Save state variable"));
    await page.waitForFunction(() => globalThis.__uiBuilderEditor?.revision === 1);
    await exportPng("local-edited", editedState, [57, 73, 171]);
  }
  assert.notEqual(exports[0].sha256, exports[1].sha256);
  const listed = (await mcp("ui_builder_list_designs")).designs;
  assert(!listed.some(d => d.designId === sample.id || d.id === sample.id));
  assert.deepEqual(errors, []);
  await writeFile(`${output}/browser-verification.json`, JSON.stringify({ exports, savedOnServer: false, errors, ...(decimalSelection ? { playback } : {}) }, null, 2) + "\n");
  console.log(JSON.stringify({ exports, savedOnServer: false, errors, ...(decimalSelection ? { playback } : {}) }));
} finally { await browser.close(); }
