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

`catalog_list_projects` discovers the current catalog set. Catalog-specific tools take `catalog` alongside
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

A client that implements MCP URL elicitation passes `urlMode: true` to `poll_access`. While the
request is pending, the server returns the standard `-32042` error with the existing `approveUrl`;
after the browser decision, the client retries that same call to collect the ordinary approved or
denied result. The URL error contains neither the device secret nor a bearer. Declining or
cancelling the client interaction therefore grants nothing, and clients without URL elicitation
keep the complete link-and-code text flow above.

**`initialize`, `ping`, `tools/list` and these two tools need no credential**; everything that reads
a catalog still does. The gate is per message, not per endpoint, because a client that cannot finish
`initialize` cannot reach the tool that asks for a credential either — the endpoint was a dead end
for exactly the agent the grant flow exists to serve. Anything the server does not recognise is
gated: a tool added later is closed until someone deliberately opens it.

### …and use it without setting a header

A token is normally presented as `X-Compose-Preview-Token` (or `Authorization: Bearer`, or
`?token=`), and where you control your own headers that is still the right place: it keeps the
credential off the message a model reasons over.

An MCP client does not control them. It fixes its request headers when it connects, from static
configuration, and nothing it learns afterwards can change them. So an agent that walks the flow
above receives its token in the one place it cannot use — a tool result, mid-session — and every
gated tool goes on refusing it until a human edits an `mcp.json` and restarts the session. The flow
worked and the session it was for was already over.

Every gated tool therefore also accepts the token as a **`token` argument**:

```json
{"name": "ui_builder_list_catalogs", "arguments": {"token": "cpat_…"}}
```

It is resolved by the same `ServeMachineAuthorization`, against the same store, for the same short
lifetime — no new authority, a second door into the one that exists. `request_access` and
`poll_access` do not offer the argument: they are what you call when you have no token yet.

Two limits are deliberate. The **operator token** is never read from a message — it is a standing
credential and belongs on a call, not in a transcript. And `resources/list` and `resources/read`
carry no arguments to put a token in; a client that needs those on a token-gated box still needs a
header, or the OAuth flow below.

This is also the recovery path when a token stops working mid-task. Grants live in memory
(`ServeAgentGrantStore`: *"a restart drops every request and every grant"*), so a redeploy of the
host invalidates every bearer regardless of its remaining TTL. A client that meets a sudden 401 asks
for a new grant the same way it asked for the first.

## MCP surface

The catalog's data tools carry a `catalog_` prefix (`catalog_render_preview`, `catalog_list_previews`, …) so a client that also runs the local `compose-preview` server never sees two tools with one name. The un-prefixed names still dispatch as a deprecated alias but are no longer listed. `status`, the access tools, the Storybook aliases and `ui_builder_*` are unchanged.

A `catalog_render_preview` PNG result also carries a plain `https` `resource_link` (named `Compose Preview render (https)`, repeated as `structuredContent.imageUrl`) when this box has a public origin: `<origin>/mcp/render.png?uri=…&exp=…&sig=…`. Hosts that cannot show inline base64 or a `compose-preview://` URI (Antigravity's `<agent-embed>` cards, for one) can put it straight in an `<img>`. The URL needs no credential: the HMAC covers exactly one resource URI, overrides included, and expires after ten minutes, so it grants nothing beyond that image. It is minted only after a live-scope render, is absent on a box with no public origin, and a missing or forged signature is a 404.

The endpoint implements Streamable HTTP MCP protocol versions `2025-06-18` and `2025-03-26`.
Catalog calls remain independent by default: JSON-RPC messages use `POST`, notifications receive
`202 Accepted`, and optional `GET`/SSE returns `405 Method Not Allowed`. A client negotiating
`2025-06-18` or `2025-11-25` that sends both Streamable HTTP media types and advertises form
elicitation may receive an opaque `MCP-Session-Id` on `initialize`. A later request using that id may elicit: only at the moment a call sends
`elicitation/create` does its POST switch to SSE, and the server then waits for the client's
response on a second POST. A call that does not elicit answers with the same JSON body as the
stateless path, so negotiating the scope changes nothing on the wire until a tool asks a question.
The scope is bounded, expires after inactivity and can be closed with `DELETE`; it stores only the
pending request correlation and a fingerprint of the credential that asked, never a design, grant,
actor or authorization decision. The three home decisions below are the calls that use it. Clients that
do not negotiate this capability continue to receive the original JSON response mode with no
session allocation.

The session id is a hint, never a requirement. A request whose id is unknown, expired or evicted,
or whose `MCP-Protocol-Version` differs from the negotiated one, is served on the stateless JSON
path instead of answering `404`/`400`: idle expiry, eviction and a server restart must not break a
client that has been working statelessly all along. It only loses elicitation, and every tool keeps
its text fallback. A full registry evicts its least recently used idle scope (and, if every scope
is waiting on a person, simply issues no id), because `initialize` is ungated and an anonymous
caller must not be able to lock legitimate clients out.

This is a compatibility path for the negotiated 2025 protocols and the current Kotlin MCP SDK.
When the server and target clients move to the 2026 protocol generation, task-level
`input_required` plus `inputResponses`/`requestState` should replace this request-scoped rendezvous;
that protocol/SDK migration is deliberately not bundled into the compatibility transport.

| Operation | Access | Purpose |
| --- | --- | --- |
| `initialize`, `ping`, `tools/list` | none | Handshake and discovery; reads no catalog |
| `request_access`, `poll_access` | none | Obtain a grant without leaving MCP (above) |
| `status` | `preview` | Report readiness and the aggregate catalog set |
| `resources/list`, `resources/read` | `preview` | List and read published preview PNGs |
| `resources/read` of `compose-preview://schemas/…` | none | The UI-builder document and mutation JSON Schemas (below); listed only where a UI builder is served |
| `catalog_list_projects`, `catalog_list_previews` | `preview` | Discover catalogs, then one catalog's preview metadata (`catalog_list_previews` requires `catalog` unless the server holds only one) |
| `catalog_render_preview` | `live` | Render with optional overrides; defaults to a token-frugal semantics/hash observation, with `observe=png` for pixels and `observe=svg` for the `compose/figma-svg` vector export |
| `catalog_render_matrix` | `live` | Render one preview across a cross-product of override axes in a single call |
| `catalog_list_devices` | `preview` | The `device` override's accepted vocabulary, with each frame's dp size and density |
| `catalog_history_list` | `preview` | One preview's render timeline |
| `catalog_history_diff` | `preview` | Compare two of its recorded renders |
| `catalog_history_read` | `preview` | One historical render's pixels, by commit or blob |
| `catalog_diff_semantics` | `live` | Compare two previews' semantics by authored `testTag` |
| `catalog_list_data_products` | `preview` | Discover structured products exposed by one catalog's previews (`catalog` or `uri` required) |
| `catalog_get_preview_data` | `live` | Retrieve accessibility or Compose annotation data |
| `list-all-documentation`, `get-documentation-for-story` | `preview` | Storybook-MCP-compatible discovery aliases |
| `preview-stories` | `live` | Storybook-MCP-compatible preview rendering alias |
| `ui_builder_list_catalogs`, `ui_builder_search_components`, `ui_builder_list_designs`, `ui_builder_get_design` | `ui-builder-read` | The component catalogs a design can pin to (a summary by default, the whole capability with `full: true`), the designs on this box, and one design's whole document (without the catalog it pins unless `includeCatalog: true`) |
| `ui_builder_create_design`, `ui_builder_apply` | `ui-builder-write` | Create a design, and apply `DesignMutationV1` operations to one — a `setProperty` whose value is `{"type":"null"}` unsets an optional property |
| `ui_builder_validate` | `ui-builder-read` | Check a whole `document`, a stored design, or `operations` against a stored design **without saving** — `{valid, problems:[…]}`, the problems panel's list |
| `ui_builder_check_design` | `ui-builder-read` (plus `ui-builder-export` for `rendered: true`) | Everything worth checking **before showing** a design: schema, catalog and accessibility (labels, content descriptions, touch targets, contrast, text clipping at 200%) on a design, a past revision, a `document`, or unsaved `operations` — a summary, then findings by node (#1255) |
| `ui_builder_render_design_matrix` | `ui-builder-read` | One design on several devices × themes × font scales as **one** contact-sheet PNG behind a signed link, with each cell's device and box; present where the host can render on a scratch copy (#1255) |
| `ui_builder_record_decision`, `ui_builder_await_decision` | `ui-builder-write`, `ui-builder-read` | Record an agent's `approve`/`reject` of one revision, and **wait** for a person's; present only where the host keeps reviews (#1255) |
| `ui_builder_set_implementation`, `ui_builder_implementation_status` | `ui-builder-write`, `ui-builder-read` + `ui-builder-export` | Record the pull request implementing a design (status, revision, whether its previews match), and read everything the code side needs in one call (#1255) |
| `ui_builder_find_design_for_pr` | `ui-builder-read` | From a pull request back to the design(s) it implements |
| `ui_builder_rename_design`, `ui_builder_delete_design` | `ui-builder-write` | Retitle a design you may write; delete one you **own**. Neither has a request type in the contract, so both answer outside the released envelope |
| `ui_builder_await_design` | `ui-builder-read` | **Wait** for somebody else to change a design, and return what they changed |
| `ui_builder_list_revisions`, `ui_builder_diff_designs` | `ui-builder-read` | A design's retained revisions (actor, time, operation, document digest, and the retention floor); a node-level diff of any two retained revisions of any two designs (below) |
| `ui_builder_restore_revision` | `ui-builder-write` | Restore a retained revision **forward**, as a new revision; `dryRun: true` returns the diff it would apply. Needs the design's own write action |
| `ui_builder_fork_design` | `ui-builder-write` | A new design from one revision of another, with its `forkedFrom` recorded and listed on both ends through `ui_builder_get_links` |
| `ui_builder_branch_design`, `ui_builder_list_branches` | `ui-builder-write`, `ui-builder-read` | Fork a design at a revision into a **branch** (a design of its own, edited with `ui_builder_apply`), and list a design's branches by status; present where the host's service keeps branches (below) |
| `ui_builder_merge_branch`, `ui_builder_archive_branch` | `ui-builder-write` | Replay a branch onto its parent, all or nothing, with a per-command report (`dryRun`, `skipOperationIds`); or close it unmerged |
| `ui_builder_compare_branches`, `ui_builder_pick_branch` | `ui-builder-read` | Put a design's open branches side by side in one signed picture with a diff summary each; ask the person which to keep (thumbnail picker, plain form, or a numbered list for chat). Present with the branch tools |
| `ui_builder_export` | `ui-builder-export` | Export a design — `compose` returns the generator's Kotlin, or diagnostics naming each reason it refused |
| `ui_builder_view` | `ui-builder-read` (plus `ui-builder-export` for `renderer: "native"`) | The editor canvas as a person sees it — a PNG with the selection, reference overlay, comment pins and layout bounds drawn on, and the node boxes and pin positions as JSON (#1114) |
| `ui_builder_set_reference` | `ui-builder-write` | Attach the picture a design is built against — a Figma frame screenshot, a mock — as its reference overlay, or clear it; replies with what the picture is against the frame (size in dp, density, screen or region, whether pixels compare). Present only where the host keeps reference overlays |
| `ui_builder_compare_reference` | `ui-builder-read` (plus `ui-builder-export` when matching layers) | Measure a design against its reference: the differing regions in dp with their layers, and per named layer the move, size and font size that would line it up, as `ui_builder_apply` operations. Writes nothing |
| `ui_builder_put_asset` | `ui-builder-write` | Put a picture behind an `assetKey`, so an `asset/image` node draws it; present only where the host keeps design assets |
| `ui_builder_design_access` | `ui-builder-read` | Who can open a design — its owner, and everyone it has been shared with |
| `ui_builder_share_design` | `ui-builder-write` | Share a design with another actor as `viewer` or `editor`, or take that back |
| `ui_builder_get_links` | `ui-builder-read` | What a design is **for**: the issue, the design-tool frame, the pull request, the chat thread, and the design it continues; present only where the host records them |
| `ui_builder_set_links` | `ui-builder-write` | Say what a design is for, replacing the whole record — an omitted link is cleared, not left alone |
| `ui_builder_list_comments`, `ui_builder_await_comments` | `ui-builder-read` | Read a design's discussion, and **wait** for the next thing said in it |
| `ui_builder_post_comment`, `ui_builder_resolve_comment_thread` | `ui-builder-write` | Say something on a design, and close a thread once it is answered |
| `ui_builder_acknowledge_comment`, `ui_builder_react_to_comment` | `ui-builder-write` | Say you have **read** a thread — which is not resolving it — or react to one comment with an emoji |

Enumerations across every catalog (`status`, `catalog_list_projects`, `resources/list`,
`list-all-documentation`) read what the registry already holds and never resume a suspended catalog.
`catalog_list_previews` and `catalog_list_data_products` without a catalog refuse at once with the available ids and a
pointer to the local `compose-preview-mcp` server, rather than serialising every catalog (#1162).

The `ui_builder_*` tools appear in `tools/list` only on a box that actually serves a UI builder
(`--ui-builder-dir`). A box without one does not advertise them, because listed-and-failing tells an
agent this server can do something it cannot.

### Authoring a design over MCP

The tools are a typed door onto the same `UiBuilderServicePort` the browser's Design API calls, so
the reply is the released `McpResponseEnvelopeV1` and the request shapes are the released
`UiBuilderRequestV1` ones. A session looks like:

1. `ui_builder_list_catalogs` — a document's `catalogPin` names a catalog revision the service
   checks, so this is where a real one comes from: each catalog in the reply carries its
   `catalogPin` verbatim. The reply is a summary — per component its id, role, traits, `slots` as
   `name[min..max]:accepted|roles` and `properties` as `name:type`, `!` when required, `=a|b` for
   the allowed values; per catalog its export formats and modifier vocabulary — because the whole
   `CatalogCapabilityV1` is 58 KB for the packaged M3 catalog alone and 72 on the hosted
   deployment with its packs, and an agent pays for every byte of it as context, on the call whose
   description says "start here". `full: true` is the released
   `CatalogsResponseV1`, adapter status and parity included; `componentIds` narrows either.
2. `ui_builder_create_design` — with a whole `document`, or `fromDesignId` to copy an existing
   design. There is no "blank template" argument: a starter document assembled inside the server
   would carry a pin invented there, and the service would reject it. A copy carries a pin that is
   real by construction.
3. `ui_builder_get_design` — read the `revision` to quote next. `baseRevision` is how a concurrent
   edit is detected, so an agent that guesses it *is* the concurrent edit. The snapshot comes back
   **without** the `catalog` a `ServiceSnapshotV1` embeds — the document's `catalogPin` names it
   exactly and step 1 serves it — so an 80-node design is single-digit KB rather than sixty;
   `includeCatalog: true` restores the released shape. `ui_builder_create_design` and a resync from
   `ui_builder_await_design` take the same argument.
4. `ui_builder_apply` — `operations` is an array of `DesignMutationV1`: `insertNode`, `setProperty`,
   `deleteNode`, `moveNode` and the rest. `operationId` is yours, and makes a retry idempotent. A
   `setProperty` with `{"type":"null"}` as its value **unsets** the property rather than storing a
   null — the way back after trying one — and is refused, naming the node and the field, when the
   catalog requires it.
5. `ui_builder_put_asset` — when a screen needs a photograph. `asset/image` names an `assetKey`,
   and the reducer refuses a key that is neither in the catalog's registry nor pinned in the
   design, so put the picture **first**: this stores PNG, JPEG, GIF or WebP bytes (base64, at most
   1 MiB) content-addressed and pins the key into the design's `assets` map, moving the revision
   like an apply does. Then insert the node naming the key. See
   [`UI_BUILDER_ASSETS.md`](UI_BUILDER_ASSETS.md).
6. `ui_builder_export` — the Kotlin, or the refusals. An `asset/image` exports as the real
   `Image(...)` with a `ColorPainter` in place of the picture and an `ASSET_PLACEHOLDER` warning
   naming the key and digest to bundle.
   Each export header names the design's canonical home and, on the line beside it, the revision
   it was cut from — the `baseRevision` to quote when going back to edit the original.
7. `ui_builder_rename_design` when the design has become something else, and
   `ui_builder_delete_design` when it was a probe. Rename is open to anybody who may write the
   design and moves no revision. Delete is **owner only** — an agent under a grant owns what it
   created as the person who approved the grant — so a session can clear its own litter and cannot
   reach anybody else's; a design whose owner no longer exists is still the operator's to remove
   through `/admin/ui-builder`.

### Home decisions: a form when the client can answer one

Three calls stop at a decision that belongs to the person rather than the agent (#1120, R3), and each
one describes it as a `compose-preview-decision/v1` object listing `options` (`{id, label}`) and a
`question`:

| Call | `decision` | Options |
| --- | --- | --- |
| `ui_builder_replace_design_document` with `dryRun: true` | `save-back-or-reimport` | `save-back`, `create-new`, `discard`, `keep` |
| `ui_builder_move_design_home` with `dryRun: true` | `move-design-home` | `move`, `cancel` |
| `ui_builder_create_design` with a `document` whose `home` is an existing design here | `import-onto-existing-home` (a refusal, `isError`) | `apply-operations`, `create-new`, `cancel` |

**Without a form** — a stateless client, a client that did not negotiate the request scope described
under the transport, or one that declared only URL elicitation — the call returns that object as
text, exactly as before, writes nothing, and the agent puts the choice to the person in chat.

**With a form** — the client negotiated the request scope and declared form elicitation — the same
call instead sends `elicitation/create` with the decision's `question` as the message and a
`requestedSchema` of one required `choice` string whose `enum` is the option ids (`enumNames` are the
labels), plus an optional `newDesignId` string wherever `create-new` is offered. Then:

- **accept** with a write option performs exactly that write, as the call's own actor:
  - `save-back` is the replace the dry run stood in for, with the same arguments and operation id,
    so the same revision check and idempotency apply; `move` likewise for the home move;
  - `create-new` creates the supplied document as a new design named `newDesignId`, its `home`
    removed so it is homed here under the new id, through the ordinary create path — an id that
    is already taken is refused as a tool error, never overwritten. A `create-new` without a
    `newDesignId` (or naming the original) writes nothing and returns the decision text;
  - the reply is that write's normal reply (an operation outcome or the new design's snapshot).
- **accept** with `discard`, `keep` or `cancel` writes nothing and returns the decision plus
  `"chosen": "<id>", "written": false` as a normal (non-error) result.
- **accept** with `apply-operations` also writes nothing: turning two documents into operations is
  the agent's job, so the reply records the choice and says to read the original with
  `ui_builder_get_design` and send the differences through `ui_builder_apply` at the decision's
  `baseRevision`.
- **decline**, **cancel**, a malformed answer, an option that was not offered, or no answer within
  the interaction timeout (two minutes) write nothing and return the decision text — for the import,
  the refusal — byte for byte what a client without forms receives.

The answer only ever selects among the options the call itself offered; it carries no authority.
The write it selects runs inside the original call, with the actor that call authenticated and
the capability it was already checked for (`ui-builder-write` for all three tools), so nothing
the non-dry-run call could not do becomes reachable. Two further checks bind the answer to that
call:

- the POST carrying the answer must present the same transport credential (the
  `X-Compose-Preview-Token`, `Authorization`, `?token=` and cookie material, compared as a SHA-256
  fingerprint) as the POST that asked; a different or missing one is refused as an unknown
  request and leaves the question pending for its real owner. A grant presented only in-band (the
  `token` argument) cannot ride a JSON-RPC response, so such a call is bound by its session id —
  192 random bits, handed only to the client that initialized — alone;
- when an **accept** arrives, the same credential is authorized again for the same capability and
  must still resolve to the same actor. A grant revoked or expired while the form was open turns
  the answer into a timeout: nothing written, the decision text returned.

Scopes, pending questions and fingerprints live in the memory of the one server process that
issued them. An answer that reaches a different process (a restart, or another replica behind a
load balancer) finds no such session and is refused `404`; the waiting call then times out and
writes nothing.

### Seeing the design, not only reading it

`ui_builder_view` is "the agent sees what the user sees" (#1114). It takes `designId`, an optional
`revision`, `include` (any of `selection`, `reference`, `comments`, `bounds`; defaults to the first
three), `selection` (node ids), `viewport` (`{width, height}` in pixels, aspect kept) and
`renderer`, and answers with a `compose-preview/ui-builder-view/v1` JSON beside the picture: the
revision, each visible node's id and box, each comment thread's pin, what the reference overlay did,
and `notes` for anything it could not draw. Every coordinate is in the returned image's pixels.

Nothing new renders the design. The frame is the PNG export (`renderer: "export"`, the default — the
editor's own renderer, as `ui_builder_export` with `format: "png"` returns it) or the native Compose
render (`renderer: "native"`, the `ui_builder_render_native` lane, which compiles the design and so
needs `ui-builder-export` as well). The overlays are drawn server-side with `java.awt`:

- **selection** — a 2 px outline round each selected node's box;
- **reference** — the design's reference picture, placed as the editor places it (contained and
  centred, then its stored scale and offset) at its stored opacity,
  honouring the `overlay`, `split` and `difference` modes (a hidden reference, `boxes` mode or a
  format this JVM cannot decode is reported, not drawn);
- **comments** — one numbered pin per thread: a point anchor at its frame fraction, a node anchor at
  the node's top-left corner; a markup-stroke anchor is reported unplaced;
- **bounds** — a thin rectangle round every node the renderer placed.

A box is only ever one a renderer reported. The PNG export reports none, so a view from it carries
`boundsUnavailable`, an empty `nodes`, and a note for each selected node it could not outline —
never an outline at a guessed position. The native lane reports every tagged node's box in its own
frame, which is why a view that needs boxes draws that frame rather than mixing two renders.

The picture is a fetchable link by default rather than base64 in the reply: a `resource_link` named
`UI-builder view (https)`, repeated as `image.url`, on the same signed `/mcp/render.png` route as a
catalog render. A view is drawn for one actor from state that actor may read, so it cannot be
re-rendered from a credential-free link; the server keeps the PNG for the link's ten-minute
lifetime instead (at most 32 at once), and a forged or expired signature is a 404. `inline: true`
adds the bytes as an `image` block; a box with no public origin always does. The tool carries
`_meta.ui.resourceUri`, so MCP App hosts open it in the bundled viewer (#1119).

The CLI spelling is `compose-preview-server design view <designId>` with `--select`, `--include`,
`--viewport <w>x<h>` and `--renderer`: the PNG goes to `--out` (default `<designId>.view.png`) and
the JSON to stdout.

### Building against a reference

The editor's **Frame, density and reference** panel has two tools on this surface, so an agent can
do what a person does there: put the mock beside the design, and measure the design against it.

`ui_builder_set_reference` takes the picture's bytes (`imageBase64`), an optional `name`,
`density` (pixels per dp — 2 for a Figma 2× export) and `sourceUrl` (where it came from; never
fetched). This host makes no outbound call for a reference, Figma included: an agent with Figma
tools takes the frame's screenshot itself and passes the bytes and the frame link. The reply says
what the picture is against the design's frame — its size in dp, the density and where that came
from (declared, inferred from the frame width, or assumed), whether it is a screen, a tall screen
or a region, the fit that lines it up, and whether a pixel comparison means anything. The overlay
is the one the browser shows, so a person opening the design sees what the agent attached.

`ui_builder_compare_reference` measures. With `differences` (the default) it reports the share of
compared pixels that differ and up to eight regions in dp; with `nodeIds` it matches each layer
against the reference — from a box drawn over it in the editor, or a search of the reference's
pixels, which for text also reads the type size — and returns the `alignment` (move, size, font
size) and the `operations` (`setModifiers`, `setProperty`) that would make it agree. It writes
nothing; the agent applies what it agrees with through `ui_builder_apply`, which validates it like
any other edit, then compares again and looks with `ui_builder_view`. Layer matching needs node
boxes, so `nodeIds` measures the native render and needs `ui-builder-export`; differences alone
use the PNG export.

Both are computed by `:ui-builder-export`'s reference engine, the code the browser editor runs over
a photograph of its own canvas, so an agent and a person comparing the same design read the same
numbers. Two gaps, said in the replies: the stored overlay does not yet record a *fit*, so a
region attached here is shown contained in the editor until somebody picks *Actual size*; and an
SVG reference is drawn in the editor but not measured here, because this JVM does not rasterise
SVG.

The CLI spellings are `compose-preview-server design reference <designId> --attach <picture>
[--density <n>] [--source-url <url>]` (or `--clear`) and `design compare <designId> [--node <id>]
[--fit <fit>] [--no-differences]`.

### Checking before writing, and the shapes

`ui_builder_validate` answers "would this be accepted, and would it export?" and writes nothing. Pass
exactly one of:

- `document` — a whole `DesignDocumentV1`, e.g. one you are about to `ui_builder_create_design` or
  `ui_builder_replace_design_document`;
- `designId` — the stored design as it is now, which is the "is my design exportable?" question;
- `designId` with `operations` (and optionally `baseRevision`) — the batch you are about to
  `ui_builder_apply`, checked against the design's current document.

The reply is `{"schema":"compose-preview/ui-builder-validation/v1","valid":…,"problems":[…]}`, each
problem `{severity, source, code, message, nodeId?, field?, operationIndex?}`, and `valid` is false
exactly when a problem is an `error`. `source` says which check spoke: `shape` (the JSON did not
decode — reported as a problem, not a tool error), `document` (the runtime refused it: quota,
environment, topology, catalog pin or the catalog's own validation), `mutations` (the reducer refused
the batch; a stale `baseRevision` is only a `warning`, because an apply rebases what does not
conflict), or `export` (the Compose export gate — `ScreenExportGate`, the same list the editor's
problems panel shows).

It asks the real runtime rather than a copy of its rules: each call opens a throwaway
`PersistentUiBuilderService` over an empty temporary store with the host's own catalogs and exporter,
creates the document there (and applies the batch there), asks for the Compose export, and deletes
the directory. No revision moves, nobody watching the design is notified, and no asset store is
attached. A design the caller cannot read is still refused outright, as `ui_builder_get_design`
refuses it. The CLI's `compose-preview-server design validate <designId> [--operations <file>]` and
`design validate --document <file>` make the same call, print each problem to stderr, and exit
non-zero when the design is not valid.

The shapes themselves are published, so an agent can write a document or a batch right the first
time instead of learning it one refusal at a time:

| Resource | HTTP | Describes |
| --- | --- | --- |
| `compose-preview://schemas/ui-builder-document-v1.json` | `GET /schemas/ui-builder-document-v1.json` | `DesignDocumentV1` — what `ui_builder_get_design` returns and `ui_builder_create_design`, `ui_builder_replace_design_document` and `ui_builder_validate` take |
| `compose-preview://schemas/design-mutation-v1.json` | `GET /schemas/design-mutation-v1.json` | One `DesignMutationV1`, discriminated by `type` — an element of `ui_builder_apply`'s `operations` |

Both are JSON Schema 2020-12 (`application/schema+json`), listed in `resources/list` beside the
viewer and readable without a grant, like the viewer: they are static and describe no design. The
released UI-builder jars ship no schema and no generator, so these are **generated at runtime from the
released `kotlinx.serialization` descriptors** (`UiBuilderJsonSchemas`), which makes them the shape
this server decodes with by construction, whichever protocol release the catalog pins. Objects are
closed (`additionalProperties: false`) because the decoder refuses unknown fields; sealed types are a
`oneOf` on their discriminator (`type`, or `kind` for `DesignHomeV1`). What a descriptor cannot say —
which components and properties the pinned catalog declares — is `ui_builder_validate`'s job.

### Before you show it: check, then look at every size

Agents working on a design for a person should not spend that person's attention on something they
could have caught (compose-ag-plugin `docs/agent-rules.md`, R1–R4). Two tools make that one call
each (#1255).

`ui_builder_check_design` runs `schema`, `catalog` and `a11y` — pick with `checks` — on a stored
design (`designId`, optionally a past `revision`), a whole `document`, or `operations` applied to a
scratch copy of `designId` exactly as `ui_builder_apply` would, with nothing saved. The schema and
catalog halves are `ui_builder_validate`'s; the accessibility half reads the document through the
pinned catalog's own vocabulary — traits like `Action`, `SelectionControl`, `IconContent`,
`TextContent`, and whether a component declares `contentDescription` — so it names no Material
component and works for a Wear or pack catalog too:

| Code | Severity | What it means |
| --- | --- | --- |
| `missingLabel` | error | A control that can be activated has no text, no `contentDescription` and no labelled child |
| `missingContentDescription` | warning | An icon or picture says nothing; set one, or `null` when it is decorative. An icon inside a control that is labelled by text is decorative by construction and is not reported |
| `touchTargetTooSmall` | warning (declared) / error (rendered) | A control under 48×48dp — from its declared `size`/`width`/`height`/`sizeDp`, or measured on a native render with `rendered: true` |
| `lowContrast` | error (literal colours) / warning (theme roles) | Text under 4.5:1 (large text and icons 3:1) against the nearest background. Theme roles are resolved against the Material 3 baseline scheme, which a custom or dynamic theme can change, so they only ever warn |
| `textMayClip` | warning | Text inside a fixed dp height that one line at 200% font scale does not fit |

The reply leads with `summary` ("Fix before showing it: 1 error, 2 warnings — missingLabel on
`play`; …"), then `ok`, the counts, and `findings` with the `nodeId` each is about — capped at 40,
with `truncated` saying how many more. A check that could not run says so in `skipped` instead of
passing silently. These are document checks: they do not claim to have seen a render unless
`rendered: true` measured one.

`ui_builder_render_design_matrix` draws the design under several environments and returns **one**
contact sheet — the design's own PNG export per cell, with the environment swapped on a scratch
copy, so nothing is written and nobody watching the design is woken. Name `devices` by preset
(`phone_small`, `phone`, `phone_landscape`, `foldable_folded`, `foldable_unfolded`,
`tablet_portrait`, `tablet_landscape`, `wear_small_round`, `wear_large_round`, `wear_square`,
`tv_1080p`) or as `{widthDp, heightDp, label?, round?}`, or a `formFactor` (`phone`, `foldable`,
`tablet`, `wear`, `tv`, `adaptive`) for its default set; with neither, a mobile catalog gets
`adaptive` (phone, unfolded foldable, landscape tablet) and a Wear catalog the two round watches.
`themes` and `fontScales` cross with the devices, up to 16 cells. The sheet is a short-lived signed
https link, as `ui_builder_view`'s picture is, and is kept under 3.5 MB so a chat surface (#1254)
can show it inline; `inline: true` adds the bytes as an image block. Each cell reports its device,
size, theme, font scale and its box on the sheet, and a cell that could not be drawn says why.

### Waiting for a verdict, and joining a design to its pull request

`ui_builder_await_comments` waits for words; `ui_builder_await_decision` waits for a **decision** —
an `approve` or `reject` recorded on one revision, with who decided, when, and an optional note. It is
the same shape as the other waits — `afterSequence`, `waitSeconds`, a `timedOut` reply — with two
additions for agents that can only follow up later (Claude Tag routines, #1254): every reply carries
`latest`, the newest matching verdict whether or not it is new, and `approved`, so `waitSeconds: 0`
is a cheap and idempotent poll. By default it waits for a **person** (`from: "human"`);
`from: "anyone"` counts agents too, and `revision` narrows it to one revision.

People record theirs over `POST /api/ui-builder/v1/designs/{designId}/decisions`
(`{revision, verdict, note?, decisionId?}`) — the route a browser review control calls; the decider
kind comes from the credential, as a comment's author kind does. `ui_builder_record_decision` records an **agent's** verdict and is
labelled as one, so it never answers a person's wait. Both are idempotent by `decisionId`.
`GET …/review` returns the whole record.

The implementation side is a record of its own beside the design rather than another field of the
published links record: `ui_builder_set_implementation` stores the pull request URL, its `status`
(`draft`, `open`, `merged`, `closed`), the design `revision` it implements, and `previewMatch` —
`{status: match | mismatch | unknown, evidence, note}` — once somebody has compared the PR's rendered
previews (the preview diff bot's comment, or `ui_builder_render_design_matrix` against the PR's
renders) with the design. This server cannot see a pull request's renders, so it records that answer
and where the evidence is rather than claiming to have checked. Writing the same record again moves
nothing and wakes nobody, so CI may report on every push. `PUT …/implementation` is the same write
over HTTP.

`ui_builder_implementation_status` is what the code side reads: the revision, its links, the
implementation record and whether it names this revision, the latest verdict on this revision, and
the Compose export with the generator's diagnostics (`includeExport: false` leaves the Kotlin out and
needs only a read grant). `ui_builder_find_design_for_pr` goes the other way — from a pull request to
the designs whose implementation record or links name it, each read as the caller before it is named
— and `GET /api/ui-builder/v1/implementations?pr=` answers the same over HTTP. Decisions and the
implementation live under `reviews/` beside the UI-builder state and are deleted with the design.

### A design's history: revisions, diff, restore, fork

Four tools give an agent the history page's operations (#1256, phase 1 of
yschimke/compose-ui-builder#375), over the same service requests and with the same access rules. None
of them answers with the released envelope — the contract has no history request — so each reply is
its own small shape with a `summary` a person can read, declared as the tool's `outputSchema`.

- `ui_builder_list_revisions {designId, limit?, before?}` — newest first: revision, sequence, actor,
  time, document digest, node count and the operation that produced it (`setProperty ×2,
  insertNode`, `undo of …`, `restored r3`) while the operation log still holds it. `retention`
  names the **floor** — `oldestRetainedRevision` — and the policy behind it (128 whole-document
  revisions within a 2 MiB budget per design, never fewer than 32, and the last 1,024 operations).
  Anything older is gone: get, diff, restore and fork refuse it with an error that names the floor,
  and a revision that never existed is refused as such.
- `ui_builder_diff_designs {a: {designId, revision?}, b?: {designId, revision?}}` — nodes added,
  removed and moved (a different parent or slot, or a different place among the siblings both sides
  share — a sibling inserted before a node is not a move), and per node the properties, the
  modifier list and any other field that changed, each with its id and a `root/slot[index]/node`
  path; plus changed document fields (title, environment, state variables). Values are the
  protocol's own JSON. Works across a design and its fork. `b` defaults to `a`'s current revision.
  The diff is computed server-side over `DesignDocumentV1`: the editor's `revisionDiff` lives in the
  unpublished frontend module and diffs the editor's own model, so the two should be consolidated
  once a document diff moves into a published module. No before/after render URLs yet: the only
  signed image link on this surface is `ui_builder_view`'s, and it is not reachable per revision.
- `ui_builder_restore_revision {designId, revision, baseRevision, dryRun?, operationId?}` — the
  runtime's `RestoreRevision`: forward, hash-bound to the document it replaces, refused as **stale**
  when `baseRevision` is not current. `dryRun: true` writes nothing and returns the diff from the
  current document to the restored one, and `stale` when the real call would be refused. Both take
  the design's own write action.
- `ui_builder_fork_design {designId, revision?, title?, newDesignId?}` — the history page's fork,
  plus **ancestry**: the fork records `forkedFrom: {designId, revision, documentDigest}`, and the
  parent lists it under `forks`; `ui_builder_get_links` returns both, a parent's `forks` filtered to
  the ones the reader may open. A fork never takes its parent's canonical home — it is homed here
  under its own id where the host has a public origin, unhomed otherwise — so it is a temporary
  copy in the R3 sense: announce it, and bring a chosen change back with `ui_builder_apply` on the
  parent. The history page's fork form records the same ancestry. Ancestry is kept beside the links
  records (`links/ancestry/`), because the published `DesignLinksV1` has no field for it and the
  runtime's branch model records branches, not forks — a fork is an independent design with no
  record there; a host without a links store forks without it and says so
  (`ancestryRecorded: false`). When a change should come back, a branch (below) is the better tool.

### Branches: explore alternatives, then merge one

A **branch** is a design forked at a revision, edited on its own and **replay-merged** back onto its
parent through the same reducer every edit goes through — the browser's Sync, run on the server
(phase 2 of yschimke/compose-ui-builder#375; the runtime is compose-ui-builder#377 and
[`UI_BUILDER_BRANCHES.md`](https://github.com/yschimke/compose-ui-builder/blob/main/docs/design/UI_BUILDER_BRANCHES.md)
there). Use one to try alternatives, or to change a design somebody is editing live without editing
under them. Unlike `ui_builder_fork_design`, a branch remembers its parent *and* has a way back.

- `ui_builder_branch_design {designId, name, revision?, branchId?}` — the new branch, `open`, at
  the fork revision (its revisions continue the parent's numbering). Needs write on the parent.
  Branches of a branch are refused, and so, on a branch, are asset uploads, restores, catalog
  upgrades, document replacements and home moves — a command log cannot carry them.
- `ui_builder_list_branches {designId, status?}` — newest first; `status` is `open`, `merged`,
  `archived` or `all` (the default). Each row has the fork and head revisions and `commandCount`,
  what a merge would replay.
- `ui_builder_merge_branch {branchId, dryRun?, skipOperationIds?}` — replays the branch's commands
  onto the parent's **current** revision. All or nothing: either every command lands, one revision
  each and attributed to the actor who authored it on the branch, or nothing is written. The reply
  lists every command attempted — `applied` with `committedRevision` and any last-writer-wins
  `conflicts` (`STALE_PROPERTY_WRITE`, `STALE_MOVE`, …), or `refused` with the reducer's `code` —
  plus `remaining` (never tried), `skippedOperationIds` and `archivedSiblingIds`. A refusal is
  resolved by skipping the command (and any undo of it) or branching again from the parent's head.
- `ui_builder_archive_branch {branchId}` — closes a branch unmerged; it stays readable and listed.

Every other tool works on a branch's id unchanged — `ui_builder_get_design`, `ui_builder_apply`,
`ui_builder_view`, `ui_builder_export`, `ui_builder_diff_designs`. **Access is the parent's**: a
branch's access list is copied from its parent and kept in step, so whoever reads the design reads
its branches, and an agent grant naming a design reaches its branches too (the grant scope resolves
a branch to its parent through the runtime). Writes — branching, merging, archiving — need write on
the parent; the branch's creator may also archive it. `ui_builder_get_links` reports the relation
from the runtime, never a second copy of it: `branchOf` on a branch (parent, fork revision, name,
status) and `branches` on its parent. A fork's `forkedFrom` / `forks` is a separate relation, kept
in the links store as before.

**Recipe: N alternatives, pick one, merge** (phase 3 of yschimke/compose-ui-builder#375).

1. `ui_builder_branch_design` ×N from the same revision, one `name` per idea (`compact header`,
   `card list`, …). Branches forked at the same revision are **siblings**.
2. Edit each with `ui_builder_apply` on its `branchId`.
3. Show them in one call: `ui_builder_compare_branches {designId, branchIds?, device?}`. It renders
   the parent's head and each open branch (or the ones named, in that order) into **one** contact
   sheet — a short-lived signed https link, like `ui_builder_view`'s, captioned `Parent · rN`,
   `1 · <name>`, `2 · <name>`, … — and gives each branch a `ui_builder_diff_designs`-style summary
   against the parent **at its fork** (counts, a headline such as `2 changed, 1 added`, the first
   lines, and `parentMovedSinceFork`). `device` (a preset id or `{widthDp, heightDp}`) redraws
   every picture at that size without saving anything. A branch whose picture fails is a blank
   tile with its `problem`, never a failed call.
4. Let the person pick: `ui_builder_pick_branch {designId, branchIds?, message?}`. It asks the
   best way the client can answer, and never waits on one that cannot:
   - a client that declared OpenAI form elicitation (`extensions["openai/elicitation"].form`, as
     ChatGPT and Codex do) gets #1253's thumbnail picker — one option per branch, the branch's
     render as its thumbnail and `ui_builder_view {designId: branchId}` as its preview;
   - a client with plain MCP form elicitation gets a single-choice enum of the branch ids, titled
     `1. <name>`, …;
   - anybody else — the stateless JSON path, a Claude or ChatGPT agent in Slack (#1254) — gets
     `outcome: "ask-in-chat"`: the numbered list and one sheet link for the agent to post, and the
     person's reply with a number is the answer. Do not choose for them.

   `outcome` is `chosen` (with `branchId`), `declined`, `ask-in-chat` or `no-branches`. A form is
   bounded by two minutes; a picker the client rejects falls back to the plain form, and one that
   goes unanswered falls back to the numbered list rather than asking twice. Picking writes nothing.
5. `ui_builder_merge_branch {branchId, dryRun: true}`, read the report, then merge for real. The
   other siblings are archived, linked to the winner through `supersededByBranchId`, and kept. The
   reply's `parentRevisionBefore` / `parentRevisionAfter` bound what landed;
   `ui_builder_diff_designs {a: {designId: parent, revision: before}, b: {designId: parent}}` shows
   it. There is deliberately no separate "adopt" tool: the merge already archives the siblings and
   names the new revision, and a second door onto the same write would be one more thing to keep in
   step.

Compare and pick are reads of the parent: whoever may read the design may compare and pick among its
branches, a design-scoped grant reaches the branches through the parent, and a `branchIds` entry
that is not a branch of `designId` is refused rather than rendered. Only an open branch can be
picked; compare also shows a merged or archived branch when it is named.

Known limit, from the runtime: only the first replayed command sees edits the parent made after the
fork as concurrent, so a later command that overwrites one carries no `STALE_*` notice. The dry run
and a diff of the parent against the branch are the review surface until that is fixed upstream.

### Watching, rather than asking again

Two tools block instead of returning at once: `ui_builder_await_design` waits for the design to move
past a `lastSequence` you quote, and `ui_builder_await_comments` waits for the discussion to move
past a `sequence` you quote. Both return the moment a designer in the browser or another agent does
something, and answer a `timedOut` reply when nothing happens within `waitSeconds`, which you act on
by calling again with the same cursor. `ui_builder_await_design` replies with the released
`DesignUpdateEnvelopeV1` — the identical frame the browser's own `/updates` socket receives.

**And why waiting is no longer the only way to find out.** A design's replies carry the discussion
with them: `ui_builder_get_design`, `ui_builder_apply`, `ui_builder_export`,
`ui_builder_render_native`, `ui_builder_put_asset` and `ui_builder_await_design` grow a `comments`
block — a count, the cursor and up to three quoted excerpts naming the node each is pinned to —
whenever somebody has said something you have not acknowledged. Clear it with
`ui_builder_acknowledge_comment`, which claims only that you have read the thread, or with
`ui_builder_react_to_comment`, which is the lightest way to say the same thing; neither claims the
question is settled, which is what `ui_builder_resolve_comment_thread` is for.
[`UI_BUILDER_COMMENTS.md`](UI_BUILDER_COMMENTS.md) has the three acts and why they are separate.

**And what the design is for, on the same reply.** `ui_builder_get_design` also grows a `links`
object — the issue, the design-tool frame, the pull request, the chat thread and the design this one
continues — whenever anybody has recorded one, so an agent opening somebody else's design sees the
brief behind it without a second call. Read it on its own with `ui_builder_get_links` and write it
with `ui_builder_set_links`, which replaces the whole record.
[`UI_BUILDER_LINKS.md`](UI_BUILDER_LINKS.md) has the record and its routes.

**Why a blocking call and not an MCP notification.** MCP has server-to-client notifications, but
this endpoint does not advertise subscriptions: `GET /mcp` answers `405`, and `initialize`
advertises `resources: {"subscribe": false}`. The optional elicitation scope does not change that:
its SSE stream belongs to one in-flight POST, closes with that request's final response and has no
resumable notification cursor. A blocking call still needs none of it, and it is the shape
`poll_access` already uses here.

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
DOM-capture tool — wants the markup. `catalog_list_previews` reports it per preview as `svgAvailable`, so the lane is discoverable without
asking for it and reading the refusal. It is available only where the host advertises it
(`ServeHost.hasSvgExportFor`): a static bundle carrying `figma/<slug>.svg` vectors, or a
daemon-backed session that can export `compose/figma-svg`. A catalog with neither is refused by
name rather than reported as a missing preview. The lane shares the render semaphore with the PNG
lane, so it is metered identically and cannot become a second unmetered renderer.

### History

`catalog_history_list` answers in one of three `mode`s, and the field is load-bearing: the three are not
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

`catalog_history_diff` compares two of a preview's recorded renders, defaulting to the two newest — *did the
last publish move this preview?* It is a **metadata** comparison: the timeline's versions are
already collapsed distinct renders, so whether the bytes changed is answered by their content ids
without fetching either image on either side.

It reports `unstable` alongside, and says so explicitly when set. That is the point of having it:
on a preview that re-renders differently on publishes that did not change it, a byte difference is
not evidence of a real change — the same question `flake-triage` otherwise settles with a
repeat-render oracle, answered here from precomputed data.

`catalog_history_read` returns one historical render's pixels through this server, addressed by `commit` or
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
daemon and a static bundle has no scroll producer. `catalog_list_previews` reports `scrollAvailable` per
preview beside `svgAvailable`. A non-scrolling preview yields its ordinary viewport output.

### Devices

`catalog_list_devices` publishes the `device` override's accepted vocabulary from `DeviceDimensions`, the
same catalog the render path resolves against — no geometry is authored in the MCP layer. The tool
exists because an unrecognised `device` value is **not** an error on the render path: it falls
through to the default frame, which from the caller's side is indistinguishable from a device that
happens to render identically to the default.

### Comparing two previews

`catalog_diff_semantics` compares two previews' semantics and reports tags present on only one side, tags
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

`catalog_render_matrix` takes an `axes` object mapping an override key to the values to sweep, renders the
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

### Large knob values: `POST /render` and the A2UI playground

A knob value can be too large for a URL — the A2UI catalog's `A2UI document` preview renders
`previewOverrideString("document", …)`, and a document is kilobytes of JSON Lines. Three ways in,
all the same render:

- **`POST /{system}/render/{id}.png`** takes the GET's parameters in the body: `application/json`
  (an object of string, number or boolean values keyed exactly like the query) or
  `application/x-www-form-urlencoded`. The body is merged over the query and handed to the GET's
  handler, so the product suffixes, the `live` gate (403 for a grant below it, as on the GET), the
  admission and the response are the GET's own. Bodies over 1 MiB — the MCP endpoint's bound — are a
  413.

  ```sh
  jq -Rs '{"knob.document": .}' card.jsonl |
    curl -sf -H "X-Compose-Preview-Token: $COMPOSE_PREVIEW_TOKEN" \
      -H 'Content-Type: application/json' --data-binary @- \
      "https://preview.coo.ee/a2ui-catalog/render/ee.schimke.a2uicatalog.playground.PlaygroundKt.A2uiDocumentPreview.png" \
      -o card.png
  ```

- **`GET /{system}/a2ui`** is a playground page for a catalog with a preview declaring a string
  `document` knob (404 otherwise): the declared default in a textarea, Render (or Ctrl/⌘+Enter, or
  auto-render on a pause), the PNG shown in place, and a refusal explained where it happens. The
  viewer's Overrides panel also edits any multi-line or long (>120 chars) string knob in a textarea.
- **`compose-preview-server a2ui render --document <file|-> [--out <png|->]`** does the same from a
  shell through this endpoint: it finds the preview by its `document` knob (`catalog_list_previews` now
  reports each preview's declared `knobs`), or takes `--preview`, calls `catalog_render_preview` with
  `observe=png` and the document as `knob.document`, and writes the PNG. `--catalog` defaults to
  `a2ui-catalog`, `--server` to `$COMPOSE_PREVIEW_SERVER` or the local default; the credential comes
  from `$COMPOSE_PREVIEW_TOKEN`, and without a `live` grant the command asks a human for one, as
  `design` does. A render the catalog answered from its published bytes is a failure, not a file.

## Relationship to UI-builder MCP

One endpoint, two authorization vocabularies:

| Surface | Endpoint/transport | Authorization | State model |
| --- | --- | --- | --- |
| Catalog tools | `/mcp`, Streamable HTTP | `preview` / `live` scopes | Independent calls; optional bounded request scope for 2025 elicitation |
| UI-builder tools | `/mcp`, same transport | `ui-builder-read`, `ui-builder-write`, `ui-builder-export` capabilities | Explicit `baseRevision`; optional request-scoped elicitation carries no design state |

This was planned as a separate sidecar on a path of its own, with authoritative state in a session.
It is one endpoint instead. An agent already holds exactly one bearer for the box, and a second
endpoint would have meant a second origin check, a second body cap and a second place for the two to
drift about what a grant means. The Design API carries its revision explicitly, so `baseRevision`
does the work an application session cursor would have done in a form a retry can repeat. The small
transport scope used by elicitation is only a response rendezvous and never replaces that rule.

What did not change is the capability model. `ui-builder-read`, `ui-builder-write` and
`ui-builder-export` are checked per call through the same mapping the HTTP routes use, so a grant
that reaches the browser's Design API reaches these tools and nothing more.

## Security and capacity

- Browser-originated MCP calls must have an `Origin` matching the request host, limiting DNS
  rebinding attacks. Non-browser clients normally omit `Origin`.
- Request bodies are capped at 1 MiB and responses disable caching.
- Request scopes use cryptographically random ids, admit one pending interaction, are globally
  bounded, cap the complete send-and-wait interaction at two minutes, expire after five minutes of
  inactivity and can be explicitly deleted. An answer must present the same transport credential
  as the call that asked, and an accepted answer is re-authorized before it may write (see Home
  decisions).
- Catalog leases protect a catalog while a request is in flight.
- Remote renders use the same server-wide semaphore and queue timeout as browser renders; enabling
  MCP does not create an unmetered rendering lane.
- Grant authorization is evaluated for every call, so expiry or revocation takes effect without an
  MCP-session teardown.
