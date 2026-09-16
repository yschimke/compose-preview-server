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
