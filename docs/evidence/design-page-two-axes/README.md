# Design page: `Show` / `Diff against`

Captures from the `preview-harness` pages run over the committed
`fixtures/pages/serve-design-page.html`, light theme. The fixture's catalog declares a
`compareWith` sibling (`wear-m3`) that draws two of the three shapes on the sheet, so every
state below is the one a paired catalog actually serves.

| File | State |
| --- | --- |
| `before-controls.png` | `origin/main`: one lane of *Our renders* / *Design spec* / *Diff %*, two sentence-long filters beside it. |
| `after-controls.png` | The same page: `SHOW` and `DIFF AGAINST` over the same three sources, plus a labelled `MARKS` group. |
| `after-sibling-lane.png` | `SHOW wear-m3` — the sibling's renders in the design's slots. The triangle is dotted: the sibling does not draw that cell, so the slot fell back to the design's own drawing and says so. |
| `after-diff-sibling.png` | `SHOW compose-m3`, `DIFF AGAINST wear-m3` — our renders scored against the sibling's, the comparison the design file cannot make because both implement it. |

Regenerate with:

```
npm --prefix serve-web run build
HARNESS_FIXTURE=serve-design-page npm --prefix preview-harness run harness:pages
```

then copy `preview-harness/out/serve-design-page*.light.png` over the files above.
