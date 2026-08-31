# UI-builder performance acceptance — Mac mini M4, 2026-08-31

This is the first product-spec acceptance run of the installed Compose Preview server and live
UI-builder Wasm client. It is a reference measurement, not a baseline that weakens or replaces the
product targets.

| Gate | Warm samples | p50 | p95 | Target | Result |
| --- | ---: | ---: | ---: | ---: | --- |
| Committed edit to another client's protocol receipt | 50 | 60.90 ms | 93.50 ms | <250 ms | Pass |
| Protocol receipt to first post-layout Wasm canvas frame | 50 | 94.50 ms | 106.10 ms | <16.67 ms | **Fail** |
| Cached design reopen to stable clean render | 20 | 328.70 ms | 412.70 ms | <2000 ms | Pass |

The canvas result is not inferred from the editor revision. The live Wasm client records the delta
receipt with `performance.now()`, records the authoritative snapshot receipt, and records the first
`requestAnimationFrame` following an inspection snapshot for the same document revision. The full
path misses the one-frame target by about 6.4× at p95.

Diagnostic splits for the failed path:

| Segment | p50 | p95 |
| --- | ---: | ---: |
| Delta receipt to authoritative snapshot receipt | 8.10 ms | 13.60 ms |
| Authoritative snapshot receipt to post-layout frame | 84.40 ms | 94.90 ms |

This points at renderer-document conversion/recomposition after the snapshot arrives, rather than
the delta notification or snapshot refresh itself. No catalog or font cold start is included: five
edits and one complete reopen were warmed and discarded before sampling.

Reference environment:

- Apple M4, 10 logical CPUs
- macOS/Darwin 25.5.0, arm64
- Node v26.5.0
- Playwright Chromium 151.0.7922.34
- 1440×900 viewport, headless SwiftShader/ANGLE
- installed `compose-preview-server` distribution, loopback HTTP/WebSocket
- 50 measured edits, 20 cached reopens

Command:

```shell
UI_BUILDER_PERF=1 npm --prefix preview-harness run harness:ui-builder-performance
```

The acceptance command exited non-zero on the canvas assertion while preserving JSON, Markdown,
server log, and Playwright trace artifacts in the local Playwright result directory. Those raw
artifacts may contain authenticated request URLs and are intentionally not uploaded automatically;
this sanitized report is the reviewable committed evidence.

## Profile-guided follow-up — 2026-09-01

The identical 50-edit/20-reopen workload was repeated after coalescing the renderer's inspection
burst before both whole-document snapshot construction and JSON encoding. The marker remains after
the latest inspection snapshot and subsequent browser animation frame.

| Gate | p50 | p95 | Original p95 | Target | Result |
| --- | ---: | ---: | ---: | ---: | --- |
| Committed edit to another client's protocol receipt | 41.10 ms | 59.50 ms | 93.50 ms | <250 ms | Pass |
| Protocol receipt to first post-layout Wasm canvas frame | 30.30 ms | 39.50 ms | 106.10 ms | <16.67 ms | **Fail** |
| Cached design reopen to stable clean render | 226.10 ms | 276.00 ms | 412.70 ms | <2000 ms | Pass |

The authoritative snapshot receipt to canvas phase fell from 94.90 ms to 32.20 ms p95. Its
source-level split is now:

| Segment | p50 | p95 |
| --- | ---: | ---: |
| Authoritative receipt to first renderer inspection invalidation | 11.10 ms | 20.10 ms |
| One coalesced 108-node inspection snapshot and JSON encoding | 0.70 ms | 1.00 ms |
| Remaining scheduling and post-layout animation frame | 11.00 ms | 11.10 ms |

The retained protocol-tree property projection itself is 0.20 ms p95 and canonical JSON/SHA-256
verification is 1.70 ms p95. It currently falls back to the authoritative snapshot because the
server hashes its new `updatedAtEpochMillis`, which legacy deltas do not carry. The additive wire
fix is tracked separately in compose-preview-contracts PR #31; the client does not trust the delta
without an exact hash match.

A stable per-node renderer-map experiment was also profiled and rejected: it added a second Compose
state application and regressed authoritative receipt to canvas to 35.80 ms in the bounded run. The
remaining 20.10 ms p95 before the first layout callback is therefore the current full editor/Compose
Wasm recomposition phase and already exceeds the one-frame target before paint scheduling. The gate
has not been weakened.
