# A committed design, generated and rendered by the CLI

Both files are `compose-preview-server design render --local` output: the committed design replayed
into a document, projected to Kotlin by the record-driven `ScreenDocumentProjection`, compiled by
the staged BTA compiler, and drawn by the CMP **desktop** daemon against `m3-catalog`'s own bundle.

| File | Design | Frame |
| --- | --- | --- |
| `menus-desktop-render.png` | `ui-builder-menus` — the selection menu, overflow menu and hover editor | 3200×1800 |
| `mobile-desktop-render.png` | `ui-builder-mobile` — the mobile workspace | 824×1830 |

Before the two projection widenings in this change, neither design exported at all: `ui-builder-menus`
was refused over three `colorToken` wrappers holding `#AARRGGBB` literals, and the designs that clip
or fill with a hand-sized corner were refused over a shape written as a number.

Reproduce (the sidecars are the fiddly part — see
[`UI_BUILDER_GETTING_STARTED.md`](../../UI_BUILDER_GETTING_STARTED.md#when-the-server-is-the-thing-that-is-broken)):

```shell
node scripts/ui-builder/design-sync.mjs publish docs/design/fixtures/ui-builder/designs --out /tmp/documents
curl -sLO https://raw.githubusercontent.com/yschimke/m3-catalog/design-artifacts/m3-catalog/bundle/bundle.png

JAVA_OPTS=-Dcomposeai.cli.skikoDir=/path/to/lib-skiko \
  compose-preview-server design render --local \
    --document /tmp/documents/ui-builder-menus.json \
    --catalog bundle.png \
    --components m3-catalog=docs/design/fixtures/ui-builder/m3-catalog-components-v1.json \
    -o menus-desktop-render.png
```
