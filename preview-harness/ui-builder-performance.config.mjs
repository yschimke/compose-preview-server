import { defineConfig } from "@playwright/test";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));

export default defineConfig({
    testDir: ".",
    testMatch: /ui-builder-performance\.spec\.mjs/,
    outputDir: resolve(here, "test-results/ui-builder-performance"),
    timeout: process.env.UI_BUILDER_PERF === "1" ? 180_000 : 60_000,
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
