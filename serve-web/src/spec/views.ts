// Which of the spec lane's four views is showing, and who decides. Three sources compete: the URL,
// a chip requesting an entry view, and the visitor's click.
// - An explicit choice latches and never clears; a later chip request must not move it.
// - A named view in the URL is an explicit choice (picked before sharing, or where Back returns).
// - A chip's request is only a default, spent once used.

export const VIEWS = ["spec", "diff", "triptych", "slider"] as const;
export type SpecView = (typeof VIEWS)[number];

/**
 * The imported reference alone. No longer the default, but the only view that paints nothing of its
 * own, so `SpecCompare.apply()` keeps the comparison surfaces and score away from it.
 */
export const PLAIN_VIEW: SpecView = "spec";

/**
 * What the lane opens on, and so what `?specView=` may omit. Triptych because entering the lane is
 * asking how render and reference compare; Spec is one click away.
 */
export const DEFAULT_VIEW: SpecView = "triptych";

export interface ViewChoice {
    view: SpecView;
    /** Whether the visitor or a URL has spoken. Latches true; never clears. */
    chosen: boolean;
    /** A chip's requested entry view, pending until the next `open`. */
    preferred: SpecView | "";
}

export const INITIAL: ViewChoice = {
    view: DEFAULT_VIEW,
    chosen: false,
    preferred: "",
};

export function isView(value: string | null | undefined): value is SpecView {
    return VIEWS.includes(value as SpecView);
}

/** Anything unrecognised falls back rather than addressing a view that does not exist. */
export function normaliseView(value: string | null | undefined): SpecView {
    return isView(value) ? value : DEFAULT_VIEW;
}

/** The visitor pressed a view button. Explicit, so it latches. */
export function choose(state: ViewChoice, next: string): ViewChoice {
    return { ...state, view: normaliseView(next), chosen: true };
}

/**
 * Restore from the address bar (load and Back/Forward). A named view latches; an absent or
 * unrecognised one does not, so a chip may still choose.
 */
export function hydrate(state: ViewChoice, next: string | null): ViewChoice {
    return isView(next)
        ? { ...state, view: next, chosen: true }
        : { ...state, view: DEFAULT_VIEW };
}

/**
 * A chip asks for an entry view, ignored once anyone has chosen. No caller does today, but the
 * precedence keeps future entry points safe.
 */
export function prefer(state: ViewChoice, next: string): ViewChoice {
    return state.chosen ? state : { ...state, preferred: normaliseView(next) };
}

/**
 * The lane was entered: spend a pending chip preference if still allowed, so it doesn't keep
 * pulling later entries back.
 */
export function onOpen(state: ViewChoice): ViewChoice {
    if (!state.preferred || state.chosen) return { ...state, preferred: "" };
    return { ...state, view: state.preferred, preferred: "" };
}

/** What `?specView=` should carry — empty for the default, which needs no parameter. */
export function viewParam(view: SpecView): string {
    return view === DEFAULT_VIEW ? "" : view;
}
