# Surfacing the paired catalog

Captures from the `preview-harness` pages run over the committed fixtures, light theme. Before is
`origin/main` at the time of the change; after is this branch, same fixtures and same harness.

| File | What it shows |
| --- | --- |
| `front-door-before.png` | Each card carrying one long `compare to Figma` chip, and no card naming a paired catalog at all — `remote-m3` and its Wear sibling sit as neighbours with nothing saying they are a pair. |
| `front-door-after.png` | `Compare to` said once by the row, then the destinations as compact chips: `Figma` and `wear-m3` side by side on `remote-m3`. |
| `viewer-before.png` | The `serve-viewer-rc-parallel` toolbar with a bare `layer diff →`. |
| `viewer-after.png` | The same link named for the sibling — `Wear M3 layers →` — which on a viewer that also has a spec diff is the only thing on the resting page saying a counterpart exists. |

The gap this closes was audited on the live deployment rather than inferred: `remote-m3`'s front-door
card offered only `compare to Figma`, and `/remote-m3/p/appcard__ideal__default__compact` carried no
occurrence of the string `layer diff` at all.

Regenerate with:

```
npm --prefix serve-web run build
HARNESS_FIXTURE=serve-home-index npm --prefix preview-harness run harness:pages
```

then copy `preview-harness/out/serve-home-index.light.png` and
`preview-harness/out/serve-viewer-rc-parallel.light.png` over the `*-after.png` files above.
