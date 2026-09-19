#!/usr/bin/env bash
#
# Rewrite every committed golden this repository generates from its own code.
#
# Those are the serve-web page fixtures under `preview-harness/fixtures/pages/` — one HTML file per
# page, plus the link-unfurl cards and render placeholders the harness screenshots — the
# exploded-view SVGs under `renders/exploded-view/`, and the frozen UI-builder catalogs under
# `docs/design/fixtures/ui-builder/`. There is one command to remember rather than a list of
# `--tests` filters copied between READMEs (two of which still named `:cli`, a module that has not
# existed since the server split) — and a golden whose regeneration is not in this script is a
# golden nobody regenerates, because AGENTS.md and `/regenerate-goldens` both send people here.
#
# Run it, then LOOK at the diff. A golden that moved is a page that moved: `npm run harness:pages`
# in `preview-harness/` re-shoots the screenshots the visual-diff bot comments with.
set -euo pipefail

cd "$(dirname "$0")/.."

# `--rerun`: the test task is up to date whenever its declared inputs have not changed, and an
# environment variable is not one of them, so without this a regeneration straight after a green
# run is a no-op that reads as "the goldens were already correct". `--rerun` and not
# `--rerun-tasks`, which would additionally recompile every task `:server:test` depends on.
#
# `-PcomposeUiBuilderDir` because `BehaviorScreenExportTest` is gated on
# `UiBuilderBuildFeatures.remoteCompose`, and that constant now comes from the UI-builder export's
# own release build — where the flag is off. Without the checkout the test SKIPS and the two
# `state-actions` fixtures silently stay as they were, which is the failure mode the `--rerun`
# comment above exists to prevent. A missing checkout fails the require() in `settings.gradle.kts`
# rather than skipping, which is the right shape for a regeneration.
UPDATE_SERVE_WEB_FIXTURES=true UPDATE_UI_BUILDER_BEHAVIOR_FIXTURE=true ./gradlew -PcomposeUiBuilderDir=../compose-ui-builder -PuiBuilderRemoteCompose=true :server:test \
  --tests '*ServeWebFixtureTest*' \
  --tests '*ExplodedSvgFixtureTest*' \
  --tests '*BehaviorScreenExportTest*' \
  --rerun

# The synthesised `wear-m3` / `remote-m3` catalogs are NOT regenerated here any more. They are
# written by `SynthesisedCatalogGoldenTest`, which lives with the runtime that synthesises them
# (yschimke/compose-ui-builder, `:ui-builder-runtime`), and this repository keeps a copy of the two
# files because `ui-builder-contract` compares the published catalogs against them. Regenerate them
# in a checkout of that repository and copy the two files across:
#
#   (cd ../compose-ui-builder && ./gradlew -PuiBuilderRemoteCompose=true \
#     :ui-builder-runtime:test --tests '*SynthesisedCatalogGoldenTest*' -PuiBuilderGoldens=write)
#   cp ../compose-ui-builder/docs/design/fixtures/ui-builder/{wear,remote}-m3-capabilities-v1.json \
#     docs/design/fixtures/ui-builder/
#
# `RemoteRootSourceExportTest` moved with it for the same reason; `remote-root.kt.txt` in this
# directory is generated there and copied the same way.

echo
echo "regenerated:"
git status --porcelain -- preview-harness/fixtures/pages 'renders/exploded-view/*.svg' \
  'docs/design/fixtures/ui-builder/*-capabilities-v1.json' \
  'docs/design/fixtures/ui-builder/state-actions.kt.txt' \
  'docs/design/fixtures/ui-builder/state-actions.document.json'
