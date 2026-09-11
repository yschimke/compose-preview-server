import { chromium, expect } from "@playwright/test";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import { fileURLToPath } from "node:url";
import assert from "node:assert/strict";
import { PNG } from "pngjs";

const origin = process.argv[2];
const token = process.env.UI_BUILDER_TEST_TOKEN;
const pendingSave = process.argv[3] === "pending";
assert(origin && token, "Pass the local origin and set UI_BUILDER_TEST_TOKEN");
const output = fileURLToPath(new URL("../docs/design/evidence/ui-builder-unsaved-remote-preview/", import.meta.url)) + (pendingSave ? "pending/" : "");
await mkdir(output, { recursive: true });
const sample = JSON.parse(await readFile(new URL("../docs/design/evidence/ui-builder-live-document-preview/sample.document.json", import.meta.url), "utf8"));
sample.id = `${pendingSave ? "pending" : "unsaved"}-remote-${Date.now()}`;
const headers = { "Content-Type": "application/json", "X-Compose-Preview-Token": token };
const sha = bytes => createHash("sha256").update(bytes).digest("hex");
async function mcp(name, args = {}) {
    const response = await fetch(origin + "/mcp", { method: "POST", headers,
        body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name, arguments: args } }) });
    const body = await response.json();
    assert(response.ok, JSON.stringify(body));
    assert(!body.result.isError, JSON.stringify(body));
    return JSON.parse(body.result.content[0].text);
}
const catalog = (await mcp("ui_builder_list_catalogs")).catalogs.find(c => c.systemId === "remote-m3");
assert(catalog);
sample.catalogPin = catalog.catalogPin;
if (pendingSave) await mcp("ui_builder_create_design", { designId: sample.id, document: sample });
let releaseSave = () => {};
let saveHeld = false;
const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH || undefined,
    args: ["--enable-unsafe-swiftshader", "--use-gl=angle", "--force-renderer-accessibility"] });
try {
    const page = await browser.newPage({ viewport: { width: 1600, height: 1050 }, deviceScaleFactor: 1 });
    const errors = [], requests = [], colors = [];
    page.on("pageerror", error => errors.push(error.message));
    page.on("request", request => {
        if (request.url().includes("/api/ui-builder/v1/")) requests.push({ method: request.method(), path: new URL(request.url()).pathname });
    });
    if (pendingSave) {
        const gate = new Promise(resolve => { releaseSave = resolve; });
        await page.route("**/api/ui-builder/v1/requests*", async route => {
            if (route.request().postDataJSON()?.request?.type === "applyOperation") {
                saveHeld = true;
                await gate;
            }
            await route.continue();
        });
    }
    if (!pendingSave) await page.addInitScript(doc => {
        const key = "ui-builder.local.design." + doc.id;
        if (!localStorage.getItem(key)) localStorage.setItem(key, JSON.stringify({
            schema: "compose-ui-builder-local-design/v1", designId: doc.id,
            catalogSystemId: "remote-m3", seed: doc, seedSequence: 0, log: [], updatedAtEpochMillis: Date.now(),
        }));
    }, sample);
    async function click(locator) {
        const bounds = await locator.boundingBox();
        assert(bounds, "control has no bounds");
        await page.mouse.click(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
        await page.mouse.move(60, 100);
    }
    const target = page.getByLabel("Interactive document preview");
    async function capture(name, expected, afterPopup = false) {
        let observed;
        try { await expect.poll(async () => {
            // A closed Compose popup can leave the preview's accessibility node without bounds.
            // The pane does not move during this save; sample its last measured rectangle then.
            const bounds = afterPopup ? colors.at(-1).observed.bounds : await target.boundingBox();
            if (!bounds) return false;
            const png = PNG.sync.read(await page.screenshot());
            const index = (Math.floor(bounds.y + bounds.height / 2) * png.width + Math.floor(bounds.x + bounds.width / 2)) * 4;
            const color = [...png.data.subarray(index, index + 3)];
            observed = { color, bounds, boundsSource: afterPopup ? "measured-before-popup" : "current-accessibility" };
            return color.every((v, i) => Math.abs(v - expected[i]) < 16);
        }, { timeout: 15000 }).toBe(true); } catch (failure) {
            await page.screenshot({ path: "/tmp/ui-builder-unsaved-failure.png" });
            await writeFile("/tmp/ui-builder-unsaved-failure.json", JSON.stringify({ name, expected, observed }, null, 2));
            throw failure;
        }
        colors.push({ name, expected, observed });
        await page.screenshot({ path: output + name + ".png" });
    }
    async function preview(savedInitial = false) {
        const pending = page.waitForResponse(r => savedInitial
            ? r.url().includes(`/designs/${sample.id}/export.rc?revision=0`)
            : r.request().method() === "POST" && new URL(r.url()).pathname === "/api/ui-builder/v1/documents/export.rc");
        await click(page.getByRole("button", { name: /^Preview \(/ }));
        const response = await pending;
        assert.equal(response.status(), 200, await response.text());
        await target.waitFor({ timeout: 30000 });
        return { bytes: await response.body(), document: savedInitial ? sample : JSON.parse(response.request().postData()) };
    }
    await page.goto(`${origin}/ui-builder/remote-m3/${sample.id}?${pendingSave ? "" : "storage=local&"}token=${encodeURIComponent(token)}&node=choice`);
    await page.waitForFunction(() => document.documentElement.dataset.uiBuilderReady === "true", null, { timeout: 60000 });
    const initial = await preview(pendingSave);
    await capture("local-first", [103, 80, 164]);
    await click(target);
    await capture("local-second", [0, 133, 119]);
    await click(target);
    await capture("local-fallback", [57, 73, 171]);
    await click(page.getByRole("button", { name: /^Design mode \(/ }));
    await click(page.getByLabel("Open screen panel"));
    await click(page.getByRole("button", { name: /^State ·/ }));
    await click(page.getByLabel("Edit state page"));
    await page.screenshot({ path: output + "state-editor.png" });
    // These Compose fields expose textbox roles but no accessible names in this Wasm build.
    // The inspected state form orders its disabled name field, then its initial-value field.
    const initialValue = page.getByRole("textbox").nth(1);
    await click(initialValue);
    await page.keyboard.press("Meta+A");
    await page.keyboard.type("20");
    await expect.poll(() => initialValue.textContent()).toBe("20");
    await page.screenshot({ path: output + "state-editor.png" });
    await click(page.getByLabel("Save state variable"));
    await page.waitForFunction(() => globalThis.__uiBuilderEditor?.revision === 1);
    const changed = await preview();
    if (pendingSave) assert(saveHeld, "The save must still be held while the draft preview compiles");
    await writeFile("/tmp/ui-builder-unsaved-posted.json", JSON.stringify(changed.document, null, 2));
    assert.equal(changed.document.stateVariables.page.initialValue, 20);
    assert(!initial.bytes.equals(changed.bytes));
    await capture("local-edited", [0, 133, 119]);
    const artifacts = {};
    const exportBounds = await page.getByRole("button", { name: /^Export(?:$| \()/ }).boundingBox();
    assert(exportBounds);
    for (const format of ["json", "rc"]) {
        const result = await mcp("ui_builder_export_document", { document: changed.document, format });
        const artifact = result.response.artifact;
        assert(artifact.diagnostics.every(d => d.severity === "info"), JSON.stringify(artifact));
        const bytes = Buffer.from(artifact.content, artifact.encoding === "base64" ? "base64" : "utf8");
        assert.equal(artifact.contentDigest, sha(bytes));
        if (format === "rc") assert(bytes.equals(changed.bytes));
        // The popup temporarily replaces Compose's accessibility tree. The fixed toolbar button
        // stays at its measured location when the popup closes after a download.
        await page.mouse.click(exportBounds.x + exportBounds.width / 2, exportBounds.y + exportBounds.height / 2);
        await page.mouse.move(60, 100);
        await page.waitForTimeout(250); // Let the Compose popup's opening animation finish.
        if (!pendingSave) assert.equal(await page.getByLabel(/^Copy .* link$/).count(), 0);
        await page.screenshot({ path: output + "export-menu.png" });
        const row = page.getByLabel(format === "json" ? "Download JSON" : "Download Remote document (.rc)", { exact: true });
        await row.waitFor();
        const [download] = await Promise.all([page.waitForEvent("download"), click(row)]);
        const downloaded = await readFile(await download.path());
        await page.waitForTimeout(250); // Closing popup animation.
        assert(downloaded.equals(bytes));
        await writeFile(output + `local.${format}`, bytes);
        artifacts[format] = { bytes: bytes.length, sha256: artifact.contentDigest, downloadMatchesMcp: true };
    }
    const listed = (await mcp("ui_builder_list_designs")).response.designs;
    if (pendingSave) {
        const before = (await mcp("ui_builder_get_design", { designId: sample.id })).response.snapshot.state.document;
        assert.equal(before.revision, 0);
        assert.equal(before.stateVariables.page.initialValue, 10);
        const savedExport = page.waitForResponse(r => r.url().includes(`/designs/${sample.id}/export.rc?revision=1`));
        releaseSave();
        const saved = await savedExport;
        assert.equal(saved.status(), 200);
        assert((await saved.body()).equals(changed.bytes));
        await capture("saved-after-pending", [0, 133, 119], true);
    } else {
        assert(!listed.some(d => d.designId === sample.id || d.id === sample.id));
        assert(!requests.some(r => r.path === `/api/ui-builder/v1/designs/${sample.id}/export.rc`));
    }
    assert.deepEqual(errors, []);
    await writeFile(output + "document.json", JSON.stringify(changed.document, null, 2) + "\n");
    const verification = { designId: sample.id, savedOnServer: pendingSave, saveHeldDuringPreview: saveHeld, revision: changed.document.revision,
        initial: { bytes: initial.bytes.length, sha256: sha(initial.bytes) }, artifacts, colors, requests, errors };
    await writeFile(output + "verification.json", JSON.stringify(verification, null, 2) + "\n");
    console.log(JSON.stringify(verification));
} finally { releaseSave(); await browser.close(); }
