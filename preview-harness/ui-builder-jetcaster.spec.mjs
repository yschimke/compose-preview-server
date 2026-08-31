import { expect, test } from "@playwright/test";
import pixelmatch from "pixelmatch";
import { PNG } from "pngjs";

async function capture(page, url, ready) {
    const errors = [];
    page.on("pageerror", (error) => errors.push(error.message));
    page.on("console", (message) => {
        if (message.type() === "error") errors.push(message.text());
    });
    page.on("response", (response) => {
        if (response.status() >= 400)
            errors.push(`${response.status()} ${response.url()}`);
    });
    await page.goto(url);
    try {
        await page.waitForFunction(ready, null, { timeout: 20_000 });
    } catch {
        throw new Error(`Wasm capture did not become ready: ${errors.join(" | ")}`);
    }
    await page.evaluate(async () => {
        if (document.fonts) await document.fonts.ready;
        await new Promise((resolve) =>
            requestAnimationFrame(() => requestAnimationFrame(resolve)),
        );
    });
    // Font activation can trigger a second Compose layout after the first ready signal. Require the
    // caller's full readiness contract again before taking authoritative pixels or inspection data.
    await page.waitForFunction(ready, null, { timeout: 20_000 });
    expect(errors).toEqual([]);
    return page.screenshot();
}

test("Jetcaster operations render against the independent Compose Wasm oracle", async ({
    page,
}, testInfo) => {
    const reference = await capture(
        page,
        "/ui-builder-reference-jetcaster/build/wasmDist/index.html",
        () => globalThis.__uiBuilderReferenceJetcasterReady === true,
    );
    const provenance = await page.evaluate(async () => {
        const response = await fetch("./provenance.json", { cache: "no-store" });
        if (!response.ok)
            throw new Error(`provenance fetch failed: ${response.status}`);
        return response.json();
    });
    expect(provenance).toMatchObject({
        referenceId: "jetcaster-discover-expanded-v1",
        responsiveVariants: [
            {
                id: "expanded-two-pane",
                widthDp: 1280,
                heightDp: 800,
                density: 1,
            },
            {
                id: "compact-main-pane",
                widthDp: 412,
                heightDp: 800,
                density: 1,
            },
        ],
        comparisonFixture: {
            resource: "jetcaster-discover-operations-v1.json",
            revision: 99,
            documentHash:
                "09b7af04ab546421f72b81b1c49564f044790b8f2db4d2304dc66ff73c148643",
        },
        source: {
            repository: "android/compose-samples",
            commit: "018c5207fb63c4f78e5841bd8ddd4faabdf19d3a",
            license: "Apache-2.0",
        },
        networkRequired: false,
    });

    const builder = await capture(
        page,
        "/ui-builder/build/wasmDist/index.html?mode=jetcaster-builder",
        () => document.documentElement.dataset.uiBuilderReady === "true",
    );
    const capabilityValidation = await page.evaluate(
        () => globalThis.__uiBuilderCapabilityValidation,
    );
    expect(capabilityValidation).toMatchObject({
        structurallyValid: true,
        wasmRenderable: false,
    });
    expect(
        capabilityValidation.plannedOrUnsupportedComponentIds.length,
    ).toBeGreaterThan(0);

    const referencePng = PNG.sync.read(reference);
    const builderPng = PNG.sync.read(builder);
    expect([referencePng.width, referencePng.height]).toEqual([1280, 800]);
    expect([builderPng.width, builderPng.height]).toEqual([1280, 800]);

    const diff = new PNG({ width: 1280, height: 800 });
    const mismatch = pixelmatch(
        referencePng.data,
        builderPng.data,
        diff.data,
        1280,
        800,
        { threshold: 0.1, includeAA: false },
    );
    const mismatchRatio = mismatch / (1280 * 800);
    console.info(
        `Jetcaster independent-oracle mismatch: ${mismatch} pixels (${(
            mismatchRatio * 100
        ).toFixed(3)}%)`,
    );

    await testInfo.attach("jetcaster-reference.png", {
        body: reference,
        contentType: "image/png",
    });
    await testInfo.attach("jetcaster-builder.png", {
        body: builder,
        contentType: "image/png",
    });
    await testInfo.attach("jetcaster-diff.png", {
        body: PNG.sync.write(diff),
        contentType: "image/png",
    });
    await testInfo.attach("jetcaster-diff.json", {
        body: Buffer.from(JSON.stringify({ mismatch, mismatchRatio }, null, 2)),
        contentType: "application/json",
    });

    // The same-browser oracle comparison above is the semantic fidelity gate. Committed PNGs are
    // review evidence and retain a separate bound for macOS/Linux Chromium/Skia raster drift. The
    // bound is intentionally separate from the same-browser semantic gate and must be remeasured
    // before it is tightened.
    expect(reference).toMatchSnapshot("jetcaster-discover-reference.png", {
        threshold: 0,
        maxDiffPixelRatio: 0.04,
    });
    expect(builder).toMatchSnapshot("jetcaster-discover-builder.png", {
        threshold: 0,
        maxDiffPixelRatio: 0.04,
    });

    // This is an honest convergence guard against a separately compiled reference. Tighten it as
    // the catalog adapters replace the remaining approximations; the release gate remains exact.
    expect(mismatchRatio, "independent-oracle mismatch ratio").toBeLessThan(0.02);
});

test("capability-generated Jetcaster Compose compiles and renders the full document", async ({
    page,
}, testInfo) => {
    const reference = await capture(
        page,
        "/ui-builder-reference-jetcaster/build/wasmDist/index.html",
        () => globalThis.__uiBuilderReferenceJetcasterReady === true,
    );
    const generated = await capture(
        page,
        "/ui-builder-generated-jetcaster/build/wasmDist/index.html",
        () =>
            document.documentElement.dataset
                .uiBuilderGeneratedJetcasterReady === "true",
    );
    const referencePng = PNG.sync.read(reference);
    const generatedPng = PNG.sync.read(generated);
    expect([generatedPng.width, generatedPng.height]).toEqual([1280, 800]);
    const diff = new PNG({ width: 1280, height: 800 });
    const mismatch = pixelmatch(
        referencePng.data,
        generatedPng.data,
        diff.data,
        1280,
        800,
        { threshold: 0.1, includeAA: false },
    );
    const mismatchRatio = mismatch / (1280 * 800);
    console.info(
        `Jetcaster generated-Compose mismatch: ${mismatch} pixels (${(
            mismatchRatio * 100
        ).toFixed(3)}%)`,
    );
    await testInfo.attach("jetcaster-generated-compose.png", {
        body: generated,
        contentType: "image/png",
    });
    await testInfo.attach("jetcaster-generated-compose-diff.png", {
        body: PNG.sync.write(diff),
        contentType: "image/png",
    });
    expect(generated).toMatchSnapshot("jetcaster-discover-generated-compose.png", {
        threshold: 0,
        maxDiffPixelRatio: 0.04,
    });
    expect(mismatchRatio, "generated Compose independent-oracle mismatch").toBeLessThan(
        0.021,
    );
});

test("the same Jetcaster document renders the compact single-pane reference", async ({
    page,
}, testInfo) => {
    await page.setViewportSize({ width: 412, height: 800 });
    const reference = await capture(
        page,
        "/ui-builder-reference-jetcaster/build/wasmDist/index.html",
        () => globalThis.__uiBuilderReferenceJetcasterReady === true,
    );
    const builder = await capture(
        page,
        "/ui-builder/build/wasmDist/index.html?mode=jetcaster-builder",
        () =>
            globalThis.__uiBuilderInspection?.documentRevision === 99 &&
            globalThis.__uiBuilderInspection?.generation?.key ===
                "fixture-jetcaster-discover-expanded@99" &&
            globalThis.__uiBuilderInspection?.generation?.completed === true,
    );
    const manifest = await page.evaluate(
        () => globalThis.__uiBuilderInspection,
    );

    const referencePng = PNG.sync.read(reference);
    const builderPng = PNG.sync.read(builder);
    expect([referencePng.width, referencePng.height]).toEqual([412, 800]);
    expect([builderPng.width, builderPng.height]).toEqual([412, 800]);
    const diff = new PNG({ width: 412, height: 800 });
    const mismatch = pixelmatch(
        referencePng.data,
        builderPng.data,
        diff.data,
        412,
        800,
        { threshold: 0.1, includeAA: false },
    );
    const mismatchRatio = mismatch / (412 * 800);
    console.info(
        `Jetcaster compact-oracle mismatch: ${mismatch} pixels (${(
            mismatchRatio * 100
        ).toFixed(3)}%)`,
    );

    expect(manifest.generation.expectedAuthoredNodeIds).toHaveLength(99);
    expect(manifest.nodes.find((node) => node.nodeId === "root-surface").bounds).toEqual(
        { x: 0, y: 0, width: 412, height: 800 },
    );
    expect(manifest.nodes.find((node) => node.nodeId === "main-background").bounds).toEqual(
        { x: 0, y: 0, width: 412, height: 800 },
    );
    expect(
        manifest.nodes.find((node) => node.nodeId === "detail-scaffold").bounds,
        "supporting pane stays uncomposed at compact width",
    ).toBeNull();

    const news = manifest.nodes.find((node) => node.nodeId === "chip-news");
    await page.mouse.click(
        news.bounds.x + news.bounds.width / 2,
        news.bounds.y + news.bounds.height / 2,
    );
    await page.waitForFunction(
        () =>
            globalThis.__uiBuilderInspection?.generation?.completed === true &&
            globalThis.__uiBuilderInspection.nodes.find(
                (node) => node.nodeId === "chip-news",
            )?.semantics?.selected === true,
    );
    const selected = await page.evaluate(() => ({
        crime: globalThis.__uiBuilderInspection.nodes.find(
            (node) => node.nodeId === "chip-crime",
        ).semantics.selected,
        news: globalThis.__uiBuilderInspection.nodes.find(
            (node) => node.nodeId === "chip-news",
        ).semantics.selected,
    }));
    expect(selected).toEqual({ crime: false, news: true });

    await testInfo.attach("jetcaster-compact-reference.png", {
        body: reference,
        contentType: "image/png",
    });
    await testInfo.attach("jetcaster-compact-builder.png", {
        body: builder,
        contentType: "image/png",
    });
    await testInfo.attach("jetcaster-compact-diff.png", {
        body: PNG.sync.write(diff),
        contentType: "image/png",
    });
    expect(reference).toMatchSnapshot("jetcaster-discover-compact-reference.png", {
        threshold: 0,
        maxDiffPixelRatio: 0.04,
    });
    expect(builder).toMatchSnapshot("jetcaster-discover-compact-builder.png", {
        threshold: 0,
        maxDiffPixelRatio: 0.04,
    });
    expect(mismatchRatio, "compact independent-oracle mismatch ratio").toBeLessThan(0.02);
});

test("editor overlay preserves clean design pixels and the inspection manifest", async ({
    page,
}, testInfo) => {
    const builderUrl =
        "/ui-builder/build/wasmDist/index.html?mode=jetcaster-builder";
    const editorUrl =
        "/ui-builder/build/wasmDist/index.html?mode=jetcaster-editor";
    const ready = () =>
        document.documentElement.dataset.uiBuilderReady === "true" &&
        globalThis.__uiBuilderInspection?.documentRevision === 99 &&
        globalThis.__uiBuilderInspection?.generation?.key ===
            "fixture-jetcaster-discover-expanded@99" &&
        globalThis.__uiBuilderInspection?.generation?.completed === true &&
        globalThis.__uiBuilderInspection?.generation?.expectedAuthoredNodeIds
            ?.length === 99;

    const cleanBefore = await capture(page, builderUrl, ready);
    const cleanManifest = await page.evaluate(
        () => globalThis.__uiBuilderInspection,
    );
    const editor = await capture(page, editorUrl, ready);
    const editorManifest = await page.evaluate(
        () => globalThis.__uiBuilderInspection,
    );
    const cleanAfter = await capture(page, builderUrl, ready);

    expect(cleanManifest).toEqual(editorManifest);
    expect(cleanManifest).toMatchObject({
        schema: "compose-ui-builder-inspection/v1",
        documentRevision: 99,
        coordinateSpace: "root-render-pixels",
        coordinatePrecision: "1/64px",
        generation: {
            key: "fixture-jetcaster-discover-expanded@99",
            completed: true,
            stabilityFrames: 2,
        },
    });
    expect(cleanManifest.generation.expectedAuthoredNodeIds).toHaveLength(99);
    expect(
        cleanManifest.generation.expectedAuthoredTextNodeIds.length,
        "authored text inventory",
    ).toBeGreaterThan(10);
    expect(cleanManifest.generation.measuredNodeIds).toEqual(
        cleanManifest.nodes
            .filter((node) => node.bounds)
            .map((node) => node.nodeId),
    );
    expect(cleanManifest.generation.measuredTextNodeIds).toEqual(
        cleanManifest.nodes
            .filter((node) => node.text)
            .map((node) => node.nodeId),
    );
    expect(cleanManifest.nodes).toHaveLength(99);
    expect(
        cleanManifest.nodes.filter((node) => node.bounds).length,
        "measured nodes",
    ).toBeGreaterThan(50);
    expect(
        cleanManifest.nodes.filter((node) => node.text?.firstBaselineY).length,
        "text nodes with absolute baselines",
    ).toBeGreaterThan(10);
    expect(cleanManifest.slots.length, "declared slots").toBeGreaterThan(20);
    // This title has authored 16dp start, 12dp end and 16dp bottom padding. Its node bounds must
    // cover that outer footprint, while its baselines remain absolute root-pixel coordinates for
    // the inner two-line Text layout.
    const paddedTitle = cleanManifest.nodes.find(
        (node) => node.nodeId === "podcast-card-android-title",
    );
    expect(paddedTitle.bounds).toEqual({ x: 8, y: 224, width: 128, height: 56 });
    expect(paddedTitle.text).toMatchObject({
        text: "Android Developers Backstage",
        lineCount: 2,
    });
    expect(paddedTitle.text.firstBaselineY).toBeCloseTo(238.578125, 4);
    expect(paddedTitle.text.lastBaselineY).toBeCloseTo(258.578125, 4);
    expect(paddedTitle.text.firstBaselineY).toBeGreaterThan(
        paddedTitle.bounds.y,
    );
    expect(paddedTitle.text.lastBaselineY).toBeLessThan(
        paddedTitle.bounds.y + paddedTitle.bounds.height,
    );

    const beforePng = PNG.sync.read(cleanBefore);
    const afterPng = PNG.sync.read(cleanAfter);
    const cleanMismatch = pixelmatch(
        beforePng.data,
        afterPng.data,
        null,
        beforePng.width,
        beforePng.height,
        { threshold: 0, includeAA: true },
    );
    expect(cleanMismatch, "clean pixels after displaying the editor").toBe(0);
    expect(editor.equals(cleanBefore), "overlay must be visible").toBe(false);

    await testInfo.attach("jetcaster-editor-inspection.json", {
        body: Buffer.from(JSON.stringify(editorManifest, null, 2)),
        contentType: "application/json",
    });
    await testInfo.attach("jetcaster-editor-overlay.png", {
        body: editor,
        contentType: "image/png",
    });
});
