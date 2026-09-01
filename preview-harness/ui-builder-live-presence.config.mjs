import { defineConfig } from "@playwright/test";

export default defineConfig({
    testDir: ".",
    testMatch: /ui-builder-live-presence\.spec\.mjs/,
    outputDir: "test-results/ui-builder-live-presence",
    timeout: 120_000,
    fullyParallel: false,
    reporter: process.env.CI ? "github" : "list",
    use: {
        browserName: "chromium",
        baseURL: process.env.SERVE_URL || "http://127.0.0.1:8727",
        viewport: { width: 1440, height: 900 },
        deviceScaleFactor: 1,
        trace: "retain-on-failure",
        launchOptions: {
            args: ["--enable-unsafe-swiftshader", "--use-gl=angle"],
            ...(process.env.HARNESS_CHROMIUM
                ? { executablePath: process.env.HARNESS_CHROMIUM }
                : {}),
        },
    },
});
