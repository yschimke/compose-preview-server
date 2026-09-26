// Which captures are uploaded and embedded without asking.
//
// A hosted capture gets an anonymous-read URL and is linked from a public GitHub issue. Captures
// of a public catalog page keep being uploaded automatically; captures of a page only a signed-in
// user can open (the UI builder, admin screens, any page of a token-gated host) wait for an
// explicit, default-off opt-in and otherwise go through the clipboard.

import "./setup.js";
import assert from "node:assert/strict";
import { resetDom, flush } from "./setup.js";
import { installCapture } from "../src/report/ui.js";
import { type Capture, STORE_KEY } from "../src/report/store.js";
import { isPrivatePath } from "../src/report/visibility.js";

const PNG = "data:image/png;base64,iVBORw0KGgo=";
const PUBLIC_PAGE = "/catalog/p/loading-button";
const PRIVATE_PAGE = "/ui-builder/designs";

function capture(id: string, page: string, uploadedUrl?: string): Capture {
    return {
        id,
        label: "Element · li#comment-by-someone",
        dataUrl: `${PNG}${id}`,
        width: 8,
        height: 8,
        page,
        uploadedUrl,
    };
}

let posts = 0;

/** `sessionStorage` plus a fetch whose uploads succeed at once and whose HEADs confirm a URL. */
function stubBrowser(
    captures: Capture[],
    capabilityHeaders: Record<string, string> = {},
): void {
    const store = new Map<string, string>([
        [STORE_KEY, JSON.stringify(captures)],
    ]);
    Object.defineProperty(globalThis, "sessionStorage", {
        configurable: true,
        value: {
            getItem: (key: string) => store.get(key) ?? null,
            setItem: (key: string, value: string) => void store.set(key, value),
            removeItem: (key: string) => void store.delete(key),
        },
    });
    posts = 0;
    Object.defineProperty(globalThis, "fetch", {
        configurable: true,
        value: (input: string | URL, init?: { method?: string }) => {
            const url = String(input);
            if (init?.method === "POST") {
                posts += 1;
                return Promise.resolve({
                    ok: true,
                    status: 200,
                    json: () =>
                        Promise.resolve({
                            url: `${location.origin}/i/shot-${posts}.png`,
                        }),
                });
            }
            if (init?.method === "HEAD")
                return Promise.resolve({ ok: true, status: 200 });
            if (url.includes("/images/capability")) {
                return Promise.resolve({
                    ok: true,
                    status: 204,
                    headers: new Headers(capabilityHeaders),
                });
            }
            // The data-URL read `blobFromDataUrl` does before posting.
            return Promise.resolve({ blob: () => Promise.resolve(url) });
        },
    });
    Object.defineProperty(navigator, "clipboard", {
        configurable: true,
        value: { write: () => Promise.resolve() },
    });
}

/** `/report-bug`, reached from [from], with the image lane advertised. */
function reportPage(from: string, scope = ""): void {
    history.replaceState(
        {},
        "",
        `/report-bug?from=${encodeURIComponent(from)}`,
    );
    document.documentElement.removeAttribute("data-cp-capture-ready");
    const scopeAttr = scope ? ` data-cp-capture-scope="${scope}"` : "";
    document.body.innerHTML = `
      <form class="cp-report-bug-form" method="get" target="_blank" rel="noopener"
        action="https://github.com/acme/tools/issues/new">
        <input class="cp-bug-summary-input" type="text" name="title" value="x" required>
        <input type="hidden" name="body" id="cp-bug-body" value="### Screenshot">
        <button type="submit" class="cp-bug-submit">Open a prefilled issue</button>
      </form>
      <div class="cp-shots" data-cp-capture-src="/assets/serve/abc/report-capture.js"
        data-cp-image-upload="true"${scopeAttr}>
        <ul class="cp-shot-list"></ul>
        <p class="cp-shot-note" role="status"></p>
      </div>`;
}

async function settle(): Promise<void> {
    for (let i = 0; i < 8; i++) await flush();
}

function body(): string {
    return document.querySelector<HTMLInputElement>("#cp-bug-body")!.value;
}

function consent(): HTMLInputElement | null {
    return document.querySelector<HTMLInputElement>(".cp-shot-share-input");
}

describe("capture upload scope", () => {
    beforeEach(resetDom);

    it("recognises the routes an anonymous visitor cannot open", () => {
        assert.equal(isPrivatePath("/ui-builder"), true);
        assert.equal(isPrivatePath("/ui-builder/abc/history"), true);
        assert.equal(isPrivatePath("/admin/ui-builder"), true);
        assert.equal(isPrivatePath("/api/ui-builder/v1/designs/x"), true);
        assert.equal(isPrivatePath("/ui-builderish"), false);
        assert.equal(isPrivatePath(PUBLIC_PAGE), false);
        assert.equal(isPrivatePath(null), false);
    });

    it("uploads and embeds a public page's capture automatically, as before", async () => {
        stubBrowser([capture("shot-1", PUBLIC_PAGE)]);
        reportPage(PUBLIC_PAGE);
        installCapture();
        await settle();

        assert.equal(posts, 1);
        assert.match(body(), /\/i\/shot-1\.png/);
        assert.match(body(), /Element · li#comment-by-someone/);
        assert.equal(consent(), null, "no opt-in is offered on a public page");
    });

    it("does not upload a UI-builder capture until the reporter opts in", async () => {
        stubBrowser([capture("shot-1", PRIVATE_PAGE)]);
        reportPage(PRIVATE_PAGE);
        installCapture();
        await settle();

        assert.equal(posts, 0, "nothing was uploaded");
        assert.doesNotMatch(body(), /\/i\//);
        const box = consent();
        assert.ok(box, "an opt-in is offered");
        assert.equal(box.checked, false, "and it defaults to off");
        assert.match(
            box.closest("label")!.textContent || "",
            /GitHub issue that links it is public/,
        );

        box.checked = true;
        box.dispatchEvent(new Event("change"));
        await settle();

        assert.equal(posts, 1);
        assert.match(body(), /!\[screenshot\]\(.*\/i\/shot-1\.png\)/);
        assert.doesNotMatch(
            body(),
            /comment-by-someone/,
            "the page's own labels stay out of the issue body",
        );
    });

    it("does not embed a private capture's earlier URL without the opt-in", async () => {
        // A capture uploaded after an opt-in on the page it was taken on rides to `/report-bug`
        // with its URL. Consent is per page view, so it is not embedded here until given again.
        stubBrowser([
            capture("shot-1", PRIVATE_PAGE, "http://localhost/i/old.png"),
        ]);
        reportPage(PRIVATE_PAGE);
        installCapture();
        await settle();

        assert.equal(posts, 0);
        assert.doesNotMatch(body(), /\/i\//);
        document
            .querySelector<HTMLFormElement>(".cp-report-bug-form")!
            .dispatchEvent(new Event("submit", { bubbles: true }));
        assert.doesNotMatch(body(), /\/i\//);
        assert.match(body(), /Paste your capture here/);
    });

    it("treats a page the server marks private the same way", async () => {
        stubBrowser([capture("shot-1", PUBLIC_PAGE)]);
        reportPage(PUBLIC_PAGE, "private");
        installCapture();
        await settle();

        assert.equal(posts, 0);
        assert.equal(consent()?.checked, false);
    });

    it("waits for the opt-in on a gated host's catalog page", async () => {
        // The launcher's own page: no upload attribute until the capability probe answers, and
        // that answer carries the host's scope.
        stubBrowser([capture("shot-1", "/catalog/p/x")], {
            "X-Compose-Preview-Capture-Scope": "private",
        });
        history.replaceState({}, "", "/catalog/p/x");
        document.documentElement.removeAttribute("data-cp-capture-ready");
        document.body.innerHTML = `
          <div class="cp-fab" data-cp-capture-src="/assets/serve/abc/report-capture.js">
            <div class="cp-shot" hidden>
              <p class="cp-shot-note" role="status"></p>
              <ul class="cp-shot-list"></ul>
            </div>
          </div>`;
        installCapture();
        await settle();

        assert.equal(
            document
                .querySelector(".cp-fab")
                ?.getAttribute("data-cp-capture-scope"),
            "private",
        );
        assert.equal(posts, 0);
        assert.equal(consent()?.checked, false);
    });

    it("uploads on a public host's catalog page once the probe answers", async () => {
        stubBrowser([capture("shot-1", "/catalog/p/x")], {
            "X-Compose-Preview-Capture-Scope": "public",
        });
        history.replaceState({}, "", "/catalog/p/x");
        document.documentElement.removeAttribute("data-cp-capture-ready");
        document.body.innerHTML = `
          <div class="cp-fab" data-cp-capture-src="/assets/serve/abc/report-capture.js">
            <div class="cp-shot" hidden>
              <p class="cp-shot-note" role="status"></p>
              <ul class="cp-shot-list"></ul>
            </div>
          </div>`;
        installCapture();
        await settle();

        assert.equal(posts, 1);
        assert.equal(consent(), null);
    });
});
