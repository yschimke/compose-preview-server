// `<cp-page-zoom>` — makes a design page's sheet readable: drill in by
// double-click, zoom about the pointer with ⌘/Ctrl + wheel, drag to pan, and one
// corner control to get back out. Specimen sheets are inlined at design size
// (thousands of px wide) into a narrow column; being SVG, a transform keeps them
// sharp.
//
// It transforms `.cp-page-canvas`, so the sheet and its overlays move together,
// but renders outside that layer (into the sticky control row) so the control
// never pans out of reach. Coupling with `<cp-design-page>` is one-way through
// the DOM: it reads `.cp-page-selected` (Escape unwinds a selection first) and
// writes `--cp-page-zoom` on the stage for the stylesheet to counter-scale marks.

import { Fragment, h, type VNode } from "../vue.js";
import { customElement } from "../controllerElement.js";
import { whenParsed } from "../dom/whenParsed.js";
import { VueElement } from "../vueElement.js";
import {
    STEP,
    clamp,
    frameRect,
    pickLevel,
    rescale,
    rest,
    revealDelta,
    zoomAbout,
    zoomed,
    type Box,
    type Level,
    type View,
} from "../zoom/viewport.js";

/** A drag under this many pixels was a click that wobbled, not a pan. */
const DRAG_SLOP = 5;

@customElement("cp-page-zoom")
export class PageZoom extends VueElement {
    /** The readout, and the only reactive state — the view itself is not rendered. */
    private percent = 100;

    private installed = false;
    private stage: HTMLElement | null = null;
    private canvas: HTMLElement | null = null;
    private svg: SVGSVGElement | null = null;

    private view: View = rest();

    /**
     * How deep the reader has drilled, outermost first; the export's `<g data-node-id>` tree
     * supplies the levels. See `pickLevel`.
     */
    private drilled: Element[] = [];

    private panning: {
        id: number;
        x: number;
        y: number;
        /** Where the gesture started, so `moved` can be a displacement and not a path. */
        fromX: number;
        fromY: number;
        moved: number;
        held: boolean;
    } | null = null;

    /** Set by a pan that travelled, spent by the click it would otherwise become. */
    private swallowClick = false;

    private observer: ResizeObserver | null = null;

    /** The stage's size at the last commit, so a resize can carry the pan across it. */
    private stageSize: Box | null = null;

    /** The page this sheet belongs to — the scope the keyboard shortcuts answer in. */
    private page: HTMLElement | null = null;

    // Bound once so `removeEventListener` in `disconnectedCallback` matches.
    private readonly onDblClick = (event: MouseEvent) => this.drill(event);
    private readonly onWheel = (event: WheelEvent) => this.wheel(event);
    private readonly onPointerDown = (event: PointerEvent) =>
        this.startPan(event);
    private readonly onPointerMove = (event: PointerEvent) =>
        this.movePan(event);
    private readonly onPointerUp = (event: PointerEvent) =>
        this.endPan(event, true);
    private readonly onPointerCancel = (event: PointerEvent) =>
        this.endPan(event, false);
    private readonly onDragStart = (event: Event) => {
        // An overlay is an `<a>`, and a drag on one drags the link instead of panning. Not
        // prevented on `pointerdown`, which would also suppress the compatibility mouse events the
        // double-click drill and slot navigation rely on.
        if (zoomed(this.view)) event.preventDefault();
    };
    private readonly onClickCapture = (event: MouseEvent) => {
        // A keyboard activation (`detail === 0`) never spends the drag guard; otherwise a pan
        // ending in `pointercancel` (no click) would leave it armed and swallow the next Enter or
        // Space.
        if (event.detail === 0) return;
        if (!this.swallowClick) return;
        this.swallowClick = false;
        event.preventDefault();
        event.stopPropagation();
    };
    private readonly onFocusCapture = (event: FocusEvent) => {
        this.reveal(event.target as Element | null);
    };
    private readonly onKeyDown = (event: KeyboardEvent) => this.escape(event);
    private readonly onFocusOut = () => {
        // Deferred: `focusout` fires before the next element takes focus.
        setTimeout(() => {
            if (!zoomed(this.view) && !this.contains(document.activeElement)) {
                this.hidden = true;
            }
        }, 0);
    };
    private readonly onPageKeyDown = (event: KeyboardEvent) =>
        this.shortcut(event);
    private readonly onResize = () => {
        const box = this.stageBox();
        const was = this.stageSize;
        // `rescale` keeps the reader's place across the change; `apply` clamps it into
        // the new bounds and records the size the next resize will measure against.
        this.apply(
            was ? rescale(this.view, was, box) : clamp(this.view, box),
            false,
        );
    };

    connectedCallback(): void {
        super.connectedCallback();
        // The bar sits in the control row above the sheet and is upgraded as soon as parsed,
        // possibly before the stage exists. Try now and again once the document is parsed
        // (`install` is idempotent).
        if (!this.install()) void whenParsed().then(() => this.install());
    }

    /**
     * Bind to the sheet this bar drives, if present. Returns true when there is nothing left to
     * wait for (bound, or disconnected meanwhile).
     */
    private install(): boolean {
        if (!this.isConnected || this.installed) return true;
        // The bar lives in the page's sticky control row (the stage is often taller than the
        // window, so a corner control was out of reach), so find the stage via the page root;
        // `closest` first handles a bar nested in a stage.
        this.stage =
            this.closest<HTMLElement>(".cp-page-stage") ??
            this.closest<HTMLElement>(
                "#cp-design-page",
            )?.querySelector<HTMLElement>(".cp-page-stage") ??
            null;
        this.canvas =
            this.stage?.querySelector<HTMLElement>("[data-cp-page-canvas]") ??
            null;
        this.svg = this.canvas?.querySelector("svg") ?? null;
        // Nothing to transform: leave the gestures inert rather than half-working.
        if (!this.stage || !this.canvas) return false;
        this.installed = true;

        this.stage.addEventListener("dblclick", this.onDblClick);
        this.stage.addEventListener("wheel", this.onWheel, { passive: false });
        this.stage.addEventListener("pointerdown", this.onPointerDown);
        this.stage.addEventListener("dragstart", this.onDragStart);
        // Capture phase, so it runs before an overlay's own handler and before the
        // anchor's default.
        this.stage.addEventListener("click", this.onClickCapture, true);
        // Capture phase: `focus` doesn't bubble, and this must run before the page parks its
        // tooltip at the node.
        this.stage.addEventListener("focus", this.onFocusCapture, true);
        window.addEventListener("pointermove", this.onPointerMove);
        window.addEventListener("pointerup", this.onPointerUp);
        window.addEventListener("pointercancel", this.onPointerCancel);
        // On the document (a mouse-zoomed page may have nothing focused) and in the capture phase,
        // so it runs before `design-page.js`'s listener and can see the selection mark; one press
        // then clears only the selection.
        document.addEventListener("keydown", this.onKeyDown, true);
        // The keyboard way in: every other gesture needs a pointer and the corner control is hidden
        // at 1:1. `+` / `-` / `0` on the page, reachable because every overlay is an anchor in the
        // tab order.
        this.page = this.closest<HTMLElement>("#cp-design-page") ?? this.stage;
        this.page.addEventListener("keydown", this.onPageKeyDown);
        this.addEventListener("focusout", this.onFocusOut);
        // A resize moves the pan limits and the reader's place (`rescale` preserves it). The
        // observer catches stage-only size changes; `resize` covers browsers without
        // `ResizeObserver`. Duplicate calls are no-ops.
        if (typeof ResizeObserver === "function") {
            this.observer = new ResizeObserver(this.onResize);
            this.observer.observe(this.stage);
        }
        window.addEventListener("resize", this.onResize);
        this.apply(this.view);
        return true;
    }

    disconnectedCallback(): void {
        this.stage?.removeEventListener("dblclick", this.onDblClick);
        this.stage?.removeEventListener("wheel", this.onWheel);
        this.stage?.removeEventListener("pointerdown", this.onPointerDown);
        this.stage?.removeEventListener("dragstart", this.onDragStart);
        this.stage?.removeEventListener("click", this.onClickCapture, true);
        this.stage?.removeEventListener("focus", this.onFocusCapture, true);
        window.removeEventListener("pointermove", this.onPointerMove);
        window.removeEventListener("pointerup", this.onPointerUp);
        window.removeEventListener("pointercancel", this.onPointerCancel);
        window.removeEventListener("resize", this.onResize);
        document.removeEventListener("keydown", this.onKeyDown, true);
        this.page?.removeEventListener("keydown", this.onPageKeyDown);
        this.removeEventListener("focusout", this.onFocusOut);
        this.observer?.disconnect();
        this.observer = null;
        this.installed = false;
        super.disconnectedCallback();
    }

    protected renderVue(): VNode {
        return h(Fragment, null, [
            h(
                "button",
                {
                    type: "button",
                    class: "cp-page-zoom-step",
                    "aria-label": "Zoom out",
                    onClick: () => this.step(1 / STEP),
                },
                "−",
            ),
            h(
                "span",
                {
                    class: "cp-page-zoom-level",
                    "data-cp-page-zoom-level": "",
                },
                `${this.percent}%`,
            ),
            h(
                "button",
                {
                    type: "button",
                    class: "cp-page-zoom-step",
                    "aria-label": "Zoom in",
                    onClick: () => this.step(STEP),
                },
                "+",
            ),
            h(
                "button",
                {
                    type: "button",
                    class: "cp-page-zoom-reset",
                    "data-cp-page-zoom-reset": "",
                    "aria-label": "Reset zoom",
                    onClick: () => this.reset(true),
                },
                "Reset",
            ),
        ]);
    }

    /**
     * Put the canvas exactly where `this.view` says, now. During the 170 ms transition
     * `getBoundingClientRect` returns interpolated positions while `this.view` holds the
     * destination, so a second drill mid-flight would over-zoom off centre. Kill the transition,
     * write the destination and force layout; the next move re-enables easing.
     */
    private settle(): void {
        if (!this.canvas) return;
        const { scale, x, y } = this.view;
        this.canvas.classList.add("cp-page-canvas-live");
        this.canvas.style.transform = `translate(${x}px, ${y}px) scale(${scale})`;
        // Reading a layout property is what flushes the style change; without it the
        // rects below are still the ones being animated away from.
        void this.canvas.getBoundingClientRect();
    }

    /**
     * The canvas's actual box: the stage's inner (padding) box, since `.cp-page-canvas` is `inset:
     * 0` inside a 1 px border. Clamping to the border box let the sheet be dragged past its edge.
     * `clientWidth`/`clientHeight` are 0 before layout, so the measured rect stands in.
     */
    private stageBox(): Box {
        const stage = this.stage;
        if (!stage) return { left: 0, top: 0, width: 0, height: 0 };
        const rect = stage.getBoundingClientRect();
        const style = getComputedStyle(stage);
        return {
            left: rect.left + (parseFloat(style.borderLeftWidth) || 0),
            top: rect.top + (parseFloat(style.borderTopWidth) || 0),
            width: stage.clientWidth || rect.width,
            height: stage.clientHeight || rect.height,
        };
    }

    /** Commit a view: transform the canvas, publish the scale, show or hide this bar. */
    private apply(next: View, eased = true): void {
        const box = this.stageBox();
        this.view = clamp(next, box);
        this.stageSize = {
            left: 0,
            top: 0,
            width: box.width,
            height: box.height,
        };
        // Back at 1:1 by any route ends the drill, so the next double-click starts fresh.
        if (!zoomed(this.view)) this.drilled = [];
        const { scale, x, y } = this.view;
        if (this.canvas) {
            // Continuous gestures drive the transform directly; discrete ones are eased so they
            // read as travel.
            this.canvas.classList.toggle("cp-page-canvas-live", !eased);
            this.canvas.style.transform = `translate(${x}px, ${y}px) scale(${scale})`;
        }
        // Read by the stylesheet to counter-scale marks drawn over the sheet (outlines, badges).
        this.stage?.style.setProperty("--cp-page-zoom", String(scale));
        this.stage?.classList.toggle("cp-page-zoomed", zoomed(this.view));
        // At 1:1 there is nothing to reset, so the bar hides, but not while focused: hiding the
        // focused element drops focus to `<body>`. It waits for focus to leave (see `onFocusOut`).
        this.hidden =
            !zoomed(this.view) && !this.contains(document.activeElement);
        this.percent = Math.round(scale * 100);
        this.requestUpdate();
    }

    private step(factor: number, eased = true): void {
        const box = this.stageBox();
        // A phone shows a horizontally scrollable slice of the sheet. Zoom about
        // that visible slice, not the middle of the offscreen full-width stage.
        const viewport = this.stage
            ?.closest(".cp-page-scroll")
            ?.getBoundingClientRect();
        const left = Math.max(box.left, viewport?.left ?? box.left);
        const right = Math.min(
            box.left + box.width,
            viewport?.right ?? box.left + box.width,
        );
        this.apply(
            zoomAbout(
                this.view,
                box,
                (left + right) / 2,
                box.top + box.height / 2,
                factor,
            ),
            eased,
        );
    }

    private reset(eased = true): void {
        // The stack is cleared by `apply` itself, which is what makes every other route
        // back to 1:1 behave like this one.
        this.apply(rest(), eased);
    }

    /**
     * The addressable elements under a point, outermost first. `elementsFromPoint` is the real hit
     * test, giving what is painted there and its ancestors (the drill chain). A bbox scan is the
     * fallback for unpainted ground. Sorted by area so both sources come out outermost first.
     */
    private chainAt(clientX: number, clientY: number): Array<Level<Element>> {
        const svg = this.svg;
        if (!svg) return [];
        const hit =
            typeof document.elementsFromPoint === "function"
                ? document.elementsFromPoint(clientX, clientY)
                : [];
        // The topmost painted element and its own ancestors, a lineage: `elementsFromPoint` also
        // returns overlapping siblings, which area ordering would turn into a false parent-child
        // chain.
        const top = hit.find((el) => el !== svg && svg.contains(el));
        if (top) {
            const lineage: Element[] = [];
            for (
                let el: Element | null = top;
                el && el !== svg;
                el = el.parentElement
            ) {
                if (el.hasAttribute("data-node-id")) lineage.unshift(el);
            }
            if (lineage.length) return lineage.map((node) => this.level(node));
        }
        // Nothing addressable painted here (gaps between specimens): fall back to every box
        // containing the point, outermost first.
        return Array.from(svg.querySelectorAll("[data-node-id]"))
            .filter((el) => {
                const box = el.getBoundingClientRect();
                return (
                    box.width > 0 &&
                    box.height > 0 &&
                    clientX >= box.left &&
                    clientX <= box.left + box.width &&
                    clientY >= box.top &&
                    clientY <= box.top + box.height
                );
            })
            .map((node) => this.level(node))
            .filter((level) => level.box.width * level.box.height > 0)
            .sort(
                (a, b) =>
                    b.box.width * b.box.height - a.box.width * a.box.height,
            );
    }

    private level(node: Element): Level<Element> {
        return { node, box: node.getBoundingClientRect() as Box };
    }

    /**
     * A double-click over a component slot doesn't drill: its first click already navigated, since
     * every overlay is a real `<a>`. Delaying navigation to detect double-clicks would slow every
     * link and break middle/modifier clicks. Drilling is a sheet gesture; going is a slot gesture.
     */
    private drill(event: MouseEvent): void {
        // The controls aren't the sheet: a quick double click on `+` must not drill (same guard as
        // `startPan`).
        if (this.contains(event.target as Node)) return;
        // A second double-click can land inside the first one's travel; measure the
        // sheet where it is going, not where it currently is.
        this.settle();
        const target =
            event.altKey || event.shiftKey
                ? null
                : this.drillIn(event.clientX, event.clientY);
        if (target) {
            this.apply(frameRect(this.view, this.stageBox(), target.box));
        } else if (!this.drillOut(event.clientX, event.clientY)) {
            return;
        }
        // A double-click selects a word by default, and on a sheet of outlined text
        // that leaves a blue smear across whatever was just zoomed to.
        window.getSelection()?.removeAllRanges();
    }

    private drillIn(clientX: number, clientY: number): Level<Element> | null {
        const chain = this.chainAt(clientX, clientY);
        if (!chain.length || !this.svg) return null;
        // The deepest entered level still containing this point; elsewhere, the drill restarts from
        // the outermost frame there.
        let start = -1;
        for (let i = this.drilled.length - 1; i >= 0; i--) {
            const at = chain.findIndex(
                (level) => level.node === this.drilled[i],
            );
            if (at >= 0) {
                start = at;
                this.drilled.length = i + 1;
                break;
            }
        }
        if (start < 0) this.drilled = [];
        const level = pickLevel(
            chain,
            start,
            this.stageBox(),
            this.svg.getBoundingClientRect(),
            this.view.scale,
        );
        if (level) this.drilled.push(level.node);
        return level;
    }

    /** Back out one level, to the frame this one was entered from. */
    private drillOut(clientX: number, clientY: number): boolean {
        if (!this.drilled.length) {
            if (!zoomed(this.view)) return false;
            // Zoomed by wheel or button rather than by drilling, so there is no
            // level to return to: step out about the pointer instead.
            this.apply(
                zoomAbout(
                    this.view,
                    this.stageBox(),
                    clientX,
                    clientY,
                    1 / STEP,
                ),
            );
            return true;
        }
        this.drilled.pop();
        const back = this.drilled[this.drilled.length - 1];
        if (back) {
            this.apply(
                frameRect(
                    this.view,
                    this.stageBox(),
                    back.getBoundingClientRect(),
                ),
            );
        } else {
            this.apply(rest());
        }
        return true;
    }

    private wheel(event: WheelEvent): void {
        // The modifier is the contract: without it the wheel scrolls the document. Trackpad pinch
        // arrives as Ctrl+wheel, so pinch-to-zoom works too.
        if (!event.ctrlKey && !event.metaKey) return;
        event.preventDefault();
        const box = this.stageBox();
        // `deltaMode` may be lines or pages (±1 per notch); normalise, or the zoom barely moves.
        const unit =
            event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? box.height : 1;
        this.apply(
            zoomAbout(
                this.view,
                box,
                event.clientX,
                event.clientY,
                Math.exp(-event.deltaY * unit * 0.0022),
            ),
            false,
        );
    }

    /**
     * Pointer capture is taken late, only once the pointer has travelled. Capturing on
     * `pointerdown` retargets the following `click` to the capture element, so slot links and Reset
     * stopped working while zoomed.
     */
    private startPan(event: PointerEvent): void {
        // A pan released outside the window produces no click to spend the guard; clear it here.
        this.swallowClick = false;
        if (!zoomed(this.view) || event.button !== 0) return;
        // Dragging from a control is a mis-click, not a pan.
        if (this.contains(event.target as Node)) return;
        this.panning = {
            id: event.pointerId,
            x: event.clientX,
            y: event.clientY,
            fromX: event.clientX,
            fromY: event.clientY,
            moved: 0,
            held: false,
        };
    }

    private movePan(event: PointerEvent): void {
        const pan = this.panning;
        if (!pan || event.pointerId !== pan.id) return;
        // The button was released somewhere unseen (outside the window before capture), so stop;
        // otherwise the next move pans with no button held.
        if (!(event.buttons & 1)) {
            this.panning = null;
            this.stage?.classList.remove("cp-page-panning");
            return;
        }
        const dx = event.clientX - pan.x;
        const dy = event.clientY - pan.y;
        pan.x = event.clientX;
        pan.y = event.clientY;
        // The furthest the pointer has strayed from its start, not total travel: jitter must not
        // become a drag that swallows a tap.
        pan.moved = Math.max(
            pan.moved,
            Math.abs(event.clientX - pan.fromX) +
                Math.abs(event.clientY - pan.fromY),
        );
        if (pan.moved > DRAG_SLOP && !pan.held) {
            pan.held = true;
            try {
                this.stage?.setPointerCapture?.(event.pointerId);
            } catch {
                // The pointer is gone; the pan still works off the window listeners.
            }
            this.stage?.classList.add("cp-page-panning");
        }
        this.apply(
            { ...this.view, x: this.view.x + dx, y: this.view.y + dy },
            false,
        );
    }

    /**
     * `clicks` says whether this sequence can still produce a click; a `pointercancel` can't, so
     * the guard isn't armed there.
     */
    private endPan(event: PointerEvent, clicks: boolean): void {
        const pan = this.panning;
        if (!pan || event.pointerId !== pan.id) return;
        // A pan that moved must not also navigate via the overlay under the pointer; under the
        // threshold it was a wobbly click.
        if (clicks && pan.moved > DRAG_SLOP) this.swallowClick = true;
        if (pan.held && this.stage?.hasPointerCapture?.(event.pointerId)) {
            this.stage.releasePointerCapture(event.pointerId);
        }
        this.panning = null;
        this.stage?.classList.remove("cp-page-panning");
    }

    /**
     * Escape unwinds one thing at a time: the selection (`design-page.js`'s, detected by its mark),
     * then the zoom.
     */
    private escape(event: KeyboardEvent): void {
        if (event.key !== "Escape" || !zoomed(this.view)) return;
        if (this.stage?.querySelector(".cp-page-selected")) return;
        this.reset();
    }

    /**
     * `+` / `-` / `0` mirror the corner buttons about the middle of the view. Ignored while a form
     * control has focus.
     */
    private shortcut(event: KeyboardEvent): void {
        if (event.metaKey || event.ctrlKey || event.altKey) return;
        const target = event.target as HTMLElement | null;
        if (target?.closest("input, textarea, select, [contenteditable]"))
            return;
        if (event.key === "+" || event.key === "=") this.step(STEP, false);
        else if (event.key === "-" || event.key === "_")
            this.step(1 / STEP, false);
        else if (event.key === "0") this.reset(false);
        else return;
        event.preventDefault();
        this.reparkTip();
    }

    /**
     * Re-dispatch `focus` on the focused node so `design-page.js` re-parks its tooltip after a
     * keyboard zoom moved the node (hence those steps are un-eased).
     */
    private reparkTip(): void {
        const focused = document.activeElement;
        if (!focused || !this.canvas?.contains(focused)) return;
        focused.dispatchEvent(new FocusEvent("focus"));
    }

    /** Pan a focused node into view. Does nothing at 1:1, or off the sheet. */
    private reveal(target: Element | null): void {
        if (!target || !zoomed(this.view)) return;
        this.settle();
        // Only the sheet's overlays; a focused audit-list row isn't something to pan to.
        if (!this.canvas?.contains(target)) return;
        const delta = revealDelta(
            target.getBoundingClientRect(),
            this.stageBox(),
        );
        if (!delta) return;
        // Not eased: `design-page.js` measures the node on the same focus event, so the box must
        // already be final.
        this.apply(
            {
                ...this.view,
                x: this.view.x + delta.x,
                y: this.view.y + delta.y,
            },
            false,
        );
    }
}

declare global {
    interface HTMLElementTagNameMap {
        "cp-page-zoom": PageZoom;
    }
}
