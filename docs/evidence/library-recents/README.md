# Recent items and cached thumbnails

`cached-preview.png` was captured by `scripts/preview-library.test.mjs` in a
controlled MCP Apps host. Preview pixels derive from the real ComposeStarter
render in `../preview-library-recovery/compose-starter.png`. Registration, widget
persistence, and the offline error are fixtures, not a live ChatGPT session.

`fresh-preview.png` shows the successful full render alongside its real thumbnail.
The cached screenshot shows a thumbnail restored after the widget reopens, its capture
time in Recent, and an explicit failed refresh while retaining those pixels.

Recents use the optional host's `window.openai.widgetState` / `setWidgetState`
through `privateContent`, preserving other widget fields. Hosts without that
extension retain recents only while the panel stays alive. This is presentation
state scoped to the host widget and current project identities, not durable
cross-conversation storage. Only current authoritative listings make recents
clickable; missing items are hidden. Eight recent items, 192px PNG thumbnails,
and 80,000 characters per thumbnail bound the persisted payload. Local `.uid`
files have metadata recents; thumbnails require an actual successful render.
