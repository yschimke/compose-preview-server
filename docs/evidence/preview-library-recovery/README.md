# Preview library recovery

The MCP Apps test host in `scripts/preview-library.test.mjs` reproduces an already-open empty
sidebar, a project registered by another chat, discovery, and automatic first rendering.
`before.png` and `after.png` show that sequence; the host/project tree is a controlled fixture.
The preview image is **real Compose output**, not a stand-in: `compose-starter.png` is the local
MCP `render_preview` result for ComposeStarter's `ListScreenPreview_Devices - Small Round`,
rendered on 2026-10-04, SHA-256
`a1d5afdaab8f3039ab3ef607c9da45c6f8eeb13bed06b5764699ce42e723598f` (384 × 384).

The browser test also verifies that revisiting the preview displays these cached pixels while
refreshing, and retains them when the daemon returns an error. This cache is bounded to twelve
successful images **within the open app session**; it does not restore images after closing the app.

Run with Playwright installed (or available through `NODE_PATH`):

```sh
PREVIEW_LIBRARY_EVIDENCE=docs/evidence/preview-library-recovery node --test scripts/preview-library.test.mjs
```

The wire regression in `PreviewLibraryMcpTest` opens a separate supervisor before another store
writes the registration, then asserts that an unscoped library refresh restores it without spawning
a daemon. Only an explicitly opened project, or the single-project default, starts preparation.
