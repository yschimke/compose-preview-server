import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: ".",
  testMatch: /ui-builder-projects\.spec\.mjs/,
  outputDir: "test-results/ui-builder-projects",
  timeout: 180_000,
  fullyParallel: false,
  reporter: process.env.CI ? "github" : "list",
  use: {
    serviceWorkers: "block",
    browserName: "chromium",
    viewport: { width: 1400, height: 1000 },
    trace: "retain-on-failure",
    launchOptions: {
      args: ["--enable-unsafe-swiftshader", "--use-gl=angle"],
      ...(process.env.HARNESS_CHROMIUM
        ? { executablePath: process.env.HARNESS_CHROMIUM }
        : {}),
    },
  },
});
