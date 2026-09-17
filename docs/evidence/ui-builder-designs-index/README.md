# `/ui-builder/designs`: the index as a file manager

Captures from the `preview-harness` pages run over the committed
`fixtures/pages/serve-ui-builder-designs.html`. Both rows of the fixture are the shapes the page
has to hold: a design this account owns and shares, and one it was shared and that cannot be
opened at all.

| File | State |
| --- | --- |
| `before-designs.png` | `origin/main`: a column of headings, each over its id, its role, and a sharing form. No picture of any design, no way to create one, no way to remove one. |
| `after-designs.png` | The same rows as cards led by the design's own SVG export, with Open / Share / Duplicate / Delete on each, the two create forms above them, and a filter box. The unopenable design keeps the placeholder mark and its reason. |
| `after-designs-dark.png` | The same page in dark, since the well and the card ground are new surfaces. |

Regenerate with:

```
scripts/regenerate-goldens.sh
npm --prefix preview-harness run harness:pages
```

then copy `preview-harness/out/serve-ui-builder-designs.{light,dark}.png` over the `after-` files
above. The thumbnails are the harness's own stand-in for
`/api/ui-builder/v1/designs/<id>/export.svg`, which has no backend in a page capture.
