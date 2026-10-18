# Concurrent export admission — issue #1421

All load-test traffic used an isolated server bound to **127.0.0.1:18081**. No load requests were sent to
preview.coo.ee or ha.coo.ee. Current main was checked before editing and again before delivery:
`ca0a47fc95d2cd0ffb3306fa039230340a3ebe07`; the only open PR at those checks was release PR #1746.
Available Hive session summaries showed no export-admission overlap; no worker internals or
private session messages were assumed to be available.

Baseline: server release **3.144.0**. Candidate: source-built **3.144.1-SNAPSHOT** from checked
main plus this patch, substituted into the same isolated distribution. Relevant resolved artifacts
match checked main: UI-builder **3.121.0**, render-host **2.67.0**, daemon client/core **3.18.6**
(the selectively published daemon BOM is **3.18.7**). Both use Temurin **21.0.12.1+1**, a **512 MiB**
server heap and fresh fixture state. The host is an isolated Linux x64 Hive VM.

The fixture serves one synthetic committed benchmark Text design, the real packaged PNG/SVG
renderer, thumbnails and one synthetic baked catalog preview. Commands and the rerunnable fixture
are in [EXPORT_LOAD_TEST.md](../development/EXPORT_LOAD_TEST.md). The probe refuses non-loopback
origins, proxies and redirects. Credentials are never URLs or report fields and are redacted if
returned in an error body.

## Captured causes and response bodies

The initial reproduction captured these actual REST error bodies before diagnosis:

| Resource refusal | Before | Exact body | After |
| --- | --- | --- | --- |
| Four active exports | 400 | `concurrent export limit reached` | 503, `Retry-After: 1` |
| Bounded worker pool unavailable (including a still-running timed-out task) | 400 | `concurrent export worker limit reached` | 503, `Retry-After: 1` |
| Render-host lock busy after its two-second wait (controlled HTTP reproduction with real host/port/runtime adapters) | 500 | `export failed: UI-builder renderer is busy` | 503, `Retry-After: 1` |

The runtime's first two refusals are `BAD_REQUEST` with `retryable=true`; the REST adapter had
mapped the code straight to 400. Its bounded `SynchronousQueue` worker pool can also refuse while
all workers are still occupied after interruption or just finishing. The render port's explicit
`Busy` outcome was thrown and wrapped by the runtime as `INTERNAL`. The compatibility mapping
recognizes only these exact, verified cases. No dependency versions or upstream workarounds changed.

MCP previously returned an ordinary successful tool result containing this service error, with
no `isError`:

```json
{"schemaVersion":1,"callId":"ui_builder_export","response":{"type":"error","error":{"code":"badRequest","message":"concurrent export limit reached","retryable":true}}}
```

Afterward its HTTP status remains 200 and JSON-RPC `result` is retained, with this execution error:

```json
{"content":[{"type":"text","text":"concurrent export limit reached"}],"isError":true,"structuredContent":{"code":"export_overloaded","retryable":true,"retryAfterSeconds":1}}
```

REST request envelopes use the existing wire enum's `internal` with `retryable=true`, HTTP 503
and the retry header. Invalid revisions remain 400; genuine exporter failures and export timeouts
remain 500. Artifact diagnostics and source compiler failures retain their existing semantics.

## Measurements

Client workers synchronized at a barrier before each burst. Each format/read mode used bursts of **1, 4, 16, 64 and 128**, followed by a single recovery request.
Mixed mode used **50 agents**, each doing get → Compose export → PNG export, then one recovery agent.
[The machine-readable evidence](concurrent-exports-1421.json) contains every level's p50/p99,
status counts, MCP execution-error counts, captured error-body examples (grouped ignoring only JSON-RPC request id) and resource samples.
The table below shows the 128-request burst; latencies include successes and refusals.

| Mode | Before status/count | After status/count | Before p50 / p99 ms | After p50 / p99 ms |
| --- | --- | --- | --- | --- |
| compose | 200: 31, 400: 97 | 200: 17, 503: 111 | 87.24 / 243.88 | 89.14 / 195.10 |
| png | 200: 14, 400: 114 | 200: 18, 503: 110 | 73.04 / 178.69 | 114.71 / 235.82 |
| svg | 200: 14, 400: 114 | 200: 27, 503: 101 | 76.73 / 177.38 | 112.61 / 253.27 |
| thumbnail | 200: 128 | 200: 128 | 76.11 / 141.19 | 55.35 / 112.60 |
| mcp-compose | 200: 128; 104 tool errors | 200: 128; 106 tool errors | 83.73 / 171.16 | 66.19 / 162.61 |
| mcp-png | 200: 128; 113 tool errors | 200: 128; 115 tool errors | 85.79 / 209.12 | 68.64 / 168.05 |
| tools | 200: 128 | 200: 128 | 164.43 / 323.42 | 192.50 / 380.63 |
| get | 200: 128 | 200: 128 | 84.43 / 194.45 | 82.42 / 215.54 |
| catalog | 200: 128 | 200: 128 | 67.01 / 175.94 | 61.78 / 146.93 |

All candidate REST refusals were 503 with `Retry-After: 1`. All candidate MCP execution errors
carried `isError`, `retryable` and a retry interval. No candidate load response was HTTP 400 or 500.
Every recovery probe succeeded (including all three operations of the recovery mixed agent).

- **before:** sampled heap peak 209.3 MiB; active/peak exports 4/4; 4 export workers; 1 renderer child process. Mixed 50-agent burst: p50 44.19 ms, p99 171.64 ms, 81 retryable service refusals.
- **after:** sampled heap peak 202.0 MiB; active/peak exports 4/4; 4 export workers; 1 renderer child process. Mixed 50-agent burst: p50 66.25 ms, p99 206.95 ms, 76 retryable service refusals.

Sampling runs every 50 ms and can miss short spikes; the runtime's cumulative `peakExports` also
confirmed the four-export ceiling. Export admission is immediate, with no added HTTP queue.
Native admission is also immediate with four permits shared by REST/MCP/rendered checks, released
in `finally`; deterministic saturation and recovery tests cover that lane. The existing 512-job thumbnail
queue retains its 60-second wait bound and now reports retryable saturation rather than a missing image.

These are single bursts against one small design; after its first rasterization, repeated frames
hit the renderer cache. Counts vary with scheduling and JIT, and this is not a throughput or speedup
claim. A many-design cold native compilation audit, production deployment limits, real compiler
sidecar counts and long soak behavior were **not** measured. The fixture installs no actual native
compiler; controlled HTTP/MCP lanes verify native admission, compiler-busy classification, rendered
checks and recovery. Production was not load-tested.

## Verification

- Nine focused regressions passed, including full/worker admission, timed-out uncooperative workers,
  renderer-lock contention, REST request envelopes, MCP, native/rendered-check sharing, thumbnail
  saturation, malformed requests, genuine exporter failures and recovery.
- Targeted server/agent tests plus real Chrome Wasm browser tests: **71 passed** (45 server, 26 Wasm).
- `ktfmtFormat`, `ktfmtCheckAll`, server module boundary, JVM floor and desktop sidecar packaging passed.
- Full server suite: **4,354 passed, 30 skipped, one unrelated failure** out of 4,385 tests. The
  unchanged `ThemeCachePersistenceTest` expected one dirty render but saw two at line 242. It failed
  on both broad runs and is tracked in [SCH-31](https://linear.app/yschimke/issue/SCH-31). No cache
  code, dependency pin or assertion was weakened to bypass it. The first root run lacked Chrome;
  installing an isolated headless browser made the Wasm checks pass on rerun.
