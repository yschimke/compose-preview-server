# Preview server extraction — historical record

> **The extraction happened, and this is the repository it produced.** The server lives here and
> consumes released `compose-ai-tools` and `compose-preview-contracts` coordinates.

This page is kept for one reason: two build files cite a lesson it recorded, and that lesson is
still worth having. Everything else it described was preparation carried out in
[yschimke/compose-ai-tools](https://github.com/yschimke/compose-ai-tools) — the symbol ratchet, the
artifact probe, the coupling gate and the daemon-launch schema check. **None of those has ever
existed in this repository**, and the first three no longer exist in that one either. The original
analysis is [compose-ai-tools#3824](https://github.com/yschimke/compose-ai-tools/issues/3824) and
this file's git history.

## The lesson `:bundle-format` taught twice

**Renaming a package is a separate change from moving a module.** The two are independent, and doing
them together makes a 300-file move unreviewable. That is why the sources here keep
`ee.schimke.composeai.cli.serve`, and why `:mcp`'s tests kept JUnit 4 and Truth when the module
moved rather than being converted in the same PR.

The general form: a move should be reviewable as a move. Anything that changes what the code *says*
— a package, a test framework, an assertion library, a formatter — waits for its own change.

## What replaced the boundary this document argued for

A published module boundary, then a repository one. The current UI-builder boundary — which module
owns the service port, persistent design state, catalog validation and export orchestration, and
what it deliberately does not own — is stated and enforced in
[`UI_BUILDER_PROJECT_BOUNDARY.md`](UI_BUILDER_PROJECT_BOUNDARY.md), not here.

`:server` depends on `:ui-builder-runtime` and on `ee.schimke.composeai:render-host` (published from
compose-ai-tools); there is no edge between those two libraries.
