import { expect, test } from "@playwright/test";
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import { access, mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "..");
const launcher = resolve(
    root,
    "server/build/install/compose-preview-server/bin/compose-preview-server",
);
const app = resolve(root, "server/build/install/compose-preview-server/ui-builder");
const mcpLauncher = process.env.GATE2_MCP_LAUNCHER;
const operatorToken = "gate2-operator-token";
const designId = "gate2-jetcaster";

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
            operatorToken,
            "--ui-builder-dir",
            app,
            "--ui-builder-state-dir",
            stateDirectory,
            "--accept-docs",
            "--agent-grants",
            "--agent-grant-capabilities",
            "ui-builder-read,ui-builder-write,ui-builder-export",
        ],
        { cwd: tmpdir(), env: environment, stdio: ["ignore", "pipe", "pipe"] },
    );
    child.stdout.on("data", (chunk) => output.push(chunk));
    child.stderr.on("data", (chunk) => output.push(chunk));
    const origin = `http://127.0.0.1:${port}`;
    await waitUntilReady(origin, child);
    return {
        origin,
        child,
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

let requestSequence = 0;

async function apiCall(origin, request, auth = { actorId: "operator", token: operatorToken }) {
    const headers = { "content-type": "application/json" };
    let endpoint = `${origin}/api/ui-builder/v1/requests`;
    if (auth.bearer) headers.authorization = `Bearer ${auth.bearer}`;
    else endpoint += `?token=${encodeURIComponent(auth.token)}`;
    const response = await fetch(endpoint, {
        method: "POST",
        headers,
        body: JSON.stringify({
            schemaVersion: 1,
            requestId: `gate2-${++requestSequence}`,
            actorId: auth.actorId,
            request,
        }),
    });
    const envelope = JSON.parse(await response.text());
    return { status: response.status, response: envelope.response };
}

function responseOf(result, type) {
    expect(result.status, JSON.stringify(result.response)).toBe(200);
    expect(result.response.type).toBe(type);
    return result.response;
}

function hiddenField(html, name) {
    const match = new RegExp(`name="${name}" value="([^"]*)"`).exec(html);
    if (!match) throw new Error(`approval page has no ${name}`);
    return match[1];
}

async function issueAgentGrant(origin) {
    const capabilities = ["ui-builder-read", "ui-builder-write", "ui-builder-export"];
    const opened = await fetch(`${origin}/agent-access/request`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
            scope: "preview",
            label: "Gate 2 compose-preview-mcp executable",
            capabilities,
        }),
    }).then((response) => response.json());
    const approvalPage = await fetch(
        `${origin}/agent-access/${opened.requestId}?token=${operatorToken}`,
    ).then((response) => response.text());
    const form = new URLSearchParams({
        action: "approve",
        csrf: hiddenField(approvalPage, "csrf"),
        scope: "preview",
        ttl: "1800",
    });
    capabilities.forEach((capability) => form.append("capability", capability));
    const approval = await fetch(
        `${origin}/agent-access/${opened.requestId}?token=${operatorToken}`,
        {
            method: "POST",
            headers: { "content-type": "application/x-www-form-urlencoded" },
            body: form,
        },
    );
    expect(approval.status).toBe(200);
    const poll = await fetch(`${origin}/agent-access/poll`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
            requestId: opened.requestId,
            deviceSecret: opened.deviceSecret,
        }),
    }).then((response) => response.json());
    expect(poll.status).toBe("approved");
    expect(poll.capabilities.sort()).toEqual(capabilities.sort());
    const whoami = await fetch(`${origin}/agent-access/whoami`, {
        headers: { authorization: `Bearer ${poll.token}` },
    }).then((response) => response.json());
    expect(whoami.active).toBe(true);
    return {
        actorId: `agent:${whoami.fingerprint}`,
        bearer: poll.token,
        token: poll.token,
    };
}

class McpProcess {
    constructor(origin, agent, logPath) {
        this.nextId = 0;
        this.pending = new Map();
        this.output = [];
        this.logPath = logPath;
        this.process = spawn(mcpLauncher, [], {
            cwd: tmpdir(),
            env: {
                ...process.env,
                COMPOSE_PREVIEW_UI_BUILDER_URL: origin,
                COMPOSE_PREVIEW_UI_BUILDER_TOKEN: agent.bearer,
                COMPOSE_PREVIEW_UI_BUILDER_ACTOR: agent.actorId,
            },
            stdio: ["pipe", "pipe", "pipe"],
        });
        let buffered = "";
        this.process.stdout.on("data", (chunk) => {
            this.output.push(chunk);
            buffered += chunk.toString();
            while (buffered.includes("\n")) {
                const index = buffered.indexOf("\n");
                const line = buffered.slice(0, index).trim();
                buffered = buffered.slice(index + 1);
                if (!line) continue;
                const message = JSON.parse(line);
                if (message.id !== undefined) {
                    const pending = this.pending.get(Number(message.id));
                    if (pending) {
                        this.pending.delete(Number(message.id));
                        message.error ? pending.reject(new Error(JSON.stringify(message.error))) : pending.accept(message.result);
                    }
                }
            }
        });
        this.process.stderr.on("data", (chunk) => this.output.push(chunk));
    }

    request(method, params, timeout = 30_000) {
        const id = ++this.nextId;
        const payload = { jsonrpc: "2.0", id, method };
        if (params !== undefined) payload.params = params;
        return new Promise((accept, reject) => {
            const timer = setTimeout(() => {
                this.pending.delete(id);
                reject(new Error(`MCP ${method} timed out`));
            }, timeout);
            this.pending.set(id, {
                accept: (value) => {
                    clearTimeout(timer);
                    accept(value);
                },
                reject: (error) => {
                    clearTimeout(timer);
                    reject(error);
                },
            });
            this.process.stdin.write(`${JSON.stringify(payload)}\n`);
        });
    }

    async initialize() {
        await this.request("initialize", {
            protocolVersion: "2025-06-18",
            capabilities: {},
            clientInfo: { name: "ui-builder-gate2", version: "1" },
        });
        this.process.stdin.write(
            `${JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized" })}\n`,
        );
        const tools = await this.request("tools/list", {});
        expect(tools.tools.map((tool) => tool.name)).toContain("apply_design_operations");
    }

    async callTool(name, arguments_) {
        const result = await this.request("tools/call", { name, arguments: arguments_ }, 90_000);
        expect(result.isError, JSON.stringify(result)).not.toBe(true);
        return JSON.parse(result.content.find((item) => item.type === "text").text);
    }

    async close() {
        if (!this.process.stdin.destroyed) this.process.stdin.end();
        await Promise.race([
            new Promise((accept) => this.process.once("exit", accept)),
            new Promise((accept) => setTimeout(accept, 5_000)),
        ]);
        if (this.process.exitCode === null) this.process.kill("SIGKILL");
        await writeFile(this.logPath, Buffer.concat(this.output));
    }
}

async function openBrowserSession(browser, origin, actorId, clientId, token, create) {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    const designResponses = [];
    page.on("response", (response) => {
        if (response.url().includes("/api/ui-builder/v1/requests")) {
            designResponses.push(response.status());
        }
    });
    const query = new URLSearchParams({
        session: "live",
        designId,
        actor: actorId,
        clientId,
        token,
    });
    if (create) query.set("create", "true");
    await page.goto(`${origin}/ui-builder/?${query}`);
    await page.waitForFunction(
        () =>
            document.documentElement.dataset.uiBuilderReady === "true" &&
            globalThis.__uiBuilderEditor?.revision >= 0,
        null,
        { timeout: 30_000 },
    );
    return { page, designResponses };
}

async function editorState(page, revision) {
    await page.waitForFunction(
        (expected) => globalThis.__uiBuilderEditor?.revision === expected,
        revision,
        { timeout: 30_000 },
    );
    return page.evaluate(() => globalThis.__uiBuilderEditor);
}

test("Gate 2 converges browsers and actual MCP through restart and exports", async ({
    browser,
}, testInfo) => {
    const port = await freePort();
    const stateDirectory = await mkdtemp(resolve(tmpdir(), "compose-ui-builder-gate2-state-"));
    const firstLog = testInfo.outputPath("server-first.log");
    const restartLog = testInfo.outputPath("server-restart.log");
    let server = await startProductServer(port, stateDirectory, firstLog);
    let browserA;
    let browserB;
    let mcp;
    try {
        const checkpoint = (message) => console.log(`[gate2] ${message}`);
        if (!mcpLauncher) {
            throw new Error(
                "GATE2_MCP_LAUNCHER must point to a built compose-ai-tools compose-preview-mcp executable",
            );
        }
        await access(mcpLauncher).catch(() => {
            throw new Error(
                `actual MCP launcher not found at ${mcpLauncher}; build compose-ai-tools :mcp:installDist or set GATE2_MCP_LAUNCHER`,
            );
        });
        // Browser A performs an explicit open-miss/create, then an explicit reopen.
        checkpoint("opening Browser A with create=true");
        browserA = await openBrowserSession(
            browser,
            server.origin,
            "operator",
            "browser-a",
            operatorToken,
            true,
        );
        expect(browserA.designResponses).toContain(404);
        expect(browserA.designResponses).toContain(200);
        checkpoint("Browser A created revision 0; reloading to reopen");
        await browserA.page.reload();
        await editorState(browserA.page, 0);
        checkpoint("Browser A reopened revision 0");

        // A real editor control emits the Browser A operation; the API client reads it back.
        const scrimLayer = await browserA.page
            .getByRole("button", { name: /Reorder main-scrim/ })
            .boundingBox();
        checkpoint("found main-scrim layer");
        expect(scrimLayer).not.toBeNull();
        await browserA.page.mouse.click(
            scrimLayer.x + scrimLayer.width / 2,
            scrimLayer.y + scrimLayer.height / 2,
        );
        await browserA.page.waitForFunction(
            () => globalThis.__uiBuilderEditor?.selectedNodeId === "main-scrim",
        );
        checkpoint("selected main-scrim; duplicating");
        await browserA.page.keyboard.press("Control+d");
        const afterBrowser = await editorState(browserA.page, 1);
        checkpoint("Browser A committed revision 1");
        expect(afterBrowser.nodeCount).toBe(100);
        expect(afterBrowser.selectedNodeId).toBe("main-scrim-copy-001");
        const readByAdapter = responseOf(
            await apiCall(server.origin, { type: "openDesign", designId }),
            "snapshot",
        ).snapshot;
        expect(readByAdapter.state.document.revision).toBe(1);
        expect(readByAdapter.state.document.nodes["main-scrim-copy-001"]).toBeDefined();
        checkpoint("HTTP adapter observed revision 1; issuing agent grant");

        // A real agent grant gives the compose-ai-tools MCP process a distinct private-design actor.
        const agent = await issueAgentGrant(server.origin);
        checkpoint(`agent grant approved for ${agent.actorId}`);
        const privateDenial = await apiCall(
            server.origin,
            { type: "openDesign", designId },
            agent,
        );
        expect(privateDenial.status).toBe(403);
        expect(privateDenial.response).toMatchObject({ type: "error", error: { code: "forbidden" } });

        responseOf(
            await apiCall(server.origin, {
                type: "updateDesignAccess",
                designId,
                baseAccessRevision: 0,
                mutations: [
                    {
                        type: "grantActor",
                        actorId: agent.actorId,
                        role: "editor",
                        allowedActions: ["read", "write", "export"],
                    },
                ],
            }),
            "designAccess",
        );
        checkpoint("agent ACL granted; initializing actual MCP process");
        mcp = new McpProcess(server.origin, agent, testInfo.outputPath("compose-preview-mcp.log"));
        await mcp.initialize();
        checkpoint("actual MCP initialized");
        const openedThroughMcp = await mcp.callTool("open_design", { designId });
        checkpoint("actual MCP opened revision 1");
        expect(openedThroughMcp.response).toMatchObject({
            type: "snapshot",
            snapshot: { state: { document: { revision: 1 } } },
        });
        const adapterOutcome = (
            await mcp.callTool("apply_design_operations", {
                submission: {
                    type: "batch",
                    designId,
                    operationId: "gate2-mcp-edit",
                    actorId: agent.actorId,
                    clientId: "compose-preview-mcp",
                    baseRevision: 1,
                    operations: [
                        {
                            type: "setProperty",
                            nodeId: "search-placeholder",
                            property: "text",
                            value: { type: "string", value: "Edited through actual MCP" },
                        },
                    ],
                },
            })
        ).response.outcome;
        expect(adapterOutcome).toMatchObject({ type: "accepted", committedRevision: 2 });
        checkpoint("actual MCP committed revision 2; opening Browser B");

        browserB = await openBrowserSession(
            browser,
            server.origin,
            agent.actorId,
            "browser-b",
            agent.token,
            false,
        );
        const [stateA, stateB] = await Promise.all([
            editorState(browserA.page, 2),
            editorState(browserB.page, 2),
        ]);
        expect(stateA.documentHash).toBe(stateB.documentHash);
        const placeholderLayer = await browserB.page
            .getByRole("button", { name: /Reorder search-placeholder/ })
            .boundingBox();
        expect(placeholderLayer).not.toBeNull();
        await browserB.page.mouse.click(
            placeholderLayer.x + placeholderLayer.width / 2,
            placeholderLayer.y + placeholderLayer.height / 2,
        );
        await browserB.page.waitForFunction(
            () => globalThis.__uiBuilderEditor?.selectedText === "Edited through actual MCP",
        );
        checkpoint("Browser A and Browser B converged at revision 2");

        // An impossible future exclusive cursor forces the server's snapshot-recovery branch.
        const recovered = await browserB.page.evaluate(
            ({ designId: id, token }) =>
                new Promise((accept, reject) => {
                    const socket = new WebSocket(
                        `${location.origin.replace("http", "ws")}/api/ui-builder/v1/designs/${encodeURIComponent(id)}/updates?afterSequence=999999&token=${encodeURIComponent(token)}`,
                    );
                    const timer = setTimeout(() => reject(new Error("snapshot recovery timed out")), 10_000);
                    socket.onmessage = (event) => {
                        clearTimeout(timer);
                        socket.close();
                        accept(JSON.parse(String(event.data)));
                    };
                    socket.onerror = () => reject(new Error("snapshot recovery socket failed"));
                }),
            { designId, token: agent.token },
        );
        expect(recovered).toMatchObject({
            designId,
            update: { type: "snapshot", snapshot: { state: { document: { revision: 2 } } } },
        });
        checkpoint("future-cursor WebSocket recovered with snapshot");
        const reconnectButton = await browserB.page
            .getByRole("button", { name: "Reconnect" })
            .boundingBox();
        expect(reconnectButton).not.toBeNull();
        await browserB.page.mouse.click(
            reconnectButton.x + reconnectButton.width / 2,
            reconnectButton.y + reconnectButton.height / 2,
        );
        await editorState(browserB.page, 2);
        checkpoint("visible Browser B reconnect completed");

        await browserA.page.close();
        await browserB.page.close();
        await server.stop();
        checkpoint("first server stopped; restarting from persisted state");
        server = await startProductServer(port, stateDirectory, restartLog);

        const reopened = responseOf(
            await apiCall(server.origin, { type: "openDesign", designId }),
            "snapshot",
        ).snapshot;
        expect(reopened.state.document.revision).toBe(2);
        expect(reopened.state.document.nodes["search-placeholder"].properties.text.value).toBe(
            "Edited through actual MCP",
        );
        checkpoint("restart reopened revision 2; exporting deterministically");

        const artifacts = {};
        for (const format of ["png", "svg", "compose"]) {
            const request = { type: "exportDesign", designId, revision: 2, format };
            const first = responseOf(await apiCall(server.origin, request), "export").artifact;
            const second = responseOf(await apiCall(server.origin, request), "export").artifact;
            expect(second).toEqual(first);
            artifacts[format] = first;
            const bytes = first.encoding === "base64" ? Buffer.from(first.content, "base64") : Buffer.from(first.content);
            await writeFile(testInfo.outputPath(`revision-2.${format === "compose" ? "kt" : format}`), bytes);
        }
        expect(Buffer.from(artifacts.png.content, "base64").subarray(0, 8).toString("hex")).toBe(
            "89504e470d0a1a0a",
        );
        expect(artifacts.svg.content.startsWith("<svg")).toBe(true);
        expect(artifacts.svg.content).toContain("Edited through actual MCP");
        expect(artifacts.compose.content).toContain("Edited through actual MCP");
        expect(artifacts.png.diagnostics.map((item) => item.code)).toContain(
            "REVISION_PINNED_DAEMON_RENDER",
        );
        expect(artifacts.svg.diagnostics.map((item) => item.code)).toContain(
            "REVISION_PINNED_DAEMON_RENDER",
        );
        checkpoint("PNG, SVG, and Compose deterministic exports verified");

        const evidencePath = testInfo.outputPath("gate2-evidence.json");
        await writeFile(
            evidencePath,
            JSON.stringify(
                {
                    adapter: "compose-ai-tools compose-preview-mcp executable over stdio JSON-RPC",
                    revision: reopened.state.document.revision,
                    browserAHash: stateA.documentHash,
                    browserBHash: stateB.documentHash,
                    serviceDocumentHash: adapterOutcome.documentHash,
                    exportDigests: Object.fromEntries(
                        Object.entries(artifacts).map(([format, artifact]) => [
                            format,
                            artifact.contentDigest,
                        ]),
                    ),
                },
                null,
                2,
            ),
        );
        await testInfo.attach("gate2-evidence.json", {
            path: evidencePath,
            contentType: "application/json",
        });
    } finally {
        if (browserA && !browserA.page.isClosed()) await browserA.page.close();
        if (browserB && !browserB.page.isClosed()) await browserB.page.close();
        if (mcp) await mcp.close();
        await server.stop();
        for (const log of [firstLog, restartLog]) {
            try {
                await testInfo.attach(log.split("/").at(-1), {
                    body: await readFile(log),
                    contentType: "text/plain",
                });
            } catch {
                // A pre-start failure can leave no second log.
            }
        }
    }
});
