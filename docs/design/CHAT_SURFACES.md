# Chat surfaces

## Browser-owned chat

The companion compose-ui-builder change adds **Connect agent → Browser chat** with local history,
default design instructions and opt-in comment monitoring while the page is open. Its lifecycle
and credential storage are documented in
[Browser-owned design chat](https://github.com/yschimke/compose-ui-builder/blob/419bca7319d7df61baca1771a9543abe36681e9f/docs/design/UI_BUILDER_BROWSER_CHAT.md).
Deploying it requires a release of that editor and the normal `composeai-ui-builder` pin update;
changing this document does not upgrade the served editor archive.

The shared server remains the design and discussion service. Chat calls OpenRouter directly from
the editor; no user provider keys, private conversation store, inference proxy or persistent agent
runner are added here. Existing operator-funded guideline checks are a separate opt-in feature.
The editor's CSP already admits `https://openrouter.ai`; catalog runtime frames do not receive
that permission. The existing comment feed supplies snapshots, and reviews remain private until
a person chooses to post through the ordinary comment UI.

For work after the browser closes, the user's own agent runtime watches comments through
`ui_builder_await_comments` and saves its own conversation/cursor. It authenticates using the
existing scoped, expiring and revocable design access grant. Reopening browser chat never renews
a grant or automatically starts monitoring. This server does not need the agent's provider token.

## External chat surfaces

Issue [#1254](https://github.com/yschimke/compose-preview-server/issues/1254), part of
[#1235](https://github.com/yschimke/compose-preview-server/issues/1235).

Teams discuss and decide designs in chat tools: Slack first, then Teams, Discord and Google Chat.
The agents there are Claude in Slack (Claude Tag) and the ChatGPT Slack app. They reach this server
through the **hosted `/mcp`** (`ServeCatalogMcp`, `ServeUiBuilderMcp`), and they have **no MCP Apps,
no file viewer and no editor**. This page covers how the main flows (seeing a design, picking an
alternative, discussing it, getting notified) work as plain text and images there. It also covers
how to set Slack up, what has and has not been verified, and the decision on syncing comments
(R4).

## What a Slack-hosted agent can do

From Anthropic's Claude Tag docs (summarised on the issue). Anything marked *not documented* was not
tested:

| Capability | Claude Tag |
| --- | --- |
| Remote MCP over HTTPS | Yes. Added as a connection (MCP Connector with OAuth, or a custom tool with Bearer, Basic or OAuth), bound to allowed hostnames. Credentials are injected at the proxy. |
| Local stdio MCP (`compose-preview mcp serve`) | *Not documented.* Plan on the hosted `/mcp`. |
| Images in a reply | Attachments: images up to 3.75 MB, at most 5 per message. |
| MCP Apps (`ui://`), `resource_link` rendering, elicitation | *Not documented.* |
| Buttons, menus, reactions as input | None. Only `@Claude` mentions and thread replies steer the agent. Reactions are context only. |
| Being woken by our events | No. PR subscriptions and scheduled routines only. |

Everything below follows from that table. A result has to be readable as text. A picture has to be
an https URL the agent can fetch and attach, or link. A choice is made by replying in the thread.

## 1. Text and image fallbacks on the hosted `/mcp`

Rule R1 (compose-agent-plugins `docs/agent-rules.md`) says the agent sees what the person sees. In a
chat thread the person sees text and attachments, so every result that would show them a picture
carries a **short-lived signed https PNG URL** on a host with a public origin, for example
`https://preview.coo.ee`. The origin comes from `--ui-builder-public-origin`, or failing that from
the GitHub OAuth callback base URL. The URL appears in three places:

- a `resource_link` named `Compose Preview render (https)` (or `UI-builder view (https)`);
- one line of text, `Image: <url>`, saying when the link expires and asking the agent to attach or
  link the image rather than describe it; Slack links a bare URL as written;
- `structuredContent.imageUrl` (on a render) or `image.url` (on a view).

The link is `<origin>/mcp/render.png?uri=…&exp=…&sig=…`. It is HMAC-signed with a per-process key,
valid for 10 minutes (`SIGNED_RESOURCE_TTL_SECONDS`), and carries no part of the grant token. It
grants that one image and nothing else. A catalog render is re-rendered from its signed resource
URI when fetched, so nothing is held in memory. Pixels that cannot be replayed (a full-page capture,
a historical blob, a UI-builder view or export, a contact sheet) are kept in memory for the life of
the link, at most `MAX_VIEW_IMAGES` of them at once.

Never base64 in the model's text and never `file://`. MCP `image` blocks still ride alongside, for
hosts that render them. Those blocks are base64 by protocol, but they are content blocks, not text
the model reads. A host with **no** public origin (a laptop, CI) has nothing to link to, so it keeps
the image blocks it always returned and changes nothing.

### Audit: hosted tools that show a person something

| Tool | Pixels | https link | Text line | Notes |
| --- | --- | --- | --- | --- |
| `catalog_render_preview` `observe=png` | image block | ✅ signed, re-rendered on fetch (#1228) | ✅ added here | The text line comes last, so a first-text reader still gets the provenance JSON. |
| `catalog_render_preview` `observe=scroll-png` | image block | ✅ **added here** (kept) | ✅ | A resource URI replays the viewport, so the capture is kept. |
| `catalog_render_preview` `observe=svg` / `scroll-svg` | SVG source as text | n/a | n/a | Vector source, not a picture a person sees in Slack. Use `png` there. |
| `catalog_render_preview` `observe=semantics` / `hash` | none | n/a | n/a | Text observations. |
| `catalog_render_matrix` `observe=png` | was base64 in the text | ✅ **added here**: one per cell, plus a contact sheet | ✅ for the sheet | With a public origin, the cell PNGs move to `_meta["composePreview/cellPngs"]` (the viewer reads them there, the model never does), each cell gets `index` and `imageUrl`, and one numbered contact sheet is linked. |
| `catalog_history_read` | image block | ✅ **added here** (kept) | ✅ | `renderUrl` may also point at `raw.githubusercontent.com`. |
| `preview-stories` | as `render_preview` | ✅ | ✅ | Goes through the same render path. |
| `ui_builder_view` | image block only with `inline=true` | ✅ (kept) | ✅ added here | |
| `ui_builder_render_native` | image block | ✅ **added here** (kept) | ✅ | |
| `ui_builder_export_document` (`png`) | image block | ✅ **added here** (kept) | ✅ | Text and JSON exports are text. |
| `ui_builder_export` | Kotlin, SVG or JSON text | n/a | n/a | Source, not a picture. |
| `ui_builder_render_design_matrix` (#1260) | image block only with `inline=true` | ✅ (kept) | ✅ | Shares `ui_builder_view`'s result path, so it got the link and the text line without its own change. |
| `ui_builder_guidelines_prompt` | one image block per picture | n/a | n/a | The pictures are the prompt's own attachments, in the order its user message numbers them, so they stay blocks for the agent's model; the JSON text carries no base64. |
| `ui_builder_check_design`, `ui_builder_diff_designs`, `ui_builder_list_revisions` | none | n/a | n/a | Text and JSON reports. |
| `resources/read` | `blob` | n/a | n/a | MCP resource content is base64 by protocol, and a chat host does not call it. |

`ServeCatalogMcpChatFallbackTest` and `ChatFallbackAssertions` pin the table. On a public origin
every row marked ✅ must have an https `resource_link` on the origin, an `Image: <url>` text line,
and no base64, `data:image` or `file://` anywhere in its text or structured content.

## 2. Pick from chat

Claude Tag has no buttons, select menus or elicitation, and it ignores reactions, so **a choice is a
thread reply**. The flow:

1. The agent renders the alternatives in one call:
   `catalog_render_matrix` with `observe=png` and the axes to vary. On a public origin the result
   carries `contactSheet.url`, a single PNG of every cell, each badged with its `index`
   (`ServeContactSheet`). A 24-cell matrix of phone screens stays under Slack's 3.75 MB limit, and
   it is one attachment rather than 24, since a Slack message carries at most five.
2. The agent attaches or links the sheet and lists the options as text:
   `1. uiMode=light, fontScale=1.0`, `2. …`. Each cell's own `imageUrl` is there if somebody wants
   to look closer.
3. The person replies `2`. The agent maps the number to the cell's `overrides` and carries on,
   calling the same operation a picker would have.

The local MCP server's thumbnail pickers (#1253, OpenAI form elicitation) are the same choice made
in a UI. In chat, the reply is the answer. Design branches use the same shape, built in:
`ui_builder_pick_branch` answers a client with no form with `outcome: "ask-in-chat"` — the numbered
list and one signed sheet link to post — and the reply's number names the branch to pass to
`ui_builder_merge_branch`
([`CATALOG_MCP.md`](CATALOG_MCP.md#branches-explore-alternatives-then-merge-one)).

For a richer side-by-side, the agent can publish a claude.ai artifact page built from the signed
render URLs. No server work is needed. Remember the links expire after 10 minutes, so the page
should embed the images it fetched rather than hotlink them.

## 3. Thread links

A design's `links.thread` (`ui_builder_set_links`, `UI_BUILDER_LINKS.md`) is the permalink of the
conversation it is discussed in. **A Slack thread is a `thread` whose URL is a Slack message
permalink.** The record's shape is the published `DesignLinksV1` contract, so it is not a new
field. `ui_builder_get_links` reports, next to the record:

```json
{
  "thread": "https://acme.slack.com/archives/C0123ABCD/p1700000000000200",
  "threadKind": "slack",
  "slackThread": {
    "workspace": "acme",
    "channel": "C0123ABCD",
    "messageTs": "1700000000.000200",
    "threadTs": "1700000000.000200"
  }
}
```

`threadKind` is `slack`, `teams`, `discord`, `google-chat` or `other`, read from the host alone
(`ServeChatThreadLinks`). `threadTs` is the thread root: a reply's permalink carries it as
`thread_ts`. An agent tagged in a thread can compare its own channel and root against these and
know it is in the design's thread. Nothing is fetched, and the server holds no Slack credential.

### Decision (R4): the design's comment board stays canonical, link-only

Slack replies are **not imported** as design comments, and design comments are **not mirrored** into
the thread one by one. The design's board (`UI_BUILDER_COMMENTS.md`) is the record. The thread gets
**notifications that link back** (section 4), and the agent posts the design link plus a summary of
unacknowledged comments when asked.

Why:

- **Identity.** Importing a Slack reply means asserting that Slack user X is design actor Y. The
  server has no Slack credential and no mapping, and inventing one would let any thread participant
  put words on the design board under a name the board then repeats. Marking imports as
  "unverified" is a second class of comment every reader has to handle.
- **No inbound path.** Claude Tag cannot be woken by our events and posts only when tagged. A
  two-way sync needs a Slack app with its own identity and install, which is out of scope here.
- **Claude Tag's memory is per channel.** The design's board is the one place the conversation is
  whole across channels, PRs and editors.

What a team does instead: tag `@Claude` in the thread with "summarise open comments on design X".
The agent calls `ui_builder_list_comments`, which is cheap and idempotent, and posts the result with
links. To put a decision on the record, the agent calls `ui_builder_post_comment` as itself, which
the board shows as an agent comment.

Revisit this if a Slack app with workspace OAuth becomes in scope. Author mapping would then rest
on a real identity.

## 4. Notifications

Already shipped: `ServeUiBuilderCommentWebhook`, configured with `--ui-builder-comment-webhook <url>`
and `--ui-builder-comment-webhook-format plain|slack|teams|google-chat`.

- **Opt-in.** Nothing is posted unless the operator sets the flag. The URL must be `https` (or
  loopback), and it is a credential: it is never logged, and the log names it by a short digest.
- **Rate-limited (added here).** Two token buckets are checked before anything is queued:
  `perDesignPerMinute` (default 12) and `totalPerMinute` (default 40, under Slack's limit of about
  one message per second). Events over a limit are dropped, not delayed. One log line is written
  per throttled design per window, and a second when that design gets through again. The board is
  canonical, so a dropped notification loses no content.
- **No secrets or grants in URLs.** A notification links to the design's permalink
  (`/ui-builder/<id>#thread=<id>`), which opens only for somebody who may read the design. Private
  designs are posted as a link with no title, excerpt or author.
- **The design's thread.** Each event carries the design's `links.thread`. The Slack, Teams and
  Google Chat bodies offer it as a second link, labelled by platform ("Slack thread for this
  design"). An incoming webhook posts to its configured channel and cannot reply into a specific
  thread. A relay reading the `plain` body can use `design.thread` (and the `slackThread` parse
  above) to post into the thread with `chat.postMessage`.

**Comment events** (the default): a new thread, a reply, a resolve, a reopen. Reactions,
acknowledgements and deletes are deliberately silent (see the class KDoc).

**Design activity** (opt-in by name with `--ui-builder-webhook-events`, `ServeUiBuilderDesignActivity`):

| Event | Source | Fires when |
| --- | --- | --- |
| `fork` | `ui_builder_fork_design` (#1259), via the ancestry store | An alternative is proposed. It is announced on the design it was forked from, with the fork's own permalink. |
| `decision` | `ui_builder_record_decision` (#1260), via the review store | A revision is approved or rejected. The note is quoted on public designs. |
| `implementation` | `ui_builder_set_implementation` (#1260), via the review store | The implementing pull request is linked, or its status (`draft`, `open`, `merged`, `closed`) or its preview match (`match`, `mismatch`) changes. "Merged" is the merge. |

- `--ui-builder-webhook-events` takes `comments`, `fork`, `decision`, `implementation` or `all`,
  comma-separated, and defaults to `comments`. A channel that opted into comment activity gets
  nothing new on upgrade, and a typo is refused at startup rather than silently posting less.
- Design activity shares the comment webhook's queue, its two rate-limit buckets, its single retry
  and its private-design rule. A private design gets a link-only event: the kind, the design id, the
  revision and the permalink, with no title, actor, note or pull request.
- The `plain` body is `compose-preview/design-activity-webhook/v1`. That shape belongs to this
  server, like the review and ancestry records it is built from. It reuses the published
  `DesignCommentWebhookDesignV1` for the design. Move it to `compose-preview-contracts` if a relay
  outside this repository comes to depend on it.
- **No "failed render" event, deliberately.** A render here is commissioned by a caller and
  answered to that caller, who already has the failure. Nothing records a design as "currently
  failing", so the event would broadcast one caller's transient error, again on every retry. The
  persistent signal, an implementation whose previews do not match the design, is announced as an
  `implementation` change.

**Agents are not woken by any of this.** For follow-ups, Claude Tag uses what it supports: a PR
subscription, or a routine that polls `ui_builder_list_comments` (or `ui_builder_await_comments`
with a short wait). Both calls are idempotent and cheap to repeat.

## 5. Link previews (unfurls)

**OpenGraph tags, no Slack app.** This is the lightest option that works: Slack, Teams, Discord,
Google Chat and iMessage all read `og:title`, `og:description` and `og:image`, and nothing needs
installing. Already shipped (`ServeWeb.UnfurlMetadata`, `ServeSocialCard`):

- **Preview viewer and browse pages** advertise their rendered PNG or a drawn 1200×630 card, with
  honest sizes and `summary_large_image` only for a shape that fills one. Covered by
  `ServeWebTest` and `ServeHttpRoutingTest` ("each browse page unfurls the content expected for
  that page").
- **Designs** unfurl as themselves only when public (readable signed out). A private design serves
  the generic shell and never lends its title to an unfurler. Covered by
  `ServeUiBuilderHistoryAndUnfurlTest`.
- **Images answer `HEAD`**, because unfurlers probe before fetching (`ServePinnedRevisionTest`).

Setup: none beyond a public origin, so the tags carry absolute URLs. A Slack app with
`links:read` / `chat:write` unfurling would add status for private designs, and needs an install
and a credential. Not needed today.

## Slack setup (Claude Tag)

1. An org owner pairs the workspace at `claude.ai/admin-settings/claude-tag`.
2. Add a connection for the hosted MCP server: URL `https://preview.coo.ee/mcp` (or your host's
   `/mcp`), allowed hostname `preview.coo.ee`.
   - **OAuth** (preferred): per-user grants through `ServeMcpOAuth`, the same flow the Claude
     Connectors Directory uses (compose-agent-plugins#54).
   - **Bearer**: one agent-grant token per connection (`request_access`, approved by a human).
     Everyone in the scope shares that token's capabilities.
3. Optionally attach the compose-agent-plugins skills to the access bundle. They apply to new threads.
4. For notifications, create a Slack incoming webhook for the channel and start the server with
   `--ui-builder-comment-webhook <url> --ui-builder-comment-webhook-format slack`. Add
   `--ui-builder-webhook-events all` (or a list) to post forks, decisions and implementation changes
   as well as comments.

## Verification status

| Check | Status |
| --- | --- |
| Hosted results carry https image links and no inline pixels in text | ✅ server tests (`ServeCatalogMcpChatFallbackTest`) |
| Contact sheet fits a Slack attachment | ✅ server test (`ServeContactSheetTest`, 24 phone screens < 3.75 MB) |
| Slack permalink parsing, `threadKind` | ✅ server tests (`ServeChatThreadLinksTest`) |
| Webhook payload shapes, opt-in, rate limit | ✅ server tests (`ServeUiBuilderCommentWebhookTest`) |
| Design-activity events: diff, privacy, bodies, opt-in, shared rate limit | ✅ server tests (`ServeUiBuilderDesignActivityTest`) |
| OpenGraph on viewer, browse and design pages | ✅ existing server tests |
| Claude Tag: connection setup against `preview.coo.ee/mcp` | ⬜ not yet verified in a live workspace |
| Claude Tag: attaching an image from a signed URL | ⬜ not yet verified |
| Claude Tag: whether a tool's MCP `ImageContent` reaches the thread | ⬜ not yet verified |
| Claude Tag: compose-agent-plugins skills load | ⬜ not yet verified |
| Claude Tag: pick by reply, round trip | ⬜ not yet verified |
| Claude Tag: PR-subscription follow-up | ⬜ not yet verified |
| ChatGPT Slack app | ⬜ not yet verified |

The live checks belong in compose-agent-plugins' harness matrix, in a new Claude Tag column next to
Q8/Q9/Q16. That follow-up, along with the skill wording ("in Slack, attach or link the image; don't
describe it from memory"), is tracked in compose-agent-plugins.
