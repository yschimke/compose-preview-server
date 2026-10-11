// `<cp-inspect-layers>` — the viewer's inspection overlays: numbered boxes over the render plus a
// legend beside it, one checkbox per layer in the Overrides panel's "Inspect" group. Box + badge +
// legend (hover either to light the other) is the compare page's idiom, since labels are wider
// than their boxes.
//
// Every source reports bounds in the render's pixel space, the snapshot `<img>`'s natural size, so
// one uniform scale places every layer. Renders nothing itself; it fills server-rendered
// containers. A second instance on the focused comparison names its own DOM via `inspect/host.ts`.
//
// Decisions live in `inspect/entries.ts` (what becomes a box), `inspect/layers.ts` (endpoints and
// deep links) and `inspect/host.ts` (which DOM it draws into).

import { ControllerElement, customElement } from "../controllerElement.js";
import { whenParsed } from "../dom/whenParsed.js";
import {
    a11yEntries,
    annotationEntries,
    slotEntries,
    type Entry,
} from "../inspect/entries.js";
import {
    LAYERS,
    activeLayers,
    dataUrlFor,
    fallbackUrl,
    inspectParam,
    carriesBeyond,
    kindsFromParam,
    layersParamFor,
    sourcesFor,
    withLayers,
    type LayerSpec,
} from "../inspect/layers.js";
import { resolveHost, type InspectHost } from "../inspect/host.js";
import { urlState } from "../urlState.js";

interface Box {
    id: string;
    node: HTMLElement;
    bounds: Entry["bounds"];
}

@customElement("cp-inspect-layers")
export class InspectLayers extends ControllerElement {
    private installed = false;
    private host: InspectHost | null = null;
    private img: HTMLImageElement | null = null;
    private previewId = "";

    /** Per `(source × frame)`: re-ticking a layer must not re-run a render of the same frame. */
    private cache = new Map<string, Promise<unknown>>();
    private cacheKey = "";
    private entries: Entry[] = [];
    private boxes: Box[] = [];
    private activeId: string | null = null;
    /** Bumped per refresh, so a slow fetch cannot draw over a newer one. */
    private generation = 0;
    private cleanups: Array<() => void> = [];
    private observer: MutationObserver | null = null;
    private resizes: ResizeObserver | null = null;

    connectedCallback(): void {
        super.connectedCallback();
        if (!this.install()) void whenParsed().then(() => this.install());
    }

    disconnectedCallback(): void {
        for (const off of this.cleanups) off();
        this.cleanups = [];
        this.observer?.disconnect();
        this.observer = null;
        this.resizes?.disconnect();
        this.resizes = null;
        this.installed = false;
        this.host = null;
        this.generation++;
        super.disconnectedCallback();
    }

    private install(): boolean {
        if (!this.isConnected || this.installed) return true;
        // Inert where the host can produce none of the products — a viewer without the inspect
        // group, or a page whose mount tag names parts it does not have.
        const host = resolveHost(this);
        if (!host) return false;
        this.host = host;
        this.syncTarget();
        this.installed = true;
        this.previewId = host.root.getAttribute("data-preview-id") ?? "";

        for (const toggle of host.toggles) {
            this.on(toggle, "change", () => void this.refresh());
        }
        this.on(window, "resize", () => this.place());
        this.on(host.frame, "load", () => this.place());
        if (host.specFrame) this.on(host.specFrame, "load", () => this.place());
        // `load` alone races: if the frame already decoded, the only `place()` happens mid-settle,
        // and boxes measured against a transient width stay subtly off. Observing the image (and
        // the stage, whose growth moves `offsetLeft`) re-places on every reflow, the first layout
        // included.
        if (typeof ResizeObserver === "function") {
            this.resizes = new ResizeObserver(() => this.place());
            this.resizes.observe(host.frame);
            if (host.specFrame) this.resizes.observe(host.specFrame);
            if (host.frame.parentElement)
                this.resizes.observe(host.frame.parentElement);
        }
        // `viewer.js` stamps `data-cp-src` once the replacement frame decodes: the honest "render
        // changed" signal.
        if (typeof MutationObserver === "function") {
            this.observer = new MutationObserver(() => {
                this.syncTarget();
                if (this.activeKinds().length) void this.refresh();
                else this.place();
            });
            this.observer.observe(host.frame, {
                attributes: true,
                attributeFilter: [host.frameSource],
            });
            this.observer.observe(host.root, {
                attributes: true,
                attributeFilter: ["data-mode", "data-spec-view"],
            });
        }

        this.hydrate();
        // Keep following the parameter on popstate: `inspect` is written with `replaceState`, but a
        // neighbour that pushes (e.g. `<cp-reference-compare>` pushing `annotate`) captures it in
        // an entry, and stepping back must not leave a checked layer undrawn.
        const offPop = urlState()?.onPop(() => {
            this.hydrate(true);
            void this.refresh();
        });
        if (offPop) this.cleanups.push(offPop);
        if (this.activeKinds().length) void this.refresh();
        return true;
    }

    private on(
        target: EventTarget,
        type: string,
        handler: EventListener,
    ): void {
        target.addEventListener(type, handler);
        this.cleanups.push(() => target.removeEventListener(type, handler));
    }

    private activeKinds(): string[] {
        return (this.host?.toggles ?? [])
            .filter((el) => el.checked && !el.disabled)
            .map((el) => el.getAttribute("data-cp-inspect") ?? "");
    }

    private isSpec(): boolean {
        if (!this.host?.hasSpecModes) return false;
        return (
            this.host.root.getAttribute("data-mode") === "spec" &&
            this.host.root.getAttribute("data-spec-view") === "spec"
        );
    }

    private isSpecComparison(): boolean {
        if (!this.host?.hasSpecModes) return false;
        const view = this.host.root.getAttribute("data-spec-view");
        return (
            this.host.root.getAttribute("data-mode") === "spec" &&
            (view === "diff" || view === "triptych" || view === "slider")
        );
    }

    /** Inspection follows the surface on the stage: Compose render or imported Figma raster. */
    private syncTarget(): void {
        const host = this.host;
        if (!host) return;
        this.img =
            this.isSpec() && host.specFrame ? host.specFrame : host.frame;
    }

    /**
     * Restore from `?inspect=a11y,typography`. On install this only ticks boxes (an absent
     * parameter doesn't deny a server-checked layer). On Back/Forward ([authoritative]) the entry
     * is the whole truth, so absent means none.
     */
    private hydrate(authoritative = false): void {
        const wanted = kindsFromParam(
            new URLSearchParams(location.search).get("inspect"),
        );
        if (!wanted.length && !authoritative) return;
        for (const toggle of this.host?.toggles ?? []) {
            const on = wanted.includes(
                toggle.getAttribute("data-cp-inspect") ?? "",
            );
            if (on || authoritative) toggle.checked = on;
        }
    }

    /** The URL of the frame ON SCREEN, which `viewer.js` records once its bytes have decoded. */
    private frameUrl(): string {
        if (this.isSpec())
            return (
                document
                    .getElementById("cp-spec-compare")
                    ?.getAttribute("data-reference") ?? "spec"
            );
        return this.img?.getAttribute(this.host?.frameSource ?? "") ?? "";
    }

    private referenceAnnotations(): unknown {
        const node = document.getElementById("cp-spec-annotations");
        if (!node) return null;
        try {
            const payload = JSON.parse(node.textContent ?? "") as {
                reference?: unknown;
            };
            return payload.reference ?? null;
        } catch {
            return null;
        }
    }

    private urlFor(source: string, kinds: string[]): string {
        const params = new URLSearchParams(location.search);
        const url =
            dataUrlFor(this.frameUrl(), source) ??
            fallbackUrl(this.host?.base ?? "", this.previewId, source, {
                token: params.get("token") ?? "",
                session: params.get("session") ?? "",
            });
        return withLayers(url, layersParamFor(source, kinds));
    }

    private fetchSource(source: string, kinds: string[]): Promise<unknown> {
        if (this.isSpec())
            return Promise.resolve(
                source === "annotations"
                    ? { annotations: this.referenceAnnotations() }
                    : null,
            );
        if (this.cacheKey !== this.frameUrl()) {
            this.cache = new Map();
            this.cacheKey = this.frameUrl();
        }
        // Keyed by endpoint and layer set, since narrower and wider requests have different
        // answers.
        const key = `${source}|${layersParamFor(source, kinds)}`;
        const wideKey = `${source}|`;
        // A wide payload answers every narrower question about the same frame, so prefer it.
        const cached = this.cache.get(key) ?? this.cache.get(wideKey);
        if (cached) return cached;
        const pending = fetch(this.urlFor(source, kinds), {
            credentials: "same-origin",
        })
            .then((response) => {
                if (!response.ok)
                    throw new Error(`${source} ${response.status}`);
                return response.json() as unknown;
            })
            .then((payload) => {
                // A narrowed request may be answered with every layer (the daemon projects all
                // three from one capture; see `ServeHost.renderAnnotations`), so file it under the
                // wide key too, avoiding a second render that might describe different pixels.
                if (key !== wideKey && carriesBeyond(payload, source, kinds))
                    this.cache.set(wideKey, pending);
                return payload;
            })
            // A host that can't produce this product just draws nothing. Don't cache the failure,
            // or a transient 500 blanks the layer for the life of the frame.
            .catch(() => {
                if (this.cache.get(key) === pending) this.cache.delete(key);
                return null;
            });
        this.cache.set(key, pending);
        return pending;
    }

    private async refresh(): Promise<void> {
        const kinds = this.activeKinds();
        // Every refresh supersedes the previous, including into comparison views where this paints
        // nothing, so a late response can't repaint a stale legend.
        const generation = ++this.generation;
        this.syncUrl(kinds);
        window.dispatchEvent(
            new CustomEvent("cp-inspect-change", { detail: { kinds } }),
        );
        if (this.isSpecComparison()) {
            this.entries = [];
            this.draw();
            return;
        }
        if (!kinds.length) {
            this.entries = [];
            this.draw();
            return;
        }
        this.host?.legend.setAttribute("aria-busy", "true");
        const names = sourcesFor(kinds);
        const results = await Promise.all(
            names.map((name) => this.fetchSource(name, kinds)),
        );
        if (generation !== this.generation) return;
        this.host?.legend.removeAttribute("aria-busy");
        const byName = new Map(names.map((name, i) => [name, results[i]]));
        this.entries = activeLayers(kinds).flatMap((spec) => {
            const payload = byName.get(spec.source) ?? null;
            if (spec.kind === "slots") return slotEntries(payload as never);
            if (spec.kind === "a11y") return a11yEntries(payload as never);
            return annotationEntries(payload as never, spec.kind);
        });
        this.draw();
    }

    /**
     * Written with `replaceState`: ticking a layer is a reading aid over the same frame, not a new
     * render.
     */
    private syncUrl(kinds: string[]): void {
        try {
            const url = new URL(location.href);
            const value = inspectParam(kinds);
            if (value) url.searchParams.set("inspect", value);
            else url.searchParams.delete("inspect");
            history.replaceState(history.state, "", url.toString());
        } catch {
            // A browser that refuses the rewrite still gets the overlay.
        }
    }

    /** Place every box against the image's current size and position (the stage centres it). */
    private place(): void {
        const img = this.img;
        const layer = this.host?.layer;
        if (!img || !layer || !img.naturalWidth || !this.boxes.length) return;
        const scale = img.clientWidth / img.naturalWidth;
        layer.style.width = `${img.clientWidth}px`;
        layer.style.height = `${img.clientHeight}px`;
        if (this.host?.anchor === "offset") {
            layer.style.left = `${img.offsetLeft}px`;
            layer.style.top = `${img.offsetTop}px`;
        }
        for (const box of this.boxes) {
            box.node.style.left = `${box.bounds.x * scale}px`;
            box.node.style.top = `${box.bounds.y * scale}px`;
            box.node.style.width = `${box.bounds.width * scale}px`;
            box.node.style.height = `${box.bounds.height * scale}px`;
        }
    }

    /** Hovering either a box or its legend row lights up the other. */
    private highlight(id: string | null): void {
        this.activeId = id;
        for (const box of this.boxes) {
            box.node.classList.toggle("cp-inspect-box-active", box.id === id);
        }
        for (const row of this.host?.legend.querySelectorAll(
            "[data-cp-entry]",
        ) ?? []) {
            row.classList.toggle(
                "cp-inspect-entry-active",
                row.getAttribute("data-cp-entry") === id,
            );
        }
    }

    private draw(): void {
        const layer = this.host?.layer;
        const legend = this.host?.legend;
        if (!layer || !legend) return;
        layer.textContent = "";
        legend.textContent = "";
        this.boxes = [];
        if (!this.entries.length) {
            legend.hidden = true;
            this.host?.root.removeAttribute("data-inspect");
            return;
        }
        this.host?.root.setAttribute("data-inspect", "on");
        legend.hidden = false;
        legend.appendChild(this.legendHead());

        // In DECLARED order, not the order the entries happen to arrive in, so the legend's
        // sections read the same way every time.
        for (const spec of LAYERS) {
            const mine = this.entries.filter(
                (entry) => entry.kind === spec.kind,
            );
            if (!mine.length) continue;
            legend.appendChild(this.section(spec, mine));
        }
        this.place();
        this.highlight(this.activeId);
    }

    private legendHead(): HTMLElement {
        const head = document.createElement("div");
        head.className = "cp-inspect-legend-head";
        head.textContent = "Inspect";
        const count = document.createElement("span");
        count.className = "cp-inspect-legend-count";
        count.textContent = String(this.entries.length);
        head.appendChild(count);
        return head;
    }

    private section(spec: LayerSpec, entries: Entry[]): HTMLElement {
        const section = document.createElement("div");
        section.className = "cp-inspect-section";
        const title = document.createElement("div");
        title.className = "cp-inspect-section-head";
        title.textContent = `${spec.label} (${entries.length})`;
        section.appendChild(title);
        const list = document.createElement("ol");
        list.className = "cp-inspect-list";
        entries.forEach((entry, index) => {
            const id = `${spec.kind}-${index}`;
            const ordinal = String(index + 1);
            this.host?.layer.appendChild(
                this.box(entry, id, ordinal, spec.kind),
            );
            list.appendChild(this.row(entry, id, ordinal, spec.kind));
        });
        section.appendChild(list);
        return section;
    }

    private box(
        entry: Entry,
        id: string,
        ordinal: string,
        kind: string,
    ): HTMLElement {
        const box = document.createElement("div");
        box.className = "cp-inspect-box";
        box.setAttribute("data-cp-kind", kind);
        box.setAttribute("data-level", entry.level);
        box.title =
            entry.tooltip ||
            (entry.detail ? `${entry.title} · ${entry.detail}` : entry.title);
        if (entry.color)
            box.style.setProperty("--cp-inspect-color", entry.color);
        const badge = document.createElement("span");
        badge.className = "cp-inspect-badge";
        badge.textContent = ordinal;
        box.appendChild(badge);
        box.addEventListener("mouseenter", () => this.highlight(id));
        box.addEventListener("mouseleave", () => this.highlight(null));
        // Only where the host says a click means something (`InspectHost.selectable`). Bounds are
        // already in the render's pixel space, the plane `compose-parity-locator/v1` accepts, so no
        // conversion.
        if (this.host?.selectable) {
            box.classList.add("cp-inspect-box--selectable");
            box.addEventListener("click", (event) => {
                event.preventDefault();
                event.stopPropagation();
                this.announcePick(entry);
            });
        }
        this.boxes.push({ id, node: box, bounds: entry.bounds });
        return box;
    }

    /**
     * Announce the picked part of the render, from both the box and its legend row so pointer and
     * keyboard picks record the same thing. Bounds are unconverted (render pixel space).
     */
    private announcePick(entry: Entry): void {
        window.dispatchEvent(
            new CustomEvent("cp-element-pick", {
                detail: { bounds: entry.bounds, label: entry.title },
            }),
        );
    }

    private row(
        entry: Entry,
        id: string,
        ordinal: string,
        kind: string,
    ): HTMLElement {
        const row = document.createElement("li");
        row.className = "cp-inspect-entry";
        row.setAttribute("data-cp-entry", id);
        row.setAttribute("data-cp-kind", kind);
        row.setAttribute("data-level", entry.level);
        row.tabIndex = 0;
        if (entry.tooltip) row.title = entry.tooltip;
        if (entry.color)
            row.style.setProperty("--cp-inspect-color", entry.color);
        const marker = document.createElement("span");
        marker.className = "cp-inspect-badge";
        marker.textContent = ordinal;
        row.appendChild(marker);
        const text = document.createElement("span");
        text.className = "cp-inspect-text";
        const strong = document.createElement("strong");
        strong.textContent = entry.title;
        text.appendChild(strong);
        if (entry.detail) {
            const sub = document.createElement("span");
            sub.className = "cp-inspect-detail";
            sub.textContent = entry.detail;
            text.appendChild(sub);
        }
        row.appendChild(text);
        // Focus as well as hover: the legend is a keyboard path into the same highlight.
        row.addEventListener("mouseenter", () => this.highlight(id));
        row.addEventListener("mouseleave", () => this.highlight(null));
        row.addEventListener("focus", () => this.highlight(id));
        row.addEventListener("blur", () => this.highlight(null));
        // Where picks mean something, the focusable legend row is also the keyboard path into
        // selection (the box is an unfocusable overlay, and the drag is pointer-only).
        if (this.host?.selectable) {
            row.classList.add("cp-inspect-entry--selectable");
            row.setAttribute("role", "button");
            row.addEventListener("click", () => this.announcePick(entry));
            row.addEventListener("keydown", (event: KeyboardEvent) => {
                if (event.key !== "Enter" && event.key !== " ") return;
                // Space scrolls a focused element by default; a selection is not a scroll.
                event.preventDefault();
                this.announcePick(entry);
            });
        }
        return row;
    }
}

declare global {
    interface HTMLElementTagNameMap {
        "cp-inspect-layers": InspectLayers;
    }
}
