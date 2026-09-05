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

### …or ask from inside the protocol

The 401 above tells a client where to go; `request_access` and `poll_access` let it go there without
leaving MCP. They mirror `POST /agent-access/request` and `POST /agent-access/poll` exactly — the
same JSON bodies, the same per-address rate limit, the same two secrets — so an agent that has one
transport does not need the other:

1. `tools/call request_access` (optionally `scope`, `ttlSeconds`, `capabilities`, `label`) returns
   `approveUrl`, `userCode` and the `deviceSecret` to keep.
2. The client shows the **link and the code** to its human, who opens the page and checks the code
   matches before approving.
3. `tools/call poll_access` with `requestId` + `deviceSecret` answers `approved` with the bearer.
   It **waits** for the decision rather than answering `pending` straight away, because every poll
   here is a tool call through a model. `waitSeconds` defaults to 8 — inside a conservative
   client's read timeout — and may be raised to 30 by a client that tolerates longer calls; a wait
   that times out answers `pending` and you simply call again.

**`initialize`, `ping`, `tools/list` and these two tools need no credential**; everything that reads
a catalog still does. The gate is per message, not per endpoint, because a client that cannot finish
`initialize` cannot reach the tool that asks for a credential either — the endpoint was a dead end
for exactly the agent the grant flow exists to serve. Anything the server does not recognise is
gated: a tool added later is closed until someone deliberately opens it.

This is also the recovery path when a token stops working mid-task. Grants live in memory
(`ServeAgentGrantStore`: *"a restart drops every request and every grant"*), so a redeploy of the
host invalidates every bearer regardless of its remaining TTL. A client that meets a sudden 401 asks
for a new grant the same way it asked for the first.

## MCP surface

The endpoint implements Streamable HTTP MCP protocol versions `2025-06-18` and `2025-03-26`.
Catalog calls are independent, so the server does not allocate sessions or advertise subscriptions:
JSON-RPC messages use `POST`, notifications receive `202 Accepted`, and optional `GET`/SSE and
`DELETE` operations return `405 Method Not Allowed`.

| Operation | Access | Purpose |
| --- | --- | --- |
| `initialize`, `ping`, `tools/list` | none | Handshake and discovery; reads no catalog |
| `request_access`, `poll_access` | none | Obtain a grant without leaving MCP (above) |
| `status` | `preview` | Report readiness and the aggregate catalog set |
| `resources/list`, `resources/read` | `preview` | List and read published preview PNGs |
| `list_projects`, `list_previews` | `preview` | Discover catalogs and preview metadata |
| `render_preview` | `live` | Render with optional overrides; defaults to a token-frugal semantics/hash observation, with `observe=png` for pixels and `observe=svg` for the `compose/figma-svg` vector export |
| `render_matrix` | `live` | Render one preview across a cross-product of override axes in a single call |
| `list_devices` | `preview` | The `device` override's accepted vocabulary, with each frame's dp size and density |
| `history_list` | `preview` | One preview's render timeline |
| `history_diff` | `preview` | Compare two of its recorded renders |
| `history_read` | `preview` | One historical render's pixels, by commit or blob |
| `diff_semantics` | `live` | Compare two previews' semantics by authored `testTag` |
| `list_data_products` | `preview` | Discover structured products exposed by previews |
| `get_preview_data` | `live` | Retrieve accessibility or Compose annotation data |
| `list-all-documentation`, `get-documentation-for-story` | `preview` | Storybook-MCP-compatible discovery aliases |
| `preview-stories` | `live` | Storybook-MCP-compatible preview rendering alias |
| `ui_builder_list_catalogs`, `ui_builder_list_designs`, `ui_builder_get_design` | `ui-builder-read` | The component catalogs a design can pin to, the designs on this box, and one design's whole document |
| `ui_builder_create_design`, `ui_builder_apply` | `ui-builder-write` | Create a design, and apply `DesignMutationV1` operations to one |
| `ui_builder_await_design` | `ui-builder-read` | **Wait** for somebody else to change a design, and return what they changed |
| `ui_builder_export` | `ui-builder-export` | Export a design — `compose` returns the generator's Kotlin, or diagnostics naming each reason it refused |
| `ui_builder_list_comments`, `ui_builder_await_comments` | `ui-builder-read` | Read a design's discussion, and **wait** for the next thing said in it |
| `ui_builder_post_comment`, `ui_builder_resolve_comment_thread` | `ui-builder-write` | Say something on a design, and close a thread once it is answered |

The `ui_builder_*` tools appear in `tools/list` only on a box that actually serves a UI builder
(`--ui-builder-dir`). A box without one does not advertise them, because listed-and-failing tells an
agent this server can do something it cannot.

### Authoring a design over MCP

The tools are a typed door onto the same `UiBuilderServicePort` the browser's Design API calls, so
the reply is the released `McpResponseEnvelopeV1` and the request shapes are the released
`UiBuilderRequestV1` ones. A session looks like:

1. `ui_builder_list_catalogs` — a document's `catalogPin` names a catalog revision the service
   checks, so this is where a real one comes from.
2. `ui_builder_create_design` — with a whole `document`, or `fromDesignId` to copy an existing
   design. There is no "blank template" argument: a starter document assembled inside the server
   would carry a pin invented there, and the service would reject it. A copy carries a pin that is
   real by construction.
3. `ui_builder_get_design` — read the `revision` to quote next. `baseRevision` is how a concurrent
   edit is detected, so an agent that guesses it *is* the concurrent edit.
4. `ui_builder_apply` — `operations` is an array of `DesignMutationV1`: `insertNode`, `setProperty`,
   `deleteNode`, `moveNode` and the rest. `operationId` is yours, and makes a retry idempotent.
5. `ui_builder_export` — the Kotlin, or the refusals.

### Watching, rather than asking again

Two tools block instead of returning at once: `ui_builder_await_design` waits for the design to move
past a `lastSequence` you quote, and `ui_builder_await_comments` waits for the discussion to move
past a `sequence` you quote. Both return the moment a designer in the browser or another agent does
something, and answer a `timedOut` reply when nothing happens within `waitSeconds`, which you act on
by calling again with the same cursor. `ui_builder_await_design` replies with the released
`DesignUpdateEnvelopeV1` — the identical frame the browser's own `/updates` socket receives.

**Why a blocking call and not an MCP notification.** MCP has server-to-client notifications, and this
endpoint deliberately cannot send one: `/mcp` is stateless JSON-RPC, `GET /mcp` — the
Streamable-HTTP listening stream a notification travels on — answers `405`, and `initialize`
advertises `resources: {"subscribe": false}` rather than claiming otherwise. Honouring
`resources/subscribe` would mean session ids, a per-session SSE stream, resumability and
server-held subscription state: a stateful transport, which is the property this endpoint is built
not to have. A call that blocks needs none of it, and it is the shape `poll_access` already uses
here.

Presence never wakes `ui_builder_await_design`. Who is looking at a design, and what they have
selected, is excluded by design from the document, the revision and the durable sequence; waking an
agent because a colleague moved their cursor would spend a tool call on something with nothing to
act on.

Two things are deliberately **not** taken from the message. The command's nested `actorId` is filled
from the presented grant, because `UiBuilderProtocolMapper` rejects a command whose actor is not the
authenticated one and the point of that check is that a caller does not choose. And the capability
is checked per tool against the same `UiBuilderRouteCapability` mapping the HTTP routes use, off the
call the credential arrived on — the gate an agent reaches is the gate a person reaches.

`observe=svg` returns the vector as SVG **source** in a `text` content block, not as a base64
`image` block with `mimeType: image/svg+xml`. The symmetry with `png` is tempting, but almost no MCP
client renders SVG from an image block, and a vector consumer — a Figma round-trip, a diff, a
DOM-capture tool — wants the markup. `list_previews` reports it per preview as `svgAvailable`, so the lane is discoverable without
asking for it and reading the refusal. It is available only where the host advertises it
(`ServeHost.hasSvgExportFor`): a static bundle carrying `figma/<slug>.svg` vectors, or a
daemon-backed session that can export `compose/figma-svg`. A catalog with neither is refused by
name rather than reported as a missing preview. The lane shares the render semaphore with the PNG
lane, so it is metered identically and cannot become a second unmetered renderer.

### History

`history_list` answers in one of three `mode`s, and the field is load-bearing: the three are not
interchangeable, and an agent that could not tell them apart would read "no versions" as "this
preview has never changed".

| `mode` | When | What comes back |
|---|---|---|
| `published` | the catalog was fetched from a delivery branch | `manifestUrl`, `repo`, `branch`, and `renderUrlTemplate` |
| `local` | project mode — `serve` against a checkout | the timeline inline, each version carrying a `renderUrl` |
| `none` | an uploaded bundle with neither | a `reason`, not an empty list |

**`published` answers from the copy the load already holds.** `ServeCatalogStore` fetches
`history.json` from the same immutable tree as `catalog.json` — the load is pinned to one commit by
construction — and parses it into the bundle host. So the timeline is in memory, describes exactly
the catalog being served, and is reported with the `pinnedCommit` it belongs to. There is no
independent staleness to manage: history is as fresh as the catalog it describes.

Answering inline rather than by URL is not a convenience. `m3-catalog`'s manifest is **1,008,000
bytes** across 1336 previews; the slice describing one preview is **497 bytes**. Sending a caller to
fetch the whole document to read one row is a 2000:1 overfetch, and it assumes the caller can reach
`raw.githubusercontent.com` at all — which an agent behind an allowlist often cannot, even while the
MCP endpoint is reachable. `manifestUrl` is still returned for a caller that wants the whole
catalog's timeline, and each version carries the `renderUrl` serving those exact bytes.

A publisher that ships no `history.json` keeps the URL-only answer as the degraded path.

In `local` mode the timeline comes from [`ServeProjectHistory`], derived from the checkout's own
delivery-branch commits and memoised per refresh window because one `git log --raw` over the branch
is ~1.6s. Each version links to this server's content-addressed `/history/render/<blob>.png` lane,
which only ever serves blobs the timeline already names.

Delivery provenance wins over a local checkout where a deployment somehow has both: a catalog
fetched from a delivery branch has already published what it rendered, and that is the truth about
it rather than whatever the serving box's clone happens to contain.

A timeline is not a commit list. Adjacent commits whose render bytes are identical collapse into one
version, and a preview that keeps returning to a render it had already moved away from is reported
`unstable` with a `flapCount` rather than as a preview with hundreds of changes — on the measured
branch, five such previews accounted for a 40% reduction in entries.

### Comparing and reading historical renders

`history_diff` compares two of a preview's recorded renders, defaulting to the two newest — *did the
last publish move this preview?* It is a **metadata** comparison: the timeline's versions are
already collapsed distinct renders, so whether the bytes changed is answered by their content ids
without fetching either image on either side.

It reports `unstable` alongside, and says so explicitly when set. That is the point of having it:
on a preview that re-renders differently on publishes that did not change it, a byte difference is
not evidence of a real change — the same question `flake-triage` otherwise settles with a
repeat-render oracle, answered here from precomputed data.

`history_read` returns one historical render's pixels through this server, addressed by `commit` or
`blob` (a prefix is enough). `preview` scope rather than `live`, matching the HTTP permalink lane:
it replays already-published bytes and commissions no render. It is still bounded — the published
lane goes through the bundle host's pinned-fetch permit and its miss cache, and the project-mode
lane only ever serves blobs the timeline already names. A timeline that names a version the branch
will not hand over is reported as such, distinctly from a version that does not exist.

### The full-page scroll lanes

`observe=scroll-png` and `observe=scroll-svg` return `render/scroll/long` and
`compose/figma-svg-long` — the whole scrollable screen (a virtualised `LazyColumn` re-rendered at an
expanded viewport so every row composes) rather than the viewport crop. Both are gated on
`ServeHost.hasScrollExportFor` and refused by name where absent, because the tall re-render needs a
daemon and a static bundle has no scroll producer. `list_previews` reports `scrollAvailable` per
preview beside `svgAvailable`. A non-scrolling preview yields its ordinary viewport output.

### Devices

`list_devices` publishes the `device` override's accepted vocabulary from `DeviceDimensions`, the
same catalog the render path resolves against — no geometry is authored in the MCP layer. The tool
exists because an unrecognised `device` value is **not** an error on the render path: it falls
through to the default frame, which from the caller's side is indistinguishable from a device that
happens to render identically to the default.

### Comparing two previews

`diff_semantics` compares two previews' semantics and reports tags present on only one side, tags
whose bounds moved, and tags whose occupancy `count` changed.

Identity is the authored `testTag`, deliberately, and not a `SemanticsRefs` ref. A ref indexes
siblings sharing an anchor — `r/role:Button[0]` means "the first Button under this parent" — so
inserting a Button ahead of it silently retargets the same string at different pixels, and a diff
built on refs would report "unchanged" for exactly the edit a reader most needs to see. A `testTag`
either survives an edit or stops resolving, and both are reported. A `count` change is reported
separately from a move: a tag carried by two nodes is no longer an identity anything can resolve,
which is a different event from the same node shifting. Two previews carrying no tags at all get an
explicit note rather than an `identical` verdict they did not earn.

### Knowing whether an override landed

Every render observation carries `generation` — the [`RenderOutcome.Generation`] wire name saying
what produced the bytes. When the call also supplied `overrides`, it carries `requestedOverrides`
and `overridesApplied` beside it, and a `baked` generation sets `overridesApplied: false` with an
`overridesIgnoredReason`: the published bundle has no renderer, so those overrides are *not*
reflected in the returned bytes. Without this a caller cannot distinguish an override that applied
and moved nothing from one that was never honoured — two overrides producing byte-identical PNGs is
the normal case, not the pathological one.

`observe=png` keeps its bare single-image reply for an override-free browse and gains a second
`text` block carrying the same provenance once `overrides` is non-empty, so the diagnostic rides
along with the pixels rather than costing a second render.

Unknown override **keys are refused** here, unlike on `GET /render` where they are ignored so a URL
may carry a cache-buster or an analytics tag beside the axes. An MCP `overrides` object has no such
passengers: every key was typed on purpose, so an unrecognised one is a caller error, and the error
lists the supported keys.

### Rendering a matrix

`render_matrix` takes an `axes` object mapping an override key to the values to sweep, renders the
cross-product, and reports one cell per combination with its overrides, `sha256`, dimensions and
`generation` (`observe=png` adds base64 pixels per cell). The base `overrides`, if given, are the
floor each cell starts from; an axis value with the same key wins for that cell.

`distinctRenders` counts the distinct hashes over the whole matrix — the single number that answers
"do these axes move the pixels at all". Cells are capped at 24 per call and the cap is enforced
before any rendering, since it exists to bound machine time. Each cell takes the shared render
permit individually, so a matrix competes with browser traffic rather than reserving the renderer.

Resource URIs use `compose-preview://catalog/<catalog>/<preview-id>`. Storybook-compatible ids are
qualified as `<catalog>::<preview-id>` so identical preview ids in different catalogs cannot
collide.

## Relationship to UI-builder MCP

One endpoint, two authorization vocabularies:

| Surface | Endpoint/transport | Authorization | State model |
| --- | --- | --- | --- |
| Catalog tools | `/mcp`, Streamable HTTP | `preview` / `live` scopes | Stateless aggregate catalog queries and renders |
| UI-builder tools | `/mcp`, same transport | `ui-builder-read`, `ui-builder-write`, `ui-builder-export` capabilities | Stateless per call; a design's revision is carried explicitly as `baseRevision` |

This was planned as a separate sidecar on a path of its own, with a stateful session. It is one
endpoint instead, and the session is stateless, for two reasons. An agent already holds exactly one
bearer for the box, and a second endpoint would have meant a second origin check, a second body cap
and a second place for the two to drift about what a grant means. And the stateful session it would
have kept turned out to buy nothing: the Design API already carries the revision in the request, so
`baseRevision` does the work a session cursor would have done, and does it in a form a retry can
repeat.

What did not change is the capability model. `ui-builder-read`, `ui-builder-write` and
`ui-builder-export` are checked per call through the same mapping the HTTP routes use, so a grant
that reaches the browser's Design API reaches these tools and nothing more.

## Security and capacity

- Browser-originated MCP calls must have an `Origin` matching the request host, limiting DNS
  rebinding attacks. Non-browser clients normally omit `Origin`.
- Request bodies are capped at 1 MiB and responses disable caching.
- Catalog leases protect a catalog while a request is in flight.
- Remote renders use the same server-wide semaphore and queue timeout as browser renders; enabling
  MCP does not create an unmetered rendering lane.
- Grant authorization is evaluated for every call, so expiry or revocation takes effect without an
  MCP-session teardown.
