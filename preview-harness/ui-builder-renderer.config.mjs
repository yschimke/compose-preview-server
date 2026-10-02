import { defineConfig } from "@playwright/test";

const PORT = Number(process.env.HARNESS_PORT ?? 5612);

export default defineConfig({
    testDir: ".",
    testMatch: /ui-builder-renderer\.spec\.mjs/,
    outputDir: "test-results/ui-builder-renderer",
    timeout: 60_000,
    reporter: process.env.CI ? "github" : "list",
    use: {
        // Unlike the other harness lanes, this one does not set `serviceWorkers: "block"`. Playwright
        // implements the block with a script injected into every frame that reads
        // `navigator.serviceWorker`, and in the renderer's sandboxed iframe (no `allow-same-origin`)
        // that read throws — a page error this spec rightly fails on. Nothing here can register a
        // worker anyway: the lane serves the renderer's static dist, not the editor shell, and a
        // sandboxed frame has no service-worker access at all.
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
