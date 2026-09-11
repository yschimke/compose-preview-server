import { chromium, expect } from "@playwright/test";
import { readFile, writeFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import assert from "node:assert/strict";
import { PNG } from "pngjs";

const [origin, output] = process.argv.slice(2);
const token = process.env.UI_BUILDER_TEST_TOKEN;
assert(origin && output && token);
const sample = JSON.parse(await readFile(`${output}/document.json`, "utf8"));
sample.id = `local-repetition-${Date.now()}`;
const headers = { "Content-Type": "application/json", "X-Compose-Preview-Token": token };
function canonicalNode(node) {
  const copy = structuredClone(node);
  for (const key of ["assetBindings", "tokenBindings"]) {
    if (copy[key] && Object.keys(copy[key]).length === 0) delete copy[key];
  }
  return copy;
}
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
  async function refreshViewport() {
    // Compose's accessibility overlay can retain the previous dock while the canvas has already
    // changed. A normal viewport relayout refreshes that overlay; preserve the evidence dimensions.
    const viewport = page.viewportSize();
    await page.setViewportSize({ ...viewport, width: viewport.width + 1 });
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    await page.setViewportSize(viewport);
  }
  async function ready() {
    await page.waitForFunction(() => document.documentElement.dataset.uiBuilderReady === "true", null, { timeout: 60000 });
  }
  await page.goto(`${origin}/ui-builder/remote-m3/${sample.id}?storage=local&token=${encodeURIComponent(token)}&node=loop`);
  await ready();
  await expect(page.getByText("3 rows", { exact: true })).toBeVisible();
  await page.screenshot({ path: `${output}/repetition-editor.png` });
  const pending = page.waitForResponse(r => r.request().method() === "POST" && new URL(r.url()).pathname === "/api/ui-builder/v1/documents/export.rc");
  await click(page.getByRole("button", { name: /^Preview \(/ }));
  assert.equal((await pending).status(), 200);
  const target = page.getByLabel("Interactive document preview");
  await target.waitFor({ timeout: 30000 });
  for (let row = 0; row < 3; row++) {
    for (const [name, x, expected] of [["green", 28 + row * 8, [0, 255, 0]], ["red", 12, [255, 0, 0]]]) {
      const bounds = await target.boundingBox();
      assert(bounds);
      await page.mouse.click(bounds.x + x * bounds.width / 100, bounds.y + (10 + row * 24) * bounds.height / 120);
      await page.mouse.move(60, 100);
      let actual;
      await expect.poll(async () => {
        const frame = PNG.sync.read(await page.screenshot());
        const offset = (Math.floor(bounds.y + 78 * bounds.height / 120) * frame.width + Math.floor(bounds.x + 30 * bounds.width / 100)) * 4;
        actual = [...frame.data.subarray(offset, offset + 3)];
        return actual.every((value, i) => Math.abs(value - expected[i]) < 16);
      }, { timeout: 15000 }).toBe(true);
      playback.push({ row, name, expected, actual });
      await page.screenshot({ path: `${output}/repetition-preview-${row}-${name}.png` });
    }
  }
  await click(page.getByRole("button", { name: /^Design mode \(/ }));
  async function exportAll(stem, state, color) {
    let posted;
    for (const [format, label] of [["json", "Download JSON"], ["rc", "Download Remote document (.rc)"], ["png", "Download PNG"]]) {
      await page.reload(); await ready();
      await click(page.getByRole("button", { name: /^Export(?:$| \()/ }));
      const item = page.getByLabel(label, { exact: true });
      await item.waitFor();
      await page.screenshot({ path: `${output}/${stem}-${format}-menu.png` });
      const [download, response] = await Promise.all([
        page.waitForEvent("download"),
        page.waitForResponse(r => r.request().method() === "POST" && new URL(r.url()).pathname === `/api/ui-builder/v1/documents/export.${format}`),
        click(item),
      ]);
      assert.equal(response.status(), 200);
      const document = response.request().postDataJSON();
      assert.equal(document.id, sample.id);
      assert.equal(document.stateVariables.page.initialValue, state);
      assert.deepEqual(document.components, sample.components);
      assert.deepEqual(canonicalNode(document.nodes.loop), canonicalNode(sample.nodes.loop));
      assert.deepEqual(canonicalNode(document.nodes.place), canonicalNode(sample.nodes.place));
      if (posted) assert.deepEqual(document, posted);
      posted = document;
      const bytes = await readFile(await download.path());
      const artifact = (await mcp("ui_builder_export_document", { document, format })).artifact;
      assert(!artifact.diagnostics.some(d => d.severity === "error"), JSON.stringify(artifact));
      assert(bytes.equals(Buffer.from(artifact.content, format === "json" ? "utf8" : "base64")));
      const sha256 = createHash("sha256").update(bytes).digest("hex");
      assert.equal(sha256, artifact.contentDigest);
      if (format === "png") {
        const png = PNG.sync.read(bytes);
        assert.equal(png.width, 100); assert.equal(png.height, 120);
        const rgb = (x, y) => [...png.data.subarray((y * png.width + x) * 4, (y * png.width + x) * 4 + 3)];
        assert.deepEqual(rgb(30, 78), color);
        for (let row = 0; row < 3; row++) {
          assert.deepEqual(rgb(12, 10 + row * 24), [255, 0, 0]);
          assert.deepEqual(rgb(28 + row * 8, 10 + row * 24), [0, 255, 0]);
        }
      }
      exports.push({ stem, format, state, sha256, browserMatchesMcp: true });
      await writeFile(`${output}/${stem}.${format}`, bytes);
    }
    await writeFile(`${output}/${stem}.document.json`, JSON.stringify(posted, null, 2) + "\n");
  }
  await exportAll("repetition-initial", 10, [255, 0, 0]);
  await page.reload(); await ready();
  await click(page.getByLabel("Open screen panel"));
  await refreshViewport();
  await click(page.getByRole("button", { name: /^State ·/ }));
  await click(page.getByLabel("Edit state page"));
  const initialValue = page.getByRole("textbox").nth(1);
  await click(initialValue);
  await page.keyboard.press("Meta+A"); await page.keyboard.type("20");
  await expect.poll(() => initialValue.textContent()).toBe("20");
  await click(page.getByLabel("Save state variable"));
  try {
    await page.waitForFunction(() => globalThis.__uiBuilderEditor?.revision === 1);
  } catch (error) {
    await page.screenshot({ path: `${output}/repetition-edit-failure.png` });
    await writeFile(`${output}/repetition-edit-failure.txt`, await page.locator("body").ariaSnapshot());
    throw error;
  }
  await exportAll("repetition-edited", 20, [0, 255, 0]);
  const listed = (await mcp("ui_builder_list_designs")).designs;
  assert(!listed.some(d => d.designId === sample.id || d.id === sample.id));
  const saved = structuredClone(sample);
  saved.id = `mcp-repetition-${Date.now()}`;
  await mcp("ui_builder_create_design", { designId: saved.id, document: saved });
  const data = structuredClone(saved.nodes.loop.properties.data);
  data.values.forEach(row => { row.fields.gap.value += 4; });
  await mcp("ui_builder_apply", { designId: saved.id, operationId: "edit-repeated-rows", baseRevision: 0,
    operations: [{ type: "setProperty", nodeId: "loop", property: "data", value: data }] });
  const changed = (await mcp("ui_builder_get_design", { designId: saved.id })).snapshot.state.document;
  assert.equal(changed.revision, 1);
  assert.deepEqual(changed.nodes.loop.properties.data, data);
  assert.deepEqual(changed.components, sample.components);
  const savedJson = (await mcp("ui_builder_export", { designId: saved.id, revision: 1, format: "json" })).artifact;
  assert(!savedJson.diagnostics.some(d => d.severity === "error"));
  const suppliedJson = (await mcp("ui_builder_export_document", { document: changed, format: "json" })).artifact;
  assert.equal(savedJson.content, suppliedJson.content);
  assert(savedJson.content.includes('"spacedBy":20.0'));
  await writeFile(`${output}/repetition-mcp-edited.document.json`, JSON.stringify(changed, null, 2) + "\n");
  await writeFile(`${output}/repetition-mcp-edited.json`, savedJson.content);
  const mcpAuthoring = { created: true, editedRows: true, revision: changed.revision,
    definitionsRetained: true, savedMatchesSupplied: true, contentDigest: savedJson.contentDigest };
  const kotlin = (await mcp("ui_builder_export", { designId: saved.id, revision: 1, format: "compose" })).artifact;
  assert.deepEqual(kotlin.diagnostics, []);
  assert(kotlin.content.includes(".forEach"));
  assert(kotlin.content.includes("private fun Pair("));
  assert(kotlin.content.includes("UiRows0(4.0f), UiRows0(12.0f), UiRows0(20.0f)"));
  assert(kotlin.content.includes("capture0 = valueChange(page, 10.ri)"));
  assert.equal(kotlin.contentDigest, createHash("sha256").update(kotlin.content).digest("hex"));
  await writeFile(`${output}/repetition-mcp-edited.kt.txt`, kotlin.content);
  // Exercise the saved design in the actual WASM Code pane after authoring its rows through MCP.
  await page.goto(`${origin}/ui-builder/remote-m3/${saved.id}?token=${encodeURIComponent(token)}&node=loop`);
  await ready();
  // The WASM runtime becomes ready before the saved document finishes loading. Wait for the
  // MCP-authored revision so its arrival cannot replace the editor state after opening Code.
  await page.waitForFunction(() => globalThis.__uiBuilderEditor?.revision === 1);
  await click(page.getByLabel("Open code panel", { exact: true }));
  await refreshViewport();
  try {
    await expect(page.getByText(/private fun Pair/)).toBeVisible();
  } catch (error) {
    await page.screenshot({ path: `${output}/repetition-code-failure.png` });
    await writeFile(`${output}/repetition-code-failure.txt`, await page.locator("body").ariaSnapshot());
    throw error;
  }
  const codeText = await page.getByText(/private fun Pair/).innerText();
  await writeFile(`${output}/repetition-browser-code.txt`, codeText);
  const bodyStart = kotlin.content.indexOf("// Generated from a Compose UI builder design.");
  assert(bodyStart >= 0);
  const expectedPane = kotlin.content.slice(bodyStart).replace("package generated.uibuilder\n\n", "");
  assert.equal(codeText.trimEnd(), expectedPane.trimEnd());
  await page.screenshot({ path: `${output}/repetition-remote-kotlin-code.png` });
  mcpAuthoring.kotlin = { revision: 1, sha256: kotlin.contentDigest, typedLoop: true, reusableFunction: true, codePaneVisible: true, browserBodyMatchesMcp: true };
  assert.deepEqual(errors, []);
  await writeFile(`${output}/repetition-verification.json`, JSON.stringify({ exports, playback, localDesignSavedOnServer: false, mcpAuthoring, errors }, null, 2) + "\n");
  console.log(JSON.stringify({ exports, playback, localDesignSavedOnServer: false, mcpAuthoring, errors }));
} finally { await browser.close(); }
