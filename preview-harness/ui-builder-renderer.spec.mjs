import { expect, test } from "@playwright/test";

test("exact pinned renderer iframe round-trips document measurements", async ({
    page,
}, testInfo) => {
    const errors = [];
    page.on("pageerror", (error) => errors.push(error.message));
    page.on("console", (message) => {
        if (message.type() === "error") errors.push(message.text());
    });
    await page.goto(
        "index.html?rendererRuntimeId=m3-2026.09-protocol1",
    );
    await page.waitForFunction(
        () =>
            document.documentElement.dataset.uiBuilderSandboxReady ===
                "true" &&
            globalThis.__uiBuilderSandboxInspection?.documentId ===
                "fixture-jetcaster-discover-expanded" &&
            globalThis.__uiBuilderSandboxInspection?.nodes?.some(
                (node) => node.nodeId === "root-surface" && node.bounds,
            ),
        null,
        { timeout: 30_000 },
    );

    const result = await page.evaluate(() => ({
        frameSandbox: document
            .getElementById("ui-builder-renderer-frame")
            .getAttribute("sandbox"),
        overlayPointerEvents: getComputedStyle(
            document.getElementById("ui-builder-renderer-overlay"),
        ).pointerEvents,
        overlayCount: globalThis.__uiBuilderSandboxOverlayCount,
        measuredNodes:
            globalThis.__uiBuilderSandboxInspection.generation.measuredNodeIds
                .length,
        measuredSlots:
            globalThis.__uiBuilderSandboxInspection.slots.filter(
                (slot) => slot.bounds,
            ).length,
    }));
    expect(result.frameSandbox).toBe("allow-scripts");
    expect(result.overlayPointerEvents).toBe("none");
    expect(result.overlayCount).toBeGreaterThan(20);
    expect(result.measuredNodes).toBeGreaterThan(20);
    expect(result.measuredSlots).toBeGreaterThan(5);

    await page.evaluate(() =>
        globalThis.__uiBuilderSandboxDispatchInput({
            kind: "pointer",
            x: 10,
            y: 10,
        }),
    );
    await expect
        .poll(() => errors.some((error) => error.includes("UNSUPPORTED_INPUT")))
        .toBe(true);
    await testInfo.attach("sandboxed-renderer.png", {
        body: await page.screenshot(),
        contentType: "image/png",
    });
});

test("unsafe or floating runtime ids are rejected before mounting", async ({
    page,
}) => {
    const errors = [];
    page.on("pageerror", (error) => errors.push(error.message));
    await page.goto("index.html?rendererRuntimeId=latest");
    await expect
        .poll(() =>
            errors.some((error) =>
                error.includes("rendererRuntimeId must be an exact safe runtime id"),
            ),
        )
        .toBe(true);
    expect(await page.locator("iframe").count()).toBe(0);
});
