# Library selection shared with chat

Captured by `scripts/preview-library.test.mjs` in a controlled MCP Apps host.
The preview pixels are the real ComposeStarter render recorded in
`../preview-library-recovery/compose-starter.png`. Listings and host acknowledgements
are test fixtures; these images do not claim a live ChatGPT end-to-end run.

- `after.png`: selected preview with its host-acknowledged chat context.
- `local-designs.png`: selected local `.uid` file with chat context and editor action.
- `before.png`: empty library before the fixture registers a project.

The tests assert context payloads for local previews, local designs, and hosted
designs, clearing on tab changes and deleted local files, serialized updates, and
nonblocking host rejection. No image bytes or file contents are sent in context.
