# UI builder · the Lottie element

Committed evidence for `remote-m3/lottie`, the Wear widget catalog's Lottie element, as the
**canvas** draws it. The canvas is the half of this feature a picture can show; the other half is
the generated Kotlin, which the PR quotes.

The element does not play an animation, and the canvas does not pretend it does. Horologist's
[`LottieAnimation`](https://github.com/google/horologist/tree/main/remotecompose/lottie) *compiles*
the animation into the widget's Remote Compose document while the document is being built — the
browser can neither run that Android-only creation API nor host a Lottie runtime to fake it with.
So what is drawn is the node's identity, its source, and whether it is ready to export, which is
what an author can act on.

| file | what it is |
| --- | --- |
| `resolved.png` | an element holding the animation: its weight, and the sentence that says where the animation ends up |
| `unresolved.png` | an element still carrying only its URL — what the export refuses, said at the moment it can still be fixed |
| `empty.png` | a freshly inserted element, with neither |

All three are `renderComposeScene` captures at a Small widget's own 216×76dp, written by
[`LottieElementRenderingTest`](../../ui-builder/src/jvmTest/kotlin/ee/schimke/composeai/uibuilder/LottieElementRenderingTest.kt),
so a change to the placeholder moves this evidence along with the test that asserts it.
