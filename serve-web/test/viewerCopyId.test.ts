// The viewer's preview id copies itself: a keyboard-reachable button that confirms on the chip and
// in a live region, and falls back to execCommand where the Clipboard API is missing.

import { resetDom } from "./setup.js";
import assert from "node:assert/strict";
import { copyText, wireCopyId } from "../src/viewer/copyId.js";

const flush = () => new Promise((r) => setTimeout(r, 0));

function chip(id: string): HTMLElement {
    document.body.innerHTML = `<div class="cp-preview-head"><code class="cp-preview-id" title="${id}">${id}</code></div>`;
    return document.querySelector<HTMLElement>(".cp-preview-id")!;
}

describe("the viewer's copyable preview id", () => {
    beforeEach(resetDom);

    it("is a named, focusable button with a live region beside it", () => {
        const el = chip("com.example.ButtonPreview");
        wireCopyId(el, () => Promise.resolve(true));
        assert.equal(el.getAttribute("role"), "button");
        assert.equal(el.tabIndex, 0);
        assert.equal(
            el.getAttribute("aria-label"),
            "Copy preview id com.example.ButtonPreview",
        );
        const live = el.nextElementSibling!;
        assert.equal(live.getAttribute("aria-live"), "polite");
        assert.equal(live.textContent, "");
    });

    it("copies the full id on click and confirms in both channels", async () => {
        const el = chip("com.example.ButtonPreview");
        const copied: string[] = [];
        wireCopyId(el, (text) => {
            copied.push(text);
            return Promise.resolve(true);
        });
        el.click();
        await flush();
        assert.deepEqual(copied, ["com.example.ButtonPreview"]);
        assert.equal(el.textContent, "Copied ✓");
        assert.equal(el.nextElementSibling!.textContent, "Preview id copied");
        // The confirmation never becomes the id: a second click mid-flash still copies the id.
        el.click();
        await flush();
        assert.deepEqual(copied, [
            "com.example.ButtonPreview",
            "com.example.ButtonPreview",
        ]);
    });

    it("answers Enter and Space like a button, and nothing else", async () => {
        const el = chip("p1");
        let n = 0;
        wireCopyId(el, () => {
            n++;
            return Promise.resolve(true);
        });
        for (const key of ["Enter", " ", "a"]) {
            const e = new KeyboardEvent("keydown", { key, cancelable: true });
            el.dispatchEvent(e);
            assert.equal(e.defaultPrevented, key !== "a", key);
        }
        await flush();
        assert.equal(n, 2);
    });

    it("says so when the copy fails", async () => {
        const el = chip("p1");
        wireCopyId(el, () => Promise.resolve(false));
        el.click();
        await flush();
        assert.equal(el.textContent, "Copy failed");
        assert.equal(el.nextElementSibling!.textContent, "Copy failed");
    });

    it("prefers the Clipboard API and falls back to execCommand", async () => {
        const el = chip("p1");
        const written: string[] = [];
        const nav = {
            clipboard: {
                writeText: (t: string) => {
                    written.push(t);
                    return Promise.resolve();
                },
            },
        } as unknown as Navigator;
        assert.equal(await copyText("p1", nav), true);
        assert.deepEqual(written, ["p1"]);

        // The fallback copies the id even while the chip is showing its confirmation.
        el.textContent = "Copied ✓";
        const commands: string[] = [];
        const original = document.execCommand;
        document.execCommand = (c: string) => {
            commands.push(`${c}:${document.getSelection()?.toString()}`);
            return true;
        };
        try {
            assert.equal(await copyText("p1", {} as Navigator), true);
        } finally {
            document.execCommand = original;
        }
        assert.deepEqual(commands, ["copy:p1"]);
    });
});
