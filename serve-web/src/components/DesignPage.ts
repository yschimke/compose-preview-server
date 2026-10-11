// `<cp-design-page>`: a design-file page inlined as SVG, with this catalog's renders standing in
// for the components it implements.
//
// The SVG is the geometry: each node is found by `data-node-id` and measured by the browser, so the
// manifest carries no rectangles and the swap lands exactly on the drawn shape. Positions are
// percentages of the stage, so a resize only re-measures.
//
// Renders nothing itself (`serve.css` hides the tag). Decisions live in `design/ink.ts` (fitting
// drawn pixels), `design/score.ts` (badges), `design/geometry.ts` (slots, crops, tip) and
// `design/lanes.ts` (lanes and filters).

import { ControllerElement, customElement } from "../controllerElement.js";
import { urlState } from "../urlState.js";
import { compareApi, type CompareApi } from "../compare/api.js";
import { whenParsed } from "../dom/whenParsed.js";
import { domGeometry, paintedRect } from "../design/clip.js";
import {
    cropFor,
    idMatches,
    sheetSize,
    slotIn,
    tipAt,
    type Box,
} from "../design/geometry.js";
import { fitInk, imageInk, type InkBounds } from "../design/ink.js";
import {
    DIFF_ALL_CLASS,
    allowsBaseline,
    baselineAfterLane,
    baselineOf,
    pageParams,
    pageStateFrom,
    isInert,
    laneOf,
    needsParallel,
    needsRenders,
    outlinesAfterUnlinked,
    scoreKey,
    showsEveryBadge,
    stageState,
    type Baseline,
    type Lane,
    type Source,
} from "../design/lanes.js";
import { badgeFor } from "../design/score.js";

/**
 * One catalog's render of a node in its slot. A slot can hold two (ours and the `compareWith`
 * sibling's) as separate elements rather than one with a swapped `src`, which would re-fetch, lose
 * the per-image ink measurement, and pass a URL through JavaScript (CodeQL `js/xss-through-dom`).
 */
interface Picture {
    image: HTMLImageElement;
    ink?: InkBounds | null;
    /** Whether the image actually decoded. A slot cannot stand in for the design until one has. */
    ok: boolean;
}

interface Entry {
    overlay: HTMLElement;
    target: SVGElement;
    /** By source — `code` for ours, `parallel` for the sibling's. Never `design`. */
    pictures: Map<Source, Picture>;
}

interface Sheet {
    image: HTMLImageElement;
    width: number;
    height: number;
}

const rectOf = (element: Element): Box => {
    const rect = element.getBoundingClientRect();
    return {
        left: rect.left,
        top: rect.top,
        width: rect.width,
        height: rect.height,
    };
};

/**
 * A design node's box. `getBoundingClientRect()` ignores `clip-path`, so a node clipping an
 * oversized shape measured as that shape and its render landed as a blob. `paintedRect` walks the
 * clips (degrading to this rect without any). A node clipped to nothing returns a zero-area box,
 * which callers treat as missing.
 */
const EMPTY_BOX: Box = { left: 0, top: 0, width: 0, height: 0 };

const nodeBoxOf = (element: SVGElement): Box =>
    paintedRect(element, domGeometry) ?? EMPTY_BOX;

/**
 * Where each source's renders wait until asked for. `design` has none: its drawing is the inlined
 * SVG.
 */
const SOURCE_TEMPLATES: ReadonlyArray<[Source, string]> = [
    ["code", "[data-cp-page-render-source]"],
    ["parallel", "[data-cp-page-parallel-source]"],
];

/** The class the images of one source carry, which is also what the stylesheet shows and hides. */
const SOURCE_CLASS: Record<Source, string> = {
    code: "cp-page-render",
    parallel: "cp-page-parallel",
    design: "",
};

@customElement("cp-design-page")
export class DesignPage extends ControllerElement {
    private installed = false;
    private root!: HTMLElement;
    private stage!: HTMLElement;
    private svg!: SVGSVGElement;
    /**
     * The zooming layer transformed by `<cp-page-zoom>`: export, overlays and renders move under
     * one transform. Everything is measured against it, since it is the overlays' containing block.
     * Named "layer" because "canvas" here means the `<canvas>` the ink fit uses.
     */
    private zoomLayer!: HTMLElement;

    private lanes: HTMLInputElement[] = [];
    /** The `Diff against` radios, in the order the server emitted them. */
    private baselines: HTMLInputElement[] = [];
    /** What the sheet showed last, so a swap onto the baseline can hand it the lane just left. */
    private shown: Lane = "code";
    /** Whether a diff control is being held down right now. See {@link showsEveryBadge}. */
    private diffHeld = false;
    private outlinesToggle: HTMLInputElement | null = null;
    private unlinkedToggle: HTMLInputElement | null = null;
    private legend: HTMLElement | null = null;
    private tip: HTMLElement | null = null;
    private list: HTMLElement | null = null;
    private disclosure: HTMLElement | null = null;

    private overlays: HTMLElement[] = [];
    private nodes: Entry[] = [];
    private byId = new Map<string, Entry>();
    /** The inert `<template>` per source, spent when the source is first needed. */
    private sources = new Map<Source, HTMLTemplateElement>();
    private diffLinkSource: HTMLTemplateElement | null = null;

    private sheetRaster: Promise<Sheet | null> | null = null;
    /**
     * Scored once per pairing ({@link scoreKey}), so flipping back doesn't redo the work, and a
     * node's score against Figma isn't reused for the sibling. Failed pairings stay unscored so
     * re-entry retries.
     */
    private scoredNodes = new Set<string>();
    private described: string | null = null;
    private cleanups: Array<() => void> = [];
    private resizes: ResizeObserver | null = null;

    connectedCallback(): void {
        super.connectedCallback();
        if (!this.install()) void whenParsed().then(() => this.install());
    }

    disconnectedCallback(): void {
        for (const off of this.cleanups) off();
        this.cleanups = [];
        this.resizes?.disconnect();
        this.resizes = null;
        this.installed = false;
        super.disconnectedCallback();
    }

    private on(
        target: EventTarget,
        type: string,
        handler: EventListener,
    ): void {
        target.addEventListener(type, handler);
        this.cleanups.push(() => target.removeEventListener(type, handler));
    }

    private install(): boolean {
        if (!this.isConnected || this.installed) return true;
        const root = document.getElementById("cp-design-page");
        const stage = root?.querySelector<HTMLElement>(".cp-page-stage");
        const svg = stage?.querySelector("svg");
        if (!root || !stage || !svg) return false;
        this.installed = true;
        this.root = root;
        this.stage = stage;
        this.svg = svg;
        this.zoomLayer =
            stage.querySelector<HTMLElement>("[data-cp-page-canvas]") ?? stage;

        this.lanes = Array.from(
            root.querySelectorAll<HTMLInputElement>("[data-cp-page-lane]"),
        );
        this.baselines = Array.from(
            root.querySelectorAll<HTMLInputElement>("[data-cp-page-baseline]"),
        );
        this.outlinesToggle = root.querySelector("[data-cp-page-outlines]");
        this.unlinkedToggle = root.querySelector("[data-cp-page-unlinked]");
        this.legend = root.querySelector(".cp-page-legend");
        this.tip = root.querySelector("[data-cp-page-tip]");
        this.list = root.querySelector(".cp-page-list");
        this.disclosure = root.querySelector(".cp-page-nodes");

        this.overlays = Array.from(
            stage.querySelectorAll<HTMLElement>(".cp-page-node"),
        );
        for (const overlay of this.overlays) {
            const id = overlay.getAttribute("data-cp-node") ?? "";
            const target = this.findInSvg(id);
            if (!target) {
                // Named by the manifest but absent from the export (flattened by the design tool):
                // mark it rather than drop it, so the list still shows the mapping.
                overlay.setAttribute("data-cp-missing", "");
                continue;
            }
            const entry: Entry = { overlay, target, pictures: new Map() };
            this.nodes.push(entry);
            this.byId.set(id, entry);
        }

        for (const [source, selector] of SOURCE_TEMPLATES) {
            const template = stage.querySelector<HTMLTemplateElement>(selector);
            if (template) this.sources.set(source, template);
        }
        this.diffLinkSource = stage.querySelector("[data-cp-page-diff-links]");
        this.armDiffLinks();
        this.wireNodes();
        this.wireControls();

        // Hydrate from the URL first, so a shared `?lane=parallel&baseline=design` opens on that
        // pairing without a visible rebuild.
        this.hydrate();
        this.shown = this.lane();
        this.applyOutlines();
        this.applyUnlinked();
        this.syncBaselines();
        this.applyLane();
        this.measure();
        // Back and Forward walk the same four controls. Unsubscribed with the rest — `onPop` is a
        // `popstate` listener on `window`, which would outlive this element otherwise.
        const offPop = urlState()?.onPop(() => {
            this.hydrate();
            this.shown = this.lane();
            this.applyOutlines();
            this.applyUnlinked();
            this.syncBaselines();
            this.applyLane();
        });
        if (offPop) this.cleanups.push(offPop);

        if (typeof ResizeObserver === "function") {
            this.resizes = new ResizeObserver(() => this.measure());
            this.resizes.observe(stage);
        } else {
            this.on(window, "resize", () => this.measure());
        }
        // Outlined text is the bulk of a specimen sheet and lands with the markup, but a page that
        // also carries a webfont or an embedded raster can reflow after first paint.
        this.on(window, "load", () => this.measure());
        return true;
    }

    /**
     * Compare attribute values rather than interpolating the id (design-file text) into a selector.
     */
    private findInSvg(id: string): SVGElement | null {
        if (!id) return null;
        for (const element of this.svg.querySelectorAll<SVGElement>(
            "[data-node-id]",
        )) {
            if (idMatches(element.getAttribute("data-node-id"), id))
                return element;
        }
        return null;
    }

    // ---- placement -----------------------------------------------------------

    private measure(): void {
        const layer = rectOf(this.zoomLayer);
        for (const entry of this.nodes) {
            const node = nodeBoxOf(entry.target);
            const slot = slotIn(layer, node);
            if (!slot) {
                // A zero-area node is missing; a zero-area layer means not laid out yet, so leave
                // the placement for the observer.
                if (layer.width > 0 && layer.height > 0)
                    entry.overlay.setAttribute("data-cp-missing", "");
                continue;
            }
            entry.overlay.removeAttribute("data-cp-missing");
            Object.assign(entry.overlay.style, slot);
            this.placeRenders(entry, node);
        }
    }

    /** Every picture in this slot, each fitted by its OWN ink — two catalogs crop differently. */
    private placeRenders(entry: Entry, slot: Box): void {
        for (const picture of entry.pictures.values())
            this.placeRender(picture, slot);
    }

    private placeRender(picture: Picture, slot: Box): void {
        const image = picture.image;
        const placed = fitInk(slot, picture.ink ?? null);
        if (!placed) {
            // Back to the stylesheet's `inset: 0` + `object-fit: contain`.
            image.style.left = "";
            image.style.top = "";
            image.style.width = "";
            image.style.height = "";
            return;
        }
        Object.assign(image.style, placed);
    }

    /**
     * Tight bounds of an image's non-transparent pixels, the browser twin of
     * `ServeThumbCrop.pngAlphaBounds`. Null without a usable canvas (none, or tainted), falling
     * back to plain `contain`.
     */
    private inkBounds(image: HTMLImageElement): InkBounds | null {
        return imageInk(image);
    }

    private takeInk(entry: Entry, picture: Picture): void {
        const read = () => {
            picture.ink = this.inkBounds(picture.image);
            // Re-place just this slot; the node's box hasn't moved.
            const node = nodeBoxOf(entry.target);
            if (node.width > 0 && node.height > 0)
                this.placeRender(picture, node);
        };
        const image = picture.image;
        if (image.complete && image.naturalWidth > 0) read();
        else image.addEventListener("load", read);
    }

    // ---- the renders ---------------------------------------------------------

    /**
     * A source's renders are served in an inert `<template>` and adopted the first time a pairing
     * names that source. `code` is adopted on first paint (images are `loading="lazy"`); `parallel`
     * only when asked for, since those images come from another catalog's daemon. A template rather
     * than a `data-src` swap keeps every URL server-built and avoids a `js/xss-through-dom` sink.
     */
    private armSource(source: Source): void {
        const template = this.sources.get(source);
        if (!template) return;
        this.sources.delete(source);
        const selector = `.${SOURCE_CLASS[source]}`;
        for (const image of template.content.querySelectorAll<HTMLImageElement>(
            selector,
        )) {
            const entry = this.byId.get(
                image.getAttribute("data-cp-node") ?? "",
            );
            // No entry means the export doesn't carry that node, so there is no box to put a render
            // in and nothing to hide. Skipping leaves the row in the list, which is the honest state.
            if (!entry) continue;
            entry.overlay.appendChild(image);
            const picture: Picture = { image, ok: false };
            entry.pictures.set(source, picture);
            this.standIn(entry, picture);
            this.takeInk(entry, picture);
        }
        template.remove();
        this.measure();
    }

    /**
     * Hide the design's drawing only once the requested picture has arrived, and restore it if it
     * fails, so a missing render never leaves a hole. Recomputed from the shown source (a slot can
     * hold two pictures). A failed `<img>` is hidden too, so the broken-image glyph doesn't cover
     * the drawing.
     */
    private standIn(entry: Entry, picture: Picture): void {
        const image = picture.image;
        const settle = (ok: boolean) => {
            picture.ok = ok;
            image.hidden = !ok;
            this.syncStandIn(entry);
        };
        if (image.complete && image.naturalWidth > 0) {
            picture.ok = true;
            this.syncStandIn(entry);
            return;
        }
        image.addEventListener("load", () => settle(true));
        image.addEventListener("error", () => settle(false));
    }

    /**
     * Whether [entry]'s design drawing stays hidden under what is shown. Not recomputed on the
     * design lane: the mark records what a slot would be covered by, which must survive a lane that
     * covers nothing.
     */
    private syncStandIn(entry: Entry): void {
        const lane = this.lane();
        if (lane === "design") return;
        const shown = entry.pictures.get(lane);
        entry.target.classList.toggle("cp-page-replaced", shown?.ok === true);
    }

    // ---- lanes and filters ---------------------------------------------------

    private lane(): Lane {
        return laneOf(this.lanes.find((input) => input.checked)?.value);
    }

    private baseline(): Baseline {
        return baselineOf(this.baselines.find((input) => input.checked)?.value);
    }

    /** Whether this page carries the sibling catalog's renders at all. */
    private hasParallel(): boolean {
        return (
            this.sources.has("parallel") ||
            this.nodes.some((entry) => entry.pictures.has("parallel"))
        );
    }

    private applyLane(): void {
        const lane = this.lane();
        const baseline = this.baseline();
        if (needsRenders(lane, baseline)) this.armSource("code");
        if (needsParallel(lane, baseline)) this.armSource("parallel");
        for (const [name, on] of Object.entries(stageState(lane, baseline))) {
            this.stage.classList.toggle(name, on);
        }
        this.stage.classList.toggle(
            DIFF_ALL_CLASS,
            showsEveryBadge(baseline, this.diffHeld),
        );
        for (const entry of this.nodes) this.syncStandIn(entry);
        if (baseline !== "off") this.score();
    }

    /**
     * A change to what the sheet shows. Both axes range over the same sources, so switching onto
     * the current baseline swaps them ({@link baselineAfterLane}) and keeps the pair.
     */
    private changeLane(): void {
        const next = this.lane();
        const baseline = baselineAfterLane(
            this.shown,
            next,
            this.baseline(),
            this.hasParallel(),
        );
        this.shown = next;
        this.selectBaseline(baseline);
        this.syncBaselines();
        this.applyLane();
    }

    private selectBaseline(baseline: Baseline): void {
        for (const input of this.baselines)
            input.checked = input.value === baseline;
    }

    /**
     * Hide the baseline the sheet already shows rather than disabling it: comparing a source with
     * itself is always 0.0%, and most catalogs have no sibling.
     */
    private syncBaselines(): void {
        const lane = this.lane();
        const hasParallel = this.hasParallel();
        for (const input of this.baselines) {
            const allowed = allowsBaseline(
                lane,
                baselineOf(input.value),
                hasParallel,
            );
            const label = input.closest("label") ?? input;
            label.toggleAttribute("hidden", !allowed);
            input.disabled = !allowed;
        }
    }

    /**
     * Hold a `Diff against` control to see every badge; release to get the sheet back. Goes through
     * {@link applyLane} so the diff-axis gate is stated once and a late release can't strand the
     * class.
     */
    private holdDiff(held: boolean): void {
        if (this.diffHeld === held) return;
        this.diffHeld = held;
        this.applyLane();
    }

    /**
     * Node outlines are off by default (the sheet is the content). The legend follows the toggle so
     * it only explains marks on screen.
     */
    private applyOutlines(): void {
        const toggle = this.outlinesToggle;
        if (!toggle) return;
        this.stage.classList.toggle("cp-page-outlines-on", toggle.checked);
        if (this.legend) this.legend.hidden = !toggle.checked;
    }

    private applyUnlinked(): void {
        const toggle = this.unlinkedToggle;
        if (!toggle) return;
        this.stage.classList.toggle("cp-page-unlinked-only", toggle.checked);
        const outlines = this.outlinesToggle;
        if (
            outlines &&
            outlinesAfterUnlinked(toggle.checked, outlines.checked) !==
                outlines.checked
        ) {
            outlines.checked = true;
            this.applyOutlines();
        }
        this.syncFocusability();
    }

    /**
     * Muted overlays are removed from the tab order and accessibility tree too; CSS alone leaves
     * them focusable.
     */
    private syncFocusability(): void {
        const unlinkedOnly = Boolean(this.unlinkedToggle?.checked);
        for (const spot of this.overlays) {
            if (isInert(unlinkedOnly, spot.hasAttribute("data-cp-gap"))) {
                spot.setAttribute("tabindex", "-1");
                spot.setAttribute("aria-hidden", "true");
            } else {
                spot.removeAttribute("tabindex");
                spot.removeAttribute("aria-hidden");
            }
        }
    }

    // The diff axis. When the design is half the pair, its side is this page's SVG cropped to the
    // node (already on the page, covers every rendered node, and compares at this slot's size and
    // layout). When both halves are catalogs, two rasters of the same cell are scored directly.

    /**
     * One raster of the sheet, cropped per node, rather than cloning and encoding the whole export
     * per node (tens of copies of a large SVG).
     */
    private rasteriseSheet(): Promise<Sheet | null> {
        if (this.sheetRaster) return this.sheetRaster;
        const view = this.svg.viewBox?.baseVal;
        const sized = view
            ? sheetSize({ width: view.width, height: view.height })
            : null;
        if (!sized) return (this.sheetRaster = Promise.resolve(null));
        const clone = this.svg.cloneNode(true) as SVGSVGElement;
        clone.setAttribute("width", String(sized.width));
        clone.setAttribute("height", String(sized.height));
        clone.removeAttribute("style");
        const markup = new XMLSerializer().serializeToString(clone);
        // A `data:` URL rather than a blob: nothing to leak, built from the page's own markup.
        const url = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(markup)}`;
        this.sheetRaster = new Promise<Sheet | null>((resolve) => {
            const image = new Image();
            image.onload = () =>
                resolve({
                    image,
                    width: image.naturalWidth || sized.width,
                    height: image.naturalHeight || sized.height,
                });
            // A sheet that cannot be rasterised (a font it cannot reach, markup a browser refuses)
            // scores nothing rather than scoring wrongly.
            image.onerror = () => resolve(null);
            image.src = url;
        });
        return this.sheetRaster;
    }

    /**
     * The node's drawing, cropped from the sheet raster. Known limit: anything the design drew
     * behind or across the node is included, while our render has only the component. Small on a
     * definition sheet (flat ground), larger on composed screens; isolating nodes would cost a
     * clone each.
     */
    private async sheetImage(
        target: SVGElement,
    ): Promise<HTMLCanvasElement | null> {
        const sheet = await this.rasteriseSheet();
        if (!sheet) return null;
        const crop = cropFor(sheet, rectOf(this.svg), nodeBoxOf(target));
        if (!crop) return null;
        const canvas = document.createElement("canvas");
        canvas.width = Math.max(1, Math.round(crop.width));
        canvas.height = Math.max(1, Math.round(crop.height));
        const context = canvas.getContext("2d");
        if (!context) return null;
        // White, matching what the scorer composites our render onto, so transparent design nodes
        // don't compare as black. Kept despite the scorer's two grounds: the crop carries opaque
        // sheet furniture around the node, which no ground changes, while the render's transparent
        // surround changes with each; flattening keeps the lanes agreeing on what the reference is.
        context.fillStyle = "#fff";
        context.fillRect(0, 0, canvas.width, canvas.height);
        context.drawImage(
            sheet.image,
            crop.left,
            crop.top,
            crop.width,
            crop.height,
            0,
            0,
            canvas.width,
            canvas.height,
        );
        return canvas;
    }

    /**
     * Renders are lazy, so most aren't decoded when the lane opens. Each comparison waits for its
     * own image; failures stay retryable.
     */
    private decoded(image: HTMLImageElement): Promise<HTMLImageElement> {
        if (image.complete && image.naturalWidth > 0)
            return Promise.resolve(image);
        return new Promise((resolve, reject) => {
            image.addEventListener("load", () => resolve(image));
            image.addEventListener("error", () =>
                reject(new Error("render unavailable")),
            );
            // Lazy images only load on approach; request it explicitly so the lane covers the whole
            // sheet.
            if (image.loading === "lazy") image.loading = "eager";
        });
    }

    private badgeElement(overlay: HTMLElement): HTMLElement {
        let badge = overlay.querySelector<HTMLElement>(".cp-page-score");
        if (!badge) {
            badge = document.createElement("span");
            badge.className = "cp-page-score";
            overlay.appendChild(badge);
        }
        return badge;
    }

    /**
     * One side of the comparison, decoded: the design's is cut from the sheet, ours and the
     * sibling's are server rasters.
     */
    private async pictureFor(
        entry: Entry,
        source: Source,
    ): Promise<HTMLImageElement | HTMLCanvasElement | null> {
        if (source === "design") return this.sheetImage(entry.target);
        const picture = entry.pictures.get(source);
        // No render, or one the server could not produce: there is nothing to compare against, and
        // saying so beats printing a number that means "absent" rather than "apart".
        if (!picture || picture.image.hidden) return null;
        return this.decoded(picture.image);
    }

    private score(): void {
        // Read at score time, not install: `format-compare.js` loads after the components bundle,
        // so a handle cached at upgrade would be `null`.
        const compare = compareApi();
        if (!compare) return;
        const lane = this.lane();
        const baseline = this.baseline();
        if (baseline === "off") return;
        for (const entry of this.nodes) {
            const id = entry.overlay.getAttribute("data-cp-node") ?? "";
            const key = scoreKey(lane, baseline, id);
            if (this.scoredNodes.has(key)) continue;
            // Both halves have to be here. A slot the sibling does not draw scores nothing rather
            // than scoring our render against a blank and reporting the absence as drift.
            if (!entry.pictures.get(lane) && lane !== "design") continue;
            if (!entry.pictures.get(baseline) && baseline !== "design")
                continue;
            this.scoredNodes.add(key);
            void this.scoreNode(entry, key, lane, baseline, compare);
        }
    }

    private async scoreNode(
        entry: Entry,
        key: string,
        lane: Lane,
        baseline: Baseline,
        compare: CompareApi,
    ): Promise<void> {
        // The badge is the whole readout: per-node diff maps would clutter the sheet; the full map
        // is one click away.
        const badge = this.badgeElement(entry.overlay);
        badge.textContent = "…";
        try {
            const [reference, candidate] = await Promise.all([
                this.pictureFor(entry, baseline as Source),
                this.pictureFor(entry, lane),
            ]);
            if (!reference || !candidate) throw new Error("not scoreable");
            const result = await compare.scoreImages(reference, candidate);
            const read = badgeFor(result);
            badge.textContent = read.text;
            badge.title = read.title;
            badge.setAttribute("data-cp-score", read.band);
            // The band goes on the node too, so the diff lane at rest still shows where to look
            // while badges only appear on demand.
            entry.overlay.setAttribute("data-cp-score", read.band);
            entry.overlay.setAttribute(
                "data-cp-score-value",
                read.value.toFixed(1),
            );
        } catch {
            badge.textContent = "—";
            badge.setAttribute("data-cp-score", "none");
            entry.overlay.setAttribute("data-cp-score", "none");
            badge.title = "not scoreable";
            // Retryable: a render that had not arrived yet is the likeliest reason to be here.
            this.scoredNodes.delete(key);
        }
    }

    // Describing a node follows the pointer (a strip under a tall sheet was out of view). The tip
    // clones the audit list's own row, so there is one description and its `href` never passes
    // through JavaScript.

    private rowFor(nodeId: string): HTMLElement | null {
        if (!this.list) return null;
        for (const row of this.list.querySelectorAll<HTMLElement>(
            "[data-cp-node]",
        )) {
            if (row.getAttribute("data-cp-node") === nodeId) return row;
        }
        return null;
    }

    private describe(nodeId: string): void {
        this.described = nodeId;
        for (const spot of this.overlays) {
            spot.classList.toggle(
                "cp-page-selected",
                spot.getAttribute("data-cp-node") === nodeId,
            );
        }
        const tip = this.tip;
        if (!tip) return;
        const row = nodeId ? this.rowFor(nodeId) : null;
        if (!row) {
            tip.hidden = true;
            tip.textContent = "";
            return;
        }
        tip.textContent = "";
        const clone = row.cloneNode(true) as HTMLElement;
        clone.classList.add("cp-page-tip-card");
        // A second element carrying the same `data-cp-node` would answer `rowFor` on the next hover
        // and the tip would start cloning itself.
        clone.removeAttribute("data-cp-node");
        tip.appendChild(clone);
        tip.hidden = false;
    }

    private moveTip(clientX: number, clientY: number): void {
        const tip = this.tip;
        if (!tip || tip.hidden) return;
        const at = tipAt(rectOf(this.stage), rectOf(tip), {
            x: clientX,
            y: clientY,
        });
        tip.style.left = `${at.left}px`;
        tip.style.top = `${at.top}px`;
    }

    /** Keyboard readers get the same tip, parked at the node. */
    private parkTipAt(spot: HTMLElement): void {
        const tip = this.tip;
        if (!tip || tip.hidden) return;
        const stage = rectOf(this.stage);
        const rect = rectOf(spot);
        this.moveTip(rect.left + rect.width / 2, rect.top + rect.height);
        if (
            rect.top + rect.height - stage.top + rectOf(tip).height >
            stage.height
        ) {
            this.moveTip(
                rect.left + rect.width / 2,
                rect.top - rectOf(tip).height,
            );
        }
    }

    private hideTip(): void {
        const tip = this.tip;
        if (!tip) return;
        tip.hidden = true;
        tip.textContent = "";
        this.described = null;
        for (const spot of this.overlays)
            spot.classList.remove("cp-page-selected");
    }

    /** Hovering a list row highlights its node and vice versa. */
    private pair(nodeId: string, on: boolean): void {
        for (const element of this.root.querySelectorAll("[data-cp-node]")) {
            if (element.getAttribute("data-cp-node") === nodeId)
                element.classList.toggle("cp-page-active", on);
        }
    }

    private wireNodes(): void {
        for (const spot of this.overlays) {
            // Clicking navigates: the overlay is an anchor, so ordinary, middle and modifier clicks
            // work. This handler only redirects plain left clicks in the diff lane to the
            // component's full comparison, by clicking a second server-built anchor.
            this.on(spot, "click", (event) => {
                const click = event as MouseEvent;
                if (this.baseline() === "off") return;
                if (click.defaultPrevented || click.button !== 0) return;
                if (
                    click.metaKey ||
                    click.ctrlKey ||
                    click.shiftKey ||
                    click.altKey
                )
                    return;
                const out =
                    spot.querySelector<HTMLElement>(".cp-page-diff-link");
                if (!out) return;
                click.preventDefault();
                out.click();
            });
        }

        for (const element of this.root.querySelectorAll<HTMLElement>(
            "[data-cp-node]",
        )) {
            const id = element.getAttribute("data-cp-node") ?? "";
            // Pointing (or keyboard focus) describes a component without committing to it.
            this.on(element, "mouseenter", (event) => {
                const move = event as MouseEvent;
                this.pair(id, true);
                this.describe(id);
                this.moveTip(move.clientX, move.clientY);
            });
            this.on(element, "mousemove", (event) => {
                const move = event as MouseEvent;
                this.moveTip(move.clientX, move.clientY);
            });
            this.on(element, "mouseleave", () => {
                this.pair(id, false);
                // The tip clears on leave, since it sits over the sheet.
                if (this.described === id) this.hideTip();
            });
            this.on(element, "focus", () => {
                this.pair(id, true);
                this.describe(id);
                this.parkTipAt(element);
            });
            this.on(element, "blur", () => {
                this.pair(id, false);
                if (this.described === id) this.hideTip();
            });
        }

        // Escape clears the selection from anywhere. `<cp-page-zoom>` defers to this while
        // `.cp-page-selected` is on the sheet, so one press clears selection and the next unwinds
        // zoom.
        this.on(this.root, "keydown", (event) => {
            const key = event as KeyboardEvent;
            if (key.key !== "Escape" || !this.described) return;
            const spot = this.overlays.find(
                (candidate) =>
                    candidate.getAttribute("data-cp-node") === this.described,
            );
            this.hideTip();
            // Return focus to the selected node only while it is still exposed; the coverage filter
            // may have made it `aria-hidden`.
            if (spot && !spot.hasAttribute("aria-hidden")) spot.focus();
        });
    }

    /**
     * With the diff axis on, a node click opens the component's full comparison (whose source
     * picker covers the sibling), via a server-built anchor.
     */
    private armDiffLinks(): void {
        const source = this.diffLinkSource;
        if (!source) return;
        for (const link of source.content.querySelectorAll<HTMLElement>(
            ".cp-page-diff-link",
        )) {
            const entry = this.byId.get(
                link.getAttribute("data-cp-node") ?? "",
            );
            if (!entry) continue;
            entry.overlay.appendChild(link);
        }
        source.remove();
        this.diffLinkSource = null;
    }

    /** Put the URL's state onto the controls without applying it; the caller owns the order. */
    private hydrate(): void {
        const url = urlState();
        if (!url) return;
        const state = pageStateFrom(
            {
                lane: url.get("lane"),
                baseline: url.get("baseline"),
                outlines: url.get("outlines"),
                unlinked: url.get("unlinked"),
            },
            this.hasParallel(),
        );
        for (const input of this.lanes)
            input.checked = laneOf(input.value) === state.lane;
        this.selectBaseline(state.baseline);
        if (this.outlinesToggle) this.outlinesToggle.checked = state.outlines;
        if (this.unlinkedToggle) this.unlinkedToggle.checked = state.unlinked;
    }

    /**
     * Write the four controls into the address bar, pushed (each is a discrete choice). The
     * press-and-hold badge reveal is a gesture, not state, so it isn't recorded.
     */
    private syncUrl(): void {
        urlState()?.push(
            pageParams({
                lane: this.lane(),
                baseline: this.baseline(),
                outlines: Boolean(this.outlinesToggle?.checked),
                unlinked: Boolean(this.unlinkedToggle?.checked),
            }),
        );
    }

    private wireControls(): void {
        if (this.outlinesToggle)
            this.on(this.outlinesToggle, "change", () => {
                this.applyOutlines();
                this.syncUrl();
            });
        if (this.unlinkedToggle)
            this.on(this.unlinkedToggle, "change", () => {
                // After `applyUnlinked`, which may turn the outlines on with it — the URL has to
                // describe both, or Back would restore a filter without the outlines it filters.
                this.applyUnlinked();
                this.syncUrl();
            });
        for (const input of this.lanes) {
            this.on(input, "change", () => {
                // Likewise after `changeLane`, which can hand the baseline the lane just vacated.
                this.changeLane();
                this.syncUrl();
            });
        }
        for (const input of this.baselines) {
            this.on(input, "change", () => {
                this.applyLane();
                this.syncUrl();
            });
        }
        // The hold works on every scoring control: pressed via the label (pointer) or input
        // (keyboard), and released from `window`, the only place that hears a pointer let go
        // elsewhere; otherwise a drag off the button would latch the sheet on.
        for (const input of this.baselines) {
            if (baselineOf(input.value) === "off") continue;
            const grip = input.closest("label") ?? input;
            this.on(grip, "pointerdown", () => this.holdDiff(true));
            this.on(input, "keydown", (event) => {
                const key = (event as KeyboardEvent).key;
                if (key === " " || key === "Enter") this.holdDiff(true);
            });
            this.on(input, "keyup", () => this.holdDiff(false));
            // A control that is only held loses its grip when focus leaves mid-press.
            this.on(input, "blur", () => this.holdDiff(false));
        }
        this.on(window, "pointerup", () => this.holdDiff(false));
        this.on(window, "pointercancel", () => this.holdDiff(false));
        // Opening the audit list can change the stage container's height on short viewports, so
        // re-measure.
        if (this.disclosure)
            this.on(this.disclosure, "toggle", () => this.measure());
    }
}

declare global {
    interface HTMLElementTagNameMap {
        "cp-design-page": DesignPage;
    }
}
