# Preview server Wasm frontend

An isolated Compose/Wasm prototype over the preview server's existing public client contracts. It
does not share source or build output with `cli/serve-web`.

Build the server distribution. It carries the static app under `wasm-ui/`:

```shell
./gradlew :server:installDist
```

Start a normal local preview server and add this distribution to its existing Wasm asset lane:

```shell
cli/build/install/compose-preview/bin/compose-preview browse \
  --wasm-ui-dir server/build/install/compose-preview-server/wasm-ui
```

The prebuilt preview-host image registers that packaged directory automatically, so deployed hosts
serve it per catalog without a volume mount or `SERVE_WASM_DIR` setting. That environment variable
remains available for catalog-owned applications, which take precedence over the fallback.

The command prints the server URL and token. Open the normal UI as usual, then open the prototype
side by side at:

```text
http://127.0.0.1:<port>/wasm/<catalog-id>/?token=<token>
```

The catalog id is part of the path. The app uses the matching canonical API, snapshot,
legacy-viewer, and WebSocket paths. Use `?token=...&preview=<id>` to deep link directly into a
preview. Add `&live=1` to connect its live stream immediately (handy for a saved development URL or
browser smoke test). Old `/wasm/preview-ui/?session=<catalog-id>` links redirect here.

Open the native UI Composer directly at `/wasm/compose-m3/?token=...&compose=1`.

Implemented in the prototype:

- catalog loading, filtering, responsive preview cards, and direct preview deep links;
- in-process Compose Multiplatform rendering for the built-in `compose-m3` catalog: its cards and
  detail stage call the shared catalog composables directly inside this Wasm runtime, with no PNG
  or live-daemon round trip;
- native interaction plus dark mode, font scale, RTL locale, transparent background, and published
  override-variant seeds for those `compose-m3` components;
- a native UI Composer workspace: drag or add compiled catalog components onto a phone canvas,
  reorder/duplicate/remove them, and drop components into regions declared by `PreviewSlot`;
  nested content is composed in-process and remains interactive in preview mode. Slot authors can
  declare fixed, fill, or hug sizing independently per axis plus content padding, so a measured
  slot rectangle is not mistaken for its layout contract;
- baked PNG previews through `/render/{id}.png`;
- persistent live preview frames through `/ws/{id}` with connection/error state;
- light/dark, font-scale, locale, and transparent-background render overrides;
- pointer taps forwarded into live compositions; and
- links back to the existing viewer for advanced inspection surfaces.

Native rendering is an additive catalog registry. A catalog/component without a compiled-in CMP
implementation automatically keeps the snapshot and server-live behaviour; currently that includes
Wear (whose Compose artifacts have no Wasm target) and the `compose-m3` full-app template.

Slot targets come from the preview code rather than a composer-only manifest. A compiled component
opts in with `PreviewSlot`; the builder observes and fills that same region directly:

```kotlin
PreviewSlot(
  name = "content",
  modifier = Modifier.fillMaxWidth(),
  constraints = PreviewSlotConstraints(
    horizontal = PreviewSlotSizing.Fill,
    vertical = PreviewSlotSizing.Hug,
    padding = PreviewSlotPadding(startDp = 16f, endDp = 16f),
  ),
) {
  DefaultContent()
}
```

The ordinary render remains unchanged. Outside the native builder, the marker still emits its
`dp-slot:` semantics tag for `/render/<id>.slots`, so a Figma-derived manifest and the composable
marker can be normalized into the same slot model without making Figma annotation mandatory.
