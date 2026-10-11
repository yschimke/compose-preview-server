// Typed seam onto the shared acceptance engine (`scripts/design-artifacts/*.mjs`). `tsc` reads the
// JS by inference only, which types `null`/`[]` defaults as `null`/`never[]`, so the signatures are
// widened here, once, rather than with JSDoc in the reference implementation or `any` at call
// sites. The conformance suite runs the JS directly and won't catch drift, so keep these
// declarations minimal.

import {
    BUDGET as BudgetJs,
    acceptanceLifecycles as acceptanceLifecyclesJs,
    evaluateKnownDifferences as evaluateJs,
    readsNoArtifacts as readsNoArtifactsJs,
    recordsThatRead as recordsThatReadJs,
} from "@design-parity/known-differences/known-differences";
import {
    canonicalRaster as canonicalRasterJs,
    projectTagIndex as projectTagIndexJs,
    resolvePlane as resolvePlaneJs,
} from "@design-parity/known-differences/known-difference-plane";
import { scoreComparison as scoreComparisonJs } from "@design-parity/known-differences/known-difference-score";
import {
    MAX_CONFORMING_HEADER_BYTES as MaxConformingHeaderBytesJs,
    decodePng as decodePngJs,
    preflightPng as preflightPngJs,
    sha256Hex as sha256HexJs,
} from "@design-parity/known-differences/png-lite";

/** A decoded raster, in the shape `png-lite.mjs` hands one over. */
export interface Raster {
    width: number;
    height: number;
    pixels: Uint8Array;
}

export interface Box {
    x: number;
    y: number;
    width: number;
    height: number;
}

/** The recorded canonical plane: the discriminant plus the resolved box (I9). */
export interface Plane {
    plane: "content-box" | "full-canvas";
    box: Box;
}

/**
 * `{ error }` is the reader's vocabulary. `{ bytes, byteLength }` is the header pass's prefix
 * answer: `bytes` is at most the requested prefix, `byteLength` the whole artifact's size. A bare
 * `Uint8Array` is the whole file (the decode pass).
 */
export type ArtifactAnswer =
    Uint8Array | { bytes: Uint8Array; byteLength: number } | { error: string };

/** The header pass asks for at most `prefix` bytes; the decode pass passes no options at all. */
export interface ReadOptions {
    prefix?: number;
}

export interface EngineStatus {
    status: string;
    causes?: string[];
    reasons?: string[];
}

export interface EngineResult {
    /** Absent entirely for a document-level rejection — not the same as "every acceptance passed". */
    statuses?: Record<string, EngineStatus>;
    /** The `valid` acceptances' masks: the union the scorer suppresses, and no other status (I5). */
    survivingMasks?: Array<{ id: string; mask: Raster }>;
    validationFailures: Array<{ id?: string; reason: string }>;
}

export interface IssueIndexRow {
    repository?: string;
    number?: number;
    url?: string;
    state: "open" | "closed";
}

export interface AcceptanceLifecycle {
    issue: string | null;
    lifecycle: "open" | "closed" | "unknown";
    stale: boolean;
}

export const acceptanceLifecycles = acceptanceLifecyclesJs as unknown as (
    documentRecords: unknown[],
    statuses: Record<string, EngineStatus>,
    issueRows?: IssueIndexRow[],
) => Record<string, AcceptanceLifecycle>;

/** `testTag → {count, bounds}`. Bounds are render-pixel on the wire and canonical after projection. */
export type TagIndex = Record<string, { count: number; bounds?: unknown }>;

export interface Comparison {
    system: string;
    component: string;
    previewId: string;
    referenceId: string;
    variant: string;
    overrides: Record<string, string>;
    referenceSha256: string | null;
    plane: Plane;
    canonicalReference: Raster;
    canonicalCandidate: Raster;
    tagIndex: TagIndex;
}

export interface Catalog {
    previews: Array<{
        system: string;
        id: string;
        component: string | null;
        variant: string;
        referenceIds: string[];
    }>;
}

/**
 * Whether the engine rejects this document before reading any artifact. The engine's own function
 * so fetch-ahead planning never uses a second copy of the rejection rules.
 */
/**
 * The ids whose artifacts the engine will actually read (the exact set the evaluation uses), so a
 * planner doesn't gate on the upper bound of every named path.
 */
export const recordsThatRead = recordsThatReadJs as unknown as (
    documentText: string,
    catalog?: unknown,
) => string[];

export const readsNoArtifacts = readsNoArtifactsJs as unknown as (
    documentText: string,
) => boolean;

export const evaluateKnownDifferences = evaluateJs as unknown as (options: {
    documentText: string;
    readArtifact: (
        path: string,
        options?: ReadOptions,
    ) => ArtifactAnswer | null;
    comparison?: Comparison | null;
    catalog?: Catalog | null;
}) => EngineResult;

export const scoreComparison = scoreComparisonJs as unknown as (options: {
    reference: Raster;
    candidate: Raster;
    referenceBox: Box;
    candidateBox: Box;
    plane: Plane;
    masks?: Raster[];
}) => { raw: number; accepted: number; unaccepted: number };

export const resolvePlane = resolvePlaneJs as unknown as (
    reference: Raster,
    candidate: Raster,
) => {
    plane: Plane;
    boxes: { reference: Box; candidate: Box };
    geometry: number;
};

export const canonicalRaster = canonicalRasterJs as unknown as (
    image: Raster,
    box: Box,
    plane: Plane,
) => Raster;

export const decodePng = decodePngJs as unknown as (
    bytes: Uint8Array,
) => Raster;

/** Lowercase hex SHA-256 of the given bytes, from the contract's own FIPS 180-4 implementation. */
export const sha256Hex = sha256HexJs as unknown as (
    bytes: Uint8Array,
) => string;

/**
 * The header preflight, so the browser host can full-fetch only artifacts whose header is clean.
 */
export const preflightPng = preflightPngJs as unknown as (
    bytes: Uint8Array,
    options?: { byteLength?: number },
) =>
    | { error: string }
    | {
          width: number;
          height: number;
          bitDepth: number;
          colourType: number;
          animated: boolean;
          hasTransparency: boolean;
          byteLength: number;
      };

/** The versioned budget, shared so the host sizes its prefix reads to the same constant. */
export const BUDGET = BudgetJs as unknown as {
    maxDocumentBytes: number;
    maxAcceptances: number;
    maxPixels: number;
    maxAxis: number;
    maxArtifactBytes: number;
    /**
     * The total bytes one document may oblige a reader to hold across all artifacts; needed by the
     * prefetch gate since `maxArtifactBytes` bounds only one file.
     */
    maxTotalArtifactBytes: number;
    maxPreflightBytes: number;
};

/** The most bytes a conforming header region can occupy; the prefix must be at least this. */
export const MAX_CONFORMING_HEADER_BYTES =
    MaxConformingHeaderBytesJs as unknown as number;

/** The published tag index, render-pixel, projected into the comparison's canonical plane. */
export const projectTagIndex = projectTagIndexJs as unknown as (
    tagIndex: TagIndex,
    candidateBox: Box,
    plane: Plane,
) => TagIndex;
