import { defineConfig } from "@playwright/test";

const PORT = Number(process.env.HARNESS_PORT ?? 5608);

export default defineConfig({
    testDir: ".",
    testMatch: /ui-builder-editor\.spec\.mjs/,
    outputDir: "test-results/ui-builder-editor",
    snapshotPathTemplate: "{testDir}/snapshots/{arg}{ext}",
    timeout: 60_000,
    reporter: process.env.CI ? "github" : "list",
    use: {
        browserName: "chromium",
        baseURL: `http://127.0.0.1:${PORT}/ui-builder/build/wasmDist/`,
        viewport: { width: 1440, height: 900 },
        deviceScaleFactor: 1,
        launchOptions: {
            args: [
                "--enable-unsafe-swiftshader",
                "--use-gl=angle",
                "--disable-partial-raster",
                "--disable-skia-runtime-opts",
            ],
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
