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
UPDATE_SERVE_WEB_FIXTURES=true UPDATE_UI_BUILDER_BEHAVIOR_FIXTURE=true ./gradlew :server:test \
  --tests '*ServeWebFixtureTest*' \
  --tests '*ExplodedSvgFixtureTest*' \
  --tests '*BehaviorScreenExportTest*' \
  --rerun

# The synthesised `wear-m3` / `remote-m3` catalogs, in a second module and behind a Gradle property
# rather than an environment variable — `-D` on the command line reaches the Gradle JVM and not the
# forked test JVM, which is a silent no-op and a confusing half hour. Same `--rerun` reasoning.
./gradlew :ui-builder-runtime:test \
  --tests '*SynthesisedCatalogGoldenTest*' \
  -PuiBuilderGoldens=write \
  --rerun

echo
echo "regenerated:"
git status --porcelain -- preview-harness/fixtures/pages 'renders/exploded-view/*.svg' \
  'docs/design/fixtures/ui-builder/*-capabilities-v1.json' \
  'docs/design/fixtures/ui-builder/state-actions.kt.txt' \
  'docs/design/fixtures/ui-builder/state-actions.document.json'
