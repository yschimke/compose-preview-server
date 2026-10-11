// End-to-end proof that the Kotlin playground of a live `compose-preview serve` works: editor →
// `POST /api/1/compiler/run` → BTA compile → Robolectric first frame → `/pg/<token>` redemption →
// viewer, against a real daemon (unit tests cover each seam with fakes).
//
// Requires SERVE_URL pointing at a playground serve. CI boots one with
// `--playground-android-bundle`, token-gated (refused under --public), so `?token=<SERVE_TOKEN>` is
// appended to every navigation. Self-skips when no playground page is reachable.

import { test, expect } from "@playwright/test";

// Must match the `--token` the boot script passed to serve.
const TOKEN = process.env.SERVE_TOKEN || "playground-e2e";
// The editor's Android mode option (ServeWeb.playgroundModeChoice): compiles the
// snippet against the Android bundle and mints a live `/pg/` token.
const ANDROID_MODE = "compose-android";

// The token gates every route; carry it on each navigation.
const q = `?token=${encodeURIComponent(TOKEN)}`;

// Set in beforeAll: whether a playground page is reachable; false self-skips locally (CI guarantees
// the page).
let playgroundUp = false;

test.beforeAll(async ({ request }) => {
  const res = await request.get(`/playground${q}`);
  playgroundUp = res.ok() && (await res.text()).includes('id="pg-source"');
});

function requirePlayground() {
  if (!playgroundUp && process.env.CI) {
    throw new Error(
      `no playground editor at /playground — the daemon-backed serve isn't exposing the lane; ` +
        `refusing to green-skip the Playground suite`,
    );
  }
  test.skip(
    !playgroundUp,
    `no playground editor reachable at /playground — is a --playground-android-bundle serve ` +
      `running at ${process.env.SERVE_URL} with token "${TOKEN}"?`,
  );
}

// "Compiling…" is the only non-terminal status; wait for "Done." or an error, then assert which.
async function runAndAwaitTerminal(page) {
  await page.click("#pg-run");
  const status = page.locator("#pg-status");
  await expect(status).toBeVisible();
  await expect
    .poll(async () => (await status.textContent())?.trim(), {
      // The compile POST blocks on the cold Android first-frame render (synchronous,
      // up to the service's 180s budget) before it answers.
      timeout: 280_000,
    })
    .not.toBe("Compiling…");
  return (await status.textContent())?.trim();
}

// Drive CodeMirror when it loaded, else the plain `<textarea>` fallback; `fromTextArea` hides
// `#pg-source`, so `fill()` on it would fail.
async function setSource(page, text) {
  await page.evaluate((value) => {
    const cm = document.querySelector(".CodeMirror");
    if (cm && cm.CodeMirror) {
      cm.CodeMirror.setValue(value);
      return;
    }
    const ta = document.getElementById("pg-source");
    ta.value = value;
    ta.dispatchEvent(new Event("input", { bubbles: true }));
  }, text);
}

/**
 * The visible editing surface. Not `.first()` over both: the hidden textarea precedes the
 * CodeMirror wrapper in the DOM.
 */
async function sourceLocator(page) {
  const cm = page.locator(".CodeMirror");
  return (await cm.count()) > 0 ? cm.first() : page.locator("#pg-source");
}

test("editor page serves its controls and the Android mode", async ({
  page,
}) => {
  requirePlayground();
  await page.goto(`/playground${q}`, { waitUntil: "domcontentloaded" });

  await expect(await sourceLocator(page), "source editor").toBeVisible();
  await expect(page.locator("#pg-mode"), "mode selector").toBeVisible();
  await expect(page.locator("#pg-run"), "run button").toBeVisible();

  // The Android compile mode must be offered — it's the one this lane serves.
  const values = await page
    .locator("#pg-mode option")
    .evaluateAll((opts) => opts.map((o) => o.value));
  expect(values, "mode options").toContain(ANDROID_MODE);
});

test("compiles the default Android snippet to a first frame + live /pg/ handoff", async ({
  page,
}) => {
  requirePlayground();
  await page.goto(`/playground${q}`, { waitUntil: "domcontentloaded" });

  // The default sample already declares an Android @Preview; just select the mode
  // and run it — a clean compile is the happy path this test pins.
  await page.selectOption("#pg-mode", ANDROID_MODE);
  const terminal = await runAndAwaitTerminal(page);
  expect(
    terminal,
    `run should succeed on the default snippet, got status "${terminal}" ` +
      `(diagnostics: ${await page.locator("#pg-diagnostics").textContent()})`,
  ).toBe("Done.");

  // A successful CMP/Android run mints a live preview token and surfaces its
  // "Open live preview →" handoff pointing at /pg/<token>.
  const open = page.locator("#pg-open");
  await expect(open, "live-preview handoff link").toBeVisible();
  const href = await open.getAttribute("href");
  expect(href, "handoff href targets the /pg/ capability").toMatch(
    /\/pg\/pg_[A-Za-z0-9_-]+/,
  );

  // The first frame must actually render: asserting it proves the compile→daemon→PNG path ran, not
  // just that a token was minted.
  const image = page.locator("#pg-image");
  await expect(image, "first-frame image is shown").toBeVisible();
  const src = await image.getAttribute("src");
  expect(
    src ?? "",
    "first-frame src is an inline PNG (empty ⇒ the daemon render produced no frame; see serve log)",
  ).toMatch(/^data:image\/png/);
});

test("shows compile errors beside the offending source line", async ({ page }) => {
  requirePlayground();
  await page.goto(`/playground${q}`, { waitUntil: "domcontentloaded" });
  await page.selectOption("#pg-mode", ANDROID_MODE);
  await setSource(
    page,
    [
      "import androidx.compose.runtime.Composable",
      "import androidx.compose.ui.tooling.preview.Preview",
      "",
      "@Preview",
      "@Composable",
      "fun Broken() {",
      "    MissingSymbol()",
      "}",
    ].join("\n"),
  );

  expect(await runAndAwaitTerminal(page)).toBe("Compilation failed.");
  const summary = page.locator("#pg-diagnostics .cp-pg-error");
  await expect(summary, "compile-error summary").toContainText("MissingSymbol");

  // The summary remains useful to screen readers and to the textarea fallback, but a normal
  // CodeMirror run also paints the bad line and puts the compiler's message directly beneath it.
  const inline = page.locator(".cp-pg-inline-error");
  await expect(inline, "inline compile error").toContainText("MissingSymbol");
  await expect(page.locator(".cp-pg-line-error"), "highlighted source line").toHaveCount(1);
});

test("a multi-file snippet compiles as one module and offers every preview it declared", async ({
  page,
}) => {
  requirePlayground();
  await page.goto(`/playground${q}`, { waitUntil: "domcontentloaded" });
  await page.selectOption("#pg-mode", ANDROID_MODE);

  // Split across two files with a cross-file reference, which only resolves if both reach one
  // compile (#3017).
  await page.click("#pg-add-file");
  await setSource(
    page,
    [
      "import androidx.compose.ui.graphics.Color",
      "",
      "val Brand = Color(0xFF6750A4)",
    ].join("\n"),
  );
  await expect(
    page.locator("[data-pg-file]"),
    "one tab per open file",
  ).toHaveCount(2);

  await page.click('[data-pg-file="Snippet.kt"]');
  await setSource(
    page,
    [
      "import androidx.compose.material3.Text",
      "import androidx.compose.runtime.Composable",
      "import androidx.compose.ui.tooling.preview.Preview",
      "",
      "@Preview",
      "@Composable",
      "fun Greeting() {",
      '    Text("Hello", color = Brand)',
      "}",
      "",
      "@Preview",
      "@Composable",
      "fun Second() {",
      '    Text("Second")',
      "}",
    ].join("\n"),
  );

  const terminal = await runAndAwaitTerminal(page);
  expect(
    terminal,
    `multi-file run should compile, got "${terminal}" ` +
      `(diagnostics: ${await page.locator("#pg-diagnostics").textContent()})`,
  ).toBe("Done.");

  // Two @Previews in the snippet: exactly one drives the still frame, and the editor says which —
  // otherwise the choice is invisible.
  const note = page.locator("#pg-preview-note");
  await expect(note, "preview note for a multi-preview snippet").toBeVisible();
  expect(await note.textContent()).toMatch(/2 previews in this snippet/);

  // …and both previews are reachable: every declared preview gets a `?preview=<id>` link into the
  // same `/pg/` token.
  const links = page.locator("#pg-previews a");
  await expect(links, "one link per declared preview").toHaveCount(2);
  const hrefs = await links.evaluateAll((els) =>
    els.map((e) => e.getAttribute("href")),
  );
  expect(hrefs.join(" ")).toContain("preview=SnippetKt.Greeting");
  expect(hrefs.join(" ")).toContain("preview=SnippetKt.Second");
  // All of them address the one token the run minted — navigating between previews must not
  // require a second compile.
  const tokens = new Set(hrefs.map((h) => h.split("?")[0]));
  expect(
    tokens.size,
    `every preview link rides one session, got ${[...tokens]}`,
  ).toBe(1);
});

test("the /pg/ token redeems into the live viewer", async ({ page }) => {
  requirePlayground();
  await page.goto(`/playground${q}`, { waitUntil: "domcontentloaded" });
  await page.selectOption("#pg-mode", ANDROID_MODE);
  const terminal = await runAndAwaitTerminal(page);
  // A non-Done terminal in CI is a regression, not a skip: the boot guarantees a compilable lane.
  expect(
    terminal,
    `compile did not succeed (status "${terminal}") — nothing to redeem`,
  ).toBe("Done.");

  const href = await page.locator("#pg-open").getAttribute("href");

  // Hit the `/pg/` capability raw first: redemption must 302 to `/<sessionId>/p/<previewId>`; the
  // failure message carries status and body to distinguish NotFound from Unavailable.
  const raw = await page.request.get(href, { maxRedirects: 0 });
  const location = raw.headers()["location"] ?? "";
  const body =
    raw.status() >= 300 && raw.status() < 400
      ? ""
      : (await raw.text()).slice(0, 300);
  expect(
    location,
    `/pg/ must 302 to the viewer /p/ route; got status ${raw.status()} ` +
      `location="${location}" body="${body}"`,
  ).toMatch(/\/p\//);

  // The viewer shell loads (live frames are the serve-lanes suite's job).
  await page.goto(href, { waitUntil: "domcontentloaded" });
  await expect
    .poll(() => new URL(page.url()).pathname, { timeout: 30_000 })
    .toMatch(/\/p\//);
  await expect(page.locator("#cp-img"), "viewer stage").toBeAttached();
});
