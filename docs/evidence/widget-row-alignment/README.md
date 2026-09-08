# A row's alignment reaches the generated widget

Evidence for [#518](https://github.com/yschimke/compose-preview-server/issues/518).

Both PNGs are the design `spotify-wear-widget` — a `remote-m3` Large Wear widget whose content row
declares `verticalAlignment: center` — exported by `WearWidgetCodeExporter`, compiled into
`:samples:wear-widget` in [`yschimke/compose-ai-tools`](https://github.com/yschimke/compose-ai-tools),
and rendered by that module's Android preview lane.

| File | `RemoteRow` gets | Result |
| --- | --- | --- |
| `before-row-top-aligned.png` | no alignment argument | Content hard against the top of the frame. |
| `after-row-centred.png` | `verticalAlignment = RemoteAlignment.CenterVertically` | Centred, as the canvas draws it. |

The canvas has always read `verticalAlignment` off the node, and defaults a row to
`CenterVertically`. The emitter read only the children's `alignVertical` **modifiers**, so a row
that declared the property — or declared nothing — generated no alignment and drew top-aligned. No
diagnostic: the design simply laid out differently from the one its author approved.

Neither render has album artwork: a generated widget takes its content bitmaps as
`RemoteImageBitmap` parameters and the generated `@Preview` supplies `ImageBitmap(1, 1)`
placeholders. The 52dp gap on the left is that placeholder occupying its slot.

The two differ in exactly one line of generated source. The "before" is the same file with

```kotlin
verticalAlignment = RemoteAlignment.CenterVertically,
```

removed from its `RemoteRow`, which is what the generator emitted before this change. Everything
else — the design, the compile, the preview, the footprint — is identical, so the vertical position
is the only variable.
