# Deployment ownership and staged extraction

New operators should start with [deploy/example](../../deploy/example/README.md). It runs a
published image with their own configuration and optional HTTPS, without the hosted site's sibling
services, accounts, cookies, auto-rollout or catalog inventory. Non-secret behavior belongs in
`settings.json`; credentials belong in a local secret store or untracked environment file.

| Component | Owner and reason |
| --- | --- |
| HTTP/auth, admin APIs, catalog delivery and retained runtimes | Server: reusable behavior and transport |
| Image build, entrypoint, schema and settings reference | Server: interface of the released image |
| Editor, catalog interpretation, browser offline design creation | compose-ui-builder releases; consumed through existing seams |
| Published catalog definitions, templates, capability pins | Catalog producers; no deployment-owned seed factories or chooser tables |
| Hostnames, catalog inventory/trust, OAuth registration, cookie parent, administrators, analytics IDs | Private deployment repository: operator policy |
| Caddy site routing, rollout scheduling/hooks, HA sidecar and demo-network joins | Private deployment repository: this machine's topology |
| HA API, demo facade/read-only enforcement, entities, custom card and dashboard semantics | homeassistant-remotecompose: integration behavior |
| Builder profile and HTTPS seed import allowlist APIs | Server: generic host/catalog restrictions and trust checks; HA is one consumer |
| Analytics service image and tooling | May be reusable, but this site's DB, IDs and installation belong to deployment; do not move server event delivery into HA |

The entrypoint no longer grants an operator's GitHub account administrator access by default or
implicitly gates OAuth against the operator's repository. Configure `uiBuilder.adminActors` and
`SERVE_GITHUB_AUTH_REPO` explicitly when required. Existing site settings already name its
administrator. GitHub sign-in currently requires a repository as well as the three OAuth
credentials, even for open builder access. The legacy site Compose file retains its repository
policy until cutover; removing it before migrating that value would prevent the server starting.
The private stack preserves its previous Compose defaults during staging.

## Migration gates

This is a preparation change. It deliberately retains the old workflows, images, file locations and
HA routes until the private replacement has passed live parity. Copying files is not switchover.
The private repository contains a commit-pinned hash inventory of the copied tracked inputs,
verification tooling, and the host runbook. No credentials or host `.env` files are copied.

1. Review both PRs. Confirm the private repository remains private. Reconcile changes landing in
   either source while review is open; specifically check the catalog and Lovelace workers.
2. Inspect the real host's Compose project, resolved files, image digests, override files, volumes,
   networks and environment *names*. Back up configuration and durable design state privately.
   Compare admin inventories with the private config before considering any pruning.
3. Test the pinned server image on a disposable deployment with this example. Verify catalog
   loading, builder entry, actual renders and browser-local creation with the network disabled.
   Separately stage the private stack without claiming production ports or sharing writable state.
4. Copy required Actions credentials through approved secret tooling; GitHub secret values cannot
   be read back from the public repository. Enable only one config/deployment writer at a time.
   Keep image production in the server release lane and site rollout/verification in the private lane.
5. After parity, switch the host's deployment checkout/config source while retaining its existing
   Compose project/volume identities and all live overrides. Verify every hostname and integration,
   authenticated flows, catalog revisions, actual render lanes and browser offline creation.
6. Only after that succeeds, remove superseded site config, HA services/routes and site-specific
   workflow stages from the public repository in a follow-up PR. Keep generic image behavior/tests.

Merging these PRs is a separate user decision. Deployment is authorized after verification, but a
merge-dependent step must wait for an explicit merge request. No old setup is removed by this PR.
Rollback before cleanup means restoring the previous checkout, Compose file list and pinned image
references, rolling the existing project back, and repeating verification. Do not delete volumes or
recreate the stack under a new project name. State-schema changes require the matching backup;
never blindly run an older binary against newly migrated state.

## Known overlap and unresolved dependencies

On 2026-10-17, server #1721 (builder 3.120.0) and #1698 (release 3.142.0) are merged. Public `/version`
and `/status.json` report 3.142.0. That release predates the #1721 adoption: a merged dependency bump
is not evidence the running image contains it. #1726 (published-template caching) and #1728 (HA
profile allowing Lovelace) remain open. The interrupted catalog worker also has builder-side work;
this migration does not duplicate it or remove its compatibility paths.

The existing catalog failures are tracked in [SCH-8](https://linear.app/yschimke/issue/SCH-8).
The first public probe reports 44 loaded catalogs, zero failed/pending, and three builder problems:
HA and Meshcore template pins, and an unavailable Lovelace publication. Preserve that baseline and
resolve those separately; do not interpret a successful HTTP page or 44/44 loading as working
catalog templates or offline design creation. Their revisions, retained runtimes and browser caches
must survive the deployment-source move.

Still unknown until host access: active local overrides, whether HA demo networking has been
provisioned, exact running editor/runtime pin, secret delivery and backup/restore mechanism.
The deployment runbook must be updated with observed facts before cutover.
