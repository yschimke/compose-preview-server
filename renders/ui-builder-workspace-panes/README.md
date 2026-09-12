# The workspace's three panes, before and after

Committed evidence for
[`UiBuilderEditor`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderEditor.kt)
— which design panes the workspace draws, and what each of them is.

Before this change the panes were a **ladder**: one value, `1 pane` / `2 panes` / `3 panes`, always
in that order. A ladder can only count, and counting is the least interesting thing about the panes
— you could not ask for the preview without the editor, and the *second* rung was the one that cost
a host compile, so a second look at a design paid for the target platform whether or not the target
platform was the question. Beside it sat a `Design | Preview` mode switch doing a third, overlapping
thing to the same canvas.

Now there are three independent switches, drawn left to right in this order:

| pane | what draws it | what it costs |
| --- | --- | --- |
| `Editor` | this browser's Compose, with the authoring overlay | nothing |
| `Preview` | the same renderer with the overlay taken off, across every device and axis the design claims | nothing |
| `Native` | the host — the compiled Android/desktop render, or the exported document played where there is no compile lane | a host round trip |

The last open pane cannot be switched off; everything else is free. `Design | Preview` is gone —
"watching rather than editing" is `Editor` off and `Preview` on.

| file | what it is |
| --- | --- |
| `three-panes.before.png` | all three panes as they shipped: the mode switch, `3 panes`, the compiled render second and a Wasm rendition third |
| `three-panes.after.png` | the same three, now named `Editor + Preview + Native`, with the browser panes first and the host pane last |
| `watching.before.png` | the old `Preview` mode: the canvas handed to the screen, with nothing beside it |
| `watching.after.png` | the same thing as a pane set — `Editor` off, `Preview` on |
| `editor-and-preview.after.png` | the pair this change is for: authoring beside a live, non-editable rendition, with no host involved |

All five are `composePreviewRender` output for previews in
[`UiBuilderEditorChromePreview.kt`](../../ui-builder/src/jvmMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderEditorChromePreview.kt)
— `UiBuilderRenderComparisonPreview`, `UiBuilderPreviewModePreview` and
`UiBuilderEditorAndPreviewPanesPreview`. The `before` pair was rendered from `origin/main` at
`6f15170`; the `after` three from this branch.

```shell
./gradlew :ui-builder:composePreviewRender --rerun \
  -PcomposePreview.filter=UiBuilderRenderComparisonPreview
```

The native pane draws its **refusal** state in both comparison renders, and that is not a
placeholder — it is what this fixture actually produces, read from the same reducer the problems
panel and the code pane read. A preview cannot compile Kotlin, so nothing here stands a real render
in for one the host would have produced.
