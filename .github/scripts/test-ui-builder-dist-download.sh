#!/usr/bin/env bash
# Exercise the real downloader with deterministic HTTP responses and real ZIP extraction.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
temporary=$(mktemp -d)
trap 'rm -rf "${temporary}"' EXIT
mkdir -p "${temporary}/bin"
python3 - "${temporary}/fixture.zip" <<'PY'
import sys
import zipfile
with zipfile.ZipFile(sys.argv[1], "w") as archive:
    archive.writestr("fixture.txt", "the pinned release\n")
PY
cat >"${temporary}/bin/curl" <<'PY'
#!/usr/bin/env python3
import hashlib
import json
import os
from pathlib import Path
import sys

args = sys.argv[1:]
assert args[args.index("--retry") + 1] == "4"
url = args[-1]
output = Path(args[args.index("-o") + 1])
mode = os.environ["TEST_DOWNLOAD_MODE"]
fixture = Path(os.environ["TEST_DOWNLOAD_ROOT"], "fixture.zip").read_bytes()
with open(os.environ["TEST_DOWNLOAD_LOG"], "a") as log:
    log.write(url + "\n")
api = "https://api.github.com/repos/yschimke/compose-ui-builder"
names = ["web", "renderer", "reference-jetcaster", "generated-jetcaster"]
if url.startswith("https://github.com/yschimke/compose-ui-builder/releases/download/v1.2.3/"):
    if mode != "normal":
        sys.exit(22)
    output.write_bytes(fixture)
elif url == api + "/releases/tags/v1.2.3":
    assets = [{"name": "compose-preview-ui-builder-" + name + "-1.2.3.zip",
               "id": i, "url": api + "/releases/assets/" + str(i), "state": "uploaded",
               "size": len(fixture), "digest": "sha256:" + hashlib.sha256(fixture).hexdigest()}
              for i, name in enumerate(names, 1)]
    if mode == "wrong-name": assets[0]["name"] = "unrelated.zip"
    if mode == "missing-digest": del assets[0]["digest"]
    if mode == "wrong-size": assets[0]["size"] += 1
    if mode == "wrong-host": assets[0]["url"] = "https://other.example/asset"
    output.write_text(json.dumps({"tag_name": "v9.9.9" if mode == "wrong-tag" else "v1.2.3", "assets": assets}))
elif url.startswith(api + "/releases/assets/"):
    assert args[args.index("-H") + 1] == "Accept: application/octet-stream"
    if mode == "corrupt": fixture = bytes([fixture[0] ^ 1]) + fixture[1:]
    output.write_bytes(fixture)
else:
    sys.exit("unexpected URL: " + url)
PY
chmod +x "${temporary}/bin/curl"
export PATH="${temporary}/bin:${PATH}"
export TEST_DOWNLOAD_ROOT="${temporary}"
for mode in normal fallback wrong-name missing-digest wrong-size wrong-host wrong-tag corrupt; do
  export TEST_DOWNLOAD_MODE="${mode}"
  export TEST_DOWNLOAD_LOG="${temporary}/${mode}.requests"
  dest="${temporary}/${mode}"
  mkdir -p "${dest}/ui-builder/build/wasmDist"
  printf 'keep on failure\n' >"${dest}/ui-builder/build/wasmDist/previous.txt"
  if bash "${root}/preview-harness/fetch-ui-builder-dists.sh" "${dest}" 1.2.3 >"${temporary}/${mode}.log" 2>&1; then
    case "${mode}" in normal|fallback) ;; *) echo "FAIL: accepted ${mode}"; exit 1 ;; esac
    [ "$(find "${dest}" -name fixture.txt | wc -l | tr -d ' ')" = 4 ]
    [ ! -e "${dest}/ui-builder/build/wasmDist/previous.txt" ]
  else
    case "${mode}" in normal|fallback) cat "${temporary}/${mode}.log"; exit 1 ;; esac
    [ -f "${dest}/ui-builder/build/wasmDist/previous.txt" ]
    [ "$(find "${dest}" -name fixture.txt | wc -l | tr -d ' ')" = 0 ]
  fi
done
! grep -q 'api.github.com' "${temporary}/normal.requests"
[ "$(grep -c '/releases/assets/' "${temporary}/fallback.requests")" = 4 ]
for mode in wrong-name missing-digest wrong-host wrong-tag; do
  ! grep -q '/releases/assets/\|other.example' "${temporary}/${mode}.requests"
done
grep -q 'SHA-256 mismatch' "${temporary}/corrupt.log"
grep -q 'size mismatch' "${temporary}/wrong-size.log"
echo "OK: canonical downloads, exact pinned API fallback and integrity failures checked."
