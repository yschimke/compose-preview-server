// `<cp-spec-compare>`: the viewer's design-spec comparison views (Spec, Diff, Triptych, Slider), so
// a visitor can compare without leaving the viewer and its overrides. Every surface comes from ONE
// normalisation pass, so all views share a pixel space and a reference exported at another scale
// still lines up.
//
// Renders nothing itself: panels, canvases and buttons are server-rendered; `serve.css` hides the
// tag. Decisions live in `spec/views.ts` (who picks the view), `spec/verdict.ts` (chip and readout
// text), `spec/wipe.ts` (the seam), `spec/loupe.ts` (magnifier geometry), `spec/align.ts` (matched
// boxes) and `dom/sameOrigin.ts` (what may reach a canvas).

import { ControllerElement, customElement } from "../controllerElement.js";
import { whenParsed } from "../dom/whenParsed.js";
import { compareApi, type NormalisedPair } from "../compare/api.js";
import { readingAt, summarise, type Offset } from "../spec/pick.js";
import { alignedBoxes, offsetAt, type AlignedBox } from "../spec/align.js";
import { crosshairAt, placeAt, windowAt } from "../spec/loupe.js";
import { urlState } from "../urlState.js";
import { sameOrigin } from "../dom/sameOrigin.js";
import {
    INITIAL,
    PLAIN_VIEW,
    choose,
    hydrate,
    onOpen,
    prefer,
    viewParam,
    type SpecView,
    type ViewChoice,
} from "../spec/views.js";
import {
    COMPARING,
    UNAVAILABLE,
    changedPercentOf,
    chipText,
    counterpartName,
    matchBand,
    offBaselineReadout,
    readout,
} from "../spec/verdict.js";
import { rangeValueAt, seamX, splitAt, splitFraction } from "../spec/wipe.js";
import {
    matchAnnotationItems,
    type AnnotationItem,
    type Bounds,
} from "../annotate/match.js";
import {
    a11yDifferences,
    a11yDifferenceValue,
    type A11yDifference,
} from "../annotate/a11yCompare.js";
import {
    groupTypography,
    pairTypography,
    typographyComparableValue,
    typographyTokensOverlap,
    typographyValue,
    type Field,
    type TypographyPair,
} from "../annotate/typography.js";
import { dataUrlFor } from "../inspect/layers.js";

/**
 * Which picker source the lane compares against, as `viewer.js` reads it from the pressed button.
 * Passed on `open()` rather than latched from the markup at install, so switching source re-points
 * the canvases too.
 */
interface SpecCompareSource {
    /** Same-origin raster to compare the render against; empty falls back to `data-reference`. */
    reference: string;
    /** What the source calls itself, e.g. `Figma` or `wear-m3-catalog`. */
    label: string;
    /** Whether those pixels are an imported specification rather than another catalog's render. */
    spec: boolean;
}

/**
 * A point the eyedropper reads at: the pointer's sub-pixel position and the target it was over
 * (kept so a remembered position resolves to the same panel).
 */
interface PickPoint {
    x: number;
    y: number;
    target: Node | null;
}

/**
 * The loupe's settings. [LOUPE_SPAN] is odd so the window has a centre cell for the crosshair.
 * Fifteen across at [LOUPE_TILE] px is an 8× cell: big enough to see a one-pixel border, small
 * enough that a 3px shift stays a visible fraction.
 */
const LOUPE_SPAN = 15;
const LOUPE_TILE = 120;
/** Clear of the cursor, and of the pixels it is over. */
const LOUPE_GAP = 20;

/** What `viewer.js` calls on the way into and out of the lane. */
interface SpecCompareApi {
    view(): SpecView;
    prefer(next: string): void;
    hydrate(next: string | null): void;
    open(url: string, source?: SpecCompareSource | null): void;
    close(): void;
    /** Whether the stage is showing the render the published score was measured against. */
    baseline(atBaseline: boolean): void;
}

declare global {
    interface Window {
        cpSpecCompare?: SpecCompareApi;
    }
}

@customElement("cp-spec-compare")
export class SpecCompare extends ControllerElement {
    private installed = false;
    private root: HTMLElement | null = null;
    private compare: HTMLElement | null = null;
    private views: HTMLElement | null = null;
    private chip: HTMLElement | null = null;
    /** The published verdict's band, so leaving the lane restores the chip exactly as served. */
    private bakedBand = "";
    /**
     * Whether the stage shows the render the published score was measured against.
     *
     * Off the baseline (e.g. a theme picked) neither the published nor a live number is shown: the
     * published one describes another frame, and a live one would grade the theme, since the spec
     * is imported once at the default. The chip falls back to the provider label and the readout
     * says the spec is baseline-only. The panels still paint.
     */
    private atBaseline = true;
    /** The kit raster the server put on `data-reference` — the lane's source when none is picked. */
    private referenceUrl = "";
    /**
     * The picked source's raster when the picker offered a choice. Kept beside [referenceUrl] so
     * returning on an unpaired catalog finds the served reference unchanged.
     */
    private sourceUrl = "";
    /** The picked source's label, for the panel caption and the off-baseline line. */
    private sourceLabel = "";
    /**
     * Whether the panel beside the render is a specification. False when the picker points at a
     * sibling catalog, because a sibling's render is not a spec:
     * - the design-spec chip withholds its published (kit) verdict;
     * - the reference panel's caption names the source instead of "Spec";
     * - the kit's typography annotations are withheld. The pictures and the live measurement are
     *   unaffected: a cross-catalog pixel comparison is real.
     */
    private sourceIsSpec = true;
    /** The reference panel's served caption/label, restored whenever the kit source is back. */
    private bakedCaption = "";
    private bakedPanelLabel = "";
    private actualUrl = "";

    private choice: ViewChoice = INITIAL;
    private open = false;

    /**
     * The normalised pair currently painted and its `(reference, actual)`. A view switch reuses it;
     * a changed render re-runs the comparison.
     */
    private frames: NormalisedPair | null = null;
    private framesKey = "";
    /** The live match those frames scored, restored on re-entry so the chip and readout agree. */
    private framesMatch: number | null = null;
    /** Whether the chip currently shows a live measurement rather than a resting label. */
    private liveOnChip = false;
    /** The readout that goes with the live number, so the chip's tooltip states the same one. */
    private scoreTip: string | null = null;
    /** Bumped to abandon a comparison in flight. */
    private generation = 0;
    /**
     * The two normalised sides as readable pixels for the eyedropper, read back once per pair
     * (`getImageData` is the expensive part). Cleared whenever [frames] is replaced.
     */
    private pickPixels: {
        reference: Uint8ClampedArray;
        candidate: Uint8ClampedArray;
    } | null = null;
    /** A frozen reading holds the panel still; pointer moves are ignored until it is released. */
    private pickFrozen = false;
    /**
     * The reading currently in the row. A click latches this rather than re-reading at the click's
     * coordinates: Chromium rounds `MouseEvent.clientX/clientY` to whole CSS pixels while
     * `pointermove` carries fractions, so re-reading could freeze a neighbouring pixel the visitor
     * never saw.
     */
    private pickLive = "";
    /**
     * Where the pointer is over the comparison (null outside), tracked even while frozen, from
     * `pointermove`'s sub-pixel coordinates. Releasing the latch re-reads here.
     */
    private pickPoint: PickPoint | null = null;
    /**
     * The point the latch closed on, held while closed. Separate from [pickPoint] because a frozen
     * reading must survive the pointer leaving (e.g. to press a loupe toggle).
     */
    private pickHeld: PickPoint | null = null;
    /**
     * Whether the frames on stage are the pair being asked for. An in-lane source switch relabels
     * immediately but re-normalises asynchronously; until the frames arrive, readings would
     * attribute old pixels to the new source, so the picker stays quiet.
     */
    private pickSettled = false;
    /** The magnifier, created on first install and parked in the body — see [ensureLoupe]. */
    private loupe: HTMLElement | null = null;
    /** Its two toggles, beside the view group. */
    private loupeControls: HTMLElement | null = null;
    /**
     * Whether the magnifier follows the pointer, latched on. Off by default: a cursor-following
     * patch covers what it magnifies. Pressed for close reading, or held via [loupeHeld].
     */
    private loupeOn = false;
    /**
     * Whether Shift is down: the magnifier for one look without the toggle. Also read from
     * `pointermove`'s `shiftKey`, since Shift may have been pressed while the page lacked focus.
     */
    private loupeHeld = false;
    /**
     * Whether the render is read at its own matched box's position rather than the reference's
     * coordinate. Off reports what is at a point (the pixel-diff contract, the default); on reports
     * what the same element is (what a design review asks).
     */
    private alignOn = false;
    /**
     * The matched layout boxes of [frames] in normalised space: null until requested, empty when
     * the pair has none.
     */
    private alignBoxes: AlignedBox[] | null = null;
    /** The `framesKey` those boxes were built for, so a new pair cannot inherit them. */
    private alignKey = "";
    private cleanups: Array<() => void> = [];
    private annotationKey = "";
    private annotationPromise: Promise<unknown> | null = null;
    /** Annotation endpoint captured with the exact render normalised into [frames]. */
    private framesAnnotationUrl = "";
    /** Accessibility endpoint captured with the actual frame normalised into [frames]. */
    private framesA11yUrl = "";
    private typographyLegend: HTMLElement | null = null;
    private typographyLayers: HTMLElement[] = [];
    private a11yLegend: HTMLElement | null = null;
    private a11yKey = "";
    private a11yPromise: Promise<unknown> | null = null;

    // `viewer.js` calls `window.cpSpecCompare` as it enters the lane, so the global has to be up as
    // soon as the markup exists rather than a parse later. Same shape as `<cp-rc-lanes>`.
    connectedCallback(): void {
        super.connectedCallback();
        if (!this.install()) void whenParsed().then(() => this.install());
    }

    disconnectedCallback(): void {
        for (const off of this.cleanups) off();
        this.cleanups = [];
        if (window.cpSpecCompare === this.api) delete window.cpSpecCompare;
        this.installed = false;
        // Abandon anything in flight rather than letting it paint into a lane nobody is watching.
        this.generation++;
        this.clearTypography();
        this.clearA11y();
        this.typographyLegend?.remove();
        this.typographyLegend = null;
        // The loupe (in the body) and toggles (in the lane) live outside this element, so remove
        // them explicitly.
        this.loupe?.remove();
        this.loupe = null;
        this.loupeControls?.remove();
        this.loupeControls = null;
        this.a11yLegend?.remove();
        this.a11yLegend = null;
        super.disconnectedCallback();
    }

    private api: SpecCompareApi = {
        view: () => this.choice.view,
        prefer: (next) => {
            this.choice = prefer(this.choice, next);
        },
        hydrate: (next) => {
            const before = this.choice.view;
            this.choice = hydrate(this.choice, next);
            // Back/Forward change the view like the buttons, so release the reading the same way.
            if (this.choice.view !== before) this.releasePick();
            this.apply();
        },
        open: (url, source) => {
            this.open = true;
            this.actualUrl = url || "";
            // An in-lane switch re-enters here so the source lands with its pair; `compute()` keys
            // cached frames on `(reference, actual)`, so a new reference re-runs the shared
            // normalisation.
            this.sourceUrl = source?.reference ?? "";
            this.sourceLabel = source?.label ?? "";
            this.sourceIsSpec = source ? source.spec : true;
            this.applySourceLabels();
            // Drop a live spec number immediately when the panel stops being the spec, rather than
            // after the async normalisation.
            if (!this.sourceIsSpec) this.setChipVerdict(null);
            // Not written to the address bar here: `viewer.js` calls this from `enterMode` before
            // its `syncUrl()`, while the history entry is still the lane being left. `syncUrl`
            // re-emits `specView` in the push that records `mode=spec`.
            this.choice = onOpen(this.choice);
            // Claim the readout's row now; claiming it on the first reading would reflow the header
            // mid-hover.
            this.setPick("");
            this.apply();
        },
        close: () => {
            this.open = false;
            // Off the lane the panel is the served one again, so the caption the server wrote comes
            // back with it rather than a sibling's name outliving the pair it described.
            this.sourceUrl = "";
            this.sourceLabel = "";
            this.sourceIsSpec = true;
            this.applySourceLabels();
            // `close()` restores the published verdict; bump the generation so late results can't
            // paint a live number over it.
            this.generation++;
            this.setChipVerdict(null);
            // The lane header outlives the lane, so clear the reading.
            this.releasePick();
            this.apply();
        },
        baseline: (atBaseline) => {
            if (atBaseline === this.atBaseline) return;
            this.atBaseline = atBaseline;
            // Off the baseline there is no comparable spec, so a live number answers a different
            // question; both numbers go.
            if (!atBaseline || !this.liveOnChip) this.setChipVerdict(null);
            // The readout depends on this too, and the cached-frames path skips it; drop the key so
            // the same pair is re-decided.
            this.framesKey = "";
            this.framesMatch = null;
            if (this.open) this.apply();
        },
    };

    private install(): boolean {
        if (!this.isConnected || this.installed) return true;
        this.root = document.querySelector<HTMLElement>(".cp-viewer");
        this.compare = document.getElementById("cp-spec-compare");
        this.views = document.getElementById("cp-spec-views");
        // Inert unless the served catalog published a design reference for this preview.
        if (!this.root || !this.compare || !this.views) return false;
        this.installed = true;

        this.chip = document.getElementById("cp-spec-chip");
        this.bakedBand = this.chip?.getAttribute("data-spec-match") ?? "";
        this.referenceUrl = this.compare.getAttribute("data-reference") ?? "";
        const referencePanel = this.referencePanel();
        this.bakedCaption = referencePanel?.caption.textContent ?? "";
        this.bakedPanelLabel =
            referencePanel?.canvas.getAttribute("aria-label") ?? "";

        // ServeWeb's inline theme bootstrap sets this before the bundle loads, so a themed deep
        // link never flashes the baked verdict. viewer.js keeps it current.
        this.atBaseline = this.root.getAttribute("data-spec-baseline") !== "0";
        if (!this.atBaseline) this.setChipVerdict(null);

        this.on(this.views, "click", (event) => {
            const button = (event.target as Element | null)?.closest?.(
                "[data-cp-spec-view]",
            );
            if (!button || !this.views?.contains(button)) return;
            this.setView(button.getAttribute("data-cp-spec-view") ?? "");
        });
        this.ensureLoupeControls();
        const range = this.range();
        // A drag is continuous input: redraw every frame, but leave the URL alone. The chosen VIEW
        // is the shareable state; where the seam happened to stop is not.
        if (range) this.on(range, "input", () => this.drawWipe());
        this.bindDrag();
        this.bindPick();
        this.on(window, "resize", () => {
            this.placeTypography();
            this.markScaling();
        });
        this.on(window, "cp-inspect-change", () => {
            void this.refreshTypography();
            void this.refreshA11y();
        });

        window.cpSpecCompare = this.api;
        this.apply();
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

    private canvas(id: string): HTMLCanvasElement | null {
        return document.getElementById(id) as HTMLCanvasElement | null;
    }

    // The eyedropper. All three panels paint the same normalised space, so one mapping serves them
    // all, and hovering the diff says what its magenta is made of.

    /**
     * The panels a reading can come from. Includes the wipe canvas (same normalised space at the
     * origin), which the Slider view needs since it hides the other panels.
     */
    private pickPanels(): HTMLCanvasElement[] {
        return [
            this.canvas("cp-spec-reference"),
            this.canvas("cp-spec-diff"),
            this.canvas("cp-spec-actual"),
            this.canvas("cp-spec-wipe-canvas"),
        ].filter((c): c is HTMLCanvasElement => c !== null);
    }

    private bindPick(): void {
        if (!this.compare) return;
        this.on(this.compare, "pointermove", (event) => {
            const pointer = event as PointerEvent;
            this.loupeHeld = pointer.shiftKey;
            // Kept even while frozen. The latch stops the ROW from moving, not the pointer, and
            // releasing it has to put the reading back where the cursor actually ended up.
            this.pickPoint = {
                x: pointer.clientX,
                y: pointer.clientY,
                target: pointer.target as Node | null,
            };
            if (this.pickFrozen) return;
            this.showPick(this.pickPoint);
        });
        this.on(this.compare, "pointerleave", () => {
            this.pickPoint = null;
            if (!this.pickFrozen) this.showPick(null);
        });
        // Click freezes the reading so it can be read and copied without the cursor holding still;
        // clicking again, or Escape, releases it. Only a settled reading is announced — the panel
        // is rewritten on every pointermove, and a live region around that would queue a stream of
        // pixel values over everything else on the page.
        this.on(this.compare, "click", (event) => {
            // On the wipe canvas a press seeks the split (`bindDrag`), so it can't freeze a reading
            // there; hover still reads.
            if (
                this.canvas("cp-spec-wipe-canvas")?.contains(
                    event.target as Node,
                )
            )
                return;
            if (this.pickFrozen) {
                this.releaseFreeze();
                return;
            }
            // Latch what is on screen (see [pickLive]).
            if (!this.pickLive) return;
            this.pickFrozen = true;
            this.pickHeld = this.pickPoint;
            this.announcePick(this.pickLive);
            this.markFrozen(true);
        });
        this.on(document, "keydown", (event) => {
            const key = (event as KeyboardEvent).key;
            // Shift alone adds the patch to a reading already shown. Listened on `document`, since
            // modifiers aren't delivered to whatever is under the cursor.
            if (key === "Shift") {
                this.holdLoupe(true);
                return;
            }
            if (key !== "Escape" || !this.pickFrozen) return;
            this.releaseFreeze();
        });
        this.on(document, "keyup", (event) => {
            if ((event as KeyboardEvent).key === "Shift") this.holdLoupe(false);
        });
        // A key released while unfocused never arrives (Shift+Tab is the usual route), so drop the
        // held modifier on blur.
        this.on(window, "blur", () => this.holdLoupe(false));
    }

    /** Take the modifier down or up, and re-read if it changed what is on screen. */
    private holdLoupe(down: boolean): void {
        if (down === this.loupeHeld) return;
        this.loupeHeld = down;
        this.refreshPick();
    }

    /** Whether the patch is wanted right now — latched on, or held. */
    private loupeShowing(): boolean {
        return this.loupeOn || this.loupeHeld;
    }

    /** Put the frozen styling on the row, or take it off. */
    private markFrozen(frozen: boolean): void {
        document
            .getElementById("cp-spec-pick")
            ?.classList.toggle("cp-spec-pick--frozen", frozen);
    }

    /**
     * Release a frozen reading by either route (second click or Escape) and hand the row back to
     * the pointer, re-reading at the tracked position (blank if the pointer has left).
     */
    private releaseFreeze(): void {
        this.pickFrozen = false;
        this.pickHeld = null;
        this.announcePick("");
        this.markFrozen(false);
        this.showPick(this.pickPoint);
    }

    /**
     * Draw the reading for a point into both the row and the loupe, from one resolution so they
     * always name the same pixel.
     */
    private showPick(point: PickPoint | null): string {
        const at = point ? this.pickAt(point) : null;
        this.pickLive = at
            ? summarise(
                  readingAt(
                      at.pixels.reference,
                      at.pixels.candidate,
                      at.pair.width,
                      at.pair.height,
                      at.x,
                      at.y,
                      at.offset,
                  ),
                  this.sourceLabel || "Spec",
                  "Render",
              )
            : "";
        this.setPick(this.pickLive);
        this.drawLoupe(at, point);
        return this.pickLive;
    }

    /**
     * Resolve a pointer position to a readable point of the normalised space, or null. Every null
     * case (unreadable pair, gutter, unsettled pair, letterbox) must empty the row.
     */
    private pickAt(point: PickPoint): {
        pair: NormalisedPair;
        pixels: { reference: Uint8ClampedArray; candidate: Uint8ClampedArray };
        x: number;
        y: number;
        offset: Offset | null;
    } | null {
        const pair = this.frames;
        if (!pair || !this.pickSettled) return null;
        const panel = this.pickPanels().find((c) => c.contains(point.target));
        if (!panel) return null;
        const pixels = this.pickBuffers(pair);
        if (!pixels) return null;
        const drawn = this.drawnRect(panel, pair);
        if (!drawn) return null;
        // The panel is the normalised space scaled to fit its box, so the mapping is that scale
        // and nothing else — no per-side offset, because both sides already share this origin.
        const x = (point.x - drawn.left) / drawn.scale;
        const y = (point.y - drawn.top) / drawn.scale;
        // Outside the drawn frame is letterbox (`object-fit: contain`), not picture; no reading.
        if (x < 0 || y < 0 || x >= pair.width || y >= pair.height) return null;
        return { pair, pixels, x, y, offset: this.offsetFor(x, y) };
    }

    /**
     * Where a panel actually draws the pair, in client coordinates, and at what scale. With
     * `object-fit: contain` the drawn rect differs from the element box when letterboxed;
     * `contain`'s one `Math.min` handles both cases.
     */
    private drawnRect(
        panel: HTMLCanvasElement,
        pair: NormalisedPair,
    ): { left: number; top: number; scale: number } | null {
        const rect = panel.getBoundingClientRect();
        if (!(rect.width > 0 && rect.height > 0)) return null;
        if (!(pair.width > 0 && pair.height > 0)) return null;
        const scale = Math.min(
            rect.width / pair.width,
            rect.height / pair.height,
        );
        return {
            left: rect.left + (rect.width - pair.width * scale) / 2,
            top: rect.top + (rect.height - pair.height * scale) / 2,
            scale,
        };
    }

    /**
     * Mark the panels being enlarged so only they get nearest-neighbour (`image-rendering:
     * pixelated` makes upscaled readings checkable; downscaled it drops rows and aliases). Settled
     * after each paint and on resize.
     */
    private markScaling(): void {
        for (const canvas of this.pickPanels()) {
            const rect = canvas.getBoundingClientRect();
            canvas.classList.toggle(
                "cp-spec-canvas--upscaled",
                canvas.width > 0 && rect.width > canvas.width,
            );
        }
    }

    /**
     * The pair's pixels, read back once. A tainted canvas throws here too: no reading rather than a
     * wrong one.
     */
    private pickBuffers(pair: NormalisedPair) {
        if (this.pickPixels) return this.pickPixels;
        try {
            const read = (canvas: HTMLCanvasElement) =>
                canvas
                    .getContext("2d", { willReadFrequently: true })!
                    .getImageData(0, 0, pair.width, pair.height).data;
            this.pickPixels = {
                reference: read(pair.reference),
                candidate: read(pair.candidate),
            };
        } catch {
            this.pickPixels = null;
        }
        return this.pickPixels;
    }

    // The loupe: a magnified patch (both sides side by side at 8×) over the eyedropper's point,
    // with the reading as its caption, showing what a single-number reading can't (a doubled
    // border, a heavier glyph). Geometry is `spec/loupe.ts`; the patch is drawn straight from the
    // panel canvases. Never simply on: latched by the toggle, or raised while Shift is held.

    /** The magnifier's element, built once and parked in the body. */
    private ensureLoupe(): HTMLElement | null {
        if (this.loupe?.isConnected) return this.loupe;
        if (!document.body) return null;
        const loupe = document.createElement("div");
        loupe.className = "cp-spec-loupe";
        loupe.id = "cp-spec-loupe";
        loupe.hidden = true;
        // Nothing here is for a screen reader: the readout row carries the same reading as text,
        // and the frozen one is announced. A live region of magnified pixels would be a second
        // voice saying the same thing badly.
        loupe.setAttribute("aria-hidden", "true");
        for (const [side, caption] of [
            ["reference", "Spec"],
            ["candidate", "Render"],
        ] as const) {
            const figure = document.createElement("figure");
            figure.className = "cp-spec-loupe-tile";
            const canvas = document.createElement("canvas");
            canvas.className = "cp-spec-loupe-canvas";
            canvas.setAttribute("data-cp-loupe-side", side);
            const label = document.createElement("figcaption");
            label.setAttribute("data-cp-loupe-caption", side);
            label.textContent = caption;
            figure.append(canvas, label);
            loupe.appendChild(figure);
        }
        const note = document.createElement("p");
        note.className = "cp-spec-loupe-note";
        note.setAttribute("data-cp-loupe-note", "");
        loupe.appendChild(note);
        // In the body rather than the lane, so a magnifier never contributes to page width.
        document.body.appendChild(loupe);
        this.loupe = loupe;
        return loupe;
    }

    /** The two toggles, beside the view group: whether to magnify, and whether to align. */
    private ensureLoupeControls(): HTMLElement | null {
        if (this.loupeControls?.isConnected) return this.loupeControls;
        const views = this.views;
        if (!views) return null;
        const group = document.createElement("span");
        // The view group's own classes: this is the same kind of control one question over — how
        // the pair is READ, beside how it is drawn — and a second shape for it would say otherwise.
        group.className = "cp-spec-views cp-spec-loupe-group";
        group.id = "cp-spec-loupe-controls";
        group.setAttribute("role", "group");
        group.setAttribute("aria-label", "Loupe");
        group.hidden = true;
        group.append(
            this.loupeToggle(
                "loupe",
                "Loupe",
                "Magnify the pixels under the pointer, on both sides at once — " +
                    "or hold Shift for one look without pressing this",
                this.loupeOn,
            ),
            this.loupeToggle(
                "align",
                "Align",
                "Read the render inside its own matched layout box, so an element that only moved " +
                    "is compared with itself instead of with whatever is now behind it",
                this.alignOn,
            ),
        );
        this.on(group, "click", (event) => {
            const button = (event.target as Element | null)?.closest?.(
                "[data-cp-spec-loupe]",
            );
            if (!button || !group.contains(button)) return;
            this.toggleLoupe(button.getAttribute("data-cp-spec-loupe") ?? "");
        });
        views.after(group);
        this.loupeControls = group;
        return group;
    }

    private loupeToggle(
        name: string,
        label: string,
        tip: string,
        pressed: boolean,
    ): HTMLButtonElement {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "cp-spec-view";
        button.setAttribute("data-cp-spec-loupe", name);
        button.setAttribute("aria-pressed", String(pressed));
        button.title = tip;
        button.textContent = label;
        return button;
    }

    /**
     * Flip a toggle and re-read where the pointer is: turning alignment on changes the reading.
     * Goes through [refreshPick] because these buttons are outside the comparison, so pressing one
     * already emptied the live point; a frozen reading re-reads at its latched point.
     */
    private toggleLoupe(name: string): void {
        if (name === "loupe") this.loupeOn = !this.loupeOn;
        else if (name === "align") this.alignOn = !this.alignOn;
        else return;
        for (const button of this.loupeControls?.querySelectorAll(
            "[data-cp-spec-loupe]",
        ) ?? []) {
            const on =
                button.getAttribute("data-cp-spec-loupe") === "loupe"
                    ? this.loupeOn
                    : this.alignOn;
            button.setAttribute("aria-pressed", String(on));
        }
        if (this.alignOn) void this.ensureAlignment();
        this.refreshPick();
    }

    /**
     * Draw the patch, or hide it in the same cases the readout empties, plus when neither latched
     * nor held. A stale patch would picture somewhere else.
     */
    private drawLoupe(
        at: {
            pair: NormalisedPair;
            x: number;
            y: number;
            offset: Offset | null;
        } | null,
        point: PickPoint | null,
    ): void {
        const loupe =
            this.loupeShowing() && at && point ? this.ensureLoupe() : null;
        if (!loupe || !at || !point) {
            if (this.loupe) this.loupe.hidden = true;
            return;
        }
        const window_ = windowAt(at.x, at.y, LOUPE_SPAN);
        this.paintTile("reference", at.pair.reference, window_, null);
        this.paintTile("candidate", at.pair.candidate, window_, at.offset);
        const caption = loupe.querySelector<HTMLElement>(
            '[data-cp-loupe-caption="reference"]',
        );
        if (caption) caption.textContent = this.sourceLabel || "Spec";
        const note = loupe.querySelector<HTMLElement>("[data-cp-loupe-note]");
        if (note) note.textContent = this.alignmentNote(at.offset);
        loupe.hidden = false;
        // Measured after unhiding (a hidden element has no size); placement keeps the patch off the
        // cursor.
        const place = placeAt(
            { x: point.x, y: point.y },
            { width: loupe.offsetWidth, height: loupe.offsetHeight },
            { width: window.innerWidth, height: window.innerHeight },
            LOUPE_GAP,
        );
        loupe.style.left = `${place.left}px`;
        loupe.style.top = `${place.top}px`;
    }

    /** One side's magnified patch, with the sampled cell ringed in the diff map's magenta. */
    private paintTile(
        side: "reference" | "candidate",
        source: CanvasImageSource,
        window_: { sx: number; sy: number; span: number },
        offset: Offset | null,
    ): void {
        const canvas = this.loupe?.querySelector<HTMLCanvasElement>(
            `[data-cp-loupe-side="${side}"]`,
        );
        if (!canvas) return;
        canvas.width = LOUPE_TILE;
        canvas.height = LOUPE_TILE;
        const context = canvas.getContext("2d");
        if (!context) return;
        context.clearRect(0, 0, LOUPE_TILE, LOUPE_TILE);
        // Nearest-neighbour: the patch is there to count pixels.
        context.imageSmoothingEnabled = false;
        try {
            context.drawImage(
                source,
                window_.sx + (offset?.dx ?? 0),
                window_.sy + (offset?.dy ?? 0),
                window_.span,
                window_.span,
                0,
                0,
                LOUPE_TILE,
                LOUPE_TILE,
            );
        } catch {
            // A window entirely off the frame has no intersection to draw. The cleared tile is the
            // right picture of that: there is nothing there.
        }
        const cell = LOUPE_TILE / window_.span;
        const cross = crosshairAt(window_.span, LOUPE_TILE);
        context.lineWidth = 2;
        context.strokeStyle = "#e52e73";
        context.strokeRect(cross.left, cross.top, cell, cell);
    }

    /** What the patch says about alignment — nothing at all while it is off. */
    private alignmentNote(offset: Offset | null): string {
        if (!this.alignOn) return "";
        if (!this.alignBoxes) return "matching layout…";
        if (!offset) return "no matched box here";
        if (!offset.dx && !offset.dy) return "aligned · this box did not move";
        const signed = (n: number) => (n < 0 ? "" : "+") + n;
        return `aligned ${signed(offset.dx)},${signed(offset.dy)}`;
    }

    // Content-aware alignment.

    /** The offset the candidate is read at, or null wherever alignment has nothing to say. */
    private offsetFor(x: number, y: number): Offset | null {
        if (!this.alignOn || !this.alignBoxes) return null;
        return offsetAt(this.alignBoxes, x, y);
    }

    /**
     * Match the two sides' layout boxes for the pair on stage, once per `framesKey` (boxes are only
     * true of one pair). The render's annotations are fetched, so readings stay plain until this
     * resolves; never aligned by an empty box set.
     */
    private async ensureAlignment(): Promise<void> {
        const key = this.framesKey;
        const pair = this.frames;
        if (!pair || !key) return;
        if (this.alignKey === key && this.alignBoxes) return;
        const reference = await this.referenceAnnotations();
        if (reference === null) {
            // No published reference annotations: alignment stays unavailable rather than using
            // other geometry.
            this.alignKey = key;
            this.alignBoxes = [];
            this.refreshPick();
            return;
        }
        const actual = await this.actualAnnotations();
        if (this.framesKey !== key || this.frames !== pair) return;
        this.alignKey = key;
        this.alignBoxes = actual
            ? alignedBoxes(
                  reference,
                  actual,
                  pair.boxes,
                  pair.width,
                  pair.height,
              )
            : [];
        this.refreshPick();
    }

    /** The point a reading describes: the latched one while frozen, else the pointer's. */
    private pickSubject(): PickPoint | null {
        return this.pickFrozen ? this.pickHeld : this.pickPoint;
    }

    /** Re-read wherever the reading is, after something that changes what a point MEANS. */
    private refreshPick(): void {
        const subject = this.pickSubject();
        if (!subject) return;
        const held = this.pickFrozen;
        const line = this.showPick(subject);
        if (held && line) this.announcePick(line);
    }

    /**
     * Drop everything the eyedropper holds: readout, announcement, latch and both readbacks. Called
     * whenever the pair stops being the one on stage (new frames, leaving the lane, since the lane
     * header persists off the lane). The readbacks can be tens of megabytes and are re-read on the
     * next hover.
     */
    private releasePick(): void {
        this.pickPixels = null;
        this.pickFrozen = false;
        this.pickSettled = false;
        // The patch is a picture of the pair, so it goes exactly where the reading goes. The
        // matched boxes go with it: they describe this pair's geometry and nothing else.
        if (this.loupe) this.loupe.hidden = true;
        this.alignBoxes = null;
        this.alignKey = "";
        // The tracked point may name a surface this pair lacks (e.g. a switch to plain Spec); drop
        // it too.
        this.pickLive = "";
        this.pickPoint = null;
        this.pickHeld = null;
        this.setPick("");
        this.announcePick("");
        this.markFrozen(false);
    }

    /**
     * The readout's text. An empty reading keeps the row (claimed on open, released on close) so
     * the wrapping header doesn't reflow under the cursor.
     */
    private setPick(text: string): void {
        const readout = document.getElementById("cp-spec-pick");
        if (!readout) return;
        readout.textContent = text;
        readout.hidden = text === "" && !this.open;
    }

    private announcePick(text: string): void {
        const live = document.getElementById("cp-spec-pick-live");
        if (live) live.textContent = text ? "Frozen reading. " + text : "";
    }

    private range(): HTMLInputElement | null {
        return document.getElementById(
            "cp-spec-wipe-range",
        ) as HTMLInputElement | null;
    }

    /** The reference panel's caption and canvas, or null on a page without the compare surface. */
    private referencePanel(): {
        caption: HTMLElement;
        canvas: HTMLElement;
    } | null {
        const figure = this.compare?.querySelector<HTMLElement>(
            '[data-cp-spec-panel="reference"]',
        );
        const caption = figure?.querySelector<HTMLElement>("figcaption");
        const canvas = this.canvas("cp-spec-reference");
        if (!caption || !canvas) return null;
        return { caption, canvas };
    }

    /** The raster the comparison is taken against: the picked source, else the served reference. */
    private reference(): string {
        return this.sourceUrl || this.referenceUrl;
    }

    /**
     * Whether the published verdict still describes the stage: false if the render moved off the
     * baseline or the panel stopped being the spec.
     */
    private publishedApplies(): boolean {
        return this.atBaseline && this.sourceIsSpec;
    }

    /**
     * Put the picked source's name on the reference panel, or restore the served caption.
     * Idempotent.
     */
    private applySourceLabels(): void {
        const panel = this.referencePanel();
        if (!panel) return;
        const named = !this.sourceIsSpec && this.sourceLabel.trim();
        panel.caption.textContent = named
            ? this.sourceLabel
            : this.bakedCaption;
        const label = named
            ? `${this.sourceLabel}'s own render of this component`
            : this.bakedPanelLabel;
        if (label) panel.canvas.setAttribute("aria-label", label);
    }

    private setView(next: string): void {
        const before = this.choice.view;
        this.choice = choose(this.choice, next);
        if (this.choice.view === before) return;
        // A reading names a point on a panel and the view decides which panels exist; a frozen
        // reading carried into Slider could only be dismissed with Escape. `apply()` re-settles the
        // picker.
        this.releasePick();
        this.apply();
        // A discrete choice, so it PUSHES: Back returns to the view you were looking at, the same
        // way it returns to the previous lane or theme.
        urlState()?.push({ specView: viewParam(this.choice.view) });
    }

    /**
     * Reconcile the stage with the chosen view. `spec` touches only its own container, leaving the
     * raster `<img>` as the whole surface; the others hide it via CSS (`.cp-viewer[data-spec-view]`
     * in `serve.css`) and take the stage.
     */
    private apply(): void {
        const view = this.choice.view;
        this.root?.setAttribute("data-spec-view", view);
        this.compare?.setAttribute("data-view", view);
        if (this.views) this.views.hidden = !this.open;
        // With the views, and gone in the plain Spec view for the same reason the panels are: there
        // is nothing on the stage to magnify, so a toggle over it would act on nothing.
        if (this.loupeControls)
            this.loupeControls.hidden = !this.open || view === PLAIN_VIEW;
        const score = document.getElementById("cp-spec-score");
        if (score) score.hidden = !this.open || view === PLAIN_VIEW;
        if (this.compare)
            this.compare.hidden = !this.open || view === PLAIN_VIEW;
        for (const button of this.views?.querySelectorAll(
            "[data-cp-spec-view]",
        ) ?? []) {
            button.setAttribute(
                "aria-pressed",
                String(button.getAttribute("data-cp-spec-view") === view),
            );
        }
        if (this.open && view !== PLAIN_VIEW) void this.compute();
        else this.clearTypography();
        if (!this.open || view === PLAIN_VIEW) this.clearA11y();
    }

    private setScore(text: string): void {
        const score = document.getElementById("cp-spec-score");
        if (score) score.textContent = text;
    }

    /**
     * Put a freshly computed match on the chip in place of the published one, or `null` to restore.
     * Once the lane has scored what is on stage, chip and readout report one comparison.
     */
    private setChipVerdict(percent: number | null): void {
        const chip = this.chip;
        if (!chip) return;
        const name =
            chip.getAttribute("data-spec-chip-name") || chip.textContent || "";
        if (percent === null) {
            this.liveOnChip = false;
            // Off the baseline the chip shows only the spec's tool, with a tooltip saying why; the
            // colour band goes too.
            const baked = this.publishedApplies();
            chip.textContent = baked
                ? chip.getAttribute("data-spec-chip-label") || name
                : name;
            const tip = baked
                ? chip.getAttribute("data-spec-chip-tip")
                : chip.getAttribute("data-spec-chip-stale-tip") ||
                  chip.getAttribute("data-spec-chip-tip");
            if (tip) chip.title = tip;
            if (baked && this.bakedBand)
                chip.setAttribute("data-spec-match", this.bakedBand);
            else chip.removeAttribute("data-spec-match");
            return;
        }
        this.liveOnChip = true;
        chip.textContent = chipText(name, percent);
        chip.setAttribute("data-spec-match", matchBand(percent));
        // The tooltip moves with the number.
        chip.title = this.scoreTip ?? chip.title;
    }

    /** Paint every comparison surface from one normalisation of the current pair. */
    private async compute(): Promise<void> {
        const api = compareApi();
        const reference = sameOrigin(this.reference(), location.origin);
        const actual = sameOrigin(this.actualUrl, location.origin);
        const annotationFrameUrl =
            document.getElementById("cp-img")?.getAttribute("data-cp-src") ||
            this.actualUrl;
        const annotationUrl = dataUrlFor(annotationFrameUrl, "annotations");
        const key = `${reference}\n${actual}`;
        if (!reference || !actual || !api) {
            this.frames = null;
            this.framesKey = "";
            this.framesAnnotationUrl = "";
            this.framesA11yUrl = "";
            this.pickSettled = false;
            this.setScore(UNAVAILABLE);
            return;
        }
        // Asking for a different pair than the one on the stage: quiet until it lands.
        if (key !== this.framesKey) this.releasePick();
        if (key === this.framesKey && this.frames) {
            // Returning cached frames abandons any in-flight normalisation; otherwise B → A → B
            // could paint A's late result under B's labels.
            this.generation++;
            this.pickSettled = true;
            if (this.alignOn) void this.ensureAlignment();
            this.drawWipe();
            // The view may have changed under the same pair — triptych stretches its columns and
            // diff does not — so which panels are enlarged is re-decided even when nothing repaints.
            this.markScaling();
            // Restore this pair's live number on the chip so it matches the readout.
            if (this.framesMatch !== null && this.sourceIsSpec)
                this.setChipVerdict(this.framesMatch);
            void this.refreshTypography();
            void this.refreshA11y();
            return;
        }
        const generation = ++this.generation;
        this.setScore(COMPARING);
        try {
            const next = await api.normaliseImageUrls(reference, actual);
            if (generation !== this.generation) return;
            this.frames = next;
            this.framesKey = key;
            // New pixels: release the old reading before declaring the pair settled (`releasePick`
            // clears that flag).
            this.releasePick();
            this.pickSettled = true;
            // Tie inspection facts to the candidate that produced these canvases; the hidden image
            // may advance.
            this.framesAnnotationUrl = annotationUrl ?? "";
            if (this.alignOn) void this.ensureAlignment();
            this.framesA11yUrl = dataUrlFor(annotationFrameUrl, "a11y") ?? "";
            if (this.alignOn) void this.ensureAlignment();
            this.copyInto(next.reference, this.canvas("cp-spec-reference"));
            this.copyInto(next.candidate, this.canvas("cp-spec-actual"));
            const diff = this.canvas("cp-spec-diff");
            const changed = diff
                ? api.diffCanvases(next.reference, next.candidate, diff)
                : 0;
            this.drawWipe();
            this.markScaling();
            const changedPercent = changedPercentOf(
                changed,
                next.width,
                next.height,
            );
            // Off the baseline, paint but don't score: the reference has no version for this
            // render. Returning before scoring means no number can leak via the cache, chip or
            // tooltip.
            if (!this.atBaseline) {
                const stale = offBaselineReadout(
                    changedPercent,
                    this.sourceIsSpec
                        ? undefined
                        : counterpartName(this.sourceLabel),
                );
                this.setScore(stale);
                this.scoreTip = null;
                this.framesMatch = null;
                this.setChipVerdict(null);
                void this.refreshTypography();
                void this.refreshA11y();
                return;
            }
            // Score the decoded frames rather than re-requesting URLs: a no-store override render
            // could come back different.
            const result = await api.scoreImages(
                next.images[0],
                next.images[1],
            );
            if (generation !== this.generation) return;
            const text = readout(
                result.percent,
                changedPercent,
                result.geometry,
            );
            this.setScore(text);
            this.scoreTip = text;
            this.framesMatch = result.percent;
            // The readout carries the live number for either source, but the design-spec chip only
            // for the spec.
            this.setChipVerdict(this.sourceIsSpec ? result.percent : null);
            void this.refreshTypography();
            void this.refreshA11y();
        } catch {
            if (generation !== this.generation) return;
            this.frames = null;
            this.framesKey = "";
            this.framesAnnotationUrl = "";
            this.framesA11yUrl = "";
            this.framesMatch = null;
            this.scoreTip = null;
            this.setScore(UNAVAILABLE);
            this.clearTypography();
            this.clearA11y();
        }
    }

    private typographyOn(): boolean {
        return Boolean(
            document.querySelector<HTMLInputElement>(
                '[data-cp-inspect="typography"]',
            )?.checked,
        );
    }

    private a11yOn(): boolean {
        return Boolean(
            document.querySelector<HTMLInputElement>('[data-cp-inspect="a11y"]')
                ?.checked,
        );
    }

    private referenceAnnotations(): Promise<unknown> {
        if (!this.sourceIsSpec)
            return this.fetchData(dataUrlFor(this.reference(), "annotations"));
        const node = document.getElementById("cp-spec-annotations");
        if (!node) return Promise.resolve(null);
        try {
            return Promise.resolve(
                JSON.parse(node.textContent ?? "") as { reference?: unknown },
            ).then((payload) => payload.reference ?? null);
        } catch {
            return Promise.resolve(null);
        }
    }

    private fetchData(url: string | null): Promise<unknown> {
        if (!url) return Promise.resolve(null);
        const key = url;
        if (this.a11yKey === key && this.a11yPromise) return this.a11yPromise;
        this.a11yKey = key;
        this.a11yPromise = fetch(url, { credentials: "same-origin" })
            .then((response) => (response.ok ? response.json() : null))
            .catch(() => null);
        return this.a11yPromise;
    }

    private actualAnnotations(): Promise<unknown> {
        // Use the annotation URL captured when this snapshot was normalised, not the live hidden
        // image.
        const url = this.framesAnnotationUrl;
        if (!url) return Promise.resolve(null);
        if (this.annotationKey === url && this.annotationPromise)
            return this.annotationPromise;
        this.annotationKey = url;
        this.annotationPromise = fetch(url, { credentials: "same-origin" })
            .then((response) => {
                if (!response.ok)
                    throw new Error(`annotations ${response.status}`);
                return response.json() as Promise<unknown>;
            })
            .then((payload) => {
                if (
                    payload &&
                    typeof payload === "object" &&
                    Array.isArray(
                        (payload as { annotations?: unknown }).annotations,
                    )
                )
                    return (payload as { annotations: unknown[] }).annotations;
                return payload;
            })
            .catch(() => null);
        return this.annotationPromise;
    }

    private changedFields(pair: TypographyPair): Field[] {
        const fields: Field[] = [
            "token",
            "family",
            "weight",
            "size",
            "tracking",
            "style",
            "axes",
        ];
        const reference = pair.reference ?? pair.comparisonReference;
        const actual = pair.actual ?? pair.comparisonActual;
        if (!reference || !actual) return fields;
        return fields.filter((field) => {
            if (field === "token")
                return !typographyTokensOverlap(reference.spec, actual.spec);
            if (
                typographyComparableValue(reference.spec, field) !==
                typographyComparableValue(actual.spec, field)
            )
                return true;
            return (
                field === "size" &&
                typographyComparableValue(reference.spec, "lineHeight") !==
                    typographyComparableValue(actual.spec, "lineHeight")
            );
        });
    }

    private fieldLabel(field: Field): string {
        return (
            {
                token: "Token",
                family: "Family",
                weight: "Weight",
                size: "Size",
                lineHeight: "Line height",
                tracking: "Tracking",
                style: "Style",
                axes: "Variations",
            } as Record<Field, string>
        )[field];
    }

    private value(
        pair: TypographyPair,
        side: "reference" | "actual",
        field: Field,
    ): string {
        const spec = (
            side === "reference"
                ? (pair.reference ?? pair.comparisonReference)
                : (pair.actual ?? pair.comparisonActual)
        )?.spec;
        let value = typographyValue(spec, field);
        if (field === "size" && spec?.lineHeight !== undefined)
            value += `/${typographyValue(spec, "lineHeight")}`;
        return value;
    }

    private ensureTypographyLegend(): HTMLElement | null {
        if (this.typographyLegend?.isConnected) return this.typographyLegend;
        const controls = document.getElementById("cp-controls");
        const parent = controls?.parentElement;
        if (!parent || !controls) return null;
        const legend = document.createElement("aside");
        legend.id = "cp-spec-typography-legend";
        legend.className = "cp-inspect-legend cp-spec-typography-legend";
        legend.setAttribute("aria-label", "Typography differences");
        legend.hidden = true;
        parent.insertBefore(legend, controls);
        this.typographyLegend = legend;
        return legend;
    }

    private clearTypography(): void {
        for (const layer of this.typographyLayers) layer.remove();
        this.typographyLayers = [];
        if (this.typographyLegend) {
            this.typographyLegend.textContent = "";
            this.typographyLegend.hidden = true;
        }
    }

    private async refreshTypography(): Promise<void> {
        const view = this.choice.view;
        const frames = this.frames;
        if (
            !this.open ||
            !this.frames ||
            !this.typographyOn() ||
            (view !== "diff" && view !== "triptych" && view !== "slider")
        ) {
            this.clearTypography();
            return;
        }
        const actual = await this.actualAnnotations();
        if (
            !this.open ||
            !this.typographyOn() ||
            this.choice.view !== view ||
            this.frames !== frames
        )
            return;
        const reference = await this.referenceAnnotations();
        if (reference === null || actual === null) {
            this.clearTypography();
            return;
        }
        const matched = matchAnnotationItems(reference, actual);
        const pairs = pairTypography(
            groupTypography(matched.reference),
            groupTypography(matched.actual),
        ).filter((pair) => this.changedFields(pair).length > 0);
        this.drawTypographyLegend(pairs);
        this.drawTypographyLayers(pairs);
    }

    private ensureA11yLegend(): HTMLElement | null {
        if (this.a11yLegend?.isConnected) return this.a11yLegend;
        const controls = document.getElementById("cp-controls");
        const parent = controls?.parentElement;
        if (!parent || !controls) return null;
        const legend = document.createElement("aside");
        legend.id = "cp-spec-a11y-legend";
        legend.className = "cp-inspect-legend cp-spec-a11y-legend";
        legend.setAttribute("aria-label", "Accessibility differences");
        legend.hidden = true;
        parent.insertBefore(legend, controls);
        this.a11yLegend = legend;
        return legend;
    }

    private clearA11y(): void {
        if (!this.a11yLegend) return;
        this.a11yLegend.textContent = "";
        this.a11yLegend.hidden = true;
    }

    private async refreshA11y(): Promise<void> {
        const view = this.choice.view;
        const frames = this.frames;
        if (
            !this.open ||
            !frames ||
            !this.a11yOn() ||
            (view !== "diff" && view !== "triptych" && view !== "slider")
        ) {
            this.clearA11y();
            return;
        }
        const [reference, actual] = await Promise.all([
            this.fetchData(dataUrlFor(this.reference(), "a11y")),
            this.fetchData(this.framesA11yUrl),
        ]);
        if (
            !this.open ||
            !this.a11yOn() ||
            this.choice.view !== view ||
            this.frames !== frames
        )
            return;
        if (reference === null || actual === null) {
            this.clearA11y();
            return;
        }
        this.drawA11yLegend(a11yDifferences(reference, actual));
    }

    private drawA11yLegend(differences: A11yDifference[]): void {
        const legend = this.ensureA11yLegend();
        if (!legend) return;
        legend.textContent = "";
        legend.hidden = false;
        const head = document.createElement("div");
        head.className = "cp-inspect-legend-head";
        head.textContent = differences.length
            ? "Accessibility differences"
            : "Accessibility matches";
        const count = document.createElement("span");
        count.className = "cp-inspect-legend-count";
        count.textContent = String(differences.length);
        head.appendChild(count);
        legend.appendChild(head);
        if (!differences.length) return;
        const list = document.createElement("ol");
        list.className = "cp-inspect-list";
        for (const difference of differences) {
            const row = document.createElement("li");
            row.className = "cp-inspect-entry cp-spec-a11y-diff";
            const badge = document.createElement("span");
            badge.className = "cp-inspect-badge";
            badge.textContent = difference.marker;
            row.appendChild(badge);
            const text = document.createElement("span");
            text.className = "cp-inspect-text";
            for (const field of difference.fields) {
                const line = document.createElement("span");
                line.className = "cp-spec-a11y-field";
                line.textContent = `${field}: ${a11yDifferenceValue(difference, "reference", field)} → ${a11yDifferenceValue(difference, "actual", field)}`;
                text.appendChild(line);
            }
            row.appendChild(text);
            list.appendChild(row);
        }
        legend.appendChild(list);
    }

    private drawTypographyLegend(pairs: TypographyPair[]): void {
        const legend = this.ensureTypographyLegend();
        if (!legend) return;
        legend.textContent = "";
        legend.hidden = false;
        const head = document.createElement("div");
        head.className = "cp-inspect-legend-head";
        head.textContent = pairs.length
            ? "Typography differences"
            : "Typography matches";
        const count = document.createElement("span");
        count.className = "cp-inspect-legend-count";
        count.textContent = String(pairs.length);
        head.appendChild(count);
        legend.appendChild(head);
        if (!pairs.length) return;
        const list = document.createElement("ol");
        list.className = "cp-inspect-list";
        for (const pair of pairs) {
            const row = document.createElement("li");
            row.className = "cp-inspect-entry cp-spec-type-diff";
            row.setAttribute("data-cp-typography-marker", pair.marker);
            const badge = document.createElement("span");
            badge.className = "cp-inspect-badge";
            badge.textContent = pair.marker;
            row.appendChild(badge);
            const text = document.createElement("span");
            text.className = "cp-inspect-text";
            for (const field of this.changedFields(pair)) {
                const line = document.createElement("span");
                line.className = "cp-spec-type-field cp-typography-changed";
                line.textContent = `${this.fieldLabel(field)}: ${this.value(pair, "reference", field)} → ${this.value(pair, "actual", field)}`;
                text.appendChild(line);
            }
            row.appendChild(text);
            list.appendChild(row);
        }
        legend.appendChild(list);
    }

    private drawTypographyLayers(pairs: TypographyPair[]): void {
        for (const layer of this.typographyLayers) layer.remove();
        this.typographyLayers = [];
        const view = this.choice.view;
        const sides: Array<["reference" | "actual", string]> =
            view === "triptych"
                ? [
                      ["reference", "reference"],
                      ["actual", "actual"],
                  ]
                : [["actual", "diff"]];
        for (const [side, panelName] of sides) {
            const panel = this.compare?.querySelector<HTMLElement>(
                `[data-cp-spec-panel="${panelName}"]`,
            );
            const canvas = panel?.querySelector("canvas");
            if (!panel || !canvas) continue;
            const layer = document.createElement("div");
            layer.className = "cp-spec-annotation-layer";
            layer.setAttribute("data-cp-side", side);
            for (const pair of pairs) {
                const group = pair[side];
                for (const item of group?.items ?? []) {
                    if (!item.bounds) continue;
                    layer.appendChild(this.typographyBox(pair.marker, item));
                }
            }
            panel.appendChild(layer);
            this.typographyLayers.push(layer);
        }
        requestAnimationFrame(() => this.placeTypography());
    }

    private typographyBox(marker: string, item: AnnotationItem): HTMLElement {
        const box = document.createElement("div");
        box.className = "cp-inspect-box cp-spec-type-box";
        box.setAttribute("data-cp-kind", "typography");
        box.setAttribute("data-source-x", String(item.bounds?.x ?? 0));
        box.setAttribute("data-source-y", String(item.bounds?.y ?? 0));
        box.setAttribute("data-source-width", String(item.bounds?.width ?? 0));
        box.setAttribute(
            "data-source-height",
            String(item.bounds?.height ?? 0),
        );
        const badge = document.createElement("span");
        badge.className = "cp-inspect-badge";
        badge.textContent = marker;
        box.appendChild(badge);
        return box;
    }

    private placeTypography(): void {
        const frames = this.frames;
        if (!frames) return;
        for (const layer of this.typographyLayers) {
            const panel = layer.parentElement;
            const canvas = panel?.querySelector("canvas");
            if (!canvas || !canvas.clientWidth) continue;
            const side =
                layer.getAttribute("data-cp-side") === "reference"
                    ? "reference"
                    : "candidate";
            const crop = frames.boxes[side];
            const sx = canvas.clientWidth / frames.width;
            const sy = canvas.clientHeight / frames.height;
            layer.style.left = `${canvas.offsetLeft}px`;
            layer.style.top = `${canvas.offsetTop}px`;
            layer.style.width = `${canvas.clientWidth}px`;
            layer.style.height = `${canvas.clientHeight}px`;
            for (const node of layer.querySelectorAll<HTMLElement>(
                ".cp-spec-type-box",
            )) {
                const bounds: Bounds = {
                    x: Number(node.getAttribute("data-source-x")),
                    y: Number(node.getAttribute("data-source-y")),
                    width: Number(node.getAttribute("data-source-width")),
                    height: Number(node.getAttribute("data-source-height")),
                };
                node.style.left = `${((bounds.x - crop.x) * frames.width * sx) / crop.width}px`;
                node.style.top = `${((bounds.y - crop.y) * frames.height * sy) / crop.height}px`;
                node.style.width = `${(bounds.width * frames.width * sx) / crop.width}px`;
                node.style.height = `${(bounds.height * frames.height * sy) / crop.height}px`;
            }
        }
    }

    private copyInto(
        source: CanvasImageSource & { width: number; height: number },
        target: HTMLCanvasElement | null,
    ): void {
        if (!target) return;
        target.width = source.width;
        target.height = source.height;
        target.getContext("2d")?.drawImage(source, 0, 0);
    }

    /**
     * The wipe: spec left of the split, render right, composited on one canvas from the shared
     * pixel space (exact at every fraction; two `drawImage`s per frame).
     */
    private drawWipe(): void {
        const wipe = this.canvas("cp-spec-wipe-canvas");
        const frames = this.frames;
        if (!wipe || !frames) return;
        const { width, height } = frames;
        wipe.width = width;
        wipe.height = height;
        const context = wipe.getContext("2d");
        if (!context) return;
        const split = splitAt(width, splitFraction(this.range()?.value));
        context.clearRect(0, 0, width, height);
        context.drawImage(frames.reference, 0, 0);
        if (split < width) {
            context.save();
            context.beginPath();
            context.rect(split, 0, width - split, height);
            context.clip();
            context.drawImage(frames.candidate, 0, 0);
            context.restore();
        }
        // The seam, in the diff map's magenta so the two comparison surfaces read as one instrument.
        context.fillStyle = "#e52e73";
        context.fillRect(seamX(width, split), 0, 2, height);
    }

    /**
     * Dragging on the frame moves the split; the range input remains the keyboard path and the
     * single source of the split.
     */
    private bindDrag(): void {
        const wipe = this.canvas("cp-spec-wipe-canvas");
        const range = this.range();
        if (!wipe || !range || !this.compare?.querySelector(".cp-spec-wipe"))
            return;
        let dragging = false;
        const seekTo = (event: PointerEvent) => {
            const value = rangeValueAt(
                event.clientX,
                wipe.getBoundingClientRect(),
            );
            if (value === null) return;
            range.value = value;
            this.drawWipe();
        };
        this.on(wipe, "pointerdown", (event) => {
            dragging = true;
            try {
                wipe.setPointerCapture((event as PointerEvent).pointerId);
            } catch {
                // Capture is a convenience — the drag still tracks without it.
            }
            seekTo(event as PointerEvent);
            event.preventDefault();
        });
        this.on(wipe, "pointermove", (event) => {
            if (dragging) seekTo(event as PointerEvent);
        });
        const end = () => {
            dragging = false;
        };
        this.on(wipe, "pointerup", end);
        this.on(wipe, "pointercancel", end);
    }
}

declare global {
    interface HTMLElementTagNameMap {
        "cp-spec-compare": SpecCompare;
    }
}
