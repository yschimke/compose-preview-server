# The samples page role

A component page under the two page roles a catalog can declare for itself
(`catalog.json`'s `display.role` → `ServeWeb.PageRole`).

| | |
| --- | --- |
| `page-catalog.png` | `CATALOG` — the ordinary page: the design comparison on the control row (`Figma 42.4%`, `spec diff →`), the code behind the `Source` chip |
| `page-samples.png` | `SAMPLES` — the comparison lanes gone, the source standing beside the render |
| `before-samples.png` | the same `SAMPLES` page before the code moved out of the render's card, kept as the one comparison a picture of a layout actually needs |

Both are `ServeWeb.viewerPage` output over the production stylesheet, from two goldens
`ServeWebFixtureTest` builds out of **one** set of inputs — same preview, same usage source, same
design reference — differing only in `pageRole`. So the difference in these pictures is the role and
nothing else.

```
node shoot.mjs samples
node shoot.mjs catalog
```

`before-samples.png` is shot from the same golden and the same harness as `page-samples.png`, with
only the stylesheet reverted, so the pair differs by the CSS and nothing else — not by a fixture
that moved under it. Both were re-shot together, which is why `page-catalog.png` changed in the same
commit despite the CSS being scoped to the samples lane: its committed copy predated the component
drawer. Re-shot with the change stashed and again with it applied, the catalog page is byte-identical
(`24f11780472de0f3e239e3ac34b6c3f5` both times), which is the evidence that this lane's rules reach
only this lane.

`/usage/<id>` is stubbed: the panel is filled by a fetch the harness has no server for, and an empty
panel would show the layout without the thing it is a layout for. The snippet is a plausible sample
rather than a real one.
