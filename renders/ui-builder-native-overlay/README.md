# UI builder — selectable overlays on the native render

Committed evidence for [#311](https://github.com/yschimke/compose-preview-server/issues/311): the
native render pane stops being a picture.

| file | what it is |
| --- | --- |
| `native-overlay.before.png` | `UiBuilderNativeOverlayPreview` with the payload's `nodeBounds` empty — the pane as it shipped: a frame and nothing over it |
| `native-overlay.after.png` | the same preview with the bounds the host now reports — the selected node (`search-placeholder`) outlined in the frame |

The two renders differ by the payload alone. The frame under both is the **same geometry fixture**,
not a Compose render: a preview cannot compile Kotlin, so `UiBuilderNativeRenderPreview`'s rule —
never stand a real frame in for one the host would have produced — still holds. What is under test
here is this repository's own half: whether the outline lands on the box the host reported, at the
scale the pane fits the frame at, for the node the editor says is selected. A regression in that
transform moves the outline off its block and this diff shows it.

The half no static render can show is the click: a tap inside a box dispatches `SelectNode` for the
smallest box containing it, which `UiBuilderEditor` then routes exactly as a layers-panel click.
`PlaygroundNodeBoundsServiceTest` covers the other end — a semantics tree in, rectangles out, and no
rectangle at all for an unplaced node, a duplicated tag, a backend with no semantics producer, or a
render that never finishes.
