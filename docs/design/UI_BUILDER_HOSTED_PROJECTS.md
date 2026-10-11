# Hosted UI Builder projects

A project at `/ui-builder/projects` owns app design files, shared resource files, membership and a
storage connection. Folders remain file-manager organisation; a project is an access and identity
boundary. This workspace surrounds the released editor, so it needs a server release rather than a
new editor archive or a dependency-pin change.

On a rooted UI Builder host, the same workspace is served at `/projects`; the legacy
`/ui-builder/projects` address redirects there with the selected project and design query intact.

## Identity and access

A `.uid` file has a stable manifest ID and a project-relative path. Each contained design retains
its source ID in the file, and receives a separate deterministic service ID derived from the host
project, file ID and source design ID. Two apps may both contain `screens/login.uid` with a design
called `login`, without overwriting each other.

The project owner manages membership. Viewers can open and download; editors can additionally edit,
add files and propose repository changes. Members use authenticated actor IDs such as
`github:username`. Design access management, deletion and home moves are refused on project-owned
designs: their access comes from the project, rather than independent grants that can disagree.

The service decorator enforces membership on direct editor/API/MCP reads, mutations, exports and
subscriptions. It delegates only the named project design to the project's owner, while retaining
the actual caller as the audit actor. Revocation stops subsequent reads and writes and closes a live
subscription on its next update. A design-scoped agent grant cannot read an entire project's source
files; it continues to use the existing scoped design API. Unrelated designs retain their existing
access rules.

Project-member designs also appear in the ordinary design browser and MCP listing with the member's
effective permissions. Shared entries are included once on the first page; the runtime cursor
continues to page the caller's own designs, as with existing design-scoped grants.

Branches and suggestions inherit project membership. Editors can create, edit, merge and archive
them; viewers can list and read them. Branch documents, assets and subscriptions use the same rule.
Ancestry is read from the runtime's branch records on each call, rather than trusting a cached ID
after deletion or reuse. Agent grants naming a project design continue to reach its branches.

Project documents start private even on a host whose ordinary new-design default is public.
Original bytes and metadata are stored together under the existing state directory's `projects/`,
using a forced temporary file and atomic rename. Project writes compare the metadata revision.
Design edits continue through the existing reducer and persistence layer. Project records are
loaded once and indexed in memory, so a canvas edit does not read every source file from disk.

## Repository connection

Connect an `owner/repository`, branch and project root. The branch defaults to the repository's
current default branch; the root defaults to `ui-builder`. A connection resolves one commit and
reads all files by blob SHA from that commit. It also records GitHub's stable numeric repository
identity, and refuses publication if that identity changes.

The root can contain a manifest:

```json
{
  "schema": "compose-ui-builder-project/v1",
  "id": "app",
  "name": "My App",
  "files": [
    { "id": "login", "path": "screens/login.uid" },
    { "id": "checkout", "path": "screens/checkout.uid" }
  ],
  "resources": {
    "components": ["libraries/common.json"],
    "tokens": ["themes/tokens.json"],
    "themes": ["themes/light.json"]
  }
}
```

Without a manifest, `.uid` files beneath the root are discovered and a manifest is proposed with
subsequent publication. Resource files are JSON objects; they are stored, shared, edited, downloaded
and published with the app's design files. Their existing component/token/theme schemas remain
unchanged. This feature does **not** invent cross-file component resolution or automatically apply a
resource edit to every screen. Importing component bodies and binding tokens continue to use the
editor's existing explicit mechanisms; project-wide automatic rebinding needs a separate versioned
contract.

Imports support ordinary documents, `compose-ui-builder-designs/v1` collections, and production
`.uid` wrappers with a visual design. Collection siblings, production APIs, unknown envelope fields
and unknown node fields survive a write. Production roots and declarations are checked before
publication. Unchanged source files are returned byte-for-byte, rather than reformatted merely by
opening them. Model-only production files belong to the consuming build, not this visual workspace.

Navigation between imported sibling designs is mapped to their scoped service IDs and mapped back
to source IDs on publication. Ambiguous or missing import targets are refused; a newly authored
navigation edge must stay in the project.

## Canonical home and working copies

The repository connection and its loaded commit are separate from each design's `home`. An existing
home is retained. An unhomed design explicitly connected to a repository gets its repository-relative
home; an unhomed file added to a hosted project gets a server home only when the host has a configured
public origin. A bind address is never treated as an identity.

The project editor page keeps the canonical-home status above the embedded real editor. A copied
server-home document still points to its original server design. A repository home resolves in its
connected repository context. Without that context the path is shown, without inventing a repository.
An unhomed document says its home is unknown. Home metadata never supplies access rights.

## Writes and publication

Autosave writes the working design to the existing durable server service. It does not write to
GitHub. Download and publication assemble complete files from captured design snapshots, restoring
source IDs and keeping the original file envelopes.

**Review saved files** returns the exact bytes, a project revision and a digest of the proposed file
set. **Open pull request** must name that revision and digest. If a design or project changed after
review, publication is refused before GitHub writes. An edit arriving after that check remains a
later unpublished revision: the PR contains the captured, reviewed bytes.

Before publication, every tracked path's current GitHub blob is compared with its loaded blob.
Changed upstream files cause a conflict; unrelated repository changes are allowed, and the new
commit is based on the current branch head. Related design/resource/manifest writes land in one
Git tree and commit. A deterministic publication branch and existing-PR lookup allow retrying a
request interrupted after GitHub accepted a write. Neither the canonical branch nor an existing
publication branch is force-updated. Text/document merging and automatic PR merging are absent.

Private imports and publication accept an optional `githubToken` in that action’s JSON request body.
The browser clears the password control before sending it; the server never persists the token in
project state. It is not sent in a custom header that an older proxy might log. GitHub requests have a fixed API origin, bounded responses, TLS verification and no
redirects. GitHub grants actual repository write permission independently of project edit access.

This first release does not refresh an existing working copy or perform automatic sync after a PR
merges. If a tracked upstream file changes, download pending work and connect a fresh project copy.
Project/file retirement and explicit home transfer remain the existing administrative operations;
they are not inferred from an import.

## Host routes

| Route | Action |
| --- | --- |
| `GET /ui-builder/projects` | Workspace shell; private data is loaded only through authenticated routes |
| `GET /api/ui-builder/v1/projects` | List caller-visible projects |
| `POST /api/ui-builder/v1/projects` | Create a hosted project or connect a repository |
| `GET /api/ui-builder/v1/projects/{projectId}` | Read project files, members and source metadata |
| `PUT /api/ui-builder/v1/projects/{projectId}/files` | Add a file or save a shared resource |
| `PUT /api/ui-builder/v1/projects/{projectId}/members` | Replace membership with an expected revision |
| `GET /api/ui-builder/v1/projects/{projectId}/review` | Capture complete files for review/download |
| `POST /api/ui-builder/v1/projects/{projectId}/publish` | Propose the reviewed bytes in a GitHub PR |

Every data response is `no-store`. Writes use the builder's existing same-origin session checks.
Paths are relative, forbid traversal, and never become local filesystem paths; the project store
uses validated IDs. Limits are 100 project files including the manifest, 100 members, 2 MiB per
source/resource file and 16 MiB per project record. An incomplete/truncated GitHub tree is refused.
