# The New design dialog, before and after

Committed evidence for `NewDesignDialog` in
[`UiBuilderEditor.kt`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderEditor.kt).

| file | what it is |
| --- | --- |
| `new-design.before.png` | the dialog as it shipped: an empty Design ID, the state form always open |
| `new-design.after.png` | form factors (Mobile, Wear, RemoteCompose), a generated id with **Shuffle**, state folded away |

Both are `composePreviewRender` output for `UiBuilderNewDesignPreview` in
[`UiBuilderEditorChromePreview.kt`](../../ui-builder/src/jvmMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderEditorChromePreview.kt),
so `./gradlew :ui-builder:composePreviewRender -PcomposePreview.filter=UiBuilderNewDesignPreview`
reproduces them and the visual-diff bot compares the next change to the dialog. The generated id
comes from [`NewDesignNames`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/NewDesignNames.kt),
which is why the two renders of the same preview never show the same name.
