// Which theme the viewer is asking for, and what the Theme bar shows. These differ until the first
// pick: the select shows the baked theme (inactive), but sending it as an override would make a
// published catalog re-render what it already baked. So `activeThemeChoice` is gated on that and
// the bar's pressed state is not. DOM-free: `viewer.js` passes plain values.

/** The `theme:` prefix an app-declared provider carries in the select's option values. */
const PROVIDER_PREFIX = "theme:";

export interface ThemeSelectState {
    value: string;
    disabled: boolean;
    /** The select's `data-theme-active` — `"1"` once the visitor has actually picked. */
    active: boolean;
    /**
     * The theme this preview is baked in, as the select spells it (`light`/`dark`), or `""` if
     * unknown. Passed separately because bootstrap and history hydration overwrite `value` before
     * this reads it.
     */
    defaultValue: string;
}

/**
 * Whether [choice] is a theme the visitor actually pinned. A system appearance equal to the baked
 * default asks for nothing (e.g. a leftover `?uiMode=light` after toggling must not suppress the
 * Figma comparison, see #4218). A `theme:<provider>` choice always counts.
 */
export function pinsTheme(choice: string, defaultValue: string): boolean {
    return !!choice && choice !== defaultValue;
}

/**
 * The theme the page asks the server for, or `""` for "whatever is baked". Empty before the first
 * pick, and when disabled because nothing can re-render (the default, `frozenFrame = false`). A
 * lane that froze the frame (spec, motion) still names its theme, so the URL and the spec baseline
 * keep describing the render on stage. Also empty for a pick equal to the baked theme ({@link
 * pinsTheme}).
 */
export function activeThemeChoice(
    select: ThemeSelectState | null,
    frozenFrame = false,
): string {
    if (!select || !select.active) return "";
    if (select.disabled && !frozenFrame) return "";
    // A choice equal to the baked theme is not an override; the server already treats it as a no-op
    // (`CatalogLiveRouting.withoutBakedNoOps`), so this only stops the URL pinning an unchosen
    // parameter.
    return pinsTheme(select.value, select.defaultValue) ? select.value : "";
}

/** The `uiMode` override — only the two system appearances, never a provider. */
export function chosenUiMode(choice: string): string {
    return choice === "light" || choice === "dark" ? choice : "";
}

/** The `themeProvider` override — the provider FQN, without its prefix. */
export function chosenThemeProvider(choice: string): string {
    return choice.startsWith(PROVIDER_PREFIX)
        ? choice.slice(PROVIDER_PREFIX.length)
        : "";
}

export interface ThemeBarButton {
    /** Whether the button can be pressed at all. */
    disabled: boolean;
    /** Whether it reads as the current theme. */
    pressed: boolean;
}

/**
 * One Theme-bar chip's state. `pressed` tracks what the select displays, not {@link
 * activeThemeChoice}, so the baked theme shows pressed before the first pick. `option` is the
 * matching `<option>`'s disabled state, or `null` if absent; unrenderable themes are disabled, not
 * hidden.
 */
export function themeBarButton(
    buttonValue: string,
    select: { value: string; disabled: boolean },
    option: { disabled: boolean } | null,
): ThemeBarButton {
    return {
        disabled: select.disabled || !option || option.disabled,
        pressed: select.value === buttonValue,
    };
}
