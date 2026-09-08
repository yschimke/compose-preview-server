import { defineConfig } from "@playwright/test";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));

// No `webServer` and no `baseURL`: the spec spawns the packaged server itself, the way the
// performance lane does. The design URL selectors are about a *live* design — a committed
// revision, a node in a stored document, a thread on the comment board — and none of the three
// exists on the static fixture server the offline harnesses run against.
export default defineConfig({
    testDir: ".",
    testMatch: /ui-builder-design-url\.spec\.mjs/,
    outputDir: resolve(here, "test-results/ui-builder-design-url"),
    timeout: 90_000,
    fullyParallel: false,
    reporter: process.env.CI ? "github" : "list",
    use: {
        browserName: "chromium",
        viewport: { width: 1440, height: 900 },
        // Copy link writes to the clipboard, and whether an ungranted `writeText` is allowed
        // varies by Chromium build — the headless shell refuses it where the full browser does
        // not. Granting it here makes the assertion about the link this editor produces rather
        // than about which binary the harness happened to launch; the refusal path is still real
        // and still reported to the operator in the same banner.
        permissions: ["clipboard-read", "clipboard-write"],
        trace: "retain-on-failure",
        launchOptions: {
            args: ["--enable-unsafe-swiftshader", "--use-gl=angle"],
            ...(process.env.HARNESS_CHROMIUM
                ? { executablePath: process.env.HARNESS_CHROMIUM }
                : {}),
        },
    },
});
