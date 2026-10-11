// `<cp-catalog-toolbar>`: the catalog landing's single toolbar row on a phone. CSS can't move the
// filter field (in the sidebar above 960px) onto the row with the Theme and action menus, so this
// moves it in the DOM (keeping reading, paint and tab order in sync, like `<cp-viewer-drawers>`).
// Stateless; on a sectioned catalog (no served toolbar) it builds the row on a phone and removes it
// on the way back. Without the bundle every control stays put and the `<details>` menus still work.

import { ControllerElement, customElement } from "../controllerElement.js";

const PHONE = "(max-width: 640px)";

/** The toolbar's disclosures — the Theme pill and the `⋯` — which open one at a time. */
const MENUS = ".cp-catalog-theme, .cp-actions-menu";

/** Where an element was before it was moved, so it can be put back exactly. */
interface Home {
    el: Element;
    parent: Node;
    next: Node | null;
}

@customElement("cp-catalog-toolbar")
export class CatalogToolbar extends ControllerElement {
    private phone: MediaQueryList | null = null;
    private bar: Element | null = null;
    /** Whether [bar] is one this element built, and so one it has to clear away. */
    private ownBar = false;
    private search: Element | null = null;
    private sub: Element | null = null;
    private toggles: Element | null = null;
    private homes: Home[] = [];
    /** Whether the rows are currently in the toolbar rather than where the server put them. */
    private moved = false;
    private themeObserver: MutationObserver | null = null;
    private readonly onBreakpoint = () => this.reflow();

    /**
     * A menu closes when used: theme picks and Transparent update the grid in place, so nothing
     * else dismisses it. The Theme chips are inside their `<details>`; the actions panel is a
     * sibling `closest()` can't reach, so it is named here. On `document`, since the panels move
     * with the reflow.
     */
    private readonly onPick = (event: Event) => {
        const target = event.target as Element | null;
        if (!target?.closest) return;
        const themeChip = target.closest(
            ".cp-catalog-theme .cp-theme-btn",
        ) as Element | null;
        if (themeChip) this.close(".cp-catalog-theme");
        if (target.closest(".cp-actions-panel .cp-bg-btn"))
            this.close(".cp-actions-menu");
    };

    /**
     * The two toolbar menus behave as one menu bar: opening one closes the other, since their
     * panels overlap at the same `z-index` and one would cover the other's options. Driven by
     * `toggle`, so it holds however a disclosure opens.
     */
    private readonly onDisclosureToggle = (event: Event) => {
        const opened = event.target as HTMLDetailsElement | null;
        if (!opened?.matches?.(MENUS) || !opened.open) return;
        for (const peer of document.querySelectorAll<HTMLDetailsElement>(MENUS))
            if (peer !== opened) peer.open = false;
    };

    connectedCallback(): void {
        super.connectedCallback();
        // The sticky toolbar holding the Theme pill and `⋯` menu (the actions row is nested
        // inside).
        this.bar = document.querySelector(".cp-catalog-tools");
        this.search = document.querySelector(".cp-catalog-menu .cp-searchbar");
        this.sub = document.querySelector(".cp-sub");
        // The pills when the server put them on the identity row (sectioned catalogs, browser
        // mode); on a phone they move into the row beside the filter.
        this.toggles = document.querySelector(
            ".cp-catalog-head-row > .cp-head-toggles",
        );
        // The filter and the toggles are the controls to bring in; the summary line does not join
        // the row at all — see `placeOnPhone`.
        this.homes = [this.search, this.toggles, this.sub]
            .filter((el): el is Element => !!el && el !== this.bar)
            .map((el) => ({
                el,
                parent: el.parentNode as Node,
                next: el.nextSibling,
            }));
        this.phone = window.matchMedia?.(PHONE) ?? null;
        this.phone?.addEventListener?.("change", this.onBreakpoint);
        this.reflow();
        this.watchThemeValue();
        document.addEventListener("click", this.onPick);
        // `toggle` doesn't bubble, so listen in the capture phase, on `document` since panels move.
        document.addEventListener("toggle", this.onDisclosureToggle, true);
    }

    disconnectedCallback(): void {
        this.phone?.removeEventListener?.("change", this.onBreakpoint);
        document.removeEventListener("click", this.onPick);
        document.removeEventListener("toggle", this.onDisclosureToggle, true);
        this.themeObserver?.disconnect();
        this.themeObserver = null;
        super.disconnectedCallback();
    }

    private close(selector: string): void {
        const menu = document.querySelector(selector);
        if (menu instanceof HTMLDetailsElement) menu.open = false;
    }

    private reflow(): void {
        if (!this.homes.length) return;
        const phone = !!this.phone?.matches;
        // Only on a crossing: re-inserting a node in place still detaches it, resetting the search
        // field's clear button and focus ring.
        if (phone === this.moved) return;
        if (phone && !this.openBar()) return;
        this.moved = phone;
        if (phone) for (const { el } of this.homes) this.placeOnPhone(el);
        else {
            for (const { el, parent, next } of this.homes)
                parent.insertBefore(el, next);
            this.closeBar();
        }
    }

    /**
     * The phone row, built if the server emitted none (it only does for flat grids where the filter
     * lives in it), and removed again on the way back up.
     */
    private openBar(): Element | null {
        if (this.bar) return this.bar;
        const head = document.querySelector(".cp-catalog-head-row");
        if (!head?.parentNode) return null;
        const bar = document.createElement("div");
        bar.className = "cp-catalog-tools";
        head.parentNode.insertBefore(bar, head.nextSibling);
        this.bar = bar;
        this.ownBar = true;
        return bar;
    }

    /** …and away, once its contents have gone back to where the server had them. */
    private closeBar(): void {
        if (!this.ownBar) return;
        this.bar?.remove();
        this.bar = null;
        this.ownBar = false;
    }

    /** Where a moved block goes on a phone. */
    private placeOnPhone(el: Element): void {
        if (!this.bar) return;
        // The summary line is a tally, not a control, so on a phone it moves below the grid with
        // the other metadata. Not hidden: it carries the only hint that a card can be held for a
        // live session.
        if (el === this.sub) {
            const download = document.querySelector(".cp-catalog-download");
            if (download?.parentNode)
                download.parentNode.insertBefore(el, download);
            else document.querySelector(".cp-main")?.appendChild(el);
            return;
        }
        // The menus sit at the trailing edge.
        if (el === this.toggles) {
            this.bar.appendChild(el);
            return;
        }
        // The filter takes the width, so it goes before the `.cp-head-toggles` group (or first if
        // none).
        const toggles = this.bar.querySelector(".cp-head-toggles");
        this.bar.insertBefore(el, toggles ?? this.bar.firstChild);
    }

    /**
     * Keep the Theme pill naming the theme in force by mirroring the chips' `aria-pressed`, which
     * the landing script sets on load and on every click.
     */
    private watchThemeValue(): void {
        const bar = document.getElementById("cp-catalog-theme-bar");
        const value = document.getElementById("cp-catalog-theme-value");
        if (!bar || !value) return;
        const sync = () => {
            const pressed = bar.querySelector(
                '.cp-theme-btn[aria-pressed="true"]',
            );
            const name = pressed?.textContent?.trim();
            // Only when it changes.
            if (name && name !== value.textContent) value.textContent = name;
        };
        sync();
        this.themeObserver = new MutationObserver(sync);
        this.themeObserver.observe(bar, {
            subtree: true,
            attributes: true,
            attributeFilter: ["aria-pressed"],
        });
    }
}

declare global {
    interface HTMLElementTagNameMap {
        "cp-catalog-toolbar": CatalogToolbar;
    }
}
