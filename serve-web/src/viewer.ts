import { trackInteraction } from "./analyticsClient.js";
// The preview viewer: the stage, its lanes, and every control that changes what is on it.
//
// Imperative rather than a Vue element on purpose: every control is server-rendered by
// `ServeWeb.viewerPage`, and this file is behaviour over that markup. DOM-free decisions live in
// `viewer/rules.js`, each with its own tests.

// Types only: the player bundle is script-injected at runtime, never imported.
import { renderRequest } from "./viewer/renderRequest.js";
import type { RcPlayer, RemoteContext } from "./rc/player.js";
import {
    FrameQueue,
    frameBlob,
    pumpFrames,
    shouldPaintDecodedFrame,
    type ServeFrame,
} from "./live/framePainter.js";
import { visibilityMessage } from "./live/session.js";
import * as rules from "./viewer/rules.js";
import { CONTINUOUS_EDIT_DEBOUNCE_MS, debounced } from "./viewer/debounce.js";
import { viewParam } from "./spec/views.js";
import {
    KIT_SOURCE,
    activeSource,
    changesSource,
    closesSource,
    isSpecSource,
    sourceForParam,
    sourceParam,
    sourcesOrFallback,
    offersChoice,
    sourceNote,
    type SpecSource,
} from "./spec/sources.js";
import { type ApiDocLink, usableApiDocs } from "./viewer/apiDocs.js";
import { reportBody } from "./report/body.js";
import { withStage } from "./annotate/report.js";
import { isTransparent } from "./backgroundChoice.js";
import { installWebShare } from "./viewer/webShare.js";
import { pageWakeHold } from "./viewer/wakeLock.js";
import { writeThemeMemory } from "./chrome/themeMemory.js";
import { fitInk, imageInk, type InkBounds } from "./design/ink.js";
import { compareApi } from "./compare/api.js";
import { chipText, matchBand } from "./spec/verdict.js";
import {
    effectiveUnseeded,
    isChecked,
    knobHydratedValue,
    rcHydratedValue,
    unseededOverrides,
} from "./viewer/overrideSeeds.js";

// Typed handles onto the server-rendered markup this file drives.
//
// `must` is for handles every viewer page renders (stage image, canvas, status line): it asserts,
// since without them the page is not a viewer. `may` is for lane-specific handles, and its `| null`
// keeps callers' guards honest. Inside a lane a `may` handle is asserted with `!`: the server emits
// a lane's elements as a set, and those bodies already sit behind the lane's guard.
function must<T extends HTMLElement>(id: string): T {
    return document.getElementById(id) as T;
}
// The screen stays on while a live stream or a motion capture is running (see `wakeLock.ts`).
var wakeHold = pageWakeHold();
function may<T extends HTMLElement>(id: string): T | null {
    return document.getElementById(id) as T | null;
}

/** A bag of `/render` override parameters, keyed by the daemon's own parameter names. */
type Overrides = Record<string, string>;

/** Any form control on the viewer's panels: a knob row, an RC seed, an overlay tick, a mode radio. */
type Control = HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement;

/** Every control matching a panel selector. */
function controls(selector: string): NodeListOf<Control> {
    return document.querySelectorAll<Control>(selector);
}

/** Controls that are always a checkbox or a radio — the overlay ticks and the mode radios. */
function ticks(selector: string): NodeListOf<HTMLInputElement> {
    return document.querySelectorAll<HTMLInputElement>(selector);
}

/**
 * A control's value as every override map here wants it: a checkbox's tick as the string the daemon
 * parses, otherwise its text.
 */
function controlValue(el: Control): string {
    if (el instanceof HTMLInputElement && el.type === "checkbox")
        return el.checked ? "true" : "false";
    return el.value;
}

/**
 * A `/render` the server refused, carried as an Error so the single `.catch` below decides what to
 * say and whether to retry. `cpFailure` is attached for every non-ok response, including the `503`
 * + `Retry-After` refusal a busy server returns.
 */
interface SnapshotFailureError extends Error {
    cpFailure?: rules.SnapshotFailure;
}

/**
 * The `/usage/<id>` payload, mirroring `UsageSnippetResponse` in `ServeHttpServer.kt`. Fields are
 * optional so an older server or an underivable snippet degrades to the panel's note.
 */
interface UsageSnippet {
    text?: string;
    entryFunction?: string | null;
    /** `false` when the catalog has not declared what its own helpers mean in plain Compose. */
    scaffoldsDeclared?: boolean;
    /** Catalog-local helpers left in the snippet, which will not resolve outside it. */
    residue?: string[];
    blobUrl?: string | null;
    playgroundHref?: string | null;
    /** Reference pages for the platform APIs the snippet uses; absent on an older server. */
    apiDocs?: ApiDocLink[];
}

/** The small CodeMirror surface the read-only Source lane uses. */
interface SourceCodeMirror {
    getWrapperElement(): HTMLElement;
}

/**
 * CodeMirror is a selectively loaded, vendored global rather than a bundle import. Keep the
 * declaration deliberately narrow: the playground owns the full editor API; this lane only needs
 * the constructor and its generated wrapper.
 */
interface SourceCodeMirrorFactory {
    (place: HTMLElement, options: Record<string, unknown>): SourceCodeMirror;
}

declare global {
    interface Window {
        CodeMirror?: SourceCodeMirrorFactory;
    }
}

/** One live-lane input message, in the daemon's wire shape. */
interface InputMessage {
    kind: string;
    pixelX?: number;
    pixelY?: number;
    pointerId?: number;
    pointerType?: string;
    scrollDeltaY?: number;
    keyCode?: string;
    text?: string;
}

/**
 * A pointer the live lane is tracking between press and release. `moved` turns a tap into a drag:
 * the press is deferred until the first move, so a plain tap becomes a single `click` (the daemon's
 * fast path, which avoids racing `Modifier.clickable` with a batched down+up).
 */
interface PointerState {
    x: number;
    y: number;
    moved: boolean;
    pointerType: string;
}

const root = document.querySelector<HTMLElement>(".cp-viewer")!;
const img = must<HTMLImageElement>("cp-img");
const stage = document.querySelector<HTMLElement>(".cp-stage")!;
const canvas = must<HTMLCanvasElement>("cp-canvas");
const status = must<HTMLElement>("cp-status");
const errorBox = may<HTMLElement>("cp-error");
const live = must<HTMLInputElement>("cp-live");
// Default to a viewport-bounded contain fit; "Fit width" restores unconstrained height. The
// snapshot remains the geometry source for Live/Wasm, so re-pin an active overlay after changing
// modes. One `aria-pressed` toggle rather than a two-button group: it is a two-state axis with a
// default.
const zoomToggle = document.querySelector<HTMLButtonElement>(".cp-zoom-toggle");
const backdropFile = may<HTMLInputElement>("cp-backdrop-file");
const backdropClear = may<HTMLButtonElement>("cp-backdrop-clear");
const backdropLabel = may<HTMLElement>("cp-backdrop-label");
let backdropObjectUrl: string | null = null;

/**
 * Places a caller-owned scene behind the snapshot without sending that image to the server.
 * `plus-lighter` on the image reproduces Glimmer's clamped additive display model; an ordinary
 * alpha preview remains useful too, and a fully opaque preview simply hides the scene.
 */
function clearBackdrop() {
    if (backdropObjectUrl) URL.revokeObjectURL(backdropObjectUrl);
    backdropObjectUrl = null;
    stage.style.backgroundImage = "";
    stage.removeAttribute("data-custom-backdrop");
    if (backdropFile) backdropFile.value = "";
    if (backdropLabel) backdropLabel.textContent = "Choose backdrop";
    if (backdropClear) backdropClear.hidden = true;
}

if (backdropFile) {
    backdropFile.addEventListener("change", function () {
        const file = backdropFile.files?.[0];
        if (!file || !file.type.startsWith("image/")) {
            clearBackdrop();
            return;
        }
        if (backdropObjectUrl) URL.revokeObjectURL(backdropObjectUrl);
        backdropObjectUrl = URL.createObjectURL(file);
        stage.style.backgroundImage = `url(${JSON.stringify(backdropObjectUrl)})`;
        stage.setAttribute("data-custom-backdrop", "1");
        if (backdropLabel) backdropLabel.textContent = file.name;
        if (backdropClear) backdropClear.hidden = false;
    });
    window.addEventListener("pagehide", function () {
        if (backdropObjectUrl) URL.revokeObjectURL(backdropObjectUrl);
    });
}
if (backdropClear) backdropClear.addEventListener("click", clearBackdrop);
// "Fit screen" means the whole preview is on screen: cap at the viewport height measured below the
// chrome above the stage. Floored at 320px so a short window still shows a usable stage;
// re-measured on resize.
function fitCap() {
    if (!stage) return "72vh";
    var top = stage.getBoundingClientRect().top + (window.scrollY || 0);
    return rules.fitCap(top, window.innerHeight);
}
// The cap last written to the stage, so a re-measure with the same answer is a no-op. That stops
// the resize observer below from looping.
var appliedFitCap: string | null = null;
function applyZoom(rawMode: string | null) {
    var mode = rules.zoomMode(rawMode);
    var maxHeight = mode === "fit" ? fitCap() : "";
    appliedFitCap = mode === "fit" ? maxHeight : null;
    img.style.maxHeight = maxHeight;
    var rcZoomCanvas = may<HTMLCanvasElement>("cp-rc-canvas");
    if (rcZoomCanvas) rcZoomCanvas.style.maxHeight = maxHeight;
    // The spec lane paints into its own <img>, so the Fit-screen cap must reach it too. Looked up
    // rather than closed over: applyZoom("fit") runs at page load, before the lane's declarations.
    var specZoomImg = may<HTMLImageElement>("cp-spec-img");
    if (specZoomImg) specZoomImg.style.maxHeight = maxHeight;
    // Same for the motion lane's <img>.
    var motionZoomImg = may<HTMLImageElement>("cp-motion-img");
    if (motionZoomImg) motionZoomImg.style.maxHeight = maxHeight;
    // ...and the canvas decoded frames are painted on. The cap goes on the canvas, not its wrapper,
    // so the transport bar's height does not come out of the picture's budget.
    var motionZoomCanvas = may<HTMLCanvasElement>("cp-motion-canvas");
    if (motionZoomCanvas) motionZoomCanvas.style.maxHeight = maxHeight;
    root.setAttribute("data-zoom", mode);
    if (zoomToggle)
        zoomToggle.setAttribute(
            "aria-pressed",
            mode === "width" ? "true" : "false",
        );
    window.requestAnimationFrame(function () {
        if (live && live.checked && !canvas.hidden) fitLiveCanvas();
        if (wasmActive()) positionWasmFrame();
    });
}
if (zoomToggle) {
    zoomToggle.addEventListener("click", function () {
        applyZoom(root.getAttribute("data-zoom") === "width" ? "fit" : "width");
        // A discrete framing choice, so it is persisted to the URL. `refit()` on resize
        // deliberately does not come through here.
        urlPush = true;
        syncUrl();
    });
}
applyZoom("fit");
// Re-measure when the answer fitCap() gave could have changed. "Fit width" is an explicit choice
// to ignore the viewport's height, so it is left alone.
function refit() {
    var mode = rules.zoomMode(root.getAttribute("data-zoom"));
    if (!rules.needsRefit(mode, fitCap(), appliedFitCap)) return;
    applyZoom("fit");
}
window.addEventListener("resize", refit);
// Re-measure when the stage moves, not just on resize: fitCap() measures from the stage's top, and
// content inserted above it later (e.g. the render-history strip) fires no `resize`. The body is
// observed because ResizeObserver reports size, not position, and only an ancestor of both the
// insertion point and the stage grows.
if (typeof ResizeObserver === "function" && document.body) {
    new ResizeObserver(function () {
        // Coalesce to a frame: the observer can fire mid-layout.
        window.requestAnimationFrame(refit);
    }).observe(document.body);
}
// Surface a mode-activation failure (dead Live stream, Wasm that never boots, failed /render) on
// the stage's backend badge rather than leaving a stale frame. Pass null to clear.
function setPending(label: string | null) {
    if (label) root.setAttribute("data-pending", label);
    else root.removeAttribute("data-pending");
}
function showModeError(msg: string) {
    setPending(null);
    if (!errorBox) {
        status.textContent = msg;
        return;
    }
    errorBox.textContent = msg;
    errorBox.hidden = false;
    status.textContent = "";
}
function clearModeError() {
    if (errorBox) {
        errorBox.hidden = true;
        errorBox.textContent = "";
    }
}
// Human-readable reason for a Live stream that closed before delivering a frame. Maps the
// server's close codes (1013 capacity, 1008 unauthorized, 1003/CANNOT_ACCEPT carries a reason);
// a bare abnormal close (1006, e.g. a proxy 502 on the WS upgrade) gets the generic message.
function liveCloseReason(ev: CloseEvent | null) {
    if (ev && ev.code === 1013)
        return "Live preview is at capacity — try again shortly.";
    if (ev && ev.code === 1008) return "Live preview unauthorized.";
    if (ev && ev.reason) return "Live preview unavailable: " + ev.reason;
    return "Live preview couldn't connect — the live stream may be unavailable on this server.";
}
// Whether the snapshot lane is static (baked PNGs, no /render re-render). Not `live.disabled`: a
// trusted-catalog live session serves static snapshots yet leaves Live enabled.
var staticSnapshot = root.getAttribute("data-static-snapshot") === "true";
// Whether an override-bearing /render returns fresh pixels even on a static snapshot lane (a
// trusted-catalog live session: its carried daemon re-renders author-declared knob edits on
// demand). When true, a knob edit re-points the snapshot /render URL rather than sitting dead.
var canRenderOverrides =
    root.getAttribute("data-can-render-overrides") === "true";
// The delivery-branch commit this page is pinned to (`?at=<sha>`). Every render URL here carries it
// so the stage, export links and Copy PNG read the same publish. Validated to a sha shape because
// it is DOM text that ends up in a request URL.
var pinnedAt = (root.getAttribute("data-pinned-at") || "").toLowerCase();
if (!/^[0-9a-f]{7,40}$/.test(pinnedAt)) pinnedAt = "";
// The delivery-branch publish this page was assembled from; frame URLs are scoped to it so a
// refresh cannot mix this page's metadata with the next publish's pixels. Empty without a delivery
// branch. Same shape validation as the pin (it reaches the server as `gen=`).
var generation = (root.getAttribute("data-generation") || "").toLowerCase();
if (!/^[0-9a-f]{7,40}$/.test(generation)) generation = "";
var previewId = root.getAttribute("data-preview-id") || "";
// The session path prefix ("/<system>"), recovered by stripping the trailing "/p/<id>" ("" for the
// root mount / ?session= form). /render and /ws requests are prefixed with it.
var base = location.pathname.replace(/\/p\/[^/]*\/?$/, "");
var token = new URLSearchParams(location.search).get("token") || "";
// Carry the tenant through follow-up requests so a non-default ?session= stays on its module.
var session = new URLSearchParams(location.search).get("session") || "";
// URL hydration of the controls happens in hydrateFromUrl (bottom of this file) so Back/Forward
// runs the same restore; it lands before the first render.
//
// The selects and text input are opt-in (empty = preview default). Font scale has no empty state,
// so it is only sent once touched (fontScaleTouched); otherwise 1.0 would override a preview's
// declared font scale. There is no background override here: the Transparent toggle is the viewer's
// only background control.
var fields = ["device", "localeTag", "orientation"];
const fs = may<HTMLInputElement>("cp-fontScale");
const fsVal = may<HTMLElement>("cp-fontScale-val");
var fontScaleTouched = false;
var ws: WebSocket | null = null;
const themeChoice = may<HTMLSelectElement>("cp-theme");
// The lanes that put a fixed frame on the stage (spec raster, finished motion recording). Overrides
// do not re-point them, but the frame was produced with the prior picks, so those still matter.
// Read off `data-mode` so it is safe during module init. Shared by `syncServerControls` and
// `activeThemeChoice` so the two cannot drift.
function onFixedFrameLane(): boolean {
    var mode = root.getAttribute("data-mode") || "";
    return mode === "spec" || mode === "motion";
}
// The theme this preview is baked in (`data-default-theme`), or "". Read on each call so it cannot
// disagree with the select.
function defaultThemeValue() {
    return (
        (themeChoice && themeChoice.getAttribute("data-default-theme")) || ""
    );
}
function activeThemeChoice() {
    return rules.activeThemeChoice(
        themeChoice && {
            value: themeChoice.value,
            disabled: themeChoice.disabled,
            active: themeChoice.getAttribute("data-theme-active") === "1",
            defaultValue: defaultThemeValue(),
        },
        onFixedFrameLane(),
    );
}
function chosenUiMode() {
    return rules.chosenUiMode(activeThemeChoice());
}
function chosenThemeProvider() {
    return rules.chosenThemeProvider(activeThemeChoice());
}
// The Theme bar: the visible face of the visually hidden #cp-theme. Chips carry the select's option
// values, so a click is an assignment plus `change`; syncThemeBar mirrors whatever
// syncServerControls decided for the select.
const themeBarBtns = document.querySelectorAll<HTMLButtonElement>(
    ".cp-theme-bar .cp-theme-btn",
);
function themeOptionFor(value: string | null): HTMLOptionElement | null {
    if (!themeChoice) return null;
    for (const o of Array.from(themeChoice.options)) {
        if (o.value === value) return o;
    }
    return null;
}
function syncThemeBar() {
    // Captured so the guard's narrowing survives into the nested function.
    const select = themeChoice;
    if (!select) return;
    themeBarBtns.forEach(function (b) {
        var choice = b.getAttribute("data-theme-choice") || "";
        var option = themeOptionFor(choice);
        var state = rules.themeBarButton(
            choice,
            { value: select.value, disabled: select.disabled },
            option && { disabled: option.disabled },
        );
        b.disabled = state.disabled;
        b.setAttribute("aria-pressed", state.pressed ? "true" : "false");
    });
}
// Record the pick without firing `change` (which would start a render, the thing following the link
// avoids). The destination reads this catalog-scoped key on arrival.
function rememberThemeChoice(value: string) {
    // Storage blocked: the destination just loses its pressed chip. Never a reason not to navigate.
    writeThemeMemory(
        themeChoice?.getAttribute("data-theme-storage-key") || "",
        value,
    );
}
// A chip whose mode the catalog already baked as its own card carries `data-theme-href`; following
// it loads baked pixels instead of waiting on a daemon re-render, and lands on a card with its own
// annotations. Only while the frame is otherwise unedited: navigating discards knobs, font scale,
// locale and size edits, so an edited page keeps the in-place override.
function themeTwinHref(b: HTMLButtonElement): string | null {
    const href = b.getAttribute("data-theme-href");
    if (!href) return null;
    return frameIsUnedited() ? href : null;
}
// Whether the frame is the preview as published, apart from the theme axis. Uses the same
// predicates as `query()` so the two agree on what counts as an edit.
function frameIsUnedited(): boolean {
    const o = overrides();
    for (const key of Object.keys(o)) {
        if (key !== "uiMode") return false;
    }
    if (chosenThemeProvider()) return false;
    let edited = false;
    controls(".cp-knob").forEach(function (el) {
        if (edited || el.disabled || !el.getAttribute("data-knob-key")) return;
        edited = rules.knobEmitted(
            controlValue(el),
            el.getAttribute("data-knob-initial") || "",
            knobKind(el),
        );
    });
    controls(".cp-rc-knob").forEach(function (el) {
        if (edited || el.disabled || !el.getAttribute("data-rc-name")) return;
        edited = rules.rcKnobEmitted(
            controlValue(el),
            el.getAttribute("data-rc-initial") || "",
        );
    });
    if (edited) return false;
    const focus = may<HTMLInputElement>("cp-focus");
    if (focus && !focus.disabled && focus.checked) return false;
    const gestures = may<HTMLInputElement>("cp-gestures");
    return !(gestures && !gestures.disabled && gestures.checked);
}
themeBarBtns.forEach(function (b) {
    b.addEventListener("click", function () {
        var value = b.getAttribute("data-theme-choice");
        if (!themeChoice || b.disabled || value === null) return;
        if (themeChoice.value === value) return;
        var twin = themeTwinHref(b);
        if (twin) {
            rememberThemeChoice(value);
            window.location.href = twin;
            return;
        }
        themeChoice.value = value;
        themeChoice.dispatchEvent(new Event("change", { bubbles: true }));
        syncThemeBar();
    });
});
// The snapshot lane serves either the raster PNG or the vector SVG through the same <img>.
// The render-mode radio flips this (".png" default, ".svg" in SVG mode); refreshSnapshot and
// the copyable links read it so a re-render / copied URL matches the on-screen format.
var snapshotExt = ".png";
// Keep the current frame visible while an override-triggered render is in flight. A generation
// token prevents an older, slower request from clearing the busy treatment (or replacing the
// pixels) after a newer control edit has already started another render.
var snapshotGen = 0;
function setSnapshotLoading(loading: boolean) {
    if (loading) {
        root.setAttribute("data-reloading", "true");
        if (stage) stage.setAttribute("aria-busy", "true");
    } else {
        root.removeAttribute("data-reloading");
        if (stage) stage.removeAttribute("aria-busy");
    }
}
function cancelSnapshotLoading() {
    snapshotGen++;
    status.textContent = "";
    setSnapshotLoading(false);
}

// Size overrides (Fixed / Max / Min / Within). Fixed pins widthPx/heightPx; the others are wrapped-
// axis bounds (maxWidthPx / minWidthPx …). Blank inputs are omitted. Server-side only, so disabled
// on a static snapshot. Inputs are dp; the wire is px, so values are multiplied by
// data-render-density.
var renderDensity =
    parseFloat(root.getAttribute("data-render-density") || "") || 2;
// dp (string from the input) → a positive integer px value, or null when blank/non-positive.
function sizePx(id: string) {
    var el = may<HTMLInputElement>(id);
    if (!el || !el.value) return null;
    return rules.sizePx(el.value, renderDensity);
}
function sizeOverrides() {
    var mode = may<HTMLSelectElement>("cp-sizeMode");
    return rules.sizeOverrides(
        (mode ? mode.value : "") as rules.SizeMode,
        function (field) {
            return sizePx("cp-" + field);
        },
    );
}
function overrides(): Overrides {
    var o: Overrides = {};
    fields.forEach(function (f) {
        var el = may<Control>("cp-" + f);
        if (el && !el.disabled && el.value) o[f] = el.value;
    });
    var uiMode = chosenUiMode();
    if (uiMode) o.uiMode = uiMode;
    if (fontScaleTouched && fs) o.fontScale = fs.value;
    var size = sizeOverrides();
    Object.keys(size).forEach(function (k) {
        o[k] = size[k];
    });
    // Overlay toggles (id "cp-<key>"; the key is the id minus the prefix). Collected in the map
    // query() serializes so they reach the page URL, export links and the live socket's connect
    // query, arriving with `stream/start` rather than a second setOverrides. Only checked overlays
    // are sent: an absent key already means off.
    ticks(".cp-overlay").forEach(function (el) {
        if (el.disabled || !el.checked) return;
        o[el.id.replace(/^cp-/, "")] = "true";
    });
    return o;
}
// A knob control's declared kind, from the server-rendered row. Only the empty-value rules below
// use it. Defaults to `string`, matching the server.
function knobKind(el: Control) {
    return el.getAttribute("data-knob-kind") || "string";
}
// The live-stream override map: display fields plus every knob as `knob.<key>=<value>`. Separate
// from overrides() because setOverrides replaces the daemon's whole map, so every knob (not just
// changed ones) must be sent or the rest reset to defaults.
function liveOverrides() {
    var o = overrides();
    controls(".cp-knob").forEach(function (el) {
        if (el.disabled) return;
        var key = el.getAttribute("data-knob-key");
        if (!key) return;
        var val = controlValue(el);
        // An empty string knob is a real value, so it is sent. An emptied number field is skipped:
        // this map replaces the whole override bag, so `knob.count=` would read as clearing it.
        if (val === "" && knobKind(el) !== "string") return;
        o["knob." + key] = val;
    });
    // Remote Compose knobs carry their own `<kind>:` tag: `rc.<name>=<kind>:<value>`. Sent for
    // every RC knob (like the plain knobs) so a Live setOverrides doesn't reset the others.
    controls(".cp-rc-knob").forEach(function (el) {
        if (el.disabled) return;
        var name = el.getAttribute("data-rc-name");
        if (!name) return;
        var kind = el.getAttribute("data-rc-kind") || "string";
        var val = controlValue(el);
        if (val === "") return;
        o["rc." + name] = kind + ":" + val;
    });
    // App-declared theme (themeProvider = provider FQN), only when picked and the control is live.
    var tp = chosenThemeProvider();
    if (tp) o["themeProvider"] = tp;
    // Detected-feature: keyboard focus. Checked ⇒ focus the first focusable + draw the overlay
    // (focus=0). Daemon-only, so skipped when disabled.
    var fc = may<HTMLInputElement>("cp-focus");
    if (fc && !fc.disabled && fc.checked) o["focus"] = "0";
    // Detected-feature: one-handed gesture hints. Checked ⇒ draw the gesture-hint overlay
    // (gestures=true). Android-daemon-only, so skipped when disabled.
    var gc = may<HTMLInputElement>("cp-gestures");
    if (gc && !gc.disabled && gc.checked) o["gestures"] = "true";
    // Detected-feature: fire a one-handed gesture (gestureInvoke=<kind>). Read once and cleared, so
    // the next render does not repeat it.
    var gi = takeGestureInvoke();
    if (gi) o["gestureInvoke"] = gi;
    // setOverrides replaces the stream's whole override map, so keep an explicit server-side player
    // in it (even the page default); otherwise the first onopen replay clears it back to the baked
    // player.
    var livePlayer = rules.serverPlayerParam(rcPlayerBackend, !!rcPlayerPicked);
    if (livePlayer) o.rcPlayer = livePlayer;
    return o;
}
// Renderer-picker state (#cp-lane-select). `rcPlayerPicked` gates whether `rcPlayerBackend` rides
// the render URL; also true for a default that differs from what the bare URL produces.
const laneSelect = may<HTMLSelectElement>("cp-lane-select");
// The design-spec lane's own chip, beside the combo rather than inside it (see ServeWeb's
// specChipHtml). Present only when this preview carries an imported reference.
const specChip = may<HTMLButtonElement>("cp-spec-chip");
// Canonical player ids throughout (see `RC_PLAYER`); a legacy spelling is read as the player it
// always named.
var rcDefaultBackend = laneSelect
    ? rules.normalizeRcPlayer(laneSelect.getAttribute("data-rc-default"))
    : "";
// The player a bare `/render` URL already produces (`ServeHost.bakedRcPlayer`); empty when the
// session cannot name one. Read as a capture record so an older server's `cmp-android` / `java`
// maps to the player it meant.
var rcBakedPlayer = laneSelect
    ? rules.normalizeBakedPlayer(
          laneSelect.getAttribute("data-rc-baked-player"),
      )
    : "";
var rcPlayerBackend = rcDefaultBackend;
// Every server-side backend must ride the very first snapshot request like a user pick — except
// the one the bare URL already is, which would only be a parameter that changes nothing.
var rcPlayerPicked = rules.backendRequiresRenderParam(
    rcDefaultBackend,
    rcBakedPlayer,
);
// Reconcile the picker (combo value and chip label) with the active lane. Hoisted so enterMode can
// call it whenever the viewer leaves a lane through any control. A no-op for a single-lane preview.
var syncLaneSelect = function () {};
/**
 * The render lane's whole override map, as the controls stand right now. Split out of [query]
 * because the report form's `compose-parity-locator/v1` block needs the map itself, and must name
 * the same frame as the body's render link: one collector, two serialisations.
 */
function renderOverrides(): Overrides {
    var o = overrides();
    // Author-declared knobs: knob.<key>=<value> (the server types it from the declaration). A knob
    // at its declared default is omitted so the URL stays on the baked snapshot.
    controls(".cp-knob").forEach(function (el) {
        if (el.disabled) return;
        var key = el.getAttribute("data-knob-key");
        if (!key) return;
        var val = controlValue(el);
        if (
            !rules.knobEmitted(
                val,
                el.getAttribute("data-knob-initial") || "",
                knobKind(el),
            )
        )
            return;
        o["knob." + key] = val;
    });
    // Remote Compose knobs: rc.<name>=<kind>:<value>; the kind prefix types the seed. Omitted at
    // the declared default.
    controls(".cp-rc-knob").forEach(function (el) {
        if (el.disabled) return;
        var name = el.getAttribute("data-rc-name");
        if (!name) return;
        var kind = el.getAttribute("data-rc-kind") || "string";
        var val = controlValue(el);
        if (!rules.rcKnobEmitted(val, el.getAttribute("data-rc-initial") || ""))
            return;
        o["rc." + name] = rules.rcKnobValue(kind, val);
    });
    // App-declared theme (themeProvider = provider FQN). Omitted at "(default)" so the URL stays on
    // the baked snapshot.
    var tp = chosenThemeProvider();
    if (tp) o.themeProvider = tp;
    // Detected-feature: keyboard focus (focus=0). Routes to the daemon like a knob; omitted when
    // unchecked so the URL stays on the baked snapshot.
    var fc = may<HTMLInputElement>("cp-focus");
    if (fc && !fc.disabled && fc.checked) o.focus = "0";
    // Detected-feature: one-handed gesture hints (gestures=true). Routes to the daemon like a
    // knob; omitted when unchecked so the URL stays on the baked snapshot.
    var gc = may<HTMLInputElement>("cp-gestures");
    if (gc && !gc.disabled && gc.checked) o.gestures = "true";
    // Detected-feature: fire a one-handed gesture (gestureInvoke=<kind>), read once — see
    // `takeGestureInvoke`.
    var gi = takeGestureInvoke();
    if (gi) o.gestureInvoke = gi;
    // Remote Compose render backend as rcPlayer=<id>, sent for a visitor pick or a server-side
    // default the bare URL does not already produce, and only for a server-side (PNG) lane. The
    // camaelon-js canvas replays in-browser, so it never sends the param.
    var serverPlayer = rules.serverPlayerParam(
        rcPlayerBackend,
        !!rcPlayerPicked,
    );
    if (serverPlayer) o.rcPlayer = serverPlayer;
    return o;
}
function query() {
    // Only carry token= when this page's own URL had one; public routes stay token-free.
    var parts: string[] = [];
    if (token) parts.push("token=" + encodeURIComponent(token));
    if (session) parts.push("session=" + encodeURIComponent(session));
    // The pin rides with the request because the server cannot tell the viewer's snapshot request
    // from any other /render call. A pinned page disables re-rendering controls, so overrides are
    // empty.
    if (pinnedAt) parts.push("at=" + encodeURIComponent(pinnedAt));
    // Everything pushed from here on is an override of some kind, which is exactly what decides
    // whether this URL may name a generation — see `rules.generationEmitted` below.
    var beforeOverrides = parts.length;
    var o = renderOverrides();
    Object.keys(o).forEach(function (k) {
        // Encode the key too: `knob.<key>` / `rc.<name>` carry author strings, and an unencoded
        // `&`, `=` or `%` would split the URL into overrides that differ from the locator's JSON.
        parts.push(encodeURIComponent(k) + "=" + encodeURIComponent(o[k]));
    });
    // The cache generation, last, and only when nothing was pushed: every lane omits itself at its
    // default, so "no overrides" means "this is the published frame".
    if (
        rules.generationEmitted(
            generation,
            pinnedAt,
            parts.length > beforeOverrides,
        )
    )
        parts.push("gen=" + encodeURIComponent(generation));
    return parts.join("&");
}
// "Full page (scroll)" appends `scroll=long` to both snapshot formats. The server routes SVG to
// compose/figma-svg-long and PNG to render/scroll/long.
const scrollLong = may<HTMLInputElement>("cp-scroll-long");
// The exploded 3D view (`?exploded=1` on the SVG lane): the layered figma-svg tilted and split into
// one sheet per drawing level. It only applies to `.svg`, so the toggle turns SVG on. Every knob
// lands in the URL (hence server-side): the angle is part of the copied link and downloaded SVG.
const explodeToggle = may<HTMLButtonElement>("cp-explode-toggle");
var EXPLODE_KNOBS = [
    ["cp-explode-tilt", "explodeTilt"],
    ["cp-explode-spin", "explodeSpin"],
    ["cp-explode-gap", "explodeGap"],
    ["cp-explode-depth", "explodeDepth"],
];
function explodeOn() {
    return !!(
        explodeToggle && explodeToggle.getAttribute("aria-pressed") === "true"
    );
}
// The same boolean forms `ServeExplodedSvg.enabled` accepts, so a hand-typed `?exploded=on` opens
// the view the render endpoint would serve.
function explodeParamOn(raw: string | null) {
    return rules.explodeParamOn(raw);
}
// A server-side Remote Compose player pick cannot survive the exploded view: the server routes
// `rcPlayer=cmp-jvm` to the desktop player before it looks at `exploded=`, and an RC document has
// no composable `<g id>` nesting to split anyway. Reset the state rather than filtering the request
// string, because syncUrl() and the picker label read the state.
function dropRcPlayerPick() {
    if (!rcPlayerPicked && rcPlayerBackend === rcDefaultBackend) return;
    rcPlayerPicked = rules.backendRequiresRenderParam(
        rcDefaultBackend,
        rcBakedPlayer,
    );
    rcPlayerBackend = rcDefaultBackend;
    if (typeof syncLaneSelect === "function") syncLaneSelect();
}
// Whether pressing 3D turned the vector lane on; if so, leaving 3D hands the lane back. Someone who
// was already on SVG keeps it.
var explodeEnabledSvg = false;
// The knobs as plain values, for the rules next door to decide on.
function explodeKnobValues() {
    return EXPLODE_KNOBS.map(function (pair) {
        var el = may<HTMLInputElement>(pair[0]);
        return {
            param: pair[1],
            value: el ? el.value : "",
            defaultValue: el ? el.getAttribute("data-cp-default") || "" : "",
        };
    });
}
// Just the exploded parameters, for `syncUrl`; shared with the render URL so the address bar,
// copied link and fetched bytes agree.
function explodeQuery() {
    return rules.explodeParams(explodeKnobValues()).join("&");
}
function withSnapshotFormat(ext: string, qs: string) {
    return rules.withSnapshotFormat(ext, qs, {
        scrollLong: !!(scrollLong && scrollLong.checked),
        exploded: explodeOn(),
        knobs: explodeKnobValues(),
    });
}
// Called once the snapshot request has settled (decoded or failed). The bookmarked-mode bootstrap
// waits on this rather than the <img> load/error, which a failed render never fires. A refusal is
// settled too, and the Wasm/live lane may be able to honour it.
var onSnapshotSettled: (() => void) | null = null;
function snapshotSettled() {
    var fn = onSnapshotSettled;
    if (fn) {
        onSnapshotSettled = null;
        fn();
    }
}
// Cancels a coalesced continuous-control edit that has not started its render yet. Forward-declared
// because that control is wired far below; a no-op until then.
//
// A non-terminal refusal (503 / 429 + `Retry-After`) means the lane is cold or busy and will
// recover, so the page retries, bounded and server-paced. Which refusals qualify is decided in
// `viewer/snapshotRetry.js` on the status. The counter is per attempt sequence: any control change
// starts a new render, resets it, and moves `snapshotGen` so a queued retry cannot paint over it.
var cancelPendingContinuousEdit: () => void = function () {};
var SNAPSHOT_RETRY_LIMIT = 4;
var snapshotRetryTimer: ReturnType<typeof setTimeout> | null = null;
var snapshotRetries = 0;
function cancelSnapshotRetry() {
    if (snapshotRetryTimer === null) return;
    clearTimeout(snapshotRetryTimer);
    snapshotRetryTimer = null;
}
function refreshSnapshot(isRetry?: boolean) {
    if (!isRetry) snapshotRetries = 0;
    cancelSnapshotRetry();
    // A render is starting now, so a coalesced edit still waiting would be redundant (it re-reads
    // the same controls). From the timer's own callback this is a no-op, so the edit renders
    // exactly once.
    cancelPendingContinuousEdit();
    status.textContent = "rendering…";
    var gen = ++snapshotGen;
    setSnapshotLoading(true);
    var qs = withSnapshotFormat(snapshotExt, query());
    // A long override — an A2UI document in a string knob — goes out as POST instead of a URL no
    // server will accept. See `renderRequest`.
    var request = renderRequest(
        base + "/render/" + encodeURIComponent(previewId) + snapshotExt,
        qs,
    );
    var url = request.url;
    var requestedExt = snapshotExt;
    // Override-bearing renders are `no-store`, so preloading via `new Image()` then assigning the
    // same URL would render twice and race the daemon's shared override state. Fetch once and hand
    // the blob URL to the image; this also keeps the current frame visible until the replacement
    // decodes.
    fetch(url, request.init)
        .then(function (response) {
            if (!response.ok) {
                // `X-Compose-Preview-Dropped-Overrides` names params the server refused to ignore;
                // it shapes the message. The status decides whether to retry.
                var after = parseInt(
                    response.headers.get("Retry-After") || "",
                    10,
                );
                var e: SnapshotFailureError = new Error(
                    "render " + response.status,
                );
                e.cpFailure = {
                    status: response.status,
                    dropped:
                        response.headers.get(
                            "X-Compose-Preview-Dropped-Overrides",
                        ) || "",
                    retryAfterSeconds: after > 0 ? after : 0,
                };
                throw e;
            }
            return response.blob();
        })
        .then(function (blob) {
            if (gen !== snapshotGen) return;
            var objectUrl = URL.createObjectURL(blob);
            var next = new Image();
            next.onload = function () {
                if (gen !== snapshotGen) {
                    URL.revokeObjectURL(objectUrl);
                    return;
                }
                var previous = img.getAttribute("data-cp-blob");
                img.src = objectUrl;
                img.setAttribute("data-cp-blob", objectUrl);
                // The blob URL is opaque, so record the /render URL actually fetched as the frame's
                // provenance. Unlike `#cp-url-png` / `#cp-url-svg` (which track the controls
                // immediately), this lands only once the matching bytes decode; the serve-lanes e2e
                // asserts on that gap.
                img.setAttribute("data-cp-src", url);
                if (previous) URL.revokeObjectURL(previous);
                status.textContent = "";
                setSnapshotLoading(false);
                clearModeError();
                syncSpecBaseline();
                // The frame is on the stage now, so the report may describe it. Other callers run
                // from control changes, where the gate declines.
                refreshReportLink();
                snapshotSettled();
            };
            next.onerror = function () {
                URL.revokeObjectURL(objectUrl);
                if (gen !== snapshotGen) return;
                setSnapshotLoading(false);
                showModeError(
                    (requestedExt === ".svg" ? "SVG" : "PNG") +
                        " render failed for this preview.",
                );
                snapshotSettled();
            };
            next.src = objectUrl;
        })
        .catch(function (e: SnapshotFailureError) {
            if (gen !== snapshotGen) return;
            setSnapshotLoading(false);
            // A throw with no `cpFailure` never reached the server; status 0 is terminal.
            var failure: rules.SnapshotFailure = (e && e.cpFailure) || {
                status: 0,
                dropped: "",
                retryAfterSeconds: 0,
            };
            var willRetry = rules.willRetrySnapshot(
                failure,
                snapshotRetries,
                SNAPSHOT_RETRY_LIMIT,
            );
            showModeError(
                rules.snapshotFailureMessage(
                    failure,
                    willRetry,
                    requestedExt === ".svg" ? "SVG" : "PNG",
                ),
            );
            if (willRetry) {
                snapshotRetries++;
                var wait = rules.snapshotRetryWaitMs(failure, snapshotRetries);
                snapshotRetryTimer = setTimeout(function () {
                    snapshotRetryTimer = null;
                    if (gen !== snapshotGen) return;
                    refreshSnapshot(true);
                }, wait);
                return;
            }
            snapshotSettled();
        });
    refreshLinks();
}
// The copyable direct-link panel: absolute /render URLs (PNG + optional SVG) rebuilt from the
// current controls on every change, so a copied link reproduces what's on screen.
function renderUrl(ext: string) {
    var qs = withSnapshotFormat(ext, query());
    return (
        location.origin +
        base +
        "/render/" +
        encodeURIComponent(previewId) +
        ext +
        (qs ? "?" + qs : "")
    );
}
// Whether the stage shows the render the published design-spec score was measured against (default
// theme, declared knob defaults, no detected features). Since every control omits itself from
// `query()` at its default, the render URL minus link-only params answers this directly. It matters
// because the design reference does not move with the render, so the baked number no longer
// describes a changed frame.
function specAtBaseline() {
    return rules.specAtPublishedBaseline(
        root.getAttribute("data-mode") || "snapshot",
        renderUrl(".png"),
        img.getAttribute("data-cp-src"),
    );
}
// The inline theme bootstrap publishes the initial value before the spec element upgrades. Keep
// both the stage attribute (for reconnects) and the installed element current from here on.
function syncSpecBaseline() {
    var at = specAtBaseline();
    root.setAttribute("data-spec-baseline", at ? "1" : "0");
    if (window.cpSpecCompare) window.cpSpecCompare.baseline(at);
    preScoreComparisonChips(at);
}
function withMode(url: string, mode: string) {
    return url + (url.indexOf("?") >= 0 ? "&" : "?") + "mode=" + mode;
}
// [skipUrlSync] refreshes the links without touching history, for a caller about to push a lane
// transition (syncing first would replace the entry the visitor came from). See `onKnobEdited`.
function refreshLinks(skipUrlSync?: boolean) {
    // Keep the page URL in step with the controls so the screen is bookmarkable. Every
    // state-changing path refreshes the links, so this one call covers them all.
    if (!skipUrlSync) syncUrl();
    [
        ["png", ".png"],
        ["svg", ".svg"],
    ].forEach(function (pair) {
        var field = may<HTMLInputElement>("cp-url-" + pair[0]);
        if (!field) return;
        var embed = renderUrl(pair[1]);
        var dl = may<HTMLAnchorElement>("cp-dl-" + pair[0]);
        if (pair[1] === ".svg") {
            // Copy URL yields the web variant (`?mode=web`, external Google Fonts @import) for
            // browsers. Copy SVG and the download use the self-contained embedded variant (from
            // data-embed-url) for Figma or <img>.
            field.value = withMode(embed, "web");
            field.setAttribute("data-embed-url", embed);
            if (dl) dl.href = embed;
        } else {
            field.value = embed;
            if (dl) dl.href = embed;
        }
    });
    updateSvgMatch();
    syncSpecBaseline();
    refreshReportLink();
}
// Keep the "report an issue" form pointed at what is on screen. The server fills the hidden `body`
// for the served settings (works with JS off); its template names `{{render}}` and, in the
// `compose-parity-locator/v1` block, `{{overrides}}`, both filled here from the current controls.
// The token is stripped: an issue body is public, a session token is a capability.
//
// Goes through `reportBody` because other producers (`<cp-report-classification>`,
// `<cp-report-scope>`) also write the field; the store recomposes from the template so none
// clobbers another. It writes an input value, never an href, so no page-derived string reaches a
// navigation sink.
function refreshReportLink() {
    var body = may<HTMLInputElement>("cp-report-body");
    var field = may<HTMLInputElement>("cp-url-png");
    if (!body || !field || !field.value) return;
    // Whether this lane's pixels are the ones `data-cp-src` names. Live, Wasm and the RC players
    // paint into a canvas/iframe, so the stage image is stale there.
    var mayName = rules.reportMayCarryLocator(
        root.getAttribute("data-mode") || "snapshot",
    );
    // Only while the frame on screen is the one the controls ask for: controls run ahead of the
    // image by a fetch and decode, so skipping keeps the body describing the frame still on stage.
    // See `viewer/reportFrame.ts`. On an interactive lane recompose regardless, to remove the
    // locator.
    if (
        mayName &&
        !rules.reportFollowsDisplayedFrame(
            renderUrl(snapshotExt),
            img.getAttribute("data-cp-src"),
        )
    )
        return;
    if (!reportBody.attach(body)) return;
    // One pass: the locator's identity and the body's render link are read off the same controls at
    // once.
    reportBody.set({
        render: stripToken(field.value),
        overrides: renderOverrides(),
        omitLocator: !mayName,
    });
}
function stripToken(url: string) {
    var cut = url.indexOf("?");
    if (cut < 0) return url;
    var kept = url
        .slice(cut + 1)
        .split("&")
        .filter(function (p: string) {
            return p && p.slice(0, 6) !== "token=";
        });
    return kept.length
        ? url.slice(0, cut) + "?" + kept.join("&")
        : url.slice(0, cut);
}
const svgMatch = may<HTMLElement>("cp-svg-match");
const svgDiff = may<HTMLAnchorElement>("cp-svg-diff");
var svgMatchGeneration = 0;
var svgMatchKey = "";
function updateSvgMatch() {
    // Captured so the guard survives into the callbacks below, which run long after it.
    const match = svgMatch;
    if (!match) return;
    if (!svgOn()) {
        match.hidden = true;
        if (svgDiff) svgDiff.hidden = true;
        return;
    }
    var png = may<HTMLInputElement>("cp-url-png");
    var svg = may<HTMLInputElement>("cp-url-svg");
    var svgUrl = svg && (svg.getAttribute("data-embed-url") || svg.value);
    if (!png || !png.value || !svgUrl || !window.ComposePreviewCompare) return;
    var key = png.value + "\n" + svgUrl;
    if (
        key === svgMatchKey &&
        match.textContent &&
        match.textContent !== "comparing…"
    ) {
        match.hidden = false;
        if (svgDiff) svgDiff.hidden = false;
        return;
    }
    svgMatchKey = key;
    var generation = ++svgMatchGeneration;
    match.hidden = false;
    match.className = "cp-match";
    match.textContent = "comparing…";
    if (svgDiff) svgDiff.hidden = true;
    window.ComposePreviewCompare.scoreSvgUrls(png.value, svgUrl).then(
        function (percent) {
            if (generation !== svgMatchGeneration || !svgOn()) return;
            match.textContent = percent.toFixed(1) + "% match";
            match.className =
                "cp-match cp-match--" +
                (percent >= 90 ? "good" : percent >= 75 ? "warn" : "bad");
            if (svgDiff) svgDiff.hidden = false;
        },
        function () {
            if (generation !== svgMatchGeneration || !svgOn()) return;
            match.textContent = "match unavailable";
            match.className = "cp-match cp-match--na";
        },
    );
}
// Copy the /render URL from the off-screen `#cp-url-<ext>` field. The field is still selected for
// the execCommand fallback; the button reports in its own label.
document.querySelectorAll<HTMLElement>(".cp-copyurl").forEach(function (btn) {
    btn.addEventListener("click", function () {
        var field = may<HTMLInputElement>(
            btn.getAttribute("data-copyurl-target") || "",
        );
        if (!field || !field.value) return;
        var was =
            btn.getAttribute("data-copyurl-label") || btn.textContent || "";
        btn.setAttribute("data-copyurl-label", was);
        var report = function (label: string) {
            btn.textContent = label;
            setTimeout(function () {
                btn.textContent = was;
            }, 1400);
        };
        field.select();
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(field.value).then(
                function () {
                    report("Copied");
                },
                function () {
                    report("Copy failed");
                },
            );
        } else {
            try {
                document.execCommand("copy");
                report("Copied");
            } catch (e) {
                report("Copy failed");
            }
        }
    });
});
// "Copy PNG" / "Copy SVG": fetch the current /render artefact (PNG as image/png bytes, falling back
// to a base64 data: URI; SVG as markup) from the same cp-url-<ext> field, so it matches on-screen
// overrides.
document.querySelectorAll<HTMLElement>(".cp-copyimg").forEach(function (btn) {
    btn.addEventListener("click", function () {
        var field = may<HTMLInputElement>(
            btn.getAttribute("data-copyimg-target") || "",
        );
        if (!field || !field.value) return;
        var ext = btn.getAttribute("data-copyimg-ext");
        var was =
            btn.getAttribute("data-copyimg-label") || btn.textContent || "";
        btn.setAttribute("data-copyimg-label", was);
        var reset = function (label: string) {
            btn.textContent = label;
            setTimeout(function () {
                btn.textContent = was;
            }, 1400);
        };
        if (!navigator.clipboard) {
            reset("No clipboard");
            return;
        }
        btn.textContent = "Copying…";
        // Copy SVG uses the embedded variant (data-embed-url) so pasted SVG has fonts baked in.
        // Copy PNG follows the stage: on the solid stage it composites onto the resolved ground
        // (paste targets have a white page, where a dark-first sticker would vanish); with
        // Transparent on it copies raw alpha.
        var src =
            (ext === ".svg" && field.getAttribute("data-embed-url")) ||
            (ext === ".png" && !isTransparent()
                ? withStage(field.value)
                : field.value);
        // fetch() resolves on non-2xx too; guard on r.ok so an error body never lands on the
        // clipboard.
        var okOrThrow = function (r: Response) {
            if (!r.ok) throw new Error("render " + r.status);
            return r;
        };
        // PNG: give the clipboard real image/png bytes when ClipboardItem exists. The blob is
        // passed as a promise because Safari requires the ClipboardItem to be constructed
        // synchronously in the click. Anything that fails falls back to base64 data: URI text.
        var copyAsText = function () {
            if (!navigator.clipboard.writeText) {
                reset("No clipboard");
                return;
            }
            var toText =
                ext === ".svg"
                    ? fetch(src)
                          .then(okOrThrow)
                          .then(function (r) {
                              return r.text();
                          })
                    : fetch(src)
                          .then(okOrThrow)
                          .then(function (r) {
                              return r.blob();
                          })
                          .then(function (blob) {
                              return new Promise<string>(function (
                                  resolve,
                                  reject,
                              ) {
                                  var fr = new FileReader();
                                  fr.onload = function () {
                                      resolve(fr.result as string);
                                  };
                                  fr.onerror = function () {
                                      reject(fr.error);
                                  };
                                  fr.readAsDataURL(blob);
                              });
                          });
            toText
                .then(function (text) {
                    return navigator.clipboard.writeText(text);
                })
                .then(
                    function () {
                        reset("Copied");
                    },
                    function () {
                        reset("Failed");
                    },
                );
        };
        if (
            ext === ".png" &&
            window.ClipboardItem &&
            navigator.clipboard.write
        ) {
            var pngBlob = fetch(src)
                .then(okOrThrow)
                .then(function (r) {
                    return r.blob();
                });
            navigator.clipboard
                .write([new ClipboardItem({ "image/png": pngBlob })])
                .then(function () {
                    reset("Copied");
                }, copyAsText);
            return;
        }
        copyAsText();
    });
});
// Share link / Share PNG beside the Copy actions, on a touchscreen with a share sheet. The PNG is
// the one Copy PNG would take: composited onto the stage unless Transparent is on.
installWebShare(function () {
    var field = may<HTMLInputElement>("cp-url-png");
    if (!field || !field.value) return null;
    return isTransparent() ? field.value : withStage(field.value);
});
// Live frame painting. Frames land on `frameQueue` and drain one per animation frame. Two
// watermarks keep the stage moving forward: the queue drops frames at or below the last released,
// and `paintedSeq` drops decodes that resolve out of order. See `live/framePainter.ts`.
var frameQueue = new FrameQueue();
var paintedSeq = -1;
var frameLoopRunning = false;
var frameLoopGeneration = 0;

/**
 * Begin painting for a freshly-opened socket. Resets per-stream state: a reconnect restarts `seq`
 * at 0, which the previous floor would reject.
 */
function startFrameLoop() {
    frameQueue = new FrameQueue();
    paintedSeq = -1;
    if (frameLoopRunning) return;
    frameLoopRunning = true;
    // Each start owns a generation, so a stop+start within one animation frame retires the old
    // chain instead of leaving two pumps draining one queue.
    frameLoopGeneration++;
    var generation = frameLoopGeneration;
    pumpFrames({
        alive: function () {
            return frameLoopRunning && generation === frameLoopGeneration;
        },
        next: function () {
            return frameQueue.dispatch();
        },
        paint: decodeAndPaint,
    });
}

function stopFrameLoop() {
    frameLoopRunning = false;
    frameQueue = new FrameQueue();
    paintedSeq = -1;
}

function decodeAndPaint(frame: ServeFrame) {
    var blob = frameBlob(frame);
    if (!blob) return; // heartbeat — the daemon says the pixels are unchanged
    createImageBitmap(blob).then(
        function (bitmap) {
            // The decode may have resolved after a newer frame already painted.
            if (!shouldPaintDecodedFrame(paintedSeq, frame.seq)) {
                bitmap.close();
                return;
            }
            paintedSeq = frame.seq;
            // ImageBitmap.close() zeroes the reported dimensions, so cache them while the bitmap is
            // live.
            liveW = bitmap.width;
            liveH = bitmap.height;
            canvas.width = liveW;
            canvas.height = liveH;
            canvas.getContext("2d")!.drawImage(bitmap, 0, 0);
            bitmap.close();
            // A <canvas> stretches its buffer to its CSS box; re-fit (contain, centred) so a frame
            // with a different aspect letterboxes instead of distorting.
            fitLiveCanvas();
        },
        function () {
            // A frame that won't decode is dropped; the next one repaints the stage.
        },
    );
}
// Live input forwarding (no-op on the snapshot lane). Coordinates are image-natural pixels; pointer
// events are grouped by pointerId for drags and multi-touch. Keys map to Android KEYCODE_* decimal
// strings.
function liveActive(): boolean {
    return !!(ws && ws.readyState === 1 && canvas.width);
}
function sendInput(msg: InputMessage) {
    var socket = ws;
    if (!socket || !liveActive()) return;
    socket.send(JSON.stringify(Object.assign({ type: "input" }, msg)));
}
function pixel(ev: MouseEvent) {
    var rect = canvas.getBoundingClientRect();
    if (!rect.width || !rect.height) return null;
    return {
        x: Math.round(((ev.clientX - rect.left) / rect.width) * canvas.width),
        y: Math.round(((ev.clientY - rect.top) / rect.height) * canvas.height),
    };
}
// Per-pointer state. pointerDown is deferred until the first move so a tap becomes a single `click`
// (see PointerState). pointermove is coalesced to one send per pointerId per animation frame.
var pointers: Record<string, PointerState> = {};
var pendingMoves: Record<string, InputMessage> = {};
var moveScheduled = false;
function flushMoves() {
    moveScheduled = false;
    var snapshot = pendingMoves;
    pendingMoves = {};
    Object.keys(snapshot).forEach(function (id) {
        sendInput(snapshot[id]);
    });
}
canvas.addEventListener("pointerdown", function (ev) {
    if (!liveActive()) return;
    var p = pixel(ev);
    if (!p) return;
    canvas.focus();
    try {
        canvas.setPointerCapture(ev.pointerId);
    } catch (e) {}
    // The device class travels with every event: Compose treats mouse and finger drags differently
    // (only a mouse drag selects text).
    pointers[ev.pointerId] = {
        x: p.x,
        y: p.y,
        moved: false,
        pointerType: ev.pointerType || "mouse",
    };
});
canvas.addEventListener("pointermove", function (ev) {
    if (!liveActive() || ev.buttons === 0) return; // only while pressed (a drag)
    var st = pointers[ev.pointerId];
    if (!st) return;
    var p = pixel(ev);
    if (!p) return;
    if (!st.moved) {
        // First movement → this is a drag: emit the deferred press at the original point.
        st.moved = true;
        sendInput({
            kind: "pointerDown",
            pixelX: st.x,
            pixelY: st.y,
            pointerId: ev.pointerId,
            pointerType: st.pointerType,
        });
    }
    pendingMoves[ev.pointerId] = {
        kind: "pointerMove",
        pixelX: p.x,
        pixelY: p.y,
        pointerId: ev.pointerId,
        pointerType: st.pointerType,
    };
    if (!moveScheduled) {
        moveScheduled = true;
        requestAnimationFrame(flushMoves);
    }
});
function endPointer(ev: PointerEvent) {
    var st = pointers[ev.pointerId];
    if (!st) return;
    delete pointers[ev.pointerId];
    var p = pixel(ev) || { x: st.x, y: st.y };
    if (st.moved) {
        flushMoves();
        sendInput({
            kind: "pointerUp",
            pixelX: p.x,
            pixelY: p.y,
            pointerId: ev.pointerId,
            pointerType: st.pointerType,
        });
    } else {
        // No drag → a tap. Send a single CLICK (the daemon renders between press and release).
        sendInput({
            kind: "click",
            pixelX: st.x,
            pixelY: st.y,
            pointerId: ev.pointerId,
            pointerType: st.pointerType,
        });
    }
}
canvas.addEventListener("pointerup", endPointer);
canvas.addEventListener("pointercancel", endPointer);
canvas.addEventListener(
    "wheel",
    function (ev) {
        if (!liveActive()) return;
        var p = pixel(ev);
        if (!p) return;
        ev.preventDefault();
        // Both daemon dispatchers drop non-key input without a position, so include the pixel.
        sendInput({
            kind: "rotaryScroll",
            pixelX: p.x,
            pixelY: p.y,
            scrollDeltaY: ev.deltaY,
        });
    },
    { passive: false },
);
// Keyboard: focus the canvas to type. Unmapped keys are dropped.
canvas.tabIndex = 0;
// See `viewer/keyInput.ts`: a keycode names a physical key, so text must be sent alongside it or
// nothing can be typed.
function keyInput(kind: string, ev: KeyboardEvent) {
    if (!liveActive()) return;
    // Carried on the release too, so a backend that suppresses the key event for a focused text
    // field can suppress both halves.
    var message = rules.keyMessage(ev);
    if (!message) return;
    var code = message.code;
    var text = message.text;
    ev.preventDefault();
    var msg: InputMessage = { kind: kind };
    if (code !== null) msg.keyCode = code;
    if (text !== null) msg.text = text;
    sendInput(msg);
}
canvas.addEventListener("keydown", function (ev) {
    keyInput("keyDown", ev);
});
canvas.addEventListener("keyup", function (ev) {
    keyInput("keyUp", ev);
});
function openStream() {
    root.setAttribute("data-mode", "live");
    // Seed the canvas buffer with the current snapshot *before* the swap, so there's no blank
    // flash while "connecting…" (the first frame overwrites it).
    if (img.naturalWidth && img.naturalHeight) {
        canvas.width = img.naturalWidth;
        canvas.height = img.naturalHeight;
        try {
            canvas.getContext("2d")!.drawImage(img, 0, 0);
        } catch (e) {}
    }
    // Mount the canvas as an absolute overlay on the snapshot's slot (the same box the Wasm tier
    // uses). The img stays in flow (visibility:hidden), so stage geometry is defined by the
    // snapshot and a differently-sized live frame scales into it.
    canvas.classList.add("cp-canvas-live");
    positionOverlay(canvas);
    img.style.visibility = "hidden";
    canvas.hidden = false;
    setPending("connecting…");
    var proto = location.protocol === "https:" ? "wss:" : "ws:";
    // Request WebP frames; the daemon downgrades to PNG when it can't encode WebP (each frame
    // carries its codec).
    var qs = query();
    // Track whether the stream ever delivered a frame: a close/error *before* the first frame is
    // a failed activation (surface it), whereas a close *after* frames is just a normal teardown.
    var liveGotFrame = false;
    // Hold the socket in a per-activation local and gate every callback on `ws === sock`: toggling
    // Live off and on opens a replacement before the old close event arrives, and that stale
    // callback would otherwise clear the new connection's badge and null `ws`.
    var sock = new WebSocket(
        proto +
            "//" +
            location.host +
            base +
            "/ws/" +
            encodeURIComponent(previewId) +
            "?" +
            (qs ? qs + "&codec=webp" : "codec=webp"),
    );
    ws = sock;
    wakeHold.hold("live", true);
    startFrameLoop();
    sock.onopen = function () {
        // Every stream starts visible daemon-side, so report a tab hidden during connecting once
        // the socket can carry it.
        if (document.hidden) sock.send(visibilityMessage(false));
        // The connect URL seeds only query()'s fields (display axes, overlays, changed knobs).
        // Replay the full live override map once open, including changes the readyState guard
        // dropped before onopen.
        sock.send(
            JSON.stringify({
                type: "setOverrides",
                overrides: liveOverrides(),
            }),
        );
    };
    sock.onmessage = function (ev) {
        // A frame from a socket the viewer has already replaced is stale in both senses: it must
        // not paint over the new lane's stage, nor report it as connected.
        if (ws !== sock) return;
        var m;
        try {
            m = JSON.parse(ev.data);
        } catch (e) {
            return;
        }
        if (m.type === "frame") {
            liveGotFrame = true;
            clearModeError();
            frameQueue.submit(m as ServeFrame);
            setPending(null);
        } else if (m.type === "error") {
            showModeError(m.message || "Live preview error.");
        }
    };
    // onerror always precedes onclose; let onclose decide (it carries the code/reason). Only
    // surface here if the socket somehow errors while already open+frame-less and never closes.
    sock.onerror = function () {
        if (ws === sock && !liveGotFrame) setPending("connecting…");
    };
    sock.onclose = function (ev) {
        // Not the current socket ⇒ a teardown the viewer already accounted for in closeStream()
        // (which cleared pending and restored the snapshot). Leave the live lane's state alone.
        if (ws !== sock) return;
        ws = null;
        wakeHold.hold("live", false);
        // The lane is done waiting either way — it painted, or it failed (showModeError below).
        setPending(null);
        // Closed before any frame ⇒ the mode failed to activate. Drop the stale seeded snapshot
        // from the canvas so it can't masquerade as a live render, and surface why.
        if (!liveGotFrame && live && live.checked) {
            canvas.hidden = true;
            img.style.removeProperty("visibility");
            showModeError(liveCloseReason(ev));
        }
    };
}
// A backgrounded tab is still rendering into an invisible canvas: tell the server, which throttles
// to a keyframe a second and repaints on resume without tearing the stream down.
document.addEventListener("visibilitychange", function () {
    var socket = ws;
    if (!socket || socket.readyState !== 1) return;
    socket.send(visibilityMessage(!document.hidden));
});
function closeStream() {
    root.setAttribute("data-mode", "snapshot");
    // Drop queued/mid-decode frames and reset both watermarks: a reconnect restarts `seq` at 0.
    stopFrameLoop();
    // Toggling Live off mid-connect must not leave the badge stuck on "connecting…".
    setPending(null);
    if (ws) {
        ws.close();
        ws = null;
    }
    wakeHold.hold("live", false);
    canvas.hidden = true;
    // Tear down the overlay: drop the absolute positioning and restore the snapshot img's slot.
    canvas.classList.remove("cp-canvas-live");
    canvas.style.removeProperty("left");
    canvas.style.removeProperty("top");
    canvas.style.removeProperty("width");
    canvas.style.removeProperty("height");
    // Forget the last frame's dims so a reconnect seeds from the snapshot (fill) until its own
    // first frame re-fits, rather than briefly letterboxing to a stale aspect.
    liveW = 0;
    liveH = 0;
    img.style.removeProperty("visibility");
    img.hidden = false;
}
// Wasm tier: the in-browser CMP app in a sandboxed iframe, wired only when data-wasm-src is
// present. Theme / font scale / locale re-point the iframe's query; device and orientation are
// server-only.
const wasmFrame = may<HTMLIFrameElement>("cp-wasm");
const wasmToggle = may<HTMLInputElement>("cp-wasm-toggle");
var wasmSrc = root.getAttribute("data-wasm-src") || "";
// Set once the app signals "cp-wasm-ready". Before that a control change re-points the query;
// after, it posts an override patch so the app recomposes in place instead of reloading the bundle.
var wasmReady = false;
// Boot watchdog: surface an error if the app never signals ready.
var wasmBootTimer: ReturnType<typeof setTimeout> | null = null;
function wasmBaseSrc() {
    if (!wasmSrc) return "";
    // Resolve the server-set src against our origin and refuse anything not same-origin http(s), so
    // a `javascript:` / `data:` URL can never reach the iframe. The query stays as baked (the
    // variant's default theme) so it is the clean base to revert to.
    var u;
    try {
        u = new URL(wasmSrc, location.origin);
    } catch (e) {
        return "";
    }
    if (u.origin !== location.origin) return "";
    return u.href;
}
// The font prefetch lives in the app's own index.html, not on this page: it must be in flight
// before the iframe navigates, and the app consumes it. Keep page-side preloads out
// (ServeWebFixtureTest guards this).
//
// The override patch (theme / font scale / locale) the running app merges over its baked base, as a
// bare `a=b&c=d` query. An absent key falls back to the app's default.
//
// The stage checkerboard's tile origin in the iframe's CSS-px coordinates. The app can't render
// transparency, so it paints the same pattern; the phase makes its cells continue the page's.
function wasmBgPhase() {
    var left = parseFloat(wasmFrame!.style.left) || 0;
    var top = parseFloat(wasmFrame!.style.top) || 0;
    var x = (stage.clientWidth - 16) / 2 - left;
    var y = (stage.clientHeight - 16) / 2 - top;
    return x.toFixed(2) + "," + y.toFixed(2);
}
// The stage's backdrop, handed to the Wasm app so the sticker sits on the same ground as in the
// snapshot (the app can't render transparency). In Transparent mode there is no colour to send; the
// app continues the checkerboard, positioned by `bgPhase`.
function wasmStageBg() {
    if (document.documentElement.classList.contains("cp-bg-transparent"))
        return "checker";
    var rgb = getComputedStyle(stage).backgroundColor || "";
    const m = rgb.match(/^rgba?\((\d+),\s*(\d+),\s*(\d+)/);
    if (!m) return "checker";
    return (
        "#" +
        [1, 2, 3]
            .map(function (i) {
                return ("0" + parseInt(m[i], 10).toString(16)).slice(-2);
            })
            .join("")
    );
}
function wasmOverridePatch() {
    var parts = [];
    var uiMode = chosenUiMode();
    if (uiMode) parts.push("uiMode=" + encodeURIComponent(uiMode));
    var loc = may<HTMLInputElement>("cp-localeTag");
    if (loc && loc.value)
        parts.push("localeTag=" + encodeURIComponent(loc.value));
    if (fontScaleTouched && fs)
        parts.push("fontScale=" + encodeURIComponent(fs.value));
    parts.push("bgPhase=" + encodeURIComponent(wasmBgPhase()));
    parts.push("stageBg=" + encodeURIComponent(wasmStageBg()));
    // Author-declared knobs also apply in the browser: the wasm catalog seeds `catalogOverride*`
    // from these `knob.<key>` params.
    //
    // Compared against the author default, not `data-knob-initial` as query() does: an
    // `@OverrideVariant` sticker opens with its knob already seeded away from the default, and the
    // Wasm tier has no baked artifact (`wasmAppSrc` strips the variant axis), so an unsent seed
    // would mount the primary variant instead.
    controls(".cp-knob").forEach(function (el) {
        if (el.disabled) return;
        var key = el.getAttribute("data-knob-key");
        if (!key) return;
        var val = controlValue(el);
        // See liveOverrides(): "" is a value for a string knob only. Dropping an @OverrideVariant
        // `label=` seed here would mount the primary variant.
        if (val === "" && knobKind(el) !== "string") return;
        // Pages without `data-knob-default` fall back to the initial value.
        var authorDefault = el.getAttribute("data-knob-default");
        if (authorDefault === null)
            authorDefault = el.getAttribute("data-knob-initial") || "";
        if (val === authorDefault) return;
        parts.push(
            "knob." + encodeURIComponent(key) + "=" + encodeURIComponent(val),
        );
    });
    return parts.join("&");
}
// Initial iframe URL: the baked base plus the current overrides in the `#…` fragment, so the
// app's first paint honours them yet keeps the query as its true base (a later clear reverts).
function wasmInitialSrc() {
    var base = wasmBaseSrc();
    if (!base) return "";
    var patch = wasmOverridePatch();
    return patch ? base + "#" + patch : base;
}
/**
 * Whether the stage's <img> shows an actual decoded render. A src-less or failed <img> still has a
 * small alt-text box (~104×20), so `getBoundingClientRect()` alone cannot tell.
 */
function snapshotPainted(): boolean {
    return img.naturalWidth > 0 && img.naturalHeight > 0;
}
// Pixel parity: lay an absolute overlay ([el], the Wasm iframe or live canvas) exactly over the
// snapshot's rendered box so switching transports moves nothing. Falls back to the stage's content
// box when there is no painted snapshot.
function positionOverlay(el: HTMLElement) {
    var sr = stage.getBoundingClientRect();
    var r = img.getBoundingClientRect();
    if (snapshotPainted() && r.width > 0 && r.height > 0) {
        // Offsets are relative to the stage's padding box — subtract its border (clientLeft/Top).
        el.style.left = r.left - sr.left - stage.clientLeft + "px";
        el.style.top = r.top - sr.top - stage.clientTop + "px";
        el.style.width = r.width + "px";
        el.style.height = r.height + "px";
    } else {
        // No snapshot box to mirror (e.g. its render 404'd): fill the stage's content box.
        el.style.left = "12px";
        el.style.top = "12px";
        el.style.width = "calc(100% - 24px)";
        el.style.height = stage.clientHeight - 24 + "px";
    }
}
function positionWasmFrame() {
    positionOverlay(wasmFrame!);
}
// The live canvas is contain-fitted and centred inside the snapshot rect rather than filling it (a
// <canvas> would stretch a differently-shaped frame). liveW/liveH cache the buffer for re-fit on
// resize; unset (before the first frame) it fills the box like positionOverlay.
var liveW = 0;
var liveH = 0;
function fitLiveCanvas() {
    var sr = stage.getBoundingClientRect();
    var r = img.getBoundingClientRect();
    var boxLeft, boxTop, boxW, boxH;
    if (r.width > 0 && r.height > 0) {
        boxLeft = r.left - sr.left - stage.clientLeft;
        boxTop = r.top - sr.top - stage.clientTop;
        boxW = r.width;
        boxH = r.height;
    } else {
        boxLeft = 12;
        boxTop = 12;
        boxW = stage.clientWidth - 24;
        boxH = stage.clientHeight - 24;
    }
    var w = boxW;
    var h = boxH;
    if (liveW > 0 && liveH > 0) {
        var scale = Math.min(boxW / liveW, boxH / liveH);
        w = liveW * scale;
        h = liveH * scale;
    }
    canvas.style.left = boxLeft + (boxW - w) / 2 + "px";
    canvas.style.top = boxTop + (boxH - h) / 2 + "px";
    canvas.style.width = w + "px";
    canvas.style.height = h + "px";
}
// Swap the stage from the snapshot to the already-painted Wasm frame. The snapshot keeps its layout
// slot (visibility, not display) so geometry never shifts.
function revealWasm() {
    if (!wasmActive() || wasmReady) return;
    wasmReady = true;
    if (wasmBootTimer) {
        clearTimeout(wasmBootTimer);
        wasmBootTimer = null;
    }
    clearModeError();
    positionWasmFrame();
    wasmFrame!.classList.add("cp-wasm-live");
    img.style.visibility = "hidden";
    status.textContent = "";
    // Re-sync any control changed during load (the fragment only captured open-time state).
    var patch = wasmOverridePatch();
    if (patch && wasmFrame!.contentWindow)
        wasmFrame!.contentWindow.postMessage(patch, "*");
}
function openWasm() {
    // No-op without a Wasm iframe; enterMode() calls this unconditionally.
    if (!wasmFrame) return;
    // Wasm and the daemon stream are exclusive; enterMode already tore the stream down.
    root.setAttribute("data-mode", "wasm");
    canvas.hidden = true;
    // Keep the snapshot visible while the app loads; the iframe fades in on its first-frame signal.
    positionWasmFrame();
    wasmFrame.hidden = false;
    wasmReady = false;
    wasmFrame.src = wasmInitialSrc();
    status.textContent = "loading Wasm…";
    if (wasmBootTimer) clearTimeout(wasmBootTimer);
    wasmBootTimer = setTimeout(function () {
        if (!wasmReady && wasmActive()) {
            showModeError(
                "Wasm preview didn't start — the in-browser app failed to load.",
            );
        }
    }, 20000);
}
function closeWasm() {
    // No Wasm iframe means nothing to tear down; enterMode() calls this unconditionally.
    if (!wasmFrame) return;
    root.setAttribute("data-mode", "snapshot");
    wasmReady = false;
    if (wasmBootTimer) {
        clearTimeout(wasmBootTimer);
        wasmBootTimer = null;
    }
    wasmFrame.classList.remove("cp-wasm-live");
    wasmFrame.hidden = true;
    wasmFrame.removeAttribute("src");
    img.style.removeProperty("visibility");
    img.hidden = false;
    status.textContent = "";
}
function wasmActive() {
    return !!(wasmToggle && wasmToggle.checked);
}

// Design spec lane: the imported design reference for this preview, shown on the same stage as the
// render so the two can be flipped between. Inert unless the catalog published a reference. The src
// is the server's `/reference/<id>.png`, assigned on first entry so it costs nothing until opened.
const specLane = may<HTMLElement>("cp-spec-lane");
const specImg = may<HTMLImageElement>("cp-spec-img");
const specToggle = may<HTMLInputElement>("cp-spec-toggle");
// Which source the lane compares against; a second comparison is a second source, not a mode. The
// buttons are the source of truth; a single-source lane renders no picker and falls back to the
// carrier's `data-spec-src`.
const specSourceGroup = may<HTMLElement>("cp-spec-sources");
var specSourceButtons: HTMLButtonElement[] = specSourceGroup
    ? Array.prototype.slice.call(
          specSourceGroup.querySelectorAll("[data-cp-spec-source]"),
      )
    : [];
/** The picker as `spec/sources.ts` sees it: the markup read once, into plain descriptors. */
function specSourceList(): SpecSource[] {
    const sources = specSourceButtons.map(function (button) {
        return {
            id: button.getAttribute("data-cp-spec-source") || "",
            label: button.getAttribute("data-spec-label") || "",
            src: button.getAttribute("data-spec-src") || "",
            provenance: button.getAttribute("data-spec-provenance") || "",
        };
    });
    return sourcesOrFallback(
        sources,
        specLane
            ? {
                  id: KIT_SOURCE,
                  label: specLane.getAttribute("data-spec-label") || "",
                  src: specLane.getAttribute("data-spec-src") || "",
                  provenance:
                      specLane.getAttribute("data-spec-provenance") || "",
              }
            : null,
    );
}
function specPressedId(): string | null {
    for (var i = 0; i < specSourceButtons.length; i++)
        if (specSourceButtons[i].getAttribute("aria-pressed") === "true")
            return specSourceButtons[i].getAttribute("data-cp-spec-source");
    return null;
}
/** The picker's button for [id], or null when the lane offers no such source. */
function specSourceButton(id: string): HTMLButtonElement | null {
    for (var i = 0; i < specSourceButtons.length; i++)
        if (specSourceButtons[i].getAttribute("data-cp-spec-source") === id)
            return specSourceButtons[i];
    return null;
}
/**
 * The source the primary chip stands for: the picker's first button, or null with no picker. Not
 * `KIT_SOURCE`: the primary chip is `specSources.first()`, which on a `compareWith`-only catalog is
 * `parallel`.
 */
function specPrimaryId(): string | null {
    if (specSourceButtons.length === 0) return null;
    return specSourceButtons[0].getAttribute("data-cp-spec-source");
}
/** Whether the source the primary chip names is the one on the stage. True when there is no picker. */
function specPrimaryOnStage(): boolean {
    var primary = specPrimaryId();
    return primary === null || specPressedId() === primary;
}
/** What `?specSource=` should say: the picked source when it is not the default, else nothing. */
function specSourceParam(): string {
    return sourceParam(specSourceList(), specPressedId());
}
// The compare strip carries both baselines per row (`data-cp-strip-source`) and shows the one the
// picker names, following every press whether or not the lane is open. See `comparisonStripHtml`.
const specStrip = may<HTMLElement>("cp-compare-strip");

// Strip images come from different producers (e.g. a card in a transparent canvas vs a tightly
// cropped render), so `object-fit: contain` would show them at different scales. Measure and fit
// the visible ink instead, like the design page's render swap.
const stripInk = new WeakMap<HTMLImageElement, InkBounds | null>();
function fitStripImage(image: HTMLImageElement) {
    var shot = image.closest<HTMLElement>(".cp-strip-shot");
    if (!shot || shot.offsetParent === null || !image.complete) return;
    var ink = stripInk.get(image);
    if (ink === undefined) {
        ink = imageInk(image);
        stripInk.set(image, ink);
    }
    var placed = fitInk(
        { width: shot.clientWidth, height: shot.clientHeight },
        ink,
    );
    if (!placed) return;
    Object.assign(image.style, placed);
}
function fitStripImages() {
    if (!specStrip) return;
    specStrip
        .querySelectorAll<HTMLImageElement>(".cp-strip-shot img")
        .forEach(function (image) {
            if (image.complete) fitStripImage(image);
            else
                image.addEventListener("load", () => fitStripImage(image), {
                    once: true,
                });
        });
}
fitStripImages();
if (specStrip && typeof ResizeObserver === "function")
    new ResizeObserver(fitStripImages).observe(specStrip);

// One score per resting-bar source, for the preview on stage. Not per strip row, which would mean
// dozens of eager decodes.
var previewScoreGeneration = 0;
var previewScoreKey = "";
function comparisonChipFor(source: SpecSource): HTMLButtonElement | null {
    if (specSourceButtons.length === 0 || source.id === specPrimaryId())
        return specChip;
    for (const chip of document.querySelectorAll<HTMLButtonElement>(
        "[data-cp-spec-open-source]",
    ))
        if (chip.getAttribute("data-cp-spec-open-source") === source.id)
            return chip;
    return null;
}
function restorePreviewScoreChips() {
    for (const chip of document.querySelectorAll<HTMLButtonElement>(
        "[data-cp-preview-score]",
    )) {
        chip.textContent =
            chip.getAttribute("data-cp-preview-score-label") ||
            chip.textContent;
        chip.removeAttribute("data-cp-preview-score");
        chip.removeAttribute("data-spec-match");
        const tip = chip.getAttribute("data-cp-preview-score-tip");
        if (tip) chip.title = tip;
    }
}
function preScoreComparisonChips(atBaseline: boolean) {
    if (!atBaseline) {
        previewScoreGeneration++;
        previewScoreKey = "";
        restorePreviewScoreChips();
        return;
    }
    // While open, the lane owns its readout and chip; a background result must not repaint over it.
    if (specActive() || !img.complete || !img.naturalWidth) return;
    const api = compareApi();
    // Score the already-decoded stage image rather than reloading its `/render` URL: a no-store
    // override render could hit the daemon again and race the visible frame. Keyed by that frame's
    // URL.
    const actual = img.currentSrc || img.src;
    const sources = specSourceList();
    if (!api || !actual || !sources.length) return;
    const key = actual + "\n" + sources.map((source) => source.src).join("\n");
    if (key === previewScoreKey) return;
    previewScoreKey = key;
    const generation = ++previewScoreGeneration;
    // Share the decoded candidate across sources rather than requesting the render once per chip.
    const actualImage = Promise.resolve(img);
    for (const source of sources) {
        const chip = comparisonChipFor(source);
        if (!chip) continue;
        // A catalog-published kit score is the cheaper, authoritative answer; live scoring is for
        // sources without one.
        if (
            chip.hasAttribute("data-spec-match") &&
            !chip.hasAttribute("data-cp-preview-score")
        )
            continue;
        Promise.all([api.loadImage(source.src), actualImage])
            .then(([reference, candidate]) =>
                api.scoreImages(reference, candidate),
            )
            .then((result) => {
                if (
                    generation !== previewScoreGeneration ||
                    !specAtBaseline() ||
                    specActive()
                )
                    return;
                if (!chip.hasAttribute("data-cp-preview-score-label")) {
                    chip.setAttribute(
                        "data-cp-preview-score-label",
                        source.label,
                    );
                    chip.setAttribute("data-cp-preview-score-tip", chip.title);
                }
                chip.textContent = chipText(source.label, result.percent);
                chip.setAttribute("data-spec-match", matchBand(result.percent));
                chip.setAttribute("data-cp-preview-score", "");
                chip.title = `${result.percent.toFixed(1)}% match against ${source.label} — click to see where`;
            })
            .catch(() => {});
    }
}
img.addEventListener("load", () => preScoreComparisonChips(specAtBaseline()));

function syncSpecStrip() {
    if (!specStrip || !specStrip.hasAttribute("data-cp-strip-source")) return;
    var active = activeSource(specSourceList(), specPressedId());
    if (!active) return;
    specStrip.setAttribute("data-cp-strip-source", active.id);
    // The revealed baseline may have decoded while `display:none`; refit after layout.
    requestAnimationFrame(fitStripImages);
    // Point each row's link at the source the strip is showing, so clicking the next variant keeps
    // the same reference. Done client-side because the pick is not known to the server.
    //
    // Uses `active.id`, not `sourceParam()`: that helper omits this page's default, but the
    // destination resolves `?specSource=` against its own picker, whose default may differ. An
    // unoffered or redundant value is safely dropped by the destination.
    const activeId = active.id;
    specStrip
        .querySelectorAll<HTMLAnchorElement>("a.cp-strip-name")
        .forEach(function (link) {
            var url = new URL(link.href, location.href);
            url.searchParams.set("specSource", activeId);
            link.href = url.pathname + url.search + url.hash;
        });
}
/** The picked source's raster, else the carrier's — the single-source lane's original behaviour. */
function specSrcRaw(): string {
    var active = activeSource(specSourceList(), specPressedId());
    if (active) return active.src;
    return specLane ? specLane.getAttribute("data-spec-src") || "" : "";
}
var specSrc = specSrcRaw();
var specLoaded = false; // the raster is requested once per source, on the lane's entry
// Bind the failure handler once for the element's life; binding per request (each source switch)
// reported one failure several times.
var specErrorBound = false;
// Same origin check as the Wasm iframe src (see wasmBaseSrc): never let a `javascript:` / `data:`
// URL reach the stage.
function specRasterSrc() {
    if (!specSrc) return "";
    var u;
    try {
        u = new URL(specSrc, location.origin);
    } catch (e) {
        return "";
    }
    if (u.origin !== location.origin) return "";
    return u.href;
}
function specAvailable() {
    return !!(specImg && specRasterSrc());
}
/**
 * The picked source, in the shape `<cp-spec-compare>` needs. The raster goes through
 * [specRasterSrc] so canvases get the same origin-checked URL as the `<img>`. A lane with no picker
 * reports the kit.
 */
/** Whether the panel beside the render is the imported spec rather than a sibling's render. */
function specSourceIsSpec() {
    return isSpecSource(activeSource(specSourceList(), specPressedId()));
}
/** What the picked source calls itself, for the surfaces that have to name it. */
function specSourceLabel() {
    var active = activeSource(specSourceList(), specPressedId());
    return active ? active.label : "";
}
function specSourceState() {
    return {
        reference: specRasterSrc(),
        label:
            specSourceLabel() ||
            (specLane ? specLane.getAttribute("data-spec-label") || "" : ""),
        spec: specSourceIsSpec(),
    };
}
function specActive() {
    return !!(specToggle && specToggle.checked);
}
function openSpec() {
    if (!specAvailable()) return;
    root.setAttribute("data-mode", "spec");
    // Out of flow (not merely hidden), like the RC canvas lane, so the stage sizes to the spec
    // raster.
    img.style.display = "none";
    canvas.hidden = true;
    specImg!.hidden = false;
    if (!specErrorBound) {
        specErrorBound = true;
        specImg!.addEventListener("error", function () {
            showModeError("The design spec could not be loaded.");
        });
    }
    if (!specLoaded) {
        specLoaded = true;
        // A property write, like every other image/frame lane here (`img.src`, `wasmFrame.src`),
        // of an origin-checked URL.
        specImg!.src = specRasterSrc();
    }
    // What this panel is when it is not a specification. Set at the end of `openSpec` (the one path
    // into the stage, source switches included) so it is not cleared right after.
    if (status)
        status.textContent = sourceNote(
            activeSource(specSourceList(), specPressedId()),
        );
    // The picker is revealed with the lane, like the view group beside it: while a render is on the
    // stage there is no pair to choose a source for.
    if (specSourceGroup && offersChoice(specSourceList()))
        specSourceGroup.hidden = false;
    if (window.cpSpecCompare)
        window.cpSpecCompare.open(specActualUrl(), specSourceState());
}
/**
 * Switch which source the lane compares against. Everything is rebuilt rather than patched: the
 * raster is re-requested and the comparison re-opened so normalisation runs again for the new pair,
 * keeping diff/triptych/slider in one pixel space.
 */
function pickSpecSource(button: HTMLButtonElement | null): boolean {
    if (!button) return false;
    var nextId = button.getAttribute("data-cp-spec-source") || "";
    if (!changesSource(specSourceList(), specPressedId(), nextId)) return false;
    for (var i = 0; i < specSourceButtons.length; i++)
        specSourceButtons[i].setAttribute(
            "aria-pressed",
            specSourceButtons[i] === button ? "true" : "false",
        );
    specSrc = specSrcRaw();
    syncSpecStrip();
    // The label follows the source, so the badge never names the panel it is no longer showing.
    if (specLane) {
        specLane.setAttribute(
            "data-spec-label",
            button.getAttribute("data-spec-label") || "",
        );
    }
    if (!specImg) return true;
    specLoaded = false;
    if (root.getAttribute("data-mode") !== "spec") return true;
    // Re-enter the lane on the new pair via `openSpec`, the single path into the stage.
    specImg.hidden = false;
    openSpec();
    // The stage hint depends on the source too, and a source switch is not a lane transition, so
    // `enterMode` won't reconcile it.
    updateLiveToggle();
    return true;
}
function closeSpec() {
    if (window.cpSpecCompare) window.cpSpecCompare.close();
    if (!specImg) return;
    if (root.getAttribute("data-mode") === "spec")
        root.setAttribute("data-mode", "snapshot");
    specImg.hidden = true;
    if (specSourceGroup) specSourceGroup.hidden = true;
    img.style.removeProperty("display");
    img.hidden = false;
    // `<cp-spec-compare>` restores the kit chip's label on close. Invalidate the resting score but
    // let refreshSnapshot's decoded frame refill it; fetching here would race two no-store renders.
    previewScoreGeneration++;
    previewScoreKey = "";
}
// Bound once; the buttons are server-rendered and never replaced.
specSourceButtons.forEach(function (button) {
    button.addEventListener("click", function () {
        // A press is a discrete choice: persist `?specSource=` so the pair survives refresh, and
        // push so Back returns to the previous source.
        if (!pickSpecSource(button)) return;
        urlPush = true;
        syncUrl();
    });
});
// Motion lane: the recorded interaction for this preview, in place of the still. A capture is far
// heavier than a PNG and an APNG/GIF plays on decode, so the src is written on first entry, never
// at page load.
const motionImg = may<HTMLImageElement>("cp-motion-img");
const motionChip = may<HTMLButtonElement>("cp-motion-chip");
const motionToggle = may<HTMLInputElement>("cp-motion-toggle");
const motionLane = may<HTMLElement>("cp-motion-lane");
const motionCaption = may<HTMLElement>("cp-motion-caption");
const motionSelect = may<HTMLSelectElement>("cp-motion-select");
var motionOptions: HTMLOptionElement[] = motionSelect
    ? Array.prototype.slice.call(motionSelect.options)
    : [];
var motionErrorBound = false;
// The options are the lane's source of truth; a single capture still renders one (menu hidden).
function motionPicked(): HTMLOptionElement | null {
    if (!motionSelect) return null;
    return (
        motionSelect.options[motionSelect.selectedIndex] ||
        motionOptions[0] ||
        null
    );
}
// Same origin check as the spec raster and Wasm frame: the URL comes from a server-set data-
// attribute.
function motionSrcOf(option: HTMLElement | null) {
    var raw = option ? option.getAttribute("data-motion-src") || "" : "";
    if (!raw) return "";
    var u;
    try {
        u = new URL(raw, location.origin);
    } catch (e) {
        return "";
    }
    if (u.origin !== location.origin) return "";
    return u.href;
}
function motionAvailable() {
    return !!(motionImg && motionSrcOf(motionPicked()));
}
/** The picked capture's published id — what `?motion=` carries. */
function motionPickedId() {
    var option = motionPicked();
    return option ? option.value : "";
}
/**
 * Select a named capture if this preview published one (URL hydration of
 * `?mode=motion&motion=<id>`). Unknown ids are ignored rather than blanking the lane.
 */
function pickMotion(id: string) {
    if (!id || !motionSelect) return false;
    const wanted = motionOptions.find(function (o) {
        return o.value === id;
    });
    if (!wanted) return false;
    motionSelect.value = id;
    return true;
}
// The radio, not `data-mode` (like specActive()/sourceActive()): on URL restore the radio is
// checked before the transition paints.
function motionActive() {
    return !!(motionToggle && motionToggle.checked);
}
// Motion transport: play once, show position, scrub, change speed. An animated `<img>` loops
// forever and can't be paused or slowed, so the viewer decodes the capture with `ImageDecoder`
// (WebCodecs) and paints frames on its own clock. Decisions live in `viewer/motionPlayback.ts`;
// this is the decoder / canvas / DOM glue. The `<img>` remains the fallback when `ImageDecoder` is
// missing or declines the capture; the transport row only appears when frames are addressable.
interface MotionVideoFrame {
    duration: number | null;
    displayWidth: number;
    displayHeight: number;
    close(): void;
}
interface MotionDecoder {
    tracks: {
        ready: Promise<void>;
        selectedTrack: { frameCount: number; animated: boolean } | null;
    };
    completed: Promise<void>;
    decode(options: {
        frameIndex: number;
        completeFramesOnly?: boolean;
    }): Promise<{ image: MotionVideoFrame }>;
    close(): void;
}
type MotionDecoderCtor = new (init: {
    data: ArrayBuffer;
    type: string;
    preferAnimation?: boolean;
}) => MotionDecoder;
const motionPlayerBox = may<HTMLElement>("cp-motion-player");
const motionCanvas = may<HTMLCanvasElement>("cp-motion-canvas");
const motionTransport = may<HTMLElement>("cp-motion-transport");
const motionPlayBtn = may<HTMLButtonElement>("cp-motion-play");
const motionReplayBtn = may<HTMLButtonElement>("cp-motion-replay");
const motionScrub = may<HTMLInputElement>("cp-motion-scrub");
const motionTime = may<HTMLElement>("cp-motion-time");
const motionRateSelect = may<HTMLSelectElement>("cp-motion-rate");
var motionDecoder: MotionDecoder | null = null;
var motionTimeline: rules.MotionTimeline = {
    frameCount: 1,
    frameDurationMs: 16,
};
var motionPlayback: rules.PlaybackState = {
    positionMs: 0,
    playing: false,
    rate: rules.DEFAULT_RATE,
};
// Which capture the in-flight load is for, so an abandoned load cannot paint over a newer pick.
var motionLoadToken = 0;
var motionRaf = 0;
var motionLastTs = 0;
var motionDrawnFrame = -1;
// One decode in flight at a time; per-tick queueing would pile up stale work at slow speeds.
var motionDecodeBusy = false;
// The latest frame requested during an in-flight decode; scrubbing stops the clock, so dropping it
// would leave the canvas stale.
var motionPendingFrame = -1;
var motionActiveSrc = "";
function motionDecoderCtor(): MotionDecoderCtor | null {
    var ctor = (window as unknown as { ImageDecoder?: unknown }).ImageDecoder;
    return typeof ctor === "function" ? (ctor as MotionDecoderCtor) : null;
}
/** A reader who asked for less motion gets the capture ready to play, not playing. */
function motionPrefersStill() {
    return (
        typeof window.matchMedia === "function" &&
        window.matchMedia("(prefers-reduced-motion: reduce)").matches
    );
}
function stopMotionClock() {
    wakeHold.hold("motion", false);
    if (motionRaf) window.cancelAnimationFrame(motionRaf);
    motionRaf = 0;
    motionLastTs = 0;
}
function startMotionClock() {
    if (motionRaf) return;
    wakeHold.hold("motion", true);
    motionLastTs = 0;
    motionRaf = window.requestAnimationFrame(motionClockTick);
}
function motionClockTick(ts: number) {
    motionRaf = 0;
    var elapsed = motionLastTs ? ts - motionLastTs : 0;
    motionLastTs = ts;
    motionPlayback = rules.tick(motionPlayback, motionTimeline, elapsed);
    paintMotion();
    if (motionPlayback.playing)
        motionRaf = window.requestAnimationFrame(motionClockTick);
    else {
        motionLastTs = 0;
        wakeHold.hold("motion", false);
    }
}
/** Draw whichever frame the playhead is on, and put the transport in step with it. */
function paintMotion() {
    drawMotionFrame(rules.frameAt(motionTimeline, motionPlayback.positionMs));
    syncMotionTransport();
}
function drawMotionFrame(index: number) {
    if (!motionDecoder || !motionCanvas) return;
    if (motionDecodeBusy) {
        // Latest intent wins even when it returns to the frame on the canvas, since the in-flight
        // decode will replace that canvas.
        motionPendingFrame = index;
        return;
    }
    if (index === motionDrawnFrame) return;
    var decoder = motionDecoder;
    var token = motionLoadToken;
    motionDecodeBusy = true;
    decoder
        .decode({ frameIndex: index, completeFramesOnly: true })
        .then(function (result) {
            motionDecodeBusy = false;
            // The load may have been abandoned mid-decode (another pick, or lane left); don't paint
            // it.
            if (token !== motionLoadToken || decoder !== motionDecoder) {
                result.image.close();
                return;
            }
            var ctx = motionCanvas!.getContext("2d");
            if (ctx) {
                ctx.clearRect(0, 0, motionCanvas!.width, motionCanvas!.height);
                ctx.drawImage(
                    result.image as unknown as CanvasImageSource,
                    0,
                    0,
                );
            }
            // Must close: a `VideoFrame` holds a decoded surface until released.
            result.image.close();
            motionDrawnFrame = index;
            var pending = motionPendingFrame;
            motionPendingFrame = -1;
            if (pending >= 0 && pending !== motionDrawnFrame)
                drawMotionFrame(pending);
        })
        .catch(function () {
            motionDecodeBusy = false;
            if (token !== motionLoadToken || decoder !== motionDecoder) return;
            // A later frame failed to decode; hand the capture back to the browser's image decoder
            // rather than leaving a stale canvas.
            motionFallbackToImage(motionActiveSrc);
        });
}
function syncMotionTransport() {
    var playing = motionPlayback.playing;
    if (motionPlayBtn) {
        motionPlayBtn.textContent = playing ? "❚❚" : "▶";
        var label = playing
            ? "Pause"
            : rules.atEnd(motionTimeline, motionPlayback.positionMs)
              ? "Play again from the start"
              : "Play";
        motionPlayBtn.setAttribute("aria-label", label);
        motionPlayBtn.title = label;
        motionPlayBtn.setAttribute("aria-pressed", playing ? "true" : "false");
    }
    var frame = rules.frameAt(motionTimeline, motionPlayback.positionMs);
    if (motionScrub) {
        motionScrub.max = String(Math.max(0, motionTimeline.frameCount - 1));
        // Only when it differs: writing `value` mid-drag fights the user.
        if (motionScrub.value !== String(frame))
            motionScrub.value = String(frame);
        motionScrub.setAttribute("aria-valuetext", motionReadout());
        // The filled part of the track, as a CSS variable so fill and thumb cannot disagree.
        motionScrub.style.setProperty(
            "--cp-motion-progress",
            `${Math.round(rules.progress(motionTimeline, motionPlayback.positionMs) * 100)}%`,
        );
    }
    if (motionTime) motionTime.textContent = motionReadout();
}
function motionReadout() {
    return rules.readout(motionTimeline, motionPlayback.positionMs);
}
/** Hand the capture back to the browser: looping, uncontrollable, and better than nothing. */
function motionFallbackToImage(src: string) {
    if (!motionImg) return;
    releaseMotionDecoder();
    if (motionPlayerBox) motionPlayerBox.hidden = true;
    if (motionTransport) motionTransport.hidden = true;
    motionImg.hidden = false;
    if (motionImg.getAttribute("src") !== src) motionImg.src = src;
}
/**
 * Fetch and decode the picked capture, then play it once. Entering the lane is still what costs the
 * network.
 */
function loadMotion(src: string) {
    var token = ++motionLoadToken;
    motionActiveSrc = src;
    releaseMotionDecoder();
    var ctor = motionDecoderCtor();
    if (!ctor || !motionCanvas || !motionPlayerBox) {
        motionFallbackToImage(src);
        return;
    }
    motionPlayerBox.hidden = false;
    if (motionImg) motionImg.hidden = true;
    fetch(src, { credentials: "same-origin" })
        .then(function (response) {
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            var type = response.headers.get("content-type") || "image/apng";
            return response.arrayBuffer().then(function (data) {
                return { data: data, type: type };
            });
        })
        .then(function (body) {
            if (token !== motionLoadToken) return;
            var decoder = new ctor!({
                data: body.data,
                type: body.type,
                preferAnimation: true,
            });
            // Wait for `completed`, not just `tracks.ready`: the frame count is only final once the
            // buffer is fully read.
            return Promise.all([decoder.tracks.ready, decoder.completed]).then(
                function () {
                    return decoder
                        .decode({
                            frameIndex: 0,
                            completeFramesOnly: true,
                        })
                        .then(function (first) {
                            return { decoder: decoder, first: first.image };
                        });
                },
            );
        })
        .then(function (loaded) {
            if (!loaded) return;
            if (token !== motionLoadToken) {
                loaded.first.close();
                loaded.decoder.close();
                return;
            }
            var track = loaded.decoder.tracks.selectedTrack;
            var frameCount = Math.max(1, (track && track.frameCount) || 1);
            // Microseconds on the frame, milliseconds on the timeline. This project's encoders use
            // one delay for every frame, so frame 0's duration is the cadence; with none reported
            // fall back to 60fps rather than a zero-length timeline.
            var durationMs = loaded.first.duration
                ? loaded.first.duration / 1000
                : 16;
            motionCanvas!.width = loaded.first.displayWidth;
            motionCanvas!.height = loaded.first.displayHeight;
            loaded.first.close();
            motionDecoder = loaded.decoder;
            motionTimeline = {
                frameCount: frameCount,
                frameDurationMs: Math.max(1, durationMs),
            };
            motionDrawnFrame = -1;
            // A single-frame capture still gets the transport, so it behaves like every other
            // capture.
            motionPlayback = {
                positionMs: 0,
                playing: !motionPrefersStill() && frameCount > 1,
                rate: rules.normaliseRate(
                    motionRateSelect ? motionRateSelect.value : null,
                ),
            };
            if (motionTransport) motionTransport.hidden = false;
            applyZoom(root.getAttribute("data-zoom"));
            paintMotion();
            if (motionPlayback.playing) startMotionClock();
        })
        .catch(function () {
            if (token !== motionLoadToken) return;
            // No `ImageDecoder`, an unsupported format, or a 404 lands here; the `<img>` shows the
            // first two and raises the lane's error for the third.
            motionFallbackToImage(src);
        });
}
/** Stop the clock and let the decoded frames go. Called on every route out of the lane. */
function releaseMotionDecoder() {
    stopMotionClock();
    motionDecodeBusy = false;
    motionPendingFrame = -1;
    motionDrawnFrame = -1;
    if (motionDecoder) {
        motionDecoder.close();
        motionDecoder = null;
    }
}
function playMotion() {
    var option = motionPicked();
    var src = motionSrcOf(option);
    if (!src) return;
    // Clear a previous capture's error on the way in, so a successful pick doesn't sit under a
    // stale message.
    clearModeError();
    // The full caption for this recording, shown once it is on stage; the menu carries only the
    // opening clause. The server omits `data-motion-detail` when the caption is the whole title.
    // With the menu hidden (single capture) the readout names the frames.
    if (motionCaption) {
        var detail = option
            ? option.getAttribute("data-motion-detail") || ""
            : "";
        motionCaption.textContent =
            detail || (motionOptions.length > 1 || !option ? "" : option.text);
    }
    // Every entry and pick starts the capture from the top.
    loadMotion(src);
}
function openMotion() {
    if (!motionAvailable()) return;
    root.setAttribute("data-mode", "motion");
    // Out of flow, like the spec and Source lanes, so the stage sizes to the capture.
    img.style.display = "none";
    canvas.hidden = true;
    if (motionLane) motionLane.hidden = false;
    if (!motionErrorBound) {
        motionErrorBound = true;
        motionImg!.addEventListener("error", function () {
            showModeError("The recorded interaction could not be loaded.");
        });
    }
    if (status) status.textContent = "";
    playMotion();
}
function closeMotion() {
    if (!motionImg) return;
    // `data-mode`, not motionActive(): during a transition the entered lane's radio is already
    // checked.
    if (root.getAttribute("data-mode") === "motion")
        root.setAttribute("data-mode", "snapshot");
    motionImg.hidden = true;
    if (motionLane) motionLane.hidden = true;
    if (motionPlayerBox) motionPlayerBox.hidden = true;
    if (motionTransport) motionTransport.hidden = true;
    // Stop both the canvas path (clock and decoder) and the `<img>` fallback, and bump the token so
    // an in-flight fetch cannot paint into a hidden lane.
    motionLoadToken++;
    releaseMotionDecoder();
    motionImg.removeAttribute("src");
    img.style.removeProperty("display");
    img.hidden = false;
}
// Source lane: the usage code behind this card, in place of the render. A chip of its own (like the
// spec chip), not a renderer option. The snippet is fetched from `/usage/<id>` on first entry only,
// since deriving it can cost the server a GitHub read.
const sourceChip = may<HTMLButtonElement>("cp-source-chip");
const sourcePanel = may<HTMLElement>("cp-source-panel");
const sourceToggle = may<HTMLInputElement>("cp-source-toggle");
// Component API metadata comes from catalog discovery, not from the lazily fetched snippet. Keep
// its template after the panel is cleared so loading, failure and success all show the same facts.
const sourceProperties = may<HTMLTemplateElement>("cp-source-properties");
var sourceLoaded = false;
var pendingSourceData: UsageSnippet | null | undefined;
function sourceAvailable() {
    return !!(sourceChip && sourcePanel && usageSrc());
}
// The side lane (a samples catalog page, server-set from `ServeWeb.PageRole`): the panel opens at
// load, the render stays on stage beside it, and closing is a no-op.
function sideSourceLane() {
    return root.getAttribute("data-source-lane") === "side";
}
// The radio, not `data-mode`, as in specActive(): on URL restore the radio is checked first.
function sourceActive() {
    return !!(sourceToggle && sourceToggle.checked);
}
// Same origin check as the spec raster and the Wasm frame.
function usageSrc() {
    var raw = sourceChip ? sourceChip.getAttribute("data-usage-src") || "" : "";
    if (!raw) return "";
    var u;
    try {
        u = new URL(raw, location.origin);
    } catch (e) {
        return "";
    }
    if (u.origin !== location.origin) return "";
    return u.href;
}
function openSource() {
    if (!sourceAvailable()) return;
    // On the side lane the render is not replaced, so `data-mode` keeps naming the stage's lane.
    if (!sideSourceLane()) {
        root.setAttribute("data-mode", "source");
        // Out of flow, like the spec lane, so the stage sizes to the panel.
        img.style.display = "none";
        canvas.hidden = true;
    }
    sourcePanel!.hidden = false;
    if (status) status.textContent = "";
    if (sourceLoaded && pendingSourceData !== undefined) {
        var pending = pendingSourceData;
        pendingSourceData = undefined;
        renderSource(pending);
    }
    if (!sourceLoaded) {
        sourceLoaded = true;
        renderSourceMessage("Loading…");
        fetch(usageSrc(), { credentials: "same-origin" })
            .then(function (r) {
                return r.ok ? r.json() : Promise.reject(r.status);
            })
            .then(function (data: UsageSnippet | null) {
                // Don't initialise CodeMirror in a display:none panel (it caches bad dimensions);
                // defer until visible.
                if (sourcePanel!.hidden) pendingSourceData = data;
                else renderSource(data);
            })
            .catch(function () {
                // A 404 (cleaner declined, or source moved) is a real answer; point at the
                // provenance row's `source` link instead of leaving a blank panel.
                sourceLoaded = false;
                renderSourceMessage(
                    "The usage source for this preview could not be derived. The \u201csource\u201d link " +
                        "above opens the preview\u2019s own Kotlin on GitHub.",
                );
            });
    }
}
function closeSource() {
    if (!sourcePanel) return;
    // On the side lane the panel is part of the page, so there is nothing to close.
    if (sideSourceLane()) return;
    // `data-mode`, not sourceActive(): during a transition the entered lane's radio is already
    // checked.
    if (root.getAttribute("data-mode") === "source")
        root.setAttribute("data-mode", "snapshot");
    sourcePanel.hidden = true;
    img.style.removeProperty("display");
    img.hidden = false;
}
function renderSourceMessage(text: string) {
    if (!sourcePanel) return;
    sourcePanel.textContent = "";
    var p = document.createElement("p");
    p.className = "cp-source-note";
    p.textContent = text;
    sourcePanel.appendChild(p);
    appendSourceProperties();
}
function appendSourceProperties() {
    if (!sourcePanel || !sourceProperties) return;
    sourcePanel.appendChild(sourceProperties.content.cloneNode(true));
}
function codeMirrorStylesReady() {
    var link = document.querySelector<HTMLLinkElement>(
        'link[href*="codemirror.css"]',
    );
    return !!(link && link.sheet);
}
/**
 * Paints the fetched snippet. Every node is filled through `textContent`, never innerHTML: the
 * payload is repository source.
 */
function renderSource(data: UsageSnippet | null) {
    if (!sourcePanel) return;
    sourcePanel.textContent = "";
    // Says what this is and, when the catalog has not declared its helpers, that they remain in the
    // snippet. Must agree with the playground's seed note.
    var note = document.createElement("p");
    note.className = "cp-source-note";
    if (data && data.scaffoldsDeclared === false) {
        note.className += " cp-source-note--warn";
        note.textContent =
            "This catalog has not declared what its own helpers mean in plain " +
            "Compose, so some of the code below is catalog machinery rather than usage.";
    } else if (data && data.residue && data.residue.length) {
        note.className += " cp-source-note--warn";
        note.textContent =
            "Usage code, with " +
            data.residue.join(", ") +
            " left as the catalog wrote them \u2014 those will not resolve outside it.";
    } else {
        note.textContent = "The plain Compose that produces this render.";
    }
    sourcePanel.appendChild(note);
    appendSourceProperties();
    var pre = document.createElement("pre");
    var code = document.createElement("code");
    var sourceText = (data && data.text) || "";
    code.textContent = sourceText;
    pre.appendChild(code);
    sourcePanel.appendChild(pre);
    // Upgrade the readable <pre> only once CodeMirror initialises; the asset is optional (a failed
    // load costs only line numbers and colours). Uses the same `text/x-kotlin` grammar as the
    // playground.
    var selectionTarget: HTMLElement = code;
    if (window.CodeMirror && codeMirrorStylesReady()) {
        var mirrorHost = document.createElement("div");
        mirrorHost.className = "cp-source-code";
        sourcePanel.insertBefore(mirrorHost, pre);
        try {
            var mirror = window.CodeMirror(mirrorHost, {
                value: sourceText,
                mode: "text/x-kotlin",
                lineNumbers: true,
                readOnly: "nocursor",
                viewportMargin: Infinity,
                screenReaderLabel: "Kotlin usage source",
            });
            selectionTarget = mirror.getWrapperElement();
            pre.remove();
        } catch (e) {
            mirrorHost.remove();
        }
    }
    var actions = document.createElement("div");
    actions.className = "cp-source-actions";
    var copy = document.createElement("button");
    copy.type = "button";
    copy.className = "cp-fmt-toggle";
    copy.textContent = "Copy";
    copy.addEventListener("click", function () {
        var text = sourceText;
        var done = function () {
            copy.textContent = "Copied";
            setTimeout(function () {
                copy.textContent = "Copy";
            }, 1500);
        };
        // The Clipboard API needs a secure context (a LAN host over HTTP is not one). The fallback
        // selects the code rather than telling the visitor to press a shortcut with nothing
        // selected.
        var fallback = function () {
            try {
                var range = document.createRange();
                // Select CodeMirror's code body (keeps line breaks, excludes the gutter), or the
                // plain <code>.
                var visibleCode =
                    selectionTarget.querySelector<HTMLElement>(
                        ".CodeMirror-code",
                    ) || selectionTarget;
                range.selectNodeContents(visibleCode);
                var sel = window.getSelection();
                sel!.removeAllRanges();
                sel!.addRange(range);
                // Deprecated, but the only synchronous copy in an insecure context.
                if (document.execCommand && document.execCommand("copy")) {
                    sel!.removeAllRanges();
                    done();
                    return;
                }
            } catch (e) {
                /* fall through to the prompt */
            }
            copy.textContent = /Mac|iP(hone|ad|od)/.test(navigator.platform)
                ? "Selected \u2014 press \u2318C"
                : "Selected \u2014 press Ctrl+C";
            setTimeout(function () {
                copy.textContent = "Copy";
            }, 3000);
        };
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text).then(done, fallback);
        } else {
            fallback();
        }
    });
    actions.appendChild(copy);
    // Link to the editor only when the server sent a href (it can compile the catalog).
    if (data && data.playgroundHref) {
        var run = document.createElement("a");
        run.className = "cp-format-link";
        run.href = data.playgroundHref;
        run.textContent = "open in playground \u2192";
        actions.appendChild(run);
    }
    if (data && data.blobUrl) {
        var whole = document.createElement("a");
        whole.className = "cp-format-link";
        whole.href = data.blobUrl;
        whole.rel = "noopener";
        whole.textContent = "the whole sticker \u2192";
        actions.appendChild(whole);
    }
    sourcePanel.appendChild(actions);
    renderApiDocs(data);
}
/**
 * The API reference list under the snippet: each platform symbol used, linked to its KDoc on
 * `developer.android.com`. Links rather than a lane because that site refuses framing. An empty or
 * absent list renders no heading.
 */
function renderApiDocs(data: UsageSnippet | null) {
    if (!sourcePanel) return;
    var usable = usableApiDocs(data && data.apiDocs);
    if (!usable.length) return;
    var section = document.createElement("div");
    section.className = "cp-api-docs";
    var heading = document.createElement("h2");
    heading.className = "cp-api-docs-heading";
    heading.textContent = "API reference";
    section.appendChild(heading);
    var list = document.createElement("ul");
    list.className = "cp-api-docs-list";
    usable.forEach(function (d) {
        var item = document.createElement("li");
        var link = document.createElement("a");
        link.className = "cp-api-doc-link";
        if (d.composable) link.className += " cp-api-doc-link--composable";
        link.href = d.url!;
        link.rel = "noopener noreferrer";
        link.target = "_blank";
        link.textContent = d.name!;
        if (d.fqn) link.title = d.fqn;
        item.appendChild(link);
        list.appendChild(item);
    });
    section.appendChild(list);
    sourcePanel.appendChild(section);
}
/**
 * The render the spec is compared against: the stage's blob when it is that frame, else a fresh
 * `/render` URL.
 *
 * Reusing the blob avoids a second no-store render racing the daemon, and compares exactly what the
 * visitor saw. It is stale in two cases:
 * - An interactive lane (Live, Wasm, RC players) applies overrides in place without re-pointing
 *   `/render`, so `#cp-img` still holds the old snapshot. `enterMode` records the outgoing lane.
 * - The SVG toggle: the blob is a vector document a `<canvas>` may not size; `data-cp-src`'s
 *   extension tells. The fallback is the PNG for the current controls; if the server can't honour
 *   them it refuses and the comparison reports itself unavailable.
 */
function specActualUrl() {
    var blob = img.getAttribute("data-cp-blob");
    var rendered = (img.getAttribute("data-cp-src") || "").split("?")[0];
    if (blob && outgoingStage === "snapshot" && rendered.slice(-4) === ".png")
        return blob;
    return renderUrl(".png");
}

// In-browser Remote Compose canvas lane: paints a captured `.rc` document with the vendored player
// (RC.RcdPlayer) into #cp-rc-canvas with no daemon; RC knob edits apply via setNamed*Override +
// repaint. Opt-in like Live / Wasm.
const rcCanvasEl = may<HTMLCanvasElement>("cp-rc-canvas");
const rcToggle = may<HTMLInputElement>("cp-rc-toggle");
const rcWasmFrame = may<HTMLIFrameElement>("cp-rc-wasm");
const rcWasmToggle = may<HTMLInputElement>("cp-rc-wasm-toggle");
var rcWasmReady = false;
var rcWasmBootTimer: ReturnType<typeof setTimeout> | null = null;
var hasRcDoc = root.getAttribute("data-has-rc-doc") === "1";
var rcPlayer: RcPlayer | null = null; // created lazily on first open
var rcCtx: RemoteContext | null = null; // its WebRemoteContext, for named-value overrides
var rcReady = false; // a first frame is painted and the canvas revealed
var rcScriptState = 0; // 0 = not loaded, 1 = loading, 2 = ready
var rcScriptWaiters: Array<(ok: boolean) => void> = [];
function rcAvailable() {
    return !!(hasRcDoc && rcCanvasEl);
}
function rcActive() {
    return !!(rcToggle && rcToggle.checked);
}
function rcWasmActive() {
    return !!(rcWasmToggle && rcWasmToggle.checked);
}
// Lazy-load the shared player bundle once; queue callers while it loads so it is never injected
// twice.
function ensureRcScript(cb: (ok: boolean) => void) {
    if (rcScriptState === 2 || window.RC) {
        rcScriptState = 2;
        cb(true);
        return;
    }
    rcScriptWaiters.push(cb);
    if (rcScriptState === 1) return;
    rcScriptState = 1;
    var s = document.createElement("script");
    s.src = "/rc-player/bundle.js";
    s.onload = function () {
        rcScriptState = 2;
        var ws = rcScriptWaiters;
        rcScriptWaiters = [];
        ws.forEach(function (f) {
            f(true);
        });
    };
    s.onerror = function () {
        rcScriptState = 0;
        var ws = rcScriptWaiters;
        rcScriptWaiters = [];
        ws.forEach(function (f) {
            f(false);
        });
    };
    document.head.appendChild(s);
}
// The `.rc` document URL: same `base` + token/session as the snapshot, no override query (knobs
// apply client-side).
function rcDocUrl() {
    var parts: string[] = [];
    if (token) parts.push("token=" + encodeURIComponent(token));
    if (session) parts.push("session=" + encodeURIComponent(session));
    var qs = parts.join("&");
    return (
        base +
        "/render/" +
        encodeURIComponent(previewId) +
        ".rc" +
        (qs ? "?" + qs : "")
    );
}
// Parse #RRGGBB / #AARRGGBB (optionally %23-escaped) into a 0xAARRGGBB int (opaque when no alpha).
function parseRcColor(v: string) {
    if (!v) return null;
    var h = v.replace(/^%23/, "").replace(/^#/, "");
    if (h.length === 6) h = "FF" + h;
    if (h.length !== 8) return null;
    var n = parseInt(h, 16);
    return isNaN(n) ? null : n >>> 0;
}
// Push every RC knob's value onto the player context, then repaint. Names are USER:-qualified to
// match the document's named variables; kinds mirror query()'s rc.<name>=<kind>:<value>.
function applyRcOverrides() {
    const ctx = rcCtx;
    if (!ctx) return;
    controls(".cp-rc-knob").forEach(function (el) {
        var name = el.getAttribute("data-rc-name");
        if (!name) return;
        var qn = "USER:" + name;
        var kind = el.getAttribute("data-rc-kind") || "string";
        var val = controlValue(el);
        try {
            if (kind === "color") {
                var argb = parseRcColor(val);
                if (argb !== null && ctx.setNamedColorOverride)
                    ctx.setNamedColorOverride(qn, argb);
            } else if (kind === "float" || kind === "dp") {
                var f = parseFloat(val);
                if (!isNaN(f) && ctx.setNamedFloatOverride)
                    ctx.setNamedFloatOverride(qn, f);
            } else if (kind === "int" || kind === "integer") {
                var n = parseInt(val, 10);
                if (!isNaN(n) && ctx.setNamedIntegerOverride)
                    ctx.setNamedIntegerOverride(qn, n);
            } else if (kind === "bool" || kind === "boolean") {
                // The player's setNamedBooleanOverride only records the value, so route booleans
                // through the integer setter as 1/0, matching the daemon's mapping.
                if (ctx.setNamedIntegerOverride) {
                    ctx.setNamedIntegerOverride(qn, val === "true" ? 1 : 0);
                }
            } else if (ctx.setNamedStringOverride) {
                ctx.setNamedStringOverride(qn, val);
            }
        } catch (e) {
            /* a knob the document doesn't declare is a harmless no-op */
        }
    });
    if (rcPlayer && rcPlayer.repaint) rcPlayer.repaint();
}
function openRc() {
    if (!rcAvailable()) return;
    root.setAttribute("data-mode", "rc");
    canvas.hidden = true;
    rcReady = false;
    status.textContent = "loading RC player…";
    ensureRcScript(function (ok: boolean) {
        if (!ok || !window.RC) {
            showModeError("The Remote Compose player failed to load.");
            return;
        }
        if (!rcActive()) return; // toggled away while the script loaded
        // `@font-face` is lazy and canvas neither triggers a load nor repaints when one finishes,
        // so await the vendored faces (`/rc-fonts/fonts.css`) alongside the fetch; otherwise the
        // first paint uses the viewer's own sans-serif.
        var rcFonts = window.cpRcFonts
            ? window.cpRcFonts.ready()
            : Promise.resolve();
        Promise.all([
            rcFonts,
            fetch(rcDocUrl()).then(function (r) {
                if (!r.ok) throw new Error("doc " + r.status);
                return r.arrayBuffer();
            }),
        ])
            .then(function (settled) {
                var buf = settled[1];
                if (!rcActive()) return null;
                // Size the canvas to the preview's real pixel dimensions before loading: the player
                // derives the document viewport from the canvas size at load time. The baked
                // snapshot carries those dimensions.
                var w = img.naturalWidth || 0,
                    h = img.naturalHeight || 0;
                if (w > 0 && h > 0) {
                    rcCanvasEl!.width = w;
                    rcCanvasEl!.height = h;
                }
                if (!rcPlayer) rcPlayer = new window.RC!.RcdPlayer(rcCanvasEl!);
                return rcPlayer.loadFromArrayBuffer(buf);
            })
            .then(function () {
                if (!rcActive()) return;
                rcCtx = rcPlayer!.getRemoteContext
                    ? rcPlayer!.getRemoteContext!()
                    : null;
                applyRcOverrides();
                if (rcPlayer!.repaint) rcPlayer!.repaint!();
                revealRc();
            })
            .catch(function () {
                showModeError("Rendering the Remote Compose document failed.");
            });
    });
}
// Swap the stage to the painted canvas; the snapshot goes display:none so the stage takes the
// document's size.
function revealRc() {
    if (!rcActive() || rcReady) return;
    rcReady = true;
    clearModeError();
    rcCanvasEl!.hidden = false;
    img.style.display = "none";
    status.textContent = "";
}
function closeRc() {
    if (!rcCanvasEl) return;
    root.setAttribute("data-mode", "snapshot");
    rcReady = false;
    rcCanvasEl.hidden = true;
    img.style.removeProperty("display");
    img.hidden = false;
}

// AndroidX-conformant CMP/Wasm RC lane: an isolated app that receives the document URL and
// announces its first rendered frame.
function positionRcWasmFrame() {
    if (rcWasmFrame) positionOverlay(rcWasmFrame);
}
function rcWasmNamedValues() {
    var values: Array<{ name: string; kind: string; value: string }> = [];
    controls(".cp-rc-knob").forEach(function (el) {
        var name = el.getAttribute("data-rc-name");
        if (!name) return;
        values.push({
            name: name,
            kind: el.getAttribute("data-rc-kind") || "string",
            value: controlValue(el),
        });
    });
    return values;
}
function rcWasmSrc() {
    var absoluteDoc = new URL(rcDocUrl(), location.origin).href;
    var src =
        "/rc-player-wasm/index.html?src=" + encodeURIComponent(absoluteDoc);
    var uiMode = may<HTMLSelectElement>("cp-uiMode");
    if (uiMode && (uiMode.value === "light" || uiMode.value === "dark")) {
        src += "&theme=" + encodeURIComponent(uiMode.value);
    }
    var namedValues = rcWasmNamedValues();
    if (namedValues.length) {
        src +=
            "&namedValues=" + encodeURIComponent(JSON.stringify(namedValues));
    }
    return src;
}
function revealRcWasm() {
    if (!rcWasmActive() || rcWasmReady) return;
    rcWasmReady = true;
    if (rcWasmBootTimer) {
        clearTimeout(rcWasmBootTimer);
        rcWasmBootTimer = null;
    }
    clearModeError();
    positionRcWasmFrame();
    rcWasmFrame!.classList.add("cp-wasm-live");
    img.style.visibility = "hidden";
    status.textContent = "";
}
function openRcWasm() {
    if (!rcWasmFrame) return;
    root.setAttribute("data-mode", "rc-wasm");
    canvas.hidden = true;
    positionRcWasmFrame();
    rcWasmFrame.hidden = false;
    rcWasmReady = false;
    rcWasmFrame.src = rcWasmSrc();
    status.textContent = "loading CMP Wasm RC player…";
    if (rcWasmBootTimer) clearTimeout(rcWasmBootTimer);
    rcWasmBootTimer = setTimeout(function () {
        if (!rcWasmReady && rcWasmActive())
            showModeError("CMP Wasm RC player didn't start.");
    }, 20000);
}
function closeRcWasm() {
    if (!rcWasmFrame) return;
    rcWasmReady = false;
    if (rcWasmBootTimer) {
        clearTimeout(rcWasmBootTimer);
        rcWasmBootTimer = null;
    }
    rcWasmFrame.classList.remove("cp-wasm-live");
    rcWasmFrame.hidden = true;
    rcWasmFrame.removeAttribute("src");
    img.style.removeProperty("visibility");
}
if (rcWasmFrame) {
    window.addEventListener("message", function (e) {
        if (
            e.source !== rcWasmFrame.contentWindow ||
            e.origin !== location.origin
        )
            return;
        if (e.data === "cp-rc-wasm-ready") revealRcWasm();
        else if (
            typeof e.data === "string" &&
            e.data.indexOf("cp-rc-wasm-error:") === 0
        ) {
            showModeError(
                "Rendering the Remote Compose document failed in CMP Wasm.",
            );
        } else if (
            e.data &&
            (e.data.type === "cp-rc-host-action" ||
                e.data.type === "cp-rc-host-named-action")
        ) {
            // The viewer never executes an action payload; it exposes the validated event to an
            // embedding host.
            window.dispatchEvent(
                new CustomEvent(e.data.type, { detail: e.data }),
            );
            status.textContent =
                e.data.type === "cp-rc-host-action"
                    ? "Remote Compose host action " + String(e.data.actionId)
                    : "Remote Compose named action “" +
                      String(e.data.name || "") +
                      "”";
        }
    });
}

function onControlsChanged() {
    // Keep the copyable direct links current no matter which transport handles the change.
    refreshLinks();
    if (rcWasmActive()) {
        // Remote Compose only consumes Day/Night here, so reload the isolated player with the new
        // theme query and the same tokened document URL.
        openRcWasm();
        return;
    }
    if (wasmActive()) {
        // Recompose in place once the app is up; before that, re-point the initial src (the
        // fragment carries the overrides).
        if (wasmReady && wasmFrame!.contentWindow) {
            wasmFrame!.contentWindow.postMessage(wasmOverridePatch(), "*");
        } else {
            wasmFrame!.src = wasmInitialSrc();
        }
        return;
    }
    if (live.checked && ws && ws.readyState === 1) {
        ws.send(
            JSON.stringify({
                type: "setOverrides",
                overrides: liveOverrides(),
            }),
        );
        return;
    }
    // Not in an interactive lane. Whenever the server can produce a fresh overridden render (a live
    // daemon session, or a catalog whose carried daemon re-renders on demand), re-point /render.
    if (!staticSnapshot || canRenderOverrides) {
        refreshSnapshot();
        return;
    }
    // A purely static catalog whose only interactive lane is the in-browser app: the wasm-honoured
    // controls can only apply there, so auto-enable Wasm instead of a /render the catalog can't
    // serve.
    if (wasmToggle) {
        setMode("wasm");
        return;
    }
    refreshSnapshot();
}

// The Static/Live toggle drives these transports: "live" (daemon stream), "wasm" (in-browser app),
// "png" (static snapshot, default). closeStream / closeWasm are idempotent. The static lane also
// honours the SVG toggle (same <img>, `.svg`); entering a live lane clears SVG.
const svgToggle = may<HTMLButtonElement>("cp-svg-toggle");
function svgOn() {
    return !!(svgToggle && svgToggle.getAttribute("aria-pressed") === "true");
}
// Leaving the static lane drops both vector affordances (SVG and exploded) together; live lanes
// produce raster frames.
function dropVectorModes() {
    if (svgToggle) svgToggle.setAttribute("aria-pressed", "false");
    clearExploded();
}
// Un-press the 3D chip and re-sync its controls; shared by every path out of the vector lane.
function clearExploded() {
    if (!explodeToggle || !explodeOn()) return;
    explodeToggle.setAttribute("aria-pressed", "false");
    if (root) root.setAttribute("data-exploded", "0");
    syncExplodeControls();
    explodeEnabledSvg = false;
}
// The lane the current transition is leaving, latched by enterMode before teardown. Read by
// specActualUrl to know whether the snapshot <img> holds what the visitor saw.
var outgoingStage = "snapshot";
function enterMode(m: string) {
    if (m !== root.getAttribute("data-mode")) {
        trackInteraction("renderer_changed", { mode: m });
    }
    // A lane switch is a discrete choice, so its URL sync pushes a history entry. Set here because
    // every transition passes through this function.
    urlPush = true;
    // Read before any close() below tears the lane down (see specActualUrl).
    outgoingStage = root.getAttribute("data-mode") || "snapshot";
    // A mode switch always clears a prior lane's error; the new lane re-raises its own if it fails.
    clearModeError();
    // Every other transition closes the spec lane, restoring the snapshot <img>.
    if (m !== "spec") closeSpec();
    // Likewise for Source.
    if (m !== "source") closeSource();
    // Likewise for Motion, which also stops the capture playing in the background.
    if (m !== "motion") closeMotion();
    if (m === "live") {
        cancelSnapshotLoading();
        snapshotExt = ".png";
        dropVectorModes();
        closeWasm();
        closeRc();
        closeRcWasm();
        openStream();
    } else if (m === "wasm") {
        cancelSnapshotLoading();
        snapshotExt = ".png";
        dropVectorModes();
        closeStream();
        closeRc();
        closeRcWasm();
        openWasm();
    } else if (m === "rc") {
        cancelSnapshotLoading();
        snapshotExt = ".png";
        dropVectorModes();
        closeStream();
        closeWasm();
        closeRcWasm();
        openRc();
    } else if (m === "rc-wasm") {
        cancelSnapshotLoading();
        snapshotExt = ".png";
        dropVectorModes();
        closeStream();
        closeWasm();
        closeRc();
        openRcWasm();
    } else if (m === "source") {
        // Source is not a render: cancel any in-flight snapshot, leave interactive lanes and show
        // the panel. Going through this branch keeps the picker, chips and URL reconciled, so
        // `?mode=source` is bookmarkable.
        cancelSnapshotLoading();
        snapshotExt = ".png";
        dropVectorModes();
        closeStream();
        closeWasm();
        closeRc();
        closeRcWasm();
        openSource();
    } else if (m === "motion") {
        // Same for Motion (`?mode=motion`).
        cancelSnapshotLoading();
        snapshotExt = ".png";
        dropVectorModes();
        closeStream();
        closeWasm();
        closeRc();
        closeRcWasm();
        openMotion();
    } else if (m === "spec") {
        // Same for the spec lane; nothing is re-requested on the way in.
        cancelSnapshotLoading();
        snapshotExt = ".png";
        dropVectorModes();
        closeStream();
        closeWasm();
        closeRc();
        closeRcWasm();
        openSpec();
    } else {
        // Browser RC lanes clear the server-side pick while they paint. Returning to the static
        // lane must restore a non-baked default before query() runs, or the chip and the rendered
        // player disagree.
        var restoredPlayer = rules.restoreStaticPlayer(
            {
                defaultBackend: rcDefaultBackend,
                pickedBackend: rcPlayerBackend,
                picked: !!rcPlayerPicked,
            },
            rcBakedPlayer,
        );
        rcPlayerBackend = restoredPlayer.pickedBackend;
        rcPlayerPicked = restoredPlayer.picked;
        closeStream();
        closeWasm();
        closeRc();
        closeRcWasm();
        // Static snapshot lane: raster PNG, or the vector SVG when the format toggle is on.
        snapshotExt = svgOn() ? ".svg" : ".png";
        if (svgOn()) root.setAttribute("data-mode", "svg");
    }
    syncOverlayToggles();
    syncServerControls();
    // Re-reconcile the picker on every transition. Runs before updateLiveToggle(), which reads the
    // combo's value.
    syncLaneSelect();
    updateLiveToggle();
    // Render the static lane after syncServerControls() has re-enabled controls for it, so an rc.*
    // value edited during a Wasm detour is included. Live/wasm lanes render themselves.
    if (
        m !== "live" &&
        m !== "wasm" &&
        m !== "rc" &&
        m !== "rc-wasm" &&
        m !== "spec" &&
        m !== "source" &&
        m !== "motion"
    )
        refreshSnapshot();
    // Interactive lanes never reach refreshLinks, so sync here so every transition writes `?mode=`
    // immediately. (For the snapshot branch this is a no-op replace.)
    else {
        syncUrl();
        syncSpecBaseline();
    }
}
// SVG toggle: swap the static snapshot between raster and vector. From a live lane it drops back to
// the static vector render; in the static lane it swaps the extension in place.
if (svgToggle) {
    svgToggle.addEventListener("click", function () {
        var turnOn = !svgOn();
        svgToggle.setAttribute("aria-pressed", turnOn ? "true" : "false");
        // The vector lane is a lane too, so make the sync (via refreshSnapshot or enterMode) a
        // push.
        urlPush = true;
        // Every non-static lane has to be left before the vector snapshot can own the stage, or a
        // hidden snapshot reloads under a canvas / iframe / spec image that is still showing.
        // Leaving the vector lane also leaves the exploded view, which is a view of it.
        if (!turnOn) clearExploded();
        // `sourceActive()` too: only closeSource() puts the snapshot <img> back in flow.
        if (
            turnOn &&
            (live.checked ||
                wasmActive() ||
                rcActive() ||
                rcWasmActive() ||
                specActive() ||
                sourceActive() ||
                motionActive())
        ) {
            setMode("png"); // enterMode("png") reads svgOn() → renders the .svg
        } else {
            snapshotExt = turnOn ? ".svg" : ".png";
            root.setAttribute("data-mode", turnOn ? "svg" : "snapshot");
            refreshSnapshot();
        }
    });
}
// The exploded 3D toggle. It is a view of the vector export, so pressing it from elsewhere switches
// to the static SVG lane first.
if (explodeToggle) {
    explodeToggle.addEventListener("click", function () {
        var turnOn = !explodeOn();
        explodeToggle.setAttribute("aria-pressed", turnOn ? "true" : "false");
        if (root) root.setAttribute("data-exploded", turnOn ? "1" : "0");
        syncExplodeControls();
        urlPush = true;
        if (turnOn) dropRcPlayerPick();
        if (turnOn && svgToggle && !svgOn()) {
            // Turning SVG on is a lane change; let its handler make the single request (with
            // `exploded=1` folded in). Remembered so leaving 3D can hand the lane back.
            explodeEnabledSvg = true;
            svgToggle.click();
            return;
        }
        if (!turnOn) explodeEnabledSvg = false;
        refreshSnapshot();
    });
}
// The angle / separation / depth knobs. Continuous drags leave `urlPush` false so they replace one
// history entry.
EXPLODE_KNOBS.forEach(function (pair) {
    var el = may<HTMLInputElement>(pair[0]);
    if (!el) return;
    el.addEventListener("input", function () {
        updateExplodeReadout(el!);
        if (explodeOn()) scheduleExplodeRender();
    });
});
// Grey the knobs out while the view is off.
function syncExplodeControls() {
    var on = explodeOn();
    EXPLODE_KNOBS.forEach(function (pair) {
        var el = may<HTMLInputElement>(pair[0]);
        if (el) el.disabled = !on;
    });
}
function updateExplodeReadout(el: HTMLInputElement) {
    var out = may<HTMLElement>(el.id + "-value");
    if (out)
        out.textContent = el.value + (el.getAttribute("data-cp-unit") || "");
}
// Coalesce slider `input` events into roughly one re-projection request per frame.
var explodeTimer: ReturnType<typeof setTimeout> | null = null;
function scheduleExplodeRender() {
    if (explodeTimer) clearTimeout(explodeTimer);
    explodeTimer = setTimeout(function () {
        explodeTimer = null;
        refreshSnapshot();
    }, 120);
}
// "Full page (scroll)" re-renders the active snapshot format and reshapes both export URLs.
if (scrollLong) {
    scrollLong.addEventListener("change", function () {
        refreshSnapshot();
    });
}
// Overlay toggles (touchOverlay) are rendered by the daemon, so they are enabled whenever the
// daemon lane is reachable; ticking one from the static snapshot switches into Live (see
// onOverlayChanged). Disabled only when the lane can't be entered (e.g. behind sign-in). Called on
// every transition.
const overlayToggles =
    document.querySelectorAll<HTMLInputElement>(".cp-overlay");
function syncOverlayToggles() {
    var on = !!(live && !live.disabled);
    Array.prototype.forEach.call(overlayToggles, function (el) {
        el.disabled = !on;
    });
}
// Enable/disable display controls to match what the active session can render. Server-render
// controls (Size / Device / Orientation) apply whenever the server can re-render (live daemon,
// carried daemon, or active stream). The wasm-honoured trio (Day/Night / Locale / Font scale) is
// also enabled when a Wasm app backs the session. Mirrors the server-rendered markup across
// transitions.
var serverOnlyControlIds = [
    "device",
    "orientation",
    "sizeMode",
    "fixedW",
    "fixedH",
    "minW",
    "minH",
    "maxW",
    "maxH",
];
var wasmHonouredControlIds = ["localeTag", "fontScale"];
var alwaysDark = root.getAttribute("data-always-dark") === "1";
// This preview is redrawn by replaying a captured Remote Compose document, not by re-running the
// composable, so controls that need a fresh composition are dead (the server refuses them with
// 409). The narrow set matches `CatalogLiveRouting.irReplayDroppedOverrideNames`:
// - Day/Night and Font scale stay live: a document can resolve both from the host at paint time.
// - RC knobs stay live except string-valued ones, and only in the server lane (see `.cp-rc-knob`
//   below).
var irReplay = root.getAttribute("data-ir-replay") === "1";
// A replayed preview whose session publishes declared themes as named colour values; the server
// rewrites `?themeProvider=` into those seeds, so provider options work here.
var replayThemes = root.getAttribute("data-replay-themes") === "1";
// Force [el] off for the IR-replay reason, with a hover explanation. Only ever adds the disable:
// callers assign `el.disabled` from their own lane logic just before.
function gateForIrReplay(el: Control | null, dead: boolean, why: string) {
    if (!el) return;
    if (dead) {
        if (!el.hasAttribute("data-ir-title"))
            el.setAttribute("data-ir-title", el.title || "");
        el.title = why;
        el.disabled = true;
    } else if (el.hasAttribute("data-ir-title")) {
        el.title = el.getAttribute("data-ir-title") || "";
        el.removeAttribute("data-ir-title");
    }
}
var IR_WHY_RECOMPOSE =
    "Not available on this preview — it is replayed from a captured document, " +
    "which cannot be recomposed.";
var IR_WHY_RC_STRING =
    "Not applied on the server lane — the Remote Compose player does not honour string " +
    "overrides on a replayed document. Switch to the JS player to edit it.";
function syncServerControls() {
    // The Wasm lane only honours the wasm-honoured trio + knobs (see wasmOverridePatch); size /
    // device / orientation / app theme re-point /render, which the iframe ignores, so disable them
    // while Wasm is active.
    var onWasm = wasmActive();
    // The RC canvas lane likewise honours only its own (client-side) RC knobs.
    var onRcCanvas = rcActive();
    var onRcWasm = rcWasmActive();
    var onRc = onRcCanvas || onRcWasm;
    // Lanes with a fixed frame (imported raster, finished recording): no override re-points them,
    // so every re-rendering control is dead here, as in Wasm / RC canvas. One predicate for every
    // family below, shared with `activeThemeChoice` via `onFixedFrameLane`.
    var onFixedFrame = onFixedFrameLane();
    var canServerRender =
        !onWasm &&
        !onRc &&
        !onFixedFrame &&
        (!staticSnapshot || canRenderOverrides || !!(live && live.checked));
    serverOnlyControlIds.forEach(function (id) {
        var el = may<Control>("cp-" + id);
        if (el) el.disabled = !canServerRender;
    });
    // The wasm-honoured trio stays live in the Wasm lane, so enable it when the server can render
    // or a Wasm app backs the session, but not in the RC canvas lane.
    wasmHonouredControlIds.forEach(function (id) {
        var el = may<Control>("cp-" + id);
        if (el)
            el.disabled =
                (id === "uiMode" && alwaysDark) ||
                !(
                    canServerRender ||
                    (wasmSrc && !onRc && !onFixedFrame) ||
                    (id === "uiMode" && onRcWasm)
                );
        // Locale is the one member a replayed document cannot express (`stringResource` resolved at
        // capture, no locale system variable), so it is dead in every replay lane; live in a real
        // CMP/Wasm app.
        if (id === "localeTag")
            gateForIrReplay(el, irReplay && !onWasm, IR_WHY_RECOMPOSE);
    });
    // Day/Night options work in Wasm; provider options need the daemon. Keep the select usable when
    // either can work and gate each option family.
    if (themeChoice) {
        var hasDeclaredThemes =
            themeChoice.getAttribute("data-has-declared-themes") === "true";
        // This preview's subject is a theme (@FixedTheme or a Themes specimen), so both axes are
        // off. Recomputed here because this block reassigns `themeChoice.disabled` and would
        // otherwise re-enable it.
        var fixedTheme =
            themeChoice.getAttribute("data-fixed-theme") === "true";
        // `!irReplay`: a provider theme wraps the preview in a composable, which needs a
        // composition. Day/Night stays offered (the player derives it at draw time).
        var canProviderTheme =
            !fixedTheme &&
            hasDeclaredThemes &&
            !onWasm &&
            !onRc &&
            !onFixedFrame &&
            (!irReplay || replayThemes) &&
            (!staticSnapshot || canRenderOverrides);
        // Wear has no day/night axis, but Night (Default) must stay selectable when provider themes
        // are offered so a chosen provider can be cleared.
        var canDefaultTheme =
            !fixedTheme &&
            !onRc &&
            !onFixedFrame &&
            ((!alwaysDark && (canServerRender || !!wasmSrc)) ||
                (alwaysDark && canProviderTheme));
        Array.prototype.forEach.call(themeChoice.options, function (option) {
            option.disabled =
                option.value.indexOf("theme:") === 0
                    ? !canProviderTheme
                    : !canDefaultTheme;
        });
        themeChoice.disabled = !canDefaultTheme && !canProviderTheme;
    }
    // The bar mirrors the select, so reconcile it in the same pass.
    syncThemeBar();
    // RC knobs are live in the RC canvas lane (applied client-side) and the CMP/Wasm lane (applied
    // on reload); elsewhere they need server rendering.
    controls(".cp-rc-knob").forEach(function (el) {
        var onBrowserRc = rcActive() || rcWasmActive();
        el.disabled = onBrowserRc
            ? false
            : onWasm ||
              onFixedFrame ||
              !(!staticSnapshot || canRenderOverrides);
        // A string seed doesn't land on the server lane: the Android player's `setUserLocalString`
        // stops at `RemoteComposeState.overrideData`, so the render is unchanged. Browser RC lanes
        // apply it, hence gating on the lane.
        if ((el.getAttribute("data-rc-kind") || "string") === "string") {
            gateForIrReplay(el, irReplay && !onBrowserRc, IR_WHY_RC_STRING);
        }
    });
    // Author-declared knobs are seeded into a composition by the daemon's named-override planner,
    // so they are dead in every replay lane except a real CMP/Wasm app. Their base enabled state is
    // the server-rendered `disabled`, recorded once and restored each pass; since `gateForIrReplay`
    // only adds the disable, without a base a knob disabled for the snapshot lane would stay off in
    // Wasm (and `wasmOverridePatch()` skips disabled knobs).
    controls(".cp-knob").forEach(function (el) {
        if (!el.hasAttribute("data-base-disabled")) {
            el.setAttribute("data-base-disabled", el.disabled ? "1" : "0");
        }
        el.disabled =
            el.getAttribute("data-base-disabled") === "1" || onFixedFrame;
        gateForIrReplay(el, irReplay && !onWasm, IR_WHY_RECOMPOSE);
    });
}
// Programmatic switch: tick the hidden mode radio, then run the transition.
function setMode(m: string) {
    var radioId =
        m === "live"
            ? "cp-live"
            : m === "wasm"
              ? "cp-wasm-toggle"
              : m === "rc-wasm"
                ? "cp-rc-wasm-toggle"
                : m === "spec"
                  ? "cp-spec-toggle"
                  : m === "source"
                    ? "cp-source-toggle"
                    : m === "motion"
                      ? "cp-motion-toggle"
                      : m === "rc"
                        ? "cp-rc-toggle"
                        : "cp-mode-png";
    var r = radioId ? may<HTMLInputElement>(radioId) : null;
    if (r) r.checked = true;
    enterMode(m);
}
Array.prototype.forEach.call(
    ticks('input[name="cp-mode"]'),
    function (r: HTMLInputElement) {
        r.addEventListener("change", function () {
            if (r.checked) enterMode(r.value);
        });
    },
);
// The primary chip names the renderer on stage ("AndroidX View", "Camaelon JS", "Figma spec",
// "Live"), its dot says whether it is interactive, and clicking toggles into the best live lane
// (daemon stream, else Wasm) and back to the static snapshot. Overrides still apply while static;
// the toggle is about interacting. The corner backend badge matches (see backendBadgeScript).
const liveToggle = may<HTMLButtonElement>("cp-live-toggle");
const liveToggleLabel = may<HTMLElement>("cp-live-toggle-label");
// The chip's second half: the label names the current lane, this names where a click goes. See
// ServeWeb's `liveToggleVerb`.
const liveToggleVerb = may<HTMLElement>("cp-live-toggle-verb");
// The invitation on the stage itself, for visitors looking at the picture rather than the toolbar.
// `updateLiveToggle()` decides when it shows.
const stageLiveHint = may<HTMLElement>("cp-stage-live-hint");
// Present only when GitHub auth is the one thing blocking the daemon lane (see ServeWeb's
// liveSignInLink). A link, not `#cp-live-toggle`, so the toggle's disabled / aria-pressed handling
// must not touch it.
const liveSignIn = may<HTMLAnchorElement>("cp-live-signin");
const modeHint = may<HTMLElement>("cp-mode-hint");
// The lane decisions live in `cli/serve-web/src/viewer/laneState.ts`.
function liveOffer() {
    return { daemon: !!(live && !live.disabled), wasm: !!wasmToggle };
}
function liveTransportAvailable() {
    return rules.liveTransportAvailable(liveOffer());
}
function bestLiveMode() {
    return rules.bestLiveMode(liveOffer());
}
// Lanes painting a running composition (daemon stream, Wasm app, both RC player lanes). This is
// what the status dot reports.
function laneFlags() {
    return {
        rcWasm: rcWasmActive(),
        rc: rcActive(),
        wasm: wasmActive(),
        spec: specActive(),
        live: !!(live && live.checked),
    };
}
function anyInteractive() {
    return rules.anyInteractive(laneFlags());
}
// The lane the picker is (or would be) on, in the combo's value space. The daemon stream is the
// live form of the picked renderer, so it falls through to the static player lane.
function currentLaneValue() {
    return rules.currentLaneValue(laneFlags(), {
        defaultBackend: rcDefaultBackend || "",
        pickedBackend: rcPlayerBackend || "",
        picked: !!rcPlayerPicked,
    });
}
// What the chip calls the current lane: "Live" while the daemon stream is up, otherwise the
// matching option's label so chip and combo agree. With no combo it names the stage's state
// ("Snapshot"); see ServeWeb's `primaryLaneLabel`.
//
// The renderer the chip returns to when the current lane isn't one of the combo's (the design
// spec). Server-rendered from `primaryLaneLabel`.
function defaultLaneLabel() {
    return (
        (liveToggle && liveToggle.getAttribute("data-default-lane-label")) ||
        "Live preview"
    );
}
function laneLabelText() {
    var options: Map<string, string> | null = null;
    if (laneSelect) {
        options = new Map();
        for (const o of Array.from(laneSelect.options)) {
            options.set(o.value, o.textContent || "");
        }
    }
    return rules.laneLabelText({
        live: !!(live && live.checked),
        laneOptions: options,
        wanted: currentLaneValue(),
        defaultLabel: defaultLaneLabel(),
    });
}
// Whether a click on the stage enters the live lane now. The chip's verb, the hint badge and the
// stage click handler all ask this, so they cannot disagree.
function liveInvited() {
    return rules.liveInviteAvailable({
        interactive: anyInteractive(),
        transport: liveTransportAvailable(),
        mode: currentMode(),
    });
}
function updateLiveToggle() {
    var interactive = anyInteractive();
    if (liveToggle) {
        liveToggle.setAttribute("aria-pressed", interactive ? "true" : "false");
        // Enabled when there is a live lane to enter, or an interactive lane on stage (the only way
        // out of an RC player lane the combo entered).
        liveToggle.disabled = !liveTransportAvailable() && !interactive;
    }
    if (liveToggleLabel) liveToggleLabel.textContent = laneLabelText();
    // The spec chip reports the lane's state; driven from here so every route out un-presses it.
    if (specChip) {
        var onSpecLane = specActive();
        var specState = rules.laneChip({
            onLane: onSpecLane,
            available: specAvailable(),
        });
        // Pressed reports which source is on the stage, not merely that the lane is open, so only
        // the chip for the visible reference lights up.
        var primaryOnStage = specPrimaryOnStage();
        specChip.setAttribute(
            "aria-pressed",
            specState.pressed && primaryOnStage ? "true" : "false",
        );
        specChip.disabled = specState.disabled;
        // The title follows the pressed state: with a sibling on stage this chip selects its own
        // source rather than closing the lane.
        specChip.title =
            onSpecLane && primaryOnStage
                ? "Showing the imported design spec — click to return to the render"
                : specChip.getAttribute("data-spec-chip-tip") || specChip.title;
    }
    // Peer chips follow the same source so exactly one reads pressed. Queried here because this
    // runs before the chips are collected further down.
    var peers = document.querySelectorAll<HTMLButtonElement>(
        "[data-cp-spec-open-source]",
    );
    for (var peerIndex = 0; peerIndex < peers.length; peerIndex++) {
        var peerChip = peers[peerIndex];
        peerChip.setAttribute(
            "aria-pressed",
            specActive() &&
                peerChip.getAttribute("data-cp-spec-open-source") ===
                    specPressedId()
                ? "true"
                : "false",
        );
    }
    // Same for the Source chip.
    if (sourceChip) {
        var onSourceLane = sourceActive();
        sourceChip.setAttribute(
            "aria-pressed",
            onSourceLane ? "true" : "false",
        );
        sourceChip.disabled = !sourceAvailable() && !onSourceLane;
        sourceChip.title = onSourceLane
            ? "Showing the usage source \u2014 click to return to the render"
            : sourceChip.getAttribute("data-source-chip-tip") ||
              sourceChip.title;
    }
    // Same for the Motion chip.
    if (motionChip) {
        var onMotionLane = motionActive();
        motionChip.setAttribute(
            "aria-pressed",
            onMotionLane ? "true" : "false",
        );
        motionChip.disabled = !motionAvailable() && !onMotionLane;
        motionChip.title = onMotionLane
            ? "Playing the recorded interaction \u2014 click to return to the render"
            : motionChip.getAttribute("data-motion-chip-tip") ||
              motionChip.title;
    }
    // The tooltip inverts with the chip's meaning (enter Live from static, exit to snapshot from an
    // interactive lane). The sign-in case never reaches here: that affordance is an <a>, so
    // `liveToggle` is null.
    if (liveToggle) {
        liveToggle.title = interactive
            ? "Interactive — click to return to the static snapshot"
            : liveTransportAvailable()
              ? "Static snapshot — click for the live, interactive preview"
              : "Static snapshot — this session has no live lane to switch to";
    }
    // The chip's verb names the lane a click goes to; blank (and out of layout) when there is
    // nowhere to go.
    if (liveToggleVerb) {
        var verb = interactive
            ? "▸ Snapshot"
            : liveTransportAvailable()
              ? "▸ Live"
              : "";
        liveToggleVerb.textContent = verb;
        liveToggleVerb.hidden = !verb;
    }
    // The stage invitation, from the same predicate as the stage click handler. Set on the viewer
    // root because the hint and the snapshot's `cursor: pointer` both need it.
    var invited = liveInvited();
    if (root) root.setAttribute("data-live-invite", invited ? "true" : "false");
    if (stageLiveHint) stageLiveHint.hidden = !invited;
    if (modeHint) {
        modeHint.textContent = sourceActive()
            ? "usage source — not a render"
            : motionActive()
              ? "recorded interaction — not a live render"
              : specActive()
                ? // The one lane whose hint depends on WHICH pair it is showing. "Not a render" is
                  // the whole point of the sentence and it stops being true the moment the picker
                  // points at a paired catalog: that panel is a render, just not this catalog's.
                  specSourceIsSpec()
                    ? "imported design spec — not a render"
                    : specSourceLabel()
                      ? specSourceLabel() + "'s render — not this catalog's"
                      : "a paired catalog's render — not this catalog's"
                : interactive
                  ? "interactive — click / scroll the preview"
                  : // "no live lane" is only true when there is genuinely nothing to switch to. When the lane
                    // exists and is merely behind sign-in, the transport radio is (correctly) disabled, so
                    // liveTransportAvailable() is false and this used to read "no live lane" right beside a
                    // chip offering to sign in for one — telling the visitor in the same breath that the thing
                    // is available and that it doesn't exist. The sign-in link's presence is the signal.
                    liveTransportAvailable()
                    ? "static snapshot"
                    : liveSignIn
                      ? "static snapshot — sign in for live"
                      : "static snapshot (no live lane)";
    }
}
if (liveToggle) {
    liveToggle.addEventListener("click", function () {
        // Toggling off returns to the static snapshot, which for RC means the server-side player
        // the combo shows; the JS canvas lane has no static form.
        if (anyInteractive()) {
            setMode("png");
        } else {
            var m = bestLiveMode();
            if (m) setMode(m);
        }
    });
}
// The stage itself is the discoverable way into the live lane. Single click (a card in the grid
// uses long-press because a tap navigates; nothing competes here). No keyboard path: the chip is
// already a button in the tab order. Once streaming, the canvas sits on top and owns pointer
// events.
{
    // Where the pointer went down, so a drag (zoom/pan, text selection) isn't read as an entry.
    var stageInvitePress: { x: number; y: number } | null = null;
    img.addEventListener("pointerdown", function (event) {
        stageInvitePress = event.isPrimary
            ? { x: event.clientX, y: event.clientY }
            : null;
    });
    img.addEventListener("click", function (event) {
        var press = stageInvitePress;
        stageInvitePress = null;
        // Modified and non-primary clicks belong to the browser.
        if (
            event.button !== 0 ||
            event.ctrlKey ||
            event.metaKey ||
            event.shiftKey ||
            event.altKey
        )
            return;
        if (
            press &&
            Math.max(
                Math.abs(event.clientX - press.x),
                Math.abs(event.clientY - press.y),
            ) > 6
        )
            return;
        if (!liveInvited()) return;
        var mode = bestLiveMode();
        if (mode) setMode(mode);
    });
}
// The design-spec chip toggles in and out of the spec lane; leaving returns to the static snapshot,
// the lane the comparison views draw from.
//
// The comparison group's other sources, on the resting bar beside the kit chip: each enters the
// lane on its own source (the in-lane picker is hidden until then). Press first, enter second:
// `pickSpecSource` returns early off the lane, so `setMode("spec")` then opens directly on the
// requested pair without flashing the kit.
var specPeerChips: HTMLButtonElement[] = Array.prototype.slice.call(
    document.querySelectorAll<HTMLButtonElement>("[data-cp-spec-open-source]"),
);
for (var pi = 0; pi < specPeerChips.length; pi++) {
    (function (chip: HTMLButtonElement) {
        chip.addEventListener("click", function () {
            if (!specAvailable()) return;
            var wanted = chip.getAttribute("data-cp-spec-open-source") || "";
            // Every comparison chip is a toggle: pressing the source already on stage returns to
            // the render.
            if (closesSource(specActive(), specPressedId(), wanted)) {
                setMode("png");
                return;
            }
            var target: HTMLButtonElement | null = null;
            for (var i = 0; i < specSourceButtons.length; i++) {
                if (
                    specSourceButtons[i].getAttribute("data-cp-spec-source") ===
                    wanted
                )
                    target = specSourceButtons[i];
            }
            if (!target) return;
            var changed = pickSpecSource(target);
            if (!specActive()) setMode("spec");
            // Already on the lane: `enterMode` won't run, so make its push here.
            else if (changed) {
                urlPush = true;
                syncUrl();
            }
        });
    })(specPeerChips[pi]);
}
if (specChip) {
    specChip.addEventListener("click", function () {
        // Leave the lane only when this chip's own source is on stage; with a sibling showing, the
        // unpressed chip selects rather than dismisses.
        if (specActive() && specPrimaryOnStage()) setMode("png");
        else if (specAvailable()) {
            // The lane's default view (Triptych; see DEFAULT_VIEW in `spec/views.ts`) applies; a
            // URL-named view still wins. This chip does own the source: it names the kit, so press
            // the kit first and enter second (as the peer chips do) so it never shows a sibling's
            // render under a "Figma" label. A no-op when the kit is already pressed or the lane has
            // one source.
            var primaryId = specPrimaryId();
            var changed = primaryId
                ? pickSpecSource(specSourceButton(primaryId))
                : false;
            if (!specActive()) setMode("spec");
            // Already on the lane with a sibling showing: `enterMode` won't run, so push here.
            else if (changed) {
                urlPush = true;
                syncUrl();
            }
        }
    });
}
// The Source chip toggles in and out like the spec chip, returning to the static snapshot. On the
// side lane the panel is already open, so it scrolls the code into view instead (useful on narrow
// screens).
if (sourceChip) {
    sourceChip.addEventListener("click", function () {
        if (sideSourceLane()) {
            if (sourcePanel) sourcePanel.scrollIntoView({ block: "nearest" });
        } else if (sourceActive()) setMode("png");
        else if (sourceAvailable()) setMode("source");
    });
}
// On the side lane the code is fetched at load: every visitor to a samples page wants it.
if (sideSourceLane()) openSource();
// The Motion chip toggles like the spec and Source chips, returning to the static snapshot.
if (motionChip) {
    motionChip.addEventListener("click", function () {
        if (motionActive()) setMode("png");
        else if (motionAvailable()) setMode("motion");
    });
}
// The per-capture menu, shown only with more than one capture. Switching swaps the capture in
// place; it is not a mode transition.
if (motionSelect) {
    motionSelect.addEventListener("change", function () {
        if (motionActive()) {
            playMotion();
            // Replace rather than push: switching recording is not a lane change.
            syncUrl();
        } else setMode("motion");
    });
}
// The transport. Each button hands the playhead to `viewer/motionPlayback.ts` (where the decisions
// live, with tests) and paints the result; this file only starts and stops the clock.
if (motionPlayBtn) {
    motionPlayBtn.addEventListener("click", function () {
        motionPlayback = rules.toggle(motionPlayback, motionTimeline);
        paintMotion();
        if (motionPlayback.playing) startMotionClock();
        else stopMotionClock();
    });
}
if (motionReplayBtn) {
    motionReplayBtn.addEventListener("click", function () {
        motionPlayback = rules.replay(motionPlayback);
        paintMotion();
        startMotionClock();
    });
}
if (motionScrub) {
    // `input`, not `change`: frames follow the thumb while dragging.
    motionScrub.addEventListener("input", function () {
        stopMotionClock();
        motionPlayback = rules.seek(
            motionPlayback,
            motionTimeline,
            parseInt(motionScrub!.value, 10) || 0,
        );
        paintMotion();
    });
}
if (motionRateSelect) {
    motionRateSelect.addEventListener("change", function () {
        // Rate only: changing speed must not move the playhead.
        motionPlayback = {
            ...motionPlayback,
            rate: rules.normaliseRate(motionRateSelect!.value),
        };
        syncMotionTransport();
    });
}
// The renderer combo: one `<select>` with every lane this preview can be drawn by: the RC players
// (`rc:camaelon-js` client-side via setMode("rc"), `rc:cmp-wasm` in its own frame, and
// `androidx-view` / `androidx-embedded` / `cmp-android` / `cmp-jvm` server-side via rcPlayer=<id>),
// the Wasm app, and the design spec. Its value tracks the active lane however it was entered.
if (laneSelect) {
    // The real reconciler for the hoisted stub. The combo is a command menu, not a state field (the
    // chip beside it holds state), so reconciling returns it to its placeholder on every route out
    // of a lane. Except in catalog mode, which drops the chip: there the menu holds the selection.
    // The server sets `data-lane-state` exactly when the chip is omitted.
    var laneHoldsState = laneSelect.getAttribute("data-lane-state") === "1";
    syncLaneSelect = function () {
        if (!laneHoldsState) {
            laneSelect.value = "";
            return;
        }
        // Only a value the menu offers; an unknown one would blank the control.
        var lane = currentLaneValue();
        var offered = Array.prototype.some.call(
            laneSelect.options,
            function (o: HTMLOptionElement) {
                return o.value === lane;
            },
        );
        laneSelect.value = offered ? lane : "";
    };
    function pickLane(value: string) {
        if (!value) return; // the placeholder; nothing was chosen
        if (value.indexOf("rc:") === 0) {
            var wire = rules.normalizeRcPlayer(value.substring(3));
            if (wire === rules.RC_PLAYER.camaelonJs) {
                // The client canvas lane. Leave the server pick untouched so returning to a
                // server-side player restores it.
                rcPlayerPicked = false;
                if (!rcActive()) setMode("rc");
                else syncLaneSelect();
            } else if (wire === rules.RC_PLAYER.cmpWasm) {
                rcPlayerPicked = false;
                if (!rcWasmActive()) setMode("rc-wasm");
                else syncLaneSelect();
            } else {
                // A server-side player. Record the pick first so the single static-lane render
                // carries rcPlayer=<wire>, then setMode("png") closes any other lane and renders
                // once.
                rcPlayerBackend = wire;
                rcPlayerPicked = true;
                setMode("png");
            }
        } else if (value === "wasm") {
            if (!wasmActive()) setMode("wasm");
            else syncLaneSelect();
        } else {
            setMode("png");
        }
    }
    laneSelect.addEventListener("change", function () {
        pickLane(laneSelect.value);
    });
    syncLaneSelect();
}
// Keep the live canvas overlay on the snapshot's slot on reflow (Wasm has its own hook below).
window.addEventListener("resize", function () {
    if (live && live.checked && !canvas.hidden) fitLiveCanvas();
    if (rcWasmActive()) positionRcWasmFrame();
});
// Re-pin the active overlay whenever the snapshot loads: positionOverlay/fitLiveCanvas measure the
// img, which is only final once new bytes decode.
img.addEventListener("load", function () {
    if (live && live.checked && !canvas.hidden) fitLiveCanvas();
    // The checkerboard phase moves with the overlay, so re-send the patch (with bgPhase) to a ready
    // app.
    if (wasmActive()) {
        positionWasmFrame();
        if (wasmReady && wasmFrame!.contentWindow) {
            wasmFrame!.contentWindow.postMessage(wasmOverridePatch(), "*");
        }
    }
    if (rcWasmActive()) positionRcWasmFrame();
});
if (wasmToggle) {
    // The app posts "cp-wasm-ready" once its first frame is on the canvas. Match on source (the
    // frame's contentWindow), not e.origin; the payload is a fixed string.
    window.addEventListener("message", function (e) {
        if (e.source !== wasmFrame!.contentWindow || e.data !== "cp-wasm-ready")
            return;
        revealWasm();
    });
    // Fallback for app builds without the ready signal: reveal shortly after load.
    wasmFrame!.addEventListener("load", function () {
        setTimeout(function () {
            revealWasm();
        }, 8000);
    });
    // The overlay tracks the snapshot's box, which moves when the page reflows — and the
    // checkerboard phase moves with it, so re-hand it to the app.
    window.addEventListener("resize", function () {
        if (!wasmActive()) return;
        positionWasmFrame();
        if (wasmReady && wasmFrame!.contentWindow) {
            wasmFrame!.contentWindow.postMessage(wasmOverridePatch(), "*");
        }
    });
    // The Transparent toggle (<cp-bg-toggle>, flipping `cp-bg-transparent` on <html>) and the
    // render theme change the stage backdrop, which the app mirrors; watch the class.
    if (typeof MutationObserver === "function") {
        new MutationObserver(function () {
            if (!wasmActive() || !wasmReady || !wasmFrame!.contentWindow)
                return;
            wasmFrame!.contentWindow.postMessage(wasmOverridePatch(), "*");
        }).observe(document.documentElement, {
            attributes: true,
            attributeFilter: ["class"],
        });
    }
}
// Coalesce continuous edits (font-scale slider, typed sizes): each step would otherwise issue a
// `/render`, most of which a busy daemon refuses with 503. The readout still updates on every
// event.
const scheduleControlsChanged = debounced(
    onControlsChanged,
    CONTINUOUS_EDIT_DEBOUNCE_MS,
);
// One edit of a continuous control: the URL, copyable links and spec baseline update now; only the
// render waits for the drag to stop (`onControlsChanged` re-runs the same sync when it fires).
cancelPendingContinuousEdit = scheduleControlsChanged.cancel;
function onContinuousControlEdit() {
    refreshLinks();
    scheduleControlsChanged();
}
if (fs) {
    fs.addEventListener("input", function () {
        fsVal!.textContent = fs.value;
        fontScaleTouched = true;
        onContinuousControlEdit();
    });
}
fields.forEach(function (f) {
    var el = document.getElementById("cp-" + f);
    if (el) el.addEventListener("change", onControlsChanged);
});
// Size mode: show only the input rows the chosen mode uses (Within shows min + max), then
// re-render.
const sizeMode = may<HTMLSelectElement>("cp-sizeMode");
if (sizeMode) {
    var syncSizeRows = function () {
        var m = sizeMode.value;
        var show: Record<string, boolean> = {
            fixed: m === "fixed",
            min: m === "min" || m === "within",
            max: m === "max" || m === "within",
        };
        ["fixed", "min", "max"].forEach(function (g) {
            var row = document.getElementById("cp-size-" + g);
            if (row) row.hidden = !show[g];
        });
    };
    syncSizeRows();
    sizeMode.addEventListener("change", function () {
        syncSizeRows();
        onControlsChanged();
    });
    [
        "cp-fixedW",
        "cp-fixedH",
        "cp-minW",
        "cp-minH",
        "cp-maxW",
        "cp-maxH",
    ].forEach(function (id) {
        var el = document.getElementById(id);
        // Typed, so `input` fires per keystroke; coalesce like the slider.
        if (el) el.addEventListener("input", onContinuousControlEdit);
    });
}
// Overlay toggles are daemon-rendered: on the live lane they push setOverrides; off it, ticking one
// enters the live lane (already part of openStream()'s initial overrides). A separate handler so a
// toggle mid-connect can't fall through to the snapshot / wasm branches.
function onOverlayChanged() {
    // Overlays are part of overrides(), so re-sync the page URL and export links.
    refreshLinks();
    if (live && live.checked) {
        if (ws && ws.readyState === 1) {
            ws.send(
                JSON.stringify({
                    type: "setOverrides",
                    overrides: liveOverrides(),
                }),
            );
        }
        return;
    }
    // Only a check starts Live; unticking from the snapshot lane must not.
    if (anyOverlayChecked() && live && !live.disabled) setMode("live");
}
function anyOverlayChecked() {
    return Array.prototype.some.call(overlayToggles, function (el) {
        return el.checked;
    });
}
Array.prototype.forEach.call(overlayToggles, function (el) {
    el.addEventListener("change", onOverlayChanged);
});
// Named knobs re-render on edit (text/number via "input", toggles via "change"). Unlike the
// app-theme selector and feature toggles, the Wasm tier honours them, so an edit drives whichever
// transport is live.
//
// A closed value-set knob renders as a <select>, which turns an unknown assigned value into "", and
// "" is a real string-knob value. So add a URL-only value (hand-written, or since renamed) as an
// option, as the server does for an unknown baked value.
function adoptChoiceValue(el: Control, value: string) {
    if (!(el instanceof HTMLSelectElement) || value === "") return;
    for (var i = 0; i < el.options.length; i++)
        if (el.options[i].value === value) return;
    var option = document.createElement("option");
    option.value = value;
    option.textContent = value;
    el.insertBefore(option, el.firstChild);
}
// Which transport will carry a knob edit, resolved before the URL sync because it decides who owns
// the history entry (see onKnobEdited).
function knobRoute() {
    if (wasmActive()) return "wasm";
    if (live.checked && ws && ws.readyState === 1) return "live";
    if (canRenderOverrides) return "snapshot";
    // A published catalog can't re-render server-side but its in-browser app can: auto-enable Wasm
    // and let its load carry the edit (wasmInitialSrc bakes the patch into the fragment).
    if (staticSnapshot && wasmToggle) return "enable-wasm";
    return "none";
}
// [discrete] marks an edit that earns its own history entry (a closed-set pick); continuous edits
// replace. The `enable-wasm` route writes no history here at all: `enterMode` pushes for the lane
// change, and a push or replace here would spend the visitor's previous entry on an intermediate
// state.
function onKnobEdited(discrete: boolean) {
    var route = knobRoute();
    if (discrete && route !== "enable-wasm") urlPush = true;
    refreshLinks(route === "enable-wasm");
    if (route === "wasm") {
        if (wasmReady && wasmFrame!.contentWindow) {
            wasmFrame!.contentWindow.postMessage(wasmOverridePatch(), "*");
        } else {
            wasmFrame!.src = wasmInitialSrc();
        }
        return;
    }
    if (route === "live") {
        ws!.send(
            JSON.stringify({
                type: "setOverrides",
                overrides: liveOverrides(),
            }),
        );
    } else if (route === "snapshot") {
        refreshSnapshot();
    } else if (route === "enable-wasm") {
        setMode("wasm");
    }
}
controls(".cp-knob").forEach(function (el) {
    el.addEventListener(
        el.type === "checkbox" ? "change" : "input",
        function () {
            // Picking from a closed-set <select> is discrete (own history entry); typing stays
            // continuous.
            onKnobEdited(el.tagName === "SELECT");
        },
    );
});
// The app-theme selector and feature toggles route only through the server daemon (provider themes
// and focus/gesture overlays are server-side), never the wasm path.
function onKnobChanged() {
    refreshLinks();
    if (live.checked && ws && ws.readyState === 1) {
        ws.send(
            JSON.stringify({
                type: "setOverrides",
                overrides: liveOverrides(),
            }),
        );
    } else if (canRenderOverrides) {
        refreshSnapshot();
    }
}
if (themeChoice)
    themeChoice.addEventListener("change", function () {
        themeChoice.setAttribute("data-theme-active", "1");
        syncThemeBar();
        // Like a lane switch: a picked theme earns its own history entry.
        urlPush = true;
        if (chosenThemeProvider()) onKnobChanged();
        else onControlsChanged();
    });
// Detected-feature toggles re-render on the daemon like a knob.
controls(".cp-feature").forEach(function (el) {
    el.addEventListener("change", onKnobChanged);
});
// The gesture a click asked to fire, consumed by whichever override builder runs for this render.
// Read-once: an invocation is an event, so it must not re-fire on the next theme change, knob edit
// or reload. Cleared at the read so an async render path cannot lose it.
function takeGestureInvoke(): string {
    var holder = may<HTMLInputElement>("cp-gesture-invoke");
    if (!holder) return "";
    var value = holder.value;
    holder.value = "";
    return value;
}
// Fire a gesture: stash the kind and re-render via the feature-toggle routing. Disabled with them.
document
    .querySelectorAll<HTMLButtonElement>(".cp-gesture-invoke")
    .forEach(function (el) {
        el.addEventListener("click", function () {
            var holder = may<HTMLInputElement>("cp-gesture-invoke");
            if (!holder || el.disabled) return;
            holder.value = el.getAttribute("data-gesture") || "";
            onKnobChanged();
        });
    });
// Remote Compose knobs apply in-browser in both RC lanes: repaint for JS, isolated reload for
// CMP/Wasm. Otherwise they route through the server daemon like theme/feature controls.
function onRcKnobChanged() {
    if (rcActive()) {
        refreshLinks();
        applyRcOverrides();
        return;
    }
    if (rcWasmActive()) {
        refreshLinks();
        openRcWasm();
        return;
    }
    onKnobChanged();
}
controls(".cp-rc-knob").forEach(function (el) {
    el.addEventListener(
        el.type === "checkbox" ? "change" : "input",
        onRcKnobChanged,
    );
});
// Address-bar state. The page URL mirrors the controls using the /render override names, so the
// viewer URL and the copyable render URL describe the same state. Only owned params are touched
// (never `token` / `session`), and a control at its default removes its param.
//
// Owned params live in `viewer/ownedParams.ts`. `cpUrlState.sync` drops any owned param the caller
// does not supply, so over-claiming deletes another's param and under-claiming leaves stale ones.
function ownsUrlParam(name: string) {
    return rules.ownsUrlParam(name);
}
function currentMode() {
    var checked = document.querySelector<HTMLInputElement>(
        'input[name="cp-mode"]:checked',
    );
    return checked ? checked.value : "png";
}
// Set before a discrete choice (lane switch, theme pick) so the next sync pushes a history entry.
// Continuous edits leave it false and replace. Consumed by the first sync that follows.
var urlPush = false;
// The query string as of the last reconcile between page and address bar, so a Back/Forward restore
// can ask what actually moved (`rules.restoredInPlace`). Written only by `syncUrl` and
// `hydrateFromUrl`; components that rewrite the URL themselves leave it stale, which can only cause
// an unneeded dispatch, never a skipped one.
var reconciledSearch = location.search;
function syncUrl() {
    var push = urlPush;
    urlPush = false;
    if (!window.cpUrlState) return;
    var values: Record<string, string> = {};
    new URLSearchParams(query()).forEach(function (value, name) {
        if (ownsUrlParam(name)) values[name] = value;
    });
    // A pinned frame cannot apply a theme override, but keep the selection in the address bar
    // (query() omits it from /render) so revision scrubbing doesn't forget the visitor's theme.
    if (pinnedAt) {
        var pinnedParams = new URLSearchParams(location.search);
        var pinnedDefault = defaultThemeValue();
        ["themeProvider", "uiMode"].forEach(function (name) {
            var value = pinnedParams.get(name);
            // ...but not a `uiMode` that merely spells out the baked theme.
            if (
                value &&
                (name !== "uiMode" || rules.pinsTheme(value, pinnedDefault))
            )
                values[name] = value;
        });
    }
    if (scrollLong && scrollLong.checked) values.scroll = "long";
    // Written from the same helper the render URL uses, so address, copied link and bytes agree.
    if (explodeOn()) {
        new URLSearchParams(explodeQuery()).forEach(function (value, name) {
            values[name] = value;
        });
    }
    var mode = currentMode();
    if (mode !== "png") values.mode = mode;
    // The vector lane, but never alongside `exploded`, which implies it (see `explodeEnabledSvg`).
    if (svgOn() && !explodeOn()) values.svg = "1";
    // Only the departure from the default `fit` is written.
    if (rules.zoomMode(root.getAttribute("data-zoom")) === "width")
        values.zoom = "width";
    var sizeModeEl = may<HTMLSelectElement>("cp-sizeMode");
    if (sizeModeEl && sizeModeEl.value) values.sizeMode = sizeModeEl.value;
    // The spec lane's comparison view. Re-emitted on every sync because `sync` drops owned params
    // not supplied (spec-compare.js pushes it on pick). Only while the lane is up. `viewParam`
    // decides what the default (omitted) view is.
    var specView = window.cpSpecCompare
        ? viewParam(window.cpSpecCompare.view())
        : "";
    if (mode === "spec" && specView) values.specView = specView;
    // The source the pair and strip are taken against. Not gated on the lane: the picker and strip
    // keep it when the lane closes. `sourceParam` omits the server's default.
    var specSource = specSourceParam();
    if (specSource) values.specSource = specSource;
    // Which recording is playing: only while the lane is up, and only past the first (the default).
    if (mode === "motion" && motionOptions.length > 1) {
        var pickedMotion = motionPickedId();
        if (pickedMotion && motionPicked() !== motionOptions[0])
            values.motion = pickedMotion;
    }
    window.cpUrlState.sync(values, ownsUrlParam, !push);
    reconciledSearch = location.search;
    // Revision links are server-rendered, but a theme can change without navigation; keep them
    // aligned with the live URL state.
    document
        .querySelectorAll<HTMLAnchorElement>(".cp-revision, .cp-pinned-current")
        .forEach(function (link) {
            var destination = new URL(link.href, location.href);
            ["themeProvider", "uiMode"].forEach(function (name) {
                var value = values[name];
                if (value) destination.searchParams.set(name, value);
                else destination.searchParams.delete(name);
            });
            link.href = destination.href;
        });
}
// What the controls hold when the URL names nothing, captured after the server markup and the
// sticky-theme script, so Back out of a choice restores the page as first opened.
var initialTheme = themeChoice ? themeChoice.value : "";
var initialThemeActive = themeChoice
    ? themeChoice.getAttribute("data-theme-active")
    : "0";
// px on the wire (like every override), dp in the input — the inverse of sizePx().
function setSizeInput(id: string, px: string | null) {
    var el = may<HTMLInputElement>(id);
    if (!el) return;
    var value = parseFloat(px || "");
    el.value = value > 0 ? String(Math.round(value / renderDensity)) : "";
}
// Restore every owned control from the URL. Also runs for Back/Forward, so a param the entry
// does NOT carry has to reset its control — leaving the live value would make the restored page
// disagree with its own URL.
/**
 * Whether [mode] names a lane this page can enter (an offered, enabled mode radio). `?mode=` is a
 * request; an unavailable lane leaves the snapshot on screen.
 */
function laneIsEnterable(mode: string | null): boolean {
    if (!mode) return false;
    return Array.from(ticks('input[name="cp-mode"]')).some(function (r) {
        return r.value === mode && !r.disabled;
    });
}
function hydrateFromUrl(popped: boolean) {
    var q = new URLSearchParams(location.search);
    fields.forEach(function (f) {
        var el = may<Control>("cp-" + f);
        if (el) el.value = q.get(f) || "";
    });
    if (fs) {
        var scale = q.get("fontScale");
        fontScaleTouched = !!scale;
        fs.value = scale || "1.0";
        if (fsVal) fsVal.textContent = scale ? fs.value : "default";
    }
    if (scrollLong) scrollLong.checked = q.get("scroll") === "long";
    // The exploded view restores from the URL like every other axis. A knob the entry doesn't carry
    // resets to its authored default.
    if (explodeToggle) {
        var explodeWanted = explodeParamOn(q.get("exploded"));
        explodeToggle.setAttribute(
            "aria-pressed",
            explodeWanted ? "true" : "false",
        );
        if (root) root.setAttribute("data-exploded", explodeWanted ? "1" : "0");
        EXPLODE_KNOBS.forEach(function (pair) {
            var el = may<HTMLInputElement>(pair[0]);
            if (!el) return;
            // Validate before assigning: `<input type="range">` sanitizes an unparseable value to
            // its midpoint, not the authored default, which would then be rewritten into the shared
            // URL.
            var raw = q.get(pair[1]);
            var num = raw === null || raw === "" ? NaN : Number(raw);
            if (isFinite(num)) {
                // Finite but out of range is clamped, matching `ExplodedSvg`, so `?explodeTilt=76`
                // opens at 75°.
                var min = parseFloat(el.getAttribute("min") || "");
                var max = parseFloat(el.getAttribute("max") || "");
                if (!isNaN(min) && num < min) num = min;
                if (!isNaN(max) && num > max) num = max;
                el.value = String(num);
            } else {
                // Only an unparseable value falls back to the default (see above).
                el.value = el.getAttribute("data-cp-default") || el.value;
            }
            updateExplodeReadout(el);
        });
        syncExplodeControls();
        // `?exploded=1` names a view of the vector export, and SVG has no URL param of its own, so
        // this puts the page on `.svg`. Done here (not at bootstrap) to cover Back/Forward, and
        // before the first refreshSnapshot so the exploded SVG is fetched once. Leaving the view
        // does not force SVG off.
        if (explodeWanted && svgToggle && !svgOn()) {
            explodeEnabledSvg = true;
            svgToggle.setAttribute("aria-pressed", "true");
            snapshotExt = ".svg";
            root.setAttribute("data-mode", "svg");
        } else if (!explodeWanted && explodeEnabledSvg && svgToggle) {
            // Back out of an entry 3D created: turn the SVG lane back off, but only if 3D turned it
            // on.
            explodeEnabledSvg = false;
            svgToggle.setAttribute("aria-pressed", "false");
            snapshotExt = ".png";
            root.setAttribute("data-mode", "snapshot");
        }
    }
    // The vector lane on its own, after the 3D block: an entry naming neither puts the raster back.
    if (svgToggle && !explodeOn()) {
        var wantSvg = q.get("svg") === "1";
        if (wantSvg !== svgOn()) {
            svgToggle.setAttribute("aria-pressed", wantSvg ? "true" : "false");
            snapshotExt = wantSvg ? ".svg" : ".png";
            root.setAttribute("data-mode", wantSvg ? "svg" : "snapshot");
        }
    }
    // Applied on the first pass too, so `?zoom=width` opens at full width.
    applyZoom(rules.zoomMode(q.get("zoom")));
    ["focus", "gestures"].forEach(function (f) {
        var el = may<HTMLInputElement>("cp-" + f);
        if (el) el.checked = q.get(f) !== null;
    });
    // Overlays ride the URL; only `true` is ever written.
    ticks(".cp-overlay").forEach(function (el) {
        el.checked = q.get(el.id.replace(/^cp-/, "")) === "true";
    });
    var sizeModeEl = may<HTMLSelectElement>("cp-sizeMode");
    if (sizeModeEl) {
        sizeModeEl.value = q.get("sizeMode") || "";
        setSizeInput("cp-fixedW", q.get("widthPx"));
        setSizeInput("cp-fixedH", q.get("heightPx"));
        setSizeInput("cp-minW", q.get("minWidthPx"));
        setSizeInput("cp-minH", q.get("minHeightPx"));
        setSizeInput("cp-maxW", q.get("maxWidthPx"));
        setSizeInput("cp-maxH", q.get("maxHeightPx"));
        if (typeof syncSizeRows === "function") syncSizeRows();
    }
    // The axes this page won't let the URL drive because its image did not apply them, scoped to
    // the lane this pass restores into and the controls that lane forwards. Read per pass
    // (Back/Forward can change lane). `mode` only counts when the lane can be entered.
    var wantedLane = q.get("mode");
    var unseeded = effectiveUnseeded(
        unseededOverrides(root),
        wantedLane,
        laneIsEnterable(wantedLane),
    );
    controls(".cp-knob").forEach(function (el) {
        var key = el.getAttribute("data-knob-key");
        if (!key) return;
        var value = knobHydratedValue({
            wireKey: key,
            urlValue: q.get("knob." + key),
            initial: el.getAttribute("data-knob-initial") || "",
            declaredKind: el.getAttribute("data-knob-kind") || "",
            unseeded: unseeded,
        });
        if (el instanceof HTMLInputElement && el.type === "checkbox")
            el.checked = isChecked(value);
        else {
            adoptChoiceValue(el, value);
            el.value = value;
        }
    });
    controls(".cp-rc-knob").forEach(function (el) {
        var name = el.getAttribute("data-rc-name");
        if (!name) return;
        var value = rcHydratedValue({
            name: name,
            urlValue: q.get("rc." + name),
            initial: el.getAttribute("data-rc-initial") || "",
            declaredKind: el.getAttribute("data-rc-kind") || "",
            unseeded: unseeded,
        });
        if (el instanceof HTMLInputElement && el.type === "checkbox")
            el.checked = isChecked(value);
        else el.value = value;
    });
    // The sticky script seeds the theme select before this file runs, so the initial pass leaves it
    // alone. Back/Forward owns it: the entry's theme, or the page's opening one.
    if (popped && themeChoice) {
        var provider = q.get("themeProvider");
        var uiMode = q.get("uiMode");
        var choice = provider
            ? "theme:" + provider
            : uiMode === "light" || uiMode === "dark"
              ? uiMode
              : "";
        var offered = false;
        Array.prototype.forEach.call(themeChoice.options, function (o) {
            if (choice && o.value === choice) offered = true;
        });
        themeChoice.value = (offered ? choice : initialTheme) || "";
        // Restoring the baked-default theme restores "nobody has picked", not a pinned pick.
        themeChoice.setAttribute(
            "data-theme-active",
            offered
                ? rules.pinsTheme(choice, defaultThemeValue())
                    ? "1"
                    : "0"
                : initialThemeActive || "0",
        );
        syncThemeBar();
        // ...and the page chrome, when the Page theme setting follows the choice. Setting `.value`
        // fires no `change`, so call it here (as the format-comparison pop handler does). Pass the
        // active choice, not the displayed one: with `data-theme-active="0"` it is "", handing the
        // page back to `prefers-color-scheme`.
        if (window.cpPageTheme) window.cpPageTheme.follow(activeThemeChoice());
    }
    // Restore `rcPlayer=<id>` from the offered options only, so an unknown id falls back to the
    // default. Legacy spellings (`java`, `embedded`, `js`, `rcplayer-*`) restore the player they
    // named.
    if (rcDefaultBackend) {
        var wantedPlayer = q.get("rcPlayer")
            ? rules.normalizeRcPlayer(q.get("rcPlayer"))
            : null;
        var playerOffered = false;
        if (wantedPlayer && laneSelect) {
            Array.prototype.forEach.call(laneSelect.options, function (o) {
                if (o.value === "rc:" + wantedPlayer && !o.disabled)
                    playerOffered = true;
            });
        }
        rcPlayerPicked =
            playerOffered ||
            rules.backendRequiresRenderParam(rcDefaultBackend, rcBakedPlayer);
        rcPlayerBackend =
            (playerOffered ? wantedPlayer : rcDefaultBackend) || "";
    }
    // Restore the picked source before entering the lane (as the peer chips do), so
    // `?mode=spec&specSource=parallel` opens directly on that pair. On Back/Forward with the lane
    // up, the pick re-enters it. No or unknown source presses the default.
    if (specSourceButtons.length)
        pickSpecSource(
            specSourceButton(
                sourceForParam(specSourceList(), q.get("specSource") || ""),
            ),
        );
    // Restore the comparison view before the lane is entered (at the bottom of this file), so
    // `?mode=spec&specView=slider` opens on the wipe.
    if (window.cpSpecCompare)
        window.cpSpecCompare.hydrate(q.get("specView") || "");
    // Same ordering: select the named capture before `?mode=motion` is applied, so openMotion()
    // loads it directly. No capture named falls back to the first.
    if (motionSelect && !pickMotion(q.get("motion") || ""))
        motionSelect.selectedIndex = 0;
    syncLaneSelect();
    // The page now says what the URL says, which is the only claim `reconciledSearch` makes.
    reconciledSearch = location.search;
}
hydrateFromUrl(false);
// Reconcile the server-rendered strip baseline when hydration pressed another source.
syncSpecStrip();
// Read the bookmarked lane now, before the first sync clears a param no control holds yet. Applied
// at the bottom of this file, after the fallback snapshot has been requested.
var initialUrlMode = new URLSearchParams(location.search).get("mode") || "";
if (window.cpUrlState) {
    window.cpUrlState.onPop(function () {
        // The vector lane is a snapshot format, not an override, so the paths below can't see it
        // moved; request the frame directly when the format changed.
        var wasSvg = svgOn();
        var wasSearch = reconciledSearch;
        hydrateFromUrl(true);
        var svgMoved = svgOn() !== wasSvg;
        var mode = currentMode();
        var wanted = new URLSearchParams(location.search).get("mode") || "png";
        // A lane change re-renders through enterMode; otherwise the restored overrides go out over
        // whichever transport is already up. Either way nothing reloads.
        if (wanted !== mode) setMode(wanted);
        else if (svgMoved) refreshSnapshot();
        // Same lane, but Motion can still change capture, which hydrateFromUrl() already pressed;
        // play it.
        else if (wanted === "motion") playMotion();
        // An entry that moved only axes hydration finishes itself is done. Routing it through the
        // render controls is not a no-op: without a re-renderable session `onControlsChanged` falls
        // back to `setMode("wasm")`, pushing a new entry (Back moving forward). Links are still
        // refreshed.
        else if (rules.restoredInPlace(wasSearch, location.search))
            refreshLinks();
        else onControlsChanged();
    });
}
// Reconcile control enabled-state and the toggle's look with the session's capabilities after
// hydration.
syncServerControls();
syncOverlayToggles();
updateLiveToggle();
// Before the first snapshot, so a deep link naming a theme never paints the baked verdict.
syncSpecBaseline();
refreshSnapshot();
// A bookmarked `?mode=live` / `wasm` / `rc` opens in that lane, but only once the initial snapshot
// has landed: entering an interactive lane cancels the in-flight snapshot, which would leave an
// empty stage behind a lane that may be slow or fail. Bounded, since a failed or hung render may
// never settle. A mode this session doesn't offer is ignored; the param clears on the next sync.
(function () {
    var wanted = initialUrlMode;
    if (!wanted || wanted === "png" || wanted === currentMode()) return;
    const radio = Array.from(ticks('input[name="cp-mode"]')).find(function (r) {
        return r.value === wanted;
    });
    if (!radio || radio.disabled) return;
    var entered = false;
    function enterBookmarkedMode() {
        if (entered) return;
        entered = true;
        img.removeEventListener("load", enterBookmarkedMode);
        img.removeEventListener("error", enterBookmarkedMode);
        setMode(wanted);
    }
    img.addEventListener("load", enterBookmarkedMode);
    img.addEventListener("error", enterBookmarkedMode);
    // A failed snapshot assigns no src, so settle on the request itself (see snapshotSettled)
    // rather than waiting for the timeout. E.g. `?mode=wasm&fontScale=2.0` on a baked-only session:
    // the snapshot is refused, but Wasm can apply the override.
    onSnapshotSettled = enterBookmarkedMode;
    setTimeout(enterBookmarkedMode, 8000);
})();
