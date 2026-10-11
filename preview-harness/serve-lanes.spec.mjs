// End-to-end proof that each render lane of a live, daemon-backed `compose-preview serve` honours a
// named-knob override (that flipping a knob changes the bytes), not just that the controls exist
// (ServeWebFixtureTest's job). For a preview declaring a `label` string knob:
//   - PNG: `/render/<id>.png` vs `?knob.label=<X>`, both 200, bytes differ.
//   - SVG: `/render/<id>.svg?knob.label=<X>`, 200 with <X> in the body.
//   - Live: `/ws/<id>?knob.label=<X>` upgrades and pushes a frame.
//   - Wasm: the in-browser iframe re-renders when the knob changes.
// Plus a viewer drive switching modes and editing the knob.
//
// Point SERVE_URL at a running serve (SERVE_SYSTEM defaults to compose-m3); CI boots a
// daemon-backed one under xvfb. Skips when no label-knob preview is reachable.

import { test, expect } from "@playwright/test";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const SYSTEM = process.env.SERVE_SYSTEM ?? "compose-m3";
const OVERRIDE = "E2eKnobProof";

// Resolved in beforeAll: a preview id that declares a `label` string knob.
let previewId = null;

test.beforeAll(async ({ request }) => {
  const res = await request.get(`/${SYSTEM}/api/previews`);
  if (!res.ok()) return; // leaves previewId null → every test self-skips
  const body = await res.json();
  const previews = Array.isArray(body) ? body : (body.previews ?? []);
  const hit = previews.find((p) =>
    (p.overrides ?? []).some(
      (o) => o.key === "label" && (o.kind ?? "string") === "string",
    ),
  );
  previewId = hit?.id ?? null;
});

// Knobs live in a collapsed "Overrides" `<details>` group; expand it before filling a `.cp-knob`
// (Playwright won't fill hidden controls).
async function openOverridesGroup(page) {
  // Two things are shut: the group, and the overrides drawer (revealed by `.cp-controls-open`,
  // which now starts closed and hides every group). Open the drawer through its toggle; force the
  // group open, since which groups start expanded isn't under test.
  const viewer = page.locator(".cp-viewer");
  const open = await viewer.evaluate((v) =>
    v.classList.contains("cp-controls-open"),
  );
  if (!open) await page.locator("#cp-controls-toggle").click();
  await expect(viewer).toHaveClass(/cp-controls-open/);
  await page
    .locator('details.cp-group[data-cp-group="overrides"]')
    .evaluate((d) => {
      d.open = true;
    });
}

// The landing's theme chips are behind the `.cp-theme-menu` dropdown, so open it first. Idempotent,
// since a pick closes it again.
async function openCatalogThemeBar(page) {
  const bar = page.locator("#cp-catalog-theme-bar");
  if ((await bar.count()) && (await bar.isHidden()))
    await page.locator(".cp-catalog-theme > summary").click();
}

function requirePreview() {
  // In CI a missing label-knob preview is a regression, so fail rather than skip.
  if (!previewId && process.env.CI) {
    throw new Error(
      `no label-knob preview at /${SYSTEM}/api/previews — the daemon-backed serve isn't exposing the expected catalog; refusing to green-skip the Serve Lanes suite`,
    );
  }
  test.skip(
    !previewId,
    `no label-knob preview reachable at /${SYSTEM}/api/previews — is a daemon-backed serve running at ${process.env.SERVE_URL}?`,
  );
}

test("PNG lane re-renders on knob override", async ({ request }) => {
  requirePreview();
  const base = await request.get(`/${SYSTEM}/render/${previewId}.png`);
  const over = await request.get(
    `/${SYSTEM}/render/${previewId}.png?knob.label=${OVERRIDE}`,
  );
  expect(base.status(), "baseline PNG").toBe(200);
  expect(over.status(), "override PNG").toBe(200);
  const baseBuf = await base.body();
  const overBuf = await over.body();
  expect(baseBuf.length, "baseline PNG is non-empty").toBeGreaterThan(0);
  // A real re-render with a different label produces different bytes.
  expect(
    Buffer.compare(baseBuf, overBuf) !== 0,
    "override PNG bytes should differ from baseline (daemon re-rendered)",
  ).toBeTruthy();
  // These pixels came from a daemon, so no dropped-override signal is allowed.
  expect(
    over.headers()["x-compose-preview-dropped-overrides"],
    "a real override render drops nothing",
  ).toBeUndefined();
});

test("SVG lane bakes the override text into the vector", async ({
  request,
}) => {
  requirePreview();
  const res = await request.get(
    `/${SYSTEM}/render/${previewId}.svg?knob.label=${OVERRIDE}`,
  );
  expect(res.status(), "override SVG status").toBe(200);
  expect(res.headers()["content-type"] ?? "").toContain("image/svg");
  const svg = await res.text();
  // The overridden label must appear as text in the SVG body — this is the lane
  // that regressed when the bundle daemon didn't register compose/figma-svg.
  expect(svg, "SVG body should contain the override text").toContain(OVERRIDE);
  // This vector was rendered with the override, so no dropped-override signal (that would mean a
  // silent fallback to the baked SVG).
  expect(
    res.headers()["x-compose-preview-dropped-overrides"],
    "a real override SVG export drops nothing",
  ).toBeUndefined();
});

test("Live WebSocket lane upgrades and pushes a frame", async ({ page }) => {
  requirePreview();
  // Same-origin: land on the viewer first so the WS is same-origin as the app.
  await page.goto(`/${SYSTEM}/p/${previewId}`, {
    waitUntil: "domcontentloaded",
  });
  const result = await page.evaluate(
    ({ system, id, override }) =>
      new Promise((resolve) => {
        const proto = location.protocol === "https:" ? "wss:" : "ws:";
        const url = `${proto}//${location.host}/${system}/ws/${encodeURIComponent(id)}?knob.label=${override}&codec=webp`;
        let ws;
        try {
          ws = new WebSocket(url);
        } catch (e) {
          resolve({ outcome: "ctor-error", error: String(e) });
          return;
        }
        let opened = false;
        const done = (r) => {
          try {
            ws.close();
          } catch {}
          resolve(r);
        };
        const t = setTimeout(() => done({ outcome: "timeout", opened }), 30000);
        ws.onopen = () => {
          opened = true;
        };
        ws.onmessage = (m) => {
          const bytes = m.data?.byteLength ?? m.data?.length ?? 0;
          clearTimeout(t);
          done({ outcome: "frame", opened, bytes });
        };
        ws.onclose = (e) => {
          clearTimeout(t);
          done({
            outcome: "closed",
            opened,
            code: e.code,
            reason: String(e.reason),
          });
        };
      }),
    { system: SYSTEM, id: previewId, override: OVERRIDE },
  );
  expect(
    result.opened,
    `WS should upgrade (got ${JSON.stringify(result)})`,
  ).toBeTruthy();
  expect(
    result.outcome,
    `WS should deliver a frame (got ${JSON.stringify(result)})`,
  ).toBe("frame");
  expect(result.bytes, "WS frame is non-empty").toBeGreaterThan(0);
});

test("viewer wires the knob into every render lane", async ({ page }) => {
  requirePreview();
  await page.goto(`/${SYSTEM}/p/${previewId}`, {
    waitUntil: "domcontentloaded",
  });

  await openOverridesGroup(page);
  const knob = page.locator('.cp-knob[data-knob-key="label"]');
  await expect(knob, "label knob control exists").toBeVisible();
  await knob.fill(OVERRIDE);
  await knob.dispatchEvent("input");
  await knob.dispatchEvent("change");

  // The direct-link fields track the current knobs in every mode.
  await expect(page.locator("#cp-url-png")).toHaveValue(
    new RegExp(`knob\\.label=${OVERRIDE}`),
  );
  await expect(page.locator("#cp-url-svg")).toHaveValue(
    new RegExp(`knob\\.label=${OVERRIDE}`),
  );

  // PNG mode: assert on `data-cp-src`, not `src`. The viewer hands the <img> a `blob:` URL (to
  // avoid rendering a no-store override twice); `data-cp-src` is the /render URL those bytes came
  // from, set when the frame swaps in.
  await expect(page.locator("#cp-img")).toHaveAttribute(
    "data-cp-src",
    new RegExp(`\\.png.*knob\\.label=${OVERRIDE}`),
  );
  // ...and `src` is still a blob, pinning the swap itself.
  await expect(page.locator("#cp-img")).toHaveAttribute("src", /^blob:/);
  await expect
    .poll(() => page.locator("#cp-img").evaluate((im) => im.naturalWidth), {
      timeout: 30000,
    })
    .toBeGreaterThan(0);

  // SVG format toggle: the same <img> repaints from the override SVG and loads.
  await page.click("#cp-svg-toggle");
  await expect(page.locator("#cp-img")).toHaveAttribute(
    "data-cp-src",
    new RegExp(`\\.svg.*knob\\.label=${OVERRIDE}`),
  );
  await expect
    .poll(() => page.locator("#cp-img").evaluate((im) => im.naturalWidth), {
      timeout: 30000,
    })
    .toBeGreaterThan(0);
  // Toggle SVG back off before exercising the live lane.
  await page.click("#cp-svg-toggle");

  // Live mode: the toggle must open the stream. A failed activation shows #cp-error (and clears
  // #cp-status), so assert the error overlay stays hidden.
  await page.click("#cp-live-toggle");
  await page.waitForTimeout(6000);
  await expect(
    page.locator("#cp-error"),
    "live stream should open, not surface an activation error",
  ).toBeHidden();
});

test("Live mode surfaces a visible error when the stream can't activate", async ({
  page,
}) => {
  requirePreview();
  // Reproduce the failed-activation symptom (the coo.ee 502 left a stale frame with only tiny
  // "stream error" text): intercept the /ws/ upgrade so it closes without ever delivering a frame.
  await page.routeWebSocket(/\/ws\//, (ws) => ws.close());

  await page.goto(`/${SYSTEM}/p/${previewId}`, {
    waitUntil: "domcontentloaded",
  });
  await expect(
    page.locator("#cp-error"),
    "no error before switching modes",
  ).toBeHidden();

  await page.click("#cp-live-toggle");
  // The viewer must raise a VISIBLE error overlay (not leave a stale snapshot masquerading as live).
  await expect(page.locator("#cp-error")).toBeVisible();
  await expect(page.locator("#cp-error")).toContainText(/live preview/i);
  // And the stale seeded canvas must not be left painted over the stage as a fake render.
  await expect(page.locator("#cp-canvas")).toBeHidden();

  // Toggling back to static (Live off) clears the error.
  await page.click("#cp-live-toggle");
  await expect(page.locator("#cp-error")).toBeHidden();
});

test("Wasm iframe re-renders on knob override", async ({ page }) => {
  requirePreview();
  await page.goto(`/${SYSTEM}/p/${previewId}`, {
    waitUntil: "domcontentloaded",
  });

  const wasmToggle = page.locator("#cp-wasm-toggle");
  test.skip((await wasmToggle.count()) === 0, "no Wasm tier for this session");

  await openOverridesGroup(page);
  const knob = page.locator('.cp-knob[data-knob-key="label"]');
  await knob.fill("WasmBefore");
  await knob.dispatchEvent("input");
  await knob.dispatchEvent("change");

  // The wasm radio is hidden behind the Static/Live toggle (which prefers the stream here), so tick
  // it directly and fire change.
  await wasmToggle.evaluate((el) => {
    el.checked = true;
    el.dispatchEvent(new Event("change", { bubbles: true }));
  });
  const frame = page.locator("#cp-wasm");
  await expect(frame).toBeVisible();
  await expect(frame).toHaveClass(/cp-wasm-live/, { timeout: 30000 });

  const before = await frame.screenshot();

  await knob.fill("WasmAfter");
  await knob.dispatchEvent("input");
  await knob.dispatchEvent("change");

  await expect
    .poll(async () => Buffer.compare(before, await frame.screenshot()) !== 0, {
      message: "Wasm stage pixels should change after the knob override",
      timeout: 30000,
    })
    .toBeTruthy();
});

// The client-side RC lane must draw with the vendored faces (`/rc-fonts/…`), not the visitor's own
// `sans-serif`, matching the PNG lane. Driven through the document lane, which needs only a `.rc`
// file. Needs a real browser and server: `@font-face` is lazy and canvas neither triggers loads nor
// repaints.
test("a Remote Compose document plays in the vendored typefaces, not the visitor's", async ({
  browser,
  request,
}) => {
  const sheet = await request.get("/rc-fonts/fonts.css");
  expect(sheet.ok(), "the server publishes the vendored @font-face block").toBeTruthy();
  const css = await sheet.text();
  for (const family of ["Roboto", "Noto Serif", "Droid Sans Mono"]) {
    expect(css, `declares ${family}`).toContain(`font-family:"${family}"`);
  }
  // The contiguous ranges are what stop an in-between weight (Wear M3 asks for 450) resolving upward
  // onto Medium, and what gives a Medium request a real file at all.
  expect(css, "Roboto Medium serves 500 and up").toContain("font-weight:500 1000");

  const doc = readFileSync(resolve(HERE, "../scripts/design-artifacts/fixtures/watch-screen-round-clip.rc"));
  const upload = await request.post("/docs", {
    headers: { "content-type": "application/octet-stream" },
    data: doc,
  });
  test.skip(upload.status() === 404, "this serve was booted without the document lane (--accept-docs)");
  expect(upload.status(), await upload.text()).toBe(201);
  const docUrl = (await upload.json()).url;

  // Two loads of the same page: as served, and with `/rc-fonts/**` blocked — which is exactly this
  // page before the faces were served at all.
  async function play(blockFonts) {
    const ctx = await browser.newContext({ deviceScaleFactor: 2, serviceWorkers: "block" });
    const page = await ctx.newPage();
    if (blockFonts) await page.route("**/rc-fonts/**", (route) => route.abort());
    await page.goto(docUrl, { waitUntil: "domcontentloaded" });
    // The page clears its status line once the player has painted a frame.
    await expect(page.locator("#cp-doc-status")).toHaveText("", { timeout: 60000 });
    const probe = await page.evaluate(() => {
      const loaded = [];
      document.fonts.forEach((f) => {
        if (f.status === "loaded") loaded.push(f.family);
      });
      const ctx2d = document.createElement("canvas").getContext("2d");
      // The stack the player itself asks for, measured the way it draws.
      ctx2d.font = "100px Roboto, sans-serif";
      const m = ctx2d.measureText("Hamburgefonstiv");
      return {
        loaded,
        lineBox: Math.round(m.fontBoundingBoxAscent + m.fontBoundingBoxDescent),
        width: Math.round(m.width),
      };
    });
    const shot = await page.locator("#cp-doc-mount").screenshot();
    await ctx.close();
    return { ...probe, shot };
  }

  const served = await play(false);
  const unregistered = await play(true);

  // Both Roboto faces must be loaded before painting; 500 is a weight no fallback has.
  expect(
    served.loaded.filter((f) => f === "Roboto").length,
    `both Roboto faces should be loaded, saw ${JSON.stringify(served.loaded)}`,
  ).toBeGreaterThanOrEqual(2);
  // Roboto's own metrics, not the host's generic — the residual no layout work can close.
  expect(served.lineBox, "the request resolved to the vendored face's metrics").not.toBe(
    unregistered.lineBox,
  );
  expect(
    Buffer.compare(served.shot, unregistered.shot) !== 0,
    "the painted document should differ from the unregistered-fallback rendering",
  ).toBeTruthy();
});

// Address-bar state: catalog selections (tab, theme, filter) land in the URL without a reload, and
// Back restores them in place. Driven against the real server because it is a navigation claim.
test("catalog selections land in the URL and Back restores them without reloading", async ({
  page,
}) => {
  await page.goto(`/${SYSTEM}/`, { waitUntil: "domcontentloaded" });
  // A reload would re-run this, so it doubles as the no-reload probe below.
  await page.evaluate(() => {
    window.__cpNavigations = (window.__cpNavigations ?? 0) + 1;
  });

  const themeChips = page.locator(".cp-theme-btn");
  test.skip(
    (await themeChips.count()) < 2,
    "catalog offers no theme control to pick from",
  );
  const chip = themeChips.nth(1);
  const chosen = await chip.getAttribute("data-theme-choice");
  await openCatalogThemeBar(page);
  await chip.click();
  await expect
    .poll(() =>
      page.evaluate(() => new URLSearchParams(location.search).get("theme")),
    )
    .toBe(chosen);

  // A tab, when the catalog authored sections — the "select Components, then a theme, and the URL
  // takes you back there" flow.
  const tabs = page.locator(".cp-tab");
  if (await tabs.count()) {
    const tab = tabs.nth(1);
    const slug = await tab.getAttribute("data-tab");
    await tab.click();
    await expect
      .poll(() =>
        page.evaluate(() => new URLSearchParams(location.search).get("tab")),
      )
      .toBe(slug);
    await expect(tab).toHaveAttribute("aria-selected", "true");
  }

  // Filtering replaces rather than pushes, so it must NOT cost a history entry: one Back from
  // here returns to the theme pick, not to a half-typed query.
  await page.fill("#cp-search", "a");
  await expect
    .poll(() =>
      page.evaluate(() => new URLSearchParams(location.search).get("q")),
    )
    .toBe("a");

  await page.goBack();
  await expect
    .poll(() =>
      page.evaluate(() => new URLSearchParams(location.search).get("q")),
    )
    .toBeNull();
  await expect(page.locator("#cp-search")).toHaveValue("");
  // Still the same document — the whole point is that Back re-points the grid rather than
  // re-fetching the catalog page.
  expect(await page.evaluate(() => window.__cpNavigations)).toBe(1);
});

test("a bookmarked catalog URL opens on the theme and tab it names", async ({
  page,
}) => {
  await page.goto(`/${SYSTEM}/`, { waitUntil: "domcontentloaded" });
  const chips = page.locator(".cp-theme-btn");
  test.skip(
    (await chips.count()) < 2,
    "catalog offers no theme control to pick from",
  );
  const chosen = await chips.nth(1).getAttribute("data-theme-choice");
  const tabs = page.locator(".cp-tab");
  const slug = (await tabs.count())
    ? await tabs.nth(1).getAttribute("data-tab")
    : null;

  const query = new URLSearchParams({ theme: chosen });
  if (slug) query.set("tab", slug);
  await page.goto(`/${SYSTEM}/?${query}`, { waitUntil: "domcontentloaded" });

  await expect(
    page.locator(`.cp-theme-btn[data-theme-choice="${chosen}"]`),
    "the bookmarked theme chip is the pressed one",
  ).toHaveAttribute("aria-pressed", "true");
  if (slug) {
    await expect(page.locator(`.cp-tab[data-tab="${slug}"]`)).toHaveAttribute(
      "aria-selected",
      "true",
    );
  }
});

test("viewer overrides ride the page URL and survive Back", async ({
  page,
}) => {
  requirePreview();
  await page.goto(`/${SYSTEM}/p/${previewId}`, {
    waitUntil: "domcontentloaded",
  });
  await openOverridesGroup(page);

  const knob = page.locator('.cp-knob[data-knob-key="label"]');
  await knob.fill(OVERRIDE);
  await knob.dispatchEvent("input");
  await expect
    .poll(() =>
      page.evaluate(() =>
        new URLSearchParams(location.search).get("knob.label"),
      ),
    )
    .toBe(OVERRIDE);

  // Reloading that URL re-opens on the override — a bookmark, not just a live control state.
  await page.reload({ waitUntil: "domcontentloaded" });
  await openOverridesGroup(page);
  await expect(page.locator('.cp-knob[data-knob-key="label"]')).toHaveValue(
    OVERRIDE,
  );
});

// Live-lane text input. Typing needs the character on the wire, not just the keycode (caret keys
// and Backspace work from the keycode alone, so the lane can look responsive while nothing is
// typed). Selecting needs the pointer's device class: Compose only drag-selects for a mouse. Both
// asserted by the streamed frame's pixels changing; daemon dispatch is unit-tested
// (`DesktopTextInputSessionTest`, `AndroidInteractiveSessionTest`).

/** A preview id that renders a text field, or null when the catalog has none. */
let textFieldPreviewId = null;

test.beforeAll(async ({ request }) => {
  const res = await request.get(`/${SYSTEM}/api/previews`);
  if (!res.ok()) return;
  const body = await res.json();
  const previews = Array.isArray(body) ? body : (body.previews ?? []);
  textFieldPreviewId =
    previews.find((p) => /^textfield-/.test(p.id ?? ""))?.id ?? null;
});

/** The one catalog expected to carry a text field (the `androidlane` system has none). */
const TEXT_FIELD_SYSTEM = "compose-m3";

function requireTextField() {
  if (!textFieldPreviewId && process.env.CI && SYSTEM === TEXT_FIELD_SYSTEM) {
    throw new Error(
      `no textfield-* preview at /${SYSTEM}/api/previews — refusing to green-skip the live text-input suite`,
    );
  }
  test.skip(
    !textFieldPreviewId,
    `no textfield-* preview reachable at /${SYSTEM}/api/previews`,
  );
}

/** Open the viewer on the text-field preview in Live mode with a painted frame. */
async function openLiveTextField(page) {
  await page.goto(`/${SYSTEM}/p/${textFieldPreviewId}`, {
    waitUntil: "domcontentloaded",
  });
  await page.click("#cp-live-toggle");
  // The canvas has a buffer only once a frame painted; input forwarding is gated on that.
  await expect
    .poll(() => page.locator("#cp-canvas").evaluate((c) => c.width), {
      timeout: 60000,
    })
    .toBeGreaterThan(0);
  await expect(
    page.locator("#cp-error"),
    "live stream should open cleanly before driving input",
  ).toBeHidden();
  await page.waitForTimeout(1500);
}

/** The live canvas's current pixels, as a flat RGBA array. */
function liveFrame(page) {
  return page.locator("#cp-canvas").evaluate((c) => {
    const ctx = c.getContext("2d");
    return Array.from(ctx.getImageData(0, 0, c.width, c.height).data);
  });
}

/** Percentage of pixels whose colour differs between two [liveFrame] snapshots. */
function frameDiffPct(a, b) {
  if (!a.length || a.length !== b.length) return 100;
  let changed = 0;
  for (let i = 0; i < a.length; i += 4) {
    if (a[i] !== b[i] || a[i + 1] !== b[i + 1] || a[i + 2] !== b[i + 2])
      changed++;
  }
  return (changed / (a.length / 4)) * 100;
}

/**
 * Floor for "the composition visibly reacted", in percent of frame pixels. Measured with a focused
 * field: caret blink 0.07%, caret moved 0.07%, two characters typed 0.82%, drag-selection 1.49%.
 * 0.4% sits between. The "real edit" numbers are with the caret inside the word (see
 * [TEXT_X_FRACTION]); appending past the end scores only 0.34%, which is why these tests press on
 * the glyphs.
 */
const REACTED_DIFF_PCT = 0.4;

/** Centre of the live canvas in page coordinates. */
async function liveCanvasBox(page) {
  return await page.locator("#cp-canvas").boundingBox();
}

/**
 * Horizontal fraction of the canvas landing on the field's glyphs: the seeded text fills only the
 * first fifth of the field, so pressing here puts the caret inside the word.
 */
const TEXT_X_FRACTION = 0.18;

test("Live lane types the visitor's keystrokes into the field", async ({
  page,
}) => {
  requireTextField();
  await openLiveTextField(page);

  const box = await liveCanvasBox(page);
  // Click into the field's text to place the caret and focus the canvas. On the
  // glyphs, not at the canvas centre — see [TEXT_X_FRACTION].
  const x = box.x + box.width * TEXT_X_FRACTION;
  await page.mouse.click(x, box.y + box.height / 2);
  await page.waitForTimeout(2000);
  const before = await liveFrame(page);

  await page.keyboard.type("Zx", { delay: 400 });
  await expect
    .poll(async () => frameDiffPct(before, await liveFrame(page)), {
      timeout: 30000,
      message:
        "typed characters must appear in the streamed frame — the keycode alone " +
        "cannot type, the character has to ride the wire",
    })
    .toBeGreaterThan(REACTED_DIFF_PCT);
});

test("Live lane selects text on a mouse drag", async ({ page }) => {
  requireTextField();
  await openLiveTextField(page);

  const box = await liveCanvasBox(page);
  const y = box.y + box.height / 2;
  // The canvas centre (past the text), focusing the field with the caret at the end: the decoy the
  // drag must ignore.
  await page.mouse.click(box.x + box.width / 2, y);
  await page.waitForTimeout(2000);
  const before = await liveFrame(page);

  // Press on the glyphs and drag right past the end. The viewer defers pointerDown until the first
  // move, so the daemon gets press and first move back to back. Ending past the text makes a
  // selection anchored on the old caret collapse to nothing.
  await page.mouse.move(box.x + box.width * TEXT_X_FRACTION, y);
  await page.mouse.down();
  for (let i = 1; i <= 12; i++) {
    await page.mouse.move(box.x + box.width * (TEXT_X_FRACTION + 0.02 * i), y);
    await page.waitForTimeout(80);
  }
  await page.mouse.up();

  // A selection paints a highlight; dispatched as touch the drag would only move the caret, under
  // the floor.
  await expect
    .poll(async () => frameDiffPct(before, await liveFrame(page)), {
      timeout: 30000,
      message:
        "a mouse drag must paint a selection highlight — a pointer forwarded as " +
        "touch never starts one",
    })
    .toBeGreaterThan(REACTED_DIFF_PCT);
});
