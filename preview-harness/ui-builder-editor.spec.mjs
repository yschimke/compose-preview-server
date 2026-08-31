import { expect, test } from "@playwright/test";
import pixelmatch from "pixelmatch";
import { PNG } from "pngjs";

async function settle(page) {
    await page.evaluate(async () => {
        if (document.fonts) await document.fonts.ready;
        for (let frame = 0; frame < 12; frame++) {
            await new Promise((resolve) => requestAnimationFrame(resolve));
        }
    });
}

async function waitForEditor(page, revision) {
    await page.waitForFunction(
        (expectedRevision) =>
            document.documentElement.dataset.uiBuilderReady === "true" &&
            globalThis.__uiBuilderEditor?.revision === expectedRevision &&
            globalThis.__uiBuilderEditorCanvas?.sourceWidthDp === 1280 &&
            globalThis.__uiBuilderEditorCanvas?.sourceHeightDp === 800 &&
            globalThis.__uiBuilderEditorCanvas?.bounds?.width > 0 &&
            globalThis.__uiBuilderInspection?.generation?.completed === true &&
            globalThis.__uiBuilderInspection?.documentRevision === expectedRevision,
        revision,
        { timeout: 20_000 },
    );
    await settle(page);
}

function crop(buffer, bounds) {
    const image = PNG.sync.read(buffer);
    const result = new PNG({ width: Math.round(bounds.width), height: Math.round(bounds.height) });
    PNG.bitblt(
        image,
        result,
        Math.round(bounds.left),
        Math.round(bounds.top),
        result.width,
        result.height,
        0,
        0,
    );
    return PNG.sync.write(result);
}

test("the pinned editor canvas preserves clean 1280x800 geometry and pixels", async ({
    page,
}, testInfo) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.goto("index.html?mode=jetcaster-builder");
    await page.waitForFunction(
        () =>
            document.documentElement.dataset.uiBuilderReady === "true" &&
            globalThis.__uiBuilderInspection?.generation?.completed === true,
        null,
        { timeout: 20_000 },
    );
    await settle(page);
    const clean = await page.screenshot();
    const cleanManifest = await page.evaluate(() => globalThis.__uiBuilderInspection);

    await page.setViewportSize({ width: 1920, height: 900 });
    await page.goto("index.html?mode=interactive-editor-clean");
    await waitForEditor(page, 99);
    const canvas = await page.evaluate(() => globalThis.__uiBuilderEditorCanvas);
    const editorManifest = await page.evaluate(() => globalThis.__uiBuilderInspection);
    expect(canvas).toMatchObject({
        sourceWidthDp: 1280,
        sourceHeightDp: 800,
        scale: 1,
        bounds: { width: 1280, height: 800 },
    });
    const editorCanvas = crop(await page.screenshot(), canvas.bounds);
    const expected = PNG.sync.read(clean);
    const actual = PNG.sync.read(editorCanvas);
    const mismatch = pixelmatch(expected.data, actual.data, null, 1280, 800, {
        threshold: 0,
        includeAA: true,
    });
    let diffBounds = { left: 1280, top: 800, right: 0, bottom: 0 };
    const colorChanges = new Map();
    let maxChannelDelta = 0;
    for (let y = 0; y < 800; y++) {
        for (let x = 0; x < 1280; x++) {
            const offset = (y * 1280 + x) * 4;
            if (
                expected.data[offset] !== actual.data[offset] ||
                expected.data[offset + 1] !== actual.data[offset + 1] ||
                expected.data[offset + 2] !== actual.data[offset + 2] ||
                expected.data[offset + 3] !== actual.data[offset + 3]
            ) {
                diffBounds.left = Math.min(diffBounds.left, x);
                diffBounds.top = Math.min(diffBounds.top, y);
                diffBounds.right = Math.max(diffBounds.right, x);
                diffBounds.bottom = Math.max(diffBounds.bottom, y);
                const beforeColor = Array.from(expected.data.subarray(offset, offset + 4)).join(",");
                const afterColor = Array.from(actual.data.subarray(offset, offset + 4)).join(",");
                const key = `${beforeColor}->${afterColor}`;
                colorChanges.set(key, (colorChanges.get(key) ?? 0) + 1);
                for (let channel = 0; channel < 4; channel++) {
                    maxChannelDelta = Math.max(
                        maxChannelDelta,
                        Math.abs(expected.data[offset + channel] - actual.data[offset + channel]),
                    );
                }
            }
        }
    }
    const geometry = ["root-surface", "main-content", "discover-grid", "main-episode-card"].map(
        (nodeId) => ({
            nodeId,
            clean: cleanManifest.nodes.find((node) => node.nodeId === nodeId)?.bounds,
            editor: editorManifest.nodes.find((node) => node.nodeId === nodeId)?.bounds,
        }),
    );
    const topColorChanges = [...colorChanges].sort((a, b) => b[1] - a[1]).slice(0, 8);
    geometry.forEach(({ clean: cleanBounds, editor: editorBounds }) => {
        expect({
            x: editorBounds.x - canvas.bounds.left,
            y: editorBounds.y - canvas.bounds.top,
            width: editorBounds.width,
            height: editorBounds.height,
        }).toEqual(cleanBounds);
    });
    await testInfo.attach("ui-builder-clean-reference.png", {
        body: clean,
        contentType: "image/png",
    });
    await testInfo.attach("ui-builder-editor-clean-canvas.png", {
        body: editorCanvas,
        contentType: "image/png",
    });
    expect(
        maxChannelDelta,
        "translated Skia raster may differ by at most one channel level",
    ).toBeLessThanOrEqual(1);
    expect(
        mismatch / (1280 * 800),
        `editor chrome must not materially alter clean pixels; diff bounds ${JSON.stringify(diffBounds)} colors ${JSON.stringify(topColorChanges)}`,
    ).toBeLessThan(0.002);
});

test("pointer operations use visible canvas and sibling targets", async ({ page }, testInfo) => {
    const errors = [];
    page.on("pageerror", (error) => errors.push(error.message));
    page.on("console", (message) => {
        if (message.type() === "error") errors.push(message.text());
    });
    page.on("response", (response) => {
        if (response.status() >= 400) errors.push(`${response.status()} ${response.url()}`);
    });

    await page.goto("index.html?mode=interactive-editor");
    await waitForEditor(page, 99);
    const initialState = await page.evaluate(() => globalThis.__uiBuilderEditor);
    expect(initialState).toMatchObject({
        revision: 99,
        nodeCount: 99,
        selectedNodeId: "root-surface",
        operationSequence: 0,
        outcome: "idle",
        mainBackgroundChildren: ["main-scrim", "main-scaffold"],
    });
    expect(initialState.documentHash).toBe(
        "09b7af04ab546421f72b81b1c49564f044790b8f2db4d2304dc66ff73c148643",
    );
    const canvas = await page.evaluate(() => globalThis.__uiBuilderEditorCanvas);
    expect(canvas.scale).toBeCloseTo(0.625, 3);
    expect(canvas.bounds).toMatchObject({ width: 800, height: 500 });
    const before = await page.screenshot();

    // Tap a measured design node and prove the outline uses the same scaled coordinate space.
    const titleBounds = await page.evaluate(() =>
        globalThis.__uiBuilderInspection.nodes.find((node) => node.nodeId === "detail-podcast-title")
            .bounds,
    );
    const titleRect = {
        left: titleBounds.x,
        top: titleBounds.y,
        right: titleBounds.x + titleBounds.width,
        bottom: titleBounds.y + titleBounds.height,
    };
    await page.mouse.click(
        (titleRect.left + titleRect.right) / 2,
        (titleRect.top + titleRect.bottom) / 2,
    );
    await page.waitForFunction(
        () => globalThis.__uiBuilderEditor?.selectedNodeId === "detail-podcast-title",
    );
    const outlined = PNG.sync.read(await page.screenshot());
    let outlinePixels = 0;
    for (let y = Math.floor(titleRect.top) - 2; y <= Math.ceil(titleRect.bottom) + 2; y++) {
        for (let x = Math.floor(titleRect.left) - 2; x <= Math.ceil(titleRect.right) + 2; x++) {
            const perimeter =
                Math.abs(x - titleRect.left) <= 2 ||
                Math.abs(x - titleRect.right) <= 2 ||
                Math.abs(y - titleRect.top) <= 2 ||
                Math.abs(y - titleRect.bottom) <= 2;
            if (!perimeter || x < 0 || y < 0 || x >= outlined.width || y >= outlined.height)
                continue;
            const offset = (y * outlined.width + x) * 4;
            const [r, g, b] = outlined.data.subarray(offset, offset + 3);
            if (Math.abs(r - 103) < 18 && Math.abs(g - 80) < 18 && Math.abs(b - 164) < 18)
                outlinePixels++;
        }
    }
    expect(outlinePixels, "selection outline must align to measured node bounds").toBeGreaterThan(8);

    // Select a concrete slot owner, then release the catalog drag over the visible canvas target.
    const discoverLayer = await page
        .getByRole("button", { name: /Reorder discover-grid/ })
        .boundingBox();
    expect(discoverLayer).not.toBeNull();
    await page.mouse.click(
        discoverLayer.x + discoverLayer.width / 2,
        discoverLayer.y + discoverLayer.height / 2,
    );
    await page.waitForFunction(
        () => globalThis.__uiBuilderEditor?.selectedNodeId === "discover-grid",
    );
    await page.getByRole("textbox", { name: "Component catalog search" }).fill("Text");
    await page.waitForFunction(() => globalThis.__uiBuilderEditor?.catalogQuery === "Text");
    const textDragHandle = await page.getByRole("img", { name: "Drag Text" }).boundingBox();
    expect(textDragHandle).not.toBeNull();
    await page.mouse.move(
        textDragHandle.x + textDragHandle.width / 2,
        textDragHandle.y + textDragHandle.height / 2,
    );
    await page.mouse.down();
    await page.mouse.move(150, canvas.bounds.top + 100, { steps: 8 });
    await page.waitForFunction(
        () => globalThis.__uiBuilderEditorDropTarget?.hovered === false,
    );
    await page.mouse.up();
    await settle(page);
    expect(await page.evaluate(() => globalThis.__uiBuilderEditor.revision)).toBe(99);

    const destination = {
        x: canvas.bounds.left + canvas.bounds.width / 2,
        y: canvas.bounds.top + canvas.bounds.height / 2,
    };
    await page.mouse.move(
        textDragHandle.x + textDragHandle.width / 2,
        textDragHandle.y + textDragHandle.height / 2,
    );
    await page.mouse.down();
    await page.mouse.move(destination.x, destination.y, { steps: 12 });
    await page.waitForFunction(
        () =>
            globalThis.__uiBuilderEditorDropTarget?.hovered === true &&
            globalThis.__uiBuilderEditorDropTarget?.label === "discover-grid.items",
    );
    await page.mouse.up();
    await waitForEditor(page, 100);
    const insertedState = await page.evaluate(() => globalThis.__uiBuilderEditor);
    expect(insertedState).toMatchObject({
        revision: 100,
        nodeCount: 100,
        selectedNodeId: "editor-m3-text-001",
        operationSequence: 1,
        outcome: "accepted",
        selectedText: "New text",
    });
    expect(insertedState.documentHash).not.toBe(initialState.documentHash);

    // The deterministic harness action and editable field share the SetText reducer command.
    const sampleText = await page.getByRole("button", { name: "Use sample text" }).boundingBox();
    expect(sampleText).not.toBeNull();
    await page.mouse.click(
        sampleText.x + sampleText.width / 2,
        sampleText.y + sampleText.height / 2,
    );
    await waitForEditor(page, 101);
    const editedState = await page.evaluate(() => globalThis.__uiBuilderEditor);
    expect(editedState).toMatchObject({
        revision: 101,
        selectedText: "Edited in Compose",
        operationSequence: 2,
        outcome: "accepted",
    });
    expect(editedState.documentHash).not.toBe(insertedState.documentHash);
    await page.waitForFunction(
        () =>
            globalThis.__uiBuilderInspection?.nodes?.find(
                (node) => node.nodeId === "editor-m3-text-001",
            )?.text?.text === "Edited in Compose",
    );
    const beforeReorder = await page.screenshot();
    const beforeCanvas = crop(beforeReorder, canvas.bounds);

    // This gesture resolves the concrete next sibling before emitting MoveNode.
    const scrimLayer = await page
        .getByRole("button", { name: /Reorder main-scrim/ })
        .boundingBox();
    expect(scrimLayer).not.toBeNull();
    await page.mouse.move(
        scrimLayer.x + scrimLayer.width / 2,
        scrimLayer.y + scrimLayer.height / 2,
    );
    await page.mouse.down();
    await page.mouse.move(
        scrimLayer.x + scrimLayer.width / 2,
        scrimLayer.y + scrimLayer.height / 2 + 46,
        { steps: 8 },
    );
    await page.mouse.up();
    await waitForEditor(page, 102);
    const finalState = await page.evaluate(() => globalThis.__uiBuilderEditor);
    expect(finalState).toMatchObject({
        revision: 102,
        nodeCount: 100,
        selectedNodeId: "main-scrim",
        operationSequence: 3,
        outcome: "accepted",
        mainBackgroundChildren: ["main-scaffold", "main-scrim"],
    });
    expect(finalState.documentHash).not.toBe(editedState.documentHash);
    expect(errors).toEqual([]);

    const after = await page.screenshot();
    const afterCanvas = crop(after, canvas.bounds);
    const beforePng = PNG.sync.read(beforeCanvas);
    const afterPng = PNG.sync.read(afterCanvas);
    const changedPixels = pixelmatch(
        beforePng.data,
        afterPng.data,
        null,
        beforePng.width,
        beforePng.height,
        { threshold: 0, includeAA: true },
    );
    expect(changedPixels, "reorder must change canvas stacking pixels").toBeGreaterThan(0);
    await testInfo.attach("ui-builder-interactive-editor-before.png", {
        body: before,
        contentType: "image/png",
    });
    await testInfo.attach("ui-builder-interactive-editor.png", {
        body: after,
        contentType: "image/png",
    });
    await testInfo.attach("ui-builder-editor-operation-result.json", {
        body: Buffer.from(JSON.stringify(finalState, null, 2)),
        contentType: "application/json",
    });
    expect(after).toMatchSnapshot("ui-builder-interactive-editor.png", {
        threshold: 0,
        maxDiffPixelRatio: 0.04,
    });
    expect(before).toMatchSnapshot("ui-builder-interactive-editor-before.png", {
        threshold: 0,
        maxDiffPixelRatio: 0.04,
    });
});
