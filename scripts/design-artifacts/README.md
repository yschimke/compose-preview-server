# scripts/design-artifacts (server subset)

The design-artifacts export driver lives in
[compose-ai-tools `scripts/design-artifacts/`](https://github.com/yschimke/compose-ai-tools/tree/main/scripts/design-artifacts).
This directory keeps only what the server itself uses:

- **The known-differences engine**, which `serve-web` bundles so the viewer and the offline
  driver agree on what an acceptance means: `known-differences.mjs`, `known-difference-*.mjs`,
  `png-lite.mjs`, `png-write.mjs`, `inflate-lite.mjs`, `sha256-lite.mjs` and
  `known-differences.schema.json`.
- **`fixtures/`**: the shared wire fixtures that `:server:test`, `serve-web`'s tests and the
  preview harness read from disk.

Change these files in compose-ai-tools first, then copy them here unchanged. The plan to
replace this copy with a package is in design-parity's
[`docs/design-artifacts/CONSOLIDATION.md`](https://github.com/yschimke/design-parity/blob/main/docs/design-artifacts/CONSOLIDATION.md).
