# UI builder — component packs

Two previews from `ui-builder/src/jvmMain/kotlin/ee/schimke/composeai/uibuilder/ComponentPackPreviews.kt`,
rendered with `./gradlew :ui-builder:composePreviewRender --rerun -PcomposePreview.filter=Pack`.
Both surfaces are new on this change, so there is no `before` — the settings dialog did not exist,
and a pack node on the canvas used to be the red **Unsupported component** diagnostic.

- `packs-panel.after.png` — `ComponentPacksPanelPreview`: the body of the **Component packs** dialog
  with two packs the host admitted, Confetti Mobile on and Jetnews off.
- `pack-placeholder.after.png` — `PackPlaceholderPreview`: a Material 3 column holding a Confetti
  `SessionCard` and `SpeakerRow`, drawn as captioned placeholders in the same dashed-outline shape
  `wear-m3`'s native-only components take. The canvas cannot link Confetti's classes; the picture of
  the real component comes from the native lane, compiled against the `confetti-mobile` bundle.

See [`docs/design/UI_BUILDER_COMPONENT_PACKS.md`](../../docs/design/UI_BUILDER_COMPONENT_PACKS.md).
