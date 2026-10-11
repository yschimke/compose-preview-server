// `<cp-rc-lanes>` — the compare page's "Remote Compose players" view: each player's published
// render of the same `ir/*.rc` side by side, and once a reference column is picked, a pixel diff of
// the others against it. Renders and baked diffs come from
// `compose-ai-tools/scripts/design-artifacts/rc-compare.mjs`; this only diffs player against player
// on a canvas. The table is server-rendered (works without JS), so this writes into cells it does
// not own and `serve.css` hides the tag. Decisions live in `rc/rowPlan.ts`, `rc/pixelDiff.ts` and
// `rc/rowFilter.ts`.

import { ControllerElement, customElement } from "../controllerElement.js";
import { aliasesFor, readAliasTable } from "../compare/aliases.js";
import { whenParsed } from "../dom/whenParsed.js";
import { urlState } from "../urlState.js";
import {
    NO_REFERENCE,
    laneIdsOf,
    referenceFrom,
    shortLabelOf,
    type RcModel,
} from "../rc/model.js";
import {
    DEFAULT_THRESHOLD,
    diffPixels,
    sameSize,
    type Pixels,
} from "../rc/pixelDiff.js";
import {
    band,
    percentText,
    planRow,
    sizeMismatchText,
    type Band,
} from "../rc/rowPlan.js";
import { countLabel, filterRows, statusFor } from "../rc/rowFilter.js";

/** The global `format-compare.js` calls — it owns the format switch and the shared search box. */
interface RcLanesApi {
    filter(query: string): void;
    refresh(): void;
}

declare global {
    interface Window {
        cpRcLanes?: RcLanesApi;
    }
}

@customElement("cp-rc-lanes")
export class RcLanes extends ControllerElement {
    private model: RcModel | null = null;
    private laneIds: string[] = [];
    private threshold = DEFAULT_THRESHOLD;
    private section: HTMLElement | null = null;
    private rows: HTMLElement[] = [];
    /** The page's alias table, read on the first filter pass. See `compare/aliases.ts`. */
    private aliases: import("../compare/aliases.js").AliasTable | null = null;
    private reference = NO_REFERENCE;
    /**
     * Bumped on every reference change; async steps abandon themselves when their token no longer
     * matches, so a switch mid-scroll does not race into the same cells.
     */
    private pass = 0;
    private installed = false;
    private observer: IntersectionObserver | null = null;
    private scored = new WeakMap<HTMLElement, number>();
    private images = new Map<string, Promise<HTMLImageElement>>();
    private cleanups: Array<() => void> = [];

    /**
     * Set up now if the markup is there, else after parse. `format-compare.js` calls
     * `window.cpRcLanes.filter()` on its first pass, so always deferring to `DOMContentLoaded`
     * would publish the global too late; the fallback covers a tag emitted elsewhere.
     */
    connectedCallback(): void {
        super.connectedCallback();
        if (!this.install()) void whenParsed().then(() => this.install());
    }

    disconnectedCallback(): void {
        for (const off of this.cleanups) off();
        this.cleanups = [];
        this.observer?.disconnect();
        this.observer = null;
        if (window.cpRcLanes === this.api) delete window.cpRcLanes;
        // Everything this owns lives outside it (picker listeners, row observer, a global), so
        // teardown clears the flag for a re-insert to rewire, and bumps the pass so in-flight work
        // abandons itself.
        this.installed = false;
        this.pass++;
        super.disconnectedCallback();
    }

    private api: RcLanesApi = {
        filter: (query: string) => this.filter(query),
        refresh: () => this.apply(),
    };

    /** @return whether the page was ready to be wired up; false means "try again after the parse". */
    private install(): boolean {
        // Tracked separately from `model`, which an in-flight pass still reads after a disconnect.
        if (!this.isConnected || this.installed) return true;
        this.section = document.getElementById("cp-rc-lanes");
        const modelNode = document.getElementById("cp-rc-model");
        if (!this.section || !modelNode) return false;
        this.installed = true;
        try {
            this.model = JSON.parse(modelNode.textContent || "null") as RcModel;
        } catch {
            // A payload the server could not have produced. The table stays as served — every
            // render is already in it — rather than the page erroring on load. Nothing to retry.
            return true;
        }
        if (!this.model?.lanes) {
            this.model = null;
            return true;
        }
        this.laneIds = laneIdsOf(this.model);
        if (typeof this.model.threshold === "number")
            this.threshold = this.model.threshold;
        this.rows = Array.from(
            this.section.querySelectorAll<HTMLElement>(".cp-rc-row"),
        );
        this.reference = referenceFrom(location.search, this.laneIds);

        this.observer = new IntersectionObserver(
            (entries) => {
                for (const entry of entries) {
                    if (!entry.isIntersecting) continue;
                    const row = entry.target as HTMLElement;
                    if (this.reference === NO_REFERENCE) continue;
                    if (this.scored.get(row) === this.pass) continue;
                    this.scored.set(row, this.pass);
                    void this.scoreRow(row, this.pass);
                }
            },
            // Start a row a little before it arrives, so scrolling meets numbers rather than
            // watching them appear.
            { rootMargin: "400px 0px" },
        );

        for (const button of this.buttons()) {
            const onClick = () => {
                this.reference =
                    button.getAttribute("data-rc-ref") ?? NO_REFERENCE;
                urlState()?.push({
                    ref: this.reference === NO_REFERENCE ? "" : this.reference,
                });
                this.apply();
            };
            button.addEventListener("click", onClick);
            this.cleanups.push(() =>
                button.removeEventListener("click", onClick),
            );
        }
        // Unsubscribed with the rest, so re-insertion doesn't stack `popstate` callbacks and a
        // detached element stops writing.
        const offPop = urlState()?.onPop(() => {
            this.reference = referenceFrom(location.search, this.laneIds);
            this.apply();
        });
        if (offPop) this.cleanups.push(offPop);

        window.cpRcLanes = this.api;
        this.apply();
        return true;
    }

    private buttons(): HTMLElement[] {
        return Array.from(
            this.section?.querySelectorAll<HTMLElement>("[data-rc-ref]") ?? [],
        );
    }

    private apply(): void {
        if (!this.section) return;
        this.pass++;
        for (const button of this.buttons()) {
            button.setAttribute(
                "aria-pressed",
                String(button.getAttribute("data-rc-ref") === this.reference),
            );
        }
        this.section.setAttribute("data-reference", this.reference);
        // Disconnect before re-observing: `observe()` on an observed target is a no-op, and
        // disconnecting queues a fresh initial callback so on-screen rows refill.
        this.observer?.disconnect();
        for (const row of this.rows) {
            this.clearRow(row);
            this.scored.delete(row);
        }
        const status = document.getElementById("cp-rc-status");
        if (status) {
            status.textContent = statusFor(
                this.reference,
                shortLabelOf(this.model!, this.reference),
            );
        }
        if (this.reference === NO_REFERENCE) return;
        for (const row of this.rows)
            if (!row.hidden) this.observer?.observe(row);
    }

    private filter(query: string): void {
        const preview =
            new URLSearchParams(location.search).get("preview") ?? "";
        const aliases = (this.aliases ??= readAliasTable(document));
        const { keep, visible, empty } = filterRows(
            this.rows.map((row) => ({
                hay: row.getAttribute("data-hay") ?? "",
                // The lane wall does NOT subtract the rowed ids: its rows are one per preview
                // rather than one per design mapping, so every id on a card selects its row.
                previewIds: aliasesFor(
                    aliases,
                    row.getAttribute("data-preview-ids") ?? "",
                    row.getAttribute("data-alias-card"),
                    false,
                ).join(" "),
            })),
            query,
            preview,
        );
        this.rows.forEach((row, i) => {
            row.hidden = !keep[i];
        });
        const count = document.getElementById("cp-compare-count");
        if (count) count.textContent = countLabel(visible);
        const emptyNote = document.getElementById("cp-rc-empty");
        if (emptyNote) emptyNote.hidden = !empty;
        if (this.reference === NO_REFERENCE) return;
        // A row filtered back into view has never been scored, so it has to start observing —
        // and one filtered out must stop, or it keeps a pass alive against a hidden row.
        for (const row of this.rows) {
            if (row.hidden) this.observer?.unobserve(row);
            else this.observer?.observe(row);
        }
    }

    private async scoreRow(row: HTMLElement, pass: number): Promise<void> {
        const model = this.model?.rows[Number(row.getAttribute("data-row"))];
        const scores = row.querySelector<HTMLElement>("[data-scores]");
        if (!model || !scores) return;
        this.cellFor(row, this.reference)?.classList.add("is-reference");
        // Mark the row mid-measurement so the harness (and debugging) can tell "still working" from
        // "finished"; `clearRow` drops it.
        row.dataset.scored = "pending";

        // Decode the reference frame once per row: each `pixels()` call allocates a canvas and does
        // a full `getImageData` readback.
        let referencePixels: Promise<Pixels> | null = null;

        // Sequential on purpose: each step decodes two full frames onto a canvas, and a row of
        // players started at once would stall the scroll this observer exists to keep smooth.
        for (const step of planRow(model, this.laneIds, this.reference)) {
            if (pass !== this.pass) return;
            const label = shortLabelOf(this.model!, step.laneId);
            if (step.kind === "chip") {
                scores.appendChild(
                    this.chip(label, step.text, band(step.pct), step.px),
                );
                if (step.diff) this.showDiff(row, step.laneId, step.diff);
                continue;
            }
            try {
                referencePixels ??= this.pixels(step.referenceSrc);
                const [reference, lane] = await Promise.all([
                    referencePixels,
                    this.pixels(step.laneSrc),
                ]);
                if (pass !== this.pass) return;
                if (!sameSize(lane, reference)) {
                    scores.appendChild(
                        this.chip(
                            label,
                            sizeMismatchText(lane, reference),
                            "na",
                            null,
                        ),
                    );
                    continue;
                }
                const diff = diffPixels(reference, lane, this.threshold);
                if (pass !== this.pass) return;
                scores.appendChild(
                    this.chip(
                        label,
                        percentText(diff.percent),
                        band(diff.percent),
                        diff.changed,
                    ),
                );
                this.showDiff(
                    row,
                    step.laneId,
                    this.toDataUrl(diff.data, reference),
                );
            } catch {
                if (pass !== this.pass) return;
                scores.appendChild(this.chip(label, "diff failed", "na", null));
            }
        }
        if (pass === this.pass) row.dataset.scored = "done";
    }

    private chip(
        label: string,
        text: string,
        tone: Band,
        px: number | null,
    ): HTMLElement {
        const line = document.createElement("div");
        line.className = "cp-rc-scoreline";
        const name = document.createElement("span");
        name.className = "cp-rc-scorelabel";
        name.textContent = label;
        const score = document.createElement("span");
        score.className = `cp-rc-score cp-rc-score--${tone}`;
        score.textContent = text;
        line.append(name, score);
        if (px !== null) {
            const pxEl = document.createElement("span");
            pxEl.className = "cp-rc-px";
            pxEl.textContent = `${px.toLocaleString("en-US")} px`;
            line.appendChild(pxEl);
        }
        return line;
    }

    private clearRow(row: HTMLElement): void {
        delete row.dataset.scored;
        const scores = row.querySelector("[data-scores]");
        if (scores) scores.textContent = "";
        for (const slot of row.querySelectorAll<HTMLElement>(
            ".cp-rc-diffslot",
        )) {
            slot.textContent = "";
            slot.hidden = true;
        }
        for (const cell of row.querySelectorAll(".cp-rc-cell")) {
            cell.classList.remove("is-reference");
        }
    }

    private cellFor(row: HTMLElement, laneId: string): HTMLElement | null {
        // Compared rather than interpolated into a selector, even though ids are already validated.
        return (
            Array.from(row.querySelectorAll<HTMLElement>(".cp-rc-cell")).find(
                (cell) => cell.dataset.lane === laneId,
            ) ?? null
        );
    }

    private showDiff(row: HTMLElement, laneId: string, src: string): void {
        const slot = this.cellFor(row, laneId)?.querySelector<HTMLElement>(
            ".cp-rc-diffslot",
        );
        if (!slot) return;
        const caption = document.createElement("div");
        caption.className = "cp-rc-difflabel";
        caption.textContent = `pixel diff vs ${shortLabelOf(this.model!, this.reference)}`;
        const img = document.createElement("img");
        img.loading = "lazy";
        img.src = src;
        img.alt = "pixel diff";
        slot.textContent = "";
        slot.append(caption, img);
        slot.hidden = false;
    }

    /** Decoded once per URL: the same render is the reference for every other lane in its row. */
    private load(src: string): Promise<HTMLImageElement> {
        const cached = this.images.get(src);
        if (cached) return cached;
        const pending = new Promise<HTMLImageElement>((resolve, reject) => {
            const img = new Image();
            img.onload = () => resolve(img);
            img.onerror = () => reject(new Error(`could not load ${src}`));
            img.src = src;
        });
        this.images.set(src, pending);
        return pending;
    }

    private async pixels(src: string): Promise<Pixels> {
        const img = await this.load(src);
        const canvas = document.createElement("canvas");
        canvas.width = img.naturalWidth;
        canvas.height = img.naturalHeight;
        const context = canvas.getContext("2d", { willReadFrequently: true });
        if (!context) throw new Error("no 2d context");
        context.drawImage(img, 0, 0);
        return context.getImageData(0, 0, canvas.width, canvas.height);
    }

    private toDataUrl(data: Uint8ClampedArray, size: Pixels): string {
        const canvas = document.createElement("canvas");
        canvas.width = size.width;
        canvas.height = size.height;
        // Copied into a fresh buffer: `ImageData` insists on a plain `ArrayBuffer`, and the
        // array `diffPixels` returns is typed loosely enough to have come from a shared one.
        const image = new ImageData(size.width, size.height);
        image.data.set(data);
        canvas.getContext("2d")!.putImageData(image, 0, 0);
        return canvas.toDataURL("image/png");
    }
}

declare global {
    interface HTMLElementTagNameMap {
        "cp-rc-lanes": RcLanes;
    }
}
