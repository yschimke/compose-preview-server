# The native pane, live on Android

Committed evidence for the workspace's third pane
([`UiBuilderEditor`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderEditor.kt)'s
`EditorPane.Native`) — what it draws, and whether you can use the screen in it.

The native pane compiled the design on the host and drew the **first frame**. The compile lane has
always stood a live, streamed, interactive session up behind that frame — the same daemon, the same
classes, and on a catalog whose `previewSurfaces.native.backend` is `android` that daemon is
Robolectric-backed Android — and the route has always answered with the token it is opened on
(`previewToken`, `previewUrl`). The editor threw those away and showed the still.

Now it opens them. `/{session}/ws/{preview}` is the same socket the viewer's Live toggle uses:
frames are pushed, and a tap on the pane is dispatched into the real composition as an `input`
message in the frame's own pixels. No new protocol, no new handler — see
[`ServeStreamProtocol`](../../server/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeStreamProtocol.kt)
and
[`PlaygroundRedeemService`](../../server/src/main/kotlin/ee/schimke/composeai/cli/serve/PlaygroundRedeemService.kt).

| file | what it is |
| --- | --- |
| `native-still.png` | the pane as it was: one compiled frame, with the node boxes that make a click select a layer |
| `native-live-android.png` | the pane streaming, captioned `Native · live on Android · taps reach the screen` |

Both are `composePreviewRender` output for previews in
[`UiBuilderEditorChromePreview.kt`](../../ui-builder/src/jvmMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderEditorChromePreview.kt)
— `UiBuilderNativeOverlayPreview` and `UiBuilderNativeLivePreview`.

```shell
./gradlew :ui-builder:composePreviewRender --rerun \
  -PcomposePreview.filter=UiBuilderNativeLivePreview
```

**Neither frame is a real Android render, and neither claims to be.** A `@Preview` cannot compile
Kotlin, let alone hold a Robolectric daemon open, so both draw the same flat geometry fixture
`UiBuilderNativeOverlayPreview` has always drawn — blocks at the boxes a host would have reported.
What is under test here is this repository's own half: which caption the pane shows, that the
streamed frame replaces the still rather than sitting beside it, and that a press arrives as one
`click` in the frame's pixels. Those are pinned in
[`NativeLivePaneTest`](../../ui-builder/src/jvmTest/kotlin/ee/schimke/composeai/uibuilder/NativeLivePaneTest.kt).
The end-to-end Android lane is exercised by
[`preview-harness/verify-remote-native-preview.mjs`](../../preview-harness/verify-remote-native-preview.mjs)
against a real server and daemon.

## Selection belongs to the still

A still is a picture with a map of node boxes over it, so clicking it selects a layer. A live
session **is** the screen, so clicking it is the click. A tap that both selected a node and pressed
the button under it would be two answers to one gesture, and the one a designer wants in this pane
is the button. The layers panel still selects, on either.
