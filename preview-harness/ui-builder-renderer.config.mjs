import { defineConfig } from "@playwright/test";

const PORT = Number(process.env.HARNESS_PORT ?? 5612);

export default defineConfig({
    testDir: ".",
    testMatch: /ui-builder-renderer\.spec\.mjs/,
    outputDir: "test-results/ui-builder-renderer",
    timeout: 60_000,
    reporter: process.env.CI ? "github" : "list",
    use: {
        browserName: "chromium",
        baseURL: `http://127.0.0.1:${PORT}/ui-builder/build/wasmDist/`,
        viewport: { width: 1280, height: 800 },
        deviceScaleFactor: 1,
        launchOptions: {
            args: ["--enable-unsafe-swiftshader", "--use-gl=angle"],
        },
    },
    webServer: {
        command: "node _server.mjs",
        url: `http://127.0.0.1:${PORT}/ui-builder/build/wasmDist/index.html`,
        reuseExistingServer: !process.env.CI,
        env: { HARNESS_PORT: String(PORT) },
        stdout: "ignore",
        stderr: "pipe",
    },
});
