// The browser adapter for `compose-preview-known-differences/v1`. It runs the same engine module as
// `design-artifacts`, so the two can't disagree; the conformance fixtures still check
// `design-parity`, a separate implementation. (Possible because the reader no longer needs
// `node:zlib` / `node:crypto`; decoding via `<img>` would normalise colour types and hide the
// mask-encoding rules. See `png-lite.mjs`.)
//
// This file does what the engine deliberately doesn't:
// - Fetching, with the server's three refusals kept distinct as status codes: 403
//   `path-not-contained`, 413 `artifact-too-large`, 404 `artifact-unreadable`.
// - Prefetching, because `readArtifact` is synchronous: the evaluation ladder's ordering would turn
//   into races if it awaited.
// - Deciding what a comparison is: scope fields, the plane, and the canonical rasters both sides
//   are gated in.

import {
    BUDGET,
    acceptanceLifecycles,
    canonicalRaster,
    decodePng,
    evaluateKnownDifferences,
    preflightPng,
    projectTagIndex,
    readsNoArtifacts,
    recordsThatRead,
    resolvePlane,
    scoreComparison,
    sha256Hex,
    type ArtifactAnswer,
    type AcceptanceLifecycle,
    type Catalog,
    type IssueIndexRow,
    type Raster,
    type ReadOptions,
    type TagIndex,
} from "./engine.js";

/** The identity half of the comparison, straight off the page's locator. */
export interface AcceptanceScope {
    system: string;
    component: string;
    previewId: string;
    referenceId: string;
    variant: string;
    overrides: Record<string, string>;
    /** The served reference's digest. Absent is `reference-hash-missing`, refused, not invalidated. */
    referenceSha256?: string | null;
}

export interface AcceptanceSources {
    /** `…/parity/known-differences.json`. A 404 means the catalog has accepted nothing. */
    documentUrl: string;
    /** `path` is `<id>/<file>`, exactly as the document spells it. */
    artifactUrl: (path: string) => string;
    referenceUrl: string;
    candidateUrl: string;
}

export interface AcceptanceStatus {
    status: string;
    causes?: string[];
    reasons?: string[];
}

export interface AcceptanceReport {
    /**
     * `absent`: the catalog accepts nothing (the ordinary case). `unavailable`: it has a document
     * this page couldn't fetch (auth, server error, network). Folding the two would read as a clean
     * bill of health for a page that measured nothing.
     */
    state: "absent" | "unavailable" | "evaluated";
    /**
     * Whether the engine refused the document rather than judging records, signalled by omitting
     * `statuses`. Not recoverable from failures: `duplicate-id` carries an `id` like a per-record
     * refusal, so telling them apart that way would hide a refused document behind an empty list.
     */
    documentRejected: boolean;
    /**
     * What happened to the two rasters. `unavailable` matters: with no pair the engine runs
     * validation-only and reports in-scope acceptances as `out-of-scope`, which bands hide, so a
     * transient 503 would look like "accepts nothing here". `none`: the walk never sought a pair.
     */
    pair: "scored" | "unavailable" | "none";
    statuses: Record<string, AcceptanceStatus>;
    /** Issue-index lifecycle, joined separately from the comparison verdict. */
    lifecycles: Record<string, AcceptanceLifecycle>;
    /** `index` rather than `id` on a record too broken to have one — see `sortFailures`. */
    validationFailures: Array<{ id?: string; index?: number; reason: string }>;
    /** The three scores, or null when the pair could not be decoded. */
    scores: { raw: number; accepted: number; unaccepted: number } | null;
    /** Ids whose mask reached the scoring union — status `valid`, and no other. */
    suppressing: string[];
}

function empty(state: AcceptanceReport["state"]): AcceptanceReport {
    // A function rather than a shared frozen object, so callers can sort or filter without
    // aliasing.
    return {
        state,
        documentRejected: false,
        pair: "none",
        statuses: {},
        lifecycles: {},
        validationFailures: [],
        scores: null,
        suppressing: [],
    };
}

/**
 * Evaluate this catalog's acceptances against one comparison, and score it. `published: false` when
 * the catalog carries no document (the route 404s rather than inventing an empty one).
 */
export async function evaluateComparison(
    sources: AcceptanceSources,
    scope: AcceptanceScope,
    tagIndex: TagIndex,
    issueRows: IssueIndexRow[] = [],
): Promise<AcceptanceReport> {
    const document = await fetchDocument(sources.documentUrl);
    if (document.state === "absent") return empty("absent");
    if (document.state === "unavailable") return empty("unavailable");

    // Decoded by the contract's own reader. Both rasters are needed before any gate: the plane gate
    // samples pixels and the candidate gate compares inside the mask at canonical resolution.
    const pair = await fetchPair(sources);
    if (
        !pair ||
        !currentGeneration(pair.referenceBytes, scope.referenceSha256)
    ) {
        // Not a document verdict, so run the validation-only pass: acceptances become
        // `out-of-scope` rather than being falsely invalidated.
        const artifacts = await prefetch(document.text, sources.artifactUrl);
        const result = evaluateKnownDifferences({
            documentText: document.text,
            readArtifact: reader(artifacts),
            comparison: null,
        });
        return {
            state: "evaluated",
            documentRejected: result.statuses === undefined,
            pair: "unavailable",
            statuses: result.statuses ?? {},
            lifecycles: joinLifecycles(
                document.text,
                result.statuses ?? {},
                issueRows,
            ),
            validationFailures: result.validationFailures,
            scores: null,
            suppressing: (result.survivingMasks ?? []).map((entry) => entry.id),
        };
    }

    const resolved = resolvePlane(pair.reference, pair.candidate);
    const artifacts = await prefetch(document.text, sources.artifactUrl);
    const result = evaluateKnownDifferences({
        documentText: document.text,
        readArtifact: reader(artifacts),
        comparison: {
            ...scope,
            referenceSha256: scope.referenceSha256 ?? null,
            plane: resolved.plane,
            canonicalReference: canonicalRaster(
                pair.reference,
                resolved.boxes.reference,
                resolved.plane,
            ),
            canonicalCandidate: canonicalRaster(
                pair.candidate,
                resolved.boxes.candidate,
                resolved.plane,
            ),
            // Projected, not passed through: the index publishes `boundsInRoot` in render pixels,
            // while an acceptance's `element.bounds` is in the canonical plane. Without projection
            // the element gate reports `element-moved` for elements that never moved. The transform
            // belongs to the comparison.
            tagIndex: projectTagIndex(
                tagIndex,
                resolved.boxes.candidate,
                resolved.plane,
            ),
        },
    });

    // Only masks the gates left `valid` reach the union; the engine already applied that rule.
    const survivingMasks = result.survivingMasks ?? [];
    const scores = scoreComparison({
        reference: pair.reference,
        candidate: pair.candidate,
        referenceBox: resolved.boxes.reference,
        candidateBox: resolved.boxes.candidate,
        plane: resolved.plane,
        masks: survivingMasks.map((entry) => entry.mask),
    });

    return {
        state: "evaluated",
        documentRejected: result.statuses === undefined,
        pair: "scored",
        statuses: result.statuses ?? {},
        lifecycles: joinLifecycles(
            document.text,
            result.statuses ?? {},
            issueRows,
        ),
        validationFailures: result.validationFailures,
        scores: {
            raw: scores.raw,
            accepted: scores.accepted,
            unaccepted: scores.unaccepted,
        },
        suppressing: survivingMasks.map((entry) => entry.id),
    };
}

/**
 * Whether the reference bytes just fetched are the ones the page's metadata describes.
 *
 * The fingerprint gate is a string comparison between the record's `referenceSha256` and the
 * comparison's, and the comparison's comes from the catalog **as the page was assembled** — while
 * the reference raster is fetched separately from a stable URL that a browser cache may answer for
 * up to five minutes (`private, max-age=300`), and the render lane longer still. A catalog that
 * republishes in place therefore has a window in which fresh metadata and stale pixels meet: the
 * gate passes against a digest describing bytes nobody scored, and a mask suppresses a region of a
 * generation it was never gated against. Silent suppression is the one failure this contract exists
 * to prevent, so the two are bound together here rather than trusted to agree.
 *
 * A mismatch is `pair: "unavailable"`, not an acceptance verdict: which generation is the stale one
 * is not knowable from here, and neither side's pixels are evidence about a record. The band says
 * the comparison could not be evaluated, and a reload — past the cache window, or forced — resolves
 * it.
 *
 * A catalog that publishes **no** digest is passed straight through, so `reference-hash-missing`
 * stays the engine's verdict to reach rather than being pre-empted by a check that had nothing to
 * compare.
 */
function currentGeneration(
    referenceBytes: Uint8Array,
    published?: string | null,
): boolean {
    if (typeof published !== "string" || published === "") return true;
    return sha256Hex(referenceBytes) === published.toLowerCase();
}

/**
 * Walk the whole acceptance set with no comparison. An acceptance naming a removed or renamed
 * target is never scoped into any focused comparison, so without this walk it would never surface
 * in the browser while `design-parity` reports `orphaned-target`. Validation-only, no rasters
 * decoded.
 */
export async function walkCatalog(
    sources: Pick<AcceptanceSources, "documentUrl" | "artifactUrl">,
    catalog: Catalog,
    issueRows: IssueIndexRow[] = [],
): Promise<AcceptanceReport> {
    const document = await fetchDocument(sources.documentUrl);
    if (document.state === "absent") return empty("absent");
    if (document.state === "unavailable") return empty("unavailable");
    const artifacts = await prefetch(
        document.text,
        sources.artifactUrl,
        catalog,
    );
    const result = evaluateKnownDifferences({
        documentText: document.text,
        readArtifact: reader(artifacts),
        comparison: null,
        catalog,
    });
    return {
        state: "evaluated",
        documentRejected: result.statuses === undefined,
        pair: "none",
        statuses: result.statuses ?? {},
        lifecycles: joinLifecycles(
            document.text,
            result.statuses ?? {},
            issueRows,
        ),
        validationFailures: result.validationFailures,
        scores: null,
        suppressing: (result.survivingMasks ?? []).map((entry) => entry.id),
    };
}

/** Best-effort metadata extraction for the lifecycle join; verdict parsing remains in the engine. */
function joinLifecycles(
    documentText: string,
    statuses: Record<string, AcceptanceStatus>,
    issueRows: IssueIndexRow[],
): Record<string, AcceptanceLifecycle> {
    let records: unknown[] = [];
    try {
        const parsed = JSON.parse(documentText) as { acceptances?: unknown };
        if (Array.isArray(parsed?.acceptances)) records = parsed.acceptances;
    } catch {
        // The engine owns `document-unreadable`. With no trustworthy records there is nothing to
        // join, and in particular no absence that may be inferred as closure.
    }
    return acceptanceLifecycles(records, statuses, issueRows);
}

/**
 * The document's text, or which kind of absence. Only a 404 is absence; 401, 500 or a network drop
 * mean a document exists that couldn't be read. A 413 is turned into text the engine refuses, so
 * the engine still reports `document-too-large`.
 */
type DocumentFetch =
    | { state: "absent" }
    | { state: "unavailable" }
    | { state: "text"; text: string };

async function fetchDocument(url: string): Promise<DocumentFetch> {
    let response: Response;
    try {
        response = await fetch(url, { credentials: "same-origin" });
    } catch {
        return { state: "unavailable" };
    }
    if (response.status === 404) return { state: "absent" };
    if (response.status === 413) {
        // A string the engine measures as over the ceiling, without transferring one. The ceiling is
        // in UTF-8 bytes and this is ASCII, so its length is its byte length.
        return { state: "text", text: "x".repeat(1024 * 1024 + 1) };
    }
    if (!response.ok) return { state: "unavailable" };
    try {
        return { state: "text", text: await response.text() };
    } catch {
        return { state: "unavailable" };
    }
}

/** The decoded pair, plus the reference's own bytes so its digest can be checked against. */
async function fetchPair(sources: AcceptanceSources): Promise<{
    reference: Raster;
    candidate: Raster;
    referenceBytes: Uint8Array;
} | null> {
    try {
        const [reference, candidate] = await Promise.all([
            fetchRaster(sources.referenceUrl),
            fetchRaster(sources.candidateUrl),
        ]);
        if (!reference || !candidate) return null;
        return {
            reference: reference.raster,
            candidate: candidate.raster,
            referenceBytes: reference.bytes,
        };
    } catch {
        return null;
    }
}

async function fetchRaster(
    url: string,
): Promise<{ raster: Raster; bytes: Uint8Array } | null> {
    const response = await fetch(url, { credentials: "same-origin" });
    if (!response.ok) return null;
    const bytes = new Uint8Array(await response.arrayBuffer());
    try {
        return { raster: decodePng(bytes), bytes };
    } catch {
        // An undecodable side means the comparison can't be measured, not a verdict; fall back to
        // validation-only.
        return null;
    }
}

/** What the two-round prefetch keeps for one path: a bounded header prefix, and the full file only
 *  once the prefix has earned it. */
interface PrefetchedArtifact {
    /** The header-pass answer: a prefix and the whole file's size, or the reader token that stands
     *  in for it. Always present — every declared path gets a header read. */
    header: ArtifactAnswer;
    /**
     * The decode-pass answer, present only for a path whose header preflight was clean: the bytes,
     * or a refusal the second read established (`path-not-contained` / `artifact-too-large`, which
     * only the server can determine). Absent means `artifact-unreadable`, which is safe since the
     * engine only decodes records its preflight cleared.
     */
    full?: Uint8Array | { error: string };
    /** Whether the header round learned the artifact's real size from the response, or only from how
     *  much of it arrived. False forces a full read purely to measure the file — see `prefetch`. */
    totalKnown: boolean;
}

/**
 * Fetch what the document names, in two rounds, before the synchronous evaluation. The reference
 * reader bounds memory by reading one record at a time and retaining nothing; fetching every
 * artifact in full up front would hold gigabytes before any preflight could refuse. So, mirroring
 * its phases:
 * 1. A bounded prefix of every declared path (`maxPreflightBytes`, streamed and cut off) for the
 *    header preflight. A conforming header fits in {@link MAX_CONFORMING_HEADER_BYTES}; the engine
 *    caps its own view to the same constant, so verdicts match.
 * 2. The full body only of paths whose prefix preflights cleanly within the byte cap: a superset of
 *    what the engine decodes, so it never asks for a skipped path, while malformed or oversized
 *    artifacts are refused on their prefix alone. The header round must not be filtered: illegal
 *    paths are still fetched and refused, so the engine sees the reader's answer. Paths come from a
 *    lenient parse; the engine re-parses and owns `document-unreadable`.
 */
async function prefetch(
    documentText: string,
    artifactUrl: (path: string) => string,
    /**
     * The catalog the evaluation will be given (or none). Needed because `orphaned-target` is a
     * pre-read refusal; without it the planner counts records the engine never reads (see the
     * ceiling gate).
     */
    catalog: unknown = null,
): Promise<Map<string, PrefetchedArtifact>> {
    const artifacts = new Map<string, PrefetchedArtifact>();
    let parsed: unknown;
    try {
        parsed = JSON.parse(documentText);
    } catch {
        return artifacts;
    }
    const acceptances = (parsed as { acceptances?: unknown })?.acceptances;
    if (!Array.isArray(acceptances)) return artifacts;

    // A document the engine rejects outright is not fetched for at all; otherwise up to 256 × 2 × 8
    // MiB would be held for a result that reads nothing. Asked of the engine (`readsNoArtifacts`,
    // the same code path) so the two can't drift: wrongly skipping a document the engine does read
    // would turn every record into `artifact-unreadable`.
    if (readsNoArtifacts(documentText)) return artifacts;

    const paths = new Set<string>();
    for (const record of acceptances) {
        const id = (record as { id?: unknown })?.id;
        if (typeof id !== "string") continue;
        for (const key of ["mask", "acceptedCandidate"] as const) {
            const value = (record as Record<string, unknown>)[key];
            if (typeof value === "string") paths.add(`${id}/${value}`);
        }
    }

    // Round one: a bounded prefix of everything — except what a URL join would rewrite.
    await pooled([...paths], ARTIFACT_CONCURRENCY, async (path) => {
        if (rewritesTheUrl(path)) {
            artifacts.set(path, {
                header: { error: "path-not-contained" },
                totalKnown: true,
            });
            return;
        }
        artifacts.set(
            path,
            await fetchPrefix(artifactUrl(path), BUDGET.maxPreflightBytes),
        );
    });

    // A document over the aggregate ceiling (`document-too-large` against `maxTotalArtifactBytes`)
    // isn't paid for either; round one already knows every size.
    //
    // Over-estimating the total is the dangerous direction (it skips round two for a readable
    // document, making records `artifact-unreadable`); under-estimating only wastes bytes. So this
    // mirrors `preflightRecord`'s accounting:
    // - only records the engine reads at all (`recordsThatRead`, given the same catalog, so orphans
    //   aren't counted);
    // - only records whose two artifacts both answered within `maxArtifactBytes` (oversized ones
    //   are refused before being charged);
    // - per record field, not per unique path (a file named for both `mask` and `acceptedCandidate`
    //   is charged twice). An undeclared size counts only what arrived (an under-count); such
    //   producers are measured by round two.
    const reading = new Set(recordsThatRead(documentText, catalog));
    let plannedBytes = 0;
    for (const record of acceptances) {
        const id = (record as { id?: unknown })?.id;
        if (typeof id !== "string" || !reading.has(id)) continue;
        let recordBytes = 0;
        let bothReadable = true;
        for (const key of ["mask", "acceptedCandidate"] as const) {
            const value = (record as Record<string, unknown>)[key];
            const header =
                typeof value === "string"
                    ? artifacts.get(`${id}/${value}`)?.header
                    : undefined;
            if (!header || !("byteLength" in header)) {
                bothReadable = false;
                break;
            }
            if (header.byteLength > BUDGET.maxArtifactBytes) {
                bothReadable = false;
                break;
            }
            recordBytes += header.byteLength;
        }
        if (bothReadable) plannedBytes += recordBytes;
    }
    if (plannedBytes > BUDGET.maxTotalArtifactBytes) return artifacts;

    // Round two: full bodies for prefixes that earned one, and for responses with no declared size
    // (read to be measured; see `totalKnown`).
    await pooled(
        [...artifacts],
        ARTIFACT_CONCURRENCY,
        async ([path, entry]) => {
            {
                if (!entry.totalKnown) {
                    const measured = await fetchArtifact(artifactUrl(path));
                    if (measured instanceof Uint8Array) {
                        // Correct the header's size to the real one; left at the prefix length, the
                        // two passes would disagree and the engine would refuse an unchanged file
                        // as `artifact-unreadable`.
                        const header = entry.header;
                        if ("bytes" in header)
                            entry.header = {
                                bytes: header.bytes,
                                byteLength: measured.length,
                            };
                        entry.totalKnown = true;
                        if (headerEarnsFullRead(entry.header))
                            entry.full = measured;
                    } else {
                        entry.header = measured;
                        entry.full = measured;
                    }
                    return;
                }
                if (!headerEarnsFullRead(entry.header)) return;
                // Stored whatever it is. A body refused between the rounds keeps the token the server
                // established, rather than being flattened into "nothing was fetched".
                entry.full = await fetchArtifact(artifactUrl(path));
            }
        },
    );

    return artifacts;
}

/**
 * Max artifact requests in flight. `Promise.all` over 256 records opens 512 requests, each costing
 * the server a whole artifact in memory. A pool bounds that without changing which requests are
 * made or their answers. Eight matches the usual per-host connection limit.
 */
const ARTIFACT_CONCURRENCY = 8;

/**
 * Whether joining this path onto the artifact base would request somewhere else. The URL parser
 * normalises traversal before `fetch`, so `ok/../../../../status` would hit an unrelated
 * same-origin route with the session credential, bypassing the server's containment check. Such
 * paths are answered here with the same token the server would give (`path-not-contained`, 403);
 * everything else goes to the host. Covered: `\` (a separator for http(s)), `?` and `#` (end the
 * path), and tab/CR/LF (removed by the parser before resolution). None appear in a legal path.
 */
function rewritesTheUrl(path: string): boolean {
    if (/[\\?#\t\n\r]/.test(path)) return true;
    return path.split("/").some((segment) => isDotSegment(segment));
}

/**
 * A single- or double-dot segment as the URL parser recognises one, including `%2e` forms
 * (case-insensitive). The decode is one pass like the parser's, so `%252e` reaches the host and is
 * refused there.
 */
function isDotSegment(segment: string): boolean {
    const decoded = segment.replace(/%2e/gi, ".");
    return decoded === "." || decoded === "..";
}

/** Run `work` over `items` with at most `limit` in flight, preserving nothing but the side effects. */
async function pooled<T>(
    items: T[],
    limit: number,
    work: (item: T) => Promise<void>,
): Promise<void> {
    let next = 0;
    const workers = Array.from(
        { length: Math.min(limit, items.length) },
        async () => {
            for (let index = next++; index < items.length; index = next++) {
                await work(items[index]);
            }
        },
    );
    await Promise.all(workers);
}

/** True when a prefix's header is clean enough that the engine might decode the record — the
 *  superset test that keeps round two from ever under-fetching a body the engine will ask for. */
function headerEarnsFullRead(header: ArtifactAnswer): boolean {
    if (!("bytes" in header)) return false;
    if (header.byteLength > BUDGET.maxArtifactBytes) return false;
    const preflight = preflightPng(header.bytes, {
        byteLength: header.byteLength,
    });
    return !("error" in preflight) && !preflight.animated;
}

/**
 * Fetch at most `limit` bytes of `url` plus the whole file's size, without allocating the rest.
 * Uses a `Range` request, but streams and cancels at `limit` in case the server ignores it. Size
 * comes from `Content-Range`'s total, else `Content-Length`; otherwise from what arrived.
 */
async function fetchPrefix(
    url: string,
    limit: number,
): Promise<PrefetchedArtifact> {
    const failed = (error: string): PrefetchedArtifact => ({
        header: { error },
        totalKnown: true,
    });
    let response: Response;
    try {
        response = await fetch(url, {
            credentials: "same-origin",
            headers: { Range: `bytes=0-${limit - 1}` },
        });
    } catch {
        return failed("artifact-unreadable");
    }
    if (response.status === 403) return failed("path-not-contained");
    if (response.status === 413) return failed("artifact-too-large");
    // A 416 for a range starting at zero means the file is empty. Hand the preflight an empty
    // prefix so it reaches `header-invalid` like the filesystem reader, rather than reporting
    // `artifact-unreadable`.
    if (response.status === 416) {
        return {
            header: { bytes: new Uint8Array(0), byteLength: 0 },
            totalKnown: true,
        };
    }
    if (!response.ok && response.status !== 206)
        return failed("artifact-unreadable");

    const total = totalBytesFromHeaders(response);
    const bytes = await readAtMost(response, limit);
    if (!bytes) return failed("artifact-unreadable");
    // A short read proves the size; only a full prefix with no declared size leaves it unknown,
    // needing a second read so the two passes agree.
    const ranTooLongToTell = total === null && bytes.length >= limit;
    return {
        header: { bytes, byteLength: total ?? bytes.length },
        totalKnown: !ranTooLongToTell,
    };
}

/** The whole-file size a range response advertises, or `null` when the server declared none. */
function totalBytesFromHeaders(response: Response): number | null {
    const contentRange = response.headers.get("Content-Range");
    if (contentRange) {
        const total = /\/(\d+)\s*$/.exec(contentRange);
        if (total) return Number(total[1]);
    }
    if (response.status !== 206) {
        const length = response.headers.get("Content-Length");
        if (length !== null && /^\d+$/.test(length)) return Number(length);
    }
    return null;
}

/** Read a response body until `limit` bytes, then cancel the stream so the rest is never allocated. */
async function readAtMost(
    response: Response,
    limit: number,
): Promise<Uint8Array | null> {
    if (!response.body) {
        // No stream to bound — take the buffer and cut it, the one path where the full body is briefly
        // held. A fetch implementation without a readable body is the fallback, not the norm.
        try {
            const buffer = new Uint8Array(await response.arrayBuffer());
            return buffer.subarray(0, limit);
        } catch {
            return null;
        }
    }
    const chunks: Uint8Array[] = [];
    let held = 0;
    const streamReader = response.body.getReader();
    try {
        while (held < limit) {
            const { done, value } = await streamReader.read();
            if (done) break;
            chunks.push(value);
            held += value.length;
        }
    } catch {
        return null;
    } finally {
        await streamReader.cancel().catch(() => {});
    }
    const out = new Uint8Array(Math.min(held, limit));
    let offset = 0;
    for (const chunk of chunks) {
        if (offset >= out.length) break;
        const slice = chunk.subarray(0, out.length - offset);
        out.set(slice, offset);
        offset += slice.length;
    }
    return out;
}

async function fetchArtifact(
    url: string,
): Promise<Uint8Array | { error: string }> {
    let response: Response;
    try {
        response = await fetch(url, { credentials: "same-origin" });
    } catch {
        return { error: "artifact-unreadable" };
    }
    // Keep the host's three refusals distinct; the engine honours only these two tokens from a
    // reader.
    if (response.status === 403) return { error: "path-not-contained" };
    if (response.status === 413) return { error: "artifact-too-large" };
    if (!response.ok) return { error: "artifact-unreadable" };
    // Bounded at the cap, not at whatever the server sends: one byte past the (inclusive) cap
    // proves the size, and the record is refused without allocating the rest.
    const bytes = await readAtMost(response, BUDGET.maxArtifactBytes + 1);
    if (!bytes) return { error: "artifact-unreadable" };
    if (bytes.length > BUDGET.maxArtifactBytes)
        return { error: "artifact-too-large" };
    return bytes;
}

/**
 * The synchronous reader the engine calls over prefetched data: `{ prefix }` for the header pass,
 * no options for the full body. A path never prefetched, or a full read of one that never earned a
 * body, is `artifact-unreadable` (the engine only decodes records its preflight cleared).
 */
function reader(artifacts: Map<string, PrefetchedArtifact>) {
    return (path: string, options?: ReadOptions): ArtifactAnswer | null => {
        const entry = artifacts.get(path);
        if (!entry) return null;
        if (options?.prefix !== undefined) return entry.header;
        return entry.full ?? { error: "artifact-unreadable" };
    };
}
