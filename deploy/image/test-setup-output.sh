#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cp "${SETUP_FILE:-$here/setup.sh}" "$work/setup.sh"
cp "$here/env-migrations.sh" "$work/env-migrations.sh"
mkdir "$work/bin"
printf '#!/bin/sh\nexit 0\n' > "$work/bin/docker"
printf '#!/bin/sh\nexit 0\n' > "$work/bin/sudo"
chmod +x "$work/bin/"*
# Test both first boot and existing credentials. None of these values are real credentials.
for public in 0 1; do
  cat > "$work/.env" <<ENV
DOMAIN=preview.example.com
SERVE_PUBLIC=$public
SERVE_TOKEN=fixture-browse-value
DEPLOY_HOOK_TOKEN=fixture-hook-value
ENV
  output="$(PATH="$work/bin:$PATH" DOMAIN=preview.example.com bash "$work/setup.sh")"
  [[ "$output" != *fixture-browse-value* && "$output" != *fixture-hook-value* ]] || {
    echo 'FAIL: setup prints a credential'; exit 1;
  }
  [[ "$output" == *https://preview.example.com/auth/github/callback* ]] || {
    echo 'FAIL: setup omits OAuth guidance for an independent host'; exit 1;
  }
done
rm "$work/.env"
PATH="$work/bin:$PATH" DOMAIN=preview.example.com bash "$work/setup.sh" > "$work/output"
while IFS='=' read -r key value; do
  case "$key" in
    SERVE_TOKEN|DEPLOY_HOOK_TOKEN)
      ! grep -Fq "$value" "$work/output" || { echo 'FAIL: setup prints a generated credential'; exit 1; } ;;
  esac
done < "$work/.env"
echo 'PASS: setup supports independent hosts without printing existing or generated credentials'
