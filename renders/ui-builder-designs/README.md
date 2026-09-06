# The builder's screens, designed in the builder

Committed evidence for the designs under
[`docs/design/fixtures/ui-builder/designs/`](../../docs/design/fixtures/ui-builder/designs/README.md):
every screen of the UI builder's own chrome, authored as a design in the builder's `m3-catalog`
and rendered by `composePreviewRender` through the previews in
[`DesignFixturePreviews.kt`](../../ui-builder/src/jvmMain/kotlin/ee/schimke/composeai/uibuilder/DesignFixturePreviews.kt).
None of these is a picture of a UI: each is a document the builder can open, select and edit, and
the same file is what `DesignFixturesTest` replays, validates and exports on every build.

| file | the screen it designs |
| --- | --- |
| `new-design-dialog.png` | the Create a new design dialog, with the Shuffle name suggestion |
| `new-design-screen.png` | the same dialog as the whole window, which a fresh host opens on |
| `shortcuts-dialog.png` | Keyboard and pointer |
| `layers-issues.png` | the Layers navigator open beside the Issues dock |
| `code-pane.png` | the Code pane docked, with the Properties inspector |
| `screen-dock.png` | the Screen dock: environment above the reference inspector, overlay on the canvas |
| `theme-dock.png` | the Theme builder |
| `talk-dock.png` | the Talk dock, with two comment pins on the frame |
| `mobile.png` | the mobile workspace at 412 × 915 with the Layers panel raised |
| `native-both.png` | render surface Both: the Wasm canvas beside the native render, menu open |
| `menus.png` | the selection menu, the toolbar overflow and the hover editor |

Where a chrome widget has no catalog entry — a segmented button, a navigation rail, a dropdown
menu, a code editor — the design stands in a placeholder built from `m3/surface`, `layout/row`
and `m3/text`, with sample content. That is the point rather than a shortcut: the gap between what
the chrome uses and what the palette offers is exactly what these designs measure.
