#!/usr/bin/env bash
set -euo pipefail

source_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
fixture_root="${source_root}/scripts/ui-builder-external-consumer"
scratch_root=$(mktemp -d "${TMPDIR:-/tmp}/compose-preview-ui-builder-consumer.XXXXXX")
trap 'rm -rf "${scratch_root}"' EXIT

published_repository="${scratch_root}/repository"
consumer_root="${scratch_root}/consumer"
gate_version="0.0.0-extraction-gate-SNAPSHOT"
mkdir -p "${published_repository}" "${consumer_root}"

PLUGIN_VERSION="${gate_version}" \
  "${source_root}/gradlew" \
  --no-daemon \
  --init-script "${fixture_root}/publish.init.gradle" \
  -PuiBuilderExtractionRepository="${published_repository}" \
  :ui-builder-runtime:publishMavenPublicationToUiBuilderExtractionRepository \
  :ui-builder-web:publishMavenPublicationToUiBuilderExtractionRepository

cp -R "${fixture_root}/." "${consumer_root}/"
rm "${consumer_root}/publish.init.gradle"
cp "${source_root}/gradlew" "${consumer_root}/gradlew"
cp "${source_root}/gradlew.bat" "${consumer_root}/gradlew.bat"
mkdir -p "${consumer_root}/gradle/wrapper"
cp "${source_root}/gradle/wrapper/gradle-wrapper.jar" "${consumer_root}/gradle/wrapper/"
cp "${source_root}/gradle/wrapper/gradle-wrapper.properties" "${consumer_root}/gradle/wrapper/"

(
  cd "${consumer_root}"
  ./gradlew \
    --no-daemon \
    --stacktrace \
    -PgateRepository="${published_repository}" \
    -PgateVersion="${gate_version}" \
    -PforbiddenSourceRoot="${source_root}" \
    clean verifyExtractionConsumer
)

echo "UI-builder external consumer gate passed"
