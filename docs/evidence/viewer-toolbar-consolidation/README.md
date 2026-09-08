# The viewer toolbar: one way out, and the stage's presentation in the panel

Captures from the `preview-harness` pages run over the committed
`fixtures/pages/serve-viewer-rc-parallel.html`, light theme — the paired Remote Compose preview,
which is the shape that carries every control this change touches at once.

| File | What it shows |
| --- | --- |
| `before-bar.png` | `origin/main`. The bar needs **two rows**: `compare players →` and `Wear M3 layers →` either side of the spec lane on the first, then `VIEW  Transparent  Fit width` on the second. |
| `after-bar.png` | **One row.** The renderer control, the `COMPARE` chips, and `Full comparisons ▾` where two grey links used to be — the `View` group collapses away entirely on a preview with no SVG or 3D lane, which is most of the catalog. |
| `after-menu-open.png` | The menu open: `Wear M3 layers`, `Compare players` (this fixture publishes no local design reference, so it has no `Spec diff` row — a `remote-m3` preview that does gets three). |
| `after-panel-view-group.png` | `Transparent` and `Fit width` in the Overrides panel's own `View` group, first in the panel and open by default. |

The two destinations in the menu are the same URLs as before, and a preview with only one of them
keeps its inline link rather than gaining a menu with a single row.

Regenerate with:

```
npm --prefix serve-web run build
HARNESS_FIXTURE=serve-viewer-rc-parallel npm --prefix preview-harness run harness:pages
```

then copy `preview-harness/out/serve-viewer-rc-parallel*.light.png` over the `after-*` files above.
The `before-*` file comes from the same command in a worktree at the merge base.
