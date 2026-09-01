# Versioning

The standalone server versions itself, starting at **2.0.0**. Nothing in this repository has been
released yet — no tag, no coordinate on Maven Central — so the renumbering costs no consumer
anything, and it is much cheaper to do now than after a 1.x is published.

## Why 2.0.0 and not 1.52.0

`.release-please-manifest.json` seeds `1.51.0`, which is not a version this repository shipped. It
is compose-ai-tools' counter, carried across at the extraction so the changelog had a baseline to
compare against. Continuing it would publish `compose-preview-server 1.52.0` alongside
`compose-ai-tools 1.53.0` — two different products, one apparently a patch behind the other, on a
shared counter neither of them controls.

The break is real rather than ceremonial. What ships here is a different artifact from what
compose-ai-tools ships: a standalone distribution with its own entry point
(`bin/compose-preview-server`, not `compose-preview serve`), its own coordinates, its own release
cadence, and no CLI, MCP server, Gradle plugin or renderer around it. Anyone resolving a
`compose-preview-server` coordinate is resolving something that never existed under the old number.

So the server's counter leaves compose-ai-tools' behind here and does not track it again. A
compose-ai-tools release does not imply a server release, and the reverse is equally untrue.

## The manifest is the LAST released version

`.release-please-manifest.json` says what has already shipped, not what to ship next. It stays at
`1.51.0`, and a `feat!:` commit (or a `BREAKING CHANGE:` footer) computes `2.0.0` from it.

Writing `2.0.0` into the manifest instead would tell release-please that 2.0.0 is already out, and
it would propose the version *after* one that nothing has tagged. compose-preview-contracts records
the same rule for the same reason, having seeded `1.46.2` and let its cutover `feat!:` compute
`2.0.0` — see [its `docs/VERSIONING.md`](https://github.com/yschimke/compose-preview-contracts/blob/main/docs/VERSIONING.md).

The open release pull request retitles itself on the next push to `main`. It is a bot-maintained
branch; do not hand-edit the version in it.

## What follows the version automatically

`deploy/image/Dockerfile`'s `ARG SERVER_VERSION` is a release-please `extra-files` entry, so it
tracks each release without anyone remembering to bump it. `ARG TOOLS_VERSION` in the same file is
**not** the server's version — it names the compose-ai-tools release supplying the daemon sidecars.
It has no Dockerfile default: automatic releases read the value from `composeai-tools` in
`gradle/libs.versions.toml`, keeping the separately packaged daemons aligned with the server's
runtime dependencies, while manual image builds must name the tools release explicitly.

## Not to be confused with compose-preview-contracts 2.x

`compose-preview-contracts` is also on a 2.x line, and the two numbers are unrelated: they were
computed independently, from different baselines, for different breaks. A server 2.x does not
require a contracts 2.x, or vice versa. The version this repository consumes is pinned in
`gradle/libs.versions.toml` as `composeai-contracts`.
