Run a snapshot-only catalog server with an optional HTTPS edge. The only initial catalog is the
public Compose Material 3 reference catalog; replace `catalogs.json` and `producers.json` with your
own publications. Trust only repositories whose code you intend to run. Native execution is off.

```sh
cd deploy/example
cp .env.example .env
# Review IMAGE_TAG, then start on loopback:
docker compose -f compose.yml up -d preview
curl --fail http://localhost:8080/version
curl --fail http://localhost:8080/status.json
curl --fail http://localhost:8080/healthz
```

The first boot downloads catalog artifacts and can take several minutes. Inspect container logs and `/status.json` until the catalog reports loaded. `/healthz` checks the
listener; `/readyz` additionally probes native rendering and is intended for deployments enabling
that lane. Open http://localhost:8080/
and `/ui-builder/`. Browser-local designs require no OAuth; hosted ownership and native export
are separate capabilities. To check offline creation, load a published catalog in the builder,
create a browser-local design, go offline, reload, and create another design from cached catalog
templates. An uncached catalog needs a network connection. Do not clear browser storage.

For public HTTPS, point your domain's DNS at this machine, set `DOMAIN` in `.env`, open ports
80/443, and run `docker compose -f compose.yml --profile https up -d`. The server port remains on
loopback; Caddy handles HTTPS and WebSockets. No access logs are configured in this example.

Non-secret behavior belongs in `settings.json`; available keys are in
[SETTINGS.md](../image/SETTINGS.md). The three deployment files are read-only, authoritative mounts.
Admin edits cannot persist to them: edit the files and recreate the container. State, caches and
certificates use persistent named volumes. Back up state before upgrading; never use `down -v`
when retaining designs. Stop with `docker compose -f compose.yml down`.

For hosted designs, register your own GitHub OAuth app with callback
`https://<your-domain>/auth/github/callback`. Add its client ID and callback URL to
`settings.json` (`auth.github`), and pass `SERVE_GITHUB_AUTH_CLIENT_SECRET` and
`SERVE_GITHUB_AUTH_COOKIE_SECRET` into the service using a local secret mechanism. This minimal
Compose file intentionally does not forward them until you add those environment entries.
GitHub sign-in currently also requires `SERVE_GITHUB_AUTH_REPO`: replace this example's empty
value with your own `owner/repo`. This is the repository used by access checks, even when open
builder access is enabled. Choose `uiBuilder.adminActors` explicitly and remove the example's `SERVE_UI_BUILDER_ADMIN_ACTORS:
none` override when enabling them. The same repository gates playground access;
no operator repository is implicit in this example. Do not widen cookie scope unless you control all sibling hosts.

Native render/compile, uploads, MCP grants, analytics and external integrations are optional.
Enable each deliberately using the image settings reference and its feature documentation. This
example needs no Home Assistant service, Docker socket, auto-updater, deploy hook or analytics DB.
