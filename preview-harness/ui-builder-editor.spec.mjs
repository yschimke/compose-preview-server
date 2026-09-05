import { expect, test } from "@playwright/test";
import { writeFile } from "node:fs/promises";
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

// The docks start closed: the editor opens on the canvas and every panel is a switch on a rail.
// A test that drives a panel opens it first, the way an operator does.
async function openDock(page, name) {
    const open = page.getByRole("button", { name: `Open ${name} panel` });
    if ((await open.count()) === 0) return;
    await clickCompose(page, open);
    await settle(page);
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

    // Tall enough for the design at 1:1 with the editor's chrome around it. The top bar, the
    // status strip under the canvas and the workspace padding come to a little over 100 dp, so a
    // 900 dp window scales an 800 dp design down and this test would be comparing a resample.
    await page.setViewportSize({ width: 1920, height: 1000 });
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

test("mobile editor defaults to the design and exposes collapsible dock panels", async ({
    page,
}, testInfo) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto("index.html?mode=interactive-editor");
    await waitForEditor(page, 108);

    await expect(page.getByText("m3-catalog components", { exact: true })).toBeHidden();
    await expect(page.getByText("m3/surface", { exact: true })).toBeHidden();
    const canvas = await page.evaluate(() => globalThis.__uiBuilderEditorCanvas);
    expect(canvas.scale).toBeGreaterThan(0.25);
    expect(canvas.scale).toBeLessThan(0.31);
    expect(canvas.bounds.width).toBeLessThanOrEqual(374);

    const design = await page.screenshot();
    await testInfo.attach("ui-builder-mobile-design.png", {
        body: design,
        contentType: "image/png",
    });

    await clickCompose(page, page.getByRole("button", { name: "Open components panel" }));
    await expect(page.getByText("m3-catalog components", { exact: true })).toBeVisible();
    await expect(
        page.getByRole("button", { name: "Close components panel" }),
    ).toBeVisible();
    const components = await page.screenshot();
    await testInfo.attach("ui-builder-mobile-components.png", {
        body: components,
        contentType: "image/png",
    });

    await clickCompose(page, page.getByRole("button", { name: "Open properties panel" }));
    await expect(page.getByText("m3-catalog components", { exact: true })).toBeHidden();
    await expect(page.getByText("m3/surface", { exact: true })).toBeVisible();
    await expect(
        page.getByRole("button", { name: "Close properties panel" }),
    ).toBeVisible();
    const properties = await page.screenshot();
    await testInfo.attach("ui-builder-mobile-properties.png", {
        body: properties,
        contentType: "image/png",
    });

    expect(design).toMatchSnapshot("ui-builder-mobile-design.png", {
        threshold: 0,
        maxDiffPixelRatio: 0.04,
    });
    expect(components).toMatchSnapshot("ui-builder-mobile-components.png", {
        threshold: 0,
        maxDiffPixelRatio: 0.04,
    });
    expect(properties).toMatchSnapshot("ui-builder-mobile-properties.png", {
        threshold: 0,
        maxDiffPixelRatio: 0.04,
    });
});

test("the canvas frames the design and the zoom controls pin a scale", async ({ page }) => {
    await page.goto("index.html?mode=interactive-editor");
    await waitForEditor(page, 108);

    // The canvas opens framed: whatever scale puts the whole 1280 x 800 design in the workspace,
    // which is the readout the control shows and the factor the drop hit-test divides by.
    const framed = await page.evaluate(() => globalThis.__uiBuilderEditorCanvas.scale);
    expect(framed).toBeGreaterThan(0);
    await expect(page.getByText(`Fit · ${Math.round(framed * 100)}%`)).toBeVisible();

    await clickCompose(page, page.getByRole("button", { name: "Zoom to 100%" }));
    await settle(page);
    expect(await page.evaluate(() => globalThis.__uiBuilderEditorCanvas.scale)).toBeCloseTo(1, 3);

    await clickCompose(page, page.getByRole("button", { name: "Zoom in ()" }));
    await settle(page);
    expect(await page.evaluate(() => globalThis.__uiBuilderEditorCanvas.scale)).toBeCloseTo(
        1.25,
        3,
    );

    await clickCompose(page, page.getByRole("button", { name: "Fit to window ()" }));
    await settle(page);
    expect(await page.evaluate(() => globalThis.__uiBuilderEditorCanvas.scale)).toBeCloseTo(
        framed,
        3,
    );
});

test("a right-click on a layer opens the verbs that act on it", async ({ page }) => {
    await page.goto("index.html?mode=interactive-editor");
    await waitForEditor(page, 108);
    await openDock(page, "layers");

    const row = await page.getByRole("button", { name: /Select main-scrim/ }).boundingBox();
    expect(row).not.toBeNull();
    await page.mouse.click(row.x + row.width / 2, row.y + row.height / 2, { button: "right" });
    await settle(page);
    await expect(page.getByText(/^Duplicate/)).toBeVisible();

    // The row carries its chord beside it, so the menu item reads "Delete Delete" to a locator.
    await clickCompose(page, page.getByText(/^Delete/));
    await waitForEditor(page, 109);
    // The layer and the one child it carried: deleting a node deletes its subtree.
    expect(await page.evaluate(() => globalThis.__uiBuilderEditor)).toMatchObject({
        nodeCount: 107,
        operationSequence: 1,
        outcome: "accepted",
    });
});

test("the layer menu lays a container out without opening a panel", async ({ page }) => {
    await page.goto("index.html?mode=interactive-editor");
    await waitForEditor(page, 108);
    await openDock(page, "layers");

    const row = await page.getByRole("button", { name: /Select main-background/ }).boundingBox();
    expect(row).not.toBeNull();
    await page.mouse.click(row.x + row.width / 2, row.y + row.height / 2, { button: "right" });
    await settle(page);

    // The fixture's background has no padding, so the row offers to add one; the catalog is what
    // says the modifier may go on this component at all.
    await clickCompose(page, page.getByRole("button", { name: "Apply Add padding" }));
    await waitForEditor(page, 109);
    expect(
        await page.evaluate(
            () =>
                globalThis.__uiBuilderEditor?.outcome === "accepted" &&
                globalThis.__uiBuilderEditor?.operationSequence === 1,
        ),
    ).toBe(true);

    // And the same row now offers to take it away.
    await page.mouse.click(row.x + row.width / 2, row.y + row.height / 2, { button: "right" });
    await settle(page);
    await expect(page.getByRole("button", { name: "Remove Add padding" })).toBeVisible();
});

test("the layer menu offers the scope the parent supplies, and not the other one", async ({
    page,
}) => {
    await page.goto("index.html?mode=interactive-editor");
    await waitForEditor(page, 108);
    await openDock(page, "layers");

    // `Modifier.align` and `Modifier.weight` come from the parent, not the child. This layer sits
    // in a box, so the row offered is the nine-way alignment a box supplies — and neither of the
    // single-axis alignments nor the weight, which only a row or a column can apply.
    const row = await page.getByRole("button", { name: /Select main-scrim/ }).boundingBox();
    expect(row).not.toBeNull();
    await page.mouse.click(row.x + row.width / 2, row.y + row.height / 2, { button: "right" });
    await settle(page);

    await expect(page.getByRole("button", { name: "Apply Align in the box" })).toBeVisible();
    expect(await page.getByRole("button", { name: /Align across the/ }).count()).toBe(0);
    expect(await page.getByRole("button", { name: /Take the leftover space/ }).count()).toBe(0);

    await clickCompose(page, page.getByRole("button", { name: "Apply Align in the box" }));
    await waitForEditor(page, 109);
    expect(await page.evaluate(() => globalThis.__uiBuilderEditor)).toMatchObject({
        operationSequence: 1,
        outcome: "accepted",
    });

    // And the same row now offers to take it away.
    await page.mouse.click(row.x + row.width / 2, row.y + row.height / 2, { button: "right" });
    await settle(page);
    await expect(page.getByRole("button", { name: "Remove Align in the box" })).toBeVisible();
});

test("the selection's values are editable over the design", async ({ page }) => {
    await page.goto("index.html?mode=interactive-editor");
    await waitForEditor(page, 108);

    const title = await page.evaluate(
        () =>
            globalThis.__uiBuilderInspection.nodes.find((n) => n.nodeId === "detail-podcast-title")
                ?.bounds,
    );
    expect(title).toBeTruthy();
    await page.mouse.click(title.x + title.width / 2, title.y + title.height / 2);
    await page.waitForFunction(
        () => globalThis.__uiBuilderEditor?.selectedNodeId === "detail-podcast-title",
    );
    await settle(page);

    // Only what the node carries: the same rule the panel opens on, in a card with no room to
    // offer anything else.
    const text = page.getByRole("textbox", { name: "Text value" });
    await expect(text).toBeVisible();
    await expect(page.getByRole("button", { name: "Style value" })).toBeVisible();
    await expect(page.getByRole("textbox", { name: "Color value" })).toHaveCount(0);

    // Leaving the field is the commit, and Enter is how you say so without moving the pointer.
    // Selected and retyped rather than backspaced: the card shows one line of a value that may be
    // longer than the box, so what is on screen is not what is in the document.
    await clickCompose(page, text);
    await page.keyboard.press("Control+a");
    await page.keyboard.type("Edited over the design");
    await page.keyboard.press("Enter");
    await waitForEditor(page, 109);
    expect(await page.evaluate(() => globalThis.__uiBuilderEditor)).toMatchObject({
        selectedNodeId: "detail-podcast-title",
        selectedText: "Edited over the design",
        outcome: "accepted",
    });
});

test("adding padding lands the caret in the number it just chose", async ({ page }) => {
    await page.goto("index.html?mode=interactive-editor");
    await waitForEditor(page, 108);
    await openDock(page, "layers");

    const row = await page.getByRole("button", { name: /Select main-background/ }).boundingBox();
    expect(row).not.toBeNull();
    await page.mouse.click(row.x + row.width / 2, row.y + row.height / 2, { button: "right" });
    await settle(page);
    await clickCompose(page, page.getByRole("button", { name: "Apply Add padding" }));
    await waitForEditor(page, 109);

    // 16 is a starting point rather than a decision, so the caret is already in the field: typing
    // straight after the menu press is what proves it, since nothing else here would take the keys.
    await page.keyboard.press("Control+a");
    await page.keyboard.type("24");
    await page.keyboard.press("Enter");
    await waitForEditor(page, 110);
    expect(
        await page.evaluate(
            () =>
                globalThis.__uiBuilderEditor?.outcome === "accepted" &&
                globalThis.__uiBuilderEditor?.operationSequence === 2,
        ),
    ).toBe(true);
});

test("the property inspector selects Google icons from a searchable catalog", async ({
    page,
}, testInfo) => {
    await page.goto("index.html?mode=interactive-editor");
    await waitForEditor(page, 108);
    await openDock(page, "layers");
    await openDock(page, "properties");

    const iconLayer = await page
        .getByRole("button", { name: /Select search-leading-icon/ })
        .boundingBox();
    expect(iconLayer).not.toBeNull();
    await page.mouse.click(
        iconLayer.x + iconLayer.width / 2,
        iconLayer.y + iconLayer.height / 2,
    );
    await page.waitForFunction(
        () => globalThis.__uiBuilderEditor?.selectedNodeId === "search-leading-icon",
    );
    const before = await page.screenshot();
    await testInfo.attach("ui-builder-google-icon-before.png", {
        body: before,
        contentType: "image/png",
    });
    const chooseIcon = await page
        .getByRole("button", { name: "Choose Google icon" })
        .boundingBox();
    expect(chooseIcon).not.toBeNull();
    await page.mouse.click(
        chooseIcon.x + chooseIcon.width / 2,
        chooseIcon.y + chooseIcon.height / 2,
    );
    await page.getByRole("textbox", { name: "Google icon search" }).fill("home");
    await expect(page.getByRole("button", { name: "Home" })).toBeVisible();

    const catalog = await page.screenshot();
    await testInfo.attach("ui-builder-google-icon-picker.png", {
        body: catalog,
        contentType: "image/png",
    });
    const homeIcon = await page.getByRole("button", { name: "Home" }).boundingBox();
    expect(homeIcon).not.toBeNull();
    await page.mouse.click(
        homeIcon.x + homeIcon.width / 2,
        homeIcon.y + homeIcon.height / 2,
    );
    await waitForEditor(page, 109);
    expect(await page.evaluate(() => globalThis.__uiBuilderEditor)).toMatchObject({
        selectedNodeId: "search-leading-icon",
        selectedIconKey: "home",
        operationSequence: 1,
        outcome: "accepted",
    });

    const selected = await page.screenshot();
    await testInfo.attach("ui-builder-google-icon-selected.png", {
        body: selected,
        contentType: "image/png",
    });
    expect(catalog).toMatchSnapshot("ui-builder-google-icon-picker.png", {
        threshold: 0,
        maxDiffPixelRatio: 0.04,
    });
    expect(before).toMatchSnapshot("ui-builder-google-icon-before.png", {
        threshold: 0,
        maxDiffPixelRatio: 0.04,
    });
    expect(selected).toMatchSnapshot("ui-builder-google-icon-selected.png", {
        threshold: 0,
        maxDiffPixelRatio: 0.04,
    });
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
    await openDock(page, "layers");
    await openDock(page, "properties");
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
        .getByRole("button", { name: /Select discover-grid/ })
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
    const scrimLayer = await page.getByRole("img", { name: "Reorder main-scrim" }).boundingBox();
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
    await openDock(page, "layers");
    await openDock(page, "properties");
    const canvasBefore = await page.evaluate(() => globalThis.__uiBuilderEditorCanvas);

    await clickCompose(page, page.getByRole("button", { name: /Select pane-scaffold/ }));
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
    await openDock(page, "layers");
    await openDock(page, "properties");
    await clickCompose(page, page.getByRole("button", { name: /Select pane-scaffold/ }));
    await page.waitForFunction(
        () => globalThis.__uiBuilderEditor?.selectedNodeId === "pane-scaffold",
    );

    const mainPaneVisible = page.getByRole("button", { name: "Main Pane Visible property" });
    await clickCompose(page, mainPaneVisible);
    await waitForEditor(page, 109);
    await clickCompose(page, page.getByRole("button", { name: /Undo \(Ctrl\/⌘\+Z\)/ }));
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

    // The inspector opens on what the node actually carries — this text has no colour yet — so the
    // property is searched for and added before it can be typed into.
    await fillCompose(
        page,
        page.getByRole("textbox", { name: "Property search" }),
        "color",
    );
    await clickCompose(page, page.getByRole("button", { name: "Add Color property" }));
    await settle(page);
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

test("screen settings update the render environment without writing component nodes", async ({
    page,
}, testInfo) => {
    await page.setViewportSize({ width: 1920, height: 900 });
    await page.goto("index.html?mode=interactive-editor");
    await waitForEditor(page, 108);
    await openDock(page, "screen");
    const beforeState = await page.evaluate(() => globalThis.__uiBuilderEditor);
    const before = await page.screenshot();
    if (process.env.UPDATE_UI_BUILDER_EVIDENCE === "true") {
        await writeFile("snapshots/ui-builder-screen-settings-before.png", before);
    }

    await expect(page.getByText("Screen environment", { exact: true })).toBeVisible();
    await fillCompose(page, page.getByRole("textbox", { name: "Width (dp)" }), "1000");
    await fillCompose(page, page.getByRole("textbox", { name: "Font scale" }), "1.15");
    await clickCompose(page, page.getByRole("button", { name: "Light theme" }));
    await clickCompose(
        page,
        page.getByRole("button", { name: "Right to left layout direction" }),
    );
    await clickCompose(page, page.getByRole("button", { name: "Apply screen settings" }));
    await page.waitForFunction(
        () =>
            globalThis.__uiBuilderEditor?.revision === 109 &&
            globalThis.__uiBuilderEditor?.environment?.widthDp === 1000 &&
            globalThis.__uiBuilderEditor?.environment?.fontScale === 1.15,
    );
    await settle(page);

    const changed = await page.evaluate(() => globalThis.__uiBuilderEditor);
    expect(changed).toMatchObject({
        revision: 109,
        nodeCount: 108,
        operationSequence: 1,
        environment: {
            widthDp: 1000,
            heightDp: 800,
            density: 1,
            fontScale: 1.15,
            locale: "en-US",
            theme: "light",
            layoutDirection: "rtl",
        },
    });
    expect(changed.nodeCount).toBe(beforeState.nodeCount);
    expect(changed.documentHash).not.toBe(beforeState.documentHash);
    const after = await page.screenshot();
    if (process.env.UPDATE_UI_BUILDER_EVIDENCE === "true") {
        await writeFile("snapshots/ui-builder-screen-settings-after.png", after);
    }

    await clickCompose(page, page.getByRole("button", { name: /Undo \(Ctrl\/⌘\+Z\)/ }));
    await page.waitForFunction(
        () =>
            globalThis.__uiBuilderEditor?.revision === 110 &&
            globalThis.__uiBuilderEditor?.environment?.widthDp === 1280 &&
            globalThis.__uiBuilderEditor?.environment?.fontScale === 1,
    );
    await clickCompose(
        page,
        page.getByRole("button", { name: /Redo \(Ctrl\/⌘\+Shift\+Z\)/ }),
    );
    await page.waitForFunction(
        () =>
            globalThis.__uiBuilderEditor?.revision === 111 &&
            globalThis.__uiBuilderEditor?.environment?.widthDp === 1000,
    );

    await testInfo.attach("ui-builder-screen-settings-before.png", {
        body: before,
        contentType: "image/png",
    });
    await testInfo.attach("ui-builder-screen-settings-after.png", {
        body: after,
        contentType: "image/png",
    });
});

test("top-level theme builder updates colours typography and shapes atomically", async ({
    page,
}, testInfo) => {
    await page.goto("index.html?mode=interactive-editor");
    await waitForEditor(page, 108);
    const before = await page.screenshot();

    await openDock(page, "theme");
    await expect(page.getByText("Theme builder", { exact: true })).toBeVisible();
    await fillCompose(page, page.getByRole("textbox", { name: "Primary colour" }), "#FFFF6B8A");
    await fillCompose(page, page.getByRole("textbox", { name: "Background colour" }), "#FF101525");
    await fillCompose(page, page.getByRole("textbox", { name: "Surface colour" }), "#FF202A44");
    await fillCompose(page, page.getByRole("textbox", { name: "Content colour" }), "#FFF4F6FF");
    await fillCompose(page, page.getByRole("textbox", { name: "Type scale (0.75–1.5)" }), "1.15");
    await fillCompose(page, page.getByRole("textbox", { name: "Corner radius (0–48dp)" }), "24");
    await clickCompose(page, page.getByRole("button", { name: "Apply theme" }));
    await waitForEditor(page, 109);

    const themed = await page.screenshot();
    const state = await page.evaluate(() => globalThis.__uiBuilderEditor);
    expect(state).toMatchObject({
        revision: 109,
        nodeCount: 108,
        operationSequence: 1,
        outcome: "accepted",
        outcomeMessage: "",
    });
    expect(themed.equals(before), "theme controls must change the rendered design").toBe(false);
    await testInfo.attach("ui-builder-theme-builder.png", {
        body: themed,
        contentType: "image/png",
    });
    expect(themed).toMatchSnapshot("ui-builder-theme-builder.png", {
        threshold: 0,
        maxDiffPixelRatio: 0.04,
    });

    await page.keyboard.press("Control+z");
    await waitForEditor(page, 110);
    expect(await page.evaluate(() => globalThis.__uiBuilderEditor)).toMatchObject({
        revision: 110,
        operationSequence: 2,
        outcome: "accepted",
    });
});
