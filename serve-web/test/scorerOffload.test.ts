// Where the comparison metric runs, and the guarantee that it always runs somewhere.
//
// `scorer/offload.ts` exists to move `scorePlanes` onto a worker thread. Its contract is narrower
// than that, and it is the narrow one that matters: the number is the same wherever it is computed,
// and no failure of the worker half may turn a comparison into a non-comparison. Two consumers
// outside the browser — the publish-time score driver and the compare audit — inject the built
// asset into a bare page with no worker URL to find, so the on-thread path is not a degraded mode
// but the one those two have always taken and must keep taking.

import "./setup.js";
import assert from "node:assert/strict";
import { resetDom } from "./setup.js";
import {
    resetScorerOffloadForTests,
    scorePlanesOffloaded,
} from "../src/scorer/offload.js";
import { scorePlanes } from "../src/scorer/planes.js";

/** A small pair with real disagreement in it, so a wrong answer is a visibly wrong number. */
const WIDTH = 6;
const HEIGHT = 6;
function planes(): { reference: Float32Array; candidate: Float32Array } {
    const reference = new Float32Array(WIDTH * HEIGHT).fill(255);
    const candidate = new Float32Array(WIDTH * HEIGHT).fill(255);
    for (let i = 0; i < WIDTH * HEIGHT; i += 3) reference[i] = 0;
    for (let i = 0; i < WIDTH * HEIGHT; i += 5) candidate[i] = 0;
    return { reference, candidate };
}

/** The URL the page names, on the tag `offload.ts` reads it from. */
function nameWorker(url = "/assets/serve/v1/compare-scorer.js"): void {
    document.body.innerHTML = `<script data-cp-scorer-worker="${url}"></script>`;
}

type Behaviour = "reply" | "error" | "silent";

/** Workers this spec created, so an assertion can look at what was posted. */
let built: StubWorker[] = [];

class StubWorker {
    static constructing: "ok" | "throw" = "ok";
    static behaviour: Behaviour = "reply";
    readonly posted: Array<Record<string, unknown>> = [];
    terminated = false;
    private listeners = new Map<string, Array<(event: unknown) => void>>();

    constructor(readonly url: string) {
        if (StubWorker.constructing === "throw")
            throw new Error("blocked by policy");
        built.push(this);
    }
    addEventListener(type: string, handler: (event: unknown) => void): void {
        const list = this.listeners.get(type) ?? [];
        list.push(handler);
        this.listeners.set(type, list);
    }
    emit(type: string, event: unknown): void {
        for (const handler of this.listeners.get(type) ?? []) handler(event);
    }
    postMessage(request: Record<string, unknown>): void {
        this.posted.push(request);
        if (StubWorker.behaviour === "silent") return;
        const data =
            StubWorker.behaviour === "error"
                ? { id: request.id, error: "worker blew up" }
                : { id: request.id, percent: 42.5 };
        queueMicrotask(() => this.emit("message", { data }));
    }
    /** A worker-level failure — the shape a script that will not load reports. */
    fail(): void {
        this.emit("error", { message: "load failed" });
    }
    terminate(): void {
        this.terminated = true;
    }
}

describe("scorer offload", () => {
    let realWorker: typeof Worker;

    beforeEach(() => {
        built = [];
        StubWorker.constructing = "ok";
        StubWorker.behaviour = "reply";
        realWorker = globalThis.Worker;
        globalThis.Worker = StubWorker as never;
        resetScorerOffloadForTests();
    });

    afterEach(() => {
        globalThis.Worker = realWorker;
        resetScorerOffloadForTests();
        resetDom();
    });

    it("scores on this thread when the page names no worker", async () => {
        document.body.innerHTML = "";
        const { reference, candidate } = planes();
        const offloaded = await scorePlanesOffloaded(
            reference,
            candidate,
            WIDTH,
            HEIGHT,
        );
        const direct = await scorePlanes(reference, candidate, WIDTH, HEIGHT);
        assert.equal(offloaded, direct);
        assert.equal(built.length, 0, "and builds no worker to do it");
    });

    it("hands the planes to the worker the page names, and returns its answer", async () => {
        nameWorker();
        const { reference, candidate } = planes();
        const percent = await scorePlanesOffloaded(
            reference,
            candidate,
            WIDTH,
            HEIGHT,
        );
        assert.equal(percent, 42.5);
        assert.equal(built.length, 1);
        assert.equal(built[0].url, "/assets/serve/v1/compare-scorer.js");
        const request = built[0].posted[0];
        assert.equal(request.width, WIDTH);
        assert.equal(request.height, HEIGHT);
        assert.deepEqual(request.reference, reference);
    });

    it("leaves the planes intact, so the next ground can be scored from them", async () => {
        nameWorker();
        const { reference, candidate } = planes();
        const before = Array.from(reference);
        await scorePlanesOffloaded(reference, candidate, WIDTH, HEIGHT);
        assert.deepEqual(
            Array.from(reference),
            before,
            "transferring the buffer would empty it for the next ground",
        );
    });

    it("reuses one worker across comparisons", async () => {
        nameWorker();
        const { reference, candidate } = planes();
        await scorePlanesOffloaded(reference, candidate, WIDTH, HEIGHT);
        await scorePlanesOffloaded(reference, candidate, WIDTH, HEIGHT);
        assert.equal(built.length, 1, "one worker, two comparisons");
        assert.equal(built[0].posted.length, 2);
    });

    it("scores on this thread when the worker cannot be constructed", async () => {
        nameWorker();
        StubWorker.constructing = "throw";
        const { reference, candidate } = planes();
        const offloaded = await scorePlanesOffloaded(
            reference,
            candidate,
            WIDTH,
            HEIGHT,
        );
        assert.equal(
            offloaded,
            await scorePlanes(reference, candidate, WIDTH, HEIGHT),
            "a CSP that forbids the URL costs latency, never the comparison",
        );
    });

    it("scores on this thread when the worker answers with an error", async () => {
        nameWorker();
        StubWorker.behaviour = "error";
        const { reference, candidate } = planes();
        const offloaded = await scorePlanesOffloaded(
            reference,
            candidate,
            WIDTH,
            HEIGHT,
        );
        assert.equal(
            offloaded,
            await scorePlanes(reference, candidate, WIDTH, HEIGHT),
        );
    });

    it("keeps the worker after ONE comparison comes back an error", async () => {
        // Deliberately not a demotion. A single odd frame must not put the rest of a wall of
        // several hundred rows back on the main thread — which is the whole regression this
        // module exists to prevent. Only a systemic signal retires the worker; see below.
        nameWorker();
        StubWorker.behaviour = "error";
        const { reference, candidate } = planes();
        await scorePlanesOffloaded(reference, candidate, WIDTH, HEIGHT);
        StubWorker.behaviour = "reply";
        const second = await scorePlanesOffloaded(
            reference,
            candidate,
            WIDTH,
            HEIGHT,
        );
        assert.equal(second, 42.5, "the next comparison is still offloaded");
        assert.equal(built.length, 1, "and it is the same worker");
        assert.equal(built[0].posted.length, 2);
    });

    it("retires a worker that fails as a worker, and scores on this thread after", async () => {
        nameWorker();
        const { reference, candidate } = planes();
        await scorePlanesOffloaded(reference, candidate, WIDTH, HEIGHT);
        built[0].fail();
        const after = await scorePlanesOffloaded(
            reference,
            candidate,
            WIDTH,
            HEIGHT,
        );
        assert.equal(
            after,
            await scorePlanes(reference, candidate, WIDTH, HEIGHT),
        );
        assert.ok(built[0].terminated, "the dead worker is torn down");
        assert.equal(built.length, 1, "and not replaced");
        assert.equal(built[0].posted.length, 1, "nor asked again");
    });

    it("scores on this thread in an environment with no Worker at all", async () => {
        nameWorker();
        (globalThis as Record<string, unknown>).Worker = undefined;
        const { reference, candidate } = planes();
        const offloaded = await scorePlanesOffloaded(
            reference,
            candidate,
            WIDTH,
            HEIGHT,
        );
        assert.equal(
            offloaded,
            await scorePlanes(reference, candidate, WIDTH, HEIGHT),
        );
    });
});
