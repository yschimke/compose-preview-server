# Five Google app screens, designed in the builder

Committed evidence for the five sample designs under
[`docs/design/fixtures/ui-builder/designs/`](../../docs/design/fixtures/ui-builder/designs/README.md)
whose ids begin `google-`. Each is a document the builder can open, select and edit — not a picture
of a UI — authored against `m3-catalog` and drawn by `composePreviewRender` through the previews in
[`GoogleAppSizePreviews.kt`](../../ui-builder/src/jvmMain/kotlin/ee/schimke/composeai/uibuilder/GoogleAppSizePreviews.kt).

They exist to answer one question: **does authoring a real tablet UI against the real composables,
and letting the AndroidX adaptive libraries do the responding, actually work?** What it turned up is
[`UI_BUILDER_GOOGLE_APP_SAMPLES.md`](../../docs/design/UI_BUILDER_GOOGLE_APP_SAMPLES.md).

| design | the screen it draws | what it exercises |
| --- | --- | --- |
| `google-gmail-tablet` | inbox list beside the open conversation | `SupportingPaneScaffold`, `Scaffold`, `SearchBar`, `ListItem`, `LazyColumn` |
| `google-photos-tablet` | library grid under a search bar | `GridCells.Adaptive`, `FilterChip`, aspect-ratio tiles |
| `google-calendar-tablet` | day schedule beside a mini month | `SupportingPaneScaffold` the other way round, `Card`, `ListItem` |
| `google-keep-tablet` | pinned notes as a reflowing card grid | `GridCells.Adaptive`, `Checkbox`, `Card`, floating toolbar |
| `google-play-tablet` | games home with a carousel and a chart | `TabRow`, carousel, `Button`, mixed scroll |
| `google-home-wear` | the Home devices list on a watch | `AppScaffold`, `ScreenScaffold`, `TransformingLazyColumn`, `SwitchButton`, `Slider`, `EdgeButton` |

Three frames each, named after the width size class they land in and matching the presets the
designs carry in `environment.exportDevices`:

| suffix | frame | preset |
| --- | --- | --- |
| `-expanded` | 1280 × 800 dp | Pixel Tablet |
| `-medium` | 841 × 701 dp | Pixel Fold |
| `-compact` | 411 × 914 dp | Pixel 7 |

**The frame is the window.** `AdaptiveSupportingPaneScaffold` computes its size class from the
frame's own constraints rather than from `currentWindowAdaptiveInfo()`, so Gmail and Calendar
dropping to one pane between `-expanded` and `-medium` is `androidx.compose.material3.adaptive`'s
own answer, not this repository's approximation of it.

Where an app's real chrome has no catalog entry — a navigation rail, a bottom bar, a staggered grid,
a chip flow row — the design stands in something built from `layout/row`, `layout/column` and
`m3/*`. That is the point rather than a shortcut: the gap between what these screens need and what
the palette offers is what the samples measure, and it is written down in the findings above.

## The Wear screen

`wear-home-small.png` and `wear-home-large.png` are the 192 dp and 240 dp round watches — the two
sizes the design names in its own `exportDevices`, and the two a `TransformingLazyColumn` wraps
differently at, since 48 dp is a quarter of the small round's width.

A round frame shows one screenful and this design is about five, so `-extent` renders the same
document in a frame taller than any watch. `UiBuilderSurface` draws the design's full extent when
given the room — the stadium the editor's canvas uses — so the scroll is something you read rather
than operate.

**`AppScaffold` is not a component.** Declaring `timeText` on `wear-m3/screen-scaffold` is what makes
the exporter wrap the screen in `AppScaffold(timeText = { TimeText { … } }) { ScreenScaffold(…) }`,
which is the only way the builder expresses it.

**The dashed boxes are the canvas being honest.** `UiBuilderRenderer` draws six of this catalog's
components and everything else falls through to the undrawn-component placeholder, while the
capability fixture declares twenty-three of them `supported`
([#907](https://github.com/yschimke/compose-preview-server/issues/907)). Read the generated Kotlin
for the other half: it is the real `AppScaffold`, `ScreenScaffold` with `scrollState`,
`scrollIndicator` and `edgeButton`, a `TransformingLazyColumn` carrying
`Modifier.transformedHeight(this, spec)` and `SurfaceTransformation(spec)` on every row, real
`SwitchButton`, `Slider`, `ListSubHeader` and `EdgeButton`, and a hoisted `remember { mutableStateOf }`
per switch and slider.
