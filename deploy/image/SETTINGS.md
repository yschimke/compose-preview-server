# Deployment settings reference

<!-- Generated from server/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeSettings.kt. Regenerate with UPDATE_SERVE_SETTINGS_REFERENCE=true on ServeSettingsTest. -->

Non-secret settings live in a deployment's `settings.json` (preview.coo.ee: [`deploy/preview.coo.ee/settings.json`](../preview.coo.ee/settings.json)), validated against [`settings.schema.json`](settings.schema.json) and published to the box through `PUT /admin/settings`. Precedence is built-in default, then `settings.json`, then the environment (`.env`): an `.env` line still wins, and `GET /admin/settings` says which source each value came from.

**live** settings take effect when published; **restart** settings are stored and apply at the next start. Secrets and machine sizing stay in `.env` (see README.md).

| Setting | Variable | Applies | Read by | Description |
|---|---|---|---|---|
| `uiBuilder.adminActors` | `SERVE_UI_BUILDER_ADMIN_ACTORS` | restart | preview | GitHub actors who administer every shared UI-builder design and its folder placement. They gain no catalog, trust or site authority. An empty list turns off the image's default (`github:yschimke`). |
| `uiBuilder.startUrl` | `SERVE_UI_BUILDER_START_URL` | restart | preview, playground | Where the UI builder's start page sends a visitor. |
| `uiBuilder.importOrigins` | `SERVE_UI_BUILDER_IMPORT_ORIGINS` | restart | preview | Sites whose seed documents a signed-in person may start a UI-builder design from, at `/ui-builder/designs/import?url=<seed URL on that origin>`. The design is created as that person, private to them; the site holds no credential on this server. Off when empty. |
| `uiBuilder.profile.host` | `SERVE_UI_BUILDER_PROFILE_HOST` | restart | preview | A white-labelled UI builder on one more hostname, whose edge sends only the builder's paths here: its pages keep `/ui-builder/`, carry `uiBuilder.profile.name`, link to no catalog or front door, and offer only `uiBuilder.profile.catalogs`. It narrows and rebrands, granting nothing; sign-in may return to it. Off when unset. Must not be SERVE_UI_BUILDER_HOST. |
| `uiBuilder.profile.name` | `SERVE_UI_BUILDER_PROFILE_NAME` | restart | preview | What the builder is called on `uiBuilder.profile.host`, in titles, headings and its web app manifest. Required with the host. |
| `uiBuilder.profile.catalogs` | `SERVE_UI_BUILDER_PROFILE_CATALOGS` | restart | preview | The UI-builder catalogs `uiBuilder.profile.host` lists and creates in; designs in any other are left off its lists, and creating one there is refused. Required with the host. |
| `uiBuilder.profile.accent` | `SERVE_UI_BUILDER_PROFILE_ACCENT` | restart | preview | The accent colour of `uiBuilder.profile.host`'s builder chrome. Optional. |
| `uiBuilder.profile.homeUrl` | `SERVE_UI_BUILDER_PROFILE_HOME_URL` | restart | preview | Where the logo and back links on `uiBuilder.profile.host` go. Optional; the builder's own home when unset. |
| `uiBuilder.hostRoot` | `SERVE_UI_BUILDER_HOST_ROOT` | restart | preview | Serve the UI builder at the root of SERVE_UI_BUILDER_HOST (`https://ui.coo.ee/<design>`) instead of under `/ui-builder/`, keeping every server route (`/start`, `/api`, `/mcp`, `/agent-access`, `/auth`, `/admin`, health and status, assets) on that host. Needs an editor bundle that reads the `ui-builder-base-path` meta. Ignored without SERVE_UI_BUILDER_HOST. |
| `uiBuilder.guidelines.model` | `SERVE_UI_BUILDER_GUIDELINES_MODEL` | live | preview | The OpenRouter model id the `guidelines` design check runs on, on this box's key. Default `deepseek/deepseek-v4.1-flash`. |
| `uiBuilder.guidelines.users` | `SERVE_UI_BUILDER_GUIDELINES_USERS` | live | preview | GitHub logins who may spend this box's OpenRouter key on the `guidelines` check. The check stays off unless this or `orgs` names someone. |
| `uiBuilder.guidelines.orgs` | `SERVE_UI_BUILDER_GUIDELINES_ORGS` | live | preview | GitHub organizations whose members may run the `guidelines` check. |
| `uiBuilder.guidelines.pictureBudgetSeconds` | `SERVE_UI_BUILDER_GUIDELINES_PICTURE_BUDGET` | live | preview | How long a guidelines prompt waits for frames it has no picture of yet. Default 45. |
| `uiBuilder.guidelines.triage` | `SERVE_UI_BUILDER_GUIDELINES_TRIAGE` | live | preview | Before a guidelines check, ask Jev which extra evidence would help (a dark render, a large-font render, the accessibility tree). Default `on`. |
| `cli.startUrl` | `SERVE_CLI_START_URL` | restart | preview, playground | Where /start sends a visitor on the catalog host: the CLI and project-previews guide. |
| `catalogs.registry` | `SERVE_CATALOG_REGISTRY` | restart | preview, playground | Catalog registries this box serves every catalog of, beyond `catalogs.json`, as `--catalog-registry` takes them. `none` turns it off. |
| `catalogs.mcp` | `SERVE_CATALOG_MCP` | restart | preview | Serve the remote catalog MCP endpoint. Needs agent grants. |
| `agents.grantCapabilities` | `SERVE_AGENT_GRANT_CAPABILITIES` | restart | preview | Capabilities an agent grant may be given, beyond the defaults. `images` is dropped unless the image lane is on. |
| `uploads.acceptDocs` | `SERVE_ACCEPT_DOCS` | restart | preview | Accept Remote Compose document uploads at `/d/`. |
| `uploads.acceptImages` | `SERVE_ACCEPT_IMAGES` | restart | preview | Accept image uploads. Left out, it is on exactly when `imageRepository` is named. |
| `uploads.imageRepository` | `SERVE_IMAGE_UPLOAD_REPO` | restart | preview, playground | The repository whose collaborators may upload images. Falls back to the sign-in repository. |
| `rendering.compileEngine` | `SERVE_COMPILE_ENGINE` | restart | preview | Run the playground's compile engine without mounting the playground page. |
| `rendering.rcDefaultPlayer` | `SERVE_RC_DEFAULT_PLAYER` | restart | preview | The Remote Compose player the viewer opens on, where a preview offers it. |
| `analytics.umami.enabled` | `SERVE_UMAMI_ENABLED` | restart | preview | Add the Umami analytics script to served pages. |
| `analytics.umami.url` | `SERVE_UMAMI_URL` | restart | preview | The Umami instance's origin. |
| `analytics.umami.websiteId` | `SERVE_UMAMI_WEBSITE_ID` | restart | preview | The Umami website id. |
| `auth.github.clientId` | `SERVE_GITHUB_AUTH_CLIENT_ID` | restart | preview, playground | The GitHub OAuth app's client id. Public by design; its client secret and the cookie secret stay in `.env`. |
| `auth.github.callbackBaseUrl` | `SERVE_GITHUB_AUTH_CALLBACK_BASE_URL` | restart | preview, playground | The origin GitHub redirects back to after sign-in. |
| `auth.github.cookieDomain` | `SERVE_GITHUB_AUTH_COOKIE_DOMAIN` | restart | preview, playground | The parent domain the sign-in cookies cover, so one sign-in covers every site host. `none` keeps them host-only. |
| `auth.github.openUiBuilder` | `SERVE_GITHUB_AUTH_OPEN_UI_BUILDER` | restart | preview | Let every signed-in GitHub account create, edit and export UI-builder designs. |
| `auth.github.scope` | `SERVE_GITHUB_AUTH_SCOPE` | restart | preview | The OAuth scope asked for. Only read-only identity scopes are accepted. |
