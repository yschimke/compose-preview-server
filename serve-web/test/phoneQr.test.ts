// The export bar's "On phone" button and its QR popover: offered to a fine pointer only, fetched on
// first click, carrying the page URL without its token, and closed by Esc, a click outside or the
// button again.

import "./setup.js";
import assert from "node:assert/strict";
import { resetDom } from "./setup.js";
import { installPhoneQr, type QrApi } from "../src/viewer/phoneQr.js";
import {
    isLoopbackHost,
    mountPhoneQr,
    phoneQrMarkup,
} from "../src/qr/phonePopover.js";

const tick = () => new Promise((r) => setTimeout(r, 0));

function stubPointer(coarse: boolean) {
    Object.defineProperty(window, "matchMedia", {
        configurable: true,
        value: (query: string) => ({
            matches: query.includes("pointer: coarse") ? coarse : false,
        }),
    });
}

function exportBar(qrSrc: string | null = "/assets/serve/v/qr-code.js") {
    document.body.innerHTML = `<div class="cp-export"${
        qrSrc ? ` data-cp-qr-src="${qrSrc}"` : ""
    }><span class="cp-link-group"><button class="cp-copyurl">Copy link</button></span></div>
    <p id="elsewhere">elsewhere</p>`;
}

/** The real popover, as `qr-code.js` would hand it over, counting how often it was fetched. */
function realApi() {
    const calls = { loads: 0 };
    const load = () => {
        calls.loads++;
        return Promise.resolve<QrApi>({ mount: mountPhoneQr });
    };
    return { calls, load };
}

/** The drawn modules: the path data, which survives the DOM's re-serialisation of the markup. */
const pathOf = (svg: Element) => svg.querySelector("path")!.getAttribute("d");
const expectedPath = (url: string) =>
    / d="([^"]+)"/.exec(phoneQrMarkup(url)!)![1];

const button = () => document.querySelector<HTMLButtonElement>(".cp-qr-btn")!;
const pop = () => document.querySelector<HTMLElement>(".cp-qr-pop")!;

describe("the viewer's On phone QR", () => {
    beforeEach(() => {
        resetDom();
        stubPointer(false);
        history.replaceState(null, "", "/preview/com.example.Card?theme=dark");
    });

    it("is offered to a mouse, not to a touchscreen that already has the page", () => {
        exportBar();
        installPhoneQr(realApi().load);
        assert.ok(button(), "fine pointer gets the button");
        assert.equal(button().getAttribute("aria-haspopup"), "dialog");

        resetDom();
        stubPointer(true);
        exportBar();
        installPhoneQr(realApi().load);
        assert.equal(document.querySelector(".cp-qr-btn"), null);
    });

    it("needs the page to name its bundle", () => {
        exportBar(null);
        installPhoneQr();
        assert.equal(document.querySelector(".cp-qr-btn"), null);
    });

    it("fetches the popover on first click and opens it with the page's QR", async () => {
        exportBar();
        const { calls, load } = realApi();
        installPhoneQr(load);
        assert.equal(calls.loads, 0, "nothing fetched until asked");
        button().click();
        await tick();
        assert.equal(calls.loads, 1);
        assert.equal(pop().hidden, false);
        assert.equal(pop().getAttribute("role"), "dialog");
        assert.equal(
            pop().getAttribute("aria-label"),
            "Open this preview on your phone",
        );
        assert.equal(button().getAttribute("aria-expanded"), "true");
        assert.equal(button().getAttribute("aria-controls"), pop().id);
        assert.equal(document.activeElement, pop());
        const svg = pop().querySelector(".cp-qr-art svg");
        assert.ok(svg, "the code is drawn");
        assert.equal(
            pathOf(svg),
            expectedPath(
                "https://preview.example/preview/com.example.Card?theme=dark",
            ),
        );
        assert.equal(
            pop().querySelector(".cp-qr-url")!.textContent,
            "https://preview.example/preview/com.example.Card?theme=dark",
        );
        assert.equal(
            pop().querySelector<HTMLElement>(".cp-qr-warn")!.hidden,
            true,
            "a real host needs no warning",
        );

        // Later clicks toggle the mounted popover; the bundle is never fetched again.
        button().click();
        assert.equal(pop().hidden, true);
        button().click();
        assert.equal(pop().hidden, false);
        assert.equal(calls.loads, 1);
    });

    it("leaves the operator's token out of the code", async () => {
        history.replaceState(null, "", "/preview/x?token=s3cret&theme=dark");
        exportBar();
        installPhoneQr(realApi().load);
        button().click();
        await tick();
        const url = pop().querySelector(".cp-qr-url")!.textContent!;
        assert.equal(url, "https://preview.example/preview/x?theme=dark");
        assert.equal(
            pathOf(pop().querySelector(".cp-qr-art svg")!),
            expectedPath(url),
        );
    });

    it("redraws for a URL that changed while it was closed", async () => {
        exportBar();
        installPhoneQr(realApi().load);
        button().click();
        await tick();
        const before = pop().querySelector(".cp-qr-art")!.innerHTML;
        button().click();
        history.replaceState(null, "", "/preview/com.example.Card?theme=light");
        button().click();
        assert.notEqual(pop().querySelector(".cp-qr-art")!.innerHTML, before);
        assert.match(pop().querySelector(".cp-qr-url")!.textContent!, /light/);
    });

    it("closes on Esc, returning focus to the button", async () => {
        exportBar();
        installPhoneQr(realApi().load);
        button().click();
        await tick();
        document.dispatchEvent(
            new KeyboardEvent("keydown", { key: "Escape", bubbles: true }),
        );
        assert.equal(pop().hidden, true);
        assert.equal(button().getAttribute("aria-expanded"), "false");
        assert.equal(document.activeElement, button());
    });

    it("closes on a click outside, but not on one inside", async () => {
        exportBar();
        installPhoneQr(realApi().load);
        button().click();
        await tick();
        pop().dispatchEvent(new Event("pointerdown", { bubbles: true }));
        assert.equal(pop().hidden, false);
        document
            .getElementById("elsewhere")!
            .dispatchEvent(new Event("pointerdown", { bubbles: true }));
        assert.equal(pop().hidden, true);
    });

    it("tries again on the next click after a failed fetch", async () => {
        exportBar();
        let attempts = 0;
        installPhoneQr(() => {
            attempts++;
            return attempts === 1
                ? Promise.reject(new Error("offline"))
                : Promise.resolve({ mount: mountPhoneQr });
        });
        button().click();
        await tick();
        assert.equal(document.querySelector(".cp-qr-pop"), null);
        button().click();
        await tick();
        assert.equal(attempts, 2);
        assert.equal(pop().hidden, false);
    });

    it("says so when the URL is too long for any QR version", async () => {
        history.replaceState(null, "", "/preview/x?q=" + "a".repeat(3000));
        exportBar();
        installPhoneQr(realApi().load);
        button().click();
        await tick();
        assert.equal(pop().querySelector("svg"), null);
        assert.match(
            pop().querySelector(".cp-qr-art")!.textContent!,
            /too long/,
        );
    });
});

describe("isLoopbackHost", () => {
    it("names the addresses a phone cannot reach", () => {
        for (const host of [
            "localhost",
            "app.localhost",
            "127.0.0.1",
            "127.1.2.3",
            "[::1]",
            "0.0.0.0",
        ])
            assert.equal(isLoopbackHost(host), true, host);
        for (const host of ["preview.coo.ee", "192.168.1.20", "10.0.0.5"])
            assert.equal(isLoopbackHost(host), false, host);
    });
});
