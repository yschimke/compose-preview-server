# Remote catalog MCP

**Status:** implemented behind `compose-preview serve --catalog-mcp`

The preview server can expose every registered catalog through one remote MCP endpoint. This covers
the catalog operations that make sense without a local checkout: discover previews, inspect their
metadata, read published PNG resources, render with overrides, and retrieve structured preview
data. Local source registration, file watching, builds, and daemon lifecycle remain local
`compose-ai-tools` responsibilities.

## Run it

The endpoint is opt-in and always requires agent grants, even when ordinary catalog pages are
public:

```shell
compose-preview serve \
  --catalogs /srv/catalogs.json \
  --github-auth-client-id "$CLIENT_ID" \
  --github-auth-client-secret "$CLIENT_SECRET" \
  --github-auth-cookie-secret "$COOKIE_SECRET" \
  --agent-grants \
  --agent-grant-scopes preview,live \
  --catalog-mcp
```

The container equivalent is `SERVE_CATALOG_MCP=1`; the existing agent-grant and GitHub auth
variables still configure the issuer and approver identity. `--catalog-mcp` without a working
`--agent-grants` lane is refused at startup rather than exposing an anonymous machine API.

Configure an MCP client with:

```text
URL: https://preview.example/mcp
Authorization: Bearer <short-lived grant>
```

`list_projects` discovers the current catalog set. Catalog-specific tools take `catalog` alongside
`previewId`, while resource URIs carry both values, so adding or retiring a catalog needs no MCP
client reconfiguration. The separate UI-builder MCP sidecar should use its configurable path (for
example `/ui-builder/mcp`) when both products share a hostname.

## Get a token

The client requests a grant through the existing device-style flow:

```http
POST /agent-access/request
Content-Type: application/json

{"scope":"live","label":"catalog MCP"}
```

It shows the returned approval URL and verification code to the user, then polls only at the
advertised interval. A signed-in GitHub user—or the operator-token holder on a private server—opens
the link and approves the requested scope and lifetime. The poll response returns the bearer once;
it expires automatically and can be revoked from `/status` or by its holder through
`POST /agent-access/revoke`.

Request `preview` for discovery and immutable published resources. Request `live` only when the
agent needs made-to-order rendering or data products; scopes are cumulative, so `live` includes
`preview`. Credentials belong in the MCP host's secret store or environment facility, never in a
URL or checked-in configuration.

An unauthenticated MCP request returns `401`, `WWW-Authenticate: Bearer`, and an
`X-Compose-Preview-Agent-Access` header naming the absolute grant-request URL. The JSON response
also contains that URL, allowing an MCP host to guide the user into the grant flow.

## MCP surface

The endpoint implements Streamable HTTP MCP protocol versions `2025-06-18` and `2025-03-26`.
Catalog calls are independent, so the server does not allocate sessions or advertise subscriptions:
JSON-RPC messages use `POST`, notifications receive `202 Accepted`, and optional `GET`/SSE and
`DELETE` operations return `405 Method Not Allowed`.

| Operation | Access | Purpose |
| --- | --- | --- |
| `status` | `preview` | Report readiness and the aggregate catalog set |
| `resources/list`, `resources/read` | `preview` | List and read published preview PNGs |
| `list_projects`, `list_previews` | `preview` | Discover catalogs and preview metadata |
| `render_preview` | `live` | Render with optional overrides; defaults to a token-frugal semantics/hash observation, with `observe=png` for pixels and `observe=svg` for the `compose/figma-svg` vector export |
| `list_data_products` | `preview` | Discover structured products exposed by previews |
| `get_preview_data` | `live` | Retrieve accessibility or Compose annotation data |
| `list-all-documentation`, `get-documentation-for-story` | `preview` | Storybook-MCP-compatible discovery aliases |
| `preview-stories` | `live` | Storybook-MCP-compatible preview rendering alias |

`observe=svg` returns the vector as SVG **source** in a `text` content block, not as a base64
`image` block with `mimeType: image/svg+xml`. The symmetry with `png` is tempting, but almost no MCP
client renders SVG from an image block, and a vector consumer — a Figma round-trip, a diff, a
DOM-capture tool — wants the markup. It is available only where the host advertises it
(`ServeHost.hasSvgExportFor`): a static bundle carrying `figma/<slug>.svg` vectors, or a
daemon-backed session that can export `compose/figma-svg`. A catalog with neither is refused by
name rather than reported as a missing preview. The lane shares the render semaphore with the PNG
lane, so it is metered identically and cannot become a second unmetered renderer.

Resource URIs use `compose-preview://catalog/<catalog>/<preview-id>`. Storybook-compatible ids are
qualified as `<catalog>::<preview-id>` so identical preview ids in different catalogs cannot
collide.

## Relationship to UI-builder MCP

These are separate MCP products with shared authentication:

| Surface | Endpoint/transport | Authorization | State model |
| --- | --- | --- | --- |
| Catalog MCP | `/mcp`, Streamable HTTP | `preview` / `live` scopes | Stateless aggregate catalog queries and renders |
| UI-builder MCP | UI-builder sidecar, configurable path such as `/ui-builder/mcp` | `ui-builder-read`, `ui-builder-write`, `ui-builder-export` capabilities | Stateful collaborative design session |

The UI-builder MCP remains a thin authoring adapter over the preview server's authenticated Design
API. It may keep a stateful MCP session because revisions and collaboration benefit from one. The
catalog endpoint has no authoring state and deliberately remains stateless. Both present the same
agent bearer to preview-server APIs, and both rely on the same authenticated-user grant approval,
TTL, audit, and revocation implementation.

## Security and capacity

- Browser-originated MCP calls must have an `Origin` matching the request host, limiting DNS
  rebinding attacks. Non-browser clients normally omit `Origin`.
- Request bodies are capped at 1 MiB and responses disable caching.
- Catalog leases protect a catalog while a request is in flight.
- Remote renders use the same server-wide semaphore and queue timeout as browser renders; enabling
  MCP does not create an unmetered rendering lane.
- Grant authorization is evaluated for every call, so expiry or revocation takes effect without an
  MCP-session teardown.
