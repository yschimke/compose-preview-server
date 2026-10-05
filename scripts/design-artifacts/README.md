# scripts/design-artifacts (server subset)

The design-artifacts export driver lives in
[compose-ai-tools `scripts/design-artifacts/`](https://github.com/yschimke/compose-ai-tools/tree/main/scripts/design-artifacts),
and the known-differences engine is the
[`@design-parity/known-differences`](https://github.com/yschimke/design-parity/tree/main/packages/known-differences)
package, which `serve-web` depends on. This directory keeps only what the server reads from disk:

- **`known-differences.schema.json`**, the shape of the committed known-difference document.
- **`fixtures/`**: the shared wire fixtures that `:server:test`, `serve-web`'s tests and the
  preview harness read.

Change these in compose-ai-tools first, then copy them here unchanged. The plan to retire this
copy is in design-parity's
[`docs/design-artifacts/CONSOLIDATION.md`](https://github.com/yschimke/design-parity/blob/main/docs/design-artifacts/CONSOLIDATION.md).
