# Surfacing the paired catalog

Captures from the `preview-harness` pages run over the committed fixtures, light theme.

| File | What it shows |
| --- | --- |
| `front-door-after.png` | The `remote-m3` card carrying both comparisons — `compare to Figma` and `compare to Wear Compose Material 3`. The sibling's title is its own and can be long, so the chip wraps inside the tile rather than running past the card's border. |
| `viewer-after.png` | The viewer toolbar's `Wear M3 layers →` link: the cross-catalog layer diff, now emitted alongside `spec diff →` instead of losing a `when` to it, and named for the sibling so the resting page says the counterpart exists. |

The "before" for both is the live deployment as audited in
[#574](https://github.com/yschimke/compose-preview-server/pull/574)'s follow-up: `remote-m3`'s
card offered only `compare to Figma`, and its preview pages carried no occurrence of the string
`layer diff` at all.

Regenerate with:

```
npm --prefix serve-web run build
npm --prefix preview-harness run harness:pages
```

then copy `preview-harness/out/serve-home-index.light.png` and
`preview-harness/out/serve-viewer-rc-parallel.light.png` over the files above.
