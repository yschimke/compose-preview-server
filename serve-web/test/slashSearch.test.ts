// `/` focuses the page's filter box, and Escape gives the page back.
//
// What is worth pinning is everything that makes `/` NOT fire: a visitor typing a path into a
// field, a shortcut with a modifier, a modal on top, a key someone else already claimed. Each of
// those, got wrong, eats a character the visitor meant to type — which is far worse than the
// shortcut not existing.

import "./setup.js";
import assert from "node:assert/strict";
import { resetDom } from "./setup.js";
import { installSlashSearch } from "../src/chrome/slashSearch.js";

function press(
    key: string,
    target: EventTarget = document.body,
    init: KeyboardEventInit = {},
): KeyboardEvent {
    const event = new KeyboardEvent("keydown", {
        key,
        bubbles: true,
        cancelable: true,
        ...init,
    });
    target.dispatchEvent(event);
    return event;
}

function box(id: string): HTMLInputElement {
    return document.getElementById(id) as HTMLInputElement;
}

/** The front door's collapsed header search, wired the way `ServeWeb.homeSearchScript` wires it. */
function frontDoor(): void {
    document.body.innerHTML = `
      <div class="cp-site-search">
        <button type="button" id="cp-site-search-toggle" aria-expanded="false"
          aria-controls="cp-site-search-field">⌕</button>
        <div id="cp-site-search-field" hidden>
          <input id="cp-browser-catalog-search" type="search">
        </div>
      </div>`;
    const q = box("cp-browser-catalog-search");
    const t = document.getElementById("cp-site-search-toggle")!;
    const f = document.getElementById("cp-site-search-field")!;
    function expand(open: boolean): void {
        t.setAttribute("aria-expanded", open ? "true" : "false");
        f.hidden = !open;
        if (open) q.focus();
        else q.value = "";
    }
    t.addEventListener("click", () => expand(f.hidden));
    q.addEventListener("keydown", (ev) => {
        if (ev.key === "Escape") {
            expand(false);
            t.focus();
        }
    });
}

describe("slash focuses the page filter", () => {
    let uninstall: () => void = () => {};
    afterEach(() => {
        uninstall();
        (document.activeElement as HTMLElement | null)?.blur?.();
        resetDom();
    });

    it("focuses and selects the catalog filter", () => {
        document.body.innerHTML = `<input id="cp-search" type="search" value="but">`;
        uninstall = installSlashSearch();
        const event = press("/");
        assert.equal(document.activeElement, box("cp-search"));
        assert.ok(
            event.defaultPrevented,
            "the slash is not typed into the box",
        );
        assert.equal(box("cp-search").getAttribute("aria-keyshortcuts"), "/");
    });

    for (const id of ["cp-compare-search", "cp-design-filter"]) {
        it(`finds #${id}`, () => {
            document.body.innerHTML = `<input id="${id}" type="search">`;
            uninstall = installSlashSearch();
            press("/");
            assert.equal(document.activeElement, box(id));
        });
    }

    it("expands the front door's header search through its own toggle", () => {
        frontDoor();
        uninstall = installSlashSearch();
        press("/");
        assert.equal(
            document
                .getElementById("cp-site-search-toggle")!
                .getAttribute("aria-expanded"),
            "true",
        );
        assert.equal(
            document.getElementById("cp-site-search-field")!.hidden,
            false,
        );
        assert.equal(document.activeElement, box("cp-browser-catalog-search"));
    });

    it("does not collapse an already-open header search", () => {
        frontDoor();
        uninstall = installSlashSearch();
        document.getElementById("cp-site-search-toggle")!.click();
        box("cp-browser-catalog-search").blur();
        press("/");
        assert.equal(
            document.getElementById("cp-site-search-field")!.hidden,
            false,
        );
        assert.equal(document.activeElement, box("cp-browser-catalog-search"));
    });

    it("opens a closed viewer drawer before focusing its filter", () => {
        document.body.innerHTML = `
          <button id="cp-nav-toggle" aria-expanded="false"></button>
          <aside id="cp-nav"><input id="cp-nav-search" type="search"></aside>`;
        const toggle = document.getElementById("cp-nav-toggle")!;
        let clicks = 0;
        toggle.addEventListener("click", () => {
            clicks++;
            toggle.setAttribute("aria-expanded", "true");
        });
        uninstall = installSlashSearch();
        press("/");
        press("Escape", box("cp-nav-search"));
        press("/");
        assert.equal(clicks, 1, "an open drawer is not toggled shut");
        assert.equal(document.activeElement, box("cp-nav-search"));
    });

    it("stands aside while the visitor is typing", () => {
        document.body.innerHTML = `
          <input id="cp-search" type="search">
          <textarea id="notes"></textarea>
          <div id="rich" contenteditable="true"></div>`;
        uninstall = installSlashSearch();
        for (const id of ["notes", "rich"]) {
            const field = document.getElementById(id)!;
            const event = press("/", field);
            assert.equal(event.defaultPrevented, false, id);
        }
        assert.notEqual(document.activeElement, box("cp-search"));
    });

    it("ignores a modified slash, a claimed key, and a page behind a modal", () => {
        document.body.innerHTML = `<input id="cp-search" type="search">`;
        uninstall = installSlashSearch();
        press("/", document.body, { ctrlKey: true });
        press("/", document.body, { metaKey: true });
        press("/", document.body, { altKey: true });
        // The live canvas forwards `/` to the device and claims it at the target.
        const canvas = document.createElement("canvas");
        document.body.appendChild(canvas);
        canvas.addEventListener("keydown", (e) => e.preventDefault());
        press("/", canvas);
        assert.notEqual(document.activeElement, box("cp-search"));

        const modal = document.createElement("section");
        modal.setAttribute("aria-modal", "true");
        document.body.appendChild(modal);
        press("/");
        assert.notEqual(document.activeElement, box("cp-search"));
    });

    it("does nothing on a page with no filter", () => {
        document.body.innerHTML = `<main></main>`;
        uninstall = installSlashSearch();
        assert.equal(press("/").defaultPrevented, false);
    });
});

describe("Escape in a page filter", () => {
    let uninstall: () => void = () => {};
    afterEach(() => {
        uninstall();
        resetDom();
    });

    it("clears, re-runs the page's filter once, and blurs", () => {
        document.body.innerHTML = `<input id="cp-search" type="search">`;
        uninstall = installSlashSearch();
        const input = box("cp-search");
        let inputs = 0;
        input.addEventListener("input", () => inputs++);
        input.focus();
        input.value = "slider";
        const event = press("Escape", input);
        assert.equal(input.value, "");
        assert.equal(inputs, 1);
        assert.ok(event.defaultPrevented, "the native clear does not run too");
        assert.notEqual(document.activeElement, input);
    });

    it("blurs an empty box without announcing a change", () => {
        document.body.innerHTML = `<input id="cp-design-filter" type="search">`;
        uninstall = installSlashSearch();
        const input = box("cp-design-filter");
        let inputs = 0;
        input.addEventListener("input", () => inputs++);
        input.focus();
        press("Escape", input);
        assert.equal(inputs, 0);
        assert.notEqual(document.activeElement, input);
    });

    it("leaves the front door's own Escape alone", () => {
        frontDoor();
        uninstall = installSlashSearch();
        press("/");
        const input = box("cp-browser-catalog-search");
        input.value = "slider";
        const event = press("Escape", input);
        // The page's handler collapsed the field and moved focus to the toggle; this module did
        // not then blur the toggle or cancel the event.
        assert.equal(
            document.activeElement,
            document.getElementById("cp-site-search-toggle"),
        );
        assert.equal(event.defaultPrevented, false);
    });

    it("ignores Escape in an input it does not own", () => {
        document.body.innerHTML = `<input id="cp-search"><input id="other" value="x">`;
        uninstall = installSlashSearch();
        const other = box("other");
        other.focus();
        press("Escape", other);
        assert.equal(other.value, "x");
        assert.equal(document.activeElement, other);
    });
});
