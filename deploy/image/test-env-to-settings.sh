#!/usr/bin/env bash
# Guard: env-to-settings.sh drafts settings.json from a box's .env without ever emitting a secret.
#
# The fixture is preview.coo.ee's .env by NAME — every key the box sets, as of the move to
# settings.json — with made-up values. The real values never left the box; the names are enough to
# prove each one lands where it should: a managed setting in the draft, a catalogs.json setting
# pointed at, and everything else (secrets, machine sizing) left alone.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
command -v jq > /dev/null || { echo "SKIP: jq not installed"; exit 0; }

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT
cat > "${work}/box.env" <<'ENV'
# Secrets
SERVE_ADMIN_TOKEN=secret-admin-token-value
SERVE_TOKEN=secret-serve-token-value
DEPLOY_HOOK_TOKEN=secret-hook-token-value
SERVE_GITHUB_AUTH_CLIENT_SECRET=secret-oauth-client-secret
SERVE_GITHUB_AUTH_COOKIE_SECRET=secret-cookie-secret
SERVE_UI_BUILDER_GUIDELINES_OPENROUTER_KEY=secret-openrouter-key
SERVE_UI_BUILDER_GUIDELINES_GITHUB_TOKEN=secret-github-token
# Box and infrastructure
COMPOSE_PROFILES=playground
PREVIEW_MEM_LIMIT=40g
SERVE_JAVA_OPTS="-Dcomposeai.serve.example=1"
ROLLOUT_INTERVAL=1200
ROLLOUT_HEALTH_TIMEOUT=600
DOMAIN=preview.example.test
SITE_DOMAINS=m3.preview.example.test
PLAYGROUND_UPSTREAM=playground
SERVE_PLAYGROUND_SANDBOX=bwrap
SERVE_PLAYGROUND_SANDBOX_RO=/opt
SERVE_PLAYGROUND_EXTERNAL=1
SERVE_LIVE_SEATS=12
SERVE_PLAYGROUND_COMPILE_SLOTS=2
SERVE_CATALOG_CACHE_MAX_BYTES=8589934592
SERVE_BACKGROUND_RENDERS=2
SERVE_UI_BUILDER_HOST=builder.example.test
# Application behaviour
SERVE_UI_BUILDER_GUIDELINES_MODEL=deepseek/deepseek-v4.1-flash
SERVE_UI_BUILDER_GUIDELINES_USERS=yschimke
SERVE_UI_BUILDER_GUIDELINES_ORGS=
SERVE_UI_BUILDER_GUIDELINES_PICTURE_BUDGET=45 # the default
SERVE_UI_BUILDER_ADMIN_ACTORS=github:yschimke
SERVE_UI_BUILDER_CATALOGS=m3-catalog,remote-m3,wear-m3
SERVE_UI_BUILDER_NATIVE_CATALOGS=wear-m3=wear-m3-catalog
SERVE_UI_BUILDER_PUBLISHED_CATALOGS=m3-catalog
SERVE_UI_BUILDER_PACKS=confetti-mobile=mobile
SERVE_UI_BUILDER_START_URL=https://preview.example.test/ui-builder/
SERVE_CATALOG_MCP=1
SERVE_CATALOG_REGISTRY=yschimke/compose-preview-imports-out
SERVE_AGENT_GRANT_CAPABILITIES=images
SERVE_ACCEPT_DOCS=true
SERVE_ACCEPT_IMAGES=1
SERVE_IMAGE_UPLOAD_REPO=yschimke/compose-ai-tools
SERVE_COMPILE_ENGINE=1
SERVE_RC_DEFAULT_PLAYER=cmp-android
SERVE_SITES=m3.preview.example.test=m3-catalog
SERVE_UMAMI_ENABLED=1
SERVE_UMAMI_URL=https://umami.example.test
SERVE_UMAMI_WEBSITE_ID=00000000-0000-0000-0000-000000000000
SERVE_GITHUB_AUTH_CLIENT_ID=Iv1.example
SERVE_GITHUB_AUTH_CALLBACK_BASE_URL=https://preview.example.test
SERVE_GITHUB_AUTH_COOKIE_DOMAIN=preview.example.test
SERVE_GITHUB_AUTH_OPEN_UI_BUILDER=1
SERVE_GITHUB_AUTH_SCOPE="read:user read:org"
ENV

fail() { echo "FAIL: $*" >&2; exit 1; }

draft="$("${here}/env-to-settings.sh" "${work}/box.env" 2> "${work}/notes")"

grep -q secret <<< "${draft}" && fail "a secret value reached the draft"
grep -q secret "${work}/notes" && fail "a secret value reached the notes"

field() { jq -r "$1" <<< "${draft}"; }
[[ "$(field .uiBuilder.guidelines.model)" == "deepseek/deepseek-v4.1-flash" ]] || fail "model"
[[ "$(field '.uiBuilder.guidelines.users | join(",")')" == "yschimke" ]] || fail "users"
[[ "$(field .uiBuilder.guidelines.orgs)" == "null" ]] || fail "an empty .env line is not a setting"
[[ "$(field .uiBuilder.guidelines.pictureBudgetSeconds)" == "45" ]] ||
  fail "an inline comment must not reach the value"
[[ "$(field .catalogs.mcp)" == "true" ]] || fail "1 is true"
[[ "$(field .uploads.acceptDocs)" == "true" ]] || fail "'true' is true"
[[ "$(field .auth.github.scope)" == "read:user read:org" ]] || fail "quotes are stripped"
[[ "$(field '.analytics.umami.url')" == "https://umami.example.test" ]] || fail "umami url"
grep -q 'SERVE_UI_BUILDER_CATALOGS is managed in catalogs.json' "${work}/notes" ||
  fail "a catalogs.json setting was not pointed at"
grep -q 'SERVE_SITES is managed in catalogs.json' "${work}/notes" || fail "SERVE_SITES not pointed at"

# Every infrastructure name stays out of the draft.
for name in PREVIEW_MEM_LIMIT SERVE_LIVE_SEATS SERVE_PLAYGROUND_COMPILE_SLOTS SERVE_BACKGROUND_RENDERS \
  SERVE_UI_BUILDER_HOST DOMAIN; do
  grep -q "${name}" <<< "${draft}" && fail "${name} reached the draft"
done

# The draft is a valid document for the deployment's own validator to read: same keys the schema has.
jq -e --slurpfile schema "${here}/settings.schema.json" '
  [paths(scalars or (type == "array")) | map(select(type == "string"))] as $set
  | [$schema[0] | paths(type == "object" and has("x-env")) | map(select(. != "properties"))] as $known
  | all($set[]; . as $p | ($p == ["$schema"]) or ($known | index([$p])))' <<< "${draft}" > /dev/null ||
  fail "the draft names a key the schema does not"

echo "PASS: env-to-settings drafts the managed settings and nothing else"
