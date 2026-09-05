# Code pane: Kotlin syntax highlighting

Before/after for `UiBuilderCodePanePreview` — the builder's **Code** pane, showing the Compose
source the export would write for the design on the canvas.

| | |
| --- | --- |
| `before.png` | one colour. `package`, the import block, `@Composable`, the `"Discover"` string literal and the braces are all `onSurface`, so reading the generated source means reading it as prose. |
| `after.png` | the same source through `dev.snipme:highlights` with the Darcula dark palette: keywords orange, the annotation in the metadata yellow, string literals green, punctuation picked out from identifiers. |

The pane is a Compose `Text` on a Wasm canvas, which is why the highlighter is a Kotlin library
producing `AnnotatedString` spans rather than the CodeMirror the playground's Source lane runs.
CodeMirror owns DOM nodes and nothing about it can reach inside the canvas; putting it here would
mean a DOM element positioned over the canvas with focus, scrolling, theming, selection and z-order
all becoming manual, to buy gutters and squiggles this pane has no use for — a design the export
cannot express is already a refusal with reasons, not a diagnostic on a line.

Rendered with `./gradlew :ui-builder:composePreviewRender --preview UiBuilderCodePanePreview`, so
the next change to this surface is diffed without anyone having to remember it exists.
