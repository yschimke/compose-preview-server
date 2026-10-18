# Isolated export load test

Never run this test against production. `scripts/export-load-test.py` accepts only numeric
loopback HTTP origins and refuses redirects. Use an isolated local server with synthetic documents.
It synchronizes client workers at a barrier, sends one bounded burst per level
(1, 4, 16, 64, 128), then sends one recovery request. Mixed
mode runs 50 agents doing get → Compose export → PNG export. Error bodies, MCP execution errors,
Retry-After, p50 and p99 are written to JSON. Tokens come only from `COMPOSE_PREVIEW_TOKEN` and
are redacted from response bodies.

Build with the repository launcher (Java 17 compiler and Java 21 runtime; Linux renderer needs
libGL, fontconfig and X11 libraries):

```sh
scripts/agent-gradle.sh --exclusive :server:installDist :server:testClasses
java -Xmx512m -Djava.awt.headless=true \
  -cp 'server/build/install/compose-preview-server/lib/*:server/build/classes/kotlin/test' \
  ee.schimke.composeai.cli.serve.ExportLoadFixtureKt /tmp/isolated-export-load
```

Run the fixture from the repository root. It binds **127.0.0.1:18081**, creates only `load-design`
in the supplied state directory, uses the committed benchmark catalog and component record, and
runs the actual packaged PNG/SVG renderer. It does not load hosted designs or catalogs. Its fixed
`local-fixture-only` token is synthetic test data, accepted only by this local fixture. Run the probe
in another terminal:

```sh
COMPOSE_PREVIEW_TOKEN=local-fixture-only python3 scripts/export-load-test.py \
  --server http://127.0.0.1:18081 --design load-design --output /tmp/export-load.json \
  --modes compose,png,svg,thumbnail,mcp-compose,mcp-png,tools,get,mixed,catalog
```

The fixture samples heap use, active/peak exports, export worker threads and descendant process
counts every 50 ms into `resources.jsonl` in its state directory. Sampling can miss short spikes;
`peakExports` is the runtime's cumulative high-water mark. Compare identical fixture inputs and
JVM limits on the base and candidate commits, retain the error bodies, and confirm the last request
of each mode succeeds after the burst. Use a fresh directory for each run. Renderer cache hits
make repeated single-design bursts cheaper than a many-design cold audit; record this limitation.

The fixture also serves one synthetic baked catalog preview (`load-fixture` / `load-preview`).
The fixture does not install a real native compile lane. Native admission,
rendered checks and thumbnail saturation are covered by deterministic HTTP/MCP regressions in
`ServeExportOverloadTest`. On another isolated server with those lanes configured, select
`--modes native,check` as additional probes. Do not interpret missing lanes or authorization failures as load results.
