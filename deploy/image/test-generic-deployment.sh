#!/usr/bin/env bash
# Exercise the actual administrator-default block, including explicit deployment overrides.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
entrypoint="${ENTRYPOINT_FILE:-$here/entrypoint.sh}"
block="$(sed -n '/^: "${SERVE_UI_BUILDER_ADMIN_ACTORS:=/,/args+=(--ui-builder-admin-actors/p' "$entrypoint")"
for value in unset '' none github:example; do
  actual="$(env -i PATH="$PATH" VALUE="$value" BLOCK="$block" bash -c '
    args=()
    if [[ "$VALUE" != unset ]]; then export SERVE_UI_BUILDER_ADMIN_ACTORS="$VALUE"; fi
    eval "$BLOCK"
    printf "%s" "${args[*]}"
  ')"
  expected=''
  [[ "$value" == github:example ]] && expected='--ui-builder-admin-actors github:example'
  [[ "$actual" == "$expected" ]] || { echo "FAIL: unexpected administrator for $value"; exit 1; }
done
python3 - "$here/../example" <<'PY'
import json, sys
from pathlib import Path
root = Path(sys.argv[1])
# The adoption path names no particular deployment: not a real public host's domain, not a sibling
# project of one. Spelled in pieces so this guard does not itself trip a repository-wide search.
forbidden = ['coo' + '.ee', 'home' + 'assistant', 'home ' + 'assistant']
for path in root.iterdir():
    text = path.read_text()
    for word in forbidden:
        assert word not in text.lower(), (path, word)
    if path.suffix == '.json':
        json.loads(text)
entrypoint = (root.parent / 'image/entrypoint.sh').read_text()
assert '${SERVE_GITHUB_AUTH_REPO:-yschimke/' not in entrypoint
assert '[[ -n "${SERVE_GITHUB_AUTH_REPO:-}" ]] && args+=' in entrypoint
print('PASS: independent example and explicit operator identity/repository policy')
PY
