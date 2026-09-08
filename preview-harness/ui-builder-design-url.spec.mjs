// What a design URL's selectors actually do, against a real server.
//
// `?revision=`, `?node=` and `#thread=` each name something that only exists once a design is
// stored: a committed revision, a node in that document, a thread on its comment board. So this
// lane boots the packaged server (see `_ui-builder-live-server.mjs`) rather than the static
// fixture app the offline harnesses run against.
//
// What is asserted is what a selector *promises*: the node is selected and the panel that edits it
// is open; the Talk panel is on the thread the fragment named. Deliberately not pixels — "the node
// is selected" and "the node is drawn with an outline" are different claims, and the visual lanes
// already own the second.
import { expect, test } from "@playwright/test";
import {
    catalogSystemId,
    designPermalink,
    seedCommentThread,
    seedJetcasterDesign,
    startUiBuilderServer,
} from "./_ui-builder-live-server.mjs";

const designId = "design-url-jetcaster";

let server;
let seededRevision;
let threadId;

/** Compose draws into a canvas: a press lands at a coordinate, not on a DOM node. */
async function clickCompose(page, locator) {
    const bounds = await locator.boundingBox();
    expect(bounds).not.toBeNull();
    await page.mouse.click(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
}

async function settle(page) {
    await page.evaluate(async () => {
        if (document.fonts) await document.fonts.ready;
        for (let frame = 0; frame < 12; frame++) {
            await new Promise((resolve) => requestAnimationFrame(resolve));
        }
    });
}

/** The docks start closed: a test that drives one opens it first, the way an operator does. */
async function openDock(page, name) {
    const open = page.getByRole("button", { name: `Open ${name} panel` });
    await expect(open).toBeVisible();
    await clickCompose(page, open);
    await settle(page);
}

async function openDesign(page, selectors) {
    await page.goto(designPermalink(server, designId, selectors));
    await page.waitForFunction(
        () =>
            document.documentElement.dataset.uiBuilderReady === "true" &&
            globalThis.__uiBuilderDesignSelectors !== undefined,
        null,
        { timeout: 60_000 },
    );
    return page.evaluate(() => globalThis.__uiBuilderDesignSelectors);
}

test.beforeAll(async () => {
    server = await startUiBuilderServer("ui-builder-design-url-token");
    seededRevision = await seedJetcasterDesign(server, designId);
    threadId = await seedCommentThread(server, designId, {
        nodeId: "discover-grid",
        body: "Should this grid be two columns on a phone?",
    });
});

test.afterAll(async () => {
    await server?.stop();
});

test("?node= selects the layer it names and opens Properties on it", async ({ page }) => {
    const selectors = await openDesign(page, { node: "discover-grid" });
    expect(selectors).toMatchObject({
        nodeId: "discover-grid",
        inspectorMode: "Properties",
        threadId: "",
    });
    // Open rather than merely selected: a link to a layer that left every dock shut would be a
    // link to a highlight nobody asked about.
    await expect(page.getByRole("button", { name: "Close Properties panel" })).toBeVisible();
});

test("a ?node= this design does not have opens it with a notice, not an error", async ({
    page,
}) => {
    const selectors = await openDesign(page, { node: "no-such-layer" });
    // The design opened, and on its own root rather than on nothing.
    expect(selectors.nodeId).not.toBe("");
    expect(selectors.nodeId).not.toBe("no-such-layer");
    await expect(page.getByLabel("Design link notice")).toContainText("no-such-layer");
});

test("#thread= opens the Talk panel on the conversation it names", async ({ page }) => {
    const selectors = await openDesign(page, { thread: threadId });
    expect(selectors).toMatchObject({ threadId, inspectorMode: "Comments" });
    await expect(page.getByRole("button", { name: "Copy link to this thread" })).toBeVisible();
    // The fragment never left the browser, which is the whole reason it is a fragment. What a page
    // can prove is the near half of that: it is still in the address bar, unsent.
    expect(new URL(page.url()).hash).toBe(`#thread=${threadId}`);
});

test("#thread= combines with ?node= where the thread is pinned to a layer", async ({ page }) => {
    const selectors = await openDesign(page, { node: "discover-grid", thread: threadId });
    expect(selectors).toMatchObject({
        nodeId: "discover-grid",
        threadId,
        inspectorMode: "Comments",
    });
});

test("?revision= shows that revision read-only, with a way back to latest", async ({ page }) => {
    const selectors = await openDesign(page, { revision: seededRevision });
    expect(selectors).toMatchObject({
        revision: String(seededRevision),
        revisionPinned: true,
    });
    await expect(page.getByLabel("Design link notice")).toContainText(
        `Showing revision ${seededRevision}`,
    );
    await expect(page.getByRole("button", { name: "Go to latest revision" })).toBeVisible();
});

test("an unknown ?revision= opens the latest design and says so", async ({ page }) => {
    const selectors = await openDesign(page, { revision: 9999 });
    expect(selectors).toMatchObject({ revision: "9999", revisionPinned: false });
    await expect(page.getByLabel("Design link notice")).toContainText("is not available");
    // The address bar stops claiming a revision the page is not showing, and there is nowhere to
    // go: the page is already on the latest design.
    expect(new URL(page.url()).searchParams.get("revision")).toBeNull();
    await expect(page.getByRole("button", { name: "Go to latest revision" })).toHaveCount(0);
});

test("a selected layer offers Copy link in its own menu", async ({ page }) => {
    // `main-scrim` rather than the grid the thread is pinned to, for a reason about the test and
    // not the feature: the Layers tree holds 108 rows and only the first handful are on screen.
    await openDesign(page, { node: "main-scrim" });
    await openDock(page, "layers");
    const layer = await page.getByRole("button", { name: /Select main-scrim/ }).boundingBox();
    expect(layer).not.toBeNull();
    await page.mouse.click(layer.x + layer.width / 2, layer.y + layer.height / 2, {
        button: "right",
    });
    await settle(page);
    // What this lane can prove about a Compose popup is that the row is offered and reads as one
    // affordance: a synthetic click on a row inside this menu does not reach it here — the
    // catalog's own `Duplicate` row is unreachable the same way — so the URL such a press produces
    // is proved twice over instead, by `DesignUrlSelectorsTest` on the builder and by the thread
    // button below on the whole chain through the host.
    await expect(page.getByRole("button", { name: "Copy link to this layer" })).toBeVisible();
});

test("Copy link on a thread copies the canonical URL, with no token on it", async ({ page }) => {
    await openDesign(page, { thread: threadId });
    await clickCompose(page, page.getByRole("button", { name: "Copy link to this thread" }));
    await settle(page);
    const notice = page.getByLabel("Design link notice");
    // The thread, and the layer it is pinned to. No revision — a discussion is about the living
    // design — and above all no `token`, which is the one thing a shared link must never carry.
    await expect(notice).toContainText(
        `/ui-builder/${catalogSystemId}/${designId}?node=discover-grid#thread=${threadId}`,
    );
    await expect(notice).not.toContainText("token");
});
