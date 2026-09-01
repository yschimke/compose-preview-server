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

async function clickCompose(page, locator) {
    const bounds = await locator.boundingBox();
    expect(bounds).not.toBeNull();
    await page.mouse.click(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
}

async function fillCompose(page, locator, value) {
    const current = (await locator.textContent()) ?? "";
    const bounds = await locator.boundingBox();
    expect(bounds).not.toBeNull();
    await page.mouse.click(bounds.x + bounds.width - 2, bounds.y + bounds.height / 2);
    await page.keyboard.press("End");
    for (let index = 0; index < current.length; index++) await page.keyboard.press("Backspace");
    await page.keyboard.type(value);
    await settle(page);
    await expect(locator).toHaveText(value);
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
    await waitForEditor(page, 108);
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
    await waitForEditor(page, 108);
    const initialState = await page.evaluate(() => globalThis.__uiBuilderEditor);
    expect(initialState).toMatchObject({
        revision: 108,
        nodeCount: 108,
        selectedNodeId: "root-surface",
        operationSequence: 0,
        outcome: "idle",
        mainBackgroundChildren: ["main-scrim", "main-scaffold"],
    });
    expect(initialState.documentHash).toBe(
        "dbd6d052f9b766db76aa7541927bacc5b6d993367f66ff05d98383be7be04cdc",
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
    expect(await page.evaluate(() => globalThis.__uiBuilderEditor.revision)).toBe(108);

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
    await waitForEditor(page, 109);
    const insertedState = await page.evaluate(() => globalThis.__uiBuilderEditor);
    expect(insertedState).toMatchObject({
        revision: 109,
        nodeCount: 109,
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
    await waitForEditor(page, 110);
    const editedState = await page.evaluate(() => globalThis.__uiBuilderEditor);
    expect(editedState).toMatchObject({
        revision: 110,
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
    await waitForEditor(page, 111);
    const reorderedState = await page.evaluate(() => globalThis.__uiBuilderEditor);
    expect(reorderedState).toMatchObject({
        revision: 111,
        nodeCount: 109,
        selectedNodeId: "main-scrim",
        operationSequence: 3,
        outcome: "accepted",
        mainBackgroundChildren: ["main-scaffold", "main-scrim"],
    });
    expect(reorderedState.documentHash).not.toBe(editedState.documentHash);

    // Duplicate is one existing reducer batch, and history targets only wasm-editor operations.
    await page.keyboard.press("Control+d");
    await waitForEditor(page, 112);
    const duplicatedState = await page.evaluate(() => globalThis.__uiBuilderEditor);
    expect(duplicatedState).toMatchObject({
        revision: 112,
        nodeCount: 110,
        selectedNodeId: "main-scrim-copy-004",
        operationSequence: 4,
        mainBackgroundChildren: ["main-scaffold", "main-scrim", "main-scrim-copy-004"],
    });
    await page.keyboard.press("Control+z");
    await waitForEditor(page, 113);
    expect(await page.evaluate(() => globalThis.__uiBuilderEditor)).toMatchObject({
        nodeCount: 109,
        selectedNodeId: "main-scrim",
        operationSequence: 5,
    });
    const redoButton = page.getByRole("button", { name: /Redo \(Ctrl\/⌘\+Shift\+Z\)/ });
    await expect(redoButton).toBeEnabled();
    await page.keyboard.press("Control+Shift+z");
    await waitForEditor(page, 114);
    expect(await page.evaluate(() => globalThis.__uiBuilderEditor)).toMatchObject({
        nodeCount: 110,
        selectedNodeId: "main-scrim-copy-004",
        operationSequence: 6,
    });

    // Delete/undo/redo use DeleteNode, UndoCommand and RedoCommand; no editor-only wire command.
    await page.keyboard.press("Backspace");
    await waitForEditor(page, 115);
    expect(await page.evaluate(() => globalThis.__uiBuilderEditor)).toMatchObject({
        nodeCount: 109,
        selectedNodeId: "main-background",
        operationSequence: 7,
    });
    await page.keyboard.press("Control+z");
    await waitForEditor(page, 116);
    expect(await page.evaluate(() => globalThis.__uiBuilderEditor)).toMatchObject({
        nodeCount: 110,
        selectedNodeId: "main-scrim-copy-004",
        operationSequence: 8,
    });
    await page.keyboard.press("Control+y");
    await waitForEditor(page, 117);
    const undoButton = page.getByRole("button", { name: /Undo \(Ctrl\/⌘\+Z\)/ });
    await expect(undoButton).toBeEnabled();
    const undoBounds = await undoButton.boundingBox();
    expect(undoBounds).not.toBeNull();
    await page.mouse.click(
        undoBounds.x + undoBounds.width / 2,
        undoBounds.y + undoBounds.height / 2,
    );
    await waitForEditor(page, 118);
    const redoBounds = await redoButton.boundingBox();
    expect(redoBounds).not.toBeNull();
    await page.mouse.click(
        redoBounds.x + redoBounds.width / 2,
        redoBounds.y + redoBounds.height / 2,
    );
    await waitForEditor(page, 119);
    const finalState = await page.evaluate(() => globalThis.__uiBuilderEditor);
    expect(finalState).toMatchObject({
        revision: 119,
        nodeCount: 109,
        selectedNodeId: "main-background",
        operationSequence: 11,
        outcome: "accepted",
        mainBackgroundChildren: ["main-scaffold", "main-scrim"],
    });
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

test("capability inspector validates and commits typed scaffold and Text properties", async ({
    page,
}, testInfo) => {
    const errors = [];
    page.on("pageerror", (error) => errors.push(error.message));
    page.on("console", (message) => {
        if (message.type() === "error") errors.push(message.text());
    });

    await page.goto("index.html?mode=interactive-editor");
    await waitForEditor(page, 108);
    const canvasBefore = await page.evaluate(() => globalThis.__uiBuilderEditorCanvas);

    await clickCompose(page, page.getByRole("button", { name: /Reorder pane-scaffold/ }));
    await page.waitForFunction(
        () => globalThis.__uiBuilderEditor?.selectedNodeId === "pane-scaffold",
    );

    const paneWidth = page.getByRole("textbox", {
        name: "Main pane preferred width dp property",
    });
    await fillCompose(page, paneWidth, "5000");
    await clickCompose(
        page,
        page.getByRole("button", { name: "Apply main pane preferred width dp" }),
    );
    await page.waitForFunction(
        () =>
            globalThis.__uiBuilderEditor?.outcome === "rejected:INVALID_PROPERTY" &&
            globalThis.__uiBuilderEditor?.outcomeNodeId === "pane-scaffold" &&
            globalThis.__uiBuilderEditor?.outcomeField === "mainPanePreferredWidthDp",
    );
    await expect(
        page.getByText("Main Pane Preferred Width Dp must be 0..4096", { exact: true }),
    ).toBeVisible();
    expect(await page.evaluate(() => globalThis.__uiBuilderEditor.revision)).toBe(108);

    await fillCompose(page, paneWidth, "800");
    await clickCompose(
        page,
        page.getByRole("button", { name: "Apply main pane preferred width dp" }),
    );
    await waitForEditor(page, 109);

    await clickCompose(page, page.getByRole("button", { name: "Layout mode property" }));
    await clickCompose(page, page.getByText("twoPane", { exact: true }));
    await waitForEditor(page, 110);

    // A fresh fixture restores the expanded reference after proving the enum operation. The rest
    // of the test can then exercise the supporting-pane Text node without a test-only reset path.
    await page.goto("index.html?mode=interactive-editor");
    await waitForEditor(page, 108);
    await clickCompose(page, page.getByRole("button", { name: /Reorder pane-scaffold/ }));
    await page.waitForFunction(
        () => globalThis.__uiBuilderEditor?.selectedNodeId === "pane-scaffold",
    );

    const mainPaneVisible = page.getByRole("button", { name: "Main Pane Visible property" });
    await clickCompose(page, mainPaneVisible);
    await waitForEditor(page, 109);
    await clickCompose(page, mainPaneVisible);
    await waitForEditor(page, 110);

    const titleBounds = await page.evaluate(() =>
        globalThis.__uiBuilderInspection.nodes.find(
            (node) => node.nodeId === "detail-podcast-title",
        )?.bounds,
    );
    expect(titleBounds).toBeTruthy();
    await page.mouse.click(
        titleBounds.x + titleBounds.width / 2,
        titleBounds.y + titleBounds.height / 2,
    );
    await page.waitForFunction(
        () => globalThis.__uiBuilderEditor?.selectedNodeId === "detail-podcast-title",
    );
    await fillCompose(
        page,
        page.getByRole("textbox", { name: "Text property" }),
        "Typed inspector title",
    );
    await clickCompose(page, page.getByRole("button", { name: "Apply text" }));
    await waitForEditor(page, 111);

    const color = page.getByRole("textbox", { name: "Color property" });
    await color.scrollIntoViewIfNeeded();
    await fillCompose(page, color, "onSurfaceVariant");
    await clickCompose(page, page.getByRole("button", { name: "Apply color" }));
    await waitForEditor(page, 112);

    await page.mouse.move(1300, 700);
    await page.mouse.wheel(0, 900);
    await settle(page);
    const maxLines = page.getByRole("textbox", { name: "Max lines property" });
    await maxLines.scrollIntoViewIfNeeded();
    await fillCompose(page, maxLines, "4");
    await clickCompose(page, page.getByRole("button", { name: "Apply max lines" }));
    await waitForEditor(page, 113);

    const finalState = await page.evaluate(() => globalThis.__uiBuilderEditor);
    expect(finalState).toMatchObject({
        revision: 113,
        operationSequence: 5,
        selectedNodeId: "detail-podcast-title",
        selectedText: "Typed inspector title",
        outcome: "accepted",
    });
    const canvasAfter = await page.evaluate(() => globalThis.__uiBuilderEditorCanvas);
    expect(canvasAfter).toMatchObject({
        sourceWidthDp: canvasBefore.sourceWidthDp,
        sourceHeightDp: canvasBefore.sourceHeightDp,
        scale: canvasBefore.scale,
        bounds: { width: canvasBefore.bounds.width, height: canvasBefore.bounds.height },
    });
    expect(errors).toEqual([]);

    await settle(page);
    const evidence = await page.screenshot();
    await testInfo.attach("ui-builder-typed-inspector.png", {
        body: evidence,
        contentType: "image/png",
    });
    await testInfo.attach("ui-builder-typed-inspector-state.json", {
        body: Buffer.from(JSON.stringify(finalState, null, 2)),
        contentType: "application/json",
    });
    expect(evidence).toMatchSnapshot("ui-builder-typed-inspector.png", {
        threshold: 0,
        maxDiffPixelRatio: 0.04,
    });
});
