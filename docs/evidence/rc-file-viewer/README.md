# `.rc` Remote Compose file viewer evidence (#1237)

The `ui://compose-preview/rc-viewer` MCP App (`mcp-app/rc-viewer.html`) in a fake MCP Apps host
(`preview-harness/fixtures/pages/_mcp-app-rc-viewer-host.js`), captured with Playwright and
Chromium. The host inlines the vendored player bundle exactly where the server does
(`/*RC_PLAYER_BUNDLE*/`) and plays `scripts/design-artifacts/fixtures/watch-screen-round-clip.rc`.

- `file-entrypoint-light.png`: an OpenAI file entrypoint. The host sent FileInput
  `{file: {name, resourceUri: "host-resource://…"}}`; the viewer read the bytes with
  `resources/read` (`representation: "blob"`), subscribed to the URI, and plays the document in the
  host's light theme.
- `named-values-dark.png`: the host's dark theme, with the named-values editor open and
  `WearM3.surfaceContainer` overridden to `#FF1B5E20`, repainted live.
- `error.png`: `rc_open` returned its structured error (`not_found`); the viewer shows the message
  and code and sets `data-rc-player-state="error"`.

`npm --prefix preview-harness run harness:rc-viewer` asserts the same flows; `harness:pages`
captures the three fixture pages in both themes for the visual-diff bot.
