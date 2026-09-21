// `<cp-spec-compare>` — the viewer's design-spec diff options. Replaces `assets/spec-compare.js`.
//
// The spec lane already put the imported design reference on the same stage as the render, so the
// two could be flipped between. Flipping is a weak instrument: it answers "are these different?" by
// asking a visitor to hold one frame in their head while looking at the other, which finds a
// wholesale colour change and misses the 4dp of padding that is the actual bug. The focused
// `/compare/<id>` page has the real instruments, but reaching it means leaving the viewer — and
// with it every override, knob and theme that produced the render worth comparing. So the
// instruments come to the lane: Spec, Diff, Triptych, Slider, one click apart.
//
// Every surface is drawn from ONE normalisation pass, so the diff, the three panels and the wipe
// are all in the same pixel space — a reference exported at a different scale than the render lines
// up here instead of reading as a total mismatch.
//
// Renders nothing of its own: the panels, canvases and buttons are all server-rendered, because the
// lane has to exist before any of this runs. `serve.css` hides the tag.
//
// The decisions live next door: `spec/views.ts` (who gets to pick the view — three sources compete
// and they are not equal), `spec/verdict.ts` (what the chip and the readout say), `spec/wipe.ts`
// (where the seam sits), `spec/loupe.ts` (what the magnifier looks at and where it sits),
// `spec/align.ts` (which matched box a point is in, and how far it moved), `dom/sameOrigin.ts`
// (what may reach a canvas at all).

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
 * Which of the picker's sources the lane is comparing against, as `viewer.js` reads it out of the
 * pressed button.
 *
 * Carried on `open()` rather than read from the markup once, which is the whole of issue #4895: the
 * reference URL was latched at install from `data-reference` — the KIT raster — so picking the
 * sibling catalog re-pointed the hidden `<img>` behind the Diff/Triptych/Slider canvases and left
 * every canvas painting the pair it had already normalised. The picker moved and the stage did not.
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
 * A point the eyedropper can read at: the pointer's sub-pixel position and what it was over.
 *
 * The target rides along rather than being recovered with `elementFromPoint`, so a reading taken
 * from a remembered position resolves to the same panel the pointer was actually on.
 */
interface PickPoint {
    x: number;
    y: number;
    target: Node | null;
}

/**
 * The loupe's dial settings, and why these numbers.
 *
 * An odd [LOUPE_SPAN] so the magnified window has a centre CELL rather than a centre line, which is
 * what lets the crosshair mark the pixel the readout names instead of the corner between four of
 * them. Fifteen across at [LOUPE_TILE] pixels is an 8× cell — large enough to see a one-pixel
 * border and a half-covered state layer, small enough that a 3px shift is a visible fraction of the
 * patch rather than a scroll of it.
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
     * Whether the stage is showing the render the PUBLISHED score was measured against.
     *
     * The baked verdict describes the catalog's own snapshot — default theme, declared knobs, no
     * detected features. Pick a theme and the render moves; the reference does not, because a
     * design spec is imported once and is not re-exported per theme. So off the baseline the
     * published number is describing a frame nobody is looking at, and it is describing it
     * flatteringly: on `switch-on__ideal__icon-off` the chip reads 99.6% while the lane, scoring
     * what is actually on the stage under Light High Contrast, reads 88.9%. Entering the lane then
     * looks like a regression when all that happened is that the honest number arrived.
     *
     * The live measurement was the answer to that, and it is the wrong one. It replaced a number
     * describing the wrong frame with a number describing the wrong QUESTION: there is no spec for
     * a themed render to be measured against, so scoring one grades the theme. On
     * `shape-bun__ideal__default__light` under Light Medium Contrast the geometry matches exactly,
     * only the token colour moves, and the lane reported "90.5% match · 89.34% pixels differ" —
     * which reads as a catastrophic parity failure in a component that is pixel-correct.
     *
     * So off the baseline NEITHER number is published. The chip falls back to the plain provider
     * label — the same thing every catalog without a baked score shows — and the readout says the
     * spec is baseline-only instead of quoting a match. The panels still paint: the pictures are
     * honest, it is only the arithmetic over them that has nothing to say.
     */
    private atBaseline = true;
    /** The kit raster the server put on `data-reference` — the lane's source when none is picked. */
    private referenceUrl = "";
    /**
     * The picked source's raster, when the picker offered a choice and `viewer.js` named one.
     *
     * Kept beside [referenceUrl] rather than overwriting it so leaving the lane and coming back on
     * a catalog with no pairing still finds the served reference exactly as it was rendered.
     */
    private sourceUrl = "";
    /** The picked source's label, for the panel caption and the off-baseline line. */
    private sourceLabel = "";
    /**
     * Whether the panel beside the render is a SPECIFICATION.
     *
     * False the moment the picker points at a sibling catalog, and three things turn on it, all for
     * the same reason — a sibling's render is not a spec, and every surface that says "spec" would
     * be stating something untrue about the pixels on the stage:
     *
     *  - the design-spec chip keeps its published verdict to itself. That number is the kit
     *    comparison, measured at publish against the imported reference; quoting it over a
     *    wear-m3-catalog pair puts two unrelated comparisons in one row and invites the reader to
     *    read one as the other. Same argument as [atBaseline], one axis over;
     *  - the reference panel's caption and label name the source instead of reading "Spec";
     *  - the kit's typography annotations are withheld. They describe the imported reference, so
     *    matching them against the render while a sibling's raster is in the panel would annotate
     *    a frame nobody is looking at.
     *
     * The pictures and the live measurement are untouched: a render against a paired catalog's
     * render is a real pixel comparison, and it is exactly the number the cross-system parity
     * surfaces report. Only the claim that it is a SPEC match goes.
     */
    private sourceIsSpec = true;
    /** The reference panel's served caption/label, restored whenever the kit source is back. */
    private bakedCaption = "";
    private bakedPanelLabel = "";
    private actualUrl = "";

    private choice: ViewChoice = INITIAL;
    private open = false;

    /**
     * The normalised pair currently painted, and the `(reference, actual)` it came from. A view
     * switch inside one lane visit must not re-run the comparison; a re-entry after the render
     * changed underneath (a new theme, a new knob) must.
     */
    private frames: NormalisedPair | null = null;
    private framesKey = "";
    /**
     * The live match those frames scored. Re-entering against an unchanged pair restores this
     * rather than leaving the published number on the chip beside a readout showing the live one —
     * two numbers for one comparison.
     */
    private framesMatch: number | null = null;
    /** Whether the chip currently shows a live measurement rather than a resting label. */
    private liveOnChip = false;
    /** The readout that goes with the live number, so the chip's tooltip states the same one. */
    private scoreTip: string | null = null;
    /** Bumped to abandon a comparison in flight. */
    private generation = 0;
    /**
     * The two normalised sides as readable pixels, for the eyedropper.
     *
     * Read back once per pair rather than per hover: `getImageData` over a full frame is the
     * expensive part, and the buffers are immutable for as long as the pair is. Cleared whenever
     * [frames] is replaced, so a reading can never describe the previous comparison.
     */
    private pickPixels: {
        reference: Uint8ClampedArray;
        candidate: Uint8ClampedArray;
    } | null = null;
    /** A frozen reading holds the panel still; pointer moves are ignored until it is released. */
    private pickFrozen = false;
    /**
     * The reading currently in the row — what the visitor is looking at.
     *
     * A click latches THIS rather than taking a fresh reading at the click's own coordinates, and
     * the difference is not academic: Chromium rounds `MouseEvent.clientX/clientY` to whole CSS
     * pixels while the `pointermove` that drew the line carries fractions, so re-reading at the
     * click lands on a neighbouring pixel of the normalised space. Measured on the triptych at
     * devicePixelRatio 2, one click on a held-still pointer: the row read
     * `146,122 · Figma #494451 · Render #332e3c · Δ 22` and froze
     * `146,121 · Figma #332e3c · Render #332e3c · identical` — the one gesture whose whole purpose
     * is to hold what is on screen replaced it with a different pixel and the opposite verdict.
     * The error grows with the panel's scale, since it is a whole CSS pixel of it.
     */
    private pickLive = "";
    /**
     * Where the pointer is over the comparison, tracked on every move INCLUDING while frozen, and
     * null while it is outside.
     *
     * Releasing the latch hands the row back to the live reading, and live means the pixel the
     * pointer is on now — not the one it was on when the latch closed, and not the click's rounded
     * position. Only `pointermove` carries the sub-pixel coordinates that produced the readings, so
     * they are kept from there rather than recovered from the releasing event.
     */
    private pickPoint: PickPoint | null = null;
    /**
     * The point the LATCH closed on, held for as long as it is closed.
     *
     * [pickPoint] cannot serve: it is the live pointer, and a frozen reading survives the pointer
     * leaving the comparison — which is most of what freezing is for. Re-reading a frozen line
     * from it therefore reads from `null` the moment the visitor moves off the stage to press one
     * of the loupe's toggles, which blanked the row and hid the patch while the latch stayed shut,
     * and a return to the panels could not restore either: `pointermove` is latched. So the two
     * are separate, and what a frozen reading is re-read at is the point it was taken at.
     */
    private pickHeld: PickPoint | null = null;
    /**
     * Whether the frames on the stage are the pair currently being asked for.
     *
     * An in-lane source switch re-labels the lane at once and re-normalises asynchronously, so
     * between the two the canvases still hold the PREVIOUS source. A reading taken then would be
     * the old pixels under the new source's name — a confidently wrong attribution, which is worse
     * than no reading. The picker goes quiet from the moment the requested pair changes until its
     * frames arrive.
     */
    private pickSettled = false;
    /** The magnifier, created on first install and parked in the body — see [ensureLoupe]. */
    private loupe: HTMLElement | null = null;
    /** Its two toggles, beside the view group. */
    private loupeControls: HTMLElement | null = null;
    /**
     * Whether the magnifier follows the pointer, latched on.
     *
     * OFF by default. A patch that tracks the cursor covers the very picture it is magnifying, and
     * the lane is not entered to use a loupe — it is entered to look at two frames, and most of
     * that looking is not asking about one pixel's neighbourhood. An instrument that is always in
     * the way is the version of this nobody wants, so it is asked for: pressed, for a spell of
     * close reading, or held on [loupeHeld]'s modifier for one look.
     */
    private loupeOn = false;
    /**
     * Whether Shift is down — the magnifier for one look, without spending the toggle.
     *
     * The common case is a glance: something on the diff looks a pixel off and the question is over
     * in a second. Reaching the toggle for that costs two presses and leaves the patch following
     * the cursor afterwards, so the answer is usually not to bother. Held, the patch lasts exactly
     * as long as the question.
     *
     * Read from `pointermove`'s own `shiftKey` as well as from the key events, because a pointer
     * arriving over a panel with Shift already down never produced a `keydown` this element saw —
     * the visitor may have pressed it while the page did not have focus at all.
     */
    private loupeHeld = false;
    /**
     * Whether the render is read at its own matched box's position rather than at the reference's
     * coordinate — issue #830, and an OPTION because it trades one truth for another. Off, the
     * lane reports what is at a point, which is what a pixel diff means. On, it reports what the
     * same element is, which is what a design review is asking. The first is the lane's contract,
     * so it stays the default.
     */
    private alignOn = false;
    /**
     * The matched layout boxes of [frames], in its normalised space — null until they have been
     * asked for, empty when the pair has none to offer.
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
        // Both of these live OUTSIDE this element — the loupe in the body, the toggles in the lane
        // — so nothing takes them away with the tag. A magnifier left floating over a page whose
        // comparison has gone is the same fault as a reading left in the lane header.
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
            // Back and Forward change the view exactly as the buttons do, so a reading has to go
            // the same way: it names a point on a panel the restored view may not show, and a
            // frozen one restored into Slider is the same trap `setView` avoids.
            if (this.choice.view !== before) this.releasePick();
            this.apply();
        },
        open: (url, source) => {
            this.open = true;
            this.actualUrl = url || "";
            // A switch inside the lane re-enters through here, so the source lands with the pair it
            // belongs to. `compute()` keys its cached frames on `(reference, actual)`, which is what
            // makes the new reference re-run the ONE normalisation the four views share — a cached
            // pass would line the new raster up against the old geometry.
            this.sourceUrl = source?.reference ?? "";
            this.sourceLabel = source?.label ?? "";
            this.sourceIsSpec = source ? source.spec : true;
            this.applySourceLabels();
            // Drop a live spec number the moment the panel stops being the spec, rather than at the
            // end of the normalisation it triggers: the switch is instant and the comparison is not,
            // so leaving it up would show the kit's percentage over the sibling's picture for as
            // long as the raster takes to arrive.
            if (!this.sourceIsSpec) this.setChipVerdict(null);
            // Deliberately NOT written to the address bar here. `viewer.js` calls this from inside
            // `enterMode`'s spec branch — before its closing `syncUrl()`, and therefore while the
            // current history entry is still the lane being LEFT. Writing now would stamp a
            // lane-scoped `specView` onto the outgoing render entry, so Back would land on a PNG
            // URL carrying it. `syncUrl` re-emits it from `view()` in the same push that records
            // `mode=spec`, which is where it belongs.
            this.choice = onOpen(this.choice);
            // Claim the readout's row now, while nothing is being read. Claiming it on the first
            // reading instead would reflow the header mid-hover and move the picture under the
            // cursor, so the reading on screen would describe a pixel that had just walked away.
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
            // `close()` puts the published verdict back, and a normalisation or score resolving
            // afterwards would otherwise still pass its generation check and paint a live —
            // possibly override-specific — number onto the chip while the published render is back.
            this.generation++;
            this.setChipVerdict(null);
            // The reading described the pair that was on the stage. There is none now, and the
            // lane header outlives the lane, so anything left in it would be describing a picture
            // that is gone.
            this.releasePick();
            this.apply();
        },
        baseline: (atBaseline) => {
            if (atBaseline === this.atBaseline) return;
            this.atBaseline = atBaseline;
            // A live measurement used to outrank this unconditionally, on the grounds that it was
            // taken from the frames on the stage. That holds only while a comparable spec exists
            // for those frames. Off the baseline there IS no such spec — the reference was
            // exported once, at the catalog's default — so a live number is not the better answer
            // to the same question, it is a confident answer to a different one. Both numbers go.
            if (!atBaseline || !this.liveOnChip) this.setChipVerdict(null);
            // The readout is decided by this too, and `compute()`'s cached-frames path returns
            // without touching it. Drop the key so the SAME pair is re-decided rather than left
            // reporting under the baseline state it was scored in.
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

        // ServeWeb's inline theme bootstrap publishes this before the component bundle loads, so
        // the first install does not briefly show the baked verdict for a themed deep link.
        // viewer.js keeps it current after controls change, and reconnects read the latest value.
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

    // ---- The eyedropper --------------------------------------------------------------------
    //
    // All three panels paint the SAME normalised space — that is what `normaliseImageUrls` returns
    // and what makes the delta map arithmetic rather than guesswork — so one mapping serves every
    // one of them, and hovering the diff is as meaningful as hovering either side: it says what the
    // magenta is made of.

    /**
     * The panels a reading can be taken from, each carrying the shared normalised space.
     *
     * The wipe canvas is one of them: `drawWipe` sizes it to the pair and draws both sides at the
     * origin, so a point on it names the same feature the other three do. It also has to be here
     * rather than merely allowed — the Slider view hides all three panels
     * (`[data-view="slider"] .cp-spec-panel { display: none }`), so without it the eyedropper is
     * simply absent from one of the four views.
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
            // On the wipe canvas a press is a SEEK — `bindDrag` moves the split on pointerdown —
            // so freezing there would hijack the slider's own gesture. It stays readable on hover;
            // it is the one surface a reading cannot be frozen from.
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
            // Latch what is ON SCREEN. Re-reading at the click's coordinates instead is the whole
            // of [pickLive]: they are the pointer's rounded to whole CSS pixels, so the gesture
            // froze a line the visitor had never seen.
            if (!this.pickLive) return;
            this.pickFrozen = true;
            this.pickHeld = this.pickPoint;
            this.announcePick(this.pickLive);
            this.markFrozen(true);
        });
        this.on(document, "keydown", (event) => {
            const key = (event as KeyboardEvent).key;
            // Shift alone, so a reading already on screen gains its patch without the pointer
            // having to move. On `document` rather than on the comparison, because a modifier is
            // not delivered to whatever the cursor happens to be over.
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
        // A key released while the page is not focused never arrives, and the modifier would stay
        // down for the rest of the visit — a loupe nobody asked for and no gesture turns off.
        // Tabbing away is the ordinary way to reach that: Shift is half of Shift+Tab.
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
     * Let a frozen reading go, by either route — a second click or Escape — and hand the row back
     * to the pointer.
     *
     * The two routes used to end differently: Escape blanked the row while a second click left the
     * latched line in place, now unstyled, so it read as a LIVE reading of whatever the cursor had
     * since moved onto. Both now re-read at the tracked position, which restores the same line the
     * pointer would have drawn had it never been frozen — and blanks the row when the pointer has
     * left the comparison, because there is nothing under it to describe.
     */
    private releaseFreeze(): void {
        this.pickFrozen = false;
        this.pickHeld = null;
        this.announcePick("");
        this.markFrozen(false);
        this.showPick(this.pickPoint);
    }

    /**
     * Draw the reading for a point into the row and the loupe, or empty both.
     *
     * One resolution feeds both, because they are one instrument at two scales: the row names the
     * pixel under the crosshair, and a row and a patch resolved separately could name different
     * ones the moment anything between the pointer and the buffer changed.
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
     * Resolve a pointer position to a readable point of the normalised space, or null.
     *
     * Every null path is a case where the row must go EMPTY rather than keep its previous line: an
     * unreadable pair, a point in the gutter between two panels, a pair that has not settled, and
     * the letterbox beside a frame are all points the lane has nothing true to say about.
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
        // Outside the drawn frame is the letterbox, not the picture: the triptych stretches its
        // columns and `object-fit: contain` bars a frame taller than its column. Nothing is there,
        // and `sampleAt` would answer "outside this frame" — a reading, for a point that is not on
        // the picture at all. The row stays empty, exactly as it does off a panel.
        if (x < 0 || y < 0 || x >= pair.width || y >= pair.height) return null;
        return { pair, pixels, x, y, offset: this.offsetFor(x, y) };
    }

    /**
     * Where a panel actually DRAWS the pair, in client coordinates, and at what scale.
     *
     * The element's own box was the mapping until the triptych began stretching its columns: a
     * panel is `object-fit: contain` there, so a frame whose ratio does not match its column is
     * letterboxed inside the box and the two rectangles stop being the same thing. Reading through
     * the element's box then slides every reading toward the centre by half the bar — silently,
     * and by more the squarer the frame is. `contain`'s own arithmetic is one `Math.min`, and it
     * is exact for the unletterboxed case too, so there is one path rather than a special case.
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
     * Mark the panels the browser is ENLARGING, so only those get nearest-neighbour.
     *
     * Upscaled, `image-rendering: pixelated` is what makes a reading checkable — the row names a
     * colour and the picture shows the pixel it belongs to, rather than a smoothed average of it
     * and its neighbours. Downscaled it is a lie of a different kind, dropping rows of a large
     * render and inventing aliasing, so the class comes off. Which one applies is a layout fact,
     * not a catalog one, so it is settled here after each paint and again on resize.
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
     * The pair's pixels, read back once. A tainted canvas throws here exactly as it does for the
     * score, and the answer is the same: no reading rather than a wrong one.
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

    // ---- The loupe --------------------------------------------------------------------------
    //
    // The eyedropper names one pixel on each side. That is the right answer to "is the state layer
    // drawn" and the wrong one to "why does this edge read as different": a one-pixel border that
    // became two, a glyph a hair heavier, a shadow that starts a row earlier are all differences a
    // single reading reports as one number and a magnified patch shows as what they are. So the
    // picker grows a magnifier over the same point — both sides, side by side, at 8× — and the
    // reading becomes its caption.
    //
    // The geometry is `spec/loupe.ts`; what is drawn is two `drawImage` calls out of the very
    // canvases the panels were painted from, so the patch is the picture and not a redraw of it.
    //
    // It is never simply ON. A patch that follows the cursor covers what it magnifies, so it is
    // reached two ways and both are the visitor's: the Loupe toggle latches it for a spell of close
    // reading, and holding Shift raises it for one look. The second is the one that gets used —
    // most questions about a neighbourhood last a second, and an instrument you have to put away
    // afterwards does not get picked up for those.

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
        // In the BODY rather than in the lane: `position: fixed` inside a scrolling row would be
        // fine, but the lane is also what `html { overflow-x: clip }` has already caught out once
        // (issue #801), and a magnifier has no business contributing to the page's width at all.
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
     * Flip one of the two and re-read where the pointer already is.
     *
     * Re-reading matters for Align in a way it does not for Loupe: turning alignment on changes the
     * READING, so leaving the row showing the unaligned line beside a patch drawn aligned would put
     * two answers to one question on screen. A frozen reading is re-read too — the latch holds a
     * POINT, and both toggles are about what is true at that point — which is why it goes through
     * [refreshPick] rather than reading [pickPoint] directly: these buttons are OUTSIDE the
     * comparison, so pressing one has already sent a `pointerleave` and emptied the live point.
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
     * Draw the patch, or take it off screen.
     *
     * Hidden rather than emptied whenever there is nothing to magnify, and the same set of cases
     * the readout goes empty on — plus nobody asking for it, neither latched nor held. A stale
     * patch is worse than none: the row at least goes blank, while a patch left up is a picture of
     * somewhere else.
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
        // Measured after unhiding, because a hidden element has no size — and the placement is the
        // whole reason the patch does not end up under the cursor it is following.
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
        // Nearest-neighbour, for the same reason `.cp-spec-canvas--upscaled` exists: a smoothed
        // magnification is an average of the pixels rather than the pixels, and the whole patch is
        // here to be counted.
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

    // ---- Content-aware alignment (issue #830) -------------------------------------------------

    /** The offset the candidate is read at, or null wherever alignment has nothing to say. */
    private offsetFor(x: number, y: number): Offset | null {
        if (!this.alignOn || !this.alignBoxes) return null;
        return offsetAt(this.alignBoxes, x, y);
    }

    /**
     * Match the two sides' layout boxes for the pair on the stage, once.
     *
     * Keyed on `framesKey` rather than on a flag, because the boxes are only true of ONE pair: a
     * source switch or a new render replaces the pixels, and boxes carried across would align the
     * new frame by the old one's geometry — which is the same class of fault as a reading that
     * outlives its pair, and harder to see, because the patch would still look like a patch.
     *
     * The render's annotations are fetched, so this resolves after the first hover that needs them;
     * until it does, `alignBoxes` is null and the readings are plain ones. That is the honest
     * intermediate state — a reading is never silently aligned by an empty box set.
     */
    private async ensureAlignment(): Promise<void> {
        const key = this.framesKey;
        const pair = this.frames;
        if (!pair || !key) return;
        if (this.alignKey === key && this.alignBoxes) return;
        const reference = await this.referenceAnnotations();
        if (reference === null) {
            // No published reference annotations — a sibling catalog's raster, or a preview whose
            // kit carries none. There is nothing to match against, so alignment stays unavailable
            // rather than falling back to some other geometry.
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

    /**
     * The point a reading describes: the latched one while the latch is shut, else the pointer's.
     *
     * Both re-read paths go through this, because both mean "say that again about the same point",
     * and while frozen the same point is the one the latch closed on rather than wherever the
     * cursor has since wandered — including off the comparison entirely.
     */
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
     * Drop everything the eyedropper holds: the readout, the announcement, the frozen latch and
     * the two readbacks.
     *
     * Called wherever the pair a reading describes stops being the pair on the stage — new frames,
     * and leaving the lane. Leaving matters as much as replacing: `cp-spec-lane` carries the source
     * buttons, so it stays in the page off the lane, and a readout left in it would go on naming
     * two colours beside a picture neither came from. The latch has to go with it or the lane opens
     * next time already frozen, ignoring the pointer until someone guesses to press Escape.
     *
     * The buffers are the largest thing this element retains — two full-frame RGBA readbacks, tens
     * of megabytes on a large pair — and nothing off the lane can read them. `pickBuffers` reads
     * them again on the next hover, which is the same work as the first hover of any pair.
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
        // The tracked point named a surface this pair may not have — a view switch comes through
        // here, and the plain Spec view has no panels at all — so it goes with the reading rather
        // than waiting to be re-read into the next one.
        this.pickLive = "";
        this.pickPoint = null;
        this.pickHeld = null;
        this.setPick("");
        this.announcePick("");
        this.markFrozen(false);
    }

    /**
     * The readout's text, and — while the lane is open — its empty row.
     *
     * An empty reading leaves the row in place rather than removing it. The lane wraps, so a row
     * that came and went with the pointer re-laid the header out under the cursor and moved the
     * picture with it; the row is claimed once, when the lane opens and nothing is being read, and
     * given up once, when it closes.
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
     * Whether the published verdict still describes what is on the stage.
     *
     * Two independent ways for it to stop doing so, and they compose: the RENDER can move off the
     * baseline the score was taken at, and the PANEL can stop being the spec the score was taken
     * against. Either one alone is enough to make the baked number a confident answer to a question
     * nobody asked.
     */
    private publishedApplies(): boolean {
        return this.atBaseline && this.sourceIsSpec;
    }

    /**
     * Put the picked source's name on the reference panel, or the served caption back.
     *
     * Cheap and idempotent, so it can run on every entry: the panel is server-rendered and never
     * replaced, and the caption is the one place a reader is told what the left-hand picture IS.
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
        // A reading names a point on a panel, and the view decides which panels exist. Carried
        // across a switch it describes a surface that may not be on screen — the plain Spec view
        // shows no canvases at all — and a frozen one carried into Slider is a trap: pointer moves
        // are latched, and the wipe canvas is the one surface a click cannot release the latch
        // from, so the reading could only be dismissed with Escape. `apply()` re-settles the
        // picker for the view it is switching to.
        this.releasePick();
        this.apply();
        // A discrete choice, so it PUSHES: Back returns to the view you were looking at, the same
        // way it returns to the previous lane or theme.
        urlState()?.push({ specView: viewParam(this.choice.view) });
    }

    /**
     * Reconcile the stage with the chosen view.
     *
     * `spec` deliberately touches nothing but its own container: the raster `<img>` viewer.js put
     * on the stage stays the whole surface, so pressing Spec puts the lane back to exactly what it
     * showed before any of this existed. The other three hide that `<img>` from CSS (see
     * `.cp-viewer[data-spec-view]` in `serve.css`) and take the stage themselves.
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
     * Put a freshly-computed match on the chip, replacing the published one — or `null` to restore.
     *
     * The chip carries the score baked at publish, which describes the PUBLISHED pixels. The moment
     * an override, a knob or a theme moves the render, that number describes a frame that is no
     * longer on the stage, so once this lane has scored what is actually in front of the visitor,
     * that is what the chip must show. Chip and readout are then one instrument reporting one
     * comparison, which is the whole reason the verdict moved onto the chip.
     */
    private setChipVerdict(percent: number | null): void {
        const chip = this.chip;
        if (!chip) return;
        const name =
            chip.getAttribute("data-spec-chip-name") || chip.textContent || "";
        if (percent === null) {
            this.liveOnChip = false;
            // Off the baseline there is no published number that describes what is on the stage,
            // so the chip says only which tool the spec came from and the tooltip says why. The
            // band goes with it: a colour is a verdict too, and a green chip over a render the
            // verdict was never taken against is the same lie in less text.
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
        // The tooltip moves with the number. Left alone it went on quoting the publish-time
        // verdict — "99.6% match … 23.09% pixels differ" — beside a chip reading 88.9%, which is
        // the same two-numbers-for-one-comparison the live chip exists to prevent.
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
            // Returning cached frames is a decision that THIS pair is what the stage holds, so any
            // normalisation still in flight has to be abandoned with it. Without this, B → A → B
            // (with B cached) left A's request passing its own generation check when it resolved,
            // and it painted A over the stage while every label still said B — measured: the
            // reference canvas carried the paired catalog's pixels under the Figma caption.
            this.generation++;
            this.pickSettled = true;
            if (this.alignOn) void this.ensureAlignment();
            this.drawWipe();
            // The view may have changed under the same pair — triptych stretches its columns and
            // diff does not — so which panels are enlarged is re-decided even when nothing repaints.
            this.markScaling();
            // The readout still holds this pair's live numbers, so the chip has to come back to the
            // same ones. Without this an override-bearing page re-entering the lane showed the
            // PUBLISHED score beside the live readout — two numbers for one comparison.
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
            // New pixels: whatever the eyedropper had read described the previous pair, and a
            // frozen reading over it would now be asserting the old comparison against the new
            // picture. Released BEFORE the pair is declared settled — `releasePick` drops that
            // flag with everything else, so settling first would immediately unsettle again.
            this.releasePick();
            this.pickSettled = true;
            // Keep inspection facts tied to the same candidate that produced these canvases. The
            // hidden render image can advance while a comparison remains open; reading its URL
            // later would put new bounds and typography over old pixels.
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
            // Off the baseline the pictures are still worth painting — a visitor holding a themed
            // render against the baseline art is doing something legitimate, and the three panels
            // are honestly labelled Spec / Diff / Render. What must not happen is a SCORE: the
            // reference has no version of itself for this render, so a percentage across the two
            // grades the override. Return before scoring rather than computing a number and
            // declining to show it — an unscored pair cannot leak one through the cached-frames
            // path, the chip, or the tooltip.
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
            // Scored from the frames just decoded, NOT by re-requesting the two URLs. An
            // override-bearing `/render` is `no-store`, so asking again would be a second render —
            // and a second render can come back different, leaving the percentage describing a
            // frame other than the diff beside it.
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
            // The readout carries the live number for either source — it sits under the views, next
            // to the pair it describes. The CHIP does not: it is the design-spec chip, named for the
            // kit's provider, and a sibling comparison's percentage wearing that label is a
            // different comparison in the same clothes.
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
        // Pixel comparison may use a snapshot object URL to avoid a second no-store render. The
        // sibling annotations endpoint was captured when that snapshot was normalised; do not
        // consult the live hidden image here because it may already contain a newer override.
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
     * The wipe: spec on the left of the split, render on the right, in one frame at one size.
     *
     * Drawn rather than clip-path'd over two stacked elements because the two sources are already
     * canvases in a shared pixel space — compositing here keeps the split exact at every fraction
     * and costs two `drawImage` calls per drag frame.
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
     * Dragging on the frame itself is what "slider" means to anyone who has used one; the range
     * input underneath remains the keyboard/assistive path and stays the single source of the split.
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
