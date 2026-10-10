# Hosted project workspace evidence

Captured against the packaged `3.119.1-SNAPSHOT` server on a throwaway localhost store.
`before.png` shows the existing design browser, `after.png` the new project workspace, and
`editor-copy.png` the released editor framed with the preserved canonical-home status.
The canonical-home pointer in this smoke fixture is deliberately supplied metadata; this is not a
claim that a test was performed against the production design store. `result.json` records the checks
and browser errors without credentials.

The reproducible assertion lane is:

```sh
scripts/agent-gradle.sh --exclusive :server:installDist
# Use JDK 21; the editor distribution is included in installDist.
npm ci --prefix preview-harness
npm --prefix preview-harness run harness:ui-builder-projects
```

The harness starts and stops its own isolated packaged server, tests project creation, `.uid`
identity isolation and download, shared resources, authentication, file review, copy status, and
editor readiness. It attaches before/after screenshots to its Playwright result. GitHub reads,
conflict detection and commit/PR writeback are tested against an injected transport in
`ServeUiBuilderProjectGithubTest`; no repository was modified by the browser run.

After the implementation PR merges and the server release rolls out, open
`https://ui.coo.ee/ui-builder/projects`, sign in, create a project and import a `.uid` file, then open
its editor and review/download it. Connecting a public repository needs no token; private reads and
publication use a repository-scoped token supplied for that action. Verify the proposed PR's files
before merging it. A release is needed: a passing local harness alone does not establish deployment.
