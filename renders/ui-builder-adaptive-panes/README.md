# The adaptive scaffold, before and after the real library

Committed evidence for `layout/supporting-pane-scaffold` in the workspace's preview pane — who
decides how many panes a frame shows.

The design is the frozen Jetcaster fixture, whose root is a supporting-pane scaffold with
`layoutMode` set to `expandedTwoPane`. It is authored at the **Pixel Fold** (841×701 dp) and claims
the **Pixel 7** (411×914 dp), so the preview pane draws the same document at both.

| file | what it is |
| --- | --- |
| `fold-and-phone.before.png` | the stand-in deciding: the fold shows **one** pane and half an empty frame, though the design asked for two |
| `fold-and-phone.after.png` | `androidx.compose.material3.adaptive` deciding: the fold shows **two**, the phone beside it still shows one |

Both are `composePreviewRender` output for `UiBuilderAdaptivePaneVariantsPreview` in
[`UiBuilderEditorChromePreview.kt`](../../ui-builder/src/jvmMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderEditorChromePreview.kt).
The `before` was rendered from `origin/main` at `1d39586` with the same preview function grafted in;
the `after` from this branch.

```shell
./gradlew :ui-builder:composePreviewRender --rerun \
  -PcomposePreview.filter=UiBuilderAdaptivePaneVariantsPreview
```

## Why the fold, and not a tablet

A tablet would have proved less than it looks. The stand-in expanded when the frame was wider than
`mainPanePreferredWidthDp + supportingPanePreferredWidthDp + paneSpacingDp`, and on this design's
values that sum is `744 + 512 + 24` — exactly 1280, a Pixel Tablet's width. Both rules expand there,
so the two renders would have differed only in how the partitions were sized.

At 841 dp they disagree outright: the library calls that an expanded window and the stand-in's sum
does not reach it. So a design authored for a foldable was shown one pane and shipped two — which is
the class of bug the preview pane exists to catch, arriving from the preview pane itself.

## What is not in these pictures

The **visual editor's** canvas, which does not collapse at any width. That is deliberate — a hidden
pane there is a subtree that cannot be selected, dropped into or edited — and it is a claim about
structure rather than about pixels, so it is pinned in
[`AdaptiveSupportingPaneTest`](../../ui-builder/src/jvmTest/kotlin/ee/schimke/composeai/uibuilder/AdaptiveSupportingPaneTest.kt)
rather than rendered. The rung it sits on is
[`UI_BUILDER_PREVIEW_FIDELITY.md`](../../docs/design/UI_BUILDER_PREVIEW_FIDELITY.md).
