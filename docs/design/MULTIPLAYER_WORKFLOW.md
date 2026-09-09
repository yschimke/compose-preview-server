# A multiplayer workflow for design, engineering and product

**Status: proposal (2026-09).** Written against what is on `main` and on `preview.coo.ee` today,
not against a greenfield. Every surface it leans on is cited; every gap it names is one the
existing documents already circle. It answers five questions the way they were asked: what would a
multiplayer-forward workflow look like end to end, whether it needs better references, "product
sagas", longer tokens or better access control, and what a team adopting it looks like.

It is written about **categories of tool, not products**. A chat-resident agent is a category;
Claude Tag is one instance of it. A design tool with a component library is a category; Figma is
the instance this stack knows best. A tracker, an agent host, a chat: categories. Where the code
today is bound to one instance — a `figma:` reference scheme, a `github:` actor id, a GitHub-only
issue report — section 2 says so, because those bindings are part of what the proposal has to
loosen.

The one-paragraph version. **This server already has the rare half of multiplayer: a shared,
revisioned, addressable object that a person in a browser and an agent over MCP edit as equals,
plus a discussion pinned to it that wakes the agent.** Chat-resident agents have the other half: a
shared conversation, in the place the team already talks, with one agent everybody in the channel
directs. Neither needs to become the other. The work is the *joins* — a citation an agent can
resolve from any of those places, back-links from a design to the issue and the frame it is for, a
way for the design's discussion to surface where the team reads, and one durable identity for the
long-running participants (a chat agent, a routine, CI) that the short-lived grant deliberately is
not. Nothing here asks for a chat inside the server, a tracker inside the server, or a longer
token.

---

## 1. Two meanings of "multiplayer", and why both are needed

The phrase is used for two different shapes, and the proposal depends on keeping them apart.

| | Shared **conversation** (a chat-resident agent) | Shared **object** (this server) |
| --- | --- | --- |
| What is shared | A thread, and the channel's accumulated context | A design, a preview at a publish, a comparison |
| Who participates | Everybody in the channel; one agent per channel, under a service identity | Owner, viewers, editors; each agent under a grant delegated by a named person |
| Where the state lives | The chat, and whatever the agent wrote elsewhere | The revision log, the comment board, the catalog's delivery branch |
| What converges | Nothing, structurally — a thread is a log | The document: every client sees revision *n* or is told to resync |
| How the agent hears | It is mentioned, or it is in an ambient mode | `ui_builder_await_design` / `ui_builder_await_comments`, and the `comments` block on every reply |
| Identity of a change | The service account | `agent:<fingerprint>` acting for `github:<login>` |

**The category, and the properties the workflow relies on.** A chat-resident agent is one that
lives in the team's chat — Slack, Teams, Google Chat, Discord — and has, in every instance worth
designing for, the same five properties: it is invoked in a *shared* thread rather than a private
window; it works asynchronously, over hours or days, posting back into the thread; it accumulates
*channel-scoped* context so the team does not re-explain the project; it reaches other tools
through admin-provisioned connectors, MCP among them; and it acts under **one service identity for
the workspace or channel, not as the person who mentioned it**. Claude Tag documents all five
explicitly; Codex, Copilot and Devin in Slack or Teams, and Gemini in Google Chat, share the shape
with variations in the last one. The proposal assumes the service-identity case because it is the
harder one: a design that only works when the agent is the tagger breaks the day the team's agent
is not.

That last row is the whole design tension. Everything in [`AGENT_ACCESS_GRANTS.md`](AGENT_ACCESS_GRANTS.md)
is built on a grant being a *delegation from a named human*: an agent's design is owned by the
approver, the agent reaches what the approver reaches, and the audit line names both. A chat agent
that is the same identity for everyone in the channel cannot be delegated *from* anybody in
particular. Section 4.4 is about that, and it is the one place the answer is "yes, build
something".

A conversation without an object drifts: three people and an agent agree in a thread and nothing
is committed anywhere a fourth person can open. An object without a conversation is invisible: a
design with an open Talk thread that nobody outside the editor knows about is a design nobody
reviews. The workflow below puts the object at the centre and lets the conversation happen wherever
the team already has one, with the agent as the thing that carries between them.

## 2. What is already built, read as multiplayer primitives

An inventory of what exists, ordered by how much of the workflow each already carries.

| Primitive | Where it is | What it gives a multiplayer flow |
| --- | --- | --- |
| A design with a canonical URL, revisions, presence and an owner/viewer/editor list | `/ui-builder/<catalog>/<design>`; [`UI_BUILDER_LIVE_SESSION.md`](UI_BUILDER_LIVE_SESSION.md), `ui_builder_share_design` | The shared object. A browser and an MCP client land in one `PersistentUiBuilderService.apply`, and every accepted write reaches every subscriber |
| Comments pinned to a node, a mark or a point, with react / acknowledge / resolve kept distinct | [`UI_BUILDER_COMMENTS.md`](UI_BUILDER_COMMENTS.md) | The in-context discussion. Stored *beside* the design, so talking never moves the revision |
| The agent is woken, and told what it has not read, on every reply | `ui_builder_await_comments`; the `comments` block on `ui_builder_get_design` / `_apply` / `_export` | An agent mid-edit learns a designer spoke without polling |
| A reference overlay: paste a frame from any tool, a screenshot of a shipped screen, or a photo of a sketch; mark it up, erase, capture a component, promote it into the tree | [`UI_BUILDER_REFERENCE_OVERLAY.md`](UI_BUILDER_REFERENCE_OVERLAY.md) | The cheapest possible join to a design tool *and* to the existing app: the clipboard |
| An app's own composables on the palette, from its discovered component record | [`UI_BUILDER_COMPONENT_PACKS.md`](UI_BUILDER_COMPONENT_PACKS.md), `ui --module app` | A change to an existing screen is built from the screen's own parts |
| Designs in the app's repository, `ui-builder/designs/index.json`, and a library to open one from | [`UI_BUILDER_PROJECT_DESIGNS.md`](UI_BUILDER_PROJECT_DESIGNS.md) | The design survives the session, can be argued about in a pull request, and is there for the next feature |
| Deterministic export: Compose source, layered SVG, PNG, all pinned to a revision | `ui_builder_export`, `compose-preview-server design …` | The handoff to engineering is a file, not a screenshot; the handoff to the design tool is a vector it can import |
| Preview permalinks that unfurl, with the render as the card | `/<system>/p/<id>`, Open Graph cards, `ServeSocialCard` ([public-preview-server.md](https://github.com/yschimke/compose-ai-tools/blob/main/docs/public-preview-server.md#pasting-a-link-into-slack-or-google-chat)) | A link pasted into the chat *is* the review artefact |
| A prefilled issue carrying a `compose-parity-locator/v1` block, and an issue index the compare page reads back | `ServeIssueReport`, [`COMPONENT_PARITY_WORKFLOW.md`](COMPONENT_PARITY_WORKFLOW.md) | "We know about this one" survives the page reload, and the tracker is where the human already is |
| History: a per-preview timeline across publishes, diffable without fetching pixels | `history_list` / `history_diff` / `history_read` | "Did the last publish move this?" answered from data |
| One MCP endpoint over both catalogs and designs, with a device-flow grant an agent can obtain from inside the protocol | [`CATALOG_MCP.md`](CATALOG_MCP.md), `request_access` / `poll_access` | Any MCP host — a coding agent, a chat agent with a connector, a routine — reaches the same objects |
| An agent on any issue or PR, with a visual-evidence contract; weekly triage; the preview-diff sticky comment | [`AGENT_INVOCATION.md`](https://github.com/yschimke/compose-ai-tools/blob/main/docs/AGENT_INVOCATION.md), `claude-triage.yml` | The tracker-side agent already exists and already has to show pixels |
| Design-led catalogs that never write back to the design tool, and a parity direction with teeth | `.design-parity.json` in m3-catalog / wear-m3-catalog | Who is authoritative is decided by configuration, not per PR |

What the inventory says, read as a whole: the objects, the discussion and the agent's wake-up are
done. What is thin is *arrival* — how a person in the chat, in the design tool or in the tracker
gets to the object with the right context, and how the object's state gets back to them.

### Where the stack is bound to one instance today

Not faults; each was the right call when made. Listed because a category-shaped workflow has to
know where it will meet a product-shaped seam.

| Seam | Bound to | What the binding costs | Loosening |
| --- | --- | --- | --- |
| Kit reference on a catalog component | `figma:<file>/<node>` | A catalog reproducing a Penpot or Sketch library has no scheme to name its node | The prefix is already the tool; admit a second scheme and let the compare page resolve per scheme |
| Parity direction and Code-to-Canvas push-back | design-parity's plugin, Figma | The *rule* (design-led never writes back) is tool-neutral; the *enforcement* is one plugin | Keep the rule in `.design-parity.json`; enforcement per tool is that tool's plugin's job |
| Actor identity | `github:<login>`, `operator`, `agent:<fp>` | A team whose chat is not signed in with GitHub cannot be shared with by name | Section 4.4: a service actor, and team targets resolved against whatever directory the box trusts |
| Prefilled issue report | GitHub `issues/new` | A Linear or Jira team gets a link they cannot use | The `links` record (4.2) makes the tracker pluggable; the *agent* files, with the team's connector |
| Agent invocation from the tracker | `@claude` via a GitHub Action | Category-shaped in principle (any agent host with a tracker hook); one instance today | Document the contract (visual evidence, `agent/` branch, no attribution) rather than the workflow file |
| Unfurl cards | Open Graph | Already category-neutral: Slack, Teams and Google Chat all read it | Extend to the design page (4.1) |

## 3. The loop

This is not a process with numbered stages. It is a set of moments a piece of UI work tends to
pass through — brief, design, review, build, verify, ship — in whatever order the work actually
takes, with most of them repeated and some skipped. What holds across every ordering is three
invariants, and they are the proposal:

- **The object is authoritative.** One design, pinned to one catalog revision, with one canonical
  URL. Every edit, from any seat, is an operation on it.
- **The conversation is wherever it is.** The chat thread, the design's Talk panel, the PR, a comment
  in the design tool. None of them is the record; the object is.
- **The agent carries between them.** It is the thing that turns a sentence in a thread into an
  operation on the object, and a change to the object into a sentence in the thread.

One assistant, three actors today: the chat agent (a service account), the builder's agent (a grant,
`agent:<fp>`, acting for whoever approved it), and the tracker-side bot. That the "same" assistant
is three identities with three credentials is the first finding, and section 6 is partly about
reducing it to two.

### Where it starts

The loop has no fixed entry, and the five common ones are different enough to name.

**The designer arrives with a frame.** A screen drawn in the design tool, against the team's kit.
The join is the clipboard: the frame goes onto the reference overlay, the designer or the agent
builds the real components under it, and the kit stays the source of truth for what it looks like.
For a kit the catalog reproduces, the agent can fetch the kit node itself through the design tool's
connector, using the reference the catalog already records per component — which is what a
`ui_builder_put_reference` tool (item 6) is for, since the overlay routes exist and the tool does
not.

**There is an existing screen, and it needs to change.** Most work is this. The screen is code; it
has a `@Preview`; the catalog serves it. There is no reverse import from a composable into a design
document, and the proposal does not add one — a screen that is code should stay code. What the
builder offers instead is exactly what the reference overlay was built for: snapshot the served
render, **erase** the region that changes with the screen's own background colour, and build the
change in the hole from the app's own composables, which are on the palette through its component
pack. The export is a fragment the engineer drops into the existing screen, and the compare page
holds the old render beside the new one. The design in the builder is scaffolding for the change,
not a second copy of the screen.

**There is a design left from the last feature.** It is in `ui-builder/designs/` in the app's
repository, or still on the host. Open it from the library, copy it (`fromDesignId`, so the
catalog pin is real), and carry on; if the catalog has moved since, the service's own upgrade diff
says what changed underneath. This is the case the project-designs convention exists for, and the
one where "where were we" is answered by the design's back-links (4.2) rather than by whoever
remembers.

**Somebody has a napkin drawing.** A photo of a sketch, a whiteboard after a meeting, a wireframe
from a whiteboarding tool, or five boxes and an arrow drawn in the builder's own markup layer — which
has exactly that vocabulary: draw, box, arrow, label, image placeholder. This is the entry where
the agent does the most and the person corrects. The picture goes on the overlay like any other;
the agent reads it and proposes the tree — *"a top bar, a list of cards, a floating action"* — as
operations the person can see land and undo. The reference-overlay document is explicit that a
piece with no provenance is the one case that needs an agent rather than a deterministic promote,
and this is that case in its purest form. The napkin is never the reference the screen is judged
against; it is replaced by the frame or the render as soon as one exists, which is what
**flatten** and the `links.reference` slot (4.2) are for.

**Somebody has feedback on the shipped screen.** The screen is a `@Preview` the app publishes,
served in its own catalog with a permalink, a matrix and a history — and today the only thing a
person can do with a reaction to it is file an issue. This entry is a comment left *on the served
render*, which is where the PM and the designer already are, and it is the one that would move
where the loop starts: circle a region, say "make this a card", and an agent woken by the comment
snapshots that render into a design, erases the region, builds the change from the app's own
component pack, exports the fragment and opens the PR. The catalog becomes where feedback starts
and the builder becomes only where the change is made. It needs the comment board keyed by catalog
and preview id rather than by design id — a small generalisation, since the board is already a
sidecar — the sign-in that live preview already requires on a public catalog, and an anchor that
survives a republish, which is the next section.

A sixth is a brief with nothing behind it yet — the PM's issue and a sentence in a thread — and it
collapses into one of the five the moment somebody starts: a template copy, a snapshot of the
screen it changes, last time's design, a sketch, or a comment on what shipped.

### Anchoring a comment to something that gets republished

A design comment pins to a node in a document that only changes when somebody edits it. A screen
comment pins to a render that the next publish replaces, so its anchor has to say three things,
and the viewer has to be honest about which of them it is using. Three fields:

| field | what it is | what it is for |
| --- | --- | --- |
| `ref` | the element — the authored `testTag` where there is one, else the semantics ref | identity: the pin follows the element wherever it now is |
| `point` | frame fractions, the space design comments and reference marks already use | position: where the author put it, surviving a phone becoming a tablet |
| `sha` | the render's content id — the history timeline's version, not the commit | time: the version this was said about, and the key that shows it |

`ref` is the `testTag` rather than the semantics tree's generated ref for the reason `diff_semantics`
gives: a generated ref is sibling-indexed and retargets when a sibling is inserted, which is exactly
the edit a reader most needs to see; a `testTag` either survives or stops resolving, and both are
reported. `sha` is the content id rather than the commit because the history timeline already
collapses adjacent publishes with identical bytes into one version — the same baseline-inheritance
a Storybook-hosted review service relies on — so a comment stays *current* across republishes that
did not move the pixels, and `history_diff` says when one did.

Resolved top-down, a republish leaves a thread in one of three states, each drawn differently:

- **on the element, current** — `ref` resolves and `sha` is the served version: an ordinary pin;
- **on the element, written against an older version** — `ref` resolves, `sha` is behind: the pin
  sits on the element as it is now, the thread says which version it was said about, and the
  history lane serves that render beside the current one, so the reader sees *what it was*;
- **by position** — `ref` no longer resolves: a visibly different pin at `point`, labelled as such,
  the thread saying the element it named is gone, and again the old render one click away.

The rule that makes the ladder trustworthy is that the fallback is never silent, and with history
it is never blind either: a comment can lose its element, but it cannot lose its picture. The
parity locator already reserves `element` and `bounds` and already keys acceptances to a reference
fingerprint, and its caveat carries over unchanged — an element's bounds are measured on the baked
render, and must not be read against pixels an override or a pin has re-rendered.

### What tends to happen

Around the object, in no particular order, and usually several at once.

**Designing.** In the builder, with the overlay under the canvas and Talk beside it. A person drags
and edits; an agent, sitting in `ui_builder_await_comments` or simply reading the `comments` block
on its next call, does the parts that were asked for in words — *"this is the kit's segmented
button, build it"* pinned to a circled region. Presence shows both; nobody reloads. Nothing writes
back to the design tool: for the design-led catalogs by configuration, for an app screen by the
designer's own decision to import the layered SVG.

**Reviewing.** Mostly not in the builder. The PM reads the chat thread, where the agent has posted
the current render and, on request, a matrix (`render_matrix` over device × font scale × locale),
because "does it survive the large-font setting on the small phone" is a PM's question. The link
unfurls to the same pixels. Comments land in two places and both are fine: a designer's *"the gap
above the card is wrong"* goes into Talk, pinned to the node; a PM's *"the primary action should say
Continue"* goes into the thread, and the agent turns it into a `setProperty` and replies in both. An
edit made from the chat is an ordinary `ui_builder_apply` that every open browser sees; a Talk
thread resolved in the editor is what closes a question, not a reply in the chat. What is missing
is outbound — a new Talk thread is invisible to anyone not in the editor — and item 3 is the webhook
that fixes it.

**Building.** `ui_builder_export` for `compose`, committed **with** the design's operations file so
the screen under review and the design it came from are in one PR; the preview-diff bot posts
before/after; the PR body carries them as commit-pinned images. The agent resolves the Talk threads
it addressed with the PR link, and the design's back-links gain the PR. From then on the design has
three anchors — issue, frame, PR — and any one finds the other two.

**Verifying.** The implementation renders through the app's own catalog; the focused comparison
puts it beside the kit reference or the previous render. A divergence is filed from the compare
page with the prefilled report; the locator block keys it; the parity issue index makes it a
*known* difference the next reader sees rather than a fresh alarm. A difference the team accepts
goes into `.design-parity.json` with its issue link — the one "saga-like" record this stack keeps,
and the right shape for it. The agent's part is triage: `history_diff` for "did the publish move
it", `diff_semantics` for "did the structure move", the flake oracle when a changed preview's
source was not touched.

**Shipping, and what is left.** The catalog publishes; the delivery branch records the render; the
weekly triage sweeps stalled review feedback into follow-up issues. The design is retired, or kept
because the next iteration is already being discussed — the project-designs document is explicit
that a design nobody is editing has no reason to stay.

### Who tends to be where

| Moment | Who | Where they are | The object | The agent's surface |
| --- | --- | --- | --- | --- |
| Brief | PM | Tracker, chat | Issue → design created, shared, linked | Chat agent → `ui_builder_create_design`, `_share_design`; writes links |
| Design | Designer, agent | Builder; the design tool as reference | Revisions, overlay, Talk threads | `ui_builder_await_comments`, `_apply`; design-tool connector → reference |
| Review | PM, designer, engineer | Chat thread, Talk panel | Render, matrix, resolved threads | Chat agent ↔ `_post_comment` / `_apply`; webhook out |
| Build | Engineer, agent | PR | Exported Kotlin + design file in one PR | `ui_builder_export`; agent on the PR; preview-diff bot |
| Verify | Agent, then a human verdict | Compare page, tracker | Parity issue with locator; accepted differences | `history_diff`, `diff_semantics`, prefilled issue |
| Ship | CI, weekly triage | Delivery branch, triage issue | Published catalog, history | The triage routine |

## 4. The four questions

### 4.1 Do we need better ways to reference content?

**Yes, but not new identifiers — a resolver and a card.** The identifiers exist and are precise:

- a preview: `/<system>/p/<id>` plus its override query, the `compose-preview://catalog/<catalog>/<id>`
  resource URI, a history entry by commit or blob, and the `compose-parity-locator/v1` block that
  spells all of it out in an issue body;
- a design: `/ui-builder/<catalog>/<design>`, a `revision`, a `nodeId`, a comment `threadId`;
- a comparison: `/<system>/compare/<id>?reference=<ref>`;
- a kit node: `<tool>:<file>/<node>`, recorded on every catalog component (`figma:` today).

Three things are missing, and they are the same thing seen from three sides.

1. **A design URL cannot say which revision, node or thread it means.** The path names the design;
   identity and transport live in the query; nothing carries "revision 41, node `primary-button`,
   thread `t-…`". A designer cannot paste "look at this thread" into the chat; an agent told "the
   button on the checkout design" has to search. The additive fix is `?revision=` / `?node=` /
   `#thread=` on the canonical URL, honoured by the editor (select the node, open the panel on the
   thread) and by the export routes (`revision` is already the parameter they take).
2. **A link handed to an agent is opaque.** An agent in a chat receives a URL and has to know this
   server's grammar to turn it into `ui_builder_get_design` with the right arguments, or
   `render_preview` with the right overrides. One tool — `resolve_reference(url)` — that answers
   with the typed object (`kind`, the ids, the revision, and the call that fetches it) makes any
   link from any of the team's tools actionable without the agent learning the routes. It is the
   MCP-side twin of the locator block, and the locator's fields are its schema.
3. **A design does not unfurl.** Catalog and viewer pages carry a drawn or rendered card; the
   builder's page carries none, so the one URL this workflow is built around arrives in a chat as
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
frame it reproduces, not the PR that implemented it, not the design it was copied from. So:

- **A `links` record beside each design**, stored exactly as comments and references are (its own
  directory, never in the document, never on the wire), holding typed URLs: `issue`, `reference`
  (the frame, in whatever tool), `pr`, `thread`, `previous` (the design this one continues).
  Written from the Screen panel or by the agent; carried in `ui_builder_get_design` and in the
  `ui-builder/designs/index.json` entry (additively, so a project's checked-in index says what each
  design is for). Read the other way, `GET /ui-builder/links?issue=<url>` lists the designs citing
  an issue — the "feature view" a PM wants, and it costs a directory scan.
- **Accepted differences stay in `.design-parity.json`**, with their issue URL, as they are: that
  file is the durable record of "we decided this", and it lives in the repository where a decision
  belongs.
- **The chat channel is the saga for a small team**, and a chat-resident agent's channel-scoped
  context is precisely a saga store for the conversational half — which is a reason to leave that
  half to it.

The thing to resist is a `Saga` object with its own lifecycle in this server. The moment it exists,
it needs status, assignment, and a reason to prefer it over the tracker, and it is a tracker.

### 4.3 Do we need longer tokens?

**No — a different kind of credential for a different kind of participant.** The grant is the
right shape for the participant it was designed for: an agent session a named person is watching,
whose credential should die when the session does, and whose blast radius on a leak is minutes.
Every argument in [`AGENT_ACCESS_GRANTS.md`](AGENT_ACCESS_GRANTS.md#lifetime-revocation-blast-radius)
for a short, in-memory, unprinted token holds unchanged.

Multiplayer adds two participants the grant was **not** designed for, and lengthening the TTL
serves neither:

- **A long-running agent nobody is watching** — the channel's agent over a week, a routine that
  renders a matrix every Monday, CI. What it needs is a credential that *survives a redeploy and is
  not delegated from an individual*, because there is no individual. That is an installation
  identity, and the hosted-service plan already names it: a GitHub App, or its equivalent for
  whatever the box trusts. A 24-hour grant approved by whoever happened to be online is the wrong
  tool even if it were a 30-day grant.
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

1. **Groups.** Sharing is per actor id. "Share with the design team" is five logins typed one at a
   time, and a new hire is a change to every design. The GitHub verdict the server already holds is
   a team-membership check away: a share target `team:<org>/<slug>`, resolved against the directory
   the box already trusts at access-check time (cached with the session), and shown in the access
   list as the team. The single-repo `repositoryAccess` verdict is the same mechanism at a coarser
   grain.
2. **A service actor.** A chat-resident agent acts as a workspace identity; a routine and CI act as
   nobody. Today the only way any of them reaches a design is a grant approved by a person, which
   then *delegates from that person* — so a design created from a chat thread is owned by whoever
   approved the chat agent's grant, which is at best surprising and at worst somebody who has left.
   The fix is a **service actor** (`app:<installation>`) that can own designs, be shared with, and be
   listed — with its own credential (an installation token, or an operator-minted long-lived key
   that is *not* a grant), and with `authorKind: agent` on everything it writes. Delegation stays
   for the interactive case; the service actor is for the participant with no principal.
3. **Attribution through a service identity.** When the chat agent posts a comment because a named
   person asked it to, the board should be able to say so: a `via` field on a comment — *"agent
   (for Dana)"* — that is **cosmetic, exactly as `authorKind` is**, never a permission. The
   permission is the service actor's; the credit is the person's. Without this, a review that went
   through the chat reads on the board as the bot talking to itself.

Beyond those three is the org / tenant / private-catalog model the hosted plan puts in its Phase 2.
It is real work and this workflow does not need it: a team on its own box, or on `preview.coo.ee`
with private designs, is served by the three items above.

### 4.5 Is the framing wrong anywhere?

Two places, one in each direction.

**Treating the chat as the workspace.** It is the lobby. The multiplayer property that matters —
two people and an agent converging on one document — is a property of the object, and a chat has no
object; it has a log. The loop above keeps the design authoritative and makes the thread a view of
it. A team that tries to run the design *in* the thread ("make the button blue" ×20) gets twenty
revisions and no design anyone can open. That is the failure a chat agent's own persistence makes
comfortable rather than prevents.

**Treating this server as the tracker.** The Talk panel is the design's in-context discussion, the
way a design tool's comments are a frame's. It is not where a feature is tracked, and it must not
grow status, assignment or milestones. The parity work already settled this the same way: the issue
index *reads* the tracker, it does not replace it.

And a smaller one: the appetite for "better references" is real, but the shortage is not of
identifiers. It is that nothing resolves them for an agent and nothing unfurls them for a person.

## 5. The team that adopts this

A concrete profile, because the workflow should be checked against one. A mobile product team of
eight to twelve: one PM, one or two designers, four to six Android / Kotlin Multiplatform
engineers, sometimes a QA. They own an app, not a design system, and they consume a kit (Material
3, or their own library in their design tool) rather than publish one.

**Where they interact today, and what gets lost at each handoff:**

| Handoff | Where it happens | What is lost |
| --- | --- | --- |
| Brief → design | A PRD in a doc tool, a tracker issue, a chat thread | The design file never links the issue; the issue links a frame URL that moves |
| Design → engineering | The design tool's handoff mode, a screenshot in the issue, a DM | The frame the engineer built from is not the frame that shipped; "which variant" is a conversation |
| Engineering → design review | A screenshot pasted into the PR, or a build on a test device | The screenshot is one device, one locale, one font scale; the designer reviews pixels with no way to point at a node |
| Review → tracker | A chat reply, a comment on the frame, an issue with a screenshot | The report has no locator, so the second reporter files it again |
| Ship → next round | Nothing durable | The design is stale the day the screen ships; the next change starts from a screenshot |

What changes for each seat, and what deliberately does not:

- **The designer keeps their design tool.** The kit is the source of truth for tokens and
  components, and the design-led catalogs enforce that nothing writes back. What they gain is a
  canvas that draws the *real* components, an overlay to build against their own frame or against
  the shipped screen, and comments that pin to a node rather than a screenshot. They do not have to
  learn the builder to review: the unfurled render in the chat and the compare page are review
  surfaces.
- **The engineer keeps the PR.** The exported Kotlin is what they review, the committed design file
  is what they diff, and the preview-diff bot is what they already read. The change is that the
  design they build from is the design in the repo, pinned to a catalog revision, rather than an
  inspection in the design tool.
- **The PM keeps the chat and the tracker.** They never open the builder. What they gain is that the
  link in the thread shows the current state, a matrix answers the device question, and the issue
  they filed is linked from the design that answers it. The chat agent is their interface to all of
  it.
- **Nobody moves their conversation.** Talk is for the comment that needs a node under it; the
  thread is for everything else; the webhook is the bridge.

The adoption path is incremental and each step is useful alone: (1) publish the app's catalog and
paste links; (2) turn on the builder against the app's `components.json`; (3) give the chat agent a
connector to `/mcp`; (4) commit designs under `ui-builder/designs/`.

## 6. What to build, in order

Each item is small enough to be one pull request and stands on its own. The repository is named by
the layer rule ([`REPOSITORY_LAYERS.md`](https://github.com/yschimke/compose-ai-tools/blob/main/docs/design/REPOSITORY_LAYERS.md)):
everything that is a route or a tool is this repository; a wire shape two repositories must agree
on is `compose-preview-contracts`.

| # | Build | Size | Where | Answers |
| --- | --- | --- | --- | --- |
| 1 | **Design URL selectors and an unfurl card.** `?revision=` / `?node=` / `#thread=` on `/ui-builder/<catalog>/<design>`; an `og:image` for the design page from its PNG export or blank frame, content-addressed, privacy-aware | S | server, ui-builder | 4.1; a link in the chat means something |
| 2 | **`links` beside the design.** A `links/<digest>.json` store, Screen-panel editor, `ui_builder_set_links` / carried in `ui_builder_get_design`, an additive `links` field on the project index, `GET /ui-builder/links?issue=` | S | server, ui-builder, contracts (index schema) | 4.2; "where were we" |
| 3 | **Outbound comment webhook.** `--ui-builder-comment-webhook <url>`, posting new threads and replies with the thread permalink as plain JSON, and carrying the design's `links.thread` so a relay can put the message in the right conversation; the Slack, Teams and Google Chat incoming-webhook bodies are one adapter each | S | server | Reviewing without opening the builder |
| 4 | **Comments on served previews.** The comment board keyed by `<catalog>/<previewId>` beside its design keying; the `ref` / `point` / `sha` anchor and the three display states above; the viewer's Talk panel; the same six MCP tools with a `preview` argument, and the same `comments` block on `render_preview` | M | server, serve-web | The "feedback on the shipped screen" entry |
| 5 | **`resolve_reference(url)`** on `/mcp`, answering `kind`, ids, revision and the fetching call; the locator block's fields as its schema | S | server (mcp), contracts | 4.1; every agent seat |
| 6 | **`ui_builder_put_reference`** — the reference-overlay routes as a tool (image bytes, or a piece placed at a rect), so an agent with a design-tool connector, or a render from this server, can put a frame under the design | S | server (mcp) | The frame, existing-screen and napkin entries |
| 7 | **Persist grants across restart.** Encrypted at rest under a `/config` key; TTLs and `unknown` unchanged | M | server | 4.3 |
| 8 | **Service actor and `via` attribution.** `app:<installation>` as an owner / share target with its own credential; `via` on comments, cosmetic; `authorKind: agent` on everything it writes | M | server, ui-builder-runtime, contracts | 4.4; chat agents, routines, CI |
| 9 | **Team share targets.** `team:<org>/<slug>` resolved against the directory the box trusts; shown as the team in the access list | M | server | 4.4 |
| 10 | **A second reference scheme.** Admit a non-`figma:` kit reference on a catalog component and resolve it per scheme on the compare page | S | compose-ai-tools (annotation), server (compare) | Section 2's first seam |
| 11 | **Chat-agent connector recipe.** For whichever chat-resident agent the team runs: the connector to `/mcp`, the device-flow grant, and the prompt shape that keeps the design authoritative | docs | docs/ | Adoption |

Items 1–6 and 10 are the whole of "better references", "sagas" and the shipped-screen entry, and are all
additive. Items 7–9
are the identity work and are where the design decisions live; 8 should come with its own
document. Item 11 is the adoption guide.

## 7. What not to build

- **A chat inside the server.** Talk is a comment board pinned to a design and should stay one.
- **A saga, milestone or status object.** The issue is the saga; `links` is the join.
- **A longer grant TTL.** Persist, and add a service actor; do not lengthen.
- **Write-back to the design tool for design-led catalogs.** The direction is decided by
  `.design-parity.json` and enforced by the tool's plugin; a multiplayer flow does not change who is
  authoritative.
- **A reverse import from Compose code into a design document.** A screen that is code stays code;
  the overlay and the component pack are how a change to it is designed.
- **A chat app per platform.** A webhook out, and the chat agent's own connector in, cover the flow.
  A bespoke app is a second identity system and a second thing to install.
- **Tracker integrations in the server.** The prefilled issue and the `links` record make the
  tracker pluggable; the agent, with the team's connector, does the writing.
- **A second identifier scheme for this server's own objects.** The ids exist; build the resolver and
  the card.

## 8. Risks worth naming

- **Comments are data the agent reads as instructions.** The `comments` block already carries a
  `hint` telling the agent what to do, by design. A shared design is a place where anyone with
  editor access can write text an agent will act on. The mitigations are the existing ones —
  the agent reaches only what its principal reaches, and nothing on the board widens that — plus
  a rule for the service actor (8): it acts on a comment only from an actor the design's owner has
  shared with, never on one from a `via` it cannot verify.
- **Noise.** An agent that reacts to every comment and posts to every thread trains people to
  ignore it. React on receipt, reply when there is something to say, resolve when it is done — the
  three-act model in [`UI_BUILDER_COMMENTS.md`](UI_BUILDER_COMMENTS.md) — and the webhook posts
  threads, not reactions.
- **Two conversations diverging.** A question answered in the chat and left open in Talk, or the
  reverse. The rule that only the board closes a thread, and the webhook that shows the board's
  state in the thread, are what keep them from drifting; a team that adopts only the chat will
  drift.
- **Attribution rules.** The repositories reject agent identities on commits and agent
  `Co-authored-by` trailers, and chat-resident agents open PRs as their own bot accounts. Bot
  accounts are exempt and the PR body rule is unchanged, so the workflow is compatible — but a team
  adopting this should read [`AGENTS.md`](../../AGENTS.md#enforced-rules) before their first PR
  opened from a thread.
- **The restart.** Until item 7, a redeploy mid-review drops every agent's grant at once. The
  in-protocol re-request makes recovery a tool call rather than a human, but it is still an
  interruption a person notices.

## 9. How to tell it worked

Not a dashboard; four things a team could report after a quarter.

- The time from a frame, or from a "change this screen" issue, to a PR with rendered before/after
  evidence, measured on the issues that link a design.
- The share of Talk threads resolved with a reply that links a revision, a PR or a commit.
- Designs committed under `ui-builder/designs/` that were opened by somebody other than their
  author, including as the starting point of the next feature.
- Parity reports filed with a locator that matched an existing issue rather than opening a new one.

If the first number does not move, the joins were not the bottleneck and this document was wrong
about where the loss is.
