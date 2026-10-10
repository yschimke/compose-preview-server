// `/` focuses the page's filter box — the convention GitHub, MDN and most docs sites share, so a
// visitor who already has it in their fingers does not have to reach for the mouse to narrow a grid.
//
// Lives in the page-shell bundle because every surface that has a filter box (the front door, a
// catalog's landing grid, the comparison wall, the designs list, the viewer's component drawer) is
// a serve page, and the shell is the one script all of them load. It is deliberately not part of
// `keyboardNavigation.ts`: that layer is opt-in, and `/` is the one shortcut a visitor expects to
// work without having turned anything on. The two do not overlap — the palette binds no `/`, and
// while one of its dialogs is open this stands aside (see `modalOpen`).
//
// Escape is the way back out: it clears the box (announcing the change as an `input` event, so the
// page's own filter re-runs exactly as if the visitor had deleted the text) and blurs it, so the
// next `/` or `j` lands on the page again rather than being typed into the filter. A box whose page
// already gives Escape a meaning keeps it — see `ownEscape`.

/**
 * One filter box, and the disclosure that has to be open before it can take focus.
 *
 * Listed in priority order; the first one present on the page wins. In practice no page carries two
 * of these, but the order is the one a visitor would mean if one ever did: the box for the page's
 * main content before the one in a side drawer.
 */
interface Target {
    input: string;
    /** A button with `aria-expanded` whose click reveals the box, when the box can be collapsed. */
    toggle?: string;
    /** The page's own script already handles Escape in this box, so this module leaves it be. */
    ownEscape?: boolean;
}

export const SEARCH_TARGETS: readonly Target[] = [
    { input: "#cp-search" },
    { input: "#cp-compare-search" },
    { input: "#cp-design-filter" },
    // The front door's box sits in a collapsed header disclosure. Clicking its toggle is how a
    // pointer opens it, and that click handler both reveals the field and focuses it — so pressing
    // the button is the whole of "expand it", and nothing here restates what expanding means. Its
    // inline script (`ServeWeb.homeSearchScript`) also owns Escape: collapse, clear, refocus the
    // toggle. A second handler here would blur the toggle it had just focused.
    {
        input: "#cp-browser-catalog-search",
        toggle: "#cp-site-search-toggle",
        ownEscape: true,
    },
    // The viewer's component drawer can be closed (always on a phone, by choice on a desktop), and a
    // focused input inside a closed drawer is an invisible caret. `ViewerDrawers` keeps the toggle's
    // `aria-expanded` truthful, so it is the one place to ask whether the drawer is open.
    { input: "#cp-nav-search", toggle: "#cp-nav-toggle" },
];

/** Whether focus is somewhere the visitor is typing, so `/` is a character rather than a command. */
function editable(target: EventTarget | null): boolean {
    const element = target instanceof Element ? target : null;
    return !!element?.closest(
        "input, textarea, select, [contenteditable]:not([contenteditable='false'])",
    );
}

/**
 * A modal is up — the keyboard palette, its help sheet, a report picker. A `/` there belongs to the
 * modal, and focusing a box behind it would strand the caret under the scrim.
 */
function modalOpen(): boolean {
    return !!document.querySelector("[aria-modal='true'], dialog[open]");
}

function find(): { target: Target; input: HTMLInputElement } | null {
    for (const target of SEARCH_TARGETS) {
        const input = document.querySelector<HTMLInputElement>(target.input);
        if (input && !input.disabled) return { target, input };
    }
    return null;
}

/** Open the box's disclosure, if it has one and it is shut, the way a pointer would. */
function reveal(target: Target): void {
    if (!target.toggle) return;
    const toggle = document.querySelector<HTMLElement>(target.toggle);
    if (toggle && toggle.getAttribute("aria-expanded") !== "true")
        toggle.click();
}

function onKeyDown(event: KeyboardEvent): void {
    // Bubble phase, and anything that already claimed the key keeps it: the live preview's canvas
    // forwards `/` to the device and says so with preventDefault, which is exactly the case where
    // the visitor is typing into the app rather than into this page.
    if (event.defaultPrevented || event.isComposing) return;
    if (event.key === "Escape") {
        escape(event);
        return;
    }
    if (event.key !== "/") return;
    if (event.metaKey || event.ctrlKey || event.altKey) return;
    if (editable(event.target) || modalOpen()) return;
    const found = find();
    if (!found) return;
    // Before revealing, so the `/` is never typed into the box the toggle's own handler focuses.
    event.preventDefault();
    reveal(found.target);
    found.input.focus();
    // Selected rather than appended to: a visitor who presses `/` on a filtered page almost always
    // wants a new query, and typing over a selection still lets them keep the old one with an arrow.
    found.input.select();
}

function escape(event: KeyboardEvent): void {
    const input = event.target;
    if (!(input instanceof HTMLInputElement)) return;
    const target = SEARCH_TARGETS.find((t) => input.matches(t.input));
    if (!target || target.ownEscape) return;
    // Taken from the browser too: a native `type=search` box clears itself on Escape, and doing it
    // here instead means the page's filter hears one `input` event, not a native one and then ours.
    event.preventDefault();
    if (input.value !== "") {
        input.value = "";
        input.dispatchEvent(new Event("input", { bubbles: true }));
    }
    input.blur();
}

/**
 * Bind `/` and Escape for the page's filter box. Returns a function that unbinds them, for tests;
 * the page itself never unbinds.
 */
export function installSlashSearch(): () => void {
    // Said in the markup as well as bound, so a screen reader announces the shortcut on the box.
    // Set here rather than in each page's Kotlin template: this file is what makes it true.
    for (const target of SEARCH_TARGETS)
        document
            .querySelector(target.input)
            ?.setAttribute("aria-keyshortcuts", "/");
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
}
