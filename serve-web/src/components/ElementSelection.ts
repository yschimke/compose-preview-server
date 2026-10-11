// `<cp-element-selection>` — choosing which element the focused comparison's report is about, so
// the `compose-parity-locator/v1` block can name it and the region it covers.
//
//   A TAG survives a re-render, so an acceptance can resolve it later. Offered only where the
//   server emits `data-cp-tags`; a tag on more than one node is listed but never selectable.
//
//   A REGION is a rectangle over the displayed pixels, converted into the render's own pixel plane
//   before it is recorded (`v1` accepts no other space).
//
// Selection is a report affordance only: nothing here accepts or scores a difference.
//
// Decisions live in `report/elementTargets.ts`, `report/locator.ts` and `report/body.ts`.

import { ControllerElement, customElement } from "../controllerElement.js";
import { whenParsed } from "../dom/whenParsed.js";
import { reportBody } from "../report/body.js";
import type { Selection } from "../report/locator.js";
import {
    renderRectBetween,
    tagTargets,
    toRenderPoint,
    type TagTarget,
} from "../report/elementTargets.js";

@customElement("cp-element-selection")
export class ElementSelection extends ControllerElement {
    private installed = false;
    private root: HTMLElement | null = null;
    private frame: HTMLImageElement | null = null;
    private layer: HTMLElement | null = null;
    private picker: HTMLSelectElement | null = null;
    private dragButton: HTMLButtonElement | null = null;
    private clearButton: HTMLButtonElement | null = null;
    private state: HTMLElement | null = null;
    private targets: TagTarget[] = [];
    private selection: Selection = {};
    private cleanups: Array<() => void> = [];

    connectedCallback(): void {
        super.connectedCallback();
        if (!this.install()) void whenParsed().then(() => this.install());
    }

    disconnectedCallback(): void {
        for (const off of this.cleanups) off();
        this.cleanups = [];
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
        const root = document.getElementById("cp-element-selection");
        const panel = document.getElementById("cp-compare-actual");
        if (!root || !panel) return false;
        this.installed = true;
        this.root = root;
        this.frame = panel.querySelector("img");
        this.layer = document.getElementById("cp-selection-layer");
        this.picker =
            root.querySelector<HTMLSelectElement>(".cp-selection-tag");
        this.dragButton =
            root.querySelector<HTMLButtonElement>(".cp-selection-drag");
        this.clearButton = root.querySelector<HTMLButtonElement>(
            ".cp-selection-clear",
        );
        this.state = root.querySelector<HTMLElement>(".cp-selection-state");

        if (this.picker) this.on(this.picker, "change", () => this.chooseTag());
        if (this.dragButton)
            this.on(this.dragButton, "click", () => this.startDrag());
        if (this.clearButton)
            this.on(this.clearButton, "click", () => this.clear());
        // A reflow moves the frame under an already-drawn marquee, so the box has to be re-placed
        // rather than left where the pointer put it.
        this.on(window, "resize", () => this.placeMarquee());
        // Clicking an annotated element, the second way to choose besides dragging:
        // `<cp-inspect-layers>` raises this from a box on the Actual panel when its host is
        // selectable.
        this.on(window, "cp-element-pick", ((event: CustomEvent) =>
            this.pickAnnotation(event.detail?.bounds)) as EventListener);

        void this.loadTags();
        return true;
    }

    // ---- tags ----------------------------------------------------------------

    /**
     * Fetch the published tag index and fill the picker. Without `data-cp-tags` the server is
     * saying a tag selection wouldn't describe this frame (overridden, pinned, or no index), so the
     * picker stays hidden; not the same as "no tags" (see `ServeWeb.referenceComparisonPage`'s
     * `tagIndexUrl`).
     */
    private async loadTags(): Promise<void> {
        const url = this.root?.getAttribute("data-cp-tags");
        const picker = this.picker;
        if (!url || !picker) return;
        let payload: unknown = null;
        try {
            const response = await fetch(url, { credentials: "same-origin" });
            if (!response.ok) return;
            payload = await response.json();
        } catch {
            // A host that can't answer leaves the page as is; an empty picker would falsely claim
            // no tagged elements.
            return;
        }
        this.targets = tagTargets(payload);
        if (!this.targets.length) return;
        for (const target of this.targets) {
            const option = document.createElement("option");
            option.value = target.tag;
            // The tag verbatim, and the count only when it is the thing that disqualifies it.
            option.textContent = target.ambiguous
                ? `${target.tag} — ${target.count} nodes, not unique`
                : target.tag;
            // Listed but not choosable. Hiding an ambiguous tag entirely would leave someone
            // hunting for a tag they can see in the code; saying why is what lets them fix it.
            option.disabled = target.ambiguous;
            picker.appendChild(option);
        }
        picker.hidden = false;
    }

    /**
     * A click on an annotated element, recorded as a REGION.
     *
     * A region and not an element, because an annotation carries no `testTag`: it is typography or
     * a resolved container projected from the semantics tree, so there is no identity to name and
     * claiming one would invent it. That makes this weaker than a tag selection — a geometric
     * acceptance with no element gate — which is exactly why the tag picker exists beside it and why
     * the brief insists selection be drivable from the index rather than from boxes alone.
     *
     * The bounds need no conversion: annotation bounds are already in the render's own pixel space,
     * the plane `v1` accepts.
     *
     * Replaces any chosen tag, for the same reason a drag does: `bounds` is the selected element's
     * authoring-time baseline, so pairing a tag with a rectangle that is not that element's box
     * records a baseline the element never had.
     */
    private pickAnnotation(bounds: unknown): void {
        const box = bounds as
            { x: number; y: number; width: number; height: number } | undefined;
        if (!box) return;
        if (this.picker) this.picker.value = "";
        this.apply({
            bounds: {
                x: Math.trunc(box.x),
                y: Math.trunc(box.y),
                width: Math.trunc(box.width),
                height: Math.trunc(box.height),
            },
        });
    }

    private chooseTag(): void {
        const tag = this.picker?.value ?? "";
        const target = this.targets.find((entry) => entry.tag === tag);
        if (!tag || !target) return this.clear();
        // An ambiguous tag reaching here (keyboard, script, ignored `disabled`) must not become a
        // selector.
        if (target.ambiguous) return this.clear();
        // `bounds` may be absent — a tag whose every carrying node had a zero-area box still counts
        // — and an element with no region is a perfectly good record. It is `count` that makes a
        // tag an identity, not its geometry.
        this.apply({ element: target.tag, bounds: target.bounds });
    }

    // ---- region --------------------------------------------------------------

    /**
     * Drag a rectangle over the Actual frame.
     *
     * Over the *frame*, not the viewport: the rectangle has to be expressible in the render's own
     * pixels, and only the frame's box has a scale to convert by. The overlay takes the pointer
     * itself so a drag that wanders over the panel's own controls does not end in a click on them.
     *
     * A drag that leaves the frame still finishes. The gesture STARTS on the layer — only the frame
     * is a place to begin selecting — but the move and release are watched on `window`, because a
     * `pointerup` released outside the layer's subtree never reaches it in any phase: the overlay
     * stayed up and the region was silently lost, and releasing just past the edge is the ordinary
     * way to select something flush with it. `setPointerCapture` is set as well where the engine
     * has it, which keeps the events off the page underneath; the window listeners are what make
     * the gesture correct without it. Coordinates are clamped to the frame throughout, so a
     * rectangle dragged past the boundary stops at it rather than describing pixels that are not in
     * the render.
     */
    private startDrag(): void {
        const layer = this.layer;
        const frame = this.frame;
        if (!layer || !frame) return;
        layer.hidden = false;
        layer.textContent = "";
        this.sizeLayer();
        const box = document.createElement("div");
        box.className = "cp-selection-marquee";
        box.hidden = true;
        layer.appendChild(box);
        this.root?.setAttribute("data-dragging", "on");
        this.say("Drag a box over the render · Esc to cancel");

        let start: { x: number; y: number } | null = null;
        // The origin in the RENDER plane, converted the moment it is touched. The display-space
        // `start` above is only for drawing the marquee; recording from it would measure an origin
        // captured against the old frame box with a scale taken from the new one if the frame
        // reflows mid-gesture — two coordinate systems in one rectangle, naming a region nobody
        // selected. The render plane is a property of the render, so a point converted early stays
        // valid however the display subsequently resizes.
        let startRender: { x: number; y: number } | null = null;
        // The gesture belongs to one pointer: with `touch-action: none` a second finger is
        // reachable and would otherwise reset the origin.
        let owner: number | null = null;
        const mine = (event: PointerEvent) =>
            owner === null || event.pointerId === owner;
        const offs: Array<() => void> = [];
        // The frame may still be decoding; `load` and the observer keep the overlay matched, or the
        // surface is 0x0.
        const resizes =
            typeof ResizeObserver === "function"
                ? new ResizeObserver(() => this.sizeLayer())
                : null;
        resizes?.observe(frame);
        const onFrameLoad = () => this.sizeLayer();
        frame.addEventListener("load", onFrameLoad);
        const stop = () => {
            start = null;
            startRender = null;
            for (const off of offs) off();
            frame.removeEventListener("load", onFrameLoad);
            resizes?.disconnect();
            layer.hidden = true;
            layer.textContent = "";
            this.root?.removeAttribute("data-dragging");
        };
        const local = (event: PointerEvent) => {
            const rect = frame.getBoundingClientRect();
            return {
                x: clamp(event.clientX - rect.left, 0, rect.width),
                y: clamp(event.clientY - rect.top, 0, rect.height),
            };
        };
        const draw = (
            a: { x: number; y: number },
            b: { x: number; y: number },
        ) => {
            box.hidden = false;
            box.style.left = `${Math.min(a.x, b.x)}px`;
            box.style.top = `${Math.min(a.y, b.y)}px`;
            box.style.width = `${Math.abs(a.x - b.x)}px`;
            box.style.height = `${Math.abs(a.y - b.y)}px`;
        };
        const listen = (
            target: EventTarget,
            type: string,
            handler: EventListener,
        ) => {
            target.addEventListener(type, handler, true);
            offs.push(() => target.removeEventListener(type, handler, true));
        };
        listen(layer, "pointerdown", ((event: PointerEvent) => {
            // A second contact during a live drag is ignored, not adopted.
            if (start) return;
            owner = event.pointerId;
            start = local(event);
            startRender = toRenderPoint(start, frame);
            // Capture so moves keep arriving outside the frame; guarded for engines without it.
            layer.setPointerCapture?.(event.pointerId);
            draw(start, start);
        }) as EventListener);
        listen(window, "pointermove", ((event: PointerEvent) => {
            if (start && mine(event)) draw(start, local(event));
        }) as EventListener);
        listen(window, "pointerup", ((event: PointerEvent) => {
            if (!start || !mine(event)) return;
            const endRender = toRenderPoint(local(event), frame);
            const origin = startRender;
            stop();
            const bounds =
                origin && endRender
                    ? renderRectBetween(origin, endRender)
                    : null;
            // A click with no drag is a cancel, not an error — that is what a stray click on a
            // full-frame overlay IS.
            if (!bounds) return this.describe();
            // A drag replaces the selection, tag included: `bounds` is the element's authoring-time
            // baseline, and pairing a tag with a rectangle that isn't its box would later report
            // the element as moved.
            if (this.picker) this.picker.value = "";
            this.apply({ bounds });
        }) as EventListener);
        // A capture lost to the system (a context menu, a device switch, another element taking it)
        // ends the gesture rather than leaving a live overlay with no way to finish it.
        listen(window, "pointercancel", ((event: PointerEvent) => {
            if (!mine(event)) return;
            stop();
            this.describe();
        }) as EventListener);
        listen(window, "keydown", ((event: KeyboardEvent) => {
            if (event.key !== "Escape") return;
            event.preventDefault();
            stop();
            this.describe();
        }) as EventListener);
    }

    /**
     * Match the overlay to the frame's current box; re-run because the image may not have decoded
     * when the drag is armed (zero box). `startDrag` keeps it in step.
     */
    private sizeLayer(): void {
        const layer = this.layer;
        const frame = this.frame;
        if (!layer || !frame) return;
        layer.style.width = `${frame.clientWidth}px`;
        layer.style.height = `${frame.clientHeight}px`;
    }

    private placeMarquee(): void {
        if (this.root?.getAttribute("data-dragging") === "on") this.sizeLayer();
    }

    // ---- what the report carries ---------------------------------------------

    private clear(): void {
        if (this.picker) this.picker.value = "";
        this.apply({});
    }

    private apply(selection: Selection): void {
        this.selection = selection;
        reportBody.set({ selection });
        this.describe();
        if (this.clearButton)
            this.clearButton.hidden = !selection.element && !selection.bounds;
    }

    /** The status line — the only place the page says what the report will actually name. */
    private describe(): void {
        const { element, bounds } = this.selection;
        const region = bounds
            ? `${bounds.width}×${bounds.height} at ${bounds.x},${bounds.y} in render pixels`
            : "";
        if (element && region)
            return this.say(`Reporting “${element}” · ${region}.`);
        if (element) return this.say(`Reporting “${element}”.`);
        if (region) return this.say(`Reporting a region · ${region}.`);
        this.say("Reporting the whole render.");
    }

    private say(text: string): void {
        if (this.state) this.state.textContent = text;
    }
}

function clamp(value: number, low: number, high: number): number {
    return Math.min(Math.max(value, low), high);
}

declare global {
    interface HTMLElementTagNameMap {
        "cp-element-selection": ElementSelection;
    }
}
