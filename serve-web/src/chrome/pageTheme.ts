// The **Page theme** setting: whether site chrome follows the selected preview theme (default) or
// the OS. A standing preference in the Settings menu, not URL state, since a shared link describes
// the previews, not the reader's chrome. Implemented via `color-scheme`: `serve.css` uses
// `light-dark()` pairs and `cp-scheme-light`/`cp-scheme-dark` on `<html>` pins the scheme. The
// pre-paint script (`ServeWeb.pageThemeScript`) sets it first to avoid a flash; this keeps it in
// step and owns the menu.

import { readThemeMemory } from "./themeMemory.js";

const SETTING_KEY = "cp-page-theme";

/** "match" follows the picked preview theme (the default); "system" follows the OS. */
export type PageThemeSetting = "match" | "system";

export interface PageThemeApi {
    /** Re-resolve the chrome, optionally against a choice the caller just applied. */
    follow(choice?: string): void;
    setting(): PageThemeSetting;
}

declare global {
    interface Window {
        cpPageTheme?: PageThemeApi;
    }
}

/**
 * The Page theme setting lives in `localStorage`; the theme choice is per-tab ({@link
 * readThemeMemory}).
 */
function storedSetting(): string | null {
    try {
        return localStorage.getItem(SETTING_KEY);
    } catch {
        return null;
    }
}

export function setting(): PageThemeSetting {
    return storedSetting() === "system" ? "system" : "match";
}

/**
 * Per-tab storage key for this catalog's theme choice, shared with the landing grid and viewer.
 * Empty on pages with no theme control.
 */
function themeKey(): string {
    return document.documentElement.getAttribute("data-cp-theme-key") || "";
}

/** The page mode a theme choice implies, or `""` when it implies nothing. */
function modeOf(choice: string): string {
    if (choice === "light" || choice === "dark") return choice;
    let button: Element | null = null;
    for (const candidate of document.querySelectorAll(".cp-theme-btn")) {
        if (candidate.getAttribute("data-theme-choice") === choice)
            button = candidate;
    }
    return button ? button.getAttribute("data-theme-mode") || "" : "";
}

/**
 * The theme choice on load, resolved as the pre-paint script does: URL, then this tab's remembered
 * choice, then the theme a `__light`/`__dark` preview bakes (`uiMode` is the viewer's spelling).
 * The remembered choice outranks the baked one because the viewer re-renders with it.
 */
function currentChoice(): string {
    const params = new URLSearchParams(location.search);
    const fromUrl = params.get("theme") || params.get("uiMode");
    if (fromUrl) return fromUrl;
    if (themeChoiceApplies()) {
        // Only a remembered value this page can take: the per-catalog memory may hold a theme this
        // page does not offer or that no longer exists, and then the baked render is what is on
        // stage.
        const remembered = readThemeMemory(themeKey());
        if (remembered && usableChoice(remembered)) return remembered;
    }
    const viewer = document.querySelector<HTMLElement>(
        ".cp-viewer[data-preview-id]",
    );
    const previewId = viewer?.getAttribute("data-preview-id") || "";
    if (/(?:^|__)(?:light|dark)(?:__|$)/.test(previewId)) {
        const baked = viewer?.getAttribute("data-bg-theme") || "";
        if (baked === "light" || baked === "dark") return baked;
    }
    return "";
}

/**
 * Theme values this page's control offers, or `null` with no control. Handles the viewer's
 * `<select>` (disabled options excluded), the landing chips and the wall's Light/Dark pair.
 */
function offeredChoices(): Set<string> | null {
    const select = document.querySelector<HTMLSelectElement>("#cp-theme");
    if (select)
        return new Set(
            Array.from(select.options)
                .filter((option) => !option.disabled)
                .map((option) => option.value),
        );
    const values = (selector: string, attribute: string) =>
        Array.from(document.querySelectorAll(selector))
            .map((el) => el.getAttribute(attribute) || "")
            .filter(Boolean);
    const chips = values(".cp-theme-btn", "data-theme-choice");
    if (chips.length) return new Set(chips);
    const compare = values("[data-compare-theme]", "data-compare-theme");
    if (compare.length) return new Set(compare);
    return null;
}

/**
 * Whether [choice] could be what this page shows. With a control, offered wins (an unqualified mode
 * follows the OS, like {@link follow}); without one, a value naming no mode is treated as stale.
 */
function usableChoice(choice: string): boolean {
    const offered = offeredChoices();
    return offered ? offered.has(choice) : !!modeOf(choice);
}

/**
 * Whether a remembered choice can change what this page shows. A disabled Theme select means the
 * stage keeps its baked image (server-side twin: `ServeWeb.themeChoiceApplies`). Pages without the
 * select always apply.
 */
function themeChoiceApplies(): boolean {
    const select = document.querySelector<HTMLSelectElement>("#cp-theme");
    return !select || !select.disabled;
}

function paint(mode: string): void {
    const root = document.documentElement;
    root.classList.toggle("cp-scheme-light", mode === "light");
    root.classList.toggle("cp-scheme-dark", mode === "dark");
}

/** Re-resolve from the setting plus the choice on screen. */
export function follow(choice?: string): void {
    if (setting() === "system") {
        paint("");
        return;
    }
    paint(modeOf(choice === undefined ? currentChoice() : choice));
}

/**
 * Wire the Settings menu radios once the document is parsed; split from the global, which must
 * exist before the page's own scripts run.
 */
export function wireSettingsMenu(): void {
    const inputs = document.querySelectorAll<HTMLInputElement>(
        "[data-cp-page-theme]",
    );
    if (!inputs.length) return;
    const current = setting();
    for (const input of inputs) {
        input.checked = input.value === current;
        input.addEventListener("change", () => {
            if (!input.checked) return;
            try {
                localStorage.setItem(SETTING_KEY, input.value);
            } catch {
                // Storage blocked: the choice applies to this page and is not remembered.
            }
            follow();
        });
    }
}

/**
 * Publish the global, then wire the menu once the document can be queried. Callers read
 * `window.cpPageTheme` lazily in handlers, so publishing early is safe.
 */
export function installPageTheme(): void {
    window.cpPageTheme = { follow, setting };
    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", wireSettingsMenu, {
            once: true,
        });
    } else {
        wireSettingsMenu();
    }
}
