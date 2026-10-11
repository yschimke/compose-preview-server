// Where a capture lives between the page it was taken on and the `/report-bug` page a navigation
// later. `sessionStorage`, not `localStorage`: it is per-gesture scratch that may show an
// unreleased design, so it must not outlive the tab or be visible to another. Storage is passed in
// so eviction is testable and a throwing storage (private mode, blocked context) is handled.

/** One captured picture, plus whatever else the selection yielded. */
export interface Capture {
    /** Stable within a session; the list is keyed by it for removal. */
    id: string;
    /** What was captured, as the list shows it: `Element · table`, `Region`, `Whole view`. */
    label: string;
    /** `data:image/png;base64,…`. A data URL rather than a blob URL because a blob URL dies with
     *  the document that minted it, which is precisely the navigation this survives. */
    dataUrl: string;
    width: number;
    height: number;
    /** Markdown the selection also produced — a picked table, rendered as one. Absent otherwise. */
    markdown?: string;
    /**
     * `location.pathname` of the captured page, so the automatic hand-off only uses a capture taken
     * for this report, not one left over in the tab. Path only: different knob settings of the same
     * preview are the same subject. Absent on older captures, which only the Copy button will send.
     */
    page?: string;
    /** Expiring, anonymous read URL returned by this host's image lane. Cleared whenever markup
     *  changes the pixels, so a filed report can never point at the pre-edit picture. */
    uploadedUrl?: string;
}

/** The `sessionStorage` key. Namespaced like every other key this server sets. */
export const STORE_KEY = "cp-report-captures";

/**
 * `sessionStorage` is ~5 MB and a full-viewport PNG can exceed 1 MB, so three is the ceiling.
 * Exceeding either limit evicts the oldest, since the newest was just taken deliberately.
 */
export const MAX_CAPTURES = 3;
export const MAX_BYTES = 3_500_000;

/** The storage this runs against, or null where there is none to have. */
export function sessionStore(): Storage | null {
    try {
        return globalThis.sessionStorage ?? null;
    } catch {
        // A blocked storage partition throws on *access*, not on use.
        return null;
    }
}

/**
 * Read the pile back, validating each entry field by field since another tab, extension or older
 * build may have written it. `dataUrl` must be a PNG data URL because it becomes an `<img src>`.
 */
export function readCaptures(store: Storage | null): Capture[] {
    if (!store) return [];
    let raw: string | null = null;
    try {
        raw = store.getItem(STORE_KEY);
    } catch {
        return [];
    }
    if (!raw) return [];
    let parsed: unknown;
    try {
        parsed = JSON.parse(raw);
    } catch {
        return [];
    }
    if (!Array.isArray(parsed)) return [];
    return parsed.filter(isCapture);
}

function isCapture(value: unknown): value is Capture {
    if (!value || typeof value !== "object") return false;
    const c = value as Record<string, unknown>;
    return (
        typeof c.id === "string" &&
        typeof c.label === "string" &&
        typeof c.dataUrl === "string" &&
        c.dataUrl.startsWith("data:image/png;base64,") &&
        typeof c.width === "number" &&
        typeof c.height === "number" &&
        (c.markdown === undefined || typeof c.markdown === "string") &&
        (c.page === undefined || typeof c.page === "string") &&
        (c.uploadedUrl === undefined || safeUploadUrl(c.uploadedUrl))
    );
}

function safeUploadUrl(value: unknown): value is string {
    if (typeof value !== "string") return false;
    try {
        const url = new URL(value);
        return (
            (url.protocol === "https:" || url.protocol === "http:") &&
            /^\/i\/[A-Za-z0-9_-]+\.[A-Za-z0-9]+$/.test(url.pathname)
        );
    } catch {
        return false;
    }
}

/**
 * Write the pile, dropping the oldest until it fits — including on a quota exception the size
 * estimate didn't predict. Returns what actually landed, possibly nothing.
 */
export function writeCaptures(
    store: Storage | null,
    captures: Capture[],
): Capture[] {
    if (!store) return [];
    let kept = captures.slice(-MAX_CAPTURES);
    while (kept.length && bytes(kept) > MAX_BYTES) kept = kept.slice(1);
    while (kept.length) {
        try {
            store.setItem(STORE_KEY, JSON.stringify(kept));
            return kept;
        } catch {
            kept = kept.slice(1);
        }
    }
    try {
        store.removeItem(STORE_KEY);
    } catch {
        // Nothing to do: there is no pile to leave behind either way.
    }
    return [];
}

function bytes(captures: Capture[]): number {
    return captures.reduce((total, c) => total + c.dataUrl.length, 0);
}

/** Append one capture and persist. Returns the pile as it now stands. */
export function addCapture(store: Storage | null, capture: Capture): Capture[] {
    return writeCaptures(store, [...readCaptures(store), capture]);
}

/** Drop one capture by id and persist. */
export function removeCapture(store: Storage | null, id: string): Capture[] {
    return writeCaptures(
        store,
        readCaptures(store).filter((c) => c.id !== id),
    );
}

/** Replace one capture without disturbing its place in the newest-first eviction order. */
export function replaceCapture(
    store: Storage | null,
    capture: Capture,
): Capture[] {
    return writeCaptures(
        store,
        readCaptures(store).map((item) =>
            item.id === capture.id ? capture : item,
        ),
    );
}

/**
 * A counter over the stored ids: never shown, and unlike a timestamp it cannot collide within a
 * millisecond.
 */
export function nextId(existing: Capture[]): string {
    const used = new Set(existing.map((c) => c.id));
    let n = existing.length + 1;
    while (used.has(`shot-${n}`)) n += 1;
    return `shot-${n}`;
}
