// The browser adapter end to end over a synthetic catalog. The contract's semantics are pinned by
// `scripts/design-artifacts/fixtures/known-differences/` (same implementation); this checks the
// plumbing: fetching over HTTP, prefetching for the synchronous `readArtifact`, resolving the plane
// from the panels' rasters, and mapping status codes back to the reader tokens, in particular the
// server-established `path-not-contained` and `artifact-too-large`.

import assert from "node:assert/strict";
import {
    MARK,
    SOURCES,
    WHITE,
    catalogRoutes,
    fillRect,
    knownDifferencesJson,
    png,
    raster,
    scope,
    withFetch,
    world,
} from "./support/knownDifferences.js";
import { sha256Hex } from "@design-parity/known-differences/png-lite";
import { evaluateComparison, walkCatalog } from "../src/parity/acceptance.js";

/** One recorded request: the path asked for, and the `Range` header if the caller sent one. */
interface RecordedRequest {
    url: string;
    range: string | null;
}

/**
 * A `fetch` that records every request and optionally honours `Range` (`206` with a `Content-Range`
 * naming the whole size). The default `serve` ignores `Range` and returns the whole body, covering
 * a host that can't range-request.
 */
function recordingFetch(
    routes: Record<string, Uint8Array | string | number>,
    {
        honourRange,
        declareSize = true,
        declaredSizes = {},
    }: {
        honourRange: boolean;
        declareSize?: boolean;
        /**
         * Sizes to claim for paths without sending the bytes, modelling a hostile catalog that
         * declares lengths, and making an aggregate ceiling testable without allocating it.
         */
        declaredSizes?: Record<string, number>;
    },
) {
    const requests: RecordedRequest[] = [];
    const impl = (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        const headers = new Headers(init?.headers ?? {});
        const range = headers.get("Range");
        requests.push({ url, range });

        const body = routes[url];
        if (body === undefined)
            return Promise.resolve(new Response("not found", { status: 404 }));
        if (typeof body === "number")
            return Promise.resolve(new Response("no", { status: body }));
        if (typeof body === "string")
            return Promise.resolve(new Response(body));

        const bytes = body as Uint8Array;
        const declared = declaredSizes[url] ?? bytes.length;
        const match = range ? /^bytes=0-(\d+)$/.exec(range) : null;
        if (honourRange && match) {
            const end = Math.min(Number(match[1]), bytes.length - 1);
            const slice = bytes.subarray(0, end + 1);
            return Promise.resolve(
                new Response(slice as unknown as BodyInit, {
                    status: 206,
                    headers: { "Content-Range": `bytes 0-${end}/${declared}` },
                }),
            );
        }
        if (!declareSize) {
            // A chunked response: the body arrives as a stream and no header names its size.
            const stream = new ReadableStream({
                start(controller) {
                    controller.enqueue(bytes);
                    controller.close();
                },
            });
            return Promise.resolve(
                new Response(stream as unknown as BodyInit, { status: 200 }),
            );
        }
        return Promise.resolve(
            new Response(bytes as unknown as BodyInit, {
                headers: { "Content-Length": String(bytes.length) },
            }),
        );
    };
    return { requests, impl };
}

function withRecordingFetch<T>(
    routes: Record<string, Uint8Array | string | number>,
    options: {
        honourRange: boolean;
        declareSize?: boolean;
        declaredSizes?: Record<string, number>;
    },
    body: (requests: RecordedRequest[]) => Promise<T>,
) {
    const original = globalThis.fetch;
    const { requests, impl } = recordingFetch(routes, options);
    globalThis.fetch = impl as typeof fetch;
    return body(requests).finally(() => {
        globalThis.fetch = original;
    });
}

/**
 * A PNG whose `PLTE` declares more data than the header prefix can hold: a full reader reaches
 * `IDAT`, a prefix-bounded one runs out first. Hand-built since the prefix mechanism is measured
 * against it.
 */
function pngWithOversizedPlte(): Uint8Array {
    const base = png(raster(4, 4, WHITE));
    const signatureAndIhdr = base.subarray(0, 8 + 25);
    const declared = 8000;
    const plte = new Uint8Array(12 + declared);
    new DataView(plte.buffer).setUint32(0, declared);
    plte.set([0x50, 0x4c, 0x54, 0x45], 4);
    const tail = base.subarray(8 + 25);
    const out = new Uint8Array(
        signatureAndIhdr.length + plte.length + tail.length,
    );
    out.set(signatureAndIhdr, 0);
    out.set(plte, signatureAndIhdr.length);
    out.set(tail, signatureAndIhdr.length + plte.length);
    return out;
}
/**
 * The fixture document repeated across [ids], so an aggregate ceiling is reachable from
 * individually legal artifacts (a per-file-oversized one would be refused before being counted).
 */
function repeatedRecords(
    scene: ReturnType<typeof world>,
    ids: string[],
): string {
    const parsed = JSON.parse(knownDifferencesJson(scene)) as {
        acceptances: Record<string, unknown>[];
    };
    const template = parsed.acceptances[0];
    parsed.acceptances = ids.map((id) => ({ ...template, id }));
    return JSON.stringify(parsed);
}

/**
 * The fixture plus a record the engine refuses before reading: `bad id` survives the identity scan
 * but fails `isSafeId`. Its headers are still fetched (lenient planning), but must not count toward
 * the ceiling.
 */
function withUnreadableRecord(scene: ReturnType<typeof world>): string {
    const parsed = JSON.parse(knownDifferencesJson(scene)) as {
        acceptances: Record<string, unknown>[];
    };
    parsed.acceptances.push({ ...parsed.acceptances[0], id: "bad id" });
    return JSON.stringify(parsed);
}

describe("evaluateComparison", () => {
    it("says nothing at all when the catalog publishes no document", async () => {
        const report = await withFetch({}, () =>
            evaluateComparison(SOURCES, scope(world()), {}),
        );
        assert.equal(report.state, "absent");
        assert.deepEqual(report.statuses, {});
        assert.equal(report.scores, null);
    });

    it("tells a document it could not fetch apart from one that is not there", async () => {
        // A 401 or 500 must not read as "absent", which looks like a clean bill of health.
        const scene = world();
        for (const status of [401, 500, 503] as const) {
            const routes = catalogRoutes(scene, knownDifferencesJson(scene));
            routes[SOURCES.documentUrl] = status;
            const report = await withFetch(routes, () =>
                evaluateComparison(SOURCES, scope(scene), {}),
            );
            assert.equal(report.state, "unavailable", `HTTP ${status}`);
        }
    });

    it("accepts the recorded difference and reports three separate numbers", async () => {
        const scene = world();
        const report = await withFetch(
            catalogRoutes(scene, knownDifferencesJson(scene)),
            () =>
                evaluateComparison(SOURCES, scope(scene), {}, [
                    {
                        repository: "YSCHIMKE/M3-CATALOG",
                        number: 40,
                        state: "closed",
                    },
                ]),
        );
        assert.equal(report.state, "evaluated");
        assert.deepEqual(report.statuses, { glyph: { status: "valid" } });
        assert.deepEqual(
            { ...report.lifecycles },
            {
                glyph: {
                    issue: "yschimke/m3-catalog#40",
                    lifecycle: "closed",
                    stale: true,
                },
            },
        );
        assert.deepEqual(report.suppressing, ["glyph"]);
        assert.ok(report.scores, "a decodable pair must be scored");
        // The raw finding survives acceptance — the whole reason this is not an ignore rectangle.
        assert.ok(report.scores!.raw < 100, "the pair really does differ");
        // Nothing outside the mask differs, so what is left is a perfect match.
        assert.equal(report.scores!.unaccepted, 100);
        // And the accepted region is measured on its own, not as a difference of the other two.
        assert.ok(report.scores!.accepted < 100);
    });

    it("relays the reader's own tokens rather than collapsing them", async () => {
        const scene = world();
        for (const [status, reason] of [
            [403, "path-not-contained"],
            [413, "artifact-too-large"],
            [404, "artifact-unreadable"],
        ] as const) {
            const routes = catalogRoutes(scene, knownDifferencesJson(scene));
            routes["/m3/parity/known-differences/glyph/mask.png"] = status;
            const report = await withFetch(routes, () =>
                evaluateComparison(SOURCES, scope(scene), {}),
            );
            assert.deepEqual(
                report.validationFailures,
                [{ id: "glyph", reason }],
                `HTTP ${status}`,
            );
            assert.deepEqual(
                report.suppressing,
                [],
                "a refused record suppresses nothing",
            );
        }
    });

    it("still reaches the document's own verdict when the pair cannot be decoded", async () => {
        const scene = world();
        const routes = catalogRoutes(scene, knownDifferencesJson(scene));
        routes[SOURCES.candidateUrl] = 404;
        const report = await withFetch(routes, () =>
            evaluateComparison(SOURCES, scope(scene), {}),
        );
        // No comparison means no gate fired: out of scope, not invalidated.
        assert.deepEqual(report.statuses, {
            glyph: { status: "out-of-scope" },
        });
        assert.equal(report.scores, null);
        assert.deepEqual(report.suppressing, []);
        // ...and it says why there are no scores, distinguishing an unfetchable comparison from one
        // the catalog says nothing about.
        assert.equal(report.pair, "unavailable");
    });

    it("refuses to score reference bytes the page's own digest does not describe", async () => {
        // The digest comes from the catalog as the page was assembled; the raster comes from a
        // stable URL a browser cache may answer for five minutes. A catalog that republishes in
        // place therefore has a window where fresh metadata meets stale pixels — and the
        // fingerprint gate, being a string comparison against that metadata, passes. A mask would
        // then suppress a region of a generation nobody gated it against, which is precisely the
        // silent suppression the contract exists to prevent.
        const scene = world();
        const stale = fillRect(raster(32, 24, WHITE), MARK, [10, 90, 190, 255]);
        const routes = catalogRoutes(scene, knownDifferencesJson(scene));
        routes[SOURCES.referenceUrl] = png(stale);
        const report = await withFetch(routes, () =>
            evaluateComparison(SOURCES, scope(scene), {}),
        );
        assert.equal(report.pair, "unavailable");
        assert.equal(report.scores, null);
        // Not an acceptance verdict either way: which generation is the stale one is not knowable
        // from here, so neither side's pixels are evidence about the record.
        assert.deepEqual(report.statuses, {
            glyph: { status: "out-of-scope" },
        });
        assert.deepEqual(report.suppressing, []);
    });

    it("scores a catalog that publishes no digest rather than pre-empting the engine", async () => {
        // `reference-hash-missing` is the engine's verdict; a generation check firing on a null
        // digest would replace it with a comparison-level failure.
        const scene = world();
        const report = await withFetch(
            catalogRoutes(scene, knownDifferencesJson(scene)),
            () =>
                evaluateComparison(
                    SOURCES,
                    scope(scene, { referenceSha256: null }),
                    {},
                ),
        );
        assert.equal(report.pair, "scored");
        assert.deepEqual(report.statuses, {
            glyph: { status: "refused", reasons: ["reference-hash-missing"] },
        });
        assert.deepEqual(
            report.suppressing,
            [],
            "a refused record suppresses nothing",
        );
    });

    it("marks a wholesale document rejection as such, not as an empty verdict", async () => {
        // `duplicate-id` carries an `id` like a per-record refusal, but `statuses` is absent
        // because no record was judged.
        const scene = world();
        const doc = JSON.parse(knownDifferencesJson(scene)) as {
            acceptances: unknown[];
        };
        doc.acceptances.push({ ...(doc.acceptances[0] as object) });
        const report = await withFetch(
            catalogRoutes(scene, JSON.stringify(doc)),
            () => evaluateComparison(SOURCES, scope(scene), {}),
        );
        assert.equal(report.documentRejected, true);
        assert.deepEqual(report.statuses, {});
        assert.deepEqual(report.validationFailures, [
            { id: "glyph", reason: "duplicate-id" },
        ]);
        assert.deepEqual(report.suppressing, []);
    });

    it("projects the tag index into the canonical plane before gating on it", async () => {
        // The index is in render pixels, `element.bounds` canonical; with a non-zero plane origin
        // the acceptance only stays `valid` if the projection happened.
        const scene = world();
        const plane = scene.plane;
        assert.ok(
            plane.box.x > 0 || plane.box.y > 0,
            "the fixture must exercise a cropped plane",
        );
        const doc = knownDifferencesJson(scene, {
            element: {
                kind: "tag",
                tag: "glyph",
                // The mark's box in CANONICAL coordinates — what an author records.
                bounds: scene.local,
                tolerance: 0.1,
            },
        });
        // …and the index reports the same node in RENDER pixels, which is the mark's box in the
        // full raster.
        const renderBounds = MARK;
        const report = await withFetch(catalogRoutes(scene, doc), () =>
            evaluateComparison(SOURCES, scope(scene), {
                glyph: { count: 1, bounds: renderBounds },
            }),
        );
        assert.deepEqual(report.statuses, { glyph: { status: "valid" } });
        assert.deepEqual(report.suppressing, ["glyph"]);
    });

    it("refuses an acceptance authored for another system", async () => {
        const scene = world();
        const doc = knownDifferencesJson(scene, { system: "wear-m3" });
        const report = await withFetch(catalogRoutes(scene, doc), () =>
            evaluateComparison(SOURCES, scope(scene), {}),
        );
        // Preview and reference ids are unique only within a system, so scope matching uses every
        // field, including `system`.
        assert.deepEqual(report.statuses, {
            glyph: { status: "out-of-scope" },
        });
        assert.deepEqual(report.suppressing, []);
    });

    it("reports a document past the ceiling as too large, not as absent", async () => {
        const scene = world();
        const routes = catalogRoutes(scene, knownDifferencesJson(scene));
        routes[SOURCES.documentUrl] = 413;
        const report = await withFetch(routes, () =>
            evaluateComparison(SOURCES, scope(scene), {}),
        );
        assert.equal(
            report.state,
            "evaluated",
            "a refused document is not an absent one",
        );
        assert.deepEqual(report.validationFailures, [
            { reason: "document-too-large" },
        ]);
    });

    it("reads a bounded prefix of every artifact before reading any of them whole", async () => {
        // Two rounds: every declared path is first requested with a bounded `Range`, rather than
        // fetching every artifact in full before any preflight can refuse.
        const scene = world();
        const routes = catalogRoutes(scene, knownDifferencesJson(scene));
        const report = await withRecordingFetch(
            routes,
            { honourRange: true },
            async (requests) => {
                const result = await evaluateComparison(
                    SOURCES,
                    scope(scene),
                    {},
                );
                const artifactRequests = requests.filter((request) =>
                    request.url.startsWith("/m3/parity/known-differences/"),
                );
                const ranged = artifactRequests.filter(
                    (request) => request.range !== null,
                );
                assert.equal(
                    ranged.length,
                    2,
                    "both artifacts are asked for as a bounded prefix",
                );
                for (const request of ranged) {
                    assert.equal(
                        request.range,
                        "bytes=0-4095",
                        "the prefix is the named budget",
                    );
                }
                // And each is then read whole exactly once, because both preflight cleanly here.
                const whole = artifactRequests.filter(
                    (request) => request.range === null,
                );
                assert.equal(
                    whole.length,
                    2,
                    "a clean header earns one full read",
                );
                return result;
            },
        );
        // The verdict is unchanged by any of it — the prefix is a resource bound, never a verdict.
        assert.deepEqual(report.statuses, { glyph: { status: "valid" } });
    });

    it("never fetches a body for a document past the aggregate ceiling", async () => {
        // The size-based gate: `document-too-large` against `maxTotalArtifactBytes` is reachable
        // from round one's sizes, so full bodies aren't retained for a document the engine will
        // refuse. Sizes are declared, not sent.
        const scene = world();
        // Five records x two artifacts x 7 MiB = 70 MiB, past the 64 MiB ceiling — and every file
        // individually under the 8 MiB per-artifact cap, so none is refused before it is counted.
        const ids = ["glyph", "glyph2", "glyph3", "glyph4", "glyph5"];
        const routes = catalogRoutes(scene, repeatedRecords(scene, ids));
        for (const id of ids) {
            routes[`/m3/parity/known-differences/${id}/mask.png`] = scene.mask;
            routes[
                `/m3/parity/known-differences/${id}/accepted-candidate.png`
            ] = scene.accepted;
        }
        const each = 7 * 1024 * 1024;
        const declaredSizes = Object.fromEntries(
            ids.flatMap((id) =>
                ["mask.png", "accepted-candidate.png"].map((file) => [
                    `/m3/parity/known-differences/${id}/${file}`,
                    each,
                ]),
            ),
        );

        const report = await withRecordingFetch(
            routes,
            { honourRange: true, declaredSizes },
            async (requests) => {
                const result = await evaluateComparison(
                    SOURCES,
                    scope(scene),
                    {},
                );
                const artifactRequests = requests.filter((request) =>
                    request.url.startsWith("/m3/parity/known-differences/"),
                );
                assert.equal(
                    artifactRequests.length,
                    10,
                    "every declared path is still sized",
                );
                for (const request of artifactRequests) {
                    assert.equal(
                        request.range,
                        "bytes=0-4095",
                        `a body was fetched for a refused document: ${request.url}`,
                    );
                }
                return result;
            },
        );
        // And the verdict is the engine's own, unchanged by the adapter having skipped the round.
        assert.equal(report.documentRejected, true);
    });

    it("still fetches when the records the engine reads are under the ceiling", async () => {
        // The naive sum over every named path over-estimates: `id-not-safe`, schema failures,
        // `orphaned-target` and `path-not-contained` refuse before reading. Here an `id-not-safe`
        // record declares 80 MiB; gating on the naive sum would make the legal record
        // `artifact-unreadable`.
        const scene = world();
        const doc = withUnreadableRecord(scene);
        const routes = catalogRoutes(scene, doc);
        routes["/m3/parity/known-differences/bad id/mask.png"] = scene.mask;
        routes["/m3/parity/known-differences/bad id/accepted-candidate.png"] =
            scene.accepted;
        const huge = 40 * 1024 * 1024; // 2 x 40 MiB, all of it on the record nobody reads.
        const declaredSizes = {
            "/m3/parity/known-differences/bad id/mask.png": huge,
            "/m3/parity/known-differences/bad id/accepted-candidate.png": huge,
        };

        const report = await withRecordingFetch(
            routes,
            { honourRange: true, declaredSizes },
            async (requests) => {
                const result = await evaluateComparison(
                    SOURCES,
                    scope(scene),
                    {},
                );
                // The safety property, asserted where it bites: every path the engine actually reads
                // was fetched whole.
                const whole = requests.filter(
                    (request) =>
                        request.url.startsWith(
                            "/m3/parity/known-differences/glyph/",
                        ) && request.range === null,
                );
                assert.equal(
                    whole.length,
                    2,
                    "the evaluated record's bodies were never fetched",
                );
                return result;
            },
        );
        assert.deepEqual(report.statuses.glyph, { status: "valid" });
        assert.equal(report.documentRejected, false);
    });

    it("charges a record twice when it names one file for both artifacts", async () => {
        // A record may use one path for both `mask` and `acceptedCandidate`; the engine charges it
        // twice, so counting unique paths under-charges. Five records × 7 MiB × 2 = 70 MiB, past
        // the 64 MiB ceiling. 7 MiB because over the 8 MiB per-artifact cap the record is refused
        // anyway.
        const scene = world();
        const ids = ["glyph", "glyph2", "glyph3", "glyph4", "glyph5"];
        const parsed = JSON.parse(knownDifferencesJson(scene)) as {
            acceptances: Record<string, unknown>[];
        };
        const template = parsed.acceptances[0];
        parsed.acceptances = ids.map((id) => ({
            ...template,
            id,
            // One file, named twice. Its digest has to answer for both fields.
            mask: "mask.png",
            acceptedCandidate: "mask.png",
            acceptedCandidateSha256: template.maskSha256,
        }));
        const routes = catalogRoutes(scene, JSON.stringify(parsed));
        for (const id of ids) {
            routes[`/m3/parity/known-differences/${id}/mask.png`] = scene.mask;
        }
        const declaredSizes = Object.fromEntries(
            ids.map((id) => [
                `/m3/parity/known-differences/${id}/mask.png`,
                7 * 1024 * 1024,
            ]),
        );

        await withRecordingFetch(
            routes,
            { honourRange: true, declaredSizes },
            async (requests) => {
                await evaluateComparison(SOURCES, scope(scene), {});
                const whole = requests.filter(
                    (request) =>
                        request.url.startsWith(
                            "/m3/parity/known-differences/",
                        ) && request.range === null,
                );
                assert.equal(
                    whole.length,
                    0,
                    `an aliased artifact was under-charged and its body fetched: ${whole.map((r) => r.url).join(", ")}`,
                );
            },
        );
    });

    it("charges nothing for a record refused by the per-artifact cap", async () => {
        // A record with an artifact over `maxArtifactBytes` is charged zero by the engine; counting
        // its declared 200 MiB would wrongly skip round two for the legal sibling.
        const scene = world();
        const routes = catalogRoutes(
            scene,
            repeatedRecords(scene, ["glyph", "huge"]),
        );
        routes["/m3/parity/known-differences/huge/mask.png"] = scene.mask;
        routes["/m3/parity/known-differences/huge/accepted-candidate.png"] =
            scene.accepted;
        const declaredSizes = {
            "/m3/parity/known-differences/huge/mask.png": 200 * 1024 * 1024,
            "/m3/parity/known-differences/huge/accepted-candidate.png":
                200 * 1024 * 1024,
        };

        const report = await withRecordingFetch(
            routes,
            { honourRange: true, declaredSizes },
            async (requests) => {
                const result = await evaluateComparison(
                    SOURCES,
                    scope(scene),
                    {},
                );
                const whole = requests.filter(
                    (request) =>
                        request.url.startsWith(
                            "/m3/parity/known-differences/glyph/",
                        ) && request.range === null,
                );
                assert.equal(
                    whole.length,
                    2,
                    "the legal record's bodies were never fetched",
                );
                return result;
            },
        );
        assert.deepEqual(report.statuses.glyph, { status: "valid" });
    });

    it("does not count records the catalog orphans toward the ceiling", async () => {
        // `orphaned-target` is a pre-read refusal, so `prefetch` needs the evaluation's catalog:
        // four orphans at 56 MiB beside a 14 MiB readable record; catalog-blind the sum (70 MiB)
        // trips the gate.
        const scene = world();
        const ids = ["glyph", "orphan1", "orphan2", "orphan3", "orphan4"];
        const routes = catalogRoutes(scene, repeatedRecords(scene, ids));
        for (const id of ids) {
            routes[`/m3/parity/known-differences/${id}/mask.png`] = scene.mask;
            routes[
                `/m3/parity/known-differences/${id}/accepted-candidate.png`
            ] = scene.accepted;
        }
        const each = 7 * 1024 * 1024;
        const declaredSizes = Object.fromEntries(
            ids.flatMap((id) =>
                ["mask.png", "accepted-candidate.png"].map((file) => [
                    `/m3/parity/known-differences/${id}/${file}`,
                    each,
                ]),
            ),
        );
        // Every record names the same preview, so re-point the orphans at one the catalog lacks.
        const parsed = JSON.parse(repeatedRecords(scene, ids)) as {
            acceptances: Record<string, unknown>[];
        };
        for (const record of parsed.acceptances) {
            if (record.id !== "glyph") record.previewId = "no-such-preview";
        }
        routes["/m3/parity/known-differences.json"] = JSON.stringify(parsed);
        const template = JSON.parse(knownDifferencesJson(scene))
            .acceptances[0] as Record<string, string>;
        const catalog = {
            previews: [
                {
                    system: template.system,
                    id: template.previewId,
                    component: template.component,
                    variant: template.variant,
                    referenceIds: [template.referenceId],
                },
            ],
        };

        const report = await withRecordingFetch(
            routes,
            { honourRange: true, declaredSizes },
            async (requests) => {
                const result = await walkCatalog(SOURCES, catalog);
                const whole = requests.filter(
                    (request) =>
                        request.url.startsWith(
                            "/m3/parity/known-differences/glyph/",
                        ) && request.range === null,
                );
                assert.equal(
                    whole.length,
                    2,
                    "the resolvable record's bodies were never fetched — the orphans were counted",
                );
                return result;
            },
        );
        assert.equal(report.documentRejected, false);
        assert.equal(report.statuses.orphan1?.status, "refused");
    });

    it("never reads an artifact whole when its prefix already refuses it", async () => {
        // A `PLTE` declaring 8 KB runs past the 4 KB prefix, so the header pass refuses it and the
        // body is never fetched.
        const scene = world();
        const oversized = pngWithOversizedPlte();
        const routes = catalogRoutes(
            scene,
            knownDifferencesJson(scene, { maskSha256: sha256Hex(oversized) }),
        );
        routes["/m3/parity/known-differences/glyph/mask.png"] = oversized;

        const report = await withRecordingFetch(
            routes,
            { honourRange: true },
            async (requests) => {
                const result = await evaluateComparison(
                    SOURCES,
                    scope(scene),
                    {},
                );
                const maskRequests = requests.filter((request) =>
                    request.url.endsWith("/glyph/mask.png"),
                );
                assert.equal(
                    maskRequests.length,
                    1,
                    "the refused mask is fetched once, not twice",
                );
                assert.equal(
                    maskRequests[0].range,
                    "bytes=0-4095",
                    "and only as a prefix",
                );
                return result;
            },
        );
        assert.deepEqual(report.statuses, {
            glyph: { status: "refused", reasons: ["header-invalid"] },
        });
    });

    it("reads an empty artifact as a short header, not as an unopenable file", async () => {
        // A zero-byte artifact makes `bytes=0-4095` unsatisfiable (`416`, `Content-Range: bytes
        // */0`); that means the file is empty, so the preflight should reach `header-invalid` like
        // the filesystem reader, not `artifact-unreadable`.
        const scene = world();
        const routes = catalogRoutes(scene, knownDifferencesJson(scene));
        const base = recordingFetch(routes, { honourRange: true });
        const original = globalThis.fetch;
        globalThis.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
            const url = String(input);
            if (url.endsWith("/glyph/mask.png")) {
                const ranged =
                    new Headers(init?.headers ?? {}).get("Range") !== null;
                if (ranged) {
                    return Promise.resolve(
                        new Response("range not satisfiable", {
                            status: 416,
                            headers: { "Content-Range": "bytes */0" },
                        }),
                    );
                }
                return Promise.resolve(
                    new Response(new Uint8Array(0) as unknown as BodyInit),
                );
            }
            return base.impl(input, init);
        }) as typeof fetch;
        try {
            const report = await evaluateComparison(SOURCES, scope(scene), {});
            assert.deepEqual(report.statuses, {
                glyph: { status: "refused", reasons: ["header-invalid"] },
            });
        } finally {
            globalThis.fetch = original;
        }
    });

    it("keeps the full read's own refusal token when an artifact changes between the rounds", async () => {
        // The body round can be refused after a clean header round (tree moved, file swapped);
        // `path-not-contained` / `artifact-too-large` must survive rather than degrade to
        // `artifact-unreadable`.
        const scene = world();
        for (const [status, token] of [
            [413, "artifact-too-large"],
            [403, "path-not-contained"],
        ] as const) {
            const routes = catalogRoutes(scene, knownDifferencesJson(scene));
            const base = recordingFetch(routes, { honourRange: true });
            const original = globalThis.fetch;
            globalThis.fetch = ((
                input: RequestInfo | URL,
                init?: RequestInit,
            ) => {
                const url = String(input);
                const ranged =
                    new Headers(init?.headers ?? {}).get("Range") !== null;
                // The prefix is served honestly; only the whole-body read is refused.
                if (url.endsWith("/glyph/mask.png") && !ranged) {
                    return Promise.resolve(new Response("no", { status }));
                }
                return base.impl(input, init);
            }) as typeof fetch;
            try {
                const report = await evaluateComparison(
                    SOURCES,
                    scope(scene),
                    {},
                );
                assert.deepEqual(
                    report.statuses,
                    { glyph: { status: "refused", reasons: [token] } },
                    `a ${status} on the full read must keep its own token`,
                );
            } finally {
                globalThis.fetch = original;
            }
        }
    });

    it("keeps its requests inside a fixed concurrency, however many records there are", async () => {
        // A request pool bounds peak concurrency (and server memory); every request is still made
        // and every answer unchanged.
        const scene = world();
        const routes = catalogRoutes(scene, knownDifferencesJson(scene));
        let inFlight = 0;
        let peak = 0;
        const original = globalThis.fetch;
        const base = recordingFetch(routes, { honourRange: true });
        globalThis.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
            inFlight += 1;
            peak = Math.max(peak, inFlight);
            return base.impl(input, init).finally(() => {
                inFlight -= 1;
            });
        }) as typeof fetch;
        try {
            const report = await evaluateComparison(SOURCES, scope(scene), {});
            assert.deepEqual(report.statuses, { glyph: { status: "valid" } });
        } finally {
            globalThis.fetch = original;
        }
        assert.ok(
            peak <= 8,
            `at most eight artifact requests in flight, saw ${peak}`,
        );
    });

    it("keeps an artifact's true size when the response never declares one", async () => {
        // A chunked `200` has no `Content-Length`, so the prefix length isn't the artifact's;
        // recording it would make the two passes disagree. Deliberately past 4096 bytes.
        const scene = world();
        // Deterministic noise, because a flat raster deflates to well under the prefix and the whole
        // point of this case is an artifact that outgrows it.
        const noisy = raster(64, 64, WHITE);
        for (let i = 0; i < noisy.pixels.length; i += 4) {
            noisy.pixels[i] = (i * 37) % 251;
            noisy.pixels[i + 1] = (i * 89) % 241;
            noisy.pixels[i + 2] = (i * 151) % 239;
        }
        const big = png(noisy);
        assert.ok(
            big.length > 4096,
            "the artifact has to outgrow the prefix to show the bug",
        );
        const routes = catalogRoutes(
            scene,
            knownDifferencesJson(scene, {
                acceptedCandidateSha256: sha256Hex(big),
            }),
        );
        routes["/m3/parity/known-differences/glyph/accepted-candidate.png"] =
            big;

        const report = await withRecordingFetch(
            routes,
            { honourRange: false, declareSize: false },
            () => evaluateComparison(SOURCES, scope(scene), {}),
        );
        // Whatever the record's verdict is on its merits, it must not be "the file changed".
        const reasons = report.statuses.glyph?.reasons ?? [];
        assert.ok(
            !reasons.includes("artifact-unreadable"),
            `an unchanged artifact must not read as changed, got ${JSON.stringify(report.statuses.glyph)}`,
        );
    });

    it("reaches the same verdict from a server that ignores Range entirely", async () => {
        // A host may answer `Range` with a full `200`; that costs bytes, never a different answer
        // (the adapter cuts the stream, the engine caps its header view).
        const scene = world();
        const oversized = pngWithOversizedPlte();
        const routes = catalogRoutes(
            scene,
            knownDifferencesJson(scene, { maskSha256: sha256Hex(oversized) }),
        );
        routes["/m3/parity/known-differences/glyph/mask.png"] = oversized;

        const report = await withRecordingFetch(
            routes,
            { honourRange: false },
            () => evaluateComparison(SOURCES, scope(scene), {}),
        );
        assert.deepEqual(report.statuses, {
            glyph: { status: "refused", reasons: ["header-invalid"] },
        });
    });
});
