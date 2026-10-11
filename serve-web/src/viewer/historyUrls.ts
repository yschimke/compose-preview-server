// URL arithmetic behind the render-history menu, as pure functions. Links are built from DOM text
// and land in an `href` (CodeQL's `js/xss-through-dom`), so inputs are matched and then rebuilt
// from the captured segments, each encoded; nothing is passed through.

/** One entry in a preview's timeline, as the manifest records it. */
export interface HistoryVersion {
    /** Delivery-branch commit the render was published in (hosted mode). */
    commit?: string;
    /** Content sha addressing the render directly (project mode). */
    blob?: string;
}

/**
 * How this page addresses old renders: a delivery repo to link into, or (for `serve` on a local
 * checkout) the server's own content-addressed lane. Exactly one is present.
 */
export interface HistorySource {
    /** `owner/name`, already validated and encoded, or null in project mode. */
    repoPath: string | null;
    /** Site-relative prefix up to the `{blob}` placeholder, or null in hosted mode. */
    blobBase: string | null;
    /** The template's query string, re-encoded, or `""`. */
    blobQuery: string;
    /**
     * Whether the strip describes renders this page is not showing: in project mode the stage is
     * the working tree while the timeline is published baselines.
     */
    local: boolean;
}

/** Percent-encode one URL word, idempotent for one that already is. */
export function reencode(word: string): string {
    try {
        return encodeURIComponent(decodeURIComponent(word));
    } catch {
        // A stray `%` is not a valid escape and `decodeURIComponent` throws on it; encode literally.
        return encodeURIComponent(word);
    }
}

/**
 * Validate and rebuild an `owner/name`. Only URI-unreserved characters are admitted, so encoding is
 * a no-op on real values; anything else yields null and no strip.
 */
export function repoPathOf(repo: string | null): string | null {
    if (!repo) return null;
    const parts =
        /^([A-Za-z0-9][A-Za-z0-9._-]*)\/([A-Za-z0-9][A-Za-z0-9._-]*)$/.exec(
            repo,
        );
    if (!parts) return null;
    return `${encodeURIComponent(parts[1])}/${encodeURIComponent(parts[2])}`;
}

/**
 * Validate and rebuild the project-mode blob template, reassembling the URL around the version's
 * sha rather than substituting `{blob}`. The leading `\/(?!\/)` keeps it site-relative (no
 * `//host`), and no `:` is admitted, so no `javascript:` URL can match.
 */
export function blobTemplateOf(
    blobUrl: string | null,
): { base: string; query: string } | null {
    if (!blobUrl) return null;
    const parts =
        /^(\/(?!\/)[A-Za-z0-9._~%/-]*)\{blob\}(\.png)(\?[A-Za-z0-9._~%&=-]*)?$/.exec(
            blobUrl,
        );
    if (!parts) return null;
    // Segments and query words are re-encoded individually, keeping the URL structure. Decoded
    // first so an already-encoded segment round-trips instead of double-encoding.
    return {
        base: parts[1].split("/").map(reencode).join("/"),
        query: (parts[3] || "").replace(/[^?&=]+/g, reencode),
    };
}

/** Resolve the two mutually exclusive addressing modes, or null when neither is usable. */
export function historySourceOf(
    repo: string | null,
    blobUrl: string | null,
): HistorySource | null {
    if (repo) {
        const repoPath = repoPathOf(repo);
        if (!repoPath) return null;
        return { repoPath, blobBase: null, blobQuery: "", local: false };
    }
    const template = blobTemplateOf(blobUrl);
    if (!template) return null;
    return {
        repoPath: null,
        blobBase: template.base,
        blobQuery: template.query,
        local: true,
    };
}

/**
 * The URL of one historical render, or null when the manifest names something unaddressable.
 * Mirrors `ServeUrls.historicalRenderUrl`: shas only, so a malformed manifest cannot point at a
 * branch; in project mode the content sha addresses it. Keyed on `source.local` so one flag decides
 * addressing and labelling.
 */
export function renderUrlAt(
    source: HistorySource,
    version: HistoryVersion,
    path: string | null,
): string | null {
    if (source.local) {
        if (!/^[0-9a-f]{40}$/.test(version.blob || "")) return null;
        return `${source.blobBase}${version.blob}.png${source.blobQuery}`;
    }
    const commit = version.commit;
    if (!/^[0-9a-fA-F]{7,40}$/.test(commit || "")) return null;
    if (!path || !isRenderDir(path) || path.includes("..")) return null;
    return (
        "https://raw.githubusercontent.com/" +
        `${source.repoPath}/${commit}/` +
        path.split("/").map(encodeURIComponent).join("/")
    );
}

/**
 * Delivery-branch directories a manifest may name a render in: baseline branches write
 * `renders/<module>/<basename>`, design catalog branches `images/<slug>/<variant>.png`. A closed
 * list on purpose, since the path is pasted into a raw.githubusercontent URL.
 */
const RENDER_DIRS = ["renders/", "images/"];

function isRenderDir(path: string): boolean {
    return RENDER_DIRS.some((dir) => path.startsWith(dir));
}

/** `2026-08-15` from an ISO timestamp, or `""` when it is not one. */
export function shortDate(iso: string | null | undefined): string {
    const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso || "");
    return m ? `${m[1]}-${m[2]}-${m[3]}` : "";
}
