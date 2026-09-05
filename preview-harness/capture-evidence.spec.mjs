import { expect, test } from "@playwright/test";

const SUFFIX = process.env.EVIDENCE_SUFFIX ?? "after";
const OUT = process.env.EVIDENCE_DIR ?? "../renders/ui-builder-zoom-and-inspector";

async function settle(page) {
    await page.evaluate(async () => {
        if (document.fonts) await document.fonts.ready;
        for (let frame = 0; frame < 12; frame++) {
            await new Promise((resolve) => requestAnimationFrame(resolve));
        }
    });
}
async function clickCompose(page, locator) {
    const b = await locator.boundingBox();
    expect(b).not.toBeNull();
    await page.mouse.click(b.x + b.width / 2, b.y + b.height / 2);
}
async function openDock(page, name) {
    const open = page.getByRole("button", { name: `Open ${name} panel` });
    if ((await open.count()) === 0) return;
    await clickCompose(page, open);
    await settle(page);
}
async function ready(page) {
    await page.waitForFunction(
        () =>
            document.documentElement.dataset.uiBuilderReady === "true" &&
            globalThis.__uiBuilderInspection?.generation?.completed === true,
        null,
        { timeout: 30000 },
    );
    await settle(page);
}

test("capture evidence", async ({ page }) => {
    await page.goto("index.html?mode=interactive-editor");
    await ready(page);
    await page.screenshot({ path: `${OUT}/canvas.${SUFFIX}.png` });

    // A workspace with room to spare: the design used to stop at 1:1 and sit in the middle of an
    // empty page, which is the case the framing is for.
    await page.setViewportSize({ width: 1920, height: 1200 });
    await ready(page);
    await page.screenshot({ path: `${OUT}/wide.${SUFFIX}.png` });
    await page.setViewportSize({ width: 1440, height: 900 });
    await ready(page);

    await openDock(page, "layers");
    await openDock(page, "properties");
    const title = await page.evaluate(
        () =>
            globalThis.__uiBuilderInspection.nodes.find((n) => n.nodeId === "detail-podcast-title")
                ?.bounds,
    );
    await page.mouse.click(title.x + title.width / 2, title.y + title.height / 2);
    await settle(page);
    await page.screenshot({ path: `${OUT}/inspector.${SUFFIX}.png` });

    const row = await page.getByRole("button", { name: /Select main-scrim/ }).boundingBox();
    await page.mouse.click(row.x + row.width / 2, row.y + row.height / 2, { button: "right" });
    await settle(page);
    await page.screenshot({ path: `${OUT}/layer-menu.${SUFFIX}.png` });
    // Fresh, because the menu above is still open and a Compose popup does not close on Escape.
    await page.goto("index.html?mode=interactive-editor");
    await ready(page);
    await openDock(page, "layers");

    // A container, whose catalog entry declares layout modifiers: the same menu then carries them.
    const container = await page
        .getByRole("button", { name: /Select main-background/ })
        .boundingBox();
    await page.mouse.click(
        container.x + container.width / 2,
        container.y + container.height / 2,
        { button: "right" },
    );
    await settle(page);
    await page.screenshot({ path: `${OUT}/container-menu.${SUFFIX}.png` });

    // The editor that follows the selection: one text node's own values, over the design.
    await page.goto("index.html?mode=interactive-editor");
    await ready(page);
    const heading = await page.evaluate(
        () =>
            globalThis.__uiBuilderInspection.nodes.find((n) => n.nodeId === "detail-podcast-title")
                ?.bounds,
    );
    await page.mouse.click(heading.x + heading.width / 2, heading.y + heading.height / 2);
    await settle(page);
    await page.screenshot({ path: `${OUT}/hover-editor.${SUFFIX}.png` });
});
