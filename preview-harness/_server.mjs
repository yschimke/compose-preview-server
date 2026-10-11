// Static file server for the preview-server harness: serves this directory's fixture pages and the
// serve viewer's own CSS/JS so captures exercise the real assets. A copy of
// compose-preview-vscode's `preview-harness/_server.mjs`, rooted here; copied rather than shared to
// avoid a cross-repository dependency.

import { fileURLToPath } from "node:url";
import {
    dirname,
    resolve,
    relative,
    normalize,
    sep,
    isAbsolute,
} from "node:path";
import { readFile, stat } from "node:fs/promises";
import { createServer } from "node:http";

const harnessDir = dirname(fileURLToPath(import.meta.url));
export const harnessRoot = resolve(harnessDir, "..");

// The UI builder (yschimke/compose-ui-builder) build output, as a built checkout or the pinned
// release unpacked by `fetch-ui-builder-dists.sh` (CI). Both `COMPOSE_UI_BUILDER_DIR` (the
// harness's own, set by CI) and `ORG_GRADLE_PROJECT_composeUiBuilderDir` (Gradle's spelling of
// `-PcomposeUiBuilderDir`) are read; reading only the first made CI fall back silently to the
// sibling default and time out.
export const uiBuilderRoot = resolve(
    process.env.COMPOSE_UI_BUILDER_DIR ||
        process.env.ORG_GRADLE_PROJECT_composeUiBuilderDir ||
        resolve(harnessRoot, "../compose-ui-builder"),
);

// Path prefixes resolved against that checkout. Matched on the first segment, not as a string
// prefix, since `ui-builder` prefixes `ui-builder-renderer`. Mapping them here keeps the specs'
// in-repo paths unchanged.
const UI_BUILDER_SEGMENTS = new Set([
    "ui-builder",
    "ui-builder-renderer",
    "ui-builder-reference-jetcaster",
    "ui-builder-generated-jetcaster",
]);

const mimeByExt = {
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".mjs": "text/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".png": "image/png",
    ".svg": "image/svg+xml",
    ".wasm": "application/wasm",
    ".ttf": "font/ttf",
    ".map": "application/json; charset=utf-8",
};

// The CLI viewer's CSS/JS, resolved from this module rather than from the server root so it holds
// however the harness is booted (in-process or standalone).
const SERVE_ASSETS_DIR = resolve(
    harnessDir,
    "../server/src/main/resources/ee/schimke/composeai/cli/serve/assets",
);

export function startServer(root, port = 0) {
    return new Promise((resolveServer) => {
        const server = createServer(async (req, res) => {
            try {
                const url = new URL(req.url, "http://localhost");
                const rel = decodeURIComponent(url.pathname).replace(
                    /^\/+/,
                    "",
                );
                // Dev-only mapping of the renderer bundle onto the production server's
                // version-addressed route, so tests exercise opaque-origin iframe messaging.
                const rendererMatch =
                    /^ui-builder\/runtime\/m3-2026\.09-protocol2\/(.*)$/.exec(
                        rel,
                    );
                if (rendererMatch) {
                    // Defaults to the sibling checkout; CI sets `COMPOSE_UI_BUILDER_DIR`.
                    const rendererRoot = resolve(
                        uiBuilderRoot,
                        "ui-builder-renderer/build/wasmRendererDist",
                    );
                    const requested = rendererMatch[1] || "index.html";
                    const rendererPath = normalize(
                        resolve(rendererRoot, requested),
                    );
                    if (
                        relative(rendererRoot, rendererPath).startsWith("..") ||
                        isAbsolute(relative(rendererRoot, rendererPath))
                    ) {
                        res.writeHead(403);
                        res.end("forbidden");
                        return;
                    }
                    try {
                        const body = await readFile(rendererPath);
                        const ext = rendererPath.slice(
                            rendererPath.lastIndexOf("."),
                        );
                        res.writeHead(200, {
                            "content-type":
                                mimeByExt[ext] ?? "application/octet-stream",
                            "cache-control": "no-store",
                            "access-control-allow-origin": "*",
                        });
                        res.end(body);
                        return;
                    } catch {
                        res.writeHead(404);
                        res.end("not found: " + rel);
                        return;
                    }
                }
                // Serve-page fixtures embed hashed asset URLs (`/assets/serve/<hash>/serve.css`)
                // that live in the server's resources; without this, captures render unstyled and
                // without JS. Match on the basename and ignore the cache-busting hash.
                const assetMatch = /^assets\/serve\/[^/]+\/([^/]+)$/.exec(rel);
                if (assetMatch) {
                    const name = assetMatch[1];
                    const assetPath = resolve(SERVE_ASSETS_DIR, name);
                    // Check the resolved path rather than the input's shape (e.g. `%5C` decodes to
                    // `\` on Windows); the same containment test as the static handler below.
                    const within = relative(SERVE_ASSETS_DIR, assetPath);
                    if (
                        within.startsWith("..") ||
                        within === "" ||
                        isAbsolute(within)
                    ) {
                        res.writeHead(403);
                        res.end("forbidden");
                        return;
                    }
                    try {
                        const body = await readFile(assetPath);
                        const ext = name.slice(name.lastIndexOf("."));
                        res.writeHead(200, {
                            "content-type":
                                mimeByExt[ext] ?? "application/octet-stream",
                            "cache-control": "no-store",
                        });
                        res.end(body);
                        return;
                    } catch {
                        res.writeHead(404);
                        res.end("not found: " + rel);
                        return;
                    }
                }
                const base = UI_BUILDER_SEGMENTS.has(rel.split("/")[0])
                    ? uiBuilderRoot
                    : root;
                const target = normalize(resolve(base, rel));
                if (
                    relative(base, target).startsWith("..") ||
                    target === base + sep + ".." // safety
                ) {
                    res.writeHead(403);
                    res.end("forbidden");
                    return;
                }
                let filePath = target;
                try {
                    const s = await stat(filePath);
                    if (s.isDirectory()) {
                        filePath = resolve(filePath, "index.html");
                    }
                } catch {
                    res.writeHead(404);
                    res.end("not found: " + rel);
                    return;
                }
                const ext = filePath.slice(filePath.lastIndexOf("."));
                const body = await readFile(filePath);
                res.writeHead(200, {
                    "content-type":
                        mimeByExt[ext] ?? "application/octet-stream",
                    "cache-control": "no-store",
                });
                res.end(body);
            } catch (err) {
                // Don't echo the error (stack trace / internal paths) to the client; log it and
                // return a generic 500. (CodeQL: information exposure through a stack trace.)
                console.error("[harness] request error:", err);
                res.writeHead(500);
                res.end("internal server error");
            }
        });
        server.listen(port, "127.0.0.1", () => {
            const addr = server.address();
            resolveServer({
                origin: `http://127.0.0.1:${addr.port}`,
                port: addr.port,
                close: () => new Promise((r) => server.close(r)),
            });
        });
    });
}

// Standalone entry point for Playwright's `webServer`.
if (process.argv[1] === fileURLToPath(import.meta.url)) {
    const port = Number(process.env.HARNESS_PORT ?? 5599);
    const { origin } = await startServer(harnessRoot, port);
    // Playwright polls the configured `url`; this log is just for humans.
    console.log(`[harness] serving ${harnessRoot} at ${origin}`);
}

/**
 * Console errors the suites ignore; neither originates here.
 * - `Cache storage is disabled` — the sandboxed renderer frame has no Cache API, and the code falls
 *   back.
 * - `Accessing \`memory\` via \`wasmExports\`` — a Kotlin 2.4.20 deprecation notice from a
 *   dependency (https://kotl.in/vr3szr), still emitted on Compose Multiplatform 1.12.0.
 *
 * Delete an entry once its message stops being emitted (for the second, when Skiko stops reaching
 * for `wasmExports.memory`); a stale entry widens what every suite tolerates.
 */
export function isIgnorableConsoleError(text) {
    return (
        text.includes("Cache storage is disabled") ||
        text.includes("Accessing `memory` via `wasmExports`")
    );
}
