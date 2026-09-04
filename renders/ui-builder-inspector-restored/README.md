# UI builder — the inspector, rendering again

Committed evidence for the `minLines` / `maxLines` validator fix.

These three `@Preview`s produced **no image at all** beforehand. Each threw out of
`CapabilityValidator.validateProperties`, which read a line-count property with `.jsonPrimitive`:

```
java.lang.IllegalArgumentException: Element class kotlinx.serialization.json.JsonObject is not a JsonPrimitive
  at ee.schimke.composeai.uibuilder.capability.CapabilityValidator.validateProperties(CapabilityValidator.kt:181)
  at ee.schimke.composeai.uibuilder.UiBuilderEditorReducer.canBindToState(UiBuilderEditorState.kt:802)
  at ee.schimke.composeai.uibuilder.UiBuilderEditorKt.UiBuilderEditor(UiBuilderEditor.kt:317)
```

There is no "before" image to show, because failing to produce one *is* the before.

| file | what it is |
| --- | --- |
| `editor-chrome.png` | `UiBuilderEditorChromePreview` — the `m3/text` property inspector, the panel the crash was reached through |
| `issues-inspector.png` | `UiBuilderIssuesInspectorPreview` — the seeded-problems panel |
| `layer-filter.png` | `UiBuilderLayerFilterPreview` — the layers panel filtered to `m3/filter-chip` |

All three select `search-placeholder`, an `m3/text`. `m3/text` declares `minLines` and `maxLines`,
`canBindToState` probes every declared property with a state binding, and a bound property is an
object with no `value` key — so the probe built exactly the shape the validator could not read.

`UiBuilderLayoutInspectorPreview` kept rendering throughout: it selects `discover-grid`, a
`layout/lazy-grid`, whose capability declares neither property.

## Not just the previews

`canBindToState` runs for the live editor's `bindableProperties` on every composition, so selecting
any text node in a document that has a state variable took the browser editor down the same way.
The previews are where it was visible; they were not where it mattered.
