# UI builder — a refused Add beside says why

Committed evidence for the row-level refusal message.

| file | what it is |
| --- | --- |
| `refusal.before.png` | `UiBuilderBesideRefusalPreview` rendered against the panel as it was: a disabled **Add**, the component id, and no reason anywhere |
| `refusal.after.png` | the same preview on this branch: the reason where the id was |

Both are the *same* preview, rendered either side of one file's change
(`UiBuilderEditor.kt`) — the fixture, the catalog, the search term and the document are identical,
so the difference between the two images is the feature and nothing else.

The design is a wear-m3 board: a root `layout/column` holding two items. That combination is what
isolates the case, and it is the one the guide got wrong before this. With no board the *document*
refuses the wrap, and that refusal has always been shown on the destination line; once a board
exists there is no wrap left to refuse, and the only thing that can still say no is the component —
`wear-m3/screen-scaffold`, whose emitter routes on the root component id and so cannot be one item
of a board. That was the refusal with nowhere to appear.

The catalog is filtered to `scaffold` so the row under discussion is the row you see. Nothing else
in the picture is arranged: the panel, the canvas and the inspector draw what they always draw.

Reproduce either with
`./gradlew :ui-builder:composePreviewRender --rerun -PcomposePreview.filter=UiBuilderBesideRefusalPreview`.
