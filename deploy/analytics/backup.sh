#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
umask 077
mkdir -p backups
backup="backups/umami-$(date -u +%Y%m%dT%H%M%SZ).dump"
trap 'rm -f "$backup.tmp"' EXIT
docker compose exec -T umami-db pg_dump -U umami -d umami -Fc > "$backup.tmp"
mv "$backup.tmp" "$backup"
echo "$backup"
