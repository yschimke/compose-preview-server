// Which surface `<cp-inspect-layers>` draws over: the viewer, or the focused comparison's Actual
// panel, whose frame, legend and toggles live elsewhere. The wiring is a descriptor resolved at
// install; an attribute-less tag gets the viewer's, and everything downstream is page-agnostic.

import { baseFrom } from "./layers.js";

/**
 * How a layer is positioned over its frame. `offset` (viewer): the frame is centred in a wider
 * stage, so the layer sits at the image's `offsetLeft`/`offsetTop`. `centred`: the panel's CSS
 * already centres it, so only the size is set (`left`/`top` would fight its `translate(-50%,
 * -50%)`).
 */
export type LayerAnchor = "offset" | "centred";

/** The DOM one mounted set of inspection layers works against. */
export interface InspectHost {
    /** Carries `data-inspect` while a layer is on, and names the preview being inspected. */
    root: HTMLElement;
    /** The Compose render every non-spec layer is placed over. */
    frame: HTMLImageElement;
    /** The alternate raster the viewer's spec view puts on the stage. Null off the viewer. */
    specFrame: HTMLImageElement | null;
    /** Absolutely-positioned container the boxes are appended to. */
    layer: HTMLElement;
    /** The readable half — one section per layer, one row per box. */
    legend: HTMLElement;
    /** The checkboxes that decide which layers are on, each carrying `data-cp-inspect`. */
    toggles: HTMLInputElement[];
    /**
     * The attribute holding the URL of the frame currently decoded: `data-cp-src` on the viewer
     * (stamped after a swap decodes), `src` on a server-rendered panel that never swaps.
     */
    frameSource: string;
    /** Whether this host has the viewer's spec / comparison modes at all. */
    hasSpecModes: boolean;
    /** See [LayerAnchor]. */
    anchor: LayerAnchor;
    /**
     * Whether a click on a box means "report this part of the render".
     *
     * False on the viewer, where a box is a reading aid and a click means nothing — and where
     * adding one would change a shipped page's behaviour for no reason. True on the focused
     * comparison, which is the page a report is filed from: the brief's two ways to choose are
     * clicking an annotated element and dragging a region, and this is the first of them.
     */
    selectable: boolean;
    /** The prefix render URLs hang off, for the address to use before any frame has decoded. */
    base: string;
}

/** The viewer's wiring; null when a load-bearing part is missing, making the tag inert. */
export function viewerHost(): InspectHost | null {
    const root = document.querySelector<HTMLElement>(".cp-viewer");
    const frame = document.getElementById("cp-img") as HTMLImageElement | null;
    const layer = document.getElementById("cp-inspect-layer");
    const legend = document.getElementById("cp-inspect-legend");
    const toggles = Array.from(
        document.querySelectorAll<HTMLInputElement>(".cp-inspect"),
    );
    if (!root || !frame || !layer || !legend || !toggles.length) return null;
    return {
        root,
        frame,
        specFrame: document.getElementById(
            "cp-spec-img",
        ) as HTMLImageElement | null,
        layer,
        legend,
        toggles,
        frameSource: "data-cp-src",
        hasSpecModes: true,
        anchor: "offset",
        selectable: false,
        base: baseFrom(location.pathname),
    };
}

/**
 * A panel's wiring, named by selectors on the mount tag because the parts don't nest. Anything not
 * named makes the mount inert rather than defaulting to viewer ids.
 */
export function panelHost(mount: HTMLElement): InspectHost | null {
    const select = <T extends Element>(name: string): T | null => {
        const selector = mount.getAttribute(name);
        return selector ? document.querySelector<T>(selector) : null;
    };
    const root = select<HTMLElement>("data-cp-host");
    const layer = select<HTMLElement>("data-cp-layer");
    const legend = select<HTMLElement>("data-cp-legend");
    const toggleSelector = mount.getAttribute("data-cp-toggles");
    const toggles = toggleSelector
        ? Array.from(
              document.querySelectorAll<HTMLInputElement>(toggleSelector),
          )
        : [];
    if (!root || !layer || !legend || !toggles.length) return null;
    const frame = root.querySelector("img");
    if (!frame) return null;
    return {
        root,
        frame,
        specFrame: null,
        layer,
        legend,
        toggles,
        frameSource: "src",
        hasSpecModes: false,
        anchor: "centred",
        selectable: mount.hasAttribute("data-cp-selectable"),
        base: mount.getAttribute("data-cp-base") ?? baseFrom(location.pathname),
    };
}

/** The host a mounted tag means: the one it names, else the viewer's. */
export function resolveHost(mount: HTMLElement): InspectHost | null {
    return mount.hasAttribute("data-cp-host") ? panelHost(mount) : viewerHost();
}
