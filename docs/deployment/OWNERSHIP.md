# Deploying your own preview server

This repository publishes two images on every release, and everything you need to run them
yourself:

- `ghcr.io/yschimke/compose-preview-host` — the server, with the released distribution and its
  live-render sidecars baked in. No Gradle build runs on the host.
- `ghcr.io/yschimke/compose-preview-caddy` — an optional `caddy:2` edge with the stack's Caddyfile
  baked in (TLS, the credential-filtered access log, security headers, rollout-aware upstreams).

No hostname, account, catalog set or trust relationship of any particular deployment is part of
either image or of the files below. Those are yours to choose.

## Start from `deploy/example`

[`deploy/example`](../../deploy/example/README.md) is the adoption path: one Compose file, the
server on loopback, an optional plain Caddy for HTTPS, and three read-only configuration files —
`settings.json`, `catalogs.json` and `producers.json`. Copy the directory into your own repository
(private if you like), edit those files, pin `IMAGE_TAG` and run it. Every configuration change is
then a reviewed commit and a container recreate.

## Grow into `deploy/image`

[`deploy/image`](../../deploy/image/README.md) is the full-featured stack for the same image:
zero-downtime rollouts (`rollout`), an instant-roll webhook (`hook`), an auto-updating edge image
(`watchtower` + `compose-preview-caddy`), the sandboxed playground container, and sizing knobs for a
large host. `.env.example` there lists every variable with an `example.com` placeholder;
`docker-compose.deploy-config.yml` mounts your deployment directory's `catalogs.json` and
`producers.json` read-only (`DEPLOY_CONFIG_DIR`, default `../example`). Run it from a checkout of
this repository, or copy the directory and track upstream changes yourself.

## What goes where

| What | Where |
| --- | --- |
| Server behaviour, admin APIs, catalog delivery, the settings schema | This repository, in the released image |
| Non-secret settings (`settings.json`, keys in [`SETTINGS.md`](../../deploy/image/SETTINGS.md)) | Your deployment directory |
| Catalog inventory and trusted producers (`catalogs.json`, `producers.json`) | Your deployment directory |
| Hostnames, OAuth app, cookie domain, administrators, analytics ids | Your `settings.json` and `.env` |
| Secrets (`SERVE_ADMIN_TOKEN`, OAuth and cookie secrets, `DEPLOY_HOOK_TOKEN`) | An untracked `.env` or your secret store, never a repository |
| Other apps on the same machine | Caddyfile fragments mounted into `/etc/caddy/sites.d`, named in `EXTRA_SITE_DOMAINS` |

The image grants no administrator and gates GitHub sign-in on no repository by default: set
`uiBuilder.adminActors` and `SERVE_GITHUB_AUTH_REPO` explicitly when you enable sign-in.

## Extra sites on the same edge

The `compose-preview-caddy` image imports one Caddyfile fragment per extra hostname from
`/etc/caddy/sites.d`. Mount yours there from a Compose override, list the hostnames in
`EXTRA_SITE_DOMAINS` so the server's own block does not claim them too, and `import edge_common`
and `import crawler_block` inside each fragment to share the log, headers and crawler policy. See
"Extra sites on the same edge" in the [image README](../../deploy/image/README.md).

## Rolling out a release

The stack's `rollout` service polls GHCR and rolls `preview` with no downtime. To roll as soon as an
image is published instead, call your host's `POST /__hooks/rollout` with `DEPLOY_HOOK_TOKEN` from
whatever watches your images. A fork that builds its own image gets this from
`preview-host-image.yml` by setting the `DEPLOY_HOOK_URL` repository variable and the
`DEPLOY_HOOK_TOKEN` secret; without the variable that step does not run.
