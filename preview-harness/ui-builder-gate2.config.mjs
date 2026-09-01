import { defineConfig } from "@playwright/test";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));

export default defineConfig({
    testDir: ".",
    testMatch: /ui-builder-gate2\.spec\.mjs/,
    outputDir: resolve(here, "test-results/ui-builder-gate2"),
    timeout: 240_000,
    fullyParallel: false,
    reporter: process.env.CI ? "github" : "list",
    use: {
        browserName: "chromium",
        viewport: { width: 1440, height: 900 },
        trace: "retain-on-failure",
        launchOptions: {
            args: ["--enable-unsafe-swiftshader", "--use-gl=angle"],
            ...(process.env.HARNESS_CHROMIUM
                ? { executablePath: process.env.HARNESS_CHROMIUM }
                : {}),
        },
    },
});
