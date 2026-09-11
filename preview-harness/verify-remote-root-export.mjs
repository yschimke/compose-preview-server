import { chromium } from "@playwright/test";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { createHash } from "node:crypto";
import assert from "node:assert/strict";

const origin = process.argv[2];
const token = process.env.UI_BUILDER_TEST_TOKEN;
assert(origin && token, "Pass the local server origin and set UI_BUILDER_TEST_TOKEN");
const output = fileURLToPath(new URL("../docs/design/evidence/ui-builder-remote-root-source/", import.meta.url));
const sample = JSON.parse(await readFile(new URL("../docs/design/evidence/ui-builder-live-document-preview/sample.document.json", import.meta.url), "utf8"));
const source = await readFile(new URL("../docs/design/fixtures/ui-builder/remote-root.kt.txt", import.meta.url), "utf8");
const headers = { "Content-Type": "application/json", "X-Compose-Preview-Token": token };
async function mcp(name, args) {
    const response = await fetch(origin + "/mcp", { method: "POST", headers,
        body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name, arguments: args } }) });
    const body = await response.json();
    assert(response.ok, JSON.stringify(body));
    const result = body.result;
    assert(!result.isError, JSON.stringify(result));
    return JSON.parse(result.content[0].text);
}
const catalog = (await mcp("ui_builder_list_catalogs", {})).catalogs.find(c => c.systemId === "remote-m3");
assert(catalog);
sample.id = "remote-root-" + Date.now();
await mkdir(output, { recursive: true });
sample.catalogPin = catalog.catalogPin;
await mcp("ui_builder_create_design", { designId: sample.id, document: sample });
const artifact = (await mcp("ui_builder_export", { designId: sample.id, revision: 0, format: "compose" })).response.artifact;
assert.deepEqual(artifact.diagnostics, []);
// Only the design-id metadata differs from the exact source compiled in the Android proof.
const expectedSource = source.replace("design remote-live-preview revision 0", `design ${sample.id} revision 0`);
assert(artifact.content.endsWith(expectedSource), artifact.content);
const exported = {};
for (const format of ["json", "rc"]) {
    const result = (await mcp("ui_builder_export", { designId: sample.id, revision: 0, format })).response.artifact;
    assert(result.diagnostics.every(d => d.severity === "info"), JSON.stringify(result.diagnostics));
    assert(result.diagnostics.some(d => d.code === "REVISION_PINNED_REMOTE_EXPORT"));
    const bytes = Buffer.from(result.content, result.encoding === "base64" ? "base64" : "utf8");
    assert.equal(result.contentDigest, createHash("sha256").update(bytes).digest("hex"));
    exported[format] = { bytes: bytes.length, sha256: result.contentDigest };
    await writeFile(output + `sample.${format}`, bytes);
}
await writeFile(output + "sample.kt.txt", artifact.content);
assert.equal(artifact.contentDigest, createHash("sha256").update(artifact.content).digest("hex"));
const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH || undefined,
    args: ["--enable-unsafe-swiftshader", "--use-gl=angle", "--force-renderer-accessibility"] });
try {
    const page = await browser.newPage({ viewport: { width: 1600, height: 1050 }, deviceScaleFactor: 1 });
    const errors = [];
    page.on("pageerror", error => errors.push(error.message));
    await page.goto(`${origin}/ui-builder/remote-m3/${sample.id}?token=${encodeURIComponent(token)}&node=choice`);
    await page.waitForFunction(() => document.documentElement.dataset.uiBuilderReady === "true", null, { timeout: 60000 });
    const button = page.getByRole("button", { name: /^Code/ });
    const bounds = await button.boundingBox();
    assert(bounds);
    await page.mouse.click(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
    await page.mouse.move(60, 100);
    // Highlighted source is drawn by Compose. Capture its completed frames for visual review;
    // exact source equality and executable behavior are asserted by MCP and the compiled fixture.
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    await page.screenshot({ path: output + "browser-code.png" });
    assert.deepEqual(errors, []);
    const verification = { designId: sample.id, revision: 0, catalogPin: sample.catalogPin,
        bytes: Buffer.byteLength(artifact.content), sha256: artifact.contentDigest,
        sourceMatchesCompiledFixtureExceptDesignId: true, exported, browserCodePaneCaptured: true, errors };
    await writeFile(output + "verification.json", JSON.stringify(verification, null, 2) + "\n");
    console.log(JSON.stringify(verification));
} finally { await browser.close(); }
