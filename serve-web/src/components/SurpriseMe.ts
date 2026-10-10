// `<cp-surprise-me>` — the catalog's 🎲 "Surprise me" row in the `⋯` menu: opens a random preview
// from the cards currently showing.
//
// "Showing" is the grid as the visitor sees it, not as the server emitted it: the filter field
// and the section tabs hide cards with `[hidden]` (on the card itself, or on the section or
// sub-group around it), and a pick from behind a filter would be a preview the visitor had just
// said they did not want. So the candidates are read at click time, not cached at mount.
//
// The previous pick is held in `sessionStorage` so the die never lands on the same card twice in
// a row — including across the round trip, since the click navigates away and Back brings the
// visitor to a freshly loaded landing. Per tab, like the theme choice, and best-effort: blocked
// storage only loses the no-repeat rule.
//
// The navigation is the card's own `click()`, not `location.assign`: the card is the link the
// visitor would have pressed, and going through it keeps whatever the page hangs off a card click
// (the live-session lane's click suppression, analytics) in the path. Inert without JS, like
// `<cp-bg-toggle>`, so this renders the button rather than adopting a server-emitted one.

import { h, type VNode } from "../vue.js";
import { customElement } from "../controllerElement.js";
import { VueElement } from "../vueElement.js";
import { pickSurprise } from "../surprisePick.js";

/** Per-tab memory of the last card the die opened, by href. */
export const SURPRISE_LAST_KEY = "cp-surprise-last";

/** The landing grid's preview cards; the same selector the filter script walks. */
const CARDS = "#cp-grid a.cp-card[href]";

/** The cards the visitor can currently see: not hidden, and not inside anything hidden. */
export function visibleCards(root: ParentNode = document): HTMLAnchorElement[] {
    return Array.from(root.querySelectorAll<HTMLAnchorElement>(CARDS)).filter(
        // `closest` starts at the card itself, so one test covers the card, its sub-group and its
        // section — every level the filter script and the tabs hide.
        (card) => !card.closest("[hidden]"),
    );
}

function remembered(): string | null {
    try {
        return sessionStorage.getItem(SURPRISE_LAST_KEY);
    } catch {
        return null;
    }
}

function remember(href: string): void {
    try {
        sessionStorage.setItem(SURPRISE_LAST_KEY, href);
    } catch {
        // Blocked storage: the pick still works, it just may repeat.
    }
}

/**
 * Opens a random visible preview, or does nothing when the filter left none showing. Exposed for
 * the keyboard layer's `R`, which reaches it by clicking this element's button rather than by
 * importing it — the two live in different bundles.
 */
export function surprise(
    rng: () => number = Math.random,
): HTMLAnchorElement | null {
    const card = pickSurprise(visibleCards(), (c) => c.href, remembered(), rng);
    if (!card) return null;
    remember(card.href);
    card.click();
    return card;
}

@customElement("cp-surprise-me")
export class SurpriseMe extends VueElement {
    protected renderVue(): VNode {
        return h(
            "button",
            {
                type: "button",
                // `cp-bg-btn` for the menu row's chip styling (`.cp-actions-panel .cp-bg-btn`), and
                // so the toolbar closes the `⋯` panel behind the pick the way it does for
                // Transparent.
                class: "cp-bg-btn cp-surprise-btn",
                title: "Open a random preview from the ones showing",
                onClick: () => surprise(),
            },
            [h("span", { "aria-hidden": "true" }, "🎲"), " Surprise me"],
        );
    }
}

declare global {
    interface HTMLElementTagNameMap {
        "cp-surprise-me": SurpriseMe;
    }
}
