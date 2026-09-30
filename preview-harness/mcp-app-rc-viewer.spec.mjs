// Smoke tests for the `.rc` Remote Compose viewer MCP App (`mcp-app/rc-viewer.html`,
// ui://compose-preview/rc-viewer, #1237), driven through a fake MCP Apps host
// (`fixtures/pages/_mcp-app-rc-viewer-host.js`). The same fixture pages are captured by
// `pages-snapshot.spec.mjs` for the visual-diff bot; this spec asserts the bridge behaviour.
//
//   npm run harness:rc-viewer

import { test, expect } from "@playwright/test";

const PAGES = "/preview-harness/fixtures/pages";

async function openViewer(page, fixture) {
  await page.goto(`${PAGES}/${fixture}.html`);
  await page.waitForFunction(() => window.__rcHost?.settled === true, null, {
    timeout: 20_000,
  });
  const frame = page.frames().find((f) => f !== page.mainFrame());
  return frame;
}

const viewerState = (frame) =>
  frame.evaluate(() => ({
    state: document.documentElement.dataset.rcPlayerState,
    error: document.documentElement.dataset.rcPlayerError,
    theme: document.documentElement.dataset.theme,
    doc: window.__rcViewer.document,
    namedValues: window.__rcViewer.namedValues(),
    text: document.body.innerText,
  }));

// Distinct colours in a coarse sample of the canvas: a blank or single-colour canvas is 1.
const canvasColours = (frame) =>
  frame.evaluate(() => {
    const canvas = document.getElementById("canvas");
    const data = canvas.getContext("2d").getImageData(0, 0, canvas.width, canvas.height).data;
    const seen = new Set();
    for (let i = 0; i < data.length; i += 4 * 97) {
      seen.add(`${data[i]},${data[i + 1]},${data[i + 2]},${data[i + 3]}`);
    }
    return seen.size;
  });

const canvasDigest = (frame) =>
  frame.evaluate(() => document.getElementById("canvas").toDataURL("image/png"));

test("file entrypoint: reads the host URI as a blob, subscribes, and plays", async ({ page }) => {
  const frame = await openViewer(page, "mcp-app-rc-viewer");
  const state = await viewerState(frame);
  expect(state.state).toBe("ready");
  expect(state.doc).toMatchObject({
    name: "watch-screen.rc",
    uri: "host-resource://fixture-rc",
    source: "host",
    width: 454,
    height: 454,
  });
  expect(state.namedValues.map((v) => v.name)).toEqual([
    "WearM3.background",
    "WearM3.onSurface",
    "WearM3.surfaceContainer",
    "WearM3.tertiary",
  ]);
  expect(await canvasColours(frame)).toBeGreaterThan(3);

  const host = await page.evaluate(() => ({
    reads: window.__rcHost.reads,
    subscriptions: window.__rcHost.subscriptions,
  }));
  expect(host.reads).toEqual([
    {
      uri: "host-resource://fixture-rc",
      _meta: { "openai/resource": { representation: "blob" } },
    },
  ]);
  expect(host.subscriptions).toEqual(["host-resource://fixture-rc"]);
});

test("live reload: notifications/resources/updated re-reads and replays", async ({ page }) => {
  const frame = await openViewer(page, "mcp-app-rc-viewer");
  const before = await viewerState(frame);
  await page.evaluate(async () => {
    await window.__rcHost.swap("_rc-viewer-icon.rc");
    window.__rcHost.fireUpdate("host-resource://fixture-rc");
  });
  await expect
    .poll(() => page.evaluate(() => window.__rcHost.reads.length))
    .toBe(2);
  await expect
    .poll(async () => (await viewerState(frame)).doc.width)
    .not.toBe(before.doc.width);
  expect((await viewerState(frame)).state).toBe("ready");
  // An update for some other URI is ignored.
  await page.evaluate(() => window.__rcHost.fireUpdate("host-resource://another"));
  await page.waitForTimeout(300);
  expect(await page.evaluate(() => window.__rcHost.reads.length)).toBe(2);
});

test("model path call: plays the inline bytes and never shows the path", async ({ page }) => {
  const frame = await openViewer(page, "mcp-app-rc-viewer-path");
  const state = await viewerState(frame);
  expect(state.state).toBe("ready");
  expect(state.doc).toMatchObject({ name: "watch-screen.rc", source: "server" });
  expect(state.doc.uri).toMatch(/^compose-preview-rc:\/\/document\/[0-9a-f]+\/watch-screen\.rc$/);
  const html = await frame.content();
  expect(html).not.toContain("/home/someone/secret");
  expect(state.text).not.toContain("/home/someone");
  const host = await page.evaluate(() => ({
    reads: window.__rcHost.reads.length,
    subscriptions: window.__rcHost.subscriptions,
  }));
  // The bytes came with the tool result, so no read; the server URI is subscribed for reloads.
  expect(host.reads).toBe(0);
  expect(host.subscriptions).toEqual([state.doc.uri]);
});

test("error: a failed rc_open shows the structured error", async ({ page }) => {
  const frame = await openViewer(page, "mcp-app-rc-viewer-error");
  const state = await viewerState(frame);
  expect(state.state).toBe("error");
  expect(state.error).toContain("no such file: missing.rc");
  await expect(frame.locator('[role="alert"]')).toContainText("no such file: missing.rc");
  await expect(frame.locator("#describe")).toBeDisabled();
});

test("theme follows the host, and the toggle overrides it", async ({ page }) => {
  await page.emulateMedia({ colorScheme: "dark" });
  const frame = await openViewer(page, "mcp-app-rc-viewer");
  expect((await viewerState(frame)).theme).toBe("dark");

  await page.evaluate(() => window.__rcHost.hostContextChanged({ theme: "light" }));
  await expect.poll(async () => (await viewerState(frame)).theme).toBe("light");

  await frame.locator("#theme").selectOption("dark");
  await expect.poll(async () => (await viewerState(frame)).theme).toBe("dark");
  await frame.locator("#theme").selectOption("auto");
  await expect.poll(async () => (await viewerState(frame)).theme).toBe("light");
});

test("named values: an edit repaints and survives a reload", async ({ page }) => {
  const frame = await openViewer(page, "mcp-app-rc-viewer");
  const before = await canvasDigest(frame);
  await frame.locator("#values summary").click();
  const input = frame.locator('input[data-name="WearM3.surfaceContainer"]');
  await input.fill("#FF1B5E20");
  await input.dispatchEvent("change");
  await expect.poll(() => canvasDigest(frame)).not.toBe(before);
  const edited = await canvasDigest(frame);

  await page.evaluate(() => window.__rcHost.fireUpdate("host-resource://fixture-rc"));
  await expect.poll(() => page.evaluate(() => window.__rcHost.reads.length)).toBe(2);
  await expect.poll(async () => (await viewerState(frame)).state).toBe("ready");
  const value = (await viewerState(frame)).namedValues.find(
    (v) => v.name === "WearM3.surfaceContainer",
  );
  expect(value.value).toBe("#FF1B5E20");
  await expect.poll(() => canvasDigest(frame)).toBe(edited);
});

test("Describe this sends the frame and named values as model context", async ({ page }) => {
  const frame = await openViewer(page, "mcp-app-rc-viewer");
  await frame.locator("#describe").click();
  await expect
    .poll(() => page.evaluate(() => window.__rcHost.modelContext != null))
    .toBe(true);
  const context = await page.evaluate(() => window.__rcHost.modelContext);
  const [image, text] = context.content;
  expect(image.type).toBe("image");
  expect(image.mimeType).toBe("image/png");
  expect(image.data.length).toBeGreaterThan(1000);
  expect(image._meta["openai/title"]).toBe("watch-screen.rc (current frame)");
  expect(text.type).toBe("text");
  expect(text.text).toContain("WearM3.tertiary (color)");
  expect(context.structuredContent.rcDocument).toMatchObject({
    name: "watch-screen.rc",
    width: 454,
    height: 454,
  });
  expect(context.structuredContent.rcDocument.namedValues).toHaveLength(4);
});
