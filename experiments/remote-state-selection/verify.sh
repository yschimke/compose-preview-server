#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
# Regenerate every source even if a previous filtered test left the task up to date.
./gradlew :ui-builder-export:jvmTest --tests '*RemoteStateSelectionExportTest' --rerun-tasks
./gradlew -p experiments/remote-state-selection testDebugUnitTest
