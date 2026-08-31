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
