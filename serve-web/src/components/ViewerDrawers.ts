// `<cp-viewer-drawers>` — the viewer's two drawers, phone reflow, theme toggle value and component
// filter. A page-level controller that wires server-rendered markup and renders nothing; the
// decisions are pure functions in `viewer/drawerState.ts` and `viewer/navFilter.ts`.

import { ControllerElement, customElement } from "../controllerElement.js";
import {
    drawerToClose,
    foldKey,
    resolveControlsOpen,
    resolveNavOpen,
    shouldPersistDrawer,
    toggleIdFor,
    type DrawerClass,
    type Viewport,
} from "../viewer/drawerState.js";
import {
    filterNav,
    keepNavRows,
    type NavListRow,
    type NavRow,
} from "../viewer/navFilter.js";

const PHONE_QUERY = "(max-width: 640px)";
const WIDE_QUERY = "(min-width: 1100px)";

function matches(query: string): boolean {
    return !!(window.matchMedia && window.matchMedia(query).matches);
}

/** Where a reflowed row came from, captured before anything moves. */
interface RowHome {
    el: HTMLElement;
    parent: Node;
    next: Node | null;
}

@customElement("cp-viewer-drawers")
export class ViewerDrawers extends ControllerElement {
    private viewer: HTMLElement | null = null;
    private scrim: HTMLElement | null = null;
    private foldScope = "default";
    private rowHomes: RowHome[] = [];
    private cleanups: Array<() => void> = [];
    private themeObserver?: MutationObserver;

    connectedCallback(): void {
        super.connectedCallback();
        this.viewer = document.querySelector<HTMLElement>(".cp-viewer");
        if (!this.viewer) return;
        this.scrim = document.getElementById("cp-scrim");
        this.foldScope =
            this.viewer.getAttribute("data-fold-scope") || "default";

        this.captureRowHomes();
        this.restoreDrawers();
        this.reflowRows();
        this.bindToggles();
        this.bindBreakpoints();
        this.bindThemeValue();
        this.bindNavSearch();
        this.syncScrim();
    }

    disconnectedCallback(): void {
        for (const off of this.cleanups) off();
        this.cleanups = [];
        this.themeObserver?.disconnect();
        this.themeObserver = undefined;
        this.viewer = null;
        super.disconnectedCallback();
    }

    private viewport(): Viewport {
        return { mobile: matches(PHONE_QUERY), wide: matches(WIDE_QUERY) };
    }

    // Storage is best-effort: blocked storage yields defaults, never a broken viewer.

    private readFold(id: string): string | null {
        try {
            return localStorage.getItem(foldKey(this.foldScope, id));
        } catch {
            return null;
        }
    }

    private writeFold(id: string, open: boolean): void {
        try {
            localStorage.setItem(foldKey(this.foldScope, id), open ? "1" : "0");
        } catch {
            // Private mode, a full quota: the drawer still opens, it just is not remembered.
        }
    }

    // ── drawer state ─────────────────────────────────────────────────────────────────────────

    private setOpen(drawer: DrawerClass, open: boolean): void {
        const viewer = this.viewer;
        if (!viewer) return;
        const other = open ? drawerToClose(this.viewport(), drawer) : null;
        if (other && viewer.classList.contains(other)) {
            viewer.classList.remove(other);
            if (other === "cp-nav-open") viewer.classList.add("cp-nav-closed");
            document
                .getElementById(toggleIdFor(other))
                ?.setAttribute("aria-expanded", "false");
        }
        // Close the comparisons sheet too: on a phone it is fixed above the drawer and its scrim,
        // and its own summary is behind the scrim, so it could not be dismissed.
        if (open) this.closeComparisonMenus();
        viewer.classList.toggle(drawer, open);
        // Closed nav must be explicit: above 1100px the absence of `cp-nav-open` means open.
        if (drawer === "cp-nav-open") {
            viewer.classList.toggle("cp-nav-closed", !open);
        }
        document
            .getElementById(toggleIdFor(drawer))
            ?.setAttribute("aria-expanded", open ? "true" : "false");
        this.syncScrim();
    }

    /**
     * Close open comparison disclosures in `.cp-preview-primary` only — the row whose panel becomes
     * a fixed sheet on phones.
     */
    private closeComparisonMenus(): void {
        document
            .querySelectorAll<HTMLDetailsElement>(
                ".cp-preview-primary .cp-detail-menu[open]",
            )
            .forEach((menu) => {
                menu.open = false;
            });
    }

    /**
     * On a phone the drawers are bottom sheets over the preview, so a scrim goes behind the open
     * one.
     */
    private syncScrim(): void {
        const viewer = this.viewer;
        if (!this.scrim || !viewer) return;
        const anyOpen =
            viewer.classList.contains("cp-controls-open") ||
            viewer.classList.contains("cp-nav-open");
        this.scrim.classList.toggle("cp-scrim-on", anyOpen);
    }

    private restoreDrawers(): void {
        const viewer = this.viewer;
        if (!viewer) return;
        const viewport = this.viewport();
        // Read the server default before touching the class; it is the third input to the rule.
        const serverDefault = viewer.classList.contains("cp-controls-open");
        this.setOpen(
            "cp-controls-open",
            resolveControlsOpen(
                viewport,
                this.readFold("cp-controls-toggle"),
                serverDefault,
            ),
        );
        if (document.getElementById("cp-nav-toggle")) {
            this.setOpen(
                "cp-nav-open",
                resolveNavOpen(viewport, this.readFold("cp-nav-toggle")),
            );
        }
    }

    private bindToggles(): void {
        for (const drawer of [
            "cp-controls-open",
            "cp-nav-open",
        ] as DrawerClass[]) {
            const id = toggleIdFor(drawer);
            const btn = document.getElementById(id);
            if (!btn) continue;
            this.on(btn, "click", () => {
                const open = !this.viewer?.classList.contains(drawer);
                this.setOpen(drawer, open);
                if (shouldPersistDrawer(this.viewport())) {
                    this.writeFold(id, open);
                }
            });
        }

        const close = document.getElementById("cp-nav-close");
        if (close) {
            this.on(close, "click", () => {
                this.setOpen("cp-nav-open", false);
                // Same rule as the toggle: dismissing a phone's sheet is not a statement about the
                // desktop column, so it stores nothing there.
                if (shouldPersistDrawer(this.viewport())) {
                    this.writeFold("cp-nav-toggle", false);
                }
            });
        }

        if (this.scrim) {
            this.on(this.scrim, "click", () => {
                this.setOpen("cp-controls-open", false);
                this.setOpen("cp-nav-open", false);
            });
        }
    }

    /**
     * Re-resolve on breakpoint crossings, so a page narrowed to phone width does not keep
     * `cp-nav-open` as a fixed sheet with a scrim.
     */
    private bindBreakpoints(): void {
        for (const query of [PHONE_QUERY, WIDE_QUERY]) {
            const list = window.matchMedia?.(query);
            if (!list?.addEventListener) continue;
            const handler = () => {
                if (document.getElementById("cp-nav-toggle")) {
                    this.setOpen(
                        "cp-nav-open",
                        resolveNavOpen(
                            this.viewport(),
                            this.readFold("cp-nav-toggle"),
                        ),
                    );
                }
                // Arriving at the phone layout (e.g. rotating) can also leave the sheet over an
                // open drawer, and `setOpen` doesn't see it.
                if (this.viewport().mobile) this.closeComparisonMenus();
                this.reflowRows();
            };
            list.addEventListener("change", handler);
            this.cleanups.push(() =>
                list.removeEventListener("change", handler),
            );
        }
    }

    // On a phone the order is bar, title, preview, so the two control rows move below the stage.
    // Moved in the DOM rather than with `order` so tab order matches visual order.

    private captureRowHomes(): void {
        const rows = [
            document.querySelector<HTMLElement>(".cp-preview-primary"),
            document.querySelector<HTMLElement>(".cp-head-toggles"),
        ].filter((el): el is HTMLElement => !!el);
        // `nextSibling` rather than an index: the anchors are stable nodes that never move
        // themselves, so restoring is exact even though the two rows leave different parents.
        this.rowHomes = rows.map((el) => ({
            el,
            parent: el.parentNode as Node,
            next: el.nextSibling,
        }));
    }

    private reflowRows(): void {
        const viewer = this.viewer;
        if (!viewer?.parentNode) return;
        if (matches(PHONE_QUERY)) {
            let after: Node = viewer;
            for (const { el } of this.rowHomes) {
                moveIfNeeded(viewer.parentNode, el, after.nextSibling);
                after = el;
            }
        } else {
            for (const { el, parent, next } of this.rowHomes) {
                moveIfNeeded(parent, el, next);
            }
        }
    }

    // ── the theme toggle's value ─────────────────────────────────────────────────────────────

    /**
     * Mirror whichever theme chip `viewer.js` has pressed into the toggle's value, since the theme
     * changes without a page load. Observing `aria-pressed` avoids coupling to `syncThemeBar`.
     */
    private bindThemeValue(): void {
        const bar = document.getElementById("cp-theme-bar");
        const value = document.getElementById("cp-theme-toggle-value");
        if (!bar || !value) return;
        const sync = () => {
            const on = bar.querySelector('.cp-theme-btn[aria-pressed="true"]');
            const name = (on?.textContent || "").trim();
            if (name) value.textContent = name;
        };
        sync();
        if (window.MutationObserver) {
            this.themeObserver = new MutationObserver(sync);
            this.themeObserver.observe(bar, {
                subtree: true,
                attributes: true,
                attributeFilter: ["aria-pressed"],
            });
        }
        this.on(bar, "click", (event) => {
            if (!(event.target as Element)?.closest?.(".cp-theme-btn")) return;
            const menu = bar.closest<HTMLDetailsElement>(".cp-theme-menu");
            if (menu) menu.open = false;
        });
    }

    // ── the component filter ─────────────────────────────────────────────────────────────────

    private bindNavSearch(): void {
        const search = document.getElementById(
            "cp-nav-search",
        ) as HTMLInputElement | null;
        if (!search) return;
        this.on(search, "input", () => {
            // Every child, not only links: section/group headings must go with the rows they head.
            const lines = [
                ...document.querySelectorAll<HTMLElement>("#cp-nav-list > li"),
            ];
            const links = lines.map((li) =>
                li.querySelector<HTMLElement>(".cp-nav-item"),
            );
            const rows: NavRow[] = links
                .filter((el): el is HTMLElement => !!el)
                .map((el) => ({
                    haystack: el.getAttribute("data-search") || "",
                    current: el.hasAttribute("aria-current"),
                }));
            const { keep, empty } = filterNav(
                rows,
                search.value,
                !!document.querySelector(".cp-nav-current"),
            );
            let next = 0;
            const listRows: NavListRow[] = lines.map((li, i) => {
                if (links[i]) return { kind: "item", kept: keep[next++] };
                return li.classList.contains("cp-nav-section-row")
                    ? { kind: "section" }
                    : { kind: "group" };
            });
            keepNavRows(listRows).forEach((visible, i) => {
                lines[i].hidden = !visible;
            });
            const none = document.getElementById("cp-nav-empty");
            if (none) none.hidden = !empty;
        });
    }

    /** addEventListener with teardown recorded, so the element leaves nothing behind. */
    private on(
        target: EventTarget,
        type: string,
        handler: (event: Event) => void,
    ): void {
        target.addEventListener(type, handler);
        this.cleanups.push(() => target.removeEventListener(type, handler));
    }
}

/**
 * Move `el` before `before` under `parent` only if it is not already there: `insertBefore` into the
 * same position detaches and re-attaches, which can drop a search input's clear button and focus.
 */
function moveIfNeeded(
    parent: Node,
    el: HTMLElement,
    before: Node | null,
): void {
    if (el.parentNode === parent && el.nextSibling === before) return;
    parent.insertBefore(el, before);
}

declare global {
    interface HTMLElementTagNameMap {
        "cp-viewer-drawers": ViewerDrawers;
    }
}
