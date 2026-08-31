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
    // review evidence and retain a separate bound for macOS/Linux Chromium/Skia raster drift; the
    // independently authored reference differs by 3% on the pinned Linux runner.
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
    expect(mismatchRatio, "independent-oracle mismatch ratio").toBeLessThan(0.08);
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
    expect(paddedTitle.bounds).toEqual({ x: 8, y: 217, width: 128, height: 56 });
    expect(paddedTitle.text).toMatchObject({
        text: "Android Developers Backstage",
        lineCount: 2,
    });
    expect(paddedTitle.text.firstBaselineY).toBeCloseTo(231.578125, 4);
    expect(paddedTitle.text.lastBaselineY).toBeCloseTo(251.578125, 4);
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
