#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
umask 077
if [[ ! -f .env ]]; then
  {
    printf 'UMAMI_DB_PASSWORD=%s\n' "$(openssl rand -hex 32)"
    printf 'UMAMI_APP_SECRET=%s\n' "$(openssl rand -hex 32)"
    printf 'UMAMI_TWO_FACTOR_KEY=%s\n' "$(openssl rand -hex 32)"
  } > .env
fi
docker compose up -d --wait --wait-timeout 300
python3 bootstrap.py
