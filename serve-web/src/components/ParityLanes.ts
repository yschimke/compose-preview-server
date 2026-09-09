// `<cp-parity-lanes>` — the design-parity feed's lane filter. Half of `assets/parity.js`.
//
// Renders nothing. The buttons and the feed are both server-rendered — the page is readable with
// JavaScript off, which is the whole reason this filters in place instead of fetching — so all the
// element does is answer "which entries stay visible" and write that answer onto rows it does not
// own. `serve.css` hides the tag.
//
// The rule itself lives in `parity/laneFilter.ts`, where its one exception (`all` means "no
// filter", not "entries whose lane is `all`") is a test rather than a screenshot of a feed.

import { ControllerElement, customElement } from "../controllerElement.js";
import { whenParsed } from "../dom/whenParsed.js";
import { filterLanes } from "../parity/laneFilter.js";
import { urlState } from "../urlState.js";

@customElement("cp-parity-lanes")
export class ParityLanes extends ControllerElement {
    private cleanups: Array<() => void> = [];

    // The buttons and the feed are both siblings further down the page, so connect time is too
    // early to find either. See `dom/whenParsed.ts`.
    connectedCallback(): void {
        super.connectedCallback();
        void whenParsed().then(() => this.bind());
    }

    private bind(): void {
        if (!this.isConnected) return;
        for (const button of this.buttons()) {
            const onClick = () => {
                const lane = button.dataset.parityLane ?? "all";
                this.apply(lane);
                // A discrete choice, so it earns a history entry: Back returns to the lane the
                // reader came from. `all` is the resting feed the page is served as, so it clears
                // the parameter rather than pinning the default onto a copied link.
                urlState()?.push({ lane: lane === "all" ? null : lane });
            };
            button.addEventListener("click", onClick);
            this.cleanups.push(() =>
                button.removeEventListener("click", onClick),
            );
        }
        // The feed is server-rendered unfiltered, so a shared `?lane=…` link (and every Back into
        // one) has to narrow it here. A lane no button offers falls back to the whole feed rather
        // than hiding every entry behind a filter nothing can lift.
        const restore = () => {
            const wanted = urlState()?.get("lane") || "all";
            const offered = this.buttons().some(
                (button) => button.dataset.parityLane === wanted,
            );
            this.apply(offered ? wanted : "all");
        };
        restore();
        const offPop = urlState()?.onPop(restore);
        if (offPop) this.cleanups.push(offPop);
    }

    disconnectedCallback(): void {
        for (const off of this.cleanups) off();
        this.cleanups = [];
        super.disconnectedCallback();
    }

    private buttons(): HTMLElement[] {
        return Array.from(
            document.querySelectorAll<HTMLElement>("[data-parity-lane]"),
        );
    }

    private apply(lane: string): void {
        const feed = document.getElementById("cp-parity-feed");
        if (!feed) return;
        const entries = Array.from(
            feed.querySelectorAll<HTMLElement>(".cp-parity-entry"),
        );
        const { keep, empty } = filterLanes(
            entries.map((entry) => entry.dataset.lane ?? ""),
            lane,
        );
        entries.forEach((entry, i) => {
            entry.hidden = !keep[i];
        });
        const emptyNote = document.getElementById("cp-parity-feed-empty");
        if (emptyNote) emptyNote.hidden = !empty;
        // `aria-current="page"` rather than a pressed state, matching the server's own resting
        // markup for the `all` button — the filter reads as navigation within the feed.
        for (const button of this.buttons()) {
            if (button.dataset.parityLane === lane)
                button.setAttribute("aria-current", "page");
            else button.removeAttribute("aria-current");
        }
    }
}

declare global {
    interface HTMLElementTagNameMap {
        "cp-parity-lanes": ParityLanes;
    }
}
