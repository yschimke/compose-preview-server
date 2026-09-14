#!/usr/bin/env bash
# Guard the post-#794 release contract: this repository ships distributions, not Maven modules.
#
# A released compose-preview CLI intentionally ignores Maven Local. Forcing its init script to add
# mavenLocal() lets an old or user-mounted coordinate shadow the released plugin, while the empty
# image seed makes the Dockerfile look self-contained when it is not. The image may still pre-fetch
# Robolectric into Maven's cache; that is a third-party runtime jar, not a repository publication.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "${here}/../.." && pwd)"
dockerfile="${DOCKERFILE_UNDER_TEST:-${here}/Dockerfile}"
workflow="${WORKFLOW_UNDER_TEST:-${root}/.github/workflows/preview-host-image.yml}"

if grep -q 'COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL' "${dockerfile}"; then
  echo "FAIL: the released image forces its Gradle init script to trust Maven Local" >&2
  exit 1
fi

if grep -Eq '^[[:space:]]*COPY[[:space:]]+m2/' "${dockerfile}"; then
  echo "FAIL: the image still carries a server Maven publication tree" >&2
  exit 1
fi

if grep -q 'deploy/image/m2' "${workflow}"; then
  echo "FAIL: the image workflow still prepares a server Maven publication tree" >&2
  exit 1
fi

echo "PASS: released images use distributions and do not force Maven Local"
