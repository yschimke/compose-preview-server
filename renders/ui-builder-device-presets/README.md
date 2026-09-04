# UI builder — device frame presets

Committed evidence for the Screen inspector's **Device** menu: one document, one preset apart.

The frames come from `ee.schimke.composeai:daemon-devices`' `DeviceDimensions` — the catalog
`ServeDeviceFrame.from` resolves when it decides what a `@Preview(device = …)` actually renders
at — handed to the editor over `GET /api/ui-builder/v1/device-presets` because `:ui-builder`
ships a `wasmJs` target and that module applies `kotlin.jvm`.

| file | what it is |
| --- | --- |
| `screen-inspector-pixel-7.png` | `UiBuilderDevicePresetPhonePreview` — the canvas on `id:pixel_7`, 411 × 914 dp at 2.625× |
| `screen-inspector-pixel-tablet.png` | `UiBuilderDevicePresetTabletPreview` — the same document on `id:pixel_tablet`, 1280 × 800 dp at 2.0× |

Read side by side: the Device button names the frame, the three fields below it move together, and
the design's supporting pane — closed on the phone, open on the tablet — is the adaptive behaviour
the whole feature exists to let someone check.

## How this stays honest

Neither PNG is hand-made or hand-placed. Both are `@Preview` renders in
[`UiBuilderEditorChromePreview.kt`](../../ui-builder/src/jvmMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderEditorChromePreview.kt),
so `./gradlew :ui-builder:composePreviewRender` reproduces them and the visual-diff bot compares
the next change to the inspector without anyone remembering to.

The geometry in them is not restated anywhere on the production path:
`UiBuilderDevicePresetsTest` asserts every offered preset equals `DeviceDimensions.resolve` for its
id, so a frame the builder offers is a frame the backend will render.
