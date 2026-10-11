// "Grid": a faint 8dp grid, with 16dp side keylines, laid over the render on the stage.
//
// Checking a layout against Material's 8dp rhythm by eye is guesswork: is that card's inset 16dp or
// 14? A grid answers it at a glance, but only if one cell is one 8dp step OF THE RENDER — not 8 CSS
// pixels of the page. The PNG on stage is `dp × density` pixels and is then scaled again to fit the
// stage (or the screen, presenting), so a grid cell is `8 × density × (shown px / natural px)` CSS
// pixels, measured per axis from the image's real box every time that box can have moved.
//
// The overlay is one absolutely positioned `<div>` whose background is two repeating gradients, so
// drawing it costs no canvas and no per-frame work; this module only keeps its box and its cell
// size (`--cp-grid-x` / `--cp-grid-y`) in step with the image, and serve.css does the painting.
//
// Like the Present button it lives inside the stage, so it rides into full screen with the render.
// Presenting is for showing a design to someone, not measuring it, so the grid starts hidden there
// whatever the page had; the button (or G) still shows it for that session of presenting.

const STORAGE_KEY = "cp-grid-overlay";
/** Material's layout step. The keylines sit two steps in from each side. */
export const GRID_DP = 8;

/** The box the decoded pixels actually occupy within the image element, in element-local px. */
export interface ContentBox {
    left: number;
    top: number;
    width: number;
    height: number;
}

/**
 * Where an image's pixels land inside its element box. `object-fit: contain` (the full-screen stage
 * uses it) letterboxes them, centred; anything else here stretches them to the box, which for this
 * stage is the same aspect anyway, since the viewer only ever caps a dimension and lets the other
 * follow.
 */
export function contentBox(
    boxWidth: number,
    boxHeight: number,
    naturalWidth: number,
    naturalHeight: number,
    objectFit: string,
): ContentBox {
    if (objectFit !== "contain" || !naturalWidth || !naturalHeight)
        return { left: 0, top: 0, width: boxWidth, height: boxHeight };
    const scale = Math.min(boxWidth / naturalWidth, boxHeight / naturalHeight);
    const width = naturalWidth * scale;
    const height = naturalHeight * scale;
    return {
        left: (boxWidth - width) / 2,
        top: (boxHeight - height) / 2,
        width,
        height,
    };
}

/**
 * The density of the render on stage when a Device override is picked: its option names the size
 * in dp (`Pixel 5 · compact phone (393 × 851 dp)`), and the PNG is that size in px, so the ratio of
 * the long edges is the density — whichever way round Orientation turned it. Null for no option or
 * one with no size (`(preview default)`), which leaves the page's own `data-render-density` to answer.
 */
export function densityFromDeviceLabel(
    option: Pick<Element, "textContent"> | null | undefined,
    img: Pick<HTMLImageElement, "naturalWidth" | "naturalHeight">,
): number | null {
    const m = /\(([\d.]+) × ([\d.]+) dp\)/.exec(option?.textContent || "");
    const dpLong = m ? Math.max(+m[1], +m[2]) : 0;
    const pxLong = Math.max(img.naturalWidth, img.naturalHeight);
    return dpLong > 0 && pxLong > 0 ? pxLong / dpLong : null;
}

function stored(): boolean {
    try {
        return localStorage.getItem(STORAGE_KEY) === "1";
    } catch (_) {
        return false;
    }
}

function store(on: boolean): void {
    try {
        localStorage.setItem(STORAGE_KEY, on ? "1" : "0");
    } catch (_) {
        /* The choice still holds for this page. */
    }
}

/**
 * Add the grid overlay and its toggle to [stage], measured off [img]. [density] answers the
 * render's px-per-dp at the moment of measuring (it changes with a Device override).
 */
export function installGridOverlay(
    stage: HTMLElement | null,
    img: HTMLImageElement | null,
    density: () => number,
): void {
    if (!stage || !img) return;
    const grid = document.createElement("div");
    grid.className = "cp-grid-overlay";
    const button = document.createElement("button");
    button.type = "button";
    button.className = "cp-grid-toggle";
    button.textContent = "#";

    let on = stored();
    // Presenting starts with the grid off; this is the toggle's state for the current full screen.
    let presentOn = false;
    const presenting = () => document.fullscreenElement === stage;
    const shown = () => (presenting() ? presentOn : on);

    const place = () => {
        const nw = img.naturalWidth;
        const nh = img.naturalHeight;
        const r = img.getBoundingClientRect();
        const d = density();
        if (!shown() || !nw || !nh || !r.width || !r.height || !(d > 0)) {
            grid.hidden = true;
            return;
        }
        const box = contentBox(
            r.width,
            r.height,
            nw,
            nh,
            getComputedStyle(img).objectFit,
        );
        const sr = stage.getBoundingClientRect();
        // Relative to the stage's padding box, as the live overlays are: subtract its border. One
        // cell is 8dp: `8 × density` natural px, times the shown/natural scale on that axis.
        grid.style.cssText = `left:${r.left - sr.left - stage.clientLeft + box.left}px;top:${r.top - sr.top - stage.clientTop + box.top}px;width:${box.width}px;height:${box.height}px;--cp-grid-x:${(GRID_DP * d * box.width) / nw}px;--cp-grid-y:${(GRID_DP * d * box.height) / nh}px`;
        grid.hidden = false;
    };

    const sync = () => {
        const visible = shown();
        // The density in the tooltip says what a cell assumes: a page that cannot know the render's
        // density falls back to 2 (`data-render-density`), and this is where that would show.
        const label =
            (visible ? "Hide" : "Show") +
            ` the 8dp grid (${+density().toFixed(3)} px per dp)`;
        button.setAttribute("aria-pressed", String(visible));
        button.setAttribute("aria-label", label);
        button.title = label;
        place();
    };

    button.addEventListener("click", () => {
        if (presenting()) presentOn = !presentOn;
        else store((on = !on));
        sync();
    });
    document.addEventListener("fullscreenchange", () => {
        presentOn = false;
        sync();
    });
    // The image's box moves on a new render, a window resize, the fit cap, and full screen; the
    // stage's on a drawer opening beside it. A ResizeObserver hears all of those that change a size,
    // and `load`/`resize` cover the rest (and browsers without one). A new render can also be at a
    // new density (a picked Device), so `load` re-reads the tooltip too.
    img.addEventListener("load", sync);
    window.addEventListener("resize", place);
    if (typeof ResizeObserver === "function") {
        const observer = new ResizeObserver(() => place());
        observer.observe(img);
        observer.observe(stage);
    }
    stage.append(grid, button);
    sync();
}
