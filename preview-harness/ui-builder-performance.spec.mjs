import { expect, test } from "@playwright/test";
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import { cpus, hostname, platform, release, tmpdir } from "node:os";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { mkdtemp, readFile, writeFile } from "node:fs/promises";

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "..");
const launcher = resolve(
    root,
    "server/build/install/compose-preview-server/bin/compose-preview-server",
);
const app = resolve(root, "server/build/install/compose-preview-server/ui-builder");
const token = "ui-builder-performance-token";
const designId = "performance-jetcaster";
const perfMode = process.env.UI_BUILDER_PERF === "1";
const propagationSamples = perfMode ? 50 : 3;
const reopenSamples = perfMode ? 20 : 2;
const warmupEdits = perfMode ? 5 : 1;
const frameBudgetMs = 1000 / 60;

async function freePort() {
    const server = createServer();
    await new Promise((accept, reject) => {
        server.once("error", reject);
        server.listen(0, "127.0.0.1", accept);
    });
    const { port } = server.address();
    await new Promise((accept) => server.close(accept));
    return port;
}

async function waitUntilReady(origin, process) {
    const deadline = Date.now() + 30_000;
    while (Date.now() < deadline) {
        if (process.exitCode !== null) {
            throw new Error(`server exited before readiness (${process.exitCode})`);
        }
        try {
            const response = await fetch(`${origin}/ui-builder/index.html`);
            if (response.ok) return;
        } catch {
            // Listener is not bound yet.
        }
        await new Promise((accept) => setTimeout(accept, 100));
    }
    throw new Error("server did not become ready");
}

async function startProductServer(port, stateDirectory, logPath) {
    const output = [];
    const environment = { ...process.env };
    delete environment.JAVA_OPTS;
    delete environment.UI_BUILDER_REAL_RENDER_APP_HOME;
    const child = spawn(
        launcher,
        [
            "--host",
            "127.0.0.1",
            "--port",
            String(port),
            "--token",
            token,
            "--ui-builder-dir",
            app,
            "--ui-builder-state-dir",
            stateDirectory,
            "--accept-docs",
        ],
        { cwd: tmpdir(), env: environment, stdio: ["ignore", "pipe", "pipe"] },
    );
    child.stdout.on("data", (chunk) => output.push(chunk));
    child.stderr.on("data", (chunk) => output.push(chunk));
    const origin = `http://127.0.0.1:${port}`;
    await waitUntilReady(origin, child);
    return {
        origin,
        async stop() {
            if (child.exitCode === null) child.kill("SIGINT");
            await Promise.race([
                new Promise((accept) => child.once("exit", accept)),
                new Promise((accept) => setTimeout(accept, 15_000)),
            ]);
            if (child.exitCode === null) {
                const exited = new Promise((accept) => child.once("exit", accept));
                child.kill("SIGKILL");
                await exited;
            }
            await writeFile(logPath, Buffer.concat(output));
        },
    };
}

function sessionUrl(origin, clientId, create = false) {
    const query = new URLSearchParams({
        session: "live",
        designId,
        actor: "operator",
        clientId,
        token,
    });
    if (create) query.set("create", "true");
    return `${origin}/ui-builder/?${query}`;
}

/**
 * Bring `performance-jetcaster` into existence before a page opens it.
 *
 * The editor **opens** a design; it does not create one. A `?create=true` in the session URL is
 * read by nothing — not this client, not the server — so a session pointed at a design that
 * does not exist gets `notFound` from `openDesign`, sets a "Live error" status, and never calls
 * `markReady()`. That is a page that never becomes interactive, which is indistinguishable from a
 * slow one at [waitForInteractive]'s timeout and is what this spec used to fail on.
 *
 * Seeded from the same checked-in fixture `ui-builder-gate2.spec.mjs` replays, for two reasons: it
 * is the document the measurements are about — a realistic Jetcaster Discover screen rather
 * than an empty canvas — and it is where `search-placeholder` comes from, the node every
 * [measureEdit] mutates. A hand-written stub here would drift from both.
 */
async function seedDesign(origin) {
    const fixture = JSON.parse(
        await readFile(
            resolve(root, "docs/design/fixtures/ui-builder/jetcaster-discover-operations-v1.json"),
            "utf8",
        ),
    );
    const [create, ...inserts] = fixture.operations;
    expect(create.type).toBe("createDesign");
    responseOf(
        await apiCall(origin, {
            type: "createDesign",
            document: {
                schema: fixture.documentSchema,
                id: designId,
                title: create.title,
                revision: 0,
                catalogPin: create.catalogPin,
                environment: create.environment,
                stateVariables: create.stateVariables,
                roots: [],
                nodes: {},
                assets: {},
                tokenBindings: {},
            },
        }),
        "snapshot",
    );
    // One batch, not 108 calls: several container capabilities require children, so publishing
    // each parent before its next fixture operation would expose a deliberately invalid
    // intermediate document. Same reasoning, and same shape, as the gate2 replay.
    const outcome = responseOf(
        await apiCall(origin, {
            type: "applyOperation",
            submission: {
                type: "batch",
                designId,
                operationId: "seed-jetcaster-discover-operations-v1",
                actorId: "operator",
                clientId: "performance-seed",
                baseRevision: 0,
                operations: inserts.map((operation) => {
                    expect(operation.type).toBe("insertNode");
                    return {
                        type: "insertNode",
                        node: operation.node,
                        location: {
                            parent: operation.parent ?? null,
                            afterNodeId: operation.afterNodeId ?? null,
                        },
                    };
                }),
            },
        }),
        "operationOutcome",
    );
    expect(outcome.outcome, JSON.stringify(outcome.outcome)).toMatchObject({
        type: "accepted",
        committedRevision: 1,
    });
    return outcome.outcome.committedRevision;
}

let requestSequence = 0;

async function apiCall(origin, request) {
    const endpoint = `${origin}/api/ui-builder/v1/requests?token=${encodeURIComponent(token)}`;
    const response = await fetch(endpoint, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
            schemaVersion: 1,
            requestId: `performance-seed-${++requestSequence}`,
            actorId: "operator",
            request,
        }),
    });
    return { status: response.status, response: JSON.parse(await response.text()).response };
}

function responseOf(result, type) {
    expect(result.status, JSON.stringify(result.response)).toBe(200);
    expect(result.response.type).toBe(type);
    return result.response;
}

async function waitForInteractive(page) {
    await page.waitForFunction(
        () =>
            document.documentElement.dataset.uiBuilderReady === "true" &&
            globalThis.__uiBuilderPerformance?.interactive?.completedAtMs > 0 &&
            globalThis.__uiBuilderInspection?.generation?.completed === true,
        null,
        { timeout: 30_000 },
    );
    return page.evaluate(() => ({
        revision: globalThis.__uiBuilderInspection.documentRevision,
        completedAtMs: globalThis.__uiBuilderPerformance.interactive.completedAtMs,
    }));
}

async function installWriter(page) {
    await page.evaluate(
        ({ designId: id, token: sessionToken }) => {
            const channel = new BroadcastChannel("ui-builder-performance-writer");
            globalThis.__uiBuilderPerformanceWriter = channel;
            channel.onmessage = async ({ data }) => {
                if (data?.type !== "apply") return;
                try {
                    const response = await fetch(
                        `/api/ui-builder/v1/requests?token=${encodeURIComponent(sessionToken)}`,
                        {
                            method: "POST",
                            headers: { "content-type": "application/json" },
                            body: JSON.stringify({
                                schemaVersion: 1,
                                requestId: `perf-${data.revision}`,
                                actorId: "operator",
                                request: {
                                    type: "applyOperation",
                                    submission: {
                                        type: "batch",
                                        designId: id,
                                        operationId: `perf-edit-${data.revision}`,
                                        actorId: "operator",
                                        clientId: "performance-writer",
                                        baseRevision: data.revision - 1,
                                        operations: [
                                            {
                                                type: "setProperty",
                                                nodeId: "search-placeholder",
                                                property: "text",
                                                value: {
                                                    type: "string",
                                                    value: `Performance sample ${data.revision}`,
                                                },
                                            },
                                        ],
                                    },
                                },
                            }),
                        },
                    );
                    const envelope = await response.json();
                    channel.postMessage({
                        type: "result",
                        revision: data.revision,
                        status: response.status,
                        response: envelope.response,
                    });
                } catch (error) {
                    channel.postMessage({
                        type: "result",
                        revision: data.revision,
                        error: String(error),
                    });
                }
            };
        },
        { designId, token },
    );
}

async function measureEdit(observer, revision) {
    return observer.evaluate(
        async ({ expectedRevision }) => {
            const channel = new BroadcastChannel("ui-builder-performance-writer");
            const startedAtMs = performance.now();
            let writerResult;
            channel.onmessage = ({ data }) => {
                if (data?.type === "result" && data.revision === expectedRevision) {
                    writerResult = data;
                }
            };
            channel.postMessage({ type: "apply", revision: expectedRevision });
            const deadline = performance.now() + 10_000;
            while (performance.now() < deadline) {
                const performanceState = globalThis.__uiBuilderPerformance;
                const protocolReceipt = performanceState?.protocolReceipts
                    ?.slice()
                    .reverse()
                    .find(
                        (marker) =>
                            marker.kind === "delta" && marker.revision === expectedRevision,
                    );
                const canvasApply = performanceState?.canvasApplies
                    ?.slice()
                    .reverse()
                    .find((marker) => marker.revision === expectedRevision);
                const reduction = performanceState?.phases
                    ?.slice()
                    .reverse()
                    .find(
                        (marker) =>
                            marker.revision === expectedRevision &&
                            marker.name.startsWith("verifiedPropertyDelta"),
                    );
                const projection = performanceState?.phases
                    ?.slice()
                    .reverse()
                    .find(
                        (marker) =>
                            marker.revision === expectedRevision &&
                            marker.name === "propertyDeltaProjection",
                    );
                const hash = performanceState?.phases
                    ?.slice()
                    .reverse()
                    .find(
                        (marker) =>
                            marker.revision === expectedRevision &&
                            marker.name === "propertyDeltaHash",
                    );
                const inspectionEncode = performanceState?.phases
                    ?.slice()
                    .reverse()
                    .find(
                        (marker) =>
                            marker.revision === expectedRevision &&
                            marker.name === "inspectionEncode",
                    );
                const inspectionInvalidated = performanceState?.phases
                    ?.slice()
                    .reverse()
                    .find(
                        (marker) =>
                            marker.revision === expectedRevision &&
                            marker.name === "inspectionInvalidated",
                    );
                if (writerResult?.error) throw new Error(writerResult.error);
                if (writerResult && writerResult.status !== 200) {
                    throw new Error(JSON.stringify(writerResult));
                }
                if (writerResult && protocolReceipt && canvasApply && reduction) {
                    channel.close();
                    return {
                        revision: expectedRevision,
                        propagationMs: protocolReceipt.receivedAtMs - startedAtMs,
                        canvasApplyMs: canvasApply.completedAtMs - protocolReceipt.receivedAtMs,
                        snapshotRefreshMs:
                            canvasApply.receiptAtMs - protocolReceipt.receivedAtMs,
                        authoritativeToCanvasMs: canvasApply.latencyMs,
                        localReductionMs: reduction.durationMs,
                        localReductionPath: reduction.name,
                        propertyDeltaProjectionMs: projection?.durationMs,
                        propertyDeltaHashMs: hash?.durationMs,
                        inspectionEncodeMs: inspectionEncode?.durationMs,
                        receiptToInspectionInvalidatedMs:
                            inspectionInvalidated?.startedAtMs - canvasApply.receiptAtMs,
                        markers: {
                            startedAtMs,
                            protocolReceivedAtMs: protocolReceipt.receivedAtMs,
                            authoritativeReceivedAtMs: canvasApply.receiptAtMs,
                            canvasCompletedAtMs: canvasApply.completedAtMs,
                        },
                    };
                }
                await new Promise((accept) => setTimeout(accept, 1));
            }
            channel.close();
            throw new Error(`revision ${expectedRevision} performance markers timed out`);
        },
        { expectedRevision: revision },
    );
}

function percentile(values, fraction) {
    const sorted = [...values].sort((left, right) => left - right);
    return sorted[Math.max(0, Math.ceil(sorted.length * fraction) - 1)];
}

function summary(values) {
    return {
        count: values.length,
        min: Math.min(...values),
        p50: percentile(values, 0.5),
        p95: percentile(values, 0.95),
        max: Math.max(...values),
    };
}

test("UI-builder performance acceptance markers remain bounded", async ({ browser }, testInfo) => {
    const stateDirectory = await mkdtemp(resolve(tmpdir(), "compose-ui-builder-performance-"));
    const logPath = testInfo.outputPath("server.log");
    const port = await freePort();
    const server = await startProductServer(port, stateDirectory, logPath);
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
    const observer = await context.newPage();
    const writer = await context.newPage();
    // The editor must never be refused by its own server. Its API calls were split across three
    // `fetch` helpers and only one carried the page's `?token=`, so `identity`, `device-presets`
    // and the reference overlay answered 401 on every load of a `--token` deployment — silently,
    // because each caller treats its own failure as an empty result: a guessed actor, no device
    // menu, no overlay.
    //
    // 401/403 only, deliberately, and not every status >= 400: a 404 here is routinely the truth
    // ("no overlay saved yet", "this box serves no such published catalog") and asserting on it
    // would pin the fixture's shape rather than the editor's behaviour. Being unauthenticated
    // against the server that served the page never is.
    const refusedRequests = [];
    for (const page of [observer, writer]) {
        page.on("response", (response) => {
            if (response.status() === 401 || response.status() === 403) {
                const request = response.request();
                refusedRequests.push(
                    `${response.status()} ${request.method()} ${response.url()}`,
                );
            }
        });
    }
    try {
        // The seeded document is revision 1, not 0: the writer derives `baseRevision` from the
        // revision it is asked to produce, so every edit below counts from what the seed left.
        const seededRevision = await seedDesign(server.origin);
        await observer.goto(sessionUrl(server.origin, "performance-observer", true));
        await waitForInteractive(observer);
        await writer.goto(sessionUrl(server.origin, "performance-writer"));
        await waitForInteractive(writer);
        await installWriter(writer);

        const editMeasurements = [];
        for (let index = 1; index <= warmupEdits + propagationSamples; index += 1) {
            const measurement = await measureEdit(observer, seededRevision + index);
            if (index > warmupEdits) editMeasurements.push(measurement);
        }

        // Catalog, Wasm, and fonts are warm. Each navigation gets a fresh monotonic performance
        // timeline; the explicit stable-render marker is measured from that navigation origin.
        await observer.reload();
        await waitForInteractive(observer);
        const reopenMeasurements = [];
        for (let index = 0; index < reopenSamples; index += 1) {
            await observer.reload();
            reopenMeasurements.push(await waitForInteractive(observer));
        }

        const propagation = summary(editMeasurements.map((item) => item.propagationMs));
        const canvasApply = summary(editMeasurements.map((item) => item.canvasApplyMs));
        const snapshotRefresh = summary(editMeasurements.map((item) => item.snapshotRefreshMs));
        const authoritativeToCanvas = summary(
            editMeasurements.map((item) => item.authoritativeToCanvasMs),
        );
        const localReduction = summary(editMeasurements.map((item) => item.localReductionMs));
        const propertyDeltaProjection = summary(
            editMeasurements.map((item) => item.propertyDeltaProjectionMs),
        );
        const propertyDeltaHash = summary(
            editMeasurements.map((item) => item.propertyDeltaHashMs),
        );
        const inspectionEncode = summary(
            editMeasurements.map((item) => item.inspectionEncodeMs),
        );
        const receiptToInspectionInvalidated = summary(
            editMeasurements.map((item) => item.receiptToInspectionInvalidatedMs),
        );
        const propertyDeltaPaths = {
            accepted: editMeasurements.filter(
                (item) => item.localReductionPath === "verifiedPropertyDeltaAccepted",
            ).length,
            fallback: editMeasurements.filter(
                (item) => item.localReductionPath === "verifiedPropertyDeltaFallback",
            ).length,
        };
        const reopen = summary(reopenMeasurements.map((item) => item.completedAtMs));
        const results = {
            schema: "compose-ui-builder-performance-results/v1",
            mode: perfMode ? "acceptance" : "correctness",
            targets: {
                propagationP95Ms: 250,
                canvasApplyP95Ms: frameBudgetMs,
                cachedReopenP95Ms: 2000,
            },
            summaries: {
                propagation,
                canvasApply,
                cachedReopen: reopen,
                diagnostics: {
                    snapshotRefresh,
                    localReduction,
                    propertyDeltaProjection,
                    propertyDeltaHash,
                    inspectionEncode,
                    receiptToInspectionInvalidated,
                    authoritativeToCanvas,
                    propertyDeltaPaths,
                },
            },
            samples: { edits: editMeasurements, cachedReopens: reopenMeasurements },
            environment: {
                capturedAt: new Date().toISOString(),
                hostname: hostname(),
                platform: platform(),
                release: release(),
                architecture: process.arch,
                cpu: cpus()[0]?.model ?? "unknown",
                logicalCpuCount: cpus().length,
                node: process.version,
                chromium: browser.version(),
                viewport: "1440x900",
                serverLauncher: launcher,
            },
        };
        const resultPath = testInfo.outputPath("performance-results.json");
        await writeFile(resultPath, JSON.stringify(results, null, 2));
        await testInfo.attach("performance-results.json", {
            path: resultPath,
            contentType: "application/json",
        });
        const reportPath = testInfo.outputPath("performance-results.md");
        const milliseconds = (value) => value.toFixed(2);
        const reportRow = (label, metric, target) =>
            [
                "",
                label,
                metric.count,
                `${milliseconds(metric.p50)} ms`,
                `${milliseconds(metric.p95)} ms`,
                target,
                "",
            ].join(" | ");
        const referenceMachine = [
            results.environment.cpu,
            `${results.environment.logicalCpuCount} logical CPUs`,
            `${results.environment.platform} ${results.environment.release}`,
            results.environment.architecture,
            `Node ${results.environment.node}`,
            `Chromium ${results.environment.chromium}`,
            `viewport ${results.environment.viewport}`,
        ].join(", ");
        await writeFile(
            reportPath,
            `# UI-builder performance ${results.mode} results\n\n` +
                `| Gate | Samples | p50 | p95 | Target |\n` +
                `| --- | ---: | ---: | ---: | ---: |\n` +
                `${reportRow("Commit to another client receipt", propagation, "<250 ms")}\n` +
                `${reportRow(
                    "Protocol receipt to Wasm canvas",
                    canvasApply,
                    `<${milliseconds(frameBudgetMs)} ms`,
                )}\n` +
                `${reportRow("Cached reopen to stable clean render", reopen, "<2000 ms")}\n\n` +
                `Reference machine: ${referenceMachine}.\n`,
        );
        await testInfo.attach("performance-results.md", {
            path: reportPath,
            contentType: "text/markdown",
        });

        expect(propagation.count).toBe(propagationSamples);
        expect(canvasApply.count).toBe(propagationSamples);
        expect(reopen.count).toBe(reopenSamples);
        expect(editMeasurements.every((item) => item.propagationMs >= 0)).toBe(true);
        expect(editMeasurements.every((item) => item.canvasApplyMs >= 0)).toBe(true);
        expect(
            editMeasurements.every(
                (item) => item.localReductionPath === "verifiedPropertyDeltaAccepted",
            ),
        ).toBe(true);
        expect(propertyDeltaPaths).toEqual({ accepted: propagationSamples, fallback: 0 });
        expect(reopenMeasurements.every((item) => item.completedAtMs > 0)).toBe(true);
        expect(refusedRequests, refusedRequests.join("\n")).toEqual([]);
        if (perfMode) {
            expect(propagation.p95).toBeLessThan(250);
            expect(canvasApply.p95).toBeLessThan(frameBudgetMs);
            expect(reopen.p95).toBeLessThan(2000);
        }
    } finally {
        await context.close();
        await server.stop();
        await testInfo.attach("server.log", {
            body: await readFile(logPath),
            contentType: "text/plain",
        });
    }
});
