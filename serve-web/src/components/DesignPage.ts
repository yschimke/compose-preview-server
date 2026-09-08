// `<cp-design-page>` — a whole page of the design file, inlined as SVG, with this catalog's renders
// standing in for the design's own drawing of the components it implements.
// Replaces `assets/design-page.js`.
//
// THE SVG IS THE GEOMETRY, AND THAT IS THE WHOLE DESIGN
//
// The screen backdrop this replaced was a flat PNG, so its manifest had to carry a rectangle per
// component and this file did no measuring at all. An inlined SVG is a document: the node is right
// there, `data-node-id` names it, and its box is whatever the browser says it is. So the manifest
// carries no geometry, and everything positional here is measured rather than declared. That is
// strictly more accurate — a Figma export box includes effect bleed, so a recorded rectangle and the
// drawn shape disagree by a few pixels on anything with a shadow — and it is what makes the swap
// land exactly on the shape it replaces.
//
// Positions are written as percentages of the stage, so a resize only has to re-measure rather than
// re-place, and a stale measurement degrades into a small offset rather than a wrong corner.
//
// Renders nothing of its own; `serve.css` hides the tag. The decisions live next door:
// `design/ink.ts` (fitting our drawn pixels onto the design's drawn box), `design/score.ts` (what a
// diff badge says), `design/geometry.ts` (slots, crops, the tip) and `design/lanes.ts` (the three
// lanes and the two filters).

import { ControllerElement, customElement } from "../controllerElement.js";
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
import { fitInk, inkFrom, sampleSize, type InkBounds } from "../design/ink.js";
import {
    DIFF_ALL_CLASS,
    allowsBaseline,
    baselineAfterLane,
    baselineOf,
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
 * One catalog's render of a node, in that node's slot.
 *
 * A slot can hold TWO of these now — this catalog's and the `compareWith` sibling's — because the
 * diff axis scores a pair and either side of that pair may be the one on screen. They are separate
 * elements rather than one element whose `src` is swapped: a swapped `src` re-fetches and re-decodes
 * on every flip, loses the ink measurement each image needs its own of, and would put a URL through
 * JavaScript as a string (CodeQL `js/xss-through-dom`), which is exactly what the server-built
 * `<template>` exists to avoid.
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
 * A DESIGN NODE's box, which is not the same question as an element's box.
 *
 * `getBoundingClientRect()` ignores `clip-path`, so a node whose export keeps an oversized shape
 * inside it by clipping — a placeholder's shimmer sweep is the case that found this — measures as
 * the sweep rather than as the component, and the render fitted into that slot lands on the page as
 * a blob several times the size of the thing it stands for (issue #4323). `paintedRect` walks the
 * clips; it degrades to exactly this rect when there are none to walk.
 *
 * A node clipped away to nothing comes back as a zero-area box rather than as its unclipped rect,
 * which is what the caller already spells "missing as far as the sheet is concerned".
 */
const EMPTY_BOX: Box = { left: 0, top: 0, width: 0, height: 0 };

const nodeBoxOf = (element: SVGElement): Box =>
    paintedRect(element, domGeometry) ?? EMPTY_BOX;

/**
 * Where each source's renders are parked until something asks for them.
 *
 * `design` is absent on purpose and always will be: the design's own drawing is the inlined SVG,
 * already on the page, and the one source that needs no template because it needs no fetching.
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
     * The zooming layer, transformed by `<cp-page-zoom>`: the export, the overlays and the renders
     * inside them, moved by ONE transform so nothing can come unstuck from the shape it marks.
     * Everything is measured against this rather than the stage, because it is the box the overlays'
     * percentages are relative to — and, being transformed, their containing block as well.
     *
     * Named for the LAYER rather than its class, because "canvas" in this file also means a
     * `<canvas>` element — the one the ink fit rasterises a render into.
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
     * Scored once per PAIRING, keyed by {@link scoreKey}. The numbers cannot move without the
     * renders moving, and re-scoring on every flip back would redo dozens of rasterise-and-count
     * passes for an answer already on screen — while keying on the node alone would answer "how far
     * from wear-m3?" with the number measured against Figma. A pairing that FAILED is left unscored,
     * so re-entering it retries.
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
                // Named by the manifest, absent from the export — a layer the design tool flattened
                // on the way out. Say so on the element rather than dropping it: the row in the list
                // still shows the mapping, and `[data-cp-missing]` is what a test (or a person
                // wondering where their shape went) can look for.
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

        this.shown = this.lane();
        this.applyOutlines();
        this.applyUnlinked();
        this.syncBaselines();
        this.applyLane();
        this.measure();

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
     * Built by COMPARING attribute values rather than with `querySelector` on an attribute value,
     * which would need escaping. A node id is text from a design file, and interpolating it into a
     * selector has the same shape as an HTML injection.
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
                // A zero-area node is missing as far as the sheet is concerned; a zero-area LAYER
                // means the page is not laid out yet, and the previous placement is left alone for
                // the observer to correct.
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
     * The tight bounds of an image's non-transparent pixels — the browser-side twin of
     * `ServeThumbCrop.pngAlphaBounds`, down to the alpha threshold.
     *
     * Null when there is nothing to measure with: no canvas, or a tainted one (a cross-origin
     * render). Both fall back to the plain `contain` this lane had before.
     */
    private inkBounds(image: HTMLImageElement): InkBounds | null {
        const sample = sampleSize(image.naturalWidth, image.naturalHeight);
        if (!sample) return null;
        const canvas = document.createElement("canvas");
        canvas.width = sample.width;
        canvas.height = sample.height;
        const context = canvas.getContext("2d", { willReadFrequently: true });
        if (!context) return null;
        context.drawImage(image, 0, 0, sample.width, sample.height);
        try {
            const data = context.getImageData(
                0,
                0,
                sample.width,
                sample.height,
            ).data;
            return inkFrom(
                data,
                sample,
                image.naturalWidth,
                image.naturalHeight,
            );
        } catch {
            return null;
        }
    }

    private takeInk(entry: Entry, picture: Picture): void {
        const read = () => {
            picture.ink = this.inkBounds(picture.image);
            // Just this slot. The node's box hasn't moved — only what we now know about the image
            // has — and re-running the whole measure per arriving render is dozens of layout reads
            // for one placement.
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
     * A source's renders are served inside an inert `<template>` and adopted the first time a
     * pairing names that source — as the lane on the sheet, or as the baseline it is scored
     * against.
     *
     * `code` is normally adopted on first paint, since that is the lane the page opens on; the
     * images carry `loading="lazy"`, which is what keeps a tall sheet from asking the daemon for
     * every node at once. `parallel` is adopted only when the reader asks for the sibling, and that
     * gate is the point: those images come off ANOTHER catalog's daemon, and a page that warmed
     * them speculatively would charge every reader of every sheet for a comparison almost none of
     * them opened.
     *
     * A template rather than a `data-src` swap, deliberately: template content is inert, so the
     * browser parses it and loads none of its images until it is adopted — and it keeps every URL
     * server-built and server-escaped. Reading a URL out of the DOM and assigning it to `img.src` is
     * a taint path (CodeQL `js/xss-through-dom`), and not having the sink beats validating it.
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
     * The design's drawing is hidden ONLY once the picture the reader asked for has actually
     * arrived, and comes back if it never does.
     *
     * Hiding on adoption instead leaves an empty slot for any render the server can't produce: a
     * preview that throws, a daemon that falls over, a 404. There is no "untick to get the sheet
     * back" control, so a failed render would be a hole where the whole point is that something is
     * in the slot.
     *
     * Recomputed from the SHOWN source rather than latched on the first image to load, because a
     * slot can now hold two of them: with our render broken and the sibling's fine, latching would
     * hide the design's drawing on the sibling's arrival and leave the `code` lane showing nothing
     * at all.
     *
     * Also hides a failed `<img>` itself, or the browser's broken-image glyph would sit on top of
     * the drawing just restored.
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
     * Whether the design's own drawing of [entry] stays hidden under what is being shown.
     *
     * Not recomputed on the design's own lane, and that is not an oversight: nothing stands in
     * there, `cp-page-hide-design` is off, and the sheet's drawing is back whatever this says. The
     * mark is what a slot WOULD be covered by, so clearing it on a lane that covers nothing would
     * lose the record of which slots have a stand-in at all.
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
     * A change to what the sheet SHOWS, which may move the baseline with it.
     *
     * The two axes range over the same three sources, so flipping onto the one you were scoring
     * against is a SWAP rather than a contradiction — {@link baselineAfterLane} hands the baseline
     * the lane just vacated, and the pair the reader was looking at survives the flip.
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
     * Hides the baseline the sheet is already showing, rather than disabling it in place.
     *
     * A control that can never do anything is worse than one that is not there: "diff ours against
     * ours" is 0.0% in every slot by construction, and a permanently dead third button is what the
     * catalogs with no sibling — which is most of them — would be left looking at. Hidden, the group
     * always offers exactly the comparisons that exist.
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
     * Hold a `Diff against` control to see every badge; let go to get the sheet back.
     *
     * Re-entered through {@link applyLane} rather than toggling the class here, so the gate on the
     * diff axis being on at all is stated once, and a release that arrives after the reader has
     * already switched it off cannot leave the class behind.
     */
    private holdDiff(held: boolean): void {
        if (this.diffHeld === held) return;
        this.diffHeld = held;
        this.applyLane();
    }

    /**
     * The resting layer of colour over every node: off by default, because the sheet is the content
     * and thirty-eight coloured rectangles are an answer to a question nobody asked yet. The legend
     * only explains marks that are actually on screen, so it follows the toggle rather than standing
     * above an unmarked sheet naming four colours it isn't showing.
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
     * A muted overlay is also taken out of the tab order and the accessibility tree. CSS alone can't
     * do this: `opacity: 0` + `pointer-events: none` still leaves a control focusable, so a keyboard
     * user could tab onto an invisible rectangle — no focus ring, no indication of where they are.
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

    // ---- the diff axis -------------------------------------------------------
    //
    // When the DESIGN is one half of the pair, its side is this page's own SVG cropped to the node —
    // not the component's imported reference raster. Both are defensible, but only one is on the
    // page already: cropping the export needs no server round trip, no manifest field, and covers
    // every node that has a render rather than only those with an imported reference. It also
    // answers the question the page actually poses, which is about THIS slot: how far is our pixel
    // from the design's pixel, here, at this size, in the layout the designer drew.
    //
    // When both halves are catalogs — ours against the `compareWith` sibling — no crop is involved
    // at all: two rasters of the same kit cell, scored directly. That comparison is the one the
    // export cannot make, because the two catalogs implement one design and the design has no
    // opinion about which of them is right.

    /**
     * ONE raster of the sheet, cropped per node — not one clone of the sheet per node.
     *
     * The first cut cloned, serialised and URI-encoded the whole export for every scoreable node. On
     * this catalog's own Shape page that is 858 KB × 35 nodes, so entering the lane built well over
     * 100 MB of transient markup before a single comparison settled. One raster costs one
     * serialisation and one decode, and every crop is a `drawImage` out of it.
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
        // A `data:` URL rather than a blob: nothing is allocated to leak, and the string is one this
        // element just built out of the page's own markup rather than anything a URL could be read
        // from.
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
     * The node's own drawing, cut out of that raster.
     *
     * KNOWN LIMIT: the crop is of the sheet, so whatever the design drew BEHIND or across the node —
     * a page backdrop, an overlapping neighbour — is in the reference while our render carries only
     * the component. On a definition sheet (a grid of component sets on a flat ground) that is a
     * near-uniform background against the scorer's own white, which is small; on a composed screen it
     * would not be. Isolating the node would mean a clone per node, which is the cost the single
     * raster exists to avoid.
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
        // White, to match what the scorer composites OUR render onto. Without it a transparent
        // design node would be compared as black and every score would be wrong in the same
        // direction.
        //
        // This stays even though the scorer now composites on two grounds, because THIS crop is not
        // an isolated node: `rasteriseSheet` rasterises a clone of the whole sheet, so the crop
        // carries whatever the design drew behind and around the target — on a definition sheet, an
        // opaque ground and its neighbouring cells. That furniture is opaque, so no ground moves it,
        // while the render's surround is transparent and every ground does. Handing the crop over
        // unflattened would make the black pass compare light sheet furniture against a black
        // surround and charge the difference to the component, which `scoreOnEveryGround`'s
        // minimum would then take as the answer.
        //
        // The scorer's own opacity gate catches this even without the fill — an opaque reference
        // never earns a second ground — but normalising here keeps the two lanes agreeing about
        // what the reference *is* rather than relying on that gate to notice.
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
     * A render is `loading="lazy"`, so on a tall sheet most of them have not been fetched — let
     * alone decoded — when the lane is entered. Scoring an undecoded image measures a blank, so each
     * comparison waits for its own image and a failure is left RETRYABLE rather than burned into a
     * permanent dash.
     */
    private decoded(image: HTMLImageElement): Promise<HTMLImageElement> {
        if (image.complete && image.naturalWidth > 0)
            return Promise.resolve(image);
        return new Promise((resolve, reject) => {
            image.addEventListener("load", () => resolve(image));
            image.addEventListener("error", () =>
                reject(new Error("render unavailable")),
            );
            // `loading="lazy"` only fetches on approach, and a node far below the fold may never be
            // approached. Asking for it explicitly is what makes the lane answer for the whole sheet
            // rather than only the part that has been scrolled past.
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
     * One side of the comparison, decoded and ready to score.
     *
     * The three sources are not the same KIND of thing and this is where that stops mattering: the
     * design's is cut out of the sheet already on the page, ours and the sibling's are rasters the
     * server produced. Above this line a pairing is just two sources; below it, each knows how to
     * become pixels.
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
        // Read at SCORE time, not at install. `format-compare.js` publishes the global from its own
        // script tag, and on this page that tag comes after the components bundle — so an element
        // that cached the handle when it upgraded would cache `null` and the diff axis would score
        // nothing, silently, with every badge stuck on a dash. Reading it here costs one property
        // lookup per entry into the axis and cannot be got wrong by moving a script.
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
        // The badge is the whole readout, deliberately. A per-node diff MAP was the obvious next
        // thing and is the wrong thing here: thirty-eight magenta thumbnails at slot size is the
        // annotated sheet this page was just rescued from. The number triages; the map is one click
        // away, at a size where it can be read.
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
            // The band on the NODE as well as on the badge. The badges only appear where the reader
            // is pointing now (see {@link holdDiff}), so without this the diff lane at rest would be
            // an unmarked sheet — the triage would be there and invisible. Colour on the node
            // survives the badge being hidden, which is the whole point of hiding it: the sheet says
            // where to look, the badge says how far.
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

    // ---- describing a node ---------------------------------------------------
    //
    // Describing follows the POINTER rather than landing in a strip under the sheet. The strip was
    // in the wrong place: a specimen sheet is taller than the fold, so on the shapes two thirds down
    // the page the answer appeared somewhere the reader could not see it while pointing.
    //
    // Its content is the audit list's own row, CLONED — one server-built description of a node
    // instead of two that can disagree, and the row's `href` never passes through JavaScript as a
    // string, so the tip cannot become the taint path `armRenders` avoids for the same reason.

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

    /**
     * A keyboard reader gets the same tip, parked at the node instead of at a pointer that isn't
     * there. Without this, tabbing the sheet would light the outline and say nothing.
     */
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

    /**
     * Hovering a row in the list highlights its node on the sheet, and vice versa — the cheapest way
     * to answer "which one is that?" on a sheet with thirty-five near-identical silhouettes.
     */
    private pair(nodeId: string, on: boolean): void {
        for (const element of this.root.querySelectorAll("[data-cp-node]")) {
            if (element.getAttribute("data-cp-node") === nodeId)
                element.classList.toggle("cp-page-active", on);
        }
    }

    private wireNodes(): void {
        for (const spot of this.overlays) {
            // Clicking GOES. The overlay is an anchor, so the browser already does the right thing
            // for the ordinary case, for the middle click and for a modifier click — this handler
            // exists only to redirect the diff lane, where the destination is the component's full
            // comparison rather than its preview. Redirected by clicking a second server-built
            // anchor, never by assigning a URL, and only for a plain left click so "open in a new
            // tab" keeps working.
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
            // Pointing DESCRIBES. Sweeping the sheet describes several components in one pass
            // without committing to any of them — the reading motion the page is for. Keyboard focus
            // does exactly the same thing, so tabbing the sheet reads like sweeping it.
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
                // Unlike the strip it replaced, the tip DOES clear on the way out — it sits over the
                // sheet, so leaving it up would cover the very drawing the reader moved on to look
                // at.
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

        // Escape clears the selection from anywhere on the page — including from inside the
        // disclosure, where a reader who arrived by keyboard is most likely to be. `<cp-page-zoom>`
        // watches for the same key and defers to this while `.cp-page-selected` is on the sheet, so
        // one press unwinds the selection and the next unwinds the zoom.
        this.on(this.root, "keydown", (event) => {
            const key = event as KeyboardEvent;
            if (key.key !== "Escape" || !this.described) return;
            const spot = this.overlays.find(
                (candidate) =>
                    candidate.getAttribute("data-cp-node") === this.described,
            );
            this.hideTip();
            // Focus goes back to the node that was selected — but only while that node is still
            // exposed. The coverage filter takes the nodes it mutes out of the accessibility tree,
            // and a selection survives the filter being switched on, so the node Escape wants to
            // hand focus back to may by then be `aria-hidden` and 12% opaque. Focusing it would drop
            // a keyboard or screen-reader user onto an element the page has deliberately hidden;
            // leaving focus where it is (on the filter they just used) is the honest alternative.
            if (spot && !spot.hasAttribute("aria-hidden")) spot.focus();
        });
    }

    /**
     * While the diff axis is on, a node's click leaves for the component's full comparison rather
     * than selecting in place — the number on the sheet is the invitation, and the diff map,
     * triptych and wipe are what it opens onto. That viewer carries its own source picker, so the
     * sibling comparison is one control away from where this lands. Clicking a server-built anchor,
     * never assigning a URL.
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

    private wireControls(): void {
        if (this.outlinesToggle)
            this.on(this.outlinesToggle, "change", () => this.applyOutlines());
        if (this.unlinkedToggle)
            this.on(this.unlinkedToggle, "change", () => this.applyUnlinked());
        for (const input of this.lanes) {
            this.on(input, "change", () => this.changeLane());
        }
        for (const input of this.baselines) {
            this.on(input, "change", () => this.applyLane());
        }
        // The hold, on every scoring control rather than on one lane's own. Its label is the hit
        // target a pointer actually lands on (the radio itself is visually replaced), while the
        // keyboard reaches the input — so the press is taken from both and the release from
        // `window`, which is the only place that hears a pointer let go outside the control it went
        // down on. Without that last one a drag off the button would latch the sheet on, which is
        // the exact failure mode a press-and-hold has to not have.
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
        // Opening the audit list changes nothing about the sheet, but it does change how tall the
        // stage's container is on a short viewport, and every overlay is placed off a measured box.
        if (this.disclosure)
            this.on(this.disclosure, "toggle", () => this.measure());
    }
}

declare global {
    interface HTMLElementTagNameMap {
        "cp-design-page": DesignPage;
    }
}
