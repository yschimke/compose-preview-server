# Umami for the preview deployment

Umami 3.4.0 and PostgreSQL run as a separate Compose project on the preview box. They survive
preview rollouts. The database has a persistent volume and no host port. The dashboard binds only
to `127.0.0.1:3001`; the existing Caddy exposes exactly `/__analytics/script.js` and
`/__analytics/api/send`, so this needs no new DNS record. No replay recorder is installed.

## Install

Copy this directory to a permanent location on the box, for example `/opt/compose-preview-analytics`.
The existing preview stack must already own the `compose-preview_default` network (override
`PREVIEW_NETWORK` if necessary). Run `./setup.sh`. It generates three secrets in `.env`, starts
the containers, changes the default administrator password, and creates separate preview and
documentation websites. Rerunning it preserves secrets and website IDs. The generated `.env`,
`admin-password` and `websites.json` are private files and never committed or printed.

Deploy `deploy/image/Caddyfile` through the existing Caddy image workflow. Until that proxy change
is running, the collector has no public route. Its dashboard stays private even after the change.

For administration:

```sh
ssh -L 3001:127.0.0.1:3001 preview
# Open http://localhost:3001; username admin, password from admin-password on the box.
```

For the preview stack, set these in its existing `.env`, then roll the preview service using its
normal deployment mechanism after the server release containing the integration:

```dotenv
SERVE_UMAMI_ENABLED=1
SERVE_UMAMI_URL=https://preview.coo.ee/__analytics
SERVE_UMAMI_WEBSITE_ID=<preview.coo.ee entry from websites.json>
```

For GitHub Pages, set `UMAMI_ENABLED=1`, `UMAMI_URL` to that same URL and `UMAMI_WEBSITE_ID` to the
`yschimke.github.io` entry as **Actions variables** on compose-ai-tools; run its Pages workflow
after the matching site PR lands. IDs are public configuration, not API credentials.

## Events and verification

The shared browser wrapper records page views, `navigation` (destination category only), and
`renderer_changed` (renderer enum). It removes URL queries/fragments, replaces builder design
paths with `/ui-builder/`, and never sends titles, referrers or arbitrary event properties.
It honors DNT/GPC and Umami's `umami.disabled` local-storage opt-out. Other server installations
send nothing unless `SERVE_UMAMI_ENABLED=1` (or `true`) and both configuration values are supplied.
The backend is not installed by the default preview stack; only this separate deployment starts it. Frames do not double-count views.

The hosted Wasm editor shell gets page views and `window.previewTelemetry.track(name, json)`;
fine-grained Compose actions are **not yet instrumented**. The bridge accepts `component_added`,
`property_changed`, `undo`, `redo` and `code_exported`, with arbitrary payload values discarded.
A future builder release can call it through Kotlin/Wasm interop without a vendor SDK dependency.

Verify a production visit and a renderer switch appear in Umami's dashboard, then inspect the
browser's `/__analytics/api/send` requests to confirm a `?token=...` never enters the payload.
Test the documentation site separately. Blocking the collector must leave navigation and renders
working. `GET /__analytics/login` must not expose the Umami dashboard.

## Operations

Run `./backup.sh` daily from the host's scheduler and copy the dumps **off the box** with the
existing backup system. Back up `.env` and `admin-password` through the secret backup process too.
The script writes an atomic custom-format PostgreSQL dump under `backups/`; retention is the
operator's choice. Validate backups with `pg_restore --list` and rehearse a restore into a separate
database before relying on them. Example restore into an empty replacement database:

```sh
docker compose exec -T umami-db pg_restore -U umami -d umami < backups/umami-TIMESTAMP.dump
```

Upgrade by changing the image pin, taking a backup, then `docker compose up -d --wait`.
Do not downgrade an image after schema migrations without restoring the matching database backup.
Disable collection with `SERVE_UMAMI_ENABLED=0` and the Pages variable `UMAMI_ENABLED=0`,
then redeploy those frontends. URL/ID settings can remain for future re-enablement. `docker compose stop` stops analytics without touching previews
or deleting data; never use `down -v` as a rollback.
