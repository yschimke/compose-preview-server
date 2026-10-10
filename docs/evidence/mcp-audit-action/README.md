# MCP viewer review action

Before and after use the actual cached ComposeStarter Wear render from
`docs/evidence/preview-library-recovery/compose-starter.png`. The browser host
is a protocol fixture, not a live Codex session. The new action requests a
review in chat; neither picture represents completed guideline findings.

- `before.png`: viewer at main commit `b3391c5d` before this change.
- `after.png`: viewer with Review design guidelines and its collapsed copyable
  request. Accessibility and layout presentation remain separate.

The browser tests cover successful delivery, exact subject/overrides, missing
messaging, static mode, protocol rejection, tool-level rejection, changed
subjects, UI Builder revision, and credential-bearing subjects. Saved-result
cases cover fresh, stale, missing and denied records, preserving unanswered
coverage and rendering finding text without treating it as HTML. Tests never
call a provider or edit a design. Live MCP Apps host delivery is unverified.

To reproduce with an installed Playwright and Chromium:

```sh
NODE_PATH=/path/to/playwright/node_modules HARNESS_CHROMIUM=/path/to/chromium \
  node --test scripts/mcp-viewer-audit.test.mjs
```

Set `MCP_AUDIT_EVIDENCE` to an output directory to capture the current viewer.
Also set `MCP_AUDIT_BEFORE_HTML` to an original viewer HTML file to capture the
baseline. CI uses the existing visual-harness Playwright installation.
