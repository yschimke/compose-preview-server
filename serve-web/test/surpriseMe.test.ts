// The catalog's 🎲 Surprise me: the pure pick (list + rng) and the element that reads the visible
// grid, remembers its last pick per tab, and opens the card through the card's own click.

import "./setup.js";
import assert from "node:assert/strict";
import { flush, resetDom } from "./setup.js";
import { pickSurprise } from "../src/surprisePick.js";
import {
    SURPRISE_LAST_KEY,
    surprise,
    surpriseNavigation,
    visibleCards,
} from "../src/components/SurpriseMe.js";

const id = (s: string) => s;

describe("pickSurprise", () => {
    it("is null for an empty list", () => {
        assert.equal(
            pickSurprise([], id, null, () => 0.5),
            null,
        );
    });

    it("maps the rng uniformly over the list", () => {
        const items = ["a", "b", "c", "d"];
        assert.equal(
            pickSurprise(items, id, null, () => 0),
            "a",
        );
        assert.equal(
            pickSurprise(items, id, null, () => 0.49),
            "b",
        );
        assert.equal(
            pickSurprise(items, id, null, () => 0.999),
            "d",
        );
    });

    it("never repeats the previous pick while anything else is left", () => {
        const items = ["a", "b", "c"];
        // Every rng value lands somewhere other than `b`.
        for (const r of [0, 0.34, 0.5, 0.67, 0.99])
            assert.notEqual(
                pickSurprise(items, id, "b", () => r),
                "b",
            );
        assert.equal(
            pickSurprise(items, id, "a", () => 0),
            "b",
        );
    });

    it("still answers with the only candidate, even if it was the last pick", () => {
        assert.equal(
            pickSurprise(["a"], id, "a", () => 0.7),
            "a",
        );
    });

    it("clamps an rng that returns 1", () => {
        assert.equal(
            pickSurprise(["a", "b"], id, null, () => 1),
            "b",
        );
    });
});

describe("<cp-surprise-me>", () => {
    let opened: string[];
    let assigned: string[];
    const realAssign = surpriseNavigation.assign;
    const startHref = location.href;

    beforeEach(() => {
        resetDom();
        // Explicitly: an earlier suite may have left a storage stub in place, which resetDom
        // deliberately does not clear.
        sessionStorage.removeItem(SURPRISE_LAST_KEY);
        opened = [];
        assigned = [];
        surpriseNavigation.assign = (href) => void assigned.push(href);
        document.body.innerHTML = `
          <cp-surprise-me></cp-surprise-me>
          <div id="cp-grid">
            <section class="cp-section" data-section="a">
              <a class="cp-card" href="/p/one">One</a>
              <a class="cp-card" href="/p/two" hidden>Two</a>
            </section>
            <section class="cp-section" data-section="b" hidden>
              <a class="cp-card" href="/p/three">Three</a>
            </section>
            <div class="cp-subgroup">
              <a class="cp-card" href="/p/four">Four</a>
            </div>
          </div>
          <a class="cp-card" href="/p/outside">Not in the grid</a>`;
        // Record navigations instead of following them.
        document.addEventListener("click", record, true);
    });

    afterEach(() => {
        document.removeEventListener("click", record, true);
        surpriseNavigation.assign = realAssign;
        history.replaceState(null, "", startHref);
    });

    // Records the card click without cancelling it, since a cancelled click is now what sends
    // the die down its fallback path. happy-dom follows the link by moving `location`, which
    // `afterEach` puts back.
    function record(event: Event): void {
        const card = (event.target as Element).closest?.("a.cp-card");
        if (!card) return;
        opened.push(card.getAttribute("href")!);
    }

    it("considers only cards the filter and tabs leave showing", () => {
        assert.deepEqual(
            visibleCards().map((c) => c.getAttribute("href")),
            ["/p/one", "/p/four"],
        );
    });

    it("renders a menu row that opens a visible card and remembers it", async () => {
        await flush();
        const button = document.querySelector<HTMLButtonElement>(
            "cp-surprise-me button.cp-surprise-btn",
        )!;
        assert.match(button.textContent!, /Surprise me/);
        button.click();
        assert.equal(opened.length, 1);
        assert.ok(["/p/one", "/p/four"].includes(opened[0]!));
        assert.match(
            sessionStorage.getItem(SURPRISE_LAST_KEY)!,
            /\/p\/(one|four)$/,
        );
    });

    it("alternates rather than repeating, across clicks", () => {
        // A constant rng would pick the same card every time without the no-repeat rule.
        for (let i = 0; i < 4; i++) surprise(() => 0);
        assert.deepEqual(opened, ["/p/one", "/p/four", "/p/one", "/p/four"]);
    });

    it("does nothing when the filter left no card showing", () => {
        document
            .querySelectorAll<HTMLElement>("#cp-grid .cp-card")
            .forEach((c) => (c.hidden = true));
        assert.equal(
            surprise(() => 0),
            null,
        );
        assert.deepEqual(opened, []);
    });

    it("still navigates when a handler swallows the card's click (a live card)", () => {
        // `<cp-catalog-live>` cancels every click on the card it is streaming; the keyboard's R
        // sends no pointerdown to end that session first.
        const live = document.querySelector<HTMLAnchorElement>(
            'a.cp-card[href="/p/one"]',
        )!;
        live.addEventListener("click", (event) => {
            event.preventDefault();
            event.stopPropagation();
        });
        assert.equal(
            surprise(() => 0),
            live,
        );
        assert.deepEqual(assigned, [live.href]);
    });

    it("leaves an uncancelled click to the link itself", () => {
        surprise(() => 0);
        assert.deepEqual(assigned, []);
    });
});
