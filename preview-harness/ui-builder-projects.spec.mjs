import { expect, test } from "@playwright/test";
import { startUiBuilderServer } from "./_ui-builder-live-server.mjs";

let server;
const headers = { "X-Compose-Preview-Token": "project-harness-local-token" };
test.beforeAll(async () => {
  server = await startUiBuilderServer(headers["X-Compose-Preview-Token"]);
});
test.afterAll(async () => { await server?.stop(); });

async function api(path, method = "GET", body) {
  const response = await fetch(server.origin + path, {
    method,
    headers: { ...headers, "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined,
  });
  expect(response.status).toBe(200);
  return response.json();
}

test("projects retain source identity and canonical home around the live editor", async ({ page }, info) => {
  const sourceId = "project-harness-source";
  const created = await fetch(server.origin + "/ui-builder/designs", {
    method: "POST",
    headers: { ...headers, Origin: server.origin, "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ designId: sourceId, catalog: "m3-catalog", template: "blank", visibility: "private" }),
    redirect: "manual",
  });
  expect(created.status).toBe(303);
  const source = await api(`/api/ui-builder/v1/designs/${sourceId}`);
  source.home = { kind: "server", url: "https://ui.coo.ee", designId: sourceId };
  const base = "/api/ui-builder/v1/projects/harness-app";
  let project = await api("/api/ui-builder/v1/projects", "POST", { id: "harness-app", name: "Harness app" });
  project = await api(base + "/files", "PUT", {
    baseRevision: project.revision, fileId: "login", path: "screens/login.uid", content: JSON.stringify(source),
  });
  const scopedId = project.files[0].designs[sourceId];
  expect(scopedId).not.toBe(sourceId);
  expect((await api(`/api/ui-builder/v1/designs/${scopedId}`)).title).toBe(source.title);
  const review = await api(base + "/review");
  expect(JSON.parse(review.files["screens/login.uid"]).id).toBe(sourceId);
  await api(base + "/files", "PUT", {
    baseRevision: project.revision, fileId: "tokens", path: "themes/tokens.json", kind: "tokens",
    content: JSON.stringify({ schema: "app-tokens/v1", primary: "#14654c" }),
  });
  expect((await fetch(server.origin + "/api/ui-builder/v1/projects")).status).toBe(401);
  await page.setExtraHTTPHeaders(headers);
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.goto(server.origin + "/ui-builder/designs");
  await info.attach("before", { body: await page.screenshot({ fullPage: true }), contentType: "image/png" });
  await page.goto(server.origin + "/ui-builder/projects");
  await page.getByRole("button", { name: "Harness app", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Design files & shared resources", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "themes/tokens.json", exact: true })).toBeVisible();
  await info.attach("after", { body: await page.screenshot({ fullPage: true }), contentType: "image/png" });
  await page.getByRole("button", { name: "Review saved files", exact: true }).click();
  await expect(page.getByText("These files contain the saved design revisions.", { exact: true })).toBeVisible();
  await page.getByRole("link", { name: `Open ${sourceId}`, exact: true }).click();
  await expect(page.getByText("Editing a working copy", { exact: true })).toBeVisible();
  await expect(page.getByText("Canonical home:", { exact: false })).toContainText(sourceId);
  await page.frameLocator("iframe").locator("[data-ui-builder-ready]").waitFor({ timeout: 120_000 });
  await info.attach("editor-copy", { body: await page.screenshot({ fullPage: true }), contentType: "image/png" });
  expect(errors).toEqual([]);
});
