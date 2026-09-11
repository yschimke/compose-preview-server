import { chromium, expect } from "@playwright/test";
import { readFile, writeFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import { fileURLToPath } from "node:url";
import assert from "node:assert/strict";
import { PNG } from "pngjs";

const origin = process.argv[2];
const stem = process.argv[3] ?? "sample";
assert(["sample", "sample-density-2"].includes(stem));
const suffix = stem === "sample" ? "" : "-density-2";
const token = process.env.UI_BUILDER_TEST_TOKEN;
assert(origin && token, "Set UI_BUILDER_TEST_TOKEN and pass the local server origin");
const directory = fileURLToPath(new URL("../docs/design/evidence/ui-builder-live-document-preview/", import.meta.url));
const sample = JSON.parse(await readFile(directory + stem + ".document.json", "utf8"));
sample.id = `remote-live-preview${suffix}-${Date.now()}`;
const headers = { "Content-Type": "application/json", "X-Compose-Preview-Token": token };
const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH || undefined,
    args: ["--enable-unsafe-swiftshader", "--use-gl=angle", "--force-renderer-accessibility"] });
const errors = [];
const sha = bytes => createHash("sha256").update(bytes).digest("hex");
try {
    const page = await browser.newPage({ viewport: { width: 1600, height: 1050 }, deviceScaleFactor: 1 });
    // Compose's accessibility nodes sit behind its canvas; send input to their measured positions.
    async function click(locator) {
        const bounds = await locator.boundingBox();
        assert(bounds);
        await page.mouse.click(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
        // Capture the branch's authored paint after the player's normal hover indication clears.
        await page.mouse.move(60, 100);
    }
    page.on("pageerror", error => errors.push(error.message));
    const created = await page.request.post(origin + "/api/ui-builder/v1/requests", {
        headers, data: { schemaVersion: 1, requestId: sample.id, actorId: "operator",
            request: { type: "createDesign", document: sample } },
    });
    assert(created.ok(), await created.text());
    assert.equal((await created.json()).response.type, "snapshot");
    await page.goto(`${origin}/ui-builder/remote-m3/${sample.id}?token=${encodeURIComponent(token)}&node=choice`);
    await page.waitForFunction(() => document.documentElement.dataset.uiBuilderReady === "true", null, { timeout: 60000 });
    const exported = page.waitForResponse(response => response.url().includes(`/designs/${sample.id}/export.rc?revision=0`));
    await click(page.getByRole("button", { name: /^Preview \(/ }));
    const initialResponse = await exported;
    assert.equal(initialResponse.status(), 200);
    const initialBytes = await initialResponse.body();
    assert(initialBytes.equals(await readFile(directory + stem + ".rc")));
    const target = page.getByLabel("Interactive document preview");
    await target.waitFor({ timeout: 30000 });
    const colors = [];
    async function capture(name, expected) {
        let observed;
        await expect.poll(async () => {
            const bounds = await target.boundingBox();
            if (!bounds) return false;
            const png = PNG.sync.read(await page.screenshot());
            const index = (Math.floor(bounds.y + bounds.height / 2) * png.width + Math.floor(bounds.x + bounds.width / 2)) * 4;
            const color = [...png.data.subarray(index, index + 3)];
            observed = { color, bounds };
            return color.every((value, i) => Math.abs(value - expected[i]) < 16);
        }, { timeout: 10000 }).toBe(true);
        colors.push({ name, expected, observed });
        if (!suffix || ["first", "mcp-edited"].includes(name)) {
            await page.screenshot({ path: directory + name + suffix + ".png" });
        }
    }
    await capture("first", [103, 80, 164]);
    await click(target);
    await capture("second", [0, 133, 119]);
    await click(target);
    await capture("fallback", [57, 73, 171]);
    await click(target);
    await capture("first-again", [103, 80, 164]);
    async function mcp(name, args) {
        const response = await page.request.post(origin + "/mcp", {
            headers, data: { jsonrpc: "2.0", id: 1, method: "tools/call", params: { name, arguments: args } },
        });
        assert(response.ok(), await response.text());
        const result = (await response.json()).result;
        assert(!result.isError, JSON.stringify(result));
        return JSON.parse(result.content[0].text).response;
    }
    const artifact = (await mcp("ui_builder_export", { designId: sample.id, revision: 0, format: "rc" })).artifact;
    assert(Buffer.from(artifact.content, "base64").equals(initialBytes));
    assert.equal(artifact.contentDigest, sha(initialBytes));
    const operation = { type: "setStateVariable", name: "page", declaration: {
        type: "value", valueType: "int", initialValue: 20, persistence: "preview",
    } };
    const nextExport = page.waitForResponse(response => response.url().includes(`/designs/${sample.id}/export.rc?revision=1`));
    await mcp("ui_builder_apply", { designId: sample.id, baseRevision: 0, operationId: "preview-state-change", clientId: "preview-proof", operations: [operation] });
    const nextResponse = await nextExport;
    assert.equal(nextResponse.status(), 200);
    await page.waitForFunction(() => globalThis.__uiBuilderEditor?.revision === 1);
    await target.waitFor();
    await capture("mcp-edited", [0, 133, 119]);
    const nextBytes = await nextResponse.body();
    const nextArtifact = (await mcp("ui_builder_export", { designId: sample.id, revision: 1, format: "rc" })).artifact;
    assert(Buffer.from(nextArtifact.content, "base64").equals(nextBytes));
    assert.equal(nextArtifact.contentDigest, sha(nextBytes));
    await click(page.getByRole("button", { name: /^Design mode \(/ }));
    await expect(target).toHaveCount(0);
    await page.waitForFunction(() => globalThis.__uiBuilderInspection?.documentRevision === 1 && globalThis.__uiBuilderInspection?.generation?.completed);
    if (!suffix) await page.screenshot({ path: directory + "design-after.png" });
    assert.deepEqual(errors, []);
    const verification = { designId: sample.id, initial: { revision: 0, bytes: initialBytes.length, sha256: sha(initialBytes) },
        updated: { revision: 1, bytes: nextBytes.length, sha256: sha(nextBytes) }, operation, colors, errors };
    await writeFile(directory + "verification" + suffix + ".json", JSON.stringify(verification, null, 2) + "\n");
    console.log(JSON.stringify(verification));
} finally { await browser.close(); }
