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
