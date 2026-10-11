# UI Builder hosted projects: handover

Status recorded on 2026-10-11 UTC. This is a snapshot; refresh PR heads and workflow results before resuming.

## Goal

Make ui.coo.ee a useful hosted alternative to Figma for app design: first-class projects containing multiple design files, shared resource files for components/themes/tokens, a project membership/auth boundary, import from a GitHub repository's .uid files, and a clear canonical home versus editable working-copy distinction. Define writes explicitly: edit the hosted working copy, then review and publish changes through a GitHub PR to the canonical repository.

Deployment is through repository PR/release pipelines. The user previously authorized fixing merge/CI issues and auto-merge as needed. Their latest instruction is to preserve in-progress work as draft PRs and write this handover; the two new drafts remain drafts without auto-merge.

## What is implemented and merged

| PR | Status and purpose |
| --- | --- |
| [Server #1519](https://github.com/yschimke/compose-preview-server/pull/1519) | Merged. Project model/workspace, multiple design/resource files, GitHub import pinned to a commit, canonical-home/copy information, and GitHub PR publication. Preserves unknown document fields and unchanged source. |
| [Server #1520](https://github.com/yschimke/compose-preview-server/pull/1520) | Merged. Member project listing and project branch CRUD/merge/archive, ancestry-aware document/assets/subscription authorization, membership revocation, scoped grants, and indexed owner lookup. |
| [Builder #668](https://github.com/yschimke/compose-ui-builder/pull/668) | Merged at 74ed3c1666f266e64985d3ece15de19ef2fadbbe. Cache Storage denied in an opaque-origin renderer iframe is treated as unavailable before module loading, letting Compose Resources fetch images. Unexpected errors still propagate; sandbox permissions remain unchanged. |
| [Server #1531](https://github.com/yschimke/compose-preview-server/pull/1531) | Merged. Root editor routes receive the builder CSP needed for Wasm. Root mode requires an editor manifest supporting the base path (3.104.0+). |
| [Server #1532](https://github.com/yschimke/compose-preview-server/pull/1532) | Merged. Removes a catalog-specific literal from a KDoc that was tripping the source gate. No runtime change. |

Resolved review threads on #1519 and #1520 covered member listing, branch authorization, and indexed owner lookup. These merged changes do not by themselves prove the latest behavior is deployed.

Shared resource files can be edited and published. Automatic component/theme/token rebinding across design screens is not implemented. Automatic refresh after the canonical repository PR merges is also not implemented.

## In-progress work preserved as drafts

### [Server #1543: project workspace on rooted hosts](https://github.com/yschimke/compose-preview-server/pull/1543)

Branch: agent/ui-builder-project-root.

With host-root mode enabled, /ui-builder/projects?project=app&design=login redirected to /projects?project=app&design=login, where the editor shell handled the request. Registering the workspace with builder page routes makes both forms serve the project workspace, preserves the selection query and no-store policy, and limits the unprefixed route to the builder host. Project API paths stay unchanged. When project services are disabled, a design called projects still uses the editor shell.

The regression was run against the original routes and failed; after restoring the fix, all 35 project and host-root tests passed in five suites. ktfmtFormat, ktfmtCheckAll, the catalog source gate and git diff --check passed.

The draft also carries the same source-gate KDoc fix as merged #1532; it should disappear from the effective diff against current main. Resolve actual new conflicts without reverting that fix.

Runtime/test files published through the GitHub app matched the tested local source tree exactly. This handover document was added after runtime validation. CI/review of the final draft still needs checking.

### [Server #1544: consume UI Builder 3.105.0](https://github.com/yschimke/compose-preview-server/pull/1544)

Branch: agent/ui-builder-renderer-release. Commit: 3d89d05e92767f282ae6eae0350e6a716c54c724.

Changes exactly one version in gradle/libs.versions.toml, 3.104.0 to 3.105.0, to consume #668. Created from server main 93ddc31e0e052f26ae00dab582a0afbe52a6f121.

**Not ready to merge:** 3.105.0 is not published. This server has not built or tested the complete released dependency. Comments in the older local uncommitted pin are explanatory scratch work; the published draft intentionally contains only the one-line pin change.

## Release and CI blockers

[Builder release #662](https://github.com/yschimke/compose-ui-builder/pull/662) remains open for 3.105.0 with auto-merge enabled. Current observed head: c72c8419512b600f656e92449abe4284e9432105. Its changelog includes #668. Latest published builder release remains 3.104.0.

[Current-head CI run 38098997545](https://github.com/yschimke/compose-ui-builder/actions/runs/38098997545) failed its ui-builder-web-smoke job at the size-budget step:

- uiBuilder.wasm: 14.19 MB versus a 13.9 MB budget.
- Gzipped: 3.99 MB versus a 3.9 MB budget.
- Browser memory/smoke steps were skipped after the budget failure.
- All seven build/test lanes passed, and the required aggregate gradle job passed despite the web-smoke failure. Do not interpret that green aggregate as complete browser validation.

The older release head 4057b123 also failed the same budget in run 38095597823. This is a reproducible size-budget failure, not an established browser regression. Investigate the growth; raise budgets only if intentional and supported by evidence.

Bot-authored release updates currently leave PR Title and No Agent Attribution workflows at action_required on the new head. A previous body update triggered those checks on the previous head, but release-please rewrote the head/body afterward. Refresh and handle the latest head's checks without bypassing repository rules.

Builder #668's own full CI run 38092916557 passed all nine CI jobs. Four local Node guard tests covered available cache identity, SecurityError denial, absent cache, and propagation of unexpected errors. Both server renderer browser cases passed with only the guard applied to the released 3.104.0 renderer index.html. This proves the targeted fallback, not complete 3.105.0 release compatibility.

Server #1520 had 4,086 passing tests and 29 skips locally, plus 44 focused tests and a passing packaged project browser harness. Its last CI renderer browser failure used the unfixed 3.104.0 renderer and motivated #668/#1544.

## Remaining work, in order

1. Refresh #662 and its latest workflow head. Investigate/resolve the Wasm budget failure and run the skipped browser checks. Ensure required metadata checks run on the final head. Existing auto-merge is enabled; do not bypass checks.
2. Wait for actual 3.105.0 publication, not just release PR merge. Verify Maven artifacts and the renderer/web and remote variants' release assets. Check the published renderer page contains the Cache Storage fallback.
3. Refresh/rebase #1543 and #1544 against current server main, review new threads, and complete CI. For #1544, build with the actual released artifacts and run packaged project and renderer browser harnesses, including image loading in the opaque-origin frame.
4. Mark drafts ready only after validation when resuming implementation. Earlier user authorization covers necessary merges/auto-merge; these drafts were deliberately left unmerged for handover.
5. Ensure the server release PR includes the project/auth, CSP, routing and renderer dependency changes before shipping the intended complete result. Follow the existing release/container deployment workflows and verify their rollout/health checks.
6. Verify deployed versions, builder health, authenticated project workspace access, membership/branch permissions, GitHub import/publication, canonical-copy information, and renderer image loading. Record evidence from the deployed host.

Host-root deployment mode is currently disabled by the prior rollback (#1529). The prefixed /ui-builder/projects workspace is the current usable entry point. The root-route fixes support a future enabled configuration; do not assume host-root mode is already enabled in production.

**Deployment is not verified complete.** Direct requests to ui.coo.ee from this environment receive a proxy 403 CONNECT rejection because the host is outside the network policy. Use release workflow live checks or an environment with permitted host access; do not substitute a local test for deployed verification.

## Resuming in this workspace

Read each repository's AGENTS.md before editing. Use agent/... branches, Conventional Commits, and human git attribution. Fetch current main and verify the destination PR is still open before pushing.

Useful local worktrees:

- /workspace/compose-preview-server: original merged project/collaboration branch at eb73525e; do not push further work to its closed PR.
- /workspace/work/compose-preview-server-project-root: tested local routing commit 968c388b. API publication used five equivalent commits (pre-handover remote head 97a3443caed6a51aa23618a59382241620924bb5); histories differ, so fetch the remote draft branch into a fresh checkout rather than force-pushing local history.
- /workspace/work/compose-preview-server-renderer: older base f28c72c0 with the uncommitted planned renderer pin/comments. The authoritative one-line draft #1544 is based on newer main; use the remote draft branch when resuming.
- /workspace/work/compose-ui-builder-cache: merged renderer guard branch at 9067638.
- /workspace/work/compose-preview-server-source-gate: merged source fix branch at b7ff55f6.

Initialize the managed Java/proxy setup with source /workspace/.onboarding/activate.sh. JDK 21 is at /workspace/.onboarding/jdks/21 when required. The server's scripts/agent-gradle.sh is the Gradle entry point. Suggested release validation includes :server:check :server:installDist ktfmtCheckAll plus the repository's packaged browser checks. Gradle needs the environment's normal network/proxy permissions and local socket access. Chromium is /usr/bin/chromium. Logs/build outputs under work/ are scratch evidence, not release artifacts.

Command-line GitHub credentials became unavailable during handover; public fetch still works but git push/gh authentication do not. The GitHub app connection successfully published the draft branches. Every routing source blob and its complete pre-handover tree were checked against the tested local commit. New API commits use Yuri Schimke <yuri@schimke.ee> as author/committer.

An initial whole-file API pin update was rejected by automatic review as potentially including unrelated dependency changes. A fresh branch read proved the blob matched main, and an exact reversible one-line replacement was approved and published. No dependency changes other than the pin are in #1544.

The GitHub connection does not expose PR subscription reads/writes, so notification subscriptions could not be verified. No deployment credentials need to be requested: deployment is through PRs.
