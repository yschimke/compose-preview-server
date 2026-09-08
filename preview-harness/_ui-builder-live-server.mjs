// Booting the packaged server with one real design on it.
//
// Shared by the specs that need a **live** UI builder rather than the static fixture app: a
// committed revision, a node in a stored document and a comment thread only exist once a design
// service is running, and the design-URL selectors name all three. The shape is the one
// `ui-builder-performance.spec.mjs` established — spawn `server/build/install/…`, seed from the
// checked-in Jetcaster fixture over the v1 envelope endpoint — pulled out here so the assertion
// lane and the evidence capture boot the *same* server rather than two that drift apart.
//
// Needs `./gradlew :ui-builder:wasmFrontendDist :server:installDist` first, and JDK 21 on
// JAVA_HOME: the start script resolves `java` from it, and the builder's renderer is compiled for
// 21. CI's `visual-harness` job already does both.
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { mkdtemp, readFile } from "node:fs/promises";

const here = dirname(fileURLToPath(import.meta.url));
export const harnessRoot = resolve(here, "..");

const launcher = resolve(
    harnessRoot,
    "server/build/install/compose-preview-server/bin/compose-preview-server",
);
const app = resolve(harnessRoot, "server/build/install/compose-preview-server/ui-builder");

export const catalogSystemId = "m3-catalog";

async function freePort() {
    const socket = createServer();
    await new Promise((accept, reject) => {
        socket.once("error", reject);
        socket.listen(0, "127.0.0.1", accept);
    });
    const { port } = socket.address();
    await new Promise((accept) => socket.close(accept));
    return port;
}

async function waitUntilReady(origin, child) {
    const deadline = Date.now() + 60_000;
    while (Date.now() < deadline) {
        if (child.exitCode !== null) {
            throw new Error(`server exited before readiness (${child.exitCode})`);
        }
        try {
            const response = await fetch(`${origin}/ui-builder/index.html`);
            if (response.ok) return;
        } catch {
            // The listener is not bound yet.
        }
        await new Promise((accept) => setTimeout(accept, 100));
    }
    throw new Error("server did not become ready");
}

/**
 * One server, on a free port, with its own throwaway design store.
 *
 * A fixed `--token` rather than the generated one, because the browser carries it in the URL and
 * the spec has to be able to write it there. No `--agent-grants`: everything here acts as the
 * operator, which is the identity a token already authenticates.
 */
export async function startUiBuilderServer(token) {
    const port = await freePort();
    const stateDirectory = await mkdtemp(`${tmpdir()}/ui-builder-live-`);
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
    child.stdout.resume();
    child.stderr.resume();
    const origin = `http://127.0.0.1:${port}`;
    await waitUntilReady(origin, child);
    return {
        origin,
        token,
        async stop() {
            if (child.exitCode === null) child.kill("SIGINT");
            await Promise.race([
                new Promise((accept) => child.once("exit", accept)),
                new Promise((accept) => setTimeout(accept, 15_000)),
            ]);
            if (child.exitCode === null) child.kill("SIGKILL");
        },
    };
}

let requestSequence = 0;

async function apiCall(server, request) {
    const response = await fetch(
        `${server.origin}/api/ui-builder/v1/requests?token=${encodeURIComponent(server.token)}`,
        {
            method: "POST",
            headers: { "content-type": "application/json" },
            body: JSON.stringify({
                schemaVersion: 1,
                requestId: `live-harness-${++requestSequence}`,
                actorId: "operator",
                request,
            }),
        },
    );
    const body = JSON.parse(await response.text());
    if (response.status !== 200) {
        throw new Error(`envelope call failed (${response.status}): ${JSON.stringify(body)}`);
    }
    return body.response;
}

/**
 * The checked-in Jetcaster Discover screen, as one design.
 *
 * The same fixture the gate2 replay and the performance lane seed from, and for the same reason: a
 * realistic screen with 108 real node ids beats a hand-written stub that would drift from both.
 * One batch rather than 108 calls, because several container capabilities require children and
 * publishing each parent alone would be a deliberately invalid intermediate document.
 */
export async function seedJetcasterDesign(server, designId) {
    const fixture = JSON.parse(
        await readFile(
            resolve(
                harnessRoot,
                "docs/design/fixtures/ui-builder/jetcaster-discover-operations-v1.json",
            ),
            "utf8",
        ),
    );
    const [create, ...inserts] = fixture.operations;
    if (create.type !== "createDesign") throw new Error("fixture does not open with createDesign");
    await apiCall(server, {
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
    });
    const response = await apiCall(server, {
        type: "applyOperation",
        submission: {
            type: "batch",
            designId,
            operationId: `seed-${designId}`,
            actorId: "operator",
            clientId: "live-harness-seed",
            baseRevision: 0,
            operations: inserts.map((operation) => ({
                type: "insertNode",
                node: operation.node,
                location: {
                    parent: operation.parent ?? null,
                    afterNodeId: operation.afterNodeId ?? null,
                },
            })),
        },
    });
    if (response.outcome?.type !== "accepted") {
        throw new Error(`seed was refused: ${JSON.stringify(response.outcome)}`);
    }
    return response.outcome.committedRevision;
}

/** One thread on the design's comment board. Returns the id the store minted for it. */
export async function seedCommentThread(server, designId, { nodeId, body, displayName }) {
    const response = await fetch(
        `${server.origin}/api/ui-builder/v1/designs/${designId}/comments` +
            `?token=${encodeURIComponent(server.token)}`,
        {
            method: "POST",
            headers: { "content-type": "application/json" },
            body: JSON.stringify({
                anchor: nodeId ? { nodeId } : null,
                body,
                displayName: displayName ?? "Operator",
            }),
        },
    );
    const board = JSON.parse(await response.text());
    if (response.status !== 201) {
        throw new Error(`comment was refused (${response.status}): ${JSON.stringify(board)}`);
    }
    return board.threads[board.threads.length - 1].id;
}

/**
 * The canonical design URL, with the identity this harness signs in as and whichever selectors the
 * caller is testing.
 *
 * Built here with `URLSearchParams` rather than by the code under test on purpose: a spec that
 * asked `designUrlPath` for the URL would agree with itself whatever either of them did.
 */
export function designPermalink(server, designId, { revision, node, thread } = {}) {
    const query = new URLSearchParams({
        actor: "operator",
        clientId: "live-harness",
        token: server.token,
    });
    if (revision !== undefined) query.set("revision", String(revision));
    if (node !== undefined) query.set("node", node);
    const fragment = thread === undefined ? "" : `#thread=${encodeURIComponent(thread)}`;
    return `${server.origin}/ui-builder/${catalogSystemId}/${designId}?${query}${fragment}`;
}
