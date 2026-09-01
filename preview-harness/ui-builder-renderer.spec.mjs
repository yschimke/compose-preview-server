import { expect, test } from "@playwright/test";
import pixelmatch from "pixelmatch";
import { PNG } from "pngjs";

async function waitForResponse(page, requestId) {
    await page.waitForFunction(
        (id) => globalThis.__uiBuilderSandboxResponse(id) !== null,
        requestId,
    );
    return page.evaluate(
        (id) => globalThis.__uiBuilderSandboxResponse(id),
        requestId,
    );
}

async function openRenderer(page) {
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
}

test("sandbox semantic actions operate Jetcaster state and an independent scroll pane", async ({
    page,
}, testInfo) => {
    const errors = [];
    page.on("pageerror", (error) => errors.push(error.message));
    page.on("console", (message) => {
        if (
            message.type() === "error" &&
            !message.text().includes("Cache storage is disabled")
        ) {
            errors.push(message.text());
        }
    });
    await openRenderer(page);

    const security = await page.evaluate(() => {
        const frame = document.getElementById("ui-builder-renderer-frame");
        const overlay = document.getElementById(
            "ui-builder-renderer-overlay",
        );
        return {
            frameSandbox: frame.getAttribute("sandbox"),
            overlayPointerEvents: getComputedStyle(overlay).pointerEvents,
            sibling: frame.parentElement === overlay.parentElement,
            overlayContainsFrame: overlay.contains(frame),
            overlayCount: globalThis.__uiBuilderSandboxOverlayCount,
            measuredNodes:
                globalThis.__uiBuilderSandboxInspection.generation
                    .measuredNodeIds.length,
            measuredSlots:
                globalThis.__uiBuilderSandboxInspection.slots.filter(
                    (slot) => slot.bounds,
                ).length,
        };
    });
    expect(security).toMatchObject({
        frameSandbox: "allow-scripts",
        overlayPointerEvents: "none",
        sibling: true,
        overlayContainsFrame: false,
    });
    expect(security.overlayCount).toBeGreaterThan(20);
    expect(security.measuredNodes).toBeGreaterThan(20);
    expect(security.measuredSlots).toBeGreaterThan(5);
    const shell = page.locator("#composeApp");
    const withOverlay = await shell.screenshot();
    const geometry = await page.evaluate(() => {
        const frame = document.getElementById("ui-builder-renderer-frame");
        const overlay = document.getElementById(
            "ui-builder-renderer-overlay",
        );
        const before = frame.getBoundingClientRect().toJSON();
        overlay.remove();
        const without = frame.getBoundingClientRect().toJSON();
        frame.parentElement.append(overlay);
        return { before, without };
    });
    const withoutOverlay = await shell.screenshot();
    expect(geometry.without).toEqual(geometry.before);
    const first = PNG.sync.read(withOverlay);
    const second = PNG.sync.read(withoutOverlay);
    const overlayMismatch = pixelmatch(
        first.data,
        second.data,
        null,
        first.width,
        first.height,
        { threshold: 0 },
    );
    expect(overlayMismatch, "pointer-inert sibling changed renderer pixels").toBe(
        0,
    );

    const beforeInteraction = await shell.screenshot();
    const activate = await page.evaluate(() =>
        globalThis.__uiBuilderSandboxActivateNode("chip-news"),
    );
    const activateResponse = await waitForResponse(page, activate);
    expect(activateResponse, JSON.stringify(activateResponse)).toMatchObject({
        type: "actionDispatched",
    });
    await expect
        .poll(() =>
            page.evaluate(() => {
                const nodes =
                    globalThis.__uiBuilderSandboxInspection?.nodes || [];
                return {
                    news: nodes.find((node) => node.nodeId === "chip-news")
                        ?.semantics.selected,
                    crime: nodes.find((node) => node.nodeId === "chip-crime")
                        ?.semantics.selected,
                };
            }),
        )
        .toEqual({ news: true, crime: false });
    const beforeScroll = await page.evaluate(() => {
        const bounds = (id) =>
            globalThis.__uiBuilderSandboxInspection.nodes.find(
                (node) => node.nodeId === id,
            )?.bounds;
        return {
            detailHero: bounds("detail-hero"),
            detailList: bounds("detail-list"),
            categoryRow: bounds("category-row"),
        };
    });
    const scrollRequest = await page.evaluate(() =>
        globalThis.__uiBuilderSandboxScrollNodeBy("detail-list", 360),
    );
    expect((await waitForResponse(page, scrollRequest)).type).toBe(
        "actionDispatched",
    );
    await expect
        .poll(() =>
            page.evaluate(() =>
                globalThis.__uiBuilderSandboxInspection.nodes.find(
                    (node) => node.nodeId === "detail-hero",
                )?.bounds?.y,
            ),
        )
        .toBeLessThan(beforeScroll.detailHero.y);
    const afterScroll = await page.evaluate(() => {
        const bounds = (id) =>
            globalThis.__uiBuilderSandboxInspection.nodes.find(
                (node) => node.nodeId === id,
            )?.bounds;
        return {
            detailHero: bounds("detail-hero"),
            detailList: bounds("detail-list"),
            categoryRow: bounds("category-row"),
        };
    });
    expect(afterScroll.detailList).toEqual(beforeScroll.detailList);
    expect(afterScroll.categoryRow).toEqual(beforeScroll.categoryRow);
    expect(afterScroll.detailHero.y).toBeLessThan(beforeScroll.detailHero.y);

    const staleId = await page.evaluate(() =>
        globalThis.__uiBuilderSandboxDispatchAction({
            documentId: "fixture-jetcaster-discover-expanded",
            documentRevision: 0,
            nodeId: "detail-list",
            kind: "scrollBy",
            deltaX: 0,
            deltaY: 1,
        }),
    );
    expect(await waitForResponse(page, staleId)).toMatchObject({
        type: "error",
        payload: { code: "STALE_DOCUMENT" },
    });
    const forgedResult = await page.evaluate((requestId) => {
        const frame = document.getElementById("ui-builder-renderer-frame");
        const forged = JSON.stringify({
            schema: "compose-ui-builder-renderer/v1",
            protocolVersion: 1,
            runtimeId: "m3-2026.09-protocol1",
            requestId,
            type: "actionDispatched",
            payload: { inspection: { documentRevision: 0 } },
        });
        dispatchEvent(
            new MessageEvent("message", {
                data: forged,
                origin: "https://attacker.example",
                source: frame.contentWindow,
            }),
        );
        dispatchEvent(
            new MessageEvent("message", {
                data: forged,
                origin: "null",
                source: window,
            }),
        );
        return globalThis.__uiBuilderSandboxResponse(requestId);
    }, staleId);
    expect(forgedResult).toMatchObject({
        type: "error",
        payload: { code: "STALE_DOCUMENT" },
    });
    const unsupportedId = await page.evaluate(() =>
        globalThis.__uiBuilderSandboxDispatchAction({
            documentId:
                globalThis.__uiBuilderSandboxInspection.documentId,
            documentRevision:
                globalThis.__uiBuilderSandboxInspection.documentRevision,
            nodeId: "chip-news",
            kind: "focus",
        }),
    );
    expect(await waitForResponse(page, unsupportedId)).toMatchObject({
        type: "error",
        payload: { code: "UNSUPPORTED_ACTION" },
    });
    const horizontalId = await page.evaluate(() =>
        globalThis.__uiBuilderSandboxDispatchAction({
            documentId:
                globalThis.__uiBuilderSandboxInspection.documentId,
            documentRevision:
                globalThis.__uiBuilderSandboxInspection.documentRevision,
            nodeId: "detail-list",
            kind: "scrollBy",
            deltaX: 1,
            deltaY: 1,
        }),
    );
    expect(await waitForResponse(page, horizontalId)).toMatchObject({
        type: "error",
        payload: { code: "INVALID_ACTION" },
    });
    expect(errors).toEqual([]);

    await testInfo.attach("jetcaster-renderer-before-semantic-actions.png", {
        body: beforeInteraction,
        contentType: "image/png",
    });
    await testInfo.attach("jetcaster-renderer-after-semantic-actions.png", {
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
                error.includes(
                    "rendererRuntimeId must be an exact safe runtime id",
                ),
            ),
        )
        .toBe(true);
    expect(await page.locator("iframe").count()).toBe(0);
});
