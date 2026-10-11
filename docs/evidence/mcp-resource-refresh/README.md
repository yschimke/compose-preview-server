# Retain a rendered preview when resource refresh is unavailable

The MCP app could draw the PNG in a tool result, then erase it when its optional
`resources/read` failed or returned no PNG. The browser regression tests feed
an inline render and resource link through a simulated MCP Apps host, then deny
the resource refresh. The existing viewer reproduces the text-only fallback;
the fixed viewer retains the image and reports that refresh is unavailable.

`before.png` and `after.png` capture that same browser scenario. These are
simulated-host evidence, not screenshots of native Codex. The tests also check
that a different preview without pixels does not reuse the previous image, and
that a host honoring size notifications enlarges a 160px initial frame.

Run from the repository root with Playwright installed on `NODE_PATH`:

```sh
node --test scripts/mcp-viewer-audit.test.mjs
```

Optional `MCP_RESOURCE_EVIDENCE` writes refresh screenshots into an existing
directory; `MCP_RESOURCE_VIEWER_HTML` selects a baseline viewer for comparison.
