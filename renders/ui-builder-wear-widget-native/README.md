# UI builder — a Wear widget on the native preview lane

The change these render for is server-side: `ScreenGeneratorComposeExportExecutor.generate` used to
refuse a `remote-m3` design, so the editor's **Native render** pane answered a widget author with a
sentence instead of a picture. It now emits the widget's `@RemoteComposable` body, its
`WearWidgetBrush` and the `WearWidgetParams` the design authored, and the synthesized preview entry
hands the three to glance-wear's `WearWidgetPreview`.

## Why the after picture is not in this directory

The after picture is drawn by the **Android/Robolectric daemon against a served `remote-m3`
bundle** — `androidx.glance.wear`, `androidx.compose.remote.creation.compose` and
`androidx.wear.compose.remote.material3` are all Android AARs, so no JVM or Wasm renderer in this
repository can produce it, and the sandbox this change was written in serves no such bundle. A
human or a deployment verifies it by opening any `remote-m3` design on a host that maps one
(`--ui-builder-native-catalog remote-m3=<served catalog>`; `preview.coo.ee` serves
`yschimke/wear-m3-catalog`'s `remote-catalog`, which carries the trio) and switching to Preview.

## What these two are, then

They are the **canvas** side of the same designs — the picture the native render is now expected to
agree with, rendered by `:ui-builder`'s own previews on this change:

- `weather-widget-canvas.png` — `WeatherWearWidgetSamplePreview`: the Weather widget from
  `android/wear-os-samples` as a design, inside the host's squircle container.
- `widget-brushes-canvas.png` — `WearWidgetBackgroundBrushPreview`: every background a
  `WearWidgetBrush` can carry — host default, colour, gradient, image. The image tile is the case
  this change had to solve on the native side: the export asks for a content picture as a
  parameter, which nothing downstream of the render lane can pass, so the native source inlines the
  bytes instead and both surfaces draw the same artwork.

Neither changed on this branch — the canvas was already right. They are here because the native
pane's job is to match them, and a reader comparing the two needs the left-hand side committed
somewhere.
