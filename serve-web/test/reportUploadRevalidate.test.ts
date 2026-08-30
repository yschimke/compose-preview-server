// A hosted capture URL restored from `sessionStorage` is a claim, not a fact.
//
// `hostedCaptureUrl` says the string is shaped like one of this origin's `/i/` URLs. That is a
// security check — it is what stops a tampered store embedding an off-origin tracking pixel in the
// reporter's issue — and it says nothing about whether the bytes are still there. The pile outlives
// the server: `sessionStorage` survives a restart of the image store and the lane's retention TTL,
// so a syntactically perfect URL can be a 404 by the time the report is filed.
//
// Trusting it costs the report its evidence twice over: the body embeds a broken image, AND the
// clipboard fallback is skipped, because the hand-off reads "every capture hosted" as "every
// capture embedded". So each restored URL is checked once per page before it is relied on.

import "./setup.js";
import assert from "node:assert/strict";
import { resetDom, flush } from "./setup.js";
import { installCapture } from "../src/report/ui.js";
import { Capture, STORE_KEY } from "../src/report/store.js";

const PNG = "data:image/png;base64,iVBORw0KGgo=";
const SUBJECT = "/catalog/p/loading-button";

function capture(id: string, uploadedUrl?: string): Capture {
    return {
        id,
        label: "Region",
        dataUrl: `${PNG}${id}`,
        width: 8,
        height: 8,
        page: SUBJECT,
        uploadedUrl,
    };
}

/** Every request the page made, so a test can assert what was NOT sent. */
let calls: Array<{ url: string; method: string }> = [];
let store: Map<string, string>;

/**
 * A browser whose image lane answers HEAD with [headStatus] and POST with a fresh URL.
 *
 * The HEAD is the revalidation; the POST is the re-upload it triggers. Keeping both on one stub is
 * what lets a test say "it re-uploaded" and "it did not" against the same fixture.
 */
function stubBrowser(captures: Capture[], headOk: boolean): void {
    store = new Map<string, string>([[STORE_KEY, JSON.stringify(captures)]]);
    Object.defineProperty(globalThis, "sessionStorage", {
        configurable: true,
        value: {
            getItem: (key: string) => store.get(key) ?? null,
            setItem: (key: string, value: string) => void store.set(key, value),
            removeItem: (key: string) => void store.delete(key),
        },
    });
    calls = [];
    let minted = 0;
    Object.defineProperty(globalThis, "fetch", {
        configurable: true,
        value: (input: string | URL, init?: { method?: string }) => {
            const method = init?.method ?? "GET";
            calls.push({ url: String(input), method });
            if (method === "HEAD") {
                return Promise.resolve({
                    ok: headOk,
                    status: headOk ? 200 : 404,
                });
            }
            if (method !== "POST") {
                // The data-URL read `blobFromDataUrl` does before posting.
                return Promise.resolve({
                    blob: () => Promise.resolve(String(input)),
                });
            }
            return Promise.resolve({
                ok: true,
                status: 200,
                json: () =>
                    Promise.resolve({
                        url: `${location.origin}/i/fresh-${++minted}.png`,
                    }),
            });
        },
    });
}

function reportPage(): void {
    history.replaceState(
        {},
        "",
        `/report-bug?from=${encodeURIComponent(SUBJECT)}`,
    );
    document.documentElement.removeAttribute("data-cp-capture-ready");
    document.documentElement.removeAttribute("data-cp-handoff-wired");
    document.body.innerHTML = `
      <form class="cp-report-bug-form" method="get" target="_blank" rel="noopener"
        action="https://github.com/acme/tools/issues/new">
        <input class="cp-bug-summary-input" type="text" name="title" required>
        <input type="hidden" name="body" id="cp-bug-body" value="### Screenshot\n">
        <button type="submit" class="cp-bug-submit">Open a prefilled issue</button>
      </form>
      <div class="cp-shots" data-cp-capture-src="/assets/serve/abc/report-capture.js"
        data-cp-image-upload="true">
        <p class="cp-sub cp-shots-empty">No captures came across.</p>
        <ul class="cp-shot-list"></ul>
        <p class="cp-shot-note" role="status"></p>
      </div>`;
}

function body(): string {
    return document.querySelector<HTMLInputElement>("#cp-bug-body")!.value;
}

function stored(): Capture[] {
    return JSON.parse(store.get(STORE_KEY) ?? "[]") as Capture[];
}

async function drain(): Promise<void> {
    for (let i = 0; i < 8; i++) await flush();
}

describe("revalidating a restored capture URL", () => {
    beforeEach(resetDom);

    it("re-uploads a capture whose hosted URL no longer resolves", async () => {
        const stale = `${location.origin}/i/gone.png`;
        stubBrowser([capture("shot-1", stale)], false);
        reportPage();
        installCapture();
        await drain();

        assert.deepEqual(
            calls.filter((c) => c.method === "HEAD").map((c) => c.url),
            [stale],
            "the restored URL must be checked before it is relied on",
        );
        assert.equal(
            calls.filter((c) => c.method === "POST").length,
            1,
            "a URL that 404s must be replaced, not trusted",
        );
        assert.equal(
            stored()[0]!.uploadedUrl,
            `${location.origin}/i/fresh-1.png`,
        );
        assert.ok(
            body().includes("/i/fresh-1.png"),
            `the report must embed the fresh URL, got: ${body()}`,
        );
        assert.ok(
            !body().includes("gone.png"),
            `the report must not embed the dead URL, got: ${body()}`,
        );
    });

    it("keeps a hosted URL that still resolves, without spending an upload", async () => {
        const live = `${location.origin}/i/live.png`;
        stubBrowser([capture("shot-1", live)], true);
        reportPage();
        installCapture();
        await drain();

        assert.equal(calls.filter((c) => c.method === "HEAD").length, 1);
        assert.equal(
            calls.filter((c) => c.method === "POST").length,
            0,
            "a URL that still resolves must not be re-uploaded — it costs a request and a slice of the rate-limit budget to learn nothing",
        );
        assert.equal(stored()[0]!.uploadedUrl, live);
        assert.ok(body().includes("/i/live.png"), body());
    });

    it("holds Submit down until the check has finished, and releases it after", async () => {
        // The window this closes: without revalidation the button was released immediately,
        // because a restored URL counted as uploaded — so a report could be filed in the gap
        // where its only evidence was a dead link.
        stubBrowser(
            [capture("shot-1", `${location.origin}/i/gone.png`)],
            false,
        );
        reportPage();
        installCapture();
        const submit =
            document.querySelector<HTMLButtonElement>(".cp-bug-submit")!;
        // Synchronously, before the first await: the stub answers instantly, so a flush here would
        // race the whole chain to completion and assert nothing. `uploadReportCaptures` counts the
        // unverified capture as pending and presses the button down before it yields, which is the
        // property that matters — there is no tick in which the report is submittable.
        assert.equal(
            submit.disabled,
            true,
            "an unverified URL must hold Submit down",
        );
        await drain();
        assert.equal(
            submit.disabled,
            false,
            "and it must be released once resolved",
        );
    });
});
