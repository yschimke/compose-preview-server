// The before/after pictures for the design-URL selectors.
//
// Rides the same live server the assertion lane boots, so a committed picture is the same design,
// the same catalog and the same fixture the tests run against rather than a second setup that
// drifts. Run it twice around the change:
//
//   EVIDENCE_SUFFIX=before npm run harness:ui-builder-design-url-evidence   # on the base build
//   EVIDENCE_SUFFIX=after  npm run harness:ui-builder-design-url-evidence   # with the change
//
// Everything it waits on exists on **both** builds — `dataset.uiBuilderReady` and the inspection
// snapshot — because a capture that needed the new globals could not photograph the old behaviour,
// which is the half of a before/after that actually carries the argument.
import { test } from "@playwright/test";
import { mkdir } from "node:fs/promises";
import { resolve } from "node:path";
import {
    designPermalink,
    harnessRoot,
    seedCommentThread,
    seedJetcasterDesign,
    startUiBuilderServer,
} from "./_ui-builder-live-server.mjs";

const suffix = process.env.EVIDENCE_SUFFIX ?? "after";
const outputDirectory = resolve(
    harnessRoot,
    process.env.EVIDENCE_DIR ?? "docs/evidence/design-url-selectors",
);
const designId = "design-url-jetcaster";

let server;
let seededRevision;
let threadId;

async function settle(page) {
    await page.evaluate(async () => {
        if (document.fonts) await document.fonts.ready;
        for (let frame = 0; frame < 12; frame++) {
            await new Promise((resolve) => requestAnimationFrame(resolve));
        }
    });
}

async function openDesign(page, selectors) {
    await page.goto(designPermalink(server, designId, selectors));
    await page.waitForFunction(
        () =>
            document.documentElement.dataset.uiBuilderReady === "true" &&
            globalThis.__uiBuilderInspection?.generation?.completed === true,
        null,
        { timeout: 60_000 },
    );
    await settle(page);
}

async function capture(page, name) {
    await page.screenshot({ path: resolve(outputDirectory, `${name}.${suffix}.png`) });
}

test.beforeAll(async () => {
    await mkdir(outputDirectory, { recursive: true });
    server = await startUiBuilderServer("ui-builder-design-url-evidence-token");
    seededRevision = await seedJetcasterDesign(server, designId);
    threadId = await seedCommentThread(server, designId, {
        nodeId: "discover-grid",
        body: "Should this grid be two columns on a phone?",
    });
});

test.afterAll(async () => {
    await server?.stop();
});

test("capture design URL selector evidence", async ({ page }) => {
    // A link that names a layer. Before: the design opens on its root with every dock shut.
    await openDesign(page, { node: "discover-grid" });
    await capture(page, "node");

    // A link that names a thread. Before: the Talk panel is not open and nothing is scrolled to.
    await openDesign(page, { thread: threadId });
    await capture(page, "thread");

    // A link that names a revision. Before: the query is ignored and the living design opens.
    await openDesign(page, { revision: seededRevision });
    await capture(page, "revision");

    // The selected layer's own menu, where Copy link lives. Opened from the Layers tree, whose
    // rows are addressable by name on both builds — the canvas outline is not.
    await openDesign(page, { node: "main-scrim" });
    const dock = page.getByRole("button", { name: "Open layers panel" });
    const dockBounds = await dock.boundingBox();
    await page.mouse.click(dockBounds.x + dockBounds.width / 2, dockBounds.y + dockBounds.height / 2);
    await settle(page);
    const row = await page.getByRole("button", { name: /Select main-scrim/ }).boundingBox();
    await page.mouse.click(row.x + row.width / 2, row.y + row.height / 2, { button: "right" });
    await settle(page);
    await capture(page, "layer-menu");
});
