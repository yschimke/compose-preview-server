# A widget background picture, inlined

Evidence for [#519](https://github.com/yschimke/compose-preview-server/issues/519).

`after-inlined-background.png` is the design `spotify-wear-nowplaying` — a `remote-m3` Large Wear
widget whose artwork sits in the container's `background` brush chain under two gradients —
exported by `WearWidgetCodeExporter`, compiled into `:samples:wear-widget` in
[`yschimke/compose-ai-tools`](https://github.com/yschimke/compose-ai-tools), and rendered by that
module's Android preview lane.

There is no "before" image to pair it with: before this change the design did not export at all.
The whole document was refused with

```
the image background `bg-art` needs a RemoteImageBitmap, which a generated file cannot name from an
asset key — supply the bitmap in provideWidgetData and add `WearWidgetBrush.image(bitmap)` by hand
```

so the only picture a "before" could show is the absence of a file.

The render is one preview rather than two: the generated `@Preview` now takes the widest footprint
the shipped provider yields (216×124dp) instead of unrolling one per value.

The content sits at the top of the frame rather than centred because the exporter drops a row's
`verticalAlignment` — [#518](https://github.com/yschimke/compose-preview-server/issues/518), not
fixed here.
