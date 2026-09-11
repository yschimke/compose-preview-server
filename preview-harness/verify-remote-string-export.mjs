import { chromium, expect } from "@playwright/test";
import { readFile, writeFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import assert from "node:assert/strict";

const [origin, output] = process.argv.slice(2);
const token = process.env.UI_BUILDER_TEST_TOKEN;
assert(origin && output && token);
const doc = JSON.parse(await readFile(`${output}/document.json`, "utf8"));
const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH || undefined,
  args: ["--enable-unsafe-swiftshader", "--use-gl=angle", "--force-renderer-accessibility"] });
try {
  const page = await browser.newPage({ viewport: { width: 1600, height: 1050 }, deviceScaleFactor: 1 });
  const errors = [], artifacts = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.addInitScript(doc => {
    const key = "ui-builder.local.design." + doc.id;
    if (!localStorage.getItem(key)) localStorage.setItem(key, JSON.stringify({
      schema: "compose-ui-builder-local-design/v1", designId: doc.id, catalogSystemId: "remote-m3",
      seed: doc, seedSequence: 0, log: [], updatedAtEpochMillis: Date.now(),
    }));
  }, doc);
  const ready = () => page.waitForFunction(() => document.documentElement.dataset.uiBuilderReady === "true", null, { timeout: 60000 });
  async function click(locator) {
    await locator.waitFor();
    const bounds = await locator.boundingBox(); assert(bounds);
    await page.mouse.click(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
    await page.mouse.move(60, 100);
  }
  async function mcp(name, args = {}) {
    const response = await fetch(`${origin}/mcp`, { method: "POST",
      headers: { "Content-Type": "application/json", "X-Compose-Preview-Token": token },
      body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name, arguments: args } }) });
    const body = await response.json(); assert(response.ok && !body.result.isError, JSON.stringify(body));
    return JSON.parse(body.result.content[0].text).response;
  }
  await page.goto(`${origin}/ui-builder/remote-m3/${doc.id}?storage=local&token=${encodeURIComponent(token)}&node=choice`);
  await ready();
  await click(page.getByLabel("Open screen panel"));
  await click(page.getByRole("button", { name: /^State ·/ }));
  await click(page.getByLabel("Edit state label"));
  const value = page.getByRole("textbox").nth(1);
  await expect.poll(() => value.textContent()).toBe("Ready");
  await page.screenshot({ path: `${output}/browser-before.png` });
  await click(value); await page.keyboard.press("Meta+A"); await page.keyboard.type("@second");
  await expect.poll(() => value.textContent()).toBe("@second");
  await click(page.getByLabel("Save state variable"));
  await page.waitForFunction(() => globalThis.__uiBuilderEditor?.revision === 1);
  await page.screenshot({ path: `${output}/browser-state.png` });
  for (const format of ["json", "rc"]) {
    await click(page.getByRole("button", { name: /^Export(?:$| \()/ }));
    const row = page.getByLabel(format === "json" ? "Download JSON" : "Download Remote document (.rc)", { exact: true });
    const [download, response] = await Promise.all([
      page.waitForEvent("download"),
      page.waitForResponse(r => r.request().method() === "POST" && new URL(r.url()).pathname === `/api/ui-builder/v1/documents/export.${format}`),
      click(row),
    ]);
    assert.equal(response.status(), 200);
    const posted = response.request().postDataJSON();
    assert.equal(posted.stateVariables.label.initialValue, "@second");
    assert.equal(posted.stateVariables.second.initialValue, "Ready");
    const bytes = await readFile(await download.path());
    const artifact = (await mcp("ui_builder_export_document", { document: posted, format })).artifact;
    assert(!artifact.diagnostics.some(d => d.severity === "error"), JSON.stringify(artifact));
    const expected = Buffer.from(artifact.content, artifact.encoding === "base64" ? "base64" : "utf8");
    assert(bytes.equals(expected));
    const digest = createHash("sha256").update(bytes).digest("hex");
    assert.equal(digest, artifact.contentDigest);
    await writeFile(`${output}/browser.${format}`, bytes);
    await writeFile(`${output}/browser.document.json`, JSON.stringify(posted, null, 2) + "\n");
    artifacts.push({ format, bytes: bytes.length, sha256: digest, browserMatchesMcp: true });
    await page.reload(); await ready();
  }
  assert(!(await mcp("ui_builder_list_designs")).designs.some(d => d.id === doc.id || d.designId === doc.id));
  assert.deepEqual(errors, []);
  const verification = { artifacts, initialText: "@second", otherInitialText: "Ready", savedOnServer: false, errors };
  await writeFile(`${output}/browser-verification.json`, JSON.stringify(verification, null, 2) + "\n");
  console.log(JSON.stringify(verification));
} finally { await browser.close(); }
