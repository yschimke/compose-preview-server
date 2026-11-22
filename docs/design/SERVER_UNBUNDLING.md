# Unbundling the server

**Status: accepted, in progress.** The target is that the UI builder and the hosted MCP each reach a
deployment on their own release trains. Neither waits for a compose-preview-server release, and
this repository stops building their containers. This page records the end state, the contracts it
needs, and the order of the work. Each step links the pull requests that carry it out.

## Why

Today a UI-builder change reaches preview.coo.ee like this: compose-ui-builder releases, Renovate
bumps `composeai-ui-builder` here, this repository releases, `preview-host-image.yml` builds
`compose-preview-ui-builder` (the server image with `SERVE_ROLE=ui-builder`) tagged with *this*
version, and Watchtower rolls it. The UI builder already left the repository as source. It is still
in this repository as a deployable:

- `:server` links `ui-builder-runtime`, `-export` and `-render-bundle`, and the server archive
  carries the editor.
- About 35,000 lines of `ServeUiBuilder*` / `Design*` route code live in `:server`: 101 of the 261
  files in its main source set, and 146 of the 261 mention the builder.
- The `ui-builder` container accepts `preview`'s session cookie only because it is the same binary
  with the same `SERVE_GITHUB_AUTH_COOKIE_SECRET`.
- The hosted `/mcp` (`ServeCatalogMcp`, `ServeLibraryMcp`, about 4,600 lines) renders through this
  process's session registry, so it cannot leave either.

`watchface-push` shows the shape to copy. watchface-tools builds `watchface-push-server`,
Watchtower rolls it on its own, and `preview` reaches it at `WATCHFACE_PUSH_SERVER_URL`.

## End state

| Image | Built by | Contents |
| --- | --- | --- |
| `compose-preview-render-runtime` | compose-preview-daemon | JDK 21, both daemon sidecars, Android SDK, Robolectric `android-all`, Skiko native, baked fonts. No entrypoint |
| `compose-preview-host`, `compose-preview-playground` | this repository | `FROM` the render runtime, plus compose-ai-tools' `lib-rcjvm`/`lib-bta`/`lib-simulator`/`lib-dex2jar`, plus the server |
| `compose-ui-builder-server` | compose-ui-builder (`:ui-builder-server`) | The UI builder's own Ktor host: `/ui-builder`, `/api/ui-builder/*`, `/mcp/ui-builder`. A JRE image while it only fronts the legacy container; `FROM` the render runtime once export moves |
| `compose-preview-catalog-mcp` | compose-preview-mcp | The hosted catalog MCP at `/mcp`, rendering through the server's HTTP API |
| `compose-preview-livery-host` | this repository, later yschimke/livery | Unchanged until `:server` stops linking `:livery-host` |
| `compose-preview-caddy` | this repository | The generic edge stays here; deployments mount their own fragments |
| `watchface-push-server` | watchface-tools | Already external |

When this is done, `:server` has no UI-builder jar on its classpath. `/ui-builder*` on a
single-container deployment is served by running the UI-builder host beside it, as `ui.coo.ee`
already is.

## Contracts

Each contract goes in [compose-preview-contracts](https://github.com/yschimke/compose-preview-contracts)
as `api/deployment-protocol`, shape only. Each one is written to match what this server already
sends, so today's server conforms without a change.

| Contract | Replaces | State |
| --- | --- | --- |
| Deployable endpoints: `/healthz`, `/readyz`, `/version` (with `component` and `apis`) | The convergence loop in `preview-host-image.yml` and `checks.txt` assuming every container is this server | compose-preview-contracts#236 |
| Release manifest: component, version, `provides`/`requires` API levels, asset digests | The editor archive's one-off `ui-builder-web.json` `serverApi` | compose-preview-contracts#236 |
| Session check: forward the browser's cookie to the auth host's `/auth/github/whoami` | Sharing `SERVE_GITHUB_AUTH_COOKIE_SECRET` and the session fingerprint across containers | compose-preview-contracts#236. ha-designs already does exactly this |
| Catalog read API: catalog list, revisions, component records, pinned revision | Each container loading every catalog from `SERVE_CATALOGS` into a shared cache volume | named, not yet typed |
| UI-builder HTTP routes (`/api/ui-builder/*`, OpenAPI): the editor's and the REST routes ha-designs calls | The single integer `SUPPORTED_SERVER_API` | named, not yet typed; next, because it has two clients |
| Render/export request | `ServeUiBuilderRenderPort` adapting onto this process's render host | named, not yet typed |

## Steps

### 1. Editor releases deploy as config (done)

The `editor` pin (#1035) already lets a box serve a compose-ui-builder release that differs from
the bundled one. preview.coo.ee did not use it. Now:

- compose-ui-builder's release sends `compose-ui-builder-released` to preview-coo-ee
  (yschimke/compose-ui-builder#1370).
- preview-coo-ee's `track-ui-builder-editor.yml` pins the release and opens the deploy PR, with
  auto-merge available as an opt-in. Its `ui-builder` service gets its own `UI_BUILDER_IMAGE_TAG`
  (yschimke/preview-coo-ee#69).

This covers editor-only changes. The runtime, export and render-bundle jars still ship with this
server until step 3.

### 2. A base image below both hosts

compose-preview-daemon publishes (yschimke/compose-preview-daemon#511) `compose-preview-render-runtime` on each release, and its layout
is a documented contract. Then `deploy/image/Dockerfile` builds `runtime` `FROM` it (an
`ARG RENDER_RUNTIME_IMAGE` pinned to the `composeai-preview-daemon` version, so the image and
`gradle/libs.versions.toml` cannot drift). It drops its own `android-daemon`, `desktop-daemon`
(daemon half) and `fonts` stages, and keeps the compose-ai-tools sidecars and the server. The
compose-ai-tools sidecars stay out of the base because compose-preview-daemon may not reach that
repository.

Acceptance: the `host` image's `/opt` tree and `JAVA_TOOL_OPTIONS` are unchanged; diff them by
`docker run … find /opt -maxdepth 1`. `check-skiko-pairing.sh` passes. The convergence loop is
green on a real rollout.

### 3. The UI builder hosts itself

A Ktor host module in compose-ui-builder, beside `ui-builder-host-jvm`, which already does sessions,
packaged catalogs and in-process export for the desktop app and IntelliJ. compose-ui-builder
publishes its image `FROM` the render runtime, and preview-coo-ee points `ui-builder` at it. Then
the routes move, as reviewable moves: one group per pull request, package names unchanged (the
lesson in [PREVIEW_SERVER_SPLIT.md](PREVIEW_SERVER_SPLIT.md)).

The seams the moved code must stop calling, and what each becomes:

| Used by the UI-builder files today | Becomes |
| --- | --- |
| `UiBuilderAuthorizationDecision`, `ServeMachineAuthorization`, `ServeGithubAuth` (`serve-auth`) | Session check against `preview`'s whoami. Agent grants are verified the same way, or the grant store moves with the builder |
| `McpRequestException`, `ServeCatalogMcp` request plumbing | The host's own MCP layer (the plumbing is about 300 lines in `ServeMcpRequestScopes.kt`; copy it rather than share it) |
| `ServeCatalogsConfig`, `ComponentRecordSource`, `ServeCatalogGuidelines` | Catalog read API, or the builder's existing `PublishedCatalogStore` reading catalogs where they publish |
| `ScreenGeneratorComposeExportExecutor`, `ExportOverloadedException`, `ServeContactSheet`, `ServeImageFormats` | Export in the builder's own process, on the render runtime's daemons (what `ui-builder-host-jvm` already does), or the render/export request against `preview` |
| `WatchFacePush*` | `WATCHFACE_PUSH_SERVER_URL`, already HTTP |
| `ServeRateLimiter`, `ServeOpenAiForms`, `ServeChatThreadLinks` | Copied; they are small and have no state worth sharing |

**The builder host goes in front, and the server stays behind it until it is empty.**
`compose-ui-builder-server` (yschimke/compose-ui-builder#1372) answers the deployable endpoints
itself and forwards every other request, WebSockets included, unchanged (`Host`, cookies,
`X-Forwarded-*`) to today's `SERVE_ROLE=ui-builder` container. Caddy points the UI-builder paths
at it once. After that, moving a route group is one compose-ui-builder pull request: the group is
handled there ahead of the proxy. No release here and no Caddy change is needed, and there is no
flag day.

Move order, smallest coupling first:
1. the read-only stores and routes (folders, links, references, comments, reviews, templates,
   trash);
2. projects and the design service;
3. export, native preview and DWF;
4. the editor pages. They are rendered inside `ServeHttpServer.kt` with per-host configuration
   injected, not served as static files, so they come after the routes they call;
5. the MCP doors.

When nothing is forwarded any more, the legacy container is removed, `SERVE_UI_BUILDER_EXTERNAL=1`
becomes the only mode, and the jars leave `:server`.

### 4. The hosted MCP moves to compose-preview-mcp

Once the catalog read API and the render request are typed, `ServeCatalogMcp` and
`ServeLibraryMcp` move to compose-preview-mcp as a Streamable HTTP server and image. It renders
through `preview`'s HTTP API instead of its session registry, and Caddy routes `/mcp` to it.
`/mcp/ui-builder` has already gone with step 3. Two more things come out of the MCP repository's
dependency on this one:

- The viewer (`compose-preview-viewer.html`, `preview-library.html`) is versioned like the editor:
  its own release asset with a digest, fetched by every host that serves it. A viewer fix then no
  longer needs a release here.
- The `compose-preview-mcp-<v>.tar.gz` bridge on this repository's releases is retired once the
  CLIs from before compose-ai-tools#5929 fall out of support.

### ha.coo.ee

ha.coo.ee is the Home Assistant card designer. Its own server, `ha-designs`, is already outside
this repository (yschimke/homeassistant-remotecompose `design-server`, image
`ha-card-design-server`, rolled by Watchtower). It is not a candidate for moving. It is the first
client of these contracts other than this server, and that changes their priority:

| ha-designs uses | Today | After |
| --- | --- | --- |
| Sign-in (`HOST_SIGN_IN_URL`) | Forwards the browser's cookie to `preview`'s `/auth/github/whoami`, `/auth/github/start` and `/logout` | The same calls, typed by `SessionWhoamiV1` (compose-preview-contracts#236). It is already the pattern the contract describes |
| The UI builder (`UI_BUILDER_URL`, `UI_BUILDER_TOKEN`, `UI_BUILDER_ADMIN_READ_TOKEN`) | About ten `/api/ui-builder/v1/*` routes (designs PUT/GET, `export.rc`, `documents/export.rc`, `requests`, `identity`) plus `/admin/ui-builder/designs`, against `preview` | Pointed at the UI-builder host (`http://ui-builder-server:8080`) once that fronts the routes. The editor↔host route contract has to cover these REST routes and not only the editor's, because this is a second client written in another repository |
| Browser links | `https://ha.coo.ee/ui-builder/<id>`, routed by `ui-builder.caddy` (`/run/caddy-ha-routes`) | Unchanged paths. Caddy sends them to the UI-builder host |
| Everything else on ha.coo.ee | `ha.caddy`'s final `handle` sends every path ha-designs does not own to `preview` | Narrowed to `/auth/github/*` and the shared static assets once the builder routes move, so ha.coo.ee no longer depends on the catalog server's surface |

ha-designs can also launch a UI builder of its own (`UI_BUILDER_BINARY`, `--ui-builder-state-dir`),
which today means spawning compose-preview-server's distribution. The UI-builder host's
distribution replaces that, so a standalone HA deployment has no compose-preview-server in it at
all.

### 5. Smaller removals

- The in-process `:watchface-push` / `:watchface-engine` fallback (about 6,400 lines), once every
  documented deployment runs `watchface-push-server`. That includes `deploy/example`, which has to
  gain the service first.
- `:livery-host` to yschimke/livery, once `:server` reaches it over HTTP instead of linking it.
- `serve-web` and `wasm-ui` are browser applications. They can ship as digest-pinned assets the
  way the editor does, if their release cadence starts to differ from the server's.

## What does not change

- The single-container story for a self-hosted deployment: `deploy/example` still runs with one
  `docker compose up`, with the UI-builder host as a second service rather than a role of the
  first.
- The layer rule. Every new edge points down: render runtime (1a) ← server and UI builder;
  contracts (0) ← everything.
