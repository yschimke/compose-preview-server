// `<cp-revision-runs>` — thumbnails in the viewer's Revision menu marking the head of each run of
// publishes that render identically, so a reader sees which publishes actually differ. Decorates
// the server-rendered rows rather than owning them, so a failed fetch leaves a working menu. Lazy:
// nothing is fetched until the disclosure opens, and the result is kept for the page's life.

import { h, type VNode } from "../vue.js";
import { customElement } from "../controllerElement.js";
import { VueElement } from "../vueElement.js";
import { whenParsed } from "../dom/whenParsed.js";
import {
    renderTemplateOf,
    runsViewOf,
    summaryOf,
    type RenderRunsPayload,
    type RunsView,
} from "../viewer/renderRuns.js";

@customElement("cp-revision-runs")
export class RevisionRuns extends VueElement {
    /** The one-line answer above the list; the thumbnails go on the rows themselves. */
    private summary = "";

    private asked = false;
    private details: HTMLDetailsElement | null = null;

    connectedCallback(): void {
        super.connectedCallback();
        void whenParsed().then(() => this.install());
    }

    /**
     * Answer the disclosure's first opening via `toggle`, which also covers find-in-page, keyboard
     * activation and state restoration.
     */
    private install(): void {
        if (!this.isConnected) return;
        const details = this.closest("details");
        if (!details || this.details === details) return;
        this.details = details;
        if (details.open) void this.load();
        this.listen(details, "toggle", () => {
            if (details.open) void this.load();
        });
    }

    override disconnectedCallback(): void {
        this.details = null;
        super.disconnectedCallback();
    }

    private async load(): Promise<void> {
        if (this.asked) return;
        this.asked = true;
        const template = renderTemplateOf(this.getAttribute("data-render-url"));
        if (!template) return;
        const payload = this.inline() ?? (await this.fetched());
        if (!payload) return;
        // One window check before anything is claimed, guarding both branches — especially the
        // single-run claim ("All N publishes render identically").
        if (!this.describesThisPage(payload)) return;
        const view = runsViewOf(payload, template);
        if (view) {
            this.decorate(view);
            this.summary = view.summary;
            this.requestUpdate();
            return;
        }
        // No runs worth marking, but the count itself still answers "do they all differ?" — so say
        // that much rather than leaving the reader to open a dozen rows to find out.
        this.summary = summaryOf(
            payload.runs?.length ?? 0,
            payload.revisions ?? 0,
        );
        this.requestUpdate();
    }

    /** The revision rows this menu is drawn over, in the order the server listed them. */
    private rows(): HTMLElement[] {
        const list =
            this.closest("details")?.querySelector<HTMLElement>(
                ".cp-revision-list",
            );
        return [
            ...(list?.querySelectorAll<HTMLElement>("[data-revision]") ?? []),
        ];
    }

    /**
     * Whether [payload] is about this page's rows: a catalog that republished since render answers
     * over a newer window. The newest row is always a run head, so comparing those shas suffices.
     */
    private describesThisPage(payload: RenderRunsPayload): boolean {
        const newest = this.rows()[0]?.getAttribute("data-revision");
        return !!newest && newest === payload.runs?.[0]?.head;
    }

    /**
     * An inline payload so fixtures and offline viewers draw the markers without the runs lane,
     * giving the harness capture real coverage.
     */
    private inline(): RenderRunsPayload | null {
        const node = document.getElementById("cp-revision-runs-data");
        if (!node) return null;
        try {
            return JSON.parse(
                node.textContent || "null",
            ) as RenderRunsPayload | null;
        } catch {
            // A malformed payload falls through to the fetch, which is the same answer the page
            // would have given without it.
            return null;
        }
    }

    private async fetched(): Promise<RenderRunsPayload | null> {
        const runsUrl = this.getAttribute("data-runs-url");
        if (!runsUrl) return null;
        try {
            const response = await fetch(runsUrl, {
                credentials: "same-origin",
            });
            // A 404 means no branch could be asked or none exists; the menu works without this.
            if (!response.ok) return null;
            return (await response.json()) as RenderRunsPayload | null;
        } catch {
            return null;
        }
    }

    /**
     * Hang a thumbnail on each run head, matched by the row's `data-revision` sha rather than
     * parsing `?at=` (the current row carries no pin).
     */
    private decorate(view: RunsView): void {
        const list =
            this.closest("details")?.querySelector<HTMLElement>(
                ".cp-revision-list",
            );
        const rows = this.rows();
        if (!list || !rows.length) return;
        // Marks the list decorated so the stylesheet can indent non-head rows; a missing
        // `data-run-head` alone can't distinguish "not yet fetched".
        list.setAttribute("data-runs", "on");
        let seen = 0;
        for (const row of rows) {
            const marker = view.markers.get(
                row.getAttribute("data-revision") || "",
            );
            if (!marker) continue;
            // Marks every run head; the first one additionally opens the list, so the divider that
            // separates runs can be drawn on the others without a leading rule above the first row.
            row.setAttribute("data-run-head", seen === 0 ? "first" : "1");
            seen += 1;
            if (row.querySelector(".cp-revision-thumb")) continue;
            const img = document.createElement("img");
            img.className = "cp-revision-thumb";
            img.loading = "lazy";
            img.decoding = "async";
            img.alt = "";
            img.title = marker.title;
            img.src = marker.thumb;
            // A publish whose render will not load says nothing useful as a broken-image glyph; the
            // row's date and sha still do.
            img.addEventListener("error", () => img.remove());
            row.prepend(img);
            if (marker.span) {
                const span = document.createElement("span");
                span.className = "cp-revision-span";
                span.title = marker.title;
                span.textContent = marker.span;
                row.appendChild(span);
            }
        }
    }

    protected renderVue(): VNode | null {
        if (!this.summary) return null;
        return h("p", { class: "cp-revision-runs-summary" }, this.summary);
    }
}

declare global {
    interface HTMLElementTagNameMap {
        "cp-revision-runs": RevisionRuns;
    }
}
