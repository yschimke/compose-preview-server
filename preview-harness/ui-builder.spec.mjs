import { expect, test } from "@playwright/test";
import pixelmatch from "pixelmatch";
import { PNG } from "pngjs";

async function capture(page, mode) {
    const errors = [];
    page.on("pageerror", (error) => errors.push(error.message));
    page.on("console", (message) => {
        if (message.type() === "error") errors.push(message.text());
    });
    page.on("response", (response) => {
        if (response.status() >= 400)
            errors.push(`${response.status()} ${response.url()}`);
    });
    await page.goto(`?mode=${mode}`);
    try {
        await page.waitForFunction(
            () =>
                document.documentElement.dataset.uiBuilderReady === "true",
            null,
            { timeout: 15_000 },
        );
    } catch {
        throw new Error(`Wasm fixture did not become ready: ${errors.join(" | ")}`);
    }
    await page.evaluate(
        () =>
            new Promise((resolve) =>
                requestAnimationFrame(() => requestAnimationFrame(resolve)),
            ),
    );
    expect(errors).toEqual([]);
    return page.screenshot();
}

test("public operations render the developer-authored Confetti Wasm slice", async ({
    page,
}, testInfo) => {
    const reference = await capture(page, "reference");
    const actual = await capture(page, "builder");

    await testInfo.attach("confetti-reference.png", {
        body: reference,
        contentType: "image/png",
    });
    expect(reference).toMatchSnapshot("confetti-header-filter-reference.png", {
        threshold: 0,
        maxDiffPixels: 0,
    });
    await testInfo.attach("confetti-builder.png", {
        body: actual,
        contentType: "image/png",
    });
    const expectedPng = PNG.sync.read(reference);
    const actualPng = PNG.sync.read(actual);
    expect([actualPng.width, actualPng.height]).toEqual([
        expectedPng.width,
        expectedPng.height,
    ]);
    const diff = new PNG({ width: expectedPng.width, height: expectedPng.height });
    const mismatch = pixelmatch(
        expectedPng.data,
        actualPng.data,
        diff.data,
        expectedPng.width,
        expectedPng.height,
        { threshold: 0, includeAA: true },
    );
    await testInfo.attach("confetti-diff.png", {
        body: PNG.sync.write(diff),
        contentType: "image/png",
    });
    expect(mismatch, "mismatching pixels").toBe(0);
});
