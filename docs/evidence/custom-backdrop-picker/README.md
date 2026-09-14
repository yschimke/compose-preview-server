# Caller-supplied preview backdrop

The before state is the existing
[`viewer-toolbar-consolidation/after-panel-view-group.png`](../viewer-toolbar-consolidation/after-panel-view-group.png):
the View group offered only transparency inspection and fit mode.

`after.png` uses the same `serve-viewer-rc-parallel` fixture with its Overrides drawer open. The
fixture's stage image was replaced with the transparent Glimmer `ButtonSticker`, then the bundled
Venice scene was selected through the new local file input. The scene stays browser-local and the
preview uses CSS `plus-lighter`, matching the clamped additive display model without a daemon
re-render or server upload.
