#!/usr/bin/env bash
#
# Rewrite every committed golden this repository generates from its own code.
#
# Those are the serve-web page fixtures under `preview-harness/fixtures/pages/` — one HTML file per
# page, plus the link-unfurl cards and render placeholders the harness screenshots — and the
# exploded-view SVGs under `renders/exploded-view/`. All of them are written by the same two tests
# when `UPDATE_SERVE_WEB_FIXTURES=true` is set, so there is one command to remember rather than a
# list of `--tests` filters copied between READMEs (two of which still named `:cli`, a module that
# has not existed since the server split).
#
# Run it, then LOOK at the diff. A golden that moved is a page that moved: `npm run harness:pages`
# in `preview-harness/` re-shoots the screenshots the visual-diff bot comments with.
set -euo pipefail

cd "$(dirname "$0")/.."

# `--rerun`: the test task is up to date whenever its declared inputs have not changed, and an
# environment variable is not one of them, so without this a regeneration straight after a green
# run is a no-op that reads as "the goldens were already correct". `--rerun` and not
# `--rerun-tasks`, which would additionally recompile every task `:server:test` depends on.
UPDATE_SERVE_WEB_FIXTURES=true ./gradlew :server:test \
  --tests '*ServeWebFixtureTest*' \
  --tests '*ExplodedSvgFixtureTest*' \
  --rerun

echo
echo "regenerated:"
git status --porcelain -- preview-harness/fixtures/pages 'renders/exploded-view/*.svg'
