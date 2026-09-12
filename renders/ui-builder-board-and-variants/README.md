# UI builder — the board, the frame, and the variant strip

Committed evidence for the three axes
[`UI_BUILDER_CANVAS_FRAMES_VARIANTS.md`](../../docs/design/UI_BUILDER_CANVAS_FRAMES_VARIANTS.md)
unpins: what is *in* a design, what it is *measured in*, and how many pictures of it you look at.

| file | what it is |
| --- | --- |
| `board.before.png` | `UiBuilderBoardBeforePreview` — one screen, Add beside switched on, nothing added yet |
| `board.after.png` | `UiBuilderBoardPreview` — the same design after two Adds beside: a board of three items |
| `variant-strip.before.png` | `UiBuilderCanvasForwardPreview` — a design claiming no devices, which is what every design drew before this: one pane |
| `variant-strip.after.png` | `UiBuilderVariantStripPreview` — the authoring canvas at one frame, and the preview pane beside it holding the tablet the design claims and the Dark question |
| `frame-inspector.before.png` | `UiBuilderDevicePresetPhonePreview` rendered at `origin/main` |
| `frame-inspector.after.png` | the same preview on this branch |

The strip's preview puts its design under the **light** scheme, which is not incidental: the
Jetcaster fixture's own environment is `"theme": "dark"`, so a Dark axis over it drew a third pane
identical in theme to the first two — a picture proving a pane is laid out and nothing about the
override reaching the colours. A variant that matches the design it varies is a finding, the same way
a before/after pair of identical images is.

## What each pair shows

**The board.** Read the two side by side: the insert panel's destination line goes from "Adds beside
the design, on a new board" to "Adds beside 3 item(s) on the board", and the canvas draws the three
items 24 dp apart down the middle of the frame. Nothing in that picture is synthetic — the board is a
`layout/column` the renderer already knew how to draw, the layers panel lists it as the ordinary node
it is, and both Kotlin exporters and the screen projection were not touched by this change at all.
The inspector on the right gains "A board of 3 items", and its frame reads `Custom size`: 900 × 1400
is not a device and the picker no longer implies one.

**The variants.** The after is the headline. One document, drawn three times, but **not on one
surface**: the authoring canvas holds a single frame — the design's own phone frame, with the
selection overlay on it and the whole pane to itself — and the two read-only frames are in the
workspace's preview pane beside it. `Pixel Tablet` is the device the document already claimed in
`exportDevices`, and which until now only the Compose export read; `Dark` is the question being
asked of it. Each is labelled with the properties it applied rather than only with a name, and
nothing draws a bezel: a device pane *is* a width, a height and a density.

The variants were drawn on the canvas for one release, which was the wrong place — the one surface
you edit on grew a row of surfaces you cannot, and the fit shrank the frame you were working in to
make room for them. You build the UI once; the pane beside it is where you watch it adapt. Note the
zoom readout: the same design at 78% where the strip had it at 33%.

The tablet pane is worth looking at twice: it draws the supporting pane the phone does not. **These
renders predate the real library** — see the note below.

**The frame inspector.** A true before/after of one preview across the change: `Screen environment` /
`Device` / `Also exports as` becomes `Frame` / `Set frame from` / `Also shown and exported as`, plus
the `Also compare` chips that switch the unstored axes on. The fields below are untouched — a board
is measured by a frame like everything else, so nothing is hidden, only the claim changed.

## What the tablet pane was, when these were captured

At the commit these PNGs were rendered from, `layout/supporting-pane-scaffold` was drawn by
`DeterministicSupportingPaneScaffold` — a `BoxWithConstraints` in this repository's own renderer that
expanded when the frame was wider than `mainPaneWidth + supportingPaneWidth + spacing`. It was **not**
`androidx.compose.material3.adaptive`'s `SupportingPaneScaffold`, and it used neither that library's
breakpoints nor its posture. The Kotlin export emitted a second hand-rolled helper with a *different*
threshold again (a hard `1280.dp`), and the native lane refused the component outright.

So these pictures show that a frame change reaches the layout, which is what the pane is for. They do
not show what `SupportingPaneScaffold` would do at that width.

**That gap is now mostly closed**, after these renders were captured: the variant panes and the
capability exporter's generated Kotlin call the real scaffold, and each frame computes its own window
size class so a row of frames can disagree. The record-driven projection still refuses the component. `layoutMode` became a `PaneScaffoldDirective` rather than a width
comparison. The canvas keeps a stand-in on purpose — it draws every declared pane at every width so
no subtree becomes uneditable — which is
[`UI_BUILDER_PREVIEW_FIDELITY.md`](../../docs/design/UI_BUILDER_PREVIEW_FIDELITY.md)'s rung-1 licence,
not a leftover. Read the pane geometry in these two PNGs as historical; everything else in them still
holds.

## How this stays honest

None of the six is hand-made or hand-placed. All are `@Preview` renders in
[`UiBuilderEditorChromePreview.kt`](../../ui-builder/src/jvmMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderEditorChromePreview.kt),
so `./gradlew :ui-builder:composePreviewRender -PcomposePreview.filter=<name>` reproduces any of
them and the next change to the board, the strip or the inspector is diffed without anyone
remembering to.

The two board pictures are one preview apart in exactly one respect — the second seeds two
`InsertComponentBeside` events through the ordinary reducer — so the difference between them is the
feature and nothing else.
