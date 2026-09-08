# A multiplayer workflow for design, engineering and product

**Status: proposal (2026-09).** Written against what is on `main` and on `preview.coo.ee` today,
not against a greenfield. Every surface it leans on is cited; every gap it names is one the
existing documents already circle. It answers five questions the way they were asked: what would a
multiplayer-forward workflow look like end to end, whether it needs better references, "product
sagas", longer tokens or better access control, and what a team adopting it looks like.

The one-paragraph version. **This server already has the rare half of multiplayer: a shared,
revisioned, addressable object that a person in a browser and an agent over MCP edit as equals,
plus a discussion pinned to it that wakes the agent.** Claude Tag has the other half: a shared
conversation, in the place the team already talks, with one agent everybody in the channel directs.
Neither needs to become the other. The work is the *joins* — a citation an agent can resolve from
any of those places, back-links from a design to the issue and the Figma frame it is for, a way for
the design's discussion to surface where the team reads, and one durable identity for the
long-running participants (a Slack agent, a routine, CI) that the short-lived grant deliberately is
not. Nothing here asks for a chat inside the server, a tracker inside the server, or a longer
token.

---

## 1. Two meanings of "multiplayer", and why both are needed

The phrase is used for two different shapes, and the proposal depends on keeping them apart.

| | Shared **conversation** (Claude Tag) | Shared **object** (this server) |
| --- | --- | --- |
| What is shared | A Slack thread and the channel's memory | A design, a preview at a publish, a comparison |
| Who participates | Everybody in the channel; one agent per channel, an org service identity | Owner, viewers, editors; each agent under a grant delegated by a named person |
| Where the state lives | Slack, and whatever the agent wrote elsewhere | The revision log, the comment board, the catalog's delivery branch |
| What converges | Nothing, structurally — a thread is a log | The document: every client sees revision *n* or is told to resync |
| How the agent hears | It is tagged, or it is in ambient mode | `ui_builder_await_design` / `ui_builder_await_comments`, and the `comments` block on every reply |
| Identity of a change | The service account, per its documentation | `agent:<fingerprint>` acting for `github:<login>` |

Claude Tag, as documented at the time of writing: it is @-mentioned in channels and threads, reads
the thread (or recent channel history) for context, works asynchronously in-thread over hours or
days, can create artifacts and open GitHub pull requests, holds admin-provisioned connectors
(GitHub, Figma, Jira, Linear, Drive, MCP servers), keeps **channel-scoped memory**, and — the part
that matters most here — **acts as one organisation-level service identity, not as the person who
tagged it**. The person who typed the request is context, not a credential.

That last row is the whole design tension. Everything in [`AGENT_ACCESS_GRANTS.md`](AGENT_ACCESS_GRANTS.md)
is built on a grant being a *delegation from a named human*: an agent's design is owned by the
approver, the agent reaches what the approver reaches, and the audit line names both. A Slack agent
that is the same identity for everyone in the channel cannot be delegated *from* anybody in
particular. Section 4.4 is about that, and it is the one place the answer is "yes, build
something".

A conversation without an object drifts: three people and an agent agree in a thread and nothing
is committed anywhere a fourth person can open. An object without a conversation is invisible: a
design with an open Talk thread that nobody outside the editor knows about is a design nobody
reviews. The workflow below puts the object at the centre and lets the conversation happen wherever
the team already has one, with the agent as the thing that carries between them.

## 2. What is already built, read as multiplayer primitives

This is an inventory of what exists, ordered by how much of the workflow each already carries.

| Primitive | Where it is | What it gives a multiplayer flow |
| --- | --- | --- |
| A design with a canonical URL, revisions, presence and an owner/viewer/editor list | `/ui-builder/<catalog>/<design>`; [`UI_BUILDER_LIVE_SESSION.md`](UI_BUILDER_LIVE_SESSION.md), `ui_builder_share_design` | The shared object. A browser and an MCP client land in one `PersistentUiBuilderService.apply`, and every accepted write reaches every subscriber |
| Comments pinned to a node, a mark or a point, with react / acknowledge / resolve kept distinct | [`UI_BUILDER_COMMENTS.md`](UI_BUILDER_COMMENTS.md) | The in-context discussion. Stored *beside* the design, so talking never moves the revision |
| The agent is woken, and told what it has not read, on every reply | `ui_builder_await_comments`; the `comments` block on `ui_builder_get_design` / `_apply` / `_export` | An agent mid-edit learns a designer spoke without polling |
| A reference overlay: paste a Figma frame, mark it up, erase, capture a component, promote it into the tree | [`UI_BUILDER_REFERENCE_OVERLAY.md`](UI_BUILDER_REFERENCE_OVERLAY.md) | The cheapest possible Figma join: the designer's clipboard |
| Designs in the app's repository, `ui-builder/designs/index.json`, and a library to open one from | [`UI_BUILDER_PROJECT_DESIGNS.md`](UI_BUILDER_PROJECT_DESIGNS.md) | The design survives the session and can be argued about in a pull request |
| Deterministic export: Compose source, Figma-compatible SVG, PNG, all pinned to a revision | `ui_builder_export`, `compose-preview-server design …` | The handoff to engineering is a file, not a screenshot |
| Preview permalinks that unfurl, with the render as the card | `/<system>/p/<id>`, Open Graph cards, `ServeSocialCard` ([public-preview-server.md](https://github.com/yschimke/compose-ai-tools/blob/main/docs/public-preview-server.md#pasting-a-link-into-slack-or-google-chat)) | A link pasted into Slack *is* the review artefact |
| A prefilled issue carrying a `compose-parity-locator/v1` block, and an issue index the compare page reads back | `ServeIssueReport`, [`COMPONENT_PARITY_WORKFLOW.md`](COMPONENT_PARITY_WORKFLOW.md) | "We know about this one" survives the page reload, and the tracker is where the human already is |
| History: a per-preview timeline across publishes, diffable without fetching pixels | `history_list` / `history_diff` / `history_read` | "Did the last publish move this?" answered from data |
| One MCP endpoint over both catalogs and designs, with a device-flow grant an agent can obtain from inside the protocol | [`CATALOG_MCP.md`](CATALOG_MCP.md), `request_access` / `poll_access` | Any MCP host — Claude Code, Claude Tag with a custom connector, Codex — reaches the same objects |
| `@claude` on any issue or PR, with a visual-evidence contract; weekly triage; the preview-diff sticky comment | [`AGENT_INVOCATION.md`](https://github.com/yschimke/compose-ai-tools/blob/main/docs/AGENT_INVOCATION.md), `claude-triage.yml` | The GitHub-side agent already exists and already has to show pixels |
| Design-led catalogs that never write to Figma, and a parity direction with teeth | `.design-parity.json` in m3-catalog / wear-m3-catalog | The Figma boundary is decided, not negotiable per PR |

What the inventory says, read as a whole: the objects, the discussion and the agent's wake-up are
done. What is thin is *arrival* — how a person in Slack, in Figma or in the tracker gets to the
object with the right context, and how the object's state gets back to them.

## 3. The workflow, end to end

Three seats — product, design, engineering — and one assistant that today shows up as **three
actors**: the Slack agent (an org service account), the builder's agent (a grant, `agent:<fp>`,
acting for whoever approved it), and `claude[bot]` on GitHub. That the "same" assistant is three
identities with three credentials is the first finding, and section 6 is partly about reducing it
to two.

The object at the centre is one **design**, pinned to one catalog revision, with one canonical URL.
The saga (section 4.2) is the tracker issue it is for. The conversation is wherever it is — a Slack
thread, the design's Talk panel, the PR — and the agent carries between them.

### Stage 0 — the brief (product)

The PM writes the ask where they already write asks: an issue (GitHub, Linear or Jira), and a Slack
thread that links it. In the thread they tag the agent: *"start a checkout design against the app's
catalog, share it with this thread, and post the link"*.

The agent, holding a preview-server grant through its MCP connector, calls
`ui_builder_create_design` (from a template copy, so the catalog pin is real), `ui_builder_share_design`
for the people in the thread, and posts the permalink. **Today the permalink unfurls as text**: the
design page advertises no Open Graph card, unlike every catalog page. Section 6 item 1 fixes that,
so the link shows the blank scaffold and, later, the current render.

The agent also writes the issue URL and the Slack thread permalink **beside the design** (item 2).
From here on, anything that opens the design can find its brief, and anything that opens the brief
can find the design.

### Stage 1 — the design (design + agent)

The designer opens the URL. They paste a Figma frame from their clipboard onto the reference
overlay — the join that already works and needs no integration — set it to a 50% overlay, and start
building: drag a scaffold, a top bar, a list. Where the kit has the component the builder lacks,
they circle it and write a Talk comment pinned to the mark: *"this is the kit's segmented button,
build it"*.

The agent, sitting in `ui_builder_await_comments` (or simply receiving the `comments` block on its
next call), reacts 👀, inserts the nodes with `ui_builder_apply`, replies with the revision, and
leaves the thread open for the designer to resolve. Presence shows both of them; the designer sees
the nodes appear without reloading. If the designer wants the *kit's* pixels rather than the
builder's, the agent fetches the node through its Figma connector — `get_screenshot` on the
`figma:<file>/<node>` reference the catalog already records per component — and puts it on the
overlay as a piece (item 5, since there is no `ui_builder_put_reference` tool today; the routes
exist, the tool does not).

Nothing writes to Figma. For the design-led catalogs that is enforced by configuration; for an app
screen the designer decides when the builder's SVG export goes *into* their file, and does it by
importing it. The direction stays one-way and explicit.

### Stage 2 — review (product + design + engineering)

The PM does not open the builder. They read the Slack thread, where the agent has posted the
current render (the `png` export) and, on request, a **matrix** — `render_matrix` over device ×
font scale × locale — because "does it survive the large-font setting on the small phone" is the
question a PM actually has. The link in the thread unfurls to the same pixels.

Review comments land in two places and both are fine: a designer's *"the gap above the card is
wrong"* goes into Talk, pinned to the node; a PM's *"the primary action should say Continue"* goes
into the Slack thread, and the agent — tagged there — turns it into a `setProperty` and replies in
both places. The rule is that **the object is authoritative and the thread is a view of it**: an
edit made from Slack is an ordinary `ui_builder_apply` that every open browser sees. A Talk thread
resolved in the editor is what closes the question, not a Slack reply.

What is missing here is outbound: a new Talk thread is invisible to anyone not in the editor. Item
3 is a per-host or per-design webhook that posts *"new thread on `checkout` by Dana, pinned to
`primary-button`: …"* with the thread permalink — Slack's incoming-webhook shape, no app needed —
which is how a PM finds out the designer has a question without either of them polling.

### Stage 3 — build (engineering + agent)

When the design settles, the engineer — or the agent, on a `@claude` from the PR or from Slack —
runs `ui_builder_export` for `compose`, commits the Kotlin **and** the design's operations file under
`ui-builder/designs/` (so the screen under review and the design it came from are in the same
PR), and opens a PR on an `agent/` branch. The preview-diff bot posts before/after renders; the PR
body carries them as commit-pinned images, per the repo rule.

The agent resolves the Talk threads it addressed with the PR link in the reply. The design's
back-links gain the PR URL. From now on the design has three anchors — issue, Figma node, PR — and
any of the three finds the other two.

### Stage 4 — verify (everyone, mostly the agent)

Engineering's implementation renders through the app's own catalog, and the focused comparison
puts it beside the kit reference. A divergence is filed from the compare page with the prefilled
report; the locator block keys it; the parity issue index makes it a *known* difference the next
reader sees rather than a fresh alarm. A difference the team accepts goes into
`.design-parity.json` as an accepted difference with the issue link — which is the one "saga-like"
record this stack already keeps, and the right shape for it.

The agent's part is triage: `history_diff` to say whether a publish moved a preview,
`diff_semantics` to say whether the structure moved, the flake oracle when a changed preview's
source was not touched. None of that needs a person until there is a verdict to make.

### Stage 5 — ship, and the next round

The catalog publishes; the delivery branch's history records the render; the weekly triage sweeps
stalled review feedback into follow-up issues. The design in `ui-builder/designs/` is either
retired (the screen is code now) or kept because the next iteration is already being discussed —
and the project-designs document is explicit that a design nobody is editing has no reason to stay.

### The same workflow as a table

| Stage | Who acts | Where they are | The object | The agent's surface |
| --- | --- | --- | --- | --- |
| 0 Brief | PM | Issue tracker, Slack | Issue → design created, shared, linked | Tag in Slack → `ui_builder_create_design`, `_share_design`; writes links |
| 1 Design | Designer, agent | Builder; Figma as reference | Design revisions, reference overlay, Talk threads | `ui_builder_await_comments`, `_apply`, Figma `get_screenshot` → reference |
| 2 Review | PM, designer, engineer | Slack thread, Talk panel | Render, matrix, resolved threads | Tag in Slack ↔ `_post_comment` / `_apply`; webhook out |
| 3 Build | Engineer, agent | PR | Exported Kotlin + design file in one PR | `ui_builder_export`; `@claude` on the PR; preview-diff bot |
| 4 Verify | Agent, then a human verdict | Compare page, tracker | Parity issue with locator; accepted differences | `history_diff`, `diff_semantics`, prefilled issue |
| 5 Ship | CI, weekly triage | Delivery branch, triage issue | Published catalog, history | `claude-triage.yml` |

## 4. The four questions

### 4.1 Do we need better ways to reference content?

**Yes, but not new identifiers — a resolver and a card.** The identifiers exist and are precise:

- a preview: `/<system>/p/<id>` plus its override query, the `compose-preview://catalog/<catalog>/<id>`
  resource URI, a history entry by commit or blob, and the `compose-parity-locator/v1` block that
  spells all of it out in an issue body;
- a design: `/ui-builder/<catalog>/<design>`, a `revision`, a `nodeId`, a comment `threadId`;
- a comparison: `/<system>/compare/<id>?reference=<ref>`;
- a kit node: `figma:<file>/<node>`, recorded on every catalog component.

Three things are missing, and they are the same thing seen from three sides.

1. **A design URL cannot say which revision, node or thread it means.** The path names the design;
   identity and transport live in the query; nothing carries "revision 41, node `primary-button`,
   thread `t-…`". A designer cannot paste "look at this thread" into Slack; an agent told "the
   button on the checkout design" has to search. The additive fix is `?revision=` / `?node=` /
   `#thread=` on the canonical URL, honoured by the editor (select the node, open the panel on the
   thread) and by the export routes (`revision` is already the parameter they take).
2. **A link handed to an agent is opaque.** An agent in Slack receives a URL and has to know this
   server's grammar to turn it into `ui_builder_get_design` with the right arguments, or
   `render_preview` with the right overrides. One tool — `resolve_reference(url)` — that answers
   with the typed object (`kind`, the ids, the revision, and the call that fetches it) makes any
   link from any of the team's tools actionable without the agent learning the routes. It is the
   MCP-side twin of the locator block, and the locator's fields are its schema.
3. **A design does not unfurl.** Catalog and viewer pages carry a drawn or rendered card; the
   builder's page carries none, so the one URL this workflow is built around arrives in Slack as
   bare text. The card should be the design's current PNG export, or the blank frame — served
   content-addressed the way `/hero/` and `/social/` already are, and, since designs are private,
   only where the design is readable anonymously or the unfurler is on the allowlist. A private
   design's card is its title and catalog and nothing else, which is still better than nothing.

The parity locator is the precedent for all three: it exists because a report without a precise
citation could not be matched to a known difference. The same holds for a design.

### 4.2 Do we need product sagas?

Read as *a long-lived thread of work that spans the brief, the design, the build and the ship, and
outlives any one session or tool*: **the team already has one, and it is the issue.** Build a
second one inside the server and every team has to choose which to trust.

What the server lacks is not the saga but the **back-links that let the saga be assembled from the
outside**. A design today knows its catalog pin and nothing else — not the issue it is for, not the
Figma frame it reproduces, not the PR that implemented it. So:

- **A `links` record beside each design**, stored exactly as comments and references are (its own
  directory, never in the document, never on the wire), holding typed URLs: `issue`, `figma`, `pr`,
  `thread`. Written from the Screen panel or by the agent; carried in `ui_builder_get_design` and in
  the `ui-builder/designs/index.json` entry (additively, so a project's checked-in index says what
  each design is for). Read the other way, `GET /ui-builder/links?issue=<url>` lists the designs
  citing an issue — which is the "feature view" a PM wants, and it costs a directory scan.
- **Accepted differences stay in `.design-parity.json`**, with their issue URL, as they are: that
  file is the durable record of "we decided this", and it lives in the repository where a decision
  belongs.
- **The Slack channel is the saga for a small team**, and Claude Tag's channel-scoped memory is
  precisely a saga store for the conversational half — which is a reason to leave that half to it.

The thing to resist is a `Saga` object with its own lifecycle in this server. The moment it exists,
it needs status, assignment, and a reason to prefer it over Linear, and it is a tracker.

### 4.3 Do we need longer tokens?

**No — a different kind of credential for a different kind of participant.** The grant is the
right shape for the participant it was designed for: an agent session a named person is watching,
whose credential should die when the session does, and whose blast radius on a leak is minutes.
Every argument in [`AGENT_ACCESS_GRANTS.md`](AGENT_ACCESS_GRANTS.md#lifetime-revocation-blast-radius)
for a short, in-memory, unprinted token holds unchanged.

Multiplayer adds two participants the grant was **not** designed for, and lengthening the TTL
serves neither:

- **A long-running agent nobody is watching** — the Slack channel's agent over a week, a routine
  that renders a matrix every Monday, CI. What it needs is a credential that *survives a redeploy
  and is not delegated from an individual*, because there is no individual. That is an installation
  identity, and the hosted-service plan already names it: a GitHub App. A 24-hour grant approved by
  whoever happened to be online is the wrong tool even if it were a 30-day grant.
- **An interactive session that outlives a restart.** Grants live in memory, so a redeploy drops
  every bearer regardless of remaining TTL, and in a workflow where the agent is mid-thread with a
  designer that is a visible interruption. The fix is **persistence, not length**: store issued
  grants encrypted under a key in `/config`, keep the TTLs, keep `unknown` for revoked. The
  `request_access` / `poll_access` in-protocol flow already makes re-entry cheap; persistence makes
  it unnecessary for a restart.

So: keep grants short; add one durable service actor (4.4); persist grants across restarts. None of
those is "longer".

### 4.4 Do we need better access control?

**Yes, at one level: who can be shared with, and who a bot is.** The per-design model —
owner / viewer / editor, actor ids, delegation, authorisation checked twice on every route — is
right and does not need replacing. Three things sit above it and are missing:

1. **Groups.** Sharing is per actor id. "Share with the design team" is five `github:` logins typed
   one at a time, and a new hire is a change to every design. The GitHub verdict the server already
   holds is a team-membership check away: a share target `team:<org>/<slug>`, resolved against
   GitHub teams at access-check time (cached with the session), and shown in the access list as the
   team. The single-repo `repositoryAccess` verdict is the same mechanism at a coarser grain.
2. **A service actor.** Claude Tag acts as an organisation identity; a routine and CI act as nobody.
   Today the only way any of them reaches a design is a grant approved by a person, which then
   *delegates from that person* — so a design created from a Slack thread is owned by whoever
   approved the Slack agent's grant, which is at best surprising and at worst somebody who has
   left. The fix is a **service actor** (`app:<installation>`) that can own designs, be shared
   with, and be listed — with its own credential (the GitHub App installation token, or an
   operator-minted long-lived key that is *not* a grant), and with `authorKind: agent` on
   everything it writes. Delegation stays for the interactive case; the service actor is for the
   participant with no principal.
3. **Attribution through a service identity.** When the Slack agent posts a comment because a
   named person asked it to, the board should be able to say so: a `via` field on a comment —
   *"agent (for @dana)"* — that is **cosmetic, exactly as `authorKind` is**, never a permission.
   The permission is the service actor's; the credit is the person's. Without this, a review that
   went through Slack reads on the board as the bot talking to itself.

Beyond those three is the org / tenant / private-catalog model the hosted plan puts in its Phase 2.
It is real work and this workflow does not need it: a team on its own box, or on `preview.coo.ee`
with private designs, is served by the three items above.

### 4.5 Is the framing wrong anywhere?

Two places, one in each direction.

**Treating Slack as the workspace.** It is the lobby. The multiplayer property that matters — two
people and an agent converging on one document — is a property of the object, and Slack has no
object; it has a log. The workflow above keeps the design authoritative and makes the thread a view
of it. A team that tries to run the design *in* the thread ("@Claude make the button blue" ×20) gets
twenty revisions and no design anyone can open. That is the failure Claude Tag's own persistence
would make comfortable rather than prevent.

**Treating this server as the tracker.** The Talk panel is the design's in-context discussion, the
way Figma comments are a frame's. It is not where a feature is tracked, and it must not grow
status, assignment or milestones. The parity work already settled this the same way: the issue
index *reads* the tracker, it does not replace it.

And a smaller one: the appetite for "better references" is real, but the shortage is not of
identifiers. It is that nothing resolves them for an agent and nothing unfurls them for a person.

## 5. The team that adopts this

A concrete profile, because the workflow should be checked against one. A mobile product team of
eight to twelve: one PM, one or two designers, four to six Android / Kotlin Multiplatform
engineers, sometimes a QA. They own an app, not a design system, and they consume a kit (Material
3, or their own Figma library) rather than publish one.

**Where they interact today, and what gets lost at each handoff:**

| Handoff | Where it happens | What is lost |
| --- | --- | --- |
| Brief → design | A PRD in Docs/Notion, a Linear or Jira issue, a Slack thread | The design file never links the issue; the issue links a Figma URL that moves |
| Design → engineering | Figma Dev Mode, a screenshot in the issue, a Slack DM | The frame the engineer built from is not the frame that shipped; "which variant" is a conversation |
| Engineering → design review | A screenshot pasted into the PR, or a build on a test device | The screenshot is one device, one locale, one font scale; the designer reviews pixels with no way to point at a node |
| Review → tracker | A Slack reply, a Figma comment, an issue with a screenshot | The report has no locator, so the second reporter files it again |
| Ship → next round | Nothing durable | The design is stale the day the screen ships |

What changes for each seat, and what deliberately does not:

- **The designer keeps Figma.** The kit is the source of truth for tokens and components, and the
  design-led catalogs enforce that nothing writes back. What they gain is a canvas that draws the
  *real* components, an overlay to build against their own frame, and comments that pin to a node
  rather than a screenshot. They do not have to learn the builder to review: the unfurled render in
  Slack and the compare page are review surfaces.
- **The engineer keeps the PR.** The exported Kotlin is what they review, the committed design file
  is what they diff, and the preview-diff bot is what they already read. The change is that the
  design they build from is the design in the repo, pinned to a catalog revision, rather than a
  Dev Mode inspection.
- **The PM keeps Slack and the tracker.** They never open the builder. What they gain is that the
  link in the thread shows the current state, a matrix answers the device question, and the issue
  they filed is linked from the design that answers it. Claude Tag is their interface to all of it.
- **Nobody moves their conversation.** Talk is for the comment that needs a node under it; the
  thread is for everything else; the webhook is the bridge.

The adoption path is incremental and each step is useful alone: (1) publish the app's catalog and
paste links; (2) turn on the builder against the app's `components.json`; (3) give the Slack agent
a connector to `/mcp`; (4) commit designs under `ui-builder/designs/`.

## 6. What to build, in order

Each item is small enough to be one pull request and stands on its own. The repository is named by
the layer rule ([`REPOSITORY_LAYERS.md`](https://github.com/yschimke/compose-ai-tools/blob/main/docs/design/REPOSITORY_LAYERS.md)):
everything that is a route or a tool is this repository; a wire shape two repositories must agree
on is `compose-preview-contracts`.

| # | Build | Size | Where | Unblocks |
| --- | --- | --- | --- | --- |
| 1 | **Design URL selectors and an unfurl card.** `?revision=` / `?node=` / `#thread=` on `/ui-builder/<catalog>/<design>`; an `og:image` for the design page from its PNG export or blank frame, content-addressed, privacy-aware | S | server, ui-builder | Stage 0, 2; 4.1 |
| 2 | **`links` beside the design.** A `links/<digest>.json` store, Screen-panel editor, `ui_builder_set_links` / carried in `ui_builder_get_design`, an additive `links` field on the project index, `GET /ui-builder/links?issue=` | S | server, ui-builder, contracts (index schema) | 4.2 |
| 3 | **Outbound comment webhook.** `--ui-builder-comment-webhook <url>` (and per-design override in `links.thread`), posting new threads and replies with the thread permalink in Slack's incoming-webhook shape | S | server | Stage 2 |
| 4 | **`resolve_reference(url)`** on `/mcp`, answering `kind`, ids, revision and the fetching call; the locator block's fields as its schema | S | server (mcp), contracts | 4.1; every agent seat |
| 5 | **`ui_builder_put_reference`** — the reference-overlay routes as a tool (image bytes, or a piece placed at a rect), so an agent with a Figma connector can put the kit's frame under the design | S | server (mcp) | Stage 1 |
| 6 | **Persist grants across restart.** Encrypted at rest under a `/config` key; TTLs and `unknown` unchanged | M | server | 4.3 |
| 7 | **Service actor and `via` attribution.** `app:<installation>` as an owner / share target with its own credential; `via` on comments, cosmetic; `authorKind: agent` on everything it writes | M | server, ui-builder-runtime, contracts | 4.4; Claude Tag, routines, CI |
| 8 | **Team share targets.** `team:<org>/<slug>` resolved against GitHub teams; shown as the team in the access list | M | server | 4.4 |
| 9 | **Slack-side connector recipe.** A documented Claude Tag connector to `/mcp` with the device-flow grant, and the prompt shape that keeps the design authoritative | docs | docs/ | Stage 0–2 |

Items 1–5 are the whole of "better references" and "sagas" and are all additive. Items 6–8 are the
identity work and are where the design decisions live; 7 should come with its own document. Item 9
is the adoption guide.

## 7. What not to build

- **A chat inside the server.** Talk is a comment board pinned to a design and should stay one.
- **A saga, milestone or status object.** The issue is the saga; `links` is the join.
- **A longer grant TTL.** Persist, and add a service actor; do not lengthen.
- **Figma write-back for design-led catalogs.** The direction is decided by `.design-parity.json`
  and enforced by the plugin; a multiplayer flow does not change who is authoritative.
- **A Slack app.** An incoming webhook out, and Claude Tag's own connector in, cover the flow. A
  bespoke app is a second identity system and a second thing to install.
- **Tracker integrations in the server.** The prefilled issue and the `links` record make the
  tracker pluggable; the agent, with the team's connector, does the writing.
- **A second identifier scheme.** The ids exist; build the resolver and the card.

## 8. Risks worth naming

- **Comments are data the agent reads as instructions.** The `comments` block already carries a
  `hint` telling the agent what to do, by design. A shared design is a place where anyone with
  editor access can write text an agent will act on. The mitigations are the existing ones —
  the agent reaches only what its principal reaches, and nothing on the board widens that — plus
  a rule for the service actor (7): it acts on a comment only from an actor the design's owner has
  shared with, never on one from a `via` it cannot verify.
- **Noise.** An agent that reacts to every comment and posts to every thread trains people to
  ignore it. React on receipt, reply when there is something to say, resolve when it is done — the
  three-act model in [`UI_BUILDER_COMMENTS.md`](UI_BUILDER_COMMENTS.md) — and the webhook posts
  threads, not reactions.
- **Two conversations diverging.** A question answered in Slack and left open in Talk, or the
  reverse. The rule that only the board closes a thread, and the webhook that shows the board's
  state in the thread, are what keep them from drifting; a team that adopts only Slack will drift.
- **Attribution rules.** The repositories reject agent identities on commits and agent
  `Co-authored-by` trailers, and Claude Tag opens PRs as the Claude GitHub App. Bot accounts are
  exempt and the PR body rule is unchanged, so the workflow is compatible — but a team adopting
  this should read [`AGENTS.md`](../../AGENTS.md#enforced-rules) before their first Slack-opened PR.
- **The restart.** Until item 6, a redeploy mid-review drops every agent's grant at once. The
  in-protocol re-request makes recovery a tool call rather than a human, but it is still an
  interruption a person notices.

## 9. How to tell it worked

Not a dashboard; four things a team could report after a quarter.

- The time from a Figma frame to a PR with rendered before/after evidence, measured on the issues
  that link a design.
- The share of Talk threads resolved with a reply that links a revision, a PR or a commit.
- Designs committed under `ui-builder/designs/` that were opened by somebody other than their
  author.
- Parity reports filed with a locator that matched an existing issue rather than opening a new one.

If the first number does not move, the joins were not the bottleneck and this document was wrong
about where the loss is.
