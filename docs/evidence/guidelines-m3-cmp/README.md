# m3-catalog guideline frames, drawn on its CMP bundle

Evidence for [#1867](https://github.com/yschimke/compose-preview-server/issues/1867).

Both PNGs are the pictures `GET /api/ui-builder/v1/designs/<id>/guidelines/prompt?rendered=true`
attached for an `m3-catalog` design created from the catalog's `hello` template (dark theme), on a
local build serving `m3-catalog@yschimke/m3-catalog-out` with `--compile-engine
--playground-bundle m3-catalog --allow-render-trusted`.

| File | Frame | Pixels |
| --- | --- | --- |
| `phone-412x915dp.png` | phone, 412×915dp | 824×1830 |
| `tablet-1280x800dp.png` | tablet, 1280×800dp | 2560×1600 |

Before the change the same request answered `pictures: []` with no reason: the catalog's
`ui-builder.json` declares the `android` native backend, its served bundle is `desktop`, and the
native lane refused (`NO_NATIVE_CATALOG`) without the prompt saying so. The catalog is `mobile`, so
the lane now compiles it on the desktop bundle through Compose Multiplatform.
