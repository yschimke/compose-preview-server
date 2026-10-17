#!/usr/bin/env bash
# An opt-in demo route must reach only the facade and keep Caddy on its existing network.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
python3 - "$here" <<'PY'
from pathlib import Path
import sys
root = Path(sys.argv[1])
ha = (root / 'ha.caddy').read_text()
route = (root / 'demo-ha.caddy').read_text()
overlay = (root / 'docker-compose.demo-ha.yml').read_text()
assert ha.index('import /run/caddy-ha-routes/*.caddy') < ha.index('handle @designs')
assert 'handle_path /demo-ha/*' in route
assert 'reverse_proxy demo-ha-gateway:8080' in route
assert '8123' not in route and 'homeassistant:' not in route
assert './demo-ha.caddy:/run/caddy-ha-routes/demo-ha.caddy:ro' in overlay
assert 'networks: [default, demo-ha]' in overlay
assert 'external: true' in overlay and 'name: public-demo-ha_edge' in overlay
# The base stack must not enable a backend before it has been privately provisioned.
assert 'demo-ha-gateway' not in (root / 'docker-compose.yml').read_text()
print('OK: optional demo route targets only the facade and preserves existing networking')
PY
